package com.localcommerce.delivery.dto;

import com.localcommerce.delivery.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSummaryDTO {
    private Long id;
    private String displayId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime createdAt;
    private String customerName;
    private String customerPhone;
    private Integer itemCount;
    private boolean hasLocation;
    private boolean stockReconciled;
    private boolean needsAttention;
    private List<String> attentionReasons;
}
