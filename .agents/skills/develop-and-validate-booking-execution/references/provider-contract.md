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

