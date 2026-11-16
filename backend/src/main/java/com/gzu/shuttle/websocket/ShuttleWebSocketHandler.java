package com.gzu.shuttle.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ShuttleWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ShuttleWebSocketHandler.class);
    public static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.put(session.getId(), session);
        log.info("WS connected: {} (total={})", session.getId(), SESSIONS.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session.getId());
        log.info("WS closed: {} (total={})", session.getId(), SESSIONS.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端可发订阅消息，此处简单忽略
    }

    public static void broadcast(String type, Object data) {
        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("data", data);
        msg.put("timestamp", System.currentTimeMillis());
        String json = msg.toJSONString();
        for (WebSocketSession s : SESSIONS.values()) {
            if (s.isOpen()) {
                try { s.sendMessage(new TextMessage(json)); } catch (IOException e) { log.error("WS send error", e); }
            }
        }
    }
}