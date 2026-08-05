# A session names itself after the work it turns out to be doing

A drydock session's tab is labelled with its `displayName`, derived from
the branch at creation time and never changed unless a human retypes it.
Branch names are chosen before the work is understood, so within an hour
the tab rail reads `feat/tab_title_mcp`, `fix/nre-again`, `spike-3` — a
row of labels saying where the work lives and nothing about what it is.

The agent inside each of those tabs knows what the work is. This design
gives it a tool to say so.

> **Revision note.** Adversarially reviewed five times before
> implementation, three reviewers per round (mechanism, abuse, spec
> quality), then corrected once more during implementation. "What the
> adversarial review changed" at the end records every finding and its
> resolution, including six claims that were false and are corrected in
> place — one of which survived every review round and was caught only
> by a test that refused to fail.

## What it writes

`session_rename` writes the session's real `displayName`: the same field
the human's inline rename writes, that the sidebar tree, `sessions_list`
and every confirm dialog read. There is no second "agent subject" field
layered over it. One session, one name, everywhere.

That choice is what makes the validation section long, because
`displayName` is not a label-only field:

- `RepositorySidebar.java:1241` builds the delete confirmation as
  `"Delete session \"" + displayName + "\"?"`, and `MainWorkspace` builds
  four more the same way (`:2081`, `:2098`, `:2159`, `:2170`) —
  including the one whose buttons are **Start new conversation** /
  **Delete session**, and one that is a native `DirectoryChooser` title.
- `RepositorySidebar.java:1491` renders each sidebar row's name as an
  unconstrained `Label`. (`:893` sorts by `displayName`, but that sort
  never reaches the tree — see the correction under "Impersonation".)
- `McpToolRouter.java:634` returns every session's `display_name` from
  `sessions_list` to any live agent.
- `McpServer.summarize` (`:328`) puts the raw call arguments into the
  activity panel, which renders them as a `Label`
  (`ReviewMcpActivityPanel.java:154`) — **including for calls that
  failed validation**.

After this change all of those consume text authored by an agent that
reads untrusted diffs and can be prompt-injected.

One sink it does **not** reach, contrary to an earlier draft:
`ClaudeAgentProvider.java:103` appends `-n <shellQuote(displayName)>` to a
command string, but `TerminalSpec`'s contract is that libghostty execs it
via `/bin/bash -c "exec -l <command>"` — an argv element, not keystrokes.
`AgentCommands.shellQuote` is correct POSIX single-quoting, so `'` is
safe there and a newline would be an ordinary literal. The validation
below is justified by the dialogs and the rail, not by that path.

## The tool

```
session_rename(title: string)
```

One required argument. No session selector: the tool always renames **the
caller's own** session, resolved from the MCP token like every other tool
in `McpToolRouter`. A session started via `session_start` renames itself,
never its parent. A `Spawn.FORBIDDEN` child may call it; the spawn grant
governs creating sessions, not naming one. Remoteness is not an axis —
unlike `session_start`, which filters remote repositories out, a remote
session's tab is renamed exactly like a local one.

### Validation

`PromptSafety` gains `checkSessionTitle(String)`. Not `checkInboundText`:
that method is written for finding bodies and deliberately **permits**
`\n`, `\r` and `\t` (`PromptSafety.java:66-69`) with an 8000-character
cap. The class javadoc currently scopes `PromptSafety` to text going
*into* a session; that scope statement widens to cover agent-authored
text drydock itself renders, and says so.

The scan iterates **code points**, not `char`s. A char-based loop misses
supplementary-plane offenders: the tag block U+E0020–U+E007F is category
`FORMAT` and is today's invisible-text-smuggling vector, but neither of
its surrogates is `FORMAT` or an ISO control. Code points also make the
length rule consistent with the rejection rule.

Rules, in this order:

1. **Reject** any code point whose `Character.getType` is `CONTROL`,
   `FORMAT`, `SURROGATE`, `PRIVATE_USE`, `UNASSIGNED`, `LINE_SEPARATOR`
   or `PARAGRAPH_SEPARATOR`.
   - `CONTROL` with **no** newline/tab exemption: a title is one line.
   - `FORMAT` covers U+202E RLO and the other bidi overrides, the
     U+2066–2069 isolates, U+200E/200F, U+200B ZWSP and the tag block —
     each of which survives a whitespace collapse and reorders or hides
     the text *around* it, in the rail and inside `Delete session "…"?`.
     It also covers U+200D ZWJ, so ZWJ emoji sequences are refused. That
     is accepted: an invisible joiner is exactly the smuggling primitive
     this rule removes, and a session title does not need one.

     **Emoji are otherwise unaffected, and this is easy to get wrong** — a
     reviewer of this design got it wrong, and the correction was only
     forced when a test written to prove the bug refused to fail. An
     emoji's variation selector (U+FE0F and its neighbours, and the
     U+E0100 supplement) is `NON_SPACING_MARK`, **not** `FORMAT`; the
     emoji themselves are `OTHER_SYMBOL`. So `⚠️`, `❤️` and `😀` all pass
     rule 1 untouched. Only the *glued* sequences — professions,
     families, composed flags — are refused. The refusal message says so
     in those words, because an agent told only "found U+200D" retries
     blind until its rename budget runs out.
   - `LINE_SEPARATOR`/`PARAGRAPH_SEPARATOR` (U+2028/U+2029) are neither
     ISO controls nor `FORMAT`, and AppKit honours them — without this
     clause "a title is one line" is not enforced.
   - `SURROGATE` catches a lone surrogate from a `\uD800` JSON escape
     (`String.codePoints()` emits it as a `SURROGATE` code point), which
     would otherwise become `?` at
     `JsonApplicationStateRepository.java:109` and fail to round-trip.
   - `PRIVATE_USE`/`UNASSIGNED` render as arbitrary glyphs or tofu.
2. **Reject** the double-quote `"`. Every dialog embeds the name in
   quotes inside a sentence; a title of `x" — already merged, safe to
   remove` renders a destructive confirmation that reads as reassurance.
   A quote in a session title is worth nothing.
3. **Reject** more than two consecutive combining marks
   (`NON_SPACING_MARK`, `ENCLOSING_MARK`, `COMBINING_SPACING_MARK`).
   Legitimate scripts never need a third in a row, and the cap in rule 6
   bounds code points, not rendered height. Note the interaction with the
   bullet above: a variation selector is a `NON_SPACING_MARK`, so it is
   *this* rule that governs it, not rule 1 — one selector after a base
   character is one mark, which is why colour-presentation emoji pass.
4. **Fold** every `SPACE_SEPARATOR` (Zs) code point to U+0020, collapse
   runs of U+0020 to one, then trim. Folding rather than trusting
   `strip()`/`isBlank()`/`\s`: `Character.isWhitespace` is **false** for
   U+00A0, U+2007 and U+202F, so a title of 60 NBSPs would otherwise pass
   every check and produce a blank tab label and
   `Delete session "                "?`.
5. **Reject** blank after folding. (`requiredStringArg`,
   `McpToolRouter.java:672`, rejects a missing or `isBlank()` argument
   earlier — but `isBlank()` is false for NBSP, so rule 4 plus this check
   is what actually closes it.)
6. **Reject** longer than **60 code points**, measured after folding.
7. **Reject** a title colliding with the current name of another session
   in the **same repository**. See below.

Order is part of the contract: folding precedes the length check, so a
70-character title that folds to 55 succeeds.

Refusal, not truncation, at every step: truncation discards the model's
own words and teaches it nothing.

```java
/** @return the folded title, which is what gets stored and compared */
public static String checkSessionTitle(String title) throws McpToolException;
```

It **returns the folded string** — rules 5, 6, the `UNCHANGED` comparison
and the write all operate on that value, not on the raw argument, so it
must cross the seam. (`PromptSafety` has both precedents in one file:
`checkInboundText` returns its input at `:58`, `validate` returns void at
`:84`.) The **router** calls it, before charging the budget and before
the context call, following `PromptSafety.validate`'s existing call site
at `McpToolRouter.java:544` — which is what lets the router tests drive
every validation case against a fake context that only returns canned
outcomes.

Rules 1–6 are pure and live there. Rule 7 needs the session catalog and
therefore lives in the same state transform as the write (see "The check
must be inside the state transform"), where the comparison cannot go
stale.

The router also calls `requireLiveSession(caller)`
(`McpToolRouter.java:655`), as every mutating tool does.

### Impersonation: rule 7, and why the dialogs still change

The sharpest attack needs no special characters. `sessions_list` hands an
agent every sibling's exact title, so it can rename itself to a
byte-identical copy, and every destructive dialog is keyed on the name
alone. `MAX_SESSIONS_PER_SESSION = 4` means one agent can hold five such
rows.

**Corrected after implementation.** Earlier drafts added "and the sidebar
sorts the impostor directly adjacent to its target
(`RepositorySidebar.java:893`)". That is false, and running the app is
what showed it: `sessionsFor` does sort by name at `:893`, but
`SidebarChildren.classify` (`:85-96`) then re-bands the rows
live-then-idle, each by `lastOpenedAt` descending, and *that* is the
order the tree renders. A rename never moves a row. So name-cloning
cannot buy adjacency; it buys two identically-labelled rows somewhere in
the list. That is still confusing, and the destructive dialogs are still
keyed on the name, so both mitigations below keep their justification —
but the most alarming-sounding half of the threat was never real.

**Rule 7** kills the exact-clone case: an agent cannot take a name
another session in the same repository is already using. Precisely:

- **Comparison**: `equalsIgnoreCase`, matching the sidebar's own
  `String.CASE_INSENSITIVE_ORDER` sort (`RepositorySidebar.java:893`)
  rather than a locale-sensitive `toLowerCase()`, whose Turkish `İ`
  behaviour would differ from the adjacency it defends.
- **Both sides folded**: rule 4's Zs-fold and trim are applied to the
  *existing* names before comparing. Existing names were typed by humans
  through a path with no `checkSessionTitle`, so `Repo Work` would
  otherwise never equal `Repo Work` and the rule would be trivially
  bypassable from the human-authored side.
- **Scope**: every session of the repository in the catalog, including
  exited ones — they are still rows in the sidebar, which is the
  adjacency the rule protects. Same repository only: the sidebar groups
  by repository, so a cross-repo collision is neither adjacent nor
  confusing.

It leaks nothing — the agent already reads every title through
`sessions_list` — and it is checked inside the write transform, so two
agents renaming at once cannot race past it.

Honest sessions **will** hit this: two agents on one repository both
deciding they are "Fix login flow" is the expected outcome of the
`instructions` text, not an attack. So the refusal explains itself and
tells the agent what to do, rather than reading as a malfunction:

> Another session in this repository is already called 'Fix login flow'.
> Choose a title that tells the two apart.

The `instructions` text names this alongside the pin, so an agent knows
both refusals exist before it meets one.

Rule 7 does **not** stop homoglyphs (`Rероsitorу` in Cyrillic) or
near-misses. Rejecting those means a script-mixing policy that breaks
honest non-Latin titles, which this design will not adopt. So the two
destructive dialogs stop being keyed on the name alone:
`RepositorySidebar.java:1241` and `MainWorkspace.java:2098` add the
session's **`workingDirectory()`** to their content text, following the
precedent already set at `MainWorkspace.java:2159`
(`"Expected directory: " + session.workingDirectory()`).

Two honest limits, stated because an earlier draft claimed more:

- `workingDirectory()` is `worktreeRoot().orElse(repository.root())`
  (`SessionManager.java:909`), so two non-worktree sessions in one
  repository share it. Rule 7 is what separates those; the path is
  defence in depth, not the primary control.
- For worktree sessions the path *is* partly agent-chosen — the agent
  picks the branch through `worktree_create`, and
  `WorktreeNaming.defaultDirectory` derives the directory from it. It can
  therefore choose a near-miss directory as easily as a near-miss title.
  The path still helps, because the two must be near-missed together and
  the human sees both.

### Two labels must stop growing

`RepositorySidebar.java:1491` is `new Label(session.displayName())` with
a style class that sets font and fill only — no max width, no overrun. A
`Label`'s min width defaults to its pref width, and the row sits in an
`HBox` inside an `HGROW` `VBox`, so 60 full-width CJK code points set the
sidebar's — and then the window's — minimum width. That is the JavaFX
trap this project has hit before, and it is invisible to the headless
suite.

`OpenSessionTab.java:629` has the same hole for the same string:
`headerTitle` (style class `session-title`, 14px bold) sits in the
`headerTitles` `VBox` (`:157`), itself HGROW'd inside the header `HBox`
(`:632`, `:671`), with no max width and no overrun. The *tab* label is
already defended (`setMaxWidth(160)`, `:582`); these two are not.

Both get `setMinWidth(0)`, `setMaxWidth(Double.MAX_VALUE)`,
`setTextOverrun(OverrunStyle.ELLIPSIS)`, and — the part that actually
makes it work — must sit under a node that grows, so the max width
resolves against the container rather than the text. In the sidebar that
is the existing `HBox.setHgrow(text, ALWAYS)`
(`RepositorySidebar.java:1516`), which already grows the column the label
is in; in the header it is `HBox.setHgrow(headerTitles, ALWAYS)`
(`OpenSessionTab.java:632`). `setMinWidth(0)` is what stops min-width
propagating up the chain; the overrun is what makes the clamp visible as
an ellipsis rather than clipped text.

These are real changes this design must make, not observations: rule 6
bounds code points, and code points do not bound pixels.

### The activity panel must not render what validation rejected

`McpServer.logActivity` records every call **including failures**, and
`summarize` (`:328`) writes the raw arguments JSON through a single
`replaceAll("\\s+", " ")` — which matches none of U+202E, U+200B, the tag
block, or NBSP. `ReviewMcpActivityPanel.java:154` renders the result as a
`Label`. So without a change, most of what `checkSessionTitle` rejects
still reaches drydock's UI, up to 160 characters of it.

`JsonWriter.writeString` escapes `"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t`
and every `c < 0x20` as `\uXXXX` (`state/json/JsonWriter.java:73-95`) —
but **only** those. U+007F DEL and the C1 block U+0080–U+009F are
category `CONTROL`, are legal unescaped JSON, and pass through both the
writer and the ASCII-only `\s+` collapse untouched. The only *other*
control characters in `summarize`'s input are `JsonWriter`'s own
pretty-print newlines and indents, which the collapse exists to flatten.

`summarize` therefore replaces `CONTROL`, `FORMAT`, `SURROGATE`,
`PRIVATE_USE`, `UNASSIGNED`, `LINE_SEPARATOR` and `PARAGRAPH_SEPARATOR`
with U+FFFD, in this order:

1. collapse `\s+` (ASCII-only, so it removes the writer's structural
   whitespace and nothing the next step targets),
2. replace the listed categories with U+FFFD,
3. truncate — **by code points**, not `text.substring(0, 159)`, which
   would split a surrogate pair and reintroduce the lone surrogate step 2
   just removed.

The order is what makes including `CONTROL` safe: sanitizing *before* the
collapse would replace `JsonWriter`'s own structural newlines and turn
every activity row for every tool into `{▯ "title": …`. After the
collapse, the only control characters left are the ones an argument
actually carried.

This is not specific to `session_rename`: it is the argument sink for
every tool, and every tool's arguments are agent-authored.

### Cost, the no-op, and the refusal

Four outcomes. `McpSessionContext` declares them, next to the
`SessionSummary` record it already owns:

```java
enum RenameKind { RENAMED, UNCHANGED, PINNED, COLLIDED }

/**
 * {@code currentName} is the name in force after the call, except on
 * COLLIDED, where it is the colliding sibling's name.
 */
record RenameOutcome(RenameKind kind, String currentName) {
}
```

Evaluated in the transform in this order, so the overlapping inputs have
one defined answer (a pinned session asked for its own current name is
`PINNED`, not `UNCHANGED`):

1. **PINNED** — the human owns the name. No write, and **no publish**: an
   agent retrying a pinned session must not drive a sidebar rebuild per
   attempt.
2. **UNCHANGED** — the validated title equals the session's current name.
   No write, no publish.
3. **COLLIDED** — rule 7. No write, no publish. The router turns it into
   an `McpToolException` carrying the message above.
4. **RENAMED** — written and published.

A fourth arm rather than "an exception from the transform", which an
earlier draft specified and which cannot be built:
`ApplicationStateStore.update` takes a `UnaryOperator`
(`ApplicationStateStore.java:106`) and `McpToolException` is checked
(`McpToolException.java:9`), so a lambda cannot throw it — and
`SessionManager`, which is core rather than MCP, should not be throwing a
wire-protocol type in any case. Every arm leaves the store by the
`ManagedAgentSession[] result` out-parameter pattern this class already
uses (`SessionManager.java:713-731`, `:808-824`), and only the router
decides what is an error.

`McpSessionRegistry` gains `chargeRename` / `refundRename`, cap **20 per
session**. It cannot reuse the shared `charge` helper: that helper
hardcodes *"has already created its limit of N … Ask the human to
continue in one of them"* (`McpSessionRegistry.java:118-126`), nonsense
for renames. `chargeRename`'s message is its own: *"This session has
already renamed itself 20 times."*

The router charges **after** validation and **before** the context call,
and refunds **only** when the call throws — a timeout or a vanished
session, where nothing happened and the agent deserves its budget back.
`PINNED`, `UNCHANGED` and `COLLIDED` are charged and **not** refunded.

That is a deliberate departure from the worktree and session budgets,
which refund every non-creating outcome (`McpToolRouter.java:511-522`,
`:562-572`; `McpSessionRegistry.java:102-116`). Refunding here would
leave the refused arms unmetered, and they are not free: each still costs
a `Platform.runLater` and a turn under `ApplicationStateStore`'s single
monitor, dispatched from `McpServer`'s
`newVirtualThreadPerTaskExecutor` (`:130`) with no concurrency bound. An
agent looping on a pinned session would saturate the FX thread and freeze
the UI while never spending budget. All three refusals are also
*informative* — each says what is wrong and what to do instead — so 20
attempts is far more than an agent needs to learn.

Two properties of the bound, stated because they are not obvious:

- Counters are in-memory, keyed by `ManagedSessionId`
  (`McpSessionRegistry.java:49`), so the cap is per-session
  per-app-process and **resets on restart**. Acceptable: it stops a
  runaway loop within a session, it does not ration renaming over a
  session's life.
- With `MAX_SESSIONS_PER_SESSION = 4` children each holding their own
  counter, one agent's true ceiling is 100 renames, not 20.
  `Spawn.FORBIDDEN` stops it there.

**On AGENTS.md's coalescing rule.** "Rebuild-the-world is a last resort:
debounce keystroke-driven rebuilds and coalesce N async completions into
one rebuild instead of one per completion." Every `RENAMED` publishes the
whole session model and rebuilds the sidebar subtree. The rule is
considered and deliberately answered with a budget instead of a debounce:
there is no async fan-in here to coalesce (renames are independent calls
minutes apart in honest use, not N completions of one operation), a
rename is a discrete event the human should see immediately rather than
150 ms later, and the pathological case is bounded at 100 per process by
the budget. If thrash is ever observed in practice, a debounce on the
agent path — not the human's — is the fix.

## The pin

The human's rename wins, permanently.

`ManagedAgentSession` gains a `boolean namePinned` component. Not free:
it is a 15-component record with ten hand-written `withX` copy methods
that each re-list every component (`ManagedAgentSession.java:98` onward)
inside 26 `new ManagedAgentSession(...)` call sites in all — ten in the
record itself, sixteen across main and test.
All must be extended, and a `withNamePinned` added. `withDisplayName`
must **not** touch the pin — pinning is a decision of the caller, not of
the act of renaming.

### Pinning is an explicit confirm, and needs new plumbing

The pin is set when a human *explicitly confirms* a rename affordance,
even if the resulting name is identical — a human who opens the editor,
decides the agent's title is right and presses Enter has performed
exactly the gesture that means "this name is mine".

Explicit confirm means Enter in the inline editor
(`renameField.setOnAction`, `OpenSessionTab.java:607`) and OK in
`MainWorkspace.promptRenameSession`'s dialog (`:2316`). It does **not**
include blur. `renameField.focusedProperty()` also commits on focus loss
(`:608-611`), and the editor opens on a double-click of the tab (`:601`).
If blur pinned, an accidental double-click then a click elsewhere would
pin a session forever — and an agent's `session_start` selects a new tab
and takes focus, so an agent could pin a human's session at will.

Today one `Consumer<String> onRenamed` serves both paths
(`OpenSessionTab.java:779`, wired at `MainWorkspace.java:2684`), so
"pins" and "does not pin" cannot be distinguished without new plumbing.
Three signatures change:

```java
// OpenSessionTab
private void commitInlineRename(boolean pin)   // Enter -> true, blur -> false
BiConsumer<String, Boolean> onRenamed          // was Consumer<String>

// MainWorkspace
public void renameSession(ManagedSessionId id, String newDisplayName, boolean pin)

// SessionManager
public ManagedAgentSession renameSession(ManagedSessionId id, String name, boolean pin)
```

There is no separate "write" path to keep a guard for: `onRenamed` *is*
the write, and today's guard is
`if (!newName.isEmpty() && !newName.equals(displayName))`
(`OpenSessionTab.java:778`). It becomes:

```java
if (!newName.isEmpty() && (pin || !newName.equals(displayName))) {
    onRenamed.accept(newName, pin);
}
```

Empty text still does nothing at all — it cancels like Escape, on both
paths. That matters: `MainWorkspace.renameSession` has no emptiness
filter of its own (only `promptRenameSession` does, `:2318`) and the
human path applies no `checkSessionTitle`, so a `pin == true` branch that
notified on empty text would blank the tab label permanently *and* pin
it. Escape cancels and neither writes nor pins. `promptRenameSession`
passes `pin == true` and already fires on an unchanged name.

The accepted cost: opening ⌘R out of curiosity and pressing OK pins the
session forever, silently, with no un-pin. That is the price of "human
rename wins, no un-pin, no new UI", and it is why the pin is worth
exactly one boolean.

### Both editors stay prefilled with what the human was shown

An earlier draft had `setDisplayName` refresh an open, unedited inline
editor with the agent's new title. Removed: combined with
confirm-pinning it let an agent substitute its own title under a human's
cursor and have the human's Enter pin it as *the human's* name —
inverting what the pin means.

Neither editor is refreshed. `promptRenameSession` has the same shape
anyway (`new TextInputDialog(session.displayName())`, and `showAndWait`
runs a nested event loop, so an agent rename lands while it is open). If
an agent renames while an editor is open, confirming writes back the name
the human was shown when they opened it — a human confirming a name they
actually read, which is the correct resolution.

### The check must be inside the state transform

`SessionManager` gains:

```java
RenameOutcome applyAgentRename(ManagedSessionId id, String title);
```

Named distinctly from `MainWorkspace.renameSessionFromAgent` below, which
wraps it and returns a future.

The pin test, the unchanged test, **rule 7's collision test** and the
write are a single transform submitted to `stateStore.update` — never
read-then-write. AGENTS.md's "one writer for persistent state" names
load-then-save as a documented data-loss bug, and the prior MCP work
retrofitted `AnnotationStore.mutate(key, transform)` for the same hazard.
Reporting an outcome out of a transform has a pattern in this class
already: the `ManagedAgentSession[] result` out-parameter used by
`adoptConversation` (`SessionManager.java:713-731`) and
`markSessionExited` (`:808-824`), both of which already have an
"unchanged" arm that returns the state untouched. `ApplicationStateStore.
update` (`:106-116`) skips the write when the transform returns an equal
state, under one monitor.

`SessionManager.renameSession(id, name, pin)` — the human path — sets
`namePinned` when `pin` is true and is otherwise unchanged.

## How an agent rename reaches the tab

`SessionManager` has no listener or observer mechanism. Every UI refresh
is a manual `publishSessions()` call — which is why
`MainWorkspace.renameSession` (`:2304`) is the manager call followed by
`publishSessions()`. Only that publish reaches
`WorkspaceViewModel.setSessions` → the listener at
`MainWorkspace.java:428` → `updateTabHeader` → `open.setDisplayName(...)`
(`:592`). A rename that goes only through `SessionManager` provably never
relabels a tab. The state write is already thread-safe off the FX thread;
the *publish* is not.

So the agent path goes through `MainWorkspace`, mirroring
`startAgentSession` exactly:

```java
public CompletableFuture<RenameOutcome> renameSessionFromAgent(
        ManagedSessionId id, String title);
```

- **It computes its own deadline**, as `startAgentSession` does at
  `:1820`, from a new
  `AGENT_RENAME_BUDGET_SECONDS = WorkspaceMcpSessionContext.RENAME_TIMEOUT_SECONDS / 2`
  beside the existing `AGENT_SESSION_BUDGET_SECONDS` (`:153`). The
  deadline is **not** a parameter. Halving is the point: the workspace's
  budget must be provably **smaller** than the context's join, not merely
  no larger. With equal deadlines an FX body passing `expired()` at
  t=24.99 s renames and publishes while the join times out at t=25.0 s —
  which is how a budget stops being a budget. Integer division gives
  `AGENT_RENAME_BUDGET_SECONDS = 12` (a `long`, as
  `AGENT_SESSION_BUDGET_SECONDS` is), strictly smaller, which is all the
  inequality needs. Because the callee derives its own deadline, the
  existing `BiFunction`-shaped seam suffices and no new functional
  interface is needed.
- It re-checks `expired(deadlineNanos)` **on the FX thread** immediately
  before touching anything, for the reason `MainWorkspace.java:1827-1832`
  already documents inline: a `runLater` queued behind a busy FX thread
  must refuse rather than complete work nobody is waiting for.
- Its FX body is wrapped in `catch (RuntimeException e) →
  completeExceptionally`, as at `:1843`. `SessionManager.updateSession`
  throws `UnknownSessionException` when the session vanished between
  `requireLiveSession` and the transform (`:959`); without the catch the
  future never completes and the HTTP handler blocks for the full join.
- `publishSessions()` runs **only** on the `RENAMED` arm.

`WorkspaceMcpSessionContext` gains a constructor parameter mirroring the
existing `sessionStarter` (`:102`):

```java
BiFunction<ManagedSessionId, String, CompletableFuture<RenameOutcome>> sessionRenamer
```

The class holds no JavaFX type and no UI import by documented design, and
this preserves that: the FX hop lives in `MainWorkspace`. Both
construction sites are updated — `DrydockApplication.java:1228` and
`WorkspaceMcpSessionContextTest.java:337`, the latter mirroring the fake
already used for `sessionStarter`.

The join uses a new **public** `RENAME_TIMEOUT_SECONDS = 25`, declared
beside `START_SESSION_TIMEOUT_SECONDS = 30`
(`WorkspaceMcpSessionContext.java:87`) and public for the same reason
that one is: so `MainWorkspace` derives its budget from it rather than
restating a number. 25 rather than 20 keeps it distinct from the private
`JOIN_TIMEOUT_SECONDS = 20` (`:74`) it sits next to, so neither is
mistaken for the other.

A timeout or an `UnknownSessionException` surfaces as an
`McpToolException` from the seam, and the router refunds the charge.

### Why the sidebar model needs nothing

`WorkspaceViewModel.isRowLevelChange` (`ui/model/WorkspaceViewModel.java:148`)
treats any `displayName` difference as *not* row-level, so a rename takes
the full-rebuild path rather than the in-place row update. The sidebar's
sort and its search filter are therefore both re-applied. No change is
needed there, and none should be made — recorded so a future reader does
not "fix" it. (The `Label` sizing fix above is a different problem.)

## Seam

```java
/** Renames the caller's own session. Never returns null. */
RenameOutcome renameSession(ManagedSessionId caller, String title) throws McpToolException;
```

`currentName` is the name in force after the call: on `PINNED` it is the
human's name, which the router needs for its message without a second
lookup. Refusal is an outcome of a legal call, not an exception; the
message belongs with the router's other refusals:

> This session was named by the human ('…'); drydock will not rename it.

That quoted embed is the one rule 2 does not protect, since the human
path applies no `checkSessionTitle` — accepted, because its only reader
is the calling agent.

`FakeMcpSessionContext` gains a settable **canned `RenameOutcome`**, so
router tests drive all three arms without JavaFX. (Not a "pin boolean":
the pin decision lives in `SessionManager`'s transform, which the fake
does not have.)

`SessionSummary` and `sessions_list` are **unchanged**. An earlier draft
added `name_pinned` to every row; that widens the design's own untrusted
cross-session channel and hands every agent a pollable "a human has
touched this session" bit, to save the caller one failed call on its own
session — where the refusal message already says everything.

## Making the agent call it

`McpServer.initializeResult` (`:336`) returns `protocolVersion`,
`capabilities` and `serverInfo`. It does not return the MCP spec's
`instructions` field, which the client injects into the session's system
prompt. That absence is why no drydock tool is called proactively: an
agent reads a tool description only when already looking for a tool, and
nothing points it at this one.

`instructions` is added, saying roughly:

> Drydock hosts this session in a tab a human is watching. As soon as you
> know what the work actually is, call `session_rename` with a short
> title naming the work — not the branch. Re-title it if the work turns
> out to be something else. Two refusals are normal and both explain
> themselves: if the human has named the session, leave it alone; if
> another session here already has that title, pick one that tells them
> apart.

The tool's own description repeats the "as soon as you know" trigger. The
field is shared infrastructure: every drydock tool added later can be
introduced there.

## Activity log

`McpServer.logActivity` (`:308`) picks a direction at `:317`, a direction with
`tool.startsWith("review_") && !review_scope && !review_state`. A tool
named `session_rename` is an agent write into drydock — `INBOUND` by the
enum's own definition — but that prefix heuristic logs it `OUTBOUND`.

The heuristic is replaced with an explicit set, enumerated here so the
replacement cannot silently reclassify anything:

```
INBOUND (agent writes into drydock):
    review_reply, review_intents, review_finding, review_answer, session_rename
OUTBOUND (drydock answers the agent):
    review_comments, review_scope, review_state, worktree_create,
    session_start, repos_list, sessions_list
```

That is every tool in `McpToolRouter`'s dispatch switch (`:158-171`).

**One tool changes classification, deliberately: `review_comments`.** It
starts with `review_` and is not one of the two exceptions, so today it
logs `INBOUND` — but it is a read: the agent asks for open threads and
drydock answers. The prefix heuristic simply cannot express that, which
is the reason for replacing it. A test asserts all three cases that could
regress: `session_rename` → INBOUND, `review_finding` → INBOUND (an
existing INBOUND tool that must not move), `review_comments` → OUTBOUND
(the deliberate change).

`Direction` is read only by `ReviewMcpActivityPanel.java:150` for a
glyph, so nothing else changes.

## Testing

- **Router** (`McpToolRouterSessionRenameTest`): happy path; missing
  argument; a title of only NBSP (rule 4/5, not caught by
  `requiredStringArg`); 60 code points accepted and 61 refused; a
  70-character title folding to 55 accepted; `\n` refused (the case
  `checkInboundText` would have passed); U+202E refused; tag character
  U+E0021 refused — the case a `char`-based loop passes; U+2028 refused;
  a lone surrogate refused; three consecutive combining marks refused,
  two accepted; `"` refused; a 60-code-point CJK title accepted; dead
  session refused; pinned session refused with the pinned name in the
  message; an unchanged title returning `UNCHANGED`; a `COLLIDED` outcome
  surfacing as an `McpToolException` naming the sibling; the 21st rename
  refused. The budget refusal is an `McpToolException` carrying the
  budget text — `McpBudgetExhaustedException` is checked and is converted
  at `:513`/`:565`, so it never escapes `router.call`.
- **Budget accounting**: `PINNED`, `UNCHANGED` and `COLLIDED` each
  consume a charge; a call whose future completes exceptionally refunds
  it.
- **`checkSessionTitle`** as a direct unit test — most of the risk is
  there, and it is pure.
- **tools/list**: `session_rename` present, declaring `title` in
  `required`.
- **SessionManager** (`applyAgentRename`): a human rename with `pin=true`
  pins; an agent rename after that returns `PINNED` with the human's name
  and leaves it unchanged; an agent rename before that returns `RENAMED`
  and does *not* pin, so a second agent rename also succeeds; an agent
  rename to the current name returns `UNCHANGED`; **rule 7** — an agent
  rename to a name another session in the same repository holds is
  refused, while the same name in a different repository is allowed; a
  collision against an *exited* session is still refused; a collision
  that differs only by case, and one that differs only by NBSP-vs-space
  on the stored side, are both refused; a pinned session asked for its
  own current name returns `PINNED`, not `UNCHANGED`.
- **Human rename plumbing**: `renameSession(id, name, pin=false)` leaves
  `namePinned` false; `pin=true` on an unchanged name still pins.
- **ApplicationState**: `namePinned` survives a round trip; a state file
  written before this change decodes with `namePinned` false. The codec's
  precedent defaults the other way — `branchCreatedHere` decodes *true*
  when absent **or malformed** (`ApplicationStateCodec.java:332`) by
  deliberate choice. `namePinned` decodes false in both cases, added
  leniently within schema version 2 with no bump, and the codec's header
  schema comment (`:66`) is updated.
- **`summarize`**: a `FORMAT` code point, a U+007F DEL (`CONTROL`, which
  `JsonWriter` does not escape) and a lone surrogate in an argument value
  all become U+FFFD, on a failed call as well as a successful
  one; a multi-member arguments object still renders on one line (the
  regression that sanitizing before the `\s+` collapse would cause); and
  truncation at the 160-character boundary never emits a lone surrogate.
- **Activity log direction**: the three assertions named above.
- **McpServerTest**: the initialize result carries a non-empty
  `instructions` string naming `session_rename`.

Deliberately **not** automated, with the reason recorded so nobody
mistakes the gap for an oversight:

- **The tab relabel.** The behaviour the feature exists for. There is no
  `MainWorkspace` test anywhere under `app/src/test/java/app/drydock/ui/`
  and its constructor takes 15 collaborators including a live `Stage`
  (`:321`); standing that up for one assertion is not proportionate.
- **"No publish" on the three refused arms.** That decision alone lives
  in `MainWorkspace.renameSessionFromAgent`, on the far side of the same
  missing harness; the router's fake has no `publishSessions` to observe.
  It is enforced structurally — the publish sits inside the `RENAMED`
  branch. ("No write" is *not* in this category: it lives in
  `SessionManager`'s transform and is covered by the `UNCHANGED`,
  `PINNED` and `COLLIDED` cases above.)
- **Enter pins and blur does not.** This is the control that stops an
  agent's focus-stealing `session_start` from pinning a human's session,
  and it deserves a test — but it lives in `OpenSessionTab`'s focus
  listener, which needs the same absent UI harness. The
  `SessionManager`/`MainWorkspace` half of the plumbing (the `pin`
  parameter) *is* tested above; the gesture-to-parameter mapping is not.
  Recorded here rather than left to look like coverage.
- **Transform atomicity.** The pin, unchanged and collision tests plus
  the write are one transform under one monitor, so no interleaving point
  exists to drive a deterministic race test. An earlier draft proposed
  asserting "exactly one `stateStore.update` call"; the build has no
  mocking framework (`app/build.gradle.kts`) and `ApplicationStateStore`
  is `final` with no counting seam, so that assertion is unwritable too.
  Atomicity is structural, and the `SessionManager` cases above are its
  coverage.
- **The two `Label` sizing fixes.** Layout properties the headless suite
  cannot see.

**`docs/manual-terminal-checklist.md`** gains three entries:

1. Start a session; confirm the agent names its own tab unprompted within
   its first few turns. No unit test can show the client actually injects
   `instructions`; without this the feature can be inert with every test
   green.
2. Confirm the tab label and the sidebar row both change when it does,
   and that the row re-sorts into its new alphabetical position.
3. With a 60-code-point CJK title, confirm the sidebar row and the
   session header both **ellipsize** — not merely that the window's
   minimum width is unchanged, which `setMinWidth(0)` alone would satisfy
   while the clamp silently did nothing.

## Landing order

Three of these changes are independent of the feature and should land as
their own commits, before it. None references `RenameOutcome`,
`namePinned`, or the seam, and each fixes something already wrong:

1. The `summarize` sanitizer — the argument sink for every tool.
2. The activity-log direction enumeration, including the deliberate
   `review_comments` reclassification, which is a behaviour change to an
   unrelated feature and should not be buried in this one.
3. The two `Label` sizing fixes — a pre-existing layout bug that the
   60-code-point cap merely makes reachable.

Then the `namePinned` record change with its codec and 26 call sites,
which is mechanical and large. Then the tool. This is a landing order,
not a design split.

## Known exposure, accepted

- **`display_name` is now an agent-authored cross-session channel.**
  `sessions_list` serves every title to every live agent. An agent
  prompt-injected by a diff can write 60 code points a sibling later
  reads. Inherent to the feature; recorded so the next reader of
  `sessions_list` knows those strings are untrusted.
- **Homoglyphs and near-miss names remain.** Rule 7 stops exact
  collisions only; the dialog path line is the mitigation for the rest.
- **The session token is attribution, not isolation.**
  `McpConfigWriter`'s javadoc (`:43-45`) already says any process running
  as the user can read a sibling's `mcp/<id>.json`, and hosted agents
  have Bash. "Renames the caller only" holds against the network, not
  against a session that shells out. Pre-existing; this is the first
  feature to write a human-visible label through that seam, which raises
  the payoff but not the exposure.

## What this deliberately does not do

- No separate agent-authored subtitle, tooltip, or second line on the tab.
- No history of previous titles, and no pin indicator in the UI.
- No renaming of any session but the caller's own.
- No un-pin affordance.

## What the adversarial review changed

Five rounds, three reviewers each (mechanism, abuse, spec quality),
the last a convergence check.

**Round 1 — the first draft.**

1. **The seam did not exist.** `WorkspaceMcpSessionContext` has no
   `SessionManager`, no FX hop, no UI import. → Constructor parameter
   mirroring `sessionStarter`.
2. **"Exactly as its other mutating methods do" was false** — no method
   there hops to FX. → Deleted; the hop lives in `MainWorkspace`.
3. **The "Open item" had a determinate negative answer.** Only
   `publishSessions()` relabels a tab. → Promoted to a specified path.
4. **`checkInboundText` permits `\n`, `\r`, `\t`, caps at 8000.** → New
   `checkSessionTitle`.
5. **Unicode `FORMAT` survives the check and the collapse.** → Rejected.
6. **The dialogs embed the name in quotes inside a sentence.** → `"`
   rejected.
7. **The pin check was two state-store cycles** — the documented
   data-loss shape. → One transform.
8. **The two human affordances disagreed** on the unchanged-name case. →
   Pin on confirm.
9. **Nothing bounded rename frequency.** → No-op short-circuit plus a
   per-session charge.
10. **`McpActivityLog` would log this `OUTBOUND`.** → Explicit set.
11. **Record blast radius understated**; codec precedent defaults the
    other way. → Both stated.
12. **`instructions` had no test that could fail meaningfully.** →
    Assertion plus a manual-checklist entry.
13. **Rejected as wrong:** that the sidebar would not re-sort or
    re-filter. `isRowLevelChange:148` already excludes `displayName`.

**Round 2 — the first revision.**

14. **`Optional<String>` could not express the no-op**, making the budget
    rule unimplementable. → Three-armed `RenameOutcome`.
15. **No workspace-side deadline**, so a `runLater` behind a busy FX
    thread lands after the refund. → FX-side `expired` re-check.
16. **The `runLater` body had no exception arm**, so
    `UnknownSessionException` would hang the handler. →
    `completeExceptionally`.
17. **`Character.getType` over `char` misses supplementary-plane
    `FORMAT`** — the tag block. → Code-point iteration.
18. **NBSP is not `isWhitespace`**, so 60 NBSPs passed everything and
    produced a blank tab and a blank delete dialog. → Zs folded first.
19. **U+2028/U+2029 are neither controls nor `FORMAT`.** → Both
    separator categories rejected.
20. **Lone surrogates, private-use, unassigned passed**; surrogates do
    not round-trip through the state file. → Rejected.
21. **Combining marks unbounded inside the code-point cap.** → Max two
    consecutive.
22. **Title cloning needs no special characters**; the dialogs are keyed
    on the name alone. → Dialog disambiguation (superseded by rule 7 in
    round 3).
23. **Blur commits the inline editor**, and `session_start` steals focus,
    so confirm-pinning on blur let an agent pin a human's session. → Pin
    on Enter/OK only.
24. **The prefill-refresh mitigation inverted the pin.** → Removed.
25. **`charge`'s message is hardcoded** and reads as nonsense for
    renames; the planned test asserted an exception that never escapes. →
    `chargeRename`; test corrected.
26. **Refusals were uncharged but still published.** → No publish on
    `PINNED`; charge-then-refund.
27. **The budget's real shape was unstated**: in-memory, per process, ×5
    with children. → Stated.
28. **The "pin atomicity" race test was unwritable.** → Replaced (and
    replaced again in round 3).
29. **The tab-relabel test hid a 15-collaborator harness.** → Moved to
    the manual checklist.
30. **`name_pinned` on `sessions_list` was a pollable human-presence
    oracle.** → Dropped.
31. **The "explicit set" was never enumerated.** → Enumerated.
32. **`FakeMcpSessionContext` "gains a settable pin"** described state
    the fake cannot hold. → A canned `RenameOutcome`.
33. **ZWJ is `FORMAT`**, so rule 1 bans ZWJ emoji, contradicting the
    emoji rationale. → Ban kept, rationale corrected. (This entry was
    itself half-wrong about the scope of the ban; see #60.)
34. **False claim removed:** that `displayName` is "typed into a
    terminal" via `claude -n`. `TerminalSpec`'s command is exec'd as an
    argv by libghostty and `shellQuote` is correct POSIX quoting.

**Round 3 — the second revision.**

35. **The enumerated activity-log table silently reclassified
    `review_comments`** while claiming every tool kept its classification,
    and the proposed test could not have caught it — the exact failure
    the enumeration was added to prevent. → Change made explicit, with a
    three-case test.
36. **The deadline inequality was backwards.** "Provably no larger"
    permits equality, and one constant fed both sides — leaving open the
    hole finding 15 claims to close. The cited precedent halves it
    (`AGENT_SESSION_BUDGET_SECONDS = START_SESSION_TIMEOUT_SECONDS / 2`).
    → Halved, and `RENAME_TIMEOUT_SECONDS` moved to 25 so it is not
    confused with the neighbouring `JOIN_TIMEOUT_SECONDS = 20`.
37. **Passing `deadlineNanos` in contradicted the precedent whose
    rationale was quoted** — `startAgentSession` derives its own, which is
    why the constant is public. → Callee derives it; the invented
    `TriFunction` (which existed nowhere and had no specified home)
    disappears with it.
38. **Blur still pinned.** One `Consumer<String> onRenamed` serves Enter
    and blur, and `SessionManager.renameSession` pins unconditionally, so
    finding 23's fix was unimplementable as written; and Enter on
    unchanged text never reaches the callback at all. → Three signatures
    specified.
39. **The worktree-path disambiguation was weaker than claimed.** It is
    absent for non-worktree sessions (they share `repository.root()`) and
    partly agent-controlled for worktree ones (the agent picks the
    branch, which names the directory). → Rule 7 added as the primary
    control; the path demoted to defence in depth with both limits
    stated; `workingDirectory()` named explicitly, following `:2159`.
40. **The 60-code-point cap does not bound pixels.** The sidebar's
    session-name `Label` has no max width or overrun, and a `Label`'s min
    width is its pref width — so a long CJK title sets the window's
    minimum width. → Explicit `Label` sizing fix plus a manual check.
41. **The activity panel renders titles that validation rejected.**
    `logActivity` records failed calls and `summarize`'s only cleanup is
    an ASCII `\s+` collapse. → `summarize` replaces the same categories
    with U+FFFD, for every tool.
42. **The replacement atomicity test was also unwritable** — no mocking
    framework, `ApplicationStateStore` is final with no counting seam. →
    Dropped; atomicity stated as structural.
43. **"No publish on `PINNED`" had no possible test** at either layer. →
    Moved to the explicit not-automated list.
44. **`UNCHANGED` was "no charge" in one paragraph and charge-then-refund
    in another.** → "Charged and refunded, net zero".
45. **`RenameOutcome` had no owning file, and two different
    `renameSessionFromAgent` methods differed by return type.** →
    Declared on `McpSessionContext`; the manager's is `applyAgentRename`.
46. **AGENTS.md's coalescing rule was unaddressed** for a full rebuild
    per rename. → Considered explicitly, answered with the budget, with
    the reasons and the fallback named.
47. **Citation drift** in eight references. → Corrected.

**Round 4 — the third revision.**

48. **Rule 7 could not be built as specified.** `ApplicationStateStore.
    update` takes a `UnaryOperator` and `McpToolException` is checked, so
    a transform cannot throw it — and `SessionManager` should not throw a
    wire type anyway. → A fourth `COLLIDED` arm, out through the existing
    out-parameter pattern; only the router makes it an error.
49. **`summarize` sanitization was ordered wrong.** Sanitizing before the
    existing `\s+` collapse would have replaced `JsonWriter`'s own
    pretty-print newlines, turning every activity row for every tool into
    `{▯ "title": …`. → Order fixed (collapse, replace, truncate), and
    truncation moved to code points so it cannot re-emit a lone
    surrogate.
50. **The second unconstrained `Label` was missed.** `OpenSessionTab`'s
    `headerTitle` (`:629`) has the identical min-width trap as the
    sidebar row, at a larger font. → Both fixed, with the growing
    container named — `setMinWidth(0)` alone satisfies the manual check
    while the ellipsis never appears.
51. **The refused arms were unmetered.** Charge-then-refund left `PINNED`
    unbounded, and each attempt still costs an FX hop and a turn under
    the state lock, dispatched from an unbounded virtual-thread executor.
    → Refund only on exception; all three refusals are charged.
52. **`commitInlineRename`'s "early return for the write" described a
    structure that does not exist** — `onRenamed` *is* the write — and
    ignored the `isEmpty` half of the guard, which on the pin path would
    have blanked and pinned a tab label permanently. → The exact guard is
    given; empty text cancels on both paths.
53. **Rule 7's comparison was underspecified in three ways**: which fold,
    whether the stored side is folded (it must be, or a human-typed NBSP
    bypasses it), and whether exited sessions count. → All three stated.
54. **Rule 7 had no message**, though honest agents will collide
    routinely — two sessions on one repository both deciding they are
    "Fix login flow" is what the `instructions` text produces. → A
    message that says what to do, and a mention in `instructions`.
55. **`checkSessionTitle` had no return type and no stated caller**,
    though the folded string must reach the transform and the router
    tests presuppose router-side validation. → Signature and call site
    given.
56. **`PINNED` and `UNCHANGED` overlapped** with no precedence, and two
    listed tests disagreed about the answer. → Evaluation order fixed.
57. **Stale text from earlier revisions survived**: the deadline
    illustration still used round-2's 20 s, and the not-automated list
    claimed "no write on `UNCHANGED`" was untestable after item 45 had
    moved it into `SessionManager`. → Both corrected; the pin *gesture*
    test, which genuinely is untestable, was added there in its place.

**Round 5 — convergence check.**

58. **`CONTROL` had been dropped from `summarize`'s replacement set** on
    the false premise that `JsonWriter` escapes every control character.
    It escapes only `c < 0x20`: U+007F DEL and the C1 block U+0080–U+009F
    are `CONTROL`, are legal unescaped JSON, and pass both the writer and
    the ASCII-only `\s+` collapse into the activity panel. → `CONTROL`
    restored, which round 4's ordering fix had already made safe.
59. **Two descriptive slips**: `headerTitles` is a `VBox` HGROW'd inside
    the header `HBox`, not an `HBox`; and the 26 `ManagedAgentSession`
    construction sites *include* the ten copy methods rather than sitting
    alongside them. → Both corrected. The prescribed fixes were right;
    only the nouns were wrong.

Round 5 found nothing else: no contradiction between sections, no
unbuildable construct, no unwritable test, and every stated sink
verified.

**After implementation — corrected in the code, then here.**

60. **The whole-branch review claimed rule 1 rejects ordinary emoji**
    (`⚠️`, `❤️`, `▶️`) because U+FE0F is `FORMAT`, and I repeated that
    claim. **Both were wrong.** Variation selectors are
    `NON_SPACING_MARK`; the emoji themselves are `OTHER_SYMBOL`. Rule 1
    never touched either — only U+200D ZWJ, and therefore only the glued
    sequences. The error survived a five-round adversarial review, a
    whole-branch review, and my own summary, and was caught only because
    the test written to demonstrate the bug would not fail. → Rule 1 and
    rule 3 now state what actually happens, and a test pins it
    (`ordinaryEmojiAreFineIncludingTheirVariationSelectors`) so the next
    reader need not re-derive it from the Unicode tables.
61. **The ZWJ refusal named a code point and nothing else.** The ban is
    right, but "found U+200D" is not actionable, and an agent that cannot
    tell what to change retries until its rename budget is gone. → The
    message now says multi-part emoji cannot be used and a single emoji
    is fine. Mutation-checked: disabling the branch fails the test.

62. **The sidebar-adjacency claim was false.** Every draft said a
    name-cloning impostor "lands directly adjacent to its target" because
    the sidebar sorts by `displayName`. Running the app showed rows are
    ordered by `lastOpenedAt` within live/idle bands
    (`SidebarChildren.classify:85-96`); the name sort at
    `RepositorySidebar:893` never reaches the tree, and a rename never
    moves a row. → Corrected in "Impersonation" and in the manual
    checklist, which had inherited the same error. Rule 7 and the dialog
    disambiguation keep their justification; the adjacency framing does
    not.
63. **Verified end to end on a running app** (2026-08-05): a real
    `session_rename` call over the live HTTP endpoint, with a real minted
    token, returned `renamed` and the tab, session header and sidebar row
    all relabelled. The `initialize` response over the wire carries
    `instructions` naming the tool. That is the chain no automated test in
    this repo can reach.

The lesson worth keeping from #60 is not about Unicode. Three tests in
this work asserted things that would pass against any implementation, and
every one of them was written to describe a property rather than to
exercise it. A test that cannot fail does not merely add nothing — here
it actively laundered a false claim through two review layers.
