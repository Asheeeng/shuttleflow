package edu.gzu.shuttle;

import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("demo & !prod & !production")
public class DemoService implements ApplicationRunner {
  final UserRepo users;
  final RouteRepo routes;
  final StopRepo stops;
  final VehicleRepo vehicles;
  final TaskRepo tasks;
  final WaitRepo waits;
  final ArrivalRepo arrivals;
  final Clock clock;
  final PasswordEncoder passwords;
  final DayRepo days;
  final PlanRepo plans;
  final DispatchRepo dispatches;
  final WaitingService waiting;
  final FleetService fleet;

  DemoService(
      UserRepo u,
      RouteRepo r,
      StopRepo s,
      VehicleRepo v,
      TaskRepo t,
      WaitRepo w,
      ArrivalRepo a,
      Clock c,
      PasswordEncoder p,
      DayRepo days,
      PlanRepo plans,
      DispatchRepo dispatches,
      WaitingService waiting,
      FleetService fleet) {
    users = u;
    routes = r;
    stops = s;
    vehicles = v;
    tasks = t;
    waits = w;
    arrivals = a;
    clock = c;
    passwords = p;
    this.days = days;
    this.plans = plans;
    this.dispatches = dispatches;
    this.waiting = waiting;
    this.fleet = fleet;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (users.count() == 0 && routes.count() == 0) seed();
  }

  @Transactional
  public void reset(String confirmation) {
    if (!"RESET DEMO".equals(confirmation))
      throw BusinessException.bad("CONFIRMATION_REQUIRED", "请确认仅重置本地演示数据，并输入 RESET DEMO");
    // Only available under demo profile. Keep identities stable so active demo sessions remain
    // usable.
    for (var o :
        dispatches.findAll().stream()
            .sorted(java.util.Comparator.comparing((DispatchTask x) -> x.id).reversed())
            .toList()) {
      dispatches.delete(o);
      dispatches.flush();
    }
    plans.deleteAllInBatch();
    days.deleteAllInBatch();
    arrivals.deleteAllInBatch();
    waits.deleteAllInBatch();
    tasks.deleteAllInBatch();
    if (routes.count() == 0) {
      seed();
      return;
    }
    ShuttleRoute route = routes.findAll().get(0);
    RouteStop first = stops.findByRouteIdOrderByStopOrder(route.id).get(0);
    Vehicle vehicle =
        vehicles.findAll().stream().filter(v -> v.code.equals("DEMO-01")).findFirst().orElseThrow();
    runTask(users.findByUsername("driver").orElseThrow(), vehicle, route, first);
    var backup =
        users.findByUsername("driver2").orElseGet(() -> account("driver2", "备勤司机 · 林师傅", "DRIVER"));
    for (var v : vehicles.findAll()) {
      if (v.code.equals("DEMO-02")) {
        standbyTask(backup, v, route, first);
        configure(v, backup, route, first, "STANDBY", 60, "演示备勤车辆，可通过辅助调度启动");
      } else if (v.id.equals(vehicle.id))
        configure(
            v,
            users.findByUsername("driver").orElseThrow(),
            route,
            first,
            "RUNNING",
            60,
            "基础演示：A站人工初始上报");
      else configure(v, null, route, first, "UNAVAILABLE", 60, "高峰场景附加车辆，基础场景未启用");
    }
  }

  private void seed() {
    AppUser passenger = account("passenger", "演示乘客", "PASSENGER");
    AppUser driver = account("driver", "演示司机", "DRIVER");
    account("admin", "演示管理员", "ADMIN");
    ShuttleRoute r = new ShuttleRoute();
    r.name = "A线路 · 校园演示线";
    r.direction = "A站 → D站（单向）";
    r.demo = true;
    routes.save(r);
    int[] seconds = {0, 240, 180, 300};
    RouteStop first = null;
    for (int i = 0; i < 4; i++) {
      RouteStop s = new RouteStop();
      s.routeId = r.id;
      s.name = "" + (char) ('A' + i) + "站";
      s.stopOrder = i;
      s.secondsFromPrevious = seconds[i];
      stops.save(s);
      if (i == 0) first = s;
    }
    Vehicle active = null;
    for (int i = 1; i <= 2; i++) {
      Vehicle v = new Vehicle();
      v.code = "DEMO-0" + i;
      v.capacity = 20;
      vehicles.save(v);
      if (i == 1) active = v;
    }
    reset("RESET DEMO");
  }

  private AppUser account(String name, String display, String role) {
    AppUser u = new AppUser();
    u.username = name;
    u.displayName = display;
    u.role = role;
    u.passwordHash = passwords.encode("Demo123456!");
    return users.save(u);
  }

  private void runTask(AppUser driver, Vehicle v, ShuttleRoute r, RouteStop first) {
    RunTask t = new RunTask();
    t.driverId = driver.id;
    t.vehicleId = v.id;
    t.routeId = r.id;
    t.status = "RUNNING";
    t.lastStopId = first.id;
    t.lastReportAt = clock.instant();
    t.occupancy = 10;
    t.occupancyConfirmed = true;
    t.occupancyUpdatedAt = clock.instant();
    tasks.save(t);
    ArrivalEvent e = new ArrivalEvent();
    e.taskId = t.id;
    e.driverId = driver.id;
    e.stopId = first.id;
    e.reportedAt = clock.instant();
    e.boarded = 0;
    e.alighted = 0;
    e.occupancyAfter = 10;
    e.countsConfirmed = true;
    arrivals.save(e);
  }

  private VehicleDay configure(
      Vehicle v,
      AppUser driver,
      ShuttleRoute route,
      RouteStop staging,
      String status,
      int ready,
      String reason) {
    var d = new VehicleDay();
    d.vehicleId = v.id;
    d.driverId = driver == null ? null : driver.id;
    d.routeId = route.id;
    d.stagingStopId = staging.id;
    d.serviceDate = fleet.date();
    d.status = status;
    d.readyInSeconds = ready;
    d.reason = reason;
    d.updatedAt = clock.instant();
    return days.save(d);
  }

  private RunTask standbyTask(AppUser driver, Vehicle v, ShuttleRoute route, RouteStop staging) {
    var t = new RunTask();
    t.driverId = driver.id;
    t.vehicleId = v.id;
    t.routeId = route.id;
    t.lastStopId = staging.id;
    t.status = "STANDBY";
    t.occupancy = 0;
    t.occupancyConfirmed = true;
    t.occupancyUpdatedAt = clock.instant();
    return tasks.save(t);
  }

  private Vehicle extraVehicle(String code) {
    return vehicles.findAll().stream()
        .filter(v -> v.code.equals(code))
        .findFirst()
        .orElseGet(
            () -> {
              var v = new Vehicle();
              v.code = code;
              v.capacity = 20;
              return vehicles.save(v);
            });
  }

  private AppUser extraDriver(String name, String display) {
    return users.findByUsername(name).orElseGet(() -> account(name, display, "DRIVER"));
  }

  private void plan(RunTask task, RouteStop stop, int down) {
    var p = new AlightingPlan();
    p.taskId = task.id;
    p.stopId = stop.id;
    p.expectedAlighted = down;
    p.source = "课间高峰演示夹具：假设司机已确认本站计划下客人数，非真实校园数据";
    p.updatedAt = clock.instant();
    plans.save(p);
  }

  @Transactional
  public java.util.Map<String, Object> peak(String confirmation) {
    if (!"INIT PEAK".equals(confirmation))
      throw BusinessException.bad("CONFIRMATION_REQUIRED", "请确认将替换本地演示运营数据，并输入 INIT PEAK");
    reset("RESET DEMO");
    var route = routes.findAll().get(0);
    var ss = stops.findByRouteIdOrderByStopOrder(route.id);
    var first = ss.get(0);
    var primary =
        tasks.findByDriverId(users.findByUsername("driver").orElseThrow().id).orElseThrow();
    primary.occupancy = 18;
    tasks.save(primary);
    var initial = arrivals.findTop50ByOrderByReportedAtDescIdDesc().get(0);
    initial.occupancyAfter = 18;
    arrivals.save(initial);
    plan(primary, ss.get(1), 1);
    plan(primary, ss.get(2), 4);
    var driver3 = extraDriver("driver3", "支线司机 · 陈师傅");
    var v3 = extraVehicle("DEMO-03");
    var t3 = standbyTask(driver3, v3, route, ss.get(1));
    t3.status = "RUNNING";
    t3.occupancy = 16;
    t3.lastReportAt = clock.instant();
    tasks.save(t3);
    days.findByVehicleIdAndServiceDate(v3.id, fleet.date()).ifPresent(days::delete);
    days.flush();
    configure(v3, driver3, route, ss.get(1), "RUNNING", 60, "演示初始位置：B站，16/20人");
    plan(t3, ss.get(2), 3);
    var driver4 = extraDriver("driver4", "备勤司机 · 王师傅");
    var v4 = extraVehicle("DEMO-04");
    standbyTask(driver4, v4, route, first);
    days.findByVehicleIdAndServiceDate(v4.id, fleet.date()).ifPresent(days::delete);
    days.flush();
    configure(v4, driver4, route, first, "STANDBY", 120, "高峰演示备勤车辆，准备时间2分钟");
    var v5 = extraVehicle("DEMO-05");
    days.findByVehicleIdAndServiceDate(v5.id, fleet.date()).ifPresent(days::delete);
    days.flush();
    configure(v5, null, route, first, "UNAVAILABLE", 60, "演示不可用车辆：例行检修，不参与推荐");
    int[] sizes = {20, 10, 2};
    for (int i = 0; i < 3; i++) {
      String name = "peak-passenger-" + i;
      var u =
          users.findByUsername(name).orElseGet(() -> account(name, "模拟候车群组 " + name, "PASSENGER"));
      waiting.create(u.id, route.id, ss.get(i == 2 ? 1 : 2).id, ss.get(3).id, sizes[i]);
    }
    return Api.map(
        "scenario",
        "课间高峰模拟",
        "source",
        "本地确定性演示夹具；候车人数、车内人数、司机下客计划、车辆位置、准备时间和站间耗时均为模拟，不代表贵州大学真实数据",
        "cStationDemand",
        30,
        "bStationDemand",
        2,
        "expectedCService",
        12,
        "expectedCGap",
        18,
        "createdAt",
        clock.instant());
  }
}
