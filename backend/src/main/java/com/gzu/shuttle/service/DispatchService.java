package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class DispatchService {
    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    @Autowired private StationQueueMapper queueMapper;
    @Autowired private CourseScheduleMapper courseMapper;
    @Autowired private TaskService taskService;
    @Autowired private AlertService alertService;
    @Autowired private LineMapper lineMapper;
    @Autowired private StationMapper stationMapper;

    @Value("${shuttle.alert.yellow-threshold:8}") private int yellowThreshold;
    @Value("${shuttle.alert.orange-threshold:15}") private int orangeThreshold;
    @Value("${shuttle.alert.red-threshold:25}") private int redThreshold;

    void onEvent(ShuttleEvent event) {
        if (event.getStationId() != null && event.getLineId() != null && event.getDirection() != null) {
            checkSurge(event.getStationId(), event.getLineId(), event.getDirection());
        }
        if ("arrive".equals(event.getType())) {
            checkCoursePreposition();
        }
    }

    private void checkSurge(Long stationId, Long lineId, String direction) {
        QueryWrapper<StationQueue> qw = new QueryWrapper<>();
        qw.eq("station_id", stationId).eq("line_id", lineId).eq("direction", direction);
        StationQueue q = queueMapper.selectOne(qw);
        if (q == null) return;
        int waiting = q.getWaitingCount();

        if (waiting >= redThreshold) {
            alertService.createAlert("red", "surge", stationId, null, lineId,
                    "站点候车" + waiting + "人，超过红色阈值" + redThreshold);
            taskService.createDispatchTask(lineId, stationId, direction, 9, "RED_SURGE",
                    "红色预警:站点" + waiting + "人候车，建议立即加车");
        } else if (waiting >= orangeThreshold) {
            alertService.createAlert("orange", "surge", stationId, null, lineId,
                    "站点候车" + waiting + "人，超过橙色阈值" + orangeThreshold);
            taskService.createDispatchTask(lineId, stationId, direction, 7, "ORANGE_SURGE",
                    "橙色预警:站点" + waiting + "人候车，建议加车");
        } else if (waiting >= yellowThreshold) {
            alertService.createAlert("yellow", "surge", stationId, null, lineId,
                    "站点候车" + waiting + "人，超过黄色阈值" + yellowThreshold);
        }
    }

    private void checkCoursePreposition() {
        LocalTime now = LocalTime.now();
        List<CourseSchedule> courses = courseMapper.selectList(null);
        for (CourseSchedule c : courses) {
            long diffMin = java.time.Duration.between(now, c.getEndTime()).toMinutes();
            if (diffMin >= 0 && diffMin <= 10) {
                int potential = (int)(c.getStudentCount() * c.getConversionRate().doubleValue());
                if (potential >= yellowThreshold && c.getNearbyStationId() != null) {
                    Station s = stationMapper.selectById(c.getNearbyStationId());
                    QueryWrapper<Line> lqw = new QueryWrapper<>();
                    List<Line> lines = lineMapper.selectList(lqw.eq("enabled", 1));
                    for (Line line : lines) {
                        taskService.createPrepositionTask(line.getId(), c.getNearbyStationId(),
                                "go", 4, "COURSE_PRE",
                                "课表先验:" + c.getBuilding() + " " + c.getEndTime() + "下课" +
                                        c.getStudentCount() + "人,转化率" + c.getConversionRate() +
                                        ",潜在" + potential + "人,建议预置运力");
                    }
                }
            }
        }
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> data = new HashMap<>();
        List<StationQueue> queues = queueMapper.selectList(null);
        queues.sort((a, b) -> b.getWaitingCount() - a.getWaitingCount());
        data.put("topQueues", queues.subList(0, Math.min(10, queues.size())));
        LocalTime now = LocalTime.now();
        List<CourseSchedule> upcoming = new ArrayList<>();
        for (CourseSchedule c : courseMapper.selectList(null)) {
            long diffMin = java.time.Duration.between(now, c.getEndTime()).toMinutes();
            if (diffMin >= 0 && diffMin <= 30) upcoming.add(c);
        }
        data.put("upcomingCourses", upcoming);
        return data;
    }
}