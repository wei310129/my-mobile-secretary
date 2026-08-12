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
- Corrective feedback with enough prior context for a same-turn read-only repair, and without enough context
  so exactly one durable repair-target question is required.
- Praise and dissatisfaction variants: at least ten controlled responses per polarity, no immediate repeat,
  restart continuity, replay of the original terminal wording, and no invented memory or repair promise.
- Contrast ordinary praise such as “that was good” with an explicit future-facing style instruction. Prove
  praise leaves the typed style absent, while the explicit instruction commits only a supported bounded style,
  is readable/resettable after restart, survives replay and does not consume an unrelated business question.
- Assistant self-name and user-address preferences: one-turn set, multi-turn target selection, all values in
  one message, correction, read-back, same-value replay, reset, cancel, restart, actor/workspace isolation and
  an unrelated business pending question that is suspended then restored.
- Claim/evidence counterexamples: seed “remembered”, “started processing”, “reprocessed”, “will notify” and
  “changed data” wording without its typed evidence and require the final boundary to fail closed.
- Generic secretary overview wording versus explicit task-only and schedule-only wording.
- Consecutive unknown interpretations that narrow the question without repeating it.
- System-owned place/reference exact hit, category question, same logical large-place multipoint result, and
  cross-region entity ambiguity. Exact hits apply directly; logical multipoint results defer point selection
  until final itinerary materialization; only cross-region ambiguity asks one region question. Then cover
  external lookup success, not-found, unavailable, and ambiguity only on catalog miss. Disclose source,
  selected point and reason when applicable, and saved/not-saved status; cause zero unintended mutation.
  A precise unique point named by the user must not receive a custom-place disclaimer; keep selection/source/
  saved-state disclosure for a large-place multipoint choice made by the system.
- Place-entity versus requested-operation neighbors: address, opening/service time, travel time, departure,
  origin-destination itinerary and explicit activity creation. Prove that place recognition cannot swallow a
  route or temporal-property request.
- Logical-place conversation operations: concise “do you know this place” acknowledgement, direct point list,
  short cross-turn point list, unique/multiple/no-match keyword filters, explicit new-place override, expired
  browse and context-free point question. Assert typed actor/scope browse keys, zero pending question for an
  optional offer, restart/replay/quote behavior and no itinerary language before itinerary materialization.
- For activity plus travel, cover direct validated activity creation followed by one optional transport offer,
  decline, acceptance, transport mode, provider evidence failure, one internal transport node, and no second
  visible activity. Cover LOCKED external activities, WINDOWED/FLEXIBLE activities, unknown control, and fresh
  confirmation before every later activity-time mutation.
- For explicit origin-destination travel, cover planning/arranging synonyms, depart-at and arrive-by, route
  provider success/unavailable/fallback, retain/discard, restart and typed retry. Assert zero calendar mutation
  before provider evidence, exactly one materialization after success, active-focus and quoted-draft precedence,
  and no slot re-ask. Cover direct overlap plus previous/next connection risk; show a verified route preview
  before one risk question without internal node labels. Verify public source/attribution, no silent paid
  first/last-mile segment, fixed-cardinality latency metrics, and representative live provider comparison before
  changing the default provider strategy.
- Cross raw structured output with resolved semantics: raw `endAt` present but resolved TIMED_POINT, activity
  markers with one explicit time, restart and short-answer continuation. Assert persisted STANDALONE_TRIP versus
  ACTIVITY_WITH_TRANSPORT and prove no later branch reinterprets raw model fields or placement alone.
- Cover unlinked previous/next nodes just inside and outside six hours, a 14-hour missing-location node, and an
  explicit same-journey linkage beyond six hours. Far unlinked nodes must not enter assessment or fingerprints.
  A nearby missing side yields a verified materialized route followed by one side-specific location question;
  direct overlap remains zero materialization.
- For omitted origin, cover explicit/quote, confirmed same-journey, previous known location within six hours,
  typed HOME, missing HOME, and possible-away clarification. Include HOME set/change/disable, restart, replay,
  quote, parallel drafts, actor/workspace isolation, FORCE RLS, early-morning 30 km boundary, 10:00 boundary,
  cross-night coverage, recent confirmed return home, unknown return, overseas/island evidence and no travel
  evidence. Assert no raw address or chat text in pending state and no provider/calendar mutation before the
  single origin question is answered.
- Neighboring intents whose wording overlaps but whose action must differ.
- Multi-turn follow-up, correction, confirmation, rejection, and cancellation.
- Operation lifecycle short answers such as “continue” and “new”, plus clear-and-restart from each blocking
  question step. Seed a legacy/missing label with an exact typed workflow owner, a missing owner, two claiming
  contributors, a materialized resource and a pending draft. Assert that only the pending draft/pointer/focus
  close, committed resources remain, replay is exactly once, and another actor/workspace cannot resolve or close it.
  Include a validated complete new request that triggers the target choice, then choose new and prove the request
  is not asked for again. Restart between staging and choice; retain only typed capability state plus a UUID pointer,
  make zero provider/committed-resource mutation before the choice, and reject missing or mismatched staged owners.
  For every resume variant, assert the public reply orders the typed current topic before current progress and the
  single next question. A user must not need prior chat memory to know what is being resumed. Reject raw-text or
  model-derived topics and fail closed when no unique typed public topic exists.
- For a Route-to-Place child, cover place-name, address, full Google Maps URL, shortened `maps.app.goo.gl` URL,
  an over-300-character valid URL, disallowed host, hostile redirect, provider unavailability, restart and replay.
  Assert zero Place/Calendar mutation before confirmation and no raw URL in pending rows, replies or diagnostics.
- At OFFER and DETAILS, send a complete new schedule/activity command, a status query and an explicit objection.
  Prove exact child choices do not use broad substring matching, incompatible input is not persisted as a location,
  and the reply names the parent, stage, unchanged state, accepted input and “retain then start new” path. Stage a
  complete typed new request and consume it after the choice without asking the user to repeat it.
- Seed a parent with committed data but an unfinished required child or missing provider evidence. Require the
  shared completion gate to reject every terminal success claim. Then complete the same committed resource in
  place, close the required child, and require the normal full parent template without duplicate mutation.
- Complete a child after its parent remains unfinished, after its parent can complete in the same turn, and after
  a historical or concurrent parent is already committed. Require the normal full parent result template whenever
  typed facts suffice; otherwise require the parent public title, date and time plus the exact child-only change.
  Reject unnamed “original item” wording, invented route facts and duplicate parent mutation.
- Duplicate delivery and replay for idempotency.
- Actor, workspace, time, month-end, year-boundary, and authorization boundaries.
- One missing slot, several missing slots, all slots supplied together, out-of-order answers, corrections,
  expired state, restart, and a previously answered slot that must not be asked again.
- Every outbound family used by the capability: REST, LINE text, image/OCR, replay, denial, failure,
  formatter/decorator, focus notice, notification, and provider reply.
- Platform-native loading behavior on the supported one-to-one source, plus group/room exclusion, timeout,
  reply-token isolation, replay, and exactly one terminal reply.

Split cases into:

1. Repair cases used while implementing.
2. Permanent regression cases retained in the repository.
3. Holdout cases withheld until the repair and known regression cases pass.

Do not show holdout expected answers to an evaluator before evaluation. A change that passes repair cases but fails holdout cases is overfit and must return to design.

## Exercise realistic entry paths

Send natural utterances through the actual intent/application boundary. Assert structured action and deterministic business results, not only response fragments. Inspect mutation counts and final state directly.

Use controlled model/provider substitutes for repeatable automated regression. Add a live configured-model or LINE/API acceptance pass for critical cases when the environment is available. Repeat stochastic critical cases enough to expose unstable classification, while keeping deterministic tests as the release gate.

For deterministic secretary shortcuts, measure at least 20 warm actual-entry samples and assert the selected
capability, absent model/token usage, exact zero business mutation, P95, and slowest sample. Loading-animation
unit tests do not replace actual LINE entry-path evidence.

For system-owned places, include custom-place precedence, an exact station or government/airport/port/theme-
park hit, a large hub with multiple physical points, two same-named entities in different regions, a correction
turn, Calendar draft reuse, restart, replay, and missing-coordinate route evidence. Assert that accepted system
data can populate the typed Calendar snapshot without creating a custom place.

## Cover quoted-message conversations

## Cover route presentation and operation context

For route-capability work, include all applicable cases:

- Explicit endpoints with a single departure time, including planning/arranging synonyms and a structured
  `endAt` that disagrees with the resolved point placement.
- Route-shaped interpreter failure and misclassification cases must ask only for missing route time or time role;
  assert that standalone transport never asks for activity duration/end time and still has zero mutation.
- Safe route, direct overlap, previous/next connection risk, nearby missing location, an unlinked missing
  location beyond six hours, and typed same-journey linkage beyond six hours.
- Two or more affected existing plans. Require each public plan title and time; reject node keys and IDs.
- Every connection-risk reason names its public plan and quantifies the available gap, required travel and
  shortage. Direct overlap, previous-only risk and any next-side risk use distinct typed questions so resume does
  not restore a directionally impossible suggestion. A bare “use another time?” is not an actionable prompt.
- No-risk standalone routes skip general-buffer questions and persist the provider departure/arrival interval
  unchanged. Add a separate qualifying adjacency case where the new/adjusted item is too close to an existing
  item and at least one side moves location; only that connection may ask for a typed buffer. Cover driving
  parking time, ride-hail wait time, start-reminder accept/decline, relative-reminder adjustment, fixed-reminder
  review, restart, quote, parallel drafts and duplicate delivery. If the user first chooses safe rescheduling or
  keeping the risky route, preserve that choice in a distinct typed buffer continuation; after the buffer answer,
  revalidate the chosen action without asking the user to repeat it.
- For the post-creation departure-reminder question, cover generic acceptance, explicit numeric/full-width/Chinese
  lead minutes in the same turn, decline, replay and a later short detail question such as “which two?”. Assert an
  explicit lead creates one relative rule instead of adaptive defaults, the success reply lists actual times, and
  detail follow-ups resolve typed route/reminder context with zero mutation.
- An external activity that is exempt from default general, parking and wait buffers.
- Explicit public endpoints, HOME origin, inferred prior-location origin and legacy-unspecified endpoint source.
  Require Maps point links only for explicitly public points. When both endpoints are public, require exactly one
  directions URL with the typed driving, walking, two-wheeler or transit mode; when either endpoint is private,
  require no full-route URL. Verify coordinate privacy and exact URL replay after restart/replay.
- Scan-friendly LINE rendering with title/date/operational range, chronological operation axis, route source,
  guidance, conflict section and at most one question. Inspect the final decorated LINE text, not only a formatter.
- Route-only lifecycle recovery: natural “continue”/“new” answers, legacy pending root-domain or missing-label
  recovery through the exact Calendar draft UUID, clear-and-restart before and after each route clarification,
  pending draft discard, materialized-plan preservation, cross-actor/workspace isolation and terminal replay. Run
  the known five-step actual-entry path—continue, a complete new route, choose new, continue, clear-and-restart—with
  at least three common control-phrase sets. Assert the resumed reply contains the full current question, the staged
  route survives restart without raw inbound text, and provider invocation starts only after choosing new.
- Generic operation-status questions such as “where are we now?”, “which item is active?” and “what is the
  current progress?”. With an active child workflow, require the public parent topic, active child step,
  preserved/completed facts, unresolved facts and the original actionable next question. Assert zero mutation,
  unchanged pending revision, restart/replay, actor/workspace isolation, leaf cancel returning to the parent,
  and explicit whole-tree cancel closing every unfinished node while preserving committed resources.

Assert zero Calendar materialization before provider evidence and before direct-overlap force; exactly one route
after provider success, non-overlap risk, missing-adjacent-location continuation and replay; and exactly one
reminder rule/occurrence when an early ride-hail reminder is accepted.

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

For a question with two or more actions, create scenarios for the normal prompt, lifecycle status/resume, restart,
replay, and a competing generic intent. Assert final LINE text uses consecutive numbered blocks with blank lines,
and that every option explains persistence or mutation impact. Resolve public labels and common answer phrases from
the same typed catalog; include at least one public label used verbatim as the answer.

When multiple drafts exist, prove both quoted selection and the no-quote ambiguity path. The latter must ask
exactly one target-selection question and mutate nothing. Insert feedback, meta, and unrelated read-only turns
between question and answer and prove the pending question survives unchanged.

For every unfinished-operation flow, include: an unmistakable continuation, an unmistakable new operation, an
ambiguous executable request, both answers to the target-choice question, a read-only/meta interjection while
the choice remains open, an explicit quote override, restart, replay, and parallel workflows. Assert that the
public reply names the retained/current operations, ambiguous input performs zero domain/provider mutation, and
the typed interrupted question is restored or completed exactly once without raw inbound text. When the ambiguous
turn was already a complete validated operation, choosing new must consume its typed staged state directly; asking
for the same content again is a failure.

## Add adversarial user checks

Review each reply as a skeptical everyday user:

- Did it answer the actual question first?
- Did it claim completion before completion?
- Did it promise memory, rerun, background work or notification without durable matching evidence?
- Did it silently create, change, cancel, or delete anything?
- Did it repeat a question already answered in context?
- Did it choose a convenient recent context instead of the explicit quote?
- Did it reveal an implementation detail or identifier?
- Would a reasonable user know whether work succeeded, failed, needs input, or is still running?
