package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.CustomerOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByDisplayId(String displayId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CustomerOrder o WHERE o.id = :id")
    Optional<CustomerOrder> findByIdForUpdate(@Param("id") Long id);

    // Used by the warehouse dashboard to see what needs to be packed right now
    // e.g., findByStatus(OrderStatus.ACCEPTED)
    List<CustomerOrder> findByStatus(OrderStatus status);

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

    // Determines if a specific delivery run has any orders not in the specified status
    boolean existsByDeliveryRunIdAndStatusNot(Long deliveryRunId, OrderStatus status);

    // Checks if a run has active orders excluding terminal states (DELIVERED and CANCELLED)
    boolean existsByDeliveryRunIdAndStatusNotIn(Long deliveryRunId, Collection<OrderStatus> statuses);

    // Finds candidate orders waiting for location for a customer
    List<CustomerOrder> findByCustomerWhatsappNumberAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(
            String whatsappNumber,
            Collection<OrderStatus> statuses
    );

    List<CustomerOrder> findByCustomerIdAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(
            Long customerId,
            Collection<OrderStatus> statuses
    );

    boolean existsByCustomerIdAndStatusInAndDeliveryLatitudeIsNull(
            Long customerId,
            Collection<OrderStatus> statuses
    );

    // Queries orders requiring manual stock reconciliation after cancellation
    List<CustomerOrder> findByStockReconciledFalse();
}