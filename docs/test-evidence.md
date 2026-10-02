# Verification evidence

This file records observed execution, not a declaration that physical hardware or real payments are complete. Commands run on Windows, Java 21.0.12.1, Node 24.14.0, Python 3.14.2. Tests use PostgreSQL processes, not H2 or mocked repositories.

## Executed

- Spring Boot compiled successfully; executable JAR produced at `backend/target/parking-api-1.0.0.jar`.
- IntegrationTest: **26 passed**, zero failures/errors/skips. Tests include a controllable backend Clock and real PostgreSQL concurrency/rollback.
- PayosSignatureTest: published HMAC example matched; tampering rejected; naive timestamp without an explicitly configured timezone did not produce paidAt.
- PaymentModeTest: mock controller exists for dev+mock and is absent for dev+payos or production+mock. Backend total: **28 tests passed**.
- Simulator: 5 tests passed (same-event retry, late response, reboot during request, no retry on 4xx, driver dedup).
- Native firmware policy: compiled with g++ C++17, `-Wall -Wextra -pedantic`; assertions passed for initial boot, serialized requests, presence debounce, deadline, dedup, reboot and monotonic uint32 wrap.
- Frontend: TypeScript check and Next.js production build passed. npm install reported zero dependency vulnerabilities.
- Chromium E2E: **4 passed in 49.2s**. Registration → login → empty USER state → denied admin route → ADMIN card assignment → mock duplicate callback → one renewal → device IN/OUT → parking history/access events/audit passed. Separate CSRF/device authentication and pre-hydration credential form tests passed.
- Polling E2E: ten alternating sensor changes each appeared within the 5s assertion deadline; after heartbeat stopped, the device became OFFLINE and all three slots UNKNOWN within the 19s assertion window. Injected browser API failure displayed the separate connection error. This is a local browser/simulator measurement, not hardware acceptance.
- `scripts/check_contract.py`: 31 operations, 42 schemas, 13 tables; all schema references resolved and every implemented controller operation matches OpenAPI.
- `docker compose --env-file .env.example config --quiet` passed. Full image build/Compose boot was not run because the Docker daemon is not running on this host.
- Performance: 100 sequential allowed IN/OUT scans through the local Next proxy; p95 38 ms, max 40.15 ms, mock payment request round trip 56.09 ms. See `benchmark.json`. These are loopback measurements, not physical LAN or real provider timings.
- Desktop/mobile screenshots: `dashboard-desktop.png`, `dashboard-mobile.png`; mobile checked at 390px, no horizontal page overflow.

## Acceptance mapping

| SRS tests | Automated evidence / limit |
| --- | --- |
| AT-01–03 | Backend session ownership, ADMIN restrictions, disabled session; browser USER denied ADMIN page |
| AT-04–05 | UID normalization/leading zeros/duplicate and null/equality expiry |
| AT-06–12 | Allowed scan, blocked/expired user's exit, repeated exit, open-session denial, 10 concurrent retries, conflicting key and competing last space |
| AT-13–15 | Clock-controlled stale data, UNKNOWN occupancy, OUT still allowed, OFFLINE, old boot heartbeat rejection |
| AT-16 | Python/C++ deadline and reboot tests; physical networking/actuator behavior still requires hardware |
| AT-17 | Persisted logical entry then audited VOID_ENTRY; simulator models ambiguous response. No physical gate evidence |
| AT-18–21 | Order-key race, payment race, one immutable renewal, expired-card base and historical expiry |
| AT-22 | Incorrect amount REVIEW, excess-payment receipt, published signature/tamper unit test |
| AT-23 | UI waits for backend status; payment core independent of browser. Live closed-tab webhook requires AT-33 |
| AT-24–26 | Timely payment with late delivery, late-payment ADMIN reconciliation, delayed QR metadata cannot overwrite PAID |
| AT-27 | Mock query uses shared renewal service. Real provider query fields must be verified on merchant account |
| AT-28–30 | Blocked card remains blocked after renewal, two orders concurrently sum durations, rollback leaves no partial renewal |
| AT-31 | Wrong device key rejected, session cannot replace device auth. Mode-dependent mock route tested separately |
| AT-32 | Correction reason/audit and VOIDED cannot reopen; DB constraints protect unique open sessions |
| AT-33 | **Not run: needs real payOS account/channel, HTTPS webhook, verified timestamps and actual payment** |
| AT-34 | **Physical run pending: drivers now use the uploaded ESP32 sketch's pin map; board build and host tests pass, wiring/motion still require hardware** |
| AT-35 | Expired old order becomes REVIEW alongside a newer PENDING order |
| AT-36–41 | Registration, normalize race, rejected fields, CSRF and rate limit, empty USER state, ADMIN assignment then mock payment (browser E2E) |
| AT-42–43 | Immutable per-order expiry history, null old expiry, repeated callback; applied filter queried separately |
| AT-44–45 | Invalid password isn't echoed, authenticated register conflict, package price/day/enable changes don't alter order snapshots |

Grouped mappings identify covered behaviors; they do not claim every possible subcase of every acceptance test or live integration has been exercised.

## Integration gaps and remaining environment work

- Live payOS: configure real credentials, receiving channel, agreed prices, webhook HTTPS and confirmed timestamp timezone. See payment-adapter.md for query schema limitations; missing trusted fields go to REVIEW.
- ESP32: the uploaded sketch supplies the pin map and peripherals. Hardware firmware and pinned PlatformIO build now exist; physical wiring, motion, RFID reliability and OTA still require on-board verification. See the firmware integration evidence below.
- Compose: the project's running Docker backend was rebuilt with the replay fix. Health is UP; public API requests to localhost and the configured LAN IP pass. Reachability from the physical ESP32 still requires an on-board test.
- Local restart preserved the generated ADMIN account (including its changed password), cards, orders and historical parking data. No DB reset was performed.

## Previous software baseline

The local app is running at `http://localhost:3000` using generated credentials in the ignored `.env`. Backend/frontend startup error logs were empty after the final restart. Final backend test reports: IntegrationTest 26/26, PayosSignatureTest 1/1, PaymentModeTest 1/1, zero skipped. Final browser report: 4/4. Simulator: 5/5. Native C++ assertions passed. No real bank transfer or hardware actuation was performed.

## Firmware/backend integration — 2026-10-02

- PlatformIO `esp32dev` build passed with espressif32 6.12.0 / Arduino-ESP32 2.0.17,
  MFRC522 1.4.12, ESP32Servo 1.1.2, LiquidCrystal_I2C 1.1.4, ElegantOTA 3.1.7,
  ArduinoJson 7.4.2. Firmware uses 911,957 bytes flash / 46,916 bytes static RAM;
  this excludes runtime heap and task stack usage. OTA partition limit is 1,966,080 bytes.
- Native controller/presentation assertions passed: startup registration, both-reader
  BUSY, matching event and direction, one execution, 3 s expiry, retry limit, deny,
  cancelled/rebooted requests, short RFID dropouts, held cards, independent readers,
  offline/BUSY presentation consumption and monotonic counter rollover.
- Native HTTP/JSON assertions passed: fragmented Content-Length/chunked responses,
  truncated/oversized/malformed framing, response correlation, strict ALLOW/DENY,
  unchanged validity interval, UTC fractional timestamps and date boundaries.
- Backend suite now **29/29 passed**, no skips: IntegrationTest 27, PaymentModeTest 1,
  PayosSignatureTest 1. New HTTP lifecycle test uses real temporary PostgreSQL and
  checks heartbeat, entry, replay after 4 s without renewing expiry, exit, denied
  second exit, exactly one session, three event rows and USER web history.
- This new regression caught numeric timestamps in replayed access responses.
  `DeviceService` now persists responses with the HTTP ObjectMapper, keeping ISO
  timestamps and the original expiration. No database migration was needed.
- Python simulator 5/5 and API/schema contract check passed. PowerShell startup
  syntax and embedded diagnostic JavaScript syntax/element references checked.
- Local private firmware config preserves Wi-Fi credentials, uses the existing
  device key and sets the host LAN IP to 192.168.1.117. Git ignores this config
  and `.pio` build artifacts. Host IP must be updated after a DHCP address change.
- Running Docker backend updated with `docker compose up -d --build --no-deps backend`;
  proxy reloaded to resolve the recreated container. `/actuator/health` on the
  internal management port 8081 reports UP. Public API checked from this computer
  over 127.0.0.1:3000 and 192.168.1.117:3000: HTTP 200. The firmware key hash matches
  the enabled ESP32-01 record in the existing Docker database `parking`. No live
  device heartbeat, scan, test session, data reset or schema migration was needed.
- No board was flashed, RFID physically scanned, servo actuated, or real OTA
  performed during this verification. AT-34 remains pending those hardware tests.
