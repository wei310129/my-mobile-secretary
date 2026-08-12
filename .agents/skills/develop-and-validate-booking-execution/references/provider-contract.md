# Provider Contract

Represent SEARCH, QUOTE, HOLD, BOOK, PAY, STATUS, CHANGE, CANCEL, REFUND, WEBHOOK and DEEPLINK separately.
Unsupported or commercially unavailable capabilities remain false even when a provider website exposes them.

Every offer records provider, environment, retrieved time, expiry, price/currency, travellers, inventory
identity, conditions and add-ons. Expired or materially changed quotes invalidate authorization unless the
selected policy explicitly covers the exact change.

Use one application idempotency key per intended operation. A timeout after sending a mutation is
`NEEDS_RECONCILIATION`, not failure. Query provider status, webhook inbox or list APIs before retry.
Duplicate and out-of-order webhooks must not regress state.

Map provider payloads inside integration adapters. Domain and public replies never expose raw payloads,
request IDs, errors or secrets. Sandbox tests assert the environment and must never accept live credentials.

Provider errors and reconciliation outcomes are internal diagnostics until mapped to a typed public reply.
The mapped reply still passes the shared final conversation boundary after all price, policy, focus and
progress decoration. A provider SDK callback, webhook, hosted-checkout return or browser result is never an
authorized bypass.

A provider complaint, correction or retry request does not authorize a mutation. Map available typed state to
a same-turn read-only repair; otherwise ask one typed target question. Public replies state whether inventory,
money or booking state changed and never turn a provider lookup failure into an implicit hold, booking,
payment, change or cancellation.

Treat “retrying”, “rebooked”, “payment completed”, “refund started” and “will notify” as typed public claims.
Require matching durable provider/application evidence after reconciliation and outbox commit. Without it,
acknowledge the issue and clarify one target; do not use future tense that implies background execution.

