package com.gzu.shuttle.service;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class SessionAuthService {
    public static final String ROLE = "SHUTTLE_ROLE";
    public static final String ID = "SHUTTLE_ID";
    public static final String USERNAME = "SHUTTLE_USERNAME";
    public static final String DISPLAY_NAME = "SHUTTLE_DISPLAY_NAME";
    public static final String CSRF = "SHUTTLE_CSRF";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public Map<String, Object> issue(HttpServletRequest request, String role, Long id,
                                     String username, String displayName) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ROLE, role);
        session.setAttribute(ID, id);
        session.setAttribute(USERNAME, username);
        session.setAttribute(DISPLAY_NAME, displayName);
        session.setAttribute(CSRF, token());
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
        if (session == null || session.getAttribute(ROLE) == null) {
            return publicView(false, null, null);
        }
        String role = (String) session.getAttribute(ROLE);
        if ("GUEST".equals(role)) role = "PASSENGER";
        if (session.getAttribute(CSRF) == null) session.setAttribute(CSRF, token());
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("id", session.getAttribute(ID));
        user.put("username", session.getAttribute(USERNAME));
        user.put("displayName", session.getAttribute(DISPLAY_NAME));
        user.put("role", role);
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
        if (session != null) session.invalidate();
    }

    private Map<String, Object> view(HttpSession session, Map<String, Object> user) {
        return publicView(true, user, (String) session.getAttribute(CSRF));
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
