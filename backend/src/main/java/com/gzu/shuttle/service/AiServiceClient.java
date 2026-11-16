package com.gzu.shuttle.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Service
public class AiServiceClient {
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    @Value("${shuttle.ai.service-url:http://localhost:8000}") private String serviceUrl;
    @Value("${shuttle.ai.enabled:true}") private boolean enabled;
    @Value("${shuttle.ai.timeout-ms:3000}") private int timeoutMs;
    @Value("${shuttle.ai.service-token:}") private String serviceToken;

    private RestTemplate restTemplate;
    private boolean available = false;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        restTemplate = new RestTemplate(factory);
        checkHealth();
    }

    public void checkHealth() {
        if (!enabled) { available = false; return; }
        try {
            String resp = get(serviceUrl + "/health");
            available = resp != null && resp.contains("ok");
            log.info("AI服务健康检查: {} -> {}", serviceUrl, available ? "可用" : "不可用");
        } catch (Exception e) {
            available = false;
            log.warn("AI服务不可用，将降级为Java内嵌规则: {}", e.getMessage());
        }
    }

    public boolean isAvailable() { return available; }

    public Map<String, Object> kalmanFuse(Long stationId, Long lineId, String direction,
                                           int observedCount, double variance, String source) {
        if (!available) return null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("station_id", stationId);
            body.put("line_id", lineId);
            body.put("direction", direction);
            body.put("observed_count", observedCount);
            body.put("variance", variance);
            body.put("source", source);
            String resp = post(serviceUrl + "/kalman", body);
            return parseResp(resp);
        } catch (Exception e) {
            log.warn("AI kalman调用失败，降级: {}", e.getMessage());
            available = false;
            return null;
        }
    }

    public Map<String, Object> predict(Long stationId, Long lineId, String direction, java.util.List<Integer> history) {
        if (!available) return null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("station_id", stationId);
            body.put("line_id", lineId);
            body.put("direction", direction);
            body.put("history", history);
            String resp = post(serviceUrl + "/predict", body);
            return parseResp(resp);
        } catch (Exception e) {
            log.warn("AI predict调用失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> cusumDetect(Long stationId, Long lineId, String direction, double arrivalRate) {
        if (!available) return null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("station_id", stationId);
            body.put("line_id", lineId);
            body.put("direction", direction);
            body.put("arrival_rate", arrivalRate);
            String resp = post(serviceUrl + "/cusum", body);
            return parseResp(resp);
        } catch (Exception e) {
            log.warn("AI cusum调用失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> assign(Long stationId, Long lineId, String direction, int waiting) {
        if (!available) return null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("station_id", stationId);
            body.put("line_id", lineId);
            body.put("direction", direction);
            body.put("waiting", waiting);
            String resp = post(serviceUrl + "/assign", body);
            return parseResp(resp);
        } catch (Exception e) {
            log.warn("AI assign调用失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> coursePredict() {
        if (!available) return null;
        try {
            String resp = get(serviceUrl + "/course-predict");
            return parseResp(resp);
        } catch (Exception e) {
            log.warn("AI course-predict调用失败: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> ablation(Long stationId, Long lineId, String direction, int waiting) {
        if (!available) return null;
        try {
            String url = String.format("%s/ablation?station_id=%d&line_id=%d&direction=%s&waiting=%d",
                    serviceUrl, stationId, lineId, direction, waiting);
            String resp = get(url);
            return parseResp(resp);
        } catch (Exception e) {
            log.warn("AI ablation调用失败: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> cusumStatus() {
        if (!available) return null;
        try {
            return parseResp(get(serviceUrl + "/cusum/status"));
        } catch (Exception e) {
            log.warn("AI服务CUSUM状态查询失败: {}", e.getMessage());
            return null;
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (serviceToken != null && !serviceToken.isEmpty()) headers.set("X-Shuttle-Service-Token", serviceToken);
        return headers;
    }

    private String get(String url) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<Object>(internalHeaders()), String.class).getBody();
    }

    private String post(String url, Object body) {
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<Object>(body, internalHeaders()), String.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResp(String resp) {
        if (resp == null || resp.isEmpty()) return null;
        JSONObject json = JSON.parseObject(resp);
        if (json == null) return null;
        return new HashMap<>(json);
    }
}
