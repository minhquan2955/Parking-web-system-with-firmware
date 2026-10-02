package vn.parking.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.parking.common.Problem;
import vn.parking.common.Store;
import vn.parking.model.PaymentReceipt;
import vn.parking.model.RenewalOrder;

@Component
@ConditionalOnProperty(name = "parking.payment-mode", havingValue = "mock")
public class MockPaymentGateway implements PaymentGateway {
  private final Store db;
  private final Clock clock;

  public MockPaymentGateway(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Link create(RenewalOrder o) {
    return new Link("mock-" + o.providerOrderCode, "DEMO_ONLY:" + o.id, null);
  }

  public QueryResult query(RenewalOrder o) {
    var receipts = db.list(PaymentReceipt.class, "where e.orderId=?1", o.id);
    var payments =
        receipts.stream()
            .map(
                r ->
                    new VerifiedPayment(
                        "mock",
                        o.providerOrderCode,
                        "mock-" + o.providerOrderCode,
                        r.transactionRef,
                        r.amountVnd,
                        r.currency,
                        r.paidAt,
                        true,
                        r.deliveryHash))
            .toList();
    return new QueryResult(
        payments.isEmpty() ? clock.instant().isBefore(o.expiresAt) ? "PENDING" : "EXPIRED" : "PAID",
        payments,
        create(o));
  }

  public VerifiedPayment verify(JsonNode body) {
    throw Problem.missing();
  }
}
