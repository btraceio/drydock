# Drydock as an MCP server for the sessions it hosts

Drydock owns state that a `claude` session running inside it cannot reach:
line-anchored review annotations, the worktree graph, the cross-repo
registry, and the session/tab graph. This design exposes that state to the
sessions Drydock spawns, over an MCP server the app hosts.

The direction matters. Drydock is the **server**; the hosted `claude`
sessions are the clients. Drydock as an MCP *client* is not part of this
design and has no identified benefit.

## Why this is not net-new surface

The review loop already exists as text injection. `AnnotationStatus.SENT`
means "posted into the session's live Claude terminal", and the enum's
documentation records that `FIXED` was retired because "the app never
claims a fix on Claude's behalf". That is exactly the gap MCP closes: the
agent claims the fix on *its own* behalf, through a tool call, instead of
Drydock pasting annotation text into a terminal and inferring an outcome.

## Scope

In scope:

- Review annotations: the agent reads open threads and marks them
  addressed.
- Worktree and session creation: the agent creates worktrees and starts
  visible Drydock sessions in them.
- Read-only workspace awareness: registered repos with live git state, and
  the session list.

Out of scope, with reasons:

- **A diff tool.** The agent can run `git diff`. Serving
  `DiffService`'s cached branch-vs-base view would guarantee the agent and
  the human reason about the same scope, but that consistency win does not
  justify the surface in a first cut.
- **Any destroy operation.** No worktree removal, no session close, no
  branch deletion, no merge. Create-only means the worst outcome is
  clutter the human can clean up by hand, never lost work. Note that a
  confirmed delete in this codebase can already override a worktree lock,
  so the existing guards are too soft to be the only barrier.
- **`ask_human` through a native dialog.** Redundant. The `claude` CLI
  already renders questions and permission prompts in its own TUI, and
  `SessionActivityWatcher` badges already tell the human *which* tab is
  waiting. A native dialog would duplicate that with lower fidelity — it
  cannot render the harness's own option lists or diff previews. A
  background-app notification is a hook concern, not an MCP tool.
- **External harnesses.** Only sessions Drydock spawns may connect. A
  `claude` in the user's own terminal, or Codex/Cursor, would need a
  published endpoint, a pairing flow, and an answer to "which repo am I?"
  when there is no Drydock session to resolve. Not worth it for a first
  cut; the per-session token model below does not preclude adding it.
- **Remote SSH sessions.** They get no MCP config. `claude` runs on the
  remote host and cannot reach `127.0.0.1` on the Mac without a reverse
  port-forward. This matches how worktrees, diffs, and Review are already
  unavailable for remotes.

## Architecture

A new `app.drydock.mcp` package with three components.

### `McpServer`

A localhost HTTP endpoint using the JDK's `HttpServer` — no new
dependency — bound to `127.0.0.1` on an ephemeral port, speaking the
streamable-HTTP MCP transport. Started once at app launch; closed in
`DrydockApplication.stop()` alongside the other services, inside the
existing per-service exception isolation.

stdio transport is ruled out: one GUI process serves N concurrent
sessions, and stdio is a single-client transport.

### `McpSessionRegistry`

Mints an opaque token per `ManagedSessionId` when a session starts, and
revokes it when the session ends.

The token is the entire identity mechanism. A request carrying it resolves
to one `ManagedSessionId`, and therefore to one repository root, one
worktree, and one annotation set. **No tool accepts a repository path as
an argument**, so an agent cannot address another session's repository
even by guessing. This is also why annotations work at all: they are keyed
by `ManagedSessionId` in `AnnotationStore`.

### `McpToolRouter`

Dispatches tool calls to existing services. It owns no domain logic; every
tool is a thin adapter over `AnnotationStore`, `WorktreeService`,
`GitStatusService`, and the session registry. Tools return data already
shaped for the UI, so the agent and the human see the same thing by
construction.

The router does **not** depend on JavaFX. It takes a narrow
`McpSessionContext` interface — resolve token to repo root, worktree,
annotation scope, and session list — implemented by the workspace in
production and by a fake in tests. This boundary exists primarily so every
tool is testable headlessly.

### Wiring into sessions

`ClaudeHookInstaller` already writes a settings file that the spawned
`claude` inherits; it takes on the MCP config too. Per session, Drydock
writes an MCP server entry pointing at `http://127.0.0.1:<port>/mcp` with
the session token in a header. The trust model is identical to the hooks':
config Drydock owns, in a directory Drydock owns.

### Threading

The HTTP handler runs on its own executor and never touches the FX thread
directly, per the repository's async rules.

- Tools needing FX-owned state (session list, tab existence) hop via
  `Platform.runLater` into a `CompletableFuture`, and the handler awaits
  it **with a timeout**. A wedged FX thread must fail the tool call, not
  hold the HTTP connection open.
- Tools hitting git go straight to the service executors, which are
  already off-thread.

## Tool surface

Six tools. Every one is scoped to the calling session's repository via the
token.

### `review_comments(scope?)`

Returns the session's annotations — `OPEN` and `SENT` by default,
optionally filtered by `DiffScope`. Each entry carries `id`, `file`, a
line number, a `deleted_line` flag, `status`, and the full thread with
authors.

`ReviewAnnotation` stores stable line keys (`n<newLine>` for post-image
lines, `o<oldLine>` for deleted lines). The tool decodes these to plain
line numbers and lets the agent read the file itself — no diff round-trip
and no excerpt duplicated into the response.

### `review_mark_addressed(id, note)`

Sets the annotation's status to `ADDRESSED` and appends a thread message
authored `"Claude"`. Both mechanisms exist already: `withStatus`,
`withReply`, and a thread author field documented as `"You"` or
`"Claude"`.

This requires:

- A new `AnnotationStatus.ADDRESSED` value, visually distinct from the
  human's own `RESOLVED` in the Review view.
- A human `ADDRESSED -> RESOLVED` action, so the human remains the sole
  judge of "done".

`AnnotationStatus.fromPersisted` is lenient, so an older build reading a
newer state file degrades an `ADDRESSED` thread to `OPEN`. That is the
safe direction: a thread reappears as open rather than silently reading as
resolved.

### `worktree_create(branch, start_point?)`

Creates a worktree in the caller's repository, with the directory name
derived by the existing `WorktreeNaming` slug rules. Returns the path and
branch. Does not start a session.

Worktree creation currently lives in `GitStatusService`
(`createWorktreeBlocking`, `addWorktreeForBranchBlocking`), both
package-private, while `WorktreeService` owns list, remove, and merge.
This tool needs a caller-facing entry point, so the design folds in a
small consolidation: expose creation through `WorktreeService` alongside
the rest of the worktree lifecycle. This is the only pre-existing
structural issue the design touches.

### `session_start(worktree_path, prompt?)`

Opens a real Drydock session tab in a worktree, optionally seeded with a
first prompt.

This is what makes worktree fan-out worth doing. An agent trying three
approaches in parallel currently shells out `git worktree add` into
directories the human never sees; through Drydock, each becomes a visible
sidebar entry and terminal tab, and the agent inherits the safety rules
the app already enforces instead of reimplementing them.

`worktree_path` is validated by canonicalizing it and requiring an exact
match against an entry returned by `WorktreeService.list(callerRepo)` — a
membership test, never a string-prefix test. A path outside the caller's
own repository is refused.

### `repos_list()`

Every registered repository: name, path, current branch, dirty flag,
ahead/behind counts.

This deliberately crosses the session boundary. It is read-only, and the
cross-repo picture is precisely what a cwd-bound harness structurally
lacks: a session changing an API in one repo can discover that a consumer
repo is registered, clean, and idle.

### `sessions_list()`

Sessions with repository, branch, worktree, activity state, and a flag
marking the caller's own session.

## Error handling

Following the repository's `ProcessRunner` conventions, a failed tool
never returns an empty success. Each of these surfaces as its own
`isError` message with actionable text:

- branch already exists
- worktree directory already exists
- `WorktreeLockedException`
- `WorktreeNotCleanException`
- git executable not found (distinct from git failed)
- FX-thread timeout
- session ended while the call was in flight (resolves to "session gone",
  not a null-repository NPE)
- unknown annotation id

Port numbers and session tokens never appear in log output.

## Security

- Bind `127.0.0.1` only.
- Every request carries the session token; missing or unknown gets 401,
  compared with a constant-time check.
- Validate `Origin` and `Host` headers. A localhost HTTP MCP endpoint is
  otherwise reachable from any page in the user's browser via DNS
  rebinding. This is the one genuinely new attack surface the feature
  introduces.
- Path safety comes from the token, not from argument validation: no tool
  accepts a repository path.
- Branch names and start-points flow through `ProcessRunner` as list
  arguments with `--end-of-options`, per the existing convention.

## Concurrency

- Two agents in different sessions creating worktrees concurrently is
  safe: different repositories, and `git worktree add` is itself the
  serialization point.
- Annotation writes go through `AnnotationStore`'s existing `synchronized`
  methods and its `flushPendingSaves`, so an MCP write and a UI write
  cannot interleave badly.

## Testing

Plain JUnit 5, matching the existing suite — the build has no mocking
library, which is why the router takes the `McpSessionContext` interface.

- **`McpSessionRegistryTest`** — mint, resolve, revoke, unknown-token
  rejection.
- **`McpToolRouterTest`** — each tool against a temp `git init` repository
  and a real `AnnotationStore`, following `AnnotationStoreTest` and
  `WorktreeServiceTest` precedent. Error cases covered individually:
  branch exists, directory exists, locked worktree, foreign worktree path.
- **`McpServerTest`** — a real `HttpServer` on an ephemeral port driven by
  `java.net.http`: 401 on a bad token, rejection of a foreign `Origin`,
  well-formed JSON-RPC for `tools/list` and one `tools/call`.
- **`AnnotationStatusTest`** — `ADDRESSED` persists and round-trips; an
  unknown status still decodes lenient to `OPEN`.
- **Line-key decoding** gets its own test (`n42`/`o17` to line number plus
  `deleted_line`). It is the only piece of real logic in the adapter
  layer.

**Not covered by automated tests:** the end-to-end path where a real
`claude` connects and calls a tool. Like Gate 0E
(`docs/claude-integration.md`), this needs a manual checklist entry in
`docs/manual-terminal-checklist.md`: spawn a session, confirm the tools
appear under `/mcp`, leave an annotation in the Review view, ask the agent
to address it, and verify the thread flips to `ADDRESSED` with a
`"Claude"`-authored note.
