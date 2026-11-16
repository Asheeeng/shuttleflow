package com.gzu.shuttle.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {
    @Test
    void limitsRequestsWithinWindowAndAllowsNextWindow() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();
        assertTrue(limiter.allow("login:127.0.0.1", 2, 50));
        assertTrue(limiter.allow("login:127.0.0.1", 2, 50));
        assertFalse(limiter.allow("login:127.0.0.1", 2, 50));
        Thread.sleep(70);
        assertTrue(limiter.allow("login:127.0.0.1", 2, 50));
    }
}
