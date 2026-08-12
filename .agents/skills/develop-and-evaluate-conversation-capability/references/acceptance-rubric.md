# Acceptance Rubric

## Apply hard gates

Fail the entire capability when any applicable hard gate fails:

- The intent, action, and deterministic business result are correct.
- Required information is never guessed or invented.
- Dates, amounts, ranges, states, geography, authorization, and mutations are validated by Java.
- Read-only, feedback, rejected, and failed interpretations produce zero unintended mutation.
- Mutation count, transactionality, state transitions, and idempotent replay are exact.
- Workspace, actor, RLS, privacy, and sensitive-data boundaries hold.
- The reply answers the direct user need before limitations or next steps.
- No public response leaks internal identifiers or diagnostics.
- Explicit quoted context is selected correctly and cannot cross actor or workspace boundaries.
- The latency target or required progress-feedback contract is met.
- Neighboring capabilities and permanent regressions remain passing.
- Every needs-input response has exactly one typed next question; bulk missing-field lists are forbidden.
- Every typed next question with two or more actions uses consecutive numbered blocks (`1.`, `2.`, `3.`), blank
  lines between options, and an action label plus persistence/mutation effect. One typed catalog supplies action
  codes, public labels, effects, and accepted answers to the normal prompt, lifecycle resume, and replay; inline
  duplicated `A, B, or C?` strings are a hard-gate failure.
- Valid slots supplied in one turn are all absorbed, and no still-valid answered slot is asked again.
- Pending question state is actor/scope/workflow fenced, durable across restart, RLS protected, replay safe,
  and contains neither raw inbound text nor a generic JSON/command slot bag.
- An unfinished operation is never silently reused or replaced. Clear continuation names and resumes it; clear
  new work retains the old operation and names both; ambiguous executable input asks one typed target-choice
  question before any domain/provider mutation. Both choices, read-only interjection, quote precedence, restart,
  replay and parallel workflows preserve exactly-one state and transactionality.
- Resume replies repeat the complete actionable current question and carry typed next-question metadata. A validated
  complete new operation that caused target choice is staged as capability-owned typed state; choosing new never
  asks the user to repeat it, makes no provider/committed mutation before consent, and remains restart/replay safe.
- Start/resume/supply/close use a typed upper lifecycle with one reviewed capability contributor at a time.
  Clear-and-restart works at every question step, resolves legacy identity only through an exact typed owner,
  fails closed on missing/ambiguous ownership, closes only unfinished state/pending/focus, names the operation,
  preserves committed resources and has replay/RLS/exact-mutation evidence.
- Every resume reply first identifies the actor/workspace/workflow-bound public topic, then states current progress,
  then asks exactly one actionable next question. The shared renderer enforces this order; contributors supply typed
  content. Raw recent text, model summaries, ambiguous history and internal identifiers cannot become the topic.
- A typed parent-child workflow never loses or silently replaces its parent. Generic progress queries are
  read-only and decision-complete; they name the parent topic and active child, preserve the pending question,
  and disclose enough completed/unresolved facts to orient the user. Leaf cancel resumes the parent, while only
  explicit whole-operation cancel or clear-and-restart closes the unfinished tree. Both paths preserve committed
  resources and prove replay, RLS and exact mutation counts.
- A Place child accepts bounded names, addresses and HTTPS Google Maps place links. Short-link resolution validates
  every redirect against an exact Google Maps host allowlist, rejects non-Google targets, persists only the resolved
  typed candidate, and performs zero Place/Calendar mutation before explicit confirmation.
- Every child answer is compatible with its typed stage. Exact choices cannot capture a complete neighboring
  operation by substring. Incompatible input preserves the child and committed state, explains the current topic
  and stage, and offers one actionable continuation or retain-and-switch path. Retaining never claims completion.
- Every outbound point reaches the same final public boundary after all formatting and decoration.
- Every user-visible commitment has matching typed evidence. Unsupported memory, repair-started,
  repair-completed, mutation and terminal-notification claims fail closed at that boundary.
- A committed row or materialized resource is not operation-completion evidence by itself. A shared typed gate
  must verify capability-owned required facts, committed-resource consistency, required provider evidence and
  required child completion before public created/scheduled/finished wording; optional post-creation settings do
  not block that gate.
- Ordinary replies use concise secretary language without automatically added decorative emoji. Praise and
  dissatisfaction each have at least ten controlled variants with no immediate repeat; dissatisfaction asks
  one useful question unless a real fresh repair completed.
- Ordinary praise neither infers nor writes a lasting response-style preference. Only an explicit
  future-facing instruction may commit a bounded typed style that the final boundary actually supports;
  the commit must be actor/workspace scoped, RLS protected, readable after restart and zero business mutation.
- Assistant self-name and user-address preferences are actor/workspace scoped, RLS enabled and forced,
  restart/replay safe, readable after commit, and never change identity or authorization. Their typed draft
  stores no raw chat text or generic JSON slot bag and restores any interrupted business question.
- Critical LINE capabilities include anonymized evidence from the actual LINE entry path; focused service or
  mocked adapter tests alone cannot satisfy the release gate.
- Corrective feedback names the actual mismatch, performs a same-turn read-only repair when grounded, or asks
  one typed repair-target question when not grounded; it never consumes unrelated pending work or mutates data.
- Consecutive unknown replies progressively narrow one question instead of repeating an open-ended fallback.
- Generic secretary overview requests include the relevant current and next task/schedule state, while an
  explicit task or schedule request stays scoped to that domain.
- A product-owned reference catalog takes precedence over external search when applicable. Deterministic exact
  matches apply without confirmation; a logical large-place multipoint result defers selection until final
  itinerary materialization and then discloses the selected point and verified reason. Only cross-region entity
  ambiguity asks one typed region question. Public replies disclose system/provider source and saved/not-saved
  state; accepted system data may enter a typed plan snapshot but cannot silently create a custom place.
  A user-specified precise unique point does not receive a custom-place disclaimer; large-place final point
  selection still discloses the system's selected point and reason.
- Missing catalog coordinates produce insufficient route evidence. They cannot be treated as a verified route,
  distance, travel time, or basis for choosing among physical points.
- Place entity recognition and requested operation are typed separately; a route, service-time or itinerary
  request cannot pass by returning only an address.
- Place knowledge, location, point-list, keyword-filter and itinerary operations are typed separately. A
  knowledge reply answers first without itinerary assumptions or read-only mutation disclaimers. Multipoint
  list/filter follow-ups use actor/scope-owned catalog keys, survive restart/replay, allow an explicit new place
  or quote to override, and ask exactly one place question when no browse context exists.
- A complete explicit activity mutation creates exactly one visible activity before offering optional transport.
  Accepted transport adds at most one typed internal plan node from reliable provider evidence. External-control
  activities never move, and WINDOWED/FLEXIBLE activities require fresh confirmation for each time change.
- An explicit complete origin-destination itinerary treats planning/arranging synonyms consistently and invokes
  the configured provider on the first turn. No route materializes before typed provider evidence. Unavailability
  asks one retain/discard question and typed retry creates at most one plan without re-asking verified slots.
  Quote precedes active route focus, which precedes a unique transport workflow; parallel drafts cannot consume
  each other. Direct overlap and both adjacent connections are checked, and any risk reply starts with a verified
  route preview, gives one recommendation and one question, and exposes no internal node or provider diagnostic.
- Route journey kind is persisted from validated placement and explicit source semantics, never selected from
  raw model `endAt` or reconstructed from placement alone. Unlinked adjacency is bounded to six hours before
  assessment/fingerprinting; typed explicit journey linkage may cross the bound. A far missing location cannot
  create a connection warning, while a nearby missing side follows a verified route with one side-specific ask.
- A standalone route derives its full interval from departure/arrival role and provider duration. Its public
  header, duration and persisted interval exclude general buffers, parking and ride-hail waiting. It never asks
  for activity duration or an activity end time; those questions remain scoped to a separately typed activity.
- A route reply is scan-friendly after final LINE decoration and preserves chronological operation order:
  ride-hail wait, provider departure/arrival and parking when applicable. Every affected existing plan is named by
  public title/time. Direct overlap is zero-materialization until explicit force; non-overlap risk and nearby
  missing-location paths create exactly one verified route before warning/asking. A general connection buffer is
  absent unless two nearby items create a connection risk and at least one side moves location; external activities
  do not inherit route-operation defaults. A safe-reschedule or keep choice survives the intervening typed buffer
  question, replay and restart; safe rescheduling includes the applicable connection buffer in provider
  revalidation without expanding the provider-derived Calendar interval. Start reminders are the final
  single-question lifecycle after higher
  priority conflict/location/buffer questions: new timed items ask once, relative rules follow adjusted start time
  with disclosure, and fixed-clock rules require review. Typed operations survive restart, replay and actor/RLS
  isolation.
- A departure-reminder answer with an explicit lead creates exactly one matching relative rule and occurrence;
  generic acceptance cannot override that supplied value. The terminal reply lists every actual reminder time.
  Short follow-up questions about those reminders resolve the typed route workflow and active rules without asking
  for the title again, reading raw recent text, or causing any mutation.
- Conflict replies are decision-complete: they name every affected public plan and state the current boundary
  times, available minutes, required travel minutes and shortage. Previous-only, next-side and direct-overlap
  questions retain distinct typed lifecycle codes; they never offer moving later when that cannot solve a
  next-side constraint, and never ask a context-free “other time?” question.
- Google Maps point links are emitted only for durable explicitly public endpoint sources. HOME, inferred context,
  legacy-unspecified and private coordinates remain absent from every public channel, including replay and LINE.
  A trusted short link is preferred when available; official coordinate search is the bounded fallback. A full
  directions URL is emitted only when both endpoints pass the same gate, uses the typed travel mode, is replay
  stable, and never claims that Google Routes supplied the verified itinerary.
- Omitted-origin precedence is deterministic and actor scoped. HOME references a confirmed owned Place, has
  ENABLE/FORCE RLS, survives restart/change/replay, and stores no raw address/chat in pending state. Early HOME
  use fails closed on typed cross-night or >=30 km evidence before 10:00, never on title/person/brand alone.
  Continuation mutations cross the inbound mutation boundary and duplicate delivery returns the same terminal
  response with exactly one business mutation.
- Route-provider source and required attribution are public; raw payload, error and identifiers are not. Default
  provider changes require representative configured-provider evidence for success, route quality and visible
  latency. A supplemental confirmation source cannot silently add paid first/last-mile travel or authorize a
  calendar mutation.

An average score cannot compensate for a hard-gate failure.

## Detect information leakage

Seed tests with distinctive internal values and assert that user-visible responses, API bodies, LINE replies, notifications, and error fallbacks do not contain them. Cover:

- Database keys, UUIDs, workspace IDs, actor IDs, external or quoted message IDs.
- Intent enums, capability codes, handler names, Java classes, and packages.
- SQL, table and column names, migration details, and repository method names.
- Stack traces, exception messages, file paths, log lines, trace payloads, and provider errors.
- Prompts, structured-output schemas, router reasons, tokens, credentials, and private URLs.

Allow business numbers such as dates, prices, and user-facing list positions only when the domain response requires them. Do not use a broad “no digits” assertion.

## Measure latency

Measure from accepted inbound request to the first meaningful user-visible response and to the terminal response. Record sample count, median, P95, slowest case, environment, model/provider mode, and warm/cold status.

Use these default targets unless a stricter approved target exists:

| Class | Typical work | Required target |
| --- | --- | --- |
| Low | Deterministic query or mutation | Terminal P95 ≤ 1.5 s |
| Medium | One model interpretation or ordinary lookup | Terminal P95 ≤ 4 s; progress feedback after 2 s |
| High | OCR, multiple providers, large or cross-domain work | Progress feedback within 1–2 s; terminal target normally 15–30 s |
| Long | Batch or externally delayed work | Progress feedback within 1–2 s and exactly one later terminal result |

Treat low- and medium-class overruns as unfinished engineering work. Profile, optimize, rerun correctness tests, and remeasure. For high or long work, verify durable acceptance, timeout behavior, replay safety, pending-state association, and final delivery instead of merely checking acknowledgement text.

For critical LINE work, verify the official one-to-one entry path, loading-animation scope, reply-token
isolation, and exactly one terminal reply. A native loading animation is neither a terminal reply nor evidence
of durable progress delivery.

## Score user experience

Score every scenario from 1 to 5 in each applicable dimension:

- Directly solves or advances the user’s request.
- Correct, truthful, and free of unsupported claims.
- Natural, concise, and understandable to an everyday user.
- Correctly uses quoted and conversational context without needless repetition.
- Clearly communicates completion, failure, missing input, or ongoing processing.
- Cannot make a reasonable user believe that memory, rerun, background work or a terminal notification exists
  without the corresponding durable state.

Require at least 4 in every dimension for every case. Record the reason for any score below 5. Continue the development loop for any score below 4; do not average it away.

## Verify progress feedback

Accept progress feedback only when all conditions hold:

- It is sent within the class budget.
- It says the request was received and is still being processed without claiming success.
- A durable job or application state exists.
- Duplicate delivery cannot create duplicate work or duplicate final responses.
- Exactly one terminal success or failure response is eventually delivered.
- A new user message cannot become attached to the wrong pending work.
- Internal job, message, database, or trace identifiers remain hidden.
