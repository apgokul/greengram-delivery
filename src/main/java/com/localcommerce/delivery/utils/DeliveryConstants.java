package com.localcommerce.delivery.utils;

public class DeliveryConstants {
    private DeliveryConstants() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    // ==================== Delivery Fees ====================
//    public static final BigDecimal FAST_DELIVERY_FEE = new BigDecimal("30.00");
//    public static final BigDecimal EVENING_BATCH_FEE = BigDecimal.ZERO;

    // ==================== Order Format ====================
    public static final String ORDER_DISPLAY_ID_PREFIX = "ORD-";

    //Low Stock Alert Threshold
    public static final int LOW_STOCK_THRESHOLD = 5;
}
