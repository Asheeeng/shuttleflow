package edu.gzu.shuttle;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EtaServiceTest {
  final Instant start = Instant.parse("2026-11-12T00:00:00Z");
  final Duration ttl = Duration.ofMinutes(15);

  List<RouteStop> stops() {
    List<RouteStop> list = new ArrayList<>();
    int[] times = {0, 240, 180, 300};
    for (int i = 0; i < 4; i++) {
      RouteStop s = new RouteStop();
      s.stopOrder = i;
      s.secondsFromPrevious = times[i];
      list.add(s);
    }
    return list;
  }

  EtaService.Candidate bus(String status, int order, Instant at) {
    return new EtaService.Candidate(1L, "TEST-01", status, order, at);
  }

  @Test
  void cumulativeTimeToDAt0802IsTenMinutes() {
    var e =
        EtaService.estimate(
            List.of(bus("RUNNING", 0, start)), stops(), 3, start.plusSeconds(120), ttl);
    assertThat(e.remainingSeconds()).isEqualTo(600);
    assertThat(e.estimatedArrivalAt()).isEqualTo(start.plusSeconds(720));
  }

  @Test
  void reportAtBRecalculatesToC() {
    var e = EtaService.estimate(List.of(bus("RUNNING", 1, start)), stops(), 2, start, ttl);
    assertThat(e.remainingSeconds()).isEqualTo(180);
  }

  @Test
  void staleAtExactTtlHasNoCountdown() {
    var e =
        EtaService.estimate(List.of(bus("RUNNING", 0, start)), stops(), 3, start.plus(ttl), ttl);
    assertThat(e.status()).isEqualTo("STALE_REPORT");
    assertThat(e.remainingSeconds()).isNull();
  }

  @Test
  void overduePredictionMustNeverRemainZero() {
    var e =
        EtaService.estimate(
            List.of(bus("RUNNING", 0, start)), stops(), 1, start.plusSeconds(240), ttl);
    assertThat(e.status()).isEqualTo("AWAITING_REPORT");
    assertThat(e.remainingSeconds()).isNull();
  }

  @Test
  void standbyDoesNotParticipate() {
    assertThat(
            EtaService.estimate(List.of(bus("STANDBY", 0, start)), stops(), 2, start, ttl).status())
        .isEqualTo("NO_RUNNING_VEHICLE");
  }

  @Test
  void stationAlreadyPassedDoesNotMatch() {
    assertThat(
            EtaService.estimate(List.of(bus("RUNNING", 2, start)), stops(), 1, start, ttl).status())
        .isEqualTo("STATION_ALREADY_REPORTED");
  }

  @Test
  void earliestValidVehicleWins() {
    var later = bus("RUNNING", 0, start);
    var early = new EtaService.Candidate(2L, "TEST-02", "RUNNING", 1, start);
    var e = EtaService.estimate(List.of(later, early), stops(), 2, start, ttl);
    assertThat(e.taskId()).isEqualTo(2);
    assertThat(e.remainingSeconds()).isEqualTo(180);
  }

  @Test
  void futureTimestampAndMissingReportAreUnavailable() {
    assertThat(
            EtaService.estimate(
                    List.of(bus("RUNNING", 0, start.plusSeconds(1))), stops(), 2, start, ttl)
                .status())
        .isEqualTo("STALE_REPORT");
    assertThat(
            EtaService.estimate(List.of(bus("RUNNING", 0, null)), stops(), 2, start, ttl)
                .remainingSeconds())
        .isNull();
  }
}
