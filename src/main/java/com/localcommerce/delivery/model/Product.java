package com.localcommerce.delivery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    private String sku; // e.g., "WHEAT_1KG"

    private String name;
    private BigDecimal currentPrice;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "inventory_id")
    private Inventory inventory;

    private BigDecimal packageQuantity;

    @Enumerated(EnumType.STRING)
    private ProductUnit packageUnit;



    public BigDecimal getQuantity() {
        return packageQuantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.packageQuantity = quantity;
    }

    public ProductUnit getUnit() {
        return packageUnit;
    }

    public void setUnit(ProductUnit unit) {
        this.packageUnit = unit;
    }

    // NEW: The magic bullet for concurrency. Spring manages this automatically.
    @Version
    private Long version;

    // Crucial for the "Out of Stock" logic
//    private Integer availableStock;
    private boolean isActive;

    public String getDisplayName() {
        if (packageQuantity != null && packageUnit != null) {
            String unitStr = packageUnit.name().equals("L") ? "L" : packageUnit.name().toLowerCase();
            return name + " – " + packageQuantity.stripTrailingZeros().toPlainString() + " " + unitStr;
        }
        return name;
    }
}