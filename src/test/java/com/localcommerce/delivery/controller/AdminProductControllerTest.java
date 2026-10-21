package com.localcommerce.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcommerce.delivery.dto.CreateProductDTO;
import com.localcommerce.delivery.dto.ProductDTO;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.service.ProductAdminService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminProductControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ProductAdminService adminService;

    @InjectMocks
    private AdminProductController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testCreateProduct_AuthenticatedPrincipalOverridesSpoofedHeader() throws Exception {
        CreateProductDTO createDto = new CreateProductDTO(
                "OIL_SUNFLOWER_1L",
                "Sunflower Oil 1L",
                new BigDecimal("120.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                true,
                10L
        );

        ProductDTO createdDto = new ProductDTO(
                1L,
                "OIL_SUNFLOWER_1L",
                "Sunflower Oil 1L",
                new BigDecimal("120.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                true,
                null
        );

        when(adminService.createNewProduct(any(CreateProductDTO.class), eq("alice")))
                .thenReturn(createdDto);

        mockMvc.perform(post("/api/admin/products")
                        .principal(() -> "alice")
                        .header("X-Admin-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("OIL_SUNFLOWER_1L"));

        verify(adminService).createNewProduct(any(CreateProductDTO.class), eq("alice"));
    }

    @Test
    void testCreateProduct_MissingPrincipalFallsBackToUnknownAdmin() throws Exception {
        CreateProductDTO createDto = new CreateProductDTO(
                "OIL_SUNFLOWER_1L",
                "Sunflower Oil 1L",
                new BigDecimal("120.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                true,
                10L
        );

        ProductDTO createdDto = new ProductDTO(
                1L,
                "OIL_SUNFLOWER_1L",
                "Sunflower Oil 1L",
                new BigDecimal("120.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                true,
                null
        );

        when(adminService.createNewProduct(any(CreateProductDTO.class), eq("Unknown_Admin")))
                .thenReturn(createdDto);

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("OIL_SUNFLOWER_1L"));

        verify(adminService).createNewProduct(any(CreateProductDTO.class), eq("Unknown_Admin"));
    }

    @Test
    void testUpdatePrice_AuthenticatedPrincipalOverridesSpoofedHeader() throws Exception {
        ProductDTO updatedDto = new ProductDTO(
                1L,
                "OIL_SUNFLOWER_1L",
                "Sunflower Oil 1L",
                new BigDecimal("135.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                true,
                null
        );

        when(adminService.updatePrice(eq("OIL_SUNFLOWER_1L"), eq(new BigDecimal("135.00")), eq("alice")))
                .thenReturn(updatedDto);

        mockMvc.perform(patch("/api/admin/products/OIL_SUNFLOWER_1L/price")
                        .principal(() -> "alice")
                        .header("X-Admin-User", "bob")
                        .param("newPrice", "135.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.new_price").value(135.00));

        verify(adminService).updatePrice(eq("OIL_SUNFLOWER_1L"), eq(new BigDecimal("135.00")), eq("alice"));
    }

    @Test
    void testToggleActiveStatus_AuthenticatedPrincipalOverridesSpoofedHeader() throws Exception {
        ProductDTO updatedDto = new ProductDTO(
                1L,
                "OIL_SUNFLOWER_1L",
                "Sunflower Oil 1L",
                new BigDecimal("120.00"),
                new BigDecimal("1.000"),
                ProductUnit.L,
                false,
                null
        );

        when(adminService.toggleActiveStatus(eq("OIL_SUNFLOWER_1L"), eq(false), eq("alice")))
                .thenReturn(updatedDto);

        mockMvc.perform(patch("/api/admin/products/OIL_SUNFLOWER_1L/toggle-active")
                        .principal(() -> "alice")
                        .header("X-Admin-User", "bob")
                        .param("isActive", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.is_active").value(false));

        verify(adminService).toggleActiveStatus(eq("OIL_SUNFLOWER_1L"), eq(false), eq("alice"));
    }

    @Test
    void testGetAllProducts_Success() throws Exception {
        when(adminService.getAllProducts()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(adminService).getAllProducts();
    }
}
