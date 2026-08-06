# Harness marks and session filters

Every session in Drydock runs one of three agentic coding CLIs, and today
nothing in the sidebar says which. A row reads the same whether Claude,
Codex, or Pi is behind it; the only way to find out is to open the session
and read the agent sub-tab. The sidebar also filters on text alone, so "show
me what is still running" or "show me my Codex sessions" has no answer short
of reading every row.

This design adds a per-agent visual mark to session rows and to the session
tab chrome, and a chip row that filters the sidebar by session status and by
harness.

## Scope

In scope:

- A per-agent glyph (and, in the sidebar, a per-agent color) on session rows
  and on the agent sub-tab of an open session. Both render sites were asked
  for explicitly; the sub-tab is the more expensive of the two and is
  justified only by that.
- A status/harness filter over sidebar session rows, driven by toggle chips
  under the existing text filter.

Out of scope:

- Marking unopened-worktree rows. Those are worktrees discovered on disk
  with no session attached, so there is no `AgentKind` to render; inferring
  one from on-disk transcript directories is a different feature.
- Persisting the filter across restarts (see "Persistence").
- Any change to how sessions are ordered or banded. `SidebarChildren`
  classification is untouched; filtering removes rows from the bands it
  produces and never reorders them.
- **A `waiting` / needs-attention facet.** It was drafted and cut: it is
  neither status nor harness, `SessionActivity` is written only by the
  Claude hooks so no Codex or Pi session can ever be `NEEDS_ATTENTION`
  (making `waiting` + either harness chip permanently empty), it is
  redundant with `running` and empty with `idle`, and it drains itself as
  the user works it, because opening a session acknowledges its attention
  state. A worklist over needs-attention sessions is a good feature and a
  different one.

One deliberate behavior change is in scope and called out rather than
smuggled: `SessionStatus.UNSUPPORTED_AGENT` currently classifies as *idle*.
This design reclassifies it as **error** in the one shared mapping. A
session whose agent this build cannot run is broken, and the filter would
otherwise ship a chip named `error` that provably fails to find the one
session that cannot run.

The blast radius is exactly one surface: the **sidebar row dot**, which
becomes red and filled instead of hollow grey. The session-tab dot, header
pill, and pill label text key on the same mapping but never read it for such
a session. Resuming one *does* create and select a placeholder tab —
`showPendingTab` runs before `SessionManager`'s blocked-resume check, which
probes the filesystem on a background executor — but that placeholder seeds
its dots from `STARTING`/`INACTIVE` at construction, and `setStatus` is
reached only from `attachOpenedSession`, which the `UnsupportedAgent` result
branch never reaches: it removes the placeholder instead. Nothing keyed on
`isRunning` moves either: the stop button's disable state, the Resume pill,
the repo aggregate dot, the footer dot, and `SidebarChildren`'s live/idle
banding are untouched.

That placeholder is also why the `unsupportedAgent` flag threaded into
`OpenSessionTab` in section A is live rather than defensive: it is the one
tab that renders the `?  Unknown agent` sub-tab, for the moment it exists.

## A. Agent visual identity

### `app.drydock.ui.AgentMarks`

A new utility owning the glyph/color half of agent identity. Every *name* it
needs comes from the existing `AgentLabels`, which stays the single source of
agent naming (AGENTS.md: shared presentation logic lives in one utility).

It splits deliberately into a pure half and a node factory, because the pure
half must be unit-testable and `Tooltip` requires an initialized FX toolkit:

| Member | Behavior |
| --- | --- |
| `glyph(AgentKind)` | `✳` Claude, `◈` Codex, `π` Pi — pure |
| `unknownGlyph()` | `?` for a session whose agent this build does not recognize — pure |
| `styleClass(AgentKind)` | `agent-mark-claude` / `-codex` / `-pi` — pure |
| `unknownStyleClass()` | `agent-mark-unknown`, which paints `-drydock-text-faint` — pure |
| `markText(ManagedAgentSession)` | The glyph for a session, honoring the unsupported case — pure |
| `createMark(ManagedAgentSession)` | A `Label` carrying `.agent-mark` plus the per-kind (or unknown) class. **No tooltip** (see below) |

Neither takes an `AgentRegistry`: glyphs are per-kind constants and the
unsupported case keys on `session.status()`. Only *names* need the registry,
and names stay in `AgentLabels`.

`π` replaces the `◉` first sketched for Pi: at sidebar font size `◈` and `◉`
are both small centered marks differing mainly in fill, and `π` names its
agent in a channel that survives monochrome rendering and color blindness.

A session with `SessionStatus.UNSUPPORTED_AGENT` renders `?` in
`-drydock-text-faint`, via `.agent-mark-unknown`. Its `agentKind()` is only a
placeholder written by the
state decoder, so rendering the Claude mark there would put the one name
known to be wrong on the session that has no name at all — the same rule
`AgentLabels.displayName(AgentRegistry, ManagedAgentSession)` already
enforces for text.

**No tooltip on the mark.** `buildSessionRow` installs a rich tooltip on the
whole row (status, activity, last opened, working directory); a second
tooltip on the mark would replace it with a poorer one exactly where the
cursor lands on the left edge. Instead, the row tooltip gains an
unconditional `Agent: <name>` line — today it names the agent only as a
prefix on the activity line, and omits it entirely when activity is
`UNKNOWN`.

### Theme tokens

Three new tokens, defined in **both** `theme-dark.css` and `theme-light.css`
(`ThemeTokenContractTest` enforces name parity):

| Token | Dark | Light |
| --- | --- | --- |
| `-drydock-agent-claude` | `#c98168` | `#9e5a3c` |
| `-drydock-agent-codex` | `#5bb3ab` | `#3f8f88` |
| `-drydock-agent-pi` | `#d977b0` | `#b04a86` |

Claude's value is deliberately **not** `-drydock-accent` (`#d97757` in both
sheets), even though Claude's orange is where that accent came from. Accent
is the app's universal emphasis color — selection borders, focus, active
rows — so an accent-orange mark at the left edge of every Claude row would
read as "these rows are special", not "these rows are Claude", and in a
Claude-heavy install that is most of the sidebar.

Pi is magenta rather than the purple first sketched: that purple was
byte-identical to `-drydock-merged`, which paints the `merged` PR chip on
these same session rows, so a Pi session with a merged PR would have shown
one color meaning two things.

These three values are **proposed, and the visual pass owns the final hex.**
The acceptance criteria are what is binding: each mark must be
distinguishable from the other two, and from `-drydock-running`
(`#6cc07a` dark / `#4c9d5e` light), `-drydock-error` (`#e06c6c` dark /
`#c6493f` light) and `-drydock-merged`, at the sidebar's 12.5 px mark size in
**both** sheets — the light ramp is the tighter of the two, and Claude-clay
against light error red is the closest pair in the set. The row must also
still read correctly with color removed, the glyph alone carrying identity.
If a value fails, change the value; the token names and the structure stand.

### Render sites

**Sidebar session row** (`RepositorySidebar.buildSessionRow`): the mark joins
the row's existing leading column rather than being inserted between the dot
and the session name — placing it in `nameRow` would both steal width from
the name in the app's narrowest column and put two independently colored
marks adrift 8 px apart.

That column is today a `StackPane`, which *stacks* its children, so it
becomes an `HBox` of dot-then-mark. Its width comes from CSS:
`.child-row-status { -fx-min-width: 16; -fx-alignment: center }`. That class
is shared by four builders — session, unopened-worktree, stale-bucket and
locked-bucket rows — and `app.css` states the invariant in a comment: one
fixed indent gutter so every child row's status column lines up. So the
**shared** gutter widens to fit two glyphs, and the other three row types
keep their single dot or caret centered in it. Giving the session row a
wider private class would break the stated alignment and, since both
selectors have equal specificity, would silently depend on declaration order
in `app.css`.

Widening the gutter has one non-CSS consequence: the path rows inside an
expanded stale or locked bucket are indented by a hard-coded
`new Insets(2, 8, 2, 34)` in Java, hand-tuned against the current gutter
width. Those two literals move with it, or the bucket's summary row and its
own path rows stop lining up.

The name `Label` keeps its `setMinWidth(0)` + ellipsis clamp (min-width leaks
in this row have held the whole window open before).

**Session tab chrome:** `OpenSessionTab` today holds only a resolved
`String agentName` and calls the `AgentLabels.subTabLabel(String)` /
`subTabTooltip(String)` overloads, so there is no `AgentKind` at the render
site and `AGENT_GLYPH` cannot be made per-agent where it currently lives.
This design therefore:

1. Adds an `AgentKind` and an `unsupportedAgent` flag to `OpenSessionTab`'s
   single constructor, threaded from `MainWorkspace`'s four `showPendingTab`
   call sites through `createOpenSessionTab`. Every one of those sites
   already holds a `ManagedAgentSession` (prepared or existing), so the kind
   is **non-null at all four** — there is no placeholder-without-an-agent
   case, and no nullable parameter. `agentName` is final and the sub-tab is
   built once, so no rename or rebind path can leave the kind stale.
2. Replaces `AgentLabels.subTabLabel(String)` with
   `subTabLabel(String mark, String agentName)`, where the mark comes from
   `AgentMarks.glyph(kind)` — or `AgentMarks.unknownGlyph()` when
   `unsupportedAgent`. The name argument stays what it is today: the value
   the caller resolved through
   `AgentLabels.displayName(AgentRegistry, ManagedAgentSession)`, which is
   the overload that correctly yields "Unknown agent".
   `subTabTooltip(String)` is unchanged.
3. **Deletes** the kind-only `subTabLabel(AgentRegistry, AgentKind)` /
   `subTabTooltip(AgentRegistry, AgentKind)` overloads. They have no
   production callers, and adopting them here would have been a regression:
   they route through `displayName(registry, kind)`, which resolves the
   `UNSUPPORTED_AGENT` session's placeholder kind through its registered
   provider and returns "Claude" — precisely the wrong name this section
   forbids. Their javadoc
   is referenced by `{@link}` from the surviving String overloads and must
   be rewritten in the same edit.

The sub-tab gets the per-agent **glyph only, no color class.**
`.session-subtab:selected:keys` already repaints the sub-tab in accent to
mean "this terminal owns the keyboard" — the one piece of state that control
communicates. A per-agent text fill would put two meanings on one channel,
and the sub-tab already spells the agent's name in words. Color stays a
sidebar-only affordance, where rows have no name to spare.

Note that the sub-tab and the sidebar resolve these glyphs through
*different* font stacks: `.session-subtab` opts into JetBrains Mono, while
the sidebar runs in the System face. The visual pass checks both sites
separately (see Risks).

**Unopened worktree rows:** unmarked, per Scope.

## B. Filter model

### Status facets live in the domain, not in the UI

`SessionStatusStyles` is a package-private class in `app.drydock.ui` that
imports JavaFX animation types, so nothing outside that package can call its
`isRunning`/`isError`, and a filter that duplicated the mapping would let the
chip a user clicks and the dot they see disagree.

The mapping therefore moves down: a new FX-free **public**
`app.drydock.domain.SessionStatusFacet` enum with
`public static SessionStatusFacet of(SessionStatus)` — it is called from
`app.drydock.ui` and `app.drydock.ui.model`, which is the whole point:

| Facet | Statuses |
| --- | --- |
| `RUNNING` | `RUNNING`, `STARTING` |
| `ERROR` | `FAILED`, `MISSING_WORKING_DIRECTORY`, `UNSUPPORTED_AGENT` |
| `IDLE` | `INACTIVE`, `EXITED` |

`SessionStatusStyles.isRunning`/`isError` are rewritten as one-line
delegations. So is `SidebarChildren`'s **private second copy** of the running
mapping, which today drives live/idle banding from its own duplicate of the
same two-status test — after this change there must be exactly one mapping,
not two, or the band a session sits in and the chip that finds it can drift
apart. `SessionStatusStyles.updateDot` holds a third inline copy of the same
running test to decide whether to pulse; it is behaviorally identical and
unaffected by the reclassification, and folding it into the delegation is
tidy-up, not a requirement. `UNSUPPORTED_AGENT` moving from idle to error is the deliberate
change declared in Scope.

### `app.drydock.ui.model.SessionFilter`

A record beside `WorkspaceViewModel`, holding no FX types:

```java
public record SessionFilter(Set<SessionStatusFacet> statuses, Set<AgentKind> agents) {
    public static SessionFilter none();
    public boolean matches(ManagedAgentSession session);
    public boolean isActive();
}
```

The type and all three members are `public`: both consumers —
`RepositorySidebar` and `SessionFilterBar` — live in `app.drydock.ui`, one
package up. Shipping it package-private would reproduce exactly the defect
this section opens by diagnosing in `SessionStatusStyles`.

The compact constructor takes defensive `Set.copyOf` of both sets — the
record is passed around and must not alias a mutable set owned by the chip
bar. `matches` needs nothing but the session: with the `waiting` facet cut,
there is no view-model state to thread in.

Semantics:

- **An empty set is no constraint, not "match nothing".** An untouched
  filter shows every session.
- Within an axis, facets OR: `{RUNNING, ERROR}` matches either.
- Across axes, they AND: running **and** Codex.
- Selecting *every* chip on an axis is treated as no constraint on that
  axis. Without this rule, "select all three agents" — the natural way to
  say "any agent" — would be the one selection that hides an
  `UNSUPPORTED_AGENT` session. The rule has one visible oddity, accepted
  here: selecting the three agent chips one at a time makes an unsupported
  session absent, absent, then suddenly present. The alternative (an
  unsupported session matching every agent chip) asserts three things known
  to be false instead of one discontinuity.
- `isActive()` is true when any axis constrains anything.

Agent matching uses `session.agentKind()`, with one exception: an
`UNSUPPORTED_AGENT` session matches **no** agent chip, because its
`agentKind()` is a placeholder and matching it against Claude would assert
the one thing known to be false. Such a session stays reachable through the
`error` chip and through an unconstrained agent axis.

### One definition of "filtering"

Four surfaces need to know whether the sidebar is filtered, and they must
not disagree. The sidebar defines a single predicate:

```
filtering() = filter.isActive() || !query.isBlank()
```

**Every** filter-aware surface uses it: the empty state, the footer suffix,
the repo row's session badge and aggregate dot, and the childless-repo rule.
The narrower `filter.isActive()` governs only the two behaviors the chips
have and the text query does not — dropping *non-session rows*, and
suppressing the repo header's worktree/locked/stale counts, which is the
same behavior seen from the header. (A third `isActive()` gate appears in
"Row membership" below, and is correct for a different reason: a text query
matches name, branch and path but never status, so a status change cannot
alter text-query membership.) Anything else keying on `isActive()` would
produce a sidebar where a text query hides rows while the repo header still
counts them.

### Composition in the sidebar

The facet step is extracted as a package-private pure function so it can be
tested without an FX toolkit or a live sidebar:

```java
static List<SidebarNode> applyFacets(List<SidebarNode> children,
                                     SessionFilter filter,
                                     Predicate<ManagedSessionId> exempt)
```

Only the facets are extracted. The text query stays where it is: its
`matchesRepo`/`matchesNode` read `viewModel.repoStatus`/`worktreeStatus` to
match branch text, so a static, view-model-free function could not reproduce
it without inventing resolver parameters that exist only to satisfy the
extraction.

`rebuildTree()` composes them in this order:

1. **Facets first, session-scoped.** When `filter.isActive()`, every
   non-session child is dropped — unopened-worktree rows, the locked bucket,
   the stale bucket — and session rows survive only if `filter.matches(...)`
   or `exempt` accepts them.
2. **Then the text query, unchanged.** It keeps matching repositories by
   name/branch and rows by session name, branch, and worktree path, and a
   repo matched by name still shows all of its (facet-surviving) children.
3. A repository left with no children is removed, including one that matched
   by name. This rule lives in `rebuildTree` (which alone can drop a repo)
   and fires **only when `filtering()`** — today a childless repo is dropped
   only on a failed query match, and a freshly added repository with no
   worktrees and no sessions must keep showing itself.

Two consequences are accepted rather than discovered later:

- Typing an exact worktree path and then clicking any chip hides the row you
  searched for, because a facet filter is a filter over *sessions* and an
  unopened worktree has none.
- A repo with no matching session disappears along with its `+` new-session
  menu and `⟳` rescan. Recovery is to clear the filter, which the empty
  state's button and the filter's transience both make cheap. This was
  chosen over dimming or keeping empty repo rows.

The `⟳` rescan is **not** disabled while filtering. A rescan on a still-
visible repo keeps its two visible effects — the button's spin and the
"Already up to date" note, both of which render in the repo header — so the
click is never silent, even when the worktree rows it would add are filtered
out.

### The active session is exempt, and says so

The session whose tab is frontmost is always rendered, together with its
repository row, regardless of the facets. Otherwise clicking `running` while
inside an idle session leaves that session open, frontmost, and absent from
the sidebar, with `syncActiveSelection` clearing the selection out from under
it.

An exempt row is a row the filter says should not be there, so it must not
be silent about it:

- Its tooltip gains a line: `Shown because it is open — it does not match
  the current filter.`
- It is **not** counted in the repo header's `N of M` (below), and a
  repository present only by exemption shows no count rather than `0 of M`.
- The empty state is keyed to the **match set**, not to the tree: if nothing
  matched, "Nothing matches your filters" appears even though the exempt row
  is on screen — as a banner above the surviving row rather than as the
  swap that replaces the tree (see "Empty state"). Otherwise a user who
  filters to `error` with no errors, while sitting in an idle session, sees
  one idle row under an `error` chip and no explanation at all.

Two navigation paths must respect the same boundary, or the exemption papers
over an inconsistency instead of fixing one:

- `activeSessionChanged` calls `requestRebuild()` whenever `filtering()` and
  either the outgoing or incoming session fails `filter.matches` — the
  exemption predicate is re-read on every rebuild, and nothing else in that
  handler rebuilds the tree.
- `focusAdjacentLiveSession` (the live-session cycle key) walks only the
  sessions `filter.matches` admits — the facets, not the text query, which
  it ignores today and continues to ignore. A filter narrows *which sessions
  I am working with*; cycling into a hidden one and having it pop into the
  tree by exemption is the same surprise the exemption exists to prevent.
  Its wrap-around is unchanged; it wraps over the narrower list, and under
  an `idle`- or `error`-only filter that list is empty, so the cycle key
  becomes the no-op it already is when nothing is live.

### Collapsed repositories

A collapsed repository silently swallowing the only match — with no empty
state, because the tree is not empty — is the worst failure this feature can
produce. Repo expansion is a local reading preference; a filter is a global
question ("where are my errors?").

So: on **every change to the filter** — any chip toggle and every debounced
query change, not merely the transition into a filtered state — the sidebar
snapshots the user's `collapsed` set (if it has not already), clears it, and
rebuilds. Firing only on entry would fail the case that motivates the rule:
with `running` on and a repo collapsed, switching to `error` would leave the
sole matching error session inside a collapsed repo.

Between filter changes the disclosure triangle stays live, so it is never a
dead control while the user reads results. Collapses made mid-filter are
discarded when the filter clears: the snapshot taken before the filter is
what gets restored. That is a deliberate choice of one behavior over the
other — the two cannot both hold, since both write to the same set.

### Row membership must be re-evaluated on status changes

`WorkspaceViewModel` emits `sessionRowChanged` for a field-level change and
escalates to `structureChanged` only on add/remove/move; the sidebar handles
the former with `updateSessionRow`, which mutates the `TreeItem` value in
place and never re-checks membership. Left alone, that means a session that
exits while the `running` chip is active stays in the list and re-renders as
an idle row, and a session that starts running never appears.

While `filter.isActive()`, `sessionRowChanged` compares
`filter.matches(session) || exempt(session)` against whether that row is
currently in the tree and calls `requestRebuild()` on a mismatch, falling
through to `updateSessionRow` otherwise. `requestRebuild`'s existing
coalescing keeps a burst of status changes to one rebuild (AGENTS.md:
rebuild-the-world is a last resort).

The `exempt` term is not optional. An exempt row fails `matches` by
definition while sitting in the tree, so testing `matches` alone makes the
frontmost session a permanent mismatch — and the frontmost session is the
one emitting the most row events, so every one of them would force a full
rebuild that cannot resolve the mismatch it is reacting to.

### Repo row aggregates follow the filter

The repo row's session count badge and its aggregate running dot are computed
from the unfiltered session list, and `repoCountsText` prints
`· 3 wt · 2 locked · 1 stale` for rows the filter may have deleted. While
`filtering()`, the badge reads `N of M` (excluding any exempt session), the
aggregate dot reflects the surviving sessions, and — when `isActive()`, i.e.
when those rows are actually gone — the worktree/locked/stale counts are
suppressed rather than left pointing at nothing.

`N` is read from the repo item's **already-composed children**, which the
cell can reach through `getTreeItem()`, and never recomputed from
`filter.matches`. A recount would disagree with the screen in both
directions, because the text half of `filtering()` also drops children, and
a repo matched by name keeps children that the query itself does not match.

## C. The chip row

### `app.drydock.ui.SessionFilterBar`

A new node inserted into the sidebar's existing header `VBox` — which today
is `new VBox(addButton, filterField)` — as its third child, directly under
the filter field.

- A `FlowPane` of `ToggleButton`s so the row wraps as the sidebar narrows
  rather than forcing a minimum width. **The chips share no `ToggleGroup`**;
  a `ToggleGroup` would give radio behavior and silently break the "facets
  OR within an axis" rule.
- **Status chips:** `running`, `idle`, `error`.
- **Harness chips:** one per `AgentKind.values()` in `preferenceOrder()` —
  always three. Gating on the `AgentRegistry` was considered and dropped:
  the `META-INF/services` file registers all three providers in every build,
  so the guard would never fire, and gating on *availability* instead would
  hide the chip for an agent whose CLI has since been uninstalled while its
  sessions are still in the tree.
- Chip content is the agent glyph plus its short name; the mark's per-agent
  color applies to the **glyph only**, never to the chip's text, border, or
  background. The chips reuse the existing `.review-filter-button` shape so
  the sidebar introduces no fourth chip visual language — with selection
  carried by the **background fill and text color**, which is what that
  class already does. What must not happen is selection being reduced to the
  border: `:focused` paints the same accent border as `:selected`, so a
  Tab-focused unselected chip would then be indistinguishable from a
  selected one. No chip gets a per-facet dim treatment either:
  `-drydock-text-faint` is already the *unselected* text fill of this class,
  so a deliberately dim `idle` chip would read as permanently off.
- The bar is constructed with the `AgentRegistry` the sidebar already holds,
  so its harness chips take their names from `AgentLabels` like every other
  agent name in the app. It owns its chip state and exposes
  `SessionFilter filter()` plus an `onChanged` callback. The sidebar keeps
  `private SessionFilter filter = SessionFilter.none()`, assigns it in the
  callback, and calls `requestRebuild()` — not `rebuildTree()` — so a
  multi-chip change coalesces into one rebuild. `rebuildTree()`'s five
  existing invocations (filter debounce, constructor, `requestRebuild`,
  `onRepositoriesChanged`, `refreshReviewBadges`) read the field and need no
  knowledge of the bar. There is no debounce: the 150 ms `FILTER_DEBOUNCE`
  exists for keystrokes, and a click is one discrete event.
- `clear()` resets every chip **under a suppression flag** and fires
  `onChanged` exactly once, rather than firing one rebuild per chip.
- Chips are real `ToggleButton`s and stay focus-traversable (AGENTS.md's
  keyboard rule) — unlike the sidebar's hover-only row actions, which set
  `setFocusTraversable(false)` because they are redundant with the context
  menu. Tab order becomes: filter field → status chips → harness chips →
  tree. `⌘F` and Escape behavior in the filter field are unchanged.
- A diagnostic hook `diagToggleFacet(String)` sits beside the existing
  `diagFilter(String)`, **and a matching `facet` verb is added to
  `DrydockApplication.diagTabStep`'s hard-coded verb switch** — the sidebar
  method alone does nothing, since that switch is what the `tabScript`
  property drives. This is what makes the visual pass scriptable rather than
  hand-driven.

### Empty state

Shown when `filtering()` and the **match set** is empty — not when the tree
is empty, so the active-session exemption cannot suppress it. Keying it to
the chips alone would have been worse still: a text-emptied tree (today's
most common way to empty it) would get no message, and a user with both a
typo and a chip active would click *Clear filters*, see the tree stay empty,
and conclude the button is broken.

Its message is **"Nothing matches your filters"** — not "No sessions match",
which is false when the user typed a repo name — with a **Clear filters**
button that clears the chips *and* the text field in one `onChanged`. It
renders in one of two forms, because the match set being empty does not
always mean the tree is:

- **Nothing on screen at all** (the common case): the sidebar swaps the
  `TreeView` out of its children for the message `VBox`, which carries the
  `Priority.ALWAYS` vgrow. A swap rather than an overlay because `TreeView`
  has no `placeholder` property (unlike `ListView`) and a tree left behind an
  overlay stays focus-traversable; a `VBox` sibling rather than a
  `StackPane` child because a `StackPane` not stretching its `VBox` child is
  a documented trap in this codebase.
- **Only the exempt row survives:** the tree stays where it is and the same
  message renders as a compact banner directly above it, without the vgrow.
  Swapping here would delete the exempt row from the screen and re-create the
  exact failure the exemption exists to prevent — a frontmost session absent
  from the sidebar, with `syncActiveSelection` running against a detached
  tree.

Which form applies is decided by the composed tree, not by the filter: swap
only when the tree would have no repository items at all.

The banner form moves no focus at all — nothing leaves the scene. The swap
form can fire while the user's hands are elsewhere — mid-keystroke in
the filter field (the debounce is what empties the tree), or in a terminal
tab when a background session starts and repopulates the match set. So focus
moves **only when the focus owner is inside the node being removed**:

- Swapping the tree out while the tree held focus moves focus to the
  **Clear filters** button — the only remaining action.
- Swapping the empty state out while that button held focus returns focus to
  the filter field.
- In every other case focus is left exactly where it is. An unconditional
  rule would yank the caret out of the filter field on the 150 ms debounce —
  swallowing the next keystroke and turning Space into *Clear filters* — and
  this codebase has already shipped keystroke-routing regressions at this
  exact field.

The state is suppressed entirely when there are no repositories at all: a
user with an empty workspace typing into the filter field is not looking at
a filter problem, and *Clear filters* cannot help them.

### Footer

`footerLabel` keeps reporting unfiltered totals across all repositories, and
gains a `· filtered` suffix whenever `filtering()` — the same condition as
the empty state, so the footer and the message never disagree. The suffix is
recomputed from the live filter on every `updateFooter()` call, including the
ones driven by `repoChanged`, and is appended after the conditional
`· N unopened` segment. The footer's job is "what exists"; silently shrinking
it would erase the only remaining evidence of what the filter is hiding.

### Prompt text

The filter field reads `⌕  Filter repos & worktrees…`, which stops being
true once chips hide worktree rows. It becomes `⌕  Filter repos & sessions…`.

### Persistence

The filter is transient — it resets on restart, and it survives a sidebar
collapse/expand (`⌘0`) unchanged. A remembered filter that hides a user's
sessions on launch is indistinguishable from data loss, and the chip row is
cheap enough to re-apply that persistence buys little.

## Testing

Pure unit tests, no FX toolkit:

- `SessionStatusFacetTest` — every `SessionStatus` maps to exactly one
  facet, with `UNSUPPORTED_AGENT` pinned to `ERROR` so the reclassification
  cannot be undone by accident.
- `SessionFilterTest` — empty-set-is-no-constraint; OR within an axis; AND
  across axes; full-set-selection is no constraint; `UNSUPPORTED_AGENT`
  matches no agent chip but is found by `error`; defensive copying of both
  sets.
- `AgentMarksTest` — `glyph`, `styleClass`, `markText` per kind, and the
  unknown-agent fallback. The `createMark` node factory is not unit-tested
  (it builds FX nodes); it is covered by the visual pass.
- `RepositorySidebarFacetTest` against the extracted static `applyFacets` —
  facets drop unopened/locked/stale rows; a repo whose sessions all fail the
  filter yields no children; the exemption predicate keeps its row; an
  inactive filter is the identity function. This is a pure list-in/list-out
  test: `RepositorySidebar` cannot be constructed in a test today (its
  constructor takes seven collaborators, including `GitStatusService` and
  `WorktreeService`, which spawn real `git`; it calls `refreshAllStatuses()`
  and starts a 30 s `INDEFINITE` timeline with no stop path), and building
  that fixture is not in this change's scope.
- `AgentLabelsTest` — updated for the new glyph set and the
  `subTabLabel(String mark, String agentName)` signature. Two of its
  existing cases cannot simply move: the title-case fallback case tests
  `displayName(AgentRegistry, AgentKind)` *through* the deleted kind-only
  overload and must be retargeted directly at `displayName`, and the only
  `subTabTooltip` case must be retargeted at the surviving `String`
  overload rather than deleted.

Theme and CSS:

- `ThemeTokenContractTest` covers token-name parity across the two sheets,
  that `app.css` references no undefined token, and that every `app.css`
  font size is a bare `px` literal so the interface-size slider scales it —
  the new `.agent-mark` and chip rules must obey that third rule or the
  suite fails. What the test does **not** prove is that anything is styled:
  three defined-but-unreferenced tokens would pass it. The actual styles —
  `.agent-mark`, `.agent-mark-claude|codex|pi`, `.agent-mark-unknown`, the
  widened `.child-row-status`, the chip rules, and the empty-state block —
  go in `app.css`, and their correctness is established by the visual pass.

Visual verification in the running app (not optional, and not a substitute
for the above), driven by the diagnostic run properties, `tabScript`'s
`filter:`/`facet:` verbs, and the `shot:` scene snapshot, in both themes:

- The sidebar with all three harnesses present, plus an `UNSUPPORTED_AGENT`
  session, confirming the marks are distinguishable at 12.5 px.
- The same capture in grayscale — the glyphs alone must still identify the
  agent.
- The agent sub-tab of an open session, checked **separately** from the
  sidebar because it resolves the same glyphs through a different font
  stack, and including the `:selected:keys` state to confirm the glyph change
  did not disturb that state's color.
- Each single facet, one multi-facet combination, and text + facet together.
- The gutter alignment across a repo showing a session row, an unopened
  worktree row, and a stale bucket **expanded**, since the shared column
  widened and the bucket's path rows carry their own hard-coded indent.
- Both empty-state forms: the full swap with its Clear filters button
  (including the recovery path from text-only emptiness), and the banner
  form — filter to `error` with no errors while sitting in an idle session,
  which must show the ghost row *and* the message together.

## Risks

- **Glyph rendering — the largest unresolved risk, and unresolvable
  statically.** The sidebar renders in the System font; the only bundled
  faces are JetBrains Mono, and `.session-name` does not opt into them,
  while `.session-subtab` does — so the same three glyphs resolve through
  two different fallback chains in one window. `π` is a Greek letterform
  with its own metrics and baseline, and `✳` is emoji-presentation-capable:
  a color-emoji fallback would render in fixed colors, defeating both the
  per-agent color and the grayscale criterion. The visual pass must confirm
  each glyph's face at both sites and that none renders as color emoji; the
  first mitigation is appending the text presentation selector U+FE0E, the
  second is swapping the glyph. An image asset is not a fallback — the app's
  whole visual vocabulary is glyph-based.
- **Row width.** The mark adds a second glyph to the leading column of the
  app's narrowest row, and that column is the shared child-row gutter, so
  every child row indents slightly further. The name `Label` must keep its
  `setMinWidth(0)` clamp, or the row takes its minimum width from the title
  and holds the window open — a failure this codebase has shipped before.
- **The `UNSUPPORTED_AGENT` reclassification** turns existing sessions' dots
  red on first launch after this change. That is the intent, but it is a
  visible change to sessions the user did not touch.
