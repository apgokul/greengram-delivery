package com.localcommerce.delivery.dto;

import com.localcommerce.delivery.model.Product;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CartItemDTO {

    private String sku;

    private Integer quantity;
}
