package com.localcommerce.delivery.exception;

import com.localcommerce.delivery.utils.UserContext;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    @Test
    void testWebhookException_Returns200AndCleansUpContext() {
        when(request.getRequestURI()).thenReturn("/api/webhooks/whatsapp");
        UserContext.setSenderId("919876543210");

        ResponseEntity<?> response = exceptionHandler.handleAllExceptions(request, new RuntimeException("Webhook parse error"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(UserContext.getPhoneNumber(), "UserContext must be cleared in finally block");
    }

    @Test
    void testAdminException_Returns500WithSafeMessage() {
        when(request.getRequestURI()).thenReturn("/api/admin/orders/1042/cancel");
        when(request.getMethod()).thenReturn("POST");
        UserContext.setSenderId("919999999999"); // If context was somehow present

        ResponseEntity<?> response = exceptionHandler.handleAllExceptions(request, new RuntimeException("Database failure"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("An internal error occurred. Please try again.", body.get("error"));
        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testAdminEntityNotFoundException_Returns404() {
        when(request.getRequestURI()).thenReturn("/api/admin/orders/9999");
        when(request.getMethod()).thenReturn("GET");

        ResponseEntity<?> response = exceptionHandler.handleEntityNotFound(request, new EntityNotFoundException("Order not found: 9999"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Resource not found", body.get("error"));
        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testWebhookEntityNotFoundException_Returns200() {
        when(request.getRequestURI()).thenReturn("/api/webhooks/whatsapp");

        ResponseEntity<?> response = exceptionHandler.handleEntityNotFound(request, new EntityNotFoundException("Order not found"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testAdminOrderValidationException_Returns400() {
        when(request.getRequestURI()).thenReturn("/api/admin/orders/5/validate");
        when(request.getMethod()).thenReturn("POST");

        ResponseEntity<?> response = exceptionHandler.handleOrderValidation(request, new OrderValidationException("Cart is empty"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Cart is empty", body.get("error"));
        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testMissingServletRequestParameter_Returns400() {
        when(request.getRequestURI()).thenReturn("/api/admin/products/SKU1/price");
        when(request.getMethod()).thenReturn("PATCH");

        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("newPrice", "BigDecimal");
        ResponseEntity<?> response = exceptionHandler.handleMissingParams(request, ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Missing required parameter: newPrice", body.get("error"));
        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testNoResourceFoundException_Returns404() {
        NoResourceFoundException ex = mock(NoResourceFoundException.class);
        ResponseEntity<?> response = exceptionHandler.handleNotFound(request, ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("Resource not found", body.get("error"));
        assertNull(UserContext.getPhoneNumber());
    }
}
