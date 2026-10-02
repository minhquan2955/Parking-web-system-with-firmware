package vn.parking.common;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.parking.auth.Actor;
import vn.parking.cards.CardService;
import vn.parking.model.*;
import vn.parking.parking.ParkingService;
import vn.parking.payments.PaymentService;
import vn.parking.users.UserService;

@Service
@Transactional(readOnly = true)
public class QueryService {
  private final Store db;
  private final CardService cards;
  private final ParkingService parking;
  private final PaymentService payments;
  private final Clock clock;

  public QueryService(
      Store db, CardService cards, ParkingService parking, PaymentService payments, Clock clock) {
    this.db = db;
    this.cards = cards;
    this.parking = parking;
    this.payments = payments;
    this.clock = clock;
  }

  public Object list(String resource, Map<String, String> filters, Actor actor) {
    int page = integer(filters, "page", 0), size = integer(filters, "size", 20);
    if (page < 0 || size < 1 || size > 100 || page > 1000000)
      throw Problem.bad("Phân trang không hợp lệ");
    String search = filters.get("search");
    if (search != null && search.length() > 100) throw Problem.bad("Từ khóa quá dài");
    Instant from = instant(filters.get("from")), to = instant(filters.get("to"));
    if (from != null && to != null && to.isBefore(from))
      throw Problem.bad("Khoảng thời gian không hợp lệ");
    Class<?> type;
    String time = "createdAt";
    List<String> parts = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    Function<Object, Object> view;
    switch (resource) {
      case "users" -> {
        type = User.class;
        parts.add("e.role='USER'");
        if (search != null)
          add(
              parts,
              args,
              "(locate(?%d,e.username)>0 or locate(?%d,lower(e.fullName))>0)",
              search.toLowerCase(Locale.ROOT));
        enumFilter(filters, parts, args, "status", "ACTIVE", "DISABLED");
        view = o -> UserService.view((User) o);
      }
      case "cards" -> {
        type = Card.class;
        if (!actor.admin()) add(parts, args, "e.ownerId=?%d", actor.id());
        if (filters.containsKey("ownerId"))
          add(parts, args, "e.ownerId=?%d", UUID.fromString(filters.get("ownerId")));
        if (search != null)
          add(parts, args, "locate(?%d,e.uid)>0", search.toUpperCase(Locale.ROOT));
        if (filters.containsKey("effectiveStatus")) {
          String status = filters.get("effectiveStatus");
          Util.choice(status, "BLOCKED", "NOT_ACTIVATED", "EXPIRED", "ACTIVE");
          parts.add(
              switch (status) {
                case "BLOCKED" -> "e.status='BLOCKED'";
                case "NOT_ACTIVATED" -> "e.status='ENABLED' and e.expiresAt is null";
                case "EXPIRED" -> "e.status='ENABLED' and e.expiresAt<=?" + (args.size() + 1);
                default -> "e.status='ENABLED' and e.expiresAt>?" + (args.size() + 1);
              });
          if (status.equals("EXPIRED") || status.equals("ACTIVE")) args.add(clock.instant());
        }
        view = o -> cards.view((Card) o);
      }
      case "orders" -> {
        type = RenewalOrder.class;
        if (!actor.admin()) add(parts, args, "e.ownerIdSnapshot=?%d", actor.id());
        uuidFilter(filters, parts, args, "cardId");
        enumFilter(
            filters,
            parts,
            args,
            "status",
            "CREATING",
            "PENDING",
            "PAID",
            "EXPIRED",
            "FAILED",
            "REVIEW");
        if (filters.containsKey("applied")) {
          Util.choice(filters.get("applied"), "true", "false");
          boolean applied = Boolean.parseBoolean(filters.get("applied"));
          parts.add(
              (applied ? "" : "not ")
                  + "exists(select r.id from CardRenewal r where r.orderId=e.id)");
          if (applied) time = "(select r.appliedAt from CardRenewal r where r.orderId=e.id)";
        }
        view = o -> o;
      }
      case "sessions" -> {
        type = ParkingSession.class;
        time = "entryAt";
        if (!actor.admin()) add(parts, args, "e.ownerIdSnapshot=?%d", actor.id());
        uuidFilter(filters, parts, args, "cardId");
        enumFilter(filters, parts, args, "status", "OPEN", "CLOSED", "VOIDED");
        view = o -> parking.sessionView((ParkingSession) o);
      }
      case "events" -> {
        type = AccessEvent.class;
        time = "receivedAt";
        if (filters.containsKey("uid"))
          add(parts, args, "e.cardUid=?%d", Util.uid(filters.get("uid")));
        enumFilter(filters, parts, args, "gate", "IN", "OUT");
        enumFilter(filters, parts, args, "decision", "ALLOW", "DENY");
        view =
            o -> {
              AccessEvent e = (AccessEvent) o;
              return Util.map(
                  "id",
                  e.id,
                  "eventId",
                  e.eventId,
                  "deviceCode",
                  "ESP32-01",
                  "gate",
                  e.gate,
                  "cardUid",
                  e.cardUid,
                  "decision",
                  e.decision,
                  "reason",
                  e.reason,
                  "sessionId",
                  e.sessionId,
                  "receivedAt",
                  e.receivedAt);
            };
      }
      case "receipts" -> {
        type = PaymentReceipt.class;
        time = "receivedAt";
        uuidFilter(filters, parts, args, "orderId");
        enumFilter(
            filters,
            parts,
            args,
            "processingStatus",
            "RECEIVED",
            "APPLIED",
            "DUPLICATE",
            "REVIEW",
            "UNMATCHED",
            "EXCESS_PAYMENT_REVIEW");
        view =
            o -> {
              PaymentReceipt r = (PaymentReceipt) o;
              return Util.map(
                  "id",
                  r.id,
                  "provider",
                  r.provider,
                  "transactionRef",
                  r.transactionRef,
                  "orderId",
                  r.orderId,
                  "providerOrderCode",
                  r.providerOrderCode,
                  "amountVnd",
                  r.amountVnd,
                  "currency",
                  r.currency,
                  "paidAt",
                  r.paidAt,
                  "processingStatus",
                  r.processingStatus,
                  "receivedAt",
                  r.receivedAt);
            };
      }
      case "audit" -> {
        type = AuditLog.class;
        uuidFilter(filters, parts, args, "entityId");
        if (filters.containsKey("entityType"))
          add(parts, args, "e.entityType=?%d", filters.get("entityType"));
        view =
            o -> {
              AuditLog a = (AuditLog) o;
              return Util.map(
                  "id",
                  a.id,
                  "actorType",
                  a.actorType,
                  "actorId",
                  a.actorId,
                  "action",
                  a.action,
                  "entityType",
                  a.entityType,
                  "entityId",
                  a.entityId,
                  "beforeJson",
                  a.beforeJson,
                  "afterJson",
                  a.afterJson,
                  "reason",
                  a.reason,
                  "createdAt",
                  a.createdAt);
            };
      }
      default -> throw Problem.missing();
    }
    String column = time.startsWith("(") ? time : "e." + time;
    if (from != null) add(parts, args, column + ">=?%d", from);
    if (to != null) add(parts, args, column + "<?%d", to);
    String sort = filters.getOrDefault("sort", "desc");
    Util.choice(sort, "asc", "desc");
    String where = parts.isEmpty() ? "" : "where " + String.join(" and ", parts);
    long total = db.count(type, where, args.toArray());
    List<?> rows =
        db.page(
            type,
            where + " order by " + column + " " + sort + ", e.id " + sort,
            page,
            size,
            args.toArray());
    List<Object> items;
    if (type == RenewalOrder.class) {
      List<UUID> ids = rows.stream().map(o -> ((RenewalOrder) o).id).toList();
      Map<UUID, CardRenewal> renewals =
          ids.isEmpty()
              ? Map.of()
              : db.list(CardRenewal.class, "where e.orderId in ?1", ids).stream()
                  .collect(Collectors.toMap(r -> r.orderId, Function.identity()));
      items =
          rows.stream()
              .map(
                  o ->
                      (Object) payments.view((RenewalOrder) o, renewals.get(((RenewalOrder) o).id)))
              .toList();
    } else items = rows.stream().map(view).toList();
    return Util.map(
        "items",
        items,
        "page",
        page,
        "size",
        size,
        "totalElements",
        total,
        "totalPages",
        (total + size - 1) / size);
  }

  private static int integer(Map<String, String> q, String key, int fallback) {
    return q.containsKey(key) ? Integer.parseInt(q.get(key)) : fallback;
  }

  private static Instant instant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  private static void add(List<String> parts, List<Object> args, String format, Object value) {
    String n = Integer.toString(args.size() + 1);
    parts.add(format.replace("%d", n));
    args.add(value);
  }

  private static void uuidFilter(
      Map<String, String> q, List<String> parts, List<Object> args, String key) {
    if (q.containsKey(key)) add(parts, args, "e." + key + "=?%d", UUID.fromString(q.get(key)));
  }

  private static void enumFilter(
      Map<String, String> q, List<String> parts, List<Object> args, String key, String... values) {
    if (q.containsKey(key)) {
      Util.choice(q.get(key), values);
      add(parts, args, "e." + key + "=?%d", q.get(key));
    }
  }
}
