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
import com.localcommerce.delivery.repository.ProductRepository;
import com.localcommerce.delivery.utils.DistanceUtil;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

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
                        CustomerIdentityService customerIdentityService) {
        this.customerRepo = customerRepo;
        this.productRepo = productRepo;
        this.orderRepo = orderRepo;
        this.outboundClient = outboundClient;
        this.warehouseAlertService = warehouseAlertService;
        this.customerIdentityService = customerIdentityService;
    }

    @Transactional // 1. CRITICAL: Enforces a single atomic transaction & enables DB locks
    public OrderProcessing.OrderProcessResult processNewCart(String bsuid, String phoneNumber, String whatsappName, List<CartItemDTO> cartItems) {

        // 1. Resolve identity gracefully
        Customer customer = customerIdentityService.resolveCustomerIdentity(bsuid, phoneNumber, whatsappName);
        String recipientId = customer.getRecipientId();

        // 2. PASS 1: Validate stock for ALL items FIRST using Pessimistic Locks
        // (Prevents deducting Item 1 if Item 2 fails stock check)
        for (CartItemDTO dto : cartItems) {
            Product product = productRepo.findBySkuForUpdate(dto.getSku()) // 2. Locks the DB row
                    .orElseThrow(() -> new RuntimeException("Product SKU not found: " + dto.getSku()));

            if (product.getStockQuantity() < dto.getQuantity()) {

                       return OrderProcessing.OrderProcessResult.failed(
                               "⚠️ Sorry, we only have " + product.getStockQuantity() + " units of " + product.getName() + " left!"
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

            // Safely deduct stock
            product.setStockQuantity(product.getStockQuantity() - dto.getQuantity());

            if (product.getStockQuantity() <= 5) {
                warehouseAlertService.sendLowStockAlert(product);
            }

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

    @Transactional
    public void finalizeOrderCheckout(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        warehouseAlertService.sendNewOrderAlert(order);
    }

    @Transactional
    public void completeDelivery(Long orderId) {
        CustomerOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));

        order.advanceStateTo(OrderStatus.DELIVERED);
        orderRepo.save(order);

        // FIX 4: Use customer.getRecipientId() for delivery alerts
        outboundClient.sendDeliveredAlert(order.getCustomer().getRecipientId());

        log.info("Order {} has been successfully closed out by the rider.", order.getDisplayId());
    }
}