package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.dto.OrderDetailDTO;
import com.localcommerce.delivery.dto.OrderSummaryCountsDTO;
import com.localcommerce.delivery.dto.OrderSummaryDTO;
import com.localcommerce.delivery.dto.PageResponseDTO;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.service.OrderAdminService;
import com.localcommerce.delivery.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Mock
    private OrderAdminService orderAdminService;

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

    @Test
    void testGetOrders_Success_Returns200WithPagedEnvelope() throws Exception {
        OrderSummaryDTO summary = OrderSummaryDTO.builder()
                .id(1042L)
                .displayId("ORD-1042")
                .status(OrderStatus.PENDING_WAREHOUSE)
                .totalAmount(new BigDecimal("350.00"))
                .createdAt(LocalDateTime.of(2026, 10, 22, 10, 0))
                .customerName("Alice")
                .customerPhone("919876543210")
                .itemCount(3)
                .hasLocation(true)
                .stockReconciled(true)
                .needsAttention(false)
                .attentionReasons(Collections.emptyList())
                .build();

        PageResponseDTO<OrderSummaryDTO> pageResponse = PageResponseDTO.<OrderSummaryDTO>builder()
                .content(List.of(summary))
                .page(0)
                .size(20)
                .totalElements(1L)
                .totalPages(1)
                .last(true)
                .build();

        when(orderAdminService.getOrders(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/admin/orders")
                        .param("page", "0")
                        .param("size", "20")
                        .param("search", "ORD-1042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1042))
                .andExpect(jsonPath("$.content[0].displayId").value("ORD-1042"))
                .andExpect(jsonPath("$.content[0].customerName").value("Alice"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void testGetOrders_InvalidSort_Returns400() throws Exception {
        when(orderAdminService.getOrders(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), eq("bad_prop,desc")))
                .thenThrow(new IllegalArgumentException("Invalid sort property: bad_prop"));

        mockMvc.perform(get("/api/admin/orders")
                        .param("sort", "bad_prop,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid sort property: bad_prop"));
    }

    @Test
    void testGetOrders_InvalidDateRange_Returns400() throws Exception {
        when(orderAdminService.getOrders(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("startDate cannot be after endDate"));

        mockMvc.perform(get("/api/admin/orders")
                        .param("startDate", "2026-10-25")
                        .param("endDate", "2026-10-20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("startDate cannot be after endDate"));
    }

    @Test
    void testGetOrderById_Success_Returns200WithDetail() throws Exception {
        OrderDetailDTO detail = OrderDetailDTO.builder()
                .id(1042L)
                .displayId("ORD-1042")
                .status(OrderStatus.READY)
                .totalAmount(new BigDecimal("450.00"))
                .createdAt(LocalDateTime.of(2026, 10, 22, 10, 0))
                .readyAt(LocalDateTime.of(2026, 10, 22, 10, 45))
                .stockReconciled(true)
                .needsAttention(false)
                .attentionReasons(Collections.emptyList())
                .customer(OrderDetailDTO.CustomerInfo.builder()
                        .name("Bob")
                        .whatsappNumber("919999999999")
                        .build())
                .delivery(OrderDetailDTO.DeliveryInfo.builder()
                        .hasLocation(true)
                        .latitude(12.97)
                        .longitude(77.59)
                        .googleMapsUrl("https://www.google.com/maps?q=12.97,77.59")
                        .build())
                .items(Collections.emptyList())
                .timeline(Collections.emptyList())
                .build();

        when(orderAdminService.getOrderDetails(1042L)).thenReturn(detail);

        mockMvc.perform(get("/api/admin/orders/1042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1042))
                .andExpect(jsonPath("$.displayId").value("ORD-1042"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.delivery.hasLocation").value(true))
                .andExpect(jsonPath("$.delivery.googleMapsUrl").value("https://www.google.com/maps?q=12.97,77.59"));
    }

    @Test
    void testGetOrderById_NotFound_Returns404() throws Exception {
        when(orderAdminService.getOrderDetails(9999L))
                .thenThrow(new EntityNotFoundException("Order not found: 9999"));

        mockMvc.perform(get("/api/admin/orders/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Order not found: 9999"));
    }

    @Test
    void testGetOrderSummary_Returns200WithCounts() throws Exception {
        OrderSummaryCountsDTO counts = OrderSummaryCountsDTO.builder()
                .createdToday(12L)
                .inProgress(5L)
                .deliveredToday(7L)
                .needsAttention(1L)
                .build();

        when(orderAdminService.getSummaryCounts()).thenReturn(counts);

        mockMvc.perform(get("/api/admin/orders/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdToday").value(12))
                .andExpect(jsonPath("$.inProgress").value(5))
                .andExpect(jsonPath("$.deliveredToday").value(7))
                .andExpect(jsonPath("$.needsAttention").value(1));
    }
}
