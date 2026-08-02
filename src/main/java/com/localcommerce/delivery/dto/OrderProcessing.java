package com.localcommerce.delivery.dto;

import com.localcommerce.delivery.model.CustomerOrder;
import lombok.AllArgsConstructor;
import lombok.Getter;

public class OrderProcessing {

    @Getter
    @AllArgsConstructor
    public static class OrderProcessResult {
        private boolean success;
        private String message;
        private CustomerOrder order;

        public static OrderProcessResult failed(String message) {
            return new OrderProcessResult(false, message, null);
        }

        public static OrderProcessResult success(CustomerOrder order) {
            return new OrderProcessResult(true, "Order processed successfully", order);
        }
    }
}