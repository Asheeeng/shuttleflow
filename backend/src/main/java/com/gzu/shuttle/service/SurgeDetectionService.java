package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import javax.annotation.PostConstruct;
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
    @Value("${shuttle.cusum.scheduler-enabled:true}") private boolean schedulerEnabled;

    private static final double K = 1.5;
    private static final double H_YELLOW = 5.0;
    private static final double H_ORANGE = 10.0;
    private static final double H_RED = 20.0;

    private static class CusumState {
        double St = 0;
        double mu0 = 2.0;
        String lastLevel;
    }

    private final Map<String, CusumState> stateMap = new ConcurrentHashMap<>();
    private volatile Long lastProcessedEventId = 0L;

    @PostConstruct
    public void initializeEventCursor() {
        QueryWrapper<ShuttleEvent> latestQuery = new QueryWrapper<ShuttleEvent>()
                .orderByDesc("id").last("LIMIT 1");
        ShuttleEvent latest = eventMapper.selectOne(latestQuery);
        if (latest != null && latest.getId() != null) lastProcessedEventId = latest.getId();
    }

    public synchronized void resetSimulationWindow() {
        stateMap.clear();
        initializeEventCursor();
    }

    @Scheduled(fixedRate = 60000)
    public void detect() {
        if (schedulerEnabled) detectNow(60);
    }

    /** 仿真环境显式推进同一检测逻辑；仅在受保护的本地测试profile开放HTTP入口。 */
    public synchronized Map<String, Object> detectNow() {
        return detectNow(60);
    }

    public synchronized Map<String, Object> detectNow(int intervalSeconds) {
        if (intervalSeconds < 1 || intervalSeconds > 900)
            throw new IllegalArgumentException("CUSUM采样窗口必须为1至900秒");
        List<ShuttleEvent> events = eventMapper.selectList(
            new QueryWrapper<ShuttleEvent>().gt("id", lastProcessedEventId)
                    .eq("processed", 1).orderByAsc("id"));
        Map<String, Integer> stationArrivalRate = new HashMap<>();
        long maxSeenEventId = lastProcessedEventId;
        for (ShuttleEvent e : events) {
            if (e.getId() != null && e.getId() > maxSeenEventId) maxSeenEventId = e.getId();
            if (!"checkin".equals(e.getType())) continue;
            if (e.getStationId() == null) continue;
            String key = e.getStationId() + "_" + (e.getLineId() != null ? e.getLineId() : "0") + "_" + (e.getDirection() != null ? e.getDirection() : "go");
            stationArrivalRate.merge(key, 1, Integer::sum);
        }
        Set<String> observedKeys = new HashSet<String>(stateMap.keySet());
        observedKeys.addAll(stationArrivalRate.keySet());
        for (String key : observedKeys) {
            double xt = stationArrivalRate.getOrDefault(key, 0) * 60.0 / intervalSeconds;
            CusumState s = stateMap.computeIfAbsent(key, k -> new CusumState());
            s.St = Math.max(0, s.St + (xt - s.mu0 - K));
            String[] parts = key.split("_");
            Long stationId = Long.valueOf(parts[0]);
            Long lineId = Long.valueOf(parts[1]);
            String direction = parts[2];

            String level = s.St >= H_RED ? "red" : s.St >= H_ORANGE ? "orange" : s.St >= H_YELLOW ? "yellow" : null;
            int levelRank = "red".equals(level) ? 3 : "orange".equals(level) ? 2 : "yellow".equals(level) ? 1 : 0;
            int previousRank = "red".equals(s.lastLevel) ? 3 : "orange".equals(s.lastLevel) ? 2 : "yellow".equals(s.lastLevel) ? 1 : 0;
            if (levelRank > previousRank && levelRank == 3) {
                alertService.createAlert("red", "surge_cusum", stationId, null, lineId,
                    "CUSUM突变检测:St=" + String.format("%.1f", s.St) + " 超红色阈值 到达率" + String.format("%.1f", xt) + "/min");
                taskService.createDispatchTask(lineId, stationId, direction, 9, "CUSUM_RED",
                    "CUSUM红色突变:到达率" + String.format("%.1f", xt) + "/min,St=" + String.format("%.1f", s.St) + " 建议立即加车");
                s.lastLevel = null;
                s.St = 0;
            } else if (levelRank > previousRank && levelRank == 2) {
                alertService.createAlert("orange", "surge_cusum", stationId, null, lineId,
                    "CUSUM突变检测:St=" + String.format("%.1f", s.St) + " 超橙色阈值");
                taskService.createDispatchTask(lineId, stationId, direction, 7, "CUSUM_ORANGE",
                    "CUSUM橙色突变:到达率" + String.format("%.1f", xt) + "/min 建议加车");
                s.lastLevel = "orange";
            } else if (levelRank > previousRank && levelRank == 1) {
                alertService.createAlert("yellow", "surge_cusum", stationId, null, lineId,
                    "CUSUM突变检测:St=" + String.format("%.1f", s.St) + " 超黄色阈值");
                s.lastLevel = "yellow";
            } else if (level == null) {
                s.lastLevel = null;
            }
        }
        Map<String, Object> wsData = new HashMap<>();
        for (Map.Entry<String, CusumState> e : stateMap.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("St", e.getValue().St);
            m.put("mu0", e.getValue().mu0);
            wsData.put(e.getKey(), m);
        }
        lastProcessedEventId = maxSeenEventId;
        ShuttleWebSocketHandler.broadcast("cusum_status", wsData);
        Map<String, Object> summary = new HashMap<String, Object>();
        summary.put("processedEvents", events.size());
        summary.put("processedThroughEventId", maxSeenEventId);
        summary.put("stations", wsData);
        return summary;
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
