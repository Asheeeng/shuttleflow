package edu.gzu.shuttle;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wait_request")
public class WaitRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long userId;
  public Long routeId;
  public Long originStopId;
  public Long destinationStopId;
  public int partySize;
  public int boardedCount;
  public Instant createdAt;
  public Instant expiresAt;
  public String status;
  public Long activeUserId;
  public int renewalCount;
}
