package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.Alert;
import com.gzu.shuttle.mapper.AlertMapper;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertService {
    @org.springframework.beans.factory.annotation.Autowired private OperationClock operationClock = new OperationClock();
    @Autowired private AlertMapper alertMapper;

    public Alert createAlert(String level, String type, Long stationId, Long vehicleId, Long lineId, String message) {
        QueryWrapper<Alert> qw = new QueryWrapper<>();
        qw.eq("station_id", stationId).eq("type", type).eq("level", level).eq("line_id", lineId).eq("status", "pending")
          .ge("created_at", operationClock.now().minusMinutes(5));
        if (alertMapper.selectCount(qw) > 0) return null;
        Alert a = new Alert();
        a.setLevel(level);
        a.setType(type);
        a.setStationId(stationId);
        a.setVehicleId(vehicleId);
        a.setLineId(lineId);
        a.setMessage(message);
        a.setStatus("pending");
        a.setCreatedAt(operationClock.now());
        alertMapper.insert(a);
        ShuttleWebSocketHandler.broadcast("alert", a);
        return a;
    }

    public List<Alert> pendingAlerts() {
        QueryWrapper<Alert> qw = new QueryWrapper<>();
        qw.eq("status", "pending").orderByDesc("created_at");
        return alertMapper.selectList(qw);
    }

    public List<Alert> recentAlerts(int limit) {
        QueryWrapper<Alert> qw = new QueryWrapper<>();
        qw.orderByDesc("id").last("LIMIT " + Math.min(limit, 100));
        return alertMapper.selectList(qw);
    }

    public Alert confirm(Long id) { return updateStatus(id, "confirmed"); }
    public Alert suppress(Long id) { return updateStatus(id, "suppressed"); }
    public Alert resolve(Long id) {
        Alert a = updateStatus(id, "resolved");
        if (a != null) { a.setResolvedAt(operationClock.now()); alertMapper.updateById(a); }
        return a;
    }

    private Alert updateStatus(Long id, String status) {
        Alert a = alertMapper.selectById(id);
        if (a != null) { a.setStatus(status); alertMapper.updateById(a); }
        return a;
    }
}