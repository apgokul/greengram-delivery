package com.localcommerce.delivery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_audit_logs")
@Getter
@Setter
public class InventoryAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false, precision = 38, scale = 3)
    private java.math.BigDecimal oldStock;

    @Column(nullable = false, precision = 38, scale = 3)
    private java.math.BigDecimal newStock;

    @Column(nullable = false, precision = 38, scale = 3)
    private java.math.BigDecimal changeAmount;

    @Column(nullable = false)
    private String reason; // e.g., "RESTOCK", "MANUAL_CORRECTION", "SPOILED"

    @Column(nullable = false)
    private String changedBy; // e.g., "Admin_API", "Warehouse_Manager"

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
