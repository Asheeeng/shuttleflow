package com.gzu.shuttle.controller;

import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.Driver;
import com.gzu.shuttle.entity.Vehicle;
import com.gzu.shuttle.mapper.DriverMapper;
import com.gzu.shuttle.mapper.VehicleMapper;
import com.gzu.shuttle.service.AuthSessionService;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthSessionService sessions;
    private final DriverMapper drivers;
    private final VehicleMapper vehicles;
    public AuthController(AuthSessionService sessions, DriverMapper drivers, VehicleMapper vehicles) {
        this.sessions = sessions; this.drivers = drivers; this.vehicles = vehicles;
    }

    @GetMapping("/session")
    public Result<Map<String, Object>> session(HttpServletRequest request,
                                               @RequestParam(defaultValue = "false") boolean guest) {
        Map<String, Object> result = sessions.session(request, guest);
        Object userObj = result.get("user");
        if (userObj instanceof Map) {
            Map user = (Map) userObj;
            if ("DRIVER".equals(user.get("role")) && user.get("id") != null) {
                Driver d = drivers.selectById(Long.valueOf(user.get("id").toString()));
                if (d != null) {
                    Map<String, Object> safe = new HashMap<String, Object>();
                    safe.put("id", d.getId()); safe.put("name", d.getName());
                    safe.put("phone", d.getPhone()); safe.put("defaultVehicleId", d.getDefaultVehicleId());
                    safe.put("status", d.getStatus());
                    result.put("driver", safe);
                    Vehicle vehicle = d.getDefaultVehicleId() == null ? null : vehicles.selectById(d.getDefaultVehicleId());
                    result.put("vehicle", vehicle);
                }
            }
        }
        return Result.ok(result);
    }

    @PostMapping("/logout")
    public Result<Map<String, Object>> logout(HttpServletRequest request) {
        sessions.logout(request);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("loggedOut", true);
        return Result.ok(data);
    }
}
