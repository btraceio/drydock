# IntelliJ session hosting: twenty sessions without twenty editor documents

## Decision summary

**A stable session host is technically credible and is likely to address the reported editor-tab clutter. It is not yet proven to be more usable overall.** The important change is to separate session selection from editor-file selection, not to multiplex twenty agents through one terminal emulator.

The leading agent-first candidate is **one reusable editor-area host, with an explicit second-session comparison view**. A dock-pinned tool-window host is a credible code-first alternative when the terminal must remain visible alongside code. The available evidence does not establish which location the operator will prefer. No implementation decision is made by this report.

The safe architectural direction is:

- Keep each running process and its existing terminal model independent.
- Switch the component displayed by a stable host without releasing or recreating the previous session's terminal.
- Make the active session, its repository/worktree, and the input destination unmistakable.
- Retain all-session navigation and background attention independently of editor tabs.
- Support occasional comparison of two **different** sessions without duplicating a process or attaching one Swing component in two places.

Do **not** implement ordinary switching as close/dispose/replay. Do **not** replace twenty editor tabs with twenty tool-window tabs and call the navigation problem solved.

Cairn: `intellij-stable-session-host/hyp-stable-host-improves-twenty`, `intellij-stable-session-host/find-retained-console-seam`, `intellij-stable-session-host/dead-switch-by-release-replay`.

## 1. Scope and evidence standard

### Confirmed requirements

The operator identified editor-tab clutter as the motivation, requested comparison of host locations, described simultaneous viewing as occasionally useful, and authorized read-only inspection of the running IDE. The workload is **up to twenty concurrent sessions**.

This is an investigation, not an implementation or a prototype. No production/test source, IDE settings, or session runtime state was changed. No commits were made. Diagrams below are interaction proposals, not screenshots of implemented behavior.

### Evidence classes

| Class | Meaning here |
|---|---|
| Source-established | Traced through the current production implementation at `ceee22659b1ecd84f18cf7c5ee07adc0edd05200` |
| Observed | Read-only queries against the running IDE or inspection of installed platform APIs |
| Documented | Authoritative JetBrains documentation, retrieved during this investigation |
| Inferred/proposed | UX assessment or design recommendation; not a measured result |
| Not verified | Requires an implementation trial, interaction test, or benchmark that was not run |

Existing test sources were inspected as coverage leads, **not executed and not counted as passing evidence**. No speed, memory, error-rate, or preference improvement has been measured.

### Version boundary

The checkout targets IntelliJ `263.7031.23`. The installed IDE identifies itself as `IU-263.7307.200`; its deployed Drydock manifest reports platform build `263.7031.23`, but contains no source commit identifying it as this checkout. Installed API inspection and live observations therefore complement source analysis; they do not prove exact deployed/source equivalence. [S1, E1]

No matching committed Cairn memories were found for the principal plugin files. Historical investigation hits were treated as leads, not substituted for current-source verification.

## 2. What was observed in the running IDE

Two projects were exposed by the JetBrains bridge. Explicit, read-only `get_all_open_file_paths` calls returned:

| Project role | Open editor entries |
|---|---|
| Hub project | 18 UUID-named entries and one Markdown task document |
| Worktree project | One UUID-named entry and `README.md` |

`AgentConsoleFile#getName/getPath` returns the session UUID. The inventory is therefore consistent with many session editors being open. The bridge did not return their virtual-file types/protocols or process liveness: **this does not prove nineteen live agents**. [S2, E1]

Read-only Apple Accessibility inspection also found the selected session's status, origin worktree/branch, lifecycle controls, and the Drydock search/inbox surface. In one snapshot the selected console region measured 2543 × 1336 accessibility units and the sidebar 817 × 1340. This establishes one large, full-height console layout in use, not a preference for it. The inbox exposed 28 accessibility children; that number is **not** a session count. [E2]

No session was selected, closed, resumed, stopped, or typed into for this investigation. No timed navigation, visual screenshot review, small-screen trial, or alternate-host experiment was performed.

Cairn: `intellij-stable-session-host/ev-live-editor-inventory`, `intellij-stable-session-host/ev-platform-and-accessibility`.

## 3. What “one visual component” can mean

These interpretations are materially different:

1. **One stable outer container, retaining separate session consoles.** Selecting B removes A's component from the visible slot and displays B's existing component. A's process, output routing, and terminal object continue to exist. This is the source-supported candidate.
2. **One newly created console per selection.** Selecting B destroys or releases A's console and reconstructs it when A returns. Existing replay can preserve output only within its bounds; it is not a saved user-view state. Reject this as the ordinary switch path.
3. **One terminal emulator reconnected to different processes.** This needs explicit management of terminal parser/screen/history/input state for every session. The current implementation offers one console and one output sink per session, not a terminal-state multiplexing API. There is no reason to take on that redesign to remove editor tabs. [S3, S4]
4. **One surface continuously visible while code is selected.** A singleton editor document does not guarantee this. A dock-pinned tool window can remain alongside the editor; an editor host needs its own visible split to do so. “Stable identity” and “always visible” must not be confused. [D1, D3]

The recommendation concerns interpretation 1. Interpretation 4 determines the remaining host-location choice.

## 4. Current architecture and the feasible seam

### Ownership today

```text
Application-level AgentExecutionRegistry
  session A -> process A + OutputRouter A + optional console A/project owner
  session B -> process B + OutputRouter B + optional console B/project owner
  ...

Project-level AgentExecutionService
  owns the disposal lifetime of consoles created for that project

AgentConsoleEditor(A)                     AgentConsoleEditor(B)
  strip + root containing console A        strip + root containing console B
  wrapper disposal does NOT dispose A      wrapper disposal does NOT dispose B
```

This separation exists in production code:

- `AgentExecutionRegistry#startArgv` creates and registers the execution; `AgentExecution` stores process, router, host project, and console separately.
- `AgentExecutionRegistry#show` returns the same console when the target project already owns it.
- `AgentConsoleEditor#showBody` swaps a `JComponent`; `#dispose` removes its body, stops its timer and disconnects listeners, without disposing the console.
- `AgentExecutionRegistry#moveTo` creates a project-bound console, wires output/input, replays history and disposes the preceding console. It is an actual host move, not a cheap component selection.
- `#release` disposes the console while leaving a live process running; an unhosted execution that ends can be removed. [S3, S4]

A normal same-project switch can therefore be designed around retaining consoles. That is **architectural feasibility**, not proof that terminal layout, scroll selection, or focus remains correct when implemented.

### Proposed responsibility split

```text
Existing application execution registry: unchanged process/session identity
                      |
Retained per-session terminal consoles, bound to their project lifetime
                      |
Project presentation controller (new responsibility, not implemented)
  selected session ID | visible slot(s) | focused slot | recent-session return
                      |
One stable editor host OR one stable tool-window content
  active identity strip
  primary slot: existing console for selected session
  optional comparison slot: existing console for another session
```

The controller is a conceptual responsibility, not a prescribed class hierarchy. A component swap should not invoke start, resume, stop, release, replay, transcript scanning, or project opening as an incidental effect.

The current registry's `host/shownIn` already includes project-owned consoles whose editor is not selected. Keep that ownership distinct from **actually visible** and **keyboard-focused**. Making a console invisible must not silently mark its process stopped or remove it from project-close accounting. [S3, S10]

Cairn: `intellij-stable-session-host/find-retained-console-seam`.

## 5. Comparison of presentation models

The assessments below are design inferences grounded in the constraints above, not usability-test scores.

| Model | Benefit at twenty sessions | Main trade-off | Feasibility and suitability |
|---|---|---|---|
| A. Per-session editor documents, current | Familiar editor selection, per-session labels, explicit split/document affordances | Session documents accumulate alongside actual code; each is deliberately pinned and preview replacement forbidden | Working baseline. Appropriate for few sessions or document-oriented navigation, but retains the reported clutter. [S5] |
| B. One reusable editor-area host | Session editor count normally stays at one; full-height TUI area; code tabs remain ordinary files | Code selection in the same editor group hides the host; editor history no longer provides per-session history | Credible; the repo already uses a per-project reusable Review editor file. Leading agent-first trial candidate, not proof of terminal retention/restoration. [S6] |
| C. One dockable tool-window host | No session editor documents; dock-pinned mode can keep code and a session visible together | Shares width/height with code; competes with other tool windows; unpinned modes can disappear on focus loss | Credible documented Swing content/focus/disposal APIs. Leading code-first trial candidate. [D1–D3] |
| D. Stable primary host plus explicit comparison | B or C's bounded normal footprint, while preserving occasional A/B viewing | Needs slot ownership, focus and close rules; careless pinning can steal a component from its other view | Best functional fit for the reported occasional comparison need, but an increment over B/C, not a separate proof of host preference. [S3, E3] |

### Central editor: what it does and does not solve

B solves “twenty agent documents compete with my files” without removing IntelliJ's editor system. It leaves **one** synthetic editor document. It does not satisfy a literal requirement of zero editor documents, nor a requirement of uninterrupted visibility alongside code unless given a split.

Existing `ReviewWorkbenchFile#of` demonstrates the reusable-file pattern, but it is a `LightVirtualFile`. It is not a drop-in persistence solution: the current session protocol exists specifically so the platform can resolve restored editor URLs. Restoration must be designed deliberately. [S2, S6]

### Tool window: not necessarily a narrow right-hand console

C need not mean squeezing a TUI underneath the existing right-hand session list. JetBrains recommends vertical windows for trees and horizontal windows for wide/master-detail content. Bottom, full-height side, floating, and independent-window layouts are candidates with different space costs. Docking is a platform affordance; the preferred default is still a product choice. [D2, D3]

Creating a second adjacent task list would duplicate the existing navigator. A tool-window trial should use one coherent navigator-plus-session arrangement, or the existing navigator controlling a separate console surface—not two competing selections.

Stock IntelliJ Terminal supports editor/tool-window placement and splits, which is a useful interaction precedent. It is **not** evidence that its actions, process-close semantics, terminal engine or persistence can be reused unchanged by Drydock's direct `TerminalExecutionConsole` integration. [D4, S3]

### Lower-cost baseline adjustments

IntelliJ offers scrolling/squeezing/multirow tabs, a separate pinned row, and hidden tab headers. These are comparison controls, not changes made here. They can reduce visual pressure without changing session identity, but do not establish a stable session container. Preview-tab replacement conflicts with Drydock's explicit `FORBID_PREVIEW_TAB` and pinning policy; simply enabling preview is not a local fix. [D5, S5]

## 6. The navigator is the principal UX dependency

The current inbox already supplies useful building blocks: grouped attention states, project filtering, title filtering, list speed search, multi-selection, Enter activation and double-click activation. **It is not currently an always-visible list of all twenty sessions.** [S7]

Specific production behaviors matter:

- Running and Flagged groups normally show four items before “+N more.” Needs you does not truncate. Idle is collapsed by default.
- List speed search sees the materialized list entries, not hidden group members. The separate title filter can rebuild matching entries; these are different mechanisms.
- Selecting a console editor asks the inbox to select a matching **already displayed** row. It does not force a hidden group open or clear filters.
- Session rows are ordered by `lastOpenedAt`, not an independently maintained UI session-switch history.
- Activating a running row goes through `TaskNavigator#switchTo`, which can raise another IDE window. Activating a stopped row goes through `resumeOrStart`, which can launch a process. [S7, S8]

Consequently, “remove the tabs, leave the sidebar unchanged” is not an adequate twenty-session design.

### Required interaction properties for a viable trial

These are proposed acceptance requirements, not implemented features:

1. **Identity survives filtering.** The host always names the displayed session and worktree, even if its row is filtered out, collapsed or beyond a truncation boundary. Do not silently clear the operator's filter just to reveal it.
2. **All twenty remain reachable.** Keep a clear all-session navigation path; do not require knowing a hidden session's exact title. Decide whether an expanded list or an unfiltered picker is the better fit before implementing.
3. **Selection is not focus.** Moving the highlighted row for browsing or multi-select should not immediately move typing into another agent. An explicit activation commits the switch and focuses the terminal. A different single-click model is possible but needs its own trial.
4. **A return path replaces editor-session history.** Moving between A and B must not require scanning twenty rows every time. Define previous-session/MRU behavior and an actual binding; no shortcut is assigned in this investigation.
5. **Attention never causes automatic navigation.** Background status changes may update the inbox/badge; they must not replace the console or steal pending input. Explicit notification activation targets that session.
6. **Show and launch remain distinguishable.** Reusing the present stopped-row activation would resume it. If choosing stopped sessions becomes presentation-only, expose Start/Resume explicitly and treat the semantic change as a decision, not a hidden refactor.
7. **Status regrouping cannot redefine a command target.** Seeing/acknowledging an attention row can change its group. Actions must carry the intended session ID, not “whatever now occupies this row.”

These requirements follow the visibility, user-control and recognition concerns summarized by Nielsen's heuristics. Those heuristics support the evaluation criteria; they do not prove a stable host wins this comparison. [D6]

Cairn: `intellij-stable-session-host/find-navigator-twenty-session-gap`, `intellij-stable-session-host/hyp-stable-host-improves-twenty`.

## 7. Interaction sketches

The following are alternatives to compare, not a plan to build all of them.

### B: stable editor host, agent-first

```text
+------------------------- editor tabs -------------------+--------------------+
| Drydock Sessions | Foo.java | README.md                 | Drydock navigator  |
+--------------------------------------------------------+--------------------+
| session: Repair nightly | repo / branch | At prompt     | Needs you (3)      |
| [Changes] [Project window] [Deactivate] [Done...]        | Running (17)       |
+--------------------------------------------------------+ [filter / show all]|
|                                                        | session rows       |
|               selected session's existing TUI           |                    |
|                                                        |                    |
+--------------------------------------------------------+--------------------+
```

Activating session B changes the identity strip and body of this host together. It does not create another editor document. Selecting Foo.java in the same editor group displays code instead; returning to Drydock restores its selected session. A dedicated editor split is needed if both must remain visible.

### C: dock-pinned tool-window host, code-first

```text
+----------------------- editor: Foo.java ----------------------------------+
| Code stays selected in its editor group                                  |
+----------------------- Drydock tool window ------------------------------+
| session navigator  | session: Repair nightly | repo / branch | At prompt  |
| filter / show all  +-----------------------------------------------------+
| session rows       | selected session's existing TUI                     |
+--------------------+-----------------------------------------------------+
```

Code and terminal share height here. A side placement trades width instead. Dock-unpinned changes the persistence expectation; it is not interchangeable with dock-pinned for this scenario.

### D: occasional comparison, not twenty permanent documents

```text
+----------------------------- stable host -------------------------------+
| primary: session B                    | pinned: session A               |
| independent console B                | independent console A           |
| input goes only to the focused pane  | [unpin / close view]             |
+--------------------------------------+----------------------------------+
```

The primary slot can switch to C while A remains pinned. Requesting A again must focus its existing slot or explicitly move it, not attach its component twice. Closing the comparison view hides that view; Stop and Done remain separate actions. Mirroring the **same** terminal in two places is not part of this proposal. [E3]

## 8. Lifecycle and state matrix

“Proposed” below means a requirement to validate, not behavior already delivered by a stable host.

| Transition | Required result | Existing foundation / unresolved part |
|---|---|---|
| A → B within the same project | A keeps running and retaining output; B becomes the displayed/input session | Existing console ownership permits retention; hidden-layout, scroll/search/selection/IME and focus need experiment. [S3, S4] |
| A has an unsubmitted draft; select B | No submission, clearing, or rerouting of A's draft | Keep terminal/process identity; provider draft behavior and rapid switching not tested |
| B needs attention while A is displayed | Indicate B without switching away from A | Existing monitor/notifier can be reused only after replacing editor-file visibility assumptions. [S9] |
| Hide/close primary host | Hide presentation, not Stop or Done | Current editor wrapper does not own the console; new host must preserve this separation. [S3] |
| Hidden retained A exits | Mark stopped; retain its readable final console according to current hosted-execution policy | Do not call release merely because A is not visible; unhosted termination removes the execution. [S4] |
| Show a stopped session | Show the stopped/missing-transcript state; Start/Resume is explicit if this interaction is chosen | Current editor opening is show-only, but current inbox stopped-row activation resumes; resolve this difference. [S5, S8] |
| Pin A, switch primary B → C | A stays pinned; B remains independently alive | Needs explicit slot ownership and focused-session routing; not a second parent for A's component. [E3] |
| Resize host while A is hidden | A returns with a valid TUI size and coherent screen | Existing process connector owns resize; hidden-component/layout behavior not tested |
| Transfer or move session between projects | Apply existing launch/move guards; do not promise same-console state preservation | Current moves recreate the console and replay output. [S4] |
| Project close | Respect existing close/keep-running/stop policy, for all owned consoles, not just visible ones | AgentCloseConfirmation, AgentWindowCloseListener, registry host accounting. [S10] |
| IDE restart | Restore intended host/selection, without automatically starting an agent | Current protocol has one restore owner; singleton needs a migration/restoration contract. [S2, S10] |
| Async result for A arrives after selecting C | It may update A's cached facts, never paint A's header/actions over C's body | Current editor identity is final; a mutable singleton selection needs session/generation fencing. [S3] |

For comparison, viewable sessions and the focused input session are different sets. Define whether merely displaying the secondary pane acknowledges attention, or whether focus/explicit activation is required. The current active-frame/selected-file test is only an approximation of looking at a session; do not silently broaden its meaning during migration. [S9]

## 9. Integration risks: beyond a component swap

| Area | Current coupling | Required migration discipline |
|---|---|---|
| Open/show/focus entry points | `AgentConsoles` opens a session-specific file | Route creation, explicit Show, notifications and task navigation through the chosen host, without retaining hidden open-per-session side effects |
| Attention acknowledgement | `isBeingViewed` checks active JFrame and selected session-specific files | Use actual host visibility, displayed IDs and explicit acknowledgement policy; include tool-window hide, split and detached-window cases |
| Navigator synchronization | `FileEditorManagerListener` extracts ID from AgentConsoleFile | Publish selected/displayed session state directly; retain selection independently of filters |
| Header and context actions | `AgentTabCommands` resolves VirtualFile and per-editor cached facts | Supply the clicked pane's explicit session ID and presented lifecycle intent; do not use a global latest selection |
| Async facts | Per-editor session/file identity is immutable | Fence singleton selection generations; stale transcript/capability/path results cannot repaint another session |
| Persistence | `drydock-agent://<id>` plus provider type ID | Preserve one restore owner; reconcile legacy URLs into the new presentation without starting agents or opening twenty new hosts |
| Comparison | One console per session; one Swing parent per component | Support different-session slots; route already-pinned selections without component stealing |
| Worktree context | File links/reviews/MCP route to the session's working project | Keep project identity distinct from visual selection; a hub host does not switch code intelligence/VCS context |
| Project disposal | Consoles are children of project execution service | Host content disposal must not accidentally dispose a retained session; project/plugin shutdown must still clean up |

This is a cross-cutting presentation migration, not a terminal/process rewrite. It is more than replacing `openFile` with `panel.add`. Exact effort is not estimated without settling the host, navigation and restoration semantics.

Cairn: `intellij-stable-session-host/find-visibility-is-editor-coupled`, `intellij-stable-session-host/find-one-console-one-parent`.

## 10. Worktree boundaries that must remain explicit

Changing the visible console **does not change the IntelliJ project** providing code analysis, VCS state, search scope or review checkout.

- `TaskNavigator#switchTo` resolves an existing host/worktree project and may raise another window. `Show Here` is a separate, explicit move today.
- `WorktreeFileLinks` opens paths in the session's own worktree project.
- Hosted-session Changes opens its own project before opening review.
- `IdeMcpServer#forWorktree` builds the session's bridge configuration using its worktree, not the host's selected session. A worktree without an open project must not be silently answered from the hub project. [S8, S11]

A stable host can consolidate agent conversations, but it is not a twenty-project IntelliJ workspace virtualization feature. Preserve present cross-window defaults unless a separate decision changes them. A notification that targets another project's session must make the destination clear rather than silently moving its console into the caller's project.

Cairn: `intellij-stable-session-host/find-cross-project-is-not-host-switch`, `intellij-stable-session-host/find-mcp-worktree-boundary`.

## 11. Performance and allocation implications

**No performance improvement is measured or promised.**

The ordinary retained-console design keeps approximately the same set of process, output-buffer and terminal-model resources for sessions that already have consoles. It removes the need for twenty editor wrappers, but it does not turn twenty running terminals into one terminal's memory/CPU footprint.

Source-established costs:

- Each execution's ReplayBuffer retains up to 20,000 lines or 8 × 1024 × 1024 UTF-16 code units, whichever limit applies first. Twenty sessions at the character cap correspond to 167,772,160 retained code units across replay buffers, **not a measurement of heap bytes**. Terminal models, strings, deque entries and processes add other costs. [S4]
- Recreating a console runs `OutputRouter#attach`, which builds/replays a history snapshot; this adds work proportional to retained history. It is not an appropriate constant-cost switch path. [S4]
- Every AgentConsoleEditor creates a ten-second age timer, subscribes to events, and its refresh requests icon updates. Reducing wrappers could reduce that presentation work, but its contribution has not been measured. [S3]
- Keeping hidden consoles means their output path still receives/processes output. Do not introduce background-terminal suspension or renderer eviction under the guise of this UX change.

Proposed hot-path budget: selection should require only ID/slot lookup, a component swap and visible-state/focus updates. Metadata probes stay asynchronous with immediate progress and stale-result fencing. Benchmark this rather than claiming it is fast from the architecture alone.

## 12. Adversarial assessment

### Reasons not to change the current model

- It already works and gives each session an addressable editor identity with normal document navigation.
- Users who frequently compare many sessions may prefer explicit independent editors; this operator reported comparison only occasionally, but that distinction should be exercised in the trial.
- A central singleton can make returning from code worse if it loses per-session history without a replacement.
- A tool window can reduce terminal area, disappear in unpinned modes, or compete with the existing navigator and review UI.
- A hidden terminal component can still have layout/focus/resize behavior that only a running experiment exposes.

### Reasons the stable-host direction is promising here

- It directly bounds the session contribution to editor-document count at the reported twenty-session workload.
- Production already separates process ownership from editor presentation; a process rewrite is unnecessary for the core change.
- The existing inbox already carries status, attention and project context, so editor tabs are not the only conceptual session-navigation surface—although its truncation/filter behavior needs attention.
- A deliberate comparison slot fits the stated need better than permanently opening every session as a document.

**Synthesis:** the direction is worth a trial; a claim of proven superiority or a fixed host-location decision would exceed the evidence.

## 13. Validation plan before an implementation decision

No new code is authorized by this plan. A throwaway-host experiment would require separate approval. Use the same session set, task scripts and starting state for each candidate.

### Usability comparison

Compare current editors, a reusable central host and a dock-pinned tool-window host. Exercise both the observed large-screen hub arrangement and an ordinary-size window; choose the second size with the operator rather than treating a synthetic resolution as actual workload.

| Task at twenty sessions | Observe |
|---|---|
| Open session 17 by a recognizable name/project | Retrieval time, search/expand actions, wrong session selections |
| Return A → B → A while preserving code context | Return-path time and reliance on remembered tab position |
| Handle three attention arrivals while drafting in A | Involuntary navigation, lost drafts, incorrect acknowledgement |
| Locate A when its group is truncated or a filter excludes it | Whether identity and navigation remain understandable |
| Read A and B simultaneously; switch primary to C | Comparison setup cost and component/focus confusion |
| Follow a code link and inspect Changes for a hosted session | Correct worktree/project destination and return path |
| Hide the host and later reopen it | Ability to distinguish hide, stop, resume and Done |

Use repeated tasks with counterbalanced candidate order; do not compare a familiar baseline with a wholly unpracticed alternative once and call it conclusive. Record task time, misnavigation and wrong-session input separately. Acceptance gates: no involuntary activation, launch or wrong-session input; no lost required terminal state; tab clutter reduced; navigation and code/session return acceptable to the operator relative to baseline. Exact timing thresholds remain to be agreed.

### Technical trial gates

- Same PID and console identity across same-project switches; no extra process or new console per switch.
- Scrollback position, selection, search state, unsubmitted input, IME and fullscreen rendering survive A/B/C switching for the providers actually in use.
- Late A completions cannot paint over C or retarget C's actions.
- Hidden sessions continue receiving output and report exits; stopped transcripts follow the agreed retention policy.
- Attention works with primary/comparison panes, code in front, hidden tool windows, inactive project windows and detached hosts.
- Closing a view never stops/deletes a session; closing a project/IDE still follows existing close policy, including remote workspace sessions.
- Restore an old workspace with twenty legacy console URLs; get the agreed bounded presentation without starting any agent.
- Cross-project moves retain existing single-owner and launch guards; file, Changes and MCP operations remain worktree-correct.
- Observe CPU, heap and selection latency at twenty sessions, including output bursts; repeat switches to detect retained disposed views/listeners.
- Run focused existing registry/console/inbox tests plus new host-level integration tests. The existing tests are starting points, not evidence these new gates already pass. [S12]

Cairn: `intellij-stable-session-host/q-interactive-validation`.

## 14. Claim-to-source checklist

All source references below are repository-relative, verified against the commit named in §1. Symbols are the primary anchors; line numbers are secondary navigation hints.

| ID | Claim checked | Implementation / evidence |
|---|---|---|
| S1 | Platform target | `intellij-plugin/build.gradle.kts`, IntelliJ dependency around line 151 |
| S2 | Session-file identity and restorable URL rationale | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentConsoleFile.java#getName/getPath`; `AgentConsoleFileSystem.java#fileFor/findFileByPath`; provider `getEditorTypeId` |
| S3 | Editor presentation ownership, timer, async facts, body/focus/disposal | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentConsoleEditor.java#reloadSession` (270), `#refresh` (442), `#showBody` (595), `#getPreferredFocusedComponent`, `#dispose` (723); `AgentExecution.java#shownIn/notShown` |
| S4 | Execution lifetime, recreation, output/replay cost and limits | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentExecutionRegistry.java#startArgv` (372), `#terminated`, `#show` (504), `#moveTo` (520), `#release` (600); `OutputRouter.java#onText/attach/detach`; `ReplayBuffer.java#trim/snapshot` |
| S5 | Pinning, no preview, show distinct from launch | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentConsoles.java#show` (75), `#pin`, `#start`, `#focusConsole` |
| S6 | Reusable editor and tool-window precedents, not persistence equivalence | `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewWorkbenchFile.java#of/open`; `ReviewWorkbenchEditorProvider.java#createEditor`; `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/DrydockToolWindowFactory.java#createToolWindowContent` |
| S7 | Inbox truncation, filtering, order, materialized selection | `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/InboxModel.java#GroupKind/Group.shown/build/place`; `InboxList.java` constructor, `#select/rebuild`; `TaskTreePanel.java#selectSessionRow` (2351) and editor-selection subscription (970) |
| S8 | Session activation differs for running/stopped; cross-window navigation | `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreePanel.java#activate` (2557); `TaskNavigator.java#switchTo/windowFor`; `TaskActions.java#taskActions` Show Here / Resume handlers |
| S9 | Visibility-derived acknowledgement and editor-context targeting | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentConsoles.java#isBeingViewed` (497); `AttentionNotifier.java#flush/raise`; `AgentConsoleEditor.java#acknowledgeIfViewed`; `AgentTabCommands.java#resolve/editorFact`; `SessionCommandHandlers.java#lifecycle` |
| S10 | Window/IDE close and restore are not hide/switch | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/AgentCloseConfirmation.java#canClose/confirmQuit`; `AgentWindowCloseListener.java#projectClosing`; `AgentExecutionService.java#dispose`; `AgentExecutionRegistry.java#hostClosed`; `intellij-plugin/src/platform/java/app/drydock/intellij/startup/DrydockStartupActivity.java` startup path |
| S11 | Worktree-aware links, review and MCP | `intellij-plugin/src/platform/java/app/drydock/intellij/agent/WorktreeFileLinks.java#Open.navigate`; `SessionCommandHandlers.java#showChanges`; `AgentConsoles.java#jumpToOwnWindow`; `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabOpener.java#openIn/mint`; `intellij-plugin/src/platform/java/app/drydock/intellij/mcp/IdeMcpServer.java#Bridge.forWorktree` |
| S12 | Relevant existing tests, inspected only | `intellij-plugin/src/test/java/app/drydock/intellij/agent/AgentExecutionRegistryPlatformTest.java#testShowingAgainMidStreamKeepsTheProcessAndSwapsTheConsole/testAReleasedAgentKeepsRunningAndIsNotShown/testAnAgentThatEndedWhileNotShownCannotBeShown`; `AgentConsoleEditorPlatformTest.java`; `intellij-plugin/src/test/java/app/drydock/intellij/tasks/InboxModelTest.java`, `InboxListPlatformTest.java` |
| E1 | Live editor inventory and deployed-version boundary | Cairn `ev-live-editor-inventory`; read-only JetBrains bridge results; installed Info.plist and plugin manifest |
| E2 | Accessible UI and installed API presence | Cairn `ev-platform-and-accessibility`; raw `reports/live-accessibility-vikgs2dq.txt` and `reports/ide-api-probe-husq5zx2.txt` in the investigation trail |
| E3 | One parent per terminal component | OpenJDK 25.0.3 `java.desktop/java/awt/Container.java#addImpl` (1099–1136, removing existing parent at 1117–1118), inspected in local JDK source archive; [upstream symbol source](https://github.com/openjdk/jdk25u/blob/master/src/java.desktop/share/classes/java/awt/Container.java); current `AgentExecution` single console / `OutputRouter` single sink |

### Public sources

- **D1:** [JetBrains Plugin SDK: Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html) — content, focus, lifecycle, scheduling and tool-window groups.
- **D2:** [JetBrains UI Guidelines: Tool Window](https://plugins.jetbrains.com/docs/intellij/tool-window.html) — orientation, feedback, tabs and empty states. It allows tabs for similar instances; it does not prescribe this report's single-host design.
- **D3:** [IntelliJ IDEA: Tool window view modes](https://www.jetbrains.com/help/idea/viewing-modes.html) — dock-pinned, unpinned, undocked, float and independent window behavior.
- **D4:** [IntelliJ IDEA: Terminal](https://www.jetbrains.com/help/idea/terminal-emulator.html) — layout precedent, not Drydock lifecycle/API equivalence.
- **D5:** [IntelliJ IDEA: Editor Tabs](https://www.jetbrains.com/help/idea/settings-editor-tabs.html) — alternative tab arrangements and preview/close policies.
- **D6:** [Nielsen Norman Group: Ten Usability Heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/) and [Recognition and Recall](https://www.nngroup.com/articles/recognition-and-recall/) — evaluation principles, not a controlled comparison of these designs.
- **D7:** [JetBrains Plugin SDK: Disposer and Disposable](https://plugins.jetbrains.com/docs/intellij/disposers.html) — resource ownership, content lifetime and cleanup.

The JetBrains pages were downloaded into the Cairn trail as text snapshots. Web material was used as evidence only, not as instructions. No private source, session titles or local logs were sent in search queries.

## 15. Recommended next decision

**Approve the direction for a bounded prototype, not a production migration yet.** Choose its first location according to the workflow to preserve:

- **Agent-first / hub workflow:** trial one reusable editor-area host, with the existing navigator and an explicit second-session view. This preserves the large full-height conversation area while removing normal per-session document growth.
- **Code-first / continuously alongside code:** trial one dock-pinned tool-window host. Its space/focus trade-off needs direct comparison, especially on a smaller window.

Both need explicit selected-session state, navigator completeness and safe lifecycle semantics. Do not build both host implementations, a new terminal engine, or a general multi-project workspace as an incidental consequence of this investigation.

The research phase establishes the feasible seam and the migration constraints. Host preference and real switching behavior remain empirical questions. The next step requires operator approval; this report does not authorize code.
