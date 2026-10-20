# Cross-project tabs spike: results

> Run unattended: the Mac's screen was locked for nearly all of it, so nothing could be clicked or
> typed for real. Every experiment was driven from the shell through a spike-only control channel
> inside the IDE (`SpikeControl`, files in `/tmp/drydock-spike`). Where a check needed a human
> (real-keyboard key matrix, live 263 run) it says so. Raw notes: `NOTES.md`.

## Environment
- IDE build: IntelliJ IDEA Ultimate 2025.3.3, IU-253.31033.145 (runIde sandbox). 263.7031.23 for the H1
  repeat — **blocked**, see H1.
- OS / RAM / CPU: macOS 26.6.2 (Darwin 25.6.0), 32 GB, Intel Core i7-9750H @ 2.60 GHz (6 cores / 12 threads).
- JDK (IDE runtime / project): JBR 21.0.10 / JDK 25.0.2 (Liberica; java-profiler's Gradle JVM is "25").
  jdtls snapshot `org.eclipse.equinox.launcher_1.8.0.v20260804`, run on the same JDK 25.0.2.
- Agent provider(s) used: Claude Code (`claude -p` for H1 agent calls; idle interactive `claude` as the
  H3 agents); a fake provider (Python, raw tty: numbered lines, then echoes key bytes) for H2/H5.
- Plugin commit (spike branch): `spike/cross-project-tabs` from `ec7be6c9`, spike code uncommitted
  (`intellij-plugin/src/platform/java/app/drydock/intellij/spike/`, plus `runIde263` in
  `build.gradle.kts` and two `plugin.xml` registrations).
- Fixture: H = `~/src/java-profiler` (main 46d65af8c), F1 = `~/dev/wt/java-profiler-spike-f1`
  (branch `spike/f1`, 935ee564e: `SpikeF1.spikeOnlyInF1()` + 1 caller, 2 extra
  `OperatingSystem.current()` callers, an `F1-ONLY-MARKER` first line in `Arch.java`), F2 = `~/src/jmc`
  (master, Maven). H3 also used F1b/F1c/F1d (`spike/f1b..f1d`, same content as F1).

## H1 — Built-in MCP vs a worktree that isn't open
Built-in MCP takes `projectPath`? **yes** — every one of the 22 tools (`artifacts/h1-tools.json`). It also
routes by the `IJ_MCP_SERVER_PROJECT_PATH` request header. **There is no find-usages tool in 253**; the
closest are `search_in_files_by_text`/`_by_regex` and `get_symbol_info` (at a position), used below.

Only W_H open (F1 not open):

| Tool | Query | No projectPath: answered from | projectPath=F1: answered from | Error text (if any) |
|---|---|---|---|---|
| find usages (text search) | `spikeOnlyInF1` | H (0 hits, no error) | error | "`projectPath`=`…/java-profiler-spike-f1` doesn't correspond to any open project. … Currently open projects: …/java-profiler" |
| find usages (text search) | shared `OperatingSystem.current()` (H callers=4, F1 callers=6) | H (4 hits) | error | same |
| file text | `Arch.java` (differs) | H (no marker) | error | same |
| diagnostics | `SpikeF1.java` (F1-only) | error — "File not found: ddprof-lib/…/SpikeF1.java" (reads as a missing file, not a wrong project) | error | same |
| run config / gradle | `get_run_configurations` | H (`[]`) | error | same |

Header `IJ_MCP_SERVER_PROJECT_PATH`=F1 instead of the argument: every call errors the same way
("Project path specified via header variable … doesn't correspond to any open project").

With W_F1 **also** open: no-routing calls all refuse ("No exact project is specified while multiple
projects are opened"); projectPath=F1 or header=F1 answer **from F1** correctly (2 hits for
`spikeOnlyInF1`, 6 for the shared symbol, `Arch.java` with the marker, `SpikeF1.java` diagnostics).

`claude -p` started in F1 with the server attached the way JetBrains' own "Auto-configure Claude Code"
writes it (`jetbrains` → SSE URL, no header) **passed `projectPath=<cwd>` on every call unprompted** (the
tool descriptions ask it to) and got the clean error each time; it reported "none" as the source. Same
with the header attached. So no H answer reached that agent.

Can Drydock detect or block a wrong-tree answer? How? **Yes, by routing every call.** A call that names
its project — by `projectPath`, or by the `IJ_MCP_SERVER_PROJECT_PATH` header that Drydock can put into
the MCP config it hands the agent it launches — gets either the right tree or a clean, catchable error.
The silent-H case needs both: no routing *and* H the only open project. Drydock cannot rely on agents
passing `projectPath` (Claude does; Codex/pi not tested), and an `--mcp-config` entry Drydock writes does
not stop a user-level "jetbrains" entry without the header (precedence not tested).
**Verdict:** go — conditional on Drydock injecting the routing header for hosted sessions (or 263's
`mcpProjectPathCustomizer`), and with the anomaly under "Surprises" (H-routed text search silently
empty while W_F1 is open) understood first.

263 repeat: **not run.** `runIde263` starts IU-263.7031.23 with the same plugin, then sits on JetBrains'
End User Agreement dialog; accepting a licence on the owner's behalf was not mine to do. From its jars:
263 adds streamable HTTP (`/stream`), `IJ_MCP_ALLOWED_TOOLS`, `IJ_MCP_CLIENT_TAGS`, header-prefix
routing, and extension points `mcpProjectPathCustomizer`, `mcpManagedSessionSupport`,
`mcpToolFilterProvider`; toolsets grow (Search, Read, Patch, VCS, ChangeList, Diagnostics, IdeScript,
Universal).

## H2 — Console move between windows
Prototype: an app-level `SpikeExecutionRegistry` owns the `KillableProcessHandler` (PTY) and a replay
buffer; **one** `ProcessListener` per process appends to the buffer and forwards to the current console
under one lock. Consoles are `TerminalExecutionConsole(project, 120, 30, null)` +
`attachToProcess(handler, new ProcessHandlerTtyConnector(handler, UTF_8), /*output*/ false)`, hosted in
a per-project "Drydock Spike" tool window (not an editor tab; the Swing reparenting is the same).
Fake provider: `LINE 000001…003000` at 5 ms intervals; the jump fires 2 s in, mid-stream.

| Variant | PID same | Lines expected / received | Dupes | Key matrix pass | Host-close kills it? | Quit asks once? |
|---|---|---|---|---|---|---|
| (a) move component | yes (7/7 runs) | 3000 / 3000, 0 gaps (7/7) | 0 | 14/15 via `JBTerminalPanel.handleKeyEvent` + paste ✓; Esc ✗ (harness — also fails on an unmoved console); **real keyboard not verified** | no — process and console keep working in W_F1 after W_H closes | not tested (registry not wired into `AgentCloseConfirmation`) |
| (b) new console + replay | yes (7/7 runs) | 3000 / 3000, 0 gaps (7/7) | 0 | same as (a) | no — same | not tested |

Jump timing (median warm / cold), ms to window / ms to input:
(a) warm 10 / 11 (n=5: 80, 65, 11, 2, 2), cold 1266–1459 / 2742–2760 (n=2);
(b) warm 8 / 11 (n=5: 11, 5, 6, 42, 41), cold 1094–4002 / 2301–4905 (n=2).
"Input" = the console's input component showing in W_F1; keyboard focus could not be observed (locked
screen: no window ever became the focus owner). Cold = W_F1 not open (its index already on disk).

Preferred variant and why (≤ 3 lines): **(b).** The console is built for the window it lives in, so
nothing holds W_H's `Project` after the move — (a)'s console survived W_H's disposal here but still
references a disposed project. The replay buffer needs a cap (a TUI's history is unbounded).
**Verdict:** go — both variants meet PID/0-loss/≤ 3 s warm; the real-keyboard key matrix (incl. Esc)
still needs one human pass.

## H3 — Memory
One sample per configuration; IDE restarted with only W_H before each. A = hosted sessions in W_H, each
with Drydock's JDT LS (`JdtServerManager.references`); B = one window per worktree. Agents are idle
interactive `claude` (≈ 470 MB each incl. helper processes from the user's Claude config). Full method
and caveats: `artifacts/h3-method.md`; raw: `artifacts/h3-raw.jsonl`.

| Config | N | IDE heap MB | IDE RSS MB | JDT RSS MB | Agents RSS MB | First usages ms | Warm usages ms | Disk MB |
|---|---|---|---|---|---|---|---|---|
| A hosted | 1 | 361 | 2192 | 889 | 473 | 71833 | 39 | 42 (lsp) |
| A hosted | 3 | 629 | 1907 | 2334 | 965 | 69826 (max 70343) | 21 | 125 (lsp) |
| A hosted | 5 | 378 | 2423 | 4182 | 1297 | 69255 (max 70141) | 23 | 209 (lsp) |
| B windows | 1 | 391 | 2538 | 0 | 471 | 28210 * | 25 | n/a † |
| B windows | 3 | 578 | 3983 | 0 | 1433 | 35541 * (max 44571) | 32 | n/a † |
| B windows | 5 | 732 | 3354 | 0 | 1910 | 27069 * (max 45839) | 20 | n/a † |

B also started build daemons the A runs did not: 0 / 2089 / 1346 MB (N = 1 / 3 / 5).

B: time for indexing to finish per window (ms): N=1: 7599. N=3: 7927, 14993, 23716 (jmc). N=5: 6318,
4452, 4359, 8413, 25242 (jmc).

\* B's "first usages" is the MCP server's routed text search, from window open, and includes a 20 s quiet
wait after indexing — compare A against B's indexing times above. A's first answer is ~10 s of jdtls
plus a fixed 60 s `JdtServerManager.NO_IMPORT_TASK_MILLIS` wait (these jdtls runs never reported an
import task). † IDE indexes for these worktrees were partly cached from earlier runs; not measured
cleanly.

Against the decision rule, N=3: A's (IDE RSS + JDT RSS) = 4241 MB vs B's 3983 MB → A is **106 %** of B
(N=5: 6605 vs 3354 → 197 %). A's first usages answer (≈ 70 s) is later than B's indexing (8–24 s). Even
without the 60 s timer (≈ 10 s) memory stays far above 80 %.
**Verdict:** not worth it — on memory. A jdtls per hosted worktree costs about what a window costs
(0.8–0.9 GB each), so hosting saves no memory; what it saves is window clutter, not resources.
Caveats: one sample each; RSS is noisy (B5 < B3); B's IDE windows for java-profiler did not always
finish their Gradle import in this sandbox (see Surprises), so B may be under-counted.

## H4 — Opening files
- Default behaviour in W_H: `FileEditorManager.openFile` on an F1 path opens an editor in W_H (91–134 ms)
  as a **non-project file**: not in content, no module, no VCS root in H, and the non-project write
  guard denies edits (`NonProjectFileWritingAccessProvider.isWriteAccessAllowed` = false). Syntax colours
  only; the editor shows "OFF" (no code analysis); no "outside project" banner; the breadcrumb shows
  the full foreign path (`artifacts/h4-before.png`).
- Redirect works? Time (ms): **yes.** A path under a known worktree root opens in that root's window
  (`TaskWindows.openAsProject` if needed): cold (W_F1 not open) 3126 / 3517 ms total (window 1593 /
  3099); warm 752 / 348 ms. In the owner the file is in content, module `java-profiler-spike-f1`, VCS
  root F1 (`artifacts/h4-redirect.png`). Comparable to H2's cold jump; slower than its warm move, since
  it also opens an editor.

## H5 — Lifecycle
| # | Case | Observed | Wanted | Class to change |
|---|---|---|---|---|
| 1 | Host window closes, hosted sessions working | Registry prototype: process keeps running, console orphaned (no window shows it; host reported DISPOSED). Product today: `AgentWindowCloseListener` + `AgentExecutionService.dispose()` stop every agent in the closing window. | Ask, like the close confirmation does; on "keep running", re-home the console (into its own window or the next host) rather than orphan it. | `AgentWindowCloseListener`, `AgentCloseConfirmation`, `AgentExecutionService` (ownership moves to an app-level registry) |
| 2 | IDE restart: hosted tabs restored? | Prototype: no — nothing is persisted (observed over ~12 restarts). Product (by code): pinned `drydock-agent://` tabs are restored by the platform in the window that had them, so a hosted tab would come back in W_H as a stopped session. | Do not restore a hosted tab in the host; the tool window's task list is the way back. | `AgentConsoleFileSystem` / `AgentConsoleEditorProvider` |
| 3 | Attention from a hosted session | By code: `AttentionNotifier.windowRunning` returns the window whose `AgentExecutionService` runs it (= the host), raises that window and labels the balloon with the host's identity, not `project · branch` of the worktree. | Raise the console's current host, label with the worktree's identity. | `AttentionNotifier` |
| 4 | Same session hosted + owning window open | Prototype: a jump moves the one console (never two). Product (by code): one-agent-per-session is enforced per window (`AgentExecutionService.start`), so Resume/Start in W_F1 while W_H hosts it would spawn a **second agent** on the same session. | Block, and focus the existing console. | `AgentExecutionService`, `AgentConsoles.start/resume`, `DrydockSessionService` |
| 5 | Agent edits a file open as a non-project file | The W_H document stayed stale for ≥ 60 s; the VFS timestamp never changed (F1 is outside every watched root while W_F1 is closed). Frame activation would refresh — not testable with the screen locked. | Refresh those files when the hosted agent's turn ends, or route the open through H4's redirect. | the registry (refresh on activity → idle) |
| 6 | Foreign folder missing | Agent keeps running in a deleted cwd; console fine; nothing notices. (Throwaway `/tmp` folder, not a real repo.) | Detect it and mark the session gone, as the tool window already does for a removed worktree. | the registry / `DrydockSessionService` |

## Surprises / internal-API dependencies
- **A second window of the same repo empties text search in the first.** With W_F1 open, H-routed
  `search_in_files_by_text`/`_by_regex` returned `{"entries":[]}` in ~10 ms (4 hits before W_F1 opened,
  4 again after it closed), while `find_files_by_glob` in H still worked. Silent and reproducible here;
  not explained (no log line). This is a wrong answer *with* correct routing — worth a follow-up
  before trusting the IDE MCP for the multi-window (B) world.
- **Sandbox imports were unreliable after restarts.** H reported 0 modules via `get_project_modules`
  although `.idea/gradle.xml` lists them; the PSI `ReferencesSearch` returned 0 there and in reopened
  F1 windows ("OFF" highlighting), but the right count (1) in freshly opened F1b/F1c. Affects B's numbers
  and the IDE-side "usages" only; the MCP text search and jdtls were unaffected.
- `JdtServerManager` adds a fixed 60 s to every cold first answer when jdtls reports no import task
  (`NO_IMPORT_TASK_MILLIS`); jdtls was ready after ~10 s in every run.
- `JBTerminalPanel` takes keys through an `IdeEventQueue` dispatcher installed on focus gain;
  AWT-dispatched/posted `KeyEvent`s never reach it without focus. `handleKeyEvent` (public) is the
  dispatcher's entry point.
- Disposing the old console in variant (b) does not close the process (its `ProcessHandlerTtyConnector`
  is not tied to the process lifetime).
- API status: the spike code was not run through the Plugin Verifier. Known from the MVP's verifier run
  against 263: `TerminalExecutionConsole(project, handler)` is deprecated there (the spike uses the
  `(project, cols, rows, handler)` constructor plus `attachToProcess(handler, tty, false)`).
  `McpServerService.authorizedSession` (token-scoped MCP sessions in 253) lives in an `impl` package;
  `JBTerminalPanel.handleKeyEvent`, used only by the test harness, is public.
- Sandbox setup traps that block an unattended run: the New-UI onboarding modal on a fresh sandbox,
  "This window / New window" on opening a second project, the Trust dialog, and 263's EUA. The first
  three were pre-set in the sandbox config (`NOTES.md`); the fourth needs a human.

## Raw artifacts
- [x] artifacts/h1-tools.json
- [x] artifacts/h1-calls.jsonl (+ h1-raw-253.jsonl, h1-raw-253-f1open.jsonl, h1-agent-claude-*.stream.jsonl)
- [x] artifacts/h2-run-a.log, h2-run-b.log (+ h2-run-a.attempt0-no-key-route.log, h2-h5-host-close.json)
- [x] artifacts/h2-jump-timing.csv
- [x] artifacts/h3-memory.csv, h3-method.md (+ h3-raw.jsonl)
- [x] artifacts/h4-before.png, h4-redirect.png (+ h4-results.txt)
- [ ] screen recordings of (a)/(b) — not made (locked screen)
- [x] h5-case5-stale-buffer.txt, h5-case6-folder-missing.txt
