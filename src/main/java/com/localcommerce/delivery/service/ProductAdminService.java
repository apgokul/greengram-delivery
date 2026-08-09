package com.localcommerce.delivery.service;

import com.localcommerce.delivery.model.InventoryAuditLog;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.InventoryAuditLogRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductAdminService {

    private final ProductRepository productRepo;
    private final InventoryAuditLogRepository auditRepo;

    @Transactional
    public Product adjustStock(String sku, int adjustmentAmount, String reason, String adminUser) {

        // 1. Lock the row so no carts can check out while we update this!
        Product product = productRepo.findBySkuForUpdate(sku)
                .orElseThrow(() -> new RuntimeException("Product not found: " + sku));

        int oldStock = product.getStockQuantity();
        int newStock = oldStock + adjustmentAmount;

        if (newStock < 0) {
            throw new IllegalArgumentException("Cannot adjust stock below zero! Current stock: " + oldStock);
        }

        // 2. Update the product
        product.setStockQuantity(newStock);
        Product savedProduct = productRepo.save(product);

        // 3. Create the permanent audit trail
        InventoryAuditLog auditLog = new InventoryAuditLog();
        auditLog.setSku(sku);
        auditLog.setOldStock(oldStock);
        auditLog.setNewStock(newStock);
        auditLog.setChangeAmount(adjustmentAmount);
        auditLog.setReason(reason);
        auditLog.setChangedBy(adminUser);
        auditRepo.save(auditLog);

        log.info("🛡️ AUDIT: Stock for {} changed by {}. Reason: {}. User: {}",
                sku, adjustmentAmount, reason, adminUser);

        return savedProduct;
    }

    // Add this inside ProductAdminService.java

    @Transactional
    public Product createNewProduct(Product newProduct, String adminUser) {
        // 1. Prevent duplicate SKUs
        if (productRepo.findBySku(newProduct.getSku()).isPresent()) {
            throw new IllegalArgumentException("Product with SKU '" + newProduct.getSku() + "' already exists!");
        }

        // 2. Save the new product
        Product savedProduct = productRepo.save(newProduct);

        // 3. Log the initial inventory injection (if they started with stock > 0)
        if (savedProduct.getStockQuantity() != null && savedProduct.getStockQuantity() > 0) {
            InventoryAuditLog auditLog = new InventoryAuditLog();
            auditLog.setSku(savedProduct.getSku());
            auditLog.setOldStock(0);
            auditLog.setNewStock(savedProduct.getStockQuantity());
            auditLog.setChangeAmount(savedProduct.getStockQuantity());
            auditLog.setReason("INITIAL_PRODUCT_SETUP");
            auditLog.setChangedBy(adminUser);
            auditRepo.save(auditLog);
        }

        log.info("🛡️ AUDIT: New product created: {} at ₹{}. User: {}",
                savedProduct.getSku(), savedProduct.getCurrentPrice(), adminUser);

        return savedProduct;
    }

    @Transactional
    public Product updatePrice(String sku, java.math.BigDecimal newPrice, String adminUser) {
        // 1. Lock the row during price change
        Product product = productRepo.findBySkuForUpdate(sku)
                .orElseThrow(() -> new RuntimeException("Product not found: " + sku));

        java.math.BigDecimal oldPrice = product.getCurrentPrice();

        // 2. Update price
        product.setCurrentPrice(newPrice);
        Product savedProduct = productRepo.save(product);

        log.info("🛡️ AUDIT: Price for {} changed from ₹{} to ₹{}. User: {}",
                sku, oldPrice, newPrice, adminUser);

        return savedProduct;
    }

    // Add this inside ProductAdminService.java

    @Transactional
    public Product toggleActiveStatus(String sku, boolean isActive, String adminUser) {
        // 1. Lock the row
        Product product = productRepo.findBySkuForUpdate(sku)
                .orElseThrow(() -> new RuntimeException("Product not found: " + sku));

        boolean oldStatus = product.isActive();

        // 2. Update the status (Lombok generates setActive for primitive booleans)
        product.setActive(isActive);
        Product savedProduct = productRepo.save(product);

        // 3. Log the action in the audit trail
        InventoryAuditLog auditLog = new InventoryAuditLog();
        auditLog.setSku(sku);
        auditLog.setOldStock(product.getStockQuantity());
        auditLog.setNewStock(product.getStockQuantity()); // Stock didn't change
        auditLog.setChangeAmount(0);
        auditLog.setReason(isActive ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED");
        auditLog.setChangedBy(adminUser);
        auditRepo.save(auditLog);

        log.info("🛡️ AUDIT: Status for {} changed from {} to {}. User: {}",
                sku, oldStatus, isActive, adminUser);

        return savedProduct;
    }

    public List<Product> getAllProducts() { return productRepo.findAll(); }
}