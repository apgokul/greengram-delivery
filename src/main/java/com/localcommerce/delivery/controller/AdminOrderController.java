package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@Slf4j
public class AdminOrderController {

    private final CustomerOrderRepository orderRepo;
    private final OrderService orderService;

    /**
     * GET /api/admin/orders/reconciliation-needed
     * Returns orders where stock reconciliation is required following cancellation of legacy items.
     */
    @GetMapping("/reconciliation-needed")
    public ResponseEntity<List<Map<String, Object>>> getOrdersRequiringReconciliation(
            @RequestHeader(value = "X-Admin-User", defaultValue = "Unknown_Admin") String adminUser) {
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
            @RequestHeader(value = "X-Admin-User", defaultValue = "Unknown_Admin") String adminUser,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> payload) {
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
}
