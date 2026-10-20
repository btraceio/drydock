# IntelliJ MVP fixes before cross-project tabs — plan

**Goal:** land the four fixes the cross-project-tabs handoff (v1.1) assumes
are merged, as one PR off `main`, before any v1.1 work starts.

**Source:** design handoff `design_handoff_drydock_cross_project_spike/`
(HANDOFF.md "Before you build", DECISION.md), spike results
`docs/spikes/cross-project-tabs/RESULTS.md` (branch `spike/cross-project-tabs`),
and the user's report: *stop, then resume, starts a new conversation instead
of continuing the stopped one.*

**Branch:** new `fix/intellij-mvp-followups` from `origin/main` (55a824d0).
The current worktree branch is PR #33, already merged.

## Fix 0 — Resume after Stop starts a fresh conversation (user-reported)

**Reported:** Codex always goes Running → Stop → **Start**, never Resume,
and Start then opens a fresh conversation. Once, Start instead failed with
"Session … already has a running agent in this window". Pi showed Resume
and worked.

**Established live (runIde, 2026-10-20):**
- Claude and Pi: Stop → Resume continues the same conversation, from the
  strip and from the tool window (including Stop & Close Window).
- The first hypothesis (the Resume/Start choice racing the late id bind) is
  refuted for both: the id was bound while the agent was running.

**Codex leads, not yet confirmed:**
- Drydock learns a Codex id only from
  `~/.codex/sessions/<date>/rollout-*.jsonl` (`CodexRolloutStore`: a
  first-line `session_meta` with `source == "cli"` and an exact `cwd`).
- Codex 0.160 rollouts on this machine have their first line written 7–67 s
  after the thread's own timestamp. That suggests the file appears only with
  the first turn, after the ~30 s discovery window.
- Codex 0.160 also runs a managed `app-server` daemon and new SQLite stores
  (`state_5.sqlite` `threads`, `thread_history_1.sqlite`).
- The "already has a running agent" error means the window still held a live
  handler after Stop. That needs explaining before any fix.

**Steps:**
1. Get the user's IDE log lines for the failing session (id discovery, late
   bind, start failure), or reproduce Codex live in `runIde`.
2. Write the failing test for the cause found, in `DrydockSessionServiceTest`
   or as a `CodexRolloutStore` test against a real 0.160 rollout shape.
3. Fix, then re-run the live Codex Stop → Resume.

## Fix 1 — Agents Drydock launches reach the IDE's MCP server, routed to their own worktree

**Today:** the plugin passes no MCP config to agents
(`ResumeContext`/`CreateContext` get `Optional.empty()`). None of the
user's agent configs has a JetBrains entry, so agents don't use the IDE MCP
server at all.

**Design:**
- Attach the IDE's bundled server through its own stdio launcher.
- In 253, `StdioRunnerUtilKt.createStdioMcpServerCommandLine` builds a
  `java -cp … McpStdioRunnerKt` command that reads the env vars
  `IJ_MCP_SERVER_PORT` and `IJ_MCP_SERVER_PROJECT_PATH`.
- Drydock sets `IJ_MCP_SERVER_PROJECT_PATH = <session worktree>` per launch.
- One stdio entry then serves Claude (`--mcp-config`), Codex
  (`-c mcp_servers…`) and Pi (bridge extension). An SSE entry doesn't: 253
  is SSE-only and Codex speaks streamable HTTP only.
- Only attach when the IDE MCP server is enabled. Never turn it on for the
  user.

**Steps:**
1. Verify live:
   - the runner forwards the env var as the routing header;
   - a Claude session in worktree F gets F's answer, or the clean
     "doesn't correspond to any open project" error, never H's.
2. Add an `ideMcp` launch input to the shared contexts. **This changes the
   agent SPI (public API), so it needs your OK.**
3. Each provider renders it in its own delivery form:
   - Claude: an entry in the config file;
   - Codex: `-c` with `env`;
   - Pi: the bridge.
4. Add per-provider unit tests on the built command or config, plus one
   `runIde` check per agent.

## Fix 2 — One agent per session across all windows

**Today:** `AgentExecutionService.start` refuses a second execution only
within its own project. Resume or Start in window B while window A runs the
session spawns a second agent (spike H5 case 4).

**Design:**
- Before starting, look across every open project's
  `AgentExecutionService`. If one already runs the session, raise that
  window and focus its console instead of starting.
- Keep the check in `AgentConsoles.start`, the single entry point for
  resume and start, plus a guard in `AgentExecutionService.start`.
- This is deliberately not the v1.1 registry. The registry replaces it
  later.

**Steps:**
1. Add a platform test: two projects; start in A; resume in B. Assert one
   process, and that A is focused.
2. Fix.

## Fix 3 — The first usages answer no longer waits a fixed 60 s

**Today:**
- After `ServiceReady`, `JdtServerManager` waits `NO_IMPORT_TASK_MILLIS =
  60_000` for an import task before treating the workspace as cached.
- In the spike, jdtls never reported one and was ready in about 10 s, so
  every cold answer took about 70 s.

**Design:**
- Shrink the wait to a short grace period (proposed 3 s). The `ServiceReady`
  → import-task gap observed in the spike logs decides the exact value.
- Keep the import-task path unchanged.

**Steps:**
1. Update `JdtServerManagerTest` with the `ManualScheduler`: answers no
   longer wait 60 s, and a late import task still gates them.
2. **Open question:** the app tree has the identical constant
   (`app/src/main/java/app/drydock/lsp/JdtServerManager.java:70`). Fix both
   copies, or only the plugin's?

## Gates
- `cd intellij-plugin && ./gradlew test buildPlugin` → all green.
- App tree, only if fix 3 touches it: `./gradlew :app:test --tests '*JdtServerManager*'`.
- `runIde` checks:
  - fix 0: the live repro;
  - fix 1: one call per agent, routed;
  - fix 2: two windows, a single process.
- Commits: one per fix, AGENTS.md style.

## Not in this PR
- v1.1: the registry, the replay buffer, and screens F1–F5. That gets its
  own plan after this merges.
- The handoff's human checks: the real-keyboard key matrix with Esc, and the
  MCP "second window empties text search" repro on a normal IDE install.
