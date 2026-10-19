package com.localcommerce.delivery.utils;

import java.time.ZoneId;

public class WhatsAppConstants {
    private WhatsAppConstants() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    // ==================== Timezone Contract ====================
    public static final ZoneId ASIA_KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");
    public static final ZoneId BUSINESS_ZONE = ASIA_KOLKATA_ZONE;

    // ==================== Button Prefixes ====================
    // Customer interaction button prefixes
    public static final String REUSE_LOCATION = "REUSE_LOC_";
    public static final String NEW_LOCATION = "NEW_LOC_";
    public static final String CONFIRM_ORDER = "CONFIRM_";
    public static final String EDIT_ORDER = "EDIT_ORDER_";
    public static final String CANCEL_ORDER = "CANCEL_ORDER_";
    public static final String SETLOC_PREFIX = "SETLOC_";
    public static final String SETLOC_ALL_PREFIX = "SETLOC_ALL_";

    // Rider action button prefixes
    public static final String RIDER_CANCEL = "RCANCEL_";
    public static final String RIDER_CANCEL_REASON = "RCANCEL_REASON_";

    // Warehouse action button prefixes
    public static final String ACCEPT_ORDER = "ACCEPT_";
    public static final String READY_FOR_PICKUP = "READY_";
    public static final String STOCK_ISSUE = "STOCK_ISSUE_";
    public static final String SET_DELIVERED = "DELIVERED_";
    public static final String VIEW_ORDER = "VIEW_ORDER_";

    // Location badge
    public static final String LOCATION_REQUIRED_BADGE = "⚠️ LOCATION REQUIRED";

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
            "🛒 We have your cart!\n\nShould we deliver to your saved default location?\n\n📍 %s";
    public static final String REUSE_LOCATION_YES_BUTTON = "Use Saved Location";
    public static final String REUSE_LOCATION_NEW_BUTTON = "Share New Location";

    // Combined order confirmation and location request templates
    public static final String ORDER_CONFIRMED_ALREADY_HAS_LOCATION =
            "🎉 *Your order is confirmed!*\n\nThank you for choosing GreenGram 💚\n\n🛵 *Your order will be delivered by this evening.*";

    public static final String ORDER_CONFIRMED_WITH_SAVED_LOCATION_PROMPT =
            "🎉 *Your order is confirmed!*\n\nThank you for choosing GreenGram 💚\n\n🛵 *Your order will be delivered by this evening.*\n\n📍 *Where should we deliver?*\n\nChoose your saved location below or share your current location so we can deliver your order to the right place.\n\n📌 *Your saved location:*\n%s";

    public static final String ORDER_CONFIRMED_NEED_NEW_LOCATION_PROMPT =
            "🎉 *Your order is confirmed!*\n\nThank you for choosing GreenGram 💚\n\n🛵 *Your order will be delivered by this evening.*\n\n📍 *Where should we deliver?*\n\nPlease share your current location so we can deliver your order to the right place:\n\n📎 Tap the attachment icon\n📍 Select *Location*\n📤 Tap *Send your current location*";

    public static final String SAVED_LOCATION_STANDALONE_PROMPT =
            "*Your delivery location*\n\nUse your saved location or share a new one so we can deliver your order to the right place.\n\nSaved location:\n%s";

    public static final String EDIT_ORDER_PROMPT =
            "*Let's update your order 🛒*\n\nNo problem! Browse our catalogue, update your cart, and send us your new order.";

    public static final String LOCATION_CONFIRMED_NATURAL =
            "Thanks! Your delivery location has been saved. We'll deliver your order to this location.";

    public static final String ORDER_CANCELLED_CUSTOMER =
            "Your order has been cancelled.";

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
    public static final String ORDER_PACKING_ALERT =
            "✅ *Order confirmed!*\n" +
                    "\n" +
                    "📦 *Your order is being packed!*\n" +
                    "\n" +
                    "Your order will arrive *between 5:00 PM and 8:00 PM.*\n" +
                    "\n" +
                    "We'll notify you as soon as it's *out for delivery.*\n";

    public static final String OUT_FOR_DELIVERY_ALERT_TEMPLATE =
            "🛵 *Out for Delivery!*\n\nOur rider, is on the way with your order. \n⏱️ ETA: %s mins.\n\n📞 Need to reach them? Call: %s";

    public static final String DELIVERED_ALERT =
            "📦 *Order delivered!*\n\n" +
                    "We hope you enjoy your groceries!\n\n" +
                    "If you have any questions or need help with your order, we're just a call away.\n\n" +
                    "Thank you for choosing *GreenGram*. See you again soon! 😊";

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
                    
                    Inventory: %s
                    Remaining: %s %s.
                    
                    Please restock soon to avoid missing sales.""";

    public static final String ORDER_SUMMARY =
            """
            🧾 *Order Invoice*
            
            *Items:*
            %s
            
            ---------------------------
            💰 *Grand Total: ₹%s*
            
            Tap below to confirm your order!""";

}



