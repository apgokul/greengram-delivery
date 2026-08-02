package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CartItemDTO;
import com.localcommerce.delivery.dto.OrderProcessing;
import com.localcommerce.delivery.dto.WhatsAppWebhookPayload;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.service.BatchingService;
import com.localcommerce.delivery.service.OrderService;
import com.localcommerce.delivery.service.WarehouseService;
import com.localcommerce.delivery.utils.UserContext;
import com.localcommerce.delivery.utils.WhatsAppConstants;
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

    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    @Value("#{'${authorized.rider.numbers}'.split(',')}")
    private List<String> authorizedRiders;

    private final OrderService orderService;
    private final WhatsAppOutboundClient outboundClient;
    private final WarehouseService warehouseService;
    private final BatchingService batchingService;
    private final ProductRepository productRepository;

    public WhatsAppWebhookController(OrderService orderService, WhatsAppOutboundClient outboundClient, WarehouseService warehouseService, BatchingService batchingService, ProductRepository productRepository) {
        this.orderService = orderService;
        this.outboundClient = outboundClient;
        this.warehouseService = warehouseService;
        this.batchingService = batchingService;
        this.productRepository = productRepository;
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
    public ResponseEntity<Void> receiveMessage(@RequestBody WhatsAppWebhookPayload payload) {

        try {
            if (payload.getEntry() != null &&
                    !payload.getEntry().isEmpty() &&
                    payload.getEntry().getFirst().getChanges() != null &&
                    !payload.getEntry().getFirst().getChanges().isEmpty() &&
                    payload.getEntry().getFirst().getChanges().getFirst().getValue().getMessages() != null &&
                    !payload.getEntry().getFirst().getChanges().getFirst().getValue().getMessages().isEmpty()) {

                var value = payload.getEntry().getFirst().getChanges().getFirst().getValue();
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
                        outboundClient.sendTextUpdate(primaryCustomerKey, result.getMessage());
                    } else {
                        // Success! Get the saved order and customer
                        CustomerOrder savedOrder = result.getOrder();
                        Customer customer = savedOrder.getCustomer();

                        // Check if we already have their location on file
                        if (customer.getLatitude() != null && customer.getLongitude() != null) {
                            outboundClient.sendReuseLocationPrompt(customer.getRecipientId(), savedOrder.getId());
                        } else {
                            outboundClient.sendLocationRequestPrompt(customer.getRecipientId());
                        }
                    }
                }
                else if (WhatsAppConstants.MESSAGE_TYPE_TEXT.equals(messageType)) {
                    String command = message.getText().getBody().trim().toUpperCase();

                    if ("DISPATCH".equals(command)) {
                        if (phoneNumber != null && authorizedRiders.contains(phoneNumber)) {
                            String resultMessage = batchingService.createManualBatchRun(phoneNumber);
                            outboundClient.sendTextUpdate(phoneNumber, resultMessage);
                        } else {
                            log.warn("SECURITY: Unauthorized attempt to DISPATCH from: {}", phoneNumber);
                            outboundClient.sendTextUpdate(phoneNumber, "⚠️ You are not authorized to dispatch orders.");
                        }
                    } else {
                        // 1. Determine the target ID (Prefer phone number over BSUID)
                        String targetId = (phoneNumber != null && !phoneNumber.isBlank()) ? phoneNumber : primaryCustomerKey;

                        // 2. Fetch all products from your DB
                        List<Product> allProducts = productRepository.findAll();

                        // 3. Extract SKUs only for items that actually have inventory!
                        List<String> availableSkus = allProducts.stream()
                                .filter(p -> p.getStockQuantity() != null && p.getStockQuantity() > 0)
                                .map(Product::getSku)
                                .toList();

                        // 4. Send the dynamic catalog!
                        if (!availableSkus.isEmpty()) {
                            outboundClient.sendCatalogMessage(targetId, availableSkus);
                        } else {
                            outboundClient.sendTextUpdate(targetId, "We are currently completely sold out! Please check back later.");
                        }
                    }
                }
                else if (WhatsAppConstants.MESSAGE_TYPE_LOCATION.equals(messageType)) {
                    Double lat = message.getLocation().getLatitude();
                    Double lng = message.getLocation().getLongitude();
                    orderService.updateCustomerLocation(primaryCustomerKey, lat, lng);
                }
                else if (WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE.equals(messageType)) {
                    String buttonId = message.getInteractive().getButton_reply().getId();

                    if (buttonId.startsWith(WhatsAppConstants.REUSE_LOCATION)) {
                        Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.REUSE_LOCATION, ""));
                        orderService.confirmSavedLocation(orderId);
                    }
                    else if (buttonId.startsWith(WhatsAppConstants.NEW_LOCATION)) {
                        outboundClient.sendLocationRequestPrompt(primaryCustomerKey);
                    }
                    else if (buttonId.startsWith(WhatsAppConstants.ACCEPT_ORDER)) {
                        warehouseService.processButtonTap(phoneNumber, buttonId);
                    }
                    else if (buttonId.startsWith(WhatsAppConstants.SET_DELIVERED)) {
                        Long orderId = Long.parseLong(buttonId.replace(WhatsAppConstants.SET_DELIVERED, ""));
                        orderService.completeDelivery(orderId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook payload", e);
        }

        return ResponseEntity.ok().build();
    }
}