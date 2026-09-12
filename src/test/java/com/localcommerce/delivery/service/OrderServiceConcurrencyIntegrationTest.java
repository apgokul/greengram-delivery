package com.localcommerce.delivery.service;

import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    private CustomerOrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long productId;
    private Long orderIdA;
    private Long orderIdB;

    @BeforeEach
    void setUp() {
        // Setup initial product with EXACTLY 1 stock
        Product product = new Product();
        product.setSku("RACE_SKU_001");
        product.setName("Limited Item");
        product.setStockQuantity(1); // Crucial: Only 1 in stock
        product.setActive(true);
        product = productRepository.save(product);
        productId = product.getId();

        Customer customer = new Customer();
        customer.setWhatsappNumber("+1234567890");
        customer = customerRepository.save(customer);

        // Setup Order A requesting 1 item
        CustomerOrder orderA = new CustomerOrder();
        orderA.setCustomer(customer);
        orderA.setStatus(OrderStatus.CREATED);
        OrderItem itemA = new OrderItem();
        itemA.setProduct(product);
        itemA.setQuantity(1);
        itemA.setCustomerOrder(orderA);
        orderA.setItems(new ArrayList<>(List.of(itemA)));
        orderA = orderRepository.save(orderA);
        orderIdA = orderA.getId();

        // Setup Order B requesting 1 item (same product)
        CustomerOrder orderB = new CustomerOrder();
        orderB.setCustomer(customer);
        orderB.setStatus(OrderStatus.CREATED);
        OrderItem itemB = new OrderItem();
        itemB.setProduct(product);
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
        customerRepository.deleteAll();
    }

    @Test
    void testConcurrentCheckout_OnlyOneSucceeds() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        Callable<Void> checkoutTaskA = () -> {
            try {
                orderService.finalizeOrderCheckout(orderIdA);
            } catch (Exception e) {
                // We expect exceptions here! Either:
                // 1. "no longer available" (stock race lost)
                // 2. IllegalArgumentException (network call to dummy URL failed AFTER DB commit)
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

        // EXACTLY ONE order must be finalized in the DB
        CustomerOrder finalOrderA = orderRepository.findById(orderIdA).orElseThrow();
        CustomerOrder finalOrderB = orderRepository.findById(orderIdB).orElseThrow();

        int successCount = 0;
        if (finalOrderA.getStatus() == OrderStatus.PENDING_WAREHOUSE) successCount++;
        if (finalOrderB.getStatus() == OrderStatus.PENDING_WAREHOUSE) successCount++;

        assertEquals(1, successCount, "Exactly one order should successfully commit to PENDING_WAREHOUSE despite any network failures");

        // The final stock in DB must be exactly 0 (not negative)
        Product finalProduct = productRepository.findById(productId).orElseThrow();
        assertEquals(0, finalProduct.getStockQuantity(), "Stock must be exactly 0");
    }
}
