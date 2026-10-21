package com.localcommerce.delivery.client;

import com.localcommerce.delivery.service.SmsFallbackService;
import com.localcommerce.delivery.service.WhatsAppLoggingService;
import com.localcommerce.delivery.utils.WhatsAppConstants;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@Slf4j
@Getter
@Setter
public class WhatsAppHttpSender {

    @Value("${whatsapp.api.url}")
    private String apiUrl;

    @Value("${whatsapp.api.token}")
    private String accessToken;

    @Value("${warehouse.phone.number}")
    private String warehousePhoneNumber;

    private final RestTemplate restTemplate;
    private final SmsFallbackService smsFallbackService;
    private final WhatsAppLoggingService loggingService;
    private Clock clock = Clock.system(WhatsAppConstants.ASIA_KOLKATA_ZONE);

    public WhatsAppHttpSender(RestTemplate restTemplate,
                              SmsFallbackService smsFallbackService,
                              WhatsAppLoggingService loggingService) {
        this.restTemplate = restTemplate;
        this.smsFallbackService = smsFallbackService;
        this.loggingService = loggingService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            noRetryFor = {HttpClientErrorException.class},
            maxAttemptsExpression = "${whatsapp.retry.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${whatsapp.retry.delay:2000}")
    )
    public void sendPostRequest(String toPhoneNumber, String loggedDescription, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken != null ? accessToken : "");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(apiUrl, request, String.class);
        } catch (HttpClientErrorException e) {
            log.error("Permanent client error sending WhatsApp message to {}: status={}, body={}",
                    toPhoneNumber, e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }

        log.info("Successfully sent payload to Meta API.");

        if (response != null && (response.getStatusCode().is2xxSuccessful() || response.getStatusCode().value() == 200)) {
            try {
                loggingService.logOutbound(toPhoneNumber, loggedDescription, LocalDateTime.now(clock));
            } catch (Exception e) {
                log.error("Failed to persist outbound WhatsApp message log for phone {}: {}", toPhoneNumber, e.getMessage());
            }
        }
    }

    @Recover
    public void handleMetaApiFailure(RestClientException e, String toPhoneNumber, String loggedDescription, Map<String, Object> payload) {
        if (e instanceof HttpClientErrorException) {
            // Permanent client errors must propagate to caller and not be reported as unreachable API
            throw e;
        }

        log.error("CRITICAL: Meta API completely unreachable after retries. Error: {}", e.getMessage());

        String intendedRecipient = toPhoneNumber != null
                ? toPhoneNumber
                : (payload != null ? (String) payload.getOrDefault(WhatsAppConstants.TO, "Unknown") : "Unknown");
        log.error("Meta API completely unreachable. Failed to deliver message to: {}", intendedRecipient);

        if (intendedRecipient != null && intendedRecipient.equals(warehousePhoneNumber)) {
            String alertMessage = "🚨 URGENT: WhatsApp API is DOWN. " +
                    "A new order was just placed but the warehouse bot is offline. " +
                    "Please check the backend database immediately to pack the order!";

            smsFallbackService.sendEmergencyAlert(alertMessage);
        } else {
            log.warn("[CUSTOMER_COMM_DROP] Failed to send WhatsApp update to customer {}. " +
                    "Payload dump: {}", intendedRecipient, payload);
        }
    }
}
