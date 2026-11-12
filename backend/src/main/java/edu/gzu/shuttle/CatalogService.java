package edu.gzu.shuttle;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
  final RouteRepo routes;
  final StopRepo stops;

  CatalogService(RouteRepo r, StopRepo s) {
    routes = r;
    stops = s;
  }

  ShuttleRoute route(Long id) {
    return routes.findById(id).orElseThrow(BusinessException::missing);
  }

  RouteStop stop(Long id) {
    return stops.findById(id).orElseThrow(BusinessException::missing);
  }

  void validateJourney(Long routeId, Long origin, Long destination) {
    route(routeId);
    RouteStop from = stop(origin), to = stop(destination);
    if (!from.routeId.equals(routeId)
        || !to.routeId.equals(routeId)
        || from.stopOrder >= to.stopOrder)
      throw BusinessException.bad("INVALID_JOURNEY", "上车站和目的站必须属于同一线路，且目的站位于运行方向下游");
  }

  Map<String, Object> routeView(ShuttleRoute r) {
    return Api.map(
        "id",
        r.id,
        "name",
        r.name,
        "direction",
        r.direction,
        "demo",
        r.demo,
        "stops",
        stops.findByRouteIdOrderByStopOrder(r.id));
  }
}
