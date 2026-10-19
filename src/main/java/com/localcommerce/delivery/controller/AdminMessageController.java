package com.localcommerce.delivery.controller;

import com.localcommerce.delivery.dto.ChatSummaryDTO;
import com.localcommerce.delivery.dto.WhatsAppMessageLogDTO;
import com.localcommerce.delivery.model.WhatsAppMessageLog;
import com.localcommerce.delivery.repository.WhatsAppMessageLogRepository;
import com.localcommerce.delivery.enums.OrderStatus;
import com.localcommerce.delivery.repository.CustomerOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
@Slf4j
public class AdminMessageController {

    private final WhatsAppMessageLogRepository messageLogRepository;
    private final CustomerOrderRepository orderRepo;

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(
            OrderStatus.CREATED, OrderStatus.PENDING_WAREHOUSE,
            OrderStatus.PACKING, OrderStatus.READY, OrderStatus.DISPATCHED
    );

    /**
     * GET /api/admin/messages/chats
     * Returns active chat sessions with the most recent message snippet, ordered by latest activity first.
     */
    @GetMapping("/chats")
    public ResponseEntity<List<ChatSummaryDTO>> getActiveChats(
            @RequestHeader(value = "X-Admin-User", defaultValue = "Unknown_Admin") String adminUser) {
        log.debug("Admin {} fetching active WhatsApp chat list", adminUser);

        List<WhatsAppMessageLog> latestLogs = messageLogRepository.findLatestMessagePerChat();

        List<ChatSummaryDTO> summaries = latestLogs.stream()
                .map(msgLog -> {
                    boolean awaitingLoc = !orderRepo.findByCustomerWhatsappNumberAndStatusInAndDeliveryLatitudeIsNullOrderByCreatedAtDesc(
                            msgLog.getPhoneNumber(), ACTIVE_STATUSES
                    ).isEmpty();

                    return ChatSummaryDTO.builder()
                            .phoneNumber(msgLog.getPhoneNumber())
                            .lastMessage(msgLog.getMessageBody())
                            .lastMessageTimestamp(msgLog.getTimestamp())
                            .lastMessageDirection(msgLog.getDirection())
                            .awaitingLocation(awaitingLoc)
                            .build();
                })
                .toList();

        return ResponseEntity.ok(summaries);
    }

    /**
     * Normalizes a customer identifier safely.
     * Preserves alphanumeric Meta BSUIDs (e.g. bsuid.user_1042_abc) intact.
     * For numeric phone numbers, strips optional leading '+' and formatting spaces/hyphens.
     * Returns null if the identifier is malformed, blank, or contains invalid characters,
     * ensuring invalid input is not transformed into a different valid identifier.
     */
    public static String normalizeCustomerIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String trimmed = identifier.trim();
        if (trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1).trim();
        }
        // Case 1: Standard phone number with optional spaces and dashes
        if (trimmed.matches("^[0-9\\s\\-]+$")) {
            String digitsOnly = trimmed.replaceAll("[\\s\\-]", "");
            return digitsOnly.isEmpty() ? null : digitsOnly;
        }
        // Case 2: Alphanumeric Meta BSUID or customer identifier (letters, digits, dots, underscores, hyphens)
        if (trimmed.matches("^[a-zA-Z0-9._\\-]+$")) {
            return trimmed;
        }
        // Malformed input: do not transform into another valid identifier
        return null;
    }

    /**
     * GET /api/admin/messages/{phoneNumber}
     * Returns full chronological message history for the specified customer phone number or BSUID.
     */
    @GetMapping("/{phoneNumber}")
    public ResponseEntity<List<WhatsAppMessageLogDTO>> getChatHistory(
            @RequestHeader(value = "X-Admin-User", defaultValue = "Unknown_Admin") String adminUser,
            @PathVariable String phoneNumber) {
        String sanitizedIdentifier = normalizeCustomerIdentifier(phoneNumber);
        if (sanitizedIdentifier == null) {
            log.warn("Admin {} requested chat history with invalid or empty identifier: {}", adminUser, phoneNumber);
            return ResponseEntity.ok(List.of());
        }
        log.debug("Admin {} fetching message history for customer: {}", adminUser, sanitizedIdentifier);

        List<WhatsAppMessageLog> history = messageLogRepository.findByPhoneNumberChronological(sanitizedIdentifier);

        List<WhatsAppMessageLogDTO> dtos = history.stream()
                .map(log -> WhatsAppMessageLogDTO.builder()
                        .id(log.getId())
                        .externalMessageId(log.getExternalMessageId())
                        .phoneNumber(log.getPhoneNumber())
                        .direction(log.getDirection())
                        .messageBody(log.getMessageBody())
                        .timestamp(log.getTimestamp())
                        .build())
                .toList();

        return ResponseEntity.ok(dtos);
    }
}
