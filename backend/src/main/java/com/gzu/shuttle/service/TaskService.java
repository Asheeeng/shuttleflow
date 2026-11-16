package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    @Autowired private TaskMapper taskMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private LineMapper lineMapper;
    @Autowired private EtaService etaService;

    public Task createDispatchTask(Long lineId, Long stationId, String direction, int priority,
                                    String reasonCode, String suggestion) {
        Task t = buildTask("dispatch", lineId, stationId, priority, reasonCode, suggestion);
        t = taskMapper.insert(t) > 0 ? t : null;
        if (t != null) {
            assignTask(t.getId());
            ShuttleWebSocketHandler.broadcast("task_new", t);
        }
        return t;
    }

    public Task createPrepositionTask(Long lineId, Long stationId, String direction, int priority,
                                       String reasonCode, String suggestion) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.eq("line_id", lineId).eq("station_id", stationId).eq("type", "preposition")
          .eq("status", "pending").ge("created_at", LocalDateTime.now().minusMinutes(10));
        if (taskMapper.selectCount(qw) > 0) return null;
        Task t = buildTask("preposition", lineId, stationId, priority, reasonCode, suggestion);
        taskMapper.insert(t);
        ShuttleWebSocketHandler.broadcast("task_new", t);
        return t;
    }

    private Task buildTask(String type, Long lineId, Long stationId, int priority, String reasonCode, String suggestion) {
        Task t = new Task();
        t.setTaskNo("T" + System.currentTimeMillis());
        t.setType(type);
        t.setLineId(lineId);
        t.setStationId(stationId);
        t.setPriority(priority);
        t.setStatus("pending");
        t.setReasonCode(reasonCode);
        t.setConfidence(java.math.BigDecimal.valueOf(0.80));
        t.setSuggestion(suggestion);
        return t;
    }

    public Task assignTask(Long taskId) {
        Task t = taskMapper.selectById(taskId);
        if (t == null || !"pending".equals(t.getStatus())) return t;
        Line line = lineMapper.selectById(t.getLineId());
        if (line == null) return t;

        QueryWrapper<Vehicle> vqw = new QueryWrapper<>();
        vqw.eq("line_id", t.getLineId()).eq("running_today", 1)
           .ne("status", "task").ne("status", "offline");
        List<Vehicle> candidates = vehicleMapper.selectList(vqw);
        if (candidates.isEmpty()) {
            vqw = new QueryWrapper<>();
            vqw.eq("line_id", t.getLineId()).eq("running_today", 1);
            candidates = vehicleMapper.selectList(vqw);
        }
        if (candidates.isEmpty()) {
            escalateTask(taskId, "NO_RUNNING_VEHICLE", "当日无运行车辆可派");
            return t;
        }

        candidates.sort((a, b) -> {
            double sa = scoreVehicle(a, t);
            double sb = scoreVehicle(b, t);
            return Double.compare(sa, sb);
        });
        Vehicle best = candidates.get(0);
        t.setVehicleId(best.getId());
        t.setStatus("assigned");
        t.setAssignedAt(LocalDateTime.now());
        double bestScore = scoreVehicle(best, t);
        int bestEta = t.getStationId() != null && best.getCurrentStationId() != null ?
            etaService.estimateTravelSec(best.getCurrentStationId(), t.getStationId(), t.getLineId()) : 600;
        StringBuilder cf = new StringBuilder();
        if (candidates.size() > 1) {
            Vehicle alt = candidates.get(1);
            int altEta = t.getStationId() != null && alt.getCurrentStationId() != null ?
                etaService.estimateTravelSec(alt.getCurrentStationId(), t.getStationId(), t.getLineId()) : 600;
            int altLoad = alt.getCapacity() > 0 ? alt.getCurrentLoad() * 100 / alt.getCapacity() : 100;
            int bestLoad = best.getCapacity() > 0 ? best.getCurrentLoad() * 100 / best.getCapacity() : 100;
            cf.append(" | 反事实:若改派车辆").append(alt.getPlateNo())
              .append(",ETA").append(altEta > bestEta ? "+" : "").append(altEta - bestEta).append("秒")
              .append(",负载率").append(altLoad < bestLoad ? "降" : "升").append(Math.abs(altLoad - bestLoad)).append("%");
        }
        cf.append(" | 评分=").append(String.format("%.3f", bestScore));
        if (t.getSuggestion() != null) t.setSuggestion(t.getSuggestion() + cf.toString());
        taskMapper.updateById(t);
        best.setStatus("task");
        vehicleMapper.updateById(best);
        ShuttleWebSocketHandler.broadcast("task_assigned", t);
        ShuttleWebSocketHandler.broadcast("vehicle_update", best);
        return t;
    }

    private double scoreVehicle(Vehicle v, Task t) {
        double loadRate = v.getCapacity() > 0 ? (double) v.getCurrentLoad() / v.getCapacity() : 1.0;
        int etaSec = 600;
        if (t.getStationId() != null && v.getCurrentStationId() != null) {
            etaSec = etaService.estimateTravelSec(v.getCurrentStationId(), t.getStationId(), t.getLineId());
        }
        double w1 = 0.4, w2 = 0.4, w3 = 0.2;
        return w1 * (1.0 - loadRate) + w2 * (1.0 / (1.0 + etaSec / 600.0)) + w3 * 1.0;
    }

    public Task acceptTask(Long taskId, Long vehicleId) {
        Task t = taskMapper.selectById(taskId);
        if (t == null) return null;
        t.setStatus("executing");
        t.setVehicleId(vehicleId);
        taskMapper.updateById(t);
        ShuttleWebSocketHandler.broadcast("task_update", t);
        return t;
    }

    public Task rejectTask(Long taskId, String reason) {
        Task t = taskMapper.selectById(taskId);
        if (t == null) return null;
        t.setStatus("rejected");
        taskMapper.updateById(t);
        log.info("Task {} rejected: {}", taskId, reason);
        QueryWrapper<Vehicle> vqw = new QueryWrapper<>();
        vqw.eq("line_id", t.getLineId()).eq("running_today", 1)
           .ne("id", t.getVehicleId()).ne("status", "task").ne("status", "offline");
        List<Vehicle> remaining = vehicleMapper.selectList(vqw);
        if (remaining.isEmpty()) {
            escalateTask(taskId, "ALL_REJECTED", "所有当日运行车辆均拒绝/超时");
        } else {
            t.setStatus("pending");
            t.setVehicleId(null);
            taskMapper.updateById(t);
            assignTask(taskId);
        }
        ShuttleWebSocketHandler.broadcast("task_update", t);
        return t;
    }

    public Task completeTask(Long taskId) {
        Task t = taskMapper.selectById(taskId);
        if (t == null) return null;
        t.setStatus("completed");
        t.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(t);
        if (t.getVehicleId() != null) {
            Vehicle v = vehicleMapper.selectById(t.getVehicleId());
            if (v != null) { v.setStatus("running"); vehicleMapper.updateById(v); }
        }
        ShuttleWebSocketHandler.broadcast("task_update", t);
        return t;
    }

    public Task escalateTask(Long taskId, String reasonCode, String message) {
        Task t = taskMapper.selectById(taskId);
        if (t == null) return null;
        t.setStatus("escalated");
        t.setReasonCode(reasonCode);
        t.setSuggestion(t.getSuggestion() + " | 升级:" + message);
        taskMapper.updateById(t);
        ShuttleWebSocketHandler.broadcast("task_escalated", t);
        return t;
    }

    public List<Task> pendingTasks() {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.in("status", "pending", "assigned", "executing").orderByDesc("priority").orderByAsc("created_at");
        return taskMapper.selectList(qw);
    }

    public List<Task> allTasks(int limit) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        qw.orderByDesc("id").last("LIMIT " + Math.min(limit, 200));
        return taskMapper.selectList(qw);
    }
}