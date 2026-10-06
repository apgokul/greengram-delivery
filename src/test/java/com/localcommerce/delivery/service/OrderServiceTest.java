package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.enums.OrderStatus;
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
import java.util.ArrayList;
import java.util.Optional;

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
        when(inventoryRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inventory));

        orderService.finalizeOrderCheckout(100L);

        // Verify stock deducted
        assertEquals(0, new BigDecimal("8.000").compareTo(inventory.getStockQuantity()));
        verify(inventoryRepo, times(1)).save(inventory);

        // Verify order status advanced
        assertEquals(OrderStatus.PENDING_WAREHOUSE, order.getStatus());
        verify(orderRepo, times(1)).save(order);
        
        // Verify warehouse alert sent
        verify(warehouseAlertService, times(1)).sendNewOrderAlert(order);
    }

    @Test
    void testFinalizeOrderCheckout_InsufficientStock_ThrowsException() {
        inventory.setStockQuantity(new BigDecimal("1.000"));

        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
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
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
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
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));

        orderService.finalizeOrderCheckout(100L);

        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
        verify(warehouseAlertService, never()).sendNewOrderAlert(any());
    }
}
