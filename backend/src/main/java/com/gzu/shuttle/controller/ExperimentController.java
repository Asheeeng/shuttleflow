package com.gzu.shuttle.controller;

import com.gzu.shuttle.common.Result;
import com.gzu.shuttle.service.AuthSessionService;
import java.util.Collections;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** 仅代理独立实验数据；不注入业务 Mapper、时钟或事件服务。 */
@RestController
@RequestMapping("/api/admin/experiment")
public class ExperimentController {
    @Value("${shuttle.experiment.enabled:false}") private boolean enabled;
    @Value("${shuttle.experiment.token:}") private String token;
    @Value("${shuttle.operation.enabled:false}") private boolean legacy;
    @Value("${shuttle.simulation.enabled:false}") private boolean testTick;
    private final RestTemplate worker;

    public ExperimentController() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(4000);
        worker = new RestTemplate(factory);
    }

    @javax.annotation.PostConstruct
    void validateIsolation() {
        if (enabled && (legacy || testTick || token.length() < 32))
            throw new IllegalStateException("算法实验与业务接口仿真相斥，并要求专用令牌");
    }

    @GetMapping("/state")
    public Result<?> state(@RequestParam(required = false) String run) {
        if (!enabled) return Result.ok(Collections.singletonMap("enabled", false));
        if (run != null && !run.matches("[a-f0-9]{12}")) return Result.fail(400, "无效运行编号");
        return forward(HttpMethod.GET, "/state" + (run == null ? "" : "?run=" + run), null);
    }

    @PostMapping("/control")
    public Result<?> control(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!enabled) return Result.fail(404, "算法实验未开启");
        HttpSession session = request.getSession(false);
        if (session == null || !"ADMIN".equals(session.getAttribute(AuthSessionService.ROLE)))
            return Result.fail(403, "仅系统管理员可操作实验");
        return forward(HttpMethod.POST, "/control", body);
    }

    private Result<?> forward(HttpMethod method, String route, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Experiment-Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return worker.exchange("http://127.0.0.1:18002" + route, method,
                    new HttpEntity<>(body, headers), Result.class).getBody();
        } catch (RestClientException e) {
            return Result.fail(503, "算法实验进程未启动或暂不可用");
        }
    }
}
