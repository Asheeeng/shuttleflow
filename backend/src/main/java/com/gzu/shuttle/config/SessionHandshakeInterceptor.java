package com.gzu.shuttle.config;

import com.gzu.shuttle.service.AuthSessionService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class SessionHandshakeInterceptor implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        HttpServletRequest servlet = ((ServletServerHttpRequest) request).getServletRequest();
        HttpSession session = servlet.getSession(false);
        if (session == null || session.getAttribute(AuthSessionService.ROLE) == null) return false;
        attributes.put("shuttleRole", session.getAttribute(AuthSessionService.ROLE));
        attributes.put("shuttleUserId", session.getAttribute(AuthSessionService.ID));
        return true;
    }
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }
}
