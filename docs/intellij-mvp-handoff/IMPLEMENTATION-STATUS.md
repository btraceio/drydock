# IntelliJ plugin MVP — implementation status

What is built against the design handoff in this directory, what is not, and
where the design was deviated from on purpose.

`README.md` beside this file is the handoff **verbatim**, as received; the
`.dc.html` files are its design references (open `Drydock MVP.dc.html` in a
browser, keeping `support.js` next to it). Neither is edited — this file is
the only place the implementation's own state is recorded.

*Last updated: 2026-10-19, against `feat/intellij-mvp`.*

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
| 2 | Agent editor tab | **partial** — tab, status dot, `RO` chip, strip, state words, Stop/Resume/Start. Missing: the 50 %-dimmed transcript, file paths as hyperlinks, `<n> files changed`, and the `<repo> · <branch>` window title |
| 3 | New Agent Session dialog | **built** — task, agent, Writes/Read-only, Here vs new worktree, both warnings, `Start anyway` |
| 4 | Attention ("needs you") | **not built**. The activity monitor already publishes `NEEDS_ATTENTION`; the balloon, the OS notification and the amber widget do not exist |
| 5 | Resume and idle state | **mostly** — resume is by id, never `--continue`/`--last`; nothing auto-starts. `Resume · ~Nk tokens` never shows its estimate because `ResumeCostEstimate` is not wired |
| 6 | Close / quit confirmation | **not built**. Closing a window *does* stop its agents and leave the rows idle; the `Stop <n> working agent(s)?` dialog and `Don't ask again` do not exist, and quit has no dialog |
| 7 | Review in the Commit tool window | **not built** (needs S4) |
| 8 | Finish ▾ | **not built** |
| 9 | "Done with this session…" | **not built**. The strip's `⋯` says so explicitly rather than doing nothing |
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
  `./gradlew test buildPlugin verifyPlugin`. Plugin Verifier reports
  **Compatible with no internal API usage** on all four checked IDEs. The 1–4
  deprecation notes on 2026.1–2026.3 are all APIs whose replacements do not
  exist in the 2025.3 target.
- **Manual**, in `./gradlew runIde` → Tools → Drydock. S1's gate (above) was
  run this way, and so was window-close-without-stopping.

**Automated gates have twice been green while the feature was unusable** —
once when the create flow started an agent but opened no tab at all, and once
when the console leaked under `ROOT_DISPOSABLE`. Treat `runIde` as the real
gate for anything user-visible.

Still unverified by hand: the new-worktree path of surface 3 end to end (it
crosses a project boundary), and stop-then-resume of a session that has
actually held a conversation.

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
