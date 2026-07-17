# Codex CLI execution adapter

`CliCodexExecutionAdapter` is the isolated Dispatcher's concrete `CodexExecutionPort`. It is
disabled by default, does not use PowerShell or `cmd.exe`, and never starts merely because the
Spring Boot service is healthy.

The implementation follows OpenAI's documented non-interactive contract:

- `codex exec --json` emits JSON Lines events including `thread.started`, `turn.started`,
  `turn.completed`, `turn.failed` and token usage.
- `codex exec resume <SESSION_ID>` resumes one explicit technical session.
- `-` reads the full prompt from stdin.
- `--cd`, `--sandbox workspace-write` and `--ask-for-approval never` make the automation boundary
  explicit.

References: [Codex non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode) and
[Codex CLI reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli#cli-codex-exec).

## Security boundary

The adapter builds one fixed argument list. It forbids `--last`, `danger-full-access`, shell
wrappers and event-derived arguments. The rendered event envelope is UTF-8 stdin and the stream is
closed to signal EOF.

The child process receives an environment allowlist instead of the full Dispatcher environment.
Dispatcher DB credentials, main-feed tokens and session-binding administration tokens are not
inherited. A second Codex `shell_environment_policy.include_only` setting prevents agent-created
shell commands from inheriting Codex authentication variables.

The configured repository and executable must be absolute existing paths. The repository must be
a Git working tree. Both are resolved to real paths when the adapter bean is created, so an armed
service fails during startup instead of on the first LINE event.

Use a dedicated Codex session and preferably a dedicated Git worktree/branch for unattended work.
The Dispatcher prevents its own concurrent runs; it cannot stop a human or another Codex process
from editing the same working tree outside its control.

## Configuration

Required when the adapter is enabled:

```text
AI_DISPATCHER_CODEX_CLI_ENABLED=true
AI_DISPATCHER_CODEX_CLI_EXECUTABLE=C:\absolute\path\to\codex.exe
AI_DISPATCHER_CODEX_REPOSITORY=D:\absolute\path\to\git-working-tree
```

Operational limits have conservative defaults:

```text
AI_DISPATCHER_CODEX_CLI_HEARTBEAT_INTERVAL=30s
AI_DISPATCHER_CODEX_CLI_NO_PROGRESS_TIMEOUT=15m
AI_DISPATCHER_CODEX_CLI_MAXIMUM_RUNTIME=2h
AI_DISPATCHER_CODEX_CLI_MAXIMUM_JSON_LINE_LENGTH=1048576
AI_DISPATCHER_CODEX_CLI_MAXIMUM_STDERR_CHARACTERS=16384
AI_DISPATCHER_CODEX_CLI_MAXIMUM_PROMPT_CHARACTERS=1048576
```

The adapter flag only creates the execution port. New work starts only when all of these independent
conditions are also satisfied:

- `AI_DISPATCHER_ENABLED=true`
- the main feed is enabled and authenticated on both applications
- `development-main` is bound and `READY`
- pending events have reached their quiet/max-wait eligibility time
- no active or uncertain run exists

The standard `scripts/dev-start.ps1` deliberately sets all automation flags to false. This is a
safety feature for ordinary local development. Run an explicitly configured standalone Dispatcher
process for the initial rollout; do not silently remove the disarm behavior from routine scripts.

## Process lifecycle

`startCodex()` returns only after the OS process exists, its random adapter execution handle is
durably inserted, the in-memory handle is registered, and supervision has been scheduled. Prompt
writing, stdout/stderr draining, waiting, heartbeat and terminal callback happen in background
threads.

The supervisor drains stdout and stderr concurrently to avoid pipe deadlock. JSONL lines, prompt
size and retained stderr diagnostics are bounded. Raw prompts and stdout are not written to normal
application logs.

Terminal classification is deliberately conservative:

| Evidence | Result |
| --- | --- |
| Matching `thread.started`, `turn.completed`, exit code `0` | `SUCCEEDED`; consume claimed events |
| Complete JSONL proves the process exited before any turn | `FAILED`; release with retry backoff |
| `turn.failed` or non-zero exit after `turn.started` | `OUTCOME_UNKNOWN`; pause and retain claimed events |
| malformed/truncated/oversized JSONL or session mismatch | `OUTCOME_UNKNOWN`; pause and retain claimed events |
| process alive but no progress or over maximum runtime | pause and retain claimed events; do not kill process |

`codex_execution_attempt` records the adapter handle, PID, timestamps, terminal event, exit code,
token usage, diagnostic code and bounded stderr excerpt. It never stores the prompt.

## Restart and shutdown

An in-process Java supervisor cannot reattach stdout, stderr or an exit code after the Dispatcher
JVM exits. On restart:

- a durably recorded terminal result can be reconciled;
- an audit still marked `RUNNING` without an in-memory process attachment returns `UNKNOWN`;
- the lane remains active and eventually pauses; it is never converted to `NOT_FOUND` and never
  starts a replacement process.

The adapter's shutdown hook does not destroy an attached Codex process. Nevertheless, losing the
pipe supervisor can affect that child and loses automatic terminal observation. Avoid intentionally
restarting Dispatcher while a run is active. Repository scripts refuse ordinary stop or restart
whenever durable `active_run_id` is present, including a fail-closed `PAUSED` run. They also refuse
to stop a running Dispatcher when its DB state cannot be inspected. Use `-SkipDispatcher` when only
the main application must restart.

If the lane is `PAUSED` with `active_run_id` present, inspect the Codex session and repository. Once
the outcome is proven, settle it through the idempotent lifecycle path. Never clear the DB row,
release claimed events or rebind the session manually.

## Test strategy

The normal Maven suite uses a fake managed process and real PostgreSQL. It verifies immediate
handoff, stdin, success and token audit, safe pre-turn failure, uncertain post-turn failure,
no-progress pause, environment filtering, and restart observations. It never invokes Codex and
does not consume tokens:

```powershell
.\mvnw.cmd -f internal\ai-dispatcher\pom.xml test
```

A real Codex smoke test is intentionally manual and opt-in because it consumes tokens and can edit
the configured repository. Perform it only with the scheduler otherwise disabled, a disposable
worktree/branch, a dedicated bound session, a known harmless event, clean Git state and an operator
watching the first run. Verify the exact session ID, one process only, terminal audit, Git diff and
lane/event settlement before enabling unattended scheduling.
