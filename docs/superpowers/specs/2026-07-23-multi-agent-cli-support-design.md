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

## Domain model & state migration

- **`ManagedClaudeSession` → `ManagedAgentSession`**, adding `AgentKind
  agentKind`. `claudeSessionId`/`claudeSessionName` generalize to
  `agentSessionId`/`agentSessionName` (same semantics: "the id/name the tool
  itself assigns"; Codex's UUID and Pi's session id occupy the same slots).
- **`AgentKind` is a persisted enum;** its serialized form (`"claude"`,
  `"codex"`, `"pi"`) is a stable wire contract, documented as such.
- **Migration (backward-compatible), consistent with existing state rules:**
  - A session with **no** `agentKind` field decodes as `CLAUDE`.
  - Session decoding is otherwise strict, but an **unknown** `agentKind` value is
    **quarantined** (retained in state, surfaced disabled as "provider
    unavailable"), *not* a corrupt-state hard failure — matching the existing
    "missing working directory" degraded-but-preserved pattern, so a Drydock
    that dropped a provider never nukes sessions.
  - The codec accepts both `claudeSessionId` and `agentSessionId` for one
    migration window; it writes the new name.

## Session-id provenance & Codex resume

`SessionIdStrategy` has two modes:

- **`PRESET`** (Claude, Pi): the app generates a UUID; `buildCreateCommand`
  bakes it into the command (`--session-id` / `--session <id>`); `finalizeCreate`
  records it immediately. This is today's flow, unchanged.
- **`DISCOVERED`** (Codex): the id is captured reliably even with **multiple
  concurrent sessions in the same cwd**, via snapshot-and-claim:
  1. **Immediately before** spawning, snapshot the set of existing rollout ids
     under `~/.codex/sessions/**` (or a high-water timestamp).
  2. Launch (command carries no id); record the session RUNNING with an empty
     `agentSessionId`.
  3. On the background executor, poll for a **new** rollout (not in the snapshot)
     whose `session_meta.payload.cwd` matches and `timestamp >= launchedAt`,
     **and whose id is not already claimed** by another live
     `ManagedAgentSession`. The registry keeps a claimed-id set so two
     simultaneous same-cwd launches cannot bind the same file. First unclaimed
     match wins; patch via `withAgentSessionId(...)`.
  4. Discovery failure leaves the id empty and is **not** a launch failure.

**Codex resume:**

- `agentSessionId` known → `codex resume <id>` (the reliable path; the reason
  discovery matters).
- id unknown (discovery failed/raced) → fall back to the **`codex resume`
  picker** (cwd-filtered). **Never `--last`**, which with same-cwd siblings could
  resume the wrong session. The picker hands the choice to the user rather than
  guessing.

**Missing-conversation probe (Codex):** meaningful only once an id is known —
check that a rollout with that specific id still exists. Before discovery
completes there is nothing to probe, so no false "missing conversation."

## Session-creation UI, availability & default resolution

**The picker.** `StartSessionModal` gains an agent selector at the top — a
segmented control / small button group (not a dropdown; ≤3–4 agents, and it
advertises what is installed at a glance). Each agent shows its `displayName`
and availability:

- **Available** → selectable.
- **Unavailable** (not found) → shown disabled, with a tooltip listing where
  Drydock looked (per-provider `describeSearched()`).

The command preview becomes provider-driven: it renders the selected provider's
`buildCreateCommand` preview (`$ codex -C …`, `$ claude …`), so what is shown
matches what launches. The "Task for Claude" label generalizes to the selected
agent's name.

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
- **Codex `ActivityReporter`** = the analog. Codex has a hook system (per
  `--dangerously-bypass-hook-trust`) and a `notify` config; the reporter injects
  — **non-invasively**, via `-c` override or a layered
  `$CODEX_HOME/<name>.config.toml`, **not** the user's base config — a
  hook/notify program that writes the *same* state words keyed by the Codex
  session id into the *same* `activity/` dir. The watcher does not change.

**⚠️ Verification task (de-risking spike, gates Codex activity):** Codex's exact
hook/notify contract — which events fire, whether the payload carries the session
id, merge-vs-replace semantics, and any trust prompts — must be **verified
empirically against the installed `codex`** before implementation, mirroring how
the Claude hooks were verified against claude 2.1.215. Until verified, Codex
activity badges are a *should*; the design degrades cleanly (`activity()` returns
empty → no badge, session still launches).

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
4. **Domain rename + state migration** (`ManagedAgentSession`, `agentKind`,
   codec back-compat + quarantine).
5. **UI picker + default resolution + availability.** With one provider it shows
   "Claude" preselected; still fully exercised.
6. **Codex provider — declarative + launch/resume.** `CodexAgentProvider`:
   locate, capabilities, `buildCreate/ResumeCommand`, `DISCOVERED` id strategy
   (snapshot+claim), env-scrub (`CODEX_*`, keep `CODEX_HOME`). Launch + resume
   work. No activity/catalog yet.
7. **Codex `ConversationSource`** — rollout-dir catalog + missing probe.
8. **Codex activity spike → `ActivityReporter`** — gated on the empirical
   verification above; ships only if the hook contract checks out, else deferred
   without blocking anything above.
9. **Document the extension recipe in `AGENTS.md`** (see below).

Steps 1–5 are pure refactor + UI (Claude only); 6–8 add Codex. Pi is a later
spec adding one provider class + one service line.

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
  `ConversationCatalog` / activity tests pass **unchanged** through step 3
  (rename aside) — the primary guard that the Claude path did not move.
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
- **Diag seam:** the existing `app.drydock.diag.command` override generalizes so
  tests can force a provider's command deterministically.

## Risks & open questions

- **Codex activity-hook contract is unverified.** Mitigated by the gating spike
  (step 8) and clean degradation to no-badge.
- **Codex id discovery is inherently racy.** Mitigated by snapshot+claim and the
  picker fallback; discovery failure never blocks a launch.
- **Codex `-C/--cd` vs terminal cwd.** Launch may set cwd via the terminal (as
  today) and/or `-C`; the provider chooses. To confirm during step 6.
- **Remote (SSH) sessions for Codex/Pi** are out of scope for this spec; the
  existing degraded remote contract remains Claude-oriented until a provider
  opts in.
