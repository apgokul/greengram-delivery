package com.localcommerce.delivery.service;

import com.localcommerce.delivery.dto.OrderDetailDTO;
import com.localcommerce.delivery.dto.OrderItemCountProjection;
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
import com.localcommerce.delivery.utils.WhatsAppConstants;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderAdminServiceTest {

    @Mock
    private CustomerOrderRepository orderRepo;

    @InjectMocks
    private OrderAdminService service;

    private final Instant fixedInstant = Instant.parse("2026-10-22T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(fixedInstant, WhatsAppConstants.ASIA_KOLKATA_ZONE);

    @BeforeEach
    void setUp() {
        service.setClock(fixedClock);
    }

    @Test
    void testGetOrders_DefaultTodayDateBoundaries_AndPagination() {
        CustomerOrder order = new CustomerOrder();
        order.setId(1001L);
        order.setDisplayId("ORD-1001");
        order.setStatus(OrderStatus.PENDING_WAREHOUSE);
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setCreatedAt(LocalDateTime.now(fixedClock));
        order.setDeliveryLatitude(12.9716);
        order.setDeliveryLongitude(77.5946);
        order.setStockReconciled(true);

        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(orderRepo.countItemsByOrderIds(List.of(1001L)))
                .thenReturn(List.of(new OrderItemCountProjection(1001L, 2L)));

        PageResponseDTO<OrderSummaryDTO> result = service.getOrders(
                0, 20, null, null, null, null, null, null, "createdAt,desc"
        );

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        OrderSummaryDTO summary = result.getContent().get(0);
        assertEquals(1001L, summary.getId());
        assertEquals("ORD-1001", summary.getDisplayId());
        assertEquals(2, summary.getItemCount());
        assertTrue(summary.isHasLocation());
        assertFalse(summary.isNeedsAttention());
        assertTrue(summary.getAttentionReasons().isEmpty());
    }

    @Test
    void testGetOrders_DateValidation_RejectsStartDateAfterEndDate() {
        LocalDate start = LocalDate.of(2026, 10, 25);
        LocalDate end = LocalDate.of(2026, 10, 20);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.getOrders(0, 20, null, null, start, end, null, null, "createdAt,desc")
        );
        assertEquals("startDate cannot be after endDate", ex.getMessage());
    }

    @Test
    void testGetOrders_DateValidation_RejectsDateSpanExceeding90Days() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 5, 1); // > 90 days

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.getOrders(0, 20, null, null, start, end, null, null, "createdAt,desc")
        );
        assertEquals("Date range cannot exceed 90 days", ex.getMessage());
    }

    @Test
    void testGetOrders_PageSizeValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                service.getOrders(-1, 20, null, null, null, null, null, null, "createdAt,desc")
        );
        assertThrows(IllegalArgumentException.class, () ->
                service.getOrders(0, 0, null, null, null, null, null, null, "createdAt,desc")
        );
        assertThrows(IllegalArgumentException.class, () ->
                service.getOrders(0, 101, null, null, null, null, null, null, "createdAt,desc")
        );
    }

    @Test
    void testGetOrders_SortValidation_RejectsUnsupportedProperty() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.getOrders(0, 20, null, null, null, null, null, null, "updatedAt,desc")
        );
        assertTrue(ex.getMessage().contains("Invalid sort property: updatedAt"));
    }

    @Test
    void testGetOrders_SortDeterministic_AppendsSecondaryIdSort() {
        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        service.getOrders(0, 20, null, null, null, null, null, null, "totalAmount,asc");

        verify(orderRepo).findAll(any(Specification.class), pageableCaptor.capture());
        Sort sort = pageableCaptor.getValue().getSort();
        List<Sort.Order> orders = sort.toList();
        assertEquals(2, orders.size(), "Non-ID sort should have exactly 2 directives (primary + secondary id tie-breaker)");
        assertEquals("totalAmount", orders.get(0).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(0).getDirection());
        assertEquals("id", orders.get(1).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(1).getDirection());
    }

    @Test
    void testGetOrders_Sort_IdAsc_DoesNotDuplicateIdDirective() {
        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        service.getOrders(0, 20, null, null, null, null, null, null, "id,asc");

        verify(orderRepo).findAll(any(Specification.class), pageableCaptor.capture());
        Sort sort = pageableCaptor.getValue().getSort();
        List<Sort.Order> orders = sort.toList();
        assertEquals(1, orders.size(), "ID sort must have exactly 1 directive without duplicate id order");
        assertEquals("id", orders.get(0).getProperty());
        assertEquals(Sort.Direction.ASC, orders.get(0).getDirection());
    }

    @Test
    void testGetOrders_Sort_IdDesc_DoesNotDuplicateIdDirective() {
        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        service.getOrders(0, 20, null, null, null, null, null, null, "id,desc");

        verify(orderRepo).findAll(any(Specification.class), pageableCaptor.capture());
        Sort sort = pageableCaptor.getValue().getSort();
        List<Sort.Order> orders = sort.toList();
        assertEquals(1, orders.size(), "ID sort must have exactly 1 directive without duplicate id order");
        assertEquals("id", orders.get(0).getProperty());
        assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
    }

    @Test
    void testGetOrders_NeedsAttention_LocationMissing_OnlyForActiveFulfillment() {
        // Order 1: PENDING_WAREHOUSE with null location -> needs attention
        CustomerOrder o1 = new CustomerOrder();
        o1.setId(1001L);
        o1.setStatus(OrderStatus.PENDING_WAREHOUSE);
        o1.setStockReconciled(true);
        o1.setDeliveryLatitude(null);
        o1.setDeliveryLongitude(null);

        // Order 2: CREATED with null location -> does NOT need attention
        CustomerOrder o2 = new CustomerOrder();
        o2.setId(1002L);
        o2.setStatus(OrderStatus.CREATED);
        o2.setStockReconciled(true);
        o2.setDeliveryLatitude(null);
        o2.setDeliveryLongitude(null);

        // Order 3: CANCELLED with null location -> does NOT need attention
        CustomerOrder o3 = new CustomerOrder();
        o3.setId(1003L);
        o3.setStatus(OrderStatus.CANCELLED);
        o3.setStockReconciled(true);
        o3.setDeliveryLatitude(null);
        o3.setDeliveryLongitude(null);

        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(o1, o2, o3)));
        when(orderRepo.countItemsByOrderIds(any()))
                .thenReturn(Collections.emptyList());

        PageResponseDTO<OrderSummaryDTO> result = service.getOrders(
                0, 20, null, null, null, null, null, null, "createdAt,desc"
        );

        OrderSummaryDTO s1 = result.getContent().get(0);
        assertTrue(s1.isNeedsAttention());
        assertTrue(s1.getAttentionReasons().contains("LOCATION_MISSING"));
        assertFalse(s1.isHasLocation());

        OrderSummaryDTO s2 = result.getContent().get(1);
        assertFalse(s2.isNeedsAttention());
        assertFalse(s2.isHasLocation());
        assertTrue(s2.getAttentionReasons().isEmpty());

        OrderSummaryDTO s3 = result.getContent().get(2);
        assertFalse(s3.isNeedsAttention());
        assertFalse(s3.isHasLocation());
        assertTrue(s3.getAttentionReasons().isEmpty());
    }

    @Test
    void testGetOrders_NeedsAttention_StockReconciliationRequired() {
        CustomerOrder order = new CustomerOrder();
        order.setId(1004L);
        order.setStatus(OrderStatus.CANCELLED);
        order.setStockReconciled(false); // Discrepancy!
        order.setDeliveryLatitude(12.9716);
        order.setDeliveryLongitude(77.5946);

        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(orderRepo.countItemsByOrderIds(any()))
                .thenReturn(Collections.emptyList());

        PageResponseDTO<OrderSummaryDTO> result = service.getOrders(
                0, 20, null, null, null, null, null, null, "createdAt,desc"
        );

        OrderSummaryDTO s = result.getContent().get(0);
        assertTrue(s.isNeedsAttention());
        assertTrue(s.getAttentionReasons().contains("STOCK_RECONCILIATION_REQUIRED"));
        assertFalse(s.isStockReconciled());
    }

    @Test
    void testGetOrders_LocationRequiresBothCoordinates() {
        // One coordinate is null -> unusable location
        CustomerOrder order = new CustomerOrder();
        order.setId(1005L);
        order.setStatus(OrderStatus.PACKING);
        order.setStockReconciled(true);
        order.setDeliveryLatitude(12.9716);
        order.setDeliveryLongitude(null); // Longitude missing!

        when(orderRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));

        PageResponseDTO<OrderSummaryDTO> result = service.getOrders(
                0, 20, null, null, null, null, null, null, "createdAt,desc"
        );

        OrderSummaryDTO s = result.getContent().get(0);
        assertFalse(s.isHasLocation());
        assertTrue(s.isNeedsAttention());
        assertTrue(s.getAttentionReasons().contains("LOCATION_MISSING"));
    }

    @Test
    void testGetOrderDetails_Success() {
        Customer customer = new Customer();
        customer.setId(50L);
        customer.setName("John Doe");
        customer.setWhatsappNumber("919876543210");
        customer.setAddressNotes("Near Water Tank");

        Inventory inv = new Inventory();
        inv.setId(1L);
        inv.setUnit(ProductUnit.KG);

        Product product = new Product();
        product.setId(10L);
        product.setSku("CHILLI_100G");
        product.setName("Chilli Powder");
        product.setPackageQuantity(new BigDecimal("100.0"));
        product.setPackageUnit(ProductUnit.G);
        product.setInventory(inv);

        OrderItem item = new OrderItem();
        item.setId(201L);
        item.setProduct(product);
        item.setQuantity(3);
        item.setLockedPrice(new BigDecimal("40.00"));
        item.setDeductedQuantity(new BigDecimal("0.300"));
        item.setStockReconciliationRequired(false);

        DeliveryRun run = new DeliveryRun();
        run.setId(8L);
        run.setRiderPhoneNumber("919999999999");
        run.setDispatchedAt(LocalDateTime.of(2026, 10, 22, 14, 0));
        run.setStatus(DeliveryRun.RunStatus.IN_PROGRESS);

        CustomerOrder order = new CustomerOrder();
        order.setId(1042L);
        order.setDisplayId("ORD-1042");
        order.setStatus(OrderStatus.DISPATCHED);
        order.setTotalAmount(new BigDecimal("120.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 10, 22, 13, 0));
        order.setReadyAt(LocalDateTime.of(2026, 10, 22, 13, 45));
        order.setDeliveryLatitude(12.9716);
        order.setDeliveryLongitude(77.5946);
        order.setStockReconciled(true);
        order.setCustomer(customer);
        order.setDeliveryRun(run);
        order.setItems(List.of(item));

        when(orderRepo.findByIdWithDetails(1042L)).thenReturn(Optional.of(order));

        OrderDetailDTO detail = service.getOrderDetails(1042L);

        assertNotNull(detail);
        assertEquals(1042L, detail.getId());
        assertEquals("ORD-1042", detail.getDisplayId());
        assertEquals(OrderStatus.DISPATCHED, detail.getStatus());
        assertEquals(new BigDecimal("120.00"), detail.getTotalAmount());
        assertTrue(detail.getDelivery().isHasLocation());
        assertEquals("https://www.google.com/maps?q=12.9716,77.5946", detail.getDelivery().getGoogleMapsUrl());

        // Check line items
        assertEquals(1, detail.getItems().size());
        OrderDetailDTO.OrderItemDetail itemDetail = detail.getItems().get(0);
        assertEquals("CHILLI_100G", itemDetail.getProductSku());
        assertEquals(new BigDecimal("120.00"), itemDetail.getLineTotal()); // 40.00 * 3
        assertEquals(new BigDecimal("0.300"), itemDetail.getDeductedQuantity());
        assertEquals("KG", itemDetail.getDeductedUnit());

        // Check timeline
        assertEquals(4, detail.getTimeline().size());
        assertEquals("COMPLETED", detail.getTimeline().get(0).getStatus()); // Placed
        assertEquals("COMPLETED", detail.getTimeline().get(1).getStatus()); // Ready
        assertEquals("COMPLETED", detail.getTimeline().get(2).getStatus()); // Dispatched
        assertEquals("PENDING", detail.getTimeline().get(3).getStatus());   // Delivered
    }

    @Test
    void testGetOrderDetails_UnconfirmedOrder_NullDeductedQuantityDoesNotFabricateUnit() {
        Inventory inv = new Inventory();
        inv.setId(2L);
        inv.setUnit(ProductUnit.KG);

        Product product = new Product();
        product.setId(20L);
        product.setSku("ONION_1KG");
        product.setName("Fresh Onion");
        product.setPackageQuantity(new BigDecimal("1.0"));
        product.setPackageUnit(ProductUnit.KG);
        product.setInventory(inv);

        OrderItem item = new OrderItem();
        item.setId(301L);
        item.setProduct(product);
        item.setQuantity(2);
        item.setLockedPrice(new BigDecimal("35.00"));
        item.setDeductedQuantity(null); // Unconfirmed order: no snapshot yet
        item.setStockReconciliationRequired(false);

        CustomerOrder order = new CustomerOrder();
        order.setId(1050L);
        order.setDisplayId("ORD-1050");
        order.setStatus(OrderStatus.CREATED);
        order.setTotalAmount(new BigDecimal("70.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 10, 22, 11, 0));
        order.setStockReconciled(true);
        order.setItems(List.of(item));

        when(orderRepo.findByIdWithDetails(1050L)).thenReturn(Optional.of(order));

        OrderDetailDTO detail = service.getOrderDetails(1050L);

        assertNotNull(detail);
        assertEquals(1, detail.getItems().size());
        OrderDetailDTO.OrderItemDetail itemDetail = detail.getItems().get(0);
        assertEquals("ONION_1KG", itemDetail.getProductSku());
        assertNull(itemDetail.getDeductedQuantity(), "Unconfirmed order must have null deductedQuantity");
        assertNull(itemDetail.getDeductedUnit(), "Unconfirmed order must have null deductedUnit, not fabricate from inventory");
        assertEquals(new BigDecimal("70.00"), itemDetail.getLineTotal());
    }

    @Test
    void testGetOrderDetails_CancelledOrder_TimelineSkippedAndUnrecordedTimestamp() {
        CustomerOrder order = new CustomerOrder();
        order.setId(1043L);
        order.setDisplayId("ORD-1043");
        order.setStatus(OrderStatus.CANCELLED);
        order.setTotalAmount(new BigDecimal("50.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 10, 22, 10, 0));
        order.setCancellationReason("Customer changed mind");
        order.setStockReconciled(true);
        order.setItems(Collections.emptyList());

        when(orderRepo.findByIdWithDetails(1043L)).thenReturn(Optional.of(order));

        OrderDetailDTO detail = service.getOrderDetails(1043L);

        assertNotNull(detail);
        assertEquals(OrderStatus.CANCELLED, detail.getStatus());
        // 5 timeline milestones: Placed, Ready, Dispatched, Delivered, Cancelled
        assertEquals(5, detail.getTimeline().size());
        assertEquals("COMPLETED", detail.getTimeline().get(0).getStatus()); // Placed
        assertEquals("SKIPPED", detail.getTimeline().get(1).getStatus());   // Ready
        assertEquals("SKIPPED", detail.getTimeline().get(2).getStatus());   // Dispatched
        assertEquals("SKIPPED", detail.getTimeline().get(3).getStatus());   // Delivered

        OrderDetailDTO.TimelineEvent cancelEvent = detail.getTimeline().get(4);
        assertEquals("CANCELLED", cancelEvent.getMilestone());
        assertNull(cancelEvent.getTimestamp());
        assertFalse(cancelEvent.isRecorded());
        assertEquals("Customer changed mind", cancelEvent.getDetail());
    }

    @Test
    void testGetOrderDetails_NotFound_ThrowsEntityNotFoundException() {
        when(orderRepo.findByIdWithDetails(9999L)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () ->
                service.getOrderDetails(9999L)
        );
        assertEquals("Order not found: 9999", ex.getMessage());
    }

    @Test
    void testGetSummaryCounts_CalculatesTodayBoundsInAsiaKolkata() {
        // Instant is 2026-10-22T12:00:00Z -> Asia/Kolkata is 2026-10-22 17:30:00
        LocalDate todayKolkata = LocalDate.of(2026, 10, 22);
        LocalDateTime expectedStartUtc = todayKolkata.atStartOfDay(WhatsAppConstants.ASIA_KOLKATA_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime expectedEndUtc = todayKolkata.plusDays(1).atStartOfDay(WhatsAppConstants.ASIA_KOLKATA_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        when(orderRepo.countOrdersCreatedBetween(expectedStartUtc, expectedEndUtc)).thenReturn(15L);
        when(orderRepo.countOrdersByStatusIn(OrderAdminService.IN_PROGRESS_STATUSES)).thenReturn(6L);
        when(orderRepo.countOrdersDeliveredBetween(OrderStatus.DELIVERED, expectedStartUtc, expectedEndUtc)).thenReturn(9L);
        when(orderRepo.countOrdersNeedingAttention(OrderAdminService.ACTIVE_FULFILLMENT_STATUSES)).thenReturn(2L);

        OrderSummaryCountsDTO counts = service.getSummaryCounts();

        assertEquals(15L, counts.getCreatedToday());
        assertEquals(6L, counts.getInProgress());
        assertEquals(9L, counts.getDeliveredToday());
        assertEquals(2L, counts.getNeedsAttention());
    }

    @Test
    void testRegression_DateBoundaries_25OctoberSemanticProof() {
        // Fix clock to 2026-10-25T10:00:00Z -> In Asia/Kolkata, this is 2026-10-25 15:30:00 (3:30 PM)
        Instant fixed25Oct = Instant.parse("2026-10-25T10:00:00Z");
        Clock clock25Oct = Clock.fixed(fixed25Oct, WhatsAppConstants.ASIA_KOLKATA_ZONE);
        service.setClock(clock25Oct);

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        when(orderRepo.countOrdersCreatedBetween(startCaptor.capture(), endCaptor.capture())).thenReturn(2L);
        when(orderRepo.countOrdersByStatusIn(any())).thenReturn(0L);
        when(orderRepo.countOrdersDeliveredBetween(eq(OrderStatus.DELIVERED), any(), any())).thenReturn(1L);
        when(orderRepo.countOrdersNeedingAttention(any())).thenReturn(0L);

        OrderSummaryCountsDTO counts = service.getSummaryCounts();
        assertNotNull(counts);

        LocalDateTime queryStart = startCaptor.getValue();
        LocalDateTime queryEnd = endCaptor.getValue();

        // 25 October 00:00:00 IST in UTC is 2026-10-24 18:30:00
        assertEquals(LocalDateTime.of(2026, 10, 24, 18, 30, 0), queryStart);
        // 26 October 00:00:00 IST in UTC is 2026-10-25 18:30:00
        assertEquals(LocalDateTime.of(2026, 10, 25, 18, 30, 0), queryEnd);

        // 1. Order at 02:00 IST on 25 October 2026 -> stored as 2026-10-24T20:30:00 UTC
        LocalDateTime orderEarlyMorningUtc = LocalDateTime.of(2026, 10, 24, 20, 30, 0);
        assertTrue(!orderEarlyMorningUtc.isBefore(queryStart) && orderEarlyMorningUtc.isBefore(queryEnd),
                "02:00 IST order (2026-10-24T20:30:00 UTC) must fall inside 25 October's query bounds");

        // 2. Order at 23:30 IST on 25 October 2026 -> stored as 2026-10-25T18:00:00 UTC
        LocalDateTime orderLateNightUtc = LocalDateTime.of(2026, 10, 25, 18, 0, 0);
        assertTrue(!orderLateNightUtc.isBefore(queryStart) && orderLateNightUtc.isBefore(queryEnd),
                "23:30 IST order (2026-10-25T18:00:00 UTC) must fall inside 25 October's query bounds");

        // 3. Order at 01:00 IST on 26 October 2026 -> stored as 2026-10-25T19:30:00 UTC
        LocalDateTime orderNextDayUtc = LocalDateTime.of(2026, 10, 25, 19, 30, 0);
        assertFalse(!orderNextDayUtc.isBefore(queryStart) && orderNextDayUtc.isBefore(queryEnd),
                "01:00 IST order on 26 October (2026-10-25T19:30:00 UTC) must NOT fall inside 25 October's query bounds");

        // 4. Delivered Today uses the exact same [start, end) bounds
        ArgumentCaptor<LocalDateTime> deliveredStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> deliveredEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepo).countOrdersDeliveredBetween(eq(OrderStatus.DELIVERED), deliveredStartCaptor.capture(), deliveredEndCaptor.capture());
        assertEquals(queryStart, deliveredStartCaptor.getValue());
        assertEquals(queryEnd, deliveredEndCaptor.getValue());
    }
}
