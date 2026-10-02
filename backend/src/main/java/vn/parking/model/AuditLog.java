package vn.parking.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseEntity {
  @Column(name = "actor_type", nullable = false)
  public String actorType;

  @Column(name = "actor_id", nullable = true)
  public UUID actorId;

  @Column(name = "action", nullable = false)
  public String action;

  @Column(name = "entity_type", nullable = false)
  public String entityType;

  @Column(name = "entity_id", nullable = false)
  public UUID entityId;

  @Column(name = "before_json", nullable = false, columnDefinition = "text")
  public String beforeJson;

  @Column(name = "after_json", nullable = false, columnDefinition = "text")
  public String afterJson;

  @Column(name = "reason", nullable = true, columnDefinition = "text")
  public String reason;
}
