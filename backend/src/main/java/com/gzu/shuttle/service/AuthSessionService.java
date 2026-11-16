package com.gzu.shuttle.service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class AuthSessionService {
    public static final String ROLE = "SHUTTLE_ROLE";
    public static final String ID = "SHUTTLE_ID";
    public static final String USERNAME = "SHUTTLE_USERNAME";
    public static final String DISPLAY_NAME = "SHUTTLE_DISPLAY_NAME";
    public static final String CSRF = "SHUTTLE_CSRF";
    public static final String GUEST_REQUESTS = "SHUTTLE_GUEST_REQUESTS";
    public static final String VEHICLE_ID = "SHUTTLE_VEHICLE_ID";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Set<HttpSession>> activeSessions = new ConcurrentHashMap<String, Set<HttpSession>>();

    public Map<String, Object> issue(HttpServletRequest request, String role, Long id,
                                     String username, String displayName) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ROLE, role);
        session.setAttribute(ID, id);
        session.setAttribute(USERNAME, username);
        session.setAttribute(DISPLAY_NAME, displayName);
        session.setAttribute(CSRF, token());
        if (!"GUEST".equals(role) && !"PASSENGER".equals(role)) session.removeAttribute(GUEST_REQUESTS);
        if (id != null && ("ADMIN".equals(role) || "DISPATCHER".equals(role) || "DRIVER".equals(role) || "PASSENGER".equals(role))) {
            Set<HttpSession> sessions = activeSessions.computeIfAbsent(principalKey(role, id), ignored -> ConcurrentHashMap.newKeySet());
            sessions.removeIf(existing -> {
                try { return existing.getAttribute(ROLE) == null; } catch (IllegalStateException invalidated) { return true; }
            });
            sessions.add(session);
        }
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("id", id);
        user.put("username", username);
        user.put("displayName", displayName);
        user.put("role", role);
        return view(session, user);
    }

    public Map<String, Object> session(HttpServletRequest request, boolean guest) {
        HttpSession session = request.getSession(false);
        if (session == null && guest) session = request.getSession(true);
        if (session != null && guest && session.getAttribute(ROLE) == null) ensureGuest(request);
        if (session == null || session.getAttribute(ROLE) == null) {
            return publicView(false, null, null);
        }
        String role = (String) session.getAttribute(ROLE);
        boolean guestSession = "GUEST".equals(role);
        if ("GUEST".equals(role)) role = "PASSENGER";
        if (session.getAttribute(CSRF) == null) session.setAttribute(CSRF, token());
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("id", session.getAttribute(ID));
        user.put("username", session.getAttribute(USERNAME));
        user.put("displayName", session.getAttribute(DISPLAY_NAME));
        user.put("role", role);
        user.put("guest", guestSession);
        return view(session, user);
    }

    public void ensureGuest(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        if (session.getAttribute(ROLE) == null) {
            session.setAttribute(ROLE, "GUEST");
            session.setAttribute(USERNAME, "guest");
            session.setAttribute(DISPLAY_NAME, "游客乘客");
            session.setAttribute(CSRF, token());
        }
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object role = session.getAttribute(ROLE);
            Object id = session.getAttribute(ID);
            if (role != null && id != null) {
                Set<HttpSession> sessions = activeSessions.get(principalKey(role.toString(), Long.valueOf(id.toString())));
                if (sessions != null) sessions.remove(session);
            }
            session.invalidate();
        }
    }

    public void revoke(String role, Long id) {
        if (role == null || id == null) return;
        Set<HttpSession> sessions = activeSessions.remove(principalKey(role, id));
        if (sessions == null) return;
        for (HttpSession session : sessions) {
            try { session.invalidate(); } catch (IllegalStateException ignored) { }
        }
        com.gzu.shuttle.websocket.ShuttleWebSocketHandler.closeSessionsForUser(role, id);
    }

    private String principalKey(String role, Long id) { return role + ":" + id; }

    private Map<String, Object> view(HttpSession session, Map<String, Object> user) {
        Map<String, Object> result = publicView(true, user, (String) session.getAttribute(CSRF));
        return result;
    }

    private Map<String, Object> publicView(boolean authenticated, Map<String, Object> user, String csrf) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("authenticated", authenticated);
        result.put("user", user);
        result.put("csrfToken", csrf);
        return result;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        char[] encoded = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            encoded[i * 2] = HEX[(bytes[i] >>> 4) & 15];
            encoded[i * 2 + 1] = HEX[bytes[i] & 15];
        }
        return new String(encoded);
    }
}
