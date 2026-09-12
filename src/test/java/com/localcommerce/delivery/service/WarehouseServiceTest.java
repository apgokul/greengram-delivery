package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock
    private CustomerOrderRepository orderRepo;
    
    @Mock
    private WhatsAppOutboundClient outboundClient;
    
    @Mock
    private ProductRepository productRepo;
    
    @Mock
    private WarehouseAlertService warehouseAlertService;

    @InjectMocks
    private WarehouseService warehouseService;

    private final String warehousePhoneNumber = "919999999999";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(warehouseService, "warehousePhoneNumber", warehousePhoneNumber);
    }

    @Test
    void testSendPendingOrdersInbox_hasOrders() {
        CustomerOrder order = new CustomerOrder();
        order.setId(1L);
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        
        when(orderRepo.findByStatus(OrderStatus.PENDING_WAREHOUSE)).thenReturn(List.of(order));

        warehouseService.sendPendingOrdersInbox(warehousePhoneNumber);

        verify(outboundClient).sendPendingOrdersList(eq(warehousePhoneNumber), anyList());
        verify(outboundClient, never()).sendTextUpdate(anyString(), anyString());
    }

    @Test
    void testSendPendingOrdersInbox_noOrders() {
        when(orderRepo.findByStatus(OrderStatus.PENDING_WAREHOUSE)).thenReturn(Collections.emptyList());

        warehouseService.sendPendingOrdersInbox(warehousePhoneNumber);

        verify(outboundClient).sendTextUpdate(warehousePhoneNumber, "No pending warehouse orders.");
        verify(outboundClient, never()).sendPendingOrdersList(anyString(), anyList());
    }

    @Test
    void testSendPendingOrdersInbox_unauthorized() {
        warehouseService.sendPendingOrdersInbox("918888888888");

        verifyNoInteractions(orderRepo);
        verifyNoInteractions(outboundClient);
    }

    @Test
    void testProcessListReply_validPendingOrder() {
        CustomerOrder order = new CustomerOrder();
        order.setId(1042L);
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);

        when(orderRepo.findById(1042L)).thenReturn(Optional.of(order));

        warehouseService.processListReply(warehousePhoneNumber, "VIEW_ORDER_1042");

        verify(warehouseAlertService).sendNewOrderAlert(order);
        verify(outboundClient, never()).sendTextUpdate(anyString(), anyString());
    }

    @Test
    void testProcessListReply_orderNoLongerPending() {
        CustomerOrder order = new CustomerOrder();
        order.setId(1042L);
        order.setStatus(OrderStatus.PACKING); // Changed state
        // Simulate getDisplayId() logic
        ReflectionTestUtils.setField(order, "id", 1042L);

        when(orderRepo.findById(1042L)).thenReturn(Optional.of(order));

        warehouseService.processListReply(warehousePhoneNumber, "VIEW_ORDER_1042");

        verify(outboundClient).sendTextUpdate(eq(warehousePhoneNumber), contains("no longer pending"));
        verifyNoInteractions(warehouseAlertService);
    }

    @Test
    void testProcessListReply_unauthorized() {
        warehouseService.processListReply("918888888888", "VIEW_ORDER_1042");

        verifyNoInteractions(orderRepo);
        verifyNoInteractions(warehouseAlertService);
    }
}
