package com.localcommerce.delivery.dto;

import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.model.CustomerOrder;

/**
 * Encapsulates the typed outcome of an order cancellation or edit attempt.
 * Enables actor-appropriate feedback for valid and invalid cancellation attempts
 * without relying on broad exception handling.
 */
public record CancellationResult(
        Status status,
        String message,
        CustomerOrder order,
        CancellationActor actor,
        String reason
) {
    public enum Status {
        SUCCESS,
        ALREADY_CANCELLED,
        ALREADY_DELIVERED,
        NOT_ALLOWED_IN_CURRENT_STATE,
        ORDER_NOT_FOUND
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static CancellationResult success(CustomerOrder order, CancellationActor actor, String reason) {
        return new CancellationResult(Status.SUCCESS, "Order successfully cancelled.", order, actor, reason);
    }

    public static CancellationResult alreadyCancelled(CustomerOrder order, CancellationActor actor) {
        return new CancellationResult(Status.ALREADY_CANCELLED, "This order is already cancelled.", order, actor, null);
    }

    public static CancellationResult alreadyDelivered(CustomerOrder order, CancellationActor actor) {
        return new CancellationResult(Status.ALREADY_DELIVERED, "This order has already been delivered and cannot be cancelled.", order, actor, null);
    }

    public static CancellationResult notAllowed(String message, CustomerOrder order, CancellationActor actor) {
        return new CancellationResult(Status.NOT_ALLOWED_IN_CURRENT_STATE, message, order, actor, null);
    }

    public static CancellationResult notFound(Long orderId, CancellationActor actor) {
        return new CancellationResult(Status.ORDER_NOT_FOUND, "Order not found: " + orderId, null, actor, null);
    }
}
