package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final CustomerOrderRepository orderRepo;
    private final WhatsAppOutboundClient outboundClient;


    // The single phone number sitting on your packing table
    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    @Transactional
    public void processButtonTap(String senderNumber, String buttonId) {

        // SECURITY CHECK: Ensure this button tap actually came from your warehouse phone
        // WhatsApp doesn't send the '+' in the webhook sender number
        if (!senderNumber.equals(warehousePhoneNumber)) {
            System.err.println("Unauthorized button tap attempt from: " + senderNumber);
            return;
        }

        // ROUTE THE ACTION
        if (buttonId.startsWith(WhatsAppConstants.ACCEPT_ORDER)) {
            handleAcceptOrder(extractId(buttonId, WhatsAppConstants.ACCEPT_ORDER));
        }
        else if (buttonId.startsWith(WhatsAppConstants.READY_FOR_PICKUP)) {
            handleBagReady(extractId(buttonId, WhatsAppConstants.READY_FOR_PICKUP));
        }
        else if (buttonId.startsWith(WhatsAppConstants.STOCK_ISSUE)) {
            handleStockIssue(extractId(buttonId, WhatsAppConstants.STOCK_ISSUE));
        }
    }

    private void handleAcceptOrder(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 1. Move state forward
        order.advanceStateTo(OrderStatus.PACKING); // Jumping straight to packing since they accepted it
        orderRepo.save(order);

        // 2. Alert the customer (keeps them engaged)
        String customerPhone = order.getCustomer().getWhatsappNumber();
        outboundClient.sendOrderConfirmedAlert(customerPhone);
        // 3. Send the NEXT button to the warehouse
        outboundClient.sendBagReadyPrompt(warehousePhoneNumber, order);
    }

    private void handleBagReady(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 1. Move state to READY
        order.advanceStateTo(OrderStatus.READY);
        orderRepo.save(order);

        log.info("Triggering Routing Engine for Order: {}", orderId);
    }

    private void handleStockIssue(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        order.advanceStateTo(OrderStatus.CANCELLED);
        orderRepo.save(order);

        String customerPhone = order.getCustomer().getWhatsappNumber();
        outboundClient.sendTextUpdate(customerPhone, WhatsAppConstants.STOCK_ISSUE_CANCELLATION);
    }

    // Helper method to safely pull the database ID out of the Meta payload string
    private Long extractId(String buttonId, String prefix) {
        return Long.parseLong(buttonId.replace(prefix, ""));
    }

    @Transactional
    public void removeItemFromOrder(Long orderId, int itemIndexToRemove) {

        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 1. Remove the item (Indices are 0-based, human input is 1-based)
        List<OrderItem> items = order.getItems();
        if (itemIndexToRemove > 0 && itemIndexToRemove <= items.size()) {
            OrderItem removedItem = items.remove(itemIndexToRemove - 1);

            // 2. Recalculate the Total Bill
            BigDecimal newTotal = items.stream()
                    .map(item -> item.getLockedPrice().multiply(new BigDecimal(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            order.setTotalAmount(newTotal);
            order.advanceStateTo(OrderStatus.PACKING); // Force state to packing
            orderRepo.save(order);

            // 3. Alert the Customer transparently (Crucial for trust)
            String customerPhone = order.getCustomer().getWhatsappNumber();
            outboundClient.sendOrderEditedAlert(customerPhone, removedItem.getProduct().getName(), newTotal.toString());

            // 4. Send the "Bag Ready" button back to the warehouse so they can finish
            outboundClient.sendBagReadyPrompt(warehousePhoneNumber, order);
        }
    }
}