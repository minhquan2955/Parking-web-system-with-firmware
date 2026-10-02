package vn.parking.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User extends BaseEntity {
  @Column(name = "username", nullable = false)
  public String username;

  @Column(name = "password_hash", nullable = false)
  public String passwordHash;

  @Column(name = "full_name", nullable = false)
  public String fullName;

  @Column(name = "phone", nullable = true)
  public String phone;

  @Column(name = "role", nullable = false)
  public String role;

  @Column(name = "status", nullable = false)
  public String status;
}
