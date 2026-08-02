package com.localcommerce.delivery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The BSUID can be up to 128 chars. It must be unique, but nullable.
    @Column(name = "bsuid", unique = true, length = 128)
    private String bsuid;

    @Column(name = "whatsapp_number", unique = true)
    private String whatsappNumber;

    private String name;

    // Captured via WhatsApp Location Pin
    private Double latitude;
    private Double longitude;
    private String addressNotes; // e.g., "Flat 4B, Blue Gates"

    // Inside Customer.java
    public String getRecipientId() {
// Prioritize Phone Number because Meta's "to" routing is 100% stable
        if (whatsappNumber != null && !whatsappNumber.isBlank()) {
            return whatsappNumber;
        }
        // Fallback to BSUID only if the user has hidden their phone number
        return bsuid;
    }
}