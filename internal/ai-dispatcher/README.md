# AI Dispatcher

AI Dispatcher is an internal, standalone application stored in the same Git repository as
My Mobile Secretary. It controls when a development agent may start. It does not perform AI
reasoning and it is not part of the main application's runtime.

## Isolation guarantees

- This directory has its own Maven build, source tree, dependencies, configuration and tests.
- It is not a module of the repository root Maven build.
- It has its own PostgreSQL database and Flyway history.
- It must not import classes from `com.aproject.aidriven.mymobilesecretary`.
- The main application must never call the Dispatcher or wait for it.
- Cross-application communication is limited to versioned HTTP contracts and opaque identifiers.
- Deleting this directory must not change how the main application compiles or runs.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the dependency and failure boundaries.
See [DESIGN.md](DESIGN.md) for the state machine, lifecycle, data model, race analysis and
operations checklist.
See [SESSION_BINDING.md](SESSION_BINDING.md) for securely binding the durable
`development-main` lane to the Codex thread displayed as `開發主要對話`.

## Local commands

The repository-level development scripts manage the main application and this isolated service
together. Dispatcher startup is best-effort: its failure is reported but never fails the main
application startup.

From the repository root:

```powershell
.\scripts\dev-start.ps1                 # starts both applications and both Compose projects
.\scripts\dev-status.ps1                # Dispatcher is reported but remains non-blocking
.\scripts\dev-status.ps1 -RequireDispatcher
.\scripts\dev-restart.ps1               # restarts both Java applications
.\scripts\dev-stop.ps1                  # stops both Java applications, retains both databases
.\scripts\dev-stop.ps1 -Docker          # also stops both Compose projects
```

Use `-SkipDispatcher` with `dev-start.ps1` or `dev-restart.ps1` when working only on the main
application. If Windows execution policy blocks direct `.ps1` invocation, call the script with
`powershell -NoProfile -ExecutionPolicy Bypass -File`. The equivalent standalone commands remain
available:

```powershell
docker compose -f internal/ai-dispatcher/compose.yaml up -d
.\mvnw.cmd -f internal/ai-dispatcher/pom.xml test
.\mvnw.cmd -f internal/ai-dispatcher/pom.xml spring-boot:run
```

The application is disabled by default. Set `AI_DISPATCHER_ENABLED=true` only after its durable
state machine, the named Codex session and a concrete Codex execution adapter have been configured.
Starting the service with the default configuration therefore proves that its database, migrations
and HTTP health endpoint are ready; it does not start Codex. The main-application feed is also
disabled independently. Its minimum settings are:

```text
AI_DISPATCHER_MAIN_FEED_ENABLED=true
AI_DISPATCHER_MAIN_FEED_BASE_URL=http://localhost:8080
AI_DISPATCHER_MAIN_FEED_TOKEN=<dedicated-read-only-token>
```

The feed uses bounded connect/read timeouts. A failed poll prevents a new Codex run from starting,
but never changes main-application state and never blocks recovery of an existing run.

Consumed event payloads are cleared after 90 days by default while event/run audit identities are
retained. Configure `AI_DISPATCHER_CONSUMED_PAYLOAD_RETENTION` to match the main application's
LINE-history replay window. Pending and claimed payloads are never purged.

The session-binding management API is independently disabled by default. Enabling it does not
enable dispatching or launch Codex. It requires a separate administrator token and is intended for
explicit setup, verification and controlled rebinding only.

## Removal

Stop its Compose project, remove its dedicated volume if desired, then delete this directory.
No root Maven, main application source, main Flyway migration or main Compose change is required.
