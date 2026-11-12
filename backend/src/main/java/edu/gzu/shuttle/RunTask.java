package edu.gzu.shuttle;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "run_task")
public class RunTask {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long driverId;
  public Long vehicleId;
  public Long routeId;
  public String status;
  public Long lastStopId;
  public Instant lastReportAt;
  public int occupancy;
  public boolean occupancyConfirmed;
  public Instant occupancyUpdatedAt;
}
