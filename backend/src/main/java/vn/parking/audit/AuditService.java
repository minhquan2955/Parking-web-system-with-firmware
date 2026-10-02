package vn.parking.audit;

import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.parking.common.*;
import vn.parking.model.*;

@Service
public class AuditService {
  private final Store db;

  public AuditService(Store db) {
    this.db = db;
  }

  public void record(
      UUID actor, String action, String type, UUID id, Object before, Object after, String reason) {
    AuditLog a = new AuditLog();
    a.actorType = actor == null ? "SYSTEM" : db.get(User.class, actor).role;
    a.actorId = actor;
    a.action = action;
    a.entityType = type;
    a.entityId = id;
    a.beforeJson = Util.json(before);
    a.afterJson = Util.json(after);
    a.reason = reason;
    if (action.equals("USER_REGISTERED")) a.actorType = "ANONYMOUS";
    db.add(a);
  }
}
