package edu.gzu.shuttle;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EtaService {
  public record Candidate(
      Long taskId, String vehicleCode, String status, int lastOrder, Instant reportedAt) {}

  public record Estimate(
      String status,
      String message,
      Long taskId,
      String vehicleCode,
      Instant estimatedArrivalAt,
      Long remainingSeconds,
      Instant lastReportAt,
      Instant serverTime,
      boolean demo) {}

  private final TaskRepo tasks;
  private final FleetService fleet;
  private final VehicleRepo vehicles;
  private final CatalogService catalog;
  private final Clock clock;
  private final Duration reportTtl;

  EtaService(
      TaskRepo t,
      VehicleRepo v,
      CatalogService c,
      Clock clock,
      @Value("${app.report-ttl}") Duration ttl,
      FleetService fleet) {
    tasks = t;
    vehicles = v;
    catalog = c;
    this.clock = clock;
    reportTtl = ttl;
    this.fleet = fleet;
  }

  public Estimate next(Long routeId, Long origin, Long destination) {
    catalog.validateJourney(routeId, origin, destination);
    List<Candidate> candidates =
        tasks.findAll().stream()
            .filter(t -> t.routeId.equals(routeId) && fleet.running(t))
            .map(
                t ->
                    new Candidate(
                        t.id,
                        vehicles.findById(t.vehicleId).orElseThrow().code,
                        t.status,
                        t.lastStopId == null ? -1 : catalog.stop(t.lastStopId).stopOrder,
                        t.lastReportAt))
            .toList();
    return estimate(
        candidates,
        catalog.stops.findByRouteIdOrderByStopOrder(routeId),
        catalog.stop(origin).stopOrder,
        clock.instant(),
        reportTtl);
  }

  // Fixed-direction routes are distinct route IDs. Only still-future estimates can be precise.
  public static Estimate estimate(
      List<Candidate> candidates,
      List<RouteStop> stops,
      int targetOrder,
      Instant now,
      Duration ttl) {
    List<Estimate> valid = new ArrayList<>();
    String reason = "NO_RUNNING_VEHICLE", message = "暂无运行中的车辆";
    for (var c : candidates) {
      if (!"RUNNING".equals(c.status())) continue;
      if (c.reportedAt() == null
          || c.lastOrder() < 0
          || c.reportedAt().isAfter(now)
          || !c.reportedAt().plus(ttl).isAfter(now)) {
        reason = "STALE_REPORT";
        message = "司机上报已过期或缺失，暂无法估算";
        continue;
      }
      if (c.lastOrder() >= targetOrder) {
        reason = "STATION_ALREADY_REPORTED";
        message = "车辆已上报该站或已驶过，请等待下一辆车";
        continue;
      }
      long seconds =
          stops.stream()
              .filter(s -> s.stopOrder > c.lastOrder() && s.stopOrder <= targetOrder)
              .mapToLong(s -> s.secondsFromPrevious)
              .sum();
      Instant arrival = c.reportedAt().plusSeconds(seconds);
      if (!arrival.isAfter(now)) {
        reason = "AWAITING_REPORT";
        message = "预计到站时间已过，等待司机更新，暂不显示倒计时";
        continue;
      }
      long remaining = Math.max(1, (Duration.between(now, arrival).toMillis() + 999) / 1000);
      valid.add(
          new Estimate(
              "AVAILABLE",
              "基于演示站间耗时估算",
              c.taskId(),
              c.vehicleCode(),
              arrival,
              remaining,
              c.reportedAt(),
              now,
              true));
    }
    return valid.stream()
        .min(Comparator.comparing(Estimate::estimatedArrivalAt))
        .orElse(new Estimate(reason, message, null, null, null, null, null, now, true));
  }
}
