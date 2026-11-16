package com.gzu.shuttle.controller;

import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private LineMapper lineMapper;
    @Autowired private DriverMapper driverMapper;
    @Autowired private FusionService fusionService;
    @Autowired private AlertService alertService;
    @Autowired private TaskService taskService;
    @Autowired private DispatchService dispatchService;
    @Autowired private EventService eventService;
    @Autowired private SurgeDetectionService surgeDetectionService;
    @Autowired private MultiVehicleAssignService multiVehicleAssignService;
    @Autowired private StatsService statsService;
    @Autowired private com.gzu.shuttle.service.AiServiceClient aiServiceClient;

    @GetMapping("/queues")
    public Result<List<Map<String, Object>>> queues() {
        return Result.ok(fusionService.queueDetail());
    }

    @GetMapping("/vehicles")
    public Result<List<Vehicle>> vehicles() {
        return Result.ok(vehicleMapper.selectList(null));
    }

    @GetMapping("/alerts")
    public Result<List<Alert>> alerts(@RequestParam(defaultValue = "pending") String status) {
        if ("all".equals(status)) return Result.ok(alertService.recentAlerts(100));
        return Result.ok(alertService.pendingAlerts());
    }

    @PostMapping("/alert/confirm")
    public Result<Alert> confirmAlert(@RequestBody Map<String, Object> body) {
        return Result.ok(alertService.confirm(Long.valueOf(body.get("id").toString())));
    }

    @PostMapping("/alert/suppress")
    public Result<Alert> suppressAlert(@RequestBody Map<String, Object> body) {
        return Result.ok(alertService.suppress(Long.valueOf(body.get("id").toString())));
    }

    @PostMapping("/alert/resolve")
    public Result<Alert> resolveAlert(@RequestBody Map<String, Object> body) {
        return Result.ok(alertService.resolve(Long.valueOf(body.get("id").toString())));
    }

    @GetMapping("/tasks")
    public Result<List<Task>> tasks(@RequestParam(defaultValue = "active") String scope) {
        if ("all".equals(scope)) return Result.ok(taskService.allTasks(200));
        return Result.ok(taskService.pendingTasks());
    }

    @PostMapping("/task/assign")
    public Result<Task> assignTask(@RequestBody Map<String, Object> body) {
        return Result.ok(taskService.assignTask(Long.valueOf(body.get("taskId").toString())));
    }

    @PostMapping("/task/complete")
    public Result<Task> completeTask(@RequestBody Map<String, Object> body) {
        return Result.ok(taskService.completeTask(Long.valueOf(body.get("taskId").toString())));
    }

    @PostMapping("/task/create")
    public Result<Task> createTask(@RequestBody Map<String, Object> body) {
        Long lineId = Long.valueOf(body.get("lineId").toString());
        Long stationId = body.get("stationId") != null ? Long.valueOf(body.get("stationId").toString()) : null;
        String direction = (String) body.getOrDefault("direction", "go");
        int priority = Integer.parseInt(body.getOrDefault("priority", "5").toString());
        String suggestion = (String) body.getOrDefault("suggestion", "管理员手动创建调度任务");
        return Result.ok(taskService.createDispatchTask(lineId, stationId, direction, priority, "MANUAL", suggestion));
    }

    @PostMapping("/vehicle/set-running")
    public Result<Vehicle> setRunning(@RequestBody Map<String, Object> body) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        boolean running = Boolean.parseBoolean(body.getOrDefault("running", "true").toString());
        Vehicle v = vehicleMapper.selectById(vehicleId);
        if (v != null) {
            v.setRunningToday(running ? 1 : 0);
            if (running && "offline".equals(v.getStatus())) v.setStatus("standby");
            if (!running) v.setStatus("offline");
            vehicleMapper.updateById(v);
        }
        return Result.ok(v);
    }

    @PostMapping("/vehicle/set-status")
    public Result<Vehicle> setStatus(@RequestBody Map<String, Object> body) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        String status = (String) body.get("status");
        Vehicle v = vehicleMapper.selectById(vehicleId);
        if (v != null) { v.setStatus(status); vehicleMapper.updateById(v); }
        return Result.ok(v);
    }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> data = new HashMap<>();
        data.put("dispatch", dispatchService.dashboard());
        data.put("vehicles", vehicleMapper.selectList(null));
        data.put("queues", fusionService.queueDetail());
        data.put("alerts", alertService.pendingAlerts());
        data.put("tasks", taskService.pendingTasks());
        data.put("lines", lineMapper.selectList(null));
        data.put("stations", stationMapper.selectList(null));
        return Result.ok(data);
    }

    @GetMapping("/events")
    public Result<List<ShuttleEvent>> events(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(eventService.recentEvents(limit));
    }

    @GetMapping("/drivers")
    public Result<List<Driver>> drivers() {
        return Result.ok(driverMapper.selectList(null));
    }

    @PostMapping("/observation")
    public Result<Map<String, Object>> observation(@RequestBody Map<String, Object> body) {
        Long stationId = Long.valueOf(body.get("stationId").toString());
        Long lineId = Long.valueOf(body.get("lineId").toString());
        String direction = (String) body.getOrDefault("direction", "go");
        int observedCount = Integer.parseInt(body.get("observedCount").toString());
        double variance = body.get("variance") != null ? Double.parseDouble(body.get("variance").toString()) : 4.0;
        String source = (String) body.getOrDefault("source", "camera");
        return Result.ok(fusionService.observationUpdate(stationId, lineId, direction, observedCount, variance, source));
    }

    @GetMapping("/cusum")
    public Result<Map<String, Object>> cusum() {
        return Result.ok(surgeDetectionService.getStatus());
    }

    @GetMapping("/assign")
    public Result<Map<String, Object>> assign(@RequestParam Long stationId,
                                               @RequestParam Long lineId,
                                               @RequestParam(defaultValue = "go") String direction) {
        return Result.ok(multiVehicleAssignService.assign(stationId, lineId, direction));
    }

    @GetMapping("/station-vehicles")
    public Result<List<Map<String, Object>>> stationVehicles(@RequestParam Long stationId) {
        return Result.ok(multiVehicleAssignService.stationVehicles(stationId));
    }

    @GetMapping("/stats/kpi")
    public Result<Map<String, Object>> statsKpi() {
        return Result.ok(statsService.realtimeKpi());
    }

    @GetMapping("/stats/line-overview")
    public Result<Map<String, Object>> statsLineOverview() {
        return Result.ok(statsService.lineOverview());
    }

    @GetMapping("/stats/waiting-trend")
    public Result<Map<String, Object>> statsWaitingTrend(@RequestParam(defaultValue = "6") int hours) {
        return Result.ok(statsService.waitingTrend(hours));
    }

    @GetMapping("/stats/station-ranking")
    public Result<List<Map<String, Object>>> statsStationRanking() {
        return Result.ok(statsService.stationRanking());
    }

    @GetMapping("/stats/history")
    public Result<List<Stats>> statsHistory(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(required = false) Long lineId) {
        return Result.ok(statsService.history(date, lineId));
    }

    @GetMapping("/stats/summary")
    public Result<Map<String, Object>> statsSummary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(statsService.summary(date));
    }

    @PostMapping("/stats/snapshot")
    public Result<String> statsSnapshot() {
        statsService.snapshotStats();
        return Result.ok("snapshot done");
    }

    @GetMapping("/ai/health")
    public Result<Map<String, Object>> aiHealth() {
        aiServiceClient.checkHealth();
        Map<String, Object> data = new HashMap<>();
        data.put("available", aiServiceClient.isAvailable());
        return Result.ok(data);
    }

    @GetMapping("/ai/course-predict")
    public Result<Map<String, Object>> aiCoursePredict() {
        Map<String, Object> result = aiServiceClient.coursePredict();
        if (result != null) return Result.ok(result);
        return Result.fail("AI服务不可用");
    }

    @GetMapping("/ai/ablation")
    public Result<Map<String, Object>> aiAblation(@RequestParam Long stationId,
                                                    @RequestParam Long lineId,
                                                    @RequestParam(defaultValue = "go") String direction,
                                                    @RequestParam(defaultValue = "20") int waiting) {
        Map<String, Object> result = aiServiceClient.ablation(stationId, lineId, direction, waiting);
        if (result != null) return Result.ok(result);
        return Result.fail("AI服务不可用");
    }

    @GetMapping("/ai/cusum/status")
    public Result<Map<String, Object>> aiCusumStatus() {
        Map<String, Object> result = aiServiceClient.cusumStatus();
        if (result != null) return Result.ok(result.get("data") instanceof Map
                ? (Map<String, Object>) result.get("data") : result);
        return Result.fail("AI服务不可用");
    }

    @PostMapping("/ai/kalman")
    public Result<Map<String, Object>> aiKalman(@RequestBody Map<String, Object> body) {
        Long stationId = Long.valueOf(body.get("station_id").toString());
        Long lineId = Long.valueOf(body.get("line_id").toString());
        String direction = (String) body.getOrDefault("direction", "go");
        int observedCount = Integer.parseInt(body.get("observed_count").toString());
        double variance = body.get("variance") == null ? 4.0 : Double.parseDouble(body.get("variance").toString());
        String source = (String) body.getOrDefault("source", "simulation");
        Map<String, Object> result = aiServiceClient.kalmanFuse(stationId, lineId, direction, observedCount, variance, source);
        if (result != null) return Result.ok(result.get("data") instanceof Map
                ? (Map<String, Object>) result.get("data") : result);
        return Result.fail("AI服务不可用");
    }
}
