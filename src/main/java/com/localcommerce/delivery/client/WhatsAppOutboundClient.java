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

import com.localcommerce.delivery.model.OrderItem;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WhatsAppOutboundClient {

    @Value("${whatsapp.api.url}") // e.g., https://graph.facebook.com/v19.0/YOUR_PHONE_ID/messages
    private String apiUrl;

    @Value("${whatsapp.api.token}") // Your permanent Meta Access Token
    private String accessToken;

    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    private final RestTemplate restTemplate;
    private final SmsFallbackService smsFallbackService;
    private final com.localcommerce.delivery.service.WhatsAppLoggingService loggingService;
    private final WhatsAppHttpSender httpSender;
    private Clock clock = Clock.system(WhatsAppConstants.ASIA_KOLKATA_ZONE);

    @org.springframework.beans.factory.annotation.Autowired
    public WhatsAppOutboundClient(RestTemplate restTemplate,
                                  SmsFallbackService smsFallbackService,
                                  com.localcommerce.delivery.service.WhatsAppLoggingService loggingService,
                                  WhatsAppHttpSender httpSender) {
        this.restTemplate = restTemplate;
        this.smsFallbackService = smsFallbackService;
        this.loggingService = loggingService;
        this.httpSender = httpSender;
    }

    public WhatsAppOutboundClient(RestTemplate restTemplate,
                                  SmsFallbackService smsFallbackService,
                                  com.localcommerce.delivery.service.WhatsAppLoggingService loggingService) {
        this(restTemplate, smsFallbackService, loggingService,
                new WhatsAppHttpSender(restTemplate, smsFallbackService, loggingService));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
            if (this.httpSender != null) {
                this.httpSender.setClock(clock);
            }
        }
    }

    /**
     * Option A: The "Reuse Location" Interactive Button
     */
    public void sendReuseLocationPrompt(String toPhoneNumber, Long orderId, String savedLocation) {

        String promptText = String.format(WhatsAppConstants.REUSE_LOCATION_PROMPT, savedLocation);

        // Constructing the Meta Interactive Button JSON payload using Maps (or a DTO)
        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, promptText),
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

        String loggedDescription = promptText + "\n[Buttons: 1. " + WhatsAppConstants.REUSE_LOCATION_YES_BUTTON + " | 2. " + WhatsAppConstants.REUSE_LOCATION_NEW_BUTTON + "]";
        sendPostRequest(toPhoneNumber, loggedDescription, body);
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

        sendPostRequest(toPhoneNumber, WhatsAppConstants.LOCATION_REQUEST_PROMPT, body);
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

        String loggedDescription = bodyText + "\n[List Options: " + limit + " orders presented]";
        sendPostRequest(toPhoneNumber, loggedDescription, body);
    }

    // Helper method to execute the HTTP call via Spring-managed retry proxy
    void sendPostRequest(String toPhoneNumber, String loggedDescription, Map<String, Object> payload) {
        if (httpSender != null) {
            if (httpSender.getApiUrl() == null && this.apiUrl != null) {
                httpSender.setApiUrl(this.apiUrl);
            }
            if (httpSender.getAccessToken() == null && this.accessToken != null) {
                httpSender.setAccessToken(this.accessToken);
            }
            if (httpSender.getWarehousePhoneNumber() == null && this.warehousePhoneNumber != null) {
                httpSender.setWarehousePhoneNumber(this.warehousePhoneNumber);
            }
            if (this.clock != null) {
                httpSender.setClock(this.clock);
            }
            httpSender.sendPostRequest(toPhoneNumber, loggedDescription, payload);
        }
    }

    // Inside com.localcommerce.delivery.client.WhatsAppOutboundClient

    /**
     * ALERT : Out for Delivery (Triggered by Routing Engine)
     */
    public void sendOutForDeliveryAlert(String toPhoneNumber, String riderPhone, String etaMins) {
        String message = String.format(
                WhatsAppConstants.OUT_FOR_DELIVERY_ALERT_TEMPLATE,
                etaMins, riderPhone
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

    public void sendOrderSummaryPrompt(String toPhoneNumber, CustomerOrder order) {
        String bodyText = buildOrderSummaryBody(order);

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
                                                        "id", WhatsAppConstants.CONFIRM_ORDER + order.getId(),
                                                        "title", "✅ Confirm Order"
                                                )
                                        ),
                                        Map.of(
                                                "type", WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                "reply", Map.of(
                                                        "id", WhatsAppConstants.EDIT_ORDER + order.getId(),
                                                        "title", "✏️ Edit Order"
                                                )
                                        ),
                                        Map.of(
                                                "type", WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                "reply", Map.of(
                                                        "id", WhatsAppConstants.CANCEL_ORDER + order.getId(),
                                                        "title", "❌ Cancel Order"
                                                )
                                       )
                                }
                        )
                )
        );
        String loggedDescription = bodyText + "\n[Buttons: 1. Confirm Order | 2. Edit Order | 3. Cancel Order]";
        sendPostRequest(toPhoneNumber, loggedDescription, body);
    }

    public String buildOrderSummaryBody(CustomerOrder order) {
        String itemList = formatItemList(order.getItems());

        BigDecimal grandTotal = order.getTotalAmount();
        if (grandTotal == null && order.getItems() != null) {
            grandTotal = order.getItems().stream()
                    .map(item -> {
                        BigDecimal price = item.getLockedPrice() != null
                                ? item.getLockedPrice()
                                : (item.getProduct() != null && item.getProduct().getCurrentPrice() != null
                                        ? item.getProduct().getCurrentPrice()
                                        : BigDecimal.ZERO);
                        int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                        return price.multiply(BigDecimal.valueOf(qty));
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        if (grandTotal == null) {
            grandTotal = BigDecimal.ZERO;
        }

        String formattedGrandTotal = String.format(Locale.ROOT, "%.2f", grandTotal);

        // Guard against Meta 1024 char limit: truncate items if total body exceeds safe threshold
        if (itemList.length() > 700) {
            itemList = itemList.substring(0, 670) + "\n... (items truncated)";
        }

        return String.format(
                WhatsAppConstants.ORDER_SUMMARY,
                order.getId() != null ? order.getId() : "",
                itemList, formattedGrandTotal
        );
    }

    public String formatItemList(List<OrderItem> items) {
        if (items == null) {
            return "";
        }
        return items.stream()
                .map(item -> {
                    int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
                    String productName = "";
                    if (item.getProduct() != null) {
                        productName = item.getProduct().getDisplayName() != null
                                ? item.getProduct().getDisplayName()
                                : (item.getProduct().getName() != null ? item.getProduct().getName() : "");
                    }
                    BigDecimal unitPrice = item.getLockedPrice() != null
                            ? item.getLockedPrice()
                            : (item.getProduct() != null && item.getProduct().getCurrentPrice() != null
                                    ? item.getProduct().getCurrentPrice()
                                    : BigDecimal.ZERO);
                    BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
                    return String.format(Locale.ROOT, "%dx %s: ₹%.2f", quantity, productName, lineTotal);
                })
                .collect(Collectors.joining("\n"));
    }

    public void sendTextUpdate(String toPhoneNumber, String messageText) {
        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_TEXT,
                WhatsAppConstants.TEXT, Map.of(WhatsAppConstants.BODY, messageText)
        );

        sendPostRequest(toPhoneNumber, messageText, body);
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

        String loggedDescription = dynamicBodyText + "\n[Button: " + WhatsAppConstants.BAG_READY_BUTTON_TITLE + "]";
        sendPostRequest(toPhoneNumber, loggedDescription, body);
    }

    public void handleMetaApiFailure(RestClientException e, Map<String, Object> payload) {
        if (httpSender != null) {
            String toPhoneNumber = payload != null ? (String) payload.get(WhatsAppConstants.TO) : null;
            httpSender.handleMetaApiFailure(e, toPhoneNumber, null, payload);
        }
    }

    // Inside com.localcommerce.delivery.client.WhatsAppOutboundClient

    public void sendRiderDeliveryCard(String riderPhoneNumber, CustomerOrder order) {
        String addressText;
        if (order.getDeliveryLatitude() != null && order.getDeliveryLongitude() != null) {
            addressText = String.format(Locale.ROOT, "https://www.google.com/maps?q=%s,%s",
                    order.getDeliveryLatitude(),
                    order.getDeliveryLongitude());
        } else {
            addressText = "⚠️ LOCATION REQUIRED (Contact customer)";
        }
        String totalAmount = order.getTotalAmount() != null ? order.getTotalAmount().toString() : "0.00";
        String customerName = (order.getCustomer() != null && order.getCustomer().getName() != null)
                ? order.getCustomer().getName() : "Customer";
        String customerPhone = (order.getCustomer() != null && order.getCustomer().getWhatsappNumber() != null)
                ? order.getCustomer().getWhatsappNumber() : "Unknown";

        String bodyText = String.format(
                """
                        📦 *ORDER DISPATCH*

                        • *ID:* %s
                        • *Customer:* %s
                        • *Phone:* %s
                        • *Collect Cash:* ₹%s
                        • *Address:* %s

                        Tap below to mark delivered or cancel delivery.""",
                order.getDisplayId(),
                customerName,
                customerPhone,
                totalAmount,
                addressText
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
                                                        WhatsAppConstants.ID, WhatsAppConstants.SET_DELIVERED + order.getId(),
                                                        WhatsAppConstants.TITLE, "✅ Delivered"
                                                )
                                        ),
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.RIDER_CANCEL + order.getId(),
                                                        WhatsAppConstants.TITLE, "❌ Cancel"
                                                )
                                        )
                                }
                        )
                )
        );

        String loggedDescription = bodyText + "\n[Buttons: 1. ✅ Delivered | 2. ❌ Cancel]";
        sendPostRequest(riderPhoneNumber, loggedDescription, body);
    }

    public void sendRiderCancellationReasonsPrompt(String riderPhoneNumber, Long orderId) {
        String bodyText = "Select a cancellation reason for Order #" + orderId + ":";
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
                                                        WhatsAppConstants.ID, WhatsAppConstants.RIDER_CANCEL_REASON + "UNREACHABLE_" + orderId,
                                                        WhatsAppConstants.TITLE, "📞 Unreachable"
                                                )
                                        ),
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.RIDER_CANCEL_REASON + "WRONG_ADDR_" + orderId,
                                                        WhatsAppConstants.TITLE, "📍 Wrong Address"
                                                )
                                        ),
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.RIDER_CANCEL_REASON + "REJECTED_" + orderId,
                                                        WhatsAppConstants.TITLE, "🚫 Refused"
                                                )
                                        )
                                }
                        )
                )
        );
        sendPostRequest(riderPhoneNumber, bodyText + "\n[Reasons: Unreachable | Wrong Address | Refused]", body);
    }

    public void sendDeliveryLocationPrompt(String toPhoneNumber) {
        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_TEXT,
                WhatsAppConstants.TEXT, Map.of(WhatsAppConstants.BODY, WhatsAppConstants.LOCATION_REQUEST_PROMPT)
        );
        sendPostRequest(toPhoneNumber, WhatsAppConstants.LOCATION_REQUEST_PROMPT, body);
    }

    public void sendSavedLocationPrompt(String toPhoneNumber, Long orderId, Double lat, Double lng) {
        if (lat == null || lng == null) {
            sendDeliveryLocationPrompt(toPhoneNumber);
            return;
        }

        String mapsUrl = String.format(Locale.ROOT, "https://www.google.com/maps?q=%.6f,%.6f", lat, lng);
        String promptText = String.format(WhatsAppConstants.SAVED_LOCATION_STANDALONE_PROMPT, mapsUrl);

        Map<String, Object> body = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, toPhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, promptText),
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
        sendPostRequest(toPhoneNumber, promptText + "\n[Buttons: 1. " + WhatsAppConstants.REUSE_LOCATION_YES_BUTTON + " | 2. " + WhatsAppConstants.REUSE_LOCATION_NEW_BUTTON + "]", body);
    }

    public void sendSavedLocationPrompt(String toPhoneNumber, Long orderId) {
        sendSavedLocationPrompt(toPhoneNumber, orderId, null, null);
    }

    public String resolveDeliveryPromise(Instant checkoutInstant) {
        if (checkoutInstant == null) {
            throw new IllegalArgumentException("checkoutInstant must not be null to resolve delivery promise");
        }
        LocalTime time = checkoutInstant.atZone(WhatsAppConstants.ASIA_KOLKATA_ZONE).toLocalTime();
        return WhatsAppConstants.getDeliveryPromise(time);
    }

    public void sendCombinedOrderConfirmationPrompt(String toPhoneNumber, Long orderId, boolean hasSavedLocation, Double lat, Double lng) {
        sendCombinedOrderConfirmationPrompt(toPhoneNumber, orderId, hasSavedLocation, lat, lng, null);
    }

    public void sendCombinedOrderConfirmationPrompt(String toPhoneNumber, Long orderId, boolean hasSavedLocation, Double lat, Double lng, Instant checkoutInstant) {
        if (checkoutInstant == null) {
            log.error("Missing required checkout timestamp for order {}. Refusing to send delivery confirmation.", orderId);
            return;
        }

        String deliveryPromise = resolveDeliveryPromise(checkoutInstant);

        if (hasSavedLocation && lat != null && lng != null) {
            String mapsUrl = String.format(Locale.ROOT, "https://www.google.com/maps?q=%.6f,%.6f", lat, lng);
            String bodyText = String.format(WhatsAppConstants.ORDER_CONFIRMED_WITH_SAVED_LOCATION_PROMPT, deliveryPromise, mapsUrl);
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
            sendPostRequest(toPhoneNumber, bodyText + "\n[Buttons: 1. " + WhatsAppConstants.REUSE_LOCATION_YES_BUTTON + " | 2. " + WhatsAppConstants.REUSE_LOCATION_NEW_BUTTON + "]", body);
        } else {
            String bodyText = String.format(WhatsAppConstants.ORDER_CONFIRMED_NEED_NEW_LOCATION_PROMPT, deliveryPromise);
            sendTextUpdate(toPhoneNumber, bodyText);
        }
    }

    public void sendOrderConfirmedConcise(String toPhoneNumber) {
        sendOrderConfirmedConcise(toPhoneNumber, null);
    }

    public void sendOrderConfirmedConcise(String toPhoneNumber, Instant checkoutInstant) {
        if (checkoutInstant == null) {
            log.error("Missing required checkout timestamp when sending concise confirmation to {}. Refusing to send message.", toPhoneNumber);
            return;
        }

        String deliveryPromise = resolveDeliveryPromise(checkoutInstant);
        String bodyText = String.format(WhatsAppConstants.ORDER_CONFIRMED_ALREADY_HAS_LOCATION, deliveryPromise);
        sendTextUpdate(toPhoneNumber, bodyText);
    }

    public void sendEditOrderPrompt(String toPhoneNumber) {
        sendCatalogMessage(toPhoneNumber, WhatsAppConstants.EDIT_ORDER_PROMPT);
    }

    public void sendLocationDisambiguationPrompt(String toPhoneNumber, List<CustomerOrder> candidateOrders, Double lat, Double lng) {
        String coordinateSuffix = String.format(Locale.ROOT, "%.6f_%.6f", lat, lng);

        if (candidateOrders.size() <= 2) {
            java.util.List<Map<String, Object>> buttons = new java.util.ArrayList<>();
            for (CustomerOrder o : candidateOrders) {
                buttons.add(Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                        WhatsAppConstants.REPLY, Map.of(
                                WhatsAppConstants.ID, WhatsAppConstants.SETLOC_PREFIX + o.getId() + "_" + coordinateSuffix,
                                WhatsAppConstants.TITLE, "Order #" + o.getDisplayId()
                        )
                ));
            }

            // Deliver All button if <= 3 buttons total and ID <= 256 chars
            String deliverAllId = WhatsAppConstants.SETLOC_ALL_PREFIX + coordinateSuffix + "_" +
                    candidateOrders.stream().map(o -> o.getId().toString()).collect(Collectors.joining("_"));
            if (buttons.size() < 3 && deliverAllId.length() <= 256) {
                buttons.add(Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                        WhatsAppConstants.REPLY, Map.of(
                                WhatsAppConstants.ID, deliverAllId,
                                WhatsAppConstants.TITLE, "Deliver All"
                        )
                ));
            }

            String bodyText = "📍 You have multiple active orders awaiting a delivery location.\n\nWhich order is this pin for?";
            Map<String, Object> body = Map.of(
                    WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                    WhatsAppConstants.TO, toPhoneNumber,
                    WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                    WhatsAppConstants.INTERACTIVE, Map.of(
                            WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                            WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, bodyText),
                            WhatsAppConstants.ACTION, Map.of(WhatsAppConstants.BUTTONS, buttons.toArray())
                    )
            );
            sendPostRequest(toPhoneNumber, bodyText + "\n[Location disambiguation buttons]", body);
        } else {
            // Interactive List Message for > 2 candidate orders
            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (CustomerOrder o : candidateOrders) {
                String rowId = WhatsAppConstants.SETLOC_PREFIX + o.getId() + "_" + coordinateSuffix;
                rows.add(Map.of(
                        WhatsAppConstants.ID, rowId,
                        WhatsAppConstants.TITLE, "Order #" + o.getDisplayId(),
                        WhatsAppConstants.DESCRIPTION, "Total: ₹" + o.getTotalAmount()
                ));
            }

            // Guardrail 1: Deliver All row check against Meta 200 character list-row ID limit
            String deliverAllId = WhatsAppConstants.SETLOC_ALL_PREFIX + coordinateSuffix + "_" +
                    candidateOrders.stream().map(o -> o.getId().toString()).collect(Collectors.joining("_"));
            if (deliverAllId.length() <= 200 && rows.size() < 10) {
                rows.add(Map.of(
                        WhatsAppConstants.ID, deliverAllId,
                        WhatsAppConstants.TITLE, "Deliver All Orders",
                        WhatsAppConstants.DESCRIPTION, "Apply location to all active pending orders"
                ));
            }

            String bodyText = "📍 You have " + candidateOrders.size() + " active orders awaiting a delivery location.\n\nSelect which order to deliver to this pin:";
            Map<String, Object> body = Map.of(
                    WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                    WhatsAppConstants.TO, toPhoneNumber,
                    WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                    WhatsAppConstants.INTERACTIVE, Map.of(
                            WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_LIST,
                            WhatsAppConstants.HEADER, Map.of(
                                    WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_TEXT,
                                    WhatsAppConstants.TEXT, "Select Order"
                            ),
                            WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, bodyText),
                            WhatsAppConstants.ACTION, Map.of(
                                    WhatsAppConstants.BUTTON, "Choose Order",
                                    WhatsAppConstants.SECTIONS, new Object[]{
                                            Map.of(
                                                    WhatsAppConstants.TITLE, "Active Orders",
                                                    WhatsAppConstants.ROWS, rows
                                            )
                                    }
                            )
                    )
            );
            sendPostRequest(toPhoneNumber, bodyText + "\n[Location disambiguation list]", body);
        }
    }

    public void sendCatalogMessage(String recipientId) {
        sendCatalogMessage(recipientId,
                "Welcome to *GreenGram!* 🛒\n\n" +
                        "Your grocery purchases made easy.\n\n" +
                        "Explore our products and place your order below. 👇");
    }

    public void sendCatalogMessage(String recipientId, String bodyText) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put(WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP);
        payload.put("recipient_type", "individual");
        payload.put(WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE);
        
        Map<String, Object> interactive = new java.util.HashMap<>();
        interactive.put(WhatsAppConstants.TYPE, "catalog_message");
        interactive.put(WhatsAppConstants.BODY, Map.of(
                WhatsAppConstants.TEXT, bodyText
        ));
        interactive.put(WhatsAppConstants.ACTION, Map.of("name", "catalog_message"));
        
        payload.put(WhatsAppConstants.INTERACTIVE, interactive);

        // Dynamically route standard numbers vs BSUIDs
        if (recipientId.length() >= 15 || recipientId.contains(".")) {
            payload.put("recipient", recipientId);
        } else {
            payload.put(WhatsAppConstants.TO, recipientId);
        }

        log.info("Sending interactive catalog message to {}", recipientId);
        String catalogDescription = bodyText + "\n[Interactive Catalog: View Products Action]";
        sendPostRequest(recipientId, catalogDescription, payload);
    }
}