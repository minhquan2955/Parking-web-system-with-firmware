package vn.parking.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.parking.audit.AuditService;
import vn.parking.auth.*;
import vn.parking.common.*;
import vn.parking.model.*;

@Service
public class PaymentService {
  private final Store db;
  private final Clock clock;
  private final PaymentGateway gateway;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final RateLimiter rates;
  private final String mode;

  public PaymentService(
      Store db,
      Clock clock,
      PaymentGateway gateway,
      AuditService audit,
      PlatformTransactionManager tm,
      RateLimiter rates,
      @Value("${parking.payment-mode}") String mode) {
    this.db = db;
    this.clock = clock;
    this.gateway = gateway;
    this.audit = audit;
    this.tx = new TransactionTemplate(tm);
    this.rates = rates;
    this.mode = mode;
  }

  public record Creation(Map<String, Object> order, boolean fresh) {}

  public Map<String, Object> view(RenewalOrder o, CardRenewal r) {
    return Util.map(
        "id",
        o.id,
        "cardId",
        o.cardId,
        "packageCode",
        o.packageCodeSnapshot,
        "durationDays",
        o.durationDaysSnapshot,
        "amountVnd",
        o.amountVnd,
        "currency",
        o.currency,
        "paymentMode",
        o.provider,
        "status",
        o.status,
        "createdAt",
        o.createdAt,
        "expiresAt",
        o.expiresAt,
        "serverTime",
        clock.instant(),
        "qrPayload",
        o.status.equals("PENDING") && clock.instant().isBefore(o.expiresAt) ? o.qrPayload : null,
        "checkoutUrl",
        o.status.equals("PENDING") && clock.instant().isBefore(o.expiresAt) ? o.checkoutUrl : null,
        "paidAt",
        o.paidAt,
        "renewalAppliedAt",
        r == null ? null : r.appliedAt,
        "oldExpiresAt",
        r == null ? null : r.oldExpiresAt,
        "newExpiresAt",
        r == null ? null : r.newExpiresAt);
  }

  public Map<String, Object> get(UUID id, Actor actor) {
    return tx.execute(
        s -> {
          RenewalOrder o = db.get(RenewalOrder.class, id);
          actor.owner(o.ownerIdSnapshot);
          return view(o, renewal(id));
        });
  }

  public Creation create(UUID cardId, UUID packageId, UUID key, Actor actor) {
    if (packageId == null || key == null) throw Problem.bad("Thiếu gói hoặc Idempotency-Key");
    String hash = Util.sha(actor.id() + "|" + cardId + "|" + packageId);
    record Started(RenewalOrder order, boolean fresh) {}
    Started start =
        tx.execute(
            s -> {
              db.advisory("order-key:" + actor.id() + ":" + key);
              RenewalOrder old =
                  db.one(
                          RenewalOrder.class,
                          "where e.createdBy=?1 and e.idempotencyKey=?2",
                          actor.id(),
                          key)
                      .orElse(null);
              if (old != null) {
                if (!hash.equals(old.requestHash)) throw Problem.conflict("IDEMPOTENCY_CONFLICT");
                actor.owner(old.ownerIdSnapshot);
                return new Started(old, false);
              }
              Card found = db.get(Card.class, cardId);
              actor.owner(found.ownerId);
              User owner = db.lock(User.class, found.ownerId);
              Card card = db.lock(Card.class, cardId);
              if (!owner.status.equals("ACTIVE")) throw Problem.conflict("USER_DISABLED");
              if (!card.status.equals("ENABLED")) throw Problem.conflict("CARD_BLOCKED");
              var active =
                  db.one(
                      RenewalOrder.class,
                      "where e.cardId=?1 and e.status in ('CREATING','PENDING','REVIEW')",
                      cardId);
              if (active.isPresent())
                throw new Problem(
                        409,
                        "ORDER_IN_PROGRESS",
                        "Thẻ đang có đơn cần xử lý. Mở danh sách đơn bên dưới để tiếp tục.")
                    .withOrder(active.get().id);
              RenewalPackage p = db.get(RenewalPackage.class, packageId);
              if (!p.enabled) throw Problem.conflict("PACKAGE_DISABLED");
              RenewalOrder o = new RenewalOrder();
              o.cardId = cardId;
              o.ownerIdSnapshot = card.ownerId;
              o.createdBy = actor.id();
              o.packageId = p.id;
              o.packageCodeSnapshot = p.code;
              o.durationDaysSnapshot = p.durationDays;
              o.amountVnd = p.priceVnd;
              o.currency = "VND";
              o.provider = mode;
              o.providerOrderCode = db.nextOrderCode();
              o.status = "CREATING";
              o.createdAt = clock.instant();
              o.updatedAt = clock.instant();
              o.expiresAt = o.createdAt.plusSeconds(900);
              o.idempotencyKey = key;
              o.requestHash = hash;
              o.nextReconcileAt = clock.instant().plusSeconds(60);
              db.add(o);
              return new Started(o, true);
            });
    if (start.fresh) {
      try {
        saveLink(start.order.id, gateway.create(start.order));
      } catch (Problem uncertain) {
        /* Preserve CREATING and the original code; reconciliation owns recovery. */
      }
    }
    return new Creation(get(start.order.id, actor), start.fresh);
  }

  private CardRenewal renewal(UUID id) {
    return db.one(CardRenewal.class, "where e.orderId=?1", id).orElse(null);
  }

  public void saveLink(UUID id, PaymentGateway.Link link) {
    tx.executeWithoutResult(
        s -> {
          RenewalOrder found = db.get(RenewalOrder.class, id);
          db.lock(Card.class, found.cardId);
          RenewalOrder o = db.lock(RenewalOrder.class, id);
          if (link.paymentId() != null) {
            if (o.providerPaymentId != null && !o.providerPaymentId.equals(link.paymentId())) {
              if (!o.status.equals("PAID")) o.status = "REVIEW";
              return;
            }
            o.providerPaymentId = link.paymentId();
          }
          if (o.status.equals("CREATING")
              && clock.instant().isBefore(o.expiresAt)
              && link.qr() != null) {
            o.qrPayload = link.qr();
            o.checkoutUrl = link.url();
            o.status = "PENDING";
          }
        });
  }

  public void webhook(JsonNode payload) {
    apply(gateway.verify(payload), false, null, null);
  }

  public void apply(
      PaymentGateway.VerifiedPayment p, boolean acceptLate, UUID actor, String reason) {
    tx.executeWithoutResult(
        s -> {
          RenewalOrder found =
              p.orderCode() == null
                  ? null
                  : db.one(
                          RenewalOrder.class,
                          "where e.provider=?1 and e.providerOrderCode=?2",
                          p.provider(),
                          p.orderCode())
                      .orElse(null);
          Card card = found == null ? null : db.lock(Card.class, found.cardId);
          RenewalOrder o = found == null ? null : db.lock(RenewalOrder.class, found.id);
          db.advisory(
              "receipt:" + p.provider() + ":" + Objects.toString(p.reference(), p.deliveryHash()));
          PaymentReceipt receipt =
              p.reference() == null
                  ? db.one(
                          PaymentReceipt.class,
                          "where e.provider=?1 and e.deliveryHash=?2",
                          p.provider(),
                          p.deliveryHash())
                      .orElse(null)
                  : db.one(
                          PaymentReceipt.class,
                          "where e.provider=?1 and e.transactionRef=?2",
                          p.provider(),
                          p.reference())
                      .orElse(null);
          if (receipt != null
              && receipt.orderId != null
              && (o == null || !receipt.orderId.equals(o.id))) {
            audit.record(
                actor,
                "PAYMENT_REFERENCE_CONFLICT",
                "PAYMENT_RECEIPT",
                receipt.id,
                null,
                Util.map("incomingOrderCode", p.orderCode()),
                reason);
            return;
          }
          boolean newReceipt = receipt == null;
          boolean wasApplied = receipt != null && receipt.processingStatus.equals("APPLIED");
          if (receipt == null) {
            receipt = new PaymentReceipt();
            receipt.provider = p.provider();
            receipt.transactionRef = p.reference();
            receipt.deliveryHash = p.deliveryHash();
            receipt.receivedAt = clock.instant();
          }
          CardRenewal existing = o == null ? null : renewal(o.id);
          if (o != null && o.status.equals("PAID") != (existing != null))
            throw Problem.conflict("PAYMENT_CONSISTENCY_ERROR");
          if (existing != null && wasApplied) return;
          receipt.orderId = o == null ? null : o.id;
          receipt.providerOrderCode = p.orderCode();
          receipt.amountVnd = p.amount();
          receipt.currency = p.currency();
          receipt.paidAt = p.paidAt();
          receipt.verificationStatus = "VERIFIED";
          receipt.processingStatus = "RECEIVED";
          receipt.sanitizedPayload =
              Util.json(Util.map("success", p.success(), "paymentId", p.paymentId()));
          if (newReceipt) db.add(receipt);
          if (o == null) {
            receipt.processingStatus = "UNMATCHED";
            return;
          }
          if (existing != null) {
            receipt.processingStatus = "EXCESS_PAYMENT_REVIEW";
            return;
          }
          boolean valid =
              p.success()
                  && p.reference() != null
                  && !p.reference().isBlank()
                  && p.paymentId() != null
                  && p.amount() != null
                  && p.amount().compareTo(o.amountVnd) == 0
                  && "VND".equals(p.currency())
                  && p.paidAt() != null
                  && (o.providerPaymentId == null || o.providerPaymentId.equals(p.paymentId()));
          if (!valid
              || o.status.equals("FAILED")
              || p.paidAt().isAfter(o.expiresAt) && !acceptLate) {
            receipt.processingStatus = "REVIEW";
            String previous = o.status;
            o.status = "REVIEW";
            if (!previous.equals("REVIEW"))
              audit.record(
                  actor,
                  "PAYMENT_REVIEW",
                  "RENEWAL_ORDER",
                  o.id,
                  Util.map("status", previous),
                  Util.map("status", "REVIEW"),
                  "Thông tin thanh toán cần đối chiếu");
            return;
          }
          Instant now = clock.instant();
          CardRenewal r = new CardRenewal();
          r.orderId = o.id;
          r.cardId = card.id;
          r.oldExpiresAt = card.expiresAt;
          r.appliedAt = now;
          r.newExpiresAt =
              (card.expiresAt != null && card.expiresAt.isAfter(now) ? card.expiresAt : now)
                  .plusSeconds(o.durationDaysSnapshot * 86400L);
          db.add(r);
          card.expiresAt = r.newExpiresAt;
          o.status = "PAID";
          o.paidAt = p.paidAt();
          o.providerPaymentId = p.paymentId();
          receipt.processingStatus = "APPLIED";
          audit.record(
              actor,
              "CARD_RENEWED",
              "CARD",
              card.id,
              Util.map("expiresAt", r.oldExpiresAt),
              Util.map("expiresAt", r.newExpiresAt, "orderId", o.id),
              reason);
          db.flush();
        });
  }

  public Map<String, Object> reconcile(UUID id, boolean acceptLate, String reason, Actor actor) {
    get(id, actor);
    if (acceptLate) {
      if (!actor.admin())
        throw new Problem(403, "FORBIDDEN", "Chỉ ADMIN được nhận thanh toán muộn");
      Util.reason(reason);
    }
    if (rates.take("reconcile:" + id, 1, 30) > 0)
      throw new Problem(429, "RECONCILE_RATE_LIMITED", "Chờ 30 giây trước khi tra soát lại");
    query(id, acceptLate, actor.id(), reason);
    return get(id, actor);
  }

  public void query(UUID id, boolean acceptLate, UUID actor, String reason) {
    RenewalOrder order = db.get(RenewalOrder.class, id);
    if (order.status.equals("PAID")) return;
    if (!order.provider.equals(mode)) throw Problem.conflict("PAYMENT_MODE_MISMATCH");
    PaymentGateway.QueryResult result = gateway.query(order);
    for (var p : result.payments()) apply(p, acceptLate, actor, reason);
    if (result.link() != null) saveLink(id, result.link());
    tx.executeWithoutResult(
        s -> {
          db.lock(Card.class, order.cardId);
          RenewalOrder o = db.lock(RenewalOrder.class, id);
          if (!o.status.equals("PAID")) {
            boolean expired = !clock.instant().isBefore(o.expiresAt);
            if (result.payments().isEmpty()
                && expired
                && Set.of("CANCELLED", "EXPIRED").contains(result.status())) o.status = "EXPIRED";
            else if (o.status.equals("CREATING")
                && o.createdAt.isBefore(clock.instant().minusSeconds(60))) o.status = "REVIEW";
          }
          o.nextReconcileAt = clock.instant().plusSeconds(60);
          if (actor != null)
            audit.record(
                actor,
                "PAYMENT_RECONCILED",
                "RENEWAL_ORDER",
                id,
                null,
                Util.map("status", o.status, "acceptLatePayment", acceptLate),
                reason);
        });
  }

  public void expire() {
    var ids =
        db
            .list(
                RenewalOrder.class, "where e.status='PENDING' and e.expiresAt<=?1", clock.instant())
            .stream()
            .map(o -> o.id)
            .toList();
    for (UUID id : ids)
      tx.executeWithoutResult(
          s -> {
            var found = db.get(RenewalOrder.class, id);
            db.lock(Card.class, found.cardId);
            var o = db.lock(RenewalOrder.class, id);
            if (o.status.equals("PENDING") && !clock.instant().isBefore(o.expiresAt))
              o.status = "EXPIRED";
          });
  }

  public void scheduledReconcile() {
    var orders =
        db.page(
            RenewalOrder.class,
            "where e.provider=?3 and (e.status in ('CREATING','PENDING','REVIEW') or"
                + " (e.status='EXPIRED' and e.expiresAt>?1)) and (e.nextReconcileAt is null or"
                + " e.nextReconcileAt<=?2) order by e.createdAt",
            0,
            10,
            clock.instant().minusSeconds(86400),
            clock.instant(),
            mode);
    for (var o : orders) {
      try {
        query(o.id, false, null, null);
      } catch (RuntimeException ex) {
        tx.executeWithoutResult(
            s -> {
              db.lock(Card.class, o.cardId);
              var current = db.lock(RenewalOrder.class, o.id);
              current.nextReconcileAt = clock.instant().plusSeconds(300);
              if (current.status.equals("CREATING")
                  && current.createdAt.isBefore(clock.instant().minusSeconds(60)))
                current.status = "REVIEW";
            });
      }
    }
  }

  public Map<String, Object> simulate(UUID id, String scenario, Actor actor) {
    Util.choice(scenario, "SUCCESS", "FAILURE", "AMOUNT_MISMATCH", "LATE", "DUPLICATE");
    get(id, actor);
    RenewalOrder o = db.get(RenewalOrder.class, id);
    if (scenario.equals("FAILURE")) {
      tx.executeWithoutResult(
          s -> {
            db.lock(Card.class, o.cardId);
            RenewalOrder current = db.lock(RenewalOrder.class, id);
            if (!current.status.equals("PENDING")) throw Problem.conflict("ORDER_NOT_PENDING");
            current.status = "FAILED";
            audit.record(
                actor.id(),
                "PAYMENT_SIMULATED_FAILURE",
                "RENEWAL_ORDER",
                id,
                Util.map("status", "PENDING"),
                Util.map("status", "FAILED"),
                "Mô phỏng thanh toán thất bại");
          });
      return get(id, actor);
    }
    if (!o.status.equals("PENDING")) throw Problem.conflict("ORDER_NOT_PENDING");
    var p =
        new PaymentGateway.VerifiedPayment(
            "mock",
            o.providerOrderCode,
            "mock-" + o.providerOrderCode,
            "mock-ref-" + id,
            o.amountVnd.add(scenario.equals("AMOUNT_MISMATCH") ? BigDecimal.ONE : BigDecimal.ZERO),
            "VND",
            scenario.equals("LATE") ? o.expiresAt.plusSeconds(1) : clock.instant(),
            true,
            Util.sha("mock-ref-" + id));
    apply(p, false, actor.id(), "Mô phỏng thanh toán dev");
    if (scenario.equals("DUPLICATE")) apply(p, false, actor.id(), "Mô phỏng callback lặp");
    return get(id, actor);
  }
}
