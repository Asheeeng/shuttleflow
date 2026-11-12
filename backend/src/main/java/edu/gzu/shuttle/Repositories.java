package edu.gzu.shuttle;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface UserRepo extends JpaRepository<AppUser, Long> {
  Optional<AppUser> findByUsername(String username);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AppUser u where u.id=:id")
  Optional<AppUser> lock(@Param("id") Long id);
}

interface RouteRepo extends JpaRepository<ShuttleRoute, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ShuttleRoute r where r.id=:id")
  Optional<ShuttleRoute> lock(@Param("id") Long id);
}

interface StopRepo extends JpaRepository<RouteStop, Long> {
  List<RouteStop> findByRouteIdOrderByStopOrder(Long routeId);
}

interface VehicleRepo extends JpaRepository<Vehicle, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from Vehicle v where v.id=:id")
  Optional<Vehicle> lock(@Param("id") Long id);
}

interface TaskRepo extends JpaRepository<RunTask, Long> {
  Optional<RunTask> findByDriverId(Long driverId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RunTask t where t.driverId=:id")
  Optional<RunTask> lockDriver(@Param("id") Long id);
}

interface WaitRepo extends JpaRepository<WaitRequest, Long> {
  List<WaitRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

  @Query("select w from WaitRequest w where w.status='WAITING' and w.expiresAt>:now")
  List<WaitRequest> valid(@Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update WaitRequest w set w.status='EXPIRED',w.activeUserId=null where w.status='WAITING' and"
          + " w.expiresAt<=:now")
  int expire(@Param("now") Instant now);
}

interface ArrivalRepo extends JpaRepository<ArrivalEvent, Long> {
  List<ArrivalEvent> findTop50ByOrderByReportedAtDescIdDesc();

  long countByReportedAtAfter(Instant after);
}

interface DayRepo extends JpaRepository<VehicleDay, Long> {
  Optional<VehicleDay> findByVehicleIdAndServiceDate(Long vehicleId, java.time.LocalDate date);

  List<VehicleDay> findByServiceDate(java.time.LocalDate date);
}

interface PlanRepo extends JpaRepository<AlightingPlan, Long> {
  List<AlightingPlan> findByTaskId(Long taskId);

  Optional<AlightingPlan> findByTaskIdAndStopId(Long taskId, Long stopId);
}

interface DispatchRepo extends JpaRepository<DispatchTask, Long> {
  List<DispatchTask> findTop100ByOrderByCreatedAtDescIdDesc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from DispatchTask d where d.id=:id")
  Optional<DispatchTask> lock(@Param("id") Long id);

  @Query("select d from DispatchTask d where d.status='PENDING' and d.deadlineAt<=:now")
  List<DispatchTask> overdue(@Param("now") Instant now);
}
