package com.gzu.shuttle.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {
    private final FixedWindowRateLimiter limiter;
    private final ObjectMapper mapper;
    @Value("${shuttle.rate-limit.auth-per-window:10}") private int authLimit;
    @Value("${shuttle.rate-limit.auth-window-seconds:900}") private int authWindowSeconds;
    @Value("${shuttle.rate-limit.passenger-writes-per-window:20}") private int passengerWriteLimit;
    @Value("${shuttle.rate-limit.write-window-seconds:60}") private int writeWindowSeconds;

    public ApiRateLimitInterceptor(FixedWindowRateLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Value("${shuttle.operation.enabled:false}") private boolean operationEnabled;
    @Value("${shuttle.operation.token:}") private String operationToken;

    private boolean operationWorker(HttpServletRequest request) {
        String supplied = request.getHeader("X-Operation-Token");
        return operationEnabled && "127.0.0.1".equals(request.getRemoteAddr())
                && operationToken.length() >= 32 && supplied != null
                && java.security.MessageDigest.isEqual(
                    operationToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String group;
        int limit;
        int seconds;
        if (path.matches("/api/(driver|admin|passenger)/(login|register)")) {
            group = "auth";
            limit = path.endsWith("/register") ? Math.max(3, authLimit / 2) : authLimit;
            seconds = authWindowSeconds;
        } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/passenger/")) {
            group = "passenger-write";
            limit = passengerWriteLimit;
            seconds = writeWindowSeconds;
        } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            group = "api-write";
            limit = Math.max(passengerWriteLimit, 60);
            seconds = writeWindowSeconds;
        } else {
            return true;
        }

        boolean worker = operationWorker(request);
        String key = worker ? "operation-worker:" + group : group + ":" + clientAddress(request);
        if (worker) {
            limit = 4800;
            seconds = 60;
        }
        if (limiter.allow(key, Math.max(limit, 1), Math.max(seconds, 1) * 1000L)) return true;
        response.setStatus(429);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(Math.max(seconds, 1)));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", 429);
        body.put("msg", "请求过于频繁，请稍后重试");
        mapper.writeValue(response.getOutputStream(), body);
        return false;
    }

    private String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean trustedLocalProxy = "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        if (trustedLocalProxy) {
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && forwarded.matches("[0-9a-fA-F:.]{2,64}")) return forwarded;
        }
        return remote == null ? "unknown" : remote;
    }
}
