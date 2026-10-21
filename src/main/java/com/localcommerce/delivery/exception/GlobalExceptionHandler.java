package com.localcommerce.delivery.exception;

import com.localcommerce.delivery.utils.UserContext;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    public static final String FALLBACK_APOLOGY_MESSAGE =
            "⚠️ *Oops! Something went wrong on our end.*\n\n"
            + "Our technical team has been notified. Please type 'Hi' to restart your order, "
            + "or contact support if the issue persists.";

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParams(HttpServletRequest request, MissingServletRequestParameterException ex) {
        try {
            log.warn("Missing request parameter '{}' on [{} {}]",
                    ex.getParameterName(),
                    request != null ? request.getMethod() : "UNKNOWN",
                    request != null ? request.getRequestURI() : "UNKNOWN");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing required parameter: " + ex.getParameterName()));
        } finally {
            UserContext.clear();
        }
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(HttpServletRequest request, NoResourceFoundException ex) {
        try {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Resource not found"));
        } finally {
            UserContext.clear();
        }
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<?> handleEntityNotFound(HttpServletRequest request, EntityNotFoundException ex) {
        try {
            String uri = request != null ? request.getRequestURI() : "";
            if (uri != null && uri.startsWith("/api/webhooks")) {
                log.warn("Webhook entity not found on [{}]: {}", uri, ex.getMessage());
                return ResponseEntity.ok().build();
            }

            log.warn("Entity not found on [{} {}]: {}",
                    request != null ? request.getMethod() : "UNKNOWN",
                    uri,
                    ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Resource not found"));
        } finally {
            UserContext.clear();
        }
    }

    @ExceptionHandler(OrderValidationException.class)
    public ResponseEntity<?> handleOrderValidation(HttpServletRequest request, OrderValidationException ex) {
        try {
            String uri = request != null ? request.getRequestURI() : "";
            if (uri != null && uri.startsWith("/api/webhooks")) {
                log.warn("Webhook order validation failed on [{}]: {}", uri, ex.getMessage());
                return ResponseEntity.ok().build();
            }

            log.warn("Order validation failed on [{} {}]: {}",
                    request != null ? request.getMethod() : "UNKNOWN",
                    uri,
                    ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", ex.getMessage()));
        } finally {
            UserContext.clear();
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleAllExceptions(HttpServletRequest request, Exception ex) {
        try {
            String uri = request != null ? request.getRequestURI() : "";
            boolean isWebhook = uri != null && uri.startsWith("/api/webhooks");

            if (isWebhook) {
                log.error("CRITICAL WEBHOOK FAILURE on [{}]: {}", uri, ex.getMessage(), ex);
                // FORCE Meta to drop the webhook by returning 200 OK.
                // Do NOT return 500, or Meta will spam this endpoint for 24 hours.
                return ResponseEntity.ok().build();
            }

            // Non-webhook (e.g. Admin API) failure: return 500 without leaking stack traces or internal details
            log.error("Unhandled exception for request [{} {}]: {}",
                    request != null ? request.getMethod() : "UNKNOWN",
                    uri,
                    ex.getMessage(),
                    ex);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An internal error occurred. Please try again."));
        } finally {
            UserContext.clear();
        }
    }
}
