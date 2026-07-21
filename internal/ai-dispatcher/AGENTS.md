# AI Dispatcher guidance

Root `AGENTS.md` rules still apply. This directory is an isolated internal application with its own lifecycle.

## Hard boundaries

- Keep this as a standalone Java 21 / Spring Boot Maven build. Do not add it to the root Maven reactor or depend on the main application artifact.
- Do not import `com.aproject.aidriven.mymobilesecretary` classes. Communicate only through versioned HTTP contracts and opaque identifiers.
- Keep its PostgreSQL database, Flyway history, Compose project, configuration, and tests separate from the main application.
- The main application must never call, wait for, or require the dispatcher. Dispatcher failure must not change main-application compilation, startup, or runtime behavior.
- Keep scheduling, state transitions, quiet-period rules, recovery, retention, and process lifecycle deterministic and testable in Java. Inject `Clock` for time behavior.
- Keep automation and the Codex CLI adapter disabled by default. Do not enable, arm, bind, unbind, or rotate credentials without explicit user authorization.

## Local routing

| Change | Read |
| --- | --- |
| Dependency, isolation, HTTP, database, or failure boundary | `ARCHITECTURE.md` |
| State machine, race handling, recovery, retention, or integration coverage | Relevant section of `DESIGN.md` |
| Session binding or management API | `SESSION_BINDING.md` |
| CLI adapter, security, process lifecycle, or smoke testing | `CODEX_CLI.md` |
| Operator commands and safe defaults | Relevant section of `README.md` |

Read only the relevant heading; do not load every dispatcher document by default.

## Build and validation

- Run dispatcher tests with `.\mvnw.cmd -f internal\ai-dispatcher\pom.xml test` from the repository root, or use a narrower `-Dtest=...` selection while iterating.
- Do not use the root Maven lifecycle to validate dispatcher code.
- Prefer isolated unit tests for policies and Testcontainers integration tests for Flyway, persistence, lifecycle, and concurrency boundaries.
- Do not start Compose or application processes for a documentation-only change.
