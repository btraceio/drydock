# Multi-agent CLI support (Claude, Codex, Pi)

**Status:** design — approved for planning
**Date:** 2026-07-23

## Problem

Drydock is hard-wired to the `claude` CLI. The integration is not a thin
"run a binary" seam — a whole `app.drydock.claude` package hardcodes Claude's
executable discovery, capability probing (`claude --help` parsing), command
construction (`-n`, `--session-id`, `--resume`, `--settings`, `--fork-session`),
session identity (a Claude UUID + name baked into `ManagedClaudeSession`),
activity reporting (settings-file hooks + a polling watcher), transcript reading
(`~/.claude/projects/<enc-cwd>/<id>.jsonl`), and env scrubbing (`CLAUDE_CODE_*`).

We want users to run other agentic coding CLIs — starting concretely with
**Codex**, then **Pi** — chosen per session with a sensible default. The goal is
**full feature parity** for each agent that can support it, delivered through a
**provider seam** so adding a new CLI is a small, well-bounded change.

## Goals

- Extract an agent-provider abstraction and refactor the existing Claude
  integration to be the first provider behind it, with **zero behavior change**
  for Claude.
- Add a **Codex** provider with as much parity as the Codex CLI allows (launch,
  resume, conversation catalog, and activity badges if its hook contract checks
  out).
- Let the user **pick an agent when creating a session**, defaulting to the
  agent last used in that repo, falling back to the best *available* agent.
- Make adding a future CLI (Pi and beyond) a matter of one provider class plus
  one service registration — documented in `AGENTS.md`.

## Non-goals

- External / out-of-tree loadable plugins. Providers are in-tree, first-party
  Java adapters. (The `ServiceLoader` seam does not preclude this later, but it
  is not a goal.)
- A whole-app JPMS migration. The design is **modular-ready**, but ships on the
  classpath (see "Modularity").
- A user-overridable global default agent in settings. The global default is
  *derived* (best available). A stored override is an additive future change.
- Pi's provider implementation. Pi is validated as the "one class + one service
  line" proof in a later spec; this spec only ensures the seam fits it.

## CLI parity matrix (probed against installed CLIs, 2026-07-23)

| Concern | **Claude** | **Codex** | **Pi** (later) |
|---|---|---|---|
| Set session id at create | `--session-id <uuid>` (app decides id) | none — Codex mints its own UUID, knowable only *after* launch | `--session <id>` + `--session-dir` |
| Set name at create | `-n <name>` | none (name set in-TUI) | ~ via session path |
| Resume | `--resume <id\|name>` / bare | `codex resume <id\|name>` / `--last` / picker | `--continue` / `--resume` / `--session <id>` |
| Fork | `--fork-session` | `codex fork` | `--fork <id>` |
| Set cwd | (terminal cwd) | `-C, --cd <dir>` | (terminal cwd) |
| Transcript layout | `~/.claude/projects/<enc-cwd>/<id>.jsonl` | `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl`, cwd in `session_meta` | `--session-dir`, layout TBD |
| Activity reporting | settings-file hooks (`--settings`) | hook system / `notify` via config injection (`-c` or layered `$CODEX_HOME/<name>.config.toml`) | extension / `--mode rpc` (TBD) |
| Env to scrub | `CLAUDECODE`, `CLAUDE_CODE_*` | `CODEX_*` (but **keep** `CODEX_HOME`) | `PI_*` |

Two findings drive the architecture:

1. **Session-id provenance is not uniform.** Claude and Pi let Drydock *set*
   the id at launch; Codex forces *discover-after-launch*. Today's
   `finalizeCreate` assumes the app pre-generated the id — that assumption must
   become a per-provider strategy.
2. **Codex resume needs the discovered id.** Multiple Drydock sessions can share
   a working directory, so `codex resume --last` (cwd-filtered) is ambiguous and
   must not be used as the general resume path. Reliable resume-by-identity
   depends on capturing Codex's UUID after launch.

## Architecture

### Package layout & the SPI/API split

New package `app.drydock.agent`, three layers:

```
app.drydock.agent
├── api/                      ← what the rest of Drydock consumes (segregated)
│   ├── Agent                 identity: kind, displayName, availability
│   ├── AgentKind             enum: CLAUDE, CODEX, PI  (persisted wire contract)
│   ├── LaunchService         buildCreate / buildResume → LaunchPlan
│   ├── LaunchPlan            command string + IdOutcome (sessionIdUsed)
│   ├── SessionIdStrategy     PRESET | DISCOVERED, + discoverAfterLaunch(...)
│   ├── ConversationSource    catalog + missing-conversation probe   [optional cap]
│   ├── ActivityReporter      hook install + activity watch          [optional cap]
│   └── AgentRegistry         list agents, resolve default, feature-detect caps
├── spi/
│   └── AgentProvider         ← the ONE fat interface each CLI implements
│   └── AgentContext          collaborators handed to a provider at init
└── providers/
    ├── claude/  ClaudeAgentProvider  (+ claude/internal/…)
    └── codex/   CodexAgentProvider   (+ codex/internal/…)
```

- **SPI = one fat interface** (`AgentProvider`), the single implementation point
  per CLI. Consumers never see it.
- **API = segregated interfaces.** The registry wraps each `AgentProvider` and
  exposes only the API. Internal callers (`SessionManager`, the UI, the activity
  watcher) depend on `agent.api.*`, never on `spi.AgentProvider` or a concrete
  provider. Feature detection is `registry.activity(kind).ifPresent(...)`, never
  `instanceof`.
- The current `app.drydock.claude` guts (`ClaudeExecutableLocator`,
  `ClaudeCapabilityService`, `ClaudeHookInstaller`, `ConversationCatalog`,
  `SessionActivityWatcher`) move under `providers/claude/internal` and become
  Claude's SPI implementation — no longer app-wide singletons.

### Discovery: ServiceLoader

- `AgentRegistry` loads providers via `ServiceLoader.load(AgentProvider.class)`
  at startup, indexes by `AgentKind`, and computes availability
  (`locateExecutable()` per provider, off the FX thread, cached).
- Each provider ships a line in
  `META-INF/services/app.drydock.agent.spi.AgentProvider`. Adding a CLI = new
  provider class + one service line; **no edits to the registry or
  `SessionManager`.**
- **No-arg constructor constraint:** `ServiceLoader` requires a no-arg
  constructor, but providers need collaborators (background executor,
  activity-settings dir, state paths). The SPI has an `init(AgentContext ctx)`
  lifecycle method the registry calls once after loading; collaborators arrive
  via `AgentContext`, not globals or a fat constructor. An un-`init`-ed provider
  is inert.

### Modularity

The app is currently non-modular (no `module-info.java`). `ServiceLoader.load`
is identical on the classpath and the module path; only the *declaration*
differs (`META-INF/services` file vs `provides ... with` / `uses` clauses).

Decision: **design modular-ready, ship classpath-now.**

- Code against `ServiceLoader`; keep packages clean and non-split with provider
  internals under `providers/<x>/internal`.
- A future JPMS migration is then declaration-only: add `module-info` with
  `uses app.drydock.agent.spi.AgentProvider;` in the consumer module and
  `provides ... with <providers>;`, and delete the `META-INF/services` file —
  **zero logic change.** This target is recorded here; modularization itself is
  a separate, unscheduled spec.

### Interfaces

**SPI (fat, single impl point):**

```java
interface AgentProvider {
    AgentKind kind();
    String displayName();
    void init(AgentContext ctx);                      // ServiceLoader lifecycle

    Optional<Path> locateExecutable();                // generalizes ClaudeExecutableLocator
    AgentCapabilities probeCapabilities();            // generalizes ClaudeCapabilities/-Service
    List<String> envScrubList();                      // CLAUDE_CODE_* | CODEX_* | PI_*

    LaunchPlan buildCreateCommand(CreateContext c);   // command + IdOutcome
    LaunchPlan buildResumeCommand(ResumeContext r);

    SessionIdStrategy idStrategy();                    // PRESET | DISCOVERED
    Optional<ConversationSource> conversations();      // empty = no catalog/probe
    Optional<ActivityReporter> activity();             // empty = no activity badges
}
```

`CreateContext`/`ResumeContext` carry an **`Optional<SshRemote> remote`** (see
"Remote sessions" below), so remote is a first-class input to the launch build,
not a `SessionManager` branch that bypasses the provider. `AgentCapabilities`
declares `supportsRemote()`; a provider that does not support remote returns a
`LaunchPlan` refusal for a remote context, and the UI gates accordingly.

**API (segregated; consumers depend only on these):**

- `Agent` — `kind()`, `displayName()`, `isAvailable()`.
- `LaunchService` — `buildCreate/ResumeCommand`; returns a `LaunchPlan` carrying
  the shell command string plus whether/how the id is obtained. `LaunchPlan`
  replaces today's bare command string so `finalizeCreate` stops assuming a
  pre-generated id.
- `SessionIdStrategy` — `PRESET` (app supplies id, command carries it) vs
  `DISCOVERED` (`discoverAfterLaunch(cwd, launchedAt)`).
- `ConversationSource` — `catalogFor(cwd)` + `transcriptExists(cwd, id)` (drives
  the missing-conversation probe). Per-provider layout.
- `ActivityReporter` — `install(settingsTarget)` + `watch(...)`. Both providers
  feed the same `SessionActivity` domain type so the badge UI is
  provider-agnostic.

### Remote (SSH) sessions

Remote is where the "route everything through the registry" claim needs care.
Today `SessionManager` branches on `remoteFor(...)` **before** touching
capabilities and uses Claude-specific ssh-wrapped builders
(`buildRemoteCreateCommand`/`buildRemoteResumeCommand`) with a distinct contract
(no env-cleanup prefix, no `--settings`, no `--session-id`). If the SPI ignored
remote, the refactor would either have to keep remote special-cased in
`SessionManager` — contradicting the seam — or silently break remote Claude.

So remote is modeled in the SPI, not bypassed:

- `CreateContext`/`ResumeContext` carry `Optional<SshRemote> remote`.
  `SessionManager` still decides *whether* a session is remote (it owns the
  repository/remote mapping) and passes that into the context, but the *command*
  is built by the provider, so the ssh-wrapped Claude builders move into
  `ClaudeAgentProvider` alongside the local ones. `SessionManager` no longer
  contains Claude command strings, remote or local.
- `AgentCapabilities.supportsRemote()` lets a provider decline. **Claude
  supports remote (behavior preserved); Codex and Pi return not-supported for
  now** — remote parity for them is explicitly out of scope, and a repo
  registered as remote offers only remote-capable agents in the picker.
- This keeps step 3 honest: remote Claude routes through the registry like local
  Claude, and its existing tests exercise the moved builders unchanged (aside
  from the package move).

## Domain model & state migration

- **`ManagedClaudeSession` → `ManagedAgentSession`**, adding `AgentKind
  agentKind`. `claudeSessionId`/`claudeSessionName` generalize to
  `agentSessionId`/`agentSessionName` (same semantics: "the id/name the tool
  itself assigns"; Codex's UUID and Pi's session id occupy the same slots).
- **`AgentKind` is a persisted enum;** its serialized form (`"claude"`,
  `"codex"`, `"pi"`) is a stable wire contract, documented as such.
- **Migration (backward-compatible), consistent with existing state rules:**
  - A session with **no** `agentKind` field decodes as `CLAUDE`. This is the
    clean half: it reuses the existing *lenient-additive* decode pattern already
    used for `prState`/`remote`/`branchCreatedHere`
    (`ApplicationStateCodec` — a missing new field takes a default, no new
    machinery).
  - An **unknown** `agentKind` value is the genuinely new case, and it is **not**
    analogous to `MISSING_WORKING_DIRECTORY` (that is a resume-time
    `SessionStatus`, computed in `checkResumeBlocked`; such sessions decode
    fine). Session decoding today is the **strict tier** — a malformed session
    throws `StateDecodeException`, and AGENTS.md reserves hard failure for
    repositories and sessions. Retaining an unknown-provider session therefore
    requires deliberate new decode machinery, not a free ride on an existing
    pattern. **Decision:** keep it small and explicit —
    - Add a decode path that, on an unrecognized `agentKind`, produces a
      session marked with a new terminal `SessionStatus.UNSUPPORTED_AGENT`
      (retained, never launched, surfaced disabled with "provider unavailable")
      **instead of** throwing. This is a scoped addition to the strict tier, and
      it is called out here as real work, not "matches an existing pattern."
    - Rationale: a Drydock build that dropped/renamed a provider must not nuke
      the user's session list. Alternative considered and rejected: hard-fail
      the whole state file (loses unrelated sessions) — unacceptable.
  - The codec accepts both `claudeSessionId` and `agentSessionId` for one
    migration window; it writes the new name.

## Session-id provenance & Codex resume

`SessionIdStrategy` has two modes:

- **`PRESET`** (Claude, Pi): the app generates a UUID; `buildCreateCommand`
  bakes it into the command (`--session-id` / `--session <id>`); `finalizeCreate`
  records it immediately. This is today's flow, unchanged.
- **`DISCOVERED`** (Codex): the id is captured **best-effort**. Snapshot+claim
  alone is *not* sufficient — it guarantees two launches bind *distinct* ids, but
  not *correct* ones. Two failure modes must be addressed head-on:
  - **Cross-binding:** two same-cwd Drydock launches started close together both
    snapshot before either rollout appears; the two new rollouts are then
    indistinguishable by `cwd`+`timestamp`, so A may claim B's id.
  - **External hijack:** a `codex` the user starts *outside* Drydock in the same
    cwd during the discovery window produces a fresh, cwd-matching, unclaimed
    rollout that Drydock would wrongly bind as its own.

  Both are solved only by an **ownership marker** — a value Drydock injects at
  launch that lands in the session's rollout and is unique per launch, so
  discovery matches on the marker rather than cwd+timestamp. **Whether such a
  marker is injectable and recorded is an open question the Codex spike (step 8's
  sibling) MUST answer** before `DISCOVERED` is considered reliable. Candidate
  markers to test empirically:
  - a `-c`/profile config override that surfaces in `session_meta.payload`;
  - a unique sentinel in the initial prompt (recorded as the first
    `response_item`), greppable in the rollout;
  - an env var recorded in `session_meta` (needs verification it is captured).

  The flow:
  1. **Immediately before** spawning, snapshot existing rollout ids under
     `~/.codex/sessions/**` and mint the per-launch marker.
  2. Launch with the marker embedded; record the session RUNNING with an empty
     `agentSessionId`.
  3. On the background executor, poll for a **new** rollout (not in the snapshot)
     whose `cwd` matches, `timestamp >= launchedAt`, **carries this launch's
     marker**, and whose id is not already claimed. The registry keeps a
     claimed-id set. First match wins; patch via `withAgentSessionId(...)`.
  4. Discovery failure (including "no injectable marker exists") leaves the id
     empty and is **not** a launch failure.

  If the spike finds **no** reliable marker, `DISCOVERED` degrades to
  cwd+timestamp best-effort with an explicit caveat: same-cwd concurrency and
  external launches may leave a session with an empty or (rarely) wrong id, and
  resume falls back to the picker (below). Resume-by-id is documented as
  best-effort, not guaranteed.

**Codex resume:**

- `agentSessionId` known (and, ideally, marker-verified) → `codex resume <id>`.
- id unknown (discovery failed/raced/no-marker) → fall back to the **`codex
  resume` picker** (cwd-filtered). **Never `--last`**, which with same-cwd
  siblings could resume the wrong session. The picker runs in the embedded
  terminal like any interactive Codex TUI; the spike also confirms the picker
  renders correctly in Drydock's terminal surface. The picker hands the choice
  to the user rather than guessing.

**Missing-conversation probe (Codex):** meaningful only once an id is known —
check that a rollout with that specific id still exists. Before discovery
completes there is nothing to probe, so no false "missing conversation."

## Session-creation UI, availability & default resolution

**Where the picker lives — both create paths.** There are *two* session-create
entry points today, and only one has a modal:

- **Worktree handoff** → `StartSessionModal` (from
  `MainWorkspace.promptStartWorktreeSession`). This modal exists and gains the
  selector.
- **Plain "New session"** → `MainWorkspace.openNewSession` calls
  `prepareSession`/`launchSession` **directly, with no modal** — it just drops a
  "Starting…" placeholder tab. This path has **no UI surface for the picker**.

So the picker is **net-new UI for the plain path**, not a free addition to an
existing modal. Decision: introduce a small shared **agent selector component**
(the segmented control + availability + preview) used in *both* places:
- `StartSessionModal` embeds it at the top.
- The plain path gains a lightweight create affordance hosting the same
  component before launch (a compact modal or an inline picker on the placeholder
  tab — exact form decided in the plan; the requirement is that no create path
  launches without an agent choice, defaulting per §default-resolution). This is
  explicitly scoped work in step 5, not assumed to already exist.

**The selector component.** A segmented control / small button group (not a
dropdown; ≤3–4 agents, advertises what is installed at a glance). Each agent
shows its `displayName` and availability:

- **Available** → selectable.
- **Unavailable** (not found) → shown disabled, with a tooltip listing where
  Drydock looked (per-provider `describeSearched()`).

**Command preview is async.** The preview renders the selected provider's
`buildCreateCommand`, which for Claude depends on the *async* capability probe
(`-n`/`--session-id`/`--settings` are capability-gated). So the preview cannot be
a trivial synchronous string swap: it shows a neutral placeholder until the probe
resolves, then updates on the FX thread. (Note: today's static
`$ claude --cwd <path>` preview is already inaccurate — the real command has no
`--cwd` — so this also *fixes* an existing wrongness.) The "Task for Claude"
label generalizes to the selected agent's name.

**Default resolution** (last-used-per-repo over availability-based global):

```
resolveDefault(repo):
  1. repo's last-used agentKind, IF that provider is currently available
  2. else global default: first available provider in the fixed preference
     order [CLAUDE, CODEX, PI], intersected with availability
  3. else none available → modal shows a blocked state explaining no agent CLI
     was found, with the searched-paths hint
```

- **Last-used-per-repo** persists as a new `RepositorySettings.lastUsedAgent`
  field, written on successful launch; falls back cleanly for never-used repos.
- The **global default is derived, not stored** — always "best available in
  preference order," so uninstalling Claude never strands the default.
- **Availability caching:** the registry probes `locateExecutable()` per
  provider once, off the FX thread, and caches (same contract as today's
  `ClaudeExecutableLocator`). No manual rescan; availability refreshes on
  restart.

## Activity reporting & conversation catalog, per provider

**Shared sink stays provider-agnostic.** The `activity/` directory, the
state-word files (`busy`/`idle`/`attention`/`end`), `SessionActivity`, and
`SessionActivityWatcher`'s polling loop are kept as-is and become the common
sink. Providers differ only in *how their hook writes those state words* — the
`ActivityReporter.install(...)` seam.

- **Claude `ActivityReporter`** = today's `ClaudeHookInstaller`, moved behind the
  API. Injects the sh hook via `--settings`, keyed by the Claude session id.
- **Codex `ActivityReporter`** = the analog, **if a viable mechanism exists**.
  What is actually confirmed: Codex has a hook system (the only direct evidence
  is `--dangerously-bypass-hook-trust` in `codex --help`) and profile/`-c` config
  layering. A `notify` program is **not** verified — it appears nowhere in
  `codex --help` or the installed config, so the earlier "hook system / `notify`"
  phrasing is downgraded to "hook system (mechanism TBD)." The intended design —
  inject, non-invasively via `-c` or a layered `$CODEX_HOME/<name>.config.toml`,
  a program that writes the *same* state words keyed by the Codex session id into
  the *same* `activity/` dir — is **contingent on the spike**, because Codex's
  **hook-trust model** may require either an interactive trust prompt or the
  dangerous bypass flag. Neither is acceptable silently, so "non-invasive" is a
  goal to be *proven*, not a property to assume.

**⚠️ Verification task (de-risking spike, gates Codex activity).** Before any
Codex activity work, verify empirically against the installed `codex`:
  - whether a hook can be registered non-invasively (no base-config edit, no
    per-run trust prompt, no dangerous bypass) — and if not, whether activity is
    worth the tradeoff or should be deferred;
  - which events fire and whether the payload carries the session id;
  - merge-vs-replace semantics with the user's existing hooks;
  - (shared with §4) whether an ownership marker is injectable and recorded, and
    whether the `codex resume` picker renders in Drydock's terminal surface.

Until verified, Codex activity badges are a *should*; the design degrades cleanly
(`activity()` returns empty → no badge, session still launches).

**Conversation catalog** is per-provider via `ConversationSource`:

- **Claude** = today's `ConversationCatalog`, moved behind the API.
- **Codex** = new impl reading `~/.codex/sessions/YYYY/MM/DD/rollout-*-<uuid>.jsonl`,
  using `session_meta.payload.{id,cwd}` to filter by working directory; missing
  probe checks a rollout with the discovered id still exists.
- The catalog UI consumes only `ConversationSource`; a provider without one (or
  Pi, pre-impl) simply shows no catalog — no special-casing.

## Implementation sequence

Each step is independently reviewable and green.

1. **Introduce the API/SPI + registry, empty.** `agent.api`, `agent.spi`,
   `AgentRegistry` (ServiceLoader-backed), `AgentKind`, `AgentContext`,
   `LaunchPlan`, `SessionIdStrategy`, capability interfaces. Nothing wired.
2. **Wrap Claude as the first provider — behavior-preserving.**
   `ClaudeAgentProvider` delegates to the existing Claude classes (moved under
   `providers/claude/internal`). Register in `META-INF/services`. No call sites
   change yet.
3. **Route `SessionManager` through the registry.** Replace direct Claude calls
   with registry + API lookups. Claude is still the only provider, so behavior
   is identical — existing `SessionManager` tests must pass **unchanged** (the
   safety net for "zero behavior change").
4. **Domain rename + state migration.** `ManagedClaudeSession` →
   `ManagedAgentSession`, `agentKind`, codec back-compat (missing → `CLAUDE`),
   new `SessionStatus.UNSUPPORTED_AGENT` for unknown kinds. Pure mechanical
   rename pass, no assertion changes (see Testing).
5. **UI picker + default resolution + availability.** Includes the **net-new
   create affordance for the plain "New session" path** plus the shared selector
   component embedded in `StartSessionModal`, async command preview, and
   `RepositorySettings.lastUsedAgent`. With one provider it shows "Claude"
   preselected; still fully exercised.
6. **Codex spike (de-risking, gates 6b/8).** Empirically verify against the
   installed `codex`: ownership-marker injectability + recording, `codex resume`
   picker rendering in the terminal surface, and the activity hook-trust
   contract. Findings determine whether `DISCOVERED` is reliable-by-marker or
   best-effort, and whether Codex activity ships.
6b. **Codex provider — declarative + launch/resume.** `CodexAgentProvider`:
   locate, capabilities (`supportsRemote()=false`), `buildCreate/ResumeCommand`,
   `DISCOVERED` id strategy (snapshot+claim+marker per spike), env-scrub
   (`CODEX_*`, keep `CODEX_HOME`). Launch + resume work.
7. **Codex `ConversationSource`** — rollout-dir catalog + missing probe.
8. **Codex `ActivityReporter`** — only if step 6 confirmed a non-invasive hook;
   else deferred without blocking anything above.
9. **Document the extension recipe in `AGENTS.md`** (see below).

Steps 1–5 are pure refactor + UI (Claude only); 6–8 add Codex, gated by the
step-6 spike. Pi is a later spec adding one provider class + one service line.

## `AGENTS.md`: "Adding an agent provider"

A new section is added to `AGENTS.md`, a checklist mirroring the SPI, citing
`CodexAgentProvider` as the reference implementation:

1. Add an `AgentKind` enum constant and its stable serialized wire name.
2. Create `agent/providers/<x>/<X>AgentProvider` implementing the SPI; keep
   internals under `providers/<x>/internal`.
3. Register it in `META-INF/services/app.drydock.agent.spi.AgentProvider` (and
   note the future `provides` target alongside).
4. Implement the required core — locate, capabilities, build create/resume,
   env-scrub, id-strategy. Return `Optional.empty()` from `conversations()` /
   `activity()` until those are built.
5. **Empirically verify the CLI's activity-hook contract before implementing
   `ActivityReporter`** (the Codex spike is the worked example).
6. Add provider unit tests + a registry availability/default case, and slot the
   agent into the picker preference order.

## Testing

- **Regression safety net:** existing `SessionManager` / command-builder /
  `ConversationCatalog` / activity tests pass **unchanged** through step 3, which
  is the strongest guard that the Claude path did not move. Be honest about its
  limit: step 4 renames `ManagedClaudeSession` → `ManagedAgentSession`, which is
  wide mechanical churn across the suite (`SessionManagerTest`,
  `WorkspaceViewModelTest`, `ManagedClaudeSessionTest`, codec/repo/branch tests).
  After step 4 the guarantee weakens from "byte-identical tests" to "same
  assertions, renamed symbols" — so step 3 must land and go green *before* the
  rename, and the rename must be a pure mechanical pass with no assertion
  changes, reviewed as such.
- **Provider unit tests:** each provider's `buildCreate/ResumeCommand`,
  env-scrub, and id-strategy in isolation via the SPI. Codex's snapshot+claim
  discovery against a fixture `sessions/` tree (fake rollout `.jsonl` files),
  including the same-cwd-concurrency claim case.
- **Registry tests:** ServiceLoader discovery; availability computation (fake
  locator); default resolution (last-used-per-repo, availability-based global
  fallback, none-available).
- **Codec/migration tests:** missing `agentKind` → `CLAUDE`; unknown `agentKind`
  → quarantined not fatal; old `claudeSessionId` field still readable.
- **UI:** picker renders availability/disabled state; command preview matches the
  selected provider's `buildCreateCommand`.
- **Diag seam:** the existing `app.drydock.diag.command` override is a *single
  global* system property that replaces the whole command and derives
  `sessionIdUsed` via `command.contains(<uuid>)` — it cannot disambiguate which
  provider is selected. Generalize it to a **provider-keyed** scheme
  (e.g. `app.drydock.diag.command.<kind>`) so a test can force a specific
  provider's command deterministically, with the un-keyed form retained as a
  fallback for existing tests.

## Risks & open questions

- **Codex id discovery can mis-bind, not just fail (highest risk).** Snapshot+
  claim prevents duplicate binding but not *wrong* binding under same-cwd
  concurrency or an external `codex` launch. Correctness depends on an
  **ownership marker** whose existence is unverified — the step-6 spike must
  resolve it. If no marker exists, resume-by-id is documented as best-effort with
  a picker fallback (§4), never a silent wrong resume.
- **Codex activity-hook contract is unverified**, and Codex's **hook-trust
  model** may make "non-invasive" injection impossible. Mitigated by the step-6
  spike and clean degradation to no-badge; activity may be deferred.
- **Plain "New session" path needs net-new UI.** The picker cannot ride an
  existing modal for the most common create path (there is none). Scoped into
  step 5 as a shared selector component + a create affordance.
- **`UNSUPPORTED_AGENT` decode is new machinery**, not a reuse of an existing
  pattern (§domain). Deliberate strict-tier addition so a dropped provider never
  nukes the session list.
- **Remote (SSH) is modeled in the SPI but only Claude implements it.** Codex/Pi
  return `supportsRemote()=false`; remote parity for them is out of scope, and
  remote repos offer only remote-capable agents in the picker.
- **Codex `-C/--cd` vs terminal cwd.** Launch may set cwd via the terminal (as
  today) and/or `-C`; the provider chooses. To confirm during step 6b.
