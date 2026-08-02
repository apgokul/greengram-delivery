package com.localcommerce.delivery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "delivery_runs")
@Getter
@Setter
public class DeliveryRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // E.g., The phone number of the gig rider assigned to this batch
    private String riderPhoneNumber;

    private LocalDateTime dispatchedAt;

    @Enumerated(EnumType.STRING)
    private RunStatus status; // E.g., PENDING, IN_PROGRESS, COMPLETED

    // One Run has Many Orders
    @OneToMany(mappedBy = "deliveryRun")
    private List<CustomerOrder> orders = new ArrayList<>();

    public enum RunStatus {
        PENDING, IN_PROGRESS, COMPLETED
    }
}