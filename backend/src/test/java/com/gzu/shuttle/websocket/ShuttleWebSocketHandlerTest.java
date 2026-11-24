package com.gzu.shuttle.websocket;

import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.Map;
import com.gzu.shuttle.service.AuthSessionService;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class ShuttleWebSocketHandlerTest {
    @AfterEach
    void clearSessions() { ShuttleWebSocketHandler.SESSIONS.clear(); }

    @Test
    void guestsReceivePublicQueueUpdatesButNotTaskOrRawEvents() throws Exception {
        WebSocketSession guest = session("guest", "GUEST", 1L, null, 1L);
        ShuttleWebSocketHandler.SESSIONS.put(guest.getId(), guest);
        ShuttleWebSocketHandler.broadcast("task_assigned", map("vehicleId", 1L));
        verify(guest, never()).sendMessage(any());
        ShuttleWebSocketHandler.broadcast("queue_update", map("lineId", 1L, "waitingCount", 4));
        verify(guest, times(1)).sendMessage(any());
    }

    @Test
    void driverReceivesOnlyOwnVehicleAndLineUpdates() throws Exception {
        WebSocketSession driver = session("driver", "DRIVER", 7L, 2L, 1L);
        ShuttleWebSocketHandler.SESSIONS.put(driver.getId(), driver);
        ShuttleWebSocketHandler.broadcast("vehicle_update", map("id", 3L));
        ShuttleWebSocketHandler.broadcast("vehicle_update", map("id", 2L));
        ShuttleWebSocketHandler.broadcast("queue_update", map("lineId", 1L));
        ShuttleWebSocketHandler.broadcast("queue_update", map("lineId", 2L));
        verify(driver, times(2)).sendMessage(any());
    }

    @Test
    void administratorsReceiveOperationalEvents() throws Exception {
        WebSocketSession admin = session("admin", "ADMIN", 4L, null, null);
        ShuttleWebSocketHandler.SESSIONS.put(admin.getId(), admin);
        ShuttleWebSocketHandler.broadcast("task_assigned", map("vehicleId", 9L));
        verify(admin, times(1)).sendMessage(any());
    }

    @Test
    void heartbeatKeepsIdleConnectionOpenAndStopsAfterHttpLogout() throws Exception {
        WebSocketSession admin = session("idle", "ADMIN", 4L, null, null);
        ShuttleWebSocketHandler handler = new ShuttleWebSocketHandler();
        handler.afterConnectionEstablished(admin);
        handler.heartbeat();
        verify(admin).sendMessage(isA(PingMessage.class));
        ((MockHttpSession) admin.getAttributes().get("shuttleHttpSession")).invalidate();
        ShuttleWebSocketHandler.broadcast("task_assigned", map("vehicleId", 9L));
        verify(admin, times(1)).sendMessage(any());
        verify(admin).close(isA(CloseStatus.class));
    }

    private WebSocketSession session(String id, String role, Long userId, Long vehicleId, Long lineId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<String, Object>();
        MockHttpSession http = new MockHttpSession();
        http.setAttribute(AuthSessionService.ROLE, role);
        http.setAttribute(AuthSessionService.ID, userId);
        attributes.put("shuttleHttpSession", http);
        attributes.put("shuttleHttpSessionId", http.getId());
        attributes.put("shuttleRole", role);
        attributes.put("shuttleUserId", userId);
        attributes.put("shuttleVehicleId", vehicleId);
        attributes.put("shuttleLineId", lineId);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new HashMap<String, Object>(); map.put(key, value); return map;
    }

    private Map<String, Object> map(String key, Object value, String key2, Object value2) {
        Map<String, Object> map = map(key, value); map.put(key2, value2); return map;
    }
}
