package com.gzu.shuttle.config;

import com.gzu.shuttle.service.AuthSessionService;
import com.gzu.shuttle.entity.Driver;
import com.gzu.shuttle.entity.Vehicle;
import com.gzu.shuttle.mapper.DriverMapper;
import com.gzu.shuttle.mapper.VehicleMapper;
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
    private final DriverMapper drivers;
    private final VehicleMapper vehicles;
    public SessionHandshakeInterceptor(DriverMapper drivers, VehicleMapper vehicles) {
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        HttpServletRequest servlet = ((ServletServerHttpRequest) request).getServletRequest();
        HttpSession session = servlet.getSession(false);
        if (session == null || session.getAttribute(AuthSessionService.ROLE) == null) return false;
        attributes.put("shuttleRole", session.getAttribute(AuthSessionService.ROLE));
        attributes.put("shuttleUserId", session.getAttribute(AuthSessionService.ID));
        if ("DRIVER".equals(session.getAttribute(AuthSessionService.ROLE))) {
            Object driverId = session.getAttribute(AuthSessionService.ID);
            Driver driver = driverId == null ? null : drivers.selectById(Long.valueOf(driverId.toString()));
            Vehicle vehicle = driver == null || driver.getDefaultVehicleId() == null
                    ? null : vehicles.selectById(driver.getDefaultVehicleId());
            if (driver == null || driver.getEnabled() == null || driver.getEnabled() != 1 || vehicle == null) return false;
            attributes.put("shuttleVehicleId", vehicle.getId());
            attributes.put("shuttleLineId", vehicle.getLineId());
        }
        return true;
    }
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }
}
