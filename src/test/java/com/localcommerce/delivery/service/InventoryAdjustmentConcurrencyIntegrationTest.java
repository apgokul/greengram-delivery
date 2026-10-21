package com.localcommerce.delivery.service;

import com.localcommerce.delivery.dto.InventoryDTO;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.*;
import com.localcommerce.delivery.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_inv_concurrency;DB_CLOSE_DELAY=-1;DEFAULT_LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
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
        "JWT_EXPIRATION_MS=10000"
})
class InventoryAdjustmentConcurrencyIntegrationTest {

    @Autowired
    private ProductAdminService adminService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockitoSpyBean
    private InventoryAuditLogRepository auditLogRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CustomerOrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long inventoryId;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        tearDown();
        Mockito.reset(auditLogRepository);
        executorService = Executors.newFixedThreadPool(2);

        Inventory inv = new Inventory();
        inv.setBaseSku("DAL_CONCURRENT");
        inv.setStockQuantity(new BigDecimal("10.000"));
        inv.setUnit(ProductUnit.KG);
        inv = inventoryRepository.save(inv);
        inventoryId = inv.getId();
    }

    @AfterEach
    void tearDown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            try {
                executorService.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        Mockito.reset(auditLogRepository);
        jdbcTemplate.execute("DELETE FROM order_items");
        orderRepository.deleteAll();
        productRepository.deleteAll();
        auditLogRepository.deleteAll();
        inventoryRepository.deleteAll();
        customerRepository.deleteAll();
    }

    private boolean waitForWorkerInLockPath(Thread workerThread, long timeoutMs) throws InterruptedException {
        if (workerThread == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            StackTraceElement[] stack = workerThread.getStackTrace();
            for (StackTraceElement frame : stack) {
                String className = frame.getClassName();
                String methodName = frame.getMethodName();
                if (className.contains("InventoryRepository")
                        || methodName.contains("findByIdForUpdate")
                        || className.contains("org.h2")
                        || className.contains("ProductAdminService")
                        || className.contains("OrderService")) {
                    Thread.State state = workerThread.getState();
                    if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED) {
                        return true;
                    }
                }
            }
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void testConcurrentAdjustments_WithRowLockContention() throws Exception {
        CountDownLatch lockHeldLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        CountDownLatch workerStartedLatch = new CountDownLatch(1);
        AtomicReference<Thread> workerThreadRef = new AtomicReference<>();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Transaction 1: Thread 1 acquires pessimistic row lock, updates stock (+5.000), then pauses before committing
        Future<Void> future1 = executorService.submit(() -> {
            txTemplate.execute(status -> {
                Inventory locked = inventoryRepository.findByIdForUpdate(inventoryId).orElseThrow();
                BigDecimal oldStock = locked.getStockQuantity();
                BigDecimal newStock = oldStock.add(new BigDecimal("5.000"));
                locked.setStockQuantity(newStock);
                inventoryRepository.save(locked);

                InventoryAuditLog audit = new InventoryAuditLog();
                audit.setSku(locked.getBaseSku());
                audit.setOldStock(oldStock);
                audit.setNewStock(newStock);
                audit.setChangeAmount(new BigDecimal("5.000"));
                audit.setReason("Restock");
                audit.setChangedBy("Admin_A");
                auditLogRepository.save(audit);

                lockHeldLatch.countDown();

                try {
                    boolean released = releaseLockLatch.await(5, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("Timeout waiting for releaseLockLatch");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return null;
            });
            return null;
        });

        try {
            // Wait deterministically until Transaction 1 has acquired the row lock
            assertTrue(lockHeldLatch.await(5, TimeUnit.SECONDS),
                    "Transaction 1 must acquire the pessimistic row lock");

            // Transaction 2: Admin_B calls production adjustInventoryStock on the same inventory row
            Future<InventoryDTO> future2 = executorService.submit(() -> {
                workerThreadRef.set(Thread.currentThread());
                workerStartedLatch.countDown();
                return adminService.adjustInventoryStock(inventoryId, new BigDecimal("-3.000"), "Spoiled", "Admin_B");
            });

            // 1. Confirm Transaction B worker thread has actually started execution
            assertTrue(workerStartedLatch.await(5, TimeUnit.SECONDS),
                    "Transaction B worker thread must have started");

            // 2. Confirm Transaction B entered the lock acquisition path and is in a lock-wait state
            assertTrue(waitForWorkerInLockPath(workerThreadRef.get(), 2000),
                    "Transaction B must enter the lock acquisition path and block on the database lock");

            // 3. Verify contention: Transaction 2 CANNOT complete because Transaction 1 holds the pessimistic row lock
            assertThrows(TimeoutException.class, () -> future2.get(300, TimeUnit.MILLISECONDS),
                    "Transaction 2 must block on the pessimistic row lock and must not complete while Transaction 1 holds the lock");
            assertFalse(future2.isDone(),
                    "Transaction 2 must remain blocked on the database row lock while Transaction 1 holds it");

            // Release Transaction 1's lock by allowing it to commit
            releaseLockLatch.countDown();

            // Both transactions must now complete successfully
            future1.get(5, TimeUnit.SECONDS);
            InventoryDTO result2 = future2.get(5, TimeUnit.SECONDS);
            assertNotNull(result2, "Second adjustment must succeed");

            // Verify final state:
            // Initial: 10.000
            // + 5.000 (Transaction 1)
            // - 3.000 (Transaction 2)
            // Final stock must be EXACTLY 12.000
            Inventory finalInv = inventoryRepository.findById(inventoryId).orElseThrow();
            assertEquals(0, new BigDecimal("12.000").compareTo(finalInv.getStockQuantity()),
                    "Stock must reflect both adjustments: 10.000 + 5.000 - 3.000 = 12.000");

            List<InventoryAuditLog> logs = auditLogRepository.findAll();
            assertEquals(2, logs.size(), "Both adjustments must produce audit log entries");
        } finally {
            releaseLockLatch.countDown();
        }
    }

    @Test
    void testConcurrentAdjustmentAndCheckout_WithRowLockContention() throws Exception {
        // Set up product and order in CREATED state demanding 2 KG
        Product product = new Product();
        product.setSku("DAL-CONCURRENT-1KG");
        product.setName("Concurrent Dal 1KG");
        product.setCurrentPrice(new BigDecimal("80.00"));
        product.setPackageQuantity(new BigDecimal("1.000"));
        product.setPackageUnit(ProductUnit.KG);
        product.setActive(true);
        product.setInventory(inventoryRepository.findById(inventoryId).orElseThrow());
        product = productRepository.save(product);

        Customer customer = new Customer();
        customer.setWhatsappNumber("919999999999");
        customer = customerRepository.save(customer);

        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CREATED);
        order.setTotalAmount(new BigDecimal("160.00"));

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(2);
        item.setLockedPrice(product.getCurrentPrice());
        item.setCustomerOrder(order);
        order.setItems(new ArrayList<>(List.of(item)));
        order = orderRepository.save(order);
        Long orderId = order.getId();

        CountDownLatch adminLockHeldLatch = new CountDownLatch(1);
        CountDownLatch adminReleaseLatch = new CountDownLatch(1);
        CountDownLatch checkoutWorkerStartedLatch = new CountDownLatch(1);
        AtomicReference<Thread> checkoutThreadRef = new AtomicReference<>();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Transaction A: Admin restocks +4.000 KG under pessimistic row lock
        Future<Void> adminFuture = executorService.submit(() -> {
            txTemplate.execute(status -> {
                Inventory locked = inventoryRepository.findByIdForUpdate(inventoryId).orElseThrow();
                BigDecimal oldStock = locked.getStockQuantity();
                BigDecimal newStock = oldStock.add(new BigDecimal("4.000"));
                locked.setStockQuantity(newStock);
                inventoryRepository.save(locked);

                InventoryAuditLog audit = new InventoryAuditLog();
                audit.setSku(locked.getBaseSku());
                audit.setOldStock(oldStock);
                audit.setNewStock(newStock);
                audit.setChangeAmount(new BigDecimal("4.000"));
                audit.setReason("Restock");
                audit.setChangedBy("Admin_Restock");
                auditLogRepository.save(audit);

                adminLockHeldLatch.countDown();

                try {
                    boolean released = adminReleaseLatch.await(5, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("Timeout waiting for adminReleaseLatch");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return null;
            });
            return null;
        });

        try {
            // Wait until Admin holds the inventory row lock
            assertTrue(adminLockHeldLatch.await(5, TimeUnit.SECONDS),
                    "Admin transaction must acquire row lock and pause");

            // Transaction B: Customer checkout calls production finalizeOrderCheckout demanding 2.000 KG
            Future<Void> checkoutFuture = executorService.submit(() -> {
                checkoutThreadRef.set(Thread.currentThread());
                checkoutWorkerStartedLatch.countDown();
                orderService.finalizeOrderCheckout(orderId);
                return null;
            });

            // 1. Confirm checkout worker thread has actually started execution
            assertTrue(checkoutWorkerStartedLatch.await(5, TimeUnit.SECONDS),
                    "Checkout worker thread must have started");

            // 2. Confirm checkout entered the lock acquisition path and is in a lock-wait state
            assertTrue(waitForWorkerInLockPath(checkoutThreadRef.get(), 2000),
                    "Checkout must enter the lock acquisition path and block on Level 2 inventory row lock");

            // 3. Verify contention: Checkout CANNOT complete while Admin holds the inventory row lock
            assertThrows(TimeoutException.class, () -> checkoutFuture.get(300, TimeUnit.MILLISECONDS),
                    "Checkout must block on Level 2 inventory row lock while Admin holds it");
            assertFalse(checkoutFuture.isDone(),
                    "Checkout must remain blocked waiting for the inventory row lock");

            // Release Admin's lock
            adminReleaseLatch.countDown();

            // Both tasks must now complete
            adminFuture.get(5, TimeUnit.SECONDS);
            checkoutFuture.get(5, TimeUnit.SECONDS);

            // Initial: 10.000 KG
            // + 4.000 KG (admin restock)
            // - 2.000 KG (checkout deduction)
            // Final stock must be EXACTLY 12.000 KG, proving serializability under lock
            Inventory finalInv = inventoryRepository.findById(inventoryId).orElseThrow();
            assertEquals(0, new BigDecimal("12.000").compareTo(finalInv.getStockQuantity()),
                    "Stock must reflect both admin restock (+4) and checkout deduction (-2): 10 + 4 - 2 = 12.000");

            CustomerOrder finalizedOrder = orderRepository.findById(orderId).orElseThrow();
            assertEquals(OrderStatus.PENDING_WAREHOUSE, finalizedOrder.getStatus(),
                    "Checkout must successfully complete and transition to PENDING_WAREHOUSE");
        } finally {
            adminReleaseLatch.countDown();
        }
    }

    @Test
    void testConcurrentAdjustments_DirectServiceCalls_DoNotLoseUpdates() throws Exception {
        Callable<Void> task1 = () -> {
            try {
                adminService.adjustInventoryStock(inventoryId, new BigDecimal("5.000"), "Restock", "Admin_A");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            try {
                adminService.adjustInventoryStock(inventoryId, new BigDecimal("-3.000"), "Spoiled", "Admin_B");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        };

        // Submit both adjustments simultaneously across separate threads
        executorService.invokeAll(List.of(task1, task2));

        // Final stock must be EXACTLY 12.000
        Inventory finalInv = inventoryRepository.findById(inventoryId).orElseThrow();
        assertEquals(0, new BigDecimal("12.000").compareTo(finalInv.getStockQuantity()),
                "Stock must reflect both adjustments: 10 + 5 - 3 = 12.000");

        List<InventoryAuditLog> logs = auditLogRepository.findAll();
        assertEquals(2, logs.size(), "Both adjustments must produce audit log entries");
    }

    @Test
    void testAdjustInventoryStock_AuditFailure_RollsBackStockChangeInRealDatabase() {
        // Force auditLogRepository.save to fail with an unchecked exception
        doThrow(new RuntimeException("Simulated database failure during audit write"))
                .when(auditLogRepository).save(any(InventoryAuditLog.class));

        // Execution must fail and propagate exception
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                adminService.adjustInventoryStock(inventoryId, new BigDecimal("5.000"), "Restock", "Admin_Fail")
        );
        assertEquals("Simulated database failure during audit write", ex.getMessage());

        // Verify real database rollback:
        // Stock must remain unchanged at 10.000, and zero audit records must exist
        Inventory freshInv = inventoryRepository.findById(inventoryId).orElseThrow();
        assertEquals(0, new BigDecimal("10.000").compareTo(freshInv.getStockQuantity()),
                "Database transaction must roll back stock modification when audit log insertion fails");

        assertEquals(0, auditLogRepository.count(),
                "No audit log entry must be committed when the transaction rolls back");
    }
}
