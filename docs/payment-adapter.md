# Payment adapter contract

The core renewal service is shared by verified webhook, reconciliation and dev-only mock scenarios. It acquires card → order locks, writes receipt + immutable renewal + card expiry + PAID order atomically. Provider HTTP runs outside these transactions. An order keeps its initial amount/duration snapshot. PAID is terminal and means payment plus renewal, never only money observed.

## payOS implementation

`PayosGateway` uses the documented merchant REST v2 endpoints through Java 21 HttpClient; no unverified SDK method names. Sources checked during implementation:

- https://payos.vn/docs/api/
- https://payos.vn/docs/du-lieu-tra-ve/webhook/
- https://payos.vn/docs/tich-hop-webhook/kiem-tra-du-lieu-voi-signature/
- https://github.com/payOSHQ/payos-lib-java (SDK comparison; SDK is not a dependency).

Create request signature: sorted amount/cancelUrl/description/orderCode/returnUrl. Responses and webhook `data` use sorted keys joined as key=value with HMAC-SHA256; signature comparison is constant time. The published provider example is covered by `PayosSignatureTest`. Payload and signature keys are never logged. The receipt stores only sanitized outcome/payment ID plus business fields.

Mapping: providerOrderCode ← orderCode; paymentId ← paymentLinkId (query id); reference ← reference; amount ← amount; currency ← currency; paidAt ← transactionDateTime; success ← signed data.code == 00. Top-level query parameters and browser return URLs cannot grant a renewal. Unknown order codes are persisted as UNMATCHED, so the provider sample used to configure a webhook cannot renew a random card. Sequence starts at 100000, outside the documented sample order 123.

## Time and reconciliation integration gate

ISO-8601 timestamps with explicit offset/Z are interpreted directly. A naive `yyyy-MM-dd HH:mm:ss` requires `PAYOS_TIMEZONE` explicitly configured after confirming the payment channel. Missing or invalid time produces REVIEW, not a guessed payment time.

The documented query response examples are incomplete about per-transaction currency/transaction structure. The adapter requires a transactions array, trusted reference, exact amount, explicit currency from transaction or query data, and valid time. If the actual account does not return these fields, reconciliation cannot automatically apply a renewal; retain REVIEW and reconcile the SRS/provider contract with the operator. Do not infer currency, sum partial transfers or accept screenshots. Inspect sanitized provider fixtures on the configured account before marking AT-27/33 verified for live mode.

CREATING failures with uncertain provider outcome preserve order code. A delayed create response never overwrites PAID/REVIEW. Provider calls time out after 10 seconds; retry happens through stored orders and the scheduled reconciliation path. A query with no transactions and confirmed EXPIRED/CANCELLED may resolve an expired order; an empty, ambiguous PENDING response does not clear REVIEW.

## Configuration and real acceptance

1. Configure eligible merchant account, receiving channel, `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`, verified `PAYOS_TIMEZONE`, agreed real prices, `PUBLIC_URL`.
2. Set `PAYMENT_MODE=payos`. Use `SPRING_PROFILES_ACTIVE=production` for real deployment. Missing keys fail startup. No mock endpoint is registered in payOS mode.
3. Run Compose and configure an HTTPS tunnel targeting the restricted loopback listener 8088. Register the exact webhook endpoint using the merchant dashboard or documented confirmation API. No automatic external webhook registration is performed by application startup.
4. Test sample callback signature, a real exact-amount transfer, repeated delivery, query recovery, and correct timestamp interpretation. Confirm money, one receipt, one renewal, card expiry and order PAID with redacted evidence.
5. Record AT-33 evidence and relevant live reconciliation results in test-evidence.md. No real transactions have been initiated by this implementation session.
