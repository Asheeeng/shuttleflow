package edu.gzu.shuttle;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "dispatch_task")
public class DispatchTask {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public java.time.Instant resolvedAt;
  public Long resolvedBy;
  public String resolutionNote;

  public Long vehicleId;
  public Long driverId;
  public Long routeId;
  public Long targetStopId;
  public Long createdBy;
  public Long previousTaskId;
  public String status;
  public Long activeVehicleId;
  public String reason;
  public String responseNote;
  public boolean escalated;
  public Instant createdAt;
  public Instant deadlineAt;
  public Instant respondedAt;
  public Instant completedAt;
  public Instant estimatedArrivalAt;
  public int plannedCapacity;
}
