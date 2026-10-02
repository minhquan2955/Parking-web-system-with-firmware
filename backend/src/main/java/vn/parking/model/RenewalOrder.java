package vn.parking.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "renewal_orders")
public class RenewalOrder extends BaseEntity {
  @Column(name = "card_id", nullable = false)
  public UUID cardId;

  @Column(name = "owner_id_snapshot", nullable = false)
  public UUID ownerIdSnapshot;

  @Column(name = "created_by", nullable = false)
  public UUID createdBy;

  @Column(name = "package_id", nullable = false)
  public UUID packageId;

  @Column(name = "package_code_snapshot", nullable = false)
  public String packageCodeSnapshot;

  @Column(name = "duration_days_snapshot", nullable = false)
  public Integer durationDaysSnapshot;

  @Column(name = "amount_vnd", nullable = false, precision = 12, scale = 0)
  public BigDecimal amountVnd;

  @Column(name = "currency", nullable = false)
  public String currency;

  @Column(name = "provider", nullable = false)
  public String provider;

  @Column(name = "provider_order_code", nullable = false)
  public Long providerOrderCode;

  @Column(name = "provider_payment_id", nullable = true)
  public String providerPaymentId;

  @Column(name = "qr_payload", nullable = true, columnDefinition = "text")
  public String qrPayload;

  @Column(name = "checkout_url", nullable = true, columnDefinition = "text")
  public String checkoutUrl;

  @Column(name = "status", nullable = false)
  public String status;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @Column(name = "paid_at", nullable = true)
  public Instant paidAt;

  @Column(name = "idempotency_key", nullable = false)
  public UUID idempotencyKey;

  @Column(name = "request_hash", nullable = false)
  public String requestHash;

  @Column(name = "next_reconcile_at", nullable = true)
  public Instant nextReconcileAt;
}
