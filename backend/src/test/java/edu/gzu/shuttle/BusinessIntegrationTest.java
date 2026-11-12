package edu.gzu.shuttle;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
class BusinessIntegrationTest {
  static class MutableClock extends Clock {
    final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-12T00:00:00Z"));

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now.get();
    }
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired DemoService demo;
  @Autowired WaitingService waiting;
  @Autowired DriverService drivers;
  @Autowired EtaService eta;
  @Autowired UserRepo users;
  @Autowired RouteRepo routes;
  @Autowired StopRepo stops;
  @Autowired WaitRepo waits;
  @Autowired TaskRepo tasks;
  @Autowired ArrivalRepo arrivals;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  Long passenger, driver, route;
  List<RouteStop> stations;

  @BeforeEach
  void setup() {
    clock.now.set(Instant.parse("2026-11-12T00:00:00Z"));
    demo.reset("RESET DEMO");
    passenger = users.findByUsername("passenger").orElseThrow().id;
    driver = users.findByUsername("driver").orElseThrow().id;
    route = routes.findAll().get(0).id;
    stations = stops.findByRouteIdOrderByStopOrder(route);
  }

  WaitRequest create() {
    return waiting.create(passenger, route, stations.get(2).id, stations.get(3).id, 1);
  }

  @Test
  void createsPersistsAndCountsDemand() {
    var r = create();
    assertThat(r.status).isEqualTo("WAITING");
    assertThat(waits.findById(r.id)).isPresent();
    assertThat(waiting.demand(route, null).get(2).get("waitingPeople")).isEqualTo(1);
  }

  @Test
  void duplicateIsRejected() {
    create();
    assertThatThrownBy(this::create)
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已有有效");
    assertThat(waits.count()).isEqualTo(1);
  }

  @Test
  void concurrentCreationProducesExactlyOneActiveRequest() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    var start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    create();
                    return true;
                  } catch (BusinessException e) {
                    return false;
                  }
                }));
      start.countDown();
      int successful = 0;
      for (var f : results) if (f.get(10, TimeUnit.SECONDS)) successful++;
      assertThat(successful).isEqualTo(1);
      assertThat(waits.valid(clock.instant())).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cancellationReleasesActiveSlotAndPreservesHistory() {
    var r = create();
    assertThat(waiting.finish(passenger, r.id, true, null).status).isEqualTo("CANCELLED");
    create();
    assertThat(waits.count()).isEqualTo(2);
  }

  @Test
  void boardingCompletesAndDoesNotAllowReactivation() {
    var r = create();
    assertThat(waiting.finish(passenger, r.id, false, null).status).isEqualTo("BOARDED");
    assertThat(waiting.demand(route, null).get(2).get("waitingPeople")).isEqualTo(0);
    assertThatThrownBy(() -> waiting.finish(passenger, r.id, true, null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void partialBoardingCountsOnlyRemainingPeople() {
    var r = waiting.create(passenger, route, stations.get(2).id, stations.get(3).id, 3);
    assertThat(waiting.finish(passenger, r.id, false, 1).status).isEqualTo("WAITING");
    assertThat(waiting.demand(route, null).get(2).get("waitingPeople")).isEqualTo(2);
    assertThat(waiting.finish(passenger, r.id, false, 2).status).isEqualTo("BOARDED");
  }

  @Test
  void expiredDemandExcludedBeforeSchedulerAndSlotCanBeReused() {
    var r = create();
    clock.now.set(clock.instant().plusSeconds(600));
    assertThat(waits.findById(r.id).orElseThrow().status).isEqualTo("WAITING");
    assertThat(waiting.demand(route, null).get(2).get("waitingPeople")).isEqualTo(0);
    create();
    assertThat(waits.findById(r.id).orElseThrow().status).isEqualTo("EXPIRED");
    assertThat(waits.valid(clock.instant())).hasSize(1);
  }

  @Test
  void schedulerExpiresAndLateBoardDoesNotRevive() {
    var r = create();
    clock.now.set(clock.instant().plusSeconds(601));
    waiting.expire();
    assertThat(waits.findById(r.id).orElseThrow().activeUserId).isNull();
    assertThatThrownBy(() -> waiting.finish(passenger, r.id, false, null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void queryAndLateCancelReturnExpiredState() {
    var r = create();
    clock.now.set(clock.instant().plusSeconds(601));
    assertThat(waiting.finish(passenger, r.id, true, null).status).isEqualTo("EXPIRED");
    assertThat(waiting.history(passenger).get(0).status).isEqualTo("EXPIRED");
  }

  @Test
  void invalidJourneyRejected() {
    assertThatThrownBy(
            () -> waiting.create(passenger, route, stations.get(3).id, stations.get(2).id, 1))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> waiting.create(passenger, route, stations.get(2).id, stations.get(2).id, 1))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void driverUpdatesOccupancyArrivalAndEtaWithoutAutoBoarding() {
    var r = create();
    var result = drivers.arrive(driver, stations.get(1).id, true, 2, 1, null);
    assertThat(result.get("occupancy")).isEqualTo(11);
    assertThat(result.get("lastStopName")).isEqualTo("B站");
    assertThat(arrivals.count()).isEqualTo(2);
    assertThat(eta.next(route, stations.get(2).id, stations.get(3).id).remainingSeconds())
        .isEqualTo(180);
    assertThat(waits.findById(r.id).orElseThrow().status).isEqualTo("WAITING");
  }

  @Test
  void overloadRollsBackBothTaskAndEvent() {
    assertThatThrownBy(() -> drivers.arrive(driver, stations.get(1).id, true, 12, 1, null))
        .hasMessageContaining("容量");
    assertThat(drivers.task(driver).occupancy).isEqualTo(10);
    assertThat(drivers.task(driver).lastStopId).isEqualTo(stations.get(0).id);
    assertThat(arrivals.count()).isEqualTo(1);
  }

  @Test
  void impossibleAlightingAndNegativeCountsRejected() {
    assertThatThrownBy(() -> DriverService.occupancy(10, 11, 0, 20))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> DriverService.occupancy(10, 0, -1, 20))
        .isInstanceOf(BusinessException.class);
    assertThat(DriverService.occupancy(10, 1, 2, 20)).isEqualTo(11);
  }

  @Test
  void skippingRepeatingOrForeignStopRejected() {
    assertThatThrownBy(() -> drivers.arrive(driver, stations.get(2).id, true, 0, 0, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> drivers.arrive(driver, stations.get(0).id, true, 0, 0, null))
        .isInstanceOf(BusinessException.class);
    ShuttleRoute other = new ShuttleRoute();
    other.name = "测试反向线";
    other.direction = "反向";
    routes.save(other);
    RouteStop foreign = new RouteStop();
    foreign.routeId = other.id;
    foreign.name = "外线路站";
    foreign.stopOrder = 1;
    stops.save(foreign);
    try {
      assertThatThrownBy(() -> drivers.arrive(driver, foreign.id, true, 0, 0, null))
          .isInstanceOf(BusinessException.class);
    } finally {
      stops.delete(foreign);
      routes.delete(other);
    }
  }

  @Test
  void unknownCountsPreservePreviousTimestampAndRequireReconfirmation() {
    Instant old = drivers.task(driver).occupancyUpdatedAt;
    clock.now.set(clock.instant().plusSeconds(5));
    drivers.arrive(driver, stations.get(1).id, false, null, null, null);
    var t = drivers.task(driver);
    assertThat(t.occupancy).isEqualTo(10);
    assertThat(t.occupancyConfirmed).isFalse();
    assertThat(t.occupancyUpdatedAt).isEqualTo(old);
    assertThatThrownBy(() -> drivers.arrive(driver, stations.get(2).id, true, 1, 0, null))
        .isInstanceOf(BusinessException.class);
    assertThat(drivers.arrive(driver, stations.get(2).id, true, 1, 0, 8).get("occupancy"))
        .isEqualTo(9);
  }

  @Test
  void stoppingVehicleMakesEtaUnavailable() {
    RunTask t = drivers.task(driver);
    t.status = "STOPPED";
    tasks.save(t);
    assertThat(eta.next(route, stations.get(2).id, stations.get(3).id).status())
        .isEqualTo("NO_RUNNING_VEHICLE");
  }

  @Test
  void ownershipCannotBeBypassed() {
    var r = create();
    assertThatThrownBy(() -> waiting.finish(driver, r.id, true, null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void apiEnforcesRolesAuthenticationAndCsrf() throws Exception {
    mvc.perform(get("/api/admin/overview"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/api/admin/overview").with(user("passenger").roles("PASSENGER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/driver/task").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/passenger/requests")
                .with(user("passenger").roles("PASSENGER"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/passenger/requests")
                .with(user("passenger").roles("PASSENGER"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void loginCreatesSessionAndLogoutRevokesIt() throws Exception {
    var c = mvc.perform(get("/api/auth/csrf")).andReturn();
    var session = (MockHttpSession) c.getRequest().getSession();
    String token =
        json.readTree(c.getResponse().getContentAsString()).path("data").path("token").asText();
    var result =
        mvc.perform(
                post("/api/auth/login")
                    .session(session)
                    .header("X-CSRF-TOKEN", token)
                    .contentType("application/json")
                    .content("{\"username\":\"passenger\",\"password\":\"Demo123456!\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("PASSENGER"))
            .andReturn();
    var logged = (MockHttpSession) result.getRequest().getSession();
    mvc.perform(get("/api/auth/me").session(logged)).andExpect(status().isOk());
    mvc.perform(post("/api/auth/logout").session(logged).with(csrf())).andExpect(status().isOk());
    assertThat(logged.isInvalid()).isTrue();
  }

  @Test
  void badPasswordAndUnconfirmedResetRejected() throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"passenger\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/admin/demo/reset")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"confirmation\":\"wrong\"}"))
        .andExpect(status().isBadRequest());
  }
}
