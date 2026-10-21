# Handoff: cross-project agent tabs (v1.1)

Builds on the MVP handoff (`design_handoff_drydock_intellij_mvp/`). Read `DECISION.md` and `RESULTS.md` in this folder first. Design reference: `Cross-project Tabs.dc.html` (frames F1–F5). It's built in HTML, so take the structure and copy from it, and the styling from the IDE's look and feel (as in the MVP's Fidelity section).

**Goal:** one window can run and show agent sessions from any project or worktree. Anything that needs the project model opens the owning window, and the console moves there.

## Before you build
- The MVP fixes 1–3 (MCP routing header, one agent per session app-wide, no fixed jdtls wait) are merged.
- One human pass of the real-keyboard key matrix, including Esc, on a moved console (spike H2).
- Reproduce the "second window empties IDE MCP text search" bug on a normal IDE install. File it with JetBrains if it holds.

## Ownership change
- **New app service `AgentExecutionRegistry`.** It owns `ManagedSessionId → (KillableProcessHandler, ReplayBuffer, currentHost: Project?)`, with a single `ProcessListener` per process that appends to the buffer and forwards to the current console under one lock.
- **`AgentExecutionService` (per project) becomes a view.** It maps session to console and editor for sessions this window currently shows, and has no process lifecycle of its own.
- **`ReplayBuffer`** is capped at 20,000 lines or 8 MB, whichever comes first. The oldest lines are dropped, and the replay begins with a line saying `… earlier output trimmed`.
- **Showing a session somewhere** builds a fresh `TerminalExecutionConsole(project, cols, rows, null)`, replays the buffer into it, and calls `attachToProcess(handler, ProcessHandlerTtyConnector, false)`. It disposes the old host's console, which doesn't kill the process (spike variant b).
- Invariant: a session is shown in **at most one** window. Every Show / Start / Resume goes through the registry.

## Vocabulary: where a session is shown
Row meta in the Drydock tool window and the Agents ▾ popup:
- `this window`: shown here, and its worktree is this window's project.
- `here`: shown here, from another project or worktree (hosted).
- `in its own window`: shown in its worktree's window.
- `in <repo> window`: hosted in some other window.
- `not shown`: running, with no window showing it.
- idle rows keep their MVP meta.

## Screens

### F1: Start menu (`Start ▸` on a worktree row)
- `Start in this window` (⏎, also a plain click on Start). Secondary text: `Tab opens here, tagged <repo>`.
- `Start in its own window` (⌥⏎). Secondary text: `Opens <repo> · <branch>`.
- separator, then `Read-only, in this window`.
- For rows in this window's own worktree, the first item reads `Start` with no secondary text.

### F2: hosted tab
- **Tab:** status dot, then a repo chip (`<repo>`, small, accent tint), then the agent title. Only hosted tabs have the chip. Tooltip: `<repo> · <branch> · hosted`.
- **Strip:** status · provider · age | `from <repo> ⑂ <branch>` | spacer | `Open project window ↗` · `Changes ↗` · `■ Stop` · `⋯`.
- `Finish ▾` isn't offered on a hosted tab.
- **File links** whose path is under a known worktree root open in that root's window (`TaskWindows.openAsProject` if needed), never as a non-project file. Tooltip: `Opens in the <repo> · <branch> window`.
- Typing, Stop and ⋯ behave as on a local tab.

### F3: jump
- `Open project window ↗` or `Changes ↗` opens or raises the owner window, then moves the session there (registry Show). `Changes ↗` also opens the Commit tool window's Review tab.
- While the owner window is opening cold (2–5 s in the spike), the host tab shows the platform's progress `Opening <repo> · <branch>…`. Its strip actions are disabled and input still works.
- After the move, the hosted tab closes, and a balloon appears in the host: title `<title> moved`, body `Now in the <repo> · <branch> window.`, actions `Go to window` · `Bring back here`.
- In its home window, the tab is a normal local tab with no chip.

### F4: closing a window that shows working agents (replaces MVP §6 for windows)
- Shown if any session this window shows is Working (busy or needs-you), whether local or hosted.
- Title: `This window is showing <n> working agent(s)`. Each row shows a dot, the title, and `<repo> · this window|hosted[ · needs you]`.
- Body: `Agents that keep running stay in the Drydock list as not shown. Open them from any window.`
- Buttons: `Cancel` · `Stop all & Close` · **`Keep running & Close`** (the default). `Don't ask again` saves "keep running" as the choice.
- Keep running: the registry clears `currentHost`, and the session becomes `not shown`.
- **Quitting the IDE** is unchanged from MVP §6: one dialog, `Stop & Quit`.
- Closing the last project window goes to the Welcome screen, whose Agents tab lists `not shown` sessions with `Open` (opens the owner window and shows the session there).

### F5: attention, not-shown sessions, restart
- **Attention** raises the session's current host and labels the balloon with the session's own `<repo> · <branch>`, not the host window's. If the session is `not shown`, the balloon goes to the focused window, with actions `Show here` · `Open project window`. Change this in `AttentionNotifier`.
- **`not shown` rows** have an inline `Show here` action.
- **Clicking a row shown in another window** raises that window (MVP fix 2). The context menu has `Show here`, which moves it.
- **Restart:** hosted tabs aren't restored. In `AgentConsoleFileSystem` / the editor provider, skip restoring a `drydock-agent://` file whose session's worktree ≠ this project. Those sessions come back as idle rows with `Resume here`. A window's own tabs restore as in the MVP.

## Other rules
- **Stale files:** when a hosted session goes from busy to idle, refresh (`VfsUtil.markDirtyAndRefresh`) any of its worktree's files open in any window as non-project files. The redirect should make that rare.
- **Missing folder:** if a session's worktree root disappears, mark it gone, as the tool window already does for a removed worktree. Stop the process only after the user confirms.
- **One writer per worktree** is unchanged and doesn't depend on which window shows the session.
- **MCP:** hosted sessions carry the routing header for their own worktree (MVP fix 1). With no window open for it, the IDE MCP calls fail cleanly, and the agent uses Drydock's usages tool, whose jdtls starts on its first query.

## Tests (beyond unit tests for the registry)
- Move mid-stream: same PID, line count matches, no duplicates (reuse the spike harness).
- A session is never shown in two windows: try Show here, Resume and the jump in every order.
- Close host → Keep running → Show here in another window: the history is intact, up to the buffer cap.
- Restart: no hosted tabs come back. Idle rows offer `Resume here`.
- Attention on a `not shown` session goes to the focused window, labelled correctly.

## Out of scope
- Sharing jdtls across worktrees.
- Hosting remote (SSH) sessions.
- Showing one session in two windows at once.
