# Scenario and Verification

Cover no inventory, stale quote, price/currency/terms change, traveller mismatch, authorization expiry,
provider timeout before and after send, duplicate command, duplicate/out-of-order webhook, partial success,
unknown reconciliation, payment decline, 3DS failure, cancellation penalty and refund pending.

Add actor/workspace/RLS, information-leak, quoted-selection, parallel-trip and zero-mutation cases. Seed
internal IDs and provider errors, then assert no public channel exposes them.

Verification order:

1. Domain and state-machine tests with injected `Clock`.
2. Provider fixture/WireMock contract tests.
3. Stateful fake crash, restart, lease and outbox tests.
4. Conversation actual-entry, latency, privacy and sealed holdout tests.
5. Provider test mode E2E with explicit environment assertions.
6. Production read-only smoke under quota.
7. Optional live canary only with same-turn approval and explicit financial limits.

Sandbox success proves test-environment lifecycle only. Cancellation acceptance does not prove refund
settlement; retain `REFUND_PENDING` until the original payment method is reconciled.

