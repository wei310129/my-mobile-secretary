# AI Dispatcher architecture boundary

## Ownership

AI Dispatcher is a repository-local internal project, not a module or package of My Mobile
Secretary. Sharing a Git repository is a source-control choice only; it does not grant permission
to share runtime code, database tables or deployment lifecycle.

## Dependency direction

```text
My Mobile Secretary                  AI Dispatcher
-------------------                  -------------
LINE message storage                 Own durable event cursor
Read-only versioned event feed  <--- Pull adapter
No Dispatcher client                 Own state machine
No Dispatcher dependency             Own PostgreSQL database
Independent availability             Codex black-box port
```

The dependency is deliberately one-way: Dispatcher may pull a versioned, read-only feed from the
main application. The main application never sends a synchronous request to Dispatcher and never
waits for Dispatcher availability. A Dispatcher outage therefore cannot fail or slow a LINE
webhook.

## Prohibited coupling

- Java dependencies on the main application artifact.
- Imports from the main application's package namespace.
- JPA entities, repositories, DTO jars or utility modules shared with the main application.
- Dispatcher tables in the main application's database or Flyway history.
- Foreign keys across databases.
- Direct writes to the main application's database.
- Main application startup, health or transaction success depending on Dispatcher.
- Redis locks, files or in-memory flags shared between the two applications.

Using the same protocol field names does not justify a shared DTO library. Each side owns its own
representation and compatibility tests the serialized contract.

## Integration contract

The future trigger feed must be versioned and cursor based. Its minimum behavior is:

```text
GET /internal/integration/v1/development-events?after=<opaque-cursor>&limit=<n>

200 OK
{
  "events": [
    {
      "eventId": "stable-source-id",
      "type": "line.conversation.recorded",
      "occurredAt": "instant",
      "subjectRef": "opaque-reference",
      "schemaVersion": 1,
      "metadata": {"messageType": "TEXT", "text": "development request"}
    }
  ],
  "nextCursor": "opaque-cursor",
  "hasMore": false
}
```

The implemented contract has these requirements:

- Results are ordered and replayable.
- `eventId` is stable so Dispatcher can deduplicate replays.
- Cursor values are opaque to Dispatcher domain code.
- Polling the feed has no side effects in the main application.
- Feed unavailability only delays Dispatcher work.
- Dispatcher persists an event before advancing its local cursor.

The quiet-period batch is the merge boundary. Fifty feed events become one Codex run containing
fifty ordered event references; Dispatcher does not summarize or semantically merge user text.
This avoids data loss and keeps AI reasoning outside Dispatcher.

## Database boundary

The local Compose project creates a dedicated PostgreSQL server/database on port `5433`:

```text
main application: jdbc:postgresql://localhost:5432/mymobilesecretary
AI Dispatcher:    jdbc:postgresql://localhost:5433/ai_dispatcher
```

Production may use the same managed PostgreSQL cluster only if it still uses a separate database,
credential, Flyway history and backup/restore policy. Dispatcher credentials must have no access
to main application tables.

## Failure boundary

- Dispatcher database unavailable: only Dispatcher is unhealthy.
- Dispatcher process stopped: LINE handling continues normally; the local cursor does not advance.
- Main application unavailable: Dispatcher waits and retries feed polling without launching Codex.
- Codex status uncertain: Dispatcher fails closed and enters `PAUSED`; main application is unaffected.
- Dispatcher schema migration failure: main application migrations and startup remain independent.

## Build boundary

The root `pom.xml` must not list this application as a module. Dispatcher is built explicitly:

```powershell
.\mvnw.cmd -f internal/ai-dispatcher/pom.xml test
```

The Maven Enforcer rule rejects a dependency on the main application artifact. A source-level test
also rejects imports from the main application's package namespace.
