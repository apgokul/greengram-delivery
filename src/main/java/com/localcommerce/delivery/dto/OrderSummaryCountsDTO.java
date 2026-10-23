package com.localcommerce.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSummaryCountsDTO {
    private long createdToday;
    private long inProgress;
    private long deliveredToday;
    private long needsAttention;
}
