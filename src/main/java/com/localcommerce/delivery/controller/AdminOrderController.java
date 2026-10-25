package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.dto.OrderDetailDTO;
import com.localcommerce.delivery.dto.OrderSummaryCountsDTO;
import com.localcommerce.delivery.dto.OrderSummaryDTO;
import com.localcommerce.delivery.dto.PageResponseDTO;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.service.OrderAdminService;
import com.localcommerce.delivery.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@Slf4j
public class AdminOrderController {

    private final CustomerOrderRepository orderRepo;
    private final OrderService orderService;
    private final OrderAdminService orderAdminService;

    /**
     * GET /api/admin/orders
     * Returns paginated, searchable, filterable order summaries.
     */
    @GetMapping
    public ResponseEntity<?> getOrders(
            Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Boolean hasLocation,
            @RequestParam(required = false) Boolean needsAttention,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        String adminUser = resolveAdminUser(principal);
        log.debug("Admin {} fetching orders page={}, size={}, sort={}", adminUser, page, size, sort);
        try {
            PageResponseDTO<OrderSummaryDTO> response = orderAdminService.getOrders(
                    page, size, search, status, startDate, endDate, hasLocation, needsAttention, sort
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/orders/summary
     * Returns operational summary KPI counts for today in Asia/Kolkata.
     */
    @GetMapping("/summary")
    public ResponseEntity<OrderSummaryCountsDTO> getOrderSummary(Principal principal) {
        String adminUser = resolveAdminUser(principal);
        log.debug("Admin {} fetching order summary counts", adminUser);
        OrderSummaryCountsDTO counts = orderAdminService.getSummaryCounts();
        return ResponseEntity.ok(counts);
    }

    /**
     * GET /api/admin/orders/{id}
     * Returns comprehensive read-only details for a single order by numeric ID.
     */
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<?> getOrderById(Principal principal, @PathVariable Long id) {
        String adminUser = resolveAdminUser(principal);
        log.debug("Admin {} fetching order details for ID: {}", adminUser, id);
        try {
            OrderDetailDTO detail = orderAdminService.getOrderDetails(id);
            return ResponseEntity.ok(detail);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/orders/reconciliation-needed
     * Returns orders where stock reconciliation is required following cancellation of legacy items.
     */
    @GetMapping("/reconciliation-needed")
    public ResponseEntity<List<Map<String, Object>>> getOrdersRequiringReconciliation(
            Principal principal) {
        String adminUser = resolveAdminUser(principal);
        log.info("Admin {} fetching orders requiring stock reconciliation", adminUser);

        List<CustomerOrder> discrepancyOrders = orderRepo.findByStockReconciledFalse();

        List<Map<String, Object>> response = discrepancyOrders.stream()
                .map(o -> Map.<String, Object>of(
                        "id", o.getId(),
                        "displayId", o.getDisplayId(),
                        "customerPhone", o.getCustomer() != null ? o.getCustomer().getWhatsappNumber() : "Unknown",
                        "status", o.getStatus().name(),
                        "totalAmount", o.getTotalAmount(),
                        "cancellationReason", o.getCancellationReason() != null ? o.getCancellationReason() : "",
                        "stockReconciled", o.isStockReconciled(),
                        "itemsRequiringReconciliation", o.getItems().stream()
                                .filter(item -> item.isStockReconciliationRequired() || item.getDeductedQuantity() == null)
                                .map(item -> Map.of(
                                        "itemId", item.getId(),
                                        "productSku", item.getProduct() != null ? item.getProduct().getSku() : "Unknown",
                                        "productName", item.getProduct() != null ? item.getProduct().getName() : "Unknown",
                                        "quantity", item.getQuantity() != null ? item.getQuantity() : 0,
                                        "deductedQuantity", item.getDeductedQuantity() != null ? item.getDeductedQuantity() : "MISSING_SNAPSHOT"
                                ))
                                .toList()
                ))
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/admin/orders/{id}/cancel
     * Allows staff to execute administrative cancellation with exact stock restoration.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrderAsAdmin(
            Principal principal,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> payload) {
        String adminUser = resolveAdminUser(principal);
        String reason = (payload != null && payload.containsKey("reason"))
                ? payload.get("reason")
                : "Administrative cancellation by " + adminUser;

        log.info("Admin {} cancelling order {} with reason: {}", adminUser, id, reason);
        try {
            CancellationResult result = orderService.cancelOrder(id, reason, CancellationActor.ADMIN);
            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "orderId", id,
                        "message", "Order successfully cancelled by administrator."
                ));
            } else if (result.status() == CancellationResult.Status.ORDER_NOT_FOUND) {
                return ResponseEntity.status(404).body(Map.of("error", result.message()));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", result.message(),
                        "status", result.status().name()
                ));
            }
        } catch (Exception e) {
            log.error("Unexpected error during admin cancellation of order {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private String resolveAdminUser(Principal principal) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return "Unknown_Admin";
    }
}
