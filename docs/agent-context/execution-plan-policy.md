# Execution plan policy

Execution plans are versioned repository artifacts unless they contain secrets, personal data, raw production conversations, or environment-specific identifiers.

## Locations

- `docs/exec-plans/active/`: currently executable plans and their indexes.
- `docs/exec-plans/completed/`: completed plans whose decisions and gate evidence remain useful.
- `docs/exec-plans/active/local/`: local-only sensitive execution material. Add an exact ignore rule and expose only a sanitized pointer from the active index.
- `.codex/`: Codex runtime configuration, rules, and logs; do not use it as the long-term execution-plan store.

Do not move an active plan while code, migrations, or tests still reference its path. First add a compatibility pointer or update all references at a phase boundary, then move it in a dedicated documentation change.

## Required plan content

State the outcome, scope, approved product decisions, invariants, affected files or modules, phase gates, validation commands, untested paths, destructive or migration boundaries, and remaining risks. Link to architecture and decisions instead of copying their full contents.

## Context compression reminder points

For a plan intended to continue across a long Terra session, list explicit reminder points only at a stable phase exit: a steering wheel or stage is complete with recorded gate results, or an investigation has converged before an independent implementation/acceptance stage.

Do not place a reminder during an uncommitted key decision, migration or destructive operation, unidentified test failure, or work that still depends on large unsummarized context.

At each reminder point, provide a continuation summary containing:

- Current phase or steering wheel.
- Approved decisions and invariants.
- Modified files.
- Validation results and failed or skipped gates.
- Remaining work and next action.
- Risks, blockers, and any required user decision.

When the point is reached, briefly tell the user: `現在是適合壓縮 context 的時機`, followed by that continuation summary. Recommend compression only; never perform it implicitly or use it to skip validation.

## Lifecycle

1. Create or register the plan in the active index.
2. Update gate results and current phase in the plan, not in unrelated architecture prose.
3. Extract newly approved durable product decisions into `docs/decisions/current.md`.
4. At a stable exit, move the plan to `completed/`, update inbound links, and retain decision/test traceability.
