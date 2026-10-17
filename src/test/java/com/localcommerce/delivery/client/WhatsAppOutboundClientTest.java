package com.localcommerce.delivery.client;

import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.service.SmsFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("2 x Chilli Powder (500g) - ₹220.00", formatted);
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

        String expected = "2 x Chilli Powder (500g) - ₹220.00\n1 x Tata Salt - ₹28.00";
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

        assertEquals("2 x Chilli Powder — 500 g - ₹220.00", formatted);
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

        assertEquals("3 x Turmeric - ₹150.00", formatted);
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
        assertTrue(summary.contains("*Items:*\n2 x Chilli Powder (500g) - ₹220.00"));
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
}
