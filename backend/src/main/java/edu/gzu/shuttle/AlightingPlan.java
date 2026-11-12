package edu.gzu.shuttle;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "alighting_plan")
public class AlightingPlan {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long taskId;
  public Long stopId;
  public int expectedAlighted;
  public String source;
  public Instant updatedAt;
}
