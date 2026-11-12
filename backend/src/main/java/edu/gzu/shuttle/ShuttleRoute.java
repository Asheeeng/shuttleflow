package edu.gzu.shuttle;

import jakarta.persistence.*;

@Entity
@Table(name = "shuttle_route")
public class ShuttleRoute {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public String name;
  public String direction;
  public boolean demo;
}
