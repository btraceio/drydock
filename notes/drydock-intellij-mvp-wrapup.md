# Wrap-up — drydock-intellij-mvp (IntelliJ session UX reset)

*Point-in-time: 2026-10-18, base e30d3923 on feat/intellij-mvp. The cairn graph
is canonical; this note is the zero-context refill artifact.*

## Current question

S1 is answered: the agent console **works** as a pinned editor tab. The open
question is now which of P2–P9 to build next, and whether to make pi a PRESET
provider first.

## Where the work stands

**Phase 0 (reset) and Phase 1 (the editor runtime) are built, tested and
verified. Spike S1 PASSED.** P2–P9 are planned but not started.

S1 verdict (user, in `runIde`): *"the tab shows up now, keys work too"* — the
TUI renders in a `FileEditor`-hosted `TerminalExecutionConsole` and the keys an
editor tab competes for reach the agent, with no keymap work.
`getPreferredFocusedComponent()` returning the console's own focusable
component was enough. **The Phase 0B Run-content fallback is not needed.**

The gate caught two defects no automated gate could:

- **the create flow never opened the tab** — the agent launched invisibly and
  every automated gate passed while the feature was unusable. "The process
  starts" and "the user can see it" are separate assertions;
- **`TerminalExecutionConsole` leaked** under `ROOT_DISPOSABLE` — it registers
  *itself* there, so a manual `console.dispose()` is not a substitute for
  `Disposer.register(parent, console)`.

Both fixed and re-verified (leak count 0 in the following run).

The uncommitted v2 Terminal-tab runtime (~1951 lines) was **discarded** on the
user's instruction, scoped to `intellij-plugin/src/` only. `notes/*.md` and
`.sphinx/` were left alone on purpose: this note is itself untracked and a
blanket `git clean` would have taken it.

Nothing is committed. The change set sits uncommitted on `feat/intellij-mvp`
and splits into: reset, removal, core session-mode, runtime.

## The model (unchanged in ownership, changed in presentation)

- **Application** — durable session catalog (`ApplicationStateStore`, single
  writer) plus an application `MessageBus` topic (`DrydockSessionsListener`)
  for fan-out. The v2 shape pushed refreshes through a static
  `SessionsPanel.refreshFor(project)`; that cannot reach a widget in another
  window, and reaches the Welcome screen not at all.
- **Project (= worktree window)** — `AgentExecutionService`, a
  `ManagedSessionId → (ProcessHandler, TerminalExecutionConsole)` map.
- **Platform** — `ProcessHandler` events are authoritative. Editor tab events
  are **presentation only**: closing the tab hides it and the agent keeps
  running.

What changed: the console is a **pinned editor tab**, not Run-tool-window
content. That comes from the UX design handoff
(`~/Downloads/Drydock IntelliJ plugin design.zip`, README.md is the spec).

## Settled findings

- **A registered protocol, not a LightVirtualFile.** A pinned tab is restored
  by the platform from the workspace file **by URL**. A light file has none, so
  drydock would have to reopen tabs itself and race the platform — the v2
  duplicate-tab bug. `AgentConsoleFileSystem` registers `drydock-agent` with
  the session id as the path, and `findFileByPath` answers for any valid id
  even before the catalog loads (restore runs during that load).
- **A tab's label and icon do not come from `FileEditor`.** The platform builds
  them from `editorTabTitleProvider` and `fileIconProvider`. A `getName()`
  returning the agent's title still shows a bare session id.
  `FileEditorManagerEx.refreshIcons()` makes a status change repaint the dot.
- **There is no public pin key.** `FileEditorManagerKeys` has
  `FORBID_PREVIEW_TAB` but nothing for pinned; pinning is
  `EditorWindow.setFilePinned`, in an `impl` package that Plugin Verifier does
  **not** flag as internal.
- **`DeprecatedVirtualFileSystem` is not `@Deprecated`** — the name is about
  its old listener API. It is the right base for a non-physical filesystem.
- **Console threading is forced.** `KillableProcessHandler`'s constructor runs
  a blocking PTY fork/exec; `TerminalExecutionConsole`'s builds Swing. So:
  spawn off the EDT, hop to the EDT for the console, `startNotify()` **last**
  (a TUI's opening screen is most of what the user sees). The hop must not take
  `project.getDisposed()` as an expiry condition — an expired runnable is
  dropped silently, leaving the start future pending and the row at STARTING.
- **Stop is non-soft plus a 2 s force-kill.** Soft stop sends SIGINT, which pi
  reads as "cancel the turn" (Phase 0B: Stop at 23:10:28, still terminating at
  23:10:54).
- **Read-only flags, read off the installed CLIs, not recalled.** Claude
  `--permission-mode plan` (gated on a `--help` probe for the flag *and* the
  `plan` choice — an unlisted choice is a startup error); Codex
  `--sandbox read-only` (verified parseable before the subcommand, so one
  placement serves create and resume); **pi has none** — `enforcesReadOnly()`
  returns false and the chip reads "Read-only · not enforced".
- `SessionMode` rides on `AgentBinding` and persists leniently, defaulting to
  `WRITES` — the opposite default from `namePinned`, because silently stripping
  edit rights from a session whose owner believes it has them is the worse
  failure.
- **pi writes no session record until a conversation turn completes.** Proved
  on disk: no session file was created on the test day at all, and today's
  launch *appended* to an Oct-16 session (`custom 2026-10-18T15:21:51Z`). So
  `discover` and `resolve` finding nothing is correct, not a bug — do not
  "fix" discovery on that symptom. `PI_SESSION_ID`, which the provider already
  injects, is a per-launch proxy-correlation value, **not** pi's session
  identity.
- **The agent session id is bound at termination**, with a resume-time
  backstop for an IDE killed outright. A known id is never replaced: rebinding
  would silently resume the wrong transcript, which looks like success and is
  worse than the picker. `resolve` filters by the claimed set but does not add
  to it, so the service makes the claim.
- **No conversation to resume → the strip offers `Start`, not `Resume`**
  (`find-resume-only-when-resumable`). Starting fresh runs the provider's
  *create* command on the existing row; `startSession` refuses a row that
  already has an id, and `persistSession` upserts so it cannot mint a
  duplicate row.

## Refuted dead ends — do not revisit

- Terminal tool-window tabs as the execution owner; all of
  `TerminalSessionLauncher`, `SessionTabRegistry`, `TerminalTabGuard`,
  `SessionLifecycleMonitor`, `SessionsPanel`, `SessionsToolWindowFactory`,
  `SessionListModel`, `SessionEventSink` is **deleted**.
- `RunProfile` ownership, persisted or ephemeral: native Rerun starts a
  process outside drydock's registry.
- A `LightVirtualFile` for the console tab, and drydock reopening tabs on
  project open.
- Soft termination anywhere in the lifecycle.
- Inventing pi read-only out of `--exclude-tools`.
- Binding `PI_SESSION_ID` as the agent session id.
- "Fixing" discovery because a stopped session had no id — on the gate's
  evidence there was no record to find.
- Custom `platformTest`/`testIde` source sets — they cannot boot
  `BasePlatformTestCase`; use the standard `test` source set.

## Open questions

- **Make pi PRESET?** `pi --help` documents `--session-id <id>  Use exact
  project session ID, creating it if missing` — the same contract that makes
  Claude PRESET. See `q-pi-preset-session-id`.
- The handoff's status table names no word for RUNNING with **unreadable**
  activity, which is most live sessions — only Claude ships an
  `ActivityReporter`. Interim answer: `SessionDots.statusWord` returns
  `"Running"`, a fifth word the handoff does not sanction. Needs confirming.
- Two strip controls (`Changes`, `⋯`) currently show an explicit "not built
  yet" message rather than doing nothing. They land in P7–P9.
- `ThreadLeakTracker` failed one `test` run and did not reproduce on
  `clean test`. Flaky; watch it rather than chasing it.

## Immediate next step

Pick one:

- **P2-P9**, in the handoff's order: Agents widget, New Agent Session dialog,
  attention balloons, close/quit confirmation, Welcome tab, review in the
  Commit tool window (plan for S4's fallback -- a Drydock Review tab on
  `ChangesBrowserBase`), diff decoration, `Finish` / "Done with this session".
- **Decide `q-pi-preset-session-id`** first: pi documents
  `--session-id <id>  Use exact project session ID, creating it if missing`,
  the same contract that makes Claude PRESET. Switching pi would delete a whole
  class of discovery flakiness. Verify the flag empirically before building on
  it.

Also still unverified: **stop/resume with an actual conversation**. The gate
exercised keystrokes only, so no agent ever wrote a session record and the
late-binding path has never had anything to bind. One real exchange, then
Stop, then Resume, settles it; the log now says either
`Bound agent session id <id> to session <row>` or
`No agent session id resolved for <row> in <dir>`.


## Gates

From `intellij-plugin/`: `./gradlew test buildPlugin verifyPlugin` — green.
Plugin Verifier reports **Compatible, no internal API usage** on all four IDEs
(clean on the IU-253 target; 1–4 deprecation notes on 2026.1–2026.3, every one
an API whose replacement does not exist in 2025.3).

## Cairn pointers

- `hyp-editor-tab-agent-console` — the editor-tab model; CONFIRMED by S1.
- `ev-s1-manual-gate` — the S1 verdict, the two defects it caught, and the
  on-disk proof about pi's session records.
- `find-resume-only-when-resumable` — Start vs Resume, and its two traps.
- `q-pi-preset-session-id` — the pi PRESET proposal.
- `ev-design-handoff-mvp` — the handoff's surfaces, copy and spikes.
- `ev-phase1-editor-runtime` — what was built, and the platform API facts.
- `ev-provider-readonly-flags` — the per-provider read-only decision.
- `q-status-word-for-unknown-activity` — the vocabulary gap.
- `hyp-execution-api-session-model`, `ev-execution-spike-phase0b`,
  `ev-execution-console-phase0a`, `ev-manualgate-fixround`,
  `ev-v2-implementation`, `deadend-custom-testide-source-set`.
