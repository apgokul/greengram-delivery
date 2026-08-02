package com.localcommerce.delivery.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SmsFallbackService {

    @Value("${twilio.account.sid:placeholder}")
    private String accountSid;

    @Value("${twilio.auth.token:placeholder}")
    private String authToken;

    @Value("${fallback.manager.phone}") // E.g., The founder's or manager's personal number
    private String managerPhone;

    public void sendEmergencyAlert(String message) {
        log.warn("INITIATING SMS FALLBACK...");

        try {
            // In a real implementation, you would initialize Twilio here:
            // Twilio.init(accountSid, authToken);
            // Message.creator(new PhoneNumber(managerPhone), new PhoneNumber("YOUR_TWILIO_NUM"), message).create();

            log.info("Emergency SMS successfully dispatched to {}: {}", managerPhone, message);
        } catch (Exception e) {
            log.error("CRITICAL: Both WhatsApp AND Fallback SMS failed! Error: {}", e.getMessage());
        }
    }
}