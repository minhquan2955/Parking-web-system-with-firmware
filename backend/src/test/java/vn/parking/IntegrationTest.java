package vn.parking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.parking.auth.Actor;
import vn.parking.cards.CardService;
import vn.parking.common.*;
import vn.parking.devices.DeviceService;
import vn.parking.model.*;
import vn.parking.payments.*;
import vn.parking.users.UserService;

@SpringBootTest(
    properties = {
      "spring.profiles.active=dev",
      "parking.device-key=integration-device-key-123456789",
      "parking.admin-username=testadmin",
      "parking.admin-password=Integration-admin-12345"
    })
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IntegrationTest.TestClockConfig.class)
class IntegrationTest {
  @org.springframework.boot.test.context.TestConfiguration
  static class TestClockConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    MutableClock controlledClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @Autowired MutableClock clock;

  @BeforeEach
  void resetClock() {
    clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  static EmbeddedPostgres pg;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) throws Exception {
    pg = EmbeddedPostgres.builder().setPort(0).start();
    r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @AfterAll
  static void close() throws Exception {
    if (pg != null) pg.close();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Store db;
  @Autowired UserService users;
  @Autowired CardService cards;
  @Autowired DeviceService devices;
  @Autowired PaymentService payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager tm;
  static final String KEY = "integration-device-key-123456789";

  Actor admin() {
    User u = db.one(User.class, "where e.role='ADMIN'").orElseThrow();
    return new Actor(u.id, u.username, u.role, Util.sha(u.passwordHash));
  }

  UUID user() {
    return (UUID)
        users
            .create(
                "u" + UUID.randomUUID().toString().replace("-", ""),
                "Integration-user-12345",
                "Người kiểm thử",
                null,
                admin().id())
            .get("id");
  }

  UUID card(UUID owner) {
    return (UUID)
        cards
            .create(UUID.randomUUID().toString().replace("-", "").substring(0, 8), owner, admin())
            .get("id");
  }

  void clearParking() {
    jdbc.update(
        "update parking_sessions set status='VOIDED',exit_at=null,closure_type='ADMIN' where"
            + " status='OPEN'");
  }

  UUID heartbeat() {
    UUID boot = UUID.randomUUID();
    devices.heartbeat(
        "ESP32-01",
        KEY,
        "test",
        new DeviceService.Heartbeat(
            boot,
            1L,
            0L,
            "test",
            List.of(
                new DeviceService.Slot("S1", "FREE"),
                new DeviceService.Slot("S2", "FREE"),
                new DeviceService.Slot("S3", "FREE"))));
    return boot;
  }

  Map<?, ?> scan(UUID card, UUID boot, String gate, UUID event) {
    return (Map<?, ?>)
        devices.scan(
            "ESP32-01",
            KEY,
            "test",
            new DeviceService.Scan(event, boot, gate, db.get(Card.class, card).uid));
  }

  UUID order(UUID card) {
    return (UUID)
        payments
            .create(
                card,
                UUID.fromString("00000000-0000-0000-0000-000000000030"),
                UUID.randomUUID(),
                admin())
            .order()
            .get("id");
  }

  PaymentGateway.VerifiedPayment verified(UUID id, String ref) {
    var o = db.get(RenewalOrder.class, id);
    return new PaymentGateway.VerifiedPayment(
        "mock",
        o.providerOrderCode,
        "mock-" + o.providerOrderCode,
        ref,
        o.amountVnd,
        "VND",
        Instant.now(),
        true,
        Util.sha(ref));
  }

  @Test
  void AT36_38_39_40_registrationSecurity() throws Exception {
    String username = "reg" + UUID.randomUUID().toString().substring(0, 8);
    String body =
        Util.json(
            Util.map(
                "username",
                username.toUpperCase(),
                "password",
                "Registration-12345",
                "fullName",
                "Người mới"));
    mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(body.substring(0, body.length() - 1) + ",\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    User u = db.one(User.class, "where e.username=?1", username).orElseThrow();
    assertThat(u.passwordHash).startsWith("$2");
    assertThat(db.count(Card.class, "where e.ownerId=?1", u.id)).isZero();
  }

  @Test
  void AT37_concurrentRegistration() throws Exception {
    String username = "race" + UUID.randomUUID().toString().substring(0, 8);
    var results =
        parallel(
            10,
            () -> {
              try {
                users.create(username, "Concurrent-12345", "Test", null, null);
                return "OK";
              } catch (Problem p) {
                return p.code;
              }
            });
    assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
    assertThat(results).allMatch(s -> s.equals("OK") || s.equals("USERNAME_TAKEN"));
  }

  @Test
  void AT01_02_03_40_webOwnershipAndDisabledSession() throws Exception {
    UUID owner = user(), foreign = card(user());
    User u = db.get(User.class, owner);
    var response =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        Util.json(
                            Util.map(
                                "username", u.username, "password", "Integration-user-12345"))))
            .andExpect(status().isOk())
            .andReturn();
    var session = (MockHttpSession) response.getRequest().getSession(false);
    mvc.perform(get("/api/v1/cards/" + foreign).session(session)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/admin/users").session(session)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/cards").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    jdbc.update("update users set status='DISABLED' where id=?", owner);
    mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void AT05_07_09_10_11_parkingIdempotencyAndExit() throws Exception {
    clearParking();
    UUID card = card(user()), boot = heartbeat();
    assertThat(scan(card, boot, "IN", UUID.randomUUID()).get("reason")).isEqualTo("CARD_EXPIRED");
    jdbc.update("update cards set expires_at=now()+interval '30 day' where id=?", card);
    UUID event = UUID.randomUUID();
    var responses = parallel(10, () -> scan(card, boot, "IN", event));
    assertThat(responses).allMatch(r -> r.get("decision").equals("ALLOW"));
    assertThat(db.count(ParkingSession.class, "where e.cardId=?1 and e.status='OPEN'", card))
        .isEqualTo(1);
    assertThat(scan(card, boot, "IN", UUID.randomUUID()).get("reason")).isEqualTo("ALREADY_INSIDE");
    assertThatThrownBy(() -> scan(card, boot, "OUT", event)).isInstanceOf(Problem.class);
    jdbc.update(
        "update cards set status='BLOCKED',expires_at=now()-interval '1 day' where id=?", card);
    jdbc.update(
        "update users set status='DISABLED' where id=(select owner_id from cards where id=?)",
        card);
    assertThat(scan(card, boot, "OUT", UUID.randomUUID()).get("reason")).isEqualTo("EXIT_ALLOWED");
    assertThat(scan(card, boot, "OUT", UUID.randomUUID()).get("reason"))
        .isEqualTo("NO_ACTIVE_SESSION");
  }

  @Test
  void firmwareHttpContractPersistsEntryExitAndWebHistory() throws Exception {
    clearParking();
    UUID owner = user(), card = card(owner), boot = UUID.randomUUID();
    String uid = db.get(Card.class, card).uid;
    jdbc.update("update cards set expires_at=now()+interval '30 day' where id=?", card);
    var heartbeatBody =
        new DeviceService.Heartbeat(
            boot,
            0L,
            10L,
            "sps24-backend-1.0.0",
            List.of(
                new DeviceService.Slot("S1", "FREE"),
                new DeviceService.Slot("S2", "FREE"),
                new DeviceService.Slot("S3", "FREE")));
    mvc.perform(
            post("/api/v1/device/heartbeats")
                .header("X-Device-Id", "ESP32-01")
                .header("X-Device-Key", KEY)
                .contentType("application/json")
                .content(Util.json(heartbeatBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));
    UUID entryId = UUID.randomUUID();
    String entryBody = Util.json(new DeviceService.Scan(entryId, boot, "IN", uid));
    var entered =
        mvc.perform(
                post("/api/v1/device/access-events")
                    .header("X-Device-Id", "ESP32-01")
                    .header("X-Device-Key", KEY)
                    .contentType("application/json")
                    .content(entryBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("ALLOW"))
            .andExpect(jsonPath("$.command").value("OPEN"))
            .andExpect(jsonPath("$.openDurationMs").value(3000))
            .andExpect(jsonPath("$.eventId").value(entryId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var entryJson = json.readTree(entered);
    assertThat(
            Duration.between(
                Instant.parse(entryJson.get("serverTime").asText()),
                Instant.parse(entryJson.get("validUntil").asText())))
        .isEqualTo(Duration.ofSeconds(3));
    UUID sessionId = UUID.fromString(entryJson.get("sessionId").asText());
    clock.now = clock.now.plusSeconds(4);
    // A retry returns the original expiry and creates neither a new log nor a session.
    mvc.perform(
            post("/api/v1/device/access-events")
                .header("X-Device-Id", "ESP32-01")
                .header("X-Device-Key", KEY)
                .contentType("application/json")
                .content(entryBody))
        .andExpect(status().isOk())
        .andExpect(content().json(entered));
    mvc.perform(
            post("/api/v1/device/access-events")
                .header("X-Device-Id", "ESP32-01")
                .header("X-Device-Key", KEY)
                .contentType("application/json")
                .content(Util.json(new DeviceService.Scan(UUID.randomUUID(), boot, "OUT", uid))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("EXIT_ALLOWED"))
        .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
    mvc.perform(
            post("/api/v1/device/access-events")
                .header("X-Device-Id", "ESP32-01")
                .header("X-Device-Key", KEY)
                .contentType("application/json")
                .content(Util.json(new DeviceService.Scan(UUID.randomUUID(), boot, "OUT", uid))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("DENY"))
        .andExpect(jsonPath("$.reason").value("NO_ACTIVE_SESSION"));
    assertThat(db.count(ParkingSession.class, "where e.cardId=?1", card)).isEqualTo(1);
    assertThat(db.get(ParkingSession.class, sessionId).status).isEqualTo("CLOSED");
    assertThat(db.count(AccessEvent.class, "where e.cardId=?1", card)).isEqualTo(3);
    User user = db.get(User.class, owner);
    var login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        Util.json(
                            Util.map(
                                "username", user.username, "password", "Integration-user-12345"))))
            .andExpect(status().isOk())
            .andReturn();
    var session = (MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(get("/api/v1/parking/sessions").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(sessionId.toString()))
        .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
        .andExpect(jsonPath("$.items[0].entryAt").isNotEmpty())
        .andExpect(jsonPath("$.items[0].exitAt").isNotEmpty());
  }

  @Test
  void AT12_lastSpaceRace() throws Exception {
    clearParking();
    UUID boot = heartbeat();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      UUID id = card(user());
      jdbc.update("update cards set expires_at=now()+interval '30 day' where id=?", id);
      ids.add(id);
    }
    scan(ids.get(0), boot, "IN", UUID.randomUUID());
    scan(ids.get(1), boot, "IN", UUID.randomUUID());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var f1 = pool.submit(() -> scan(ids.get(2), boot, "IN", UUID.randomUUID()));
      var f2 = pool.submit(() -> scan(ids.get(3), boot, "IN", UUID.randomUUID()));
      assertThat(List.of(f1.get().get("decision"), f2.get().get("decision")))
          .containsExactlyInAnyOrder("ALLOW", "DENY");
    }
    assertThat(db.count(ParkingSession.class, "where e.status='OPEN'")).isEqualTo(3);
  }

  @Test
  void AT15_oldHeartbeatDoesNotRefresh() {
    UUID old = heartbeat(), current = heartbeat();
    Object result =
        devices.heartbeat(
            "ESP32-01",
            KEY,
            "test",
            new DeviceService.Heartbeat(
                old,
                999L,
                0L,
                "old",
                List.of(
                    new DeviceService.Slot("S1", "FREE"),
                    new DeviceService.Slot("S2", "FREE"),
                    new DeviceService.Slot("S3", "FREE"))));
    assertThat(((Map<?, ?>) result).get("accepted")).isEqualTo(false);
    assertThat(db.one(Device.class, "where e.code='ESP32-01'").orElseThrow().currentBootId)
        .isEqualTo(current);
  }

  @Test
  void AT18_19_21_42_43_paymentRaceHistory() throws Exception {
    clearParking();
    UUID card = card(user()), id = order(card);
    var p = verified(id, "ref-" + UUID.randomUUID());
    parallel(
        10,
        () -> {
          payments.apply(p, false, null, null);
          return true;
        });
    assertThat(db.count(CardRenewal.class, "where e.orderId=?1", id)).isEqualTo(1);
    CardRenewal first = db.one(CardRenewal.class, "where e.orderId=?1", id).orElseThrow();
    assertThat(first.oldExpiresAt).isNull();
    Instant original = first.newExpiresAt;
    UUID second = order(card);
    payments.apply(verified(second, "ref-" + UUID.randomUUID()), false, null, null);
    assertThat(db.get(Card.class, card).expiresAt).isEqualTo(original.plusSeconds(30 * 86400L));
    assertThat(payments.get(id, admin()).get("newExpiresAt")).isEqualTo(original);
    payments.apply(p, false, null, null);
    assertThat(db.get(Card.class, card).expiresAt).isEqualTo(original.plusSeconds(30 * 86400L));
  }

  @Test
  void AT22_24_25_28_35_paymentExceptions() {
    clearParking();
    UUID card = card(user()), id = order(card);
    var p = verified(id, "late-" + UUID.randomUUID());
    var o = db.get(RenewalOrder.class, id);
    jdbc.update("update renewal_orders set status='EXPIRED' where id=?", id);
    UUID next = order(card);
    var late =
        new PaymentGateway.VerifiedPayment(
            p.provider(),
            p.orderCode(),
            p.paymentId(),
            p.reference(),
            p.amount(),
            p.currency(),
            o.expiresAt.plusSeconds(2),
            true,
            p.deliveryHash());
    payments.apply(late, false, null, null);
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("REVIEW");
    assertThat(db.get(RenewalOrder.class, next).status).isEqualTo("PENDING");
    assertThat(db.count(CardRenewal.class, "where e.orderId=?1", id)).isZero();
    jdbc.update("update cards set status='BLOCKED' where id=?", card);
    payments.apply(verified(next, "blocked-" + UUID.randomUUID()), false, null, null);
    assertThat(db.get(Card.class, card).status).isEqualTo("BLOCKED");
    assertThat(db.get(RenewalOrder.class, next).status).isEqualTo("PAID");
  }

  @Test
  void AT30_transactionRollback() {
    clearParking();
    UUID c = card(user()), id = order(c);
    var tx = new TransactionTemplate(tm);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      payments.apply(
                          verified(id, "rollback-" + UUID.randomUUID()), false, null, null);
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(db.count(CardRenewal.class, "where e.orderId=?1", id)).isZero();
    assertThat(db.get(Card.class, c).expiresAt).isNull();
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("PENDING");
  }

  @Test
  void AT04_uidNormalizationAndUniqueness() {
    clearParking();
    String raw = "00:" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    UUID id = (UUID) cards.create(raw, user(), admin()).get("id");
    assertThat(db.get(Card.class, id).uid).startsWith("00").hasSize(8);
    UUID another = user();
    assertThatThrownBy(() -> cards.create(raw.toLowerCase(), another, admin()))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("UID_TAKEN");
  }

  @Test
  void AT05_boundaryExpiry() {
    clearParking();
    UUID c = card(user()), boot = heartbeat();
    jdbc.update(
        "update cards set expires_at=? where id=?", java.sql.Timestamp.from(clock.instant()), c);
    assertThat(scan(c, boot, "IN", UUID.randomUUID()).get("reason")).isEqualTo("CARD_EXPIRED");
  }

  @Test
  void AT13_14_unknownStaleAndExitPriority() {
    clearParking();
    UUID c = card(user()), boot = heartbeat();
    jdbc.update(
        "update cards set expires_at=? where id=?",
        java.sql.Timestamp.from(clock.instant().plusSeconds(86400)),
        c);
    assertThat(scan(c, boot, "IN", UUID.randomUUID()).get("decision")).isEqualTo("ALLOW");
    clock.now = clock.now.plusSeconds(16);
    assertThat(((Map<?, ?>) devices.summary(false)).get("status")).isEqualTo("OFFLINE");
    assertThat(scan(c, boot, "OUT", UUID.randomUUID()).get("decision")).isEqualTo("ALLOW");
    assertThat(scan(c, boot, "IN", UUID.randomUUID()).get("reason")).isEqualTo("OCCUPANCY_UNKNOWN");
    var p =
        new vn.parking.parking.ParkingService(db, clock, new vn.parking.audit.AuditService(db))
            .occupancy();
    assertThat(p.get("unknownCount")).isEqualTo(3);
    assertThat(p.get("freeCount")).isEqualTo(0);
  }

  @Autowired vn.parking.parking.ParkingService parking;

  @Test
  void AT17_32_correctionsAreAuditedAndDoNotChangeEvents() {
    clearParking();
    UUID c = card(user()), boot = heartbeat();
    jdbc.update("update cards set expires_at=now()+interval '1 day' where id=?", c);
    Map<?, ?> event = scan(c, boot, "IN", UUID.randomUUID());
    UUID session = (UUID) event.get("sessionId");
    assertThatThrownBy(() -> parking.correct(session, "VOID_ENTRY", "short", admin()))
        .isInstanceOf(Problem.class);
    parking.correct(session, "VOID_ENTRY", "Xe chưa thực sự qua cổng", admin());
    assertThat(db.get(ParkingSession.class, session).status).isEqualTo("VOIDED");
    assertThat(db.count(AccessEvent.class, "where e.sessionId=?1 and e.decision='ALLOW'", session))
        .isEqualTo(1);
    assertThat(db.count(AuditLog.class, "where e.entityId=?1", session)).isEqualTo(1);
    assertThatThrownBy(() -> parking.correct(session, "REOPEN", "Thử mở lại phiên đã hủy", admin()))
        .isInstanceOf(Problem.class);
  }

  @Test
  void AT18_creationIdempotencyAndConflict() throws Exception {
    clearParking();
    UUID c = card(user()),
        key = UUID.randomUUID(),
        pack = UUID.fromString("00000000-0000-0000-0000-000000000030");
    Actor a = admin();
    var results = parallel(10, () -> payments.create(c, pack, key, a));
    assertThat(results.stream().map(r -> r.order().get("id")).distinct().count()).isEqualTo(1);
    assertThat(db.count(RenewalOrder.class, "where e.cardId=?1", c)).isEqualTo(1);
    assertThatThrownBy(
            () ->
                payments.create(c, UUID.fromString("00000000-0000-0000-0000-000000000090"), key, a))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void AT20_expiredCardRenewalStartsAtApply() {
    clearParking();
    UUID c = card(user());
    jdbc.update(
        "update cards set expires_at=? where id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(86400)),
        c);
    UUID id = order(c);
    payments.apply(verified(id, "expired-" + UUID.randomUUID()), false, null, null);
    CardRenewal r = db.one(CardRenewal.class, "where e.orderId=?1", id).orElseThrow();
    assertThat(r.newExpiresAt).isEqualTo(r.appliedAt.plusSeconds(30 * 86400L));
  }

  @Test
  void AT22_amountMismatchAndExcessPaymentDoNotRenew() {
    clearParking();
    UUID c = card(user()), id = order(c);
    var p = verified(id, "amount-" + UUID.randomUUID());
    var wrong =
        new PaymentGateway.VerifiedPayment(
            p.provider(),
            p.orderCode(),
            p.paymentId(),
            p.reference(),
            BigDecimal.ONE,
            p.currency(),
            p.paidAt(),
            true,
            p.deliveryHash());
    payments.apply(wrong, false, null, null);
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("REVIEW");
    assertThat(db.get(Card.class, c).expiresAt).isNull();
    payments.apply(p, false, null, null);
    Instant expires = db.get(Card.class, c).expiresAt;
    payments.apply(verified(id, "extra-" + UUID.randomUUID()), false, null, null);
    assertThat(db.get(Card.class, c).expiresAt).isEqualTo(expires);
    assertThat(
            db.count(
                PaymentReceipt.class,
                "where e.orderId=?1 and e.processingStatus='EXCESS_PAYMENT_REVIEW'",
                id))
        .isEqualTo(1);
  }

  @Test
  void AT24_timelyPaymentArrivingAfterExpiry() {
    clearParking();
    UUID c = card(user()), id = order(c);
    var p = verified(id, "delayed-" + UUID.randomUUID());
    clock.now = clock.now.plusSeconds(1000);
    payments.expire();
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("EXPIRED");
    payments.apply(p, false, null, null);
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("PAID");
    assertThat(db.get(Card.class, c).expiresAt).isEqualTo(clock.instant().plusSeconds(30 * 86400L));
  }

  @Test
  void AT25_27_latePaymentRequiresAdminReconcile() {
    clearParking();
    UUID owner = user(), c = card(owner), id = order(c);
    payments.simulate(id, "LATE", admin());
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("REVIEW");
    User u = db.get(User.class, owner);
    Actor actor = new Actor(owner, u.username, "USER", Util.sha(u.passwordHash));
    assertThatThrownBy(() -> payments.reconcile(id, true, "Người dùng tự nhận khoản muộn", actor))
        .isInstanceOf(Problem.class);
    payments.reconcile(id, true, "Đã tra soát khoản trả muộn mô phỏng", admin());
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("PAID");
  }

  @Test
  void AT26_lateQrResponseDoesNotOverwritePayment() {
    clearParking();
    UUID c = card(user()), id = order(c);
    jdbc.update(
        "update renewal_orders set status='CREATING',qr_payload=null,provider_payment_id=null where"
            + " id=?",
        id);
    var p = verified(id, "early-webhook-" + UUID.randomUUID());
    payments.apply(p, false, null, null);
    payments.saveLink(id, new PaymentGateway.Link(p.paymentId(), "LATE_QR", null));
    assertThat(db.get(RenewalOrder.class, id).status).isEqualTo("PAID");
    assertThat(payments.get(id, admin()).get("qrPayload")).isNull();
  }

  @Test
  void AT29_twoOrdersSameCardDoNotLoseDuration() throws Exception {
    clearParking();
    UUID c = card(user()), first = order(c);
    jdbc.update("update renewal_orders set status='EXPIRED' where id=?", first);
    UUID second = order(c);
    var p1 = verified(first, "parallel1-" + UUID.randomUUID());
    var p2 = verified(second, "parallel2-" + UUID.randomUUID());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> payments.apply(p1, false, null, null));
      var b = pool.submit(() -> payments.apply(p2, false, null, null));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    assertThat(db.get(Card.class, c).expiresAt).isEqualTo(clock.instant().plusSeconds(60 * 86400L));
  }

  @Test
  void AT31_wrongDeviceKeyCannotMutate() throws Exception {
    long count = db.count(AccessEvent.class, "");
    mvc.perform(
            post("/api/v1/device/access-events")
                .header("X-Device-Id", "ESP32-01")
                .header("X-Device-Key", "bad-key")
                .contentType("application/json")
                .content(
                    Util.json(
                        new DeviceService.Scan(
                            UUID.randomUUID(), UUID.randomUUID(), "IN", "00112233"))))
        .andExpect(status().isUnauthorized());
    assertThat(db.count(AccessEvent.class, "")).isEqualTo(count);
  }

  @Test
  void AT39_invalidRequestsAreRateLimited() throws Exception {
    for (int i = 0; i < 5; i++)
      mvc.perform(
              post("/api/v1/auth/register")
                  .with(
                      r -> {
                        r.setRemoteAddr("198.51.100.50");
                        return r;
                      })
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"unexpected\":true}"))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/register")
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.50");
                      return r;
                    })
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  @Test
  void AT44_authenticatedCannotRegisterAndPasswordsAreNotEchoed() throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        "{\"username\":\"testadmin\",\"password\":\"Integration-admin-12345\"}"))
            .andExpect(status().isOk())
            .andReturn();
    var session = (MockHttpSession) result.getRequest().getSession(false);
    mvc.perform(
            post("/api/v1/auth/register")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"username\":\"another\",\"password\":\"TooShort\",\"fullName\":\"Another\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_AUTHENTICATED"));
    mvc.perform(
            post("/api/v1/auth/register")
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.51");
                      return r;
                    })
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"username\":\"another\",\"password\":\"TooShort\",\"fullName\":\"Another\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TooShort"))));
  }

  @Test
  void AT45_packageChangesDoNotAlterSnapshot() {
    clearParking();
    UUID c = card(user()), id = order(c);
    UUID pack = db.get(RenewalOrder.class, id).packageId;
    try {
      jdbc.update(
          "update renewal_packages set duration_days=3,price_vnd=1,enabled=false where id=?", pack);
      payments.apply(verified(id, "snapshot-" + UUID.randomUUID()), false, null, null);
      assertThat(db.get(Card.class, c).expiresAt)
          .isEqualTo(clock.instant().plusSeconds(30 * 86400L));
      assertThat(db.get(RenewalOrder.class, id).amountVnd).isEqualByComparingTo("30000");
    } finally {
      jdbc.update(
          "update renewal_packages set duration_days=30,price_vnd=30000,enabled=true where id=?",
          pack);
    }
  }

  @Autowired QueryService queries;

  @Test
  void AT42_appliedFilterUsesRenewalHistory() {
    clearParking();
    UUID c = card(user()), id = order(c);
    payments.apply(verified(id, "history-query-" + UUID.randomUUID()), false, null, null);
    var page =
        (Map<?, ?>)
            queries.list(
                "orders",
                Map.of(
                    "cardId",
                    c.toString(),
                    "applied",
                    "true",
                    "from",
                    clock.instant().minusSeconds(1).toString(),
                    "to",
                    clock.instant().plusSeconds(1).toString()),
                admin());
    assertThat((Long) page.get("totalElements")).isEqualTo(1L);
    var row = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
    assertThat(row.get("id")).isEqualTo(id);
    assertThat(row.get("oldExpiresAt")).isNull();
    assertThat(row.get("renewalAppliedAt")).isEqualTo(clock.instant());
  }

  @Test
  void loginFailuresAreLimitedAndPasswordChangeRevokesSessions() throws Exception {
    String body = "{\"username\":\"missing-user\",\"password\":\"Wrong-password-12345\"}";
    for (int i = 0; i < 10; i++)
      mvc.perform(
              post("/api/v1/auth/login")
                  .with(
                      r -> {
                        r.setRemoteAddr("198.51.100.70");
                        return r;
                      })
                  .with(csrf())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.70");
                      return r;
                    })
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isTooManyRequests());
    UUID id = user();
    User u = db.get(User.class, id);
    List<MockHttpSession> sessions = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      var r =
          mvc.perform(
                  post("/api/v1/auth/login")
                      .with(csrf())
                      .contentType("application/json")
                      .content(
                          Util.json(
                              Util.map(
                                  "username", u.username, "password", "Integration-user-12345"))))
              .andExpect(status().isOk())
              .andReturn();
      sessions.add((MockHttpSession) r.getRequest().getSession(false));
    }
    mvc.perform(
            put("/api/v1/me/password")
                .session(sessions.getFirst())
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"Integration-user-12345\",\"newPassword\":\"Updated-password-12345\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/me").session(sessions.getLast())).andExpect(status().isUnauthorized());
  }

  static <T> List<T> parallel(int n, Callable<T> job) throws Exception {
    try (var pool = Executors.newFixedThreadPool(n)) {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) futures.add(pool.submit(job));
      List<T> results = new ArrayList<>();
      for (var f : futures) results.add(f.get(30, TimeUnit.SECONDS));
      return results;
    }
  }
}
