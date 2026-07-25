# Drydock as an MCP server for the sessions it hosts

Drydock owns state that a `claude` session running inside it cannot reach:
line-anchored review annotations, the worktree graph, the cross-repo
registry, and the session/tab graph. This design exposes that state to the
sessions Drydock spawns, over an MCP server the app hosts.

The direction matters. Drydock is the **server**; the hosted `claude`
sessions are the clients. Drydock as an MCP *client* is not part of this
design and has no identified benefit.

> **Revision note.** This design was adversarially reviewed before
> implementation, and revised. Section "What the adversarial review
> changed" at the end records each finding and its resolution. Two claims
> in the first draft were simply false and are corrected in place: the
> per-session isolation property, and "create-only means the worst outcome
> is clutter".

## Why this is not net-new surface

The review loop already exists as text injection. `ReviewView.sendToClaude`
builds a one-line prompt listing every `OPEN` annotation and submits it to
the live terminal as keystrokes via `TerminalBridge.sendPrompt`, marking
each thread `SENT`. `AnnotationStatus`'s documentation records that `FIXED`
was retired because "the app never claims a fix on Claude's behalf".

MCP does two things that path cannot: it lets the agent **re-read** the
annotations on demand mid-task rather than once at handoff, and it lets the
agent **write back** what it did. The existing keystroke handoff is also
mechanically fragile — `sendPrompt` silently no-ops when the surface is
disposed or closing, the payload must be a single line, and it races the
session being mid-turn behind a fixed 1.5-second delay.

**Discovery stays with the existing push path.** Nothing in this design
makes an agent spontaneously aware that annotations exist, and that is not
a gap to be fixed here: the Send button already tells the session, in the
conversation, that there are comments to address. MCP supplies the read-back
and write-back; the button supplies the trigger. Both remain.

## Scope

In scope:

- Review annotations: the agent reads open threads and reports what it did.
- Worktree and session creation: the agent creates worktrees and starts
  visible Drydock sessions in them, bounded (see "Bounding fan-out").
- Read-only workspace awareness: registered repos and the session list.

Out of scope, with reasons:

- **A general diff tool.** The agent can run `git diff`. But
  `review_comments` was unusable without diff context — an annotation
  anchored to a line of a diff the agent cannot see — so it now carries
  `base_branch` and a source excerpt. That is the minimum that makes the
  annotation actionable, not a diff tool.
- **Any destroy operation.** No worktree removal, no session close, no
  branch deletion, no merge. Note that a confirmed delete in this codebase
  can already override a worktree lock, so the existing guards are too soft
  to be the only barrier.
- **`ask_human` through a native dialog.** Redundant. The `claude` CLI
  already renders questions and permission prompts in its own TUI, and
  `SessionActivityWatcher` badges already tell the human *which* tab is
  waiting. A native dialog would duplicate that with lower fidelity — it
  cannot render the harness's own option lists or diff previews. A
  background-app notification is a hook concern, not an MCP tool.
- **External harnesses.** Only sessions Drydock spawns are *given* config.
  See "Trust boundary" for what that does and does not prevent.
- **Remote SSH sessions.** They get no MCP config. `claude` runs on the
  remote host and cannot reach `127.0.0.1` on the Mac without a reverse
  port-forward. This matches how worktrees, diffs, and Review are already
  unavailable for remotes — but unlike those, there is no disabled button
  to make it legible, so the remote session's launch banner says so.

## Trust boundary

**The boundary is the user account, not the session.** The first draft
claimed an agent "cannot address another session's repository even by
guessing". That is false, and the corrected position is:

Each session's `--mcp-config` file lives under Drydock's state directory,
owned by the user, mode `rw-------`. Every `claude` session Drydock spawns
runs as that same user, so any agent with a shell can list the directory
and read a *sibling* session's token, then call tools scoped to that
sibling's repository. The launch command is a shell string containing the
config path, so `ps` reveals the directory even without guessing.

This is accepted rather than fixed, because the escalation is negligible: an
agent with Bash can already `cd` to any repository on the machine and run
git directly. Confining the token to its process would need peer-credential
checks on the socket (`LOCAL_PEERPID`), which is not reachable from the JDK.

So the token's job is **attribution, not isolation**: it tells the server
which session a call came from, so tools resolve to the right repository and
annotation set without the agent naming a path. That is a correctness and
ergonomics property. Treat it as such, and do not build further security on
top of it.

What the token *does* prevent: a non-Drydock process that never had a token
cannot call tools, and a token stops working the moment its session ends.

## Architecture

A new `app.drydock.mcp` package with four components.

### `McpServer`

A localhost HTTP endpoint using the JDK's `HttpServer` — no new
dependency — bound to `127.0.0.1` on an ephemeral port, speaking the
streamable-HTTP MCP transport. Started once at app launch; closed in
`DrydockApplication.stop()` alongside the other services, inside the
existing per-service exception isolation.

stdio transport is ruled out: one GUI process serves N concurrent
sessions, and stdio is a single-client transport.

`jdk.httpserver` must be added to the jlink `--add-modules` list in
`RuntimeImageTask`. The full JDK toolchain resolves
`com.sun.net.httpserver` for `test` and `run`, so omitting it would break
only the *packaged* app, at first tool call.

### `McpSessionRegistry`

Mints an opaque token per `ManagedSessionId` when a session starts, revokes
it when the session ends, and holds each session's **grant**: whether it may
spawn (see "Bounding fan-out") and how much of its creation budget is left.

Tokens live only in memory: no terminal process survives an app restart, so
a persisted token could only ever be stale.

### `McpToolRouter`

Dispatches tool calls to existing services. It owns no domain logic; every
tool is a thin adapter. Tools return data already shaped for the UI, so the
agent and the human see the same thing by construction.

The router does **not** depend on JavaFX. It takes a narrow
`McpSessionContext` interface, implemented by the workspace in production
and by a fake in tests. This boundary exists primarily so every tool is
testable headlessly.

The context resolves a caller to its repository, worktree, base branch,
annotations, and worktree list, and owns worktree-directory naming — the
router never derives a path. That matters because the naming recipe needs a
`Repository` and the user's configured worktree base, neither of which the
router has or should have.

### `McpConfigWriter`

`ClaudeHookInstaller` already writes a settings file the spawned `claude`
inherits via `--settings`, and that is the model to copy — but not the file
to reuse. That settings file is written once at startup and shared by every
session, so it cannot carry a per-session token.

Instead, a per-session file passed with `claude --mcp-config <file>`, holding
an MCP server entry pointing at `http://127.0.0.1:<port>/mcp` with the
session token in a header. Created `rw-------`, since it holds a token, and
deleted when the session ends.

`--mcp-config` is gated on a detected capability, exactly as `--settings` is
gated on `ClaudeCapabilities.supportsSettings`: a `claude` without the flag
launches without Drydock tools rather than failing to launch.
`--strict-mcp-config` is deliberately not passed — it would suppress the
user's own MCP servers, and Drydock's tools are an addition to their setup,
not a replacement.

### Threading

The HTTP handler runs on its own executor and never touches the FX thread
directly, per the repository's async rules.

- Tools needing FX-owned state hop via `Platform.runLater` into a
  `CompletableFuture`, and the handler awaits it **with a timeout**. A
  wedged FX thread must fail the tool call, not hold the HTTP connection
  open.
- Tools hitting git go straight to the service executors, which are already
  off-thread.

## Protocol

JSON-RPC 2.0 over `POST /mcp`.

- **Requests** (carrying an `id`): `initialize`, `ping`, `tools/list`,
  `tools/call`. Any other method gets `-32601`.
- **Notifications** (no `id`) are accepted and ignored, answered with `204`
  and no body. `claude` sends `notifications/initialized` immediately after
  `initialize`; answering a notification with an error object is a protocol
  violation and risks the handshake never completing — which would make the
  entire feature dead code behind a green test suite.
- A tool that fails returns a `200` JSON-RPC **result** with `isError: true`
  and the message as text content. A tool failure is not a transport
  failure.

## Tool surface

Six tools. Every one resolves its repository from the caller's token; none
accepts a repository path.

### `review_comments(scope?)`

Returns the session's annotations — `OPEN` and `SENT` by default, optionally
filtered by `DiffScope`. Each entry carries `id`, `file`, a line number, a
`deleted_line` flag, `status`, `scope`, an `excerpt`, and the full thread
with authors. The response also carries the scope's `base_branch`.

`ReviewAnnotation` stores stable line keys (`n<newLine>` for post-image
lines, `o<oldLine>` for deleted lines), decoded here to plain line numbers.

`base_branch` and `excerpt` are what make the annotation actionable:

- Without the base branch, `scope: "BASE"` is an enum name and the agent
  cannot reproduce the diff the comment refers to.
- For a post-image line, `excerpt` is that line plus two lines of context
  read from the working tree. This also disambiguates as the agent's own
  edits shift line numbers — the first fix would otherwise misalign every
  later comment.
- For a deleted line (`deleted_line: true`) there is no working-tree
  content, so `excerpt` is null and the agent is told to use
  `git show <base_branch>:<file>`. The first draft's claim that the agent
  "reads the file itself" was false for exactly these annotations.

### `review_reply(id, note, addressed)`

Appends a thread message authored `"Claude"`. When `addressed` is true, also
sets the status to `ADDRESSED`.

`ADDRESSED` is distinct from `RESOLVED`, which only the human sets. This is
the case `FIXED` got wrong: that value was the *app* inferring a fix from a
successful handoff, which it cannot know. An agent reporting its own work
can — though the report is a claim, not evidence, which is why the human
still confirms and why the note matters more than the status.

`RESOLVED` and `FIXED` threads are refused: the human's verdict is final,
and an agent must not reopen it.

Adding a status value is not free, and the UI work is part of this design,
not a follow-up:

- `ReviewView`'s status-pill `switch` is an exhaustive switch **expression**
  with no `default`, so it fails to compile until `ADDRESSED` is handled.
- `updateSummary` counts only `OPEN` and `SENT`, so an `ADDRESSED` thread
  would vanish from the counters.
- The thread toggle is `status == OPEN ? "Resolve" : "Reopen"`, so an
  `ADDRESSED` thread would offer only "Reopen" — the human's path to done
  would be Reopen, then Resolve. It must offer "Resolve".
- `AnnotationStatus.fromPersisted` is lenient, so an older build reading a
  newer state file degrades an `ADDRESSED` thread to `OPEN`. That is the
  safe direction: reappearing as open beats silently reading as resolved.

### Annotation change notification

`AnnotationStore` has no observer API, and its documentation names the
Review tab as its mutator. `ReviewView` caches built cards in `cardNodes`
keyed by annotation id, and the cards' handlers capture the
`ReviewAnnotation` value they were built from.

An MCP write therefore creates a **lost update**: the open Review tab keeps
showing the stale card, and a human clicking Resolve on it writes
`staleAnnotation.withStatus(RESOLVED)`, discarding the agent's status *and*
its note. This is the read-modify-write hazard AGENTS.md's "One writer for
persistent state" section records as a past data-loss bug; `synchronized`
methods prevent list corruption, not this.

So `AnnotationStore` grows a change listener, fired on `add`/`update`/
`remove`, delivered on the FX thread. `ReviewView` subscribes, and its card
handlers re-read the annotation from the store by id instead of trusting the
captured value. Without this, the feature corrupts the data it exists to
serve.

### `worktree_create(branch, start_point?)`

Creates a worktree in the caller's repository. The directory name is derived
by the context, which owns the `WorktreeNaming` recipe. Returns path and
branch.

`WorktreeNaming` is currently package-private in `app.drydock.ui`, and the
recipe lives in `NewWorktreeModal`: `WorktreeNaming.defaultDirectory(home,
userConfig.worktreesDirectory(), repository.displayName(), branch)`. It
moves to a neutral package and becomes public — a router that depends on
`app.drydock.ui` would invert the layering of a component advertised as
JavaFX-free.

**Branch names are validated.** The first draft claimed `--end-of-options`
covers them; it does not, and does not need to (git takes `-b`'s value
unconditionally), but the name still needs checking because it is now
model-generated rather than human-typed in a modal:

- A name whose first path component matches an existing remote is refused.
  `worktree_create(branch="origin/main")` otherwise creates
  `refs/heads/origin/main`, which **shadows** the remote-tracking ref for
  every short-name lookup, so a later `git merge origin/main` silently
  targets an agent-chosen commit. `git worktree add` exits 0 and warns only
  on stderr, so the tool would report success. That is lost work, not
  clutter, and it is why "create-only is non-destructive" was wrong.
- A name starting with `refs/` is refused.
- The name is pre-validated with `git check-ref-format --branch`.

### `session_start(worktree_path, prompt?)`

Opens a real Drydock session tab in a worktree, optionally seeded with a
first prompt, and returns the new session's id.

`worktree_path` is validated by resolving **both sides** with
`Path.toRealPath()` and requiring an exact match against an entry from the
caller's own worktree list. Lexical `normalize()` is not enough in either
direction: `git worktree list --porcelain` reports realpaths, so an honest
path through a symlinked base (`/tmp` → `/private/tmp`, a symlinked `~/dev`)
would be wrongly *rejected*; and a symlink swapped in under a recorded path
would be wrongly *accepted*, spawning a session outside the repository. A
`toRealPath()` on both sides fixes both. A narrow TOCTOU window remains
between check and spawn, and is accepted.

**`prompt` is sanitized.** It is not delivered as an API message: it reaches
the session through `MainWorkspace.sendTaskWhenReady`, which after a fixed
1.5-second delay types it as real keystrokes and presses Return, collapsing
whitespace but preserving the first character. In the `claude` TUI a leading
`!` is bash mode and a leading `/` is a slash command — neither is a model
turn, so "the new session is a fresh `claude` with its own permissions" does
not cover them. The router therefore refuses a prompt whose first
non-whitespace character is `!`, `/`, or `#`, and refuses embedded newlines
or control characters. Sanitizing in the router, not in `MainWorkspace`,
covers future callers too.

This also means `session_start` inherits the fragility this design
criticizes: a fixed delay plus keystroke injection that silently no-ops if
the surface is not ready. Making that delivery reliable is out of scope; the
tool reports the session id, not that the prompt was received.

### Bounding fan-out

An agent-started session is itself a local session, so without a rule it
would get its own token and could spawn again: one instruction becomes 13
`claude` processes, 12 worktrees, and 13 native terminal surfaces, each
burning tokens. Nothing in `SessionManager` rate-limits session creation.
"Worst case is clutter" is false when the clutter is running processes that
cost money and cannot be cleaned up through MCP by design.

Two bounds, both held in the registry:

1. **Depth 1.** A session started via `session_start` gets a token whose
   grant forbids `worktree_create` and `session_start`. It can still read
   review comments and reply. Recursion is therefore impossible, not merely
   discouraged.
2. **A per-session budget.** At most 4 worktrees created and 4 sessions
   started per session, for the session's lifetime. Exceeding it is a
   distinct, actionable error naming the limit.

Neither number is tunable in this design; a constant that proves too low is
a cheap follow-up, and a constant that proves too high is not recoverable
once the processes are running.

### `repos_list()`

Every registered repository: name, path, current branch, and — for local
repositories only — dirty flag and ahead/behind counts.

**Remote repositories are reported without git state.** `GitStatusService`
has no cache, so a status call per repository means a `git` spawn per
repository, and for a remote target it runs `ssh` with its own timeout while
the HTTP handler waits. One tool call must not open N ssh connections.

This tool crosses the session boundary deliberately: it is read-only, and
the cross-repo picture is what a cwd-bound harness structurally lacks.
Its honest limit is that the agent cannot act on another repository through
any tool here — it informs the agent's reasoning and its conversation with
the human, nothing more. It also puts the user's full local repository
inventory into an LLM context on request, which is a real if modest privacy
expansion.

### `sessions_list()`

Sessions with repository, branch, worktree, activity state, and a flag
marking the caller's own session. Useful mainly for "is another agent
already working in this repository?".

## Error handling

Following the repository's `ProcessRunner` conventions, a failed tool never
returns an empty success. Each of these surfaces as its own `isError`
message with actionable text:

- branch already exists; branch name shadows a remote; branch name
  malformed per `check-ref-format`
- worktree directory already exists
- `WorktreeLockedException`, `WorktreeNotCleanException`
- git executable not found (distinct from git failed)
- creation budget exhausted; spawning not permitted for this session
- prompt rejected as unsafe
- FX-thread timeout
- session ended while the call was in flight (resolves to "session gone",
  not a null-repository NPE)
- unknown annotation id; annotation already `RESOLVED`/`FIXED`

Port numbers and session tokens never appear in log output.

## Security

- Bind `127.0.0.1` only.
- Every request carries the session token; missing or unknown gets 401. The
  comparison is uniform per candidate token, but the real defense is 256
  bits of entropy, not timing.
- Validate `Origin` **and** `Host`. Browsers attach `Origin` to every POST
  and a custom auth header requires a CORS preflight that the handler
  refuses, so rebinding is already blocked; these are defense in depth. A
  request with no `Origin` is accepted, because CLI clients send none —
  which is safe against browsers and irrelevant against local processes,
  per "Trust boundary".
- Path safety comes from the token, not from argument validation: no tool
  accepts a repository path.
- Branch names are validated (see `worktree_create`); start-points flow
  through `ProcessRunner` with `--end-of-options`, per the existing
  convention.
- Prompts are sanitized (see `session_start`).

## Concurrency

- Two agents in different sessions creating worktrees concurrently is safe:
  different repositories, and `git worktree add` is itself the serialization
  point.
- Annotation writes go through `AnnotationStore`'s `synchronized` methods,
  and the new change listener plus store-re-reading card handlers close the
  lost-update hazard described above.

## Testing

Plain JUnit 5, matching the existing suite — the build has no mocking
library, which is why the router takes the `McpSessionContext` interface.

- **`McpSessionRegistryTest`** — mint, resolve, revoke, unknown-token
  rejection, grant and budget accounting.
- **`McpToolRouterTest`** (split by area) — each tool against a temp
  `git init` repository and a real `AnnotationStore`. Error cases covered
  individually: branch exists, remote-shadowing name, malformed ref name,
  directory exists, locked worktree, foreign worktree path, symlinked
  worktree path, unsafe prompt, budget exhausted, spawn-forbidden grant.
- **`McpServerTest`** — a real `HttpServer` on an ephemeral port driven by
  `java.net.http`: 401 on a bad token, rejection of a foreign `Origin` and
  `Host`, `notifications/initialized` answered without an error, `ping`,
  well-formed JSON-RPC for `tools/list` and one `tools/call`.
- **`AnnotationStatusTest`** — `ADDRESSED` persists and round-trips; an
  unknown status still decodes lenient to `OPEN`.
- **`AnnotationStoreTest`** additions — the change listener fires on
  add/update/remove.
- **Line-key decoding** gets its own test.

**Not covered by automated tests:** the end-to-end path where a real
`claude` connects and calls a tool. Like Gate 0E
(`docs/claude-integration.md`), this needs a manual checklist entry in
`docs/manual-terminal-checklist.md` — including a check that the handshake
completes at all, since a protocol-level failure there would leave every
unit test green and the feature inert.

## What the adversarial review changed

Three reviewers attacked the first draft on security, implementation
correctness, and design. Findings and resolutions:

| Finding | Resolution |
|---|---|
| Per-session isolation claim false: a sibling agent can read another session's token file | Claim corrected. Boundary is the user account; token is for attribution, not isolation |
| `worktree_create(branch="origin/main")` shadows the remote-tracking ref, silently changing what `git merge origin/main` means | Branch-name validation: no remote-shadowing names, no `refs/` prefix, `check-ref-format` |
| `session_start`'s prompt is typed as keystrokes, so a leading `!` reaches the TUI's bash mode | Prompt sanitized in the router |
| `normalize()` is lexical; git reports realpaths, so honest symlinked paths are rejected and swapped symlinks accepted | `toRealPath()` on both sides |
| `ReviewView`'s status `switch` is exhaustive — `ADDRESSED` is a compile error | UI work moved into the same change as the enum value |
| `AnnotationStore` has no observers; stale `ReviewView` cards cause a lost update that discards the agent's note | Change listener added; card handlers re-read from the store |
| `notifications/initialized` answered with `-32601` risks the handshake never completing | Notifications accepted and ignored; `ping` handled |
| `jdk.httpserver` missing from the jlink module list — packaged app only | Added to `RuntimeImageTask` |
| `WorktreeNaming` package-private in `app.drydock.ui`; recipe actually lives in `NewWorktreeModal` and needs `UserConfig` + `Repository.displayName()` | Moved to a neutral package; the context owns naming, not the router |
| Fan-out unbounded: child sessions can spawn too | Depth 1 via token grants, plus a per-session budget |
| `repos_list` spawns a `git` per repo and `ssh` per remote repo | Remote repositories reported without git state |
| `review_comments` unusable without diff context; `deleted_line` gave the agent nothing | `base_branch` and `excerpt` added; deleted lines documented as pre-image only |
| "Create-only, worst case is clutter" | Retracted; see the shadowing and fan-out rows |
| Constant-time token compare rationale wrong | Rationale corrected; entropy is the defense |

Rejected: rebuilding the review loop on a `UserPromptSubmit` hook instead of
MCP. A hook can inject annotations as context but cannot write back, and the
Send button already handles discovery.
