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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import com.gzu.shuttle.service.AuthSessionService;

@RestController
@RequestMapping("/api/passenger")
public class PassengerController {
    @Autowired private PassengerMapper passengerMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private RequestMapper requestMapper;
    @Autowired private EventService eventService;
    @Autowired private EtaService etaService;
    @Autowired private FusionService fusionService;
    @Autowired private StationQueueMapper queueMapper;

    @PostMapping("/checkin")
    public Result<Map<String, Object>> checkin(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        String qrCode = (String) body.get("qrCode");
        Long lineId = Long.valueOf(body.get("lineId").toString());
        String direction = (String) body.getOrDefault("direction", "go");

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

        Passenger p = new Passenger();
        p.setOpenId(UUID.randomUUID().toString());
        p.setCreatedAt(LocalDateTime.now());
        passengerMapper.insert(p);

        Request req = new Request();
        req.setPassengerId(p.getId());
        req.setStationId(station.getId());
        req.setLineId(lineId);
        req.setDirection(direction);
        req.setType("checkin");
        req.setStatus("waiting");
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.insert(req);
        HttpSession session = httpRequest.getSession(false);
        if (session != null && "GUEST".equals(session.getAttribute(AuthSessionService.ROLE))) {
            @SuppressWarnings("unchecked")
            Set<Long> guestRequests = (Set<Long>) session.getAttribute(AuthSessionService.GUEST_REQUESTS);
            if (guestRequests == null) guestRequests = new HashSet<Long>();
            guestRequests.add(req.getId());
            session.setAttribute(AuthSessionService.GUEST_REQUESTS, guestRequests);
        }

        ShuttleEvent event = new ShuttleEvent();
        event.setEventNo("E" + System.currentTimeMillis() + "_" + new Random().nextInt(9999));
        event.setType("checkin");
        event.setSource("passenger");
        event.setStationId(station.getId());
        event.setLineId(lineId);
        event.setDirection(direction);
        event.setCount(1);
        event.setConfidence(BigDecimal.ONE);
        event.setEventTime(LocalDateTime.now());
        eventService.receiveEvent(event);

        List<Map<String, Object>> etaList = etaService.calculateEta(lineId, station.getId(), direction);
        Map<String, Object> result = new HashMap<>();
        result.put("station", station);
        result.put("request", req);
        result.put("etaList", etaList);
        return Result.ok(result);
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long requestId = Long.valueOf(body.get("requestId").toString());
        HttpSession session = httpRequest.getSession(false);
        if (session != null && "GUEST".equals(session.getAttribute(AuthSessionService.ROLE))) {
            @SuppressWarnings("unchecked")
            Set<Long> guestRequests = (Set<Long>) session.getAttribute(AuthSessionService.GUEST_REQUESTS);
            if (guestRequests == null || !guestRequests.contains(requestId))
                return Result.fail(403, "只能取消本次访问创建的候车请求");
        }
        Request req = requestMapper.selectById(requestId);
        if (req == null) return Result.fail("请求不存在");
        req.setStatus("cancelled");
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);
        if (session != null && "GUEST".equals(session.getAttribute(AuthSessionService.ROLE))) {
            @SuppressWarnings("unchecked")
            Set<Long> guestRequests = (Set<Long>) session.getAttribute(AuthSessionService.GUEST_REQUESTS);
            if (guestRequests != null) guestRequests.remove(requestId);
        }

        ShuttleEvent event = new ShuttleEvent();
        event.setEventNo("E" + System.currentTimeMillis() + "_c" + new Random().nextInt(9999));
        event.setType("cancel");
        event.setSource("passenger");
        event.setStationId(req.getStationId());
        event.setLineId(req.getLineId());
        event.setDirection(req.getDirection());
        event.setCount(-1);
        event.setConfidence(BigDecimal.ONE);
        event.setEventTime(LocalDateTime.now());
        eventService.receiveEvent(event);
        return Result.ok();
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
