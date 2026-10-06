package com.localcommerce.delivery.dto;

import java.math.BigDecimal;
import com.localcommerce.delivery.model.ProductUnit;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryDTO {
    private Long id;
    private String baseSku;
    private BigDecimal stockQuantity;
    private ProductUnit unit;
}
