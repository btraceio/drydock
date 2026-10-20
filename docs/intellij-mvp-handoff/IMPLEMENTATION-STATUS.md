# IntelliJ plugin MVP — implementation status

What is built against the design handoff in this directory, what is not, and
where the design was deviated from on purpose.

`README.md` beside this file is the handoff **verbatim**, as received; the
`.dc.html` files are its design references (open `Drydock MVP.dc.html` in a
browser, keeping `support.js` next to it). Neither is edited — this file is
the only place the implementation's own state is recorded.

*Last updated: 2026-10-19, against `feat/intellij-mvp`, after the
task-centric redesign (`docs/superpowers/specs/2026-10-19-intellij-task-tool-window-design.md`)
replaced the handoff's entry points. See "Task tool window" below.*

---

## Gating spikes

The handoff blocks the build on four spikes (`README.md`, Phase 0).

| Spike | Verdict |
|---|---|
| **S1** — agent console in a `FileEditor` | **PASSED**, manually, on IU-2025.3.3. All three agent TUIs render, and Esc, Shift+Tab, ⌃C, ⌃R, ⌃D, arrows, ⌥/Alt+arrows, Tab, Enter, Backspace and paste reach the agent while the tab is focused. **The Phase 0B Run-tool-window fallback is therefore not needed.** |
| **S2** — worktree window time-to-useful | not run. The agent is already started without waiting for indexing, but the times to frame / agent start / smart mode have not been measured. |
| **S3** — toolbar widget + Welcome tab on public API | not run. Blocks surfaces 1 and 10. |
| **S4** — branch-vs-main in the stock Changes view | not run. The handoff expects it to fail and says to plan for the fallback; surfaces 7–8 should not be built until it is answered. |

S1's one requirement turned out to be that
`FileEditor.getPreferredFocusedComponent()` returns the console's own
`getPreferredFocusableComponent()`. No keymap suppression, no custom event
dispatcher, no action shadowing.

---

## Surfaces

Numbered as in the handoff's "Screens / views".

| # | Surface | State |
|---|---|---|
| 1 | Agents ▾ widget + popup | **superseded** by the Drydock tool window (task-centric redesign) |
| 2 | Agent editor tab | **partial** — tab (pinned, first, dot, `· RO`), strip (state word, RO chip, secondary line with a live age, Stop/Resume/Start, Changes, ⋯), the 50 %-dimmed stopped transcript with its footer, the status-bar breadcrumb named by the session title. Missing: file paths as hyperlinks, `<n> files changed`, and the `<repo> · <branch>` window title |
| 3 | New Agent Session dialog | **replaced** by **New task**: a task name (a label, never sent), optional instructions (the agent's first message only when given), agent, mode, and one generated summary line (`New branch <slug> from origin/main in <folder>`) with `Change…` for branch, start point (Git's own picker via Git4Idea), folder (base from Settings › Tools › Drydock, shared with the app), and new/this window |
| 4 | Attention ("needs you") | **built, except the widget** — one balloon in the focused window (`Claude needs you` / `n agents need you`, `Raise window` · `Snooze`), an OS notification when the IDE is not active, and the needs-you state clears once the user is looking at the session. The amber Agents widget is surface 1. No prompt summary (the hook records only a state word) |
| 5 | Resume and idle state | **built** — resume is by id, never `--continue`/`--last`; nothing auto-starts; `Resume · ~Nk tokens` from `ResumeCostEstimate` |
| 6 | Close / quit confirmation | **built** — `Stop n working agent(s)?` on a window close, one dialog for all windows on quit (`Stop & Quit`), `Don't ask again` |
| 7 | Review in the Commit tool window | **not built** (needs S4) |
| 8 | Finish ▾ | **not built** |
| 9 | "Done with this task…" | **built** — from the strip or the tool window, for any task in any window. Says what removal would lose (uncommitted, untracked, commits not merged — a PR recorded MERGED counts as merged; the IDE's own `.idea`/`.iml` do not count), `Remove anyway`, and `Also delete branch` (ticked only when merged) |
| 10 | Welcome screen Agents tab | **dropped** from the MVP by the redesign |
| — | **Drydock tool window** | **built** — registered projects (shared with the standalone app via `~/.drydock/projects.json`), their tasks and idle worktrees; `<repo> isn't in Drydock · Add`; switch, Stop, Stop & Close Window, Resume, Done, New task, New task in this worktree, Remove from Drydock |

Also outstanding: the `DiffExtension` gutter/inlay findings; *enforcing* (not
merely warning about) one writer per worktree; and the
`n stale worktrees · Prune` line; and **a task that works on a picked
branch itself**. `New Drydock Task from '<branch>'…` (Git's branch menus)
only branches *off* the picked branch, or opens the worktree it is already
checked out in. Working on the branch needs a fourth `WorkIn` mode that
checks the existing branch out into a new worktree (`git worktree add
<folder> <branch>`, no `-b`), refused while git has it checked out
elsewhere, and for a remote branch a local tracking branch created first.

In progress: **cross-project tabs (v1.1)** — one window hosting agents from
any project or worktree. Design and spike results:
`docs/intellij-cross-project-handoff/`; plan:
`docs/superpowers/plans/2026-10-20-intellij-cross-project-tabs.md`.

Entry points are the **Drydock tool window** (right stripe, Drydock icon;
its toolbar holds New task, Switch, Stop, Stop & Close, Resume, Done, Remove
from Drydock and Refresh) ⌃⌥N (New task), and `New Drydock Task from '<branch>'…` in Git's
per-branch menu (the Log's Branches pane and the branches popup). The Tools → Drydock menu is gone, and with it Resume Conversation…
(out of the MVP until conversations can be summarised), New Worktree… and
Check Out Branch in Worktree….

---

## Ownership model, as built

Unchanged from the handoff; restated because it is what the rest depends on.

- **Application** — the durable session catalog (`ApplicationStateStore`,
  single writer) plus a `MessageBus` topic for fan-out, because the surfaces
  that show sessions live in other windows and on the Welcome screen.
- **Project (= worktree window)** — `AgentExecutionService`, a
  `ManagedSessionId → (ProcessHandler, TerminalExecutionConsole)` map of what
  is alive in that window.
- **Platform** — `ProcessHandler` events are authoritative. Editor tab events
  are presentation only: closing a console tab hides it and the agent keeps
  running.

A terminated process persists `EXITED` (it ended on its own) or `INACTIVE`
(Drydock stopped it) and **never removes a row**. Only an explicit Forget
does.

The console lives behind a registered `drydock-agent` virtual-file protocol so
the *platform* restores pinned console tabs; a `LightVirtualFile` has no
restorable URL, which would force the plugin to reopen tabs itself and race the
platform — the exact failure the v2 runtime had.

---

## Deliberate deviations from the handoff

Each of these is a place the handoff's text was not followed, with the reason.
They are candidates for reversal if the reason does not hold.

1. **Agent and Mode are radio groups, not segmented chips** (surface 3). The
   platform's segmented control is Kotlin-UI-DSL-only and unreachable from
   Java. A radio group is the IDE's own rendering of an exclusive choice,
   focusable and arrow-navigable; only the pill shape is missing.
2. **The task placeholder is a hint label above the field**, not greyed text
   inside it. `JTextArea` has no `emptyText`, and faking one with a focus
   listener is worse than a label that is always true.
3. **A fifth status word, `Running`.** The handoff's table gives four words and
   maps them to RUNNING joined with BUSY / IDLE / NEEDS_ATTENTION. It names no
   word for RUNNING with *unreadable* activity — which is most live sessions,
   since only Claude ships an activity reporter. Both `Working` and
   `At prompt` would assert something unknown, so `SessionDots` answers
   `Running`. **Needs a designer's confirmation.**
4. **`Read-only · not enforced` for pi** (surface 2's chip). pi's CLI has no
   read-only mode at all — see below — so the chip reports the mode as the
   statement of intent it is, rather than a guarantee pi cannot keep.
5. **`Start` instead of `Resume`** when a stopped session has no recorded
   conversation. The handoff shows `Resume · ~Nk tokens` unconditionally, but
   with nothing to resume into that button can only reach the provider's own
   session picker. `Start` runs the provider's create command on the same row.
6. **`IDE still indexing` only while an agent is running.** The note explains
   "the agent started before the IDE was ready"; on a stopped session it
   explains nothing, and under the words "Not running" it reads as the reason
   the agent is not running.
7. **New Agent Session is ⌃⌥N on macOS, not ⌥⌘N.** In the macOS keymap ⌥⌘N
   is Refactor | Inline in an editor and Git's New Branch elsewhere; pressing
   it in `runIde` opened Create New Branch. The `$default` binding
   `alt meta N` is translated by the macOS keymaps to ⌃⌥N, which opens the
   dialog with no conflict. Windows/Linux (Alt+Meta+N) not tried. **Needs a
   designer's confirmation.**
8. **Codex does not receive the dialog's task** (surface 3). Claude and pi
   get it as their positional prompt, read off their `--help`; the installed
   `codex` answered neither `--help` nor `--version` on the verification
   host, so its prompt argument was not added unverified.
9. **"Forget + remove worktree" keeps the branch, and says so** (surface 9:
   `Closes this window. The branch stays.`). The handoff only removes the
   worktree; `git branch -D` would destroy unmerged commits.
   Removal is offered only for this window's own linked worktree with no
   other session in it; otherwise it is shown disabled with the reason.
10. **The close confirmation counts unreadable activity as working**
    (surface 6). The handoff asks for busy or needs-you agents; Codex, pi,
    and Claude before its first hook have no readable activity, and are not
    known to be at their prompt.
11. **Claude's idle reminder is "needs you" until seen.** The shared hook
    maps `idle_prompt` and `permission_prompt` to the same state, so a
    Claude session turns amber about a minute after a turn ends. The
    plugin now acknowledges the state as soon as the user is looking at the
    session (tab selected in the active window, or Raise window / Snooze),
    which is what makes surfaces 4 and 6 behave; distinguishing the two
    notification types would need a new state word in the shared hook.

---

## Read-only enforcement is uneven, by necessity

`SessionMode` (WRITES | READ_ONLY) is persisted on every session and travels
into both launch contexts. Enforcement is the CLI's, and each flag was read
off the installed binary's `--help` rather than assumed:

| Agent | Flag | Enforced |
|---|---|---|
| Claude | `--permission-mode plan` | yes, when the installed build advertises both the flag and the `plan` choice |
| Codex | `--sandbox read-only` | yes, unconditionally |
| pi | — | **no.** `pi --help` offers only tool allow/deny lists; enumerating edit tools here would rot the moment pi adds one |

`AgentProvider.enforcesReadOnly()` is what lets the UI say "not enforced"
instead of showing a guarantee the agent cannot keep.

---

## How this is verified

- **Automated**, from `intellij-plugin/`:
  `./gradlew test buildPlugin verifyPlugin`. As of the capture pass:
  `test buildPlugin` green (1774 tests, 0 failures), every commit of the
  pass compiling on its own. Plugin Verifier against the **latest** IDE,
  IU-263.7031.23: **Compatible, no internal API usage**, 5 deprecated-API
  notes — `FileEditorManager.openFile(file, focus)` (×2),
  `OpenProjectTask.build()` / `withForceOpenInNewFrame`, and the
  `TerminalExecutionConsole(project, handler)` constructor — all calls the
  2025.3 target has no replacement for. The full `recommended()` set was
  not rerun: it extracts ~4 GB per IDE and filled the verification host's
  disk; IU-253.33813.55 reported Compatible earlier in the pass.
- **Manual**, in `./gradlew runIde` (the Drydock tool window on the right;
  Tools → Drydock before the task tool window replaced it). S1's gate (above)
  was run this way, and so was window-close-without-stopping. Click from the
  Mac itself: over a remote-viewing session the Welcome screen's project list
  did not receive clicks at all (the IDE's UI thread saw no input), which
  looked like a freeze and was not one.

**Automated gates have twice been green while the feature was unusable** —
once when the create flow started an agent but opened no tab at all, and once
when the console leaked under `ROOT_DISPOSABLE`. Treat `runIde` as the real
gate for anything user-visible.

### The live screen-capture pass (2026-10-19)

`runIde` on macOS against a scratch repository with a bare `origin`
(`~/dev/scratch/dd-scratch`), driven by synthetic clicks and keys, every
state captured per window with `screencapture -l` and read back. The
captures live in `intellij-plugin/build/screens/` (not committed); the
commit messages name the ones each change was verified by.

Verified by capture, end to end:

- **New worktree path of surface 3** — worktree created off `main`, opened
  as its own window, agent tab pinned and in front, the dialog's task
  delivered, agent working before indexing finished.
- **Stop then Resume of a real conversation** — Claude edited a file,
  Stop, Resume reopened the same conversation (`--resume <id>`; Claude is a
  preset-id provider, so no late binding was needed). pi received a task
  and answered; its row then offered Resume after a restart.
- **Quit** — every row `INACTIVE`, no agent process left, after a plain
  quit, after `Stop & Quit`, and after the IDE's Gradle daemon was killed.
- **idea.log** — zero SEVERE entries attributable to Drydock across the
  later runs (two per worktree open before the write-safe fix).

Defects the captures found and the branch fixed: ⌥⌘N opens Git's New
Branch (documented, deviation 7); the dialog's squashed task field,
off-axis layout with the warning, lost first keystrokes, monospaced task
font, a `Terminal` agent, a taken branch name suggested; the task never
reaching the agent; SEVERE write-unsafe errors and an empty Project view
in new worktree windows; README.md in front of the agent; the launch
command echoed into the console; the session UUID in the status bar;
focus left on Stop after Resume; an unpinned second tab; a frozen strip
age; a stopped transcript indistinguishable from a live one; the
worktree's folder named as the repository; "needs you" never clearing.

Not verified, and why:

- **Codex** — the installed binary did not answer `--help` or
  `--version` within 20 s on the verification host.
- **The OS notification's banner** — macOS asked for the unsigned sandbox
  `java` process's notification permission on first use; the prompt was
  left for the owner of the machine.
- Windows/Linux keymaps; a quit with working agents in two windows;
  `Don't ask again`; removal refused for the main checkout; a vetoed
  window close; directory naming for a worktree created from a worktree.
- Hover-only effects: no synthetic pointer test can show them.

Observed and left as they are:

- Every worktree window opens behind IntelliJ's own *Trust and Open
  Project?* prompt, and Claude asks its own folder-trust question the
  first time; marking drydock's worktrees trusted needs a trust API.
- Opening a worktree the File > Open way creates `.idea/` and a
  `<name>.iml` in it (standard for a plain directory); they are untracked
  files in the agent's tree and count in the Done dialog's "uncommitted
  files".
- The status-bar breadcrumb's dot is painted once and can show a hollow
  ring while the agent works, until the selection changes.

---

## Task tool window (2026-10-19)

Built to the design in `docs/superpowers/specs/2026-10-19-intellij-task-tool-window-design.md`
and its plan, `docs/superpowers/plans/2026-10-19-intellij-task-tool-window.md`.

**Verified live in runIde (IU 2025.3.3)**, scratch repo `/private/tmp/dd-resume/demo`,
with the sandbox's own projects list (`build/sandbox-projects/projects.json`,
seeded from the user's real app state):

- the list seeded from the app's projects; `demo isn't in Drydock · Add`,
  then Add; idle worktrees listed with `Start ▸`;
- ⌃⌥N → `New task in demo`; the name generated `add-farewell-method` and its
  folder; `Change…` unfolded with Git's `Choose…`; Start created the
  worktree, opened a new window and started Claude at an **empty** prompt;
- switching from the tree to a task whose window was closed reopened the
  window with its tab and **no** agent started;
- Settings › Tools › Drydock showed the worktree folder (cancelled unsaved);
- Resume from the tree on a never-prompted task started it fresh (before the
  fix it exited with "No conversation found");
- Stop from another window: `Stop 1 working agent?` · `Stop anyway`;
- Done with an untracked file and an unmerged commit: `Removing loses 1
  untracked file, 1 commit not merged`, `Remove anyway`; the window closed,
  the folder went, the branch stayed;
- Remove from Drydock on a project with nothing running: no question, gone
  from the list, folder untouched.

**Not verified live:** Open in = This window; a read-only task; `Start ▸` on
an idle worktree; instructions reaching the agent; Stop & Close Window; the
standalone app following the shared list (this worktree's ghostty submodule
is not initialised, so the app was not run — its side is covered by
`RepositoryManagerTest` only).

**Gate (latest):** `./gradlew test buildPlugin verifyPlugin` → BUILD
SUCCESSFUL, 1828 tests, 0 failed; Plugin Verifier against IU-263.7031.23
(temporary override): Compatible, 7 usages of deprecated API. App:
`./gradlew :app:test` → BUILD SUCCESSFUL (all passing).

The whole-branch review's findings (7 important, 13 minor) are all fixed,
each with its own commit.

**Known gap:** an agent at its prompt counts as working (and is asked about)
until Claude reports its first activity event.

---

## Where the rest of the record lives

- `notes/drydock-intellij-mvp-wrapup.md` — the zero-context refill note:
  settled findings, refuted dead ends, open questions, immediate next step.
- The cairn knowledgebase under `.knowledgebase/memories/` (and the personal
  investigation trail) carries the per-finding detail, including the platform
  API facts that cost the most to establish: which jar holds
  `TerminalExecutionConsole`, that a tab's label and icon come from
  `editorTabTitleProvider`/`fileIconProvider` rather than `FileEditor`, that
  there is no public API for pinning a tab, and that pi writes no session
  record until a conversation turn completes.
