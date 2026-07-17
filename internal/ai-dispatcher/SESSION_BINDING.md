# Codex session binding

The Dispatcher owns one durable logical session:

- key: `development-main`
- display name: `開發主要對話`
- provider: `CODEX_DESKTOP`
- external session id: the opaque technical Codex thread/session ID

The display name is for operators. It is never used to resume Codex. A future
`CodexExecutionPort` adapter must start work with the bound technical ID and must correlate every
heartbeat, status query and completion with the Dispatcher `runId` and fencing token.

## Obtain the technical ID

Codex's stable non-interactive interface is `codex exec`. With `--json`, output is JSON Lines and
the `thread.started` event contains `thread_id`. A specific saved session can subsequently be
continued with `codex exec resume <SESSION_ID>`.

For a new dedicated development thread, run a harmless bootstrap task from the repository and
capture the first `thread.started.thread_id`:

```powershell
codex exec --json "Inspect this repository without changing files and wait for future tasks."
```

Rename that chat to `開發主要對話` in Codex Desktop for operator visibility. If binding an existing
Desktop chat, use its technical thread ID, not its title. A Desktop deep link has the form
`codex://threads/<TECHNICAL_THREAD_ID>`.

Do not use `--last` in the execution adapter. Another human or automation run could change what
"last" means between dispatches. Always resume the exact ID stored in the run snapshot.

Reference: [OpenAI Codex non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode).

## Enable the management API

The API is disabled by default and does not share the main-feed token. Configure a random token of
at least 32 characters:

```text
AI_DISPATCHER_SESSION_BINDING_API_ENABLED=true
AI_DISPATCHER_SESSION_BINDING_ADMIN_TOKEN=<dedicated-random-admin-token>
```

Keep `AI_DISPATCHER_ENABLED=false` while first configuring the binding. Restart only the isolated
Dispatcher after changing these environment variables.

The single resource is:

```text
/internal/v1/session-bindings/development-main
```

Every request requires `Authorization: Bearer <token>`. Mutations additionally require a non-empty
`X-Dispatcher-Actor` value. Responses use `Cache-Control: no-store`; the `ETag` and response
`version` represent the current optimistic-lock version.

## Read and bind

First read the current version:

```powershell
$baseUrl = "http://localhost:8091"
$token = $env:AI_DISPATCHER_SESSION_BINDING_ADMIN_TOKEN
$headers = @{ Authorization = "Bearer $token" }
$current = Invoke-RestMethod `
  -Uri "$baseUrl/internal/v1/session-bindings/development-main" `
  -Headers $headers
$current
```

Bind the technical ID using the version just read:

```powershell
$headers["X-Dispatcher-Actor"] = $env:USERNAME
$body = @{
  externalSessionId = "0199a213-81c0-7800-8aa1-bbab2a035a53"
  expectedVersion = $current.version
  reason = "Initial development-main binding"
} | ConvertTo-Json

$bound = Invoke-RestMethod `
  -Method Put `
  -Uri "$baseUrl/internal/v1/session-bindings/development-main" `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $body
$bound
```

Sending the same ID again is an audited re-verification and advances the version. Sending a
different ID is an audited rebind. HTTP `409 Conflict` means the supplied version is stale or a run
may still be active; read again and investigate instead of retrying blindly.

## Unbind

Unbinding is fail-closed: it pauses the lane as `SESSION_NOT_READY`. It is rejected while any run
may still be active, including `OUTCOME_UNKNOWN`.

```powershell
$version = $bound.version
Invoke-RestMethod `
  -Method Delete `
  -Uri "$baseUrl/internal/v1/session-bindings/development-main?expectedVersion=$version&reason=Thread%20retired" `
  -Headers $headers
```

Binding a valid session resumes only a `SESSION_NOT_READY` pause. It never clears an operator pause
or an uncertain execution.

## Runtime guarantees

- PostgreSQL lane/session row locks serialize binding and dispatch decisions across processes.
- `expectedVersion` prevents a stale operator request from overwriting a newer binding.
- Every successful mutation is recorded in `agent_session_binding_audit`.
- A claimed run stores an immutable session snapshot. Later rebinding cannot redirect that run's
  launch or recovery query.
- `/actuator/health` reports session status, provider, binding version and verification age, but
  never the technical session ID.
- The API token is configuration-only. It is not stored in the database or returned by an endpoint.

After the binding is `READY`, the health component is healthy, and a concrete execution adapter has
been integration-tested, set `AI_DISPATCHER_ENABLED=true`. A binding alone cannot launch Codex;
the adapter remains a separate implementation and deployment concern.
