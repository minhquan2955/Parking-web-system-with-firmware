package vn.parking.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import vn.parking.model.RenewalOrder;

public interface PaymentGateway {
  record Link(String paymentId, String qr, String url) {}

  record VerifiedPayment(
      String provider,
      Long orderCode,
      String paymentId,
      String reference,
      BigDecimal amount,
      String currency,
      Instant paidAt,
      boolean success,
      String deliveryHash) {}

  record QueryResult(String status, List<VerifiedPayment> payments, Link link) {}

  Link create(RenewalOrder order);

  QueryResult query(RenewalOrder order);

  VerifiedPayment verify(JsonNode body);
}
