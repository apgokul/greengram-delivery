package com.localcommerce.delivery.exception;

import com.localcommerce.delivery.client.WhatsAppOutboundClient;
import com.localcommerce.delivery.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final WhatsAppOutboundClient outboundClient;
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> handleMissingParams(MissingServletRequestParameterException ex) {
        // Log this at a lower level, it's usually just a bad client request
        System.out.println("Missing parameter: " + ex.getParameterName());
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    // 2. Handle 404s silently so they don't trigger critical alerts
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleAllExceptions(Exception ex) {
        // 1. Log the absolute truth for your debugging
        log.error("CRITICAL WEBHOOK FAILURE: {}", ex.getMessage(), ex);

        // 2. Check if we know who was talking to the bot
        String activePhoneNumber = UserContext.getPhoneNumber();

        if (activePhoneNumber != null) {
            log.info("Dispatching fallback apology message to {}", activePhoneNumber);

            String fallbackMessage = "⚠️ *Oops! Something went wrong on our end.*\n\n" +
                    "Our technical team has been notified. Please type 'Hi' to restart your order, " +
                    "or contact support if the issue persists.";
            try {
                outboundClient.sendTextUpdate(activePhoneNumber, fallbackMessage);
            } catch (Exception outboundEx) {
                log.error("Failed to send fallback message! Error: {}", outboundEx.getMessage());
            }
        }

        // 3. FORCE Meta to drop the webhook by returning 200 OK.
        // Do NOT return 500, or Meta will spam this endpoint for 24 hours.
        return ResponseEntity.ok().build();
    }
}