package edu.gzu.shuttle;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverService {
  final TaskRepo tasks;
  final VehicleRepo vehicles;
  final ArrivalRepo arrivals;
  final CatalogService catalog;
  final Clock clock;
  final FleetService fleet;
  final DispatchRepo dispatches;
  final PlanRepo plans;

  DriverService(
      TaskRepo t,
      VehicleRepo v,
      ArrivalRepo a,
      CatalogService c,
      Clock clock,
      FleetService fleet,
      DispatchRepo dispatches,
      PlanRepo plans) {
    tasks = t;
    vehicles = v;
    arrivals = a;
    catalog = c;
    this.clock = clock;
    this.fleet = fleet;
    this.dispatches = dispatches;
    this.plans = plans;
  }

  RunTask task(Long driver) {
    return tasks
        .findByDriverId(driver)
        .orElseThrow(() -> BusinessException.conflict("NO_TASK", "当前司机没有运行任务"));
  }

  public static int occupancy(int old, int alighted, int boarded, int capacity) {
    if (old < 0 || old > capacity || alighted < 0 || boarded < 0 || alighted > old)
      throw BusinessException.bad("INVALID_OCCUPANCY", "下车人数不能超过当前载客人数，人数不能为负数");
    long next = (long) old - alighted + boarded;
    if (next > capacity) throw BusinessException.bad("CAPACITY_EXCEEDED", "上车人数超过下客后的剩余容量");
    return (int) next;
  }

  @Transactional
  public Map<String, Object> arrive(
      Long driver,
      Long stopId,
      boolean confirmed,
      Integer boarded,
      Integer alighted,
      Integer occupancyBefore) {
    var meta = task(driver);
    vehicles.lock(meta.vehicleId).orElseThrow();
    RunTask t =
        tasks
            .lockDriver(driver)
            .orElseThrow(() -> BusinessException.conflict("NO_TASK", "当前司机没有运行任务"));
    if (!fleet.running(t)) throw BusinessException.conflict("NOT_RUNNING", "车辆当前未运行，不能上报到站");
    RouteStop stop = catalog.stop(stopId);
    int last = t.lastStopId == null ? -1 : catalog.stop(t.lastStopId).stopOrder;
    if (!stop.routeId.equals(t.routeId) || stop.stopOrder != last + 1)
      throw BusinessException.bad("INVALID_STOP_SEQUENCE", "请按运行方向依次上报下一站，不可跳站或重复到站");
    Vehicle v = vehicles.findById(t.vehicleId).orElseThrow();
    Instant now = clock.instant();
    if (confirmed) {
      if (boarded == null || alighted == null)
        throw BusinessException.bad("COUNTS_REQUIRED", "请填写上车和下车人数");
      if (!t.occupancyConfirmed && occupancyBefore == null)
        throw BusinessException.bad("OCCUPANCY_RECONFIRM_REQUIRED", "上次人数未确认，请先核实本站上下车前载客人数");
      if (t.occupancyConfirmed && occupancyBefore != null)
        throw BusinessException.bad("UNEXPECTED_OCCUPANCY_OVERRIDE", "当前人数已确认，无需覆盖载客人数");
      int old = t.occupancyConfirmed ? t.occupancy : occupancyBefore;
      t.occupancy = occupancy(old, alighted, boarded, v.capacity);
      t.occupancyUpdatedAt = now;
    } else if (boarded != null || alighted != null || occupancyBefore != null)
      throw BusinessException.bad("UNCONFIRMED_COUNTS", "人数未确认时，请勿提交人数");
    t.occupancyConfirmed = confirmed;
    t.lastStopId = stopId;
    t.lastReportAt = now;
    ArrivalEvent e = new ArrivalEvent();
    e.taskId = t.id;
    e.driverId = driver;
    e.stopId = stopId;
    e.reportedAt = now;
    e.boarded = boarded;
    e.alighted = alighted;
    e.occupancyAfter = t.occupancy;
    e.countsConfirmed = confirmed;
    arrivals.save(e);
    var day = fleet.today(t.vehicleId);
    day.stagingStopId = stopId;
    day.updatedAt = now;
    plans.findByTaskIdAndStopId(t.id, stopId).ifPresent(plans::delete);
    for (var order : dispatches.findAll())
      if (order.vehicleId.equals(t.vehicleId)
          && order.status.equals("ACCEPTED")
          && order.targetStopId.equals(stopId)) {
        order.status = "COMPLETED";
        order.activeVehicleId = null;
        order.completedAt = now;
        order.responseNote = "司机已按线路到达支援站点；实际上下车以本次到站记录为准";
      }
    return view(t, v);
  }

  public Map<String, Object> view(RunTask t, Vehicle v) {
    var day = fleet.today(v.id);
    RouteStop next =
        t == null
            ? null
            : catalog.stops.findByRouteIdOrderByStopOrder(t.routeId).stream()
                .filter(
                    s ->
                        s.stopOrder
                            > (t.lastStopId == null ? -1 : catalog.stop(t.lastStopId).stopOrder))
                .findFirst()
                .orElse(null);
    return Api.map(
        "taskId",
        t == null ? null : t.id,
        "vehicleId",
        v.id,
        "vehicleCode",
        v.code,
        "capacity",
        v.capacity,
        "status",
        t == null || !day.status.equals("RUNNING") ? day.status : t.status,
        "routeId",
        t == null ? null : t.routeId,
        "routeName",
        t == null ? "未分配" : catalog.route(t.routeId).name,
        "direction",
        t == null ? "—" : catalog.route(t.routeId).direction,
        "lastStopId",
        t == null ? null : t.lastStopId,
        "lastStopName",
        t == null || t.lastStopId == null ? "尚未上报" : catalog.stop(t.lastStopId).name,
        "lastReportAt",
        t == null ? null : t.lastReportAt,
        "occupancy",
        t == null ? null : t.occupancy,
        "occupancyConfirmed",
        t != null && t.occupancyConfirmed,
        "occupancyUpdatedAt",
        t == null ? null : t.occupancyUpdatedAt,
        "remainingCapacity",
        t == null || !t.occupancyConfirmed ? null : v.capacity - t.occupancy,
        "nextStop",
        next);
  }

  Map<String, Object> current(Long driver) {
    RunTask t = task(driver);
    return view(t, vehicles.findById(t.vehicleId).orElseThrow());
  }

  List<Map<String, Object>> allVehicles() {
    return vehicles.findAll().stream()
        .map(
            v ->
                view(
                    tasks.findAll().stream()
                        .filter(t -> t.vehicleId.equals(v.id))
                        .findFirst()
                        .orElse(null),
                    v))
        .toList();
  }

  List<Map<String, Object>> events() {
    return arrivals.findTop50ByOrderByReportedAtDescIdDesc().stream()
        .map(
            e -> {
              RunTask t = tasks.findById(e.taskId).orElseThrow();
              return Api.map(
                  "id",
                  e.id,
                  "vehicleCode",
                  vehicles.findById(t.vehicleId).orElseThrow().code,
                  "stopName",
                  catalog.stop(e.stopId).name,
                  "reportedAt",
                  e.reportedAt,
                  "boarded",
                  e.boarded,
                  "alighted",
                  e.alighted,
                  "occupancyAfter",
                  e.occupancyAfter,
                  "countsConfirmed",
                  e.countsConfirmed);
            })
        .toList();
  }
}
