package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
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
    private OrderItem item;

    @BeforeEach
    void setUp() {
        // Mock the TransactionManager to allow TransactionTemplate to execute synchronously
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        product = new Product();
        product.setId(1L);
        product.setSku("SKU_123");
        product.setName("Test Product");
        product.setStockQuantity(10); // initial stock 10
        product.setActive(true); // Default to active

        item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2); // requesting 2

        com.localcommerce.delivery.model.Customer customer = new com.localcommerce.delivery.model.Customer();
        customer.setId(10L);
        customer.setWhatsappNumber("+1234567890");

        order = new CustomerOrder();
        order.setId(100L);
        order.setStatus(OrderStatus.CREATED);
        order.setCustomer(customer);
        order.setItems(new ArrayList<>());
        order.getItems().add(item);
    }

    @Test
    void testFinalizeOrderCheckout_SufficientStock() {
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));

        orderService.finalizeOrderCheckout(100L);

        // Verify stock deducted
        assertEquals(8, product.getStockQuantity());
        verify(productRepo, times(1)).save(product);

        // Verify order status advanced
        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(orderRepo, times(1)).save(order);
        
        // Verify warehouse alert sent
        verify(warehouseAlertService, times(1)).sendNewOrderAlert(order);
    }

    @Test
    void testFinalizeOrderCheckout_InsufficientStock_ThrowsException() {
        // Product only has 1 in stock, but order wants 2
        product.setStockQuantity(1);

        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));

        com.localcommerce.delivery.exception.OrderValidationException exception = assertThrows(com.localcommerce.delivery.exception.OrderValidationException.class, () -> {
            orderService.finalizeOrderCheckout(100L);
        });

        assertTrue(exception.getMessage().contains("unavailable in the requested quantity"));
        
        // Verify NO stock was deducted (still 1)
        assertEquals(1, product.getStockQuantity());
        
        // Verify the product was NOT saved
        verify(productRepo, never()).save(any());
        
        // Verify the order was NOT finalized
        assertEquals(OrderStatus.CREATED, order.getStatus());
        verify(orderRepo, never()).save(any());
        verify(warehouseAlertService, never()).sendNewOrderAlert(any());
    }

    @Test
    void testFinalizeOrderCheckout_InactiveProduct_ThrowsException() {
        product.setActive(false);

        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));

        com.localcommerce.delivery.exception.OrderValidationException exception = assertThrows(com.localcommerce.delivery.exception.OrderValidationException.class, () -> {
            orderService.finalizeOrderCheckout(100L);
        });

        assertTrue(exception.getMessage().contains("is currently out of stock"));
        verify(productRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testFinalizeOrderCheckout_MissingProduct_ThrowsException() {
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.empty());

        com.localcommerce.delivery.exception.OrderValidationException exception = assertThrows(com.localcommerce.delivery.exception.OrderValidationException.class, () -> {
            orderService.finalizeOrderCheckout(100L);
        });

        assertTrue(exception.getMessage().contains("no longer available"));
        verify(productRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_MissingSku() {
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_MISSING", 1);
        when(productRepo.findBySkuForUpdate("SKU_MISSING")).thenReturn(Optional.empty());

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("currently unavailable"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_InactiveProduct() {
        product.setActive(false);
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_123", 1);
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("out of stock"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_ZeroStockProduct() {
        product.setStockQuantity(0);
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_123", 1);
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("out of stock"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_QuantityExceedsStock() {
        product.setStockQuantity(5);
        com.localcommerce.delivery.dto.CartItemDTO cartItem = new com.localcommerce.delivery.dto.CartItemDTO("SKU_123", 6);
        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("unavailable in the requested quantity"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testProcessNewCart_MultipleItemsAtomicRejection() {
        Product product2 = new Product();
        product2.setId(2L);
        product2.setSku("SKU_456");
        product2.setActive(true);
        product2.setStockQuantity(0); // Failing product

        com.localcommerce.delivery.dto.CartItemDTO cartItem1 = new com.localcommerce.delivery.dto.CartItemDTO("SKU_123", 1);
        com.localcommerce.delivery.dto.CartItemDTO cartItem2 = new com.localcommerce.delivery.dto.CartItemDTO("SKU_456", 1);

        when(productRepo.findBySkuForUpdate("SKU_123")).thenReturn(Optional.of(product));
        when(productRepo.findBySkuForUpdate("SKU_456")).thenReturn(Optional.of(product2));

        com.localcommerce.delivery.dto.OrderProcessing.OrderProcessResult result = orderService.processNewCart("bsuid", "123", "Name", java.util.List.of(cartItem1, cartItem2));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("out of stock"));
        // Make sure no order was created even though SKU_123 was valid
        verify(orderRepo, never()).save(any());
    }

    @Test
    void testFinalizeOrderCheckout_Idempotency() {
        // Set order to a state beyond CREATED
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));

        orderService.finalizeOrderCheckout(100L);

        // Verify it returns early and does not process
        verify(productRepo, never()).findBySkuForUpdate(any());
        verify(productRepo, never()).save(any());
        verify(warehouseAlertService, never()).sendNewOrderAlert(any());
    }
    
    /**
     * Note on Concurrency Testing:
     * A true concurrency test for pessimistic locking requires real database transactions 
     * operating on multiple threads, which demands substantial infrastructure (Testcontainers 
     * or dedicated test DB) not currently present in this project's test suite. 
     * Instead, we assert the logical deterministic steps: finding by SKU for update, 
     * checking stock sufficiency, and rejecting if insufficient.
     */
}
