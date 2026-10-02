package vn.parking.payments;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import vn.parking.auth.Actor;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "parking.payment-mode", havingValue = "mock")
public class MockPaymentController {
  private final PaymentService service;

  public MockPaymentController(PaymentService service) {
    this.service = service;
  }

  public record Simulation(String scenario) {}

  @PostMapping("/api/v1/admin/dev/renewal-orders/{id}/simulate-payment")
  Object simulate(@PathVariable UUID id, @RequestBody Simulation r) {
    return service.simulate(id, r.scenario, Actor.current());
  }
}
