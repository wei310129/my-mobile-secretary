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
| Calendar ownership | Calendar v2 is the final backend source of truth; legacy `ScheduleItem` is isolated only during development/cutover and is removed before product release; EventKit/ICS are lossy adapters |
| Calendar model | A `CalendarPlan` has at most one level of `CalendarActivity`; plan/activity can own multiple critical/non-critical and locked/windowed/flexible time nodes |
| Calendar adoption | Creation, visibility, participation, adoption, busy impact and reminders are separate; only actor-adopted nodes constrain that actor's route |
| Feasibility | Overlap is always recordable; Java evaluates physical feasibility only for adopted constraints, preserves impossible combinations, and never lets the planner promise an impossible route |
| Calendar time/reminders | Timed data uses Instant + ZoneId, all-day uses LocalDate; reminders belong to actor/node, max 8 active rules, and post-node follow-up is a Task |
| Planning and Task convergence | User-visible planning types are Draft, Task, Calendar and Knowledge only; Task deadline, Calendar placement and reminder rule are independent, and a Calendar-node follow-up is a typed linked Task |
| Calendar sharing/registration | One actor can join multiple group calendars; same-workspace ACL, participation, roster, capacity, waitlist, late-change notification and ownership transfer follow D20–D31/D40–D48 in the Calendar v2 plan |
| Calendar recurrence | Typed DAILY/WEEKLY/MONTHLY/YEARLY rules, occurrence exceptions/splits and bounded expansion follow D32–D38; raw RRULE is not core truth |
| Calendar interoperability | First release has authenticated one-time ICS snapshot export and private import proposals; no webcal/two-way sync; EventKit waits for the iOS client and starts with least privilege |
| Calendar first-release UX | Backend REST v2 + LINE; secure search and one HTTPS online link per target are release gates, StoredMedia attachment grants land before cutover, and color waits for iOS UI |
| Calendar cutover | No test-data migration or dual-write; actor pilot/feature flag precedes hard cutover, then a separately approved destructive gate removes legacy schedule code/schema |
| Pending work | Undecided schedules/tasks enter the pending flow and may be surfaced during suitable free time |
| Reminder debounce | Same-task reminders have a default minimum interval of 10 minutes, configurable through the approved property |
| Reminder escalation | Unconfirmed reminders escalate after 15 minutes, at most three times |
| Group calendar sharing | Calendar v2 generalizes family sharing to multiple groups; workspace membership/visibility never implies participation, adoption, availability or reminders |
| Primary interaction | LINE Bot is the current primary interaction channel |
| Notifications | Log, Windows Toast, and notification outbox are the current channels/foundation |
| Intent additions | Add the domain handler, `conversation-capabilities.txt` entry, and regression test together |
| User-visible events | Record applicable events through the common LifeRecord/tag graph path; development feedback is not a life event |
| External cost/privacy | Ask before adding paid services or using real personal/location data |
| Destructive behavior | Ask before data deletion, destructive migrations, or silent changes to approved product semantics |
| Dispatcher | `internal/ai-dispatcher` is an isolated development application and must not become a main-runtime dependency |
| Development coordination | Coordinator-aware repository-owned Maven, Spotless and dev lifecycle scripts use lease/receipt/doctor tooling; unmanaged IDE/direct commands remain detect-and-block, not technically intercepted |
| Runtime version identity | `/actuator/info` reports the running main JVM as Git reachable-commit count plus canonical SHA; only matching clean checkout/build is CURRENT, dirty metadata is explicit, and Java delivery preserves the service's pre-change running/stopped state |
| Two-machine ownership | Laptop owns main integration, Calendar/Travel/Conversation, central docs/config and the Flyway sequence through Calendar Wheel 10. After the published B2 checkpoint, desktop exclusively owns existing `booking/**` and new `execution/**`; cross-machine safety uses GitHub branch/path ownership and explicit handoffs, not single-machine mutexes |
| Cross-lane triggers | Cross-session readiness is delivered through producer-owned Git state plus a user-visible trigger receipt. A consumer never waits open or assumes automatic notification. Desktop start, PR review/merge, Calendar W10, schema grant/stale, Travel typed handoff, ADD handoff, new decisions and destructive/external authority use hard-yield gates |
| Human availability | Attention, sleep and lane-switch time are coordination resources. Agents must proactively propose improvements when observed execution friction conflicts with availability, but may not infer quiet hours or authority from silence, and may not change ordering, safety gates or product behavior without approval |
| Agent merge authority | Agents may merge only after the user explicitly authorizes the exact PR and current head SHA in that turn. GitHub must fail closed through the required `Merge policy` check, current-base required checks and no administrator bypass; a changed head invalidates prior authorization |
| Desktop Booking order | Desktop gates are `B3-Core → B4-Fake → B3-Durable → B3-Upstream Adapters`, one branch and PR per gate. While schema or Travel typed handoff is unavailable, desktop may do schema-free ADD core after B4 instead of crossing ownership. First external boundary ends at fake B4; no Duffel/sandbox/live/Playwright/credential/order/payment |
| ADD execution v1 | Reuse unfiltered `SUGGEST_NEXT_TASK`; category queries keep task-only behavior. Return at most 1 Now, 1 Next and a Later count using time, dependencies and actor-adopted Calendar only. Imminent fixed Calendar in its prep window wins over overdue high-priority Task; default missing buffer is 15 minutes for display only. Owner pilot flag defaults off and LINE v1 is read-only |
| Coordination Phase 0 freeze | v1 uses Windows Global gate/slot mutexes as the lease authority, LOCALAPPDATA atomic registries as metadata, fixed resource ordering, owner/generation fencing, and fail-closed handling for unverifiable machine/logon visibility; existing wrappers retain their contracts until phased adapter rollout |
| Coordination rollout | State writes, service-generation logs, protected Dispatcher drain, source-writer/Flyway claims, handoff receipts and ledger are rolled out; shared/persistent and unverifiable Testcontainers resources remain retain-only/fail-closed |
| Product roadmap phase | Product Phase 0–2 complete; Phase 3/conversation work remains active; advanced infrastructure phases have not started |
| Travel project | Wheels 1, 2 and 3A are complete. 2026-07-25 override: 3B-A is the one-off `ProjectCalendarPlanBinding` core; its focused gates and Calendar Wheel 8 merged full regression are green, so the stable handoff is available. 3B-B recurrence/copy/split propagation remains `BLOCKED_BY_CALENDAR_WHEEL_10`. Wheel 10 only clears that dependency. Never add `schedule_item.project_id`, project-scoped legacy Schedule CRUD, or a transition binding; this does not start 3C. |

## Current work

- Calendar v2 wheels 0–8 are complete under `docs/exec-plans/active/calendar-plan-v2-sol-medium-development-test-plan.md`; Wheel 8 delivered the V72–V82 Knowledge, attachment, sharing, lifecycle and immutable replacement scope with sealed holdout and 1,417-test root regression green. Hard cutover and legacy removal remain separately gated.
- Travel Project wheels 1, 2 and 3A are complete under `docs/exec-plans/active/travel-project-terra-high-development-test-plan.md`; 3B-A one-off Calendar ownership now has a stable focused-plus-full-regression handoff, while recurrence/copy/split propagation remains in 3B-B behind Calendar Wheel 10.
- Real-conversation repair batches remain ignored and local-only under `docs/exec-plans/active/local/`; see the active index for the privacy boundary.
- The laptop/desktop development split is documented under `docs/exec-plans/active/two-machine-parallel-development-plan.md`. Desktop product development remains blocked until the laptop publishes a Git-retrievable Booking B2 handoff SHA and changes `desktopStart.status` to `READY`.
- Development-session coordination is recorded under `docs/exec-plans/completed/development-session-coordination-pipeline.md`; shared/persistent cleanup remains disabled by default and unmanaged tools remain blocked when detected.
- `internal/ai-dispatcher` evolves independently of product phase progress.
