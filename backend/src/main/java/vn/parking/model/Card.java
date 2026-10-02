package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cards")
public class Card extends BaseEntity {
  @Column(name = "uid", nullable = false)
  public String uid;

  @Column(name = "owner_id", nullable = false)
  public UUID ownerId;

  @Column(name = "status", nullable = false)
  public String status;

  @Column(name = "expires_at", nullable = true)
  public Instant expiresAt;
}
