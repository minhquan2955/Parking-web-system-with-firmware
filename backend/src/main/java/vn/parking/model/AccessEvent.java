package vn.parking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_events")
public class AccessEvent extends BaseEntity {
  @Column(name = "device_id", nullable = false)
  public UUID deviceId;

  @Column(name = "event_id", nullable = false)
  public UUID eventId;

  @Column(name = "boot_id", nullable = false)
  public UUID bootId;

  @Column(name = "gate", nullable = false)
  public String gate;

  @Column(name = "card_uid", nullable = false)
  public String cardUid;

  @Column(name = "card_id", nullable = true)
  public UUID cardId;

  @Column(name = "session_id", nullable = true)
  public UUID sessionId;

  @Column(name = "decision", nullable = false)
  public String decision;

  @Column(name = "reason", nullable = false)
  public String reason;

  @Column(name = "request_hash", nullable = false)
  public String requestHash;

  @Column(name = "response_json", nullable = false, columnDefinition = "text")
  public String responseJson;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt;
}
