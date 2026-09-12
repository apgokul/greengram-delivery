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
                        CustomerOrderRepository orderRepo,
                        WhatsAppOutboundClient outboundClient,
                        WarehouseAlertService warehouseAlertService,
                        CustomerIdentityService customerIdentityService,
                        DeliveryRunRepository deliveryRepo,
                        PlatformTransactionManager transactionManager) {
        this.customerRepo = customerRepo;
        this.productRepo = productRepo;
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

        // 2. PASS 1: Validate stock for ALL items FIRST using Pessimistic Locks
        // (Prevents deducting Item 1 if Item 2 fails stock check)
        for (CartItemDTO dto : cartItems) {
            if (dto.getQuantity() <= 0) {
                return OrderProcessing.OrderProcessResult.failed("Sorry, invalid quantity requested.");
            }

            java.util.Optional<Product> optProduct = productRepo.findBySkuForUpdate(dto.getSku());

            if (optProduct.isEmpty()) {
                return OrderProcessing.OrderProcessResult.failed(
                        "Sorry, one or more items in your cart are currently unavailable."
                );
            }

            Product product = optProduct.get();

            if (!product.isActive() || product.getStockQuantity() <= 0) {
                return OrderProcessing.OrderProcessResult.failed(
                        "Sorry, " + product.getName() + " is currently out of stock."
                );
            }

            if (product.getStockQuantity() < dto.getQuantity()) {
                return OrderProcessing.OrderProcessResult.failed(
                        "Sorry, " + product.getName() + " is currently unavailable in the requested quantity. Please try a smaller quantity."
                );
            }
        }

        // 3. PASS 2: Create shell order, deduct stock, and link line items
        CustomerOrder order = new CustomerOrder();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CREATED);

        BigDecimal initialCartTotal = BigDecimal.ZERO;

        for (CartItemDTO dto : cartItems) {
            // Retrieve the locked product from the managed persistence context
            Product product = productRepo.findBySkuForUpdate(dto.getSku()).get();

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
        public final List<Product> lowStockProducts;
        public CheckoutResult(CustomerOrder order, List<Product> lowStockProducts) {
            this.order = order;
            this.lowStockProducts = lowStockProducts;
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

            java.util.List<Product> lowStockProducts = new java.util.ArrayList<>();

            for (OrderItem item : order.getItems()) {
                // Acquire a fresh pessimistic write lock on this product row.
                // Blocks any concurrent finalizeOrderCheckout for the same product
                // until this transaction commits or rolls back.
                java.util.Optional<Product> optProduct = productRepo.findBySkuForUpdate(item.getProduct().getSku());
                
                if (optProduct.isEmpty()) {
                    throw new com.localcommerce.delivery.exception.OrderValidationException(
                            "Sorry, one or more items in your cart are no longer available. Your order has not been placed. Please try again.");
                }

                Product product = optProduct.get();

                if (!product.isActive() || product.getStockQuantity() <= 0) {
                    throw new com.localcommerce.delivery.exception.OrderValidationException(
                            "Sorry, " + product.getName() + " is currently out of stock. Your order has not been placed. Please try again.");
                }

                // Re-verify stock is still sufficient after acquiring the lock.
                // This closes the race window between processNewCart and finalizeOrderCheckout.
                if (product.getStockQuantity() < item.getQuantity()) {
                    throw new com.localcommerce.delivery.exception.OrderValidationException(
                            "Sorry, " + product.getName() + " is currently unavailable in the " +
                            "requested quantity. Your order has not been placed. Please try again.");
                }

                // Safe to deduct — we hold the lock and sufficiency is confirmed
                product.setStockQuantity(product.getStockQuantity() - item.getQuantity());

                if (product.getStockQuantity() <= DeliveryConstants.LOW_STOCK_THRESHOLD) {
                    lowStockProducts.add(product);
                }
                productRepo.save(product);
            }

            order.advanceStateTo(OrderStatus.PENDING_WAREHOUSE);
            orderRepo.save(order);
            
            // Prevent LazyInitializationException outside the transaction boundary
            order.getCustomer().getWhatsappNumber();

            return new CheckoutResult(order, lowStockProducts);
        });

        if (result == null) {
            return;
        }

        // Network calls safely execute OUTSIDE the database transaction
        for (Product product : result.lowStockProducts) {
            warehouseAlertService.sendLowStockAlert(product);
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