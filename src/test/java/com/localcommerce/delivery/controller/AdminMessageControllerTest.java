package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.enums.MessageDirection;
import com.localcommerce.delivery.model.WhatsAppMessageLog;
import com.localcommerce.delivery.repository.WhatsAppMessageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdminMessageControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WhatsAppMessageLogRepository messageLogRepository;

    @Mock
    private com.localcommerce.delivery.repository.CustomerOrderRepository orderRepo;

    @InjectMocks
    private AdminMessageController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testGetActiveChats_ReturnsChatList() throws Exception {
        LocalDateTime time1 = LocalDateTime.of(2026, 10, 17, 14, 0, 0);
        LocalDateTime time2 = LocalDateTime.of(2026, 10, 17, 13, 0, 0);

        WhatsAppMessageLog log1 = WhatsAppMessageLog.builder()
                .id(10L)
                .phoneNumber("919876543210")
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Welcome to GreenGram!")
                .timestamp(time1)
                .build();

        WhatsAppMessageLog log2 = WhatsAppMessageLog.builder()
                .id(5L)
                .phoneNumber("919123456789")
                .direction(MessageDirection.INBOUND)
                .messageBody("Hi")
                .timestamp(time2)
                .build();

        when(messageLogRepository.findLatestMessagePerChat()).thenReturn(List.of(log1, log2));
        when(orderRepo.findByCustomerWhatsappNumberAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/messages/chats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].phoneNumber").value("919876543210"))
                .andExpect(jsonPath("$[0].lastMessage").value("Welcome to GreenGram!"))
                .andExpect(jsonPath("$[0].lastMessageDirection").value("OUTBOUND"))
                .andExpect(jsonPath("$[0].lastMessageTimestamp").value("2026-10-17T14:00:00"))
                .andExpect(jsonPath("$[1].phoneNumber").value("919123456789"))
                .andExpect(jsonPath("$[1].lastMessage").value("Hi"))
                .andExpect(jsonPath("$[1].lastMessageDirection").value("INBOUND"));
    }

    @Test
    void testGetActiveChats_EmptyList() throws Exception {
        when(messageLogRepository.findLatestMessagePerChat()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/messages/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void testGetChatHistory_ReturnsChronologicalMessages() throws Exception {
        String phone = "919876543210";
        LocalDateTime time1 = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
        LocalDateTime time2 = LocalDateTime.of(2026, 10, 17, 10, 1, 0);

        WhatsAppMessageLog msg1 = WhatsAppMessageLog.builder()
                .id(1L)
                .externalMessageId("wamid.1")
                .phoneNumber(phone)
                .direction(MessageDirection.INBOUND)
                .messageBody("Hello")
                .timestamp(time1)
                .build();

        WhatsAppMessageLog msg2 = WhatsAppMessageLog.builder()
                .id(2L)
                .phoneNumber(phone)
                .direction(MessageDirection.OUTBOUND)
                .messageBody("Welcome to GreenGram!")
                .timestamp(time2)
                .build();

        when(messageLogRepository.findByPhoneNumberChronological(phone)).thenReturn(List.of(msg1, msg2));

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", phone)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].externalMessageId").value("wamid.1"))
                .andExpect(jsonPath("$[0].direction").value("INBOUND"))
                .andExpect(jsonPath("$[0].messageBody").value("Hello"))
                .andExpect(jsonPath("$[0].timestamp").value("2026-10-17T10:00:00"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$[1].messageBody").value("Welcome to GreenGram!"));
    }

    @Test
    void testGetChatHistory_NormalPhoneNumber() throws Exception {
        String phone = "919876543210";
        when(messageLogRepository.findByPhoneNumberChronological(phone))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(messageLogRepository).findByPhoneNumberChronological(eq("919876543210"));
    }

    @Test
    void testGetChatHistory_LeadingPlusPhoneNumber() throws Exception {
        String input = "+919876543210";
        String expected = "919876543210";
        when(messageLogRepository.findByPhoneNumberChronological(expected))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", input))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(messageLogRepository).findByPhoneNumberChronological(eq(expected));
    }

    @Test
    void testGetChatHistory_SanitizesFormattedPhoneNumber() throws Exception {
        String formattedInput = "+91 98765-43210";
        String expectedSanitized = "919876543210";

        when(messageLogRepository.findByPhoneNumberChronological(expectedSanitized))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", formattedInput))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(messageLogRepository).findByPhoneNumberChronological(eq(expectedSanitized));
    }

    @Test
    void testGetChatHistory_PreservesAlphanumericBsuid() throws Exception {
        String bsuid = "bsuid.user_1042_abc";
        when(messageLogRepository.findByPhoneNumberChronological(bsuid))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", bsuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Critical requirement: Alphanumeric characters and dots in BSUID must NOT be stripped
        verify(messageLogRepository).findByPhoneNumberChronological(eq("bsuid.user_1042_abc"));
    }

    @Test
    void testGetChatHistory_InvalidInputSafelyRejected() throws Exception {
        String invalidInput = "invalid$#@!phone";

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", invalidInput))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Must NOT transform invalid input into a different valid identifier or query repo
        org.mockito.Mockito.verifyNoInteractions(messageLogRepository);
    }

    @Test
    void testGetChatHistory_EmptyOrWhitespaceInputSafelyRejected() throws Exception {
        String whitespaceInput = "   ";

        mockMvc.perform(get("/api/admin/messages/{phoneNumber}", whitespaceInput))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        org.mockito.Mockito.verifyNoInteractions(messageLogRepository);
    }

    @Test
    void testNormalizeCustomerIdentifier_DirectUnitValidations() {
        org.junit.jupiter.api.Assertions.assertEquals("919876543210", AdminMessageController.normalizeCustomerIdentifier("919876543210"));
        org.junit.jupiter.api.Assertions.assertEquals("919876543210", AdminMessageController.normalizeCustomerIdentifier("+919876543210"));
        org.junit.jupiter.api.Assertions.assertEquals("919876543210", AdminMessageController.normalizeCustomerIdentifier("+91 98765-43210"));
        org.junit.jupiter.api.Assertions.assertEquals("bsuid.user_1042_abc", AdminMessageController.normalizeCustomerIdentifier("bsuid.user_1042_abc"));
        org.junit.jupiter.api.Assertions.assertNull(AdminMessageController.normalizeCustomerIdentifier(""));
        org.junit.jupiter.api.Assertions.assertNull(AdminMessageController.normalizeCustomerIdentifier("   "));
        org.junit.jupiter.api.Assertions.assertNull(AdminMessageController.normalizeCustomerIdentifier(null));
        org.junit.jupiter.api.Assertions.assertNull(AdminMessageController.normalizeCustomerIdentifier("<script>alert(1)</script>"));
        org.junit.jupiter.api.Assertions.assertNull(AdminMessageController.normalizeCustomerIdentifier("invalid$#@!phone"));
    }
}
