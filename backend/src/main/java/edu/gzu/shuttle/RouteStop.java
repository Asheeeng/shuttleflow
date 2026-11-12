package edu.gzu.shuttle;

import jakarta.persistence.*;

@Entity
@Table(name = "route_stop")
public class RouteStop {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long routeId;
  public String name;
  public int stopOrder;
  public int secondsFromPrevious;
}
