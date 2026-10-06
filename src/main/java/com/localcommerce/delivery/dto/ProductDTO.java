package com.localcommerce.delivery.dto;

import java.math.BigDecimal;
import com.localcommerce.delivery.model.ProductUnit;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductDTO {
    private Long id;
    private String sku;
    private String name;
    private BigDecimal currentPrice;
    private BigDecimal packageQuantity;
    private ProductUnit packageUnit;
    private boolean isActive;
    private InventoryDTO inventory;
}
