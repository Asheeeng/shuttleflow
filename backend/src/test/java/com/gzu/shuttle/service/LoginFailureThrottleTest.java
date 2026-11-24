package com.gzu.shuttle.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginFailureThrottleTest {
    @Test
    void caseVariantsShareFailuresButRolesAndAccountsDoNot() {
        TestClock clock = new TestClock();
        LoginFailureThrottle throttle = new LoginFailureThrottle(clock);
        for (int i = 0; i < 6; i++) assertNull(throttle.verify("admin", i % 2 == 0 ? " Alice " : "ALICE", () -> null));
        LoginFailureThrottle.Limited limited = assertThrows(LoginFailureThrottle.Limited.class,
                () -> throttle.verify("admin", "alice", () -> fail("should not validate the password")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        limited.respond(response);
        assertEquals(429, response.getStatus());
        assertEquals("900", response.getHeader("Retry-After"));
        assertEquals("ok", throttle.verify("driver", "alice", () -> "ok"));
        assertEquals("ok", throttle.verify("admin", "bob", () -> "ok"));
        clock.seconds = 900;
        assertEquals("ok", throttle.verify("admin", "alice", () -> "ok"));
    }

    @Test
    void successfulLoginClearsPreviousFailures() {
        LoginFailureThrottle throttle = new LoginFailureThrottle(new TestClock());
        for (int i = 0; i < 5; i++) assertNull(throttle.verify("passenger", "123", () -> null));
        assertEquals("ok", throttle.verify("passenger", "123", () -> "ok"));
        for (int i = 0; i < 5; i++) assertNull(throttle.verify("passenger", "123", () -> null));
        assertEquals("ok", throttle.verify("passenger", "123", () -> "ok"));
    }

    private static final class TestClock extends Clock {
        long seconds;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds); }
    }
}
