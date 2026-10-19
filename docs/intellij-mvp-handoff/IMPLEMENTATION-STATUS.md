# IntelliJ plugin MVP — implementation status

What is built against the design handoff in this directory, what is not, and
where the design was deviated from on purpose.

`README.md` beside this file is the handoff **verbatim**, as received; the
`.dc.html` files are its design references (open `Drydock MVP.dc.html` in a
browser, keeping `support.js` next to it). Neither is edited — this file is
the only place the implementation's own state is recorded.

*Last updated: 2026-10-19, against `feat/intellij-mvp` (after the live
screen-capture pass recorded under "How this is verified").*

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
| 1 | Agents ▾ widget + popup | **not built** (needs S3) |
| 2 | Agent editor tab | **partial** — tab (pinned, first, dot, `· RO`), strip (state word, RO chip, secondary line with a live age, Stop/Resume/Start, Changes, ⋯), the 50 %-dimmed stopped transcript with its footer, the status-bar breadcrumb named by the session title. Missing: file paths as hyperlinks, `<n> files changed`, and the `<repo> · <branch>` window title |
| 3 | New Agent Session dialog | **built** — task (focused, three rows), agent (the agent CLIs only), Writes/Read-only, Here (`<repo> / <branch>` of the main checkout) vs new worktree (a free branch name suggested, a taken one refused), both warnings, `Start anyway`. **The task is delivered to the agent** as its first message (Claude, pi; not Codex — see deviations) |
| 4 | Attention ("needs you") | **built, except the widget** — one balloon in the focused window (`Claude needs you` / `n agents need you`, `Raise window` · `Snooze`), an OS notification when the IDE is not active, and the needs-you state clears once the user is looking at the session. The amber Agents widget is surface 1. No prompt summary (the hook records only a state word) |
| 5 | Resume and idle state | **built** — resume is by id, never `--continue`/`--last`; nothing auto-starts; `Resume · ~Nk tokens` from `ResumeCostEstimate` |
| 6 | Close / quit confirmation | **built** — `Stop n working agent(s)?` on a window close, one dialog for all windows on quit (`Stop & Quit`), `Don't ask again` |
| 7 | Review in the Commit tool window | **not built** (needs S4) |
| 8 | Finish ▾ | **not built** |
| 9 | "Done with this session…" | **built** — Forget, or Forget + remove worktree (closes the window; the branch stays; the data-loss line only on a dirty tree) |
| 10 | Welcome screen Agents tab | **not built** (needs S3) |

Also outstanding: the `DiffExtension` gutter/inlay findings; *enforcing* (not
merely warning about) one writer per worktree; and the
`n stale worktrees · Prune` line.

Entry points currently live under **Tools → Drydock**, because the v1 tool
window is gone and the handoff forbids shipping it beside its replacement.
They move to the Agents widget when surface 1 lands.

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
  pass compiling on its own. **Plugin Verifier ran only partly**:
  IU-253.33813.55 reported **Compatible** for the first ten commits of the
  pass; the 2026.1–2026.3 verifications could not download their bundled
  plugins' dependencies because the verification host's disk was full
  ("No space left on device" — the verifier itself had just extracted
  ~20 GB of IDEs into the Gradle cache). The surfaces 4, 6, 9 and the
  later commits add public-API usage the verifier has not yet seen:
  `ProjectCloseHandler`, `SystemNotifications`, `NotificationGroupManager`,
  `ApplicationActivationListener`, `ProjectUtil.openOrImport` and
  `focusProjectWindow`, `DoNotAskOption.Adapter`, and an override of
  `BaseProcessHandler.getCommandLineForLog`. Rerun `verifyPlugin` with
  ~10 GB free before relying on cross-version compatibility.
- **Manual**, in `./gradlew runIde` → Tools → Drydock. S1's gate (above) was
  run this way, and so was window-close-without-stopping.

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
