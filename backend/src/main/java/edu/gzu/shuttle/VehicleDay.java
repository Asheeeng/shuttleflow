package edu.gzu.shuttle;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "vehicle_day")
public class VehicleDay {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long vehicleId;
  public LocalDate serviceDate;
  public String status;
  public Long driverId;
  public Long routeId;
  public Long stagingStopId;
  public int readyInSeconds;
  public String reason;
  public Instant updatedAt;
}
