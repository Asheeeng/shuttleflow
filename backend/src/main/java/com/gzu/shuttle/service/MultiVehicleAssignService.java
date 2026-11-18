package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多车协同分配服务
 * 核心洞察："满载"是到站前状态，"可用座位"是下客后状态
 * 到站拆5原子事件：到站→下客→座位释放→上客→离站
 * sb,i = Cb - nb⁺ (下客后可用座位)
 * 按可用座位/下游压力/等待时长排序分配，给下游预留座位(上限40%)
 * 超时降级为"先到先上+固定预留"
 */
@Service
public class MultiVehicleAssignService {
    private static final Logger log = LoggerFactory.getLogger(MultiVehicleAssignService.class);

    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private StationQueueMapper queueMapper;
    @Autowired private LineStationMapper lsMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private AiServiceClient aiClient;

    public Map<String, Object> assign(Long stationId, Long lineId, String direction) {
        Map<String, Object> result = new HashMap<>();
        result.put("stationId", stationId);
        result.put("lineId", lineId);
        result.put("direction", direction);

        QueryWrapper<StationQueue> qw = new QueryWrapper<>();
        qw.eq("station_id", stationId).eq("line_id", lineId).eq("direction", direction);
        StationQueue queue = queueMapper.selectOne(qw);
        int waiting = queue != null ? queue.getWaitingCount() : 0;
        result.put("waitingCount", waiting);

        Map<String, Object> aiResult = aiClient.assign(stationId, lineId, direction, waiting);
        if (aiResult != null && Integer.valueOf(200).equals(asInt(aiResult.get("code")))
                && aiResult.containsKey("data")) {
            Object data = aiResult.get("data");
            if (data instanceof Map) {
                Map<String, Object> aiData = (Map<String, Object>) data;
                aiData.put("source", "ai_service");
                result.putAll(aiData);
                return result;
            }
        }

        QueryWrapper<Vehicle> vqw = new QueryWrapper<>();
        vqw.eq("line_id", lineId).eq("direction", direction).eq("running_today", 1)
           .in("status", "running", "standby")
           .isNotNull("current_station_id");
        List<Vehicle> vehicles = vehicleMapper.selectList(vqw);
        List<Vehicle> atStation = new ArrayList<>();
        for (Vehicle v : vehicles) {
            if (stationId.equals(v.getCurrentStationId())) atStation.add(v);
        }
        if (atStation.isEmpty()) {
            result.put("strategy", "no_vehicle");
            result.put("message", "当前无车辆到站");
            return result;
        }


        List<Map<String, Object>> vehicleSeats = new ArrayList<>();
        for (Vehicle v : atStation) {
            int capacity = v.getCapacity();
            int currentLoad = v.getCurrentLoad();
            int alightEstimate = estimateAlight(v, stationId);
            int availableAfterAlight = capacity - (currentLoad - alightEstimate);
            availableAfterAlight = Math.max(0, Math.min(availableAfterAlight, capacity));
            int downstreamReserve = Math.min((int)(availableAfterAlight * 0.4), availableAfterAlight);
            int assignable = availableAfterAlight - downstreamReserve;
            if (waiting > 0 && waiting > assignable * atStation.size()) {
                downstreamReserve = 0;
                assignable = availableAfterAlight;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("vehicleId", v.getId());
            m.put("plateNo", v.getPlateNo());
            m.put("capacity", capacity);
            m.put("currentLoad", currentLoad);
            m.put("alightEstimate", alightEstimate);
            m.put("availableAfterAlight", availableAfterAlight);
            m.put("downstreamReserve", downstreamReserve);
            m.put("assignable", assignable);
            vehicleSeats.add(m);
        }
        vehicleSeats.sort((a, b) -> (int)b.get("assignable") - (int)a.get("assignable"));
        result.put("vehicles", vehicleSeats);

        List<Map<String, Object>> allocation = new ArrayList<>();
        int remainingWaiting = waiting;
        for (Map<String, Object> vs : vehicleSeats) {
            int assignable = (int) vs.get("assignable");
            int board = Math.min(remainingWaiting, assignable);
            if (board > 0) {
                Map<String, Object> alloc = new HashMap<>();
                alloc.put("vehicleId", vs.get("vehicleId"));
                alloc.put("plateNo", vs.get("plateNo"));
                alloc.put("boardCount", board);
                alloc.put("reserveForDownstream", vs.get("downstreamReserve"));
                alloc.put("reasonCode", "SEAT_RELEASE");
                alloc.put("confidence", 0.85);
                alloc.put("suggestion", "车辆" + vs.get("plateNo") + "上" + board + "人,给下游预留" + vs.get("downstreamReserve") + "座");
                allocation.add(alloc);
                remainingWaiting -= board;
            }
        }
        result.put("allocation", allocation);
        result.put("leftover", remainingWaiting);
        result.put("strategy", remainingWaiting < waiting ? "seat_release" : "first_come_first_serve");

        if (remainingWaiting > 0) {
            result.put("escalation", "仍有" + remainingWaiting + "人未上车,建议加车或引导至下一班");
        }

        int totalAvailable = vehicleSeats.stream().mapToInt(vs -> (int)vs.get("availableAfterAlight")).sum();
        int totalAssignable = vehicleSeats.stream().mapToInt(vs -> (int)vs.get("assignable")).sum();
        result.put("totalAvailableSeats", totalAvailable);
        result.put("totalAssignableSeats", totalAssignable);
        result.put("counterfactual", "若忽略下客释放,仅" +
            vehicleSeats.stream().mapToInt(vs -> Math.max(0, (int)vs.get("capacity") - (int)vs.get("currentLoad"))).sum() +
            "座可用;考虑下客释放后" + totalAvailable + "座可用");

        ShuttleWebSocketHandler.broadcast("multi_vehicle_assign", result);
        return result;
    }

    private int estimateAlight(Vehicle v, Long stationId) {
        if (v.getCurrentLoad() == 0) return 0;
        return (int)Math.round(v.getCurrentLoad() * 0.3);
    }

    private Integer asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    public List<Map<String, Object>> stationVehicles(Long stationId) {
        return stationVehicles(stationId, null, null);
    }

    public List<Map<String, Object>> stationVehicles(Long stationId, Long lineId, String direction) {
        QueryWrapper<Vehicle> qw = new QueryWrapper<>();
        qw.eq("current_station_id", stationId).eq("running_today", 1)
          .in("status", "running", "standby");
        if (lineId != null) qw.eq("line_id", lineId);
        if (direction != null) qw.eq("direction", direction);
        List<Vehicle> vehicles = vehicleMapper.selectList(qw);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Vehicle v : vehicles) {
            Map<String, Object> m = new HashMap<>();
            m.put("vehicleId", v.getId());
            m.put("plateNo", v.getPlateNo());
            m.put("lineId", v.getLineId());
            m.put("direction", v.getDirection());
            m.put("currentLoad", v.getCurrentLoad());
            m.put("capacity", v.getCapacity());
            m.put("status", v.getStatus());
            m.put("availableSeats", v.getCapacity() - v.getCurrentLoad());
            result.add(m);
        }
        return result;
    }
}
