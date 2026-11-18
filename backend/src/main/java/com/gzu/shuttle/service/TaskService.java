package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.Line;
import com.gzu.shuttle.entity.Task;
import com.gzu.shuttle.entity.Vehicle;
import com.gzu.shuttle.mapper.LineMapper;
import com.gzu.shuttle.mapper.TaskMapper;
import com.gzu.shuttle.mapper.VehicleMapper;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

@Service
public class TaskService {
    @org.springframework.beans.factory.annotation.Autowired private OperationClock operationClock = new OperationClock();
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final TaskMapper taskMapper;
    private final VehicleMapper vehicleMapper;
    private final LineMapper lineMapper;
    private final EtaService etaService;

    public TaskService(TaskMapper taskMapper, VehicleMapper vehicleMapper,
                       LineMapper lineMapper, EtaService etaService) {
        this.taskMapper = taskMapper;
        this.vehicleMapper = vehicleMapper;
        this.lineMapper = lineMapper;
        this.etaService = etaService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task createDispatchTask(Long lineId, Long stationId, String direction, int priority,
                                   String reasonCode, String suggestion) {
        requireDirection(direction);
        String dispatchKey = dispatchKey(lineId, stationId, direction, reasonCode);
        if (dispatchKey != null) {
            Task existing = taskMapper.selectByDispatchKey(dispatchKey);
            if (existing != null) return existing;
        }
        Task task = buildTask("dispatch", lineId, stationId, direction, priority, reasonCode, suggestion);
        task.setDispatchKey(dispatchKey);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            Task existing = dispatchKey == null ? null : taskMapper.selectByDispatchKeyForUpdate(dispatchKey);
            if (existing == null) throw duplicate;
            return existing;
        }
        operationClock.task("created", task, null);
        Task assigned = assignTaskLocked(task.getId(), null);
        ShuttleWebSocketHandler.broadcast("task_new", assigned == null ? task : assigned);
        return assigned == null ? task : assigned;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task createPrepositionTask(Long lineId, Long stationId, String direction, int priority,
                                      String reasonCode, String suggestion) {
        requireDirection(direction);
        QueryWrapper<Task> query = new QueryWrapper<Task>();
        query.eq("line_id", lineId).eq("station_id", stationId).eq("type", "preposition")
             .eq("direction", direction).eq("status", "pending")
             .ge("created_at", operationClock.now().minusMinutes(10));
        if (taskMapper.selectCount(query) > 0) return null;
        Task task = buildTask("preposition", lineId, stationId, direction, priority, reasonCode, suggestion);
        taskMapper.insert(task);
        operationClock.task("created", task, null);
        ShuttleWebSocketHandler.broadcast("task_new", task);
        return task;
    }

    private Task buildTask(String type, Long lineId, Long stationId, String direction, int priority,
                           String reasonCode, String suggestion) {
        Task task = new Task();
        task.setTaskNo("T" + UUID.randomUUID().toString().replace("-", "").substring(0, 26));
        task.setType(type);
        task.setLineId(lineId);
        task.setStationId(stationId);
        task.setDirection(direction);
        task.setPriority(priority);
        task.setStatus("pending");
        task.setCreatedAt(operationClock.now());
        task.setUpdatedAt(operationClock.now());
        task.setReasonCode(reasonCode);
        task.setConfidence(java.math.BigDecimal.valueOf(0.80));
        task.setSuggestion(suggestion);
        return task;
    }

    private void requireDirection(String direction) {
        if (!"go".equals(direction) && !"back".equals(direction))
            throw new IllegalArgumentException("调度方向必须为 go 或 back");
    }

    private String dispatchKey(Long lineId, Long stationId, String direction, String reasonCode) {
        if (reasonCode == null || reasonCode.trim().isEmpty() || "MANUAL".equals(reasonCode)) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (lineId + "|" + stationId + "|" + direction + "|" + reasonCode).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte value : digest) key.append(String.format("%02x", value & 0xff));
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Task assignTask(Long taskId) {
        return assignTaskLocked(taskId, null);
    }

    private Task assignTaskLocked(Long taskId, Long excludedVehicleId) {
        Task task = taskMapper.selectForUpdate(taskId);
        if (task == null || !"pending".equals(task.getStatus())) return task;
        if (!"go".equals(task.getDirection()) && !"back".equals(task.getDirection()))
            return escalateLocked(task, "TASK_DIRECTION_MISSING", "任务缺少有效运行方向，已停止自动派车");
        Line line = lineMapper.selectById(task.getLineId());
        if (line == null) return escalateLocked(task, "LINE_NOT_FOUND", "任务线路不存在");

        QueryWrapper<Vehicle> query = new QueryWrapper<Vehicle>();
        query.eq("line_id", task.getLineId()).eq("running_today", 1)
             .eq("direction", task.getDirection()).in("status", "running", "standby");
        if (excludedVehicleId != null) query.ne("id", excludedVehicleId);
        List<Vehicle> candidates = new java.util.ArrayList<Vehicle>(vehicleMapper.selectList(query));
        candidates.removeIf(candidate -> !eligible(candidate, task));
        if (candidates.isEmpty()) return escalateLocked(task, "NO_RUNNING_VEHICLE", "没有符合条件的当日运行车辆");

        candidates.sort(Comparator.comparingDouble((Vehicle candidate) -> vehicleCost(candidate, task))
                .thenComparing(Vehicle::getId));
        Vehicle chosen = null;
        for (Vehicle candidate : candidates) {
            Vehicle locked = vehicleMapper.selectForUpdate(candidate.getId());
            if (!eligible(locked, task)) continue;
            locked.setStatus("task");
            vehicleMapper.updateById(locked);
            chosen = locked;
            break;
        }
        if (chosen == null) return escalateLocked(task, "NO_RUNNING_VEHICLE", "候选车辆已被其他任务占用");

        task.setVehicleId(chosen.getId());
        task.setStatus("assigned");
        task.setAssignedAt(operationClock.now());
        appendReason(task, chosen, candidates);
        taskMapper.updateById(task);
        operationClock.task(excludedVehicleId == null ? "assigned" : "reassigned", task, chosen.getId());
        ShuttleWebSocketHandler.broadcast("task_assigned", task);
        ShuttleWebSocketHandler.broadcast("vehicle_update", chosen);
        return task;
    }

    private void appendReason(Task task, Vehicle chosen, List<Vehicle> candidates) {
        int eta = travelSeconds(chosen, task);
        StringBuilder reason = new StringBuilder();
        Vehicle alternative = candidates.stream().filter(v -> !v.getId().equals(chosen.getId()))
                .min(Comparator.comparingDouble(v -> vehicleCost(v, task))).orElse(null);
        if (alternative != null) {
            int alternativeEta = travelSeconds(alternative, task);
            int alternativeLoad = alternative.getCapacity() != null && alternative.getCapacity() > 0
                    ? alternative.getCurrentLoad() * 100 / alternative.getCapacity() : 100;
            int chosenLoad = chosen.getCapacity() != null && chosen.getCapacity() > 0
                    ? chosen.getCurrentLoad() * 100 / chosen.getCapacity() : 100;
            reason.append(" | 反事实:若改派车辆").append(alternative.getPlateNo())
                  .append(",ETA").append(alternativeEta > eta ? "+" : "").append(alternativeEta - eta).append("秒")
                  .append(",负载率").append(alternativeLoad < chosenLoad ? "降" : "升")
                  .append(Math.abs(alternativeLoad - chosenLoad)).append("%");
        }
        reason.append(" | 综合成本=").append(String.format(java.util.Locale.ROOT, "%.3f", vehicleCost(chosen, task)))
              .append(" | 方向=").append(task.getDirection());
        if (task.getSuggestion() != null) task.setSuggestion(task.getSuggestion() + reason);
    }

    private int travelSeconds(Vehicle vehicle, Task task) {
        if (task.getStationId() != null && vehicle.getCurrentStationId() != null)
            return etaService.estimateTravelSec(vehicle.getCurrentStationId(), task.getStationId(),
                    task.getLineId(), task.getDirection());
        return 600;
    }

    private boolean eligible(Vehicle vehicle, Task task) {
        if (vehicle == null || vehicle.getRunningToday() == null || vehicle.getRunningToday() != 1
                || !task.getLineId().equals(vehicle.getLineId())
                || !task.getDirection().equals(vehicle.getDirection())) return false;
        if (!"running".equals(vehicle.getStatus()) && !"standby".equals(vehicle.getStatus())) return false;
        return vehicle.getCapacity() != null && vehicle.getCapacity() > 0
                && vehicle.getCurrentLoad() >= 0 && vehicle.getCurrentLoad() <= vehicle.getCapacity();
    }

    /** Lower is better: 40% ETA, 40% load, 20% operating state. */
    private double vehicleCost(Vehicle vehicle, Task task) {
        double loadRate = (double) vehicle.getCurrentLoad() / vehicle.getCapacity();
        double etaCost = Math.min(1.0, Math.max(0, travelSeconds(vehicle, task)) / 1200.0);
        double statusCost = "running".equals(vehicle.getStatus()) ? 0.0 : 0.5;
        return 0.4 * etaCost + 0.4 * loadRate + 0.2 * statusCost;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task acceptTask(Long taskId, Long vehicleId) {
        Task task = taskMapper.selectForUpdate(taskId);
        if (task == null || !"assigned".equals(task.getStatus()) || task.getVehicleId() == null
                || !task.getVehicleId().equals(vehicleId)) return null;
        task.setStatus("executing");
        taskMapper.updateById(task);
        operationClock.task("accepted", task, vehicleId);
        operationClock.task("executing", task, vehicleId);
        ShuttleWebSocketHandler.broadcast("task_update", task);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task rejectTask(Long taskId, Long vehicleId, String reason) {
        Task task = taskMapper.selectForUpdate(taskId);
        if (task == null || !"assigned".equals(task.getStatus()) || task.getVehicleId() == null
                || !task.getVehicleId().equals(vehicleId)) return null;
        Long oldVehicleId = task.getVehicleId();
        task.setStatus("pending");
        task.setVehicleId(null);
        task.setReasonCode("DRIVER_REJECTED");
        if (reason != null && !reason.trim().isEmpty()) task.setSuggestion((task.getSuggestion() == null ? "" : task.getSuggestion()) + " | 拒绝原因:" + reason.trim());
        taskMapper.updateById(task);
        Vehicle old = vehicleMapper.selectForUpdate(oldVehicleId);
        if (old != null && "task".equals(old.getStatus())) {
            old.setStatus("running");
            vehicleMapper.updateById(old);
        }
        operationClock.task("rejected", task, oldVehicleId);
        log.info("司机拒绝调度任务 taskNo={}", task.getTaskNo());
        Task reassigned = assignTaskLocked(taskId, oldVehicleId);
        ShuttleWebSocketHandler.broadcast("task_update", reassigned == null ? task : reassigned);
        return reassigned == null ? task : reassigned;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task completeTask(Long taskId) {
        return completeTask(taskId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Task completeTask(Long taskId, Long expectedVehicleId) {
        Task task = taskMapper.selectForUpdate(taskId);
        if (task == null || !"executing".equals(task.getStatus())) return null;
        if (expectedVehicleId != null && !expectedVehicleId.equals(task.getVehicleId())) return null;
        task.setStatus("completed");
        task.setDispatchKey(null);
        task.setCompletedAt(operationClock.now());
        operationClock.task("completed", task, task.getVehicleId());
        taskMapper.updateById(task);
        if (task.getVehicleId() != null) {
            Vehicle vehicle = vehicleMapper.selectForUpdate(task.getVehicleId());
            if (vehicle != null && "task".equals(vehicle.getStatus())) {
                vehicle.setStatus("running");
                vehicleMapper.updateById(vehicle);
                ShuttleWebSocketHandler.broadcast("vehicle_update", vehicle);
            }
        }
        ShuttleWebSocketHandler.broadcast("task_update", task);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task escalateTask(Long taskId, String reasonCode, String message) {
        Task task = taskMapper.selectForUpdate(taskId);
        if (task == null || "completed".equals(task.getStatus()) || "escalated".equals(task.getStatus())) return task;
        return escalateLocked(task, reasonCode, message);
    }

    private Task escalateLocked(Task task, String reasonCode, String message) {
        if (task.getVehicleId() != null) {
            Vehicle vehicle = vehicleMapper.selectForUpdate(task.getVehicleId());
            if (vehicle != null && "task".equals(vehicle.getStatus())) {
                vehicle.setStatus("running");
                vehicleMapper.updateById(vehicle);
            }
        }
        task.setStatus("escalated");
        if (operationClock.active()) task.setDispatchKey(null);
        task.setVehicleId(null);
        task.setReasonCode(reasonCode);
        task.setSuggestion((task.getSuggestion() == null ? "" : task.getSuggestion()) + " | 升级:" + message);
        taskMapper.updateById(task);
        operationClock.task("escalated", task, null);
        ShuttleWebSocketHandler.broadcast("task_escalated", task);
        return task;
    }

    public List<Task> pendingTasks() {
        QueryWrapper<Task> query = new QueryWrapper<Task>();
        query.in("status", "pending", "assigned", "executing").orderByDesc("priority").orderByAsc("created_at");
        return taskMapper.selectList(query);
    }

    public List<Task> allTasks(int limit) {
        QueryWrapper<Task> query = new QueryWrapper<Task>();
        query.orderByDesc("id").last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return taskMapper.selectList(query);
    }
}
