# Implementation decisions

Source of truth: `IoT_System (2).md`, SRS 2.3. No business requirements superseded.

## CONFIRMED
C-01–C-12 apply: Spring Boot/Next.js/PostgreSQL; one ESP32/two readers/three sensors; USER/ADMIN with public USER registration; ADMIN assigns physical cards; exits honor existing sessions even after expiry; backend required; verified payment renews automatically; packages/orders/renewals remain separate. This delivery implements the project directly, not lessons.

## DEFAULT
D-01 REST/JSON, D-02 visible-tab polling 3s, D-03 prepaid duration, D-05 one enabled card/user, D-06 owner or ADMIN creates orders, D-07 payOS plus explicitly separate mock, D-08 30/90/365 days, D-09 logical sessions, D-10 independent simulated gates, D-11 exits prioritize open session, D-12 audited corrections without gate control. LAN HTTP only for supervised demo. Session authentication, no JWT. A modular monolith with JPA repositories and PostgreSQL locks.

## TBD / integration gates
The supplied `firmware/SPS_24_Doi_Cong.ino` now establishes ESP32-WROOM, two RC522 readers on shared SPI, three HC-SR04 sensors, two SG90 servos, IR anti-pinch inputs, LCD and the original pin map. The integration preserves these assignments; physical wiring, power, orientation and motion still require AT-34 hardware evidence. Real payment requires credentials, verified provider timestamp/timezone and payment channel. Real prices, host address and HTTPS webhook ingress must be configured by operator. AT-33/34 cannot be passed using mocks.

## Firmware integration amendment — 2026-10-02

The user explicitly selected retaining both the existing diagnostic web server and OTA when integrating the uploaded sketch. This supersedes the original SRS exclusion of OTA for this device. The local web remains diagnostic only; the central backend authorizes RFID and stores logical sessions. OTA uses separate local credentials and suspends new access. The original IR motion cycle continues locally. No web gate-opening API, offline whitelist, physical-passage session semantics or database migration was added. Local startup now supports optional `-Lan` on web port 3000; backend remains on loopback.

## Delivery order
OpenAPI and Flyway contract; security/users/cards; transactional parking/device; payment service; web; simulator/firmware core; verification and deployment documentation.
