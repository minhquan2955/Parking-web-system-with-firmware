package vn.parking.common;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.parking.auth.Actor;
import vn.parking.cards.CardService;
import vn.parking.devices.DeviceService;
import vn.parking.model.*;
import vn.parking.parking.ParkingService;
import vn.parking.users.UserService;

@RestController
@RequestMapping("/api/v1")
public class WebController {
  private final Store db;
  private final QueryService queries;
  private final UserService users;
  private final CardService cards;
  private final ParkingService parking;
  private final DeviceService devices;

  public WebController(
      Store db,
      QueryService queries,
      UserService users,
      CardService cards,
      ParkingService parking,
      DeviceService devices) {
    this.db = db;
    this.queries = queries;
    this.users = users;
    this.cards = cards;
    this.parking = parking;
    this.devices = devices;
  }

  public record CreateUser(
      String username, String initialPassword, String fullName, String phone) {}

  public record UpdateUser(String fullName, String phone, String status) {}

  public record CreateCard(String uid, UUID ownerId) {}

  public record Status(String status, String reason) {}

  public record Correction(String action, String reason) {}

  @GetMapping("/admin/users")
  Object users(@RequestParam Map<String, String> q) {
    return queries.list("users", q, Actor.current());
  }

  @PostMapping("/admin/users")
  ResponseEntity<?> user(@RequestBody CreateUser r) {
    return ResponseEntity.status(201)
        .body(
            users.create(r.username, r.initialPassword, r.fullName, r.phone, Actor.current().id()));
  }

  @PatchMapping("/admin/users/{id}")
  Object user(@PathVariable UUID id, @RequestBody UpdateUser r) {
    return users.update(id, r.fullName, r.phone, r.status, Actor.current());
  }

  @GetMapping("/cards")
  Object cards(@RequestParam Map<String, String> q) {
    return queries.list("cards", q, Actor.current());
  }

  @GetMapping("/cards/{id}")
  Object card(@PathVariable UUID id) {
    Card c = db.get(Card.class, id);
    Actor.current().owner(c.ownerId);
    return cards.view(c);
  }

  @PostMapping("/admin/cards")
  ResponseEntity<?> card(@RequestBody CreateCard r) {
    if (r.ownerId == null) throw Problem.bad("Thiếu chủ thẻ");
    return ResponseEntity.status(201).body(cards.create(r.uid, r.ownerId, Actor.current()));
  }

  @PatchMapping("/admin/cards/{id}/status")
  Object status(@PathVariable UUID id, @RequestBody Status r) {
    return cards.status(id, r.status, r.reason, Actor.current());
  }

  @GetMapping("/renewal-packages")
  Object packages() {
    return db.list(RenewalPackage.class, "where e.enabled=true order by e.durationDays").stream()
        .map(
            p ->
                Util.map(
                    "id",
                    p.id,
                    "code",
                    p.code,
                    "name",
                    p.name,
                    "durationDays",
                    p.durationDays,
                    "priceVnd",
                    p.priceVnd,
                    "currency",
                    "VND"))
        .toList();
  }

  @GetMapping("/renewal-orders")
  Object orders(@RequestParam Map<String, String> q) {
    return queries.list("orders", q, Actor.current());
  }

  @GetMapping("/parking/sessions")
  Object sessions(@RequestParam Map<String, String> q) {
    return queries.list("sessions", q, Actor.current());
  }

  @GetMapping("/parking/occupancy")
  Object occupancy() {
    return parking.occupancy();
  }

  @GetMapping("/devices/summary")
  Object summary() {
    return devices.summary(false);
  }

  @GetMapping("/admin/devices")
  Object devices() {
    return List.of(devices.summary(true));
  }

  @GetMapping("/admin/access-events")
  Object events(@RequestParam Map<String, String> q) {
    return queries.list("events", q, Actor.current());
  }

  @GetMapping("/admin/audit-logs")
  Object audit(@RequestParam Map<String, String> q) {
    return queries.list("audit", q, Actor.current());
  }

  @GetMapping("/admin/payment-receipts")
  Object receipts(@RequestParam Map<String, String> q) {
    return queries.list("receipts", q, Actor.current());
  }

  @PostMapping("/admin/parking/sessions/{id}/corrections")
  Object correction(@PathVariable UUID id, @RequestBody Correction r) {
    return parking.correct(id, r.action, r.reason, Actor.current());
  }
}
