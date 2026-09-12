package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.DeliveryRun;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_deliveryrun;DB_CLOSE_DELAY=-1",
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
        "authorized.rider.numbers=919999999999",
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

    @Autowired
    private DeliveryRunRepository deliveryRepo;

    @Autowired
    private CustomerOrderRepository orderRepo;

    @Autowired
    private CustomerRepository customerRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WhatsAppOutboundClient outboundClient;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setWhatsappNumber("918888888888");
        customer = customerRepo.save(customer);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM order_items");
        orderRepo.deleteAll();
        deliveryRepo.deleteAll();
        customerRepo.deleteAll();
    }

    private CustomerOrder createOrderInState(OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(status);
        order.setTotalAmount(java.math.BigDecimal.TEN);
        return orderRepo.save(order);
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
        assertEquals(DeliveryRun.RunStatus.COMPLETED, updatedRun.getStatus(), "Run must transition to COMPLETED");
    }

    @Test
    void testD_SingleOrderRun_TransitionsToCompleted() {
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run = deliveryRepo.save(run);

        CustomerOrder order1 = createOrderInState(OrderStatus.DISPATCHED);
        order1.setDeliveryRun(run);
        orderRepo.save(order1);

        orderService.completeDelivery(order1.getId());

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
}
