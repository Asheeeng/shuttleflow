package edu.gzu.shuttle;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetService {
  final DayRepo days;
  final VehicleRepo vehicles;
  final TaskRepo tasks;
  final UserRepo users;
  final CatalogService catalog;
  final DispatchRepo dispatches;
  final Clock clock;

  FleetService(
      DayRepo d,
      VehicleRepo v,
      TaskRepo t,
      UserRepo u,
      CatalogService c,
      DispatchRepo o,
      Clock clock) {
    days = d;
    vehicles = v;
    tasks = t;
    users = u;
    catalog = c;
    dispatches = o;
    this.clock = clock;
  }

  LocalDate date() {
    return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai"));
  }

  @Transactional
  public VehicleDay today(Long vehicleId) {
    var existing = days.findByVehicleIdAndServiceDate(vehicleId, date());
    if (existing.isPresent()) return existing.get();
    vehicles.lock(vehicleId).orElseThrow(BusinessException::missing);
    existing = days.findByVehicleIdAndServiceDate(vehicleId, date());
    if (existing.isPresent()) return existing.get();
    var t =
        tasks.findAll().stream()
            .filter(x -> x.vehicleId.equals(vehicleId))
            .findFirst()
            .orElse(null);
    var d = new VehicleDay();
    d.vehicleId = vehicleId;
    d.serviceDate = date();
    d.status = "UNAVAILABLE";
    d.reason = "未确认当日出勤";
    d.readyInSeconds = 60;
    d.updatedAt = clock.instant();
    if (t != null) {
      d.driverId = t.driverId;
      d.routeId = t.routeId;
      d.stagingStopId = t.lastStopId;
      // Adopt a fresh V0.1 task on upgrade, but never carry yesterday's service into today.
      if (t.lastReportAt != null
          && LocalDate.ofInstant(t.lastReportAt, ZoneId.of("Asia/Shanghai")).equals(date())) {
        d.status = t.status.equals("RUNNING") ? "RUNNING" : "STANDBY";
        d.reason = "沿用当日人工上报";
      }
    }
    return days.saveAndFlush(d);
  }

  @Transactional
  public boolean running(RunTask t) {
    return "RUNNING".equals(t.status) && "RUNNING".equals(today(t.vehicleId).status);
  }

  @Transactional
  public List<Map<String, Object>> list() {
    return vehicles.findAll().stream().map(v -> view(v, today(v.id))).toList();
  }

  Map<String, Object> view(Vehicle v, VehicleDay d) {
    return Api.map(
        "vehicleId",
        v.id,
        "vehicleCode",
        v.code,
        "capacity",
        v.capacity,
        "serviceDate",
        d.serviceDate,
        "status",
        d.status,
        "driverId",
        d.driverId,
        "driverName",
        d.driverId == null ? "未绑定" : users.findById(d.driverId).orElseThrow().displayName,
        "routeId",
        d.routeId,
        "routeName",
        d.routeId == null ? "未分配" : catalog.route(d.routeId).name,
        "stagingStopId",
        d.stagingStopId,
        "stagingStopName",
        d.stagingStopId == null ? "未确认" : catalog.stop(d.stagingStopId).name,
        "readyInSeconds",
        d.readyInSeconds,
        "reason",
        d.reason,
        "updatedAt",
        d.updatedAt);
  }

  @Transactional
  public Map<String, Object> update(Long vehicleId, String status, String reason, int ready) {
    Vehicle v = vehicles.lock(vehicleId).orElseThrow(BusinessException::missing);
    VehicleDay d = today(vehicleId);
    if (!Set.of("RUNNING", "STANDBY", "UNAVAILABLE").contains(status))
      throw BusinessException.bad("INVALID_FLEET_STATUS", "车辆状态不合法");
    if (dispatches.findAll().stream().anyMatch(o -> vehicleId.equals(o.activeVehicleId)))
      throw BusinessException.conflict("VEHICLE_RESERVED", "车辆有待接单或执行中任务，请先处理任务");
    RunTask t =
        tasks.findAll().stream()
            .filter(x -> x.vehicleId.equals(vehicleId))
            .findFirst()
            .orElse(null);
    if (!status.equals("UNAVAILABLE")
        && (d.driverId == null || d.routeId == null || d.stagingStopId == null || t == null))
      throw BusinessException.bad("FLEET_BINDING_REQUIRED", "需先绑定司机、线路、驻车站和运行任务");
    if (status.equals("RUNNING")
        && (t.lastReportAt == null || !t.lastReportAt.plusSeconds(900).isAfter(clock.instant())))
      throw BusinessException.bad("FRESH_REPORT_REQUIRED", "到站位置已过期，请通过调度任务由司机确认后出发");
    if (status.equals("UNAVAILABLE") && (reason == null || reason.isBlank()))
      throw BusinessException.bad("REASON_REQUIRED", "请填写不可用原因");
    d.status = status;
    d.reason = reason;
    d.readyInSeconds = ready;
    d.updatedAt = clock.instant();
    if (t != null) t.status = status.equals("UNAVAILABLE") ? "STOPPED" : status;
    return view(v, d);
  }
}
