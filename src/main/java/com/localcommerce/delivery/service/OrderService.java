package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.CartItemDTO;
import com.localcommerce.delivery.dto.OrderProcessing;
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

        // FIX 1: Look up by BSUID first, fallback to Phone Number
        Customer customer = customerRepo.findByBsuid(customerKey)
                .orElseGet(() -> customerRepo.findByWhatsappNumber(customerKey)
                        .orElseThrow(() -> new RuntimeException("Customer not found for key: " + customerKey)));

        String recipientId = customer.getRecipientId();

        // Calculate distance
        double distanceKm = DistanceUtil.calculateDistance(storeLat, storeLng, lat, lng);
        log.info("Customer {} dropped pin at distance: {} km", customerKey, distanceKm);

        // Enforce radius limit
        if (distanceKm > maxRadiusKm) {
            String outOfBoundsMessage = String.format(
                    WhatsAppConstants.OUT_OF_BOUNDS_TEMPLATE,
                    distanceKm, maxRadiusKm
            );
            outboundClient.sendTextUpdate(recipientId, outOfBoundsMessage);
            return;
        }

        CustomerOrder pendingOrder = orderRepo.findTopByCustomerIdAndStatusOrderByCreatedAtDesc(
                        customer.getId(), OrderStatus.CREATED)
                .orElseThrow(() -> new RuntimeException("No pending order waiting for location"));

        // Update customer profile
        customer.setLatitude(lat);
        customer.setLongitude(lng);
        customerRepo.save(customer);

        // Snapshot coordinates onto order
        pendingOrder.setDeliveryLatitude(lat);
        pendingOrder.setDeliveryLongitude(lng);
        orderRepo.save(pendingOrder);

        // FIX 2: Send summary prompt using recipientId
        outboundClient.sendOrderSummaryPrompt(recipientId, pendingOrder);
    }

    @Transactional
    public void confirmSavedLocation(Long orderId) {

        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        Customer customer = order.getCustomer();

        order.setDeliveryLatitude(customer.getLatitude());
        order.setDeliveryLongitude(customer.getLongitude());
        orderRepo.save(order);

        // FIX 3: Use customer.getRecipientId() instead of getWhatsappNumber()
        outboundClient.sendOrderSummaryPrompt(customer.getRecipientId(), order);
    }

    public static class CheckoutResult {
        public final CustomerOrder order;
        public final List<com.localcommerce.delivery.model.Inventory> lowStockInventories;
        public CheckoutResult(CustomerOrder order, List<com.localcommerce.delivery.model.Inventory> lowStockInventories) {
            this.order = order;
            this.lowStockInventories = lowStockInventories;
        }
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.NOT_SUPPORTED)
    public void finalizeOrderCheckout(Long orderId) {
        CheckoutResult result = transactionTemplate.execute(status -> {
            CustomerOrder order = orderRepo.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // Idempotency guard — prevents double-processing if the customer taps Confirm twice
            if (order.getStatus() != OrderStatus.CREATED) {
                log.warn("Attempted to finalize order {} already in state {}. Ignoring.",
                        orderId, order.getStatus());
                return null;
            }

            java.util.List<com.localcommerce.delivery.model.Inventory> lowStockInventories = new java.util.ArrayList<>();

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
            
            // Prevent LazyInitializationException outside the transaction boundary
            order.getCustomer().getWhatsappNumber();

            return new CheckoutResult(order, lowStockInventories);
        });

        if (result == null) {
            return;
        }

        // Network calls safely execute OUTSIDE the database transaction
        for (com.localcommerce.delivery.model.Inventory inventory : result.lowStockInventories) {
            warehouseAlertService.sendLowStockAlert(inventory);
        }
        warehouseAlertService.sendNewOrderAlert(result.order);
    }

    @Transactional
    public void completeDelivery(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));

        order.advanceStateTo(OrderStatus.DELIVERED);
        order.setDeliveredAt(LocalDateTime.now());
        orderRepo.save(order);

        DeliveryRun run = order.getDeliveryRun();
        if (run != null) {
            boolean hasUndeliveredOrders = orderRepo.existsByDeliveryRunIdAndStatusNot(
                    run.getId(), OrderStatus.DELIVERED
            );

            if (!hasUndeliveredOrders) {
                run.setStatus(DeliveryRun.RunStatus.COMPLETED);
                deliveryRepo.save(run);
                log.info("Delivery run {} has been completed. All orders are delivered.", run.getId());
            } else {
                log.info("Delivery run {} remains IN_PROGRESS after order {} was delivered", run.getId(), order.getId());
            }
        }

        // FIX 4: Use customer.getRecipientId() for delivery alerts
        outboundClient.sendDeliveredAlert(order.getCustomer().getRecipientId());

        log.info("Order {} has been successfully closed out by the rider.", order.getDisplayId());
    }
}