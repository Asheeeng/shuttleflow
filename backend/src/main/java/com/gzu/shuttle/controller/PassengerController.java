package com.gzu.shuttle.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.service.EtaService;
import com.gzu.shuttle.service.EventService;
import com.gzu.shuttle.service.FusionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import com.gzu.shuttle.service.AuthSessionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@RestController
@RequestMapping("/api/passenger")
public class PassengerController {
    @Autowired private com.gzu.shuttle.service.OperationClock operationClock;
    @Autowired private PassengerMapper passengerMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private RequestMapper requestMapper;
    @Autowired private EventService eventService;
    @Autowired private EtaService etaService;
    @Autowired private FusionService fusionService;
    @Autowired private StationQueueMapper queueMapper;
    @Autowired private LineStationMapper lineStationMapper;

    @PostMapping("/checkin")
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result<Map<String, Object>> checkin(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long lineId = longValue(body.get("lineId"));
        String direction = String.valueOf(body.getOrDefault("direction", "go"));
        if (lineId == null || !("go".equals(direction) || "back".equals(direction))) return Result.fail(400, "线路或方向无效");
        String qrCode = body.get("qrCode") == null ? null : body.get("qrCode").toString();

        Station station = null;
        if (body.get("stationId") != null) {
            station = stationMapper.selectById(Long.valueOf(body.get("stationId").toString()));
        }
        if (station == null && qrCode != null) {
            QueryWrapper<Station> sqw = new QueryWrapper<>();
            sqw.eq("qr_code", qrCode);
            station = stationMapper.selectOne(sqw);
        }
        if (station == null) return Result.fail("无效站点或二维码");
        QueryWrapper<LineStation> route = new QueryWrapper<LineStation>();
        route.eq("station_id", station.getId()).eq("line_id", lineId).eq("direction", direction);
        if (lineStationMapper.selectCount(route) == 0) return Result.fail(400, "所选站点不属于该线路方向");

        Long destination = longValue(body.get("destStationId"));
        if (body.get("destStationId") != null || operationClock.active()) {
            List<LineStation> stops = lineStationMapper.selectList(new QueryWrapper<LineStation>()
                    .eq("line_id", lineId).eq("direction", direction).orderByAsc("seq"));
            if (body.get("destStationId") == null && !stops.isEmpty())
                destination = stops.get(stops.size() - 1).getStationId();
            boolean pastOrigin = false;
            boolean valid = false;
            for (LineStation stop : stops) {
                if (pastOrigin && stop.getStationId().equals(destination)) valid = true;
                if (stop.getStationId().equals(station.getId())) pastOrigin = true;
            }
            if (!valid) return Result.fail(400, "请选择同方向的下游目的站");
        }

        HttpSession session = httpRequest.getSession(false);
        if (session == null) return Result.fail(401, "请先登录或选择游客访问");
        Object roleValue = session.getAttribute(AuthSessionService.ROLE);
        String role = roleValue == null ? "" : roleValue.toString();
        if (!("GUEST".equals(role) || "PASSENGER".equals(role))) return Result.fail(403, "当前账号不能发起乘客候车");
        Object passengerIdValue = session.getAttribute(AuthSessionService.ID);
        Long passengerId = "PASSENGER".equals(role) && passengerIdValue != null
                ? Long.valueOf(passengerIdValue.toString()) : null;
        String owner = passengerId == null ? "G:" + session.getId() : "P:" + passengerId;
        String activeKey = activeKey(owner, station.getId(), lineId, direction);
        Request existing = requestMapper.selectActive(activeKey);
        if (existing != null) {
            rememberGuestRequest(session, role, existing.getId());
            return Result.ok(checkinView(station, existing, lineId, direction));
        }

        Request req = new Request();
        req.setPassengerId(passengerId);
        req.setStationId(station.getId());
        req.setLineId(lineId);
        req.setDirection(direction);
        req.setDestStationId(destination);
        req.setType("checkin");
        req.setStatus("waiting");
        req.setCreatedAt(operationClock.now());
        req.setUpdatedAt(operationClock.now());
        req.setActiveKey(activeKey);
        try {
            requestMapper.insert(req);
        } catch (DuplicateKeyException duplicate) {
            existing = requestMapper.selectActive(activeKey);
            if (existing == null) throw duplicate;
            rememberGuestRequest(session, role, existing.getId());
            return Result.ok(checkinView(station, existing, lineId, direction));
        }
        rememberGuestRequest(session, role, req.getId());

        ShuttleEvent event = new ShuttleEvent();
        event.setEventNo(UUID.randomUUID().toString().replace("-", ""));
        event.setType("checkin");
        event.setSource("passenger");
        event.setStationId(station.getId());
        event.setLineId(lineId);
        event.setDirection(direction);
        event.setCount(1);
        event.setConfidence(BigDecimal.ONE);
        event.setEventTime(operationClock.now());
        eventService.receiveEvent(event);

        return Result.ok(checkinView(station, req, lineId, direction));
    }

    @PostMapping("/cancel")
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Result<Void> cancel(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        if (body.get("requestId") == null) return Result.fail(400, "请求编号不能为空");
        Long requestId = longValue(body.get("requestId"));
        if (requestId == null) return Result.fail(400, "请求编号无效");
        HttpSession session = httpRequest.getSession(false);
        if (session == null) return Result.fail(401, "登录状态已失效");
        Object roleValue = session.getAttribute(AuthSessionService.ROLE);
        String role = roleValue == null ? "" : roleValue.toString();
        Request req = requestMapper.selectById(requestId);
        if (req == null) return Result.fail(404, "请求不存在");
        boolean ownsGuestRequest = false;
        if ("GUEST".equals(role) || "PASSENGER".equals(role)) {
            @SuppressWarnings("unchecked")
            Set<Long> guestRequests = (Set<Long>) session.getAttribute(AuthSessionService.GUEST_REQUESTS);
            ownsGuestRequest = guestRequests != null && guestRequests.contains(requestId);
        }
        if (!ownsGuestRequest && "PASSENGER".equals(role)) {
            Object passengerId = session.getAttribute(AuthSessionService.ID);
            if (passengerId == null || req.getPassengerId() == null
                    || !req.getPassengerId().toString().equals(passengerId.toString()))
                return Result.fail(403, "只能取消本人账号创建的候车请求");
        } else if (!ownsGuestRequest && "GUEST".equals(role)) {
            return Result.fail(403, "只能取消本次访问创建的候车请求");
        } else if (!ownsGuestRequest && !"PASSENGER".equals(role)) {
            return Result.fail(403, "当前账号不能取消乘客候车请求");
        }
        if (!"waiting".equals(req.getStatus()) || requestMapper.cancelAt(requestId, operationClock.now()) != 1)
            return Result.fail(409, "该候车请求已处理，不能重复取消");

        ShuttleEvent event = new ShuttleEvent();
        event.setEventNo(UUID.randomUUID().toString().replace("-", ""));
        event.setType("cancel");
        event.setSource("passenger");
        event.setStationId(req.getStationId());
        event.setLineId(req.getLineId());
        event.setDirection(req.getDirection());
        event.setCount(-1);
        event.setConfidence(BigDecimal.ONE);
        event.setEventTime(operationClock.now());
        eventService.receiveEvent(event);
        return Result.ok();
    }

    private Map<String, Object> checkinView(Station station, Request req, Long lineId, String direction) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("station", station);
        result.put("request", req);
        result.put("etaList", etaService.calculateEta(lineId, station.getId(), direction));
        return result;
    }

    private void rememberGuestRequest(HttpSession session, String role, Long requestId) {
        if (!"GUEST".equals(role)) return;
        synchronized (session) {
            @SuppressWarnings("unchecked")
            Set<Long> ids = (Set<Long>) session.getAttribute(AuthSessionService.GUEST_REQUESTS);
            if (ids == null) ids = Collections.synchronizedSet(new TreeSet<Long>());
            synchronized (ids) {
                ids.add(requestId);
                while (ids.size() > 1000) ids.remove(Collections.min(ids));
            }
            session.setAttribute(AuthSessionService.GUEST_REQUESTS, ids);
        }
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(value.toString()); } catch (NumberFormatException ignored) { return null; }
    }

    private String activeKey(String owner, Long stationId, Long lineId, String direction) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((owner + "|" + stationId + "|" + lineId + "|" + direction)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @GetMapping("/eta")
    public Result<List<Map<String, Object>>> eta(@RequestParam Long lineId,
                                                   @RequestParam Long stationId,
                                                   @RequestParam(defaultValue = "go") String direction) {
        return Result.ok(etaService.calculateEta(lineId, stationId, direction));
    }

    @GetMapping("/queue")
    public Result<List<Map<String, Object>>> queue(@RequestParam(required = false) Long stationId) {
        return Result.ok(fusionService.queueDetail());
    }

    @GetMapping("/crowding")
    public Result<Map<String, Object>> crowding(@RequestParam Long lineId,
                                                  @RequestParam Long stationId,
                                                  @RequestParam(defaultValue = "go") String direction) {
        List<Map<String, Object>> etaList = etaService.calculateEta(lineId, stationId, direction);
        QueryWrapper<StationQueue> qw = new QueryWrapper<>();
        qw.eq("station_id", stationId).eq("line_id", lineId).eq("direction", direction);
        StationQueue q = queueMapper.selectOne(qw);
        Map<String, Object> result = new HashMap<>();
        result.put("waitingCount", q != null ? q.getWaitingCount() : 0);
        result.put("etaList", etaList);
        return Result.ok(result);
    }
}
