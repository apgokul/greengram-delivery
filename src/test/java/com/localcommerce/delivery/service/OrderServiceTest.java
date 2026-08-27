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

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            orderService.finalizeOrderCheckout(100L);
        });

        assertTrue(exception.getMessage().contains("no longer available in the requested quantity"));
        
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
