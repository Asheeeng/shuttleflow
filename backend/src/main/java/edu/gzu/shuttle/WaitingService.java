package edu.gzu.shuttle;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitingService {
  final UserRepo users;
  final WaitRepo waits;
  final CatalogService catalog;
  final Clock clock;
  final Duration ttl;

  WaitingService(
      UserRepo u,
      WaitRepo w,
      CatalogService c,
      Clock clock,
      @Value("${app.request-ttl}") Duration ttl) {
    users = u;
    waits = w;
    catalog = c;
    this.clock = clock;
    this.ttl = ttl;
  }

  @Transactional
  public WaitRequest create(
      Long userId, Long routeId, Long origin, Long destination, int partySize) {
    users.lock(userId).orElseThrow(BusinessException::missing);
    Instant now = clock.instant();
    // A user lock serializes concurrent requests. The nullable unique key is a second defense.
    List<WaitRequest> history = waits.findByUserIdOrderByCreatedAtDesc(userId);
    for (WaitRequest old : history)
      if (old.status.equals("WAITING")) {
        if (old.expiresAt.isAfter(now))
          throw BusinessException.conflict("ACTIVE_REQUEST_EXISTS", "您已有有效候车请求，请先取消或确认上车");
        old.status = "EXPIRED";
        old.activeUserId = null;
      }
    waits.flush();
    catalog.validateJourney(routeId, origin, destination);
    if (partySize < 1 || partySize > 20)
      throw BusinessException.bad("INVALID_PARTY_SIZE", "候车人数必须为1至20人");
    WaitRequest r = new WaitRequest();
    r.userId = userId;
    r.activeUserId = userId;
    r.routeId = routeId;
    r.originStopId = origin;
    r.destinationStopId = destination;
    r.partySize = partySize;
    r.boardedCount = 0;
    r.status = "WAITING";
    r.createdAt = now;
    r.expiresAt = now.plus(ttl);
    return waits.saveAndFlush(r);
  }

  @Transactional
  public WaitRequest finish(Long userId, Long requestId, boolean cancel, Integer count) {
    users.lock(userId).orElseThrow(BusinessException::missing);
    WaitRequest r =
        waits
            .findById(requestId)
            .filter(x -> x.userId.equals(userId))
            .orElseThrow(BusinessException::missing);
    if (r.status.equals("WAITING") && !r.expiresAt.isAfter(clock.instant())) {
      r.status = "EXPIRED";
      r.activeUserId = null;
      return r;
    }
    if (!r.status.equals("WAITING"))
      throw BusinessException.conflict("REQUEST_FINISHED", "该候车请求已结束，无法再次修改");
    if (cancel) {
      r.status = "CANCELLED";
      r.activeUserId = null;
    } else {
      int n = count == null ? r.partySize - r.boardedCount : count;
      if (n < 1 || n > r.partySize - r.boardedCount)
        throw BusinessException.bad("INVALID_BOARDING_COUNT", "确认上车人数不能超过剩余候车人数");
      r.boardedCount += n;
      if (r.boardedCount == r.partySize) {
        r.status = "BOARDED";
        r.activeUserId = null;
      }
    }
    return r;
  }

  @Transactional
  public List<WaitRequest> history(Long userId) {
    users.lock(userId).orElseThrow(BusinessException::missing);
    var list = waits.findByUserIdOrderByCreatedAtDesc(userId);
    for (var r : list)
      if (r.status.equals("WAITING") && !r.expiresAt.isAfter(clock.instant())) {
        r.status = "EXPIRED";
        r.activeUserId = null;
      }
    return list;
  }

  @Transactional
  @Scheduled(fixedDelayString = "${app.expiry-interval-ms:30000}")
  public void expire() {
    waits.expire(clock.instant());
  }

  public List<Map<String, Object>> demand(Long routeId, Integer afterOrder) {
    Instant now = clock.instant();
    List<WaitRequest> active = waits.valid(now);
    List<RouteStop> all =
        routeId == null
            ? catalog.stops.findAll()
            : catalog.stops.findByRouteIdOrderByStopOrder(routeId);
    return all.stream()
        .filter(s -> afterOrder == null || s.stopOrder > afterOrder)
        .sorted(Comparator.comparing((RouteStop s) -> s.routeId).thenComparingInt(s -> s.stopOrder))
        .map(
            s -> {
              var here = active.stream().filter(r -> r.originStopId.equals(s.id)).toList();
              return Api.map(
                  "stopId",
                  s.id,
                  "stopName",
                  s.name,
                  "routeId",
                  s.routeId,
                  "routeName",
                  catalog.route(s.routeId).name,
                  "stopOrder",
                  s.stopOrder,
                  "waitingPeople",
                  here.stream().mapToInt(r -> r.partySize - r.boardedCount).sum(),
                  "requestCount",
                  here.size(),
                  "updatedAt",
                  now);
            })
        .toList();
  }

  Map<String, Object> view(WaitRequest r) {
    return Api.map(
        "id",
        r.id,
        "routeId",
        r.routeId,
        "routeName",
        catalog.route(r.routeId).name,
        "originStopId",
        r.originStopId,
        "originName",
        catalog.stop(r.originStopId).name,
        "destinationStopId",
        r.destinationStopId,
        "destinationName",
        catalog.stop(r.destinationStopId).name,
        "partySize",
        r.partySize,
        "boardedCount",
        r.boardedCount,
        "remaining",
        r.partySize - r.boardedCount,
        "createdAt",
        r.createdAt,
        "expiresAt",
        r.expiresAt,
        "status",
        r.status);
  }
}
