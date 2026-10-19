package com.localcommerce.delivery.service;

import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.model.Inventory;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.repository.InventoryRepository;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_concurrency;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=test",
        "whatsapp.api.token=test",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=test",
        "fallback.manager.phone=test",
        "authorized.rider.numbers=test",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000",
        "spring.flyway.enabled=false"
})
class OrderServiceConcurrencyIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private CustomerOrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long inventoryId;
    private Long orderIdA;
    private Long orderIdB;

    @BeforeEach
    void setUp() {
        Inventory inv = new Inventory();
        inv.setBaseSku("CHILLIPWD");
        inv.setStockQuantity(new BigDecimal("1.000")); // 1 KG total in stock
        inv.setUnit(ProductUnit.KG);
        inv = inventoryRepository.save(inv);
        inventoryId = inv.getId();
        
        Product product1 = new Product();
        product1.setSku("CHILLIPWD_1KG");
        product1.setName("Chilli Powder 1KG");
        product1.setPackageQuantity(new BigDecimal("1.0"));
        product1.setPackageUnit(ProductUnit.KG);
        product1.setInventory(inv);
        product1.setActive(true);
        product1 = productRepository.save(product1);

        Product product2 = new Product();
        product2.setSku("CHILLIPWD_250G");
        product2.setName("Chilli Powder 250G");
        product2.setPackageQuantity(new BigDecimal("250"));
        product2.setPackageUnit(ProductUnit.G);
        product2.setInventory(inv);
        product2.setActive(true);
        product2 = productRepository.save(product2);

        Customer customer = new Customer();
        customer.setWhatsappNumber("+1234567890");
        customer = customerRepository.save(customer);

        // Setup Order A requesting 1 unit of CHILLIPWD_1KG -> Requires 1 KG
        CustomerOrder orderA = new CustomerOrder();
        orderA.setCustomer(customer);
        orderA.setStatus(OrderStatus.CREATED);
        OrderItem itemA = new OrderItem();
        itemA.setProduct(product1);
        itemA.setQuantity(1);
        itemA.setCustomerOrder(orderA);
        orderA.setItems(new ArrayList<>(List.of(itemA)));
        orderA = orderRepository.save(orderA);
        orderIdA = orderA.getId();

        // Setup Order B requesting 1 unit of CHILLIPWD_250G -> Requires 0.25 KG
        CustomerOrder orderB = new CustomerOrder();
        orderB.setCustomer(customer);
        orderB.setStatus(OrderStatus.CREATED);
        OrderItem itemB = new OrderItem();
        itemB.setProduct(product2);
        itemB.setQuantity(1);
        itemB.setCustomerOrder(orderB);
        orderB.setItems(new ArrayList<>(List.of(itemB)));
        orderB = orderRepository.save(orderB);
        orderIdB = orderB.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM order_items");
        orderRepository.deleteAll();
        productRepository.deleteAll();
        inventoryRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void testConcurrentCheckout_OnlyOneSucceeds() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        Callable<Void> checkoutTaskA = () -> {
            try {
                orderService.finalizeOrderCheckout(orderIdA);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        };

        Callable<Void> checkoutTaskB = () -> {
            try {
                orderService.finalizeOrderCheckout(orderIdB);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        };

        // Submit both simultaneously
        executorService.invokeAll(List.of(checkoutTaskA, checkoutTaskB));
        executorService.shutdown();

        // EXACTLY ONE order must be finalized in the DB, because if A succeeds first, 
        // 1 KG is deducted, leaving 0. B needs 0.25 KG so it will fail.
        // If B succeeds first, 0.25 KG is deducted, leaving 0.75 KG. A needs 1 KG so it will fail.
        CustomerOrder finalOrderA = orderRepository.findById(orderIdA).orElseThrow();
        CustomerOrder finalOrderB = orderRepository.findById(orderIdB).orElseThrow();

        int successCount = 0;
        if (finalOrderA.getStatus() == OrderStatus.PENDING_WAREHOUSE) successCount++;
        if (finalOrderB.getStatus() == OrderStatus.PENDING_WAREHOUSE) successCount++;

        assertEquals(1, successCount, "Exactly one order should successfully commit to PENDING_WAREHOUSE despite any network failures");

        // The final stock in DB must be correctly deducted
        Inventory finalInventory = inventoryRepository.findById(inventoryId).orElseThrow();
        if (finalOrderA.getStatus() == OrderStatus.PENDING_WAREHOUSE) {
            assertTrue(finalInventory.getStockQuantity().compareTo(BigDecimal.ZERO) == 0, "Stock must be exactly 0 if A succeeded");
        } else {
            assertTrue(finalInventory.getStockQuantity().compareTo(new BigDecimal("0.750")) == 0, "Stock must be exactly 0.750 if B succeeded");
        }
    }

    @Test
    void testConcurrentCancellationAndDelivery_NoDeadlock() throws InterruptedException {
        // Setup order in DISPATCHED status with deducted inventory snapshot
        Customer customer = customerRepository.findAll().get(0);
        Product product1 = productRepository.findBySku("CHILLIPWD_1KG").orElseThrow();
        Inventory inv = inventoryRepository.findById(inventoryId).orElseThrow();
        inv.setStockQuantity(new BigDecimal("5.000"));
        inventoryRepository.save(inv);

        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.DISPATCHED);

        OrderItem item = new OrderItem();
        item.setProduct(product1);
        item.setQuantity(1);
        item.setDeductedQuantity(new BigDecimal("1.000")); // 1 KG deducted
        item.setCustomerOrder(order);
        order.setItems(new ArrayList<>(List.of(item)));
        order = orderRepository.save(order);
        Long orderId = order.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> cancelTask = () -> {
            try {
                orderService.cancelOrder(orderId, "Customer Unreachable", com.localcommerce.delivery.enums.CancellationActor.RIDER);
            } catch (Exception ignored) {
            }
            return null;
        };

        Callable<Void> deliverTask = () -> {
            try {
                orderService.completeDelivery(orderId);
            } catch (Exception ignored) {
            }
            return null;
        };

        // Run both concurrently
        executor.invokeAll(List.of(cancelTask, deliverTask));
        executor.shutdown();

        CustomerOrder finalOrder = orderRepository.findById(orderId).orElseThrow();
        assertTrue(
                finalOrder.getStatus() == OrderStatus.CANCELLED || finalOrder.getStatus() == OrderStatus.DELIVERED,
                "Order must reach exactly one terminal state without deadlock"
        );

        Inventory finalInv = inventoryRepository.findById(inventoryId).orElseThrow();
        if (finalOrder.getStatus() == OrderStatus.CANCELLED) {
            // Stock restored exactly once: 5.000 + 1.000 = 6.000
            assertEquals(0, new BigDecimal("6.000").compareTo(finalInv.getStockQuantity()));
        } else {
            // Delivered: stock untouched = 5.000
            assertEquals(0, new BigDecimal("5.000").compareTo(finalInv.getStockQuantity()));
        }
    }

    @Test
    void testLocationAssignment_NeverOverwritesExistingLocation() {
        Customer customer = customerRepository.findAll().get(0);
        customer.setWhatsappNumber("919876543210");
        customerRepository.save(customer);

        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        order.setDeliveryLatitude(10.015000);
        order.setDeliveryLongitude(76.224000);
        order = orderRepository.save(order);
        Long orderId = order.getId();

        // Attempt to assign a new location to an order that already has coordinates
        orderService.assignLocationToOrder(orderId, "919876543210", 10.020000, 76.230000);

        CustomerOrder updatedOrder = orderRepository.findById(orderId).orElseThrow();
        // Existing coordinates must be preserved
        assertEquals(10.015000, updatedOrder.getDeliveryLatitude(), 0.00001);
        assertEquals(76.224000, updatedOrder.getDeliveryLongitude(), 0.00001);
    }
}
