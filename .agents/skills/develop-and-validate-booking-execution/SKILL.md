---
name: develop-and-validate-booking-execution
description: Develop and validate availability, quote, hold, booking, payment, change, cancellation, refund, provider webhook, post-booking monitoring, hosted checkout, or Playwright-assisted purchase flows in my-mobile-secretary. Use for any external operation that can reserve inventory, create an order, charge money, alter or cancel a booking, or claim that provider availability or transaction state is known.
---

# Develop and Validate Booking Execution

Treat every provider response as external evidence, not permission to mutate. Keep authorization, freshness,
price, time, actor, state and idempotency decisions deterministic in Java.

## Load the required guidance

Read repository guidance, current decisions and the active Booking execution plan. Then read:

- [provider-contract.md](references/provider-contract.md) for provider capability, quote and reconciliation work.
- [authorization-and-payment-safety.md](references/authorization-and-payment-safety.md) before any hold,
  booking, payment, change, cancellation, refund, traveller-vault or browser-session work.
- [scenario-and-verification.md](references/scenario-and-verification.md) before tests or completion claims.

When the change affects intent, LINE, quoted context, progress or public replies, also use
`develop-and-evaluate-conversation-capability`.

## Execute the workflow

1. Inspect the dirty worktree and active Calendar／Travel handoffs. Preserve other-session changes.
2. Acquire repository coordinator claims before mutation. Fail closed when owner or generation is uncertain.
3. Identify provider capabilities and environment as `FAKE`, `SANDBOX` or `LIVE`.
4. Obtain a fresh quote and typed purchase authorization before every external mutation.
5. Use one idempotency scope per intended operation. Reconcile unknown outcomes before retry.
6. Stop after partial success, preserve successes and ask before compensation.
7. Keep card data, credentials, OTP, 3DS, CAPTCHA, passport data and browser state outside prompts,
   logs, LifeRecord, handoff and version control.
8. Validate deterministic behavior first, then provider contract, sandbox and conversation gates.
9. Report mutation counts, environment, masked evidence, untested paths and remaining risk.

All provider, hosted-checkout and browser-assisted public replies use the conversation capability's typed
final boundary after provider/error decoration. Never expose raw provider reason, payload, request/order ID,
internal booking/payment state, exception detail or reconciliation key. Needs-input replies ask exactly one
typed next question, preserve valid answered traveller/offer/payment slots, and store answers only in the
booking capability's typed draft. Provider success never relaxes these reply or clarification contracts.

Do not promise that a provider operation was remembered, started, retried, changed, canceled, paid, refunded
or will produce a notification unless the public result carries the corresponding committed typed evidence.
An acknowledgement or repair question is not proof of a provider retry. Use concise secretary language and
let the shared final tone boundary remove decorative channel-specific wording before adapter output.

Treat booking complaints and corrections as conversation repair turns. If typed booking state is sufficient,
return the corrected read-only status or quote explanation in the same turn without a provider mutation. If
the target is ambiguous, ask exactly one typed repair-target question, preserve unrelated pending work, and
never claim a provider error or transaction outcome without evidence.

A public “help me book” intake without provider authority is guidance only. Store restaurant/time/party-size
answers in an actor/workspace-owned typed draft, ask one next question, preserve valid supplied values, and say
plainly that no booking or payment was sent. Completing that local draft is not provider availability, a hold,
a reservation, payment, or authority to open a browser transaction.

## Hard stops

Stop and request authority for live booking, payment, cancellation, refund, paid services, real traveller
data, new provider contracts, destructive cleanup or changed product semantics.

Never bypass CAPTCHA, OTP, 3DS or provider safeguards. Provider acceptance is not proof of card settlement.

## Resource policy

The host is resource constrained. Default to one mutating agent, one Maven writer, one Docker gate and one
browser context. Do not run full Maven regression, sandbox E2E and Playwright concurrently. Mutating
subagents require an independent worktree, frozen interface and non-overlapping source claim.

