package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CustomerOrderRepository orderRepo;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private AdminOrderController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testCancelOrderAsAdmin_Success_Returns200() throws Exception {
        when(orderService.cancelOrder(1042L, "Custom Reason", CancellationActor.ADMIN))
                .thenReturn(CancellationResult.success(null, CancellationActor.ADMIN, "Custom Reason"));

        mockMvc.perform(post("/api/admin/orders/1042/cancel")
                        .header("X-Admin-User", "SuperAdmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Custom Reason\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.orderId").value(1042))
                .andExpect(jsonPath("$.message").value("Order successfully cancelled by administrator."));

        verify(orderService).cancelOrder(1042L, "Custom Reason", CancellationActor.ADMIN);
    }

    @Test
    void testCancelOrderAsAdmin_DeliveredOrder_Returns400() throws Exception {
        when(orderService.cancelOrder(1042L, "Administrative cancellation by SuperAdmin", CancellationActor.ADMIN))
                .thenReturn(CancellationResult.alreadyDelivered(null, CancellationActor.ADMIN));

        mockMvc.perform(post("/api/admin/orders/1042/cancel")
                        .principal(() -> "SuperAdmin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This order has already been delivered and cannot be cancelled."))
                .andExpect(jsonPath("$.status").value("ALREADY_DELIVERED"));
    }

    @Test
    void testCancelOrderAsAdmin_AuthenticatedPrincipalOverridesSpoofedHeader() throws Exception {
        when(orderService.cancelOrder(1042L, "Administrative cancellation by alice", CancellationActor.ADMIN))
                .thenReturn(CancellationResult.success(null, CancellationActor.ADMIN, "Administrative cancellation by alice"));

        mockMvc.perform(post("/api/admin/orders/1042/cancel")
                        .principal(() -> "alice")
                        .header("X-Admin-User", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderService).cancelOrder(1042L, "Administrative cancellation by alice", CancellationActor.ADMIN);
    }

    @Test
    void testCancelOrderAsAdmin_NotFound_Returns404() throws Exception {
        when(orderService.cancelOrder(9999L, "Administrative cancellation by Unknown_Admin", CancellationActor.ADMIN))
                .thenReturn(CancellationResult.notFound(9999L, CancellationActor.ADMIN));

        mockMvc.perform(post("/api/admin/orders/9999/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Order not found: 9999"));
    }
}
