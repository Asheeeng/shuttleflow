package com.gzu.shuttle.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 单实例限流计数器；公网入口同时由 Nginx 按来源 IP 限流。 */
@Component
public class FixedWindowRateLimiter {
    private static final int MAX_KEYS = 10000;
    private final Map<String, Window> windows = new ConcurrentHashMap<String, Window>();
    private final AtomicLong calls = new AtomicLong();

    public boolean allow(String key, int limit, long windowMillis) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        boolean allowed = window.acquire(now, limit, windowMillis);
        if ((calls.incrementAndGet() & 255) == 0 || windows.size() > MAX_KEYS) {
            for (Map.Entry<String, Window> entry : windows.entrySet()) {
                if (now - entry.getValue().startedAt > windowMillis * 2) windows.remove(entry.getKey(), entry.getValue());
            }
        }
        return allowed;
    }

    private static final class Window {
        private long startedAt;
        private int count;
        private Window(long now) { this.startedAt = now; }
        private synchronized boolean acquire(long now, int limit, long windowMillis) {
            if (now - startedAt >= windowMillis || now < startedAt) {
                startedAt = now;
                count = 0;
            }
            if (count >= limit) return false;
            count++;
            return true;
        }
    }
}
