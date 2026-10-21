package com.localcommerce.delivery.repository;

import com.localcommerce.delivery.model.InventoryAuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_audit_migration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=test",
        "whatsapp.api.token=test",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=test",
        "fallback.manager.phone=test",
        "authorized.rider.numbers=919999999999",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000"
})
class InventoryAuditLogMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryAuditLogRepository auditRepo;

    @BeforeEach
    void setUp() {
        // 1. Drop existing table if present
        jdbcTemplate.execute("DROP TABLE IF EXISTS inventory_audit_logs CASCADE");

        // 2. Create table using original V1 schema (INTEGER columns)
        jdbcTemplate.execute("""
            CREATE TABLE inventory_audit_logs (
                id BIGSERIAL PRIMARY KEY,
                sku VARCHAR(255) NOT NULL,
                old_stock INTEGER NOT NULL,
                new_stock INTEGER NOT NULL,
                change_amount INTEGER NOT NULL,
                reason VARCHAR(255) NOT NULL,
                changed_by VARCHAR(255) NOT NULL,
                timestamp TIMESTAMP WITHOUT TIME ZONE NOT NULL
            )
        """);

        // 3. Insert legacy integer audit records (simulating pre-V5 database records)
        jdbcTemplate.execute("""
            INSERT INTO inventory_audit_logs (sku, old_stock, new_stock, change_amount, reason, changed_by, timestamp)
            VALUES ('DAL-1KG', 10, 15, 5, 'RESTOCK', 'Admin_Legacy', CURRENT_TIMESTAMP)
        """);
        jdbcTemplate.execute("""
            INSERT INTO inventory_audit_logs (sku, old_stock, new_stock, change_amount, reason, changed_by, timestamp)
            VALUES ('RICE-5KG', 20, 12, -8, 'MANUAL_CORRECTION', 'Admin_Legacy', CURRENT_TIMESTAMP)
        """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS inventory_audit_logs CASCADE");
    }

    @Test
    void testV5Migration_PreservesLegacyIntegerRecords_AndAcceptsFractionalValues() {
        // Execute the exact V5 migration DDL statements
        jdbcTemplate.execute("ALTER TABLE inventory_audit_logs ALTER COLUMN old_stock TYPE NUMERIC(38, 3)");
        jdbcTemplate.execute("ALTER TABLE inventory_audit_logs ALTER COLUMN new_stock TYPE NUMERIC(38, 3)");
        jdbcTemplate.execute("ALTER TABLE inventory_audit_logs ALTER COLUMN change_amount TYPE NUMERIC(38, 3)");

        // 1. Verify legacy rows survived without data loss or value change
        List<InventoryAuditLog> existingLogs = auditRepo.findAll();
        assertEquals(2, existingLogs.size());

        InventoryAuditLog log1 = existingLogs.stream()
                .filter(l -> "DAL-1KG".equals(l.getSku()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, new BigDecimal("10").compareTo(log1.getOldStock()), "Legacy old_stock 10 must be preserved");
        assertEquals(0, new BigDecimal("15").compareTo(log1.getNewStock()), "Legacy new_stock 15 must be preserved");
        assertEquals(0, new BigDecimal("5").compareTo(log1.getChangeAmount()), "Legacy change_amount 5 must be preserved");

        InventoryAuditLog log2 = existingLogs.stream()
                .filter(l -> "RICE-5KG".equals(l.getSku()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, new BigDecimal("20").compareTo(log2.getOldStock()));
        assertEquals(0, new BigDecimal("12").compareTo(log2.getNewStock()));
        assertEquals(0, new BigDecimal("-8").compareTo(log2.getChangeAmount()));

        // 2. Insert new fractional audit log via JPA repository
        InventoryAuditLog fractionalLog = new InventoryAuditLog();
        fractionalLog.setSku("CHILLI_POWDER");
        fractionalLog.setOldStock(new BigDecimal("1.250"));
        fractionalLog.setNewStock(new BigDecimal("1.500"));
        fractionalLog.setChangeAmount(new BigDecimal("0.250"));
        fractionalLog.setReason("INVENTORY_UPDATE");
        fractionalLog.setChangedBy("Admin_V5");

        InventoryAuditLog savedFractional = auditRepo.save(fractionalLog);
        assertNotNull(savedFractional.getId());

        // 3. Read back from DB and verify exact fractional precision
        InventoryAuditLog reloaded = auditRepo.findById(savedFractional.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("1.250").compareTo(reloaded.getOldStock()));
        assertEquals(0, new BigDecimal("1.500").compareTo(reloaded.getNewStock()));
        assertEquals(0, new BigDecimal("0.250").compareTo(reloaded.getChangeAmount()));
    }
}
