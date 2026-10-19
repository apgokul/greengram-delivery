package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import com.localcommerce.delivery.model.Inventory;
import com.localcommerce.delivery.repository.InventoryRepository;
import com.localcommerce.delivery.utils.UnitConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final CustomerOrderRepository orderRepo;
    private final WhatsAppOutboundClient outboundClient;
    private final InventoryRepository inventoryRepo;
    private final WarehouseAlertService warehouseAlertService;
    private final TransactionTemplate transactionTemplate;
    private final OrderService orderService;

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
        CustomerOrder order = transactionTemplate.execute(status -> {
            CustomerOrder o = orderRepo.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
            o.advanceStateTo(OrderStatus.PACKING);
            return orderRepo.save(o);
        });

        // Send the NEXT button to the warehouse
        outboundClient.sendBagReadyPrompt(warehousePhoneNumber, order);
    }

    private void handleBagReady(Long orderId) {
        transactionTemplate.execute(status -> {
            CustomerOrder o = orderRepo.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
            o.advanceStateTo(OrderStatus.READY);
            o.setReadyAt(LocalDateTime.now());
            orderRepo.save(o);
            return null;
        });

        log.info("Triggering Routing Engine for Order: {}", orderId);
    }

    private void handleStockIssue(Long orderId) {
        orderService.cancelOrder(orderId, "Warehouse Stock Issue", com.localcommerce.delivery.enums.CancellationActor.WAREHOUSE);
    }

    // Helper method to safely pull the database ID out of the Meta payload string
    private Long extractId(String buttonId, String prefix) {
        return Long.parseLong(buttonId.replace(prefix, ""));
    }

}