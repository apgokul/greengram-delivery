package com.localcommerce.delivery.client;

import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.service.SmsFallbackService;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppOutboundClient {

    @Value("${whatsapp.api.url}") // e.g., https://graph.facebook.com/v19.0/YOUR_PHONE_ID/messages
    private String apiUrl;

    @Value("${whatsapp.api.token}") // Your permanent Meta Access Token
    private String accessToken;

    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    @Value("${whatsapp.catalog.id}")
    private String catalogId;

    private final RestTemplate restTemplate;
    private final SmsFallbackService smsFallbackService;

    /**
     * Option A: The "Reuse Location" Interactive Button
     */
    public void sendReuseLocationPrompt(String toPhoneNumber, Long orderId) {

        // Constructing the Meta Interactive Button JSON payload using Maps (or a DTO)
        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, WhatsAppConstants.REUSE_LOCATION_PROMPT),
                        WhatsAppConstants.ACTION, Map.of(
                                WhatsAppConstants.BUTTONS, new Object[]{
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.REUSE_LOCATION + orderId,
                                                        WhatsAppConstants.TITLE, WhatsAppConstants.REUSE_LOCATION_YES_BUTTON
                                                )
                                        ),
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.NEW_LOCATION + orderId,
                                                        WhatsAppConstants.TITLE, WhatsAppConstants.REUSE_LOCATION_NEW_BUTTON
                                                )
                                        )
                                }
                        )
                )
        );

        sendPostRequest(body);
    }

    /**
     * Option B: The explicit "Drop a Pin" instruction
     */
    public void sendLocationRequestPrompt(String toPhoneNumber) {
        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_TEXT,
                WhatsAppConstants.TEXT, Map.of(WhatsAppConstants.BODY, WhatsAppConstants.LOCATION_REQUEST_PROMPT)
        );

        sendPostRequest(body);
    }

    public void sendPendingOrdersList(String toPhoneNumber, List<CustomerOrder> pendingOrders) {
        // Meta API limits list rows to 10 per section. We will safely truncate to 10 for Phase 1.
        int limit = Math.min(pendingOrders.size(), 10);
        
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < limit; i++) {
            CustomerOrder order = pendingOrders.get(i);
            int itemCount = order.getItems().stream().mapToInt(com.localcommerce.delivery.model.OrderItem::getQuantity).sum();
            String description = String.format("₹%s — %d items", order.getTotalAmount(), itemCount);
            
            rows.add(Map.of(
                    WhatsAppConstants.ID, WhatsAppConstants.VIEW_ORDER + order.getId(),
                    WhatsAppConstants.TITLE, "Order #" + order.getDisplayId(),
                    WhatsAppConstants.DESCRIPTION, description
            ));
        }

        String bodyText = String.format("📦 *Pending Warehouse Orders*\n\nYou have %d order(s) waiting for action.\nSelect an order below to view it.", pendingOrders.size());

        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_LIST,
                        WhatsAppConstants.HEADER, Map.of(
                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_TEXT,
                                WhatsAppConstants.TEXT, "Pending Orders"
                        ),
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, bodyText),
                        WhatsAppConstants.ACTION, Map.of(
                                WhatsAppConstants.BUTTON, "View Orders",
                                WhatsAppConstants.SECTIONS, new Object[]{
                                        Map.of(
                                                WhatsAppConstants.TITLE, "Orders Awaiting Action",
                                                WhatsAppConstants.ROWS, rows
                                        )
                                }
                        )
                )
        );

        sendPostRequest(body);
    }

    // Helper method to execute the HTTP call
    @Retryable(
            retryFor = {RestClientException.class},
            backoff = @Backoff(delay = 2000) // Wait 2 seconds between tries
    )
    private void sendPostRequest(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
        // --- TEMPORARY MUTE FOR LOCAL TESTING ---
        restTemplate.postForEntity(apiUrl, request, String.class);


        log.info("Successfully sent payload to Meta API.");
    }

    // Inside com.localcommerce.delivery.client.WhatsAppOutboundClient

    /**
     * ALERT : Out for Delivery (Triggered by Routing Engine)
     */
    public void sendOutForDeliveryAlert(String toPhoneNumber, String riderName, String riderPhone, String etaMins) {
        String message = String.format(
                WhatsAppConstants.OUT_FOR_DELIVERY_ALERT_TEMPLATE,
                riderName, etaMins, riderPhone
        );
        sendTextUpdate(toPhoneNumber, message);
    }

    /**
     * ALERT 3: Delivered (Triggered by Rider App/Bot)
     */
    public void sendDeliveredAlert(String toPhoneNumber) {
        String message = WhatsAppConstants.DELIVERED_ALERT;
        sendTextUpdate(toPhoneNumber, message);
    }

    public void sendOrderSummaryPrompt(String toPhoneNumber, com.localcommerce.delivery.model.CustomerOrder order) {

        // Calculate the grand total to display
        java.math.BigDecimal grandTotal = order.getTotalAmount();

        String itemList = order.getItems().stream()
                .map(item -> "▪️ " + item.getQuantity() + "x " + item.getProduct().getDisplayName())
                .collect(java.util.stream.Collectors.joining("\n"));

        String bodyText = String.format(
                WhatsAppConstants.ORDER_SUMMARY,
                itemList, order.getTotalAmount().toString()
        );

        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, bodyText),
                        WhatsAppConstants.ACTION, Map.of(
                                WhatsAppConstants.BUTTONS, new Object[]{
                                        Map.of(
                                                "type", WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                "reply", Map.of(
                                                        "id", WhatsAppConstants.CONFIRM_CHECKOUT + order.getId(),
                                                        "title", "✅ Confirm Order"
                                                )
                                        )
                                }
                        )
                )
        );
        sendPostRequest(body);
    }

    public void sendTextUpdate(String toPhoneNumber, String messageText) {
        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_TEXT,
                WhatsAppConstants.TEXT, Map.of(WhatsAppConstants.BODY, messageText)
        );

        sendPostRequest(body);
    }

    public void sendBagReadyPrompt(String toPhoneNumber, CustomerOrder order) {

        String dynamicBodyText = String.format(
                WhatsAppConstants.BAG_READY_PROMPT_TEMPLATE,
                order.getDisplayId()
        );

        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, dynamicBodyText),
                        WhatsAppConstants.ACTION, Map.of(
                                WhatsAppConstants.BUTTONS, new Object[]{
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.READY_FOR_PICKUP + order.getId(),
                                                        WhatsAppConstants.TITLE, WhatsAppConstants.BAG_READY_BUTTON_TITLE
                                                )
                                        )
                                }
                        )
                )
        );

        sendPostRequest(body);
    }

    @Recover
    public void handleMetaApiFailure(RestClientException e, Map<String, Object> payload) {
        log.error("CRITICAL: Meta API completely unreachable after retries. Error: {}", e.getMessage());

        //      1. Extract the intended recipient
        String intendedRecipient = (String) payload.getOrDefault(WhatsAppConstants.TO, "Unknown");
        log.error("Meta API completely unreachable. Failed to deliver message to: {}", intendedRecipient);

        if (intendedRecipient.equals(warehousePhoneNumber)) {
            // SCENARIO A: The warehouse didn't get the alert! This is an operational emergency.
            String alertMessage = "🚨 URGENT: WhatsApp API is DOWN. " +
                    "A new order was just placed but the warehouse bot is offline. " +
                    "Please check the backend database immediately to pack the order!";

            smsFallbackService.sendEmergencyAlert(alertMessage);
        }
        else {
            // SCENARIO B: A customer update failed (e.g., "Out for delivery" alert dropped).
            // We usually don't want to pay to SMS customers for every minor status update.
            // Instead, we log it with a specific searchable tag so customer support knows
            // why the user might call complaining about a lack of updates.
            log.warn("[CUSTOMER_COMM_DROP] Failed to send WhatsApp update to customer {}. " +
                    "Payload dump: {}", intendedRecipient, payload);
        }
    }

    // Inside com.localcommerce.delivery.client.WhatsAppOutboundClient

    public void sendRiderDeliveryCard(String riderPhoneNumber, CustomerOrder order) {
        String googleMapsLink = String.format(
                "https://www.google.com/maps?q=%s,%s",
                order.getDeliveryLatitude(),
                order.getDeliveryLongitude()
        );
        String totalAmount = order.getTotalAmount().toString();

        String bodyText = String.format(
                """
                        📦 *ORDER DISPATCH*
                        
                        • *ID:* %s
                        • *Customer:* %s
                        • *Phone:* %s
                        • *Collect Cash:* ₹%s
                        • *Address:* %s
                        
                        Tap below ONLY when you have handed this bag to the customer.""",
                order.getDisplayId(), 
                order.getCustomer().getName(),
                order.getCustomer().getWhatsappNumber(),
                totalAmount, 
                googleMapsLink
        );

        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, riderPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, bodyText),
                        WhatsAppConstants.ACTION, Map.of(
                                WhatsAppConstants.BUTTONS, new Object[]{
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        // E.g., "DELIVERED_1042"
                                                        WhatsAppConstants.ID, WhatsAppConstants.SET_DELIVERED + order.getId(),
                                                        WhatsAppConstants.TITLE, "✅ Marked Delivered"
                                                )
                                        )
                                }
                        )
                )
        );

        sendPostRequest(body);
    }

    public void sendCatalogMessage(String recipientId, List<String> activeSkus) {

        Map<String, Object> headerNode = Map.of(
                "type", "text",
                "text", "GreenGram"
        );

        Map<String, Object> bodyNode = Map.of(
                "text", "Welcome to GreenGram! 🛒 Tap below to view our menu and place your order."
        );

        Map<String, Object> footerNode = Map.of(
                "text", "Quality at your doorstep."
        );

        // Dynamically build the product list from your database SKUs (Meta limits this to 30 items max)
        List<Map<String, Object>> productItems = new java.util.ArrayList<>();
        for (int i = 0; i < activeSkus.size() && i < 30; i++) {
            productItems.add(Map.of("product_retailer_id", activeSkus.get(i)));
        }

        // Build the section
        Map<String, Object> section1 = Map.of(
                "title", "Fresh Groceries",
                "product_items", productItems
        );

        Map<String, Object> actionNode = Map.of(
                "catalog_id", catalogId,
                "sections", List.of(section1)
        );

        Map<String, Object> interactiveNode = Map.of(
                "type", "product_list",
                "header", headerNode,
                "body", bodyNode,
                "footer", footerNode,
                "action", actionNode
        );

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("type", "interactive");
        payload.put("interactive", interactiveNode);

        // Dynamically route standard numbers vs BSUIDs
        if (recipientId.length() >= 15 || recipientId.contains(".")) {
            payload.put("recipient", recipientId);
        } else {
            payload.put("to", recipientId);
        }

        log.info("Sending Multi-Product Message with {} items to {}", productItems.size(), recipientId);
        sendPostRequest(payload);
    }
}