package com.gzu.shuttle.service;

import java.text.Normalizer;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletResponse;
import com.gzu.shuttle.common.Result;
import org.springframework.stereotype.Component;

/** 限制单个账号的失败尝试（不同出口 IP 共享），成功后清零。 */
@Component
public class LoginFailureThrottle {
    private static final int LIMIT = 6;
    private static final long WINDOW_SECONDS = 900;
    private static final int MAX_PER_BUCKET = 128;
    private final Clock clock;
    private final Bucket[] buckets = new Bucket[128];

    public LoginFailureThrottle() { this(Clock.systemUTC()); }
    LoginFailureThrottle(Clock clock) {
        this.clock = clock;
        for (int i = 0; i < buckets.length; i++) buckets[i] = new Bucket();
    }

    public <T> T verify(String role, String identity, Supplier<T> credentials) {
        String key = role + ":" + Normalizer.normalize(identity == null ? "" : identity.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        Bucket bucket = buckets[key.hashCode() & (buckets.length - 1)];
        synchronized (bucket) {
            long now = clock.instant().getEpochSecond();
            Window window = bucket.accounts.get(key);
            if (window != null && window.until <= now) {
                bucket.accounts.remove(key);
                window = null;
            }
            if (window != null && window.failures >= LIMIT) throw new Limited(window.until - now);
            if (window == null && bucket.accounts.size() >= MAX_PER_BUCKET) {
                bucket.accounts.values().removeIf(entry -> entry.until <= now);
                if (bucket.accounts.size() >= MAX_PER_BUCKET) throw new Limited(60);
            }
            T result = credentials.get();
            if (result != null) bucket.accounts.remove(key);
            else if (window == null) bucket.accounts.put(key, new Window(now + WINDOW_SECONDS));
            else window.failures++;
            return result;
        }
    }

    private static final class Bucket {
        final Map<String, Window> accounts = new HashMap<>();
    }

    private static final class Window {
        final long until;
        int failures = 1;
        Window(long until) { this.until = until; }
    }

    public static final class Limited extends RuntimeException {
        private final long retryAfter;
        Limited(long retryAfter) { this.retryAfter = retryAfter; }
        public <T> Result<T> respond(HttpServletResponse response) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            return Result.fail(429, "该账号尝试次数过多，请稍后再试");
        }
    }
}
