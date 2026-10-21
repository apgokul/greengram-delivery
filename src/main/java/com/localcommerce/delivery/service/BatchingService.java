package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.DeliveryRun;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BatchingService {

    private final CustomerOrderRepository orderRepo;
    private final WhatsAppOutboundClient outboundClient;
    private final DeliveryRunRepository deliveryRepo;
    private final TransactionTemplate transactionTemplate;

    public record OrderDispatchItem(
            Long id,
            String displayId,
            BigDecimal totalAmount,
            Double deliveryLatitude,
            Double deliveryLongitude,
            String customerName,
            String customerWhatsappNumber
    ) {
        public CustomerOrder toSnapshotOrder() {
            CustomerOrder snapshot = new CustomerOrder();
            snapshot.setId(id);
            snapshot.setDisplayId(displayId);
            snapshot.setTotalAmount(totalAmount != null ? totalAmount : BigDecimal.ZERO);
            snapshot.setDeliveryLatitude(deliveryLatitude);
            snapshot.setDeliveryLongitude(deliveryLongitude);
            Customer customer = new Customer();
            customer.setName(customerName);
            customer.setWhatsappNumber(customerWhatsappNumber);
            snapshot.setCustomer(customer);
            return snapshot;
        }
    }

    public record DispatchNotificationPlan(
            boolean hasEligibleOrders,
            Long runId,
            String riderPhoneNumber,
            String manifestText,
            List<OrderDispatchItem> items
    ) {
        public static DispatchNotificationPlan noEligibleOrders(String riderPhone) {
            return new DispatchNotificationPlan(false, null, riderPhone, null, List.of());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createManualBatchRun(String riderPhoneNumber) {
        log.info("Starting Manual Batch Routing for Rider: {}", riderPhoneNumber);

        // 1. Execute candidate locking, eligibility revalidation, DeliveryRun creation,
        // and order status transitions in one database transaction.
        DispatchNotificationPlan plan = transactionTemplate.execute(status -> {
            // Fetch candidate READY order IDs sorted in ascending order to prevent deadlocks
            List<Long> candidateIds = orderRepo.findIdsByStatusOrderByIdAsc(OrderStatus.READY);

            if (candidateIds.isEmpty()) {
                return DispatchNotificationPlan.noEligibleOrders(riderPhoneNumber);
            }

            // Lock each candidate order under a pessimistic write lock in ascending ID order
            // and revalidate eligibility (must still be in READY status and unassigned to any DeliveryRun).
            List<CustomerOrder> dispatchableOrders = new ArrayList<>();
            for (Long orderId : candidateIds) {
                CustomerOrder lockedOrder = orderRepo.findByIdForUpdate(orderId).orElse(null);
                if (lockedOrder != null
                        && lockedOrder.getStatus() == OrderStatus.READY
                        && lockedOrder.getDeliveryRun() == null) {
                    dispatchableOrders.add(lockedOrder);
                } else {
                    log.info("Order {} is no longer eligible for dispatch (status: {}, run: {}). Skipping.",
                            orderId,
                            lockedOrder != null ? lockedOrder.getStatus() : "null",
                            lockedOrder != null && lockedOrder.getDeliveryRun() != null ? lockedOrder.getDeliveryRun().getId() : "none");
                }
            }

            // If no orders remain eligible after locking and revalidation, abort without creating a DeliveryRun
            if (dispatchableOrders.isEmpty()) {
                log.info("No orders remain eligible for dispatch for rider {}.", riderPhoneNumber);
                return DispatchNotificationPlan.noEligibleOrders(riderPhoneNumber);
            }

            // Create the Delivery Run for the confirmed dispatchable orders
            DeliveryRun run = new DeliveryRun();
            run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
            run.setDispatchedAt(LocalDateTime.now());
            run.setRiderPhoneNumber(riderPhoneNumber);
            run = deliveryRepo.save(run);

            // Attach confirmed orders to the run, update status, and populate immutable DTOs
            List<OrderDispatchItem> items = new ArrayList<>();
            for (CustomerOrder order : dispatchableOrders) {
                order.setDeliveryRun(run);
                order.advanceStateTo(OrderStatus.DISPATCHED);
                orderRepo.save(order);

                Customer customer = order.getCustomer();
                String customerName = (customer != null && customer.getName() != null) ? customer.getName() : "Customer";
                String customerPhone = (customer != null && customer.getWhatsappNumber() != null) ? customer.getWhatsappNumber() : null;

                items.add(new OrderDispatchItem(
                        order.getId(),
                        order.getDisplayId(),
                        order.getTotalAmount(),
                        order.getDeliveryLatitude(),
                        order.getDeliveryLongitude(),
                        customerName,
                        customerPhone
                ));
            }

            // Build manifest text inside transaction while entity data is active
            String manifestText = buildManifestText(run.getId(), items);

            return new DispatchNotificationPlan(true, run.getId(), riderPhoneNumber, manifestText, items);
        });

        if (plan == null) {
            return;
        }

        // 2. Post-commit: If no orders are ready, send notification to rider outside the transaction
        if (!plan.hasEligibleOrders()) {
            try {
                outboundClient.sendTextUpdate(riderPhoneNumber, "⚠️ No orders are currently ready for dispatch.");
            } catch (Exception e) {
                log.error("Failed to send no-ready-orders notification to rider {}: {}", riderPhoneNumber, e.getMessage());
            }
            return;
        }

        // 3. Post-commit: Send summary manifest first so it appears at top of chat
        try {
            outboundClient.sendTextUpdate(plan.riderPhoneNumber(), plan.manifestText());
        } catch (Exception e) {
            log.error("Failed to deliver summary manifest to rider {} for run {}: {}",
                    plan.riderPhoneNumber(), plan.runId(), e.getMessage());
        }

        // 4. Post-commit: For each order in ascending ID order, send customer alert then rider card
        for (OrderDispatchItem item : plan.items()) {
            if (item.customerWhatsappNumber() != null) {
                try {
                    outboundClient.sendOutForDeliveryAlert(
                            item.customerWhatsappNumber(),
                            plan.riderPhoneNumber(),
                            "45" // Same-day ETA
                    );
                } catch (Exception e) {
                    log.error("Failed to deliver out-for-delivery alert to customer for order {} (displayId: {}): {}",
                            item.id(), item.displayId(), e.getMessage());
                }
            } else {
                log.warn("Order {} (displayId: {}) is missing customer WhatsApp number; skipping customer alert",
                        item.id(), item.displayId());
            }

            try {
                outboundClient.sendRiderDeliveryCard(plan.riderPhoneNumber(), item.toSnapshotOrder());
            } catch (Exception e) {
                log.error("Failed to deliver rider delivery card for order {} (displayId: {}) to rider {}: {}",
                        item.id(), item.displayId(), plan.riderPhoneNumber(), e.getMessage());
            }
        }
    }

    private String buildManifestText(Long runId, List<OrderDispatchItem> items) {
        log.info("Generating summary manifest for Run ID: {}", runId);

        int totalDrops = items.size();
        BigDecimal totalCashToCollect = items.stream()
                .map(item -> item.totalAmount() != null ? item.totalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder manifestBuilder = new StringBuilder();
        manifestBuilder.append(String.format(
                WhatsAppConstants.RIDER_MANIFEST_HEADER_TEMPLATE,
                runId,
                totalDrops,
                totalCashToCollect
        ));

        int itemNumber = 1;
        for (OrderDispatchItem item : items) {
            String itemLine = String.format(
                    WhatsAppConstants.RIDER_MANIFEST_ITEM_TEMPLATE,
                    itemNumber++,
                    item.displayId(),
                    (item.totalAmount() != null ? item.totalAmount() : BigDecimal.ZERO).toString()
            );
            if (item.deliveryLatitude() == null) {
                itemLine = itemLine.trim() + " " + WhatsAppConstants.LOCATION_REQUIRED_BADGE + "\n\n";
            }
            manifestBuilder.append(itemLine);
        }

        manifestBuilder.append(WhatsAppConstants.RIDER_MANIFEST_FOOTER);
        return manifestBuilder.toString();
    }
}
