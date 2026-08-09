# Codex registered-worktree Git fetch execution-policy follow-up

> Status: `BLOCKED_HOST_POLICY_OWNER_HANDOFF_READY`
>
> Repository owner: tooling documentation only
>
> Implementation owner: Codex host execution-policy / sandbox owner
>
> Base: `origin/main` at `0f86167f7cc9675e507a03e1933351472441d3c5`

## Outcome

Allow one low-risk operation from a registered repository worktree:

```text
git fetch origin main
```

The operation may update only the selected repository's necessary Git common-directory metadata,
including `FETCH_HEAD`, the configured remote-tracking ref and fetched objects. It must not grant
write access to tracked or untracked worktree files, broaden shell authority, or authorize any other
Git operation.

The observed blocker is outside repository tooling. A linked worktree stores `FETCH_HEAD` under the
primary clone's Git common directory. The sandbox cannot write that path, while the current host
auto-review policy rejects every `require_escalated` request even when the command and scoped
`git fetch` prefix have already been approved.

## Ownership decision

This requires a host execution-policy change, not a repository wrapper or a broader repo-local
`.codex/rules` entry.

OpenAI's rules documentation defines `prefix_rule.pattern` as an argv **prefix**, permits trailing
arguments, and applies the most restrictive matching decision. It does not expose predicates for a
canonical Git common directory, registered-worktree membership, configured remote URL, exact
end-of-argv matching, or a per-command filesystem write set. The sandbox and approval policy are
also separate enforcement layers. See:

- <https://learn.chatgpt.com/docs/agent-configuration/rules>
- <https://learn.chatgpt.com/docs/agent-approvals-security>

Consequently, adding `pattern = ["git", "fetch", "origin", "main"]` would be too broad: it would also
match trailing flags or refspecs, and it could not independently prove the repository, worktree or
remote identity. A PowerShell wrapper, alternate shell, `git -C`, or indirect command would evade
rather than repair the host boundary and is forbidden.

## Host policy contract

The host implementation must evaluate the original executable, argv, environment and canonical
working directory before execution. It must fail closed unless every condition below is true:

1. The executable is the host-approved native `git`/`git.exe`; no shell, alias, wrapper, shim or
   alternate `GIT_EXEC_PATH` is involved.
2. The tokenized argv is exactly `fetch`, `origin`, `main`, with no leading global option and no
   fourth argument. The policy does not normalize, reorder or discard tokens.
3. The canonical current directory is either the registered primary worktree or a worktree present
   in that clone's authoritative `git worktree list --porcelain` registration. A merely nested
   directory, copied `.git` file or same-looking path is insufficient.
4. The canonical Git directory and common directory match the repository identity stored by the
   trusted project registration. `GIT_DIR`, `GIT_WORK_TREE`, `--git-dir`, `--work-tree` and `-C`
   overrides are absent.
5. `origin` is the registered remote name, its effective fetch URL equals the immutable URL captured
   by trusted project registration, and the requested branch is exactly the registered `main` ref.
   A mutable remote name alone is not authority.
6. The effective configured fetch mapping for this operation is the registered
   `refs/heads/main` to `refs/remotes/origin/main` mapping. Additional positive or negative refspecs,
   tag expansion, submodule recursion and remote groups are rejected.
7. Configuration and environment cannot redirect execution. Reject command-line `-c`/`--config-env`,
   `--upload-pack`/`--exec`, custom remote-helper schemes, `remote.origin.uploadpack`, URL rewrite
   rules that alter the registered URL, custom hooks path, and Git environment variables that can
   change repository, transport, helper, config or executable resolution.
8. Network access is limited to the already registered remote endpoint and ordinary read-only Git
   upload-pack negotiation. No credential, URL or repository identity is written to audit output.
9. The elevated filesystem grant is capability-scoped to the canonical Git common directory and
   permits only fetch-created metadata: `FETCH_HEAD`, the exact `origin/main` remote-tracking ref and
   its lock/reflog, fetched object/quarantine/pack metadata, and Git's required atomic lock/rename
   files. Worktree paths, index, local heads, config, hooks and unrelated refs remain read-only.
10. The audit receipt records opaque repository/worktree identity, caller identity, normalized
    operation `GIT_FETCH_REGISTERED_ORIGIN_MAIN`, decision, timestamp and changed metadata classes;
    it excludes absolute paths, remote URL, account, hostname, credentials and fetched content.

The policy must explicitly deny `pull`, `merge`, `rebase`, `checkout`, `switch`, `reset`, `restore`,
`stash`, `clean`, all pushes, force/prune operations, config/hook mutation and arbitrary shell. A
successful fetch conveys no authorization to integrate, checkout or modify fetched content.

## Policy-owner implementation prompt

```text
Implement a host-side Codex execution-policy capability named
GIT_FETCH_REGISTERED_ORIGIN_MAIN. Do not implement a repository wrapper.

Accept only the native tokenized command [git, fetch, origin, main] from a trusted,
registered primary or linked worktree. Before execution, bind the canonical worktree,
Git dir and common dir to the host's immutable project registration; bind remote name
origin, its effective URL and the exact main fetch mapping to the registered remote
identity. Reject all argv tails, global options, config/env overrides, upload-pack or
remote-helper overrides, shell/wrapper invocation, URL rewrites, extra refspecs, tags,
submodules, prune and force.

Execute with a command-scoped filesystem grant that permits only fetch-required Git
common-dir metadata (FETCH_HEAD, exact origin/main tracking ref and reflog/locks,
objects/quarantine/pack metadata). Keep the entire worktree, index, local refs, config,
hooks and all unrelated repositories read-only. Network access is limited to the
registered remote endpoint and read-only upload-pack negotiation.

Emit a privacy-safe caller/repository/worktree audit receipt. This capability must not
authorize pull, merge, rebase, checkout/switch, reset, restore, stash, clean, push,
config or hooks. The existing broader require_escalated prohibition may remain for all
other operations, but must not override this exact capability after all fences pass.
Run the acceptance matrix in this document and fail closed on unresolved identity,
configuration, environment, tokenization or write-scope state.
```

## Acceptance matrix

Use disposable local bare remotes and registered primary/linked worktrees. Capture the worktree
content/index/local-head/config/hook hashes and Git common-directory inventory before each case.
Denied cases must execute no Git subprocess and change no file. The allowed case must change only
the declared metadata classes.

| Case | Invocation / condition | Expected |
| --- | --- | --- |
| A1 | Registered linked worktree, exact `git fetch origin main`, exact registered URL/ref mapping | ALLOW; exit 0; `FETCH_HEAD` and required origin/main/object metadata only |
| A2 | Immediate identical rerun with nothing new | ALLOW; exit 0; no worktree/index/local-head/config/hook mutation |
| D1 | Same argv from another repository | DENY before exec |
| D2 | Same argv from an unregistered/copied worktree | DENY before exec |
| D3 | `origin` URL differs from immutable registered URL | DENY before exec |
| D4 | `git fetch upstream main` or any nonconfigured remote | DENY before exec |
| D5 | `git fetch origin other`, extra refspec, negative refspec or remote group | DENY before exec |
| D6 | Any leading/trailing `-c`, `--config-env`, `--git-dir`, `--work-tree` or `-C` | DENY before exec |
| D7 | `--upload-pack`, `--exec`, custom remote helper, URL rewrite or transport override | DENY before exec |
| D8 | `--force`, `+refspec`, `--prune`, `--prune-tags`, `--tags` or `--recurse-submodules` | DENY before exec |
| D9 | Shell separator, pipe, redirection, substitution, variable expansion or wrapper | DENY before exec |
| D10 | Git environment redirects repository/config/helper/executable/transport | DENY before exec |
| D11 | `git pull`, `merge`, `rebase`, `checkout`, `switch`, `reset`, `restore`, `stash` or `clean` | DENY; capability not applicable |
| D12 | Any `git push`, including non-force push | DENY; capability not applicable |
| D13 | Fetch attempts worktree, index, local-head, config, hook or unrelated-ref write | Kill/fail operation; audit scope violation; preserve prior state |
| D14 | Repo/worktree/remote/ref/config identity cannot be resolved exactly | DENY before exec |
| D15 | Caller audit identity absent or mismatched | DENY before exec |

Acceptance requires the entire matrix on Windows primary and linked worktrees, including the
original failure shape where `.git/worktrees/<id>/FETCH_HEAD` is outside the linked worktree's
ordinary sandbox write root. It also requires a regression proving existing forbidden project Git
rules remain the strictest decision for destructive commands.

## Repository scope and gates

- Repository changes: this plan, the active-plan index and `docs/tooling-backlog.md` only.
- Production code, Calendar/Conversation, Flyway, `.codex/rules`, trigger state and runtime: unchanged.
- External product, Calendar, Docker and provider mutation: zero.
- Repository validation: Markdown/path checks, clean scoped status, merge-policy documentation gate.
- Host acceptance: pending the policy owner implementation and the matrix above; repository CI
  cannot truthfully mark this host behavior fixed.

## Context 壓縮提醒點

- 本 handoff、ownership 判定與驗收矩陣完成並提交後，是適合壓縮 context 的時機。續作摘要必須保留：
  host policy ownership、禁止 repo wrapper／寬鬆 prefix rule、不變的 exact argv/repo/worktree/remote/write-set
  fences、修改文件、repo 驗證結果、PR/head，以及 host acceptance 尚未執行。
- host owner 完成 capability 並跑完全矩陣後，才可把本計畫標為完成；若任一 deny case 執行了 Git
  subprocess、任一 worktree/index/config/hook 發生 mutation，或 caller/repo identity 無法精確稽核，不得壓縮
  成 PASS 或移入 completed。

