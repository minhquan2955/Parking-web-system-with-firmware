package vn.parking.payments;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentJobs {
  private final PaymentService payments;

  public PaymentJobs(PaymentService payments) {
    this.payments = payments;
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void expiry() {
    payments.expire();
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void reconcile() {
    payments.scheduledReconcile();
  }
}
