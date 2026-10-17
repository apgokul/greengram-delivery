package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.WhatsAppMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WhatsAppMessageLogRepository extends JpaRepository<WhatsAppMessageLog, Long> {

    @Query("""
        SELECT m FROM WhatsAppMessageLog m
        WHERE NOT EXISTS (
            SELECT 1 FROM WhatsAppMessageLog m2
            WHERE m2.phoneNumber = m.phoneNumber
              AND (m2.timestamp > m.timestamp OR (m2.timestamp = m.timestamp AND m2.id > m.id))
        )
        ORDER BY m.timestamp DESC, m.id DESC
    """)
    List<WhatsAppMessageLog> findLatestMessagePerChat();

    @Query("""
        SELECT m FROM WhatsAppMessageLog m
        WHERE m.phoneNumber = :phoneNumber
        ORDER BY m.timestamp ASC, m.id ASC
    """)
    List<WhatsAppMessageLog> findByPhoneNumberChronological(@Param("phoneNumber") String phoneNumber);

    boolean existsByExternalMessageId(String externalMessageId);

    Optional<WhatsAppMessageLog> findByExternalMessageId(String externalMessageId);
}
