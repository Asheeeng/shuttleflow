package com.gzu.shuttle.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class CommonController {
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
    public Result<Map<String, Object>> driverLogin(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String password = body.get("password");
        QueryWrapper<Driver> qw = new QueryWrapper<>();
        qw.eq("phone", phone).eq("password", password).eq("enabled", 1);
        Driver d = driverMapper.selectOne(qw);
        if (d == null) return Result.fail(401, "手机号或密码错误");
        d.setStatus("on_duty");
        driverMapper.updateById(d);
        Map<String, Object> m = new HashMap<>();
        m.put("driver", d);
        if (d.getDefaultVehicleId() != null) m.put("vehicle", vehicleMapper.selectById(d.getDefaultVehicleId()));
        return Result.ok(m);
    }

    @PostMapping("/driver/register")
    public Result<Map<String, Object>> driverRegister(@RequestBody Map<String, Object> body) {
        String phone = (String) body.get("phone");
        String password = (String) body.get("password");
        String name = (String) body.getOrDefault("name", "");
        QueryWrapper<Driver> existQw = new QueryWrapper<>();
        existQw.eq("phone", phone);
        if (driverMapper.selectCount(existQw) > 0) return Result.fail("该手机号已注册");

        Long vehicleId = null;
        if (body.get("vehicleId") != null) {
            vehicleId = Long.valueOf(body.get("vehicleId").toString());
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
        d.setPassword(password);
        d.setDefaultVehicleId(vehicleId);
        d.setStatus("off");
        d.setEnabled(1);
        driverMapper.insert(d);

        Map<String, Object> m = new HashMap<>();
        m.put("driver", d);
        m.put("vehicle", vehicleMapper.selectById(vehicleId));
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
    public Result<Admin> adminLogin(@RequestBody Map<String, String> body) {
        QueryWrapper<Admin> qw = new QueryWrapper<>();
        qw.eq("username", body.get("username")).eq("password", body.get("password"));
        Admin a = adminMapper.selectOne(qw);
        if (a == null) return Result.fail(401, "用户名或密码错误");
        return Result.ok(a);
    }
}