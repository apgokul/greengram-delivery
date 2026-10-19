package com.localcommerce.delivery.model;


import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private CustomerOrder customerOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    private Integer quantity;

    // The price of the product AT THE TIME of the order
    private BigDecimal lockedPrice;

    // Snapshot of the exact quantity deducted from inventory at checkout confirmation
    @jakarta.persistence.Column(name = "deducted_quantity", precision = 38, scale = 3)
    private BigDecimal deductedQuantity;

    // Flags legacy items where packaging/quantity was uncertain at cancellation
    @jakarta.persistence.Column(name = "stock_reconciliation_required", nullable = false)
    private boolean stockReconciliationRequired = false;
}