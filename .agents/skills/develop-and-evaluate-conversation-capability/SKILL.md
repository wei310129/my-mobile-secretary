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
- `docs/exec-plans/active/conversation-line-full-hardening-plan.md` when the task affects the shared
  public boundary, pending state, LINE evidence or all-module conversation gates.

## Execute the development loop

Repeat this loop until every applicable case and gate passes:

1. Identify the user outcome, invariants, variation axes, mutation boundary, privacy boundary, latency class, and directly affected modules.
2. Inspect the dirty worktree and preserve pre-existing user changes. Limit initial reads to directly responsible production files, tests, capability entries, and migrations.
3. Build a realistic scenario matrix. Include real evidence when available, common utterance variants, negative and ambiguous cases, neighboring-intent counterexamples, corrective feedback, repeated unknown turns, quoted-message cases when applicable, idempotent replay, and a holdout set.
4. Reproduce at least one failure with a service-level regression before changing behavior. Reuse an equivalent existing test instead of duplicating it.
5. Design the smallest reusable application/domain solution that covers the stable variation axes. Keep controllers protocol-only and keep deterministic rules out of prompts.
6. Implement only the required path. Add or update the domain handler, capability catalog, injected `Clock`, LifeRecord/tag-graph recorder, Flyway migration, and actor/RLS boundary whenever the change requires them.
7. Run the smallest relevant deterministic tests through the repository-safe Maven entry point. Then run the required capability, API, persistence, RLS, or full-suite tests selected by repository policy.
8. Exercise realistic natural utterances through the actual intent/application entry path. When credentials and the development stack are available, also exercise critical cases through the real API or LINE path; never replace deterministic regression coverage with a live-model result.
9. Measure user-visible latency by scenario class. If a low- or medium-complexity case exceeds its budget, automatically continue with profiling, optimization, correctness regression, and remeasurement.
10. Score every case from a strict user perspective. Inspect the action, reply, mutation count, persisted state, idempotency, quoted context, privacy, and latency. Do not accept a response merely because it contains a success phrase.
11. Run the holdout and neighboring-capability cases only after the initial repair passes. Treat any regression or overfitting as a failure and continue the loop.
12. Finish only when every applicable hard gate passes, every user-experience dimension meets its threshold, and no required validation remains.

For every needs-input path, require a capability-owned stable question code and exactly one natural next
question. Recompute it from unresolved typed slots after each turn, absorb every valid slot supplied in the
same message, and never ask again for a still-valid answered slot. Persist only actor/scope/workflow/question
fencing in the shared pending-question row; persist answers in the capability's typed domain draft, never raw
LINE text or a generic JSON/command slot bag. Resolve explicit quote before active-focus pending question,
then a unique pending workflow, then bounded history; ambiguous parallel drafts ask one target-selection
question with zero mutation. Feedback, meta and unrelated read-only interjections do not consume pending state.

When an unfinished typed operation exists, classify a new executable turn before any domain mutation. A clear
continuation resumes the bound workflow and names it; a clear new operation retains the old workflow, starts the
new one, and names both in the transition notice. If the target is ambiguous, persist one typed context-target
question and perform no provider or committed-resource mutation. If that turn already contains a validated new
operation, stage only capability-owned typed state and retain its UUID pointer; choosing new must continue that
staged operation instead of asking the user to repeat its content. Never put raw text, addresses, or a generic JSON
slot bag in the pending pointer. After the user chooses continuation, restore the interrupted question and render
its complete actionable prompt plus typed next-question metadata, not merely “answer the previous question”. A
read-only or meta interjection may answer without consuming this choice. Complete the retained pointer in the same
transaction as the successful new domain/focus transition.
Explicit trusted quote remains higher priority than pending/focus context.

Treat start, resume, supply-input and close as an upper typed operation lifecycle. Introduce one capability
contributor at a time; do not attach every capability in one change. A user must be able to resume or safely
clear an unfinished operation at any question step. Resolve the unique owner from actor/workspace/scope-bound
typed identity, including an exact workflow lookup for legacy pending labels; fail closed when ownership is
missing or ambiguous. Clearing closes only unfinished domain state, the current pending pointer and conversational
focus. It never deletes committed resources or implies cancellation of provider, booking or payment state. Name
the public operation and state that committed data was preserved. Prove replay exactly-once, RLS isolation and
exact/zero mutation before enabling the next contributor.
Require every enabled contributor to return a typed public topic, current progress and exactly one next question.
Render resume replies in that order—current topic, progress, next step—so a user who says “continue” can reorient
without remembering prior turns. Derive the topic only from actor/workspace/workflow-bound typed state, never raw
recent text, model summarization or ambiguous history. Keep the shared interface available to later capabilities,
but schedule and review each contributor separately so the interface does not silently grant lifecycle authority.

When one operation requires another capability, model a typed parent-child workflow instead of replacing the
parent pending question or inferring a return target from chat history. Persist the parent workflow identity,
expected child result type, completed facts and unresolved facts in capability-owned typed state. A generic
status question must be read-only, preserve the pending question, and render the main topic, active child step,
preserved/completed facts, unresolved facts and one actionable next question. A plain cancel closes only the
active leaf and resumes the parent; an explicit whole-operation cancel or clear-and-restart closes the unfinished
tree while preserving committed resources. Enable and review each parent-child edge separately.
When a child completes, render the exact parent outcome instead of referring to an unnamed "original item".
Treat committed domain data and public operation completion as separate typed states: a materialized row or plan
never proves that the parent conversation is complete. Resume the parent from its typed state and require one
shared completion gate to verify the capability-owned draft, committed resource, required evidence and required
children before any "created", "scheduled" or equivalent terminal claim. When complete, emit the normal full
capability template; otherwise state progress and the one remaining step without a success claim. Optional
post-creation settings may follow completion but must not become completion prerequisites. Never reconstruct
missing parent facts from chat history or model inference.
When a Place child collects location details, accept a bounded place name, address or HTTPS Google Maps place
link. Expand short links through a bounded allowlisted redirect policy, convert the result to typed name and
coordinates, and never persist or log the raw URL in pending state. Non-Google links fail closed with zero mutation.
Bind every child answer to its typed stage before consuming it. Use bounded exact choices rather than substring
matches that can swallow a complete new operation. When input is incompatible with the active stage, preserve
state and name the parent topic, child stage, unchanged-data boundary, accepted input and one way to continue or
retain the current progress and start a new operation. “Retain” means keeping typed unfinished state; it never
implies that a resource was created or that external mutation authority was granted.

Treat a complaint or correction as a repair turn, not a generic acknowledgement. Name the concrete mismatch
without claiming unsupported facts. When grounded prior context is sufficient, rerun the corrected read-only
operation in the same turn. Otherwise ask exactly one typed repair-target question and keep it durable without
mutating business data. Repeated unknown turns must narrow the choice and must not repeat the same question.
Generic secretary overview requests combine the relevant task and schedule view; an explicitly named domain
remains scoped to that domain.

Inventory every public outbound point, including REST, LINE text, image/OCR, literal errors, role denial,
replay, formatter, greeting, focus notice, notification and provider failure. After every decorator and before
each adapter send, route the reply through the same typed final public boundary. A local sanitizer or denylist
is defense in depth, not the primary separation between public replies and internal diagnostics.

Treat public wording as a typed claim contract. A reply may say that information was remembered, work started,
repair completed, data changed, or a terminal notification will arrive only when the result carries matching
durable evidence. A complaint acknowledges the observed mismatch and asks one useful question unless a fresh
read-only repair actually ran. Never say that work is being redone merely because a repair question was opened.
Default to concise secretary language with no decorative emoji; keep internal codes and diagnostic reasons out.

When the product supports assistant self-name or user-address preferences, persist them per actor/workspace in
a typed RLS-protected profile. Support direct set, multi-turn set, read-back, correction, reset, cancellation,
restart and replay. Setting or praising must not alter business identity, authorization or provider identity.
Use at least ten controlled praise and dissatisfaction variants without an immediate repeat. Persist only a
bounded variant cursor, never the raw feedback text, and replay the already selected terminal response.
Ordinary praise is acknowledgement only and must not create or infer a lasting response-style preference.
Persist a response style only from an explicit future-facing instruction and only as a bounded typed style
that the public boundary actually enforces; prove read-back, reset, restart, replay and zero business mutation.

System-owned reference catalogs are checked before external search when the product owns a suitable catalog.
Resolve catalog confidence from typed deterministic evidence, never from model self-confidence. Apply an
exact catalog match directly. For one logical large place with several physical points, retain typed catalog
candidate keys without asking early; select a point only when materializing the final itinerary from verified
route and schedule constraints, then disclose the selected point, reason, system source, and that no custom
place was saved. Ask one region question only when distinct entities in different regions remain ambiguous.
An accepted catalog point may be copied into a typed plan snapshot but must not create a user custom place.
Do not append a saved/not-saved or custom-place disclaimer for a precise unique system point the user already
specified; that disclosure belongs to a large-place final point selection where the system made a visible choice.
Missing coordinates mean insufficient route evidence, not permission to guess. External search or directory
results remain read-only supplemental evidence and neither lookup path gains independent mutation authority.

Treat a recognized place as typed entity evidence, never as proof that the user asked for an address. Classify
the requested operation separately: direct location/catalog questions may terminate in the place capability;
service-time, route, departure, arrival and itinerary requests must continue to their owning capability. For
an explicit complete activity mutation, create the validated activity first and then ask one optional transport
question. Persist that offer in the typed Calendar draft. Transport is an internal plan node, not a second
visible activity. Record activity control as LOCKED, WINDOWED or FLEXIBLE; external control is LOCKED, unknown
control asks one question, and every later activity-time change requires fresh user confirmation.

For an explicit origin-destination itinerary with usable time and endpoints, treat planning and arranging
synonyms alike and invoke the configured route provider on the first turn. Do not materialize a route without
typed provider evidence. On provider unavailability, persist only bounded status, mode, time role, revision and
fencing, ask one retain/discard question, and retry from that typed draft rather than raw chat text. Resolve an
explicit quote first, then an active route focus, then a unique transport workflow; never let a short answer
consume another parallel draft. Before materialization, check direct overlap plus previous and next connection
risk. Present the verified route preview before a concise risk recommendation and one confirmation question.

Resolve and persist route journey kind from validated placement plus explicit source semantics; never branch on
raw model `endAt`. A single departure time with explicit endpoints is provider-first standalone travel unless
typed activity semantics say otherwise. Treat the standalone transport interval as the provider-derived travel
interval: departure/arrival role plus provider duration determines both ends. Never ask for activity duration or
an activity end time merely to plan that route; only a separately typed activity may require those fields. Ignore
unlinked adjacent nodes more than six hours away before route
assessment and fingerprinting; explicit same-journey, flight or transfer linkage may cross that bound. When a
nearby adjacent location is missing, materialize the verified route first, identify only the affected side, and
ask one location question. Direct overlap remains a validation failure.

Render a verified standalone route as one scan-friendly public block: outcome, title, date, operational range,
departure/arrival axis, duration/source, concise guidance, conflicts, then one question when needed. List every
affected existing plan by public title and time; never substitute node keys or identifiers. General before/after
buffers, driving parking time, ride-hail wait time and any early-call reminder are actor/workspace-owned typed
preferences with injected-Clock calculations. External activities do not inherit these defaults. Direct overlap
causes zero Calendar materialization until explicit force; non-overlap connection risk materializes exactly one
verified route before the warning. Replay must preserve both wording and exact mutation count.

When answering the optional route departure-reminder question, an explicit lead such as “five minutes before”
overrides adaptive defaults in the same turn and creates exactly one typed relative reminder. Do not let a generic
acceptance word swallow the supplied lead. Render the actual scheduled times immediately. A later “which two?” or
“what reminders did you set?” is a read-only reference to the actor/workspace/workflow-bound route draft and its
active reminder rules; it must not require the route title again, infer from raw chat history, or mutate reminders.

Publish Google Maps point links only for endpoints whose durable typed source proves the user explicitly supplied
that point in the current trusted turn or quote. Never publish HOME, inferred adjacency/context, legacy-unspecified
or otherwise private endpoint coordinates. Prefer a trusted catalog short link when present; an official
coordinate search URL is an acceptable fallback. Endpoint-source semantics must survive restart and replay.
When both endpoints pass that same public-source gate, append one Google Maps directions URL using the typed
transport mode. Suppress the full-route URL when either endpoint is private or invalid. Treat Maps URLs as
read-only navigation conveniences, never Google Routes provider evidence, and preserve them exactly on replay.

For an omitted route origin, apply typed Java precedence: explicit or trusted-quote origin, unique confirmed
same-journey location, known previous location within six hours, actor-owned active HOME preference, then one
origin question. HOME references a confirmed actor/workspace-owned Place and is RLS protected; pending state
stores only typed workflow/fencing, never an address, raw message or generic slot bag. Before using HOME for an
early departure, fail closed on typed overnight coverage or a confirmed recent location at least 30 km away;
do not infer travel from a title, person, hotel or brand, and never guess a country. Explain automatic HOME use
briefly and allow correction. Every continuation that can mutate HOME, draft or Calendar state must cross the
inbound mutation boundary before its first mutation so duplicate delivery replays the terminal result.

Keep route-provider selection policy-driven and evidence-based. Do not switch the default between TDX and
Google from assumption or one synthetic test; compare configured providers on representative actual routes,
success, route quality and user-visible latency. Disclose the selected public source and required attribution,
never raw provider details. A confirmation provider is supplemental evidence, not permission to replace the
selected route, add paid first/last-mile travel, or gain mutation authority.

Distinguish place knowledge confirmation, location, point listing, candidate-keyword filtering and itinerary
selection as typed operations. A knowledge question answers the known logical place first and may briefly offer
point listing or keyword filtering; it must not introduce itinerary assumptions, mutation disclaimers or early
point selection. Keep same-place multipoint candidates in an actor/scope-owned typed browse context so a short
follow-up can list or filter them after restart. Store only logical/catalog keys and fencing, never raw chat text.
An explicit new place or quote overrides that browse. A context-free point request asks one place question.

Do not lower a threshold, delete a difficult case, weaken an assertion, or relabel a failure as acceptable to finish the loop. If an external dependency, missing authority, or unresolved product decision prevents completion, report the exact blocker and leave the result incomplete.

## Protect user-facing responses

Keep the public response contract separate from internal diagnostics. Never expose database identifiers, UUIDs, workspace or actor identifiers, message identifiers, intent enums, handler or class names, package names, SQL, schema details, stack traces, exception classes, file paths, prompts, structured-output schemas, router reasons, tokens, or raw provider errors.

Render every public question with two or more actions as numbered `1.`, `2.`, `3.` blocks with a blank line
between options. Each option must name the action and explain its persistence or mutation effect. Source the
action code, public label, effect, and accepted answers from one typed choice catalog; conversation methods only
assemble facts with the shared renderer. Carry the same typed choice question through lifecycle resume and replay.
Never duplicate inline `A, B, or C?` strings across handlers. Roll this rule out one reviewed capability at a time.

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

A platform-native loading animation is a best-effort UI affordance, not a public reply or durable progress
message. Scope it to the platform-supported conversation type, never consume a reply token or include reply
content, and never count it as satisfying a durable progress or terminal-delivery gate.

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

