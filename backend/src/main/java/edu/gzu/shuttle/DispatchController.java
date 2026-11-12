package edu.gzu.shuttle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DispatchController {
  final DispatchService dispatch;
  final FleetService fleet;
  final CapacityService capacity;
  final AuthController auth;
  final PlanRepo plans;
  final TaskRepo tasks;
  final CatalogService catalog;
  final java.time.Clock clock;
  final VehicleRepo vehicles;

  DispatchController(
      DispatchService d,
      FleetService f,
      CapacityService c,
      AuthController a,
      PlanRepo p,
      TaskRepo t,
      CatalogService ca,
      java.time.Clock clock,
      VehicleRepo vehicles) {
    dispatch = d;
    fleet = f;
    capacity = c;
    auth = a;
    plans = p;
    tasks = t;
    catalog = ca;
    this.clock = clock;
    this.vehicles = vehicles;
  }

  record Create(@NotNull Long routeId, @NotNull Long targetStopId, @NotNull Long vehicleId) {}

  record Respond(
      @NotNull Boolean accept, @Size(max = 300) String note, @Min(0) @Max(100) Integer occupancy) {}

  record Fleet(
      @NotBlank String status,
      @Size(max = 300) String reason,
      @Min(0) @Max(600) int readyInSeconds) {}

  record Resolve(@NotBlank @Size(max = 300) String note) {}

  @PostMapping("/admin/dispatches/{id}/resolve")
  Api<?> resolve(Authentication a, @PathVariable Long id, @Valid @RequestBody Resolve b) {
    return Api.ok(dispatch.view(dispatch.resolve(auth.current(a).id, id, b.note())));
  }

  record Plan(@NotNull Long stopId, @Min(0) @Max(100) int expectedAlighted) {}

  @GetMapping("/admin/dispatch-board")
  Api<?> board() {
    return Api.ok(dispatch.board());
  }

  @GetMapping("/admin/fleet")
  Api<?> fleet() {
    return Api.ok(fleet.list());
  }

  @PatchMapping("/admin/fleet/{id}")
  Api<?> update(@PathVariable Long id, @Valid @RequestBody Fleet b) {
    return Api.ok(fleet.update(id, b.status(), b.reason(), b.readyInSeconds()));
  }

  @PostMapping("/admin/dispatches")
  Api<?> create(Authentication a, @Valid @RequestBody Create b) {
    return Api.ok(
        dispatch.view(
            dispatch.create(
                auth.current(a).id, b.routeId(), b.targetStopId(), b.vehicleId(), null)));
  }

  @PostMapping("/admin/dispatches/{id}/retry")
  Api<?> retry(Authentication a, @PathVariable Long id) {
    return Api.ok(dispatch.view(dispatch.retry(auth.current(a).id, id)));
  }

  @PostMapping("/admin/dispatches/{id}/cancel")
  Api<?> cancel(@PathVariable Long id) {
    return Api.ok(dispatch.view(dispatch.cancel(id)));
  }

  @GetMapping("/driver/dispatches")
  Api<?> inbox(Authentication a) {
    return Api.ok(dispatch.driverTasks(auth.current(a).id));
  }

  @PostMapping("/driver/dispatches/{id}/respond")
  Api<?> respond(Authentication a, @PathVariable Long id, @Valid @RequestBody Respond b) {
    return Api.ok(
        dispatch.view(
            dispatch.respond(auth.current(a).id, id, b.accept(), b.note(), b.occupancy())));
  }

  @GetMapping("/driver/alighting-plans")
  Api<?> plans(Authentication a) {
    var t = tasks.findByDriverId(auth.current(a).id).orElseThrow(BusinessException::missing);
    return Api.ok(plans.findByTaskId(t.id));
  }

  @PostMapping("/driver/alighting-plans")
  @org.springframework.transaction.annotation.Transactional
  Api<?> plan(Authentication a, @Valid @RequestBody Plan b) {
    var t = tasks.lockDriver(auth.current(a).id).orElseThrow(BusinessException::missing);
    var s = catalog.stop(b.stopId());
    if (!s.routeId.equals(t.routeId)
        || t.lastStopId != null && s.stopOrder <= catalog.stop(t.lastStopId).stopOrder)
      throw BusinessException.bad("INVALID_STOP_SEQUENCE", "只可为本线路后续站点填写下客计划");
    if (!t.occupancyConfirmed)
      throw BusinessException.bad("OCCUPANCY_RECONFIRM_REQUIRED", "人数未确认，不能计算下客计划");
    int others =
        plans.findByTaskId(t.id).stream()
            .filter(
                p ->
                    !p.stopId.equals(s.id)
                        && catalog.stop(p.stopId).stopOrder
                            > (t.lastStopId == null ? -1 : catalog.stop(t.lastStopId).stopOrder))
            .mapToInt(p -> p.expectedAlighted)
            .sum();
    if (others + b.expectedAlighted() > t.occupancy)
      throw BusinessException.bad("INVALID_ALIGHTING_PLAN", "后续计划下车总人数不能超过最近确认的车内人数");
    var p = plans.findByTaskIdAndStopId(t.id, s.id).orElseGet(AlightingPlan::new);
    p.taskId = t.id;
    p.stopId = s.id;
    p.expectedAlighted = b.expectedAlighted();
    p.source = "司机人工下客计划（估算，实际以到站上报为准）";
    p.updatedAt = clock.instant();
    return Api.ok(plans.save(p));
  }

  @GetMapping("/passenger/service")
  Api<?> service(@RequestParam Long routeId) {
    var rows = capacity.alerts(routeId);
    return Api.ok(
        Api.map(
            "stations",
            rows,
            "vehicles",
            tasks.findAll().stream()
                .filter(t -> t.routeId.equals(routeId) && fleet.running(t))
                .map(
                    t -> {
                      var v = vehicles.findById(t.vehicleId).orElseThrow();
                      return Api.map(
                          "vehicleCode",
                          v.code,
                          "lastStopId",
                          t.lastStopId,
                          "lastStopName",
                          t.lastStopId == null ? "未上报" : catalog.stop(t.lastStopId).name,
                          "lastReportAt",
                          t.lastReportAt,
                          "occupancy",
                          t.occupancy,
                          "capacity",
                          v.capacity,
                          "occupancyConfirmed",
                          t.occupancyConfirmed);
                    })
                .toList()));
  }
}
