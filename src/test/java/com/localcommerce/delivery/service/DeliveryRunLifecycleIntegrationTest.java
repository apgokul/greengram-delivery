package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.DeliveryRun;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_deliveryrun;DB_CLOSE_DELAY=-1;DEFAULT_LOCK_TIMEOUT=10000",
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
        "authorized.rider.numbers=919999999999,918888888888",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000",
        "spring.flyway.enabled=false"
})
class DeliveryRunLifecycleIntegrationTest {

    @Autowired
    private BatchingService batchingService;

    @Autowired
    private OrderService orderService;

    @MockitoSpyBean
    private DeliveryRunRepository deliveryRepo;

    @Autowired
    private CustomerOrderRepository orderRepo;

    @Autowired
    private CustomerRepository customerRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WhatsAppOutboundClient outboundClient;

    private Customer customer;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(4);
        customer = new Customer();
        customer.setWhatsappNumber("918888888888");
        customer = customerRepo.save(customer);
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
        jdbcTemplate.execute("DELETE FROM order_items");
        orderRepo.deleteAll();
        deliveryRepo.deleteAll();
        customerRepo.deleteAll();
        reset(deliveryRepo);
        reset(outboundClient);
    }

    private CustomerOrder createOrderInState(OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(status);
        order.setTotalAmount(java.math.BigDecimal.TEN);
        return orderRepo.save(order);
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
                if (className.contains("CustomerOrderRepository")
                        || methodName.contains("findByIdForUpdate")
                        || className.contains("org.h2")
                        || className.contains("BatchingService")) {
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
    void testA_NewDeliveryRunStartsInProgress() {
        CustomerOrder order = createOrderInState(OrderStatus.READY);

        batchingService.createManualBatchRun("919999999999");

        List<DeliveryRun> runs = deliveryRepo.findAll();
        assertEquals(1, runs.size());
        DeliveryRun run = runs.get(0);

        assertEquals(DeliveryRun.RunStatus.IN_PROGRESS, run.getStatus(), "Newly created run must be IN_PROGRESS");

        CustomerOrder dispatchedOrder = orderRepo.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.DISPATCHED, dispatchedOrder.getStatus());
        assertEquals(run.getId(), dispatchedOrder.getDeliveryRun().getId());
    }

    @Test
    void testB_MixedRun_OneDelivered_OneDispatched_RemainsInProgress() {
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run = deliveryRepo.save(run);

        CustomerOrder order1 = createOrderInState(OrderStatus.DISPATCHED);
        order1.setDeliveryRun(run);
        orderRepo.save(order1);

        CustomerOrder order2 = createOrderInState(OrderStatus.DISPATCHED);
        order2.setDeliveryRun(run);
        orderRepo.save(order2);

        // Deliver order1
        orderService.completeDelivery(order1.getId());

        boolean hasUndelivered = orderRepo.existsByDeliveryRunIdAndStatusNot(run.getId(), OrderStatus.DELIVERED);
        assertTrue(hasUndelivered, "Should return true since order2 is still DISPATCHED");

        DeliveryRun updatedRun = deliveryRepo.findById(run.getId()).orElseThrow();
        assertEquals(DeliveryRun.RunStatus.IN_PROGRESS, updatedRun.getStatus());
    }

    @Test
    void testC_AllDeliveredRun_TransitionsToCompleted() {
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run = deliveryRepo.save(run);

        CustomerOrder order1 = createOrderInState(OrderStatus.DISPATCHED);
        order1.setDeliveryRun(run);
        orderRepo.save(order1);

        CustomerOrder order2 = createOrderInState(OrderStatus.DISPATCHED);
        order2.setDeliveryRun(run);
        orderRepo.save(order2);

        // Deliver order1
        orderService.completeDelivery(order1.getId());

        // Deliver order2
        orderService.completeDelivery(order2.getId());

        boolean hasUndelivered = orderRepo.existsByDeliveryRunIdAndStatusNot(run.getId(), OrderStatus.DELIVERED);
        assertFalse(hasUndelivered, "Should return false since all orders are DELIVERED");

        DeliveryRun updatedRun = deliveryRepo.findById(run.getId()).orElseThrow();
        assertEquals(DeliveryRun.RunStatus.COMPLETED, updatedRun.getStatus());
    }

    @Test
    void testD_OneDelivered_OneCancelled_RunCompletes() {
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run = deliveryRepo.save(run);

        CustomerOrder order1 = createOrderInState(OrderStatus.DISPATCHED);
        order1.setDeliveryRun(run);
        orderRepo.save(order1);

        CustomerOrder order2 = createOrderInState(OrderStatus.DISPATCHED);
        order2.setDeliveryRun(run);
        orderRepo.save(order2);

        // Cancel order 1
        orderService.cancelOrder(order1.getId(), "Customer unavailable", com.localcommerce.delivery.enums.CancellationActor.RIDER);

        // Deliver order 2
        orderService.completeDelivery(order2.getId());

        DeliveryRun updatedRun = deliveryRepo.findById(run.getId()).orElseThrow();
        assertEquals(DeliveryRun.RunStatus.COMPLETED, updatedRun.getStatus());
    }

    @Test
    void testE_OrderWithoutDeliveryRun_DeliveredSafely() {
        CustomerOrder order = createOrderInState(OrderStatus.DISPATCHED);
        assertNull(order.getDeliveryRun());

        orderService.completeDelivery(order.getId());

        CustomerOrder updatedOrder = orderRepo.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.DELIVERED, updatedOrder.getStatus());

        verify(outboundClient, times(1)).sendDeliveredAlert(customer.getRecipientId());
    }

    @Test
    void testF_InvalidTransition_RejectsAndNoSideEffects() {
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run = deliveryRepo.save(run);

        // Order is READY, not DISPATCHED
        CustomerOrder order = createOrderInState(OrderStatus.READY);
        order.setDeliveryRun(run);
        orderRepo.save(order);

        assertThrows(IllegalStateException.class, () -> {
            orderService.completeDelivery(order.getId());
        });

        CustomerOrder unchangedOrder = orderRepo.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.READY, unchangedOrder.getStatus());

        DeliveryRun unchangedRun = deliveryRepo.findById(run.getId()).orElseThrow();
        assertEquals(DeliveryRun.RunStatus.IN_PROGRESS, unchangedRun.getStatus(), "Failed delivery must not transition run");

        verify(outboundClient, never()).sendDeliveredAlert(anyString());
    }

    @Test
    void testG_ConcurrentDispatch_OverlappingReadyOrders_NoDuplicateAssignmentsAndNoEmptyRuns() throws Exception {
        CustomerOrder order1 = createOrderInState(OrderStatus.READY);
        CustomerOrder order2 = createOrderInState(OrderStatus.READY);
        CustomerOrder order3 = createOrderInState(OrderStatus.READY);

        Callable<Void> rider1Task = () -> {
            try {
                batchingService.createManualBatchRun("919999999999");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        };

        Callable<Void> rider2Task = () -> {
            try {
                batchingService.createManualBatchRun("918888888888");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        };

        executorService.invokeAll(List.of(rider1Task, rider2Task));

        List<DeliveryRun> runs = deliveryRepo.findAll();
        assertFalse(runs.isEmpty(), "At least one delivery run must be created");
        assertTrue(runs.size() <= 2, "At most two runs can be created");

        Set<Long> assignedOrderIds = new HashSet<>();
        for (DeliveryRun run : runs) {
            List<CustomerOrder> runOrders = orderRepo.findAll().stream()
                    .filter(o -> o.getDeliveryRun() != null && run.getId().equals(o.getDeliveryRun().getId()))
                    .toList();

            assertFalse(runOrders.isEmpty(),
                    "No empty delivery run must be created; each run must contain at least one order");

            for (CustomerOrder o : runOrders) {
                assertEquals(OrderStatus.DISPATCHED, o.getStatus(),
                        "Dispatched orders must be in DISPATCHED status");
                boolean newlyAdded = assignedOrderIds.add(o.getId());
                assertTrue(newlyAdded,
                        "Order " + o.getId() + " was assigned to multiple delivery runs!");
            }
        }

        // Invariant checks:
        CustomerOrder finalOrder1 = orderRepo.findById(order1.getId()).orElseThrow();
        CustomerOrder finalOrder2 = orderRepo.findById(order2.getId()).orElseThrow();
        CustomerOrder finalOrder3 = orderRepo.findById(order3.getId()).orElseThrow();

        if (finalOrder1.getDeliveryRun() != null) {
            assertEquals(OrderStatus.DISPATCHED, finalOrder1.getStatus());
        }
        if (finalOrder2.getDeliveryRun() != null) {
            assertEquals(OrderStatus.DISPATCHED, finalOrder2.getStatus());
        }
        if (finalOrder3.getDeliveryRun() != null) {
            assertEquals(OrderStatus.DISPATCHED, finalOrder3.getStatus());
        }
    }

    @Test
    void testH_Dispatch_StatusRecheckUnderLock_ConcurrentCancellationSkipsOrder() throws Exception {
        CustomerOrder order1 = createOrderInState(OrderStatus.READY);
        CustomerOrder order2 = createOrderInState(OrderStatus.READY);

        CountDownLatch order2LockedLatch = new CountDownLatch(1);
        CountDownLatch releaseOrder2Latch = new CountDownLatch(1);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Transaction B: Independent transaction acquires lock on order2 FIRST and holds it
        Future<Void> cancelTxFuture = executorService.submit(() -> {
            txTemplate.execute(status -> {
                CustomerOrder lockedOrder2 = orderRepo.findByIdForUpdate(order2.getId()).orElseThrow();
                order2LockedLatch.countDown();

                try {
                    boolean released = releaseOrder2Latch.await(5, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("Timeout waiting for releaseOrder2Latch");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

                // Under lock, cancel order 2 concurrently while dispatch is waiting for it
                lockedOrder2.advanceStateTo(OrderStatus.CANCELLED);
                lockedOrder2.setCancellationReason("Customer cancelled while batching");
                orderRepo.save(lockedOrder2);
                return null;
            });
            return null;
        });

        // 1. Wait until Transaction B has locked order 2
        assertTrue(order2LockedLatch.await(5, TimeUnit.SECONDS),
                "Transaction B must acquire row lock on Order 2");

        // 2. Launch Transaction A (Dispatch): candidate IDs are [order1.id, order2.id]
        // It locks order1 (READY -> eligible), then attempts to lock order2 and BLOCKS on Transaction B's lock
        Future<Void> dispatchFuture = executorService.submit(() -> {
            batchingService.createManualBatchRun("919999999999");
            return null;
        });

        try {
            // Confirm dispatch cannot complete while Order 2's lock is held by Transaction B
            assertThrows(TimeoutException.class, () -> dispatchFuture.get(300, TimeUnit.MILLISECONDS),
                    "Dispatch must block when attempting to acquire lock on Order 2");
            assertFalse(dispatchFuture.isDone(),
                    "Dispatch must remain blocked waiting for Order 2 lock");
        } finally {
            // 3. Unblock Transaction B to cancel Order 2 and commit
            releaseOrder2Latch.countDown();
        }

        // Wait for both transactions to complete
        cancelTxFuture.get(5, TimeUnit.SECONDS);
        dispatchFuture.get(5, TimeUnit.SECONDS);

        // Invariants:
        // - Order 1 must be DISPATCHED and assigned to the new DeliveryRun
        CustomerOrder finalOrder1 = orderRepo.findById(order1.getId()).orElseThrow();
        assertEquals(OrderStatus.DISPATCHED, finalOrder1.getStatus());
        assertNotNull(finalOrder1.getDeliveryRun());

        // - Order 2 must be CANCELLED and NOT assigned to any DeliveryRun
        CustomerOrder finalOrder2 = orderRepo.findById(order2.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, finalOrder2.getStatus());
        assertNull(finalOrder2.getDeliveryRun(), "Cancelled order must not be assigned to any run");

        // - Delivery run must contain ONLY order 1
        DeliveryRun run = finalOrder1.getDeliveryRun();
        List<CustomerOrder> runOrders = orderRepo.findAll().stream()
                .filter(o -> o.getDeliveryRun() != null && run.getId().equals(o.getDeliveryRun().getId()))
                .toList();
        assertEquals(1, runOrders.size(), "Delivery run must contain only eligible orders");
        assertEquals(finalOrder1.getId(), runOrders.get(0).getId());
    }

    @Test
    void testI_Dispatch_OrdersWithMissingLocations_DispatchedSuccessfully() {
        CustomerOrder order = createOrderInState(OrderStatus.READY);
        order.setDeliveryLatitude(null);
        order.setDeliveryLongitude(null);
        order = orderRepo.save(order);

        batchingService.createManualBatchRun("919999999999");

        CustomerOrder dispatched = orderRepo.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.DISPATCHED, dispatched.getStatus());
        assertNotNull(dispatched.getDeliveryRun());
        assertNull(dispatched.getDeliveryLatitude(), "Missing location must be preserved");

        // Verify manifest was sent containing the LOCATION_REQUIRED badge
        verify(outboundClient, atLeastOnce()).sendTextUpdate(eq("919999999999"), contains(WhatsAppConstants.LOCATION_REQUIRED_BADGE));
    }

    @Test
    void testJ_Dispatch_DatabaseFailureRollsBackRunAndOrderAssignments_SendsZeroNotifications() {
        CustomerOrder order = createOrderInState(OrderStatus.READY);

        // Simulate a persistence failure during run creation inside transactionTemplate.execute
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated database failure during run persistence"))
                .when(deliveryRepo).save(any(DeliveryRun.class));

        assertThrows(RuntimeException.class, () ->
                batchingService.createManualBatchRun("919999999999")
        );

        // Database Atomicity Assertions:
        // Order must remain in READY status and not assigned to any run
        CustomerOrder rolledBackOrder = orderRepo.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.READY, rolledBackOrder.getStatus(),
                "Order status must roll back to READY on transaction failure");
        assertNull(rolledBackOrder.getDeliveryRun(),
                "Order deliveryRun assignment must roll back on transaction failure");

        // No DeliveryRun entity must be committed in the database
        List<DeliveryRun> runs = deliveryRepo.findAll();
        assertEquals(0, runs.size(),
                "DeliveryRun creation must be rolled back atomically with order updates");

        // Outbound Notification Guarantee:
        // When database commit fails, ZERO dispatch notifications must be sent!
        verify(outboundClient, never()).sendTextUpdate(anyString(), anyString());
        verify(outboundClient, never()).sendOutForDeliveryAlert(anyString(), anyString(), anyString());
        verify(outboundClient, never()).sendRiderDeliveryCard(anyString(), any(CustomerOrder.class));
    }

    @Test
    void testK_ConcurrentDispatch_LockContention_SecondWorkerBlocksUntilFirstReleases() throws Exception {
        CustomerOrder order = createOrderInState(OrderStatus.READY);

        CountDownLatch orderLockedLatch = new CountDownLatch(1);
        CountDownLatch releaseOrderLatch = new CountDownLatch(1);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Thread 1: Lock order under Level 1 pessimistic write lock in open transaction
        Future<Void> lockHolderFuture = executorService.submit(() -> {
            txTemplate.execute(status -> {
                CustomerOrder lockedOrder = orderRepo.findByIdForUpdate(order.getId()).orElseThrow();
                orderLockedLatch.countDown();

                try {
                    boolean released = releaseOrderLatch.await(5, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("Timeout waiting for releaseOrderLatch");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

                // Dispatch order under lock
                DeliveryRun run = new DeliveryRun();
                run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
                run.setDispatchedAt(java.time.LocalDateTime.now());
                run.setRiderPhoneNumber("919999999999");
                run = deliveryRepo.save(run);

                lockedOrder.setDeliveryRun(run);
                lockedOrder.advanceStateTo(OrderStatus.DISPATCHED);
                orderRepo.save(lockedOrder);
                return null;
            });
            return null;
        });

        CountDownLatch worker2StartedLatch = new CountDownLatch(1);
        AtomicReference<Thread> worker2ThreadRef = new AtomicReference<>();

        try {
            assertTrue(orderLockedLatch.await(5, TimeUnit.SECONDS),
                    "Thread 1 must acquire the pessimistic lock on the order");

            // Worker 2 attempts dispatch on the exact same order
            Future<Void> dispatchFuture = executorService.submit(() -> {
                worker2ThreadRef.set(Thread.currentThread());
                worker2StartedLatch.countDown();
                batchingService.createManualBatchRun("918888888888");
                return null;
            });

            // 1. Confirm worker 2 has started
            assertTrue(worker2StartedLatch.await(5, TimeUnit.SECONDS),
                    "Worker 2 must have started");

            // 2. Wait until worker 2 is in the lock-acquisition path
            assertTrue(waitForWorkerInLockPath(worker2ThreadRef.get(), 2000),
                    "Worker 2 must enter lock-acquisition path and block on Level 1 order lock");

            // 3. Verify worker 2 CANNOT complete while Thread 1 holds the lock
            assertThrows(TimeoutException.class, () -> dispatchFuture.get(300, TimeUnit.MILLISECONDS),
                    "Worker 2 must block on the order lock and cannot complete");
            assertFalse(dispatchFuture.isDone());

            // Release lock holder
            releaseOrderLatch.countDown();

            // Both tasks must complete
            lockHolderFuture.get(5, TimeUnit.SECONDS);
            dispatchFuture.get(5, TimeUnit.SECONDS);

            // Invariants:
            // Exactly 1 DeliveryRun created
            List<DeliveryRun> runs = deliveryRepo.findAll();
            assertEquals(1, runs.size(), "Only one delivery run must be created");

            // Order is DISPATCHED to Run 1
            CustomerOrder finalOrder = orderRepo.findById(order.getId()).orElseThrow();
            assertEquals(OrderStatus.DISPATCHED, finalOrder.getStatus());
            assertEquals(runs.get(0).getId(), finalOrder.getDeliveryRun().getId());

            // Rider 2 received 'no orders' notification because order was already dispatched under lock
            verify(outboundClient, atLeastOnce()).sendTextUpdate(eq("918888888888"), contains("No orders are currently ready for dispatch"));
        } finally {
            releaseOrderLatch.countDown();
        }
    }

    @Test
    void testL_Dispatch_OutboundNotificationFailureDoesNotRollbackCommittedState() {
        CustomerOrder order = createOrderInState(OrderStatus.READY);

        // Configure outbound calls to throw simulated network/API exceptions
        doThrow(new RuntimeException("Simulated Meta API failure on manifest"))
                .when(outboundClient).sendTextUpdate(eq("919999999999"), anyString());
        doThrow(new RuntimeException("Simulated Meta API failure on customer alert"))
                .when(outboundClient).sendOutForDeliveryAlert(anyString(), anyString(), anyString());

        // Dispatch must complete cleanly without throwing out of createManualBatchRun
        assertDoesNotThrow(() -> batchingService.createManualBatchRun("919999999999"));

        // Database state must be fully committed despite notification failures
        CustomerOrder dispatched = orderRepo.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.DISPATCHED, dispatched.getStatus(),
                "Order must remain committed in DISPATCHED status despite notification failure");
        assertNotNull(dispatched.getDeliveryRun(),
                "Order must remain assigned to the committed delivery run");

        List<DeliveryRun> runs = deliveryRepo.findAll();
        assertEquals(1, runs.size(),
                "DeliveryRun must remain committed in the database");

        // Subsequent notification (rider card) must still be attempted
        verify(outboundClient).sendRiderDeliveryCard(eq("919999999999"), any(CustomerOrder.class));
    }

    @Test
    void testM_Dispatch_CommitOccursBeforeOutboundNotifications() {
        CustomerOrder order = createOrderInState(OrderStatus.READY);

        AtomicBoolean dbCommittedAtNotificationTime = new AtomicBoolean(false);

        // When sendTextUpdate is called for the manifest, inspect DB state through an independent read
        doAnswer(invocation -> {
            CustomerOrder currentOrder = orderRepo.findById(order.getId()).orElse(null);
            List<DeliveryRun> runs = deliveryRepo.findAll();
            if (currentOrder != null && currentOrder.getStatus() == OrderStatus.DISPATCHED
                    && currentOrder.getDeliveryRun() != null && runs.size() == 1) {
                dbCommittedAtNotificationTime.set(true);
            }
            return null;
        }).when(outboundClient).sendTextUpdate(eq("919999999999"), anyString());

        batchingService.createManualBatchRun("919999999999");

        assertTrue(dbCommittedAtNotificationTime.get(),
                "Database changes must be committed and visible in the database before outbound notification executes");
    }

    @Test
    void testN_Dispatch_NotificationOrdering_ManifestPrecedesDeliveryCards() {
        CustomerOrder order1 = createOrderInState(OrderStatus.READY);
        CustomerOrder order2 = createOrderInState(OrderStatus.READY);

        batchingService.createManualBatchRun("919999999999");

        // Verify notification ordering:
        // 1. Manifest text sent first
        // 2. Order 1 customer alert, then rider card
        // 3. Order 2 customer alert, then rider card
        InOrder inOrder = inOrder(outboundClient);
        inOrder.verify(outboundClient).sendTextUpdate(eq("919999999999"), contains("DAILY DISPATCH MANIFEST"));
        inOrder.verify(outboundClient).sendOutForDeliveryAlert(eq("918888888888"), eq("919999999999"), eq("45"));
        inOrder.verify(outboundClient).sendRiderDeliveryCard(eq("919999999999"), argThat(o -> o.getId().equals(order1.getId())));
        inOrder.verify(outboundClient).sendOutForDeliveryAlert(eq("918888888888"), eq("919999999999"), eq("45"));
        inOrder.verify(outboundClient).sendRiderDeliveryCard(eq("919999999999"), argThat(o -> o.getId().equals(order2.getId())));
    }

    @Test
    void testO_Dispatch_NoEligibleOrders_SendsNoOrdersMessageOutsideTransaction() {
        // No orders created in READY status
        batchingService.createManualBatchRun("919999999999");

        // Verify no run created
        assertEquals(0, deliveryRepo.findAll().size(), "No delivery run should be created");

        // Verify no-ready-orders message was sent
        verify(outboundClient).sendTextUpdate(eq("919999999999"), contains("No orders are currently ready for dispatch"));

        // Verify zero customer alerts or rider cards sent
        verify(outboundClient, never()).sendOutForDeliveryAlert(anyString(), anyString(), anyString());
        verify(outboundClient, never()).sendRiderDeliveryCard(anyString(), any(CustomerOrder.class));
    }

    @Test
    void testP_Dispatch_CustomerNotificationFailureForOneOrder_DoesNotPreventLaterOrderNotifications() {
        Customer customer2 = new Customer();
        customer2.setWhatsappNumber("917777777777");
        customer2 = customerRepo.save(customer2);

        CustomerOrder order1 = createOrderInState(OrderStatus.READY);
        CustomerOrder rawOrder2 = new CustomerOrder();
        rawOrder2.setCustomer(customer2);
        rawOrder2.setStatus(OrderStatus.READY);
        rawOrder2.setTotalAmount(java.math.BigDecimal.valueOf(25));
        CustomerOrder order2 = orderRepo.save(rawOrder2);

        // Fail customer 1 alert specifically
        doThrow(new RuntimeException("Simulated customer 1 Meta timeout"))
                .when(outboundClient).sendOutForDeliveryAlert(eq("918888888888"), anyString(), anyString());

        assertDoesNotThrow(() -> batchingService.createManualBatchRun("919999999999"));

        // Database commit invariants
        CustomerOrder dispatched1 = orderRepo.findById(order1.getId()).orElseThrow();
        CustomerOrder dispatched2 = orderRepo.findById(order2.getId()).orElseThrow();
        assertEquals(OrderStatus.DISPATCHED, dispatched1.getStatus());
        assertEquals(OrderStatus.DISPATCHED, dispatched2.getStatus());
        assertEquals(dispatched1.getDeliveryRun().getId(), dispatched2.getDeliveryRun().getId());

        // Manifest delivered
        verify(outboundClient).sendTextUpdate(eq("919999999999"), contains("DAILY DISPATCH MANIFEST"));

        // Order 1: customer alert failed (attempted), but rider delivery card must still be delivered
        verify(outboundClient).sendOutForDeliveryAlert(eq("918888888888"), eq("919999999999"), eq("45"));
        verify(outboundClient).sendRiderDeliveryCard(eq("919999999999"), argThat(o -> o.getId().equals(order1.getId())));

        // Order 2: both customer alert and rider delivery card must be delivered cleanly
        verify(outboundClient).sendOutForDeliveryAlert(eq("917777777777"), eq("919999999999"), eq("45"));
        verify(outboundClient).sendRiderDeliveryCard(eq("919999999999"), argThat(o -> o.getId().equals(order2.getId())));
    }
}
