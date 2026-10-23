package com.localcommerce.delivery.dto;

import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.DeliveryRun;
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
public class OrderDetailDTO {
    private Long id;
    private String displayId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime createdAt;
    private LocalDateTime readyAt;
    private LocalDateTime deliveredAt;
    private String cancellationReason;
    private boolean stockReconciled;
    private boolean needsAttention;
    private List<String> attentionReasons;
    private CustomerInfo customer;
    private DeliveryInfo delivery;
    private DeliveryRunInfo deliveryRun;
    private List<OrderItemDetail> items;
    private List<TimelineEvent> timeline;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CustomerInfo {
        private Long id;
        private String name;
        private String whatsappNumber;
        private String addressNotes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeliveryInfo {
        private boolean hasLocation;
        private Double latitude;
        private Double longitude;
        private String googleMapsUrl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeliveryRunInfo {
        private Long runId;
        private String riderPhoneNumber;
        private LocalDateTime dispatchedAt;
        private DeliveryRun.RunStatus status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItemDetail {
        private Long id;
        private String productSku;
        private String productName;
        private BigDecimal packageQuantity;
        private String packageUnit;
        private Integer quantity;
        private BigDecimal lockedPrice;
        private BigDecimal lineTotal;
        private BigDecimal deductedQuantity;
        private String deductedUnit;
        private boolean stockReconciliationRequired;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimelineEvent {
        private String milestone;
        private String title;
        private LocalDateTime timestamp;
        private boolean recorded;
        private String status; // COMPLETED, PENDING, SKIPPED
        private String detail;
    }
}
