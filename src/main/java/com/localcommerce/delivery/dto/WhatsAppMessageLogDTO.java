package com.localcommerce.delivery.dto;

import com.localcommerce.delivery.enums.MessageDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppMessageLogDTO {
    private Long id;
    private String externalMessageId;
    private String phoneNumber;
    private MessageDirection direction;
    private String messageBody;
    private LocalDateTime timestamp;
}
