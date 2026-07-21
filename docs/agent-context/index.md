# Agent context routing index

Use this page as the repository knowledge map. Start with the task row, read only the named sections or skill, and expand only when a concrete dependency appears. Root `AGENTS.md` invariants always apply.

| Task | Load first | Expand only when needed |
| --- | --- | --- |
| Conversation, intent, LINE/chat reply, quoted context, user-visible response, or latency | `.agents/skills/develop-and-evaluate-conversation-capability/SKILL.md`; relevant headings in `docs/architecture.md`; `docs/test-strategy.md` | Relevant active execution plan; matching capability catalog, service, test, and migration files |
| Flyway, workspace isolation, actor scope, or RLS | Root `AGENTS.md`; schema/security sections in `docs/architecture.md`; relevant migrations and integration tests | Current decision or active plan that owns the schema change |
| External provider/client | Integration section of `docs/architecture.md`; the provider adapter, properties, and tests | Current official provider documentation and the active plan for that integration |
| AI Dispatcher | `internal/ai-dispatcher/AGENTS.md` | Only the dispatcher document named by that local routing table |
| Pure documentation or product decision | Relevant heading in `docs/architecture.md`; relevant current/active plan section | Historical sections only for traceability or conflict resolution |
| Future phase or large implementation plan | Current phase heading in `docs/development-plan.md`; applicable active plan | Earlier phases only when the current phase explicitly depends on them |

## Reading rules

- Search headings or key terms before opening a long document; do not read `docs/architecture.md` or `docs/development-plan.md` in full by default.
- Treat repository files as the source of truth. External systems may provide current reference material, issue state, or service status, but do not replace versioned decisions, security rules, schema history, or execution plans.
- Resolve conflicts in this order: applicable `AGENTS.md`, current approved decision or active plan, architecture invariant, then historical plan text. Stop for confirmation when product semantics remain ambiguous.
- Keep secrets, personal data, workspace identifiers, and environment-specific credentials out of documentation and version control.

## Current long documents

- `docs/architecture.md`: product and architecture boundaries; select by numbered heading.
- `docs/development-plan.md`: decisions, phases, progress, and historical implementation notes; select the current phase or named issue section.
- `docs/test-strategy.md`: change-relevant test selection and full-suite gates.
- `docs/exec-plans/active/local/conversation-improvement-batches-2026-07-20.md`: ignored local conversation batch plan; load only for a task explicitly belonging to those batches.
- `docs/decisions/current.md`: concise current approved decisions; use `docs/development-plan.md` for historical rationale and traceability.
- `docs/exec-plans/active/index.md`: active-plan registry and local-sensitive plan pointers.
- `docs/agent-context/execution-plan-policy.md`: plan lifecycle and context-compression requirements.
- `docs/agent-context/context-routing-evaluation.md`: fixed A/B byte-proxy method, representative routes, and current acceptance result.
