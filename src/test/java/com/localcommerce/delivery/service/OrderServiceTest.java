package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.DeliveryRun;
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

        orderService.confirmSavedLocation(100L, "+1234567890");

        assertEquals(10.012, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.212, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(customer.getRecipientId(), WhatsAppConstants.LOCATION_CONFIRMED_NATURAL);
    }

    @Test
    void testConfirmSavedLocation_CancelledOrder_RejectedWithoutModifyingCoordinates() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L, "+1234567890");

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 has been cancelled and cannot be assigned a delivery location."));
    }

    @Test
    void testConfirmSavedLocation_DeliveredOrder_RejectedWithoutModifyingCoordinates() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L, "+1234567890");

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 has already been delivered."));
    }

    @Test
    void testConfirmSavedLocation_CompletedDeliveryRun_RejectedWithoutModifyingCoordinates() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.COMPLETED);
        order.setDeliveryRun(run);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L, "+1234567890");

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 delivery run has already been completed."));
    }

    @Test
    void testConfirmSavedLocation_AlreadyHasLocation_PreservesExistingCoordinates() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        order.setDeliveryLatitude(10.005);
        order.setDeliveryLongitude(76.205);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L, "+1234567890");

        assertEquals(10.005, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.205, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 already has a confirmed delivery location."));
    }

    @Test
    void testConfirmSavedLocation_UnauthorizedSender_RejectedWithoutModifyingCoordinates() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L, "+9999999999");

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+9999999999"), eq("Unauthorized order location update."));
    }

    @Test
    void testNoSingleArgConfirmSavedLocationOverloadExists() {
        assertThrows(NoSuchMethodException.class, () ->
                OrderService.class.getMethod("confirmSavedLocation", Long.class)
        );
    }

    @Test
    void testAssignLocationToOrder_CancelledOrder_RejectedWithoutModifyingCoordinates() {
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 has been cancelled and cannot be assigned a delivery location."));
    }

    @Test
    void testAssignLocationToOrder_DeliveredOrder_RejectedWithoutModifyingCoordinates() {
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 has already been delivered."));
    }

    @Test
    void testAssignLocationToOrder_CompletedDeliveryRun_RejectedWithoutModifyingCoordinates() {
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.COMPLETED);
        order.setDeliveryRun(run);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 delivery run has already been completed."));
    }

    @Test
    void testAssignLocationToOrder_AlreadyHasLocation_PreservesExistingCoordinates() {
        order.setDeliveryLatitude(10.005);
        order.setDeliveryLongitude(76.205);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertEquals(10.005, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.205, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Order #ORD-100 already has a confirmed delivery location."));
    }

    @Test
    void testAssignLocationToOrder_DispatchedOrder_AllowedWhenNoExistingLocation() {
        order.setStatus(OrderStatus.DISPATCHED);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, "+1234567890", 10.010, 76.210);

        assertEquals(10.010, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.210, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(order);
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
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
        verify(customerRepo, never()).save(any());
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
        verify(customerRepo, never()).save(any());
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("Unauthorized order location update."));
    }

    @Test
    void testAssignLocationToOrder_NullSenderNumber_FailsAuthorization() {
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToOrder(100L, null, 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(customerRepo, never()).save(any());
        verify(outboundClient, never()).sendTextUpdate(any(), any());
    }

    @Test
    void testAssignLocationToAllOrders_MixedBatch_SkipsIneligibleAndUpdatesEligible() {
        CustomerOrder eligibleOrder = new CustomerOrder();
        eligibleOrder.setId(101L);
        eligibleOrder.setDisplayId("ORD-101");
        eligibleOrder.setStatus(OrderStatus.CREATED);
        eligibleOrder.setCustomer(customer);

        CustomerOrder cancelledOrder = new CustomerOrder();
        cancelledOrder.setId(102L);
        cancelledOrder.setDisplayId("ORD-102");
        cancelledOrder.setStatus(OrderStatus.CANCELLED);
        cancelledOrder.setCustomer(customer);

        CustomerOrder alreadyAssignedOrder = new CustomerOrder();
        alreadyAssignedOrder.setId(103L);
        alreadyAssignedOrder.setDisplayId("ORD-103");
        alreadyAssignedOrder.setStatus(OrderStatus.READY);
        alreadyAssignedOrder.setCustomer(customer);
        alreadyAssignedOrder.setDeliveryLatitude(10.005);
        alreadyAssignedOrder.setDeliveryLongitude(76.205);

        when(orderRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(eligibleOrder));
        when(orderRepo.findByIdForUpdate(102L)).thenReturn(Optional.of(cancelledOrder));
        when(orderRepo.findByIdForUpdate(103L)).thenReturn(Optional.of(alreadyAssignedOrder));
        when(customerRepo.findByWhatsappNumber("+1234567890")).thenReturn(Optional.of(customer));

        orderService.assignLocationToAllOrders(List.of(103L, 101L, 102L), "+1234567890", 10.010, 76.210);

        // Eligible order is updated
        assertEquals(10.010, eligibleOrder.getDeliveryLatitude(), 0.0001);
        assertEquals(76.210, eligibleOrder.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(eligibleOrder);

        // Cancelled order is untouched
        assertNull(cancelledOrder.getDeliveryLatitude());
        verify(orderRepo, never()).save(cancelledOrder);

        // Already assigned order retains original coordinates
        assertEquals(10.005, alreadyAssignedOrder.getDeliveryLatitude(), 0.0001);
        verify(orderRepo, never()).save(alreadyAssignedOrder);

        // Customer profile updated
        assertEquals(10.010, customer.getLatitude(), 0.0001);
        verify(customerRepo, times(1)).save(customer);

        // Success message sent because updatedCount > 0
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToAllOrders_ZeroEligible_SendsTruthfulNonSuccessMessage() {
        CustomerOrder cancelledOrder = new CustomerOrder();
        cancelledOrder.setId(101L);
        cancelledOrder.setDisplayId("ORD-101");
        cancelledOrder.setStatus(OrderStatus.CANCELLED);
        cancelledOrder.setCustomer(customer);

        CustomerOrder deliveredOrder = new CustomerOrder();
        deliveredOrder.setId(102L);
        deliveredOrder.setDisplayId("ORD-102");
        deliveredOrder.setStatus(OrderStatus.DELIVERED);
        deliveredOrder.setCustomer(customer);

        when(orderRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(cancelledOrder));
        when(orderRepo.findByIdForUpdate(102L)).thenReturn(Optional.of(deliveredOrder));

        orderService.assignLocationToAllOrders(List.of(101L, 102L), "+1234567890", 10.010, 76.210);

        assertNull(cancelledOrder.getDeliveryLatitude());
        assertNull(deliveredOrder.getDeliveryLatitude());
        verify(orderRepo, never()).save(any());
        verify(customerRepo, never()).save(any());

        // Verifies accurate feedback when no orders were updated
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("None of the selected orders are awaiting a delivery location."));
        verify(outboundClient, never()).sendTextUpdate(eq("+1234567890"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToAllOrders_MissingOrderIds_DoesNotUpdateProfileAndSendsTruthfulMessage() {
        when(orderRepo.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        orderService.assignLocationToAllOrders(List.of(999L), "+1234567890", 10.010, 76.210);

        verify(orderRepo, never()).save(any());
        verify(customerRepo, never()).save(any());

        // Verifies truthful message for missing orders
        verify(outboundClient, times(1)).sendTextUpdate(eq("+1234567890"), eq("No selected orders were found awaiting a location update."));
        verify(outboundClient, never()).sendTextUpdate(eq("+1234567890"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToAllOrders_Unauthorized_DoesNotUpdateCustomerProfile() {
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.assignLocationToAllOrders(List.of(100L), "+9999999999", 10.010, 76.210);

        assertNull(order.getDeliveryLatitude());
        verify(orderRepo, never()).save(any());
        verify(customerRepo, never()).save(any());
        verify(outboundClient, times(1)).sendTextUpdate(eq("+9999999999"), eq("Unauthorized order location update."));
        verify(outboundClient, never()).sendTextUpdate(eq("+9999999999"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testConfirmSavedLocation_WithoutCoordinates_SendsPrompt() {
        customer.setLatitude(null);
        customer.setLongitude(null);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.confirmSavedLocation(100L, "+1234567890");

        assertNull(order.getDeliveryLatitude());
        assertNull(order.getDeliveryLongitude());
        verify(orderRepo, never()).save(order);
        verify(outboundClient, times(1)).sendDeliveryLocationPrompt(customer.getRecipientId());
        verify(outboundClient, never()).sendTextUpdate(any(), any());
    }

    @Test
    void testConfirmSavedLocation_OutboundFailure_DoesNotThrow() {
        customer.setLatitude(10.012);
        customer.setLongitude(76.212);
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        doThrow(new RuntimeException("WhatsApp API timeout"))
                .when(outboundClient).sendTextUpdate(any(), any());

        assertDoesNotThrow(() -> orderService.confirmSavedLocation(100L, "+1234567890"));

        assertEquals(10.012, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.212, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(order);
    }

    @Test
    void testUpdateCustomerLocation_OutOfBounds_SendsOutOfBoundMessage_NoDbChanges() {
        when(customerRepo.findByBsuid("bsuid_10")).thenReturn(Optional.of(customer));

        // Pin ~150km away
        orderService.updateCustomerLocation("bsuid_10", 11.500, 77.500);

        verify(customerRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
        verify(orderRepo, never()).findByIdForUpdate(any());
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboundClient, times(1)).sendTextUpdate(eq(customer.getRecipientId()), msgCaptor.capture());
        assertTrue(msgCaptor.getValue().contains("Out of Delivery Zone"));
        assertTrue(msgCaptor.getValue().contains("we only deliver within 20.0 km"));
    }

    @Test
    void testUpdateCustomerLocation_ZeroCandidateOrders_UpdatesCustomerProfile_SendsSavedMessage() {
        when(customerRepo.findByBsuid("bsuid_10")).thenReturn(Optional.of(customer));
        when(orderRepo.findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(eq(10L), anyList()))
                .thenReturn(List.of());

        orderService.updateCustomerLocation("bsuid_10", 10.010, 76.210);

        assertEquals(10.010, customer.getLatitude(), 0.0001);
        assertEquals(76.210, customer.getLongitude(), 0.0001);
        verify(customerRepo, times(1)).save(customer);
        verify(orderRepo, never()).save(any());
        verify(outboundClient, times(1)).sendTextUpdate(
                customer.getRecipientId(),
                "📍 Your location has been updated & saved for your future orders."
        );
    }

    @Test
    void testUpdateCustomerLocation_SingleCandidateOrder_UpdatesOrderAndCustomer_SendsConfirmedMessage() {
        when(customerRepo.findByBsuid("bsuid_10")).thenReturn(Optional.of(customer));
        when(orderRepo.findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(eq(10L), anyList()))
                .thenReturn(List.of(order));
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.updateCustomerLocation("bsuid_10", 10.010, 76.210);

        assertEquals(10.010, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.210, order.getDeliveryLongitude(), 0.0001);
        assertEquals(10.010, customer.getLatitude(), 0.0001);
        assertEquals(76.210, customer.getLongitude(), 0.0001);

        // Verify lock order: findByIdForUpdate called before customerRepo.save
        org.mockito.InOrder inOrder = inOrder(orderRepo, customerRepo);
        inOrder.verify(orderRepo).findByIdForUpdate(100L);
        inOrder.verify(orderRepo).save(order);
        inOrder.verify(customerRepo).save(customer);

        verify(outboundClient, times(1)).sendTextUpdate(
                customer.getRecipientId(),
                WhatsAppConstants.LOCATION_CONFIRMED_NATURAL
        );
    }

    @Test
    void testUpdateCustomerLocation_SingleCandidateOrder_StaleCandidateAlreadyHasCoordinates_DoesNotOverwrite() {
        // Initial candidate query returned order, but under lock, order already has coordinates!
        order.setDeliveryLatitude(10.050);
        order.setDeliveryLongitude(76.250);

        when(customerRepo.findByBsuid("bsuid_10")).thenReturn(Optional.of(customer));
        when(orderRepo.findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(eq(10L), anyList()))
                .thenReturn(List.of(order));
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        orderService.updateCustomerLocation("bsuid_10", 10.010, 76.210);

        // Order coordinates MUST NOT be overwritten
        assertEquals(10.050, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.250, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, never()).save(order);

        // Customer profile updated with new pin
        assertEquals(10.010, customer.getLatitude(), 0.0001);
        assertEquals(76.210, customer.getLongitude(), 0.0001);
        verify(customerRepo, times(1)).save(customer);

        verify(outboundClient, times(1)).sendTextUpdate(
                customer.getRecipientId(),
                "📍 Your location has been updated & saved for your future orders."
        );
    }

    @Test
    void testUpdateCustomerLocation_MultipleCandidateOrders_UpdatesCustomerProfile_SendsDisambiguationPrompt() {
        CustomerOrder order2 = new CustomerOrder();
        order2.setId(200L);
        order2.setDisplayId("ORD-200");
        order2.setTotalAmount(new BigDecimal("350.00"));
        order2.setStatus(OrderStatus.CREATED);
        order2.setCustomer(customer);

        order.setTotalAmount(new BigDecimal("150.00"));

        when(customerRepo.findByBsuid("bsuid_10")).thenReturn(Optional.of(customer));
        when(orderRepo.findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(eq(10L), anyList()))
                .thenReturn(List.of(order, order2));

        orderService.updateCustomerLocation("bsuid_10", 10.010, 76.210);

        // Customer profile updated
        assertEquals(10.010, customer.getLatitude(), 0.0001);
        assertEquals(76.210, customer.getLongitude(), 0.0001);
        verify(customerRepo, times(1)).save(customer);
        verify(orderRepo, never()).save(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CustomerOrder>> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboundClient, times(1)).sendLocationDisambiguationPrompt(
                eq(customer.getRecipientId()),
                listCaptor.capture(),
                eq(10.010),
                eq(76.210)
        );

        List<CustomerOrder> capturedOrders = listCaptor.getValue();
        assertEquals(2, capturedOrders.size());
        assertEquals(100L, capturedOrders.get(0).getId());
        assertEquals("ORD-100", capturedOrders.get(0).getDisplayId());
        assertEquals(new BigDecimal("150.00"), capturedOrders.get(0).getTotalAmount());
        assertEquals(200L, capturedOrders.get(1).getId());
        assertEquals("ORD-200", capturedOrders.get(1).getDisplayId());
        assertEquals(new BigDecimal("350.00"), capturedOrders.get(1).getTotalAmount());
    }

    @Test
    void testUpdateCustomerLocation_OutboundFailure_DoesNotThrowAndLocationSaved() {
        when(customerRepo.findByBsuid("bsuid_10")).thenReturn(Optional.of(customer));
        when(orderRepo.findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(eq(10L), anyList()))
                .thenReturn(List.of(order));
        when(orderRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        doThrow(new RuntimeException("WhatsApp timeout"))
                .when(outboundClient).sendTextUpdate(any(), any());

        assertDoesNotThrow(() -> orderService.updateCustomerLocation("bsuid_10", 10.010, 76.210));

        assertEquals(10.010, order.getDeliveryLatitude(), 0.0001);
        assertEquals(76.210, order.getDeliveryLongitude(), 0.0001);
        verify(orderRepo, times(1)).save(order);
        verify(customerRepo, times(1)).save(customer);
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
