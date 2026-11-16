package com.gzu.shuttle.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.Passenger;
import com.gzu.shuttle.mapper.PassengerMapper;
import com.gzu.shuttle.service.AuthSessionService;
import com.gzu.shuttle.service.PasswordHashService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/passenger")
public class PassengerAccountController {
    private final PassengerMapper passengers;
    private final AuthSessionService sessions;
    private final PasswordHashService passwordHashes;

    public PassengerAccountController(PassengerMapper passengers, AuthSessionService sessions,
                                      PasswordHashService passwordHashes) {
        this.passengers = passengers;
        this.sessions = sessions;
        this.passwordHashes = passwordHashes;
    }

    @PostMapping("/register")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                 HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        Object role = current == null ? null : current.getAttribute(AuthSessionService.ROLE);
        if (role != null && !"GUEST".equals(role)) return Result.fail(409, "请先退出当前账号，再注册乘客账号");

        String name = trim(body.get("name"));
        String phone = normalizePhone(body.get("phone"));
        String password = body.get("password");
        if (name == null || name.length() > 20) return Result.fail(400, "姓名需为1至20个字符");
        if (phone == null || !phone.matches("^\\+?[0-9]{7,18}$")) return Result.fail(400, "请输入有效手机号");
        if (password == null || password.length() < 8 || password.length() > 128)
            return Result.fail(400, "密码需为8至128个字符");

        Passenger passenger = new Passenger();
        passenger.setName(name);
        passenger.setPhone(phone);
        passenger.setOpenId("local:" + UUID.randomUUID().toString().replace("-", ""));
        passenger.setPasswordHash(passwordHashes.encode(password));
        passenger.setCreatedAt(LocalDateTime.now());
        try {
            passengers.insert(passenger);
        } catch (DuplicateKeyException duplicate) {
            return Result.fail(409, "该手机号已注册");
        }
        return Result.ok(authResult(request, passenger));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                              HttpServletRequest request) {
        String phone = normalizePhone(body.get("phone"));
        String password = body.get("password");
        Passenger passenger = null;
        if (phone != null && phone.matches("^\\+?[0-9]{7,18}$")) {
            QueryWrapper<Passenger> query = new QueryWrapper<Passenger>();
            query.eq("phone", phone);
            passenger = passengers.selectOne(query);
        }
        if (passenger == null || password == null || password.length() > 128
                || passenger.getPasswordHash() == null
                || !passwordHashes.matches(password, passenger.getPasswordHash())) {
            return Result.fail(401, "手机号或密码错误");
        }
        return Result.ok(authResult(request, passenger));
    }

    private Map<String, Object> authResult(HttpServletRequest request, Passenger passenger) {
        Map<String, Object> auth = sessions.issue(request, "PASSENGER", passenger.getId(),
                passenger.getPhone(), passenger.getName());
        Map<String, Object> safe = new HashMap<String, Object>();
        safe.put("id", passenger.getId());
        safe.put("name", passenger.getName());
        safe.put("phone", passenger.getPhone());
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("passenger", safe);
        result.put("user", auth.get("user"));
        result.put("csrfToken", auth.get("csrfToken"));
        return result;
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private String normalizePhone(String value) {
        if (value == null) return null;
        return value.replaceAll("[\\s-]", "").trim();
    }
}
