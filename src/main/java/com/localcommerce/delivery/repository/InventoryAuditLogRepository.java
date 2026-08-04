package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.InventoryAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryAuditLogRepository extends JpaRepository<InventoryAuditLog, Long> {
    // We can add custom queries later, like findBySkuOrderByTimestampDesc()
}