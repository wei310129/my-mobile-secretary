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

If a plan can run across multiple sessions or agents, it must also include a resource concurrency
matrix, ownership and lease scope, fixed multi-resource acquisition order, stale-owner recovery,
owner-scoped cleanup boundaries, a machine-readable handoff contract, and concurrency/crash
validation. A timeout or heartbeat expiry alone must not authorize destructive takeover.

## Context compression decisions

The active agent decides when context compression is appropriate for every project task. It must balance development quality, development efficiency, and token efficiency rather than mechanically following a fixed token threshold, turn count, phase, or document heading. Relevant signals include whether decisions, invariants, and gate evidence are durably recorded; whether the next unit of work can continue independently; whether completed context is obscuring current work; whether keeping it still adds material value; and whether the remaining context can safely carry the expected next unit of work.

A long-running plan may list context-compression candidate points and draft continuation summaries. Candidate points produced by GPT-5.6, Sol, or another high-capability planning model are important inputs, but they are advisory rather than exclusive or mandatory. Based on actual development needs, the active agent may compress earlier or later, skip a candidate, or add a new one. Any plan or prompt that says compression is required at a prelisted point is interpreted as a recommendation unless the user explicitly fixes the timing in the current request.

The agent must not recommend compression during an uncommitted key decision, migration or destructive operation, unidentified test failure, or work that still depends on large unsummarized context. Before every actual recommendation, it must prepare a self-contained continuation summary containing:

- Current phase or steering wheel.
- Approved decisions and invariants.
- Modified files.
- Validation results and failed or skipped gates.
- Remaining work and next action.
- Risks, blockers, and any required user decision.

When the execution environment requires the user to trigger compression, briefly tell the user: `現在是適合壓縮 context 的時機`, followed by that continuation summary. Recommend compression only; never discard approved decisions, use compression to skip validation, or describe directly dependent unfinished work as a stable exit.

## Lifecycle

1. Create or register the plan in the active index.
2. Update gate results and current phase in the plan, not in unrelated architecture prose.
3. Extract newly approved durable product decisions into `docs/decisions/current.md`.
4. At a stable exit, move the plan to `completed/`, update inbound links, and retain decision/test traceability.
