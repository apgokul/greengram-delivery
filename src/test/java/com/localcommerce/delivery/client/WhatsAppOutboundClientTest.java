package com.localcommerce.delivery.client;

import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.service.SmsFallbackService;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppOutboundClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private SmsFallbackService smsFallbackService;

    @Mock
    private com.localcommerce.delivery.service.WhatsAppLoggingService loggingService;

    private WhatsAppOutboundClient outboundClient;

    @BeforeEach
    void setUp() {
        outboundClient = new WhatsAppOutboundClient(restTemplate, smsFallbackService, loggingService);
    }

    @Test
    void testFormatItemList_MatchesTargetFormat() {
        Product product = new Product();
        product.setName("Chilli Powder (500g)");
        product.setCurrentPrice(new BigDecimal("110.00"));

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2);
        item.setLockedPrice(new BigDecimal("110.00"));

        String formatted = outboundClient.formatItemList(List.of(item));

        assertEquals("2 x Chilli Powder (500g): ₹220.00", formatted);
    }

    @Test
    void testFormatItemList_MultipleItems() {
        Product p1 = new Product();
        p1.setName("Chilli Powder (500g)");
        p1.setCurrentPrice(new BigDecimal("110.00"));

        OrderItem item1 = new OrderItem();
        item1.setProduct(p1);
        item1.setQuantity(2);
        item1.setLockedPrice(new BigDecimal("110.00"));

        Product p2 = new Product();
        p2.setName("Tata Salt");
        p2.setCurrentPrice(new BigDecimal("28.00"));

        OrderItem item2 = new OrderItem();
        item2.setProduct(p2);
        item2.setQuantity(1);
        item2.setLockedPrice(new BigDecimal("28.00"));

        String formatted = outboundClient.formatItemList(List.of(item1, item2));

        String expected = "2 x Chilli Powder (500g): ₹220.00\n1 x Tata Salt: ₹28.00";
        assertEquals(expected, formatted);
    }

    @Test
    void testFormatItemList_WithPackageUnitAndQuantity() {
        Product product = new Product();
        product.setName("Chilli Powder");
        product.setPackageQuantity(new BigDecimal("500"));
        product.setPackageUnit(ProductUnit.G);

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2);
        item.setLockedPrice(new BigDecimal("110.00"));

        String formatted = outboundClient.formatItemList(List.of(item));

        assertEquals("2 x Chilli Powder – 500 g: ₹220.00", formatted);
    }

    @Test
    void testFormatItemList_NullLockedPrice_FallbacksToCurrentPrice() {
        Product product = new Product();
        product.setName("Turmeric");
        product.setCurrentPrice(new BigDecimal("50.00"));

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(3);
        item.setLockedPrice(null);

        String formatted = outboundClient.formatItemList(List.of(item));

        assertEquals("3 x Turmeric: ₹150.00", formatted);
    }

    @Test
    void testFormatItemList_NullOrEmptyList() {
        assertEquals("", outboundClient.formatItemList(null));
        assertEquals("", outboundClient.formatItemList(List.of()));
    }

    @Test
    void testBuildOrderSummaryBody_ReceiptFormat() {
        Product product = new Product();
        product.setName("Chilli Powder (500g)");

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2);
        item.setLockedPrice(new BigDecimal("110.00"));

        CustomerOrder order = new CustomerOrder();
        order.setId(101L);
        order.setItems(new ArrayList<>(List.of(item)));
        order.setTotalAmount(new BigDecimal("220.00"));

        String summary = outboundClient.buildOrderSummaryBody(order);

        assertTrue(summary.contains("🧾 *Order Invoice*"));
        assertTrue(summary.contains("*Items:*\n2 x Chilli Powder (500g): ₹220.00"));
        assertTrue(summary.contains("💰 *Grand Total: ₹220.00*"));
        assertTrue(summary.contains("Tap below to confirm your order!"));
    }

    @Test
    void testSendTextUpdate_LogsOutboundOnHttpSuccess() {
        org.springframework.test.util.ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        org.springframework.test.util.ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        org.mockito.Mockito.when(restTemplate.postForEntity(
                org.mockito.ArgumentMatchers.eq("http://meta-api"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)
        )).thenReturn(org.springframework.http.ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendTextUpdate("919876543210", "Hello customer!");

        org.mockito.Mockito.verify(loggingService).logOutbound(
                org.mockito.ArgumentMatchers.eq("919876543210"),
                org.mockito.ArgumentMatchers.eq("Hello customer!"),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void testSendTextUpdate_DoesNotLogWhenHttpFails() {
        org.springframework.test.util.ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        org.springframework.test.util.ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        org.mockito.Mockito.when(restTemplate.postForEntity(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)
        )).thenThrow(new org.springframework.web.client.RestClientException("Meta API is down"));

        try {
            outboundClient.sendTextUpdate("919876543210", "Hello customer!");
        } catch (Exception ignored) {
        }

        org.mockito.Mockito.verifyNoInteractions(loggingService);
    }

    @Test
    void testSendTextUpdate_LoggingPersistenceFailureDoesNotPropagate() {
        org.springframework.test.util.ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        org.springframework.test.util.ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        org.mockito.Mockito.when(restTemplate.postForEntity(
                org.mockito.ArgumentMatchers.eq("http://meta-api"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)
        )).thenReturn(org.springframework.http.ResponseEntity.ok("{\"success\":true}"));

        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated DB error"))
                .when(loggingService).logOutbound(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );

        // Crucial requirement: outbound logging failure must not throw or propagate out of sendTextUpdate
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                outboundClient.sendTextUpdate("919876543210", "Hello customer!")
        );

        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(1)).postForEntity(
                org.mockito.ArgumentMatchers.eq("http://meta-api"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(String.class)
        );
    }

    @Test
    void testSendTextUpdate_LogsOutboundWithIstTimestamp_IndependentOfHostJvmDefaultTimezone() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(
                eq("http://meta-api"),
                any(),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"success\":true}"));

        // Fixed instant: 2026-10-18 10:30:00 UTC -> 2026-10-18 16:00:00 IST
        Instant fixedInstant = Instant.parse("2026-10-18T10:30:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("Asia/Kolkata"));
        outboundClient.setClock(fixedClock);

        // Temporarily change host JVM default timezone to UTC or America/New_York
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));

            outboundClient.sendTextUpdate("919876543210", "Order out for delivery!");

            ArgumentCaptor<LocalDateTime> timestampCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(loggingService).logOutbound(
                    eq("919876543210"),
                    eq("Order out for delivery!"),
                    timestampCaptor.capture()
            );

            LocalDateTime loggedTime = timestampCaptor.getValue();
            assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0, 0), loggedTime,
                    "Outbound log timestamp must be 16:00:00 IST regardless of JVM default timezone");
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Test
    void testSendSavedLocationPrompt_WithCoordinates_SendsMapsUrlAndNewButtons() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendSavedLocationPrompt("919876543210", 100L, 10.012345, 76.212345);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("interactive", body.get("type"));

        Map<String, Object> interactive = (Map<String, Object>) body.get("interactive");
        Map<String, Object> bodyTextMap = (Map<String, Object>) interactive.get("body");
        String text = (String) bodyTextMap.get("text");

        assertTrue(text.contains("https://www.google.com/maps?q=10.012345,76.212345"));
        assertTrue(text.contains("Your delivery location"));

        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        Object[] buttons = (Object[]) action.get("buttons");
        assertEquals(2, buttons.length);

        Map<String, Object> b1 = (Map<String, Object>) buttons[0];
        Map<String, Object> reply1 = (Map<String, Object>) b1.get("reply");
        assertEquals(WhatsAppConstants.REUSE_LOCATION + "100", reply1.get("id"));
        assertEquals("Use Saved Location", reply1.get("title"));
        assertTrue(((String) reply1.get("title")).length() <= 20);

        Map<String, Object> b2 = (Map<String, Object>) buttons[1];
        Map<String, Object> reply2 = (Map<String, Object>) b2.get("reply");
        assertEquals(WhatsAppConstants.NEW_LOCATION + "100", reply2.get("id"));
        assertEquals("Share New Location", reply2.get("title"));
        assertTrue(((String) reply2.get("title")).length() <= 20);
    }

    @Test
    void testSendSavedLocationPrompt_NullCoordinates_FallsBackToStandardPrompt() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendSavedLocationPrompt("919876543210", 100L, null, null);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("text", body.get("type"));
        Map<String, Object> textMap = (Map<String, Object>) body.get("text");
        String text = (String) textMap.get("body");
        assertEquals(WhatsAppConstants.LOCATION_REQUEST_PROMPT, text);
    }

    @Test
    void testSendCombinedOrderConfirmationPrompt_WithSavedLocation_InteractiveButtons() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, true, 10.012345, 76.212345);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("interactive", body.get("type"));

        Map<String, Object> interactive = (Map<String, Object>) body.get("interactive");
        Map<String, Object> bodyTextMap = (Map<String, Object>) interactive.get("body");
        String text = (String) bodyTextMap.get("text");

        assertTrue(text.contains("Your order is confirmed!"));
        assertTrue(text.contains("https://www.google.com/maps?q=10.012345,76.212345"));
        assertTrue(text.contains("choose your saved location or share a new location"));

        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        Object[] buttons = (Object[]) action.get("buttons");
        assertEquals(2, buttons.length);
        Map<String, Object> reply1 = (Map<String, Object>) ((Map<String, Object>) buttons[0]).get("reply");
        assertEquals("Use Saved Location", reply1.get("title"));
        Map<String, Object> reply2 = (Map<String, Object>) ((Map<String, Object>) buttons[1]).get("reply");
        assertEquals("Share New Location", reply2.get("title"));
    }

    @Test
    void testSendCombinedOrderConfirmationPrompt_NoSavedLocation_TextPrompt() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, false, null, null);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("text", body.get("type"));
        Map<String, Object> textMap = (Map<String, Object>) body.get("text");
        String text = (String) textMap.get("body");

        assertTrue(text.contains("Your order is confirmed!"));
        assertTrue(text.contains("share your delivery location"));
    }

    @Test
    void testSendOrderConfirmedConcise_SendsConciseConfirmation() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendOrderConfirmedConcise("919876543210");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("text", body.get("type"));
        Map<String, Object> textMap = (Map<String, Object>) body.get("text");
        String text = (String) textMap.get("body");

        assertTrue(text.contains("Your order is confirmed!"));
        assertTrue(text.contains("preparing your order and will keep you updated."));
    }

    @Test
    void testSendEditOrderPrompt_SendsEditGuidance() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendEditOrderPrompt("919876543210");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("text", body.get("type"));
        Map<String, Object> textMap = (Map<String, Object>) body.get("text");
        String text = (String) textMap.get("body");

        assertTrue(text.contains("Let's update your order"));
        assertTrue(text.contains("open our catalogue, update your cart and submit your order again."));
    }
}
