package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.service.ProductAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductAdminService adminService;

    // --- 1. CREATE A NEW PRODUCT ---
    @PostMapping
    public ResponseEntity<?> createProduct(
            Principal principal,
            @RequestBody com.localcommerce.delivery.dto.CreateProductDTO product) {

        String adminUser = resolveAdminUser(principal);
        try {
            com.localcommerce.delivery.dto.ProductDTO createdProduct = adminService.createNewProduct(product, adminUser);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdProduct);
        } catch (IllegalArgumentException e) {
            // Catches the duplicate SKU error
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // --- 2. CHANGE PRODUCT PRICE ---
    @PatchMapping("/{sku}/price")
    public ResponseEntity<?> updatePrice(
            Principal principal,
            @PathVariable String sku,
            @RequestParam java.math.BigDecimal newPrice) {

        String adminUser = resolveAdminUser(principal);
        try {
            com.localcommerce.delivery.dto.ProductDTO updatedProduct = adminService.updatePrice(sku, newPrice, adminUser);

            return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "sku", updatedProduct.getSku(),
                    "new_price", updatedProduct.getCurrentPrice(),
                    "message", "Price updated successfully"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("error", e.getMessage()));
        }
    }
    // Add this inside AdminProductController.java

    // Toggle active Status
    @PatchMapping("/{sku}/toggle-active")
    public ResponseEntity<?> toggleActiveStatus(
            Principal principal,
            @PathVariable String sku,
            @RequestParam boolean isActive) {

        String adminUser = resolveAdminUser(principal);
        try {
            com.localcommerce.delivery.dto.ProductDTO updatedProduct = adminService.toggleActiveStatus(sku, isActive, adminUser);

            return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "sku", updatedProduct.getSku(),
                    "is_active", updatedProduct.isActive(),
                    "message", "Product status updated to " + (isActive ? "Active" : "Hidden")
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getAllProducts() {

        // Assuming you have a standard findAll() in your ProductRepository
        return ResponseEntity.ok(adminService.getAllProducts());
    }

    private String resolveAdminUser(Principal principal) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return "Unknown_Admin";
    }
}