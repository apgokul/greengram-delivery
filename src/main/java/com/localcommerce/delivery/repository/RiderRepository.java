package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.Rider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiderRepository extends JpaRepository<Rider, Long> {

    // For authenticating rider bot commands
    Optional<Rider> findByPhone(String phone);

    // The Routing Engine uses this to find who to assign an order to
    // e.g., findByStatus(RiderStatus.AVAILABLE)
    List<Rider> findByStatus(String status);
}