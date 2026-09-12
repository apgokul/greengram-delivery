package com.localcommerce.delivery.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppWebhookPayload {

    private String object;
    private List<Entry> entry;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private String id;
        private List<Change> changes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Change {
        private Value value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        private Metadata metadata;
        private List<Contact> contacts;
        private List<Message> messages;
        private List<Status> statuses; // <-- ADD THIS
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        @JsonProperty("display_phone_number")
        private String displayPhoneNumber;
        @JsonProperty("phone_number_id")
        private String phoneNumberId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contact {
        private Profile profile;
        @JsonProperty("wa_id")
        private String waId;

        // NEW: The permanent BSUID field
        private String user_id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String from; // The sender's phone number
        private String id;
        private String type; // Will be "order", "interactive", or "location"

        private OrderPayload order;
        private InteractivePayload interactive;
        private LocationPayload location;
        private TextPayload text;
    }

    // --- The 3 Types of Payloads We Care About ---

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderPayload {
        @JsonProperty("catalog_id")
        private String catalogId;
        @JsonProperty("product_items")
        private List<ProductItem> productItems;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductItem {
        @JsonProperty("product_retailer_id")
        private String productRetailerId; // This is your SKU (e.g., RICE_5KG)
        private String quantity;
        @JsonProperty("item_price")
        private String itemPrice;
        private String currency;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InteractivePayload {
        private ButtonReply button_reply;
        private ListReply list_reply;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ButtonReply {
            private String id; // e.g., "ACCEPT_1042"
            private String title;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ListReply {
            private String id; // e.g., "VIEW_ORDER_1042"
            private String title;
            private String description;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocationPayload {
        private Double latitude;
        private Double longitude;
        private String address;
        // Sometimes provided if they share a specific place
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextPayload {
        private String body;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Status {
        private String id;
        private String status;
        private String recipient_id;
        private List<Error> errors;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        private int code;
        private String title;
        private String message;
    }
}