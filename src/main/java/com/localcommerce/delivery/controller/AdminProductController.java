package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.service.ProductAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductAdminService adminService;

    // Pulls the secret key from your application.properties
    @Value("${admin.api.key}")
    private String validApiKey;

    // Helper method to validate the request
    private boolean isUnauthorized(String apiKey) {
        return apiKey == null || !apiKey.equals(validApiKey);
    }

    @PatchMapping("/{sku}/stock")
    public ResponseEntity<?> adjustStock(
            @RequestHeader(value = "X-Admin-API-Key", required = false) String apiKey,
            @PathVariable String sku,
            @RequestParam int adjustmentAmount,
            @RequestParam String reason) {

        // 1. Security Check
        if (isUnauthorized(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized: Invalid or missing API Key"));
        }

        // 2. Execute Business Logic
        try {
            // "Admin_API" is hardcoded here as the actor, but later you can pass specific user names
            Product updatedProduct = adminService.adjustStock(sku, adjustmentAmount, reason, "Admin_API");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sku", updatedProduct.getSku(),
                    "new_stock", updatedProduct.getStockQuantity(),
                    "message", "Stock adjusted successfully"
            ));

        } catch (IllegalArgumentException e) {
            // Catches the "stock cannot be negative" rule we wrote
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Catches "Product not found"
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }
}