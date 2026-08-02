package com.localcommerce.delivery.model;

import com.localcommerce.delivery.enums.RiderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.List;


@Entity
@Table(name = "riders")
public class Rider {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String phone;

    @Enumerated(EnumType.STRING)
    private RiderStatus status; // OFFLINE, AVAILABLE, DELIVERING

    // Updated continuously when they are active
    private Double currentLat;
    private Double currentLng;

    @OneToMany(mappedBy = "rider")
    private List<CustomerOrder> assignedOrders;
}