package com.localcommerce.delivery.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UserContextTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    @Test
    void testSetAndGetPhoneNumber() {
        assertNull(UserContext.getPhoneNumber());

        UserContext.setSenderId("919876543210");
        assertEquals("919876543210", UserContext.getPhoneNumber());

        UserContext.clear();
        assertNull(UserContext.getPhoneNumber());
    }

    @Test
    void testThreadIsolation() throws InterruptedException {
        UserContext.setSenderId("thread-main-user");

        AtomicReference<String> threadUser = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread backgroundThread = new Thread(() -> {
            try {
                // Background thread should initially see null
                assertNull(UserContext.getPhoneNumber());

                UserContext.setSenderId("thread-bg-user");
                threadUser.set(UserContext.getPhoneNumber());
            } finally {
                UserContext.clear();
                latch.countDown();
            }
        });

        backgroundThread.start();
        latch.await();

        assertEquals("thread-bg-user", threadUser.get());
        // Main thread's context must be unaffected by background thread
        assertEquals("thread-main-user", UserContext.getPhoneNumber());

        UserContext.clear();
        assertNull(UserContext.getPhoneNumber());
    }
}
