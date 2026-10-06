package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.InventoryDTO;
import com.localcommerce.delivery.service.ProductAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventories")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final ProductAdminService adminService;

    @PutMapping("/{id}/stock")
    public ResponseEntity<?> updateStock(
            @RequestHeader(value = "X-Admin-User", defaultValue = "Unknown_Admin") String adminUser,
            @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> payload) {
        try {
            BigDecimal newStock = payload.get("stockQuantity");
            if (newStock == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "stockQuantity is required"));
            }
            InventoryDTO updated = adminService.updateInventoryStock(id, newStock, adminUser);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "inventoryId", updated.getId(),
                    "baseSku", updated.getBaseSku(),
                    "new_stock", updated.getStockQuantity(),
                    "message", "Inventory stock adjusted successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
