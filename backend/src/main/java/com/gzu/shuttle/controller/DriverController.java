package com.gzu.shuttle.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.service.EventService;
import com.gzu.shuttle.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import com.gzu.shuttle.service.AuthSessionService;

@RestController
@RequestMapping("/api/driver")
public class DriverController {
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private DriverMapper driverMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private EventService eventService;
    @Autowired private TaskService taskService;

    @PostMapping("/arrive")
    public Result<Vehicle> arrive(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权操作未绑定车辆");
        Long stationId = Long.valueOf(body.get("stationId").toString());
        String direction = (String) body.getOrDefault("direction", "go");
        sendEvent("arrive", vehicleId, stationId, null, direction, 0, null);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/alight")
    public Result<Vehicle> alight(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权操作未绑定车辆");
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        int count = Integer.parseInt(body.getOrDefault("count", 0).toString());
        Integer afterLoad = body.get("afterLoad") != null ? Integer.parseInt(body.get("afterLoad").toString()) : null;
        String direction = (String) body.get("direction");
        sendEvent("alight", vehicleId, stationId, null, direction, -count, afterLoad);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/board")
    public Result<Vehicle> board(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权操作未绑定车辆");
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        Long lineId = body.get("lineId") != null ? Long.valueOf(body.get("lineId").toString()) : null;
        int count = Integer.parseInt(body.getOrDefault("count", 0).toString());
        Integer afterLoad = body.get("afterLoad") != null ? Integer.parseInt(body.get("afterLoad").toString()) : null;
        String direction = (String) body.get("direction");
        sendEvent("board", vehicleId, stationId, lineId, direction, count, afterLoad);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/depart")
    public Result<Vehicle> depart(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权操作未绑定车辆");
        sendEvent("depart", vehicleId, null, null, null, 0, null);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/calibrate")
    public Result<Vehicle> calibrate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权操作未绑定车辆");
        int afterLoad = Integer.parseInt(body.get("afterLoad").toString());
        sendEvent("calibrate", vehicleId, null, null, null, 0, afterLoad);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @GetMapping("/vehicle/{vehicleId}")
    public Result<Vehicle> vehicle(@PathVariable Long vehicleId, HttpServletRequest request) {
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权查看未绑定车辆");
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @GetMapping("/tasks/{vehicleId}")
    public Result<List<Task>> tasks(@PathVariable Long vehicleId, HttpServletRequest request) {
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权查看未绑定车辆任务");
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("vehicle_id", vehicleId).in("status", "assigned", "executing").orderByDesc("priority");
        return Result.ok(taskMapper.selectList(qw));
    }

    @PostMapping("/task/accept")
    public Result<Task> acceptTask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权接收其他司机车辆的任务");
        return Result.ok(taskService.acceptTask(Long.valueOf(body.get("taskId").toString()), vehicleId));
    }

    @PostMapping("/task/reject")
    public Result<Task> rejectTask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!ownsTask(request, Long.valueOf(body.get("taskId").toString()))) return Result.fail(403, "无权处理其他车辆任务");
        return Result.ok(taskService.rejectTask(Long.valueOf(body.get("taskId").toString()), (String) body.getOrDefault("reason", "")));
    }

    @PostMapping("/task/complete")
    public Result<Task> completeTask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!ownsTask(request, Long.valueOf(body.get("taskId").toString()))) return Result.fail(403, "无权完成其他车辆任务");
        return Result.ok(taskService.completeTask(Long.valueOf(body.get("taskId").toString())));
    }

    @PostMapping("/set-running")
    public Result<Vehicle> setRunning(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        if (!ownsVehicle(request, vehicleId)) return Result.fail(403, "无权更改未绑定车辆运行状态");
        boolean running = Boolean.parseBoolean(body.getOrDefault("running", "true").toString());
        Vehicle v = vehicleMapper.selectById(vehicleId);
        if (v != null) {
            v.setRunningToday(running ? 1 : 0);
            if (running && "offline".equals(v.getStatus())) v.setStatus("standby");
            if (!running) v.setStatus("offline");
            vehicleMapper.updateById(v);
        }
        return Result.ok(v);
    }


    private boolean ownsVehicle(HttpServletRequest request, Long vehicleId) {
        Object driverId = request.getSession(false) == null ? null : request.getSession(false).getAttribute(AuthSessionService.ID);
        if (driverId == null) return false;
        Driver driver = driverMapper.selectById(Long.valueOf(driverId.toString()));
        return driver != null && driver.getEnabled() != null && driver.getEnabled() == 1
                && driver.getDefaultVehicleId() != null && driver.getDefaultVehicleId().equals(vehicleId);
    }

    private boolean ownsTask(HttpServletRequest request, Long taskId) {
        Task task = taskMapper.selectById(taskId);
        return task != null && ownsVehicle(request, task.getVehicleId());
    }

    private void sendEvent(String type, Long vehicleId, Long stationId, Long lineId, String direction, int count, Integer afterLoad) {
        Vehicle v = vehicleMapper.selectById(vehicleId);
        ShuttleEvent event = new ShuttleEvent();
        event.setEventNo("E" + System.currentTimeMillis() + "_" + type + "_" + new Random().nextInt(9999));
        event.setType(type);
        event.setSource("driver");
        event.setVehicleId(vehicleId);
        event.setStationId(stationId);
        event.setLineId(lineId != null ? lineId : (v != null ? v.getLineId() : null));
        event.setDirection(direction);
        event.setCount(count);
        event.setAfterLoad(afterLoad);
        event.setConfidence(BigDecimal.ONE);
        event.setEventTime(LocalDateTime.now());
        eventService.receiveEvent(event);
    }
}
