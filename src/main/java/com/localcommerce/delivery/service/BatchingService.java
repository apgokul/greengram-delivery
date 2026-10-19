package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.DeliveryRun;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
// ... imports ...
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BatchingService {

    private final CustomerOrderRepository orderRepo;
    private final WhatsAppOutboundClient outboundClient;
    // You would inject your DeliveryRunRepository here as well
    private final DeliveryRunRepository deliveryRepo;

    // Inside com.localcommerce.delivery.service.BatchingService

    // Remove the @Scheduled annotation!

    @Transactional
    public void createManualBatchRun(String riderPhoneNumber) {
        log.info("Starting Manual Batch Routing for Rider: {}", riderPhoneNumber);

        // 1. Find all bags that are packed and sitting on the rack
        List<CustomerOrder> readyOrders = orderRepo.findByStatus(OrderStatus.READY);

        if (readyOrders.isEmpty()) {
            outboundClient.sendTextUpdate(riderPhoneNumber, "⚠️ No orders are currently ready for dispatch.");
            return;
        }

        // 2. Create the Delivery Run
        DeliveryRun run = new DeliveryRun();
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run.setDispatchedAt(LocalDateTime.now());
        run.setRiderPhoneNumber(riderPhoneNumber);

        run = deliveryRepo.save(run);

        // Send the manifest first so it appears at the top of the chat
        sendManifestToRider(run, readyOrders);

        // 3. Attach orders to the run
        for (CustomerOrder order : readyOrders) {
            order.setDeliveryRun(run);
            order.advanceStateTo(OrderStatus.DISPATCHED);
            orderRepo.save(order);

            // Fire ALERT 2 to the customer
            outboundClient.sendOutForDeliveryAlert(
                    order.getCustomer().getWhatsappNumber(),
                    run.getRiderPhoneNumber(),
                    "45" // Same-day ETA
            );

            // Send the digital manifest card to the rider for this specific order
            outboundClient.sendRiderDeliveryCard(riderPhoneNumber, order);
        }
    }

    // Inside com.localcommerce.delivery.service.BatchingService

    private void sendManifestToRider(DeliveryRun run, List<CustomerOrder> orders) {
        log.info("Generating summary manifest for Run ID: {}", run.getId());

        // 1. Calculate aggregate metrics for the run
        int totalDrops = orders.size();
        java.math.BigDecimal totalCashToCollect = orders.stream()
                .map(CustomerOrder::getTotalAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        // 2. Build the Header using a StringBuilder for performance
        StringBuilder manifestBuilder = new StringBuilder();
        manifestBuilder.append(String.format(
                WhatsAppConstants.RIDER_MANIFEST_HEADER_TEMPLATE,
                run.getId(),
                totalDrops,
                totalCashToCollect
        ));

        // 3. Append each order as a checklist item
        int itemNumber = 1;
        for (CustomerOrder order : orders) {
            String itemLine = String.format(
                    WhatsAppConstants.RIDER_MANIFEST_ITEM_TEMPLATE,
                    itemNumber++,
                    order.getDisplayId(),
                    order.getTotalAmount().toString()
            );
            if (order.getDeliveryLatitude() == null) {
                itemLine = itemLine.trim() + " " + WhatsAppConstants.LOCATION_REQUIRED_BADGE + "\n\n";
            }
            manifestBuilder.append(itemLine);
        }

        // 4. Append the footer
        manifestBuilder.append(WhatsAppConstants.RIDER_MANIFEST_FOOTER);

        // 5. Send the complete text string as a single unified update to the rider
        outboundClient.sendTextUpdate(run.getRiderPhoneNumber(), manifestBuilder.toString());
    }
}