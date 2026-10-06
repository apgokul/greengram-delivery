package com.localcommerce.delivery.service;

import com.localcommerce.delivery.model.InventoryAuditLog;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.dto.InventoryDTO;
import com.localcommerce.delivery.dto.ProductDTO;
import com.localcommerce.delivery.model.Inventory;
import com.localcommerce.delivery.repository.InventoryRepository;
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
    private final InventoryRepository inventoryRepo;
    private final InventoryAuditLogRepository auditRepo;

    private InventoryDTO convertToInventoryDTO(Inventory inventory) {
        if (inventory == null) return null;
        return new InventoryDTO(
                inventory.getId(),
                inventory.getBaseSku(),
                inventory.getStockQuantity(),
                inventory.getUnit()
        );
    }

    private ProductDTO convertToProductDTO(Product product) {
        if (product == null) return null;
        return new ProductDTO(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getCurrentPrice(),
                product.getPackageQuantity(),
                product.getPackageUnit(),
                product.isActive(),
                convertToInventoryDTO(product.getInventory())
        );
    }

    @Transactional
    public InventoryDTO updateInventoryStock(Long inventoryId, java.math.BigDecimal newStock, String adminUser) {
        Inventory inventory = inventoryRepo.findById(inventoryId)
                .orElseThrow(() -> new RuntimeException("Inventory not found with id: " + inventoryId));

        java.math.BigDecimal oldStock = inventory.getStockQuantity() != null ? inventory.getStockQuantity() : java.math.BigDecimal.ZERO;

        if (newStock.compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Cannot adjust stock below zero! Current stock: " + oldStock);
        }

        inventory.setStockQuantity(newStock);
        Inventory savedInventory = inventoryRepo.save(inventory);

        InventoryAuditLog auditLog = new InventoryAuditLog();
        auditLog.setSku(inventory.getBaseSku()); // Audit log currently tracks SKU. Let's use baseSku for inventory.
        auditLog.setOldStock(oldStock.intValue()); // Note: keeping intValue() as per existing audit log logic, or should update schema. We'll leave as intValue() for now to avoid breaking DB.
        auditLog.setNewStock(newStock.intValue());
        auditLog.setChangeAmount(newStock.subtract(oldStock).intValue());
        auditLog.setReason("INVENTORY_UPDATE");
        auditLog.setChangedBy(adminUser);
        auditRepo.save(auditLog);

        log.info("🛡️ AUDIT: Inventory {} stock changed from {} to {}. User: {}",
                inventory.getBaseSku(), oldStock, newStock, adminUser);

        return convertToInventoryDTO(savedInventory);
    }

    // Add this inside ProductAdminService.java

    @Transactional
    public ProductDTO createNewProduct(Product newProduct, String adminUser) {
        // 1. Prevent duplicate SKUs
        if (productRepo.findBySku(newProduct.getSku()).isPresent()) {
            throw new IllegalArgumentException("Product with SKU '" + newProduct.getSku() + "' already exists!");
        }

        // 2. Save the new product
        Product savedProduct = productRepo.save(newProduct);

        // 3. Log the initial inventory injection (if they started with stock > 0)
        if (savedProduct.getInventory() != null && savedProduct.getInventory().getStockQuantity() != null && savedProduct.getInventory().getStockQuantity().compareTo(java.math.BigDecimal.ZERO) > 0) {
            InventoryAuditLog auditLog = new InventoryAuditLog();
            auditLog.setSku(savedProduct.getSku());
            auditLog.setOldStock(0);
            auditLog.setNewStock(savedProduct.getInventory().getStockQuantity().intValue());
            auditLog.setChangeAmount(savedProduct.getInventory().getStockQuantity().intValue());
            auditLog.setReason("INITIAL_PRODUCT_SETUP");
            auditLog.setChangedBy(adminUser);
            auditRepo.save(auditLog);
        }

        log.info("🛡️ AUDIT: New product created: {} at ₹{}. User: {}",
                savedProduct.getSku(), savedProduct.getCurrentPrice(), adminUser);

        return convertToProductDTO(savedProduct);
    }

    @Transactional
    public ProductDTO updatePrice(String sku, java.math.BigDecimal newPrice, String adminUser) {
        // 1. Lock the row during price change
        Product product = productRepo.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found: " + sku));

        java.math.BigDecimal oldPrice = product.getCurrentPrice();

        // 2. Update price
        product.setCurrentPrice(newPrice);
        Product savedProduct = productRepo.save(product);

        log.info("🛡️ AUDIT: Price for {} changed from ₹{} to ₹{}. User: {}",
                sku, oldPrice, newPrice, adminUser);

        return convertToProductDTO(savedProduct);
    }

    // Add this inside ProductAdminService.java

    @Transactional
    public ProductDTO toggleActiveStatus(String sku, boolean isActive, String adminUser) {
        // 1. Lock the row
        Product product = productRepo.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Product not found: " + sku));

        boolean oldStatus = product.isActive();

        // 2. Update the status (Lombok generates setActive for primitive booleans)
        product.setActive(isActive);
        Product savedProduct = productRepo.save(product);

        // 3. Log the action in the audit trail
        InventoryAuditLog auditLog = new InventoryAuditLog();
        auditLog.setSku(sku);
        auditLog.setOldStock(product.getInventory() != null && product.getInventory().getStockQuantity() != null ? product.getInventory().getStockQuantity().intValue() : 0);
        auditLog.setNewStock(product.getInventory() != null && product.getInventory().getStockQuantity() != null ? product.getInventory().getStockQuantity().intValue() : 0);
        auditLog.setChangeAmount(0);
        auditLog.setReason(isActive ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED");
        auditLog.setChangedBy(adminUser);
        auditRepo.save(auditLog);

        log.info("🛡️ AUDIT: Status for {} changed from {} to {}. User: {}",
                sku, oldStatus, isActive, adminUser);

        return convertToProductDTO(savedProduct);
    }

    public List<ProductDTO> getAllProducts() { 
        return productRepo.findAll().stream().map(this::convertToProductDTO).toList(); 
    }
}