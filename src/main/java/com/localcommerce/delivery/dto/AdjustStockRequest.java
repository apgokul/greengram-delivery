package com.localcommerce.delivery.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdjustStockRequest {

    @JsonAlias({"adjustmentAmount", "delta"})
    private BigDecimal adjustmentQuantity;

    private String reason;
}
