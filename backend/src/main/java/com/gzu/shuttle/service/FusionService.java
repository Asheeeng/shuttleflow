package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FusionService {
    private static final Logger log = LoggerFactory.getLogger(FusionService.class);

    @Autowired private StationQueueMapper queueMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private RequestMapper requestMapper;
    @Autowired private AiServiceClient aiClient;

    private static final double Q = 1.0;
    private static final double R_DEFAULT = 4.0;
    private final Map<String, Double> pMap = new ConcurrentHashMap<>();

    private String qKey(Long sid, Long lid, String dir) { return sid + "_" + lid + "_" + dir; }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> observationUpdate(Long stationId, Long lineId, String direction,
                                                  int observedCount, double observationVariance, String source) {
        Map<String, Object> aiResult = aiClient.kalmanFuse(stationId, lineId, direction, observedCount, observationVariance, source);
        if (aiResult != null && aiResult.containsKey("data")) {
            Object data = aiResult.get("data");
            if (data instanceof Map) {
                Map<String, Object> aiData = (Map<String, Object>) data;
                aiData.put("source", "ai_service");
                updateQueueFromFusion(stationId, lineId, direction,
                        ((Number) aiData.getOrDefault("fusedCount", observedCount)).intValue(),
                        aiData.get("confidence") != null ? new BigDecimal(aiData.get("confidence").toString()) : null);
                ShuttleWebSocketHandler.broadcast("kalman_fusion", aiData);
                return aiData;
            }
        }

        String key = qKey(stationId, lineId, direction);
        QueryWrapper<StationQueue> qw = new QueryWrapper<>();
        qw.eq("station_id", stationId).eq("line_id", lineId).eq("direction", direction);
        StationQueue q = queueMapper.selectForUpdate(stationId, lineId, direction);

        double xPred = q != null ? q.getWaitingCount() : 0;
        double pPred = pMap.getOrDefault(key, 10.0) + Q;
        double R = observationVariance > 0 ? observationVariance : R_DEFAULT;
        double K = pPred / (pPred + R);
        double xUpdated = xPred + K * (observedCount - xPred);
        double pUpdated = (1 - K) * pPred;
        pMap.put(key, pUpdated);

        int fusedCount = Math.max(0, (int) Math.round(xUpdated));
        double confidence = 1.0 / (1.0 + pUpdated);

        if (q == null) {
            q = new StationQueue();
            q.setStationId(stationId); q.setLineId(lineId); q.setDirection(direction);
            q.setWaitingCount(fusedCount);
            q.setConfidence(BigDecimal.valueOf(confidence));
            q.setLastUpdateTime(LocalDateTime.now());
            queueMapper.insert(q);
        } else {
            q.setWaitingCount(fusedCount);
            q.setConfidence(BigDecimal.valueOf(confidence));
            q.setLastUpdateTime(LocalDateTime.now());
            queueMapper.updateById(q);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("stationId", stationId);
        result.put("source", source);
        result.put("observedCount", observedCount);
        result.put("previousEstimate", (int)xPred);
        result.put("fusedCount", fusedCount);
        result.put("kalmanGain", String.format("%.3f", K));
        result.put("confidence", String.format("%.3f", confidence));
        result.put("varianceP", String.format("%.3f", pUpdated));
        ShuttleWebSocketHandler.broadcast("kalman_fusion", result);
        return result;
    }

    private void updateQueueFromFusion(Long stationId, Long lineId, String direction, int fusedCount, BigDecimal confidence) {
        queueMapper.setAbsolute(stationId, lineId, direction, Math.max(0, fusedCount),
                confidence != null ? confidence : BigDecimal.ONE);
    }

    void onCheckin(ShuttleEvent event) {
        updateQueue(event.getStationId(), event.getLineId(), event.getDirection(), 1, event.getConfidence());
        ShuttleWebSocketHandler.broadcast("queue_update", getQueueSnapshot(event.getStationId(), event.getLineId(), event.getDirection()));
    }

    void onCancel(ShuttleEvent event) {
        updateQueue(event.getStationId(), event.getLineId(), event.getDirection(), -1, event.getConfidence());
        ShuttleWebSocketHandler.broadcast("queue_update", getQueueSnapshot(event.getStationId(), event.getLineId(), event.getDirection()));
    }

    void onArrive(ShuttleEvent event) {
        Vehicle v = vehicleMapper.selectForUpdate(event.getVehicleId());
        if (v == null) return;
        v.setCurrentStationId(event.getStationId());
        v.setDirection(event.getDirection());
        v.setLastReportTime(LocalDateTime.now());
        if ("standby".equals(v.getStatus()) || "offline".equals(v.getStatus())) v.setStatus("running");
        vehicleMapper.updateById(v);
        ShuttleWebSocketHandler.broadcast("vehicle_update", v);
    }

    void onAlight(ShuttleEvent event) {
        Vehicle v = vehicleMapper.selectForUpdate(event.getVehicleId());
        if (v == null) return;
        int count = event.getCount() != null ? Math.abs(event.getCount()) : 0;
        int nextLoad = event.getAfterLoad() != null ? event.getAfterLoad() : v.getCurrentLoad() - count;
        if (nextLoad < 0 || nextLoad > v.getCapacity())
            throw new IllegalStateException("下客后人数超出车辆容量范围");
        v.setCurrentLoad(nextLoad);
        v.setLastReportTime(LocalDateTime.now());
        vehicleMapper.updateById(v);
        ShuttleWebSocketHandler.broadcast("vehicle_update", v);
    }

    void onBoard(ShuttleEvent event) {
        Vehicle v = vehicleMapper.selectForUpdate(event.getVehicleId());
        if (v == null) return;
        int count = event.getCount() != null ? event.getCount() : 0;
        int newLoad = event.getAfterLoad() != null ? event.getAfterLoad() : v.getCurrentLoad() + count;
        if (count < 0 || newLoad < v.getCurrentLoad() || newLoad > v.getCapacity())
            throw new IllegalStateException("上客后人数超出车辆容量范围");
        v.setCurrentLoad(newLoad);
        v.setLastReportTime(LocalDateTime.now());
        vehicleMapper.updateById(v);
        if (event.getStationId() != null && event.getLineId() != null) {
            updateQueue(event.getStationId(), event.getLineId(), event.getDirection(), -count, event.getConfidence());
            if (count > 0) requestMapper.markBoardedAtStation(event.getStationId(), event.getLineId(), event.getDirection(), count);
            ShuttleWebSocketHandler.broadcast("queue_update",
                    getQueueSnapshot(event.getStationId(), event.getLineId(), event.getDirection()));
        }
        ShuttleWebSocketHandler.broadcast("vehicle_update", v);
    }

    void onDepart(ShuttleEvent event) {
        Vehicle v = vehicleMapper.selectForUpdate(event.getVehicleId());
        if (v == null) return;
        v.setLastReportTime(LocalDateTime.now());
        vehicleMapper.updateById(v);
        ShuttleWebSocketHandler.broadcast("vehicle_update", v);
    }

    void onCalibrate(ShuttleEvent event) {
        Vehicle v = vehicleMapper.selectForUpdate(event.getVehicleId());
        if (v == null) return;
        if (event.getAfterLoad() != null) {
            if (event.getAfterLoad() < 0 || event.getAfterLoad() > v.getCapacity())
                throw new IllegalStateException("校准人数超出车辆核定容量");
            v.setCurrentLoad(event.getAfterLoad());
        }
        v.setLastReportTime(LocalDateTime.now());
        vehicleMapper.updateById(v);
        ShuttleWebSocketHandler.broadcast("vehicle_update", v);
    }

    private void updateQueue(Long stationId, Long lineId, String direction, int delta, BigDecimal confidence) {
        if (stationId == null || lineId == null || direction == null) return;
        String key = qKey(stationId, lineId, direction);
        double pPred = pMap.getOrDefault(key, 10.0) + Q;
        pMap.put(key, pPred);
        double conf = 1.0 / (1.0 + pPred);
        queueMapper.applyDelta(stationId, lineId, direction, delta, BigDecimal.valueOf(conf));
    }

    private Map<String, Object> getQueueSnapshot(Long stationId, Long lineId, String direction) {
        QueryWrapper<StationQueue> qw = new QueryWrapper<>();
        qw.eq("station_id", stationId).eq("line_id", lineId).eq("direction", direction);
        StationQueue q = queueMapper.selectOne(qw);
        Map<String, Object> m = new HashMap<>();
        m.put("stationId", stationId); m.put("lineId", lineId); m.put("direction", direction);
        if (q != null) { m.put("waitingCount", q.getWaitingCount()); m.put("confidence", q.getConfidence()); }
        return m;
    }

    public List<StationQueue> allQueues() {
        return queueMapper.selectList(null);
    }

    public List<Map<String, Object>> queueDetail() {
        List<StationQueue> queues = allQueues();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StationQueue q : queues) {
            Map<String, Object> m = new HashMap<>();
            m.put("stationId", q.getStationId());
            m.put("lineId", q.getLineId());
            m.put("direction", q.getDirection());
            m.put("waitingCount", q.getWaitingCount());
            m.put("confidence", q.getConfidence());
            m.put("lastUpdateTime", q.getLastUpdateTime());
            result.add(m);
        }
        return result;
    }

    private final Map<Long, Long> overloadStartMap = new ConcurrentHashMap<>();
    private final Set<Long> overloadedSet = ConcurrentHashMap.newKeySet();

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 1000)
    public void checkOverloadHysteresis() {
        List<Vehicle> vehicles = vehicleMapper.selectList(
            new QueryWrapper<Vehicle>().eq("running_today", 1).ne("status", "offline"));
        long now = System.currentTimeMillis();
        for (Vehicle v : vehicles) {
            if (v.getCapacity() == null || v.getCapacity() == 0) continue;
            int rate = v.getCurrentLoad() * 100 / v.getCapacity();
            if (rate >= 95) {
                if (!overloadStartMap.containsKey(v.getId())) overloadStartMap.put(v.getId(), now);
                if (now - overloadStartMap.get(v.getId()) >= 10000 && !overloadedSet.contains(v.getId())) {
                    overloadedSet.add(v.getId());
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("vehicleId", v.getId());
                    alert.put("plateNo", v.getPlateNo());
                    alert.put("loadRate", rate);
                    alert.put("status", "OVERLOADED");
                    alert.put("reasonCode", "HYSTERESIS_95_10s");
                    ShuttleWebSocketHandler.broadcast("overload_alert", alert);
                }
            } else if (rate < 85) {
                overloadStartMap.remove(v.getId());
                if (overloadedSet.remove(v.getId())) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("vehicleId", v.getId());
                    alert.put("plateNo", v.getPlateNo());
                    alert.put("loadRate", rate);
                    alert.put("status", "OVERLOAD_RELEASED");
                    alert.put("reasonCode", "HYSTERESIS_85");
                    ShuttleWebSocketHandler.broadcast("overload_alert", alert);
                }
            }
        }
    }

    public Set<Long> getOverloadedVehicles() { return overloadedSet; }
}
