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
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/driver")
public class DriverController {
    @Autowired private com.gzu.shuttle.service.OperationClock operationClock;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private DriverMapper driverMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private EventService eventService;
    @Autowired private RequestMapper requests;
    @Autowired private StationQueueMapper queues;
    @Autowired private TaskService taskService;

    @PostMapping("/arrive")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> arrive(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = longValue(body.get("vehicleId"));
        if (vehicleId == null) return Result.fail(400, "车辆编号无效");
        Vehicle vehicle = ownedVehicleForUpdate(request, vehicleId);
        if (vehicle == null) return Result.fail(403, "无权操作未绑定车辆");
        Long stationId = Long.valueOf(body.get("stationId").toString());
        String direction = (String) body.getOrDefault("direction", "go");
        sendEvent("arrive", vehicleId, stationId, null, direction, 0, null, request);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/alight")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> alight(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = longValue(body.get("vehicleId"));
        if (vehicleId == null) return Result.fail(400, "车辆编号无效");
        Vehicle vehicle = ownedVehicleForUpdate(request, vehicleId);
        if (vehicle == null) return Result.fail(403, "无权操作未绑定车辆");
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        int count = intValue(body.getOrDefault("count", 0));
        Integer afterLoad = body.get("afterLoad") != null ? Integer.parseInt(body.get("afterLoad").toString()) : null;
        String direction = (String) body.get("direction");
        if (count < 0) return Result.fail(400, "下客人数不能为负数");
        if (afterLoad != null) {
            if (afterLoad < 0 || afterLoad > vehicle.getCurrentLoad()) return Result.fail(409, "下客后人数必须在0至当前载客人数之间");
            if (count > 0 && vehicle.getCurrentLoad() - count != afterLoad) return Result.fail(400, "下客人数与校准后人数不一致");
            count = vehicle.getCurrentLoad() - afterLoad;
        }
        if (count > vehicle.getCurrentLoad()) return Result.fail(409, "下客人数超过当前车内人数");
        sendEvent("alight", vehicleId, stationId, null, direction, -count, vehicle.getCurrentLoad() - count, request);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/board")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> board(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = longValue(body.get("vehicleId"));
        if (vehicleId == null) return Result.fail(400, "车辆编号无效");
        Vehicle vehicle = ownedVehicleForUpdate(request, vehicleId);
        if (vehicle == null) return Result.fail(403, "无权操作未绑定车辆");
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        Long lineId = body.get("lineId") != null ? Long.valueOf(body.get("lineId").toString()) : null;
        int count = intValue(body.getOrDefault("count", 0));
        Integer afterLoad = body.get("afterLoad") != null ? Integer.parseInt(body.get("afterLoad").toString()) : null;
        String direction = (String) body.get("direction");
        if (count < 0) return Result.fail(400, "上客人数不能为负数");
        if (afterLoad != null) {
            if (afterLoad < vehicle.getCurrentLoad() || afterLoad > vehicle.getCapacity()) return Result.fail(409, "上客后人数超出车辆容量范围");
            if (count > 0 && vehicle.getCurrentLoad() + count != afterLoad) return Result.fail(400, "上客人数与校准后人数不一致");
            count = afterLoad - vehicle.getCurrentLoad();
        }
        if (vehicle.getCurrentLoad() + count > vehicle.getCapacity()) return Result.fail(409, "车辆剩余座位不足，不能超载");
        if (operationClock.trip(vehicleId) != null && count > 0) {
            if (!java.util.Objects.equals(stationId, vehicle.getCurrentStationId())
                    || !java.util.Objects.equals(direction, vehicle.getDirection())
                    || !java.util.Objects.equals(lineId, vehicle.getLineId()))
                return Result.fail(409, "请按仿真车辆当前站点和方向上报");
            long waiting = requests.selectCount(new QueryWrapper<Request>()
                    .eq("line_id", lineId).eq("station_id", stationId).eq("direction", direction).eq("status", "waiting"));
            if (waiting < count) return Result.fail(409, "上客人数超过本站有效候车人数");
        }
        sendEvent("board", vehicleId, stationId, lineId, direction, count, vehicle.getCurrentLoad() + count, request);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/depart")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> depart(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = longValue(body.get("vehicleId"));
        if (vehicleId == null || ownedVehicleForUpdate(request, vehicleId) == null) return Result.fail(403, "无权操作未绑定车辆");
        sendEvent("depart", vehicleId, null, null, null, 0, null, request);
        return Result.ok(vehicleMapper.selectById(vehicleId));
    }

    @PostMapping("/calibrate")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> calibrate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long vehicleId = longValue(body.get("vehicleId"));
        if (vehicleId == null) return Result.fail(400, "车辆编号无效");
        Vehicle vehicle = ownedVehicleForUpdate(request, vehicleId);
        if (vehicle == null) return Result.fail(403, "无权操作未绑定车辆");
        int afterLoad = Integer.parseInt(body.get("afterLoad").toString());
        if (afterLoad < 0 || afterLoad > vehicle.getCapacity()) return Result.fail(400, "校准人数不能小于0或超过核定容量");
        sendEvent("calibrate", vehicleId, null, null, null, 0, afterLoad, request);
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
        Task task = taskService.acceptTask(Long.valueOf(body.get("taskId").toString()), vehicleId);
        return task == null ? Result.fail(409, "任务已被处理或未指派给当前车辆") : Result.ok(task);
    }

    @PostMapping("/task/reject")
    public Result<Task> rejectTask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!ownsTask(request, Long.valueOf(body.get("taskId").toString()))) return Result.fail(403, "无权处理其他车辆任务");
        Long taskId = Long.valueOf(body.get("taskId").toString());
        Task old = taskMapper.selectById(taskId);
        if (old == null || old.getVehicleId() == null) return Result.fail(409, "任务当前没有可拒绝的车辆指派");
        Task result = taskService.rejectTask(taskId, old.getVehicleId(), (String) body.getOrDefault("reason", ""));
        return result == null ? Result.fail(409, "任务状态已变化，无法拒绝") : Result.ok(result);
    }

    @PostMapping("/task/complete")
    public Result<Task> completeTask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long taskId = Long.valueOf(body.get("taskId").toString());
        if (!ownsTask(request, taskId)) return Result.fail(403, "无权完成其他车辆任务");
        Task assigned = taskMapper.selectById(taskId);
        if (assigned == null || assigned.getVehicleId() == null) return Result.fail(409, "任务当前没有车辆指派");
        Task task = taskService.completeTask(taskId, assigned.getVehicleId());
        return task == null ? Result.fail(409, "仅执行中的本人车辆任务可以完成") : Result.ok(task);
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

    private Vehicle ownedVehicleForUpdate(HttpServletRequest request, Long vehicleId) {
        Object driverId = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(AuthSessionService.ID);
        if (driverId == null) return null;
        Driver driver = driverMapper.selectById(Long.valueOf(driverId.toString()));
        if (driver == null || driver.getEnabled() == null || driver.getEnabled() != 1
                || driver.getDefaultVehicleId() == null || !driver.getDefaultVehicleId().equals(vehicleId)) return null;
        return vehicleMapper.selectForUpdate(vehicleId);
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(value.toString()); } catch (NumberFormatException ignored) { return null; }
    }

    private int intValue(Object value) {
        try { return Integer.parseInt(value.toString()); } catch (RuntimeException ignored) { return -1; }
    }

    private boolean ownsTask(HttpServletRequest request, Long taskId) {
        Task task = taskMapper.selectById(taskId);
        return task != null && ownsVehicle(request, task.getVehicleId());
    }

    private void sendEvent(String type, Long vehicleId, Long stationId, Long lineId, String direction,
                           int count, Integer afterLoad, HttpServletRequest request) {
        Vehicle v = vehicleMapper.selectById(vehicleId);
        ShuttleEvent event = new ShuttleEvent();
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{16,63}"))
            idempotencyKey = UUID.randomUUID().toString().replace("-", "");
        event.setEventNo("D" + idempotencyKey);
        event.setType(type);
        event.setSource("driver");
        event.setVehicleId(vehicleId);
        event.setTripId(operationClock.trip(vehicleId));
        boolean tracked = event.getTripId() != null;
        event.setStationId(tracked && stationId == null && v != null ? v.getCurrentStationId() : stationId);
        event.setLineId(lineId != null ? lineId : (v != null ? v.getLineId() : null));
        event.setDirection(tracked && direction == null && v != null ? v.getDirection() : direction);
        event.setCount(count);
        event.setAfterLoad(afterLoad);
        if (tracked && v != null && "depart".equals(type)) {
            event.setAfterLoad(v.getCurrentLoad());
            StationQueue queue = queues.selectOne(new QueryWrapper<StationQueue>()
                    .eq("line_id", event.getLineId()).eq("station_id", event.getStationId())
                    .eq("direction", event.getDirection()));
            event.setWaitingAtDeparture(queue == null ? 0 : queue.getWaitingCount());
        }
        event.setConfidence(BigDecimal.ONE);
        event.setEventTime(operationClock.now());
        eventService.receiveEvent(event);
    }
}
