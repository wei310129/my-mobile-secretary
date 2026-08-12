# Scenario and Verification

Cover no inventory, stale quote, price/currency/terms change, traveller mismatch, authorization expiry,
provider timeout before and after send, duplicate command, duplicate/out-of-order webhook, partial success,
unknown reconciliation, payment decline, 3DS failure, cancellation penalty and refund pending.

Add actor/workspace/RLS, information-leak, quoted-selection, parallel-trip and zero-mutation cases. Seed
internal IDs and provider errors, then assert no public channel exposes them.

For missing traveller, offer, authorization or payment inputs, assert exactly one typed next question per
turn, absorption of all valid values supplied together, no repeat of valid answered slots, durable typed
pending state, and zero provider mutation before the relevant authorization gate. Exercise provider error,
replay and hosted-checkout return replies through the same final public boundary as ordinary LINE/REST text.

Add complaint/correction cases with sufficient typed context for a same-turn read-only repair and ambiguous
cases that ask one durable repair-target question. Assert no provider call with mutation authority, no consumed
unrelated pending state, explicit changed/unchanged status, no repeated question, and exactly one terminal
public reply after all provider and progress decoration.

Seed unsupported “retrying”, “booked”, “paid”, “refund started” and “will notify” wording and prove the shared
final boundary rejects it without matching typed evidence. Also prove a genuinely committed provider/outbox
state preserves truthful wording, uses the configured secretary address naturally, and adds no decorative
emoji by default.

For guidance-only restaurant intake, cover restaurant → dining time → party size as separate typed questions,
all-at-once absorption, restart, replay and actor/workspace RLS. Assert the completed local draft states that no
reservation or payment was sent and causes zero provider, order, payment, cancellation or browser mutation.

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

