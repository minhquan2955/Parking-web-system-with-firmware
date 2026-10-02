# Data model — SRS 2.3

```mermaid
erDiagram
    users ||--o{ cards : owns
    users ||--o{ renewal_orders : purchases
    users ||--o{ parking_sessions : owner_snapshot
    cards ||--o{ renewal_orders : renews
    renewal_packages ||--o{ renewal_orders : price_duration_snapshot
    renewal_orders ||--o| card_renewals : applied_result
    cards ||--o{ card_renewals : expiry_history
    renewal_orders o|--o{ payment_receipts : matched_payment
    parking_lots ||--o{ parking_sessions : logical_sessions
    parking_lots ||--|{ parking_slots : physical_occupancy
    cards ||--o{ parking_sessions : scanned_card
    devices ||--o{ device_boots : boot_history
    devices ||--o{ access_events : decisions
    devices ||--|{ parking_slots : reports
    parking_sessions o|--o{ access_events : related_decision
    audit_logs {
       uuid id PK
       uuid actor_id
       string actor_type
       string action
       string entity_type
       uuid entity_id
       text before_json
       text after_json
       text reason
    }
```

No slot_id on sessions. Effective slot state, device online and card expired are derived from timestamps. Partial unique indexes enforce one enabled card per owner, one OPEN session per owner/card, one CREATING/PENDING order per card. REVIEW deliberately is outside the live-order unique index. Renewal's composite FK ensures its card matches the order, and unique order_id limits it to one result. Cross-table PAID/renewal invariants are enforced by the transaction service and integration tests.
