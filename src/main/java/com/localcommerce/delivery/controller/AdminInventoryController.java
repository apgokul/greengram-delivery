package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.AdjustStockRequest;
import com.localcommerce.delivery.dto.InventoryDTO;
import com.localcommerce.delivery.service.ProductAdminService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventories")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final ProductAdminService adminService;

    @GetMapping
    public ResponseEntity<?> getAllInventories() {
        return ResponseEntity.ok(adminService.getAllInventories());
    }

    @PostMapping
    public ResponseEntity<?> createInventory(
            Principal principal,
            @RequestBody InventoryDTO payload) {
        String adminUser = resolveAdminUser(principal);
        try {
            InventoryDTO created = adminService.createInventory(payload, adminUser);
            return ResponseEntity.status(201).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/stock")
    public ResponseEntity<?> updateStock(
            Principal principal,
            @PathVariable Long id,
            @RequestBody AdjustStockRequest payload) {
        String adminUser = resolveAdminUser(principal);
        try {
            if (payload == null || payload.getAdjustmentQuantity() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "adjustmentQuantity is required"));
            }
            InventoryDTO updated = adminService.adjustInventoryStock(
                    id,
                    payload.getAdjustmentQuantity(),
                    payload.getReason(),
                    adminUser
            );
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "inventoryId", updated.getId(),
                    "baseSku", updated.getBaseSku(),
                    "new_stock", updated.getStockQuantity(),
                    "message", "Inventory stock adjusted successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    private String resolveAdminUser(Principal principal) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return "Unknown_Admin";
    }
}
