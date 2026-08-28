package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseAlertService {

    @Value("${whatsapp.api.url}")
    private String apiUrl;

    @Value("${whatsapp.api.token}")
    private String accessToken;

    // The single phone number sitting on your packing table
    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    private final RestTemplate restTemplate;

    private final WhatsAppOutboundClient outboundClient;

    public void sendNewOrderAlert(CustomerOrder order) {

        // 1. Format the items into a clean list for the packer to read
        // Example output: "1x Ashirvad Atta (5kg)\n2x Tata Salt"
        String itemsText = order.getItems().stream()
                .map(item -> item.getQuantity() + "x " + item.getProduct().getDisplayName())
                .collect(Collectors.joining("\n"));

        // 2. Build the WhatsApp message body
        String messageBody = String.format(
                WhatsAppConstants.WAREHOUSE_NEW_ORDER_ALERT_TEMPLATE,
                order.getDisplayId(),
                order.getCustomer().getName() + " (" + order.getCustomer().getWhatsappNumber() + ")",
                itemsText
        );

        // 3. Construct the nested JSON payload using Java Maps
        Map<String, Object> payload = Map.of(
                WhatsAppConstants.MESSAGING_PRODUCT, WhatsAppConstants.WHATSAPP,
                WhatsAppConstants.TO, warehousePhoneNumber,
                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_INTERACTIVE,
                WhatsAppConstants.INTERACTIVE, Map.of(
                        WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_BUTTON,
                        WhatsAppConstants.BODY, Map.of(WhatsAppConstants.TEXT, messageBody),
                        WhatsAppConstants.ACTION, Map.of(
                                WhatsAppConstants.BUTTONS, new Object[]{
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.ACCEPT_ORDER + order.getId(),
                                                        WhatsAppConstants.TITLE, WhatsAppConstants.WAREHOUSE_ACCEPT_ORDER_BUTTON
                                                )
                                        ),
                                        Map.of(
                                                WhatsAppConstants.TYPE, WhatsAppConstants.MESSAGE_TYPE_REPLY,
                                                WhatsAppConstants.REPLY, Map.of(
                                                        WhatsAppConstants.ID, WhatsAppConstants.STOCK_ISSUE + order.getId(),
                                                        WhatsAppConstants.TITLE, WhatsAppConstants.WAREHOUSE_STOCK_ISSUE_BUTTON
                                                )
                                        )
                                }
                        )
                )
        );

        // 4. Fire the HTTP request to Meta
        sendPostRequest(payload);
    }

    private void sendPostRequest(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForEntity(apiUrl, request, String.class);
            log.info("Alert sent to warehouse for processing.");
        } catch (Exception e) {
            // In a production environment, you would log this to a file
            // and potentially trigger a fallback SMS or email to the founders
            log.error("CRITICAL: Failed to ping warehouse. Error: {} ", e.getMessage());
        }
    }

    // Inside com.localcommerce.delivery.service.WarehouseAlertService

    public void sendLowStockAlert(Product product) {
        String alertMessage = String.format(
                WhatsAppConstants.LOW_STOCK_ALERT,
                product.getDisplayName(), product.getSku(), product.getStockQuantity()
        );

        // Send this directly to the warehouse phone number
        outboundClient.sendTextUpdate(warehousePhoneNumber, alertMessage);
    }
}