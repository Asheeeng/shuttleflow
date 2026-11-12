package edu.gzu.shuttle;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@Import(BusinessIntegrationTest.TimeConfig.class)
class DispatchIntegrationTest {
  @Autowired BusinessIntegrationTest.MutableClock clock;
  @Autowired DemoService demo;
  @Autowired DispatchService dispatch;
  @Autowired CapacityService capacity;
  @Autowired FleetService fleet;
  @Autowired UserRepo users;
  @Autowired RouteRepo routes;
  @Autowired StopRepo stops;
  @Autowired VehicleRepo vehicles;
  @Autowired TaskRepo tasks;
  @Autowired DispatchRepo orders;
  @Autowired DriverService drivers;
  @Autowired PlanRepo plans;
  @Autowired MockMvc mvc;
  @Autowired WaitRepo waitRepo;
  Long route, admin, driver2, c, b, v2;
  List<RouteStop> ss;

  @BeforeEach
  void setup() {
    clock.now.set(Instant.parse("2026-11-12T00:00:00Z"));
    demo.peak("INIT PEAK");
    route = routes.findAll().get(0).id;
    ss = stops.findByRouteIdOrderByStopOrder(route);
    c = ss.get(2).id;
    b = ss.get(1).id;
    admin = users.findByUsername("admin").orElseThrow().id;
    driver2 = users.findByUsername("driver2").orElseThrow().id;
    v2 =
        vehicles.findAll().stream()
            .filter(v -> v.code.equals("DEMO-02"))
            .findFirst()
            .orElseThrow()
            .id;
  }

  Map<String, Object> cAlert() {
    return capacity.alerts(route).stream()
        .filter(a -> a.get("stopId").equals(c))
        .findFirst()
        .orElseThrow();
  }

  DispatchTask create() {
    return dispatch.create(admin, route, c, v2, null);
  }

  @Test
  void peakFixtureProducesExplainableHighAlert() {
    var a = cAlert();
    assertThat(a.get("demand")).isEqualTo(30);
    assertThat(a.get("expectedCapacity")).isEqualTo(12);
    assertThat(a.get("gap")).isEqualTo(18);
    assertThat(a.get("level")).isEqualTo("HIGH");
    assertThat(dispatch.candidates(route, c))
        .extracting(DispatchService.Candidate::vehicleCode)
        .containsExactly("DEMO-02", "DEMO-04");
  }

  @Test
  void pendingIsReservedButNotRunningAndBlocksDuplicateCoverage() {
    var o = create();
    assertThat(o.reason).contains("缺口18人");
    assertThat(o.status).isEqualTo("PENDING");
    assertThat(cAlert().get("pendingCapacity")).isEqualTo(20);
    assertThat(cAlert().get("uncoveredGap")).isEqualTo(0);
    assertThat(fleet.today(v2).status).isEqualTo("STANDBY");
    assertThatThrownBy(this::create).isInstanceOf(BusinessException.class);
  }

  @Test
  void concurrentAdminConfirmationCreatesOnlyOneReservation() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    create();
                    return true;
                  } catch (BusinessException e) {
                    return false;
                  }
                }));
      gate.countDown();
      int n = 0;
      for (var f : results) if (f.get(10, TimeUnit.SECONDS)) n++;
      assertThat(n).isEqualTo(1);
      assertThat(orders.findAll()).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void acceptingStartsVehicleAndArrivalCompletesTask() {
    var o = create();
    assertThat(dispatch.respond(driver2, o.id, true, null, 0).status).isEqualTo("ACCEPTED");
    assertThat(fleet.today(v2).status).isEqualTo("RUNNING");
    assertThat(tasks.findByDriverId(driver2).orElseThrow().lastStopId).isEqualTo(ss.get(0).id);
    drivers.arrive(driver2, b, true, 0, 0, null);
    drivers.arrive(driver2, c, true, 18, 0, null);
    var completed = orders.findById(o.id).orElseThrow();
    assertThat(completed.status).isEqualTo("COMPLETED");
    assertThat(completed.activeVehicleId).isNull();
    assertThat(completed.completedAt).isNotNull();
  }

  @Test
  void rejectionEscalatesAndAdminCanRetryNextVehicle() {
    var old = create();
    dispatch.respond(driver2, old.id, false, "临时无法出车", null);
    assertThat(orders.findById(old.id).orElseThrow().escalated).isTrue();
    var next = dispatch.retry(admin, old.id);
    assertThat(next.previousTaskId).isEqualTo(old.id);
    assertThat(vehicles.findById(next.vehicleId).orElseThrow().code).isEqualTo("DEMO-04");
    assertThat(orders.findById(old.id).orElseThrow().escalated).isFalse();
    assertThatThrownBy(() -> dispatch.retry(admin, old.id)).isInstanceOf(BusinessException.class);
  }

  @Test
  void timeoutReleasesReservationAndEscalatesAtExactDeadline() {
    var o = create();
    clock.now.set(o.deadlineAt);
    dispatch.expire();
    var expired = orders.findById(o.id).orElseThrow();
    assertThat(expired.status).isEqualTo("TIMED_OUT");
    assertThat(expired.activeVehicleId).isNull();
    assertThat(expired.escalated).isTrue();
    assertThat(fleet.today(v2).status).isEqualTo("STANDBY");
  }

  @Test
  void lateAcceptanceCannotStartTimedOutVehicle() {
    var o = create();
    clock.now.set(o.deadlineAt.plusSeconds(1));
    assertThat(dispatch.respond(driver2, o.id, true, null, 0).status).isEqualTo("TIMED_OUT");
    assertThat(tasks.findByDriverId(driver2).orElseThrow().status).isEqualTo("STANDBY");
  }

  @Test
  void noFurtherCandidateReturnsExplicitEscalation() {
    var o = create();
    dispatch.respond(driver2, o.id, false, "无法出车", null);
    var retry = dispatch.retry(admin, o.id);
    dispatch.respond(retry.driverId, retry.id, false, "检修中", null);
    assertThatThrownBy(() -> dispatch.retry(admin, retry.id))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有其他");
    assertThat(orders.findById(retry.id).orElseThrow().escalated).isTrue();
  }

  @Test
  void cancellationDoesNotStartVehicleAndPreventsAcceptance() {
    var o = create();
    dispatch.cancel(o.id);
    assertThatThrownBy(() -> dispatch.respond(driver2, o.id, true, null, 0))
        .isInstanceOf(BusinessException.class);
    assertThat(fleet.today(v2).status).isEqualTo("STANDBY");
    assertThat(cAlert().get("uncoveredGap")).isEqualTo(18);
  }

  @Test
  void otherDriverCannotRespondAndOverloadOnAcceptanceIsRejected() {
    var o = create();
    Long other = users.findByUsername("driver").orElseThrow().id;
    assertThatThrownBy(() -> dispatch.respond(other, o.id, true, null, 0))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> dispatch.respond(driver2, o.id, true, null, 21))
        .isInstanceOf(BusinessException.class);
    assertThat(orders.findById(o.id).orElseThrow().status).isEqualTo("PENDING");
  }

  @Test
  void fleetUnavailableExcludedAndActiveReservationsCannotBeChanged() {
    fleet.update(v2, "UNAVAILABLE", "检修", 60);
    assertThat(dispatch.candidates(route, c))
        .extracting(DispatchService.Candidate::vehicleCode)
        .doesNotContain("DEMO-02", "DEMO-05");
    fleet.update(v2, "STANDBY", "检修结束", 60);
    create();
    assertThatThrownBy(() -> fleet.update(v2, "UNAVAILABLE", "检修", 60))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void nextDayNeverCarriesOldRunningStatus() {
    clock.now.set(clock.instant().plus(Duration.ofDays(1)));
    assertThat(fleet.list()).allSatisfy(v -> assertThat(v.get("status")).isEqualTo("UNAVAILABLE"));
    assertThat(fleet.date()).isEqualTo(LocalDate.of(2026, 11, 13));
  }

  @Test
  void staleOrUnknownRunningLoadIsExcludedFromSupply() {
    for (var t : tasks.findAll())
      if (t.status.equals("RUNNING")) {
        t.occupancyConfirmed = false;
        tasks.save(t);
      }
    assertThat(cAlert().get("expectedCapacity")).isEqualTo(0);
    assertThat((List<?>) cAlert().get("excluded")).hasSize(2);
  }

  @Test
  void oldForecastPlanIsConsumedButActualCountsWin() {
    Long main = users.findByUsername("driver").orElseThrow().id;
    drivers.arrive(main, b, true, 2, 1, null);
    assertThat(plans.findByTaskIdAndStopId(tasks.findByDriverId(main).orElseThrow().id, b))
        .isEmpty();
    assertThat(drivers.arrive(main, c, true, 0, 3, null).get("occupancy")).isEqualTo(16);
  }

  @Test
  void newApisEnforceRolesValidationAndUnknownRecordErrors() throws Exception {
    mvc.perform(get("/api/admin/dispatch-board").with(user("passenger").roles("PASSENGER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/driver/dispatches").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/driver/alighting-plans")
                .with(user("driver").roles("DRIVER"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"stopId\":" + ss.get(3).id + ",\"expectedAlighted\":19}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/admin/dispatches")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sameStagingStationIsNotRecommendedAsAFutureArrival() {
    assertThat(dispatch.candidates(route, ss.get(0).id)).isEmpty();
  }

  @Test
  void fullVehicleCannotAcceptAdditionalCapacityTask() {
    var o = create();
    assertThatThrownBy(() -> dispatch.respond(driver2, o.id, true, null, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("满载");
    assertThat(fleet.today(v2).status).isEqualTo("STANDBY");
  }

  @Test
  void acceptedTaskThatNeverArrivesEscalatesWithoutStoppingVehicle() {
    var o = create();
    dispatch.respond(driver2, o.id, true, null, 0);
    var accepted = orders.findById(o.id).orElseThrow();
    clock.now.set(accepted.estimatedArrivalAt.plusSeconds(300));
    dispatch.expire();
    var late = orders.findById(o.id).orElseThrow();
    assertThat(late.status).isEqualTo("TIMED_OUT");
    assertThat(late.escalated).isTrue();
    assertThat(late.responseNote).contains("执行超时");
    assertThat(fleet.today(v2).status).isEqualTo("RUNNING");
  }

  @Test
  void concurrentDifferentVehiclesCannotCoverOneGapTwice() throws Exception {
    var choices = dispatch.candidates(route, c);
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Boolean>>();
      for (var choice : choices)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    dispatch.create(admin, route, c, choice.vehicleId(), null);
                    return true;
                  } catch (BusinessException e) {
                    return false;
                  }
                }));
      gate.countDown();
      int n = 0;
      for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) n++;
      assertThat(n).isEqualTo(1);
      assertThat(orders.findAll()).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void escalatedTaskCanBeResolvedWithAuditableAdminNote() {
    var o = create();
    dispatch.respond(driver2, o.id, false, "无法出车", null);
    var resolved = dispatch.resolve(admin, o.id, "已电话核实，需求已取消，无需继续派车");
    assertThat(resolved.escalated).isFalse();
    assertThat(resolved.resolvedAt).isEqualTo(clock.instant());
    assertThat(resolved.resolvedBy).isEqualTo(admin);
    assertThat(resolved.status).isEqualTo("REJECTED");
    assertThat(resolved.resolutionNote).contains("电话核实");
    assertThat(fleet.today(v2).status).isEqualTo("STANDBY");
  }

  @Test
  void candidateForecastDeductsUpstreamDemandBeforeClaimingTargetSeats() {
    var w =
        waitRepo.valid(clock.instant()).stream()
            .filter(x -> x.originStopId.equals(b))
            .findFirst()
            .orElseThrow();
    w.partySize = 20;
    waitRepo.save(w);
    assertThat(dispatch.candidates(route, c))
        .extracting(DispatchService.Candidate::availableCapacity)
        .containsExactly(3, 3);
  }
}
