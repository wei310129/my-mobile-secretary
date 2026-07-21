# Context routing A/B evaluation

Evaluation date: 2026-07-21 (Asia/Taipei)

## Purpose

Verify that repository-scoped progressive disclosure reduces routine documentation context by at least 20% without removing safety invariants or task-specific gates.

## Method

Use UTF-8 byte count as a deterministic proxy for context size. This is not an exact model input-token count; tokenizer, harness metadata, user prompts, source code, and tool results are intentionally excluded.

Baseline A represents the previous common reading pattern:

- Root `AGENTS.md`.
- Full `docs/architecture.md`.
- Full `docs/development-plan.md`.

Baseline A totals 109,377 bytes in the current worktree.

Routed B includes root `AGENTS.md`, `docs/agent-context/index.md`, `docs/decisions/current.md`, and only the task row's required skill or document sections. The common routed entry is 11,873 bytes.

## Results

| Representative task | Routed bytes | Reduction from baseline |
| --- | ---: | ---: |
| Conversation regression / quoted context | 37,180 | 66.0% |
| Flyway / workspace / RLS | 16,642 | 84.8% |
| External provider client | 12,676 | 88.4% |
| AI Dispatcher | 18,299 | 83.3% |
| Pure product/architecture documentation | 13,825 | 87.4% |
| Future phase planning | 14,538 | 86.7% |

All six representative routes exceed the initial 20% reduction target. The conversation route intentionally retains the complete conversation skill and its three required acceptance references; removing those gates would reduce context further but weaken correctness, privacy, latency, and generalization controls.

## Safety and routing gates

- Root safety invariants remain present: Java 21/Spring Boot 3.5.x/Spring AI 1.1.x, deterministic Java execution, controller/domain boundaries, injected `Clock`, Flyway, disabled open-in-view, intent catalog/test coupling, LifeRecord/tag recording, approved product behavior, and secret handling.
- Dispatcher retains independent Maven, database, Flyway, Compose, failure, enablement, and main-runtime isolation boundaries in its nested `AGENTS.md`.
- Conversation work retains scenario, holdout, mutation, idempotency, actor/RLS, information-leak, quoted-context, latency, and UX gates in the repo-scoped skill.
- Current decisions, active plans, completed plans, historical development records, and long-flow context-compression policy have distinct entry points.
- The real-conversation batch plan remains local-only and ignored; raw LINE-derived material was not copied into versioned documentation.
- Six of six routing scenarios resolved to existing repository files on the first static conformance pass.

## Operational metrics not inferred from this migration

Do not claim improved Maven first-test success rate or end-to-end development elapsed time from byte counts. Record those metrics over subsequent real tasks by task class, including selected files, first test command/result, number of unrelated reads, elapsed time, and invariant violations. Compare like-for-like tasks only. The routing migration gate is complete when context reduction and static safety pass; operational performance remains a longitudinal observation rather than a reason to keep this documentation migration open.

## Result

PASS for repository context routing: minimum measured reduction 66.0%, six of six static routes valid, and no safety invariant intentionally removed.
