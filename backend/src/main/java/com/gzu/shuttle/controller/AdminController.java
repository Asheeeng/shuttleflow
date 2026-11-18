package com.gzu.shuttle.controller;

import com.gzu.shuttle.common.Result;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private LineMapper lineMapper;
    @Autowired private DriverMapper driverMapper;
    @Autowired private AdminMapper adminMapper;
    @Autowired private FusionService fusionService;
    @Autowired private AlertService alertService;
    @Autowired private TaskService taskService;
    @Autowired private DispatchService dispatchService;
    @Autowired private EventService eventService;
    @Autowired private SurgeDetectionService surgeDetectionService;
    @Value("${shuttle.simulation.enabled:false}") private boolean simulationEnabled;
    @Autowired private MultiVehicleAssignService multiVehicleAssignService;
    @Autowired private StatsService statsService;
    @Autowired private com.gzu.shuttle.service.AiServiceClient aiServiceClient;
    @Autowired private PasswordHashService passwordHashes;
    @Autowired private AuthSessionService authSessions;

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
        Task task = taskService.completeTask(Long.valueOf(body.get("taskId").toString()));
        return task == null ? Result.fail(409, "仅执行中的调度任务可以完成") : Result.ok(task);
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
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> setRunning(@RequestBody Map<String, Object> body) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        boolean running = Boolean.parseBoolean(body.getOrDefault("running", "true").toString());
        Vehicle v = vehicleMapper.selectForUpdate(vehicleId);
        if (v == null) return Result.fail(404, "车辆不存在");
        if (!running && "task".equals(v.getStatus())) return Result.fail(409, "车辆仍有执行中的调度任务，暂不能下线");
        if (v != null) {
            v.setRunningToday(running ? 1 : 0);
            if (running && "offline".equals(v.getStatus())) v.setStatus("standby");
            if (!running) v.setStatus("offline");
            vehicleMapper.updateById(v);
        }
        return Result.ok(v);
    }

    @PostMapping("/vehicle/set-status")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> setStatus(@RequestBody Map<String, Object> body) {
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        String status = (String) body.get("status");
        if (!Arrays.asList("offline", "standby", "running", "returning").contains(status)) return Result.fail(400, "车辆状态无效");
        Vehicle v = vehicleMapper.selectForUpdate(vehicleId);
        if (v == null) return Result.fail(404, "车辆不存在");
        if ("task".equals(v.getStatus())) return Result.fail(409, "车辆仍被调度任务占用");
        v.setStatus(status); vehicleMapper.updateById(v);
        return Result.ok(v);
    }

    @PostMapping("/vehicle/capacity")
    @Transactional(rollbackFor = Exception.class)
    public Result<Vehicle> setCapacity(@RequestBody Map<String, Object> body) {
        if (body.get("vehicleId") == null || body.get("capacity") == null) return Result.fail(400, "车辆和容量不能为空");
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        int capacity = Integer.parseInt(body.get("capacity").toString());
        if (capacity < 1 || capacity > 100) return Result.fail(400, "车辆容量范围应为1至100人");
        Vehicle vehicle = vehicleMapper.selectForUpdate(vehicleId);
        if (vehicle == null) return Result.fail(404, "车辆不存在");
        if (capacity < vehicle.getCurrentLoad()) return Result.fail(409, "新容量不能小于当前载客人数");
        vehicle.setCapacity(capacity);
        vehicleMapper.updateById(vehicle);
        return Result.ok(vehicle);
    }

    @PostMapping("/driver/create")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createDriver(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? "" : body.get("name").toString().trim();
        String phone = body.get("phone") == null ? "" : body.get("phone").toString().replaceAll("[\\s-]", "").trim();
        String password = body.get("password") == null ? null : body.get("password").toString();
        if (name.isEmpty() || name.length() > 20) return Result.fail(400, "司机姓名需为1至20个字符");
        if (!phone.matches("^\\+?[0-9]{7,18}$")) return Result.fail(400, "请输入有效手机号");
        if (password == null || password.length() < 8 || password.length() > 128) return Result.fail(400, "司机密码需为8至128个字符");
        if (body.get("vehicleId") == null) return Result.fail(400, "请选择绑定车辆");
        Long vehicleId = Long.valueOf(body.get("vehicleId").toString());
        Vehicle vehicle = vehicleMapper.selectForUpdate(vehicleId);
        if (vehicle == null) return Result.fail(404, "车辆不存在");
        QueryWrapper<Driver> phoneQuery = new QueryWrapper<Driver>().eq("phone", phone);
        if (driverMapper.selectCount(phoneQuery) > 0) return Result.fail(409, "该手机号已注册");
        QueryWrapper<Driver> vehicleQuery = new QueryWrapper<Driver>().eq("default_vehicle_id", vehicleId);
        if (driverMapper.selectCount(vehicleQuery) > 0) return Result.fail(409, "该车辆已绑定司机");

        Driver driver = new Driver();
        driver.setName(name);
        driver.setPhone(phone);
        driver.setPasswordHash(passwordHashes.encode(password));
        driver.setDefaultVehicleId(vehicleId);
        driver.setStatus("off");
        driver.setEnabled(1);
        try {
            driverMapper.insert(driver);
        } catch (DuplicateKeyException duplicate) {
            return Result.fail(409, "手机号或车辆已被其他管理员使用");
        }
        Map<String, Object> safe = new HashMap<String, Object>();
        safe.put("id", driver.getId()); safe.put("name", driver.getName()); safe.put("phone", driver.getPhone());
        safe.put("defaultVehicleId", driver.getDefaultVehicleId()); safe.put("status", driver.getStatus());
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("driver", safe); result.put("vehicle", vehicle);
        return Result.ok(result);
    }

    @PostMapping("/driver/reset-password")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> resetDriverPassword(@RequestBody Map<String, Object> body) {
        if (body.get("driverId") == null || body.get("password") == null) return Result.fail(400, "司机账号和新密码不能为空");
        Long driverId = Long.valueOf(body.get("driverId").toString());
        String password = body.get("password").toString();
        if (password.length() < 8 || password.length() > 128) return Result.fail(400, "新密码需为8至128个字符");
        Driver driver = driverMapper.selectById(driverId);
        if (driver == null) return Result.fail(404, "司机账号不存在");
        driver.setPasswordHash(passwordHashes.encode(password));
        driverMapper.updateById(driver);
        authSessions.revoke("DRIVER", driverId);
        return Result.ok();
    }

    @PostMapping("/account/password")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> changeOwnPassword(@RequestBody Map<String, Object> body,
                                                         javax.servlet.http.HttpServletRequest request) {
        String current = body.get("currentPassword") == null ? null : body.get("currentPassword").toString();
        String next = body.get("newPassword") == null ? null : body.get("newPassword").toString();
        if (current == null || next == null || next.length() < 14 || next.length() > 128)
            return Result.fail(400, "请输入当前密码，并设置14至128位新密码");
        javax.servlet.http.HttpSession session = request.getSession(false);
        Object idValue = session == null ? null : session.getAttribute(AuthSessionService.ID);
        Object roleValue = session == null ? null : session.getAttribute(AuthSessionService.ROLE);
        if (idValue == null || roleValue == null) return Result.fail(401, "登录状态已失效");
        Long id = Long.valueOf(idValue.toString());
        String role = roleValue.toString();
        Admin admin = adminMapper.selectById(id);
        if (admin == null || admin.getPasswordHash() == null || !passwordHashes.matches(current, admin.getPasswordHash()))
            return Result.fail(401, "当前密码不正确");
        admin.setPasswordHash(passwordHashes.encode(next));
        adminMapper.updateById(admin);
        authSessions.revoke(role, id);
        Map<String, Object> auth = authSessions.issue(request, role, id, admin.getUsername(), admin.getName());
        return Result.ok(auth);
    }

    @GetMapping("/operators")
    public Result<List<Map<String, Object>>> operators() {
        List<Admin> accounts = adminMapper.selectList(null);
        List<Map<String, Object>> safe = new ArrayList<Map<String, Object>>();
        for (Admin account : accounts) {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("id", account.getId()); row.put("username", account.getUsername());
            row.put("name", account.getName()); row.put("role", account.getRole());
            safe.add(row);
        }
        return Result.ok(safe);
    }

    @PostMapping("/dispatcher/create")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createDispatcher(@RequestBody Map<String, Object> body) {
        String username = body.get("username") == null ? "" : body.get("username").toString().trim();
        String name = body.get("name") == null ? "" : body.get("name").toString().trim();
        String password = body.get("password") == null ? null : body.get("password").toString();
        if (!username.matches("^[A-Za-z0-9_.-]{3,30}$")) return Result.fail(400, "用户名需为3至30位字母、数字或._-");
        if (name.isEmpty() || name.length() > 20) return Result.fail(400, "姓名需为1至20个字符");
        if (password == null || password.length() < 12 || password.length() > 128) return Result.fail(400, "调度员初始密码需为12至128个字符");
        QueryWrapper<Admin> query = new QueryWrapper<Admin>().eq("username", username);
        if (adminMapper.selectCount(query) > 0) return Result.fail(409, "该用户名已使用");
        Admin account = new Admin();
        account.setUsername(username); account.setName(name); account.setRole("dispatcher");
        account.setPasswordHash(passwordHashes.encode(password));
        try { adminMapper.insert(account); }
        catch (DuplicateKeyException duplicate) { return Result.fail(409, "该用户名已使用"); }
        Map<String, Object> safe = new HashMap<String, Object>();
        safe.put("id", account.getId()); safe.put("username", account.getUsername());
        safe.put("name", account.getName()); safe.put("role", account.getRole());
        return Result.ok(safe);
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

    @PostMapping("/simulation/cusum/tick")
    public Result<Map<String, Object>> simulationCusumTick(@RequestBody(required = false) Map<String, Object> body) {
        if (!simulationEnabled) return Result.fail(404, "仿真检测入口未启用");
        int intervalSeconds = body == null || body.get("intervalSeconds") == null ? 60
                : Integer.parseInt(body.get("intervalSeconds").toString());
        return Result.ok(surgeDetectionService.detectNow(intervalSeconds));
    }

    @GetMapping("/assign")
    public Result<Map<String, Object>> assign(@RequestParam Long stationId,
                                               @RequestParam Long lineId,
                                               @RequestParam(defaultValue = "go") String direction) {
        return Result.ok(multiVehicleAssignService.assign(stationId, lineId, direction));
    }

    @GetMapping("/station-vehicles")
    public Result<List<Map<String, Object>>> stationVehicles(@RequestParam Long stationId,
                                                              @RequestParam(required = false) Long lineId,
                                                              @RequestParam(required = false) String direction) {
        if (direction != null && !"go".equals(direction) && !"back".equals(direction))
            return Result.fail(400, "方向必须为go或back");
        return Result.ok(multiVehicleAssignService.stationVehicles(stationId, lineId, direction));
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
