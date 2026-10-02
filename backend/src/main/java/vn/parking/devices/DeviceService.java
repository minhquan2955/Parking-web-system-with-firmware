package vn.parking.devices;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.parking.auth.RateLimiter;
import vn.parking.common.*;
import vn.parking.model.*;
import vn.parking.parking.ParkingService;

@Service
public class DeviceService {
  private final Store db;
  private final Clock clock;
  private final ParkingService parking;
  private final ObjectMapper json;
  private final RateLimiter rates;

  public DeviceService(
      Store db, Clock clock, ParkingService parking, ObjectMapper json, RateLimiter rates) {
    this.db = db;
    this.clock = clock;
    this.parking = parking;
    this.json = json;
    this.rates = rates;
  }

  public record Slot(String slotId, String state) {}

  public record Heartbeat(
      UUID bootId, Long seq, Long uptimeMs, String firmwareVersion, List<Slot> slots) {}

  public record Scan(UUID eventId, UUID bootId, String gate, String cardUid) {}

  private Device authenticate(String code, String key, String ip) {
    String bucket = "device:" + ip;
    if (rates.remaining(bucket, 10, 300) > 0)
      throw new Problem(429, "DEVICE_RATE_LIMITED", "Quá nhiều lần xác thực sai");
    Device d = db.one(Device.class, "where e.code=?1", code).orElse(null);
    if (d == null
        || key == null
        || !MessageDigest.isEqual(
            Util.sha(key).getBytes(StandardCharsets.UTF_8),
            d.apiKeyHash.getBytes(StandardCharsets.UTF_8))) {
      rates.take(bucket, 10, 300);
      throw new Problem(401, "DEVICE_UNAUTHORIZED", "Khóa thiết bị không hợp lệ");
    }
    if (!d.enabled) throw new Problem(403, "DEVICE_DISABLED", "Thiết bị đã bị khóa");
    return d;
  }

  @Transactional
  public Object heartbeat(String code, String key, String ip, Heartbeat h) {
    Device d = authenticate(code, key, ip);
    if (h.bootId == null
        || h.seq == null
        || h.seq < 0
        || h.uptimeMs == null
        || h.uptimeMs < 0
        || h.firmwareVersion == null
        || h.firmwareVersion.length() > 100
        || h.slots == null
        || h.slots.size() != 3) throw Problem.bad("Heartbeat không hợp lệ");
    Set<String> ids = new HashSet<>();
    for (Slot s : h.slots) {
      if (s == null) throw Problem.bad("Slot không hợp lệ");
      Util.choice(s.slotId, "S1", "S2", "S3");
      Util.choice(s.state, "FREE", "OCCUPIED", "UNKNOWN");
      ids.add(s.slotId);
    }
    if (ids.size() != 3) throw Problem.bad("Phải gửi đủ ba slot khác nhau");
    db.lock(ParkingLot.class, ParkingService.LOT);
    d = db.lock(Device.class, d.id);
    DeviceBoot boot =
        db.one(DeviceBoot.class, "where e.deviceId=?1 and e.bootId=?2", d.id, h.bootId)
            .orElse(null);
    boolean accepted = true;
    if (boot != null && (!h.bootId.equals(d.currentBootId) || h.seq <= boot.lastSeq))
      accepted = false;
    if (accepted) {
      if (boot == null) {
        boot = new DeviceBoot();
        boot.deviceId = d.id;
        boot.bootId = h.bootId;
        boot.firstSeenAt = clock.instant();
        boot.lastSeq = h.seq;
        db.add(boot);
      }
      boot.lastSeq = h.seq;
      d.currentBootId = h.bootId;
      d.lastSeenAt = clock.instant();
      d.uptimeMs = h.uptimeMs;
      d.firmwareVersion = h.firmwareVersion;
      for (Slot s : h.slots) {
        ParkingSlot target = db.one(ParkingSlot.class, "where e.code=?1", s.slotId).orElseThrow();
        target.reportedState = s.state;
        target.reportedAt = clock.instant();
      }
    }
    return Util.map("accepted", accepted, "serverTime", clock.instant());
  }

  @Transactional
  public Object scan(String code, String key, String ip, Scan r) {
    Device d = authenticate(code, key, ip);
    if (r.eventId == null || r.eventId.version() != 4 || r.bootId == null)
      throw Problem.bad("eventId phải là UUID v4");
    Util.choice(r.gate, "IN", "OUT");
    String uid = Util.uid(r.cardUid);
    String hash = Util.sha(r.bootId + "|" + r.gate + "|" + uid);
    db.lock(ParkingLot.class, ParkingService.LOT);
    d = db.lock(Device.class, d.id);
    if (!r.bootId.equals(d.currentBootId)) throw Problem.conflict("DEVICE_BOOT_NOT_REGISTERED");
    AccessEvent existing =
        db.one(AccessEvent.class, "where e.deviceId=?1 and e.eventId=?2", d.id, r.eventId)
            .orElse(null);
    if (existing != null) {
      if (!hash.equals(existing.requestHash)) throw Problem.conflict("IDEMPOTENCY_CONFLICT");
      try {
        return json.readValue(existing.responseJson, new TypeReference<Map<String, Object>>() {});
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    Card c = db.one(Card.class, "where e.uid=?1", uid).orElse(null);
    User owner = null;
    if (c != null) {
      owner = db.lock(User.class, c.ownerId);
      c = db.lock(Card.class, c.id);
    }
    ParkingSession session =
        c == null
            ? null
            : db.one(ParkingSession.class, "where e.cardId=?1 and e.status='OPEN'", c.id)
                .orElse(null);
    String reason = null;
    Instant now = clock.instant();
    if (c == null) reason = "UNKNOWN_CARD";
    else if (r.gate.equals("OUT")) {
      if (session == null) reason = "NO_ACTIVE_SESSION";
      else {
        session.status = "CLOSED";
        session.exitAt = now;
        session.closureType = "SCAN";
      }
    } else {
      if (c.status.equals("BLOCKED")) reason = "CARD_BLOCKED";
      else if (owner.status.equals("DISABLED")) reason = "USER_DISABLED";
      else if (c.expiresAt == null || !now.isBefore(c.expiresAt)) reason = "CARD_EXPIRED";
      else if (db.count(
              ParkingSession.class, "where e.ownerIdSnapshot=?1 and e.status='OPEN'", c.ownerId)
          > 0) reason = "ALREADY_INSIDE";
      else {
        var occupancy = parking.occupancy();
        if ((int) occupancy.get("unknownCount") > 0) reason = "OCCUPANCY_UNKNOWN";
        else if (((Number) occupancy.get("admissionAvailable")).longValue() == 0)
          reason = "PARKING_FULL";
      }
      if (reason == null) {
        session = new ParkingSession();
        session.lotId = ParkingService.LOT;
        session.cardId = c.id;
        session.ownerIdSnapshot = c.ownerId;
        session.status = "OPEN";
        session.entryAt = now;
        db.add(session);
      }
    }
    boolean allow = reason == null;
    if (allow) reason = r.gate.equals("IN") ? "ENTRY_ALLOWED" : "EXIT_ALLOWED";
    var response =
        Util.map(
            "eventId",
            r.eventId,
            "gate",
            r.gate,
            "decision",
            allow ? "ALLOW" : "DENY",
            "reason",
            reason,
            "sessionId",
            allow ? session.id : null,
            "command",
            allow ? "OPEN" : "NONE",
            "openDurationMs",
            allow ? 3000 : 0,
            "serverTime",
            now,
            "validUntil",
            allow ? now.plusSeconds(3) : null);
    AccessEvent event = new AccessEvent();
    event.deviceId = d.id;
    event.eventId = r.eventId;
    event.bootId = r.bootId;
    event.gate = r.gate;
    event.cardUid = uid;
    event.cardId = c == null ? null : c.id;
    event.sessionId = allow ? session.id : null;
    event.decision = allow ? "ALLOW" : "DENY";
    event.reason = reason;
    event.requestHash = hash;
    // Use the HTTP mapper: replay must preserve ISO timestamps as well as values.
    // Util.json's standalone mapper writes Instant as numeric epoch seconds.
    try {
      event.responseJson = json.writeValueAsString(response);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    event.receivedAt = now;
    db.add(event);
    db.flush();
    return response;
  }

  public Object summary(boolean detailed) {
    Device d = db.one(Device.class, "where e.code='ESP32-01'").orElseThrow();
    var v =
        Util.map(
            "code",
            d.code,
            "status",
            d.lastSeenAt == null
                ? "NEVER_SEEN"
                : d.lastSeenAt.isBefore(clock.instant().minusSeconds(15)) ? "OFFLINE" : "ONLINE",
            "lastSeenAt",
            d.lastSeenAt,
            "serverTime",
            clock.instant());
    if (detailed) {
      v.put("firmwareVersion", d.firmwareVersion);
      v.put("currentBootId", d.currentBootId);
      v.put("uptimeMs", d.uptimeMs);
    }
    return v;
  }
}
