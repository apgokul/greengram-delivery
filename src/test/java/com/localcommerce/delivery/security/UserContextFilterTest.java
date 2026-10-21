package com.localcommerce.delivery.security;

import com.localcommerce.delivery.utils.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserContextFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private UserContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new UserContextFilter();
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testFilterCleansUpContextOnSuccessfulRequest() throws ServletException, IOException {
        doAnswer(invocation -> {
            UserContext.setSenderId("active_customer_123");
            assertEquals("active_customer_123", UserContext.getPhoneNumber());
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        // After filter completes, UserContext must be cleared
        assertNull(UserContext.getPhoneNumber());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testFilterCleansUpContextEvenWhenExceptionThrown() throws ServletException, IOException {
        doAnswer(invocation -> {
            UserContext.setSenderId("leaked_customer_456");
            throw new RuntimeException("Simulated controller failure");
        }).when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class, () -> filter.doFilterInternal(request, response, filterChain));

        // Guaranteed cleanup in finally block
        assertNull(UserContext.getPhoneNumber());
    }
}
