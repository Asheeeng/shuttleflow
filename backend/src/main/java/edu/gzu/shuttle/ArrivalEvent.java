package edu.gzu.shuttle;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "arrival_event")
public class ArrivalEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long taskId;
  public Long driverId;
  public Long stopId;
  public Instant reportedAt;
  public Integer boarded;
  public Integer alighted;
  public int occupancyAfter;
  public boolean countsConfirmed;
}
