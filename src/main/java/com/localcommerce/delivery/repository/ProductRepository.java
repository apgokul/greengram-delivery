package com.localcommerce.delivery.repository;


import com.localcommerce.delivery.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // Used during checkout to lock in the price and check stock
    Optional<Product> findBySku(String sku);

    // Write Lock: Prevents any other transaction from reading/writing this specific row until the transaction completes
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.sku = :sku")
    Optional<Product> findBySkuForUpdate(@Param("sku") String sku);
}