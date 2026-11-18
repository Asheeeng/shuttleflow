package com.gzu.shuttle.service;

import java.time.LocalDateTime;
import java.util.*;
import com.gzu.shuttle.entity.Task;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class OperationClock {
    private volatile LocalDateTime simulated;
    private volatile String policy = "smart";
    private volatile Long requestFloor;
    private final ConcurrentHashMap<Long, String> trips = new ConcurrentHashMap<>();

    public LocalDateTime now() {
        LocalDateTime value = simulated;
        return value == null ? LocalDateTime.now() : value;
    }

    public boolean active() { return simulated != null; }
    public String policy() { return policy; }
    public String trip(Long vehicle) { return vehicle == null ? null : trips.get(vehicle); }

    public void advance(LocalDateTime time, String strategy) {
        policy = strategy;
        simulated = time;
    }

    public Long requestFloor() { return requestFloor; }
    public void scope(long floor) { requestFloor = floor; }

    public void bind(Long vehicle, String trip) { trips.put(vehicle, trip); }
    private final List<Map<String, Object>> taskHistory = new ArrayList<>();

    public synchronized void task(String action, Task task, Long vehicle) {
        if (!active() || taskHistory.size() >= 10000) return;
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", action);
        step.put("taskId", task.getId());
        step.put("vehicleId", vehicle);
        step.put("lineId", task.getLineId());
        step.put("direction", task.getDirection());
        step.put("stationId", task.getStationId());
        step.put("time", now().toString());
        step.put("reason", task.getSuggestion());
        taskHistory.add(step);
    }

    public synchronized List<Map<String, Object>> taskHistory() {
        return new ArrayList<>(taskHistory);
    }

    public synchronized void clear() {
        taskHistory.clear();
        trips.clear();
        requestFloor = null;
        simulated = null;
        policy = "smart";
    }
}
