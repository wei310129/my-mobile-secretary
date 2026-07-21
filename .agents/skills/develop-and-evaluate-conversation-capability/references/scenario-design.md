# Scenario Design

## Build the matrix

Create 12–20 meaningful cases for a normal conversation capability and increase the count with risk. Do not add filler cases. Cover every applicable category:

- Real user evidence or an existing intent issue.
- Common complete phrasing.
- Short colloquial phrasing and omitted subjects.
- Synonyms, reordered words, punctuation differences, and realistic typos.
- Different people, dates, places, amounts, and recurrence values.
- Missing required information and ambiguous input.
- Read-only questions, feedback, and failure cases that must cause zero mutation.
- Neighboring intents whose wording overlaps but whose action must differ.
- Multi-turn follow-up, correction, confirmation, rejection, and cancellation.
- Duplicate delivery and replay for idempotency.
- Actor, workspace, time, month-end, year-boundary, and authorization boundaries.

Split cases into:

1. Repair cases used while implementing.
2. Permanent regression cases retained in the repository.
3. Holdout cases withheld until the repair and known regression cases pass.

Do not show holdout expected answers to an evaluator before evaluation. A change that passes repair cases but fails holdout cases is overfit and must return to design.

## Exercise realistic entry paths

Send natural utterances through the actual intent/application boundary. Assert structured action and deterministic business results, not only response fragments. Inspect mutation counts and final state directly.

Use controlled model/provider substitutes for repeatable automated regression. Add a live configured-model or LINE/API acceptance pass for critical cases when the environment is available. Repeat stochastic critical cases enough to expose unstable classification, while keeping deterministic tests as the release gate.

## Cover quoted-message conversations

Include these cases when the channel supports replies or quotes:

- Reply to a numbered list with “第一個”, “第二個”, or an equivalent ordinal.
- Reply to a draft with a short date such as “7/9”.
- Reject a quoted merge or suggestion with “不要併入” or “不是這個”.
- Ask a short follow-up such as “健身工廠呢” against different quoted messages.
- Quote text, an image, an OCR summary, and an unsupported or expired artifact.
- Maintain two parallel pending drafts and quote each one in turn.
- Quote a missing, deleted, expired, unauthorized, or cross-actor message.
- Replay the same quoted webhook and prove exactly one mutation.
- Use the same short reply with different quotes and prove context-specific results.
- Send the short reply without a quote and prove safe clarification when recent context is not unique.

Resolve context in this order: explicit quote, unambiguous actor-scoped pending context, bounded actor-scoped recent context, clarification. Never expose a platform message ID in the reply.

## Add adversarial user checks

Review each reply as a skeptical everyday user:

- Did it answer the actual question first?
- Did it claim completion before completion?
- Did it silently create, change, cancel, or delete anything?
- Did it repeat a question already answered in context?
- Did it choose a convenient recent context instead of the explicit quote?
- Did it reveal an implementation detail or identifier?
- Would a reasonable user know whether work succeeded, failed, needs input, or is still running?
