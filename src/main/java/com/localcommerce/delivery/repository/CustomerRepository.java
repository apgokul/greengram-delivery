package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // Your primary lookup method when a webhook arrives
    Optional<Customer> findByWhatsappNumber(String whatsappNumber);

    Optional<Customer> findByBsuid(String bsuid);
}