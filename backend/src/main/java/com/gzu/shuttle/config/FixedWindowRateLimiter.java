package com.gzu.shuttle.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** 单实例限流；每个窗口独立保存到期时间。 */
@Component
public class FixedWindowRateLimiter {
    private static final int MAX_KEYS = 10000;
    private final Map<String, Window> windows = new HashMap<>();
    private final LongSupplier clock;
    private int calls;

    public FixedWindowRateLimiter() { this(System::currentTimeMillis); }
    FixedWindowRateLimiter(LongSupplier clock) { this.clock = clock; }

    public synchronized boolean allow(String key, int limit, long windowMillis) {
        long now = clock.getAsLong();
        if ((++calls & 255) == 0) windows.values().removeIf(window -> now >= window.until);
        Window window = windows.get(key);
        if (window == null || now >= window.until) {
            if (window == null && windows.size() >= MAX_KEYS) return false;
            window = new Window(now + windowMillis);
            windows.put(key, window);
        }
        if (window.count >= limit) return false;
        window.count++;
        return true;
    }

    private static final class Window {
        private final long until;
        private int count;
        private Window(long until) { this.until = until; }
    }
}
