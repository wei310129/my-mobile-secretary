# Current approved decisions

This is the concise current-decision entry point. `docs/development-plan.md` remains the historical trace and contains the original rationale and phase records. When the two conflict, stop and confirm rather than silently choosing historical text.

| Area | Current decision |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.x, Spring AI 1.1.x; do not cross minor/major lines without approval |
| Product priority | Reminder reliability is more important than reminder cleverness |
| Architecture | Thin clients and a modular-monolith backend; controllers are protocol-only and domain does not depend on Web/API packages |
| AI boundary | LLM performs structured language understanding and expression only; Java validates and executes time, geography, state, authorization, and mutation rules |
| Time | Inject `Clock`; do not bind business logic to system time |
| Schema | PostgreSQL/PostGIS with Flyway-only schema changes; keep `spring.jpa.open-in-view=false` |
| Workspace isolation | New owned tables use `workspace_id` and the established PostgreSQL RLS pattern; the former “do not pre-add user_id” rule is obsolete |
| Schedule ownership | Backend `ScheduleItem` is the source of truth; EventKit may later become a synchronization source |
| Feasibility | Only feasible plans are accepted; deterministic checks improve as external travel-time evidence becomes available |
| Pending work | Undecided schedules/tasks enter the pending flow and may be surfaced during suitable free time |
| Reminder debounce | Same-task reminders have a default minimum interval of 10 minutes, configurable through the approved property |
| Reminder escalation | Unconfirmed reminders escalate after 15 minutes, at most three times |
| Family sharing | Product functionality remains a later phase; workspace/RLS foundations already apply |
| Primary interaction | LINE Bot is the current primary interaction channel |
| Notifications | Log, Windows Toast, and notification outbox are the current channels/foundation |
| Intent additions | Add the domain handler, `conversation-capabilities.txt` entry, and regression test together |
| User-visible events | Record applicable events through the common LifeRecord/tag graph path; development feedback is not a life event |
| External cost/privacy | Ask before adding paid services or using real personal/location data |
| Destructive behavior | Ask before data deletion, destructive migrations, or silent changes to approved product semantics |
| Dispatcher | `internal/ai-dispatcher` is an isolated development application and must not become a main-runtime dependency |
| Current phase | Phase 0–2 complete; Phase 3/conversation work remains active; advanced infrastructure phases have not started |
| Travel project | Planning is approved, but runtime support must follow the dedicated execution plan and its consent/focus invariants |

## Current work

- Global Conversation Focus foundations and the travel-project prerequisites are active under `docs/exec-plans/active/travel-project-terra-high-development-test-plan.md`.
- Real-conversation repair batches remain ignored and local-only under `docs/exec-plans/active/local/`; see the active index for the privacy boundary.
- `internal/ai-dispatcher` evolves independently of product phase progress.
