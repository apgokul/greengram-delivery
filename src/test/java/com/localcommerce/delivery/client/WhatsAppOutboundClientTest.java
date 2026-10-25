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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

        assertEquals("2x Chilli Powder (500g): ₹220.00", formatted);
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

        String expected = "2x Chilli Powder (500g): ₹220.00\n1x Tata Salt: ₹28.00";
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

        assertEquals("2x Chilli Powder – 500 g: ₹220.00", formatted);
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

        assertEquals("3x Turmeric: ₹150.00", formatted);
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
        assertTrue(summary.contains("Order ID: #101"));
        assertTrue(summary.contains("*Items:*\n2x Chilli Powder (500g): ₹220.00"));
        assertTrue(summary.contains("💰 *Grand Total: ₹220.00*"));
        assertTrue(summary.contains("Tap below to confirm your order!"));
    }

    @Test
    void testSendOrderSummaryPrompt_IncludesPersistedOrderIdAndPreservesButtons() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        Product product = new Product();
        product.setName("Chilli Powder (500g)");

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2);
        item.setLockedPrice(new BigDecimal("110.00"));

        CustomerOrder order = new CustomerOrder();
        order.setId(12345L);
        order.setItems(new ArrayList<>(List.of(item)));
        order.setTotalAmount(new BigDecimal("220.00"));

        outboundClient.sendOrderSummaryPrompt("919876543210", order);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("interactive", body.get("type"));

        Map<String, Object> interactive = (Map<String, Object>) body.get("interactive");
        assertEquals("button", interactive.get("type"));

        Map<String, Object> bodyTextMap = (Map<String, Object>) interactive.get("body");
        String text = (String) bodyTextMap.get("text");

        assertTrue(text.contains("🧾 *Order Invoice*"));
        assertTrue(text.contains("Order ID: #12345"));
        assertTrue(text.contains("*Items:*\n2x Chilli Powder (500g): ₹220.00"));
        assertTrue(text.contains("💰 *Grand Total: ₹220.00*"));
        assertTrue(text.contains("Tap below to confirm your order!"));

        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        Object[] buttons = (Object[]) action.get("buttons");
        assertEquals(3, buttons.length);

        Map<String, Object> b1 = (Map<String, Object>) buttons[0];
        Map<String, Object> reply1 = (Map<String, Object>) b1.get("reply");
        assertEquals("CONFIRM_12345", reply1.get("id"));
        assertEquals("✅ Confirm Order", reply1.get("title"));

        Map<String, Object> b2 = (Map<String, Object>) buttons[1];
        Map<String, Object> reply2 = (Map<String, Object>) b2.get("reply");
        assertEquals("EDIT_ORDER_12345", reply2.get("id"));
        assertEquals("✏️ Edit Order", reply2.get("title"));

        Map<String, Object> b3 = (Map<String, Object>) buttons[2];
        Map<String, Object> reply3 = (Map<String, Object>) b3.get("reply");
        assertEquals("CANCEL_ORDER_12345", reply3.get("id"));
        assertEquals("❌ Cancel Order", reply3.get("title"));

        verify(loggingService).logOutbound(
                eq("919876543210"),
                org.mockito.ArgumentMatchers.contains("Order ID: #12345"),
                any()
        );
    }

    @Test
    void testSendOrderSummaryPrompt_MultipleOrders_CorrectOrderIdForRelevantOrder() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        Product product = new Product();
        product.setName("Rice");

        OrderItem item1 = new OrderItem();
        item1.setProduct(product);
        item1.setQuantity(1);
        item1.setLockedPrice(new BigDecimal("100.00"));

        CustomerOrder order1 = new CustomerOrder();
        order1.setId(1001L);
        order1.setItems(new ArrayList<>(List.of(item1)));
        order1.setTotalAmount(new BigDecimal("100.00"));

        OrderItem item2 = new OrderItem();
        item2.setProduct(product);
        item2.setQuantity(2);
        item2.setLockedPrice(new BigDecimal("100.00"));

        CustomerOrder order2 = new CustomerOrder();
        order2.setId(1002L);
        order2.setItems(new ArrayList<>(List.of(item2)));
        order2.setTotalAmount(new BigDecimal("200.00"));

        outboundClient.sendOrderSummaryPrompt("919876543210", order1);
        outboundClient.sendOrderSummaryPrompt("919876543211", order2);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(2)).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        List<HttpEntity<Map<String, Object>>> captured = entityCaptor.getAllValues();

        // Check order 1 payload
        Map<String, Object> body1 = captured.get(0).getBody();
        Map<String, Object> interactive1 = (Map<String, Object>) body1.get("interactive");
        String text1 = (String) ((Map<String, Object>) interactive1.get("body")).get("text");
        assertTrue(text1.contains("Order ID: #1001"));
        assertFalse(text1.contains("Order ID: #1002"));

        Object[] buttons1 = (Object[]) ((Map<String, Object>) interactive1.get("action")).get("buttons");
        assertEquals("CONFIRM_1001", ((Map<String, Object>) ((Map<String, Object>) buttons1[0]).get("reply")).get("id"));
        assertEquals("EDIT_ORDER_1001", ((Map<String, Object>) ((Map<String, Object>) buttons1[1]).get("reply")).get("id"));
        assertEquals("CANCEL_ORDER_1001", ((Map<String, Object>) ((Map<String, Object>) buttons1[2]).get("reply")).get("id"));

        // Check order 2 payload
        Map<String, Object> body2 = captured.get(1).getBody();
        Map<String, Object> interactive2 = (Map<String, Object>) body2.get("interactive");
        String text2 = (String) ((Map<String, Object>) interactive2.get("body")).get("text");
        assertTrue(text2.contains("Order ID: #1002"));
        assertFalse(text2.contains("Order ID: #1001"));

        Object[] buttons2 = (Object[]) ((Map<String, Object>) interactive2.get("action")).get("buttons");
        assertEquals("CONFIRM_1002", ((Map<String, Object>) ((Map<String, Object>) buttons2[0]).get("reply")).get("id"));
        assertEquals("EDIT_ORDER_1002", ((Map<String, Object>) ((Map<String, Object>) buttons2[1]).get("reply")).get("id"));
        assertEquals("CANCEL_ORDER_1002", ((Map<String, Object>) ((Map<String, Object>) buttons2[2]).get("reply")).get("id"));
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
        assertEquals(WhatsAppConstants.REUSE_LOCATION_YES_BUTTON, reply1.get("title"));
        assertTrue(((String) reply1.get("title")).codePointCount(0, ((String) reply1.get("title")).length()) <= 20);

        Map<String, Object> b2 = (Map<String, Object>) buttons[1];
        Map<String, Object> reply2 = (Map<String, Object>) b2.get("reply");
        assertEquals(WhatsAppConstants.NEW_LOCATION + "100", reply2.get("id"));
        assertEquals(WhatsAppConstants.REUSE_LOCATION_NEW_BUTTON, reply2.get("title"));
        assertTrue(((String) reply2.get("title")).codePointCount(0, ((String) reply2.get("title")).length()) <= 20);
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

        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, true, 10.012345, 76.212345, Instant.parse("2026-10-21T10:00:00Z"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("interactive", body.get("type"));

        Map<String, Object> interactive = (Map<String, Object>) body.get("interactive");
        Map<String, Object> bodyTextMap = (Map<String, Object>) interactive.get("body");
        String text = (String) bodyTextMap.get("text");

        assertTrue(text.contains("Your order is confirmed!"));
        assertTrue(text.contains("Thank you for choosing *GreenGram*"));
        assertTrue(text.contains("Your order will be delivered by"));
        assertTrue(text.contains("Where should we deliver?"));
        assertTrue(text.contains("https://www.google.com/maps?q=10.012345,76.212345"));
        assertTrue(text.contains("Your saved location:"));

        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        Object[] buttons = (Object[]) action.get("buttons");
        assertEquals(2, buttons.length);
        Map<String, Object> reply1 = (Map<String, Object>) ((Map<String, Object>) buttons[0]).get("reply");
        assertEquals(WhatsAppConstants.REUSE_LOCATION_YES_BUTTON, reply1.get("title"));
        Map<String, Object> reply2 = (Map<String, Object>) ((Map<String, Object>) buttons[1]).get("reply");
        assertEquals(WhatsAppConstants.REUSE_LOCATION_NEW_BUTTON, reply2.get("title"));
    }

    @Test
    void testSendCombinedOrderConfirmationPrompt_NoSavedLocation_TextPrompt() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, false, null, null, Instant.parse("2026-10-21T10:00:00Z"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("text", body.get("type"));
        Map<String, Object> textMap = (Map<String, Object>) body.get("text");
        String text = (String) textMap.get("body");

        assertTrue(text.contains("Your order is confirmed!"));
        assertTrue(text.contains("Thank you for choosing *GreenGram*"));
        assertTrue(text.contains("Your order will be delivered by"));
        assertTrue(text.contains("share your current location so we can deliver your order to the right place"));
    }

    @Test
    void testSendOrderConfirmedConcise_SendsConciseConfirmation() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendOrderConfirmedConcise("919876543210", Instant.parse("2026-10-21T10:00:00Z"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("text", body.get("type"));
        Map<String, Object> textMap = (Map<String, Object>) body.get("text");
        String text = (String) textMap.get("body");

        assertTrue(text.contains("Your order is confirmed!"));
        assertTrue(text.contains("Thank you for choosing *GreenGram*"));
        assertTrue(text.contains("Your order will be delivered by"));
    }

    @Test
    void testSendEditOrderPrompt_SendsInteractiveCatalogMessage() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        outboundClient.sendEditOrderPrompt("919876543210");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = entityCaptor.getValue().getBody();
        assertEquals("interactive", body.get("type"));
        Map<String, Object> interactive = (Map<String, Object>) body.get("interactive");
        assertEquals("catalog_message", interactive.get("type"));

        Map<String, Object> bodyMap = (Map<String, Object>) interactive.get("body");
        String text = (String) bodyMap.get("text");
        assertTrue(text.contains("Let's update your order 🛒"));
        assertTrue(text.contains("No problem! Update your cart, and send us your new order."));

        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        assertEquals("catalog_message", action.get("name"));
    }

    @Test
    void testDeliveryPromise_CutoffBoundaries() {
        // 18:29:00 IST (12:59:00 UTC) -> today evening
        assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TODAY,
                outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T12:59:00Z")));

        // 18:29:59 IST (12:59:59 UTC) -> today evening
        assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TODAY,
                outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T12:59:59Z")));

        // Exactly 18:30:00 IST (13:00:00 UTC) -> tomorrow evening
        assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TOMORROW,
                outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T13:00:00Z")));

        // 18:31:00 IST (13:01:00 UTC) -> tomorrow evening
        assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TOMORROW,
                outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T13:01:00Z")));

        // 11:00:00 IST (05:30:00 UTC) (morning) -> today evening
        assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TODAY,
                outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T05:30:00Z")));

        // 14:30:00 IST (09:00:00 UTC) (afternoon) -> today evening
        assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TODAY,
                outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T09:00:00Z")));
    }

    @Test
    void testDeliveryPromise_EachMessageVariant_BeforeCutoff_TodayEvening() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");
        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        // Fixed at 6:29:59 PM IST (12:59:59 UTC)
        Instant beforeCutoff = Instant.parse("2026-10-21T12:59:59Z");

        // Variant 1: Combined with saved location
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, true, 10.01, 76.21, beforeCutoff);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor1 = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), captor1.capture(), eq(String.class));
        Map<String, Object> interactive1 = (Map<String, Object>) captor1.getValue().getBody().get("interactive");
        String text1 = (String) ((Map<String, Object>) interactive1.get("body")).get("text");
        assertTrue(text1.contains("🛵 *Your order will be delivered by today evening.*"));

        // Variant 2: Combined without saved location
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, false, null, null, beforeCutoff);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor2 = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(2)).postForEntity(eq("http://meta-api"), captor2.capture(), eq(String.class));
        String text2 = (String) ((Map<String, Object>) captor2.getValue().getBody().get("text")).get("body");
        assertTrue(text2.contains("🛵 *Your order will be delivered by today evening.*"));

        // Variant 3: Concise confirmation
        outboundClient.sendOrderConfirmedConcise("919876543210", beforeCutoff);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor3 = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(3)).postForEntity(eq("http://meta-api"), captor3.capture(), eq(String.class));
        String text3 = (String) ((Map<String, Object>) captor3.getValue().getBody().get("text")).get("body");
        assertTrue(text3.contains("🛵 *Your order will be delivered by today evening.*"));
    }

    @Test
    void testDeliveryPromise_EachMessageVariant_AtAndAfterCutoff_TomorrowEvening() {
        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");
        when(restTemplate.postForEntity(eq("http://meta-api"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        // Exactly 6:30:00 PM IST (13:00:00 UTC)
        Instant atCutoff = Instant.parse("2026-10-21T13:00:00Z");

        // Variant 1: Combined with saved location
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, true, 10.01, 76.21, atCutoff);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor1 = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://meta-api"), captor1.capture(), eq(String.class));
        Map<String, Object> interactive1 = (Map<String, Object>) captor1.getValue().getBody().get("interactive");
        String text1 = (String) ((Map<String, Object>) interactive1.get("body")).get("text");
        assertTrue(text1.contains("🛵 *Your order will be delivered by tomorrow evening.*"));

        // Variant 2: Combined without saved location
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, false, null, null, atCutoff);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor2 = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(2)).postForEntity(eq("http://meta-api"), captor2.capture(), eq(String.class));
        String text2 = (String) ((Map<String, Object>) captor2.getValue().getBody().get("text")).get("body");
        assertTrue(text2.contains("🛵 *Your order will be delivered by tomorrow evening.*"));

        // Variant 3: Concise confirmation at 6:31 PM IST (13:01 UTC)
        Instant afterCutoff = Instant.parse("2026-10-21T13:01:00Z");
        outboundClient.sendOrderConfirmedConcise("919876543210", afterCutoff);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor3 = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(3)).postForEntity(eq("http://meta-api"), captor3.capture(), eq(String.class));
        String text3 = (String) ((Map<String, Object>) captor3.getValue().getBody().get("text")).get("body");
        assertTrue(text3.contains("🛵 *Your order will be delivered by tomorrow evening.*"));
    }

    @Test
    void testDeliveryPromise_IndependentOfServerDefaultTimeZone_Including1900IST() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            for (String tzId : List.of("America/New_York", "UTC", "Asia/Tokyo", "Europe/London")) {
                TimeZone.setDefault(TimeZone.getTimeZone(tzId));

                // 18:29:59 IST (12:59:59 UTC) -> today evening
                assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TODAY,
                        outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T12:59:59Z")));

                // Exactly 18:30:00 IST (13:00:00 UTC) -> tomorrow evening
                assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TOMORROW,
                        outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T13:00:00Z")));

                // Exactly 19:00:00 IST (13:30:00 UTC) -> tomorrow evening
                assertEquals(WhatsAppConstants.DELIVERY_PROMISE_TOMORROW,
                        outboundClient.resolveDeliveryPromise(Instant.parse("2026-10-21T13:30:00Z")));
            }
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Test
    void testDeliveryPromise_NullInputRejected() {
        // WhatsAppConstants.getDeliveryPromise(null) must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> WhatsAppConstants.getDeliveryPromise(null));

        // outboundClient.resolveDeliveryPromise(null) must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> outboundClient.resolveDeliveryPromise(null));

        ReflectionTestUtils.setField(outboundClient, "apiUrl", "http://meta-api");
        ReflectionTestUtils.setField(outboundClient, "accessToken", "test_token");

        // Null timestamp passed to sendCombinedOrderConfirmationPrompt must fail safely without calling Meta API
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, true, 10.01, 76.21, null);
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, false, null, null, null);
        outboundClient.sendCombinedOrderConfirmationPrompt("919876543210", 100L, false, null, null);

        // Null timestamp passed to sendOrderConfirmedConcise must fail safely without calling Meta API
        outboundClient.sendOrderConfirmedConcise("919876543210", null);
        outboundClient.sendOrderConfirmedConcise("919876543210");

        verify(restTemplate, never()).postForEntity(any(), any(), any());
    }
}
