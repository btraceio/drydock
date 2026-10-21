# Drydock IntelliJ: cross-project agent tabs (spike)

**Goal:** decide whether one IDE window can host agent console tabs for sessions from **other projects or worktrees**. Each hosted tab shows where it comes from (`project · branch`) and has a one-click jump that opens the owning project window.

**This is an investigation, not a build.** Write throwaway code on a branch (`spike/cross-project-tabs`). Don't merge it. What you deliver is `RESULTS.md`, filled in using the template in this folder, plus the raw artifacts listed under each spike.

Codebase: `intellij-plugin/`. Relevant classes:
- `intellij/agent/AgentExecutionService`: per-`Project` map from session to process and console. **Ownership lives here today.**
- `intellij/agent/AgentConsoles`, `AgentConsoleEditor`, `AgentConsoleEditorProvider`, `AgentConsoleTabPresentation`: the console tab.
- `intellij/agent/AgentWindowCloseListener`, `AgentCloseConfirmation`: closing the window stops the agent (the v1 rule).
- `intellij/agent/AttentionNotifier`: brings the owning window to the front.
- `intellij/tasks/TaskWindows`, `TaskLauncher`, `TaskNavigator`: `ProjectUtil.openOrImport` / `focusProjectWindow`.
- `lsp/LspUsageProvider`, `lsp/JdtServerManager`: Drydock's own usages. These are rooted at the worktree, with one `-data` dir per worktree under `~/.drydock/lsp/`.
- `mcp/McpServer`, `McpToolRouter`: Drydock's MCP tools.

## Fixture (every spike uses it)

- **Host project H:** `java-profiler` main checkout, opened in IDE window W_H.
- **Foreign worktree F1:** a Drydock worktree of `java-profiler` (e.g. `jb/rc-7-tooling`). Make sure it has a file that differs from H in a way you can grep. Add a uniquely named method `spikeOnlyInF1()` that exists **only in F1**, with one caller in F1.
- **Foreign project F2:** a different repo (`dd-jmc` or `dd-trace-java`). Only needed for H3 and H5.
- No window is open for F1 or F2 unless a step says so.
- Record the IDE build (`Help → About`), OS, RAM, and JDK in `RESULTS.md`.

---

## H1 — JetBrains built-in MCP server against a worktree that isn't open (gating)

**Question:** when a hosted session's agent calls the IDE's built-in MCP tools, does it fail cleanly, or does it answer from H without saying so?

Steps:
1. Enable the built-in MCP server (Settings → Tools → MCP Server). Copy the list of tools it exposes, with each tool's parameters, into `artifacts/h1-tools.json`. Get it from `tools/list` on the server.
2. In W_H, launch a Drydock session whose working directory is **F1**, with the IDE MCP server attached the way the user's agent would have it. If it isn't attached by default, attach it by hand and note how.
3. Have the agent call, verbatim, and record each request and response:
   - find usages / references of `spikeOnlyInF1`
   - find usages of a symbol that exists in **both** H and F1 but has a different number of callers
   - get file text / open file on a path that differs between H and F1
   - get diagnostics / problems for a file in F1
   - run a run configuration or a gradle task, if such a tool exists
4. For each call, say which tree the answer came from: H, F1, or an error. Repeat once with an explicit `projectPath` = F1, if the tools take that parameter.

Outputs:
- `artifacts/h1-tools.json`: tool list with parameter schemas.
- `artifacts/h1-calls.jsonl`: one line per call, `{tool, args, response_excerpt (≤ 500 chars), answered_from: "H"|"F1"|"error", error_text}`.
- The H1 table in `RESULTS.md`.

## H2 — Move a running console between windows (gating)

**Question:** can an agent process that's running in W_H get a new parent console in W_F1 (opened by the jump) without a restart, lost output, or a second process?

Steps:
1. Prototype an app-level `ExecutionRegistry` (app service) that holds `ProcessHandler`s. Keep `AgentExecutionService` as a view over it.
2. Start a session for F1 from W_H and let it produce output continuously (e.g. a long `pi` turn, or a `yes | head -n 200000`-style fake provider).
3. Jump: `ProjectUtil.openOrImport(F1)`, then attach a new `TerminalExecutionConsole` in W_F1 to the **same** handler, and detach or dispose the one in W_H. Try both:
   - (a) the same console component moved between frames
   - (b) a new console, with buffered history replayed into it
4. Check:
   - the PID is unchanged
   - there's no output gap or duplication: compare line counts against a counter the fake provider prints
   - keystrokes go to the agent in W_F1, including Ctrl-C, arrows and paste; reuse the S1 key matrix from the MVP handoff
   - closing W_H afterwards doesn't kill the process
   - IDE quit still asks once
5. Time from the jump click until W_F1's console accepts input.

Outputs:
- `artifacts/h2-run-{a,b}.log`: PID before and after, expected vs received line counts, key-matrix pass/fail.
- `artifacts/h2-jump-timing.csv`: `run, cold_or_warm, ms_to_window, ms_to_console_input`. Five warm runs, two cold.
- Short screen recordings of (a) and (b), if you can make them.
- The H2 table in `RESULTS.md`, and which variant you'd ship.

## H3 — Memory cost: hosted sessions vs full windows (gating, decides if it's worth it)

**Question:** how much does hosting N sessions cost compared with opening N project windows?

Steps, run each for N = 1, 3, 5 foreign worktrees (mix F1-style worktrees and F2):
- **A, hosted:** only W_H is open, with N hosted sessions. Each session runs one usages query, so its JDT LS starts.
- **B, windows:** W_H plus N worktree windows, each with one session. Wait until indexing finishes.

For each configuration, once it has settled (no indexing, 60 s idle):
- IDE JVM: used heap after a GC (`Help → Diagnostic Tools → Activity Monitor`, or `jcmd <pid> GC.heap_info` after `jcmd <pid> GC.run`)
- RSS of the IDE process
- RSS of each JDT LS process, and of each agent process
- time until the first usages query answers (cold) and the second one (warm)
- disk used by `~/.drydock/lsp/*` and by IDE index/caches for the new windows

Outputs:
- `artifacts/h3-memory.csv`: `config(A|B), N, ide_heap_mb, ide_rss_mb, jdt_rss_mb_total, agents_rss_mb_total, first_usages_ms, warm_usages_ms, disk_mb`
- the shell commands you used, in `artifacts/h3-method.md`
- The H3 table in `RESULTS.md`

## H4 — Opening files from a hosted tab (quick)

**Question:** what happens today when a path in a hosted F1 console is clicked or opened in W_H?

Steps:
1. Click a hyperlinked F1 path in the hosted console. If there isn't one, call `FileEditorManager.openFile` on it.
2. Record: does it open in W_H as a non-project file? Is there highlighting, red code, a VCS gutter, navigation? Does an "outside project" banner show?
3. Prototype a redirect: links under a foreign root open in the owning window instead (go through `TaskWindows`). Record the timing, as in H2.

Outputs: two screenshots (`artifacts/h4-before.png`, `artifacts/h4-redirect.png`) and the H4 rows in `RESULTS.md`.

## H5 — Lifecycle edge cases (quick)

Using the H2 registry prototype, record what happens for each case:
1. W_H closes while hosted sessions are working.
2. The IDE restarts. Do hosted tabs come back in W_H, and should they?
3. A hosted F1 session asks for attention. Which window comes to the front?
4. W_F1 is already open and the user opens the same session hosted in W_H. Does that make two consoles, block it, or focus W_F1?
5. The agent edits a file in F1 that W_H has open as a non-project file (from H4). Is the buffer stale, and when does it refresh?
6. The F2 folder goes missing while it's hosted.

Outputs: the H5 table in `RESULTS.md`: case, what happened, what we'd want, and the class you'd change.

---

## How I'll decide (so please measure exactly this)

| Decision | Go if | No-go if |
|---|---|---|
| Hosting at all | H1: hosted sessions either get correct F1 answers or a clear error we can catch | H1: any `answered_from: H` for an F1 query, with no way to detect or block it |
| Console move | H2: one variant with the same PID, 0 lost or duplicated lines, the key matrix passing, and a warm jump ≤ 3 s to input | Both variants fail, or need a process restart |
| Worth it | H3: for N=3, A uses ≤ 50 % of B's (IDE RSS + JDT RSS) **or** A's first usages answer ≤ B's time until indexing finishes | A ≥ 80 % of B's memory with no time saved |
| Link redirect | H4: redirect works and is ≤ the H2 jump time | — (nice to have) |

Also note anything that surprised you, in a free-text section. Mentions of the platform API (`@ApiStatus.Internal`, experimental) count as findings.

## Return package

Zip `RESULTS.md` and `artifacts/` and bring them back. Don't paste code unless a finding depends on it. In that case, a ≤ 30-line excerpt with the file path is enough.
