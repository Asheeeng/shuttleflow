package edu.gzu.shuttle;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public String username;
  public String passwordHash;
  public String displayName;
  public String role;
}
