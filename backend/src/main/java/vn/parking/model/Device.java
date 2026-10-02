package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device extends BaseEntity {
  @Column(name = "code", nullable = false)
  public String code;

  @Column(name = "api_key_hash", nullable = false)
  public String apiKeyHash;

  @Column(name = "enabled", nullable = false)
  public Boolean enabled;

  @Column(name = "current_boot_id", nullable = true)
  public UUID currentBootId;

  @Column(name = "last_seen_at", nullable = true)
  public Instant lastSeenAt;

  @Column(name = "firmware_version", nullable = true)
  public String firmwareVersion;

  @Column(name = "uptime_ms", nullable = true)
  public Long uptimeMs;
}
