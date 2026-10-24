package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.enums.MessageDirection;
import com.localcommerce.delivery.model.WhatsAppMessageLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_messagelog_repo;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=test",
        "whatsapp.api.token=test",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=test",
        "fallback.manager.phone=test",
        "authorized.rider.numbers=test",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000",
        "spring.flyway.enabled=false"
})
class WhatsAppMessageLogRepositoryTest {

    @Autowired
    private WhatsAppMessageLogRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void testFindLatestMessagePerChat_OutOfOrderInsertion() {
        String phone = "919876543210";
        LocalDateTime baseTime = LocalDateTime.of(2026, 10, 17, 10, 0, 0);

        // Insert Msg 1 at 10:00:00 (id 1)
        WhatsAppMessageLog msg1 = repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("First message")
                .timestamp(baseTime)
                .build());

        // Insert Msg 2 at 12:00:00 (id 2) - LATEST TIMESTAMP
        WhatsAppMessageLog msg2 = repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Latest timestamp message")
                .timestamp(baseTime.plusHours(2))
                .build());

        // Insert Msg 3 at 11:00:00 (id 3) - HIGHER ID than msg2, but EARLIER TIMESTAMP
        WhatsAppMessageLog msg3 = repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("Inserted out-of-order message")
                .timestamp(baseTime.plusHours(1))
                .build());

        assertTrue(msg3.getId() > msg2.getId(), "Message 3 must have a higher ID than Message 2");

        List<WhatsAppMessageLog> latest = repository.findLatestMessagePerChat();

        assertEquals(1, latest.size(), "Should return exactly one chat summary for the phone number");
        assertEquals(msg2.getId(), latest.getFirst().getId(), 
                "Must select msg2 with the latest timestamp, NOT msg3 with highest ID");
        assertEquals("Latest timestamp message", latest.getFirst().getMessageBody());
    }

    @Test
    void testFindLatestMessagePerChat_EqualTimestampsDeterministicTieBreak() {
        String phone = "919111111111";
        LocalDateTime exactSameTime = LocalDateTime.of(2026, 10, 17, 14, 30, 0);

        WhatsAppMessageLog msgA = repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("Message A at 14:30")
                .timestamp(exactSameTime)
                .build());

        WhatsAppMessageLog msgB = repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Message B at 14:30 with higher ID")
                .timestamp(exactSameTime)
                .build());

        assertTrue(msgB.getId() > msgA.getId());

        List<WhatsAppMessageLog> latest = repository.findLatestMessagePerChat();

        assertEquals(1, latest.size());
        assertEquals(msgB.getId(), latest.getFirst().getId(), 
                "Equal timestamps must deterministically tie-break by higher ID");
        assertEquals("Message B at 14:30 with higher ID", latest.getFirst().getMessageBody());
    }

    @Test
    void testFindLatestMessagePerChat_MultipleCustomersSortedByLatestActivity() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 15, 0, 0);

        // Customer A: last activity at 15:00
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber("919999999991")
                .direction(MessageDirection.INBOUND)
                .messageBody("Customer A older")
                .timestamp(now.minusHours(1))
                .build());
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber("919999999991")
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Customer A latest")
                .timestamp(now)
                .build());

        // Customer B: last activity at 15:30 (more recent)
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber("919999999992")
                .direction(MessageDirection.INBOUND)
                .messageBody("Customer B latest")
                .timestamp(now.plusMinutes(30))
                .build());

        List<WhatsAppMessageLog> chats = repository.findLatestMessagePerChat();

        assertEquals(2, chats.size(), "Should have exactly 2 chats");
        assertEquals("919999999992", chats.get(0).getPhoneNumber(), "Most recent active chat must be first");
        assertEquals("Customer B latest", chats.get(0).getMessageBody());
        assertEquals("919999999991", chats.get(1).getPhoneNumber());
        assertEquals("Customer A latest", chats.get(1).getMessageBody());
    }

    @Test
    void testFindByPhoneNumberChronological_StrictlyOrderedAscending() {
        String phone = "919876543210";
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 17, 10, 0, 0);

        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("Step 1")
                .timestamp(t0)
                .build());

        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Step 3")
                .timestamp(t0.plusMinutes(2))
                .build());

        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("Step 2")
                .timestamp(t0.plusMinutes(1))
                .build());

        List<WhatsAppMessageLog> thread = repository.findByPhoneNumberChronological(phone);

        assertEquals(3, thread.size());
        assertEquals("Step 1", thread.get(0).getMessageBody());
        assertEquals("Step 2", thread.get(1).getMessageBody());
        assertEquals("Step 3", thread.get(2).getMessageBody());
    }

    @Test
    void testExistsByExternalMessageId() {
        String extId = "wamid.HBgLMTA0Mg==";

        assertFalse(repository.existsByExternalMessageId(extId));

        repository.save(WhatsAppMessageLog.builder()
                .externalMessageId(extId)
                .phoneNumber("919876543210")
                .direction(MessageDirection.INBOUND)
                .messageBody("Hello with Meta ID")
                .timestamp(LocalDateTime.now())
                .build());

        assertTrue(repository.existsByExternalMessageId(extId));
        assertFalse(repository.existsByExternalMessageId("non_existent_id"));
    }

    @Test
    void testFindLatestProfileNames_SelectsLatestProfileNameEvenWhenLatestMessageIsOutbound() {
        String phone1 = "919876543210";
        String phone2 = "919111111111";
        LocalDateTime base = LocalDateTime.of(2026, 10, 20, 10, 0, 0);

        // 1. Phone 1 - Inbound with old profile name
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone1)
                .direction(MessageDirection.INBOUND)
                .messageBody("Hello")
                .profileName("Anu Old")
                .timestamp(base)
                .build());

        // 2. Phone 1 - Inbound with updated profile name
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone1)
                .direction(MessageDirection.INBOUND)
                .messageBody("Second hello")
                .profileName("Anu Joseph")
                .timestamp(base.plusHours(1))
                .build());

        // 3. Phone 1 - Outbound bot reply (profileName is null)
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone1)
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Bot reply")
                .profileName(null)
                .timestamp(base.plusHours(2))
                .build());

        // 4. Phone 2 - Single inbound with profile name
        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone2)
                .direction(MessageDirection.INBOUND)
                .messageBody("Bob message")
                .profileName("Bob Dylan")
                .timestamp(base.plusMinutes(30))
                .build());

        var results = repository.findLatestProfileNames(List.of(phone1, phone2, "919999999999"));

        assertEquals(2, results.size(), "Should return exactly 2 projection entries");

        var map = results.stream().collect(
                java.util.stream.Collectors.toMap(
                        com.localcommerce.delivery.dto.ChatProfileNameProjection::phoneNumber,
                        com.localcommerce.delivery.dto.ChatProfileNameProjection::profileName
                )
        );

        assertEquals("Anu Joseph", map.get(phone1), "Must select latest non-null profile name (Anu Joseph), not Anu Old or outbound null");
        assertEquals("Bob Dylan", map.get(phone2));
        assertNull(map.get("919999999999"));
    }

    @Test
    void testFindLatestProfileNames_IgnoresBlankOrWhitespaceNames() {
        String phone = "919222222222";
        LocalDateTime base = LocalDateTime.of(2026, 10, 20, 10, 0, 0);

        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("First message")
                .profileName("Valid Name")
                .timestamp(base)
                .build());

        repository.save(WhatsAppMessageLog.builder()
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("Second message")
                .profileName("   ")
                .timestamp(base.plusHours(1))
                .build());

        var results = repository.findLatestProfileNames(List.of(phone));
        assertEquals(1, results.size());
        assertEquals("Valid Name", results.getFirst().profileName());
    }
}
