package vn.parking.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.parking.auth.Actor;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final PaymentService service;

  public PaymentController(PaymentService service) {
    this.service = service;
  }

  public record Create(UUID packageId) {}

  public record Reconcile(boolean acceptLatePayment, String reason) {}

  @PostMapping("/cards/{id}/renewal-orders")
  ResponseEntity<?> create(
      @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key, @RequestBody Create r) {
    var result = service.create(id, r.packageId, key, Actor.current());
    return ResponseEntity.status(
            result.order().get("status").equals("CREATING") ? 202 : result.fresh() ? 201 : 200)
        .body(result.order());
  }

  @GetMapping("/renewal-orders/{id}")
  Object get(@PathVariable UUID id) {
    return service.get(id, Actor.current());
  }

  @PostMapping("/renewal-orders/{id}/reconcile")
  Object reconcile(@PathVariable UUID id, @RequestBody Reconcile r) {
    return service.reconcile(id, r.acceptLatePayment, r.reason, Actor.current());
  }

  @PostMapping("/payments/webhooks/payos")
  Object webhook(@RequestBody JsonNode payload) {
    service.webhook(payload);
    return java.util.Map.of("accepted", true);
  }
}
