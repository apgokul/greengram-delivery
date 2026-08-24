package com.localcommerce.delivery.enums;

public enum OrderStatus {
    CREATED,      // 1. Customer just checked out via WhatsApp
    PENDING_WAREHOUSE,     // 2. Warehouse received and verified it
    PACKING,      // 3. Warehouse is physically putting items in the bag
    READY,        // 4. Bag is sealed, stapled, and waiting on the dispatch rack
    DISPATCHED,   // 5. Rider has picked it up and is on the bike
    DELIVERED,    // 6. Handed over to the customer
    CANCELLED     // 7. Used if it's a fake order or total stock failure
}