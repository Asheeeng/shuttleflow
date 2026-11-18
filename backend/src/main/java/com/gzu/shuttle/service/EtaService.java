package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class EtaService {
    @Autowired private LineStationMapper lsMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private LineMapper lineMapper;

    @Value("${shuttle.eta.buffer-sec:60}") private int bufferSec;

    public boolean hasBoardingStop(Long lineId, Long stationId, String direction) {
        List<LineStation> route = lsMapper.selectList(new QueryWrapper<LineStation>()
                .eq("line_id", lineId).eq("direction", direction).orderByAsc("seq"));
        return route.stream().limit(Math.max(0, route.size() - 1)).anyMatch(stop -> stationId.equals(stop.getStationId()));
    }

    public int estimateTravelSec(Long fromStationId, Long toStationId, Long lineId) {
        return estimateTravelSec(fromStationId, toStationId, lineId, null);
    }

    public int estimateTravelSec(Long fromStationId, Long toStationId, Long lineId, String direction) {
        if (fromStationId == null || toStationId == null || lineId == null) return 600;
        if (fromStationId.equals(toStationId)) return 0;
        QueryWrapper<LineStation> qw1 = new QueryWrapper<>();
        qw1.eq("line_id", lineId).eq("station_id", fromStationId);
        if (direction != null) qw1.eq("direction", direction);
        List<LineStation> fromList = lsMapper.selectList(qw1);
        LineStation from = fromList.isEmpty() ? null : fromList.get(0);
        QueryWrapper<LineStation> qw2 = new QueryWrapper<>();
        qw2.eq("line_id", lineId).eq("station_id", toStationId);
        if (direction != null) qw2.eq("direction", direction);
        List<LineStation> toList = lsMapper.selectList(qw2);
        LineStation to = toList.isEmpty() ? null : toList.get(0);
        if (from == null || to == null) return 600;
        if (from.getDirection().equals(to.getDirection()) && from.getSeq() < to.getSeq()) {
            QueryWrapper<LineStation> qw = new QueryWrapper<>();
            qw.eq("line_id", lineId).eq("direction", from.getDirection())
              .ge("seq", from.getSeq()).lt("seq", to.getSeq());
            List<LineStation> segs = lsMapper.selectList(qw);
            return segs.stream().mapToInt(s -> s.getTravelSec() != null ? s.getTravelSec() : 180).sum();
        }
        return 600;
    }

    public List<Map<String, Object>> calculateEta(Long lineId, Long stationId, String direction) {
        QueryWrapper<LineStation> targetQw = new QueryWrapper<>();
        targetQw.eq("line_id", lineId).eq("station_id", stationId).eq("direction", direction);
        LineStation target = lsMapper.selectOne(targetQw);
        if (target == null) return Collections.emptyList();

        QueryWrapper<Vehicle> vqw = new QueryWrapper<>();
        vqw.eq("line_id", lineId).eq("running_today", 1)
           .ne("status", "offline").isNotNull("current_station_id");
        List<Vehicle> vehicles = vehicleMapper.selectList(vqw);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Vehicle v : vehicles) {
            QueryWrapper<LineStation> vq = new QueryWrapper<>();
            vq.eq("line_id", lineId).eq("station_id", v.getCurrentStationId()).eq("direction", direction);
            LineStation vPos = lsMapper.selectOne(vq);
            if (vPos == null || vPos.getSeq() > target.getSeq()) continue;

            QueryWrapper<LineStation> segQw = new QueryWrapper<>();
            segQw.eq("line_id", lineId).eq("direction", direction)
                 .ge("seq", vPos.getSeq()).lt("seq", target.getSeq());
            List<LineStation> segs = lsMapper.selectList(segQw);
            int travelSec = segs.stream().mapToInt(s -> s.getTravelSec() != null ? s.getTravelSec() : 180).sum();
            int etaSec = travelSec + bufferSec;
            int loadRate = v.getCapacity() > 0 ? v.getCurrentLoad() * 100 / v.getCapacity() : 0;
            String crowding = loadRate >= 90 ? "red" : loadRate >= 70 ? "yellow" : "green";

            Map<String, Object> m = new HashMap<>();
            m.put("vehiclePlate", v.getPlateNo());
            m.put("vehicleId", v.getId());
            m.put("etaSec", etaSec);
            m.put("etaMin", etaSec / 60);
            m.put("currentLoad", v.getCurrentLoad());
            m.put("capacity", v.getCapacity());
            m.put("loadRate", loadRate);
            m.put("crowding", crowding);
            m.put("confidence", 0.75);
            result.add(m);
        }
        result.sort(Comparator.comparingInt(m -> (int) m.get("etaSec")));
        return result;
    }
}
