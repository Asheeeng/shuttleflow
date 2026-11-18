package com.gzu.shuttle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gzu.shuttle.entity.Line;
import com.gzu.shuttle.entity.Task;
import com.gzu.shuttle.entity.Vehicle;
import com.gzu.shuttle.mapper.LineMapper;
import com.gzu.shuttle.mapper.TaskMapper;
import com.gzu.shuttle.mapper.VehicleMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceDispatchTest {
    @Mock private TaskMapper taskMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private LineMapper lineMapper;
    @Mock private EtaService etaService;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(taskMapper, vehicleMapper, lineMapper, etaService);
    }

    @Test
    void selectsLowestCostVehicleAndFiltersOppositeDirection() {
        Task task = task("go");
        Vehicle overloaded = vehicle(1L, "go", "running", 12, 13, 2L);
        Vehicle farther = vehicle(2L, "go", "running", 1, 13, 3L);
        Vehicle nearest = vehicle(3L, "go", "running", 2, 13, 4L);
        Vehicle oppositeDirection = vehicle(4L, "back", "running", 0, 13, 4L);

        when(taskMapper.selectForUpdate(10L)).thenReturn(task);
        when(lineMapper.selectById(1L)).thenReturn(new Line());
        when(vehicleMapper.selectList(any())).thenReturn(new ArrayList<Vehicle>(Arrays.asList(
                overloaded, farther, nearest, oppositeDirection)));
        when(vehicleMapper.selectForUpdate(3L)).thenReturn(nearest);
        when(vehicleMapper.updateById(any(Vehicle.class))).thenReturn(1);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);
        when(etaService.estimateTravelSec(2L, 4L, 1L, "go")).thenReturn(60);
        when(etaService.estimateTravelSec(3L, 4L, 1L, "go")).thenReturn(600);
        when(etaService.estimateTravelSec(4L, 4L, 1L, "go")).thenReturn(30);

        Task assigned = service.assignTask(10L);

        assertEquals("assigned", assigned.getStatus());
        assertEquals(3L, assigned.getVehicleId());
        assertEquals("go", assigned.getDirection());
        verify(vehicleMapper, never()).selectForUpdate(4L);
    }

    @Test
    void dispatchIdempotencyKeyIncludesDirection() throws Exception {
        Method method = TaskService.class.getDeclaredMethod("dispatchKey", Long.class, Long.class,
                String.class, String.class);
        method.setAccessible(true);
        String go = (String) method.invoke(service, 1L, 4L, "go", "ORANGE_SURGE");
        String back = (String) method.invoke(service, 1L, 4L, "back", "ORANGE_SURGE");
        assertNotEquals(go, back);
    }

    @Test
    void refusesToAssignLegacyTaskWithoutDirection() {
        Task task = task(null);
        when(taskMapper.selectForUpdate(10L)).thenReturn(task);
        when(taskMapper.updateById(any(Task.class))).thenReturn(1);

        Task escalated = service.assignTask(10L);

        assertEquals("escalated", escalated.getStatus());
        assertEquals("TASK_DIRECTION_MISSING", escalated.getReasonCode());
        verify(vehicleMapper, never()).selectList(any());
    }

    private Task task(String direction) {
        Task task = new Task();
        task.setId(10L); task.setLineId(1L); task.setStationId(4L);
        task.setDirection(direction); task.setStatus("pending"); task.setSuggestion("测试调度");
        return task;
    }

    private Vehicle vehicle(Long id, String direction, String status, int load, int capacity, Long stationId) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id); vehicle.setLineId(1L); vehicle.setDirection(direction);
        vehicle.setStatus(status); vehicle.setRunningToday(1); vehicle.setCurrentLoad(load);
        vehicle.setCapacity(capacity); vehicle.setCurrentStationId(stationId);
        vehicle.setPlateNo(id.toString());
        return vehicle;
    }
}
