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
import com.localcommerce.delivery.model.OrderItem;
import com.localcommerce.delivery.model.Product;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OrderAdminService {

    private final CustomerOrderRepository orderRepo;

    private Clock clock = Clock.system(WhatsAppConstants.ASIA_KOLKATA_ZONE);

    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public static final List<OrderStatus> ACTIVE_FULFILLMENT_STATUSES = List.of(
            OrderStatus.PENDING_WAREHOUSE,
            OrderStatus.PACKING,
            OrderStatus.READY,
            OrderStatus.DISPATCHED
    );

    public static final List<OrderStatus> IN_PROGRESS_STATUSES = List.of(
            OrderStatus.PENDING_WAREHOUSE,
            OrderStatus.PACKING,
            OrderStatus.READY,
            OrderStatus.DISPATCHED
    );

    public static final long MAX_DATE_RANGE_DAYS = 90L;

    public static final Set<String> SUPPORTED_SORT_PROPERTIES = Set.of(
            "createdAt",
            "id",
            "totalAmount"
    );

    /**
     * Retrieves a paginated, filtered list of orders for the admin dashboard.
     */
    public PageResponseDTO<OrderSummaryDTO> getOrders(
            int page,
            int size,
            String search,
            List<OrderStatus> statuses,
            LocalDate startDate,
            LocalDate endDate,
            Boolean hasLocation,
            Boolean needsAttention,
            String sortParam
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }

        // 1. Date range resolution & validation
        LocalDate effectiveStartDate = startDate;
        LocalDate effectiveEndDate = endDate;

        if (effectiveStartDate == null && effectiveEndDate == null) {
            // Default to Today in Asia/Kolkata
            LocalDate today = LocalDate.now(clock);
            effectiveStartDate = today;
            effectiveEndDate = today;
        } else if (effectiveStartDate != null && effectiveEndDate == null) {
            // Start date provided without end date -> default end date to today
            effectiveEndDate = LocalDate.now(clock);
        }

        if (effectiveStartDate != null && effectiveEndDate != null) {
            if (effectiveStartDate.isAfter(effectiveEndDate)) {
                throw new IllegalArgumentException("startDate cannot be after endDate");
            }
            if (ChronoUnit.DAYS.between(effectiveStartDate, effectiveEndDate) > MAX_DATE_RANGE_DAYS) {
                throw new IllegalArgumentException("Date range cannot exceed 90 days");
            }
        }

        LocalDateTime fromTimestamp = (effectiveStartDate != null) ? effectiveStartDate.atStartOfDay() : null;
        LocalDateTime toTimestamp = (effectiveEndDate != null) ? effectiveEndDate.plusDays(1).atStartOfDay() : null;

        // 2. Sorting resolution with deterministic secondary key
        Sort sort = parseSort(sortParam);
        Pageable pageable = PageRequest.of(page, size, sort);

        // 3. Specification assembly
        Specification<CustomerOrder> spec = buildSpecification(
                search, statuses, fromTimestamp, toTimestamp, hasLocation, needsAttention
        );

        // 4. Execution of paginated query
        Page<CustomerOrder> orderPage = orderRepo.findAll(spec, pageable);

        if (orderPage.isEmpty()) {
            return PageResponseDTO.<OrderSummaryDTO>builder()
                    .content(Collections.emptyList())
                    .page(orderPage.getNumber())
                    .size(orderPage.getSize())
                    .totalElements(orderPage.getTotalElements())
                    .totalPages(orderPage.getTotalPages())
                    .last(orderPage.isLast())
                    .build();
        }

        // 5. Batch-fetch item counts for page IDs only (Zero N+1)
        List<Long> pageOrderIds = orderPage.getContent().stream()
                .map(CustomerOrder::getId)
                .toList();

        Map<Long, Integer> itemCountMap = orderRepo.countItemsByOrderIds(pageOrderIds).stream()
                .collect(Collectors.toMap(
                        OrderItemCountProjection::orderId,
                        p -> p.itemCount() != null ? p.itemCount().intValue() : 0
                ));

        // 6. Map to summary DTOs
        List<OrderSummaryDTO> dtos = orderPage.getContent().stream()
                .map(order -> toOrderSummaryDTO(order, itemCountMap.getOrDefault(order.getId(), 0)))
                .toList();

        return PageResponseDTO.<OrderSummaryDTO>builder()
                .content(dtos)
                .page(orderPage.getNumber())
                .size(orderPage.getSize())
                .totalElements(orderPage.getTotalElements())
                .totalPages(orderPage.getTotalPages())
                .last(orderPage.isLast())
                .build();
    }

    /**
     * Retrieves comprehensive read-only details for a single order.
     */
    public OrderDetailDTO getOrderDetails(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Order ID cannot be null");
        }

        CustomerOrder order = orderRepo.findByIdWithDetails(id)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + id));

        return toOrderDetailDTO(order);
    }

    /**
     * Calculates the 4 global operational summary metrics for today in Asia/Kolkata.
     */
    public OrderSummaryCountsDTO getSummaryCounts() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime startOfTomorrow = today.plusDays(1).atStartOfDay();

        long createdToday = orderRepo.countOrdersCreatedBetween(startOfToday, startOfTomorrow);
        long inProgress = orderRepo.countOrdersByStatusIn(IN_PROGRESS_STATUSES);
        long deliveredToday = orderRepo.countOrdersDeliveredBetween(OrderStatus.DELIVERED, startOfToday, startOfTomorrow);
        long needsAttention = orderRepo.countOrdersNeedingAttention(ACTIVE_FULFILLMENT_STATUSES);

        return OrderSummaryCountsDTO.builder()
                .createdToday(createdToday)
                .inProgress(inProgress)
                .deliveredToday(deliveredToday)
                .needsAttention(needsAttention)
                .build();
    }

    // =========================================================================
    // SPECIFICATIONS & MAPPINGS
    // =========================================================================

    private Specification<CustomerOrder> buildSpecification(
            String search,
            List<OrderStatus> statuses,
            LocalDateTime fromTimestamp,
            LocalDateTime toTimestamp,
            Boolean hasLocation,
            Boolean needsAttention
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Search filter
            if (search != null && !search.trim().isBlank()) {
                String trimmed = search.trim();
                List<Predicate> searchPredicates = new ArrayList<>();

                String escaped = trimmed.replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                String likePattern = "%" + escaped.toLowerCase() + "%";

                // Numeric match on PK if digits only
                if (trimmed.matches("^\\d+$")) {
                    try {
                        Long numericId = Long.parseLong(trimmed);
                        searchPredicates.add(cb.equal(root.get("id"), numericId));
                    } catch (NumberFormatException ignored) {}
                }

                // Display ID match
                searchPredicates.add(cb.like(cb.lower(root.get("displayId")), likePattern, '\\'));

                // Customer join for name and phone
                Join<CustomerOrder, Customer> customerJoin = root.join("customer", JoinType.LEFT);
                searchPredicates.add(cb.like(cb.lower(customerJoin.get("name")), likePattern, '\\'));

                // Phone search: normalize to digits. Only apply if at least one digit is present.
                String digitsOnly = trimmed.replaceAll("[^0-9]", "");
                if (!digitsOnly.isEmpty()) {
                    String phonePattern = "%" + digitsOnly + "%";
                    searchPredicates.add(cb.like(customerJoin.get("whatsappNumber"), phonePattern, '\\'));
                }

                predicates.add(cb.or(searchPredicates.toArray(new Predicate[0])));
            }

            // 2. Statuses filter
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }

            // 3. Date range filters
            if (fromTimestamp != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromTimestamp));
            }
            if (toTimestamp != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toTimestamp));
            }

            // 4. Location filter (both coordinates required for true)
            if (hasLocation != null) {
                if (hasLocation) {
                    predicates.add(cb.and(
                            cb.isNotNull(root.get("deliveryLatitude")),
                            cb.isNotNull(root.get("deliveryLongitude"))
                    ));
                } else {
                    predicates.add(cb.or(
                            cb.isNull(root.get("deliveryLatitude")),
                            cb.isNull(root.get("deliveryLongitude"))
                    ));
                }
            }

            // 5. Needs Attention filter
            if (needsAttention != null) {
                Predicate stockDiscrepancy = cb.isFalse(root.get("stockReconciled"));
                Predicate activeMissingLoc = cb.and(
                        root.get("status").in(ACTIVE_FULFILLMENT_STATUSES),
                        cb.or(
                                cb.isNull(root.get("deliveryLatitude")),
                                cb.isNull(root.get("deliveryLongitude"))
                        )
                );
                Predicate orderNeedsAttention = cb.or(stockDiscrepancy, activeMissingLoc);

                if (needsAttention) {
                    predicates.add(orderNeedsAttention);
                } else {
                    predicates.add(cb.not(orderNeedsAttention));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Sort parseSort(String sortParam) {
        String effectiveParam = (sortParam != null && !sortParam.trim().isBlank())
                ? sortParam.trim()
                : "createdAt,desc";

        String[] parts = effectiveParam.split(",");
        String property = parts[0].trim();
        String directionStr = parts.length > 1 ? parts[1].trim().toLowerCase() : "desc";

        if (!SUPPORTED_SORT_PROPERTIES.contains(property)) {
            throw new IllegalArgumentException("Invalid sort property: " + property
                    + ". Supported properties: " + String.join(", ", SUPPORTED_SORT_PROPERTIES));
        }

        Sort.Direction direction;
        if ("asc".equalsIgnoreCase(directionStr)) {
            direction = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(directionStr)) {
            direction = Sort.Direction.DESC;
        } else {
            throw new IllegalArgumentException("Invalid sort direction: " + directionStr + ". Must be 'asc' or 'desc'");
        }

        // Secondary tiebreaker for deterministic ordering (only if primary sort is not id)
        Sort primarySort = Sort.by(direction, property);
        if (!"id".equals(property)) {
            Sort secondarySort = Sort.by(direction, "id");
            return primarySort.and(secondarySort);
        }
        return primarySort;
    }

    private OrderSummaryDTO toOrderSummaryDTO(CustomerOrder order, int itemCount) {
        boolean hasLoc = isUsableLocation(order.getDeliveryLatitude(), order.getDeliveryLongitude());
        List<String> reasons = computeAttentionReasons(order, hasLoc);

        Customer customer = order.getCustomer();
        String customerName = (customer != null && customer.getName() != null) ? customer.getName() : "Customer";
        String customerPhone = (customer != null) ? customer.getWhatsappNumber() : null;

        return OrderSummaryDTO.builder()
                .id(order.getId())
                .displayId(order.getDisplayId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .customerName(customerName)
                .customerPhone(customerPhone)
                .itemCount(itemCount)
                .hasLocation(hasLoc)
                .stockReconciled(order.isStockReconciled())
                .needsAttention(!reasons.isEmpty())
                .attentionReasons(reasons)
                .build();
    }

    private OrderDetailDTO toOrderDetailDTO(CustomerOrder order) {
        boolean hasLoc = isUsableLocation(order.getDeliveryLatitude(), order.getDeliveryLongitude());
        List<String> reasons = computeAttentionReasons(order, hasLoc);

        // Customer Info
        Customer customer = order.getCustomer();
        OrderDetailDTO.CustomerInfo customerInfo = null;
        if (customer != null) {
            customerInfo = OrderDetailDTO.CustomerInfo.builder()
                    .id(customer.getId())
                    .name(customer.getName() != null ? customer.getName() : "Customer")
                    .whatsappNumber(customer.getWhatsappNumber())
                    .addressNotes(customer.getAddressNotes())
                    .build();
        }

        // Delivery Info
        String googleMapsUrl = hasLoc
                ? "https://www.google.com/maps?q=" + order.getDeliveryLatitude() + "," + order.getDeliveryLongitude()
                : null;

        OrderDetailDTO.DeliveryInfo deliveryInfo = OrderDetailDTO.DeliveryInfo.builder()
                .hasLocation(hasLoc)
                .latitude(order.getDeliveryLatitude())
                .longitude(order.getDeliveryLongitude())
                .googleMapsUrl(googleMapsUrl)
                .build();

        // Delivery Run Info
        DeliveryRun run = order.getDeliveryRun();
        OrderDetailDTO.DeliveryRunInfo runInfo = null;
        if (run != null) {
            runInfo = OrderDetailDTO.DeliveryRunInfo.builder()
                    .runId(run.getId())
                    .riderPhoneNumber(run.getRiderPhoneNumber())
                    .dispatchedAt(run.getDispatchedAt())
                    .status(run.getStatus())
                    .build();
        }

        // Line Items
        List<OrderDetailDTO.OrderItemDetail> itemDetails = new ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                String sku = (product != null) ? product.getSku() : "UNKNOWN";
                String name = (product != null) ? product.getName() : "Unknown Product";
                BigDecimal pkgQty = (product != null) ? product.getPackageQuantity() : null;
                String pkgUnit = (product != null && product.getPackageUnit() != null) ? product.getPackageUnit().name() : null;

                BigDecimal lineTotal = BigDecimal.ZERO;
                if (item.getLockedPrice() != null && item.getQuantity() != null) {
                    lineTotal = item.getLockedPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                }

                String deductedUnit = null;
                if (item.getDeductedQuantity() != null && product != null && product.getInventory() != null && product.getInventory().getUnit() != null) {
                    deductedUnit = product.getInventory().getUnit().name();
                }

                itemDetails.add(OrderDetailDTO.OrderItemDetail.builder()
                        .id(item.getId())
                        .productSku(sku)
                        .productName(name)
                        .packageQuantity(pkgQty)
                        .packageUnit(pkgUnit)
                        .quantity(item.getQuantity())
                        .lockedPrice(item.getLockedPrice())
                        .lineTotal(lineTotal)
                        .deductedQuantity(item.getDeductedQuantity())
                        .deductedUnit(deductedUnit)
                        .stockReconciliationRequired(item.isStockReconciliationRequired())
                        .build());
            }
        }

        // Timeline
        List<OrderDetailDTO.TimelineEvent> timeline = buildTimeline(order, run);

        return OrderDetailDTO.builder()
                .id(order.getId())
                .displayId(order.getDisplayId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .readyAt(order.getReadyAt())
                .deliveredAt(order.getDeliveredAt())
                .cancellationReason(order.getCancellationReason())
                .stockReconciled(order.isStockReconciled())
                .needsAttention(!reasons.isEmpty())
                .attentionReasons(reasons)
                .customer(customerInfo)
                .delivery(deliveryInfo)
                .deliveryRun(runInfo)
                .items(itemDetails)
                .timeline(timeline)
                .build();
    }

    private List<OrderDetailDTO.TimelineEvent> buildTimeline(CustomerOrder order, DeliveryRun run) {
        List<OrderDetailDTO.TimelineEvent> events = new ArrayList<>();
        boolean isCancelled = (order.getStatus() == OrderStatus.CANCELLED);

        // 1. Order Placed
        events.add(OrderDetailDTO.TimelineEvent.builder()
                .milestone("ORDER_PLACED")
                .title("Order Placed")
                .timestamp(order.getCreatedAt())
                .recorded(order.getCreatedAt() != null)
                .status("COMPLETED")
                .build());

        // 2. Ready for Dispatch
        boolean readyRecorded = (order.getReadyAt() != null);
        String readyStatus;
        if (readyRecorded) {
            readyStatus = "COMPLETED";
        } else if (isCancelled) {
            readyStatus = "SKIPPED";
        } else {
            readyStatus = "PENDING";
        }
        events.add(OrderDetailDTO.TimelineEvent.builder()
                .milestone("READY_FOR_DISPATCH")
                .title("Bag Packed & Sealed")
                .timestamp(order.getReadyAt())
                .recorded(readyRecorded)
                .status(readyStatus)
                .build());

        // 3. Dispatched
        LocalDateTime dispatchedAt = (run != null) ? run.getDispatchedAt() : null;
        boolean dispatchRecorded = (dispatchedAt != null);
        String dispatchStatus;
        if (dispatchRecorded) {
            dispatchStatus = "COMPLETED";
        } else if (isCancelled) {
            dispatchStatus = "SKIPPED";
        } else {
            dispatchStatus = "PENDING";
        }
        events.add(OrderDetailDTO.TimelineEvent.builder()
                .milestone("DISPATCHED")
                .title("Out for Delivery")
                .timestamp(dispatchedAt)
                .recorded(dispatchRecorded)
                .status(dispatchStatus)
                .build());

        // 4. Delivered
        boolean deliveredRecorded = (order.getDeliveredAt() != null);
        String deliveredStatus;
        if (deliveredRecorded) {
            deliveredStatus = "COMPLETED";
        } else if (isCancelled) {
            deliveredStatus = "SKIPPED";
        } else {
            deliveredStatus = "PENDING";
        }
        events.add(OrderDetailDTO.TimelineEvent.builder()
                .milestone("DELIVERED")
                .title("Delivered")
                .timestamp(order.getDeliveredAt())
                .recorded(deliveredRecorded)
                .status(deliveredStatus)
                .build());

        // 5. Cancelled (only appended if status is CANCELLED)
        if (isCancelled) {
            events.add(OrderDetailDTO.TimelineEvent.builder()
                    .milestone("CANCELLED")
                    .title("Order Cancelled")
                    .timestamp(null) // Unrecorded timestamp in PostgreSQL schema
                    .recorded(false)
                    .status("COMPLETED")
                    .detail(order.getCancellationReason() != null ? order.getCancellationReason() : "Cancelled")
                    .build());
        }

        return events;
    }

    private boolean isUsableLocation(Double lat, Double lng) {
        return lat != null && lng != null;
    }

    private List<String> computeAttentionReasons(CustomerOrder order, boolean hasUsableLocation) {
        List<String> reasons = new ArrayList<>();
        if (!order.isStockReconciled()) {
            reasons.add("STOCK_RECONCILIATION_REQUIRED");
        }
        if (!hasUsableLocation && ACTIVE_FULFILLMENT_STATUSES.contains(order.getStatus())) {
            reasons.add("LOCATION_MISSING");
        }
        return reasons;
    }
}
