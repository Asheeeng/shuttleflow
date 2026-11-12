package edu.gzu.shuttle;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CapacityServiceTest {
  final Instant now = Instant.parse("2026-11-12T00:00:00Z");

  List<RouteStop> stops() {
    List<RouteStop> rows = new ArrayList<>();
    int[] sec = {0, 240, 180, 300};
    for (int i = 0; i < 4; i++) {
      var s = new RouteStop();
      s.id = (long) i + 1;
      s.stopOrder = i;
      s.name = "" + (char) ('A' + i) + "站";
      s.secondsFromPrevious = sec[i];
      rows.add(s);
    }
    return rows;
  }

  CapacityService.Bus bus(long id, int load, int order, Map<Long, Integer> down) {
    return new CapacityService.Bus(id, id, "TEST-" + id, 20, load, order, now, down);
  }

  @Test
  void twoVehiclesAlightBeforeBoardingAndShareOneDemandPool() {
    var rows =
        CapacityService.project(
            stops(),
            List.of(bus(1, 18, 0, Map.of(3L, 4)), bus(2, 16, 1, Map.of(3L, 3))),
            Map.of(3L, 30),
            now,
            Duration.ofMinutes(10));
    var c = rows.get(2);
    assertThat(c.expectedCapacity()).isEqualTo(13);
    assertThat(c.allocated()).isEqualTo(13);
    assertThat(c.gap()).isEqualTo(17);
    assertThat(c.vehicles()).allSatisfy(v -> assertThat(v.occupancyAfter()).isBetween(0, 20));
  }

  @Test
  void upstreamBoardingConsumesSeatsAtDownstreamStop() {
    var rows =
        CapacityService.project(
            stops(),
            List.of(bus(1, 18, 0, Map.of(2L, 1, 3L, 4)), bus(2, 16, 1, Map.of(3L, 3))),
            Map.of(2L, 2, 3L, 30),
            now,
            Duration.ofMinutes(10));
    assertThat(rows.get(1).allocated()).isEqualTo(2);
    assertThat(rows.get(2).expectedCapacity()).isEqualTo(12);
    assertThat(rows.get(2).gap()).isEqualTo(18);
  }

  @Test
  void boardingCannotExceedDemandOrCapacity() {
    var c =
        CapacityService.project(
                stops(),
                List.of(bus(1, 20, 0, Map.of(3L, 5)), bus(2, 20, 1, Map.of(3L, 4))),
                Map.of(3L, 2),
                now,
                Duration.ofMinutes(10))
            .get(2);
    assertThat(c.allocated()).isEqualTo(2);
    assertThat(c.gap()).isZero();
    assertThat(c.vehicles().stream().mapToInt(CapacityService.Allocation::boarded).sum())
        .isEqualTo(2);
  }

  @Test
  void vehiclesOutsideForecastWindowDoNotSupplySeats() {
    var c =
        CapacityService.project(
                stops(),
                List.of(bus(1, 0, 0, Map.of())),
                Map.of(3L, 10),
                now,
                Duration.ofMinutes(5))
            .get(2);
    assertThat(c.expectedCapacity()).isZero();
    assertThat(c.gap()).isEqualTo(10);
  }

  @Test
  void pastArrivalIsNotTreatedAsFreshCapacity() {
    var c =
        CapacityService.project(
                stops(),
                List.of(bus(1, 0, 0, Map.of())),
                Map.of(3L, 10),
                now.plusSeconds(421),
                Duration.ofMinutes(10))
            .get(2);
    assertThat(c.vehicles()).isEmpty();
  }
}
