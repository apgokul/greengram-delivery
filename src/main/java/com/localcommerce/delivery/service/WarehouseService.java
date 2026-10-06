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

        // 1. Restore the inventory safely via TransactionTemplate
        transactionTemplate.execute(status -> {
            // Group the restoration quantities by Inventory
            Map<Inventory, BigDecimal> inventoryRestorationMap = new HashMap<>();
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                Inventory inv = product.getInventory();
                if (inv == null) continue;

                BigDecimal variantTotalQuantity = product.getPackageQuantity().multiply(BigDecimal.valueOf(item.getQuantity()));
                BigDecimal restoredQuantityInBaseUnit = UnitConverter.convert(variantTotalQuantity, product.getPackageUnit(), inv.getUnit());

                inventoryRestorationMap.merge(inv, restoredQuantityInBaseUnit, BigDecimal::add);
            }

            // Sort inventories by ID to prevent deadlocks
            List<Inventory> inventoriesToRestore = inventoryRestorationMap.keySet().stream()
                    .sorted(Comparator.comparing(Inventory::getId))
                    .toList();

            for (Inventory inv : inventoriesToRestore) {
                Inventory lockedInv = inventoryRepo.findByIdForUpdate(inv.getId())
                        .orElseThrow(() -> new RuntimeException("Inventory not found"));

                BigDecimal quantityToRestore = inventoryRestorationMap.get(inv);
                lockedInv.setStockQuantity(lockedInv.getStockQuantity().add(quantityToRestore));
                inventoryRepo.save(lockedInv);
            }
            
            return null;
        });

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