package com.localcommerce.delivery.enums;

public enum RiderStatus {
    OFFLINE,      // Shift is over, or they haven't logged in today
    AVAILABLE,    // Sitting near the warehouse, ready for a ping
    DELIVERING    // Currently out on a run (carrying 1 or more orders)
}