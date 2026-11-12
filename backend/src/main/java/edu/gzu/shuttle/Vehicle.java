package edu.gzu.shuttle;

import jakarta.persistence.*;

@Entity
@Table(name = "vehicle")
public class Vehicle {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public String code;
  public int capacity;
}
