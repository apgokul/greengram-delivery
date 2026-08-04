package com.localcommerce.delivery.service;

import com.localcommerce.delivery.model.InventoryAuditLog;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.InventoryAuditLogRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}