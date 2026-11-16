package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CUSUM突变检测服务
 * St = max{0, St-1 + (xt - μ0 - k)}，St > h 时报警
 * 1分钟粒度，融合签到/视觉/司机上报得到到达率xt
 */
@Service
public class SurgeDetectionService {
    private static final Logger log = LoggerFactory.getLogger(SurgeDetectionService.class);

    @Autowired private ShuttleEventMapper eventMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private AlertService alertService;
    @Autowired private TaskService taskService;

    private static final double K = 1.5;
    private static final double H_YELLOW = 5.0;
    private static final double H_ORANGE = 10.0;
    private static final double H_RED = 20.0;

    private static class CusumState {
        double St = 0;
        double mu0 = 2.0;
        int lastEventCount = 0;
        LocalDateTime lastCheck = LocalDateTime.now();
    }

    private final Map<String, CusumState> stateMap = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 60000)
    public void detect() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(1);
        List<ShuttleEvent> events = eventMapper.selectList(
            new QueryWrapper<ShuttleEvent>().ge("event_time", since).eq("processed", 1));
        Map<String, Integer> stationArrivalRate = new HashMap<>();
        for (ShuttleEvent e : events) {
            if (e.getStationId() == null) continue;
            String key = e.getStationId() + "_" + (e.getLineId() != null ? e.getLineId() : "0") + "_" + (e.getDirection() != null ? e.getDirection() : "go");
            stationArrivalRate.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : stationArrivalRate.entrySet()) {
            String key = entry.getKey();
            int xt = entry.getValue();
            CusumState s = stateMap.computeIfAbsent(key, k -> new CusumState());
            s.St = Math.max(0, s.St + (xt - s.mu0 - K));
            if (xt > 0) s.mu0 = s.mu0 * 0.9 + xt * 0.1;
            String[] parts = key.split("_");
            Long stationId = Long.valueOf(parts[0]);
            Long lineId = Long.valueOf(parts[1]);
            String direction = parts[2];

            if (s.St >= H_RED) {
                alertService.createAlert("red", "surge_cusum", stationId, null, lineId,
                    "CUSUM突变检测:St=" + String.format("%.1f", s.St) + " 超红色阈值 到达率" + xt + "/min");
                taskService.createDispatchTask(lineId, stationId, direction, 9, "CUSUM_RED",
                    "CUSUM红色突变:到达率" + xt + "/min,St=" + String.format("%.1f", s.St) + " 建议立即加车");
                s.St = 0;
            } else if (s.St >= H_ORANGE) {
                alertService.createAlert("orange", "surge_cusum", stationId, null, lineId,
                    "CUSUM突变检测:St=" + String.format("%.1f", s.St) + " 超橙色阈值");
                taskService.createDispatchTask(lineId, stationId, direction, 7, "CUSUM_ORANGE",
                    "CUSUM橙色突变:到达率" + xt + "/min 建议加车");
            } else if (s.St >= H_YELLOW) {
                alertService.createAlert("yellow", "surge_cusum", stationId, null, lineId,
                    "CUSUM突变检测:St=" + String.format("%.1f", s.St) + " 超黄色阈值");
            }
        }
        Map<String, Object> wsData = new HashMap<>();
        for (Map.Entry<String, CusumState> e : stateMap.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("St", e.getValue().St);
            m.put("mu0", e.getValue().mu0);
            wsData.put(e.getKey(), m);
        }
        ShuttleWebSocketHandler.broadcast("cusum_status", wsData);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, CusumState> e : stateMap.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("St", String.format("%.2f", e.getValue().St));
            m.put("mu0", String.format("%.2f", e.getValue().mu0));
            result.put(e.getKey(), m);
        }
        return result;
    }
}