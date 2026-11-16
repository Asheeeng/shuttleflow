package com.gzu.shuttle.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.service.AuthSessionService;
import com.gzu.shuttle.service.PasswordHashService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class CommonController {
    @Autowired private PasswordHashService passwordHashes;
    @Autowired private AuthSessionService authSessions;
    @Autowired private LineMapper lineMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private LineStationMapper lsMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private DriverMapper driverMapper;
    @Autowired private AdminMapper adminMapper;

    @GetMapping("/lines")
    public Result<List<Line>> lines() {
        return Result.ok(lineMapper.selectList(new QueryWrapper<Line>().eq("enabled", 1)));
    }

    @GetMapping("/stations")
    public Result<List<Station>> stations() {
        return Result.ok(stationMapper.selectList(new QueryWrapper<Station>().eq("enabled", 1)));
    }

    @GetMapping("/line/{lineId}/stations")
    public Result<List<Map<String, Object>>> lineStations(@PathVariable Long lineId,
                                                           @RequestParam(defaultValue = "go") String direction) {
        QueryWrapper<LineStation> qw = new QueryWrapper<>();
        qw.eq("line_id", lineId).eq("direction", direction).orderByAsc("seq");
        List<LineStation> list = lsMapper.selectList(qw);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LineStation ls : list) {
            Station s = stationMapper.selectById(ls.getStationId());
            Map<String, Object> m = new HashMap<>();
            m.put("seq", ls.getSeq());
            m.put("stationId", s.getId());
            m.put("stationName", s.getName());
            m.put("shortName", s.getShortName());
            m.put("campus", s.getCampus());
            m.put("qrCode", s.getQrCode());
            m.put("distanceM", ls.getDistanceM());
            m.put("travelSec", ls.getTravelSec());
            result.add(m);
        }
        return Result.ok(result);
    }

    @GetMapping("/vehicles")
    public Result<List<Vehicle>> vehicles() {
        return Result.ok(vehicleMapper.selectList(null));
    }

    @GetMapping("/vehicle/{id}")
    public Result<Vehicle> vehicleById(@PathVariable Long id) {
        return Result.ok(vehicleMapper.selectById(id));
    }

    @PostMapping("/driver/login")
    public Result<Map<String, Object>> driverLogin(@RequestBody Map<String, String> body, javax.servlet.http.HttpServletRequest request) {
        String phone = body.get("phone");
        String supplied = body.get("password");
        QueryWrapper<Driver> qw = new QueryWrapper<Driver>();
        qw.eq("phone", phone).eq("enabled", 1);
        Driver d = driverMapper.selectOne(qw);
        if (d == null || supplied == null || d.getPasswordHash() == null || !passwordHashes.matches(supplied, d.getPasswordHash()))
            return Result.fail(401, "手机号或密码错误");
        d.setStatus("on_duty");
        driverMapper.updateById(d);
        Vehicle vehicle = d.getDefaultVehicleId() == null ? null : vehicleMapper.selectById(d.getDefaultVehicleId());
        Map<String, Object> safeDriver = new HashMap<String, Object>();
        safeDriver.put("id", d.getId()); safeDriver.put("name", d.getName());
        safeDriver.put("phone", d.getPhone()); safeDriver.put("defaultVehicleId", d.getDefaultVehicleId());
        safeDriver.put("status", d.getStatus());
        Map<String, Object> auth = authSessions.issue(request, "DRIVER", d.getId(), d.getPhone(), d.getName());
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("driver", safeDriver); result.put("vehicle", vehicle);
        result.put("user", auth.get("user")); result.put("csrfToken", auth.get("csrfToken"));
        return Result.ok(result);
    }

    @PostMapping("/driver/register")
    public Result<Map<String, Object>> driverRegister(@RequestBody Map<String, Object> body, javax.servlet.http.HttpServletRequest request) {
        String phone = (String) body.get("phone");
        String password = (String) body.get("password");
        String name = (String) body.getOrDefault("name", "");
        if (phone == null || !phone.matches("^[0-9+ -]{7,20}$")) return Result.fail(400, "请输入有效手机号");
        if (password == null || password.length() < 8 || password.length() > 128) return Result.fail(400, "新密码长度需为8至128个字符");
        QueryWrapper<Driver> existQw = new QueryWrapper<>();
        existQw.eq("phone", phone);
        if (driverMapper.selectCount(existQw) > 0) return Result.fail("该手机号已注册");

        Long vehicleId = null;
        if (body.get("vehicleId") != null && !body.get("vehicleId").toString().isEmpty()) {
            vehicleId = Long.valueOf(body.get("vehicleId").toString());
            QueryWrapper<Driver> assignedCheck = new QueryWrapper<Driver>();
            assignedCheck.eq("default_vehicle_id", vehicleId);
            if (driverMapper.selectCount(assignedCheck) > 0) return Result.fail(409, "该车辆已绑定司机");
        } else {
            List<Driver> allDrivers = driverMapper.selectList(null);
            java.util.Set<Long> assignedVehicles = new java.util.HashSet<>();
            for (Driver d : allDrivers) {
                if (d.getDefaultVehicleId() != null) assignedVehicles.add(d.getDefaultVehicleId());
            }
            List<Vehicle> allVehicles = vehicleMapper.selectList(null);
            for (Vehicle v : allVehicles) {
                if (!assignedVehicles.contains(v.getId())) { vehicleId = v.getId(); break; }
            }
            if (vehicleId == null) return Result.fail("所有车辆已分配司机");
        }

        Driver d = new Driver();
        d.setName(name);
        d.setPhone(phone);
        d.setPassword("");
        d.setPasswordHash(passwordHashes.encode(password));
        d.setDefaultVehicleId(vehicleId);
        d.setStatus("off");
        d.setEnabled(1);
        driverMapper.insert(d);

        Map<String, Object> safeDriver = new HashMap<String, Object>();
        safeDriver.put("id", d.getId()); safeDriver.put("name", d.getName()); safeDriver.put("phone", d.getPhone());
        safeDriver.put("defaultVehicleId", d.getDefaultVehicleId()); safeDriver.put("status", d.getStatus());
        Map<String, Object> auth = authSessions.issue(request, "DRIVER", d.getId(), d.getPhone(), d.getName());
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("driver", safeDriver); m.put("vehicle", vehicleMapper.selectById(vehicleId));
        m.put("user", auth.get("user")); m.put("csrfToken", auth.get("csrfToken"));
        return Result.ok(m);
    }

    @GetMapping("/driver/available-vehicles")
    public Result<List<Map<String, Object>>> availableVehicles() {
        List<Driver> allDrivers = driverMapper.selectList(null);
        java.util.Set<Long> assigned = new java.util.HashSet<>();
        for (Driver d : allDrivers) {
            if (d.getDefaultVehicleId() != null) assigned.add(d.getDefaultVehicleId());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<Vehicle> allVehicles = vehicleMapper.selectList(null);
        for (Vehicle v : allVehicles) {
            Map<String, Object> m = new HashMap<>();
            m.put("vehicleId", v.getId());
            m.put("plateNo", v.getPlateNo());
            m.put("lineId", v.getLineId());
            m.put("assigned", assigned.contains(v.getId()));
            result.add(m);
        }
        return Result.ok(result);
    }

    @PostMapping("/admin/login")
    public Result<Map<String, Object>> adminLogin(@RequestBody Map<String, String> body, javax.servlet.http.HttpServletRequest request) {
        QueryWrapper<Admin> qw = new QueryWrapper<Admin>();
        qw.eq("username", body.get("username"));
        Admin a = adminMapper.selectOne(qw);
        String supplied = body.get("password");
        if (a == null || supplied == null || a.getPasswordHash() == null || !passwordHashes.matches(supplied, a.getPasswordHash()))
            return Result.fail(401, "用户名或密码错误");
        String role;
        if ("admin".equalsIgnoreCase(a.getRole())) role = "ADMIN";
        else if ("dispatcher".equalsIgnoreCase(a.getRole())) role = "DISPATCHER";
        else return Result.fail(403, "该账号未配置有效的管理角色");
        Map<String, Object> auth = authSessions.issue(request, role, a.getId(), a.getUsername(), a.getName());
        Map<String, Object> safe = new HashMap<String, Object>();
        safe.put("id", a.getId()); safe.put("username", a.getUsername()); safe.put("name", a.getName()); safe.put("role", role);
        Map<String, Object> result = new HashMap<String, Object>(); result.put("admin", safe);
        result.put("user", auth.get("user")); result.put("csrfToken", auth.get("csrfToken"));
        return Result.ok(result);
    }

}
