package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.DeliveryRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeliveryRunRepository extends JpaRepository<DeliveryRun, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DeliveryRun r WHERE r.id = :id")
    Optional<DeliveryRun> findByIdForUpdate(@Param("id") Long id);
}
