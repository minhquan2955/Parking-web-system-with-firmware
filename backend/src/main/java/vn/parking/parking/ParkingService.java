package vn.parking.parking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.parking.audit.AuditService;
import vn.parking.auth.Actor;
import vn.parking.common.*;
import vn.parking.model.*;

@Service
public class ParkingService {
  public static final UUID LOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final Store db;
  private final Clock clock;
  private final AuditService audit;

  public ParkingService(Store db, Clock clock, AuditService audit) {
    this.db = db;
    this.clock = clock;
    this.audit = audit;
  }

  public Map<String, Object> occupancy() {
    Instant now = clock.instant();
    List<ParkingSlot> slots = db.list(ParkingSlot.class, "order by e.code");
    boolean stale =
        slots.stream()
            .anyMatch(s -> s.reportedAt == null || s.reportedAt.isBefore(now.minusSeconds(15)));
    List<Map<String, Object>> views = new ArrayList<>();
    int occupied = 0, free = 0, unknown = 0;
    Instant updated = null;
    for (var s : slots) {
      String state = stale ? "UNKNOWN" : s.reportedState;
      if (state.equals("FREE")) free++;
      else if (state.equals("OCCUPIED")) occupied++;
      else unknown++;
      if (s.reportedAt != null && (updated == null || s.reportedAt.isBefore(updated)))
        updated = s.reportedAt;
      views.add(
          Util.map(
              "slotId",
              s.code,
              "state",
              state,
              "reportedState",
              s.reportedState,
              "reportedAt",
              s.reportedAt));
    }
    long open = db.count(ParkingSession.class, "where e.status='OPEN'");
    return Util.map(
        "capacity",
        3,
        "occupiedCount",
        occupied,
        "freeCount",
        free,
        "unknownCount",
        unknown,
        "openSessionCount",
        open,
        "admissionAvailable",
        unknown > 0 ? null : Math.max(0, 3 - Math.max(occupied, open)),
        "stale",
        stale,
        "updatedAt",
        updated,
        "serverTime",
        now,
        "slots",
        views);
  }

  public Map<String, Object> sessionView(ParkingSession s) {
    return Util.map(
        "id",
        s.id,
        "cardId",
        s.cardId,
        "cardUid",
        db.get(Card.class, s.cardId).uid,
        "ownerId",
        s.ownerIdSnapshot,
        "ownerName",
        db.get(User.class, s.ownerIdSnapshot).fullName,
        "status",
        s.status,
        "entryAt",
        s.entryAt,
        "exitAt",
        s.exitAt,
        "closureType",
        s.closureType);
  }

  @Transactional
  public Map<String, Object> correct(UUID id, String action, String reason, Actor actor) {
    Util.reason(reason);
    Util.choice(action, "VOID_ENTRY", "CLOSE", "REOPEN");
    db.lock(ParkingLot.class, LOT);
    ParkingSession found = db.get(ParkingSession.class, id);
    db.lock(User.class, found.ownerIdSnapshot);
    db.lock(Card.class, found.cardId);
    ParkingSession s = db.lock(ParkingSession.class, id);
    var before = sessionView(s);
    if (action.equals("REOPEN")) {
      if (!s.status.equals("CLOSED")) throw Problem.conflict("INVALID_SESSION_STATE");
      if (db.count(
                  ParkingSession.class,
                  "where e.status='OPEN' and (e.ownerIdSnapshot=?1 or e.cardId=?2)",
                  s.ownerIdSnapshot,
                  s.cardId)
              > 0
          || db.count(ParkingSession.class, "where e.status='OPEN'") >= 3)
        throw Problem.conflict("SESSION_CAPACITY_CONFLICT");
      s.status = "OPEN";
      s.exitAt = null;
      s.closureType = null;
    } else {
      if (!s.status.equals("OPEN")) throw Problem.conflict("INVALID_SESSION_STATE");
      s.status = action.equals("CLOSE") ? "CLOSED" : "VOIDED";
      s.exitAt = action.equals("CLOSE") ? clock.instant() : null;
      s.closureType = "ADMIN";
    }
    audit.record(
        actor.id(), "SESSION_" + action, "PARKING_SESSION", id, before, sessionView(s), reason);
    return sessionView(s);
  }
}
