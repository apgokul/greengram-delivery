package com.localcommerce.delivery.enums;

/**
 * Identifies the actor initiating an order cancellation to enforce
 * strict state-transition and inventory-restoration business rules.
 */
public enum CancellationActor {
    CUSTOMER,
    WAREHOUSE,
    RIDER,
    ADMIN
}
