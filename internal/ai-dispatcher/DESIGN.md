# AI Dispatcher design and operations

## System architecture

```text
 My Mobile Secretary (port 8080 / main DB)
 +--------------------------------------------------------------+
 | LINE webhook -> normal application transaction               |
 |                  -> line_message_log                          |
 |                                                              |
 | GET /internal/integration/v1/development-events              |
 |   read-only / cursor based / actor+workspace RLS / token auth |
 +------------------------------^-------------------------------+
                                | pull only
                                | (main app never calls Dispatcher)
 +------------------------------+-------------------------------+
 | AI Dispatcher (port 8091 / dedicated DB on port 5433)        |
 |                                                              |
 | TriggerSource -> TriggerPollingService -> dispatcher_event    |
 |                                      + trigger_cursor         |
 |                                                |              |
 | DispatcherScheduler -> DispatcherEngine -> Coordinator       |
 |                                                |              |
 |                            one durable lane / one active run  |
 |                                                |              |
 | SessionRegistry -> CodexLaunchService -> CodexExecutionPort  |
 |                     LifecycleService <- finish / heartbeat    |
 |                     RecoveryService  <- status query          |
 |                                                              |
 | CodexExecutionPort is an intentionally unimplemented adapter  |
 +--------------------------------------------------------------+
```

Deleting `internal/ai-dispatcher` does not change the main build. Disabling the main read-only
feed removes its runtime surface without affecting LINE ingestion.

## Durable state machine

```text
             pending event
       +------------------------+
       |                        v
    +------+                +---------+
    | IDLE |<---------------| WAITING |
    +------+ no pending      +---------+
       ^                         |
       | success/no pending      | quiet 5m OR first pending +30m
       |                         v
       |                     +----------+
       +---------------------| STARTING |
                             +----------+
                                |    |
                      ack start |    | uncertain handoff / restart
                                v    v
                           +---------+     status unknown
                           | RUNNING |-------------------+
                           +---------+                   |
                              |    |                     v
                    finish    |    | stale heartbeat +------------+
                              |    +------------------>| RECOVERING |
                              |                        +------------+
                              |                           |      |
                              +--> IDLE / WAITING <-------+      |
                                      confirmed outcome          |
                                                                 |
                                          unknown retry exhausted|
                                                                 v
                                                             +--------+
                                                             | PAUSED |
                                                             +--------+
```

`PAUSED` is required in addition to the requested four states. It is the fail-closed state for an
unbound session or an execution whose outcome cannot be proven. An uncertain run remains active;
its events stay `CLAIMED`, so no second development flow can start.

## Normal sequence

```text
LINE        Main DB       Feed       Dispatcher DB     Coordinator    Codex port
 |             |            |              |                |             |
 | webhook     |            |              |                |             |
 |------------>| INSERT log |              |                |             |
 |             | COMMIT     |              |                |             |
 |<------------| 200        |              |                |             |
 |             |            | GET after C  |                |             |
 |             |<-----------|--------------|                |             |
 |             | page C..N  |              |                |             |
 |             |----------->| ingest events + cursor N      |             |
 |             |            |-------------->| (one tx)      |             |
 |             |            |              | wait quiet     |             |
 |             |            |              |--------------->|             |
 |             |            |              | claim batch + run (one tx)   |
 |             |            |              |----------------|             |
 |             |            |              | durable handoff marker        |
 |             |            |              |----------------------------->|
 |             |            |              |        start receipt          |
 |             |            |              |<-----------------------------|
 |             |            |              | RUNNING + heartbeat deadline  |
 |             |            |              |                |             |
 |             |            |              |<--------- finish(token) ------|
 |             |            |              | consume batch; lane IDLE/WAIT |
```

The webhook transaction contains no Dispatcher call. Feed or Dispatcher failure therefore cannot
increase webhook latency or roll back a LINE message.

## Core class relationships

```text
TriggerSource (port) <|-- MainApplicationFeedSource
        |
        v
TriggerPollingService --> JdbcTriggerEventStore --> PostgreSQL

DispatcherScheduler --> DispatcherEngine --> DispatcherCoordinator
                                  |                 |
                                  |                 +--> QuietPeriodPolicy
                                  |                 +--> DispatcherStateMachine
                                  |
                                  +--> CodexLaunchService ----> CodexExecutionPort (port)
                                  +--> CodexRecoveryService --> CodexExecutionPort (query)
                                                    |
                                                    +--> CodexLifecycleService

AgentSessionRegistry --> agent_session
DispatcherDataRetentionService --> consumed dispatcher_event metadata
DispatcherHealthIndicator --> non-sensitive lane/event aggregates
```

New triggers implement `TriggerSource` and receive their own `trigger_cursor.source_key`. The
coordinator does not know whether an event came from LINE, Email, Calendar, GitHub, Jenkins or a
log monitor.

## Data model

| Table | Purpose | Important invariant |
| --- | --- | --- |
| `agent_session` | Named `development-main` / `開發主要對話` binding | Rebinding forbidden while a run may be active |
| `trigger_cursor` | Opaque cursor per source | Locked while events and cursor are committed |
| `dispatcher_event` | Durable queue item and payload | Unique `(source_key, source_event_id)` deduplicates replay |
| `dispatcher_lane` | Singleton coordination row | Row lock serializes state transitions |
| `dispatcher_run` | Attempt/audit/fencing/heartbeat | Partial unique index allows one active run globally |
| `dispatcher_run_event` | Ordered batch membership | A source event belongs once at a position in a run |

PostgreSQL is the lock and queue. Redis locks, file locks, JVM semaphores and `AtomicBoolean` are
not correctness mechanisms. The scheduler's `AtomicBoolean` only avoids redundant re-entry inside
one process; DB constraints and fencing remain authoritative across processes and restarts.

## Race and duplicate handling

- 100 events arriving in seconds are deduplicated by source event id and become one ordered batch.
- Cursor advance and event insert are one transaction; a crash cannot advance past an unpersisted event.
- Lane and run rows are locked in the same order: lane first, then run/event rows.
- External calls never occur while a database row lock is held.
- Launch is marked durably before `startCodex`; concurrent launchers produce one external call.
- A callback carries `runId + fencingToken`; stale callbacks cannot settle a newer run.
- Finish is idempotent. A duplicate terminal callback does not consume/release events twice.
- Successful events become `CONSUMED`; failed confirmed runs release the batch to `PENDING` with backoff.
- An uncertain launch or stale heartbeat becomes `OUTCOME_UNKNOWN`, retaining the global active-run lock.

The merge strategy is batching, not summarization. Fifty messages create one run with fifty ordered
event records and their metadata. Dispatcher never performs AI interpretation.

## Quiet period and restart lifecycle

The eligibility time is the earlier of:

- five minutes after the later of the newest pending event and previous run completion; or
- thirty minutes after the oldest pending event.

Failure retry backoff may move eligibility later. On startup the first engine tick checks durable
active state before claiming anything:

- `STARTING` without a handoff marker: launch the already committed run.
- recent `STARTING` or healthy `RUNNING`: leave it active.
- stale/uncertain execution: enter `RECOVERING` and query the adapter outside the transaction.
- confirmed running: restore heartbeat state.
- confirmed terminal/not found: settle through the idempotent lifecycle service.
- repeated unknown: `PAUSED`, never start another run.

## Heartbeat contract

The adapter should call `CodexLifecycleService.heartbeat(runId, fencingToken)` before the configured
deadline. It should call `onCodexFinish` exactly once when possible; duplicates are safe. A missed
heartbeat does not automatically kill or replace Codex. Recovery first queries the external system.

## Enablement checklist

1. Start the dedicated DB:
   `docker compose -f internal/ai-dispatcher/compose.yaml up -d`.
2. Configure the main application feed with a random token of at least 32 characters and one
   `DEVELOPMENT_FEED_WORKSPACE_ID` / `DEVELOPMENT_FEED_ACTOR_ID`.
3. Configure Dispatcher with the same token and main base URL.
4. Supply a `CodexExecutionPort` adapter. This repository deliberately does not control Codex Desktop.
5. Bind the adapter's real session id through `AgentSessionRegistry.bindDevelopmentSession`; the
   durable display name remains `開發主要對話`.
6. Keep `AI_DISPATCHER_ENABLED=false` until steps 1-5 are verified, then enable the scheduler.
7. Confirm `/actuator/health` contains the `aiDispatcher` component and no `PAUSED` state.

The two services should use independently generated DB credentials and deployment units. Never use
the main application's datasource credential in Dispatcher.

## Recovery runbook

- `SESSION_NOT_READY`: bind/verify the configured development session. If no active run exists the
  registry returns the lane to `WAITING` or `IDLE`.
- `RECOVERING`: wait for the bounded query/backoff cycle; do not manually clear DB rows.
- `CODEX_OUTCOME_UNCONFIRMED`: inspect Codex by run/session correlation. Report the proven outcome
  through `onCodexFinish`; do not delete the active run or release events manually.
- feed unavailable: fix token/base URL/main availability. No new run starts while a poll fails.
- Dispatcher DB unavailable: repair only the isolated DB; main LINE handling remains available.

Consumed payload metadata is purged after the configured retention window. Pending/claimed payloads
are never purged. The main feed replay horizon is bounded by main LINE-history retention (90 days by
default), so operational outage recovery must occur inside that window.

## Required integration coverage

Implemented tests cover:

- 100-event concurrency and quiet-period/max-wait scheduling.
- two coordinators contending for one batch/run.
- duplicate feed polling and atomic cursor progression.
- concurrent launch handoff with one external invocation.
- external launch/status calls holding no DB lock.
- success, failure, duplicate finish, stale fencing and heartbeat extension.
- restart recovery, unknown retry/backoff and fail-closed pause.
- main feed token/method/cursor/pagination and inbound-only filtering.
- real PostgreSQL RLS under a `NOBYPASSRLS` runtime role.
- build/source isolation and consumed-payload retention.

Before changing locking, migrations, callbacks, security, cursor format or retention, run both:

```powershell
.\mvnw.cmd -f internal\ai-dispatcher\pom.xml test
.\mvnw.cmd test
```

## Completed roadmap

1. Standalone build and database boundary.
2. Durable schema and explicit state machine.
3. Atomic trigger ingestion and duplicate protection.
4. Transactional batch claim and global single-run invariant.
5. Black-box launch handoff and session registry.
6. Finish, heartbeat, fencing and retry lifecycle.
7. Restart/stale execution recovery.
8. Scheduler, health and startup reconciliation.
9. Generic trigger port and main-feed pull adapter.
10. Main application's authenticated, actor-scoped, read-only feed.
11. Payload retention and final cross-build regression.
