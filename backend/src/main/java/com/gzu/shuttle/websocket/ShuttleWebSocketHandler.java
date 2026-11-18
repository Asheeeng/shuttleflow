package com.gzu.shuttle.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class ShuttleWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ShuttleWebSocketHandler.class);
    public static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<String, WebSocketSession>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.put(session.getId(), session);
        log.info("WebSocket连接建立：{}，当前连接数={}", session.getId(), SESSIONS.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session.getId());
        log.info("WebSocket连接关闭：{}，当前连接数={}", session.getId(), SESSIONS.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 服务端只推送事件，不接受客户端订阅或权限声明。
    }

    public static void broadcast(String type, Object data) {
        JSONObject payload = jsonObject(data);
        JSONObject message = new JSONObject();
        message.put("type", type);
        message.put("data", data);
        message.put("timestamp", System.currentTimeMillis());
        TextMessage frame = new TextMessage(message.toJSONString());
        for (WebSocketSession session : SESSIONS.values()) {
            if (!session.isOpen()) {
                SESSIONS.remove(session.getId(), session);
                continue;
            }
            if (!canReceive(session, type, payload)) continue;
            try {
                synchronized (session) { if (session.isOpen()) session.sendMessage(frame); }
            } catch (IOException | IllegalStateException error) {
                log.warn("WebSocket消息推送失败：{}", session.getId());
            }
        }
    }

    private static boolean canReceive(WebSocketSession session, String type, JSONObject payload) {
        Object roleValue = session.getAttributes().get("shuttleRole");
        String role = roleValue == null ? "" : roleValue.toString();
        if ("simulation_change".equals(type)) return !role.isEmpty();
        if ("ADMIN".equals(role) || "DISPATCHER".equals(role)) return true;
        if ("GUEST".equals(role) || "PASSENGER".equals(role)) return "queue_update".equals(type);
        if (!"DRIVER".equals(role)) return false;
        if ("queue_update".equals(type))
            return sameId(payload.get("lineId"), session.getAttributes().get("shuttleLineId"));
        if (!("vehicle_update".equals(type) || "task_new".equals(type) || "task_assigned".equals(type)
                || "task_update".equals(type) || "task_escalated".equals(type)
                || "overload_alert".equals(type) || "event".equals(type))) return false;
        Object vehicleId = payload.containsKey("vehicleId") ? payload.get("vehicleId") : payload.get("id");
        return sameId(vehicleId, session.getAttributes().get("shuttleVehicleId"));
    }

    private static boolean sameId(Object left, Object right) {
        return left != null && right != null && left.toString().equals(right.toString());
    }

    public static void closeSessionsForUser(String role, Long userId) {
        for (WebSocketSession session : SESSIONS.values()) {
            Object sessionRole = session.getAttributes().get("shuttleRole");
            Object sessionUser = session.getAttributes().get("shuttleUserId");
            if (role != null && userId != null && role.equals(sessionRole)
                    && sessionUser != null && userId.toString().equals(sessionUser.toString())) {
                try { session.close(CloseStatus.NORMAL.withReason("账号凭据已变更，请重新连接")); }
                catch (IOException ignored) { }
                SESSIONS.remove(session.getId(), session);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static JSONObject jsonObject(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        Object converted = JSON.toJSON(value);
        if (converted instanceof JSONObject) return (JSONObject) converted;
        if (converted instanceof Map) return new JSONObject((Map<String, Object>) converted);
        return new JSONObject();
    }
}
