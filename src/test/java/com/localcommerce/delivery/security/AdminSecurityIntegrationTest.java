package com.localcommerce.delivery.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest(properties = {
        "admin.username=testadmin",
        "admin.api.key=testkey",
        "admin.password.hash=${ADMIN_TEST_HASH}",
        "jwt.secret=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "jwt.expiration.ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:testdb_security;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=test",
        "whatsapp.api.token=test",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=test",
        "fallback.manager.phone=test",
        "authorized.rider.numbers=test"
})
public class AdminSecurityIntegrationTest {

    static {
        String hash = new BCryptPasswordEncoder().encode("testpass");
        System.setProperty("ADMIN_TEST_HASH", hash);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private com.localcommerce.delivery.repository.InventoryRepository inventoryRepository;

    @Autowired
    private com.localcommerce.delivery.repository.InventoryAuditLogRepository inventoryAuditLogRepository;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    public void testSuccessfulLogin() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"testadmin\", \"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    public void testInvalidLogin_WrongPassword() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"testadmin\", \"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    public void testMissingJwtOnProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden()); // Spring Security default without custom entry point is 403
    }

    @Test
    public void testInvalidJwtOnProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/products")
                .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testValidJwtAllowsAccess() throws Exception {
        // First login to get a real token
        String response = mockMvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"testadmin\", \"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = response.split("\"token\":\"")[1].split("\"")[0];

        // Access protected endpoint
        mockMvc.perform(get("/api/admin/products")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    public void testCorsOptionsRequest() throws Exception {
        mockMvc.perform(options("/api/admin/products")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"));
    }

    @Test
    public void testAdminMessagesEndpointProtectedByJwt() throws Exception {
        // 1. Missing token -> forbidden
        mockMvc.perform(get("/api/admin/messages/chats"))
                .andExpect(status().isForbidden());

        // 2. Obtain valid token
        String response = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testadmin\", \"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = response.split("\"token\":\"")[1].split("\"")[0];

        // 3. Valid token -> OK
        mockMvc.perform(get("/api/admin/messages/chats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    public void testAuditAttribution_UsesJwtPrincipal_AndIgnoresSpoofedHeader() throws Exception {
        // 1. Seed an inventory record
        com.localcommerce.delivery.model.Inventory inv = new com.localcommerce.delivery.model.Inventory();
        inv.setBaseSku("TEST_BASE_SKU");
        inv.setStockQuantity(new java.math.BigDecimal("20.000"));
        inv.setUnit(com.localcommerce.delivery.model.ProductUnit.KG);
        inv = inventoryRepository.save(inv);

        // 2. Obtain valid token for testadmin
        String response = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testadmin\", \"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = response.split("\"token\":\"")[1].split("\"")[0];

        // 3. Make an auditable request supplying a spoofed X-Admin-User: attacker
        mockMvc.perform(put("/api/admin/inventories/" + inv.getId() + "/stock")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Admin-User", "attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adjustmentQuantity\": 5.000, \"reason\": \"Restock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 4. Assert audit log records the authenticated principal ('testadmin') and NOT the spoofed header ('attacker')
        java.util.List<com.localcommerce.delivery.model.InventoryAuditLog> logs = inventoryAuditLogRepository.findAll();
        org.junit.jupiter.api.Assertions.assertFalse(logs.isEmpty(), "Audit log must be created");
        com.localcommerce.delivery.model.InventoryAuditLog lastLog = logs.get(logs.size() - 1);
        org.junit.jupiter.api.Assertions.assertEquals("testadmin", lastLog.getChangedBy(),
                "Audit log must record the authenticated JWT principal ('testadmin'), not the spoofed 'attacker'");
    }
}
