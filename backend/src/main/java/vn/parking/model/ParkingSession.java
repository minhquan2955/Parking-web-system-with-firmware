package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "parking_sessions")
public class ParkingSession extends BaseEntity {
  @Column(name = "lot_id", nullable = false)
  public UUID lotId;

  @Column(name = "card_id", nullable = false)
  public UUID cardId;

  @Column(name = "owner_id_snapshot", nullable = false)
  public UUID ownerIdSnapshot;

  @Column(name = "status", nullable = false)
  public String status;

  @Column(name = "entry_at", nullable = false)
  public Instant entryAt;

  @Column(name = "exit_at", nullable = true)
  public Instant exitAt;

  @Column(name = "closure_type", nullable = true)
  public String closureType;
}
