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
- User-visible output remains free of internal identifiers and diagnostics on success and failure paths.
- Performance optimization preserves the same semantic, mutation, authorization, and privacy assertions.

## Review extensibility without overengineering

Explain how one additional realistic variant would be added. A healthy design usually adds data, a typed value, a handler, or a strategy implementation without rewriting the central flow. If the predicted variant is speculative and no stable pattern exists, keep the implementation local and clear rather than inventing a premature framework.
