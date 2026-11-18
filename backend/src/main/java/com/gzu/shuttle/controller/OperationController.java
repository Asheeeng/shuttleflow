package com.gzu.shuttle.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.entity.*;
import com.gzu.shuttle.mapper.*;
import com.gzu.shuttle.service.AuthSessionService;
import com.gzu.shuttle.service.EventService;
import com.gzu.shuttle.service.OperationClock;
import com.gzu.shuttle.service.SurgeDetectionService;
import com.gzu.shuttle.service.TaskService;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
@ConditionalOnProperty(name = "shuttle.operation.enabled", havingValue = "true")
public class OperationController {
    private final OperationClock clock;
    private final SurgeDetectionService detector;
    private final VehicleMapper vehicles;
    private final RequestMapper requests;
    private final ShuttleEventMapper events;
    private final AlertMapper alerts;
    private final TaskMapper tasks;
    private final TaskService taskService;
    private final EventService eventService;
    private final RestTemplate worker;

    @Value("${shuttle.operation.token}") private String token;
    @Value("${spring.datasource.url}") private String database;
    @Value("${spring.redis.host}") private String redisHost;
    @Value("${spring.redis.port}") private int redisPort;
    @Value("${server.address}") private String bindAddress;

    @javax.annotation.PostConstruct
    void enforceIsolation() {
        if (!database.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/shuttle_v13_sim_[a-f0-9]{8}(\\?.*)?")
                || !"127.0.0.1".equals(redisHost) || redisPort != 16379
                || !"127.0.0.1".equals(bindAddress) || token.length() < 32)
            throw new IllegalStateException("运营仿真仅允许独立本机 MySQL、Redis 与回环监听");
    }

    public OperationController(OperationClock clock, SurgeDetectionService detector, VehicleMapper vehicles,
                               RequestMapper requests, ShuttleEventMapper events, AlertMapper alerts,
                               TaskMapper tasks, TaskService taskService, EventService eventService) {
        this.clock = clock;
        this.detector = detector;
        this.vehicles = vehicles;
        this.requests = requests;
        this.events = events;
        this.alerts = alerts;
        this.tasks = tasks;
        this.taskService = taskService;
        this.eventService = eventService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(4000);
        worker = new RestTemplate(factory);
    }

    private boolean trusted(HttpServletRequest request) {
        String supplied = request.getHeader("X-Operation-Token");
        return supplied != null && !token.isEmpty() && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/operation/view")
    public Result<?> view(HttpServletRequest request, @RequestParam(required = false) String run) {
        HttpSession session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute(AuthSessionService.ROLE);
        if (role == null) return Result.fail(401, "请登录仿真环境");
        String query = "?role=" + role;
        if ("DRIVER".equals(role)) query += "&vehicle=" + session.getAttribute(AuthSessionService.VEHICLE_ID);
        if (run != null && run.matches("[a-f0-9]{12}") && ("ADMIN".equals(role) || "DISPATCHER".equals(role)))
            query += "&run=" + run;
        return forward(HttpMethod.GET, "/state" + query, null);
    }

    @PostMapping("/api/admin/operation/control")
    public Result<?> control(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!"ADMIN".equals(request.getSession(false).getAttribute(AuthSessionService.ROLE)))
            return Result.fail(403, "仅系统管理员可管理仿真运行");
        return forward(HttpMethod.POST, "/control", body);
    }

    private Result<?> forward(HttpMethod method, String route, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operation-Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return worker.exchange("http://127.0.0.1:18001" + route, method,
                    new HttpEntity<>(body, headers), Result.class).getBody();
        } catch (RestClientException failure) {
            return Result.fail(503, "本机运营仿真进程不可用");
        }
    }

    @PostMapping("/api/admin/operation/clock")
    public Result<?> advance(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!trusted(request)) return Result.fail(403, "需要仿真进程授权");
        switch (body.getOrDefault("action", "advance")) {
            case "clear":
                clock.clear();
                break;
            case "scope":
                clock.scope(Long.parseLong(body.get("requestAfter")));
                break;
            case "bind":
                clock.bind(Long.valueOf(body.get("vehicleId")), body.get("tripId"));
                break;
            case "reset-detector":
                detector.resetSimulationWindow();
                break;
            case "refresh":
                ShuttleWebSocketHandler.broadcast("simulation_change", Collections.emptyMap());
                break;
            default:
                clock.advance(LocalDateTime.parse(body.get("time")), body.get("policy"));
        }
        return Result.ok();
    }

    @PostMapping("/api/admin/operation/fleet")
    public Result<Vehicle> createVehicle(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!trusted(request)) return Result.fail(403, "需要仿真进程授权");
        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo((String) body.get("plate"));
        vehicle.setLineId(Long.valueOf(body.get("lineId").toString()));
        vehicle.setCapacity(Integer.parseInt(body.get("capacity").toString()));
        vehicle.setStatus("offline");
        vehicle.setRunningToday(0);
        vehicle.setCurrentLoad(0);
        vehicles.insert(vehicle);
        return Result.ok(vehicle);
    }

    @PostMapping("/api/admin/operation/release")
    public Result<?> release(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        if (!trusted(request)) return Result.fail(403, "需要仿真进程授权");
        return Result.ok(taskService.escalateTask(body.get("taskId"), "SIM_FLEET_EXIT", "仿真车辆退出运营"));
    }

    @PostMapping("/api/admin/operation/clear-waiting")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result<?> clearWaiting(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        if (!trusted(request) || !clock.active()) return Result.fail(403, "仅可清理当前仿真");
        Long after = body.get("requestAfter");
        if (after == null || !after.equals(clock.requestFloor())) return Result.fail(400, "请求与当前仿真起点不一致");
        List<Request> waiting = requests.selectList(new QueryWrapper<Request>()
                .gt("id", after).eq("status", "waiting").orderByAsc("id").last("LIMIT 2000"));
        for (Request rider : waiting) {
            if (requests.cancelAt(rider.getId(), clock.now()) != 1) continue;
            ShuttleEvent event = new ShuttleEvent();
            event.setType("cancel");
            event.setSource("simulation");
            event.setStationId(rider.getStationId());
            event.setLineId(rider.getLineId());
            event.setDirection(rider.getDirection());
            event.setEventTime(clock.now());
            event.setCount(-1);
            eventService.receiveEvent(event);
        }
        return Result.ok();
    }

    @GetMapping("/api/admin/operation/ledger")
    public Result<Map<String, Object>> ledger(@RequestParam(defaultValue = "0") long requestAfter,
            @RequestParam(defaultValue = "0") long eventAfter, @RequestParam(defaultValue = "0") long taskAfter,
            @RequestParam(defaultValue = "0") long alertAfter, HttpServletRequest request) {
        if (!trusted(request)) return Result.fail(403, "需要仿真进程授权");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskHistory", clock.taskHistory());
        data.put("requests", requests.selectList(new QueryWrapper<Request>().gt("id", requestAfter).orderByAsc("id").last("LIMIT 2000")));
        data.put("events", events.selectList(new QueryWrapper<ShuttleEvent>().gt("id", eventAfter).orderByAsc("id").last("LIMIT 2000")));
        data.put("tasks", tasks.selectList(new QueryWrapper<Task>().gt("id", taskAfter).orderByAsc("id").last("LIMIT 1000")));
        data.put("alerts", alerts.selectList(new QueryWrapper<Alert>().gt("id", alertAfter).orderByAsc("id").last("LIMIT 1000")));
        return Result.ok(data);
    }

    @GetMapping("/api/admin/operation/cursors")
    public Result<Map<String, Long>> cursors(HttpServletRequest request) {
        if (!trusted(request)) return Result.fail(403, "需要仿真进程授权");
        Map<String, Long> data = new HashMap<>();
        Request r = requests.selectOne(new QueryWrapper<Request>().orderByDesc("id").last("LIMIT 1"));
        ShuttleEvent e = events.selectOne(new QueryWrapper<ShuttleEvent>().orderByDesc("id").last("LIMIT 1"));
        Task t = tasks.selectOne(new QueryWrapper<Task>().orderByDesc("id").last("LIMIT 1"));
        Alert a = alerts.selectOne(new QueryWrapper<Alert>().orderByDesc("id").last("LIMIT 1"));
        data.put("requestAfter", r == null ? 0 : r.getId());
        data.put("eventAfter", e == null ? 0 : e.getId());
        data.put("taskAfter", t == null ? 0 : t.getId());
        data.put("alertAfter", a == null ? 0 : a.getId());
        return Result.ok(data);
    }
}
