package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.enums.RiderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import org.hibernate.query.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    // Used when the warehouse taps the "✅ Accept ORD-1042" button
    Optional<CustomerOrder> findByDisplayId(String displayId);

    // Used by the warehouse dashboard to see what needs to be packed right now
    // e.g., findByStatus(OrderStatus.ACCEPTED)
    List<CustomerOrder> findByStatus(OrderStatus status);

    // CRITICAL FOR BATCHING: Finds all orders currently assigned to a specific rider
    // that haven't been delivered yet.
    List<CustomerOrder> findByRiderIdAndStatus(Long riderId, RiderStatus status);

    // Finds all orders for a specific customer (useful for order history)
    List<CustomerOrder> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    // Returns the single most recent order waiting for a location
    Optional<CustomerOrder> findTopByCustomerWhatsappNumberAndStatusOrderByCreatedAtDesc(
            String whatsappNumber,
            OrderStatus status
    );

    // This query will now use your 'idx_customer_status' index!
    Optional<CustomerOrder> findTopByCustomerIdAndStatusOrderByCreatedAtDesc(
            Long customerId,
            OrderStatus status
    );
}