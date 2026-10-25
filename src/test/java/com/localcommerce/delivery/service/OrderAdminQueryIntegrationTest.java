package com.localcommerce.delivery.service;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.dto.OrderDetailDTO;
import com.localcommerce.delivery.dto.OrderSummaryCountsDTO;
import com.localcommerce.delivery.dto.OrderSummaryDTO;
import com.localcommerce.delivery.dto.PageResponseDTO;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.model.Customer;
import com.localcommerce.delivery.model.CustomerOrder;
import com.localcommerce.delivery.model.DeliveryRun;
import com.localcommerce.delivery.model.Inventory;
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.model.ProductUnit;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.repository.CustomerRepository;
import com.localcommerce.delivery.repository.DeliveryRunRepository;
import com.localcommerce.delivery.repository.InventoryRepository;
import com.localcommerce.delivery.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.localcommerce.delivery.utils.WhatsAppConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_order_admin_query;DB_CLOSE_DELAY=-1;DEFAULT_LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "store.latitude=10.000",
        "store.longitude=76.200",
        "store.max.radius.km=20.0",
        "whatsapp.verify.token=test",
        "whatsapp.api.url=test",
        "whatsapp.api.token=test",
        "whatsapp.catalog.id=test",
        "whatsapp.app.secret=test",
        "warehouse.phone.number=test",
        "fallback.manager.phone=test",
        "authorized.rider.numbers=919999999999,918888888888",
        "admin.api.key=test",
        "admin.username=testadmin",
        "ADMIN_PASSWORD_HASH=dummy",
        "JWT_SECRET=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=",
        "JWT_EXPIRATION_MS=10000",
        "spring.flyway.enabled=false"
})
class OrderAdminQueryIntegrationTest {

    @Autowired
    private OrderAdminService orderAdminService;

    @Autowired
    private CustomerOrderRepository orderRepo;

    @Autowired
    private CustomerRepository customerRepo;

    @Autowired
    private ProductRepository productRepo;

    @Autowired
    private InventoryRepository inventoryRepo;

    @Autowired
    private DeliveryRunRepository deliveryRunRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WhatsAppOutboundClient outboundClient;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM order_items");
        jdbcTemplate.execute("DELETE FROM customer_orders");
        jdbcTemplate.execute("DELETE FROM delivery_runs");
        jdbcTemplate.execute("DELETE FROM products");
        jdbcTemplate.execute("DELETE FROM inventories");
        jdbcTemplate.execute("DELETE FROM customers");
    }

    @Test
    void testCriteriaSearch_Wildcards_LiteralPercentAndUnderscoreEscaping() {
        // Customer 1: Name contains literal '%' and display ID contains literal '_'
        Customer c1 = new Customer();
        c1.setName("Alice % Special");
        c1.setWhatsappNumber("919876543201");
        c1 = customerRepo.save(c1);

        CustomerOrder o1 = new CustomerOrder();
        o1.setDisplayId("ORD_SPECIAL%01");
        o1.setCustomer(c1);
        o1.setStatus(OrderStatus.PENDING_WAREHOUSE);
        o1.setTotalAmount(new BigDecimal("150.00"));
        o1.setCreatedAt(LocalDateTime.now());
        o1.setDeliveryLatitude(12.97);
        o1.setDeliveryLongitude(77.59);
        o1.setStockReconciled(true);
        orderRepo.save(o1);

        // Customer 2: Normal Name and display ID with hyphen (no % or _)
        Customer c2 = new Customer();
        c2.setName("Bob Regular");
        c2.setWhatsappNumber("919876543202");
        c2 = customerRepo.save(c2);

        CustomerOrder o2 = new CustomerOrder();
        o2.setDisplayId("ORD-REGULAR-02");
        o2.setCustomer(c2);
        o2.setStatus(OrderStatus.PENDING_WAREHOUSE);
        o2.setTotalAmount(new BigDecimal("200.00"));
        o2.setCreatedAt(LocalDateTime.now());
        o2.setDeliveryLatitude(12.97);
        o2.setDeliveryLongitude(77.59);
        o2.setStockReconciled(true);
        orderRepo.save(o2);

        // 1. Search literal '%' -> Must match only Order 1 (both in name and displayId), NOT Order 2
        PageResponseDTO<OrderSummaryDTO> percentResults = orderAdminService.getOrders(
                0, 10, "%", null, null, null, null, null, "createdAt,desc"
        );
        assertEquals(1, percentResults.getContent().size(), "Search for '%' must match only the order with literal '%'");
        assertEquals("ORD_SPECIAL%01", percentResults.getContent().get(0).getDisplayId());

        // 2. Search literal '_' -> Must match only Order 1 ('ORD_SPECIAL%01'), NOT Order 2 ('ORD-REGULAR-02')
        PageResponseDTO<OrderSummaryDTO> underscoreResults = orderAdminService.getOrders(
                0, 10, "_", null, null, null, null, null, "createdAt,desc"
        );
        assertEquals(1, underscoreResults.getContent().size(), "Search for '_' must match only the order with literal '_'");
        assertEquals("ORD_SPECIAL%01", underscoreResults.getContent().get(0).getDisplayId());
    }

    @Test
    void testCriteriaSearch_PunctuationOnlyPhoneSearch_DoesNotMatchAllRows() {
        Customer c = new Customer();
        c.setName("Charlie Brown");
        c.setWhatsappNumber("919876543203");
        c = customerRepo.save(c);

        CustomerOrder o = new CustomerOrder();
        o.setDisplayId("ORD-CHARLIE-03");
        o.setCustomer(c);
        o.setStatus(OrderStatus.PACKING);
        o.setTotalAmount(new BigDecimal("300.00"));
        o.setCreatedAt(LocalDateTime.now());
        o.setStockReconciled(true);
        orderRepo.save(o);

        // Punctuation-only search: digitsOnly is empty, so phone predicate must NOT match any phone
        PageResponseDTO<OrderSummaryDTO> punctResults = orderAdminService.getOrders(
                0, 10, "+--()", null, null, null, null, null, "createdAt,desc"
        );
        assertTrue(punctResults.getContent().isEmpty(), "Punctuation-only search should not match ordinary orders");

        // Phone search with formatting (+91 98765-43203): digitsOnly = 919876543203
        PageResponseDTO<OrderSummaryDTO> phoneResults = orderAdminService.getOrders(
                0, 10, "+91 98765-43203", null, null, null, null, null, "createdAt,desc"
        );
        assertEquals(1, phoneResults.getContent().size(), "Formatted phone number must match customer phone");
        assertEquals("ORD-CHARLIE-03", phoneResults.getContent().get(0).getDisplayId());
    }

    @Test
    void testBatchItemCountAggregation_EmptyAndPopulatedPages() {
        Customer c = new Customer();
        c.setName("David");
        c.setWhatsappNumber("919876543204");
        c = customerRepo.save(c);

        Inventory inv = new Inventory();
        inv.setBaseSku("INV_WHEAT");
        inv.setStockQuantity(new BigDecimal("50.000"));
        inv.setUnit(ProductUnit.KG);
        inv = inventoryRepo.save(inv);

        Product p = new Product();
        p.setSku("WHEAT_1KG");
        p.setName("Whole Wheat");
        p.setCurrentPrice(new BigDecimal("45.00"));
        p.setInventory(inv);
        p.setPackageQuantity(new BigDecimal("1.0"));
        p.setPackageUnit(ProductUnit.KG);
        p = productRepo.save(p);

        // Order 1: Has 2 items
        CustomerOrder o1 = new CustomerOrder();
        o1.setDisplayId("ORD-WITH-ITEMS");
        o1.setCustomer(c);
        o1.setStatus(OrderStatus.READY);
        o1.setTotalAmount(new BigDecimal("90.00"));
        o1.setCreatedAt(LocalDateTime.now());
        o1.setStockReconciled(true);

        OrderItem i1 = new OrderItem();
        i1.setProduct(p);
        i1.setQuantity(1);
        i1.setLockedPrice(new BigDecimal("45.00"));
        i1.setCustomerOrder(o1);

        OrderItem i2 = new OrderItem();
        i2.setProduct(p);
        i2.setQuantity(1);
        i2.setLockedPrice(new BigDecimal("45.00"));
        i2.setCustomerOrder(o1);

        o1.setItems(new ArrayList<>(List.of(i1, i2)));
        orderRepo.save(o1);

        // Order 2: Has 0 items
        CustomerOrder o2 = new CustomerOrder();
        o2.setDisplayId("ORD-WITHOUT-ITEMS");
        o2.setCustomer(c);
        o2.setStatus(OrderStatus.READY);
        o2.setTotalAmount(BigDecimal.ZERO);
        o2.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        o2.setStockReconciled(true);
        o2.setItems(new ArrayList<>());
        orderRepo.save(o2);

        PageResponseDTO<OrderSummaryDTO> page = orderAdminService.getOrders(
                0, 10, null, null, null, null, null, null, "createdAt,desc"
        );

        assertEquals(2, page.getContent().size());
        OrderSummaryDTO summary1 = page.getContent().stream()
                .filter(s -> s.getDisplayId().equals("ORD-WITH-ITEMS"))
                .findFirst().orElseThrow();
        assertEquals(2, summary1.getItemCount(), "Order with 2 items must report itemCount = 2");

        OrderSummaryDTO summary2 = page.getContent().stream()
                .filter(s -> s.getDisplayId().equals("ORD-WITHOUT-ITEMS"))
                .findFirst().orElseThrow();
        assertEquals(0, summary2.getItemCount(), "Order with 0 items must report itemCount = 0");
    }

    @Test
    void testDetailRetrieval_WithAssociations_AndNullableDeductionUnit() {
        Customer c = new Customer();
        c.setName("Eve");
        c.setWhatsappNumber("919876543205");
        c.setAddressNotes("Sector 4");
        c = customerRepo.save(c);

        DeliveryRun run = new DeliveryRun();
        run.setRiderPhoneNumber("919999000001");
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);
        run.setDispatchedAt(LocalDateTime.now().minusMinutes(15));
        run = deliveryRunRepo.save(run);

        Inventory inv = new Inventory();
        inv.setBaseSku("INV_OIL");
        inv.setStockQuantity(new BigDecimal("20.000"));
        inv.setUnit(ProductUnit.L);
        inv = inventoryRepo.save(inv);

        Product p = new Product();
        p.setSku("OIL_1L");
        p.setName("Sunflower Oil");
        p.setCurrentPrice(new BigDecimal("120.00"));
        p.setInventory(inv);
        p.setPackageQuantity(new BigDecimal("1.0"));
        p.setPackageUnit(ProductUnit.L);
        p = productRepo.save(p);

        CustomerOrder o = new CustomerOrder();
        o.setDisplayId("ORD-DETAIL-05");
        o.setCustomer(c);
        o.setDeliveryRun(run);
        o.setStatus(OrderStatus.DISPATCHED);
        o.setTotalAmount(new BigDecimal("240.00"));
        o.setCreatedAt(LocalDateTime.now().minusHours(1));
        o.setReadyAt(LocalDateTime.now().minusMinutes(30));
        o.setDeliveryLatitude(12.9716);
        o.setDeliveryLongitude(77.5946);
        o.setStockReconciled(true);

        // Item 1: Confirmed deduction snapshot
        OrderItem item1 = new OrderItem();
        item1.setProduct(p);
        item1.setQuantity(2);
        item1.setLockedPrice(new BigDecimal("120.00"));
        item1.setDeductedQuantity(new BigDecimal("2.000"));
        item1.setStockReconciliationRequired(false);
        item1.setCustomerOrder(o);

        // Item 2: Unconfirmed / missing deduction snapshot
        OrderItem item2 = new OrderItem();
        item2.setProduct(p);
        item2.setQuantity(1);
        item2.setLockedPrice(new BigDecimal("120.00"));
        item2.setDeductedQuantity(null); // null snapshot
        item2.setStockReconciliationRequired(false);
        item2.setCustomerOrder(o);

        o.setItems(new ArrayList<>(List.of(item1, item2)));
        CustomerOrder saved = orderRepo.save(o);

        // Execute query against database
        OrderDetailDTO detail = orderAdminService.getOrderDetails(saved.getId());

        assertNotNull(detail);
        assertEquals("ORD-DETAIL-05", detail.getDisplayId());
        assertEquals("Eve", detail.getCustomer().getName());
        assertEquals("Sector 4", detail.getCustomer().getAddressNotes());
        assertEquals("919999000001", detail.getDeliveryRun().getRiderPhoneNumber());
        assertTrue(detail.getDelivery().isHasLocation());
        assertEquals("https://www.google.com/maps?q=12.9716,77.5946", detail.getDelivery().getGoogleMapsUrl());

        assertEquals(2, detail.getItems().size());
        OrderDetailDTO.OrderItemDetail dItem1 = detail.getItems().stream()
                .filter(i -> i.getDeductedQuantity() != null)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("2.000"), dItem1.getDeductedQuantity());
        assertEquals("L", dItem1.getDeductedUnit());
        assertEquals(new BigDecimal("240.00"), dItem1.getLineTotal());

        OrderDetailDTO.OrderItemDetail dItem2 = detail.getItems().stream()
                .filter(i -> i.getDeductedQuantity() == null)
                .findFirst().orElseThrow();
        assertNull(dItem2.getDeductedQuantity());
        assertNull(dItem2.getDeductedUnit(), "Item with null deductedQuantity must have null deductedUnit");
    }

    @Test
    void testAttentionQueries_MissingLocationAndStockReconciliation() {
        // Order 1: PENDING_WAREHOUSE with null location -> Needs attention (LOCATION_MISSING)
        CustomerOrder o1 = new CustomerOrder();
        o1.setDisplayId("ORD-ATTN-1");
        o1.setStatus(OrderStatus.PENDING_WAREHOUSE);
        o1.setStockReconciled(true);
        o1.setDeliveryLatitude(null);
        o1.setDeliveryLongitude(null);
        o1.setCreatedAt(LocalDateTime.now());
        orderRepo.save(o1);

        // Order 2: CANCELLED with stockReconciled = false -> Needs attention (STOCK_RECONCILIATION_REQUIRED)
        CustomerOrder o2 = new CustomerOrder();
        o2.setDisplayId("ORD-ATTN-2");
        o2.setStatus(OrderStatus.CANCELLED);
        o2.setStockReconciled(false);
        o2.setDeliveryLatitude(12.97);
        o2.setDeliveryLongitude(77.59);
        o2.setCreatedAt(LocalDateTime.now());
        orderRepo.save(o2);

        // Order 3: CREATED with null location -> Does NOT need attention
        CustomerOrder o3 = new CustomerOrder();
        o3.setDisplayId("ORD-ATTN-3");
        o3.setStatus(OrderStatus.CREATED);
        o3.setStockReconciled(true);
        o3.setDeliveryLatitude(null);
        o3.setDeliveryLongitude(null);
        o3.setCreatedAt(LocalDateTime.now());
        orderRepo.save(o3);

        // Order 4: DELIVERED with null location -> Does NOT need attention
        CustomerOrder o4 = new CustomerOrder();
        o4.setDisplayId("ORD-ATTN-4");
        o4.setStatus(OrderStatus.DELIVERED);
        o4.setStockReconciled(true);
        o4.setDeliveryLatitude(null);
        o4.setDeliveryLongitude(null);
        o4.setDeliveredAt(LocalDateTime.now());
        o4.setCreatedAt(LocalDateTime.now());
        orderRepo.save(o4);

        // Order 5: READY with coordinates -> Does NOT need attention
        CustomerOrder o5 = new CustomerOrder();
        o5.setDisplayId("ORD-ATTN-5");
        o5.setStatus(OrderStatus.READY);
        o5.setStockReconciled(true);
        o5.setDeliveryLatitude(12.97);
        o5.setDeliveryLongitude(77.59);
        o5.setCreatedAt(LocalDateTime.now());
        orderRepo.save(o5);

        // 1. Repository direct count
        long dbAttentionCount = orderRepo.countOrdersNeedingAttention(OrderAdminService.ACTIVE_FULFILLMENT_STATUSES);
        assertEquals(2, dbAttentionCount, "Exactly 2 orders should need attention in database");

        // 2. Service summary counts
        OrderSummaryCountsDTO summaryCounts = orderAdminService.getSummaryCounts();
        assertEquals(2, summaryCounts.getNeedsAttention());

        // 3. Filtered list with needsAttention = true
        PageResponseDTO<OrderSummaryDTO> needsAttnPage = orderAdminService.getOrders(
                0, 10, null, null, null, null, null, true, "createdAt,desc"
        );
        assertEquals(2, needsAttnPage.getContent().size());
        List<String> attnDisplayIds = needsAttnPage.getContent().stream().map(OrderSummaryDTO::getDisplayId).toList();
        assertTrue(attnDisplayIds.contains("ORD-ATTN-1"));
        assertTrue(attnDisplayIds.contains("ORD-ATTN-2"));

        // 4. Filtered list with needsAttention = false
        PageResponseDTO<OrderSummaryDTO> noAttnPage = orderAdminService.getOrders(
                0, 10, null, null, null, null, null, false, "createdAt,desc"
        );
        assertEquals(3, noAttnPage.getContent().size());
        List<String> noAttnDisplayIds = noAttnPage.getContent().stream().map(OrderSummaryDTO::getDisplayId).toList();
        assertTrue(noAttnDisplayIds.contains("ORD-ATTN-3"));
        assertTrue(noAttnDisplayIds.contains("ORD-ATTN-4"));
        assertTrue(noAttnDisplayIds.contains("ORD-ATTN-5"));
    }

    @Test
    void testDateFiltering_IstBoundaries_CorrectlyFiltersUtcStoredOrders() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), WhatsAppConstants.ASIA_KOLKATA_ZONE);
        orderAdminService.setClock(fixedClock);

        try {
            Customer customer = new Customer();
            customer.setName("Suresh");
            customer.setWhatsappNumber("919876543299");
            customer = customerRepo.save(customer);

            // 1. Order at 02:00 IST on 25 October 2026 -> stored as 2026-10-24T20:30:00 UTC
            CustomerOrder o1 = new CustomerOrder();
            o1.setDisplayId("ORD-OCT25-EARLY");
            o1.setCustomer(customer);
            o1.setStatus(OrderStatus.DELIVERED);
            o1.setTotalAmount(new BigDecimal("120.00"));
            o1.setDeliveryLatitude(10.01);
            o1.setDeliveryLongitude(76.21);
            o1.setStockReconciled(true);
            o1 = orderRepo.save(o1);
            jdbcTemplate.update("UPDATE customer_orders SET created_at = ?, delivered_at = ? WHERE id = ?",
                    LocalDateTime.of(2026, 10, 24, 20, 30, 0),
                    LocalDateTime.of(2026, 10, 24, 20, 45, 0),
                    o1.getId());

            // 2. Order at 23:30 IST on 25 October 2026 -> stored as 2026-10-25T18:00:00 UTC
            CustomerOrder o2 = new CustomerOrder();
            o2.setDisplayId("ORD-OCT25-LATE");
            o2.setCustomer(customer);
            o2.setStatus(OrderStatus.READY);
            o2.setTotalAmount(new BigDecimal("250.00"));
            o2.setDeliveryLatitude(10.02);
            o2.setDeliveryLongitude(76.22);
            o2.setStockReconciled(true);
            o2 = orderRepo.save(o2);
            jdbcTemplate.update("UPDATE customer_orders SET created_at = ? WHERE id = ?",
                    LocalDateTime.of(2026, 10, 25, 18, 0, 0),
                    o2.getId());

            // 3. Order at 01:00 IST on 26 October 2026 -> stored as 2026-10-25T19:30:00 UTC
            CustomerOrder o3 = new CustomerOrder();
            o3.setDisplayId("ORD-OCT26-EARLY");
            o3.setCustomer(customer);
            o3.setStatus(OrderStatus.PENDING_WAREHOUSE);
            o3.setTotalAmount(new BigDecimal("300.00"));
            o3.setDeliveryLatitude(10.03);
            o3.setDeliveryLongitude(76.23);
            o3.setStockReconciled(true);
            o3 = orderRepo.save(o3);
            jdbcTemplate.update("UPDATE customer_orders SET created_at = ? WHERE id = ?",
                    LocalDateTime.of(2026, 10, 25, 19, 30, 0),
                    o3.getId());

            // Query with explicit IST calendar date range: 25 October 2026
            PageResponseDTO<OrderSummaryDTO> oct25Orders = orderAdminService.getOrders(
                    0, 10, null, null,
                    LocalDate.of(2026, 10, 25),
                    LocalDate.of(2026, 10, 25),
                    null, null, "createdAt,desc"
            );

            List<String> orderIds = oct25Orders.getContent().stream().map(OrderSummaryDTO::getDisplayId).toList();
            assertEquals(2, orderIds.size(), "Exactly 2 orders belong to 25 October IST");
            assertTrue(orderIds.contains("ORD-OCT25-EARLY"), "02:00 IST order must belong to 25 October");
            assertTrue(orderIds.contains("ORD-OCT25-LATE"), "23:30 IST order must belong to 25 October");
            assertFalse(orderIds.contains("ORD-OCT26-EARLY"), "01:00 IST order on 26 October must NOT belong to 25 October");

            // Query summary KPI counts for "Today" (25 October)
            OrderSummaryCountsDTO counts = orderAdminService.getSummaryCounts();
            assertEquals(2, counts.getCreatedToday(), "Created Today must count 2 orders belonging to 25 October IST");
            assertEquals(1, counts.getDeliveredToday(), "Delivered Today must count 1 order delivered on 25 October IST");
        } finally {
            orderAdminService.setClock(Clock.system(WhatsAppConstants.ASIA_KOLKATA_ZONE));
        }
    }
}
