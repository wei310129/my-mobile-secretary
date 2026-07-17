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

## Local commands

From the repository root:

```powershell
docker compose -f internal/ai-dispatcher/compose.yaml up -d
.\mvnw.cmd -f internal/ai-dispatcher/pom.xml test
.\mvnw.cmd -f internal/ai-dispatcher/pom.xml spring-boot:run
```

The application is disabled by default. Set `AI_DISPATCHER_ENABLED=true` only after its durable
state machine and recovery behavior have been configured.

## Removal

Stop its Compose project, remove its dedicated volume if desired, then delete this directory.
No root Maven, main application source, main Flyway migration or main Compose change is required.
