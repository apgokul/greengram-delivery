package com.localcommerce.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcommerce.delivery.dto.AdjustStockRequest;
import com.localcommerce.delivery.dto.InventoryDTO;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.service.ProductAdminService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdminInventoryControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @Mock
    private ProductAdminService adminService;

    @InjectMocks
    private AdminInventoryController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testAdjustStock_PositiveDelta_Returns200() throws Exception {
        InventoryDTO updated = new InventoryDTO(10L, "DAL_MOONG", new BigDecimal("15.500"), ProductUnit.KG);
        when(adminService.adjustInventoryStock(eq(10L), eq(new BigDecimal("5.500")), eq("Restock"), eq("Admin_User")))
                .thenReturn(updated);

        AdjustStockRequest request = new AdjustStockRequest(new BigDecimal("5.500"), "Restock");

        mockMvc.perform(put("/api/admin/inventories/10/stock")
                        .principal(() -> "Admin_User")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.inventoryId").value(10))
                .andExpect(jsonPath("$.baseSku").value("DAL_MOONG"))
                .andExpect(jsonPath("$.new_stock").value(15.500))
                .andExpect(jsonPath("$.message").value("Inventory stock adjusted successfully"));
    }

    @Test
    void testAdjustStock_AuthenticatedPrincipalOverridesSpoofedHeader() throws Exception {
        InventoryDTO updated = new InventoryDTO(10L, "DAL_MOONG", new BigDecimal("15.500"), ProductUnit.KG);
        when(adminService.adjustInventoryStock(eq(10L), eq(new BigDecimal("5.500")), eq("Restock"), eq("alice")))
                .thenReturn(updated);

        AdjustStockRequest request = new AdjustStockRequest(new BigDecimal("5.500"), "Restock");

        mockMvc.perform(put("/api/admin/inventories/10/stock")
                        .principal(() -> "alice")
                        .header("X-Admin-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(adminService).adjustInventoryStock(eq(10L), eq(new BigDecimal("5.500")), eq("Restock"), eq("alice"));
    }

    @Test
    void testCreateInventory_AuthenticatedPrincipalOverridesSpoofedHeader() throws Exception {
        InventoryDTO payload = new InventoryDTO(null, "OIL_SUNFLOWER", new BigDecimal("50.000"), ProductUnit.L);
        InventoryDTO created = new InventoryDTO(20L, "OIL_SUNFLOWER", new BigDecimal("50.000"), ProductUnit.L);
        when(adminService.createInventory(any(InventoryDTO.class), eq("alice")))
                .thenReturn(created);

        mockMvc.perform(post("/api/admin/inventories")
                        .principal(() -> "alice")
                        .header("X-Admin-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(20));

        verify(adminService).createInventory(any(InventoryDTO.class), eq("alice"));
    }

    @Test
    void testAdjustStock_NegativeDelta_Returns200() throws Exception {
        InventoryDTO updated = new InventoryDTO(10L, "DAL_MOONG", new BigDecimal("7.000"), ProductUnit.KG);
        when(adminService.adjustInventoryStock(eq(10L), eq(new BigDecimal("-3.000")), any(), eq("Unknown_Admin")))
                .thenReturn(updated);

        AdjustStockRequest request = new AdjustStockRequest(new BigDecimal("-3.000"), null);

        mockMvc.perform(put("/api/admin/inventories/10/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.new_stock").value(7.000));
    }

    @Test
    void testAdjustStock_MissingAdjustmentQuantity_Returns400() throws Exception {
        mockMvc.perform(put("/api/admin/inventories/10/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("adjustmentQuantity is required"));
    }

    @Test
    void testAdjustStock_ZeroQuantity_Returns400() throws Exception {
        when(adminService.adjustInventoryStock(eq(10L), eq(BigDecimal.ZERO), any(), any()))
                .thenThrow(new IllegalArgumentException("Adjustment quantity cannot be zero"));

        AdjustStockRequest request = new AdjustStockRequest(BigDecimal.ZERO, "No-op");

        mockMvc.perform(put("/api/admin/inventories/10/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Adjustment quantity cannot be zero"));
    }

    @Test
    void testAdjustStock_ReductionBelowZero_Returns400() throws Exception {
        when(adminService.adjustInventoryStock(eq(10L), eq(new BigDecimal("-50.000")), any(), any()))
                .thenThrow(new IllegalArgumentException("Cannot adjust stock below zero! Current stock: 10.000, requested adjustment: -50.000"));

        AdjustStockRequest request = new AdjustStockRequest(new BigDecimal("-50.000"), "Over-reduction");

        mockMvc.perform(put("/api/admin/inventories/10/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot adjust stock below zero! Current stock: 10.000, requested adjustment: -50.000"));
    }

    @Test
    void testAdjustStock_InventoryNotFound_Returns404() throws Exception {
        when(adminService.adjustInventoryStock(eq(999L), any(), any(), any()))
                .thenThrow(new EntityNotFoundException("Inventory not found with id: 999"));

        AdjustStockRequest request = new AdjustStockRequest(new BigDecimal("2.000"), "Restock");

        mockMvc.perform(put("/api/admin/inventories/999/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Inventory not found with id: 999"));
    }
}
