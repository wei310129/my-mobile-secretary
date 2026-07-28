---
name: develop-and-evaluate-conversation-capability
description: Develop, fix, optimize, and rigorously validate conversation capabilities in the my-mobile-secretary project. Use for natural-language intent handling, LINE or chat replies, quoted-message context, user-visible response behavior, conversation regressions, performance problems, or any change that can affect how a user phrase is understood, executed, or answered.
---

# Develop and Evaluate Conversation Capabilities

Follow the repository `AGENTS.md` and current product decisions before this workflow. Treat LLM output only as structured interpretation. Keep scheduling, time ranges, geography, state transitions, authorization, and mutations deterministic in Java application/domain services.

## Load the required guidance

Read these references before changing code:

- Read [references/scenario-design.md](references/scenario-design.md) to build realistic utterance, quoted-context, negative, and holdout cases.
- Read [references/acceptance-rubric.md](references/acceptance-rubric.md) to apply correctness, information-safety, latency, and user-experience gates.
- Read [references/generalization-checklist.md](references/generalization-checklist.md) before selecting the design and again before completion.

When working in `my-mobile-secretary`, also read only the directly relevant parts of:

- `docs/architecture.md` for product and architecture boundaries.
- `docs/development-plan.md` for approved behavior and decision checkpoints.
- `docs/test-strategy.md` for change-relevant test selection.
- `docs/exec-plans/active/local/conversation-improvement-batches-2026-07-20.md` only when the task belongs to one of its batches.

## Execute the development loop

Repeat this loop until every applicable case and gate passes:

1. Identify the user outcome, invariants, variation axes, mutation boundary, privacy boundary, latency class, and directly affected modules.
2. Inspect the dirty worktree and preserve pre-existing user changes. Limit initial reads to directly responsible production files, tests, capability entries, and migrations.
3. Build a realistic scenario matrix. Include real evidence when available, common utterance variants, negative and ambiguous cases, neighboring-intent counterexamples, quoted-message cases when applicable, idempotent replay, and a holdout set.
4. Reproduce at least one failure with a service-level regression before changing behavior. Reuse an equivalent existing test instead of duplicating it.
5. Design the smallest reusable application/domain solution that covers the stable variation axes. Keep controllers protocol-only and keep deterministic rules out of prompts.
6. Implement only the required path. Add or update the domain handler, capability catalog, injected `Clock`, LifeRecord/tag-graph recorder, Flyway migration, and actor/RLS boundary whenever the change requires them.
7. Run the smallest relevant deterministic tests through the repository-safe Maven entry point. Then run the required capability, API, persistence, RLS, or full-suite tests selected by repository policy.
8. Exercise realistic natural utterances through the actual intent/application entry path. When credentials and the development stack are available, also exercise critical cases through the real API or LINE path; never replace deterministic regression coverage with a live-model result.
9. Measure user-visible latency by scenario class. If a low- or medium-complexity case exceeds its budget, automatically continue with profiling, optimization, correctness regression, and remeasurement.
10. Score every case from a strict user perspective. Inspect the action, reply, mutation count, persisted state, idempotency, quoted context, privacy, and latency. Do not accept a response merely because it contains a success phrase.
11. Run the holdout and neighboring-capability cases only after the initial repair passes. Treat any regression or overfitting as a failure and continue the loop.
12. Finish only when every applicable hard gate passes, every user-experience dimension meets its threshold, and no required validation remains.

Do not lower a threshold, delete a difficult case, weaken an assertion, or relabel a failure as acceptable to finish the loop. If an external dependency, missing authority, or unresolved product decision prevents completion, report the exact blocker and leave the result incomplete.

## Protect user-facing responses

Keep the public response contract separate from internal diagnostics. Never expose database identifiers, UUIDs, workspace or actor identifiers, message identifiers, intent enums, handler or class names, package names, SQL, schema details, stack traces, exception classes, file paths, prompts, structured-output schemas, router reasons, tokens, or raw provider errors.

Return a user-language explanation of what happened, whether data changed, and what the user can do next. Preserve detailed diagnostics only in authorized logs, traces, or issues. Use a public support reference only when it is explicitly designed, non-reversible, and product-approved; never reuse an internal identifier.

Add failure-path tests that seed internal identifiers and diagnostic strings, then assert that none appear in any user-visible channel.

## Enforce latency and progress feedback

Classify scenarios before acceptance testing:

- Low complexity: deterministic confirmation, cancellation, simple creation, or direct query. Target P95 at or below 1.5 seconds.
- Medium complexity: one model interpretation, ordinary lookup, or one rule calculation. Target P95 at or below 4 seconds; send progress feedback when work exceeds 2 seconds.
- High complexity: OCR, multiple providers, cross-domain planning, or large data work. Send progress feedback within 1–2 seconds and use a task-specific completion target, normally 15–30 seconds.
- Long-running work: accept asynchronously and send progress feedback within 1–2 seconds, followed by exactly one terminal success or failure result.

Use stricter repository or product budgets when present. Do not silently relax an approved budget.

If progress feedback is required, implement real application state and reliable final delivery; do not emit a cosmetic “processing” message while continuing an untracked request. Ensure replay safety, exactly-once user-visible progression, timeout handling with injected `Clock`, outbox-based final delivery when appropriate, and correct association when the user sends another message while work is pending.

For low- or medium-complexity budget failures, measure stage timing internally, locate the bottleneck, and optimize the correct layer. Check unnecessary LLM calls, N+1 queries, repeated reads, blocking I/O, excessive context, serialization, and incorrect routing. Never trade away validation, authorization, correctness, or privacy for speed.

## Validate quoted-message context

Prefer explicit quoted-message context over an active pending context, and prefer an unambiguous active context over bounded recent history. Scope every lookup to the same workspace and actor. If the referenced content is absent, expired, unauthorized, or ambiguous, ask the user to identify or resend it; never guess and never reveal its internal message identifier.

Test short replies whose meaning depends on the referenced message, including selections, dates, rejection, correction, follow-up locations, media, and parallel drafts. Verify that the same short phrase can correctly produce different outcomes with different quoted contexts, and that no quote produces a safe clarification when context is insufficient.

## Report completion

Report in Traditional Chinese and include:

- The realistic cases and holdout categories exercised.
- Files and user-visible behavior changed, without showing a diff unless requested.
- Exact test commands and pass counts.
- Mutation, idempotency, actor/RLS, information-leak, quoted-context, and latency evidence.
- User-experience scores for every applicable dimension.
- Any untested path or remaining risk.

Never claim full success from focused tests alone. Mark the work incomplete when any scenario, hard gate, latency target, or required regression remains failing.

