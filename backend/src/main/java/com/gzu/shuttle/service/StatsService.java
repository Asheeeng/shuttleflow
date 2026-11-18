package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class StatsService {
    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    @Autowired private StatsMapper statsMapper;
    @Autowired private OperationClock operationClock;
    @Autowired private StationQueueMapper queueMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private ShuttleEventMapper eventMapper;
    @Autowired private AlertMapper alertMapper;
    @Autowired private LineMapper lineMapper;
    @Autowired private StationMapper stationMapper;

    private static final DateTimeFormatter SLOT_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public Map<String, Object> realtimeKpi() {
        Map<String, Object> kpi = new HashMap<>();
        List<StationQueue> queues = queueMapper.selectList(null);
        int totalWaiting = queues.stream().mapToInt(q -> q.getWaitingCount() != null ? q.getWaitingCount() : 0).sum();
        int maxWaiting = queues.stream().mapToInt(q -> q.getWaitingCount() != null ? q.getWaitingCount() : 0).max().orElse(0);
        kpi.put("totalWaiting", totalWaiting);
        kpi.put("maxWaiting", maxWaiting);
        kpi.put("queueCount", queues.size());

        List<Vehicle> vehicles = vehicleMapper.selectList(null);
        int running = 0, task = 0, offline = 0, standby = 0;
        double totalLoadRate = 0;
        int loadedVehicles = 0;
        for (Vehicle v : vehicles) {
            String st = v.getStatus();
            if ("running".equals(st)) running++;
            else if ("task".equals(st)) task++;
            else if ("offline".equals(st)) offline++;
            else if ("standby".equals(st)) standby++;
            if (v.getCapacity() != null && v.getCapacity() > 0 && v.getCurrentLoad() != null) {
                totalLoadRate += (double) v.getCurrentLoad() / v.getCapacity();
                loadedVehicles++;
            }
        }
        kpi.put("vehicleTotal", vehicles.size());
        kpi.put("vehicleRunning", running);
        kpi.put("vehicleTask", task);
        kpi.put("vehicleOffline", offline);
        kpi.put("vehicleStandby", standby);
        kpi.put("avgLoadRate", loadedVehicles > 0 ? round2(totalLoadRate / loadedVehicles * 100) : 0);

        QueryWrapper<Task> pendingQw = new QueryWrapper<>();
        pendingQw.in("status", "pending", "assigned", "executing");
        int pendingTaskCount = taskMapper.selectCount(pendingQw).intValue();
        QueryWrapper<Task> todayQw = new QueryWrapper<>();
        todayQw.ge("created_at", operationClock.now().toLocalDate().atStartOfDay());
        List<Task> todayTasks = taskMapper.selectList(todayQw);
        int completedToday = (int) todayTasks.stream().filter(t -> "completed".equals(t.getStatus())).count();
        int escalatedToday = (int) todayTasks.stream().filter(t -> "escalated".equals(t.getStatus())).count();
        double completeRate = todayTasks.isEmpty() ? 0 : (double) completedToday / todayTasks.size() * 100;
        kpi.put("pendingTasks", pendingTaskCount);
        kpi.put("todayTasks", todayTasks.size());
        kpi.put("completedToday", completedToday);
        kpi.put("escalatedToday", escalatedToday);
        kpi.put("taskCompleteRate", round2(completeRate));

        QueryWrapper<Alert> alertQw = new QueryWrapper<>();
        alertQw.eq("status", "pending");
        int pendingAlerts = alertMapper.selectCount(alertQw).intValue();
        QueryWrapper<Alert> todayAlertQw = new QueryWrapper<>();
        todayAlertQw.ge("created_at", operationClock.now().toLocalDate().atStartOfDay());
        int todayAlerts = alertMapper.selectCount(todayAlertQw).intValue();
        kpi.put("pendingAlerts", pendingAlerts);
        kpi.put("todayAlerts", todayAlerts);

        QueryWrapper<ShuttleEvent> eventQw = new QueryWrapper<>();
        eventQw.ge("event_time", operationClock.now().toLocalDate().atStartOfDay());
        int todayEvents = eventMapper.selectCount(eventQw).intValue();
        kpi.put("todayEvents", todayEvents);

        return kpi;
    }

    public Map<String, Object> lineOverview() {
        Map<String, Object> result = new HashMap<>();
        List<Line> lines = lineMapper.selectList(new QueryWrapper<Line>().eq("enabled", 1));
        List<StationQueue> queues = queueMapper.selectList(null);
        List<Vehicle> vehicles = vehicleMapper.selectList(null);
        for (Line line : lines) {
            Map<String, Object> info = new HashMap<>();
            int lineWaiting = queues.stream()
                    .filter(q -> line.getId().equals(q.getLineId()))
                    .mapToInt(q -> q.getWaitingCount() != null ? q.getWaitingCount() : 0)
                    .sum();
            List<Vehicle> lineVehicles = new ArrayList<>();
            for (Vehicle v : vehicles) {
                if (line.getId().equals(v.getLineId())) lineVehicles.add(v);
            }
            int lineRunning = (int) lineVehicles.stream().filter(v -> "running".equals(v.getStatus()) || "task".equals(v.getStatus())).count();
            double lineLoad = lineVehicles.stream()
                    .filter(v -> v.getCapacity() != null && v.getCapacity() > 0 && v.getCurrentLoad() != null)
                    .mapToDouble(v -> (double) v.getCurrentLoad() / v.getCapacity())
                    .average().orElse(0);
            info.put("lineNo", line.getLineNo());
            info.put("lineName", line.getName());
            info.put("totalWaiting", lineWaiting);
            info.put("vehicleCount", lineVehicles.size());
            info.put("runningCount", lineRunning);
            info.put("avgLoadRate", round2(lineLoad * 100));
            result.put(line.getLineNo(), info);
        }
        return result;
    }

    public Map<String, Object> waitingTrend(int hours) {
        Map<String, Object> result = new HashMap<>();
        LocalDateTime since = operationClock.now().minusHours(hours);
        QueryWrapper<ShuttleEvent> qw = new QueryWrapper<>();
        qw.ge("event_time", since).orderByAsc("event_time");
        List<ShuttleEvent> events = eventMapper.selectList(qw);
        Map<String, List<Map<String, Object>>> byLine = new HashMap<>();
        for (ShuttleEvent e : events) {
            if (e.getLineId() == null || e.getEventTime() == null) continue;
            String slot = e.getEventTime().format(SLOT_FMT);
            String key = "line" + e.getLineId();
            byLine.computeIfAbsent(key, k -> new ArrayList<>());
            Map<String, Object> point = new HashMap<>();
            point.put("time", slot);
            point.put("type", e.getType());
            point.put("count", e.getCount());
            byLine.get(key).add(point);
        }
        result.putAll(byLine);
        return result;
    }

    public List<Map<String, Object>> stationRanking() {
        List<StationQueue> queues = queueMapper.selectList(null);
        List<Station> stations = stationMapper.selectList(null);
        List<Line> lines = lineMapper.selectList(null);
        Map<Long, String> stationNames = new HashMap<>();
        for (Station s : stations) stationNames.put(s.getId(), s.getName());
        Map<Long, String> lineNames = new HashMap<>();
        for (Line l : lines) lineNames.put(l.getId(), l.getName());
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (StationQueue q : queues) {
            Map<String, Object> row = new HashMap<>();
            row.put("stationId", q.getStationId());
            row.put("stationName", stationNames.getOrDefault(q.getStationId(), "未知"));
            row.put("lineName", lineNames.getOrDefault(q.getLineId(), "未知"));
            row.put("direction", q.getDirection());
            row.put("waiting", q.getWaitingCount());
            row.put("confidence", q.getConfidence());
            ranking.add(row);
        }
        ranking.sort((a, b) -> Integer.compare(
                (Integer) b.getOrDefault("waiting", 0),
                (Integer) a.getOrDefault("waiting", 0)));
        return ranking;
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void snapshotStats() {
        log.info("Snapshot stats started");
        LocalDate today = operationClock.now().toLocalDate();
        String slot = currentSlot();
        List<Line> lines = lineMapper.selectList(new QueryWrapper<Line>().eq("enabled", 1));
        List<StationQueue> queues = queueMapper.selectList(null);
        List<Vehicle> vehicles = vehicleMapper.selectList(null);

        for (Line line : lines) {
            List<StationQueue> lineQueues = new ArrayList<>();
            for (StationQueue q : queues) {
                if (line.getId().equals(q.getLineId())) lineQueues.add(q);
            }
            int waitingAvg = lineQueues.isEmpty() ? 0 :
                    lineQueues.stream().mapToInt(q -> q.getWaitingCount() != null ? q.getWaitingCount() : 0).sum() / lineQueues.size();

            List<Vehicle> lineVehicles = new ArrayList<>();
            for (Vehicle v : vehicles) {
                if (line.getId().equals(v.getLineId())) lineVehicles.add(v);
            }
            double loadRate = lineVehicles.stream()
                    .filter(v -> v.getCapacity() != null && v.getCapacity() > 0 && v.getCurrentLoad() != null)
                    .mapToDouble(v -> (double) v.getCurrentLoad() / v.getCapacity())
                    .average().orElse(0);

            QueryWrapper<Task> taskQw = new QueryWrapper<>();
            taskQw.eq("line_id", line.getId()).ge("created_at", today.atStartOfDay());
            List<Task> lineTasks = taskMapper.selectList(taskQw);
            int completed = (int) lineTasks.stream().filter(t -> "completed".equals(t.getStatus())).count();
            double completeRate = lineTasks.isEmpty() ? 0 : (double) completed / lineTasks.size();

            int leftoverAvg = lineQueues.stream()
                    .mapToInt(q -> q.getWaitingCount() != null && q.getWaitingCount() > line.getCapacity() ? q.getWaitingCount() - line.getCapacity() : 0)
                    .sum() / Math.max(1, lineQueues.size());

            Stats stats = new Stats();
            stats.setStatDate(today);
            stats.setLineId(line.getId());
            stats.setTimeSlot(slot);
            stats.setWaitingAvg(waitingAvg);
            stats.setLeftoverAvg(leftoverAvg);
            stats.setLoadRate(BigDecimal.valueOf(round2(loadRate * 100)));
            stats.setTaskCompleteRate(BigDecimal.valueOf(round2(completeRate * 100)));
            stats.setDataSource("measured");
            statsMapper.insert(stats);
        }
        log.info("Snapshot stats completed for slot {}", slot);
    }

    public List<Stats> history(LocalDate date, Long lineId) {
        QueryWrapper<Stats> qw = new QueryWrapper<>();
        qw.eq("stat_date", date);
        if (lineId != null) qw.eq("line_id", lineId);
        qw.orderByAsc("time_slot").orderByAsc("line_id");
        return statsMapper.selectList(qw);
    }

    public Map<String, Object> summary(LocalDate date) {
        Map<String, Object> summary = new HashMap<>();
        QueryWrapper<Stats> qw = new QueryWrapper<>();
        qw.eq("stat_date", date).orderByAsc("time_slot");
        List<Stats> stats = statsMapper.selectList(qw);
        if (stats.isEmpty()) {
            summary.put("date", date.toString());
            summary.put("slots", 0);
            summary.put("avgWaiting", 0);
            summary.put("avgLoadRate", 0);
            summary.put("avgCompleteRate", 0);
            return summary;
        }
        double avgWaiting = stats.stream().filter(s -> s.getWaitingAvg() != null)
                .mapToInt(Stats::getWaitingAvg).average().orElse(0);
        double avgLoad = stats.stream().filter(s -> s.getLoadRate() != null)
                .mapToDouble(s -> s.getLoadRate().doubleValue()).average().orElse(0);
        double avgComplete = stats.stream().filter(s -> s.getTaskCompleteRate() != null)
                .mapToDouble(s -> s.getTaskCompleteRate().doubleValue()).average().orElse(0);
        summary.put("date", date.toString());
        summary.put("slots", stats.size());
        summary.put("avgWaiting", round2(avgWaiting));
        summary.put("avgLoadRate", round2(avgLoad));
        summary.put("avgCompleteRate", round2(avgComplete));
        return summary;
    }

    private String currentSlot() {
        LocalTime now = LocalTime.now();
        int minute = now.getMinute() / 15 * 15;
        LocalTime start = LocalTime.of(now.getHour(), minute);
        LocalTime end = start.plusMinutes(15);
        return start.format(SLOT_FMT) + "-" + end.format(SLOT_FMT);
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}