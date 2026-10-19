package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CancellationResult;
import com.localcommerce.delivery.dto.CartItemDTO;
import com.localcommerce.delivery.dto.OrderProcessing;
import com.localcommerce.delivery.enums.CancellationActor;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.model.DeliveryRun;
import com.localcommerce.delivery.utils.DeliveryConstants;
import com.localcommerce.delivery.utils.DistanceUtil;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
@Transactional
@Slf4j
public class OrderService {

    private final CustomerRepository customerRepo;
    private final ProductRepository productRepo;
    private final com.localcommerce.delivery.repository.InventoryRepository inventoryRepo;
    private final CustomerOrderRepository orderRepo;
    private final WhatsAppOutboundClient outboundClient;
    private final WarehouseAlertService warehouseAlertService;
    private final CustomerIdentityService customerIdentityService;
    private final DeliveryRunRepository deliveryRepo;
    private final TransactionTemplate transactionTemplate;

    @Value("${store.latitude}")
    private Double storeLat;

    @Value("${store.longitude}")
    private Double storeLng;

    @Value("${store.max.radius.km}")
    private Double maxRadiusKm;

    public OrderService(CustomerRepository customerRepo,
                        ProductRepository productRepo,
                        com.localcommerce.delivery.repository.InventoryRepository inventoryRepo,
                        CustomerOrderRepository orderRepo,
                        WhatsAppOutboundClient outboundClient,
                        WarehouseAlertService warehouseAlertService,
                        CustomerIdentityService customerIdentityService,
                        DeliveryRunRepository deliveryRepo,
                        PlatformTransactionManager transactionManager) {
        this.customerRepo = customerRepo;
        this.productRepo = productRepo;
        this.inventoryRepo = inventoryRepo;
        this.orderRepo = orderRepo;
        this.outboundClient = outboundClient;
        this.warehouseAlertService = warehouseAlertService;
        this.customerIdentityService = customerIdentityService;
        this.deliveryRepo = deliveryRepo;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional // 1. CRITICAL: Enforces a single atomic transaction & enables DB locks
    public OrderProcessing.OrderProcessResult processNewCart(String bsuid, String phoneNumber, String whatsappName, List<CartItemDTO> cartItems) {

        // 1. Resolve identity gracefully
        Customer customer = customerIdentityService.resolveCustomerIdentity(bsuid, phoneNumber, whatsappName);

        // 2. PASS 1: Validate stock for ALL items FIRST using Pessimistic Locks on Inventory
        java.util.Map<Product, Integer> requestedProducts = new java.util.HashMap<>();
        for (CartItemDTO dto : cartItems) {
            if (dto.getQuantity() <= 0) {
                return OrderProcessing.OrderProcessResult.failed("Sorry, invalid quantity requested.");
            }

            java.util.Optional<Product> optProduct = productRepo.findBySku(dto.getSku());
            if (optProduct.isEmpty()) {
                return OrderProcessing.OrderProcessResult.failed("Sorry, one or more items in your cart are currently unavailable.");
            }
            requestedProducts.put(optProduct.get(), dto.getQuantity());
        }

        java.util.List<com.localcommerce.delivery.model.Inventory> inventoriesToLock = requestedProducts.keySet().stream()
                .map(Product::getInventory)
                .distinct()
                .sorted(java.util.Comparator.comparing(com.localcommerce.delivery.model.Inventory::getId))
                .collect(java.util.stream.Collectors.toList());

        for (com.localcommerce.delivery.model.Inventory inv : inventoriesToLock) {
            inventoryRepo.findByIdForUpdate(inv.getId()).orElseThrow(
                () -> new RuntimeException("Inventory no longer available")
            );
        }

        java.util.Map<com.localcommerce.delivery.model.Inventory, BigDecimal> inventoryRequirements = new java.util.HashMap<>();
        java.util.Map<com.localcommerce.delivery.model.Inventory, Product> representativeProducts = new java.util.HashMap<>();

        for (java.util.Map.Entry<Product, Integer> entry : requestedProducts.entrySet()) {
            Product product = entry.getKey();
            Integer cartQty = entry.getValue();

            if (!product.isActive()) {
                return OrderProcessing.OrderProcessResult.failed("Sorry, " + product.getName() + " is currently out of stock.");
            }

            BigDecimal itemPhysicalQty = product.getPackageQuantity().multiply(BigDecimal.valueOf(cartQty));
            BigDecimal convertedQty = com.localcommerce.delivery.utils.UnitConverter.convert(itemPhysicalQty, product.getPackageUnit(), product.getInventory().getUnit());

            inventoryRequirements.merge(product.getInventory(), convertedQty, BigDecimal::add);
            representativeProducts.put(product.getInventory(), product);
        }

        for (java.util.Map.Entry<com.localcommerce.delivery.model.Inventory, BigDecimal> req : inventoryRequirements.entrySet()) {
            com.localcommerce.delivery.model.Inventory inv = req.getKey();
            com.localcommerce.delivery.model.Inventory lockedInv = inventoryRepo.findById(inv.getId()).get();
            BigDecimal required = req.getValue();
            Product repProduct = representativeProducts.get(inv);

            if (lockedInv.getStockQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                return OrderProcessing.OrderProcessResult.failed("Sorry, " + repProduct.getName() + " is currently out of stock.");
            }

            if (lockedInv.getStockQuantity().compareTo(required) < 0) {
                return OrderProcessing.OrderProcessResult.failed("Sorry, " + repProduct.getName() + " is currently unavailable in the requested quantity. Please try a smaller quantity.");
            }
        }

        // 3. PASS 2: Create shell order, deduct stock, and link line items
        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CREATED);

        BigDecimal initialCartTotal = BigDecimal.ZERO;

        for (CartItemDTO dto : cartItems) {
            Product product = productRepo.findBySku(dto.getSku()).get();

            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(dto.getQuantity());
            item.setLockedPrice(product.getCurrentPrice());
            item.setCustomerOrder(order);

            BigDecimal lineItemTotal = item.getLockedPrice().multiply(new BigDecimal(item.getQuantity()));
            initialCartTotal = initialCartTotal.add(lineItemTotal);

            order.getItems().add(item);
        }

        order.setTotalAmount(initialCartTotal);
        CustomerOrder savedOrder = orderRepo.save(order);

        return OrderProcessing.OrderProcessResult.success(savedOrder);
    }

    @Transactional
    public void updateCustomerLocation(String customerKey, Double lat, Double lng) {
        Customer customer = customerRepo.findByBsuid(customerKey)
                .orElseGet(() -> customerRepo.findByWhatsappNumber(customerKey)
                        .orElseThrow(() -> new RuntimeException("Customer not found for key: " + customerKey)));

        String recipientId = customer.getRecipientId();

        // Calculate distance & enforce radius limit
        double distanceKm = DistanceUtil.calculateDistance(storeLat, storeLng, lat, lng);
        log.info("Customer {} dropped pin at distance: {} km", customerKey, distanceKm);

        if (distanceKm > maxRadiusKm) {
            String outOfBoundsMessage = String.format(
                    WhatsAppConstants.OUT_OF_BOUNDS_TEMPLATE,
                    distanceKm, maxRadiusKm
            );
            outboundClient.sendTextUpdate(recipientId, outOfBoundsMessage);
            return;
        }

        // Update customer profile default location
        customer.setLatitude(lat);
        customer.setLongitude(lng);
        customerRepo.save(customer);

        List<OrderStatus> candidateStatuses = List.of(
                OrderStatus.CREATED, OrderStatus.PENDING_WAREHOUSE,
                OrderStatus.PACKING, OrderStatus.READY, OrderStatus.DISPATCHED
        );
        List<CustomerOrder> candidateOrders = orderRepo.findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(
                customer.getId(), candidateStatuses
        );

        if (candidateOrders.isEmpty()) {
            outboundClient.sendTextUpdate(recipientId, "📍 Your location has been saved for your future orders.");
        } else if (candidateOrders.size() == 1) {
            CustomerOrder singleOrder = candidateOrders.get(0);
            transactionTemplate.execute(status -> {
                CustomerOrder lockedOrder = orderRepo.findByIdForUpdate(singleOrder.getId())
                        .orElseThrow(() -> new RuntimeException("Order not found: " + singleOrder.getId()));
                if (lockedOrder.getDeliveryLatitude() == null) {
                    lockedOrder.setDeliveryLatitude(lat);
                    lockedOrder.setDeliveryLongitude(lng);
                    orderRepo.save(lockedOrder);
                }
                return null;
            });
            try {
                outboundClient.sendTextUpdate(recipientId, WhatsAppConstants.LOCATION_CONFIRMED_NATURAL);
            } catch (Exception e) {
                log.warn("Failed to deliver location confirmation alert to {}: {}", recipientId, e.getMessage());
            }
        } else {
            // Disambiguation prompt for multiple pending orders
            try {
                outboundClient.sendLocationDisambiguationPrompt(recipientId, candidateOrders, lat, lng);
            } catch (Exception e) {
                log.warn("Failed to deliver location disambiguation prompt to {}: {}", recipientId, e.getMessage());
            }
        }
    }

    public void assignLocationToOrder(Long orderId, String senderNumber, Double lat, Double lng) {
        double distanceKm = DistanceUtil.calculateDistance(storeLat, storeLng, lat, lng);
        if (distanceKm > maxRadiusKm) {
            String outOfBoundsMessage = String.format(
                    WhatsAppConstants.OUT_OF_BOUNDS_TEMPLATE,
                    distanceKm, maxRadiusKm
            );
            if (senderNumber != null) {
                try {
                    outboundClient.sendTextUpdate(senderNumber, outOfBoundsMessage);
                } catch (Exception e) {
                    log.warn("Failed to deliver out-of-bounds message to {}: {}", senderNumber, e.getMessage());
                }
            }
            return;
        }

        String confirmationMsg = transactionTemplate.execute(status -> {
            CustomerOrder order = orderRepo.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            Customer customer = order.getCustomer();
            String storedNumber = (customer != null) ? customer.getWhatsappNumber() : null;
            if (storedNumber == null || senderNumber == null || !storedNumber.equals(senderNumber)) {
                log.warn("Unauthorized location assignment attempt for order {} by {}", orderId, senderNumber);
                return "Unauthorized order location update.";
            }

            if (order.getDeliveryLatitude() != null) {
                log.info("Order {} already has delivery location assigned. Preserving existing.", orderId);
                return "Order #" + order.getDisplayId() + " already has a confirmed delivery location.";
            }

            order.setDeliveryLatitude(lat);
            order.setDeliveryLongitude(lng);
            orderRepo.save(order);

            if (customer != null) {
                customer.setLatitude(lat);
                customer.setLongitude(lng);
                customerRepo.save(customer);
            }

            return WhatsAppConstants.LOCATION_CONFIRMED_NATURAL;
        });

        if (confirmationMsg != null && senderNumber != null) {
            try {
                outboundClient.sendTextUpdate(senderNumber, confirmationMsg);
            } catch (Exception e) {
                log.warn("Failed to send outbound WhatsApp confirmation to {}: {}", senderNumber, e.getMessage());
            }
        }
    }

    public void assignLocationToAllOrders(List<Long> orderIds, String senderNumber, Double lat, Double lng) {
        double distanceKm = DistanceUtil.calculateDistance(storeLat, storeLng, lat, lng);
        if (distanceKm > maxRadiusKm) {
            String outOfBoundsMessage = String.format(
                    WhatsAppConstants.OUT_OF_BOUNDS_TEMPLATE,
                    distanceKm, maxRadiusKm
            );
            if (senderNumber != null) {
                try {
                    outboundClient.sendTextUpdate(senderNumber, outOfBoundsMessage);
                } catch (Exception e) {
                    log.warn("Failed to deliver out-of-bounds message to {}: {}", senderNumber, e.getMessage());
                }
            }
            return;
        }

        // Sort order IDs in ascending order to prevent deadlocks
        List<Long> sortedIds = orderIds.stream().sorted().toList();

        transactionTemplate.execute(status -> {
            for (Long orderId : sortedIds) {
                CustomerOrder order = orderRepo.findByIdForUpdate(orderId).orElse(null);
                if (order == null) continue;

                Customer customer = order.getCustomer();
                String storedNumber = (customer != null) ? customer.getWhatsappNumber() : null;
                if (storedNumber == null || senderNumber == null || !storedNumber.equals(senderNumber)) {
                    log.warn("Unauthorized location assignment for order {} by {}", orderId, senderNumber);
                    continue;
                }

                if (order.getDeliveryLatitude() == null) {
                    order.setDeliveryLatitude(lat);
                    order.setDeliveryLongitude(lng);
                    orderRepo.save(order);
                }
            }

            if (senderNumber != null) {
                customerRepo.findByWhatsappNumber(senderNumber).ifPresent(c -> {
                    c.setLatitude(lat);
                    c.setLongitude(lng);
                    customerRepo.save(c);
                });
            }

            return null;
        });

        if (senderNumber != null) {
            try {
                outboundClient.sendTextUpdate(senderNumber, WhatsAppConstants.LOCATION_CONFIRMED_NATURAL);
            } catch (Exception e) {
                log.warn("Failed to deliver location confirmation to {}: {}", senderNumber, e.getMessage());
            }
        }
    }

    @Transactional
    public void confirmSavedLocation(Long orderId) {
        CustomerOrder order = orderRepo.findByIdForUpdate(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        Customer customer = order.getCustomer();

        if (customer.getLatitude() != null && customer.getLongitude() != null) {
            order.setDeliveryLatitude(customer.getLatitude());
            order.setDeliveryLongitude(customer.getLongitude());
            orderRepo.save(order);
            try {
                outboundClient.sendTextUpdate(customer.getRecipientId(), WhatsAppConstants.LOCATION_CONFIRMED_NATURAL);
            } catch (Exception e) {
                log.warn("Failed to deliver saved location confirmation to {}: {}", customer.getRecipientId(), e.getMessage());
            }
        } else {
            try {
                outboundClient.sendDeliveryLocationPrompt(customer.getRecipientId());
            } catch (Exception e) {
                log.warn("Failed to send location prompt to {}: {}", customer.getRecipientId(), e.getMessage());
            }
        }
    }

    public static class CheckoutResult {
        public final CustomerOrder order;
        public final List<com.localcommerce.delivery.model.Inventory> lowStockInventories;
        public final boolean locationPromptNeeded;
        public final boolean hasSavedLocation;
        public final Double savedLat;
        public final Double savedLng;

        public CheckoutResult(CustomerOrder order,
                              List<com.localcommerce.delivery.model.Inventory> lowStockInventories,
                              boolean locationPromptNeeded,
                              boolean hasSavedLocation,
                              Double savedLat,
                              Double savedLng) {
            this.order = order;
            this.lowStockInventories = lowStockInventories;
            this.locationPromptNeeded = locationPromptNeeded;
            this.hasSavedLocation = hasSavedLocation;
            this.savedLat = savedLat;
            this.savedLng = savedLng;
        }
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.NOT_SUPPORTED)
    public void finalizeOrderCheckout(Long orderId) {
        CheckoutResult result = transactionTemplate.execute(status -> {
            // Level 1 Lock: CustomerOrder
            CustomerOrder order = orderRepo.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // Idempotency guard — prevents double-processing if the customer taps Confirm twice
            if (order.getStatus() != OrderStatus.CREATED) {
                log.warn("Attempted to finalize order {} already in state {}. Ignoring.",
                        orderId, order.getStatus());
                return null;
            }

            java.util.List<com.localcommerce.delivery.model.Inventory> lowStockInventories = new java.util.ArrayList<>();

            // Level 2 Locks: Sort inventories by ID ASC to prevent deadlocks
            java.util.List<com.localcommerce.delivery.model.Inventory> inventoriesToLock = order.getItems().stream()
                    .map(item -> item.getProduct().getInventory())
                    .distinct()
                    .sorted(java.util.Comparator.comparing(com.localcommerce.delivery.model.Inventory::getId))
                    .collect(java.util.stream.Collectors.toList());

            for (com.localcommerce.delivery.model.Inventory inv : inventoriesToLock) {
                inventoryRepo.findByIdForUpdate(inv.getId()).orElseThrow(
                        () -> new com.localcommerce.delivery.exception.OrderValidationException("Sorry, one or more items in your cart are no longer available. Your order has not been placed. Please try again.")
                );
            }

            java.util.Map<com.localcommerce.delivery.model.Inventory, BigDecimal> inventoryRequirements = new java.util.HashMap<>();
            java.util.Map<com.localcommerce.delivery.model.Inventory, Product> representativeProducts = new java.util.HashMap<>();

            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                if (!product.isActive()) {
                    throw new com.localcommerce.delivery.exception.OrderValidationException(
                            "Sorry, " + product.getName() + " is currently out of stock. Your order has not been placed. Please try again.");
                }

                BigDecimal itemPhysicalQty = product.getPackageQuantity().multiply(BigDecimal.valueOf(item.getQuantity()));
                BigDecimal convertedQty = com.localcommerce.delivery.utils.UnitConverter.convert(itemPhysicalQty, product.getPackageUnit(), product.getInventory().getUnit());

                // SNAPSHOT: Store exact deducted quantity on the OrderItem
                item.setDeductedQuantity(convertedQty);

                inventoryRequirements.merge(product.getInventory(), convertedQty, BigDecimal::add);
                representativeProducts.put(product.getInventory(), product);
            }

            for (java.util.Map.Entry<com.localcommerce.delivery.model.Inventory, BigDecimal> req : inventoryRequirements.entrySet()) {
                com.localcommerce.delivery.model.Inventory inv = req.getKey();
                com.localcommerce.delivery.model.Inventory lockedInv = inventoryRepo.findById(inv.getId()).get();
                BigDecimal required = req.getValue();
                Product repProduct = representativeProducts.get(inv);

                if (lockedInv.getStockQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new com.localcommerce.delivery.exception.OrderValidationException(
                            "Sorry, " + repProduct.getName() + " is currently out of stock. Your order has not been placed. Please try again.");
                }

                if (lockedInv.getStockQuantity().compareTo(required) < 0) {
                    throw new com.localcommerce.delivery.exception.OrderValidationException(
                            "Sorry, " + repProduct.getName() + " is currently unavailable in the requested quantity. Your order has not been placed. Please try again.");
                }

                lockedInv.setStockQuantity(lockedInv.getStockQuantity().subtract(required));

                if (lockedInv.getStockQuantity().compareTo(BigDecimal.valueOf(DeliveryConstants.LOW_STOCK_THRESHOLD)) <= 0) {
                    lowStockInventories.add(lockedInv);
                }
                inventoryRepo.save(lockedInv);
            }

            order.advanceStateTo(OrderStatus.PENDING_WAREHOUSE);
            orderRepo.save(order);

            Customer customer = order.getCustomer();
            boolean locationPromptNeeded = (order.getDeliveryLatitude() == null);
            Double savedLat = (customer != null) ? customer.getLatitude() : null;
            Double savedLng = (customer != null) ? customer.getLongitude() : null;
            boolean hasSavedLocation = (savedLat != null && savedLng != null);

            // Force initialization of lazy fields inside transaction
            if (customer != null) {
                customer.getWhatsappNumber();
                customer.getRecipientId();
            }

            return new CheckoutResult(order, lowStockInventories, locationPromptNeeded, hasSavedLocation, savedLat, savedLng);
        });

        if (result == null) {
            return;
        }

        // Post-commit: external WhatsApp notifications
        try {
            for (com.localcommerce.delivery.model.Inventory inventory : result.lowStockInventories) {
                warehouseAlertService.sendLowStockAlert(inventory);
            }
            warehouseAlertService.sendNewOrderAlert(result.order);

            Customer customer = result.order.getCustomer();
            String recipientId = (customer != null) ? customer.getRecipientId() : null;

            if (recipientId != null) {
                if (result.locationPromptNeeded) {
                    outboundClient.sendCombinedOrderConfirmationPrompt(
                            recipientId,
                            result.order.getId(),
                            result.hasSavedLocation,
                            result.savedLat,
                            result.savedLng
                    );
                } else {
                    outboundClient.sendOrderConfirmedConcise(recipientId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to deliver post-commit checkout notifications for order {}: {}", orderId, e.getMessage());
        }
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.NOT_SUPPORTED)
    public CancellationResult editOrder(Long orderId) {
        CancellationResult result = transactionTemplate.execute(status -> {
            java.util.Optional<CustomerOrder> orderOpt = orderRepo.findByIdForUpdate(orderId);
            if (orderOpt.isEmpty()) {
                log.warn("Attempted to edit non-existent order: {}", orderId);
                return CancellationResult.notFound(orderId, CancellationActor.CUSTOMER);
            }
            CustomerOrder order = orderOpt.get();

            if (order.getStatus() == OrderStatus.DELIVERED) {
                return CancellationResult.alreadyDelivered(order, CancellationActor.CUSTOMER);
            }

            if (order.getStatus() == OrderStatus.CANCELLED) {
                return CancellationResult.alreadyCancelled(order, CancellationActor.CUSTOMER);
            }

            if (order.getStatus() != OrderStatus.CREATED) {
                log.warn("Customer attempted to edit order {} in state {}", orderId, order.getStatus());
                return CancellationResult.notAllowed(
                        "This order is already being prepared or out for delivery and can no longer be edited.",
                        order,
                        CancellationActor.CUSTOMER
                );
            }

            // Cancel the original CREATED order internally without modifying inventory
            order.advanceStateTo(OrderStatus.CANCELLED);
            order.setCancellationReason("Customer Edited Order");
            orderRepo.save(order);

            Customer customer = order.getCustomer();
            if (customer != null) {
                customer.getRecipientId();
            }

            return CancellationResult.success(order, CancellationActor.CUSTOMER, "Customer Edited Order");
        });

        if (result != null && result.isSuccess()) {
            try {
                CustomerOrder order = result.order();
                Customer customer = (order != null) ? order.getCustomer() : null;
                String recipientId = (customer != null) ? customer.getRecipientId() : null;
                if (recipientId != null) {
                    outboundClient.sendEditOrderPrompt(recipientId);
                }
            } catch (Exception e) {
                log.error("Failed to send edit order prompt for order {}: {}", orderId, e.getMessage());
            }
        }

        return result;
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.NOT_SUPPORTED)
    public CancellationResult cancelOrder(Long orderId, String reason, CancellationActor actor) {
        CancellationResult result = transactionTemplate.execute(status -> {
            // Level 1 Lock: CustomerOrder
            java.util.Optional<CustomerOrder> orderOpt = orderRepo.findByIdForUpdate(orderId);
            if (orderOpt.isEmpty()) {
                log.warn("Attempted to cancel non-existent order: {}", orderId);
                return CancellationResult.notFound(orderId, actor);
            }
            CustomerOrder order = orderOpt.get();

            // Terminal guard: DELIVERED orders cannot be cancelled
            if (order.getStatus() == OrderStatus.DELIVERED) {
                log.warn("Attempted to cancel delivered order {} by actor {}", orderId, actor);
                return CancellationResult.alreadyDelivered(order, actor);
            }

            // Idempotency: If already cancelled, return early without error
            if (order.getStatus() == OrderStatus.CANCELLED) {
                log.info("Order {} is already CANCELLED. No-op for actor {}", orderId, actor);
                return CancellationResult.alreadyCancelled(order, actor);
            }

            OrderStatus preStatus = order.getStatus();

            // Validate actor eligibility
            switch (actor) {
                case CUSTOMER -> {
                    if (preStatus != OrderStatus.CREATED) {
                        log.warn("Customer attempted to cancel order {} in state {}", orderId, preStatus);
                        return CancellationResult.notAllowed("This order is already being prepared or out for delivery and can no longer be cancelled.", order, actor);
                    }
                }
                case WAREHOUSE -> {
                    if (preStatus != OrderStatus.PENDING_WAREHOUSE && preStatus != OrderStatus.PACKING) {
                        log.warn("Warehouse attempted to cancel order {} in state {}", orderId, preStatus);
                        return CancellationResult.notAllowed("The order can no longer be cancelled at this stage.", order, actor);
                    }
                }
                case RIDER -> {
                    if (preStatus != OrderStatus.DISPATCHED) {
                        log.warn("Rider attempted to cancel order {} in state {}", orderId, preStatus);
                        return CancellationResult.notAllowed("The order can no longer be cancelled at this stage.", order, actor);
                    }
                }
                case ADMIN -> {
                    // Admin can cancel any active non-terminal order
                }
            }

            // Inventory restoration if order was confirmed (stock was deducted)
            boolean stockWasDeducted = (preStatus != OrderStatus.CREATED);
            if (stockWasDeducted) {
                java.util.Map<Long, BigDecimal> inventoryRestorationMap = new java.util.HashMap<>();
                boolean anyDiscrepancy = false;

                for (OrderItem item : order.getItems()) {
                    Product product = item.getProduct();
                    com.localcommerce.delivery.model.Inventory inv = (product != null) ? product.getInventory() : null;
                    if (inv == null) continue;

                    BigDecimal qtyToRestore = item.getDeductedQuantity();
                    if (qtyToRestore != null) {
                        inventoryRestorationMap.merge(inv.getId(), qtyToRestore, BigDecimal::add);
                    } else {
                        // Legacy item missing snapshot: do NOT guess, flag reconciliation required
                        anyDiscrepancy = true;
                        item.setStockReconciliationRequired(true);
                        log.warn("OrderItem {} in Order {} missing deductedQuantity snapshot. Stock reconciliation required.",
                                item.getId(), orderId);
                    }
                }

                if (anyDiscrepancy) {
                    order.setStockReconciled(false);
                }

                // Level 2 Locks: Sort inventories by ID ASC to strictly prevent deadlocks
                List<Long> sortedInvIds = inventoryRestorationMap.keySet().stream()
                        .sorted()
                        .toList();

                for (Long invId : sortedInvIds) {
                    com.localcommerce.delivery.model.Inventory lockedInv = inventoryRepo.findByIdForUpdate(invId)
                            .orElseThrow(() -> new IllegalStateException("Inventory not found: " + invId));
                    BigDecimal qtyToRestore = inventoryRestorationMap.get(invId);
                    lockedInv.setStockQuantity(lockedInv.getStockQuantity().add(qtyToRestore));
                    inventoryRepo.save(lockedInv);
                    log.info("Restored {} {} to inventory {} for cancelled order {}",
                            qtyToRestore, lockedInv.getUnit(), invId, order.getDisplayId());
                }
            }

            // Advance state to CANCELLED and persist reason
            order.advanceStateTo(OrderStatus.CANCELLED);
            String finalReason = reason;
            if (!order.isStockReconciled()) {
                finalReason = (reason != null ? reason + " " : "") + "[STOCK RECONCILIATION REQUIRED - Missing snapshot]";
            }
            order.setCancellationReason(finalReason);
            orderRepo.save(order);

            // Level 3 Lock: Check delivery run completion if applicable
            DeliveryRun run = order.getDeliveryRun();
            if (run != null) {
                DeliveryRun lockedRun = deliveryRepo.findByIdForUpdate(run.getId()).orElse(run);
                boolean hasActiveOrders = orderRepo.existsByDeliveryRunIdAndStatusNotIn(
                        lockedRun.getId(), List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED)
                );
                if (!hasActiveOrders) {
                    lockedRun.setStatus(DeliveryRun.RunStatus.COMPLETED);
                    deliveryRepo.save(lockedRun);
                    log.info("Delivery run {} completed after order {} was cancelled.", lockedRun.getId(), order.getDisplayId());
                }
            }

            Customer customer = order.getCustomer();
            if (customer != null) {
                customer.getRecipientId();
                customer.getWhatsappNumber();
            }

            return CancellationResult.success(order, actor, finalReason);
        });

        if (result == null || !result.isSuccess()) {
            return result;
        }

        // Post-commit: Best-effort notifications on SUCCESS
        try {
            CustomerOrder order = result.order();
            Customer customer = (order != null) ? order.getCustomer() : null;
            String recipientId = (customer != null) ? customer.getRecipientId() : null;
            String phone = (customer != null) ? customer.getWhatsappNumber() : null;

            if (result.actor() == CancellationActor.CUSTOMER) {
                if (recipientId != null) {
                    outboundClient.sendTextUpdate(recipientId, WhatsAppConstants.ORDER_CANCELLED_CUSTOMER);
                }
            } else if (result.actor() == CancellationActor.WAREHOUSE) {
                if (phone != null) {
                    outboundClient.sendTextUpdate(phone, WhatsAppConstants.STOCK_ISSUE_CANCELLATION);
                }
            } else if (result.actor() == CancellationActor.RIDER) {
                if (phone != null) {
                    outboundClient.sendTextUpdate(phone,
                            "Your order #" + order.getDisplayId() + " could not be delivered and has been cancelled. Reason: " + result.reason());
                }
            } else if (result.actor() == CancellationActor.ADMIN) {
                if (phone != null) {
                    outboundClient.sendTextUpdate(phone,
                            "Your order #" + order.getDisplayId() + " was cancelled by support. Reason: " + result.reason());
                }
            }
        } catch (Exception e) {
            log.error("Failed to deliver post-commit cancellation alert for order {}: {}", orderId, e.getMessage());
        }

        return result;
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.NOT_SUPPORTED)
    public void completeDelivery(Long orderId) {
        CustomerOrder completedOrder = transactionTemplate.execute(status -> {
            // Level 1 Lock: CustomerOrder
            CustomerOrder order = orderRepo.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

            if (order.getStatus() == OrderStatus.DELIVERED) {
                log.info("Order {} is already DELIVERED. No-op.", orderId);
                return null;
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                log.warn("Cannot mark cancelled order {} as delivered.", orderId);
                return null;
            }

            order.advanceStateTo(OrderStatus.DELIVERED);
            order.setDeliveredAt(LocalDateTime.now());
            orderRepo.save(order);

            // Level 3 Lock: DeliveryRun completion check
            DeliveryRun run = order.getDeliveryRun();
            if (run != null) {
                DeliveryRun lockedRun = deliveryRepo.findByIdForUpdate(run.getId()).orElse(run);
                boolean hasUndeliveredOrders = orderRepo.existsByDeliveryRunIdAndStatusNotIn(
                        lockedRun.getId(), List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED)
                );

                if (!hasUndeliveredOrders) {
                    lockedRun.setStatus(DeliveryRun.RunStatus.COMPLETED);
                    deliveryRepo.save(lockedRun);
                    log.info("Delivery run {} has been completed. All orders are delivered or cancelled.", lockedRun.getId());
                } else {
                    log.info("Delivery run {} remains IN_PROGRESS after order {} was delivered", lockedRun.getId(), order.getId());
                }
            }

            order.getCustomer().getRecipientId(); // Force initialization
            return order;
        });

        if (completedOrder != null) {
            try {
                outboundClient.sendDeliveredAlert(completedOrder.getCustomer().getRecipientId());
            } catch (Exception e) {
                log.error("Failed to send delivered alert for order {}: {}", orderId, e.getMessage());
            }
            log.info("Order {} has been successfully closed out by the rider.", completedOrder.getDisplayId());
        }
    }
}