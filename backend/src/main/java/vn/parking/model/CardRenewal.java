package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "card_renewals")
public class CardRenewal extends BaseEntity {
  @Column(name = "order_id", nullable = false)
  public UUID orderId;

  @Column(name = "card_id", nullable = false)
  public UUID cardId;

  @Column(name = "old_expires_at", nullable = true)
  public Instant oldExpiresAt;

  @Column(name = "new_expires_at", nullable = false)
  public Instant newExpiresAt;

  @Column(name = "applied_at", nullable = false)
  public Instant appliedAt;
}
