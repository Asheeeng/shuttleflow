package com.gzu.shuttle.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class ApiRateLimitInterceptorTest {
    @Test
    void independentQuotasSharedByRouteGroupAndResetAfterFifteenMinutes() throws Exception {
        AtomicLong time = new AtomicLong(1000);
        ApiRateLimitInterceptor interceptor = new ApiRateLimitInterceptor(new FixedWindowRateLimiter(time::get), new ObjectMapper());
        ReflectionTestUtils.setField(interceptor, "loginLimit", 30);
        ReflectionTestUtils.setField(interceptor, "registerLimit", 20);
        ReflectionTestUtils.setField(interceptor, "authWindowSeconds", 900);
        for (int i = 0; i < 20; i++) assertEquals(200, post(interceptor, i % 2 == 0 ? "/api/passenger/register/" : "/api/passenger/register", "192.0.2.1").getStatus());
        assertEquals(429, post(interceptor, "/api/passenger/register", "192.0.2.1").getStatus());
        for (int i = 0; i < 30; i++) assertEquals(200, post(interceptor, i % 2 == 0 ? "/api/driver/login" : "/api/passenger/login", "192.0.2.1").getStatus());
        MockHttpServletResponse limited = post(interceptor, "/api/admin/login/", "192.0.2.1");
        assertEquals(429, limited.getStatus());
        assertEquals("900", limited.getHeader("Retry-After"));
        assertEquals(200, post(interceptor, "/api/passenger/register", "192.0.2.2").getStatus());
        time.addAndGet(900000);
        assertEquals(200, post(interceptor, "/api/passenger/register", "192.0.2.1").getStatus());
        assertEquals(200, post(interceptor, "/api/admin/login", "192.0.2.1").getStatus());
    }

    @Test
    void cleaningShortWindowsDoesNotClearLongerAuthWindow() {
        AtomicLong time = new AtomicLong(1000);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(time::get);
        assertTrue(limiter.allow("login", 1, 900000));
        assertTrue(limiter.allow("write", 1, 60000));
        time.addAndGet(61000);
        for (int i = 0; i < 256; i++) assertTrue(limiter.allow("new:" + i, 1, 60000));
        assertFalse(limiter.allow("login", 1, 900000));
        assertTrue(limiter.allow("write", 1, 60000));
    }

    private MockHttpServletResponse post(ApiRateLimitInterceptor interceptor, String path, String address) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRemoteAddr(address);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(req, res, new Object());
        assertEquals(res.getStatus() == 200, allowed);
        return res;
    }
}
