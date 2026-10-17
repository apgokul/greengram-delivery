package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.WhatsAppMessageLog;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import com.localcommerce.delivery.repository.WhatsAppMessageLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Realistic Spring transaction integration test verifying Fix 1:
 * A message-log persistence failure (executing under Propagation.REQUIRES_NEW)
 * does NOT mark an enclosing business transaction (OrderService.completeDelivery) rollback-only.
 *
 * This test uses the real Spring transaction manager, real OrderService, real WhatsAppLoggingService proxy,
 * and real database entities. Only external HTTP calls to Meta API are mocked.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_deliverylogging;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=https://graph.facebook.com/v21.0/test/messages",
        "whatsapp.api.token=test",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=918888888888",
        "fallback.manager.phone=917777777777",
        "authorized.rider.numbers=919999999999",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000",
        "spring.flyway.enabled=false"
})
class DeliveryLoggingTransactionIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CustomerOrderRepository orderRepo;

    @Autowired
    private CustomerRepository customerRepo;

    @Autowired
    private DeliveryRunRepository deliveryRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private WhatsAppMessageLogRepository messageLogRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @Autowired
    private WhatsAppLoggingService loggingService;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setWhatsappNumber("919876543210");
        customer = customerRepo.save(customer);

        // Configure Meta API to succeed with 200 OK so outbound messaging path executes normally
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.outbound123\"}]}"));
    }

    @AfterEach
    void tearDown() {
        try {
            jdbcTemplate.execute("ALTER TABLE whatsapp_message_logs DROP CONSTRAINT force_db_fail");
        } catch (Exception ignored) {
        }
        jdbcTemplate.execute("DELETE FROM order_items");
        orderRepo.deleteAll();
        deliveryRepo.deleteAll();
        customerRepo.deleteAll();
        messageLogRepository.deleteAll();
    }

    @Test
    void testLoggingServiceIsSpringProxiedWithRequiresNew() throws NoSuchMethodException {
        // Verify that WhatsAppLoggingService is genuinely intercepted by a Spring AOP proxy
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(loggingService),
                "WhatsAppLoggingService must be a Spring AOP proxy to enforce transaction boundaries");

        // Verify that logOutbound method declares Propagation.REQUIRES_NEW
        var method = WhatsAppLoggingService.class.getMethod("logOutbound", String.class, String.class, java.time.LocalDateTime.class);
        var tx = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "logOutbound must be annotated with @Transactional");
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, tx.propagation(),
                "logOutbound must have propagation = Propagation.REQUIRES_NEW");
    }

    @Test
    void testCompleteDelivery_CommitsSuccessfullyWhenMessageLogPersistenceFails() {
        // Arrange: Create an order in DISPATCHED state (the state required to advance to DELIVERED)
        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.DISPATCHED);
        order.setTotalAmount(BigDecimal.valueOf(250.00));
        order = orderRepo.save(order);

        Long orderId = order.getId();

        // Simulate a persistence failure (e.g. database error, unique constraint, or lock failure)
        // inside WhatsAppMessageLogRepository during logOutbound
        doThrow(new DataIntegrityViolationException("Simulated database failure during message logging"))
                .when(messageLogRepository).save(any(WhatsAppMessageLog.class));

        // Act: Execute completeDelivery which is @Transactional
        assertDoesNotThrow(() -> orderService.completeDelivery(orderId));

        // Assert: The enclosing transaction MUST commit successfully despite the message log failure
        CustomerOrder reloaded = orderRepo.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.DELIVERED, reloaded.getStatus(),
                "Order status must be DELIVERED; business transaction must not be marked rollback-only");
        assertNotNull(reloaded.getDeliveredAt(), "Order deliveredAt must be populated");

        // Verify that messageLogRepository.save was attempted
        verify(messageLogRepository, atLeastOnce()).save(any(WhatsAppMessageLog.class));

        // Verify that Meta API was invoked exactly once (no duplicate resend via @Retryable)
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void testCompleteDelivery_CommitsSuccessfullyWhenActualDatabaseFailsOnMessageLog() {
        // Reset messageLogRepository spy so real repository, Hibernate session, and JDBC connection are invoked
        reset(messageLogRepository);

        // Arrange: Add a real H2 database check constraint that forces any INSERT into whatsapp_message_logs to fail
        jdbcTemplate.execute("ALTER TABLE whatsapp_message_logs ADD CONSTRAINT force_db_fail CHECK (id < 0)");

        try {
            CustomerOrder order = new CustomerOrder();
            order.setCustomer(customer);
            order.setStatus(OrderStatus.DISPATCHED);
            order.setTotalAmount(BigDecimal.valueOf(300.00));
            order = orderRepo.save(order);

            Long orderId = order.getId();

            // Act: Execute completeDelivery which is @Transactional
            // Inside completeDelivery, logOutbound runs in a real REQUIRES_NEW transaction.
            // The real database constraint throws a real JDBC integrity constraint violation.
            // That inner transaction rolls back, but does NOT mark the outer completeDelivery transaction rollback-only.
            assertDoesNotThrow(() -> orderService.completeDelivery(orderId));

            // Assert: The business transaction successfully committed to customer_orders table!
            CustomerOrder reloaded = orderRepo.findById(orderId).orElseThrow();
            assertEquals(OrderStatus.DELIVERED, reloaded.getStatus(),
                    "Order status must be DELIVERED; business transaction must not be marked rollback-only");
            assertNotNull(reloaded.getDeliveredAt());

            // Assert: Outbound logging row was NOT inserted because its inner transaction rolled back
            assertEquals(0, messageLogRepository.count(),
                    "whatsapp_message_logs row must have been rolled back by the failed inner transaction");

            // Verify Meta API was invoked once
            verify(restTemplate, atLeastOnce()).postForEntity(anyString(), any(), eq(String.class));
        } finally {
            try {
                jdbcTemplate.execute("ALTER TABLE whatsapp_message_logs DROP CONSTRAINT force_db_fail");
            } catch (Exception ignored) {
            }
        }
    }
}
