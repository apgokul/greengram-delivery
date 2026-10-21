package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.model.Inventory;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private CustomerRepository customerRepo;
    @Mock
    private ProductRepository productRepo;
    @Mock
    private InventoryRepository inventoryRepo;
    @Mock
    private CustomerOrderRepository orderRepo;
    @Mock
    private WhatsAppOutboundClient outboundClient;
    @Mock
    private WarehouseAlertService warehouseAlertService;
    @Mock
    private CustomerIdentityService customerIdentityService;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private OrderService orderService;

    private CustomerOrder order;
    private Product product;
    private Inventory inventory;
    private OrderItem item;
    private com.localcommerce.delivery.model.Customer customer;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        inventory = new Inventory();
        inventory.setId(1L);
        inventory.setBaseSku("SKU");
        inventory.setStockQuantity(new BigDecimal("10.000"));
        inventory.setUnit(ProductUnit.KG);

        product = new Product();
        product.setId(1L);
        product.setSku("SKU_1KG");
        product.setName("Test Product");
        product.setPackageQuantity(new BigDecimal("1.0"));
        product.setPackageUnit(ProductUnit.KG);
        product.setInventory(inventory);
        product.setActive(true);

        item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2); // requesting 2 units, which is 2 KG

        customer = new com.localcommerce.delivery.model.Customer();
        customer.setId(10L);
        customer.setWhatsappNumber("+1234567890");

        order = new CustomerOrder();
        order.setId(100L);
        order.setDisplayId("ORD-100");
        order.setStatus(OrderStatus.CREATED);
        order.setCustomer(customer);
        order.setItems(new ArrayList<>());
        order.getItems().add(item);

        org.springframework.test.util.ReflectionTestUtils.setField(orderService, "storeLat", 10.000);
        org.springframework.test.util.ReflectionTestUtils.setField(orderService, "storeLng", 76.200);
        org.springframework.test.util.ReflectionTestUtils.setField(orderService, "maxRadiusKm", 20.0);
    }

    @Test
    void testFinalizeOrderCheckout_SufficientStock_NoSavedLocation_SendsCombinedPrompt() {
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        orderService.finalizeOrderCheckout(100L);

        // Verify stock deducted
        assertEquals(0, new BigDecimal("8.000").compareTo(inventory.getStockQuantity()));
        verify(inventoryRepo, times(1)).save(inventory);

        // Verify deducted quantity snapshot recorded on OrderItem
        assertEquals(0, new BigDecimal("2.000").compareTo(item.getDeductedQuantity()));

        // Verify order status advanced
        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(orderRepo, times(1)).save(order);
        
        // Verify warehouse alert sent
        verify(warehouseAlertService, times(1)).sendNewOrderAlert(order);
        // Verify combined location prompt sent without saved coordinates
        verify(outboundClient, times(1)).sendCombinedOrderConfirmationPrompt(eq(customer.getRecipientId()), eq(100L), eq(false), isNull(), isNull(), any(Instant.class));
    }

    @Test
    void testFinalizeOrderCheckout_WithSavedLocation_SendsCombinedPromptWithCoordinates() {
        customer.setLatitude(10.010);
        customer.setLongitude(76.210);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        orderService.finalizeOrderCheckout(100L);

        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(outboundClient, times(1)).sendCombinedOrderConfirmationPrompt(eq(customer.getRecipientId()), eq(100L), eq(true), eq(10.010), eq(76.210), any(Instant.class));
        verify(outboundClient, never()).sendOrderConfirmedConcise(any(), any());
    }

    @Test
    void testFinalizeOrderCheckout_OrderAlreadyHasCoordinates_SendsConciseConfirmationOnly() {
        order.setDeliveryLatitude(10.015);
        order.setDeliveryLongitude(76.215);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        orderService.finalizeOrderCheckout(100L);

        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(outboundClient, times(1)).sendOrderConfirmedConcise(eq(customer.getRecipientId()), any(Instant.class));
        verify(outboundClient, never()).sendCombinedOrderConfirmationPrompt(any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void testFinalizeOrderCheckout_UsesCapturedCheckoutTimeEvenIfWarehouseAlertAdvancesClockAcrossCutoff() {
        // Order confirmed at 6:29:59 PM IST (12:59:59 UTC)
        Instant checkoutTime = Instant.parse("2026-10-21T12:59:59Z");
        Instant delayedTime = Instant.parse("2026-10-21T13:00:05Z"); // 6:30:05 PM IST (after cutoff)

        java.util.concurrent.atomic.AtomicReference<Instant> currentInstant = new java.util.concurrent.atomic.AtomicReference<>(checkoutTime);
        Clock controllableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return WhatsAppConstants.ASIA_KOLKATA_ZONE;
            }
            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
            @Override
            public Instant instant() {
                return currentInstant.get();
            }
        };
        orderService.setClock(controllableClock);

        // When sendNewOrderAlert is called, simulate warehouse alert network latency advancing clock past 18:30:00
        doAnswer(invocation -> {
            currentInstant.set(delayedTime);
            return null;
        }).when(warehouseAlertService).sendNewOrderAlert(any());

        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        orderService.finalizeOrderCheckout(100L);

        // Verify that outboundClient was called with the captured checkoutTime (12:59:59Z / 18:29:59 IST), NOT delayedTime
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboundClient, times(1)).sendCombinedOrderConfirmationPrompt(
                eq(customer.getRecipientId()), eq(100L), eq(false), isNull(), isNull(), instantCaptor.capture()
        );
        assertEquals(checkoutTime, instantCaptor.getValue());
    }

    @Test
    void testFinalizeOrderCheckout_RepeatedConfirmation_IdempotentNoOp() {
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.finalizeOrderCheckout(100L);

        // Does NOT deduct stock again
        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
        // Does NOT send duplicate alerts
        verify(warehouseAlertService, never()).sendNewOrderAlert(any());
        verify(outboundClient, never()).sendCombinedOrderConfirmationPrompt(any(), any(), anyBoolean(), any(), any(), any());
        verify(outboundClient, never()).sendOrderConfirmedConcise(any(), any());
    }

    @Test
    void testFinalizeOrderCheckout_InsufficientStock_ThrowsException() {
        inventory.setStockQuantity(new BigDecimal("1.000"));

        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        com.localcommerce.delivery.exception.OrderValidationException exception = assertThrows(com.localcommerce.delivery.exception.OrderValidationException.class, () -> {
            orderService.finalizeOrderCheckout(100L);
        });

        assertTrue(exception.getMessage().contains("unavailable in the requested quantity"));
        
        verify(inventoryRepo, never()).save(any());
        assertEquals(OrderStatus.CREATED, order.getStatus());
        verify(orderRepo, never()).save(any());
        verify(warehouseAlertService, never()).sendNewOrderAlert(any());
    }

    @Test
    void testFinalizeOrderCheckout_InactiveProduct_ThrowsException() {
        product.setActive(false);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        
        com.localcommerce.delivery.exception.OrderValidationException exception = assertThrows(com.localcommerce.delivery.exception.OrderValidationException.class, () -> {
            orderService.finalizeOrderCheckout(100L);
        });

        assertTrue(exception.getMessage().contains("is currently out of stock"));
        verify(inventoryRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_MissingSku() {
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_MISSING", 1);
        when(productRepo.findBySku("SKU_MISSING")).thenReturn(Optional.empty());

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("currently unavailable"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_InactiveProduct() {
        product.setActive(false);
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_1KG", 1);
        when(productRepo.findBySku("SKU_1KG")).thenReturn(Optional.of(product));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("out of stock"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_ZeroStockProduct() {
        inventory.setStockQuantity(BigDecimal.ZERO);
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_1KG", 1);
        when(productRepo.findBySku("SKU_1KG")).thenReturn(Optional.of(product));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("out of stock"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_QuantityExceedsStock() {
        inventory.setStockQuantity(new BigDecimal("0.500"));
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_1KG", 1); // wants 1KG
        when(productRepo.findBySku("SKU_1KG")).thenReturn(Optional.of(product));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("unavailable in the requested quantity"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testFinalizeOrderCheckout_Idempotency() {
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.finalizeOrderCheckout(100L);

        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
        verify(warehouseAlertService, never()).sendNewOrderAlert(any());
    }

    @Test
    void testCancelOrder_CustomerCancelsCreatedOrder_NoStockRestored() {
        order.setStatus(OrderStatus.CREATED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.cancelOrder(100L, "Customer Cancelled", com.localcommerce.delivery.enums.CancellationActor.CUSTOMER);

        assertTrue(res.isSuccess());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("Customer Cancelled", order.getCancellationReason());
        // Customer cancel on CREATED must NEVER restore stock
        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
        verify(outboundClient, times(1)).sendTextUpdate(eq(customer.getRecipientId()), eq(WhatsAppConstants.ORDER_CANCELLED_CUSTOMER));
    }

    @Test
    void testCancelOrder_CustomerCancelsPendingWarehouse_ReturnsNotAllowed() {
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.cancelOrder(100L, "Customer Cancelled", com.localcommerce.delivery.enums.CancellationActor.CUSTOMER);

        assertFalse(res.isSuccess());
        assertEquals(CancellationResult.Status.NOT_ALLOWED_IN_CURRENT_STATE, res.status());
        assertEquals("This order is already being prepared or out for delivery and can no longer be cancelled.", res.message());
        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    void testCancelOrder_WarehouseStockIssue_RestoresExactDeductedStock() {
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        item.setDeductedQuantity(new BigDecimal("2.000"));
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));

        CancellationResult res = orderService.cancelOrder(100L, "Stock Issue", com.localcommerce.delivery.enums.CancellationActor.WAREHOUSE);

        assertTrue(res.isSuccess());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(0, new BigDecimal("12.000").compareTo(inventory.getStockQuantity()));
        verify(inventoryRepo, times(1)).save(inventory);
        verify(outboundClient, times(1)).sendTextUpdate(eq(customer.getWhatsappNumber()), eq(WhatsAppConstants.STOCK_ISSUE_CANCELLATION));
    }

    @Test
    void testCancelOrder_RiderCancelsDispatchedOrder_RestoresStock() {
        order.setStatus(OrderStatus.DISPATCHED);
        item.setDeductedQuantity(new BigDecimal("2.000"));
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));

        CancellationResult res = orderService.cancelOrder(100L, "Customer Unreachable", com.localcommerce.delivery.enums.CancellationActor.RIDER);

        assertTrue(res.isSuccess());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(0, new BigDecimal("12.000").compareTo(inventory.getStockQuantity()));
        verify(inventoryRepo, times(1)).save(inventory);
    }

    @Test
    void testCancelOrder_DeliveredOrder_ReturnsAlreadyDelivered() {
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.cancelOrder(100L, "Rider Return", com.localcommerce.delivery.enums.CancellationActor.RIDER);

        assertFalse(res.isSuccess());
        assertEquals(CancellationResult.Status.ALREADY_DELIVERED, res.status());
        assertEquals("This order has already been delivered and cannot be cancelled.", res.message());
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    void testCancelOrder_AlreadyCancelled_IdempotentNoOp() {
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.cancelOrder(100L, "Duplicate tap", com.localcommerce.delivery.enums.CancellationActor.RIDER);

        assertFalse(res.isSuccess());
        assertEquals(CancellationResult.Status.ALREADY_CANCELLED, res.status());
        assertEquals("This order is already cancelled.", res.message());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    void testCancelOrder_LegacyItemMissingSnapshot_FlagsReconciliation() {
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        item.setDeductedQuantity(null); // Legacy item
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.cancelOrder(100L, "Cancel Legacy", com.localcommerce.delivery.enums.CancellationActor.ADMIN);

        assertTrue(res.isSuccess());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertFalse(order.isStockReconciled());
        assertTrue(item.isStockReconciliationRequired());
        assertTrue(order.getCancellationReason().contains("STOCK RECONCILIATION REQUIRED"));
        // Does NOT restore uncertain stock!
        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    void testEditOrder_CreatedOrder_Success() {
        order.setStatus(OrderStatus.CREATED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.editOrder(100L);

        assertTrue(res.isSuccess());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals("Customer Edited Order", order.getCancellationReason());
        // Must never touch inventory
        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
        // Must send dedicated edit order prompt
        verify(outboundClient, times(1)).sendEditOrderPrompt(customer.getRecipientId());
        // Must not send generic cancellation text
        verify(outboundClient, never()).sendTextUpdate(eq(customer.getRecipientId()), eq(WhatsAppConstants.ORDER_CANCELLED_CUSTOMER));
    }

    @Test
    void testEditOrder_PendingWarehouseOrder_FailsValidation() {
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        CancellationResult res = orderService.editOrder(100L);

        assertFalse(res.isSuccess());
        assertEquals(CancellationResult.Status.NOT_ALLOWED_IN_CURRENT_STATE, res.status());
        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(inventoryRepo, never()).save(any());
        verify(outboundClient, never()).sendEditOrderPrompt(any());
    }

    @Test
    void testConfirmSavedLocation_WithCoordinates_Success() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L);

        assertEquals(10.012, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.212, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(customer.getRecipientId(), WhatsAppConstants.LOCATION_CONFIRMED_NATURAL);
    }

    @Test
    void testAssignLocationToOrder_MatchingNumber_Success() {
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertEquals(10.010, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.210, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToOrder_MismatchedNumber_FailsAuthorization() {
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+9999999999", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+9999999999"), eq("Unauthorized order location update."));
    }

    @Test
    void testAssignLocationToOrder_NullStoredNumber_FailsAuthorization() {
        customer.setWhatsappNumber(null);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Unauthorized order location update."));
    }

    @Test
    void testAssignLocationToOrder_NullSenderNumber_FailsAuthorization() {
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, null, 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, never()).sendTextUpdate(any(), any());
    }

    @Test
    void testAssignLocationToAllOrders_NullStoredNumber_SkippedSafely() {
        customer.setWhatsappNumber(null);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToAllOrders(List.of(100L), "+1234567890", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
    }
}
