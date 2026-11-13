# Persistent agent sessions on workspace boxes — design, as shipped

**Status:** implemented. The remote arm ships in the Drydock IntelliJ plugin
behind an experimental opt-in — Settings › Tools › Drydock › "Run agent
sessions on workspace boxes (experimental)" (key
`drydock.workspaceSessions.enabled`, default **false**) — plus a per-launch
"Run on workspace box" checkbox in the New task dialog. Verification status:
every component is covered by automated unit and platform tests (exact
command argv, the lifecycle decision table, settle outcomes — including
the stop-identity, close-origin and deferred-settle orderings — refusal
wiring, provisioning serialization, and a three-layer quoting integration
test run under `/bin/sh` and `/bin/zsh`
with stub `ssh`/`tmux`/`claude`); **no live-box end-to-end check has been run**
— the six checks M1–M6 below are all **NOT VERIFIED**. Runtime claims marked
**proven** were measured on a live workspace box during the 2026-11-12
investigation (`workspace-persistent-sessions`, 12 nodes); anything else is
marked **inferred** or, for what the plugin now does, **implemented** (code
plus tests, no live box). Companion to `docs/unattended-sessions-tmux-spike.md`
(the local tmux arm, implemented in the desktop app). This doc records the
shipped behavior, including every divergence from the original design
("Divergences from the original design" below).

## Goal

Agent sessions launched from the Drydock IntelliJ plugin survive the IDE:
fire up sessions on a Datadog workspace box, close the IDE, reconnect later
and keep working — conversation, working directory, and terminal state
intact. All agent LLM traffic exits through `omlx_proxy` on the box in
gateway-only mode, so keys never leave the user's machine and no personal
API keys are involved.

## Why the plugin could not do this before

**proven** (code-level, pre-change): the plugin ran agents as children of
the IDE on a JediTerm PTY (`app.drydock.intellij.session.DrydockSessionService`);
quitting the IDE SIGHUPs every agent. The plugin's remote plumbing existed at
the provider/SPI level — `ClaudeAgentProvider` has remote command branches
that build `ssh -t` — but the plugin always passed `Optional.empty()` for
remote context, and `CreateContext`/`ResumeContext` carried no
`ManagedSessionId`, so a launched remote session could not be matched back
to its plugin-side record. On IDE restart, every persisted session decoded
to IDLE with no reattach concept. Both gaps are now closed: the contexts
carry `Optional<RemotePersistence>` (the `ManagedSessionId` plus the agent
env — see component 1), and the session row carries an optional
`WorkspaceBoxBinding` persisted as a `workspaceBox` state member (component 2).
The plugin's dead `TmuxPersistence` copy was deleted in this series; the
desktop app's spike remains the local arm.

## Architecture

```
Mac (IntelliJ + Drydock)                     workspace box (Linux aarch64)
┌─────────────────────┐    ssh -t            ┌──────────────────────────────┐
│ Drydock console     │─────────────────────▶│ tmux server                  │
│ (JediTerm PTY       │  attach-or-create    │  ├─ drydock-<id> … claude    │
│  rendering the ssh  │  on Resume click     │  ├─ drydock-<id> … claude    │
│  client)            │                      │  └─ drydock-<id> … claude    │
│                     │  ssh probes,         │ omlx_proxy :4000             │
│ Box lifecycle       │  workspaces list     │   └─▶ AI gateway (ddtool     │
│ (reuse/create)      │◀────────────────────│        vault token)          │
└─────────────────────┘                      └──────────────────────────────┘
```

The lifecycle is **reuse/create** — there is no wake path (see the lifecycle
section). Three layers, each independently proven:

1. **Persistence layer — tmux on the box.** Sessions are named
   `drydock-<ManagedSessionId>` (the one place the name is formed is
   `SshCommandBuilder.tmuxSessionName`) and launched through
   `ssh -t -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -- '<host>'`
   with the remote command
   `export TERM=xterm-256color; tmux set-option -g history-limit 20000 \;
   set-option -g remain-on-exit off \; new-session -A -s drydock-<id> -c
   <quoted box path> <quoted agent command>` — `history-limit` precedes
   `new-session` because tmux applies it at pane creation. The tmux server
   owns the agent PTY on the box; the ssh client is just a renderer, and
   the IDE console renders the ssh client. Closing the console detaches;
   closing the IDE detaches. **proven**: detached sessions survive repeated
   ssh disconnects with real work completing; claude conversation history
   lives on the box (`~/.claude/projects`), so reattach resumes full context.
2. **Traffic layer — omlx_proxy on the box, gateway-only.** The proxy runs
   **unmodified** on Linux aarch64. The proxy binary and its config come
   from the Mac (`/usr/local/bin/omlx_proxy` and `~/.omlx_proxy`) — if
   either is missing locally, the launch is refused with that reason; no
   launch ever proceeds without the proxy. The box config is derived from
   the local one by a pure transform: `routing.enabled=false`,
   `chain_headroom=false`, the local models nulled, `proxy_port=4000`,
   `omlx_key`/`omlx_url` removed, `gateway_headers` kept. Artifacts are
   copied over scp only when their SHA-256 differs from the box's copy
   (bootstrap is idempotent); the proxy starts under
   `nohup setsid uv run ~/omlx_proxy`, with a bounded (5 min) health poll.
   Gateway auth is the box's own: the bootstrap runs the box-side
   `ddtool auth token … >/dev/null` and reads the exit status only — no
   token material is logged, transported, or stored. **No per-box login of
   any kind is performed** (fresh boxes ship a cached vault session,
   `find-box-vault-session-fresh`), and no Mac-side token is sent (the
   gateway 401s personal keys, `find-box-gateway-auth-ddtool`). Agent env
   in each tmux session:
   `ANTHROPIC_BASE_URL=http://127.0.0.1:4000`. The proxy running
   unmodified on the box and completing end-to-end is **proven** from the
   investigation; the shipped bootstrap automates the same steps.
3. **Box layer — one box per user+repo.** The user runs 5+ concurrent
   sessions per repo in separate worktrees; all map to separate tmux
   sessions on one box. A claude session is ≈ 240MB RSS and a fraction of
   a core; a default box (16 vCPU / 61GiB) hosts dozens. **proven**
   (capacity), decision recorded by the user (scope). The default box name
   is `drydock-<sanitized repo>` with a per-repo override and per-repo
   re-create generations (see the lifecycle section).

### Why tmux and not the alternatives

**proven** (mechanism + measurements):

- `nohup`/`setsid` alone survives the ssh client but offers no PTY to
  reattach to — the IDE console needs a PTY to render, prompts cannot be
  typed, and liveness would need bespoke process-tree probing.
- `dtach`/`abduco` give detach/reattach but no session naming, no batch
  liveness probe, no scrollback management, tiny maintainer base.
- tmux (3.6b, preinstalled on boxes) gives attach-or-create (`-A`),
  `send-keys` input injection, `capture-pane` readback, `has-session`
  liveness, `kill-session` teardown.

A bespoke supervisor daemon was ruled out in a prior investigation: it would
own all PTYs exactly like the tmux server — the same single point of
failure — with none of the hardening.

## Reliability model

Measured on a live box (tmux 3.6b); the shipped arm follows it:

- **Liveness is exact.** With `remain-on-exit=off` (set by every persistent
  launch), a session whose command exits is removed, so one `tmux ls` parse
  is an authoritative agent-alive signal for all sessions. This design
  treats has-session as the truth; only tmux's two no-server answers
  (`no server running`, `error connecting to`) count as a valid "zero
  sessions" — anything else is UNKNOWN, never silently empty.
- **Detached liveness is memory-only.** The row's persisted status is
  RUNNING only while this IDE owns its live ssh execution (the Resume or
  Create that opened the console); a detached row decodes to IDLE on IDE
  restart and its liveness (ALIVE / DEAD / UNKNOWN / BOX_STOPPED /
  BOX_GONE) lives only in the in-memory, application-level
  `RemoteSessionLiveness` map — never in the state file, and no new
  `SessionStatus` wire value exists (an older plugin build must still read
  the file). The map is refreshed by one `tmux ls` per box at startup
  (below) and by one `has-session` probe when a row's ssh client ends
  spontaneously — an end an explicit Stop or a close requested settles
  by its own origin or the kill's verdict, without a probe.
- **The tmux server is a single point of failure — accepted.** Killing the
  server SIGHUPs every agent within a second. This is shared by every
  PTY-owning approach including the status quo, and a box stop/restart has
  the identical effect. The damage bound is on-disk conversation state: a
  dead agent is `claude --resume`-able.
- **Scale is a non-issue.** 20 sessions created instantly; 20 probes in
  38ms locally; server RSS trivial. Over ssh, one `tmux ls` per box and
  parse — never per-session round-trips; `history-limit` bounds scrollback
  memory (20000, set at every launch).

## Shipped component design (plugin)

1. **Identity plumbing — `RemotePersistence`.** `CreateContext` and
   `ResumeContext` gained a trailing `Optional<RemotePersistence>` component
   (`RemotePersistence(ManagedSessionId, Map<String,String>
   agentEnvironment)`, plus `withPersistence`); today's constructors became
   delegating constructors passing empty, so no existing construction site
   changed. With persistence present, `ClaudeAgentProvider` builds the
   launch through `SshCommandBuilder.persistentSessionCommand`: create is
   `exec env -u <ENV_CLEANUP set> LANG=en_US.UTF-8
   CLAUDE_CODE_NO_FLICKER=1 ANTHROPIC_BASE_URL='http://127.0.0.1:4000'
   claude [--model m] -n <displayName> --session-id <uuid>
   [--permission-mode plan] [-- <initial prompt>]` — the generated UUID
   rides the plan, so the row's `agentSessionId` persists; resume is the
   same prefix plus `claude --resume <agentSessionId>
   [--permission-mode plan] [-- <resume prompt>]`. The branch runs no
   local capability probe, emits no MCP/hook/settings/PATH flags, and
   refuses host-path launch resources. Read-only remote launches always
   carry `--permission-mode plan`, on create and on resume.
2. **The remote row — `WorkspaceBoxBinding`.** `ManagedAgentSession` gained
   `Optional<WorkspaceBoxBinding> workspaceBox` (box name + absolute box
   working path; `sshRemote()` is `workspace-<name>` + the path). The codec
   writes `"workspaceBox": {"name","path"}` only when present and decodes
   it leniently (absent, malformed, or invalid → an empty binding, never a
   file failure); `SCHEMA_VERSION` stays 2. A remote row stores the
   binding's placeholder root as `workingDirectory`, an empty
   `worktreeRoot`, and `branchCreatedHere=false`.
3. **Settings.** Application-level `PropertiesComponent` keys:
   `drydock.workspaceSessions.enabled` (default false; the Settings
   checkbox), `drydock.workspace.<RepositoryId>.box` (the current box name
   — a user override or a re-created generation),
   `drydock.workspace.<RepositoryId>.usedBoxes` (the used-names history:
   every box name the repo's mapping has ever pointed at, current mapping
   plus past generations — so a mapped name is never re-created even after
   the box left `workspaces list` or the override was cleared; decoded
   leniently, invalid entries skipped), and
   `drydock.workspace.<RepositoryId>.clone` (the discovered box clone
   path, trusted only while `drydock.workspace.<RepositoryId>.cloneBox`
   names the same box generation — a cached path picked on one box never
   carries into a fresh one, which discovers its own clone; a manual path
   from the dialog is stamped with the box the mapping points at when it
   is set).
   A per-project "Workspace box" dialog (project-row menu next to GitHub
   settings, visible only while the flag is on) edits the box-name
   override and the clone path — the manual escape hatch for a discovery
   that finds zero or several candidate checkouts.
4. **Box tooling — package `app.drydock.workspace`.** Every external
   process runs through `CommandRunner` → `ProcessRunner.run(List, Options,
   int)` with an argument list and an explicit timeout; "not installed",
   "failed", and "timeout" are distinct typed, logged outcomes.
   `WorkspaceBoxNames` (default name, `nextGeneration`, `validate`),
   `WorkspacesCli` (only `list` and `create`; `list` parses the bounded
   `workspaces list --no-color` table — status `8` means STOPPED, an
   unlisted name is ABSENT, anything else listed is OTHER), `BoxShell`
   (probe, has-session, kill-session, `tmux ls`, scp — each a typed
   five-class result), `BoxLifecycle.decide` (the exhaustive reuse/create
   /re-create/transient table; every ambiguous input — a listed non-8
   status, a CLI failure or timeout, an unparseable table, a missing CLI —
   maps to TRANSIENT_ERROR, never to a re-create offer), `ProxyBootstrap`
   (component layer 2 above), and `BoxCheckout` (discovers the auto-clone
   under `$HOME` by origin URL, accepts exactly one match, and creates
   each session's box-side worktree under `<clone>/.drydock-worktrees/`).
5. **Liveness and revalidation.** `RemoteSessionLiveness` (memory-only map,
   above) plus `WorkspaceRevalidation`: once per IDE run, only when the
   flag is on, triggered after startup warm-up — exactly one `tmux ls` per
   distinct box, bounded to 30 s, marking each remote row ALIVE/DEAD/UNKNOWN.
   It writes no row, spawns no console, prompts nothing, and never creates
   or bootstraps a box.
6. **Session service branches (`DrydockSessionService`), each keyed on the
   row's binding — local rows run today's code.**
   - *Create*: refuse a non-Claude kind (naming the agent) and any launch
     with task bindings or resolvable launch resources — before any row
     persist, MCP mint, box command, or spawn; then generate the UUID,
     build the context, and only then persist the row and open the console
     (cwd = the session repository's local root, else `user.home` — never
     the placeholder).
   - *Resume*: with the flag off, refuse ("Workspace sessions are turned
     off in Settings › Tools › Drydock") and never fall through to a local
     launch. Skip every local-only probe (no `Files.notExists`, no
     transcript check, no late id bind, no MCP access); after acquiring
     its claim, re-check that the row still exists (a Forget that finished
     in between refuses with "was forgotten before it resumed" — the same
     deletion-gap guard the create path carries); run one batch ssh
     probe — on failure classify with `workspaces list` and refuse (see the
     lifecycle section for the stopped/gone texts), persisting no FAILED
     row; on success attach and persist only RUNNING + `lastOpenedAt` —
     no MCP mint, no `agentSessionId` rewrite, no last-used records, no
     box worktree. `resumeOrStartSession` always resumes a remote row, even
     with no local transcript and no local working directory.
   - *Stop*: the click first takes a synchronous per-session in-flight
     claim, before the service call — the busy fact every surface's guard
     reads while the blocking kill runs ("Deactivating…", a disabled
     primary; a second click while the stop is in flight is ignored — the
     persisted STOPPING mark is written on the service executor after the
     click returns, too late to close the double-click window; the claim
     clears on every completion path). The stop itself is async — the row
     goes STOPPING (only when this IDE owns its live execution), the Stop
     is identified before the kill through a registry-level requested-end
     mark, then `tmux kill-session -t drydock-<id>` (10 s timeout), then
     the ssh client ends; exit 0 or "can't find session" settles the row
     INACTIVE with liveness DEAD — by the kill's own verdict, never by
     an end's probe, regardless of box reachability (the client's own
     end settles the same verdict when the flow's settle was
     suppressed); anything else surfaces an error naming the box and
     settles the row to IDLE with liveness UNKNOWN — never INACTIVE. A
     settle records its liveness answer even when the row already
     carries the settle's status (only a row gone or a claim/live
     execution suppresses it). One residual race remains, accepted: a
     spontaneous client end whose probe was already in flight when the
     Stop click landed is not cancelled, and its late answer can settle
     over the Stop's verdict. Stop is offered on ALIVE-detached rows in
     the strip, the tab header and the tab popup, and the task tree
     (lifecycle DEACTIVATE without a Resume first, on the same lifecycle
     derivation in all three surfaces).
   - *Forget*: kill-session first; on any kill outcome other than success
     or no-such-session, confirm "The agent may still be running on box
     <name>. Forget anyway?" — remove the row only on confirm.
   - *Close paths*: tab close, window close, `hostClosed`, `dispose`, and
     quit end only the local ssh client — no kill-session — and the
     close-originated end (identified before the client is asked to end)
     settles the durable row IDLE, never EXITED or INACTIVE, records no
     liveness (no probe ran) and issues no box command; the quit and
     close-window confirmation counts exclude remote sessions.
   - *Refused for remote rows*, each with a named reason: `startSession`
     (a remote row "can only be resumed there, never started fresh here"),
     `transferStoppedSession`, and the Done flow ("the Done flow is
     local-only — use Stop or Forget instead" — the Done action consults
     this gate before running the local flow).
   - *Settle on client end*: when a remote row's ssh client ends, the end
     is classified by what requested it. An end an explicit Stop requested
     never probes — it settles the kill's verdict itself (ordering 1: the
     client died during the kill, verdict pending, the Stop flow owns the
     settle; ordering 2: the flow's settle was suppressed by the
     still-dying client, the decided verdict settles here); a
     close-originated end settles IDLE with no probe (the close-path
     bullet above). A spontaneous end — one nobody requested — runs one
     `has-session` probe (off the EDT) which decides: ALIVE → the row
     persists IDLE with liveness ALIVE; DEAD → today's EXITED/INACTIVE
     settle, without `lastExitCode` (the ssh client's exit code is not the
     agent's); anything else → IDLE with liveness UNKNOWN and a WARNING
     carrying the excerpt. An early client end that arrives while the
     launch that opened it still holds its claim is deferred in memory —
     not discarded — and re-applied after the launch finalizes, so a
     completed launch never leaves the row RUNNING when this IDE owns no
     live ssh execution. A Resume claimed during the probe is never
     overwritten.
7. **UI.** The New task dialog's "Run on workspace box" checkbox (visible
   only while the flag is on, default off) is enabled only when the agent
   is Claude, the work target is a new worktree or the main checkout,
   there are no task bindings and no fixed worktree, the login shell is
   not fish, and the project has no shared-project remote. The remote
   launch runs inside the existing background task with per-step progress
   (origin, probe, "Creating workspace box <name> (≈3 min)…", bootstrap,
   box worktree), cleared on every exit, holding a repo-scoped in-process
   provisioning claim from the first mapping read through the create's
   mapping write — one provisioning attempt per repository: a second
   overlapping launch for the same repo refuses visibly (every exit —
   success, refusal, cancel, interrupt — releases the claim); a non-GitHub
   origin or an owner other than `DataDog`/`ddoghq` is refused before any
   `workspaces create`.
   Session surfaces (tab title/tooltip/icon, the strip's status line, the
   session commands) show the box name and the liveness state; the strip
   paints a remote row through the remote presentation — box name plus
   liveness verdict on the status line (a detached row another window of
   this IDE shows keeps the "· not shown here" qualifier and Show agent),
   never the local display rules that would flatten a detached remote
   answer to "Not running"; a remote row never shows a working/idle
   activity claim (the activity monitor cannot see a box-side agent).

## Lifecycle policy — the stopped-box problem, settled for v1

Box facts that constrain the policy (**proven**): create ≈ 2.5 min; TTL
starts at ~180d and decays with age; the GC may delete sooner if unused;
idle boxes are stopped by the platform and become ssh-unreachable.

**`workspaces restart` does not wake a stopped box** — **proven**
(`find-restart-wake-refuted`): "Restart initiated" in 1s, then 18+
minutes still status 8 and unreachable; restart means "reboot a RUNNING
container", and no `start`/`resume` subcommand exists. The original design
left the wake path open (keep-alive, `workspaces connect`, re-create, or a
team API). **The shipped arm closes it the re-create way, and only from
the launch path:**

- The plugin never issues `workspaces restart`, `delete`, or any mutating
  subcommand other than `workspaces create`; a stopped or gone box is left
  in place.
- A box the repo has used before is never a first-ever box: the launch
  reads both the current mapping and the persisted used-names history
  (`.usedBoxes`), so a cleared override whose box then vanished is a
  re-create offer, never a silent same-name create. When a new remote
  launch finds that box stopped or gone, the user
  is asked once, on the EDT, with a destructive confirmation that names
  the old box and states that its conversations are unreachable or gone
  and are not recovered by this action. Confirm provisions a fresh box
  under the next generation name (`<base>-2`, `-3`, … —
  `WorkspaceBoxNames.nextGeneration`, excluding every name the repo has
  ever used: the current mapping and its lineage root, the used-names
  history, and every name the current `workspaces list` table answers
  for — so a GC'd earlier generation, absent from the listing, is still
  never handed back); a name is never reused, because a re-created
  same-name box would re-bind old rows to a fresh box where
  `claude --resume <id>` cannot find their conversations. A mapped name
  too long to suffix (a valid 62/63-character override that leaves no
  valid generation) refuses visibly before the confirmation is offered —
  never an offer-then-submit of a name the CLI adapter would reject.
  Cancel changes nothing. The used-names history is forward-looking: a
  `.box` written by an earlier build carries no `.usedBoxes`, so the
  first post-upgrade re-create of such a repo still relies on the
  current listing plus the current name.
- Stopped and gone are detected by one ssh probe plus one `workspaces
  list` classification (status 8 = stopped; unlisted = gone); any
  ambiguous input maps to a transient error, never to a re-create offer
  (component 4).
- Rows already bound to the old box stay bound: their resume is refused
  with "Box <name> is stopped; its conversations are unreachable" or
  "Box <name> no longer exists". A remote row's binding never changes, and
  no row is ever re-launched on a different box.
- Box create and re-create happen only from the New task dialog's
  remote-launch path — never from Resume, Stop, Forget, or IDE startup.

Keep-alive and `workspaces connect` were not pursued.

## Known limitations (accepted for v1)

- **Claude only.** A remote launch of any other agent kind is refused with
  an error naming the agent, before any row persist, MCP mint, box
  command, or spawn.
- **No MCP tools for remote agents.** Drydock's MCP server is in-process
  on a Mac-loopback port; remote sessions launch without it, and a launch
  whose `LaunchResources` request host paths is refused. Same deferred
  limitation as the desktop spike.
- **The Done flow is local-only.** For a remote row it is refused with the
  named reason above; Stop and Forget are the remote equivalents.
- **Remote Stop has no ask gate.** The local Stop's "agent still working?"
  ask is bypassed for remote rows — the activity monitor cannot see a
  box-side agent, so the literal gate would ask on every Stop, and its
  not-running branch would silently do nothing for a detached row (which
  Stop must serve). The only gate is the click itself.
- **The task tree offers no Stop for a detached remote row.** The strip,
  the tab header and the tab popup offer Stop on an ALIVE-detached row
  (lifecycle DEACTIVATE without a Resume first); the task tree's
  Deactivate is gated on a live local execution, so a detached remote
  row sees Resume there.
- **No scrollback replay.** tmux's redraw covers the visible pane;
  scrollback from before attach is not replayed into the IDE console (the
  desktop spike has the same caveat).
- **Interactive claude TUI through tmux is unverified** (M2). The TUI
  renders correctly through tmux on the box, but first-run claude
  onboarding was observed to exit on the box regardless of trust
  pre-seed/flags/TERM (needs one GUI run; `claude -p` works perfectly in
  tmux).
- **Vault session renewal unverified.** Fresh boxes carry a cached ddtool
  vault session (~12h observed); the proxy re-mints tokens via ddtool,
  but whether the cached session renews indefinitely on a long-lived box
  is unverified (M3's live run would also confirm the shipped bootstrap's
  auth check on a fresh box).
- **Downgrade note (an older plugin build).** An older plugin that
  re-saves the state file drops the unknown `workspaceBox` member; the
  row then decodes as a local row whose working directory is the box
  placeholder path, and its resume is refused with
  MISSING_WORKING_DIRECTORY (the placeholder is not a Mac path). The file
  itself never fails to decode.
- **Launch-scope restrictions.** Remote launch is offered only for
  GitHub origins owned by `DataDog`/`ddoghq` (case-insensitive), not under
  a fish login shell, and not for a project with a shared-project remote
  or a fixed worktree / task bindings.

## Live-box checks — M1–M6 (all NOT VERIFIED)

None of these was run against a live DataDog/ddoghq-org workspace box
before this shipped. Each is reported **NOT VERIFIED**:

- **M1 — survive-quit-and-reattach** (launch → close console → quit IDE →
  restart → revalidation shows the session alive → Resume attaches to
  the same TUI with the conversation intact): **NOT VERIFIED**.
- **M2 — interactive TUI and first-run onboarding** through ssh+tmux in
  the JediTerm console: **NOT VERIFIED**.
- **M3 — proxy-routed completion** (the agent's completions actually go
  through the box proxy to the AI gateway, no personal key involved):
  **NOT VERIFIED**.
- **M4 — stopped-box detection and re-create** (the real `workspaces list`
  status value 8 and the re-create confirmation flow): **NOT VERIFIED**.
- **M5 — bracketed-paste `submitPrompt`** delivers the whole prompt through
  ssh+tmux: **NOT VERIFIED**.
- **M6 — Ctrl+C and the claude Kitty-keyboard protocol** through tmux
  (carried over from the desktop spike's unverified list): **NOT
  VERIFIED**.

## Divergences from the original design

Each entry is the shipped behavior, recorded where the implementation
contract settled a question the design doc had left open:

1. **Attach on Resume, not on IDE start.** The original design item 4
   flipped persisted records to RUNNING and reattached at IDE startup.
   Shipped: startup runs one liveness-only revalidation pass (no row
   writes, no console, no prompt); a console opens only when the user
   clicks Resume, which probes the box once and attaches with the same
   `new-session -A` command. Nothing is ever auto-attached.
2. **No RUNNING persisted for detached sessions.** The original design's
   "flip to RUNNING" is rejected: RUNNING means this IDE owns a live
   execution (it drives the busy guard and launch claims). Detached
   liveness lives only in the memory-only `RemoteSessionLiveness` map, and
   the row decodes to IDLE.
3. **Stopped and gone boxes are handled by re-creating under a new
   generation name.** The `workspaces restart` wake path is refuted and
   removed (`find-restart-wake-refuted`); keep-alive and
   `workspaces connect` were not pursued. The destructive re-create is the
   v1 answer, and only from the New task dialog's launch path.
4. **The `RemotePersistence` plumbing.** The design left the
   `ManagedSessionId`-into-contexts plumbing "to be validated during
   implementation". Shipped: the trailing optional
   `RemotePersistence(ManagedSessionId, agentEnvironment)` component on
   `CreateContext`/`ResumeContext` (component 1 above), with the row's
   `WorkspaceBoxBinding` keying every service-side remote branch.
5. **Claude only.** The design said "agent sessions"; the shipped arm is
   Claude-only, with the refusal naming the agent.
6. **The Done flow is refused for remote rows** (local-only), wired as a
   gate ahead of the local Done flow.
7. **Remote Stop has no ask gate** (see Known limitations) — a deliberate
   divergence from the local Stop policy, recorded because the activity
   monitor cannot see box-side agents.
8. **The proxy bootstrap refuses rather than degrades** — and performs
   **no per-box device login**: the proxy and its gateway-only config come
   from the Mac, a missing local artifact or unhealthy proxy or failed
   auth check is a visible refusal naming the reason, and no Mac-side
   token is ever sent (fresh boxes ship their own cached vault session,
   `find-box-vault-session-fresh`).
9. **No scrollback replay** on attach (unchanged from the design's known
   limitation, listed here because it is a behavior a reader might expect
   from "reattach").
10. **The box lifecycle diagram is reuse/create** — the original
    reuse/wake/create triple lost its wake arm with the restart refutation.
11. **An older plugin build drops the `workspaceBox` member on re-save**
    (the downgrade note above): the row becomes local with a placeholder
    path and resumes to MISSING_WORKING_DIRECTORY. Accepted for v1.

## Proven vs inferred vs implemented — index

| Claim | Status | Node |
|---|---|---|
| IDE PTY launch killed agents on quit; remote plumbing existed but unused; no ManagedSessionId in contexts (gap now closed by `RemotePersistence`) | proven (code, pre-change) | `find-plugin-launch-path-pty` |
| Box create ~2.5 min, decaying ~180d TTL + GC, idle-stop unreachable, org-restricted create, secrets paths | proven | `find-box-provisioning-facts` |
| Gateway auth via ddtool vault token; personal keys rejected (401) | proven | `find-box-gateway-auth-ddtool` |
| omlx_proxy runs unmodified on the box, gateway-only, e2e completion | proven | `find-omlx-proxy-portable` |
| Fresh boxes carry a cached vault session | proven — the shipped arm performs no login at all | `find-box-vault-session-fresh` |
| Remote tmux survives ssh disconnects | proven | `find-box-tmux-persistence-proven` |
| ~240MB RSS per claude session; shared box hosts dozens | proven | `find-box-capacity-topology` |
| One box per user+repo | decision (shipped as the default name + per-repo mapping) | `find-decision-box-scope` |
| `workspaces restart` does not wake a stopped box | proven — the shipped arm never calls it; stopped/gone boxes are re-created under a new generation name | `find-restart-wake-refuted` |
| tmux liveness semantics, SPOF, scale numbers, alternative comparison | proven | `find-tmux-reliability` |
| Shipped plugin components (RemotePersistence plumbing, remote row, box tooling, liveness/settle, Stop/Forget, UI) | implemented — code + automated tests; not live-box verified | components 1–7 above |
| M1–M6 live-box end-to-end checks | NOT VERIFIED | M1–M6 section |

## References

- Investigation trail (frozen 2026-11-12, 12 nodes):
  `~/.cairn/investigations/btraceio--drydock/brave-dewy-sparrow/workspace-persistent-sessions/`
- Wrap-up note (refill artifact): `notes/workspace-persistent-sessions-wrapup.md`
- Local tmux arm (implemented in the desktop app, flag-guarded):
  `docs/unattended-sessions-tmux-spike.md` and the app's
  `app.drydock.process.TmuxPersistence` (`app/src/main/java`) — the
  IntelliJ plugin's dead copy was deleted in this series.
