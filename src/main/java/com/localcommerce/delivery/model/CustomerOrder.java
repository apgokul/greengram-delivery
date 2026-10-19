package com.localcommerce.delivery.model;

import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.utils.DeliveryConstants;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "customer_orders", indexes = {
// This tells PostgreSQL to build an index grouping these two columns together
@Index(name = "idx_customer_status", columnList = "customer_id, status")
})
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_sequence", initialValue = 1001, allocationSize = 1)
    private Long id;

    @Column(unique = true, nullable = false)
    private String displayId; // e.g., "ORD-1042" for the warehouse to write on the bag

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    // CREATED -> ACCEPTED -> PACKING -> READY -> DISPATCHED -> DELIVERED (or CANCELLED)

    private BigDecimal totalAmount;
//    private BigDecimal deliveryFee;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime readyAt; // To track warehouse packing speed
    private LocalDateTime deliveredAt; // To track rider delivery speed

    private Double deliveryLatitude;
    private Double deliveryLongitude;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "stock_reconciled", nullable = false)
    private boolean stockReconciled = true;

    @OneToMany(mappedBy = "customerOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @ManyToOne
    private DeliveryRun deliveryRun;

    public void advanceStateTo(OrderStatus targetState) {
        boolean isValid = switch (targetState) {
            case PENDING_WAREHOUSE -> this.status == OrderStatus.CREATED;
            case PACKING -> this.status == OrderStatus.PENDING_WAREHOUSE;
            case READY -> this.status == OrderStatus.PACKING;
            case DISPATCHED -> this.status == OrderStatus.READY;
            case DELIVERED -> this.status == OrderStatus.DISPATCHED;
            case CANCELLED -> this.status == OrderStatus.CREATED
                    || this.status == OrderStatus.PENDING_WAREHOUSE
                    || this.status == OrderStatus.PACKING
                    || this.status == OrderStatus.READY
                    || this.status == OrderStatus.DISPATCHED;
            default -> false;
        };

        if (!isValid) {
            throw new IllegalStateException(
                    String.format("Invalid state transition from %s to %s for Order %s", this.status, targetState, this.id)
            );
        }

        this.status = targetState;
    }

    @PrePersist
    protected void onCreate() {
        // Because you use a sequence, 'this.id' is already fetched and available here!
        if (this.displayId == null && this.id != null) {
            this.displayId = DeliveryConstants.ORDER_DISPLAY_ID_PREFIX + this.id;
        }
    }

}