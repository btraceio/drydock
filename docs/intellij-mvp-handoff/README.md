# Handoff: Drydock IntelliJ plugin, MVP

## Overview
Drydock runs agent CLI sessions (Claude, Codex, pi) against git worktrees. This handoff covers the MVP of the IntelliJ plugin (`intellij-plugin/`). Its UX model is the result of the design exploration in this bundle:

- **One IDE window (Project) per worktree.** A window holds 1…n agent sessions; usually one writing session, plus optional read-only ones.
- **The agent console is a pinned editor tab** in that window, not a Terminal or Run tool-window tab.
- **The session catalog is app-level** and shown through IDE-native surfaces: an **Agents ▾** widget in the main toolbar (every window) and an **Agents** tab on the Welcome screen (no window open).
- **Window close stops the agent**; the session stays as an idle row. Only explicit Forget removes a row. Nothing auto-starts.
- **Review happens in the Commit tool window** (branch-vs-main scope, findings as child nodes) plus the IDE diff viewer with gutter findings. **Finish** = merge locally or push + PR.

This replaces the v2 Terminal-tab runtime, whose failure is described in `notes/drydock-intellij-mvp-wrapup.md`. The v3 ownership split (durable catalog, then project execution service, then ProcessHandler) still holds; only the presentation moves from Run content to an editor.

## About the design files
The `.dc.html` files in this bundle are **design references built in HTML**. They show the intended structure, copy and behaviour; they are not code to port. Implement the surfaces in Swing/IntelliJ Platform UI (Kotlin UI DSL / JB components) inside `intellij-plugin/`, using platform components and LaF colours, so the result looks native in any theme.

Open `Drydock MVP.dc.html` in a browser for the clickable prototype. Its scenario bar simulates "agent needs you", window close, IDE restart and the Welcome screen.

## Fidelity
- **Drydock-owned surfaces are high-fidelity for structure, copy and behaviour.** These are the Agents widget and popup, the agent editor strip, the dialogs, the balloon, review rows and finding cards.
- **Visual styling must come from the IDE**, not the hex values in the prototype. The host IDE chrome in the prototype (title bar, stripes, tabs, Commit view) is a neutral approximation. Use JBColor / `JBUI.CurrentTheme.*`, standard paddings (`JBUI.Borders`), `ActionButton` / `ComboBoxAction` / `JBPopup`, and `DialogWrapper`.
- **The only Drydock-specific colours** are the status-dot palette already defined in `SessionDots.java` (see Tokens).

---

## Phase 0: gating spikes (do these first; each has a pass/fail)
The build does not start until S1 passes. The other spikes decide between a primary and a fallback path.

| # | Spike | Pass when | If it fails |
|---|---|---|---|
| **S1** | Agent console in a `FileEditor`: a custom `FileEditorProvider` for a Drydock `LightVirtualFile`, whose component hosts the `TerminalExecutionConsole` already proven in Phase 0A (`PtyCommandLine → KillableProcessHandler → TerminalExecutionConsole`). | All three agents (Claude, Codex, pi) render their TUI correctly. **Keys reach the agent while the tab is focused**: Esc, Shift+Tab, ⌃C, ⌃R, ⌃D, arrows, ⌥/Alt+arrows, Tab, Enter, Backspace, paste. Splitting and pinning the tab works. Reopening the project restores the tab **without** starting a process. Explicit Stop (non-soft, force-kill after 2 s) ends the process exactly once. | Fall back to Run-tool-window content (Phase 0B path) with the same strip as its toolbar. Everything else in this README stays. |
| **S2** | Worktree window time-to-useful on `dd-trace-java`: create worktree, open the Project, start the agent. | The agent starts within 2 s of the frame appearing, **not** waiting for indexing or build import. Measure and record the times to frame, agent start, and smart mode. | Keep agent start independent of indexing regardless; add "skip build import" to the dialog. |
| **S3** | IDE-native catalog surfaces: (a) an action/widget in the main toolbar placed after the Project widget (verify group ids in 2025.3, e.g. `MainToolbarLeft`); (b) a Welcome-screen tab (verify a public EP such as `welcomeTabFactory`). | Both use public API only. Plugin Verifier reports no internal API usage. | (a) Fall back to the `MainToolbarRight` area. (b) Fall back to a "New Agent Worktree…" action on the Welcome screen's Projects list. |
| **S4** | Review in the Commit tool window: can Drydock show **branch-vs-main** scope with findings as child nodes and **without commit checkboxes** in the stock Changes view? | Yes via a public extension. | Add a Drydock **Review** content tab in the Commit tool window built on `ChangesBrowserBase` (it looks native, and Drydock owns the checkboxes). This is the expected outcome; plan for it. |

Also check before relying on them: `ProjectCloseHandler` (or equivalent) to veto/confirm a project close, `AppLifecycleListener` / `ApplicationListener#canExitApplication` for quit, `SystemNotifications` for OS notifications, and `DiffExtension` for decorating the diff viewer.

---

## Ownership model (unchanged from v3, presentation updated)
- **Application:** durable session catalog (single writer, existing `ApplicationStateStore`), plus change notifications.
- **Project (= worktree window):** execution service mapping `ManagedSessionId → (ProcessHandler, agent FileEditor)`. A project can hold several.
- **Platform:** `ProcessHandler` events are authoritative for live state. Editor tab events are **presentation only**:
  - Closing the agent tab hides it; the process keeps running. Reopen it with ⌃⇧A or from Agents ▾.
  - Closing the Project stops every execution in it (confirmation rules below), and the rows go idle.
  - Quitting the IDE does the same for all projects.
  - Opening the IDE or a Project starts nothing.
- **Remove from runtime** (per wrap-up notes): `TerminalSessionLauncher`, `SessionTabRegistry`, `TerminalTabGuard`, restored-tab sweeps, terminal content lifecycle monitor, tab-close-as-status-transition. The existing `SessionsPanel` tool window is **replaced** by Agents ▾; don't ship both.

## Status vocabulary
Map from existing `SessionStatus` + `SessionActivity` (keep `SessionDots` as the single mapping):

| UI word | Dot | Source |
|---|---|---|
| Working | filled blue | RUNNING + BUSY |
| At prompt | filled blue | RUNNING + IDLE |
| Needs you | filled amber | RUNNING + NEEDS_ATTENTION |
| Not running | hollow grey ring | INACTIVE / EXITED |
| Failed / Missing directory / Unsupported agent | filled red | existing ERROR facet words |

Dots are static, with no animation (see `notes/status-dot-pulse-cpu-wrapup.md`).

---

## Screens / views

### 1. Agents ▾ widget (main toolbar, every window)
- **Placement:** directly after the stock Project widget and branch widget.
- **Default:** blue dot + count of non-idle sessions + "Agents" + chevron. Copy: `5 Agents`.
- **Any session needs you:** amber dot + `1 waiting · Agents`, with a warning-tinted background (use the theme's warning/notification colours).
- **Click** opens a `JBPopup` (≈440 px wide, max ≈420 px list height) anchored under the widget:
  - **Search field** (focused): `Search sessions`. Filters by title and branch.
  - **List**, grouped by repository (header row: repo name, small semibold secondary text).
    - A worktree with **>1 session** gets a sub-header `⑂ <branch>` (with `this window` right-aligned if current), and its sessions are indented 18 px.
    - A worktree with **1 session** is a single flat row: dot · title · [RO chip] · right-aligned `<Agent> · <branch>` (or `this window`).
  - **Row click:** raise or open that worktree's window **and select that session's agent tab**. If the window isn't open, open it without starting the agent.
  - **Footer row:** `＋ New Agent Session…` with shortcut `⌥⌘N`.
- **Order:** this repo first, then the others alphabetically. Within a repo, Needs you sorts first.

### 2. Agent editor tab
- **Tab:** status dot + agent-given session title (+ `RO` chip for read-only). Pinned, first in the tab row. Each session in the window has its own tab.
- **Strip** (≈36 px, editor-notification-panel style, full width above the console):
  - left: dot · bold state word (`Working` / `At prompt` / `Needs you` / `Not running`) · for RO, a bordered chip `Read-only · won't edit files` · secondary `<Agent> · <age> · <n> files changed`.
  - right (running): link `Changes` (opens the Commit tool window review) · button `■ Stop`.
  - right (not running): primary button `Resume · ~<N>k tokens` (from `ResumeCostEstimate`).
  - right (always): `⋯` opens "Done with this session…" (screen 9).
- **Console body:** the terminal, edge to edge, editor font.
  - Not running: the last transcript dimmed (50 %) with a footer line `— stopped <when/why> · nothing restarts on its own —`.
  - File paths in output are hyperlinks that open in the adjacent editor split.
- **Window title:** `<repo> · <branch>` (stable). The agent title appears on the tab and in Agents ▾ only.

### 3. New Agent Session dialog (`DialogWrapper`, ≈520 px)
Fields:
- **Task:** multiline, placeholder `What should the agent do?`
- **Agent:** segmented chips `Claude` `Codex` `pi`
- **Mode:** segmented `Writes` | `Read-only`
- **Where:** radios
  - `Here` with `<repo> / <branch>`
  - `New worktree` with `⑂ <slug> from main`. The slug is the first 3 words of the task; the branch name stays editable. The base is the repo's **default** branch.

Defaults:
- Writes → New worktree.
- Read-only → Here.

Warnings (inline warning-tinted box) for Writes + Here:
- If another writing session is active in this worktree: `Another writing session is active in this worktree. Two writers can overwrite each other.`
- Otherwise: `A writing agent here shares the tree with your own edits.`

The primary button then reads `Start anyway`. Otherwise it reads `Create & Open Window` (new worktree) or `Start Here`.

On create:
1. Create the worktree (existing `WorktreeService`).
2. Open its Project.
3. Open the agent tab.
4. Start one execution **immediately** (don't wait for indexing). Strip secondary text: `IDE still indexing` while the project is in dumb mode.

Read-only enforcement is per provider: Claude plan mode, the Codex read-only sandbox, and nothing for pi (strip copy then says `not enforced`). Verify the flags in the provider SPI.

### 4. Attention ("needs you")
On a transition into NEEDS_ATTENTION:
- the Agents ▾ widget turns amber (every window), and the agent tab dot turns amber;
- one balloon in the focused window. Title `<Agent> needs you`, body `<session title> · <repo>` plus the prompt summary, actions `Raise window` · `Snooze`. If several arrive at once, collapse them into one: `2 agents need you`;
- if the IDE is not the active app, an OS notification via the platform's system notifications.

The answer is always given in the agent tab; Drydock never proxies it. There's no title-bar marker in the MVP.

### 5. Resume and idle state
- After restart or window close, every session row is idle and its tab restores in Not running state. Nothing starts.
- `Resume · ~Nk tokens` starts exactly one execution of the provider's resume command (by id; never `--continue`/`--last`).

### 6. Close / quit confirmation
- **Closing a worktree window:** if ≥1 session in it is **Working** (busy or needs-you), show a `DialogWrapper`:
  - title `Stop <n> working agent(s)?`
  - body `Closing <repo> · <branch> stops them mid-task.`, then a list of `● <title> · <Agent>`, then secondary `Agents waiting at the prompt are stopped without asking.`
  - checkbox `Don't ask again`; buttons `Cancel` · `Stop & Close` (default).
- Otherwise close silently.
- **Quitting the IDE:** one dialog listing working agents across all windows, with the same copy and buttons (`Stop & Quit`).

### 7. Review in the Commit tool window
Expected: a Drydock **Review** content tab (S4 fallback); it may be the stock view if S4 passes.

- **Header:**
  - `Scope  Branch vs main ▾` with `<k> / <n> reviewed` right-aligned;
  - hint line `<c> commits + <u> uncommitted file(s) vs main`.
- **Tree:** `▾ <branch> vs main · <n> files`.
  - **File rows:** file-type icon · name · `●` (blue) if uncommitted · secondary path · right mark: `✓` (reviewed, green), `◆ n` (open findings, amber), `○` (unreviewed, muted).
  - A file with open findings expands to **finding child rows**: severity chip (`MED`, amber) · finding title · `:line`. **Resolved and dismissed findings are hidden** (footer `n resolved findings hidden`).
  - Click a file → IDE diff (unified, full context) for branch vs main. Click a finding → the same diff scrolled to the line.
- **Branch scope has no checkboxes.** The bottom keeps the commit-panel shape:
  - the agent-drafted message (`Drafted by <Agent> · edit freely`), then `No checkboxes in branch scope` and a primary `Finish ▾`;
  - no Commit button in this scope.
- **Diff decoration** (via a diff-viewer extension):
  - gutter `◆` on finding lines;
  - block inlay card under the line (warning-tinted, max ≈640 px): severity chip, title, body, and status (`Proposed by <Agent>` / `Confirmed`);
  - actions on a proposed finding: `Confirm` (primary) · `Dismiss…` (asks for a one-line reason, which is appended to the thread) · `Not sure · ask <Agent>`;
  - action on a confirmed finding: `Ask <Agent> to fix` (sends it to the agent tab).
- **Diff header actions:** `Mark file reviewed` / `Reviewed ✓`, plus the change counter `1 of 2 changes`.

### 8. Finish ▾ menu
- `Merge into main`: secondary `Commit, merge in the main checkout, close this window`, plus a pre-flight line:
  - green `✓ main checkout is clean and on main`, or
  - disabled with the reason (dirty / not on main / not found).
- `Push & create pull request…`: secondary `Commit, push, open a PR; the window stays for review rounds`. Uses `gh`.
- Checkbox `Remove worktree after merge` (default on).
- Warning line if any finding is open: `1 open finding. You can still finish.`
- Uncommitted files are committed first using the message in the panel. Finishing stops any read-only sessions in the worktree.

### 9. "Done with this session…" (from `⋯`)
Radio options:
- `Forget session only`, with secondary `Removes the row. Worktree and branch stay.`
- `Forget + remove worktree`, with secondary `Closes this window. <n> uncommitted file(s) will be discarded.` The data-loss text appears only when the tree is dirty.

Footer: `The agent's own conversation history on disk is kept either way.` Buttons: `Cancel` · `Forget` / `Forget & Remove`.

### 10. Welcome screen: Agents tab
- Next to Projects; the label shows the count.
- **Toolbar:** search `Search sessions`, plus primary `New Agent Worktree…`.
- **List** grouped by repo. Row: dot · title · RO chip · secondary `<Agent> · <branch> · <age>` · `Open` · `Resume` (Resume only when the session is not running).
- Footer: `Nothing runs without a window. Open opens the worktree window; Resume also starts one execution.`

---

## Interactions and rules (summary)
- At most one **writing** session per worktree by default; a second one needs "Start anyway".
- A read-only session can't switch mode in place. `Continue as writing session…` (later) resumes the same conversation in Writes mode.
- Clicking a session that belongs to another window raises that window; nothing is proxied across windows.
- The worktree list's stale entries collapse into one `n stale worktrees · Prune` line (no confirm; metadata only). This is in the Done dialog and Agents ▾ for MVP.
- **Blocking work is async with visible progress**: worktree create, git, gh, resume. Every completion path clears progress (existing AGENTS.md rule).

## State (plugin side)
- **`ManagedAgentSession`** (existing): id, agent kind, agent-given title, repo, worktree path, branch, **mode (W | RO)** (new field), status, activity, last active, conversation id.
- **Project execution service:** `Map<ManagedSessionId, Execution(ProcessHandler, AgentVirtualFile)>`.
- **UI-only:**
  - Agents popup query;
  - review scope per project (`BRANCH_VS_MAIN`);
  - per-file reviewed flags keyed by `(scopeId, file, hunk digests)`;
  - finding triage state (existing annotation store: PROPOSED / CONFIRMED / DISMISSED);
  - "don't ask again" for close confirmation (application setting).

## Design tokens
Use the platform LaF for everything except:

| Token | Value | Source |
|---|---|---|
| Running dot | `#3574F0` | `SessionDots.RUNNING_BLUE` |
| Needs-you dot | `#E0A030` | `SessionDots.ATTENTION_AMBER` |
| Error dot | `#DB3B3B` | `SessionDots.ERROR_RED` |
| Idle ring | `#8C8C8C`, 1.5 px stroke | `SessionDots.IDLE_GRAY` |
| Dot size | 8 px (10 px icon box) | `SessionDots.DOT_DIAMETER` |

The prototype's other hex values approximate the IntelliJ light theme, for orientation only:
- text `#1E1F22`, secondary `#6C707E`, muted `#818594`, border `#EBECF0`, panel `#F7F8FA`, selection `#D5E1FF`;
- warning tint `#FDF3DC` / `#F0C674` / text `#A86500`;
- diff add `#E9F5E9`, delete `#FBEAEA`.

## Out of scope for MVP
Guided tour (T1/T2 in `E Synthesis.dc.html`, pending a diff-viewer spike), Services node, status-bar widget, title-bar marker, injecting rows into the stock Project widget popup, detached/persistent agents, SSH remotes, Windows support beyond what the platform gives.

## Files in this bundle
- `Drydock MVP.dc.html`: **clickable hi-fi prototype of the MVP** (primary reference).
- `E Synthesis.dc.html`: storyboard of the chosen direction, multi-session (E9–E11), review density (R1–R3, R2 chosen) and tour options (T1/T2).
- `E Adversarial Review.dc.html`: risks (H1–H5, M1–M7) and the MVP cut; H2 and H3 are decided.
- `Drydock IntelliJ UX.dc.html`, `A`–`D` `*.dc.html`: the explored alternatives, for context on why they were rejected.
- `support.js`: runtime needed to open the `.dc.html` files in a browser; keep it next to them.
