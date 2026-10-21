package com.localcommerce.delivery.client;

import com.localcommerce.delivery.service.SmsFallbackService;
import com.localcommerce.delivery.service.WhatsAppLoggingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_retry;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=https://graph.facebook.com/v19.0/12345/messages",
        "whatsapp.api.token=test_token",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=919999999999",
        "fallback.manager.phone=test",
        "authorized.rider.numbers=919999999999",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000",
        "whatsapp.retry.delay=50"
})
class WhatsAppRetryIntegrationTest {

    @Autowired
    private WhatsAppOutboundClient outboundClient;

    @Autowired
    private WhatsAppHttpSender httpSender;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private SmsFallbackService smsFallbackService;

    @MockitoBean
    private WhatsAppLoggingService loggingService;

    @BeforeEach
    void setUp() {
        reset(restTemplate);
        reset(smsFallbackService);
        reset(loggingService);
    }

    @Test
    void testSpringRetryProxy_IsAopProxy() {
        assertTrue(AopUtils.isAopProxy(httpSender),
                "WhatsAppHttpSender must be wrapped in a Spring AOP proxy to enable @Retryable advice");
    }

    @Test
    void testTransientHttpServerError_RetriesAndEventuallySucceeds() {
        // Attempt 1: 503 Service Unavailable (transient server error)
        // Attempt 2: 200 OK (success)
        when(restTemplate.postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE, "Meta 503"))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        assertDoesNotThrow(() -> outboundClient.sendTextUpdate("918888888888", "Hello customer"));

        // Must retry through the proxy: 2 calls to postForEntity
        verify(restTemplate, times(2))
                .postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class));

        // Must log outbound message upon eventual success
        verify(loggingService, times(1))
                .logOutbound(eq("918888888888"), eq("Hello customer"), any(LocalDateTime.class));

        // Emergency SMS should never be invoked on eventual success
        verify(smsFallbackService, never()).sendEmergencyAlert(any());
    }

    @Test
    void testTransientResourceAccessException_RetriesAndEventuallySucceeds() {
        // Attempt 1: Connection timeout (transient network I/O error)
        // Attempt 2: 200 OK (success)
        when(restTemplate.postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection timed out to graph.facebook.com"))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        assertDoesNotThrow(() -> outboundClient.sendTextUpdate("918888888888", "Order is on the way"));

        // Must retry through the proxy: 2 calls to postForEntity
        verify(restTemplate, times(2))
                .postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class));

        verify(loggingService, times(1))
                .logOutbound(eq("918888888888"), eq("Order is on the way"), any(LocalDateTime.class));

        verify(smsFallbackService, never()).sendEmergencyAlert(any());
    }

    @Test
    void testExhaustedRetries_CustomerMessage_InvokesRecoveryWithoutEmergencySms() {
        // All 3 attempts fail with transient network errors
        when(restTemplate.postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("Socket reset"));

        // Recovery method handles the failure cleanly without propagating unexpected exceptions
        assertDoesNotThrow(() -> outboundClient.sendTextUpdate("918888888888", "Customer alert"));

        // Exactly 3 attempts made (initial + 2 retries)
        verify(restTemplate, times(3))
                .postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class));

        // Must NOT log outbound success
        verify(loggingService, never()).logOutbound(any(), any(), any());

        // Must NOT trigger warehouse emergency SMS for customer notifications
        verify(smsFallbackService, never()).sendEmergencyAlert(any());
    }

    @Test
    void testExhaustedRetries_WarehouseMessage_InvokesRecoveryWithEmergencySms() {
        // All 3 attempts fail with 500 Internal Server Error
        when(restTemplate.postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Meta 500"));

        // Message to warehousePhoneNumber ("919999999999")
        assertDoesNotThrow(() -> outboundClient.sendTextUpdate("919999999999", "New order awaiting pack"));

        // Exactly 3 attempts made
        verify(restTemplate, times(3))
                .postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class));

        // Warehouse failure must trigger emergency SMS fallback
        verify(smsFallbackService, times(1))
                .sendEmergencyAlert(contains("URGENT: WhatsApp API is DOWN"));

        // Must NOT log outbound success
        verify(loggingService, never()).logOutbound(any(), any(), any());
    }

    @Test
    void testPermanentClientError_NotRetried_FailsImmediately() {
        // 400 Bad Request (permanent client error: malformed payload, invalid template, invalid phone)
        HttpClientErrorException badRequest = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"Invalid phone number\",\"code\":100}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        when(restTemplate.postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class)))
                .thenThrow(badRequest);

        // Permanent client error must fail immediately and not be retried
        assertThrows(HttpClientErrorException.class, () ->
                outboundClient.sendTextUpdate("918888888888", "Malformed request")
        );

        // Exactly 1 attempt made (NO retries)
        verify(restTemplate, times(1))
                .postForEntity(eq("https://graph.facebook.com/v19.0/12345/messages"), any(), eq(String.class));

        // Must NOT log outbound success or send SMS
        verify(loggingService, never()).logOutbound(any(), any(), any());
        verify(smsFallbackService, never()).sendEmergencyAlert(any());
    }
}
