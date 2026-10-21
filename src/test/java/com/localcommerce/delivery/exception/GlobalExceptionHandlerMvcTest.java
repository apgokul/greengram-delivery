package com.localcommerce.delivery.exception;

import com.localcommerce.delivery.utils.UserContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that Spring MVC's exception-resolution pipeline correctly dispatches exceptions
 * to GlobalExceptionHandler and serializes the intended HTTP status and JSON response body.
 */
class GlobalExceptionHandlerMvcTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {
        @GetMapping("/api/admin/test/error")
        public void throwUnhandledError() {
            throw new RuntimeException("Simulated unexpected database failure");
        }

        @GetMapping("/api/admin/test/validation")
        public void throwValidationError() {
            throw new OrderValidationException("Cart is empty");
        }

        @GetMapping("/api/admin/test/not-found")
        public void throwEntityNotFound() {
            throw new EntityNotFoundException("Order 1042 not found");
        }

        @GetMapping("/api/admin/test/param")
        public String requireParam(@RequestParam("requiredParam") String param) {
            return param;
        }

        @GetMapping("/api/webhooks/test/webhook-error")
        public void throwWebhookError() {
            throw new RuntimeException("Simulated webhook payload failure");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testAdminUnexpectedException_ResolvesToHttp500WithSafeBody() throws Exception {
        mockMvc.perform(get("/api/admin/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("An internal error occurred. Please try again."));

        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testAdminOrderValidationException_ResolvesToHttp400() throws Exception {
        mockMvc.perform(get("/api/admin/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Cart is empty"));

        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testAdminEntityNotFoundException_ResolvesToHttp404() throws Exception {
        mockMvc.perform(get("/api/admin/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Resource not found"));

        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testAdminMissingRequestParam_ResolvesToHttp400() throws Exception {
        mockMvc.perform(get("/api/admin/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Missing required parameter: requiredParam"));

        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testWebhookException_ResolvesToHttp200() throws Exception {
        mockMvc.perform(get("/api/webhooks/test/webhook-error"))
                .andExpect(status().isOk());

        assertNull(UserContext.getPhoneNumber());
    }
}
