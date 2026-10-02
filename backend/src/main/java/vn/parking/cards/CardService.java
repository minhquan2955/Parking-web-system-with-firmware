package vn.parking.cards;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.parking.audit.AuditService;
import vn.parking.auth.Actor;
import vn.parking.common.*;
import vn.parking.model.*;

@Service
public class CardService {
  private final Store db;
  private final Clock clock;
  private final AuditService audit;

  public CardService(Store db, Clock clock, AuditService audit) {
    this.db = db;
    this.clock = clock;
    this.audit = audit;
  }

  public Map<String, Object> view(Card c) {
    String effective =
        c.status.equals("BLOCKED")
            ? "BLOCKED"
            : c.expiresAt == null
                ? "NOT_ACTIVATED"
                : !clock.instant().isBefore(c.expiresAt) ? "EXPIRED" : "ACTIVE";
    return Util.map(
        "id",
        c.id,
        "uid",
        c.uid,
        "ownerId",
        c.ownerId,
        "ownerName",
        db.get(User.class, c.ownerId).fullName,
        "status",
        c.status,
        "effectiveStatus",
        effective,
        "expiresAt",
        c.expiresAt,
        "hasOpenSession",
        db.count(ParkingSession.class, "where e.cardId=?1 and e.status='OPEN'", c.id) > 0,
        "createdAt",
        c.createdAt);
  }

  @Transactional
  public Map<String, Object> create(String uid, UUID owner, Actor actor) {
    uid = Util.uid(uid);
    db.lock(ParkingLot.class, vn.parking.parking.ParkingService.LOT);
    User u = db.lock(User.class, owner);
    if (!u.role.equals("USER")) throw Problem.bad("Chỉ gán thẻ cho USER");
    db.advisory("card:" + uid);
    if (db.count(Card.class, "where e.uid=?1", uid) > 0) throw Problem.conflict("UID_TAKEN");
    if (db.count(Card.class, "where e.ownerId=?1 and e.status='ENABLED'", owner) > 0)
      throw Problem.conflict("ENABLED_CARD_EXISTS");
    if (db.count(ParkingSession.class, "where e.ownerIdSnapshot=?1 and e.status='OPEN'", owner) > 0)
      throw Problem.conflict("ALREADY_INSIDE");
    Card c = new Card();
    c.uid = uid;
    c.ownerId = owner;
    c.status = "ENABLED";
    db.add(c);
    audit.record(actor.id(), "CARD_ASSIGNED", "CARD", c.id, null, view(c), null);
    return view(c);
  }

  @Transactional
  public Map<String, Object> status(UUID id, String status, String reason, Actor actor) {
    Util.choice(status, "ENABLED", "BLOCKED");
    Util.reason(reason);
    Card found = db.get(Card.class, id);
    db.lock(User.class, found.ownerId);
    Card c = db.lock(Card.class, id);
    if (status.equals("ENABLED")
        && db.count(
                Card.class, "where e.ownerId=?1 and e.status='ENABLED' and e.id<>?2", c.ownerId, id)
            > 0) throw Problem.conflict("ENABLED_CARD_EXISTS");
    var before = view(c);
    c.status = status;
    audit.record(actor.id(), "CARD_STATUS_CHANGED", "CARD", id, before, view(c), reason);
    return view(c);
  }
}
