package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final CustomerOrderRepository orderRepo;
    private final WhatsAppOutboundClient outboundClient;
    private final ProductRepository productRepo;
    private final WarehouseAlertService warehouseAlertService;

    // The single phone number sitting on your packing table
    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    public void sendPendingOrdersInbox(String senderNumber) {
        if (!senderNumber.equals(warehousePhoneNumber)) {
            return;
        }

        java.util.List<CustomerOrder> pendingOrders = orderRepo.findByStatus(OrderStatus.PENDING_WAREHOUSE);

        if (pendingOrders.isEmpty()) {
            outboundClient.sendTextUpdate(warehousePhoneNumber, "No pending warehouse orders.");
        } else {
            outboundClient.sendPendingOrdersList(warehousePhoneNumber, pendingOrders);
        }
    }

    public void processListReply(String senderNumber, String listId) {
        if (!senderNumber.equals(warehousePhoneNumber)) {
            System.err.println("Unauthorized list reply attempt from: " + senderNumber);
            return;
        }

        if (listId.startsWith(WhatsAppConstants.VIEW_ORDER)) {
            Long orderId = extractId(listId, WhatsAppConstants.VIEW_ORDER);
            CustomerOrder order = orderRepo.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found"));
            
            if (order.getStatus() == OrderStatus.PENDING_WAREHOUSE) {
                warehouseAlertService.sendNewOrderAlert(order);
            } else {
                outboundClient.sendTextUpdate(warehousePhoneNumber, "Order #" + order.getDisplayId() + " is no longer pending.");
            }
        }
    }

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

        // 2. Send the NEXT button to the warehouse
        outboundClient.sendBagReadyPrompt(warehousePhoneNumber, order);
    }

    private void handleBagReady(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 1. Move state to READY
        order.advanceStateTo(OrderStatus.READY);
        order.setReadyAt(LocalDateTime.now());
        orderRepo.save(order);

        log.info("Triggering Routing Engine for Order: {}", orderId);
    }
    private void handleStockIssue(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // 1. Restore the inventory for every item in the cart!
        for (OrderItem item : order.getItems()) {
            // Using the pessimistic lock to safely put stock back
            Product product = productRepo.findBySkuForUpdate(item.getProduct().getSku())
                    .orElseThrow(() -> new RuntimeException("Product missing: " + item.getProduct().getSku()));

            product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
            productRepo.save(product);
        }

        // 2. Cancel the order
        order.advanceStateTo(OrderStatus.CANCELLED);
        orderRepo.save(order);

        // 3. Alert the customer
        String customerPhone = order.getCustomer().getWhatsappNumber();
        outboundClient.sendTextUpdate(customerPhone, WhatsAppConstants.STOCK_ISSUE_CANCELLATION);
    }

    // Helper method to safely pull the database ID out of the Meta payload string
    private Long extractId(String buttonId, String prefix) {
        return Long.parseLong(buttonId.replace(prefix, ""));
    }

}