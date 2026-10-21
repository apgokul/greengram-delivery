package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_orderservice_loc;DB_CLOSE_DELAY=-1;DEFAULT_LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "store.latitude=10.000",
        "store.longitude=76.200",
        "store.max.radius.km=20.0",
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
class OrderServiceLocationTransactionIntegrationTest {

    @Autowired
    private OrderService orderService;

    @MockitoSpyBean
    private CustomerOrderRepository orderRepo;

    @Autowired
    private CustomerRepository customerRepo;

    @MockitoBean
    private WhatsAppOutboundClient outboundClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate requiresNewTx;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM order_items");
        jdbcTemplate.execute("DELETE FROM customer_orders");
        jdbcTemplate.execute("DELETE FROM delivery_runs");
        jdbcTemplate.execute("DELETE FROM customers");

        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private Customer createCustomer(String bsuid, String phone) {
        return requiresNewTx.execute(status -> {
            Customer c = new Customer();
            c.setBsuid(bsuid);
            c.setWhatsappNumber(phone);
            c.setName("Customer " + bsuid);
            return customerRepo.save(c);
        });
    }

    private CustomerOrder createOrder(Customer customer, String displayId, OrderStatus status, BigDecimal amount) {
        return requiresNewTx.execute(txStatus -> {
            Customer managedCustomer = customerRepo.findById(customer.getId()).orElseThrow();
            CustomerOrder o = new CustomerOrder();
            o.setCustomer(managedCustomer);
            o.setDisplayId(displayId);
            o.setStatus(status);
            o.setTotalAmount(amount);
            o.setItems(new ArrayList<>());
            return orderRepo.save(o);
        });
    }

    @Test
    void testUpdateCustomerLocation_CommittedStateVisibleBeforeNotificationStarts() {
        Customer customer = createCustomer("CUST_VIS_1", "919000000001");
        CustomerOrder order = createOrder(customer, "ORD-VIS-1", OrderStatus.CREATED, new BigDecimal("250.00"));

        AtomicBoolean verifiedInDb = new AtomicBoolean(false);

        doAnswer(invocation -> {
            // Independent read outside the transaction
            Double dbLat = jdbcTemplate.queryForObject(
                    "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                    Double.class,
                    order.getId()
            );
            Double custLat = jdbcTemplate.queryForObject(
                    "SELECT latitude FROM customers WHERE id = ?",
                    Double.class,
                    customer.getId()
            );

            if (dbLat != null && Math.abs(dbLat - 10.010) < 0.001
                    && custLat != null && Math.abs(custLat - 10.010) < 0.001) {
                verifiedInDb.set(true);
            }
            return null;
        }).when(outboundClient).sendTextUpdate(eq("919000000001"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));

        orderService.updateCustomerLocation("CUST_VIS_1", 10.010, 76.210);

        assertTrue(verifiedInDb.get(), "Database changes must be committed and visible BEFORE outbound notification starts");
    }

    @Test
    void testConfirmSavedLocation_CommittedStateVisibleBeforeNotificationStarts() {
        Customer customer = createCustomer("CUST_SAVED_1", "919000000002");
        requiresNewTx.executeWithoutResult(status -> {
            Customer c = customerRepo.findById(customer.getId()).orElseThrow();
            c.setLatitude(10.015);
            c.setLongitude(76.215);
            customerRepo.save(c);
        });

        CustomerOrder order = createOrder(customer, "ORD-SAVED-1", OrderStatus.CREATED, new BigDecimal("180.00"));

        AtomicBoolean verifiedInDb = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Double dbLat = jdbcTemplate.queryForObject(
                    "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                    Double.class,
                    order.getId()
            );
            if (dbLat != null && Math.abs(dbLat - 10.015) < 0.001) {
                verifiedInDb.set(true);
            }
            return null;
        }).when(outboundClient).sendTextUpdate(eq("919000000002"), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));

        orderService.confirmSavedLocation(order.getId(), "919000000002");

        assertTrue(verifiedInDb.get(), "Saved location changes must be committed before notification starts");
    }

    @Test
    void testConfirmSavedLocation_WithoutSavedCoordinates_SendsPromptAndDoesNotSetOrderCoordinates() {
        Customer customer = createCustomer("CUST_NO_SAVED", "919000000003");
        CustomerOrder order = createOrder(customer, "ORD-NO-SAVED-1", OrderStatus.CREATED, new BigDecimal("120.00"));

        orderService.confirmSavedLocation(order.getId(), "919000000003");

        verify(outboundClient, times(1)).sendDeliveryLocationPrompt("919000000003");
        verify(outboundClient, never()).sendTextUpdate(anyString(), anyString());

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Order coordinates must remain null when customer has no saved location");
    }

    @Test
    void testUpdateCustomerLocation_PersistenceFailure_RollsBackAndSendsNoSuccessNotification() {
        Customer customer = createCustomer("CUST_FAIL_1", "919000000004");
        CustomerOrder order = createOrder(customer, "ORD-FAIL-1", OrderStatus.CREATED, new BigDecimal("300.00"));

        doThrow(new DataIntegrityViolationException("Simulated DB failure on order save"))
                .when(orderRepo).save(argThat(o -> o != null && order.getId().equals(o.getId())));

        assertThrows(DataIntegrityViolationException.class, () ->
                orderService.updateCustomerLocation("CUST_FAIL_1", 10.010, 76.210)
        );

        // Verify NO success notification sent
        verifyNoInteractions(outboundClient);

        // Verify DB state rolled back
        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Order coordinates must not be updated if transaction fails");

        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertNull(custLat, "Customer profile must not be updated if transaction fails");
    }

    @Test
    void testUpdateCustomerLocation_OutboundFailure_DoesNotRollBackCommittedLocation() {
        Customer customer = createCustomer("CUST_OUTBOUND_FAIL", "919000000005");
        CustomerOrder order = createOrder(customer, "ORD-OUT-FAIL", OrderStatus.CREATED, new BigDecimal("200.00"));

        doThrow(new RuntimeException("WhatsApp API timeout"))
                .when(outboundClient).sendTextUpdate(anyString(), anyString());

        assertDoesNotThrow(() ->
                orderService.updateCustomerLocation("CUST_OUTBOUND_FAIL", 10.010, 76.210)
        );

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNotNull(dbLat);
        assertEquals(10.010, dbLat, 0.001, "Committed coordinates must persist despite outbound failure");

        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertNotNull(custLat);
        assertEquals(10.010, custLat, 0.001, "Committed customer profile must persist despite outbound failure");
    }

    @Test
    void testConfirmSavedLocation_OutboundFailure_DoesNotRollBackCommittedLocation() {
        Customer customer = createCustomer("CUST_CONFIRM_FAIL", "919000000006");
        requiresNewTx.executeWithoutResult(status -> {
            Customer c = customerRepo.findById(customer.getId()).orElseThrow();
            c.setLatitude(10.025);
            c.setLongitude(76.225);
            customerRepo.save(c);
        });

        CustomerOrder order = createOrder(customer, "ORD-CONFIRM-FAIL", OrderStatus.CREATED, new BigDecimal("150.00"));

        doThrow(new RuntimeException("WhatsApp network error"))
                .when(outboundClient).sendTextUpdate(anyString(), anyString());

        assertDoesNotThrow(() -> orderService.confirmSavedLocation(order.getId(), "919000000006"));

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNotNull(dbLat);
        assertEquals(10.025, dbLat, 0.001, "Order coordinates must remain committed despite outbound failure");
    }

    @Test
    void testUpdateCustomerLocation_StaleCandidateRevalidatedUnderLock_DoesNotOverwrite() {
        Customer customer = createCustomer("CUST_STALE_1", "919000000007");
        CustomerOrder order = createOrder(customer, "ORD-STALE-1", OrderStatus.CREATED, new BigDecimal("320.00"));

        // Simulate concurrent assignment right as findByIdForUpdate is called:
        // When findByIdForUpdate is called, another transaction has already assigned (10.050, 76.250).
        doAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            return requiresNewTx.execute(status -> {
                CustomerOrder o = orderRepo.findById(orderId).orElseThrow();
                o.setDeliveryLatitude(10.050);
                o.setDeliveryLongitude(76.250);
                return Optional.of(orderRepo.save(o));
            });
        }).when(orderRepo).findByIdForUpdate(order.getId());

        orderService.updateCustomerLocation("CUST_STALE_1", 10.010, 76.210);

        // Assert order coordinates were NOT overwritten by stale candidate
        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertEquals(10.050, dbLat, 0.001, "Stale candidate must not overwrite newer coordinates");

        // Customer default profile gets the new pin
        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertEquals(10.010, custLat, 0.001, "Customer profile is updated with the new pin");

        // Outbound notification informs customer that pin was saved for future orders
        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000007"),
                eq("📍 Your location has been updated & saved for your future orders.")
        );
    }

    @Test
    void testUpdateCustomerLocation_OutOfBounds_SendsMessageAndDoesNotMutateDb() {
        Customer customer = createCustomer("CUST_OOB_1", "919000000008");
        CustomerOrder order = createOrder(customer, "ORD-OOB-1", OrderStatus.CREATED, new BigDecimal("100.00"));

        // Pin at 15.000, 80.000 (~600km away from 10.000, 76.200)
        orderService.updateCustomerLocation("CUST_OOB_1", 15.000, 80.000);

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Order coordinates must not be updated for out-of-bounds pin");

        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertNull(custLat, "Customer profile must not be updated for out-of-bounds pin");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000008"),
                contains("Out of Delivery Zone")
        );
    }

    @Test
    void testUpdateCustomerLocation_MultipleOrders_DisambiguationPromptDetachedSnapshots() {
        Customer customer = createCustomer("CUST_MULTI_1", "919000000009");
        CustomerOrder order1 = createOrder(customer, "ORD-M-1", OrderStatus.CREATED, new BigDecimal("100.00"));
        CustomerOrder order2 = createOrder(customer, "ORD-M-2", OrderStatus.PENDING_WAREHOUSE, new BigDecimal("200.00"));

        orderService.updateCustomerLocation("CUST_MULTI_1", 10.010, 76.210);

        // Neither order coordinates set yet
        Double dbLat1 = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order1.getId()
        );
        Double dbLat2 = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order2.getId()
        );
        assertNull(dbLat1);
        assertNull(dbLat2);

        // Customer profile updated
        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertEquals(10.010, custLat, 0.001);

        // Disambiguation prompt sent with snapshots
        verify(outboundClient, times(1)).sendLocationDisambiguationPrompt(
                eq("919000000009"),
                argThat(list -> list.size() == 2 && list.stream().anyMatch(o -> o.getId().equals(order1.getId()))
                        && list.stream().anyMatch(o -> o.getId().equals(order2.getId()))),
                eq(10.010),
                eq(76.210)
        );
    }

    @Test
    void testConcurrentLocationUpdates_NoDeadlock() throws Exception {
        Customer customer = createCustomer("CUST_CONC_1", "919000000010");
        CustomerOrder order = createOrder(customer, "ORD-CONC-1", OrderStatus.CREATED, new BigDecimal("150.00"));

        int concurrency = 4;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    if (index % 2 == 0) {
                        orderService.updateCustomerLocation("CUST_CONC_1", 10.010 + index * 0.001, 76.210 + index * 0.001);
                    } else {
                        orderService.assignLocationToOrder(order.getId(), "919000000010", 10.020 + index * 0.001, 76.220 + index * 0.001);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Concurrent location updates must complete without deadlock");

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get());
        }

        // Verify order has delivery coordinates assigned
        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNotNull(dbLat, "Order must have delivery coordinates assigned");
    }

    @Test
    void testConfirmSavedLocation_CancelledOrder_RejectedAndDbUnchanged() {
        Customer customer = createCustomer("CUST_CONF_CANCEL", "919000000021");
        requiresNewTx.executeWithoutResult(status -> {
            Customer c = customerRepo.findById(customer.getId()).orElseThrow();
            c.setLatitude(10.025);
            c.setLongitude(76.225);
            customerRepo.save(c);
        });

        CustomerOrder order = createOrder(customer, "ORD-CONF-CANCEL", OrderStatus.CANCELLED, new BigDecimal("180.00"));

        orderService.confirmSavedLocation(order.getId(), "919000000021");

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Cancelled order coordinates must not be modified by saved location reuse");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000021"),
                eq("Order #" + order.getDisplayId() + " has been cancelled and cannot be assigned a delivery location.")
        );
    }

    @Test
    void testConfirmSavedLocation_DeliveredOrder_RejectedAndDbUnchanged() {
        Customer customer = createCustomer("CUST_CONF_DELIV", "919000000022");
        requiresNewTx.executeWithoutResult(status -> {
            Customer c = customerRepo.findById(customer.getId()).orElseThrow();
            c.setLatitude(10.025);
            c.setLongitude(76.225);
            customerRepo.save(c);
        });

        CustomerOrder order = createOrder(customer, "ORD-CONF-DELIV", OrderStatus.DELIVERED, new BigDecimal("190.00"));

        orderService.confirmSavedLocation(order.getId(), "919000000022");

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Delivered order coordinates must not be modified by saved location reuse");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000022"),
                eq("Order #" + order.getDisplayId() + " has already been delivered.")
        );
    }

    @Test
    void testConfirmSavedLocation_UnauthorizedSender_RejectedAndDbUnchanged() {
        Customer customer = createCustomer("CUST_CONF_UNAUTH", "919000000023");
        requiresNewTx.executeWithoutResult(status -> {
            Customer c = customerRepo.findById(customer.getId()).orElseThrow();
            c.setLatitude(10.025);
            c.setLongitude(76.225);
            customerRepo.save(c);
        });

        CustomerOrder order = createOrder(customer, "ORD-CONF-UNAUTH", OrderStatus.CREATED, new BigDecimal("210.00"));

        orderService.confirmSavedLocation(order.getId(), "919999999999");

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Unauthorized caller must not modify order coordinates");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919999999999"),
                eq("Unauthorized order location update.")
        );
    }

    @Test
    void testAssignLocationToOrder_TerminalOrder_CoordinatesUnchanged() {
        Customer customer = createCustomer("CUST_ASSIGN_TERM", "919000000024");
        CustomerOrder order = createOrder(customer, "ORD-ASSIGN-TERM", OrderStatus.CANCELLED, new BigDecimal("220.00"));

        orderService.assignLocationToOrder(order.getId(), "919000000024", 10.010, 76.210);

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Cancelled order coordinates must not be modified by assignLocationToOrder");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000024"),
                eq("Order #" + order.getDisplayId() + " has been cancelled and cannot be assigned a delivery location.")
        );
    }

    @Test
    void testAssignLocationToAllOrders_PersistenceFailure_RollsBackAll() {
        Customer customer = createCustomer("CUST_MULTI_FAIL", "919000000025");
        CustomerOrder order1 = createOrder(customer, "ORD-MULTI-F1", OrderStatus.CREATED, new BigDecimal("100.00"));
        CustomerOrder order2 = createOrder(customer, "ORD-MULTI-F2", OrderStatus.CREATED, new BigDecimal("200.00"));

        // Simulate persistence failure when saving order2
        doThrow(new DataIntegrityViolationException("Simulated order save error"))
                .when(orderRepo).save(argThat(o -> o != null && order2.getId().equals(o.getId())));

        assertThrows(DataIntegrityViolationException.class, () ->
                orderService.assignLocationToAllOrders(List.of(order1.getId(), order2.getId()), "919000000025", 10.010, 76.210)
        );

        // Verify neither order was updated in DB
        Double dbLat1 = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order1.getId()
        );
        Double dbLat2 = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order2.getId()
        );
        assertNull(dbLat1, "Order 1 must roll back when batch fails");
        assertNull(dbLat2, "Order 2 must roll back when batch fails");

        // Verify no success notifications sent
        verify(outboundClient, never()).sendTextUpdate(anyString(), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToAllOrders_OnlyTerminalOrders_PersistedCoordinatesAndProfileUnchanged() {
        Customer customer = createCustomer("CUST_MULTI_TERM", "919000000028");
        CustomerOrder order = createOrder(customer, "ORD-MULTI-TERM", OrderStatus.CANCELLED, new BigDecimal("100.00"));

        orderService.assignLocationToAllOrders(List.of(order.getId()), "919000000028", 10.010, 76.210);

        Double orderLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(orderLat, "Terminal order coordinates must not be updated");

        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertNull(custLat, "Customer profile must not be updated when batch updates zero orders");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000028"),
                eq("None of the selected orders are awaiting a delivery location.")
        );
        verify(outboundClient, never()).sendTextUpdate(anyString(), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToAllOrders_MissingOrderIds_PersistedStateUnchanged_SendsTruthfulMessage() {
        Customer customer = createCustomer("CUST_MULTI_MISS", "919000000029");

        orderService.assignLocationToAllOrders(List.of(999999L), "919000000029", 10.010, 76.210);

        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertNull(custLat, "Customer profile must not be updated when order IDs are nonexistent");

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000029"),
                eq("No selected orders were found awaiting a location update.")
        );
        verify(outboundClient, never()).sendTextUpdate(anyString(), eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL));
    }

    @Test
    void testAssignLocationToAllOrders_MixedBatch_UpdatesEligibleAndProfile() {
        Customer customer = createCustomer("CUST_MULTI_MIXED", "919000000030");
        CustomerOrder eligibleOrder = createOrder(customer, "ORD-MIX-ELIG", OrderStatus.CREATED, new BigDecimal("150.00"));
        CustomerOrder cancelledOrder = createOrder(customer, "ORD-MIX-CANC", OrderStatus.CANCELLED, new BigDecimal("150.00"));

        orderService.assignLocationToAllOrders(List.of(eligibleOrder.getId(), cancelledOrder.getId()), "919000000030", 10.010, 76.210);

        Double eligibleLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                eligibleOrder.getId()
        );
        assertNotNull(eligibleLat, "Eligible order must receive coordinates");
        assertEquals(10.010, eligibleLat, 0.001);

        Double cancelledLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                cancelledOrder.getId()
        );
        assertNull(cancelledLat, "Cancelled order must not receive coordinates");

        Double custLat = jdbcTemplate.queryForObject(
                "SELECT latitude FROM customers WHERE id = ?",
                Double.class,
                customer.getId()
        );
        assertNotNull(custLat, "Customer profile must be updated when at least one order was updated");
        assertEquals(10.010, custLat, 0.001);

        verify(outboundClient, times(1)).sendTextUpdate(
                eq("919000000030"),
                eq(WhatsAppConstants.LOCATION_CONFIRMED_NATURAL)
        );
    }

    @Test
    void testSequentialCancellationRejection_PreventsLocationAssignment() {
        Customer customer = createCustomer("CUST_SEQ_CANCEL", "919000000026");
        CustomerOrder order = createOrder(customer, "ORD-SEQ-CANCEL", OrderStatus.CREATED, new BigDecimal("300.00"));

        orderService.cancelOrder(order.getId(), "Customer Cancelled", CancellationActor.CUSTOMER);
        orderService.assignLocationToOrder(order.getId(), "919000000026", 10.015, 76.215);

        Double dbLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );
        assertNull(dbLat, "Order cancelled before location assignment must never receive coordinates");

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM customer_orders WHERE id = ?",
                String.class,
                order.getId()
        );
        assertEquals(OrderStatus.CANCELLED.name(), dbStatus);
    }

    @Test
    void testConcurrentCancellationAndLocationAssignment_CannotLeaveTerminalOrderWithCoordinates() throws Exception {
        Customer customer = createCustomer("CUST_CONC_CANCEL", "919000000027");
        CustomerOrder order = createOrder(customer, "ORD-CONC-CANCEL", OrderStatus.CREATED, new BigDecimal("300.00"));

        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<?> cancelFuture = executor.submit(() -> {
            try {
                readyLatch.countDown();
                assertTrue(startLatch.await(5, TimeUnit.SECONDS), "Start latch timed out");
                return orderService.cancelOrder(order.getId(), "Customer Cancelled", CancellationActor.CUSTOMER);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> locationFuture = executor.submit(() -> {
            try {
                readyLatch.countDown();
                assertTrue(startLatch.await(5, TimeUnit.SECONDS), "Start latch timed out");
                orderService.assignLocationToOrder(order.getId(), "919000000027", 10.015, 76.215);
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Workers must be ready");
        startLatch.countDown();

        // Bounded wait and exception propagation
        assertDoesNotThrow(() -> cancelFuture.get(10, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> locationFuture.get(10, TimeUnit.SECONDS));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor must terminate cleanly");

        // Fresh database read to assert final persisted state
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM customer_orders WHERE id = ?",
                String.class,
                order.getId()
        );
        assertEquals(OrderStatus.CANCELLED.name(), finalStatus, "Order must reach terminal CANCELLED state");

        Double finalLat = jdbcTemplate.queryForObject(
                "SELECT delivery_latitude FROM customer_orders WHERE id = ?",
                Double.class,
                order.getId()
        );

        // Terminal safety invariant:
        // Either cancellation won the race (finalLat remains null),
        // OR location assignment committed first while the order was still active (finalLat == 10.015),
        // and then cancellation completed.
        // Under no circumstances can a terminal order acquire newly assigned coordinates post-cancellation.
        if (finalLat != null) {
            assertEquals(10.015, finalLat, 0.001);
        }
    }
}
