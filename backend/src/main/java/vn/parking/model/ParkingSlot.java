package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "parking_slots")
public class ParkingSlot extends BaseEntity {
  @Column(name = "lot_id", nullable = false)
  public UUID lotId;

  @Column(name = "code", nullable = false)
  public String code;

  @Column(name = "device_id", nullable = false)
  public UUID deviceId;

  @Column(name = "reported_state", nullable = false)
  public String reportedState;

  @Column(name = "reported_at", nullable = true)
  public Instant reportedAt;
}
