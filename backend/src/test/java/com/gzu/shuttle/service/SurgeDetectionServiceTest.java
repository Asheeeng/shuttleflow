package com.gzu.shuttle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.ShuttleEvent;
import com.gzu.shuttle.mapper.ShuttleEventMapper;
import com.gzu.shuttle.websocket.ShuttleWebSocketHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SurgeDetectionServiceTest {
    @Mock private ShuttleEventMapper eventMapper;
    @Mock private AlertService alertService;
    @Mock private TaskService taskService;

    @Test
    void manualSimulationTickUsesOnlyNewCheckinsAndPreservesDirection() {
        SurgeDetectionService service = new SurgeDetectionService();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "alertService", alertService);
        ReflectionTestUtils.setField(service, "taskService", taskService);
        List<ShuttleEvent> window = new ArrayList<ShuttleEvent>();
        for (long id = 1; id <= 14; id++) window.add(event(id, "checkin", 19L, 3L, "back"));
        window.add(event(15L, "board", 19L, 3L, "back"));
        window.add(event(16L, "cancel", 19L, 3L, "back"));
        when(eventMapper.selectList(any(QueryWrapper.class))).thenReturn(window, new ArrayList<ShuttleEvent>());

        Map<String, Object> result = service.detectNow();
        assertEquals(16L, result.get("processedThroughEventId"));
        verify(taskService).createDispatchTask(eq(3L), eq(19L), eq("back"), eq(7),
                eq("CUSUM_ORANGE"), any(String.class));
        verify(alertService).createAlert(eq("orange"), eq("surge_cusum"), eq(19L), isNull(), eq(3L), any(String.class));

        service.detectNow();
        verify(taskService).createDispatchTask(eq(3L), eq(19L), eq("back"), eq(7),
                eq("CUSUM_ORANGE"), any(String.class));
    }

    @Test
    void schedulerWrapperCanBeDisabledInIsolatedApiDrivenSimulation() {
        SurgeDetectionService service = new SurgeDetectionService();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "schedulerEnabled", false);
        service.detect();
        verify(eventMapper, never()).selectList(any(QueryWrapper.class));
    }

    private ShuttleEvent event(Long id, String type, Long station, Long line, String direction) {
        ShuttleEvent event = new ShuttleEvent();
        event.setId(id); event.setType(type); event.setStationId(station);
        event.setLineId(line); event.setDirection(direction); event.setProcessed(1);
        return event;
    }
}
