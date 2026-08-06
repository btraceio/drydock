# Harness marks and session filters

Every session in Drydock runs one of three agentic CLIs, and today nothing in
the sidebar says which. A row reads the same whether Claude, Codex, or Pi is
behind it; the only way to find out is to open the session and read the agent
sub-tab. The sidebar also filters on text alone, so "show me what is still
running" or "show me my Codex sessions" has no answer short of reading every
row.

This design adds a per-agent visual mark to session rows and the session tab
chrome, and a chip row that filters the sidebar by session status and by
harness.

## Scope

In scope:

- A per-agent glyph + color, rendered on sidebar session rows and on the
  agent sub-tab of an open session.
- A status/harness filter over sidebar session rows, driven by toggle chips
  under the existing text filter.

Out of scope:

- Marking unopened-worktree rows. Those rows are worktrees discovered on
  disk with no session attached, so there is no `AgentKind` to render;
  inferring one from on-disk transcript directories is a different feature.
- Persisting the filter across restarts (see "Persistence" below).
- Changing the meaning of any `SessionStatus` value.

## A. Agent visual identity

### `app.drydock.ui.AgentMarks`

A new utility owning the glyph/color half of agent identity. Every *name* it
needs comes from the existing `AgentLabels`, which stays the single source of
agent naming (AGENTS.md: shared presentation logic lives in one utility, no
per-view copies).

| Member | Behavior |
| --- | --- |
| `glyph(AgentKind)` | `✳` Claude, `◈` Codex, `◉` Pi |
| `styleClass(AgentKind)` | `agent-mark-claude` / `-codex` / `-pi` |
| `createMark(AgentRegistry, ManagedAgentSession)` | A `Label` with `.agent-mark` plus the per-kind class, tooltip = `AgentLabels.displayName(registry, session)` |
| `createMark(AgentRegistry, AgentKind)` | Same, for call sites that have a kind rather than a session (the sub-tab) |

A session with `SessionStatus.UNSUPPORTED_AGENT` renders a neutral `◇` in
dim text with the "Unknown agent" tooltip. Its `agentKind()` is only a
placeholder written by the state decoder, so rendering the Claude mark there
would put the one name known to be wrong on the session that has no name at
all — the same rule `AgentLabels.displayName(AgentRegistry,
ManagedAgentSession)` already enforces for text.

### Theme tokens

Three new tokens, defined in **both** `theme-dark.css` and
`theme-light.css` (`ThemeTokenContractTest` enforces parity):

| Token | Dark | Light | Note |
| --- | --- | --- | --- |
| `-drydock-agent-claude` | `#d97757` | `#d97757` | The existing app accent; Claude's orange is already Drydock's accent color |
| `-drydock-agent-codex` | `#5bb3ab` | `#3f8f88` | New teal; the only hue this design adds |
| `-drydock-agent-pi` | `#b07ad9` | `#8a4fb5` | The existing merged-purple values, under a new semantic name |

Reusing values under new semantic names follows the precedent set by the
Review tokens: the meanings genuinely differ, so the names must, even where
the hex matches.

### Render sites

**Sidebar session row** (`RepositorySidebar.buildSessionRow`): the mark goes
immediately before the session name in `nameRow`. The status dot keeps its
own leading `statusCol` column. Status and harness are independent axes;
folding them into a single glyph would make neither readable, and the status
dot's running-pulse animation is tied to status alone.

**Session tab chrome** (`AgentLabels.subTabLabel`, consumed by
`OpenSessionTab`): the generic `AGENT_GLYPH` constant — currently the same
`✳` for all three agents — is replaced by the per-agent glyph, and the sub-tab
button gains the matching color style class. The sub-tab that reads "Codex"
then wears the Codex mark.

**Unopened worktree rows**: unmarked, per Scope.

## B. Filter model

### `app.drydock.ui.model.SessionFilter`

A record beside `WorkspaceViewModel`, holding no FX types so it is testable
without the toolkit:

```java
record SessionFilter(Set<StatusFacet> statuses, Set<AgentKind> agents) {
    enum StatusFacet { RUNNING, IDLE, ERROR }
    boolean matches(ManagedAgentSession session);
    boolean isActive();
}
```

Semantics:

- **An empty set is no constraint, not "match nothing".** An untouched
  filter shows every session.
- Within a group, facets OR: `{RUNNING, ERROR}` matches either.
- Across groups, they AND: running **and** Codex.
- `isActive()` is true when either set is non-empty.

Status classification delegates to `SessionStatusStyles.isRunning` and
`isError` rather than re-deriving the mapping, so the chip a user clicks and
the dot they see can never disagree. `IDLE` is defined as neither. This
inherits one existing quirk: `UNSUPPORTED_AGENT` classifies as *idle*, not
error. That mapping is kept as-is — changing status semantics is a separate
change from adding a filter.

Agent matching uses `session.agentKind()`, with one exception:
an `UNSUPPORTED_AGENT` session matches **no** agent chip. Its `agentKind()`
is a placeholder, so matching it against Claude would assert the one thing
known to be false.

### Composition in `RepositorySidebar.rebuildTree()`

The existing text query is untouched: it keeps matching repositories by
name/branch and rows by session name, branch, and worktree path, and a repo
matched only through its children still narrows to the matching rows.

The facet filter layers on top and is session-scoped. When
`filter.isActive()`:

1. Every non-session child is dropped — unopened-worktree rows, the locked
   bucket, and the stale bucket.
2. Session rows survive only if `filter.matches(session)`.
3. A repository left with no children is removed from the tree, **including
   a repository that matched by name**. Under an active session filter, "no
   sessions here match" is the honest answer, and an empty repo row would
   read as noise.

## C. The chip row

### `app.drydock.ui.SessionFilterBar`

A new node placed in the sidebar header `VBox`, directly under the existing
filter field.

- A `FlowPane` of `ToggleButton`s, so the row wraps as the sidebar narrows
  rather than forcing a minimum width — the sidebar is the app's narrowest
  column and has a documented history of min-width leaks holding the window
  open.
- **Status chips** first: `running`, `idle`, `error`, each carrying its
  status color (`-drydock-running`, dim, `-drydock-error`).
- **Harness chips** next: one per provider actually registered in
  `AgentRegistry`, showing that agent's glyph and color with its display
  name as tooltip. A provider this build did not discover gets no chip, so
  no chip can filter to a guaranteed-empty result.
- Exposes the current `SessionFilter` and an `onChanged` callback. A toggle
  rebuilds the tree immediately, with **no debounce** — the 150 ms
  `FILTER_DEBOUNCE` exists for keystrokes; a click is one discrete event.
- Chips are real `ToggleButton`s, hence focusable and Space-togglable for
  free (AGENTS.md keyboard rule). No new keyboard shortcut is introduced, so
  `ShortcutsOverlay` needs no change.

### Empty state

When the filter is active and the resulting tree is empty, the sidebar shows
"No sessions match" with a **Clear filters** button that resets every chip.
This is the one guaranteed path out of a filter that hid everything.

### Footer

`footerLabel` keeps reporting unfiltered totals across all repositories, and
gains a `· filtered` suffix while `isActive()`. The footer's job is "what
exists"; silently shrinking it would erase the only remaining evidence of
what the filter is hiding.

### Persistence

The filter is transient — it resets on restart. A remembered filter that
hides a user's sessions on launch is indistinguishable from data loss, and
the chip row is cheap enough to re-apply that persistence buys little.

## Testing

Unit (no FX toolkit):

- `SessionFilterTest` — empty-set-is-no-constraint; OR within a group; AND
  across groups; `UNSUPPORTED_AGENT` classifies as idle on the status axis
  and matches no chip on the agent axis; `isActive()`.
- `AgentMarksTest` — glyph and style class per `AgentKind`; the unknown-agent
  fallback for an `UNSUPPORTED_AGENT` session; tooltip text delegates to
  `AgentLabels`.

Headless UI, in the style of the existing `RepositorySidebarChipTest`:

- An active facet filter drops unopened-worktree rows and the locked/stale
  buckets.
- A repository with no matching session disappears, even when its name
  matches the text query.
- Clearing every chip restores the unfiltered tree.

Theme:

- `ThemeTokenContractTest` picks up the three new tokens automatically; both
  theme files must define them.

Visual verification in the running app (not a substitute for the above, and
not optional): using the diagnostic run properties and the in-app `shot:`
scene snapshot, capture the sidebar with all three harnesses present, each
filter combination including a multi-facet one, the empty state with its
Clear filters button, and the agent sub-tab — in both light and dark themes.

## Risks

- **Glyph rendering.** `✳ ◈ ◉ ◇` must render in the sidebar's UI font on
  macOS without falling back to a mismatched face. The visual pass is what
  confirms this; if any glyph substitutes badly, swap it for another from
  the same family rather than reaching for an image asset — the app's whole
  visual vocabulary is glyph-based.
- **Row width.** The mark adds one cell before an already-tight name row.
  The name `Label` is clamped (`setMinWidth(0)`, ellipsis overrun) precisely
  because of past min-width leaks; the mark must be `USE_PREF_SIZE` and must
  not become a second thing competing for the row's width.
