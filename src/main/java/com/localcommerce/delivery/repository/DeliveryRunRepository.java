package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.DeliveryRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRunRepository extends JpaRepository<DeliveryRun, Long>
{
}
