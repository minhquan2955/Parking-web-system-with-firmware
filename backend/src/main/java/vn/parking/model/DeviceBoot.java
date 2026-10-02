package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_boots")
public class DeviceBoot extends BaseEntity {
  @Column(name = "device_id", nullable = false)
  public UUID deviceId;

  @Column(name = "boot_id", nullable = false)
  public UUID bootId;

  @Column(name = "last_seq", nullable = false)
  public Long lastSeq;

  @Column(name = "first_seen_at", nullable = false)
  public Instant firstSeenAt;
}
