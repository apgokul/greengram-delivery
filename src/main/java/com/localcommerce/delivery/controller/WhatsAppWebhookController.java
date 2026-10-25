package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.dto.CartItemDTO;
import com.localcommerce.delivery.dto.OrderProcessing;
import com.localcommerce.delivery.dto.WhatsAppWebhookPayload;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;

import com.localcommerce.delivery.service.BatchingService;
import com.localcommerce.delivery.service.OrderService;
import com.localcommerce.delivery.service.WarehouseService;
import com.localcommerce.delivery.utils.UserContext;
import com.localcommerce.delivery.utils.WebhookSignatureVerifier;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
public class WhatsAppWebhookController {

    @Value("${whatsapp.verify.token}")
    private String verifyToken;

    @Value("${whatsapp.app.secret}")
    private String appSecret;

    @Value("${whatsapp.signature.verification.enabled:true}")
    private boolean signatureVerificationEnabled;

    @Value("#{'${authorized.rider.numbers}'.split(',')}")
    private List<String> authorizedRiders;

    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    private final OrderService orderService;
    private final WhatsAppOutboundClient outboundClient;
    private final WarehouseService warehouseService;
    private final BatchingService batchingService;
    private final com.localcommerce.delivery.service.WhatsAppLoggingService loggingService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Clock clock = Clock.system(WhatsAppConstants.ASIA_KOLKATA_ZONE);

    public WhatsAppWebhookController(OrderService orderService, WhatsAppOutboundClient outboundClient, WarehouseService warehouseService, BatchingService batchingService, com.localcommerce.delivery.service.WhatsAppLoggingService loggingService) {
        this.orderService = orderService;
        this.outboundClient = outboundClient;
        this.warehouseService = warehouseService;
        this.batchingService = batchingService;
        this.loggingService = loggingService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    LocalDateTime parseMetaTimestampToIst(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isBlank()) {
            log.warn("Missing Meta message timestamp; falling back to current IST time");
            return LocalDateTime.now(clock);
        }
        try {
            long epochSeconds = Long.parseLong(rawTimestamp.trim());
            return Instant.ofEpochSecond(epochSeconds)
                    .atZone(WhatsAppConstants.ASIA_KOLKATA_ZONE)
                    .toLocalDateTime();
        } catch (NumberFormatException e) {
            log.warn("Malformed Meta message timestamp '{}'; falling back to current IST time", rawTimestamp);
            return LocalDateTime.now(clock);
        } catch (Exception e) {
            log.warn("Error converting Meta timestamp '{}' to IST: {}; falling back to current IST time", rawTimestamp, e.getMessage());
            return LocalDateTime.now(clock);
        }
    }

    @GetMapping("/whatsapp")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = WhatsAppConstants.HUB_MODE, required = false) String mode,
            @RequestParam(name = WhatsAppConstants.HUB_VERIFY_TOKEN, required = false) String token,
            @RequestParam(name = WhatsAppConstants.HUB_CHALLENGE, required = false) String challenge) {

        if (WhatsAppConstants.WEBHOOK_MODE_SUBSCRIBE.equals(mode) && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PostMapping("/whatsapp")
    public ResponseEntity<Void> receiveMessage(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        // Item 2: Verify X-Hub-Signature-256 before processing any payload.
        // Returns 403 for non-Meta requests — 200 OK is only for verified, processed events.
        if (signatureVerificationEnabled
                && !WebhookSignatureVerifier.isValid(rawBody, signature, appSecret)) {
            log.warn("SECURITY: Rejected webhook POST — invalid or missing X-Hub-Signature-256.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            WhatsAppWebhookPayload payload = objectMapper.readValue(rawBody, WhatsAppWebhookPayload.class);

            if (payload.getEntry() != null &&
                    !payload.getEntry().isEmpty() &&
                    payload.getEntry().getFirst().getChanges() != null &&
                    !payload.getEntry().getFirst().getChanges().isEmpty() &&
                    payload.getEntry().getFirst().getChanges().getFirst().getValue() != null) {

                var value = payload.getEntry().getFirst().getChanges().getFirst().getValue();

                // Item 3 fix: messages and statuses are now independent sibling branches.
                // Previously, the statuses branch was nested inside the messages block,
                // making it unreachable for status-only webhook events.
                if (value.getMessages() != null && !value.getMessages().isEmpty()) {
                    // Branch A: inbound customer messages (orders, texts, locations, button taps)
                    WhatsAppWebhookPayload.Message message = value.getMessages().getFirst();

                    // 1. Extract Contact Info & Sender Identifiers safely
                    String rawFrom = message.getFrom();
                    String bsuid = null;
                    String whatsappName = "Valued Customer";
                    String rawProfileName = null;

                    if (value.getContacts() != null && !value.getContacts().isEmpty()) {
                        WhatsAppWebhookPayload.Contact contact = value.getContacts().getFirst();
                        bsuid = contact.getUser_id();
                        if (contact.getProfile() != null && contact.getProfile().getName() != null) {
                            rawProfileName = contact.getProfile().getName();
                            whatsappName = rawProfileName;
                        }
                    }

                    // Consistent customer identifier: use phone number if present, otherwise fallback to BSUID
                    String phoneNumber = (rawFrom != null && !rawFrom.isBlank()) ? rawFrom : bsuid;
                    String primaryCustomerKey = (bsuid != null && !bsuid.isBlank()) ? bsuid : phoneNumber;

                    // 1.1 Inbound message logging hook: isolated so persistence failure does not block business handling
                    try {
                        String loggedBody = loggingService.formatInboundMessage(message);
                        LocalDateTime messageTimestamp = parseMetaTimestampToIst(message.getTimestamp());
                        loggingService.logInbound(phoneNumber, loggedBody, messageTimestamp, message.getId(), rawProfileName);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        log.info("Duplicate inbound WhatsApp message skipped due to unique constraint: externalMessageId={}", message.getId());
                    } catch (Exception e) {
                        log.error("Failed to persist inbound WhatsApp message log for phone {}: {}", phoneNumber, e.getMessage());
                    }

                    // Set context thread for logging
                    UserContext.setSenderId(primaryCustomerKey);
                    try {
                        String messageType = message.getType();
                        if (WhatsAppConstants.MESSAGE_TYPE_ORDER.equals(messageType)) {
                            List<WhatsAppWebhookPayload.ProductItem> metaItems = message.getOrder().getProductItems();
                            List<CartItemDTO> internalCart = metaItems.stream()
                                    .map(item -> new CartItemDTO(
                                            item.getProductRetailerId(),
                                            Integer.parseInt(item.getQuantity())
                                    ))
                                    .toList();

                            OrderProcessing.OrderProcessResult result = orderService.processNewCart(primaryCustomerKey, phoneNumber, whatsappName, internalCart);

                            // 2. Handle the outbound messaging based on the result!
                            if (!result.isSuccess()) {
                                // Stock validation failed. Send error message back to the customer.
                                outboundClient.sendTextUpdate(phoneNumber, result.getMessage());
                            } else {
                                // Success! Order is committed in CREATED status. Handle notification locally to avoid misleading apologies.
                                CustomerOrder savedOrder = result.getOrder();
                                Customer customer = savedOrder.getCustomer();
                                String recipientId = (customer != null && customer.getRecipientId() != null)
                                        ? customer.getRecipientId()
                                        : phoneNumber;
                                try {
                                    outboundClient.sendOrderSummaryPrompt(recipientId, savedOrder);
                                } catch (Exception ex) {
                                    log.error("Failed to deliver order summary prompt for persisted order {} (displayId: {}) to recipient {}: {}",
                                            savedOrder.getId(), savedOrder.getDisplayId(), recipientId, ex.getMessage(), ex);
                                    // Order is already safely committed; do not propagate to the outer catch
                                    // which would send a generic apology instructing the customer to restart.
                                }
                            }
                        } else if (WhatsAppConstants.MESSAGE_TYPE_TEXT.equals(messageType)) {
                            if (phoneNumber != null && phoneNumber.equals(warehousePhoneNumber)) {
                                warehouseService.sendPendingOrdersInbox(phoneNumber);
                                return ResponseEntity.ok().build();
                            }

                            String command = message.getText().getBody().trim().toUpperCase();

                            if ("DISPATCH".equals(command)) {
                                if (phoneNumber != null && authorizedRiders.contains(phoneNumber)) {
                                    batchingService.createManualBatchRun(phoneNumber);
                                } else {
                                    log.warn("SECURITY: Unauthorized attempt to DISPATCH from: {}", phoneNumber);
                                    outboundClient.sendTextUpdate(phoneNumber, "⚠️ You are not authorized to dispatch orders.");
                                }
                            } else {
                                // Send the static catalog instruction message
                                outboundClient.sendCatalogMessage(phoneNumber);
                            }
                        } else if (WhatsAppConstants.MESSAGE_TYPE_LOCATION.equals(messageType)) {
                            Double lat = message.getLocation().getLatitude();
                            Double lng = message.getLocation().getLongitude();
                            orderService.updateCustomerLocation(primaryCustomerKey, lat, lng);
                        } else if (WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE.equals(messageType)) {
                            if (message.getInteractive().getButton_reply() != null) {
                                String buttonId = message.getInteractive().getButton_reply().getId();

                                if (buttonId.startsWith(WhatsAppConstants.REUSE_LOCATION)) {
                                    Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.REUSE_LOCATION, ""));
                                    orderService.confirmSavedLocation(orderId, phoneNumber);
                                } else if (buttonId.startsWith(WhatsAppConstants.NEW_LOCATION)) {
                                    outboundClient.sendLocationRequestPrompt(phoneNumber);
                                } else if (buttonId.startsWith(WhatsAppConstants.CONFIRM_ORDER) || buttonId.startsWith(WhatsAppConstants.CONFIRM_CHECKOUT)) {
                                    String idStr = buttonId.startsWith(WhatsAppConstants.CONFIRM_ORDER)
                                            ? buttonId.replace(WhatsAppConstants.CONFIRM_ORDER, "")
                                            : buttonId.replace(WhatsAppConstants.CONFIRM_CHECKOUT, "");
                                    Long orderId = Long.parseLong(idStr);
                                    try {
                                        orderService.finalizeOrderCheckout(orderId);
                                    } catch (com.localcommerce.delivery.exception.OrderValidationException e) {
                                        log.warn("Checkout validation failed for order {}: {}", orderId, e.getMessage());
                                        outboundClient.sendTextUpdate(phoneNumber, e.getMessage());
                                    }
                                } else if (buttonId.startsWith(WhatsAppConstants.EDIT_ORDER)) {
                                    Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.EDIT_ORDER, ""));
                                    CancellationResult res = orderService.editOrder(orderId);
                                    if (!res.isSuccess()) {
                                        outboundClient.sendTextUpdate(phoneNumber, res.message());
                                    }
                                } else if (buttonId.startsWith(WhatsAppConstants.CANCEL_ORDER)) {
                                    Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.CANCEL_ORDER, ""));
                                    CancellationResult res = orderService.cancelOrder(orderId, "Customer Cancelled", com.localcommerce.delivery.enums.CancellationActor.CUSTOMER);
                                    if (!res.isSuccess()) {
                                        outboundClient.sendTextUpdate(phoneNumber, res.message());
                                    }
                                } else if (buttonId.startsWith(WhatsAppConstants.RIDER_CANCEL_REASON)) {
                                    String remainder = buttonId.replace(WhatsAppConstants.RIDER_CANCEL_REASON, "");
                                    int lastUnder = remainder.lastIndexOf('_');
                                    String reasonCode = remainder.substring(0, lastUnder);
                                    Long orderId = Long.parseLong(remainder.substring(lastUnder + 1));
                                    String reasonDisplay = switch (reasonCode) {
                                        case "UNREACHABLE" -> "Customer Unreachable";
                                        case "WRONG_ADDR" -> "Address Not Found";
                                        case "REJECTED" -> "Customer Refused";
                                        default -> reasonCode;
                                    };
                                    CancellationResult res = orderService.cancelOrder(orderId, reasonDisplay, com.localcommerce.delivery.enums.CancellationActor.RIDER);
                                    if (res.isSuccess()) {
                                        outboundClient.sendTextUpdate(phoneNumber, "✅ Order #" + orderId + " delivery cancelled.");
                                    } else {
                                        outboundClient.sendTextUpdate(phoneNumber, "⚠️ " + res.message());
                                    }
                                } else if (buttonId.startsWith(WhatsAppConstants.RIDER_CANCEL)) {
                                    Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.RIDER_CANCEL, ""));
                                    outboundClient.sendRiderCancellationReasonsPrompt(phoneNumber, orderId);
                                } else if (buttonId.startsWith(WhatsAppConstants.SETLOC_ALL_PREFIX)) {
                                    String locPayload = buttonId.replace(WhatsAppConstants.SETLOC_ALL_PREFIX, "");
                                    String[] parts = locPayload.split("_");
                                    Double lat = Double.parseDouble(parts[0]);
                                    Double lng = Double.parseDouble(parts[1]);
                                    List<Long> orderIds = new java.util.ArrayList<>();
                                    for (int i = 2; i < parts.length; i++) {
                                        orderIds.add(Long.parseLong(parts[i]));
                                    }
                                    orderService.assignLocationToAllOrders(orderIds, phoneNumber, lat, lng);
                                } else if (buttonId.startsWith(WhatsAppConstants.SETLOC_PREFIX)) {
                                    String locPayload = buttonId.replace(WhatsAppConstants.SETLOC_PREFIX, "");
                                    String[] parts = locPayload.split("_");
                                    Long orderId = Long.parseLong(parts[0]);
                                    Double lat = Double.parseDouble(parts[1]);
                                    Double lng = Double.parseDouble(parts[2]);
                                    orderService.assignLocationToOrder(orderId, phoneNumber, lat, lng);
                                } else if (buttonId.startsWith(WhatsAppConstants.ACCEPT_ORDER) ||
                                        buttonId.startsWith(WhatsAppConstants.STOCK_ISSUE) ||
                                        buttonId.startsWith(WhatsAppConstants.READY_FOR_PICKUP)) {

                                    warehouseService.processButtonTap(phoneNumber, buttonId);
                                } else if (buttonId.startsWith(WhatsAppConstants.SET_DELIVERED)) {
                                    Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.SET_DELIVERED, ""));
                                    orderService.completeDelivery(orderId);
                                }
                            } else if (message.getInteractive().getList_reply() != null) {
                                String listId = message.getInteractive().getList_reply().getId();
                                if (listId.startsWith(WhatsAppConstants.VIEW_ORDER)) {
                                    warehouseService.processListReply(phoneNumber, listId);
                                } else if (listId.startsWith(WhatsAppConstants.SETLOC_ALL_PREFIX)) {
                                    String locPayload = listId.replace(WhatsAppConstants.SETLOC_ALL_PREFIX, "");
                                    String[] parts = locPayload.split("_");
                                    Double lat = Double.parseDouble(parts[0]);
                                    Double lng = Double.parseDouble(parts[1]);
                                    List<Long> orderIds = new java.util.ArrayList<>();
                                    for (int i = 2; i < parts.length; i++) {
                                        orderIds.add(Long.parseLong(parts[i]));
                                    }
                                    orderService.assignLocationToAllOrders(orderIds, phoneNumber, lat, lng);
                                } else if (listId.startsWith(WhatsAppConstants.SETLOC_PREFIX)) {
                                    String locPayload = listId.replace(WhatsAppConstants.SETLOC_PREFIX, "");
                                    String[] parts = locPayload.split("_");
                                    Long orderId = Long.parseLong(parts[0]);
                                    Double lat = Double.parseDouble(parts[1]);
                                    Double lng = Double.parseDouble(parts[2]);
                                    orderService.assignLocationToOrder(orderId, phoneNumber, lat, lng);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("CRITICAL WEBHOOK FAILURE: Error processing WhatsApp message from {}: {}", phoneNumber, e.getMessage(), e);
                        sendFallbackApology(phoneNumber);
                    } finally {
                        UserContext.clear();
                    }

                } else if (value.getStatuses() != null && !value.getStatuses().isEmpty()) {
                    // Branch B: delivery status updates from Meta (send failures, read receipts, etc.)
                    // Item 3 fix: this branch was previously nested inside Branch A and was
                    // unreachable for status-only events where messages == null.
                    WhatsAppWebhookPayload.Status status = value.getStatuses().getFirst();

                    if ("failed".equals(status.getStatus()) && status.getErrors() != null) {
                        WhatsAppWebhookPayload.Error error = status.getErrors().getFirst();
                        log.error("WHATSAPP SEND FAILED — Recipient: {}, Code: {}, Reason: {}",
                                status.getRecipient_id(),
                                error.getCode(),
                                error.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook payload", e);
        } finally {
            UserContext.clear();
        }
        return ResponseEntity.ok().build();
    }

    private void sendFallbackApology(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return;
        }
        try {
            outboundClient.sendTextUpdate(phoneNumber, com.localcommerce.delivery.exception.GlobalExceptionHandler.FALLBACK_APOLOGY_MESSAGE);
        } catch (Exception ex) {
            log.error("Failed to send fallback message to {}: {}", phoneNumber, ex.getMessage());
        }
    }


}
