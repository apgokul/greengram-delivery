package com.localcommerce.delivery.service;

import com.localcommerce.delivery.dto.CreateProductDTO;
import com.localcommerce.delivery.dto.InventoryDTO;
import com.localcommerce.delivery.dto.ProductDTO;
import com.localcommerce.delivery.model.Inventory;
import com.localcommerce.delivery.model.InventoryAuditLog;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.repository.InventoryAuditLogRepository;
import com.localcommerce.delivery.repository.InventoryRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductAdminServiceTest {

    @Mock
    private ProductRepository productRepo;

    @Mock
    private InventoryRepository inventoryRepo;

    @Mock
    private InventoryAuditLogRepository auditRepo;

    @InjectMocks
    private ProductAdminService adminService;

    private Inventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        inventory.setId(10L);
        inventory.setBaseSku("RICE_BASMATI");
        inventory.setStockQuantity(new BigDecimal("10.000"));
        inventory.setUnit(ProductUnit.KG);
    }

    @Test
    void testAdjustInventoryStock_PositiveDelta_UsesLockedValue() {
        when(inventoryRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryDTO result = adminService.adjustInventoryStock(10L, new BigDecimal("5.000"), "Restock delivery", "admin_tester");

        assertNotNull(result);
        assertEquals(0, new BigDecimal("15.000").compareTo(result.getStockQuantity()));
        verify(inventoryRepo).findByIdForUpdate(10L);

        ArgumentCaptor<InventoryAuditLog> captor = ArgumentCaptor.forClass(InventoryAuditLog.class);
        verify(auditRepo).save(captor.capture());

        InventoryAuditLog savedLog = captor.getValue();
        assertEquals("RICE_BASMATI", savedLog.getSku());
        assertEquals("admin_tester", savedLog.getChangedBy());
        assertEquals("Restock delivery", savedLog.getReason());

        assertEquals(0, new BigDecimal("10.000").compareTo(savedLog.getOldStock()));
        assertEquals(0, new BigDecimal("15.000").compareTo(savedLog.getNewStock()));
        assertEquals(0, new BigDecimal("5.000").compareTo(savedLog.getChangeAmount()));
        assertEquals(0, savedLog.getOldStock().add(savedLog.getChangeAmount()).compareTo(savedLog.getNewStock()));
    }

    @Test
    void testAdjustInventoryStock_FractionalPositiveDelta_PreservesExactPrecision() {
        inventory.setStockQuantity(new BigDecimal("1.250"));
        when(inventoryRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryDTO result = adminService.adjustInventoryStock(10L, new BigDecimal("0.250"), null, "admin_tester");

        assertNotNull(result);
        assertEquals(0, new BigDecimal("1.500").compareTo(result.getStockQuantity()));

        ArgumentCaptor<InventoryAuditLog> captor = ArgumentCaptor.forClass(InventoryAuditLog.class);
        verify(auditRepo).save(captor.capture());

        InventoryAuditLog savedLog = captor.getValue();
        assertEquals("INVENTORY_ADJUSTMENT", savedLog.getReason(), "Default reason should be INVENTORY_ADJUSTMENT");
        assertEquals(0, new BigDecimal("1.250").compareTo(savedLog.getOldStock()));
        assertEquals(0, new BigDecimal("1.500").compareTo(savedLog.getNewStock()));
        assertEquals(0, new BigDecimal("0.250").compareTo(savedLog.getChangeAmount()));
        assertEquals(0, savedLog.getOldStock().add(savedLog.getChangeAmount()).compareTo(savedLog.getNewStock()));
    }

    @Test
    void testAdjustInventoryStock_FractionalNegativeDelta_RecordsSignedDelta() {
        inventory.setStockQuantity(new BigDecimal("5.750"));
        when(inventoryRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryDTO result = adminService.adjustInventoryStock(10L, new BigDecimal("-3.500"), "Damaged bags discarded", "admin_tester");

        assertNotNull(result);
        assertEquals(0, new BigDecimal("2.250").compareTo(result.getStockQuantity()));

        ArgumentCaptor<InventoryAuditLog> captor = ArgumentCaptor.forClass(InventoryAuditLog.class);
        verify(auditRepo).save(captor.capture());

        InventoryAuditLog savedLog = captor.getValue();
        assertEquals("Damaged bags discarded", savedLog.getReason());
        assertEquals(0, new BigDecimal("5.750").compareTo(savedLog.getOldStock()));
        assertEquals(0, new BigDecimal("2.250").compareTo(savedLog.getNewStock()));
        assertEquals(0, new BigDecimal("-3.500").compareTo(savedLog.getChangeAmount()));
        assertEquals(0, savedLog.getOldStock().add(savedLog.getChangeAmount()).compareTo(savedLog.getNewStock()));
    }

    @Test
    void testAdjustInventoryStock_ReductionBelowZero_ThrowsExceptionAndDoesNotPersist() {
        inventory.setStockQuantity(new BigDecimal("2.000"));
        when(inventoryRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inventory));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminService.adjustInventoryStock(10L, new BigDecimal("-2.500"), "Over-reduction", "admin_tester")
        );
        assertTrue(ex.getMessage().contains("Cannot adjust stock below zero"));

        verify(inventoryRepo, never()).save(any());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void testAdjustInventoryStock_ZeroDelta_ThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminService.adjustInventoryStock(10L, BigDecimal.ZERO, "No-op", "admin_tester")
        );
        assertEquals("Adjustment quantity cannot be zero", ex.getMessage());

        verify(inventoryRepo, never()).findByIdForUpdate(any());
        verify(inventoryRepo, never()).save(any());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void testAdjustInventoryStock_NullDelta_ThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminService.adjustInventoryStock(10L, null, "No-op", "admin_tester")
        );
        assertEquals("adjustmentQuantity is required", ex.getMessage());
    }

    @Test
    void testAdjustInventoryStock_InventoryNotFound_ThrowsEntityNotFoundException() {
        when(inventoryRepo.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () ->
                adminService.adjustInventoryStock(999L, new BigDecimal("5.000"), "Restock", "admin_tester")
        );
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    void testAdjustInventoryStock_AuditFailure_PropagatesException() {
        when(inventoryRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("Database error during audit insert"))
                .when(auditRepo).save(any(InventoryAuditLog.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                adminService.adjustInventoryStock(10L, new BigDecimal("2.000"), "Restock", "admin_tester")
        );
        assertEquals("Database error during audit insert", ex.getMessage());
    }

    @Test
    void testUpdateInventoryStock_DelegatesToAdjustInventoryStock() {
        when(inventoryRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepo.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // inventory has 10.000, newStock is 13.500 -> delta is +3.500
        InventoryDTO result = adminService.updateInventoryStock(10L, new BigDecimal("13.500"), "admin_tester");

        assertNotNull(result);
        assertEquals(0, new BigDecimal("13.500").compareTo(result.getStockQuantity()));

        ArgumentCaptor<InventoryAuditLog> captor = ArgumentCaptor.forClass(InventoryAuditLog.class);
        verify(auditRepo).save(captor.capture());
        assertEquals(0, new BigDecimal("3.500").compareTo(captor.getValue().getChangeAmount()));
    }

    @Test
    void testCreateNewProduct_AuditLogPreservesFractionalInventoryStock() {
        inventory.setStockQuantity(new BigDecimal("24.750"));
        when(inventoryRepo.findById(10L)).thenReturn(Optional.of(inventory));
        when(productRepo.findBySku("RICE-1KG")).thenReturn(Optional.empty());

        Product savedProduct = new Product();
        savedProduct.setSku("RICE-1KG");
        savedProduct.setName("Basmati Rice 1KG");
        savedProduct.setCurrentPrice(new BigDecimal("120.00"));
        savedProduct.setPackageQuantity(new BigDecimal("1.000"));
        savedProduct.setPackageUnit(ProductUnit.KG);
        savedProduct.setActive(true);
        savedProduct.setInventory(inventory);
        when(productRepo.save(any(Product.class))).thenReturn(savedProduct);

        CreateProductDTO dto = new CreateProductDTO(
                "RICE-1KG",
                "Basmati Rice 1KG",
                new BigDecimal("120.00"),
                new BigDecimal("1.000"),
                ProductUnit.KG,
                true,
                10L
        );

        ProductDTO created = adminService.createNewProduct(dto, "admin_tester");
        assertNotNull(created);

        ArgumentCaptor<InventoryAuditLog> captor = ArgumentCaptor.forClass(InventoryAuditLog.class);
        verify(auditRepo).save(captor.capture());

        InventoryAuditLog savedLog = captor.getValue();
        assertEquals("RICE-1KG", savedLog.getSku());
        assertEquals("INITIAL_PRODUCT_SETUP", savedLog.getReason());
        assertEquals(0, new BigDecimal("24.750").compareTo(savedLog.getOldStock()));
        assertEquals(0, new BigDecimal("24.750").compareTo(savedLog.getNewStock()));
        assertEquals(0, BigDecimal.ZERO.compareTo(savedLog.getChangeAmount()));
        assertEquals(0, savedLog.getOldStock().add(savedLog.getChangeAmount()).compareTo(savedLog.getNewStock()));
    }

    @Test
    void testToggleActiveStatus_AuditLogPreservesFractionalInventoryStock() {
        inventory.setStockQuantity(new BigDecimal("8.375"));
        Product product = new Product();
        product.setSku("RICE-1KG");
        product.setActive(true);
        product.setInventory(inventory);

        when(productRepo.findBySku("RICE-1KG")).thenReturn(Optional.of(product));
        when(productRepo.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductDTO updated = adminService.toggleActiveStatus("RICE-1KG", false, "admin_tester");
        assertNotNull(updated);
        assertFalse(updated.isActive());

        ArgumentCaptor<InventoryAuditLog> captor = ArgumentCaptor.forClass(InventoryAuditLog.class);
        verify(auditRepo).save(captor.capture());

        InventoryAuditLog savedLog = captor.getValue();
        assertEquals("RICE-1KG", savedLog.getSku());
        assertEquals("PRODUCT_DEACTIVATED", savedLog.getReason());
        assertEquals(0, new BigDecimal("8.375").compareTo(savedLog.getOldStock()));
        assertEquals(0, new BigDecimal("8.375").compareTo(savedLog.getNewStock()));
        assertEquals(0, BigDecimal.ZERO.compareTo(savedLog.getChangeAmount()));
        assertEquals(0, savedLog.getOldStock().add(savedLog.getChangeAmount()).compareTo(savedLog.getNewStock()));
    }

    @Test
    void testCreateNewProduct_MissingInventoryId_ThrowsIllegalArgumentException() {
        CreateProductDTO dto = new CreateProductDTO(
                "COLD_PRESSED_COCONUT_OIL_500ML",
                "Cold Pressed Coconut Oil 500ML",
                new BigDecimal("250.00"),
                new BigDecimal("0.500"),
                ProductUnit.L,
                true,
                null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminService.createNewProduct(dto, "admin_tester")
        );
        assertEquals("inventoryId is required to create a new product.", ex.getMessage());
        verify(productRepo, never()).save(any());
    }

    @Test
    void testCreateNewProduct_InventoryNotFound_ThrowsIllegalArgumentException() {
        CreateProductDTO dto = new CreateProductDTO(
                "COLD_PRESSED_COCONUT_OIL_500ML",
                "Cold Pressed Coconut Oil 500ML",
                new BigDecimal("250.00"),
                new BigDecimal("0.500"),
                ProductUnit.L,
                true,
                999L
        );
        when(inventoryRepo.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminService.createNewProduct(dto, "admin_tester")
        );
        assertEquals("Inventory with ID 999 not found.", ex.getMessage());
        verify(productRepo, never()).save(any());
    }

    @Test
    void testCreateNewProduct_MultiwordSkuAndPackageVariants_ShareSameInventory() {
        Inventory sharedOilInventory = new Inventory();
        sharedOilInventory.setId(50L);
        sharedOilInventory.setBaseSku("COLD_PRESSED_COCONUT_OIL");
        sharedOilInventory.setStockQuantity(new BigDecimal("100.000"));
        sharedOilInventory.setUnit(ProductUnit.L);

        when(inventoryRepo.findById(50L)).thenReturn(Optional.of(sharedOilInventory));
        when(productRepo.findBySku("COLD_PRESSED_COCONUT_OIL_500ML")).thenReturn(Optional.empty());
        when(productRepo.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(101L);
            return p;
        });

        CreateProductDTO dto500ML = new CreateProductDTO(
                "COLD_PRESSED_COCONUT_OIL_500ML",
                "Cold Pressed Coconut Oil 500ML",
                new BigDecimal("250.00"),
                new BigDecimal("0.500"),
                ProductUnit.L,
                true,
                50L
        );

        ProductDTO created500ML = adminService.createNewProduct(dto500ML, "admin_tester");
        assertNotNull(created500ML);
        assertEquals("COLD_PRESSED_COCONUT_OIL_500ML", created500ML.getSku());
        assertNotNull(created500ML.getInventory());
        assertEquals(50L, created500ML.getInventory().getId());
        assertEquals("COLD_PRESSED_COCONUT_OIL", created500ML.getInventory().getBaseSku());

        // Second variant: 1L pack sharing the exact same inventory pool
        when(productRepo.findBySku("COLD_PRESSED_COCONUT_OIL_1L")).thenReturn(Optional.empty());

        CreateProductDTO dto1L = new CreateProductDTO(
                "COLD_PRESSED_COCONUT_OIL_1L",
                "Cold Pressed Coconut Oil 1L",
                new BigDecimal("480.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                true,
                50L
        );

        ProductDTO created1L = adminService.createNewProduct(dto1L, "admin_tester");
        assertNotNull(created1L);
        assertEquals("COLD_PRESSED_COCONUT_OIL_1L", created1L.getSku());
        assertNotNull(created1L.getInventory());
        assertEquals(50L, created1L.getInventory().getId());
        assertEquals("COLD_PRESSED_COCONUT_OIL", created1L.getInventory().getBaseSku());
    }

    @Test
    void testGetAllProducts_PopulatesProductAndInventoryDtoFields() {
        Product p = new Product();
        p.setId(100L);
        p.setSku("RICE_BASMATI_1KG");
        p.setName("Basmati Rice 1KG");
        p.setCurrentPrice(new BigDecimal("120.00"));
        p.setPackageQuantity(new BigDecimal("1.000"));
        p.setPackageUnit(ProductUnit.KG);
        p.setActive(true);
        p.setInventory(inventory);

        when(productRepo.findAllWithInventory()).thenReturn(List.of(p));

        List<ProductDTO> result = adminService.getAllProducts();

        assertNotNull(result);
        assertEquals(1, result.size());
        ProductDTO dto = result.get(0);
        assertEquals(100L, dto.getId());
        assertEquals("RICE_BASMATI_1KG", dto.getSku());
        assertEquals("Basmati Rice 1KG", dto.getName());
        assertEquals(0, new BigDecimal("120.00").compareTo(dto.getCurrentPrice()));
        assertEquals(0, new BigDecimal("1.000").compareTo(dto.getPackageQuantity()));
        assertEquals(ProductUnit.KG, dto.getPackageUnit());
        assertTrue(dto.isActive());

        assertNotNull(dto.getInventory());
        assertEquals(10L, dto.getInventory().getId());
        assertEquals("RICE_BASMATI", dto.getInventory().getBaseSku());
        assertEquals(0, new BigDecimal("10.000").compareTo(dto.getInventory().getStockQuantity()));
        assertEquals(ProductUnit.KG, dto.getInventory().getUnit());

        verify(productRepo, times(1)).findAllWithInventory();
        verify(productRepo, never()).findAll();
    }

    @Test
    void testGetAllProducts_MultipleVariantsSharingSameInventory() {
        Inventory sharedOilInventory = new Inventory();
        sharedOilInventory.setId(50L);
        sharedOilInventory.setBaseSku("COLD_PRESSED_COCONUT_OIL");
        sharedOilInventory.setStockQuantity(new BigDecimal("100.000"));
        sharedOilInventory.setUnit(ProductUnit.L);

        Product p1 = new Product();
        p1.setId(101L);
        p1.setSku("COLD_PRESSED_COCONUT_OIL_500ML");
        p1.setName("Cold Pressed Coconut Oil 500ML");
        p1.setCurrentPrice(new BigDecimal("250.00"));
        p1.setPackageQuantity(new BigDecimal("0.500"));
        p1.setPackageUnit(ProductUnit.L);
        p1.setActive(true);
        p1.setInventory(sharedOilInventory);

        Product p2 = new Product();
        p2.setId(102L);
        p2.setSku("COLD_PRESSED_COCONUT_OIL_1L");
        p2.setName("Cold Pressed Coconut Oil 1L");
        p2.setCurrentPrice(new BigDecimal("480.00"));
        p2.setPackageQuantity(new BigDecimal("1.000"));
        p2.setPackageUnit(ProductUnit.L);
        p2.setActive(true);
        p2.setInventory(sharedOilInventory);

        when(productRepo.findAllWithInventory()).thenReturn(List.of(p1, p2));

        List<ProductDTO> result = adminService.getAllProducts();

        assertNotNull(result);
        assertEquals(2, result.size());

        ProductDTO dto1 = result.get(0);
        assertEquals("COLD_PRESSED_COCONUT_OIL_500ML", dto1.getSku());
        assertNotNull(dto1.getInventory());
        assertEquals(50L, dto1.getInventory().getId());
        assertEquals("COLD_PRESSED_COCONUT_OIL", dto1.getInventory().getBaseSku());
        assertEquals(0, new BigDecimal("100.000").compareTo(dto1.getInventory().getStockQuantity()));
        assertEquals(ProductUnit.L, dto1.getInventory().getUnit());

        ProductDTO dto2 = result.get(1);
        assertEquals("COLD_PRESSED_COCONUT_OIL_1L", dto2.getSku());
        assertNotNull(dto2.getInventory());
        assertEquals(50L, dto2.getInventory().getId());
        assertEquals("COLD_PRESSED_COCONUT_OIL", dto2.getInventory().getBaseSku());
        assertEquals(0, new BigDecimal("100.000").compareTo(dto2.getInventory().getStockQuantity()));
        assertEquals(ProductUnit.L, dto2.getInventory().getUnit());

        verify(productRepo, times(1)).findAllWithInventory();
    }

    @Test
    void testGetAllProducts_ProductWithNullInventory_MapsToNullInventoryDto() {
        Product p = new Product();
        p.setId(200L);
        p.setSku("UNMANAGED_PRODUCT");
        p.setName("Unmanaged Product");
        p.setCurrentPrice(new BigDecimal("50.00"));
        p.setPackageQuantity(new BigDecimal("1.000"));
        p.setPackageUnit(ProductUnit.PCS);
        p.setActive(true);
        p.setInventory(null);

        when(productRepo.findAllWithInventory()).thenReturn(List.of(p));

        List<ProductDTO> result = adminService.getAllProducts();

        assertNotNull(result);
        assertEquals(1, result.size());
        ProductDTO dto = result.get(0);
        assertEquals("UNMANAGED_PRODUCT", dto.getSku());
        assertNull(dto.getInventory(), "InventoryDTO should be null when product inventory association is null");

        verify(productRepo, times(1)).findAllWithInventory();
    }

    @Test
    void testGetAllProducts_EmptyList_ReturnsEmptyList() {
        when(productRepo.findAllWithInventory()).thenReturn(List.of());

        List<ProductDTO> result = adminService.getAllProducts();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(productRepo, times(1)).findAllWithInventory();
    }
}
