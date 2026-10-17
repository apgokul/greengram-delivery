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
public class ChatSummaryDTO {
    private String phoneNumber;
    private String lastMessage;
    private LocalDateTime lastMessageTimestamp;
    private MessageDirection lastMessageDirection;
}
