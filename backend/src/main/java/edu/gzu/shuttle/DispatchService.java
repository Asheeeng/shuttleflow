package edu.gzu.shuttle;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchService {
  final DispatchRepo orders;
  final VehicleRepo vehicles;
  final TaskRepo tasks;
  final FleetService fleet;
  final CapacityService capacity;
  final CatalogService catalog;
  final Clock clock;
  final Duration ttl;
  final UserRepo users;

  DispatchService(
      DispatchRepo o,
      VehicleRepo v,
      TaskRepo t,
      FleetService f,
      CapacityService c,
      CatalogService ca,
      Clock clock,
      @Value("${app.dispatch-offer-ttl:PT2M}") Duration ttl,
      UserRepo users) {
    orders = o;
    vehicles = v;
    tasks = t;
    fleet = f;
    capacity = c;
    catalog = ca;
    this.clock = clock;
    this.ttl = ttl;
    this.users = users;
  }

  record Candidate(
      Long vehicleId,
      String vehicleCode,
      Long driverId,
      String driverName,
      int availableCapacity,
      long etaSeconds,
      Instant estimatedArrivalAt,
      String reason) {}

  @Transactional
  public List<Candidate> candidates(Long routeId, Long stopId) {
    RouteStop stop = catalog.stop(stopId);
    if (!stop.routeId.equals(routeId)) throw BusinessException.bad("INVALID_JOURNEY", "目标站不属于所选线路");
    List<Candidate> result = new ArrayList<>();
    Instant now = clock.instant();
    var history = orders.findAll();
    for (var v : vehicles.findAll()) {
      var d = fleet.today(v.id);
      if (!d.status.equals("STANDBY")
          || d.driverId == null
          || !routeId.equals(d.routeId)
          || d.stagingStopId == null) continue;
      if (history.stream().anyMatch(o -> v.id.equals(o.activeVehicleId))) continue;
      if (history.stream()
          .anyMatch(
              o ->
                  o.vehicleId.equals(v.id)
                      && o.targetStopId.equals(stopId)
                      && Set.of("REJECTED", "TIMED_OUT").contains(o.status)
                      && o.createdAt.isAfter(now.minusSeconds(600)))) continue;
      RouteStop staging = catalog.stop(d.stagingStopId);
      if (staging.stopOrder >= stop.stopOrder) continue;
      var task = tasks.findByDriverId(d.driverId).orElse(null);
      if (task == null
          || !task.vehicleId.equals(v.id)
          || !task.routeId.equals(routeId)
          || !task.occupancyConfirmed) continue;
      int seats = capacity.candidateCapacity(task, v, d, stopId);
      if (seats <= 0) continue;
      long seconds =
          d.readyInSeconds
              + catalog.stops.findByRouteIdOrderByStopOrder(routeId).stream()
                  .filter(s -> s.stopOrder > staging.stopOrder && s.stopOrder <= stop.stopOrder)
                  .mapToLong(s -> s.secondsFromPrevious)
                  .sum();
      if (seconds > capacity.horizon().toSeconds()) continue;
      result.add(
          new Candidate(
              v.id,
              v.code,
              d.driverId,
              users.findById(d.driverId).orElseThrow().displayName,
              seats,
              seconds,
              now.plusSeconds(seconds),
              "今日待命；位于"
                  + staging.name
                  + "，准备"
                  + d.readyInSeconds / 60
                  + "分钟，按同向站间耗时累计；预计余座已扣除上游候车需求并计入已核实下客计划"));
    }
    return result.stream()
        .sorted(
            Comparator.comparingLong(Candidate::etaSeconds)
                .thenComparing(Comparator.comparingInt(Candidate::availableCapacity).reversed())
                .thenComparing(Candidate::vehicleId))
        .toList();
  }

  @Transactional
  public Map<String, Object> board() {
    expire();
    var alerts = capacity.alerts(null);
    return Api.map(
        "alerts",
        alerts,
        "tasks",
        orders.findTop100ByOrderByCreatedAtDescIdDesc().stream().map(this::view).toList(),
        "suggestions",
        alerts.stream()
            .filter(a -> (int) a.get("uncoveredGap") >= capacity.threshold())
            .map(
                a ->
                    Api.map(
                        "routeId",
                        a.get("routeId"),
                        "stopId",
                        a.get("stopId"),
                        "stopName",
                        a.get("stopName"),
                        "gap",
                        a.get("uncoveredGap"),
                        "candidates",
                        candidates((Long) a.get("routeId"), (Long) a.get("stopId"))))
            .toList(),
        "serverTime",
        clock.instant());
  }

  @Transactional
  public DispatchTask create(Long admin, Long route, Long stop, Long vehicle, Long previous) {
    // Route lock serializes simultaneous suggestions at one station, avoiding duplicate coverage.
    expire();
    catalog.routes.lock(route).orElseThrow(BusinessException::missing);
    vehicles.lock(vehicle).orElseThrow(BusinessException::missing);
    var alert =
        capacity.alerts(route).stream()
            .filter(a -> a.get("stopId").equals(stop))
            .findFirst()
            .orElseThrow(BusinessException::missing);
    int gap = (int) alert.get("uncoveredGap");
    if (gap < capacity.threshold())
      throw BusinessException.conflict("NO_CAPACITY_GAP", "当前缺口已低于阈值或已被待接单运力覆盖，请刷新建议");
    var candidate =
        candidates(route, stop).stream()
            .filter(c -> c.vehicleId().equals(vehicle))
            .findFirst()
            .orElseThrow(
                () ->
                    BusinessException.conflict(
                        "VEHICLE_NOT_ELIGIBLE", "车辆已被占用、不可用、拒绝过或不符合当前线路方向/到站窗口"));
    DispatchTask o = new DispatchTask();
    o.vehicleId = vehicle;
    o.activeVehicleId = vehicle;
    o.driverId = candidate.driverId();
    o.routeId = route;
    o.targetStopId = stop;
    o.createdBy = admin;
    o.previousTaskId = previous;
    o.status = "PENDING";
    o.createdAt = clock.instant();
    o.deadlineAt = o.createdAt.plus(ttl);
    o.estimatedArrivalAt = candidate.estimatedArrivalAt();
    o.plannedCapacity = candidate.availableCapacity();
    o.reason =
        catalog.stop(stop).name
            + "有效需求"
            + alert.get("demand")
            + "人，预计可承接"
            + alert.get("allocated")
            + "人，未覆盖缺口"
            + gap
            + "人；推荐"
            + candidate.vehicleCode()
            + "，预计"
            + (candidate.etaSeconds() + 59) / 60
            + "分钟抵达，预计可用"
            + candidate.availableCapacity()
            + "座。"
            + candidate.reason();
    return orders.saveAndFlush(o);
  }

  @Transactional
  public DispatchTask respond(
      Long driver, Long id, boolean accept, String note, Integer occupancy) {
    DispatchTask meta = orders.findById(id).orElseThrow(BusinessException::missing);
    if (!meta.driverId.equals(driver)) throw BusinessException.missing();
    vehicles.lock(meta.vehicleId).orElseThrow();
    DispatchTask o = orders.lock(id).orElseThrow();
    if (!o.driverId.equals(driver)) throw BusinessException.missing();
    if (o.status.equals("PENDING") && !o.deadlineAt.isAfter(clock.instant())) {
      timeout(o);
      return o;
    }
    if (!o.status.equals("PENDING"))
      throw BusinessException.conflict("TASK_FINISHED", "该任务已处理，不能重复接受或拒绝");
    var day = fleet.today(o.vehicleId);
    if (!accept) {
      if (note == null || note.isBlank()) throw BusinessException.bad("REASON_REQUIRED", "请填写拒绝原因");
      o.status = "REJECTED";
      o.responseNote = note;
      o.escalated = true;
      o.activeVehicleId = null;
      o.respondedAt = clock.instant();
      return o;
    }
    if (!day.status.equals("STANDBY"))
      throw BusinessException.conflict("VEHICLE_NOT_ELIGIBLE", "车辆已不是待命状态，请联系管理员");
    if (occupancy == null) throw BusinessException.bad("COUNTS_REQUIRED", "接受前请核实当前车内人数");
    var v = vehicles.findById(o.vehicleId).orElseThrow();
    DriverService.occupancy(occupancy, 0, 0, v.capacity);
    if (occupancy == v.capacity)
      throw BusinessException.bad("NO_FREE_SEATS", "当前车辆满载，不能接受增援任务；请核实人数或拒绝并说明原因");
    RunTask t = tasks.lockDriver(driver).orElseThrow(BusinessException::missing);
    if (!t.vehicleId.equals(o.vehicleId) || !t.routeId.equals(o.routeId))
      throw BusinessException.conflict("BINDING_CHANGED", "车辆绑定已变化，请联系管理员");
    t.status = "RUNNING";
    t.lastStopId = day.stagingStopId;
    t.lastReportAt = clock.instant();
    t.occupancy = occupancy;
    t.occupancyConfirmed = true;
    t.occupancyUpdatedAt = clock.instant();
    day.status = "RUNNING";
    day.updatedAt = clock.instant();
    day.reason = "司机接受调度并确认出发位置与载客人数";
    long travel =
        catalog.stops.findByRouteIdOrderByStopOrder(o.routeId).stream()
            .filter(
                s ->
                    s.stopOrder > catalog.stop(day.stagingStopId).stopOrder
                        && s.stopOrder <= catalog.stop(o.targetStopId).stopOrder)
            .mapToLong(s -> s.secondsFromPrevious)
            .sum();
    o.estimatedArrivalAt = clock.instant().plusSeconds(travel);
    o.status = "ACCEPTED";
    o.respondedAt = clock.instant();
    o.responseNote = "司机确认从" + catalog.stop(day.stagingStopId).name + "出发，当前" + occupancy + "人";
    return o;
  }

  @Transactional
  public DispatchTask retry(Long admin, Long id) {
    var old = orders.findById(id).orElseThrow(BusinessException::missing);
    catalog.routes.lock(old.routeId).orElseThrow();
    old = orders.findById(id).orElseThrow();
    if (!Set.of("REJECTED", "TIMED_OUT").contains(old.status) || !old.escalated)
      throw BusinessException.conflict("RETRY_NOT_ALLOWED", "仅被拒绝或超时且未处理的任务可顺延");
    var next = candidates(old.routeId, old.targetStopId);
    if (next.isEmpty())
      throw BusinessException.conflict("NO_CANDIDATE", "没有其他符合条件的待命车，已升级管理员，请调整当日车辆状态");
    var result = create(admin, old.routeId, old.targetStopId, next.get(0).vehicleId(), old.id);
    old.escalated = false;
    return result;
  }

  @Transactional
  public DispatchTask resolve(Long admin, Long id, String note) {
    var o = orders.lock(id).orElseThrow(BusinessException::missing);
    if (!o.escalated || !Set.of("REJECTED", "TIMED_OUT").contains(o.status))
      throw BusinessException.conflict("RESOLUTION_NOT_ALLOWED", "仅升级管理员的拒绝或超时任务可标记处理");
    if (note == null || note.isBlank()) throw BusinessException.bad("REASON_REQUIRED", "请记录人工处理结果");
    o.escalated = false;
    o.resolvedAt = clock.instant();
    o.resolvedBy = admin;
    o.resolutionNote = note;
    return o;
  }

  @Transactional
  public DispatchTask cancel(Long id) {
    var meta = orders.findById(id).orElseThrow(BusinessException::missing);
    vehicles.lock(meta.vehicleId).orElseThrow();
    var o = orders.lock(id).orElseThrow();
    if (!o.status.equals("PENDING"))
      throw BusinessException.conflict("TASK_NOT_PENDING", "只可撤回尚未接受的派单");
    o.status = "CANCELLED";
    o.activeVehicleId = null;
    o.respondedAt = clock.instant();
    o.responseNote = "管理员撤回";
    return o;
  }

  @Transactional
  @Scheduled(fixedDelayString = "${app.dispatch-scan-ms:5000}")
  public void expire() {
    for (var meta : orders.overdue(clock.instant())) {
      vehicles.lock(meta.vehicleId).orElseThrow();
      var o = orders.lock(meta.id).orElseThrow();
      if (o.status.equals("PENDING") && !o.deadlineAt.isAfter(clock.instant())) timeout(o);
    }
    for (var meta : orders.findAll())
      if (meta.status.equals("ACCEPTED")
          && !meta.estimatedArrivalAt.plusSeconds(300).isAfter(clock.instant())) {
        vehicles.lock(meta.vehicleId).orElseThrow();
        var o = orders.lock(meta.id).orElseThrow();
        if (o.status.equals("ACCEPTED")
            && !o.estimatedArrivalAt.plusSeconds(300).isAfter(clock.instant())) {
          timeout(o);
          o.responseNote = "执行超时：预计到站后5分钟仍未收到目标站上报，已升级管理员核实；车辆运行状态保留，请勿将其视为停运";
        }
      }
  }

  private void timeout(DispatchTask o) {
    o.status = "TIMED_OUT";
    o.activeVehicleId = null;
    o.escalated = true;
    o.responseNote = "司机未在应答期限内处理，已升级管理员";
    o.respondedAt = clock.instant();
  }

  Map<String, Object> view(DispatchTask o) {
    return Api.map(
        "id",
        o.id,
        "vehicleId",
        o.vehicleId,
        "vehicleCode",
        vehicles.findById(o.vehicleId).orElseThrow().code,
        "driverId",
        o.driverId,
        "driverName",
        users.findById(o.driverId).orElseThrow().displayName,
        "routeId",
        o.routeId,
        "targetStopId",
        o.targetStopId,
        "targetStopName",
        catalog.stop(o.targetStopId).name,
        "status",
        o.status,
        "reason",
        o.reason,
        "responseNote",
        o.responseNote,
        "escalated",
        o.escalated,
        "previousTaskId",
        o.previousTaskId,
        "createdAt",
        o.createdAt,
        "deadlineAt",
        o.deadlineAt,
        "estimatedArrivalAt",
        o.estimatedArrivalAt,
        "plannedCapacity",
        o.plannedCapacity,
        "completedAt",
        o.completedAt,
        "resolvedAt",
        o.resolvedAt,
        "resolvedBy",
        o.resolvedBy,
        "resolutionNote",
        o.resolutionNote);
  }

  @Transactional
  public List<Map<String, Object>> driverTasks(Long driver) {
    expire();
    return orders.findTop100ByOrderByCreatedAtDescIdDesc().stream()
        .filter(o -> o.driverId.equals(driver))
        .map(this::view)
        .toList();
  }
}
