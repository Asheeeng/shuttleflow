package com.gzu.shuttle.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gzu.shuttle.service.AuthSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SessionAuthInterceptor implements HandlerInterceptor {
    private final ObjectMapper mapper;
    @Value("${shuttle.ai.service-token:}") private String aiServiceToken;
    public SessionAuthInterceptor(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        if (path.equals("/api/driver/login") || path.equals("/api/admin/login")) return true;
        if (path.equals("/api/auth/session")) return true;
        if (path.equals("/api/driver/register")) return csrf(request, response);
        if (path.equals("/api/driver/available-vehicles")) return true;
        if (isTrustedAiRead(request, path)) return true;
        HttpSession session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute(AuthSessionService.ROLE);
        if (path.startsWith("/api/passenger/")) {
            if (role != null && isMutation(request.getMethod())
                    && !"GUEST".equals(role) && !"PASSENGER".equals(role))
                return reject(response, 403, "FORBIDDEN", "当前账号不能使用乘客操作");
            if (role == null && isMutation(request.getMethod()))
                return reject(response, 401, "UNAUTHENTICATED", "请先建立乘客游客会话");
            return csrf(request, response);
        }
        if (path.startsWith("/api/admin/")) {
            if (!"ADMIN".equals(role) && !"DISPATCHER".equals(role))
                return reject(response, 401, "UNAUTHENTICATED", "请先使用管理员或调度员账号登录");
            if (path.startsWith("/api/admin/vehicle/") && !"ADMIN".equals(role))
                return reject(response, 403, "FORBIDDEN", "该车辆管理操作仅限系统管理员");
        }
        if (path.startsWith("/api/driver/") && !"DRIVER".equals(role))
            return reject(response, 401, "UNAUTHENTICATED", "请先使用司机账号登录");
        return csrf(request, response);
    }

    private boolean isMutation(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private boolean isTrustedAiRead(HttpServletRequest request, String path) {
        if (aiServiceToken == null || aiServiceToken.isEmpty() || !"GET".equalsIgnoreCase(request.getMethod())) return false;
        if (!"/api/admin/dashboard".equals(path) && !"/api/admin/station-vehicles".equals(path)) return false;
        String supplied = request.getHeader("X-Shuttle-Service-Token");
        return supplied != null && MessageDigest.isEqual(
                aiServiceToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean csrf(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)
                && !"PATCH".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) return true;
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(AuthSessionService.ROLE) == null) return true;
        String expected = (String) session.getAttribute(AuthSessionService.CSRF);
        String supplied = request.getHeader("X-Shuttle-CSRF");
        if (expected == null || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            return reject(response, 403, "CSRF_INVALID", "安全校验已失效，请刷新页面后重试");
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", status); body.put("msg", message); body.put("error", code); body.put("data", null);
        mapper.writeValue(response.getWriter(), body);
        return false;
    }
}
