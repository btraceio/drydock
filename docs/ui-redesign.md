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
| Scope handles | `app.drydock.review.ReviewScope` / `ReviewScopeRegistry` | Opaque `rs_…` handles minted per reviewable thing (Review MCP schema §0). Minting is idempotent on identity (kind + repo + worktree + base + head + PR), so a queue rescan never orphans anything keyed by `(scopeId, …)`. Grants let a human hand one session's agent another worktree's scope. |
| Queue assembly | `ReviewQueueService` | `WorktreeService.list` + `GitStatusService` + `gh pr list --search "review-requested:@me"`, grouped MINE / AGENTS / REQUESTED / STACK. A repository whose git fails, or a missing `gh`, contributes nothing and is logged — the rest of the queue still assembles. |
| `gh` review requests | `GhCliService.listReviewRequests` | Read-only, per repository, bounded; a malformed row is skipped rather than emptying the group. |
| Destination view | `app.drydock.ui.review.ReviewDestinationView` | 36px title bar, queue rail, two-row item header (what is being reviewed; its session binding), and a body supplied by its `Host`. |
| Queue rail | `ReviewQueueRail` | 236 / 206 narrow / 44 collapsed, animated the way `SessionExplorerView` animates its search rail. Every row is a real focusable `Button`. |
| Terminal swap | `MainWorkspace.setReviewShowing` | Review is a scene-graph view, so showing it hides every native terminal — one writer (`updateTerminalVisibility`) over three independent conditions (selected tab, modal up, Review showing), and `updateGeometryNow()` on the way back. |
| Sidebar entry | `RepositorySidebar` | Focusable `◨ Review` row above the tree with an item-count badge, plus a `◨n` badge on worktree rows that jumps into Review scoped to that worktree. |

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

### Open decisions carried forward

The five in §10 of the Review handoff are deliberately unresolved and no
answer was invented for any of them: four parallel risk encodings; accent
overload (`#d97757` means selected, primary action *and* agent identity);
density affecting code only; stacked PRs having no dedicated view; and
per-finding verdicts.
