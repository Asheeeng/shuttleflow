package edu.gzu.shuttle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessController {
  final CatalogService catalog;
  final WaitingService waiting;
  final DriverService drivers;
  final EtaService eta;
  final AuthController auth;
  final WaitRepo waits;
  final TaskRepo tasks;
  final ArrivalRepo arrivals;
  final Clock clock;
  final FleetService fleet;
  final org.springframework.core.env.Environment env;

  BusinessController(
      CatalogService c,
      WaitingService w,
      DriverService d,
      EtaService e,
      AuthController a,
      WaitRepo wr,
      TaskRepo t,
      ArrivalRepo ar,
      Clock cl,
      org.springframework.core.env.Environment env,
      FleetService fleet) {
    catalog = c;
    waiting = w;
    drivers = d;
    eta = e;
    auth = a;
    waits = wr;
    tasks = t;
    arrivals = ar;
    clock = cl;
    this.env = env;
    this.fleet = fleet;
  }

  @GetMapping("/routes")
  Api<?> routes() {
    return Api.ok(catalog.routes.findAll().stream().map(catalog::routeView).toList());
  }

  @GetMapping("/routes/{id}")
  Api<?> route(@PathVariable Long id) {
    return Api.ok(catalog.routeView(catalog.route(id)));
  }

  record CreateWait(
      @NotNull Long routeId,
      @NotNull Long originStopId,
      @NotNull Long destinationStopId,
      @Min(1) @Max(20) int partySize) {}

  record Board(@Min(1) @Max(20) Integer count) {}

  record Arrive(
      @NotNull Long stopId,
      @NotNull Boolean countsConfirmed,
      @Min(0) @Max(20) Integer boarded,
      @Min(0) @Max(20) Integer alighted,
      @Min(0) @Max(20) Integer occupancyBefore) {}

  @PostMapping("/passenger/requests")
  Api<?> create(Authentication a, @Valid @RequestBody CreateWait b) {
    return Api.ok(
        waiting.view(
            waiting.create(
                auth.current(a).id,
                b.routeId(),
                b.originStopId(),
                b.destinationStopId(),
                b.partySize())));
  }

  @GetMapping("/passenger/requests")
  Api<?> history(Authentication a) {
    return Api.ok(waiting.history(auth.current(a).id).stream().map(waiting::view).toList());
  }

  @GetMapping("/passenger/requests/current")
  Api<?> current(Authentication a) {
    return Api.ok(
        waiting.history(auth.current(a).id).stream()
            .filter(r -> r.status.equals("WAITING"))
            .findFirst()
            .map(waiting::view)
            .orElse(null));
  }

  @PostMapping("/passenger/requests/{id}/cancel")
  Api<?> cancel(Authentication a, @PathVariable Long id) {
    return Api.ok(waiting.view(waiting.finish(auth.current(a).id, id, true, null)));
  }

  @PostMapping("/passenger/requests/{id}/board")
  Api<?> board(Authentication a, @PathVariable Long id, @Valid @RequestBody Board b) {
    return Api.ok(waiting.view(waiting.finish(auth.current(a).id, id, false, b.count())));
  }

  @GetMapping("/passenger/eta")
  Api<?> eta(
      @RequestParam Long routeId,
      @RequestParam Long originStopId,
      @RequestParam Long destinationStopId) {
    return Api.ok(eta.next(routeId, originStopId, destinationStopId));
  }

  @GetMapping("/driver/task")
  Api<?> task(Authentication a) {
    return Api.ok(drivers.current(auth.current(a).id));
  }

  @PostMapping("/driver/arrivals")
  Api<?> arrive(Authentication a, @Valid @RequestBody Arrive b) {
    return Api.ok(
        drivers.arrive(
            auth.current(a).id,
            b.stopId(),
            b.countsConfirmed(),
            b.boarded(),
            b.alighted(),
            b.occupancyBefore()));
  }

  @GetMapping("/driver/demand")
  Api<?> demand(Authentication a) {
    RunTask t = drivers.task(auth.current(a).id);
    return Api.ok(
        waiting.demand(
            t.routeId, t.lastStopId == null ? -1 : catalog.stop(t.lastStopId).stopOrder));
  }

  @GetMapping("/admin/overview")
  Api<?> overview() {
    var active = waits.valid(clock.instant());
    var history = waits.findAll();
    Map<String, Long> states = new LinkedHashMap<>();
    for (String s : List.of("WAITING", "BOARDED", "CANCELLED", "EXPIRED"))
      states.put(
          s,
          history.stream()
              .filter(
                  r -> {
                    String effective =
                        r.status.equals("WAITING") && !r.expiresAt.isAfter(clock.instant())
                            ? "EXPIRED"
                            : r.status;
                    return effective.equals(s);
                  })
              .count());
    return Api.ok(
        Api.map(
            "runningVehicles",
            tasks.findAll().stream().filter(t -> fleet.running(t)).count(),
            "activeRequests",
            active.size(),
            "waitingPeople",
            active.stream().mapToInt(r -> r.partySize - r.boardedCount).sum(),
            "recentArrivals",
            arrivals.countByReportedAtAfter(clock.instant().minus(Duration.ofHours(24))),
            "requestStates",
            states,
            "updatedAt",
            clock.instant(),
            "demoResetEnabled",
            env.acceptsProfiles(
                org.springframework.core.env.Profiles.of("demo & !prod & !production"))));
  }

  @GetMapping("/admin/vehicles")
  Api<?> vehicles() {
    return Api.ok(drivers.allVehicles());
  }

  @GetMapping("/admin/demand")
  Api<?> demand() {
    return Api.ok(waiting.demand(null, null));
  }

  @GetMapping("/admin/arrivals")
  Api<?> arrivals() {
    return Api.ok(drivers.events());
  }

  @GetMapping("/admin/requests")
  Api<?> requests() {
    return Api.ok(
        waits.findAll().stream()
            .sorted(Comparator.comparing((WaitRequest r) -> r.createdAt).reversed())
            .limit(100)
            .map(
                r -> {
                  var v = waiting.view(r);
                  if (r.status.equals("WAITING") && !r.expiresAt.isAfter(clock.instant()))
                    v.put("status", "EXPIRED");
                  return v;
                })
            .toList());
  }
}
