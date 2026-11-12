package edu.gzu.shuttle;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapacityService {
  public record Bus(
      Long taskId,
      Long vehicleId,
      String code,
      int capacity,
      int occupancy,
      int lastOrder,
      Instant reportedAt,
      Map<Long, Integer> alighting) {}

  public record Allocation(
      Long vehicleId,
      String vehicleCode,
      Instant arrivalAt,
      int beforeAlighting,
      int alighted,
      int seatsAfterAlighting,
      int boarded,
      int occupancyAfter) {}

  public record Station(
      Long stopId,
      String stopName,
      int order,
      int demand,
      int expectedCapacity,
      int allocated,
      int gap,
      List<Allocation> vehicles) {}

  record Event(Bus bus, RouteStop stop, Instant arrival) {}

  final TaskRepo tasks;
  final VehicleRepo vehicles;
  final PlanRepo plans;
  final WaitRepo waits;
  final CatalogService catalog;
  final FleetService fleet;
  final Clock clock;
  final int threshold;
  final Duration horizon;

  public int threshold() {
    return threshold;
  }

  public Duration horizon() {
    return horizon;
  }

  final DispatchRepo dispatches;

  CapacityService(
      TaskRepo t,
      VehicleRepo v,
      PlanRepo p,
      WaitRepo w,
      CatalogService c,
      FleetService f,
      Clock clock,
      @Value("${app.peak-threshold:5}") int threshold,
      @Value("${app.capacity-horizon:PT10M}") Duration horizon,
      DispatchRepo dispatches) {
    tasks = t;
    vehicles = v;
    plans = p;
    waits = w;
    catalog = c;
    fleet = f;
    this.clock = clock;
    this.threshold = threshold;
    this.horizon = horizon;
    this.dispatches = dispatches;
  }

  // Simulate chronologically across all stops, so seats used upstream cannot be counted twice.
  public static List<Station> project(
      List<RouteStop> stops,
      List<Bus> buses,
      Map<Long, Integer> demand,
      Instant now,
      Duration horizon) {
    Map<Long, Integer> remaining = new HashMap<>(demand), loads = new HashMap<>();
    Map<Long, List<Allocation>> allocations = new HashMap<>();
    List<Event> events = new ArrayList<>();
    for (var b : buses) {
      loads.put(b.vehicleId(), b.occupancy());
      long seconds = 0;
      for (var s : stops) {
        if (s.stopOrder <= b.lastOrder()) continue;
        seconds += s.secondsFromPrevious;
        Instant at = b.reportedAt().plusSeconds(seconds);
        if (at.isAfter(now) && !at.isAfter(now.plus(horizon))) events.add(new Event(b, s, at));
      }
    }
    events.sort(
        Comparator.comparing(Event::arrival)
            .thenComparing(e -> e.bus().vehicleId())
            .thenComparingInt(e -> e.stop().stopOrder));
    for (var e : events) {
      Bus b = e.bus();
      RouteStop s = e.stop();
      int before = loads.get(b.vehicleId());
      int down = Math.min(before, Math.max(0, b.alighting().getOrDefault(s.id, 0)));
      int seats = b.capacity() - (before - down);
      int up = Math.min(Math.max(0, remaining.getOrDefault(s.id, 0)), seats);
      int after = DriverService.occupancy(before, down, up, b.capacity());
      loads.put(b.vehicleId(), after);
      remaining.put(s.id, Math.max(0, remaining.getOrDefault(s.id, 0) - up));
      allocations
          .computeIfAbsent(s.id, k -> new ArrayList<>())
          .add(
              new Allocation(b.vehicleId(), b.code(), e.arrival(), before, down, seats, up, after));
    }
    return stops.stream()
        .map(
            s -> {
              var rows = allocations.getOrDefault(s.id, List.of());
              int need = demand.getOrDefault(s.id, 0),
                  allocated = rows.stream().mapToInt(Allocation::boarded).sum();
              return new Station(
                  s.id,
                  s.name,
                  s.stopOrder,
                  need,
                  rows.stream().mapToInt(Allocation::seatsAfterAlighting).sum(),
                  allocated,
                  Math.max(0, need - allocated),
                  rows);
            })
        .toList();
  }

  @Transactional
  public List<Map<String, Object>> alerts(Long routeId) {
    List<ShuttleRoute> routes =
        routeId == null ? catalog.routes.findAll() : List.of(catalog.route(routeId));
    List<Map<String, Object>> result = new ArrayList<>();
    Instant now = clock.instant();
    var active = waits.valid(now);
    for (var route : routes) {
      var stops = catalog.stops.findByRouteIdOrderByStopOrder(route.id);
      Map<Long, Integer> demand = new HashMap<>();
      for (var w : active)
        if (w.routeId.equals(route.id))
          demand.merge(w.originStopId, w.partySize - w.boardedCount, Integer::sum);
      List<Bus> buses = new ArrayList<>();
      List<String> excluded = new ArrayList<>();
      for (var t : tasks.findAll())
        if (t.routeId.equals(route.id)) {
          var v = vehicles.findById(t.vehicleId).orElseThrow();
          if (!fleet.running(t)) continue;
          if (!t.occupancyConfirmed
              || t.lastReportAt == null
              || t.lastStopId == null
              || !t.lastReportAt.plusSeconds(900).isAfter(now)
              || t.lastReportAt.isAfter(now)) {
            excluded.add(v.code + "：人数未确认或到站上报过期，未计入预计运力");
            continue;
          }
          Map<Long, Integer> down = new HashMap<>();
          for (var p : plans.findByTaskId(t.id)) down.put(p.stopId, p.expectedAlighted);
          buses.add(
              new Bus(
                  t.id,
                  v.id,
                  v.code,
                  v.capacity,
                  t.occupancy,
                  catalog.stop(t.lastStopId).stopOrder,
                  t.lastReportAt,
                  down));
        }
      for (var s : project(stops, buses, demand, now, horizon)) {
        int pending = dispatchPending(route.id, s.stopId(), now);
        String level = s.gap() >= threshold ? "HIGH" : s.gap() > 0 ? "WATCH" : "NORMAL";
        result.add(
            Api.map(
                "routeId",
                route.id,
                "routeName",
                route.name,
                "stopId",
                s.stopId(),
                "stopName",
                s.stopName(),
                "stopOrder",
                s.order(),
                "demand",
                s.demand(),
                "expectedCapacity",
                s.expectedCapacity(),
                "allocated",
                s.allocated(),
                "gap",
                s.gap(),
                "pendingCapacity",
                pending,
                "uncoveredGap",
                Math.max(0, s.gap() - pending),
                "level",
                level,
                "threshold",
                threshold,
                "vehicles",
                s.vehicles(),
                "excluded",
                excluded,
                "horizonMinutes",
                horizon.toMinutes(),
                "updatedAt",
                now,
                "source",
                "有效候车请求 + 人工到站/载客上报 + 司机下客计划；演示耗时仅用于规则估算"));
      }
    }
    return result;
  }

  @Transactional
  public int candidateCapacity(RunTask candidate, Vehicle vehicle, VehicleDay day, Long target) {
    Instant now = clock.instant();
    Map<Long, Integer> demand = new HashMap<>();
    for (var w : waits.valid(now))
      if (w.routeId.equals(candidate.routeId))
        demand.merge(w.originStopId, w.partySize - w.boardedCount, Integer::sum);
    List<Bus> buses = new ArrayList<>();
    for (var t : tasks.findAll())
      if (t.routeId.equals(candidate.routeId)
          && fleet.running(t)
          && t.occupancyConfirmed
          && t.lastStopId != null
          && t.lastReportAt != null
          && t.lastReportAt.plusSeconds(900).isAfter(now)
          && !t.lastReportAt.isAfter(now)) {
        var v = vehicles.findById(t.vehicleId).orElseThrow();
        Map<Long, Integer> down = new HashMap<>();
        for (var p : plans.findByTaskId(t.id)) down.put(p.stopId, p.expectedAlighted);
        buses.add(
            new Bus(
                t.id,
                v.id,
                v.code,
                v.capacity,
                t.occupancy,
                catalog.stop(t.lastStopId).stopOrder,
                t.lastReportAt,
                down));
      }
    Map<Long, Integer> down = new HashMap<>();
    for (var p : plans.findByTaskId(candidate.id)) down.put(p.stopId, p.expectedAlighted);
    buses.add(
        new Bus(
            candidate.id,
            vehicle.id,
            vehicle.code,
            vehicle.capacity,
            candidate.occupancy,
            catalog.stop(day.stagingStopId).stopOrder,
            now.plusSeconds(day.readyInSeconds),
            down));
    return project(
            catalog.stops.findByRouteIdOrderByStopOrder(candidate.routeId),
            buses,
            demand,
            now,
            horizon)
        .stream()
        .filter(s -> s.stopId().equals(target))
        .flatMap(s -> s.vehicles().stream())
        .filter(v -> v.vehicleId().equals(vehicle.id))
        .mapToInt(Allocation::seatsAfterAlighting)
        .findFirst()
        .orElse(0);
  }

  private int dispatchPending(Long route, Long stop, Instant now) {
    return dispatches.findAll().stream()
        .filter(
            d ->
                d.routeId.equals(route)
                    && d.targetStopId.equals(stop)
                    && d.status.equals("PENDING")
                    && d.deadlineAt.isAfter(now)
                    && !d.estimatedArrivalAt.isAfter(now.plus(horizon)))
        .mapToInt(d -> d.plannedCapacity)
        .sum();
  }
}
