# UI redesign (design-handoff implementation)

The JavaFX UI was rebuilt against the high-fidelity design handoff
(`Drydock.dc.html` + its README, provided out-of-repo in
`/tmp/handoff`). The design README is the source of truth for measurements,
colors, and interactions.

## Structure

| Piece | Class | Notes |
|---|---|---|
| Window shell | `app.drydock.ui.AppShell` | Undecorated stage, custom 44px title bar (`TitleBar`), manual edge resize (`StageResizer`), SplitPane with sidebar clamped 220–520px, in-scene `ModalLayer`. |
| Theming | `ThemeManager` + `app.css` / `theme-dark.css` / `theme-light.css` | `app.css` is structure only; ALL colors are looked-up tokens in the two theme sheets, swapped at runtime. Theme persists in `WorkspaceUiState.theme`. JetBrains Mono is bundled under `resources/app/drydock/ui/fonts/`. The two invariants this rests on — the sheets defining identical token names, and every `app.css` font size being a `px` literal so `UiFontScale` can scale it — are pinned by `ThemeTokenContractTest`. |
| Sidebar | `RepositorySidebar` | `TreeView` of repos → session children, custom cells (caret, branch line, running dot, hover quick-actions), accent Add-repository menu (disk / GitHub clone), live filter, footer status line. The Review destination row is pinned above the tree. |
| Tabs + session view | `MainWorkspace` + `OpenSessionTab` | Two-line tab graphic (repo over title) with status dot + close ×, double-click inline rename, trailing "+" repo menu; per-tab session header (back, title/meta, status pill, rename) above the ghostty host. Session tabs are Claude · Terminal · Explorer. |
| Resume picker | `ResumePickerView` + `app.drydock.claude.ConversationCatalog` | Shows when no tab is selected (or after Back/Esc). Lists real conversations from `~/.claude/projects/<encoded-cwd>/*.jsonl`; Enter/click adopts the conversation as a managed session (`SessionManager.adoptConversation`) and resumes it exactly (`claude --resume '<uuid>'`). |
| GitHub clone | `GitHubCloneModal` + `app.drydock.github.GitHubService` | Live unauthenticated GitHub search (or pasted URL), `git clone` into a chosen parent dir, auto-registers the clone. Needs the `java.net.http` jlink module (added in `app/build.gradle.kts`). |
| Headless FX tests | TestFX 4.0.18 + Monocle 21.0.2 (`app/build.gradle.kts`) | View tests show a real `Stage`, apply the real stylesheets and drive real key events with no window server, so they run identically locally and on the `macos-14` CI runner. `headless.geometry` must exceed the widest scene any test builds — Monocle's default 1280×800 virtual screen overflows the software pixel buffer otherwise. |
| Shortcuts | `ShortcutsOverlay` + scene filter in `DrydockApplication` | `?` overlay, Esc close/back, ⌘⇧L theme, ⌘F filter, ⌘N new session, ⌘R rename, ⌘4 Review. Sectioned; the IN REVIEW section lists only keys that are actually bound. |

## Review destination (Review handoff)

Review is a **global destination, not a session sub-tab**
(`OpenSessionTab.SubTab` is now `CLAUDE · TERMINAL · EXPLORER`). `⌘4` is a
navigation command: from anywhere it shows Review, scoped to the current
session's checkout.

| Piece | Class | Notes |
|---|---|---|
| Scope handles | `app.drydock.review.ReviewScope` / `ReviewScopeRegistry` | Opaque `rs_…` handles derived by HMAC from the scope's identity — **a place, plus which PR it is**: kind + repo + worktree + PR number, and deliberately *not* base or head — and a per-profile secret persisted in the annotation store. Deterministic, so a handle survives a restart and persisted findings stay addressable; keyed rather than path-derived, so the handle leaks no repository paths. Grants let a human hand one session's agent another worktree's scope. |
| Queue assembly | `ReviewQueueService` | `WorktreeService.list` + `GitStatusService` + `gh pr list --search "review-requested:@me"`, grouped MINE / AGENTS / REQUESTED / STACK. A repository whose git fails, or a missing `gh`, contributes nothing and is logged — the rest of the queue still assembles. |
| `gh` review requests | `GhCliService.listReviewRequests` | Read-only, per repository, bounded; a malformed row is skipped rather than emptying the group. |
| Destination view | `app.drydock.ui.review.ReviewDestinationView` | 36px title bar, queue rail, two-row item header (what is being reviewed; its session binding), and a body supplied by its `Host`. |
| Diff column | `ReviewDiffColumn` + `ReviewDiffRows` / `ReviewDiffRow` | Virtualized `ListView` over a pure row model. Hunk cards are drawn from a per-row `Edge` (top/body/bottom) rather than nested containers, so the column stays virtualized on a 21-file diff. Nothing sets a cell height — a fixed row height was the bug that hid code with no scrollbar. |
| Code lexer | `app.drydock.ui.code.SyntaxHighlighter` | Moved out of `ui.explorer` now that two surfaces render code. One lexer, two output shapes: `spans` (plain data, for the diff column's per-line `TextFlow`) and `computeHighlighting` (RichTextFX, for the Explorer's `CodeArea`). |
| Density | `ReviewDensity` | `d` cycles cozy/compact/dense by swapping one style class; the measurements are `px` literals in `app.css`, so density stays relative to the user's absolute interface size. |
| Queue rail | `ReviewQueueRail` | 236 / 206 narrow / 44 collapsed, animated the way `SessionExplorerView` animates its search rail. Every row is a real focusable `Button`. |
| Terminal swap | `MainWorkspace.setReviewShowing` | Review is a scene-graph view, so showing it hides every native terminal — one writer (`updateTerminalVisibility`) over three independent conditions (selected tab, modal up, Review showing), and `updateGeometryNow()` on the way back. |
| Findings + verdicts | `app.drydock.review.AnnotationStore` / `ReviewAnnotation` / `ReviewVerdict` / `ReviewIntent` / `IntentGrouping` | Everything keyed by `(scopeId, id)`. Findings carry severity, confidence, intent, evidence, a proposed patch, ASK chips, a thread and a human severity override. Intents come from `review_intents`, or from the by-file fallback that keeps the verdict bar meaningful with no reviewer configured. |
| Findings margin | `ReviewFindingsMargin` | 336 / 286 narrow / 30px strip. Cards sit beside the code, never inline. Reply drafts are held by the margin keyed by `(scopeId, id)`, not by the card node — a card is rebuilt whenever its finding changes, and a node-owned draft would go with it. |
| Verdict bar | `ReviewVerdictBar` | Below both columns and always in the layout, so collapsing every rail cannot take the primary action with it. Approval is refused inline while a blocking finding of the intent is open. |
| Review MCP tools | `McpToolRouter` + `ReviewToolCodec` | `review_scope` (paged on a byte budget), `review_intents`, `review_finding` (idempotent upsert), `review_answer`, `review_state`. Every inbound text field goes through `PromptSafety.checkInboundText`. |
| Intent rail | `ReviewIntentRail` | 232 / 196 narrow / 40px collapsed. Risk heat bar, kind tag, collapsed-intent note; settled intents dim, and collapse to a status dot rather than a clipped label. |
| PR checkout | `app.drydock.git.PrCheckoutService` + `ReviewCheckoutGate` | Detached worktree, then `gh pr checkout` **inside it**, then a session, then the scope grant. A failed checkout removes the worktree it made. |
| Symbol lens | `SymbolIndex` + the diff column's popover | A local lexical index of the diff — never MCP. Identifiers it knows get a dotted underline; the popover chips each occurrence `in diff` / `not touched`. |
| MCP activity | `app.drydock.mcp.McpActivityLog` + `ReviewMcpActivityPanel` | Bounded ring buffer written by the one place every tool call passes through; `\` opens the panel, with a payload inspector and a budget bar. A hidden panel listens to nothing. |
| Sidebar entry | `RepositorySidebar` | Focusable `◨ Review` row above the tree with an item-count badge, plus a `◨n` badge on worktree rows that jumps into Review scoped to that worktree. |

## Visual verification

`-Dapp.drydock.diag.screenshot=<path>` renders the scene graph to a PNG from
inside the process, on the FX thread. It needs no macOS screen-recording
permission, cannot capture the wrong window, and works where a screen
capture is unavailable — which is the only reason the FX layer had no visual
evidence before. It uses `java.desktop` (already on the jlink module list)
rather than `javafx.swing` (which is not), copying pixels out of the
`WritableImage` by hand.

Two bugs were found by looking at that image and by nothing else:

- The verdict bar was stuck on **"no intent"** on every freshly opened item,
  so Approve, Request change and Submit were all dead. Intents fall back to
  one-per-file and are therefore derived *from* the diff, which arrives
  asynchronously; the bar rendered once against an empty diff and never
  re-rendered. Every test until then supplied the diff synchronously and so
  could not see it. `ReviewIntentFallbackTest` now drives the real
  asynchronous path.
- Diff rows were **almost twice their intended height**. Section 4.8's
  "row padding 8 / 6 / 4px" is the prototype's `ipad`, which it applies to
  the queue and intent cards — not to code lines, whose height is
  font-size × line-height. Applying it to both gave 36px rows where the
  design wants 20px.

## Known deviations from the handoff

- The tab-strip "+" button sits at the top-right of the strip (a TabPane
  cannot easily append a trailing node after the last tab).
- The resume picker always shows the "all projects" scope; ⌘A/⌘B scope
  toggles are documented in the keycap footer but not yet implemented.
- Conversation preview (Space) is not implemented.

### Review

These are design decisions the implementation forced, not defects — they
need a look before the next milestone builds on them.

- **`showReview(scope)` is two methods.** `WorkspaceNavigator` exposes
  `showReview()` and `showReviewForCheckout(Path)`. Callers (a session
  pressing ⌘4, the sidebar's `◨n` badge) know a checkout, not a scope
  handle; minting the handle is the registry's job, and the queue may not
  have assembled yet when the request arrives.
- **The queue rail is a scrolled `VBox` of `Button`s, not a `ListView`.**
  The handoff maps it to `ListView` with a custom cell, but the hard rule
  that every interactive element is focus-traversable is much cheaper to
  honour with real buttons, and a queue is tens of rows — nothing is gained
  from virtualization.
- **Light-theme values for the three new greys are invented.** The handoff
  specifies dark only (`#1a1918` queue rail, `#181716` intent rail,
  `#1c1b1a` hunk header). The light equivalents keep the same ordering
  against the existing light ramp. Same for the semantic severity tokens.
- **Semantic tokens duplicate existing values.** `-drydock-question`
  (`#d9a03a`) has the same value as `-drydock-dirty`, and
  `-drydock-deviation` the same as `-drydock-merged`. They are separate
  names because the meanings are unrelated: a MED-risk intent is not a dirty
  worktree, and collapsing them would make a future palette change to one
  silently move the other.
- **Scope identity excludes base and head.** They are properties of a
  review, not what makes it that review, and both move for reasons unrelated
  to it. Including them was a silent data-loss bug: findings and verdicts are
  keyed `(scopeId, …)`, so anything that moved the base — checking out a PR,
  or a plain `git switch` in the main checkout — re-derived every worktree's
  handle and made that worktree's review vanish from the UI while sitting
  untouched in the store under the old handle. Two regression tests pin it,
  one at the registry and one end-to-end against a real repository.
- **The base is the repository's default branch, not the current one.**
  `GitStatusService.defaultBranch` resolves `origin/HEAD`, then the
  conventional names, and only then falls back to whatever is checked out.
  It returns a ref that actually **resolves**: `origin/HEAD` names a *remote*
  default, and `git clone -b feat/x` (or deleting a local `main` after a
  merge) leaves no local branch of that name, so the bare name would fail
  every worktree's diff with "unknown revision". With no local counterpart
  the remote-tracking ref (`origin/main`) is used instead.
  Deriving it from the main checkout's current branch made every queue item's
  diff follow the user's branch switches, leaving line-key-anchored findings
  pointed at unrelated code with nothing on screen to say so.
- **Scope handles are derived, not minted at random.** An earlier revision
  generated a fresh random id per process, which meant findings persisted
  under a `scopeId` were orphaned on the next launch. They are now an HMAC of
  the scope's identity under a per-profile secret stored alongside the
  findings (schema version 3), so the same worktree resolves to the same
  handle across restarts without putting repository paths in the handle.
  Identity paths are normalized, so a symlinked or relative root does not
  produce a second handle for the same scope.
- **The `STACK` group is modelled but never produced.** Stacked-PR detection
  is not implemented; this is open decision §10.4 (stacks have no dedicated
  view), so nothing was invented for it.
- **Finding counts are a proxy until the store is scope-keyed.** The queue's
  open-finding count and the sidebar's `◨n` badge currently count open
  `ReviewAnnotation`s for the scope's bound session, not findings for the
  scope. `AnnotationStore` becomes `(scopeId, id)`-keyed in M3, and both
  read the real thing then. An item with no session shows no count at all,
  which is already the specified behaviour.
- **`f` toggles rather than only collapsing.** The spec says "focus mode —
  collapse everything"; a one-way key would be dead on its second press, so
  it collapses when anything is expanded and restores otherwise (which is
  what the prototype does).
- **`j`/`k` clamp rather than wrap.** Holding a key must not teleport from
  the last REQUESTED PR back to the first MINE item — that silently moves
  the review to a different repository.
- **`ReviewView` (the per-session diff tab) was deleted, not parked.** Its
  hunk-row model (`ReviewRow` / `ReviewRowModels`) is kept — the new diff
  column builds on it — but the inline-annotation view itself is superseded
  by the findings margin. Git history is the archive (AGENTS.md).
- **`OpenSessionTab.openExplorerAt` / `searchInExplorer` are currently
  unreferenced.** They are the `⤢` bridge the diff column consumes; they
  were left in place rather than deleted and recreated one milestone later.

### Review — M2 (diff column)

- **Review asks git for a wider context window.** `DiffService` gained an
  explicit `contextLines` parameter and Review passes 12 rather than git's
  default 3. Found by testing: with a three-line window no unchanged run is
  ever longer than the fold threshold, so `⋯ N unchanged` would have
  rendered correctly and simply never appeared. Showing more context and
  folding it is the point of the feature. Other callers keep git's default.
- **Line height is approximated by row height.** The handoff specifies line
  heights of 1.6 / 1.42 / 1.25; JavaFX has no line-height on a single-line
  row, so each density sets `-fx-min-height` to the font size times that
  ratio instead.
- **Collapsed runs are expandable.** The spec only says unchanged runs
  collapse to `⋯ N unchanged`. Making the row a focusable `Button` that
  expands its own run costs nothing and follows the hard rule about
  interactive elements; runs are keyed by (file, hunk index, run index) so
  an expansion survives a re-diff that shifts the lines.
- **`⤢` disables itself when there is nowhere to go.** The Explorer lives
  inside a session's tab, so a scope with no bound session (or a closed tab)
  cannot open one. The button reports that and explains why in its tooltip,
  rather than appearing to do nothing.
- **`hot path` tags are not rendered.** The spec lists them on hunk cards,
  but their only source is `review_intents` over MCP; nothing was invented
  for them ahead of that.

### Review — M3 (findings, threads, verdicts)

- **`ReviewAnnotation` keeps its name but is now a finding.** The handoff's
  data model calls it `Finding` while its port map says to extend
  `ReviewAnnotation`; the existing name was kept to match the port map and
  keep the diff small. A human annotation and an agent finding are the same
  record, distinguished only by `author`.
- **Annotations written before scope handles existed are migrated, not
  dropped.** A v1 file names a session and a `DiffScope`, which cannot
  identify a handle minted this process. Those entries are parked under a
  deterministic `legacy:` scope id and adopted the first time the matching
  scope is minted; a finding the live scope already has always wins.
- **`review_reply` refuses an ambiguous finding id.** Ids repeat across
  scopes, so an id alone can name two findings. The tool lists the candidate
  scopes and asks for `scopeId` rather than picking one — guessing is exactly
  the bug the scoped key exists to prevent.
- **`Apply patch` is a hand-off, not an edit.** drydock does not apply the
  unified diff itself; it sends the proposal to the scope's live session, as
  every other worktree action does, and records only that the hand-off
  happened. With no session bound, nothing is recorded.
- **`f` and `⇧F` are different keys.** The spec gives `f` to focus mode and
  `F` to the whole-review filter. Plain `f` collapses/restores every rail;
  Shift+F widens the margin.
- **The intent rail is not built yet.** `[`, `]` and `n` move the intent the
  verdict bar is settling, which is visible and meaningful, but the rail
  itself (with risk heat and collapsed refactor cards) belongs to the
  milestone that adds `review_intents`. `i` is bound with the rail, not
  before it.
- **Per-finding verdicts are still open** (handoff §10.5): a finding can stay
  open under an approved intent unless it is `blocking`. Implemented as
  specified, flagged as undecided.

### Review — M4 (the MCP surface)

- **The human-side calls are not MCP tools.** The schema lists
  `review_message` / `review_patch_apply` / `review_resolve` /
  `review_verdict` / `review_submit` under "human-side writes"; exposing them
  as agent-callable tools would let an agent approve its own work and resolve
  its own findings. They are the UI's own actions against the store, and
  agents observe them read-only through `review_state`. **This is the one
  place the implementation deliberately narrows the schema, and it is worth a
  second look.**
- **`propose*` is recorded, never applied.** `review_answer`'s
  `proposeSeverity` and `proposeResolve` are appended to the thread as
  labelled suggestions. The store changes when the human accepts, so an agent
  cannot downgrade or resolve its own finding.
- **A re-run cannot undo the human.** `review_finding` upserts on the id and
  refreshes the reviewer's opening statement, but the thread, the human's
  severity override and the resolution all survive — they are the human's,
  not the agent's to restate.
- **A batch is all-or-nothing.** Findings are decoded in full before anything
  is stored, so one malformed entry writes nothing rather than half a review.
- **Unknown and forbidden scopes are one answer.** Probing scope handles must
  not tell an agent that a scope exists.
- **`PromptSafety` gained a second, different check.** Inbound finding text
  refuses control characters but allows a leading `!`, `/` or `#`: those
  rules are about what the claude TUI does with a typed line, and a finding
  body is not typed as a line. It allows markup, because the margin renders
  findings as `Label` text and there is no renderer to confuse — the real
  hazard is that "Ask the agent to fix it" later types a finding's own words
  into a terminal.
- **A single oversized hunk is truncated, not dropped.** One generated file
  must not make a scope unreadable.
- **Notifications (schema §7) are not implemented.** `review/thread.message`,
  `review/verdict` and `review/rescope` need a server-initiated channel that
  `McpServer` does not have yet; agents poll `review_state` instead, which
  the schema already positions as the read-path. Flagged rather than faked.
- **`promptHistory` returns empty.** The step timeline is M6; `review_scope`
  treats it as an optional include, so an empty list is a valid answer.
- **The reviewer selector lists available agents.** "Re-run review on this
  scope" grants the scope to its bound session and asks that session's agent
  to review it — the grant is the human action the schema requires before an
  agent may address a scope that is not its own.

### Review — M5 (the PR loop)

- **`gh pr checkout --worktree` does not exist.** The handoff gives that as
  the literal command; `gh` (checked against 2.96) has no such flag, and
  `gh pr checkout` always operates on the working tree it is run in — so
  running it in the repository root would move the reviewer's **main
  checkout** onto the PR branch mid-task. drydock instead does
  `git worktree add --detach <dir>` and then runs
  `gh pr checkout <n> --branch pr-<n>` *inside* that worktree, where `gh`
  resolves the repository from its working directory. The gate prints this
  sequence rather than the handoff's, because the reader may run it
  themselves and the handoff's would fail.
- **A failed checkout cleans up after itself.** A half-made worktree is worse
  than none: the next attempt would collide with the directory left behind.
  Covered by a test that also pins that the main checkout's branch and HEAD
  do not move.
- **Checking out is not in `GhCliService`.** That service's contract is
  read-only observation; checking a PR out changes a working tree, so it
  lives in its own service. `gh pr diff` (the "Read the patch only" path) is
  read-only and does belong there.
- **"Read the patch only" reuses the diff parser.** `gh pr diff` output goes
  through the same `UnifiedDiff` parser as a local `git diff`, so a PR read
  without a worktree renders exactly like one with — with a banner saying the
  agent actions are unavailable, because everything that needs a session is.
- **Submitting hands off to the existing Finish flow.** The review is over,
  and merge / open a PR / delete the worktree is what that flow already does.
- **The `gh pr checkout` step itself is not unit-tested.** It needs a real
  pull request; the pre-flight refusals, the branch naming and the
  failure-cleanup path are covered, and the end-to-end run is the manual
  pass.

### Review — M6 (extras)

- **The symbol lens is a lexical index, and says so.** A resolver would need
  a compiler per language. What the lens promises is "here is every place
  this text appears"; the popover therefore calls them occurrences and
  carries the caveat, rather than implying resolved references it did not
  compute. Keywords and identifiers under three characters are excluded, and
  a symbol appearing once gets no popover — an underline on every word would
  mean nothing.
- **The lens indexes the diff, not the repository.** Its promise is scoped to
  what the reader is looking at. A repository-wide index would need
  `SessionSearchService` and an invalidation story, and would make the
  underline appear on symbols whose other uses are off-screen.
- **The activity log is bounded but its counters are not.** A long review
  makes thousands of calls; keeping them all would be an unbounded leak
  behind a panel nobody has open. The buffer holds the last 500; the budget
  bar counts the whole session.
- **The budget bar is a reference point, not a limit.** Nothing enforces it —
  `review_scope` pages on its own per-call `maxBytes`. It is there so a
  reviewer reading far more than expected is visible at a glance.
- **The agent step timeline is NOT implemented.** `review_scope.promptHistory`
  returns an empty list, and the `t` toggle is not bound. The timeline needs
  three things, and only the first is cheap: reading the session transcript
  (`ConversationCatalog` already can), attributing each step to the lines it
  touched, and computing which of those edits *survive to HEAD*. The last two
  are real work — step→line attribution means reconstructing per-turn diffs,
  and survives-to-HEAD means diffing each against the current tree — and a
  plausible-looking approximation would be worse than nothing here, because
  the whole value of the panel is answering "which of the agent's edits
  actually made it" correctly. Left unbuilt and flagged rather than faked.

### Open decisions carried forward

The five in §10 of the Review handoff are deliberately unresolved and no
answer was invented for any of them: four parallel risk encodings; accent
overload (`#d97757` means selected, primary action *and* agent identity);
density affecting code only; stacked PRs having no dedicated view; and
per-finding verdicts.
