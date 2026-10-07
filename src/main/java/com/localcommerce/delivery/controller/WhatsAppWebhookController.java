package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
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
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public WhatsAppWebhookController(OrderService orderService, WhatsAppOutboundClient outboundClient, WarehouseService warehouseService, BatchingService batchingService) {
        this.orderService = orderService;
        this.outboundClient = outboundClient;
        this.warehouseService = warehouseService;
        this.batchingService = batchingService;
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

                    // 1. Extract Phone Number
                    String phoneNumber = message.getFrom();

                    // 2. Extract Contact Info safely
                    String bsuid = null;
                    String whatsappName = "Valued Customer";

                    if (value.getContacts() != null && !value.getContacts().isEmpty()) {
                        WhatsAppWebhookPayload.Contact contact = value.getContacts().getFirst();
                        bsuid = contact.getUser_id();
                        if (contact.getProfile() != null && contact.getProfile().getName() != null) {
                            whatsappName = contact.getProfile().getName();
                        }
                    }

                    // Fallback: If BSUID isn't present (e.g. in legacy tests), use phone number as target ID
                    String primaryCustomerKey = (bsuid != null && !bsuid.isBlank()) ? bsuid : phoneNumber;

                    // Set context thread for logging
                    UserContext.setSenderId(primaryCustomerKey);

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
                            // Stock failed. Send the exact error message back to the customer.
                            outboundClient.sendTextUpdate(phoneNumber, result.getMessage());
                        } else {
                            // Success! Get the saved order and customer
                            CustomerOrder savedOrder = result.getOrder();
                            Customer customer = savedOrder.getCustomer();

                            // Check if we already have their location on file
                            if (customer.getLatitude() != null && customer.getLongitude() != null) {
                                String savedLocation = String.format("https://maps.google.com/?q=%s,%s", customer.getLatitude(), customer.getLongitude());
                                outboundClient.sendReuseLocationPrompt(customer.getRecipientId(), savedOrder.getId(), savedLocation);
                            } else {
                                outboundClient.sendLocationRequestPrompt(customer.getRecipientId());
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
                                orderService.confirmSavedLocation(orderId);
                            } else if (buttonId.startsWith(WhatsAppConstants.NEW_LOCATION)) {
                                outboundClient.sendLocationRequestPrompt(phoneNumber);
                            } else if (buttonId.startsWith(WhatsAppConstants.CONFIRM_CHECKOUT)) {
                                Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.CONFIRM_CHECKOUT, ""));
                                try {
                                    orderService.finalizeOrderCheckout(orderId);
                                    // Optional: Send a quick "Sending to store..." text to the customer so they know it worked
                                    outboundClient.sendTextUpdate(phoneNumber, WhatsAppConstants.ORDER_PACKING_ALERT);
                                } catch (com.localcommerce.delivery.exception.OrderValidationException e) {
                                    log.warn("Checkout validation failed for order {}: {}", orderId, e.getMessage());
                                    outboundClient.sendTextUpdate(phoneNumber, e.getMessage());
                                }
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
                            }
                        }
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
        }
        return ResponseEntity.ok().build();
    }


}