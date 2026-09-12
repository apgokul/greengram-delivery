package com.localcommerce.delivery.utils;

public class WhatsAppConstants {
    private WhatsAppConstants() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    // ==================== Button Prefixes ====================
    // Customer interaction button prefixes
    public static final String REUSE_LOCATION = "REUSE_LOC_";
    public static final String NEW_LOCATION = "NEW_LOC_";


    // Warehouse action button prefixes
    public static final String ACCEPT_ORDER = "ACCEPT_";
    public static final String READY_FOR_PICKUP = "READY_";
    public static final String STOCK_ISSUE = "STOCK_ISSUE_";
    public static final String SET_DELIVERED = "DELIVERED_";
    public static final String VIEW_ORDER = "VIEW_ORDER_";

    // ==================== Payload Keys ====================
    // Messaging product type
    public static final String MESSAGING_PRODUCT = "messaging_product";
    public static final String WHATSAPP = "whatsapp";

    // Message types
    public static final String MESSAGE_TYPE_INTERACTIVE = "interactive";
    public static final String MESSAGE_TYPE_TEXT = "text";
    public static final String MESSAGE_TYPE_BUTTON = "button";
    public static final String MESSAGE_TYPE_LIST = "list";
    public static final String MESSAGE_TYPE_REPLY = "reply";
    public static final String MESSAGE_TYPE_ORDER = "order";
    public static final String MESSAGE_TYPE_LOCATION = "location";

    // Payload structure keys
    public static final String TO = "to";
    public static final String TYPE = "type";
    public static final String INTERACTIVE = "interactive";
    public static final String BODY = "body";
    public static final String TEXT = "text";
    public static final String ACTION = "action";
    public static final String BUTTONS = "buttons";
    public static final String REPLY = "reply";
    public static final String ID = "id";
    public static final String TITLE = "title";
    public static final String HEADER = "header";
    public static final String SECTIONS = "sections";
    public static final String ROWS = "rows";
    public static final String DESCRIPTION = "description";
    public static final String BUTTON = "button";

    // Webhook parameter names
    public static final String HUB_MODE = "hub.mode";
    public static final String HUB_VERIFY_TOKEN = "hub.verify_token";
    public static final String HUB_CHALLENGE = "hub.challenge";
    public static final String WEBHOOK_MODE_SUBSCRIBE = "subscribe";

    // ==================== Message Templates ====================
    // Location confirmation prompt
    public static final String REUSE_LOCATION_PROMPT =
            "🛒 We have your cart!\n\nShould we deliver to your saved default location?";
    public static final String REUSE_LOCATION_YES_BUTTON = "✅ Yes, deliver here";
    public static final String REUSE_LOCATION_NEW_BUTTON = "📍 Send new location";

    // Location request prompt
    public static final String LOCATION_REQUEST_PROMPT =
            "📍 *WHERE SHOULD WE DELIVER?*\n\n"
                    + "Your cart is ready! 🛒\n\n"
                    + "*Please send us your current location:*\n\n"
                    + "📎 Tap the attachment icon\n"
                    + "📍 Select *Location*\n"
                    + "📤 Tap *Send your current location*\n\n"
                    + "This helps us deliver your order to the correct address. ❤️";

    // Order status alerts
    public static final String ORDER_CONFIRMED_ALERT =
            "✅ *Order confirmed!*\n\nWe are packing it now and it will be delivered to you today. We will let you know as soon as it's out for delivery.";

    public static final String OUT_FOR_DELIVERY_ALERT_TEMPLATE =
            "🛵 *Out for Delivery!*\n\nOur rider, %s, is on the way with your order. \n⏱️ ETA: %s mins.\n\n📞 Need to reach them? Call: %s";

    public static final String DELIVERED_ALERT =
            "📦 *Delivered!*\n\nEnjoy your groceries! If you need any help with your items, please call this number. See you next time!";

    public static final String CONFIRM_CHECKOUT = "CHECKOUT_";

    // Warehouse order alert
    public static final String WAREHOUSE_NEW_ORDER_ALERT_TEMPLATE =
            "🚨 *NEW ORDER: %s*\n📍 Customer: %s\n\n*Items to Pack:*\n%s\n\nAction required:";
    public static final String WAREHOUSE_ACCEPT_ORDER_BUTTON = "✅ Accept Order";
    public static final String WAREHOUSE_STOCK_ISSUE_BUTTON = "❌ Stock Issue";

    // Bag ready prompt to warehouse
    public static final String BAG_READY_PROMPT_TEMPLATE = "📦 *Order %s is Packing*\n\nTap below ONLY when the bag is sealed, stapled, and waiting on the dispatch rack.";
    public static final String BAG_READY_BUTTON_TITLE = "🎒 Bag is Ready";

    // Stock issue cancellation message
    public static final String STOCK_ISSUE_CANCELLATION =
            "❌ *Apologies!* We just checked our shelves and one or more items in your cart are completely out of stock. Your order has been cancelled. Please try again later.";


    // Batch Delivery Manifest Constants
    public static final String RIDER_MANIFEST_HEADER_TEMPLATE =
            """
                    📋 *DAILY DISPATCH MANIFEST*
                    • *Run ID:* RUN-%s
                    • *Total Drops:* %d
                    • *Total Cash to Collect:* ₹%s
                    --------------------------------
                    
                    🎒 *LOADING CHECKLIST:*
                    """;

    public static final String RIDER_MANIFEST_ITEM_TEMPLATE =
            """
                    %d. Order: %s | Cash: ₹%s
                    
                    """;

    public static final String RIDER_MANIFEST_FOOTER =
            "--------------------------------\n" +
                    "👉 Individual delivery action cards will follow immediately below. Please load your vehicle accordingly.";

    public static final String OUT_OF_BOUNDS_TEMPLATE =
            """
                    😔 *Out of Delivery Zone*
                    
                    You are currently %.1f km away, but we only deliver within %.1f km of our store. \
                    We hope to expand to your neighborhood soon!""";


    public static final String LOW_STOCK_ALERT =
            """
                    ⚠️ *LOW STOCK ALERT*
                    
                    Item: %s (SKU: %s)
                    Remaining Stock: %d units
                    
                    Please restock soon to avoid missing sales.""";

    public static final String ORDER_SUMMARY =
            """
                    🛒 *Order Summary*\n\n
                    %s\n\n
                    ---------------------------\n
                    💰 *Grand Total: ₹%s*\n\n
                    Tap below to confirm your order!""";

}



