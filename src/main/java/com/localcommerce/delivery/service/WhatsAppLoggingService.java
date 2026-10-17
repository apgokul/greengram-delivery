package com.localcommerce.delivery.service;

import com.localcommerce.delivery.dto.WhatsAppWebhookPayload;
import com.localcommerce.delivery.enums.MessageDirection;
import com.localcommerce.delivery.model.WhatsAppMessageLog;
import com.localcommerce.delivery.repository.WhatsAppMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppLoggingService {

    private final WhatsAppMessageLogRepository repository;

    /**
     * Persists an inbound customer message safely in an isolated transaction.
     * Enforces deduplication using externalMessageId.
     * Uses REQUIRES_NEW so log persistence runs in an independent transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logInbound(String phoneNumber, String messageBody, LocalDateTime timestamp, String externalMessageId) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("Cannot log inbound WhatsApp message: phoneNumber is blank.");
            return;
        }

        // Idempotency check: Skip if external Meta message ID was already recorded
        if (externalMessageId != null && !externalMessageId.isBlank()) {
            if (repository.existsByExternalMessageId(externalMessageId)) {
                log.info("Duplicate inbound WhatsApp message skipped: externalMessageId={}", externalMessageId);
                return;
            }
        }

        WhatsAppMessageLog logEntry = WhatsAppMessageLog.builder()
                .externalMessageId(externalMessageId)
                .phoneNumber(phoneNumber)
                .direction(MessageDirection.INBOUND)
                .messageBody(messageBody != null ? messageBody : "")
                .timestamp(timestamp != null ? timestamp : LocalDateTime.now())
                .build();

        repository.save(logEntry);
    }

    /**
     * Persists an outbound bot message in an isolated transaction.
     * Uses REQUIRES_NEW so any logging database failure cannot mark an enclosing business
     * transaction (such as OrderService.completeDelivery) rollback-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOutbound(String phoneNumber, String messageBody, LocalDateTime timestamp) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("Cannot log outbound WhatsApp message: phoneNumber is blank.");
            return;
        }

        WhatsAppMessageLog logEntry = WhatsAppMessageLog.builder()
                .phoneNumber(phoneNumber)
                .direction(MessageDirection.OUTBOUND)
                .messageBody(messageBody != null ? messageBody : "")
                .timestamp(timestamp != null ? timestamp : LocalDateTime.now())
                .build();

        repository.save(logEntry);
    }

    /**
     * Converts an incoming WhatsApp message payload into a human-readable, privacy-conscious representation.
     */
    public String formatInboundMessage(WhatsAppWebhookPayload.Message message) {
        if (message == null) {
            return "[Empty message]";
        }

        String type = message.getType();
        if (type == null) {
            return "[Unknown message]";
        }

        switch (type.toLowerCase()) {
            case "text":
                return (message.getText() != null && message.getText().getBody() != null)
                        ? message.getText().getBody()
                        : "[Empty text message]";

            case "order":
                if (message.getOrder() != null && message.getOrder().getProductItems() != null) {
                    String itemsSummary = message.getOrder().getProductItems().stream()
                            .map(item -> item.getQuantity() + "x " + item.getProductRetailerId())
                            .collect(Collectors.joining(", "));
                    return "[Order submitted: " + itemsSummary + "]";
                }
                return "[Order submitted]";

            case "location":
                // Privacy safeguard: do NOT store raw GPS coordinates in message description
                if (message.getLocation() != null && message.getLocation().getAddress() != null && !message.getLocation().getAddress().isBlank()) {
                    return "[Customer shared location pin: " + message.getLocation().getAddress() + "]";
                }
                return "[Customer shared location pin]";

            case "interactive":
                if (message.getInteractive() != null) {
                    if (message.getInteractive().getButton_reply() != null) {
                        String title = message.getInteractive().getButton_reply().getTitle();
                        String id = message.getInteractive().getButton_reply().getId();
                        if (title != null && !title.isBlank()) {
                            return "[Button: " + title + "]";
                        } else if (id != null && !id.isBlank()) {
                            return "[Button: " + id + "]";
                        } else {
                            return "[Button clicked]";
                        }
                    }
                    if (message.getInteractive().getList_reply() != null) {
                        String title = message.getInteractive().getList_reply().getTitle();
                        String id = message.getInteractive().getList_reply().getId();
                        if (title != null && !title.isBlank()) {
                            return "[Selection: " + title + "]";
                        } else if (id != null && !id.isBlank()) {
                            return "[Selection: " + id + "]";
                        } else {
                            return "[List option selected]";
                        }
                    }
                }
                return "[Interactive response]";

            default:
                return "[Unsupported message: " + type + "]";
        }
    }
}
