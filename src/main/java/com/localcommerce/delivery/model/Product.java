package com.localcommerce.delivery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sku; // e.g., "RICE_ASHIRVAD_5KG"

    private String name;
    private BigDecimal currentPrice;

    @Column(nullable = false)
    private Integer stockQuantity;

    // NEW: The magic bullet for concurrency. Spring manages this automatically.
    @Version
    private Long version;

    // Crucial for the "Out of Stock" logic
    private Integer availableStock;
    private boolean isActive;
}