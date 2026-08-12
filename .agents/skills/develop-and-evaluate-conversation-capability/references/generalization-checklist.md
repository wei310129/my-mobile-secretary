# Generalization Checklist

## Identify the design shape

Before implementing, state:

- The user-visible invariant.
- The deterministic business invariant.
- The variation axes: wording, person, date, place, amount, recurrence, channel, context, and state.
- The ownership boundary: controller, application service, domain policy, repository, integration adapter, or response formatter.
- The mutation and transaction boundary.
- The extension point for the next realistic variant.

Prefer an existing service, value object, policy, strategy, registry, parser, or formatter when it owns the rule. Introduce a new abstraction only when a stable variation axis or repeated responsibility justifies it.

## Reject narrow fixes

Do not accept:

- An exact-phrase branch added only for a reported sentence.
- A growing monolithic `if` or regex router for deterministic business behavior.
- A prompt-only repair for dates, states, money, authorization, selection, or mutation.
- Duplicate business logic in controllers, LINE adapters, API handlers, and background workers.
- A schema change when the existing model can express the required state.
- A new framework or generic hierarchy without a demonstrated extension need.
- A cache or shortcut that weakens correctness, privacy, freshness, or actor isolation.

Lexical normalization may use phrase dictionaries or bounded patterns, but normalized meaning must flow into typed deterministic behavior.

## Prove generalization

Before completion, prove that:

- Changing nouns, people, dates, places, and amounts does not require a code branch per phrase.
- At least one previously unseen holdout phrasing passes.
- At least two neighboring-intent counterexamples remain correctly rejected or routed.
- Ambiguous input asks for the missing decision instead of guessing.
- Read-only and failure cases leave state unchanged.
- Duplicate delivery remains idempotent.
- Explicit quote selection overrides unrelated recent context.
- A new executable intent while another typed question is pending flows through the same context-transition
  policy: lexical variants classify only clear continuation/new-work signals, ambiguity asks once, read-only
  interjections preserve state, and no domain-specific full-sentence branch is added.
- A complete validated new request is not discarded at the context-choice boundary. Capability-owned typed staging
  and an actor/workspace/scope-bound UUID pointer survive restart; no raw text, address, or generic slot bag is kept.
  Choosing new consumes the staged request directly, while choosing continuation restores and fully re-renders the
  interrupted question. Provider and committed-resource mutations remain zero until the choice.
- Operation lifecycle behavior is contributed per typed capability rather than a monolithic cross-domain router.
  New contributors reuse start/resume/supply/close semantics, exact owner resolution, fail-closed ambiguity,
  committed-resource preservation, replay and actor/workspace/RLS tests. Enabling one contributor does not
  silently grant lifecycle or mutation authority to neighboring capabilities.
- A new contributor supplies typed public topic, progress and next-question data to the same shared resume renderer.
  It cannot reorder the context-first layout, infer a topic from raw recent text, or become production-enabled merely
  because the upper interface exists; each capability receives a separate implementation and review gate.
- A new required sub-capability adds one typed parent-child edge and a typed child-result contract. It does not
  overwrite the parent pointer, retain raw dialogue as continuation state, or grant lifecycle authority to every
  capability. Generic status rendering remains shared and read-only; leaf/tree cancellation semantics remain
  deterministic when a second child type is introduced.
- A new capability completion claim extends the shared typed completion gate with capability-owned evidence.
  It cannot equate a generic persisted/materialized status with conversation completion, cannot let an optional
  post-creation setting block the main operation, and cannot duplicate a committed resource while repairing an
  incomplete parent.
- New child stages declare compatible typed input instead of accepting arbitrary bounded text. Choice matching is
  exact/compositional, so verbs shared with complete neighboring operations do not capture them. Incompatible input
  preserves state and uses the shared topic/stage/unchanged-data/next-step response; “retain” is not completion.
- New Google Maps place URL shapes reuse one bounded URI/redirect resolver. They do not add sample-link branches,
  trust arbitrary HTTP hosts, require a Places key when coordinates are already present, or persist the raw URL.
- User-visible output remains free of internal identifiers and diagnostics on success and failure paths.
- Performance optimization preserves the same semantic, mutation, authorization, and privacy assertions.
- Every executable intent and every pre-interpreter/outbound bypass has an audited public-response,
  clarification, pending-state, mutation/confirmation, and regression contract.
- Adding another required slot changes typed draft data and ordered clarification steps, not a prompt-only
  missing-field list or phrase-specific branch.
- Adding or changing a multi-action question changes one typed choice catalog and shared numbered renderer, not
  separate strings in a handler, conversation method, lifecycle resume, or replay path. Verify public labels,
  persistence/mutation effects, accepted answers, numbering, spacing, and one-capability-at-a-time rollout.
- Image/OCR, replay, provider failure, denial, greeting, formatter and focus decoration cannot bypass final
  validation even when a new adapter is added.
- A new reply template declares its claim class and evidence requirement; unsupported future-tense promises
  cannot be made true by adding a phrase to a sanitizer allowlist.
- New feedback wording selects from controlled polarity variants without storing raw feedback or adding a
  person-, brand- or full-sentence branch. A new address/name phrase flows into the same typed preference draft.
- A praise synonym remains acknowledgement-only. A new explicit long-term style phrase maps to a supported
  typed style rather than raw prose, and cannot claim persistence unless the actor/workspace commit succeeded.
- Name/address settings remain actor/workspace data with restart, replay and RLS evidence, while feedback and
  meta turns suspend and restore unrelated pending business work.
- Corrective feedback repairs any supported prior read-only domain through typed prior action, not a branch for
  one complaint phrase; insufficient context creates one fenced repair question with zero business mutation.
- Generic overview and explicit-domain routes are selected from compositional semantic features, and repeated
  unknown replies progress without sentence-specific branches.
- A new system-owned reference category can be added as catalog data. Exact, logical-multipoint and cross-region
  entity ambiguity remain typed states: exact applies directly, logical multipoint defers selection to final
  planning, and only cross-region ambiguity asks one region question. Custom places retain precedence.
- A new public-search provider can return typed found/not-found/unavailable evidence only after catalog miss,
  without gaining write authority or bypassing source/saved-state disclosure and the final response boundary.
- Catalog data without coordinates cannot masquerade as route evidence, and copying accepted system data into
  a plan snapshot cannot silently create a custom place.
- A new place category or transport mode flows through typed entity evidence and operation routing without
  adding a sentence-specific branch. Address, temporal-property and route intents remain neighboring contracts.
- A new logical multipoint place is added as catalog/group data. Knowledge confirmation, point listing and
  keyword filtering reuse typed operation routing and actor/scope browse keys; no full-sentence branch, raw-text
  draft or premature itinerary explanation is required.
- Optional transport survives restart/replay as typed Calendar state, produces no second visible activity, and
  cannot change a LOCKED/WINDOWED/FLEXIBLE activity time without a new explicit confirmation.
- A new route phrasing, transport mode or provider flows through typed itinerary semantics and policy instead of
  a sentence-specific branch. Planning/arranging remain equivalent; provider-unavailable retain/retry, quote and
  active-focus precedence, direct/adjacent conflict checks, one terminal materialization, public attribution and
  no silent paid first/last mile remain intact. Provider priority changes only after representative live evidence,
  not prompt edits or assumed vendor quality.
- Route journey kind remains stable when raw structured fields vary but validated placement/source semantics are
  equivalent. Adding an activity verb updates bounded typed semantics, not a full-sentence branch. Unlinked
  adjacency remains six-hour bounded before both assessment and fingerprinting, with only typed linkage override.
- New route phrasings preserve the provider-derived transport interval in public headers, duration and persistence;
  general connection buffers, parking and ride-hail waiting never expand that core interval. Standalone travel
  never reintroduces an activity-duration question; activity-duration questions require typed activity semantics.
- New transport modes and operation steps extend typed mode/window policies, not sentence-specific reply branches.
  Public route blocks remain chronological and name every affected plan. General buffer is connection-scoped and
  appears only for nearby-item risk with location movement; no-risk routes neither ask nor persist it. Parking, wait
  and the shared start-reminder lifecycle keep exact restart/replay mutations. Any choice made before a buffer ask
  is retained by a bounded typed continuation rather than inferred from raw conversation text; relative reminders follow adjusted
  starts and fixed reminders require review. New endpoint sources default private and gain Maps-link authority only
  through an explicit bounded enum decision, never label text, coordinates alone or model confidence. Full-route
  Maps URLs require both endpoints to have that authority and map new travel modes through typed URL policy rather
  than route text; they remain navigation links, not route-provider evidence.
- New reminder acceptance wording flows through one typed lead-or-adaptive policy. Chinese/full-width/numeric minute
  forms do not add full-sentence branches; explicit values retain precedence, reminder results list actual times,
  and short detail follow-ups reuse actor/workspace/workflow-bound active-rule lookup with zero mutation.
- New risk statuses and directions extend typed quantitative rendering: plan title, boundary times, gap, required
  travel and shortage. Question codes preserve direct overlap versus previous/next connection semantics across
  restart; generic prompts never erase why the user is being asked to choose.
- New HOME wording, travel titles, people, hotels, brands, countries or islands do not add phrase-specific origin
  branches. Origin selection uses confirmed typed locations, time, coordinates, distance and journey linkage;
  uncertainty asks one question. Preference/pending rows stay actor/workspace fenced, RLS protected, raw-text
  free, restart/replay safe, and every continuation mutation remains behind the inbound mutation boundary.
- Platform loading support remains source-scoped, reply-token independent, best effort, and separate from the
  exactly-once terminal response.

## Review extensibility without overengineering

Explain how one additional realistic variant would be added. A healthy design usually adds data, a typed value, a handler, or a strategy implementation without rewriting the central flow. If the predicted variant is speculative and no stable pattern exists, keep the implementation local and clear rather than inventing a premature framework.
