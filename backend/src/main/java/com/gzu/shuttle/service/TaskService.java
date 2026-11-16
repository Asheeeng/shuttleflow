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
import java.util.Collections;
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
        String dispatchKey = dispatchKey(lineId, stationId, reasonCode);
        if (dispatchKey != null) {
            Task existing = taskMapper.selectByDispatchKey(dispatchKey);
            if (existing != null) return existing;
        }
        Task task = buildTask("dispatch", lineId, stationId, priority, reasonCode, suggestion);
        task.setDispatchKey(dispatchKey);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            Task existing = dispatchKey == null ? null : taskMapper.selectByDispatchKeyForUpdate(dispatchKey);
            if (existing == null) throw duplicate;
            return existing;
        }
        Task assigned = assignTaskLocked(task.getId(), null);
        ShuttleWebSocketHandler.broadcast("task_new", assigned == null ? task : assigned);
        return assigned == null ? task : assigned;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task createPrepositionTask(Long lineId, Long stationId, String direction, int priority,
                                      String reasonCode, String suggestion) {
        QueryWrapper<Task> query = new QueryWrapper<Task>();
        query.eq("line_id", lineId).eq("station_id", stationId).eq("type", "preposition")
             .eq("status", "pending").ge("created_at", LocalDateTime.now().minusMinutes(10));
        if (taskMapper.selectCount(query) > 0) return null;
        Task task = buildTask("preposition", lineId, stationId, priority, reasonCode, suggestion);
        taskMapper.insert(task);
        ShuttleWebSocketHandler.broadcast("task_new", task);
        return task;
    }

    private Task buildTask(String type, Long lineId, Long stationId, int priority,
                           String reasonCode, String suggestion) {
        Task task = new Task();
        task.setTaskNo("T" + UUID.randomUUID().toString().replace("-", "").substring(0, 26));
        task.setType(type);
        task.setLineId(lineId);
        task.setStationId(stationId);
        task.setPriority(priority);
        task.setStatus("pending");
        task.setReasonCode(reasonCode);
        task.setConfidence(java.math.BigDecimal.valueOf(0.80));
        task.setSuggestion(suggestion);
        return task;
    }

    private String dispatchKey(Long lineId, Long stationId, String reasonCode) {
        if (reasonCode == null || reasonCode.trim().isEmpty() || "MANUAL".equals(reasonCode)) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (lineId + "|" + stationId + "|" + reasonCode).getBytes(StandardCharsets.UTF_8));
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
        Line line = lineMapper.selectById(task.getLineId());
        if (line == null) return escalateLocked(task, "LINE_NOT_FOUND", "任务线路不存在");

        QueryWrapper<Vehicle> query = new QueryWrapper<Vehicle>();
        query.eq("line_id", task.getLineId()).eq("running_today", 1)
             .ne("status", "task").ne("status", "offline");
        if (excludedVehicleId != null) query.ne("id", excludedVehicleId);
        List<Vehicle> candidates = vehicleMapper.selectList(query);
        if (candidates.isEmpty()) return escalateLocked(task, "NO_RUNNING_VEHICLE", "没有符合条件的当日运行车辆");

        Collections.sort(candidates, (a, b) -> Double.compare(scoreVehicle(a, task), scoreVehicle(b, task)));
        Vehicle chosen = null;
        for (Vehicle candidate : candidates) {
            Vehicle locked = vehicleMapper.selectForUpdate(candidate.getId());
            if (locked == null || locked.getRunningToday() == null || locked.getRunningToday() != 1
                    || "task".equals(locked.getStatus()) || "offline".equals(locked.getStatus())) continue;
            locked.setStatus("task");
            vehicleMapper.updateById(locked);
            chosen = locked;
            break;
        }
        if (chosen == null) return escalateLocked(task, "NO_RUNNING_VEHICLE", "候选车辆已被其他任务占用");

        task.setVehicleId(chosen.getId());
        task.setStatus("assigned");
        task.setAssignedAt(LocalDateTime.now());
        appendReason(task, chosen, candidates);
        taskMapper.updateById(task);
        ShuttleWebSocketHandler.broadcast("task_assigned", task);
        ShuttleWebSocketHandler.broadcast("vehicle_update", chosen);
        return task;
    }

    private void appendReason(Task task, Vehicle chosen, List<Vehicle> candidates) {
        int eta = travelSeconds(chosen, task);
        StringBuilder reason = new StringBuilder();
        if (candidates.size() > 1) {
            Vehicle alternative = candidates.get(1);
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
        reason.append(" | 评分=").append(String.format("%.3f", scoreVehicle(chosen, task)));
        if (task.getSuggestion() != null) task.setSuggestion(task.getSuggestion() + reason);
    }

    private int travelSeconds(Vehicle vehicle, Task task) {
        if (task.getStationId() != null && vehicle.getCurrentStationId() != null)
            return etaService.estimateTravelSec(vehicle.getCurrentStationId(), task.getStationId(), task.getLineId());
        return 600;
    }

    private double scoreVehicle(Vehicle vehicle, Task task) {
        double loadRate = vehicle.getCapacity() != null && vehicle.getCapacity() > 0
                ? (double) vehicle.getCurrentLoad() / vehicle.getCapacity() : 1.0;
        int etaSec = travelSeconds(vehicle, task);
        double w1 = 0.4, w2 = 0.4, w3 = 0.2;
        return w1 * (1.0 - loadRate) + w2 * (1.0 / (1.0 + etaSec / 600.0)) + w3;
    }

    @Transactional(rollbackFor = Exception.class)
    public Task acceptTask(Long taskId, Long vehicleId) {
        Task task = taskMapper.selectForUpdate(taskId);
        if (task == null || !"assigned".equals(task.getStatus()) || task.getVehicleId() == null
                || !task.getVehicleId().equals(vehicleId)) return null;
        task.setStatus("executing");
        taskMapper.updateById(task);
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
        task.setCompletedAt(LocalDateTime.now());
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
        task.setVehicleId(null);
        task.setReasonCode(reasonCode);
        task.setSuggestion((task.getSuggestion() == null ? "" : task.getSuggestion()) + " | 升级:" + message);
        taskMapper.updateById(task);
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
