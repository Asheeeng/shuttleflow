package com.gzu.shuttle.config;

import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SessionHandshakeInterceptor authentication;
    @Value("${shuttle.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
    private String allowedOrigins;
    public WebSocketConfig(SessionHandshakeInterceptor authentication) { this.authentication = authentication; }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        registry.addHandler(new ShuttleWebSocketHandler(), "/ws")
                .addInterceptors(authentication).setAllowedOrigins(origins);
    }
}
