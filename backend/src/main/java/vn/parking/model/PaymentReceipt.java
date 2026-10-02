package vn.parking.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_receipts")
public class PaymentReceipt extends BaseEntity {
  @Column(name = "provider", nullable = false)
  public String provider;

  @Column(name = "transaction_ref", nullable = true)
  public String transactionRef;

  @Column(name = "delivery_hash", nullable = false)
  public String deliveryHash;

  @Column(name = "order_id", nullable = true)
  public UUID orderId;

  @Column(name = "provider_order_code", nullable = true)
  public Long providerOrderCode;

  @Column(name = "amount_vnd", nullable = true, precision = 12, scale = 0)
  public BigDecimal amountVnd;

  @Column(name = "currency", nullable = true)
  public String currency;

  @Column(name = "paid_at", nullable = true)
  public Instant paidAt;

  @Column(name = "verification_status", nullable = false)
  public String verificationStatus;

  @Column(name = "processing_status", nullable = false)
  public String processingStatus;

  @Column(name = "sanitized_payload", nullable = false, columnDefinition = "text")
  public String sanitizedPayload;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt;
}
