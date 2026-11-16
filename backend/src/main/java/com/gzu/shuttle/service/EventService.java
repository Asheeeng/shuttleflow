package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    @Autowired private ShuttleEventMapper eventMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private FusionService fusionService;
    @Autowired private DispatchService dispatchService;
    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Value("${shuttle.redis.event-stream-max-length:10000}") private long maxStreamLength;

    @Transactional(rollbackFor = Exception.class)
    public ShuttleEvent receiveEvent(ShuttleEvent event) {
        if (event.getEventNo() == null || event.getEventNo().isEmpty()) {
            event.setEventNo(UUID.randomUUID().toString().replace("-", ""));
        }
        QueryWrapper<ShuttleEvent> qw = new QueryWrapper<>();
        qw.eq("event_no", event.getEventNo());
        if (eventMapper.selectCount(qw) > 0) {
            log.info("Duplicate event ignored: {}", event.getEventNo());
            return event;
        }
        if (event.getEventTime() == null) event.setEventTime(LocalDateTime.now());
        if (event.getReportTime() == null) event.setReportTime(LocalDateTime.now());
        if (event.getConfidence() == null) event.setConfidence(BigDecimal.ONE);
        event.setProcessed(1);
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException duplicate) {
            log.info("重复事件已忽略 eventNo={}", event.getEventNo());
            return event;
        }

        switch (event.getType()) {
            case "checkin":  fusionService.onCheckin(event); break;
            case "cancel":   fusionService.onCancel(event); break;
            case "arrive":   fusionService.onArrive(event); break;
            case "alight":   fusionService.onAlight(event); break;
            case "board":    fusionService.onBoard(event); break;
            case "depart":   fusionService.onDepart(event); break;
            case "calibrate":fusionService.onCalibrate(event); break;
        }

        dispatchService.onEvent(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publishCommittedEvent(event); }
            });
        } else {
            publishCommittedEvent(event);
        }
        return event;
    }

    private void publishCommittedEvent(ShuttleEvent event) {
        try {
            Map<String, String> eventMap = new HashMap<String, String>();
            eventMap.put("eventNo", event.getEventNo());
            eventMap.put("type", event.getType());
            redisTemplate.opsForStream().add("shuttle:events", eventMap);
            redisTemplate.opsForStream().trim("shuttle:events", Math.max(100L, maxStreamLength), true);
        } catch (Exception e) {
            log.warn("Redis事件流写入失败：{}", e.getMessage());
        }
        ShuttleWebSocketHandler.broadcast("event", event);
    }

    public List<ShuttleEvent> recentEvents(int limit) {
        QueryWrapper<ShuttleEvent> qw = new QueryWrapper<>();
        qw.orderByDesc("id").last("LIMIT " + Math.min(limit, 200));
        return eventMapper.selectList(qw);
    }
}
