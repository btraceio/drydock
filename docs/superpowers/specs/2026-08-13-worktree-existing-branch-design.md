# A worktree can start on a branch that already exists

Opening a worktree on an existing branch already works in the desktop
app. What is missing is that the capability is **invisible in one surface
and absent from the other**.

In the modal the mode is derived, never chosen: the branch field's text is
looked up in the `BranchCatalog` on every keystroke, a hit means "check it
out", a miss means "create it" (`NewWorktreeModal.java:41-45`). A user who
opens the modal sees a field pre-seeded with `feat/` under a label reading
"New branch" and no indication that the other thing is possible. Checking
out an existing branch reads as an accident of typing rather than a
supported choice.

In MCP there is no other thing. `worktree_create` always runs
`git worktree add <dir> -b <branch> [start_point]`
(`GitStatusService.java:240-261`), so an agent that names a branch which
already exists gets git's "branch already exists" and no way forward.

This design makes the choice explicit in the modal and possible over MCP.

> **Revision note.** Ten revisions across nine adversarial review
> rounds, three reviewers each
> (fact-check, mechanism, spec quality). Every confirmed finding is
> corrected in place; "What the adversarial review changed" at the end
> records them. Four defects in shipped code are fixed along the way — the
> remote-adoption shadow hole, the modal's absence of refname and shadow
> validation, `BranchNames` picking an arbitrary remote when several
> match, and `createWorktree` refunding a worktree that exists — and the
> ones this change does not touch are listed at the end.

## What "existing branch" means

A branch that exists **and is not currently checked out anywhere**. Git
enforces the second half — one branch, one worktree — and Drydock already
knows it: `BranchCatalog.merge` fills each local branch's occupancy from
`git worktree list`, carrying `prunable` and `locked` separately because
`git worktree prune` releases the first and silently skips the second
(`BranchCatalog.java:46-69`).

A branch that exists only as a remote-tracking ref counts as existing.
Checking it out runs `-b <local> --track <remote>`, which does mint a
local ref — but it is the ref that *represents* that branch locally, not a
fork off it, and it is what "open a worktree from `origin/feat/login`"
means to someone who has just fetched. Both surfaces treat it that way.

What is deliberately excluded is the fork: naming an existing branch and
getting a new branch based on it. That is the create path, and it stays
the create path.

## Minting is what needs guarding

`BranchNames.validate` refuses to mint a local branch whose first path
component matches a remote, because `refs/heads/origin/main` shadows
`refs/remotes/origin/main` for every short-name lookup — so a later
`git merge origin/main` silently targets the wrong commit.
`git worktree add` exits 0 and warns only on stderr. The class Javadoc
records that this was verified against real git (`BranchNames.java:9-29`).

Today that rule is enforced in exactly one place: `McpToolRouter.java:521`.
The modal never calls it. So the rule's reach is wrong in **both**
directions, and this design corrects both.

### Under-applied: the modal mints raw text

Type `origin/hotfix` in the modal with a remote called `origin`, and
`catalog.lookup` misses (no such remote ref yet), so Create runs
`-b origin/hotfix`. It shadows nothing that instant — it shadows
`refs/remotes/origin/hotfix` the moment anyone pushes, which is precisely
what the rule exists to stop. The modal's create path gets the same check
the MCP create path has had all along.

### Over-applied would be wrong: adopting a local branch mints nothing

`addWorktreeForBranchBlocking` emits `-b <localName> --track` **only for a
remote ref**; a local branch is checked out as
`git worktree add <dir> --end-of-options <name>` with no `-b` at all
(`GitStatusService.java:303-311`). So the shadow rule must not fire for a
free local branch, however it is named. Applying it there would refuse
`origin/main` with "pick a name that does not start with a remote name" —
advice that is meaningless for a branch that already exists, in a modal
with no rename — and would make the branch created by the bug below
impossible to clean up through Drydock.

### The remote-adoption hole, which is a live bug today

The one place a name is minted from something the user did not type is
remote adoption, and that path is unguarded:

- `listBranchesBlocking` names a remote-tracking ref by stripping
  `refs/remotes/` (`GitStatusService.java:562-564`), so a branch actually
  named `origin/main`, pushed to remote `origin`, is listed as
  `origin/origin/main`.
- `merge` keeps it, because no local branch is called `origin/main`
  (`BranchCatalog.java:76`).
- `localName` strips the longest matching remote prefix
  (`BranchCatalog.java:98-110`), yielding `origin/main`.
- `addWorktreeForBranchBlocking` runs
  `-b origin/main --track --end-of-options origin/origin/main`, creating
  exactly the shadowing ref `BranchNames` exists to prevent.

The modal reaches this today (`NewWorktreeModal.java:216-217` →
`MainWorkspace.java:1780-1784`). It is a shipped bug, not merely a gap in
this proposal, and it is closed here because this design would otherwise
hand it to unattended agents.

The derived name is also minted in *option position*: the command is
`-b <localName> --track --end-of-options <ref>`, so `--end-of-options`
protects the ref and not the name in front of it. Git permits
`refs/remotes/origin/-foo`, whose derived local name is `-foo`; the
resulting `git worktree add … -b -foo …` dies with `error: unknown switch
'o'` and a usage dump — verified against git 2.49. Harmless in outcome,
unreadable as an error, and it is a mint that a shadow-only check would
not catch.

**The rule, stated once:** a name is checked against git's full refname
rules when, and only when, it is about to be minted with `-b`. That is the
typed name in create mode, and the derived `localName` in remote adoption.
Never a local branch being checked out.

### `BranchNameRules`: the whole rule set, not just the shadow clause

`BranchNames.validate` throws in **thirteen** places
(`BranchNames.java:51-106`). Twelve of them judge the name and move to
`app.drydock.git.BranchNameRules`; the thirteenth — `remoteNames == null`
— is an argument precondition about the *caller*, not the name, and stays
in `validate` ahead of the delegation.

```java
public enum Clause {
    BLANK, REFS_PREFIX, SHADOWS_REMOTE, DOT_DOT, AT_BRACE, DOUBLE_SLASH,
    LEADING_DASH, TRAILING_SLASH_OR_DOT, FORBIDDEN_CHAR,
    EMPTY_COMPONENT, COMPONENT_LEADING_DOT, COMPONENT_LOCK_SUFFIX
}

/** `token` is the matched remote, the offending character, or the offending component. */
public record Refusal(Clause clause, String token) { }

/** Empty when `name` is safe to create as a local branch. */
public static Optional<Refusal> check(String name, Collection<String> remotes);

/** Today's MCP wording, reproduced word for word. */
public static String agentMessage(String name, Refusal refusal);

/** A standalone sentence, for the modal's hint line. */
public static String humanSentence(Refusal refusal);

/** A clause fragment that completes "…, which ": for dropdown rows and the unmintable hints. */
public static String shortClause(Refusal refusal);
```

`token` is `""` for the clauses that quote nothing (`BLANK`,
`REFS_PREFIX`, `DOT_DOT`, `AT_BRACE`, `DOUBLE_SLASH`, `LEADING_DASH`,
`TRAILING_SLASH_OR_DOT`), never null.

| clause | `humanSentence` | `shortClause` |
|---|---|---|
| `BLANK` | `Name the branch to create.` | `has no name` |
| `REFS_PREFIX` | `A branch name cannot start with 'refs/'.` | `starts with 'refs/'` |
| `SHADOWS_REMOTE` | `A branch named that would shadow the remote '<token>'.` | `shadows the remote '<token>'` |
| `DOT_DOT` | `A branch name cannot contain '..'.` | `contains '..'` |
| `AT_BRACE` | `A branch name cannot contain '@{'.` | `contains '@{'` |
| `DOUBLE_SLASH` | `A branch name cannot contain two slashes in a row.` | `contains '//'` |
| `LEADING_DASH` | `A branch name cannot start with '-'.` | `starts with '-'` |
| `TRAILING_SLASH_OR_DOT` | `A branch name cannot end with '/' or '.'.` | `ends with '/' or '.'` |
| `FORBIDDEN_CHAR` | `A branch name cannot contain the character '<token>'.` | `contains '<token>'` |
| `EMPTY_COMPONENT` | `A branch name cannot have an empty path component.` | `has an empty path component` |
| `COMPONENT_LEADING_DOT` | `A path component cannot start with '.' ('<token>').` | `has a component starting with '.'` |
| `COMPONENT_LOCK_SUFFIX` | `A path component cannot end with '.lock' ('<token>').` | `has a component ending with '.lock'` |

Four are unreachable from the modal and exist only for completeness of the
enum: `BLANK` and the `/` half of `TRAILING_SLASH_OR_DOT` are pre-empted by
the `unfinished` row, `FORBIDDEN_CHAR` with a space by the `space` row, and
`SHADOWS_REMOTE`'s sentence by `unmintable-new`'s own frame. The `space`
row's copy is therefore the *only* space message; it does not duplicate
`humanSentence(FORBIDDEN_CHAR, " ")`, which the modal never reaches.

One `Kind` for every refname clause would not work: three of today's
messages need both *which* clause failed and an offending fragment —
`"branch name must not contain '" + c + "': " + branch` (`:91-92`), and
the two component clauses that quote the **component** rather than the
whole name (`:100`, `:103`). Hence a per-clause enum plus one `token`.

**The evaluation order is normative**, and it is today's exactly:

1. `BLANK`, then `REFS_PREFIX`, then `SHADOWS_REMOTE` (`:53,59,63-69`).
2. The whole-name clauses in source order: `DOT_DOT`, `AT_BRACE`,
   `DOUBLE_SLASH`, `LEADING_DASH`, `TRAILING_SLASH_OR_DOT`,
   `FORBIDDEN_CHAR` (`:73-93`).
3. The component clauses **component-major**: for each component in order,
   `EMPTY_COMPONENT` → `COMPONENT_LEADING_DOT` → `COMPONENT_LOCK_SUFFIX`,
   before moving to the next component (`:95-105`).

Step 3 is the subtle one, and a flat twelve-clause ladder gets it wrong:
`foo.lock/.bar` reports the `.lock` refusal today, because the first
component is judged completely before the second is looked at; a flat
ladder that asks "does any component start with `.`" first would report
`.bar`. `FORBIDDEN_CHAR` has the same shape — the scan is leftmost
character **in the name** (`:88-93`), so `a^b~c` reports `^`, not
whichever character comes first in `FORBIDDEN_CHARS`. Where several
occurrences match, `token` carries the first one encountered by that
order.

The order has to be pinned because a name can violate two clauses and
`BranchNamesTest` would not notice a flip: it has eleven tests and exactly
one `contains` assertion, the rest being bare `assertThrows`.
`BranchNameRulesTest` pins it with genuinely double-violating names —
`origin/a..b` and `refs/heads/a..b` across groups, `foo.lock/.bar` and
`a^b~c` within them.

`BranchNames.validate` becomes a thin wrapper: null-remotes guard, then
`check`, then `throw new McpToolException(agentMessage(name, refusal))`.
Its messages are unchanged, so `BranchNamesTest` needs no edit and the MCP
error surface is untouched. Only the judging moves — case-insensitivity
and "a remote name may itself contain a slash" travel with it
(`BranchNames.java:17-29`). `app.drydock.git` gains no dependency on
`app.drydock.mcp`; the direction is the other way.

`humanSentence` is the modal's copy, and it lives beside the rules rather
than in the modal so the twelve clauses have one home — the same
arrangement `dropdownLabel` already uses. Its wording drops the
git-shaped `"branch name must not …: <name>"` frame for a sentence:
`A branch name cannot contain '..'.`, `A branch name cannot contain the
character '~'.`, `A branch name cannot start with '-'.`, and so on, one per
clause, with `SHADOWS_REMOTE` rendered by `unmintable-new`'s own frame (below), which needs the
typed name as well.

When two remotes both match, the **longest** wins, matching
`BranchCatalog.localName`'s documented rule (`BranchCatalog.java:87-93`)
rather than `BranchNames`' current first-match scan over a `Set` whose
iteration order is salted per JVM run. That is a bug fix in passing; the
message text is unchanged, only which remote it names when several apply.

## One classifier, two audiences: `BranchCheckout`

Resolution moves into `app.drydock.git.BranchCheckout`, and both surfaces
switch on its outcome:

```java
public sealed interface Outcome {
    record Ready(BranchRef ref, String localName, Optional<String> tracking) implements Outcome { }
    record NoSuchBranch(String text)                                         implements Outcome { }
    record Occupied(BranchRef ref)                                           implements Outcome { }
    record Unmintable(BranchRef ref, String localName,
                      BranchNameRules.Refusal refusal)                       implements Outcome { }
}

/**
 * Resolves picker/argument text against the catalog. `catalog` must be
 * non-null. Null or blank `text` yields NoSuchBranch(""); `text` is
 * stripped before lookup and NoSuchBranch carries the stripped form, so
 * the hint never renders surrounding whitespace.
 */
public static Outcome resolve(BranchCatalog catalog, String text);

/**
 * The dropdown row's full text: the name, plus why it cannot be picked.
 * Pass the row's own verdict from {@link #unmintable}; empty for every
 * row that is fine.
 */
public static String dropdownLabel(BranchRef branch, Optional<Unmintable> unmintable);

/** Whether adopting `ref` would mint a name git or Drydock must refuse. */
public static Optional<Unmintable> unmintable(BranchCatalog catalog, BranchRef ref);
```

`dropdownLabel` takes the whole `Unmintable` because it needs the derived
`localName` as well as the refusal, and `BranchRef` cannot yield one
without the remotes list. `unmintable` is the per-**row** verdict: the
cell factory renders every branch in the list while `resolve` answers only
for the editor's text, so the factory calls `unmintable(catalog, row)`
itself and uses the result for both the label and `setDisable`. In piece 1
the factory passes `Optional.empty()` and behaviour is unchanged; piece 3
wires the real call.

All members are `public` — `app.drydock.ui` and `app.drydock.mcp` both
call them.

`Ready.tracking` is `ref.name()` for a remote-tracking ref and empty for a
local one; remotes come from `catalog.remotes()`. `Unmintable` arises only
from remote adoption, where a `localName` is derived and minted — by the
rule above, a local branch being checked out is never name-checked. Since
a remote ref is never itself occupied (`BranchRef.remote` hardcodes an
empty `checkedOutAt`, `BranchRef.java:36-38`, and `merge` re-wraps only
locals, `BranchCatalog.java:65-69,77`), **`Occupied` and `Unmintable` are
mutually exclusive by construction** and need no precedence rule.

`dropdownLabel` reproduces `BranchRefConverter.describe`'s output exactly:
the bare name for an available branch, and
`name + "  —  " + why + " (" + holder + ")"` for an occupied one, with
`why` one of "locked worktree", "stale worktree", "in use". `describe` is
**deleted**, not kept as a delegate; the cell factory
(`NewWorktreeModal.java:152`) and the converter's Javadoc link change to
`dropdownLabel`.

A remote ref whose derived name is unmintable is `available()`, so it
would otherwise sit enabled in the list and disable Create only after
being picked. The cell factory therefore disables it too, and
`dropdownLabel` appends
`"  —  would create " + localName + ", which " + BranchNameRules.shortClause(refusal)`,
so the dropdown and the hint agree.

### Wording is per-audience, deliberately

The locked / prunable / in-use **classification** lives once, in
`BranchRef` plus `Outcome`. The **sentences** do not, because the two
audiences can act on different things:

- The modal says "run `git worktree unlock` to release it" — a human can.
- MCP says what `translate` already says for the same condition: "the
  worktree at X is locked; the human can unlock it from the UI"
  (`WorkspaceMcpSessionContext.java:636-639`). Telling an agent to run
  `git worktree prune`, a repo-wide mutation, would contradict shipped
  doctrine.

Likewise `NoSuchBranch`: the modal says `No branch named 'nope'.`, and MCP
adds the argument advice the modal has no equivalent of ("omit `existing`
to create it"). One utility that returned one string could not do this;
AGENTS.md's no-per-view-copies rule (`AGENTS.md:83-85`) is satisfied by
the single classifier, not by a single sentence.

`dropdownLabel` is the exception that *is* shared verbatim: it replaces
`BranchRefConverter.describe` (`BranchRefConverter.java:36-52`) and keeps
its exact output — the bare name for an available branch, and
`name + "  —  " + why + " (" + holder + ")"` otherwise.

## The modal: an explicit mode switch

A two-segment switch — **New branch** / **Existing branch** — sits
directly under the modal title, above the branch row. `app.css` already
carries `.seg-toggle` and `.seg-toggle-button` rules that nothing in
`main` references (`app.css:944-962`); this revives them rather than
adding a third toggle look beside `.agent-toggle`.

### The segments are `RadioButton`s, not `ToggleButton`s

`ToggleButton.fire()` toggles unconditionally — disassembling the JavaFX
26 jar the build resolves shows `isSelected()` negated straight into
`setSelected()`, with no guard. The `getToggleGroup() == null ||
!isSelected()` guard is on **`RadioButton.fire()`**. So a two-`ToggleButton`
group can be clicked into `selectedToggle == null`, i.e. into no mode at
all. `AgentSelector.java:56-76` uses `ToggleButton` and survives only
because it keeps the value in a field and never reads the group's
selection — a latent bug there, out of scope here.

`SettingsModal.themeRow` already uses `RadioButton` + `ToggleGroup` for
the same shape, and its comment relies on exactly this guarantee: "the
group always has one toggle selected" (`SettingsModal.java:128-146`). So
this switch follows that precedent. Taking `ToggleButton` plus a
`selectedToggleProperty` listener that re-selects on null would also work;
`RadioButton` is chosen because the guarantee comes from JavaFX rather
than from a listener someone must remember not to delete. `:selected` is
inherited from `ToggleButton`, so the revived `.seg-toggle-button:selected`
rule applies unchanged.

The CSS cost is more than one rule, and worth stating plainly. Modena
paints `.radio-button > .radio` with the full button background stack
(`-fx-shadow-highlight-color, -fx-outer-border, -fx-inner-border,
-fx-body-color`), a 1em radius and 0.333em padding, and adds
`-fx-label-padding` — so hiding only `.dot` leaves a circular button
sitting beside the label. `app.css` already needed four rules just to
recolour that control for settings (`:2116-2130`), and there is no
hide-the-dot precedent to reuse. This adds, scoped under
`.seg-toggle-button`: `> .radio { -fx-background-color: transparent;
-fx-border-color: transparent; -fx-padding: 0; -fx-background-radius: 0; }`
and `-fx-label-padding: 0`. Modena's only focus indicator for a
`RadioButton` is `.radio-button:focused > .radio`, which that neutralises,
and `.seg-toggle-button` defines none (`app.css:944-962`) — so a
`:focused` ring is added too. The switch is keyboard-operable, so it needs
one.

The modal opens in **New branch** mode, so the seeded `feat/` and existing
muscle memory survive unchanged. `Mode` is a two-constant enum nested in
`NewWorktreeState`, shared by the record, `CreateHandler` and
`MainWorkspace`. It lives in a field on the modal, written from four
places — the two segment handlers, `⌘E`, and **Check it out instead** —
each of which goes through one private `setMode(Mode)`. That method does
four things **in this order**: writes the field; sets the
segment's selection so the two can never diverge; **swaps the two branch
controls' `visible`/`managed`** and moves focus to the newly active one;
and ends in `refreshState()`.

The order is not arbitrary. `Scene.requestFocus` silently does nothing
unless the target `isTreeVisible()`, so focusing before the swap is a
no-op — which is why the swap belongs to `setMode` rather than to
`refreshState()`, even though every *other* visibility swap in this modal
(`baseGroup`, `hintLine`) is a `refreshState()` product and stays one.

Focus is `setMode`'s duty rather than the `⌘E` handler's because the offer
hides itself as a consequence of being pressed: its handler ends in
`setMode(EXISTING)`, whose `refreshState()` then computes
`switchOffer == false`. A hidden `Button` does not keep focus — the scene
notices it can no longer receive focus and traverses to whatever is next —
so without an explicit move, a keyboard user who activates the offer with
Space lands somewhere arbitrary. The last part is not decoration: `mode` is
the only input to `derive` with no listener behind it — `catalog` refreshes
through `applyCatalog`/`applyCatalogFailure`, `creatingInFlight` through
`showCreating`/`showError`, and the three text fields through their own
listeners — and the directory re-derivation that might have refreshed
incidentally is skipped while the new control is blank and never runs at
all once the directory has been hand-edited
(`NewWorktreeModal.java:88-89,166-171`). Without the explicit call, `⌘E`
after a manual directory edit would leave the hint, the preview, **Fork
from**'s visibility and the button state on the old mode — and Create, fed
from the last computed state, would run the old mode's action.

### Two pickers, swapped — never one control reconfigured

Toggling `ComboBox.editable` at runtime makes the skin swap its editor out
from under any bound property. That is the same class of trap already
documented in the modal, where setting `promptText` on the editor rather
than the `ComboBox` throws because the skin binds one to the other
(`NewWorktreeModal.java:159-165`). So the two modes get two controls,
swapped by `visible`/`managed` — a swap `setMode` performs, for the
focus-ordering reason above:

- **New branch**: a plain `TextField`, seeded `feat/`, plus the existing
  **Fork from** combo.
- **Existing branch**: the `ComboBox<BranchRef>` as it is today —
  editable, so a repository with hundreds of branches is still typeable;
  every branch listed; occupied ones disabled and labelled by
  `BranchCheckout.dropdownLabel`. **Fork from** is hidden.

The ⟳ refresh button stays visible in **both** modes: New-branch mode
depends on a fresh catalog just as much, because that is what makes the
collision warning correct. Initial focus is the New-branch `TextField`,
caret at the end, exactly as the constructor does today
(`NewWorktreeModal.java:237-240`). The Create button reads "Create
worktree" in both modes: the literal appears at `NewWorktreeModal.java:70`
(field initialiser) and `:435` (`showError`'s restore) and nowhere else, so
a mode-dependent label would silently revert after any failure.

Each control keeps its own text, so flipping back and forth does not
clobber what was typed in the other. Occupied branches stay in the list
rather than being filtered out: a user hunting for a branch they know
exists must be told where it went, not shown a shorter list.

### Only the active control drives derivation

`deriveDirectory`, `refreshState` and `onRefresh` read the **active
mode's** control. `onRefresh` is the easy one to miss: it calls
`branchText()` twice, before and after the reload
(`NewWorktreeModal.java:289,313`), and left alone it would compare the
*hidden* picker's leftover text and warn about a branch the user is not
naming.

Mode-awareness alone does not finish the job, because the two reads are
separated by a fetch and the mode can change in between — nothing gates
`⌘E` on `refreshInFlight`, only on `creatingInFlight`. So `onRefresh`
captures the mode alongside `matchedBefore` and says nothing at all if it
changed by the time the reload lands. Otherwise a ⟳ started in Existing
mode and finished in New would warn about a branch the user has stopped
naming, and the mirror case would swallow a genuine pruning.
This is not cosmetic. `applyCatalog` does `items.setAll(…)` and repairs
the editor on a later pulse, because the skin nulls a value that is not
among the new items and re-syncs the editor from that null
(`NewWorktreeModal.java:335-351`). Both the wipe and the repair fire the
combo editor's text listener, which today calls `deriveDirectory`
regardless of mode (`:166-171`, guarded only by `directoryManuallyEdited`).

Concretely, with one shared listener: select `feat/login` in Existing
mode, `⌘E` back to New, type `feat/other`, press ⟳; if `feat/login` was
checked out elsewhere meanwhile, its reloaded `BranchRef` is no longer
`equals` the selected value (the occupancy fields differ), the value is
cleared, the editor is wiped, the listener fires with `""`, and the
**visible** directory — derived from the New-mode `TextField` — becomes
`<repo>-worktree`. The repair one pulse later restores the combo's text
but not necessarily the directory. So the inactive control's listener must
not derive.

Re-derivation is also **skipped while the newly active control is blank**:
`WorktreeNaming.slug("")` returns `"worktree"` (`WorktreeNaming.java:24-34`),
so typing `feat/login`, then `⌘E` into an untouched Existing picker, would
rewrite `…/drydock-login` to `…/drydock-worktree` and back on the way out.
The "unless manually edited" rule (`NewWorktreeModal.java:88-89`) is
unchanged. In existing mode the directory derives from
`Ready.localName()`, so `origin/feat/login` and `feat/login` propose the
same path.

### `derive` computes two things independently

`NewWorktreeState.derive` gains a `Mode` parameter, loses `branchLabel`
(the switch says that now; the branch row's label becomes a plain
"Branch"), and gains two components: `switchOffer`, and the whole
`Outcome` — not `Optional<Ready>`. Carrying only `Ready` would be a lossy
projection: Existing mode has to tell `NoSuchBranch`, `Occupied` and
`Unmintable` apart, since each gets its own row and its own sentence, and
an `Optional<Ready>` collapses all three into "empty" — one disabled
button with three different explanations owed. `baseVisible` collapses to `mode == NEW`.

**The hint and the disabled state are separate computations, not one
ladder.** Today `hint` is derived from the branch alone, while
`dir.isEmpty()` and `creatingInFlight` feed only `blocked`
(`NewWorktreeState.java:64-70,75`). Collapsing them into a single
first-match table would delete shipped behaviour: clear the directory
field while an occupied branch is selected and the "Already checked out
in …" hint would vanish, leaving a disabled button explaining nothing.

**`hint`**, first match wins. Rows are named, not numbered: two rounds of
review lost time to off-by-one references after a row was inserted or
deleted, and a name survives that. Branch names shown are the **catalog's**
spelling where one resolved and the typed text otherwise; `/x` is
`checkedOutAt()`.

| row | mode | condition | hint |
|---|---|---|---|
| `loading` | either | `catalog == null && !catalogFailed` | `Loading branches…` |
| `load-failed` | either | `catalog == null && catalogFailed` | — (the error line speaks) |
| `unfinished` | NEW | name blank, or ends `/` | `Finish the branch name.` |
| `space` | NEW | name contains a space | `A branch name cannot contain a space.` |
| `exists-free` | NEW | `localBranch(name)` present, available | `feat/login already exists.` |
| `exists-busy` | NEW | `localBranch(name)` present, occupied | `feat/login already exists — checked out in /x.` |
| `unmintable-new` | NEW | `BranchNameRules.check(name, remotes)` refuses | the refusal, phrased for a human (below) |
| `no-base` | NEW | **Fork from** blank | `Pick a branch to fork from.` |
| `pick-branch` | EXISTING | branch blank | `Pick a branch to check out.` |
| `no-such` | EXISTING | `NoSuchBranch` | `No branch named 'nope'.` |
| `occupied` | EXISTING | `Occupied` | today's `blockedHint` wording, unchanged |
| `unmintable-existing` | EXISTING | `Unmintable` | `Checking out origin/origin/main would create local origin/main, which ` + `shortClause` + `.` |
| `none` | otherwise | | no hint |

**Both catalog-absent rows are needed, and `load-failed` must sit second.**
`applyCatalogFailure` sets `catalogFailed` and never assigns `catalog`
(`NewWorktreeModal.java:373-379`), so a failed **first** load leaves
`catalog == null && catalogFailed` — which today produces an empty hint,
because the shipped condition is `catalog == null && !catalogFailed`
(`NewWorktreeState.java:64-65`), with a comment at `:375-376` recording
that a failed first load must not keep claiming the branches are loading.
Keying `loading` on `catalog == null` alone would say "Loading branches…"
for ever beside an error line saying the listing failed.

Narrowing `loading` without adding `load-failed` is equally wrong: the
state would fall through to `exists-free` and `unmintable-new`, which
dereference a null catalog. Two rows, in this order.

A load failure with a catalog *already in hand* is deliberately **not** a
row. After a successful load and a failed ⟳ the modal still holds a usable
catalog and still renders its occupancy hint (`NewWorktreeState.java:64-68`);
a first-match row above `occupied` would blank that and leave a disabled
button explaining nothing — the defect `no-base` and `pick-branch` exist to
remove.

**`createDisabled`** is `!hint().isEmpty() || catalog == null ||
catalogFailed || directory blank || creation in flight`. Every row above
`none` yields a non-empty hint except `load-failed`, which the second and
third terms cover. The rules carried forward from today are
`NewWorktreeState.java:70-72` (directory, name shape, base) and `:75`
(in flight); `unfinished`, `space` and `no-base` are those rules made
audible instead of blocking silently. `none` means "no hint" — it says
nothing about the button, which the trailing terms still govern.

`unfinished` says something rather than nothing because that is the modal's
**opening** state: the branch field is seeded `feat/`
(`NewWorktreeModal.java:156`), which ends in `/`. Silence there would mean
a filled-in form, a dead button and a blank hint line. It also keeps
`unmintable-new` off a half-typed name, which matters in a repository with
a remote literally named `feat`, since `BranchNameRules` matches whole path
components case-insensitively.

`unmintable-new`'s wording is
`A branch named <typed> would shadow the remote '<remote>'.` for
`SHADOWS_REMOTE`, `<remote>` being the longest match, and
`BranchNameRules.humanSentence` for every other clause.

While `catalog == null`, `derive` does not call `resolve` at all — its
contract requires a non-null catalog — and the record's `Outcome`
component is `NoSuchBranch(text)`. Both catalog-absent rows precede every
hint row that reads it, and the three non-row readers all tolerate it:
`switchOffer` is false (not `Ready`), the preview is empty, and the
directory derivation falls back to the raw branch text exactly as the
shipped code does, repairing itself when `applyCatalog` arrives
(`NewWorktreeModal.java:79-86,362-364`).

### New mode only warns about *local* collisions

`exists-free` and `exists-busy` test for an exact local branch, not for any `Outcome`, and that
is load-bearing twice over. `BranchCatalog` gains the named accessor they
need:

```java
/** The local branch of exactly this name, if there is one. Text is stripped first. */
public Optional<BranchRef> localBranch(String name);
```

— i.e. `branches().stream().filter(b -> !b.remote()).filter(b -> b.name().equals(name.strip()))`,
deliberately **not** `lookup`.

*Because `lookup` qualifies bare names by remote.* Its fourth pass tries
`<remote>/<typed>` (`BranchCatalog.java:152-161`), so `login` resolves to
`origin/login` when no local `login` exists. Keyed on the raw `Outcome`,
New-branch mode would refuse to create a local `login` at all — offering
only "check it out" — while `worktree_create {branch: "login"}` runs
`-b login` happily, because `BranchNames.validate` sees no shadow.
Creating a local branch that a remote already has a ref for is ordinary
git; the modal must not be stricter than the tool. So a remote-only match
produces no hint and leaves Create enabled.

*And because `lookup` strips remote prefixes.* Its third pass maps
`origin/main` onto local `main` when the remote ref was dropped as
shadowed (`:140-151`, `:76`) — the ordinary case for any branch that
exists both locally and on origin. Keyed on `Outcome`, typing
`origin/main` would show `unmintable-new`'s *"A branch named origin/main would shadow
the remote 'origin'"* beside a **Check it out instead** button that
silently switches to `main`, a name the hint never mentions. With the
exact test, `exists-free`/`exists-busy` stay quiet and `unmintable-new`
fires; the offer still appears, naming `main`, which is what makes the
warning actionable.

### The offer names its target instead of hiding when it differs

`switchOffer` is simply

```java
mode == NEW && outcome instanceof Ready
```

and the **button says which branch it will switch to**: `Check it out
instead` when `ref.name().equals(text.strip())`, and
`Check out <ref.name()> instead` otherwise. The comparison is against the
stripped text, like every other comparison here, so a trailing space does
not make the button name the branch the user just typed.

Suppressing the offer when the names differ — the obvious alternative —
does not work, because `lookup` resolves four ways and only two of them
produce a matching name:

| typed | catalog | resolves to | offer label |
|---|---|---|---|
| `feat/login` | local `feat/login`, free | pass 1, `feat/login` | `Check it out instead` |
| `feat/login` | local `feat/login`, occupied | `Occupied`, not `Ready` | *none* |
| `origin/feat/login` | remote ref of that name | pass 2, `origin/feat/login` | `Check it out instead` |
| `origin/main` | local `main`; remote ref dropped | pass 3, `main` | `Check out main instead` |
| `feat/login` | only remote `origin/feat/login` | pass 4, `origin/feat/login` | `Check out origin/feat/login instead` |

The last row is the one that matters most: it is the ordinary
fetch-then-check-out flow, and it is what today's derived mode handles. A
name-must-match rule drops the offer there, leaving no hint, an enabled
Create, and `-b feat/login <base>` — a fresh branch off the fork point
rather than the remote's history, silently diverging from what the user
asked for. Rows three and five also show that a matching name only ever
arises from passes 1 and 2, both of which already produce a hint, so a
name-must-match offer could never appear beside an enabled Create at all.

Round 5's defect — the button switching to a branch the hint never
mentions — is closed by the label, not by suppression. In the
`origin/main` row the hint warns about `origin/main` and the button says
`Check out main instead`; the two can no longer disagree, because the
button states its own target.

An occupied local branch yields `Occupied` rather than `Ready`, so no
offer: checking it out is exactly what cannot happen.

`Pick a branch to fork from.` is new wording for a dead end that already
ships: `baseField` is filled asynchronously from `getStatus` and only when
the repo is `OnBranch` (`NewWorktreeModal.java:118-123`), so on a detached
HEAD, an unborn branch, or a failed status call, Create is disabled today
with nothing on screen explaining it.

The offer is a separate `Button`, shown when `switchOffer` is true, beside
the hint label, with a new `.worktree-hint-action` rule in `app.css` —
flat, link-coloured, no background, `-fx-font-size: 11.5px` to match
`.worktree-hint` (`app.css:1426`). It is shown for every `Ready`, whether
Create is enabled or not.

### The offer hands over the ref, not its name

The button calls `branchField.setValue(ref)` — the `BranchRef` itself, not
its name — and then `setMode(EXISTING)`. Handing over a string would pick
the wrong branch, because a name does not identify a ref uniquely and
`lookup` resolves the two namespaces in a different order than the offer
did.

The case is not hypothetical: a local branch named `origin/foo` is exactly
what the shadow bug above creates, and `merge` keeps a remote ref whose
*stripped* name has no local counterpart (`BranchCatalog.java:76`), so
local `origin/foo` and remote-tracking `origin/foo` coexist. Typing `foo`
resolves through pass 4 to the **remote** ref; re-resolving the string
`origin/foo` in Existing mode hits pass 1 — local-exact first
(`:130-134`) — and lands on the local typo branch. Create would then run
`git worktree add <dir> origin/foo` against stale commits with no
tracking, and nothing on screen would say so: `dropdownLabel` renders both
rows as the bare name and `slug` derives the same directory either way.

So Existing mode uses the picker's selected value as a **disambiguator**,
never as an authority:

> When `branchField.getValue()` is non-null, its `name()` equals the
> stripped editor text, **and the catalog still holds a ref with the same
> `(name(), remote())` pair**, resolve from *the catalog's* instance of
> that ref. Otherwise resolve from the text.

Every clause of that is load-bearing, because a `ComboBox`'s value is not
a catalog ref and is not kept fresh:

- **ENTER fabricates one.** `ComboBoxPopupControl.handleKeyEvent` commits
  on ENTER via `setTextFromTextFieldIntoComboBoxValue()`, which is
  `setValue(converter.fromString(text))`, and then rewrites the editor
  from that value. `BranchRefConverter.fromString` returns
  `BranchRef.local(name)` for arbitrary text — always local, never
  occupied — and its Javadoc says so outright: "the catalog lookup — not
  this converter — decides what it means" (`BranchRefConverter.java:28-33`).
  So after ENTER the name always matches, by construction. Without the
  catalog clause, typing an occupied branch and pressing Enter would
  resolve `Ready` instead of `Occupied` and enable Create; an unknown name
  would resolve `Ready` instead of `NoSuchBranch`.
- **A reload does not invalidate a stale selection.**
  `ComboBoxSelectionModel`'s `setAll` recovery scans for an `equals` match
  and, finding none, **does nothing** — the value keeps pointing at the
  old snapshot. `BranchRef` is a record, so a branch that got checked out
  elsewhere between loads is no longer `equals` its replacement. Pick a
  free branch, let it be taken, press ⟳: without the catalog clause the
  modal would keep the free snapshot and stay silent where today's
  text-resolution flips to the occupancy hint.

Resolving from the catalog's instance rather than the picker's is what
makes both cases self-correcting. The rule is not just for the offer — it
makes picking a duplicate-named row from the dropdown unambiguous too.
After an ENTER commit the fabricated value is local, so `(name, remote)`
finds the *local* ref and the text's own meaning wins; that is the right
answer, because the user has just re-committed the text.

### The plumbing this needs

`derive` gains a nullable `BranchRef selected` alongside `Mode`, and
`BranchCheckout` gains the ref-shaped entry point it otherwise lacks:

```java
/** As resolve(catalog, text), for a ref already identified — occupancy and mintability only. */
public static Outcome resolve(BranchCatalog catalog, BranchRef ref);
```

Without both, the rule cannot be implemented from the API this document
declares, and the path of least resistance —
`resolve(catalog, selected.name())` — compiles, reads like the rule, and
walks straight back into the local-exact-first defect the section exists
to close.

The mirror-image offer is deliberately **not** built: `NoSuchBranch` in
Existing mode says so and disables Create, with no "create it instead"
button. One directed shortcut earns its place because it rescues the flow
the derived mode used to give for free; the reverse never existed.

`refreshState()` remains the single writer of `createButton.setDisable`,
for the reason its own comment gives (`NewWorktreeModal.java:402-408`),
and it gains three more subjects: the two segments and **Check it out
instead** are disabled while `creatingInFlight`. Without that, `⌘E` during
a creation re-derives the directory, the creation fails, `showError`
re-enables Create (`:433-437`), and the user's second click targets a
*different* directory than the failure message names.

### The preview branches on mode, not on the lookup

`derive` currently picks the preview from `existing.isPresent()`
(`NewWorktreeState.java:49-58`). Left alone, that shows the wrong command
on exactly the states the switch introduces: a NEW-mode collision would
preview the checkout form, and an unresolvable EXISTING name would preview
`-b nope` — the create command that mode refuses. The preview's lockstep
contract (`NewWorktreeState.java:17-24`) is with *what Create would run*,
so it branches on mode:

```
NEW                        $ git worktree add <dir> -b <branch> [<base>]
EXISTING, Ready, local     $ git worktree add <dir> <name>
EXISTING, Ready, remote    $ git worktree add <dir> -b <local> --track <remote>
EXISTING, not Ready        empty
```

`--end-of-options` stays omitted for the reason already recorded there:
git forbids refs starting with `-`, so it can never change the meaning of
anything typeable here, and previewing it would only obscure the command a
reader is meant to recognise.

### The refresh warning becomes mode-aware

`onRefresh` currently says "That branch no longer exists on the remote —
Create would now make a new one." when `--prune` removes the selected ref
(`NewWorktreeModal.java:312-316`). Under an explicit mode that sentence is
wrong in **both** modes, not just one. Existing mode disables Create, so
it will not "make a new one"; and New mode always makes a new one, so
nothing about it is *now* true — under the old derived mode the sentence
described a real flip, and there is no flip any more. Existing reads
"That branch no longer exists on the remote — pick another." New reads
"That branch no longer exists on the remote." and stops, because what
Create would do has not changed.

### ⌘E flips the mode

`⌘E` flips the switch — it acts on the mode, not on the active control —
installed as a `KEY_PRESSED` filter on the modal itself, routed through
`setMode` and gated on `creatingInFlight` like every other control. The
chord is free: the global scene filter handles
`⌘N ⌘R ⌘1-4 ⌘0 ⌘[ ⌘] ⌘↑ ⌘↓ ⌘F ⌘⇧L ⌘,`, Esc and `⇧/`
(`DrydockApplication.java:809-916`), no `KeyCode.E` appears anywhere in
`app/src/main`, and it is not a JavaFX Mac text-editing binding — so it
works with the caret in the branch editor, unlike `⌥←/→`, `⌘←/→` and
`⌘A/C/V/X/Z`.

Reusing `⌘1`/`⌘2` was rejected: scene-level filters fire during capture,
root-down, so the global handler sees those keys before any modal handler
can. Only Esc (`:818`), `⌘⇧L` (`:844`) and `⌘,` (`:906`) consult
`isShowingModal()`; making `⌘1`/`⌘2` work inside the modal would mean
gating the view-switch keys too.

Focus follows the mode. Two honest limits: the filter does not fire while
the `ComboBox` popup is open, because popup key events route through the
popup's own window; and `⇧/` cannot be used to *read* the shortcut while
the modal is up, because it is gated on `!inTextInput` (`:910-913`) and
`ModalLayer.show` does `getChildren().setAll(modal)` (`ModalLayer.java:67`),
replacing the half-filled modal rather than layering. So the chord is also
a `Tooltip` on the switch, discoverable in place, in addition to the
overlay section AGENTS.md requires (`AGENTS.md:86-88`; the overlay already
carries context sections at `ShortcutsOverlay.java:43,63`):

```
IN THE NEW-WORKTREE MODAL
  Switch new / existing branch      ⌘E
```

### Mode reaches the create call; the press-time lookup goes

`CreateHandler.create` currently takes `Optional<BranchRef> existing`,
recomputed by `catalog.lookup(branchText())` at button-press time
(`NewWorktreeModal.java:216`), and `MainWorkspace` branches on it
(`:1779-1784`). With an explicit switch that press-time lookup is a
**second, hidden mode oracle**: an Existing-mode Create whose text was
invalidated by a ⟳ refresh would fall through to `-b`.

So the handler becomes

```java
void create(Mode mode, Outcome outcome, String branch, String base,
            Path directory, Optional<String> task, AgentKind agent);
```

fed from the `NewWorktreeState` that `refreshState()` last computed —
held in a field, or recomputed from the same inputs at press time, which is
equivalent and not a second oracle. Every wipe of either editor fires its text
listener into `refreshState()` — including `applyCatalog`'s `items.setAll`
wipe and its later repair (`NewWorktreeModal.java:335-351`) — and
`refreshState()` runs once before the modal is shown (`:236`), so the
field is never null and never staler than what is on screen.

`MainWorkspace` branches on `mode` alone. In NEW mode `branch` is the
typed text and `outcome` is ignored; in EXISTING mode `branch` is
`((Ready) outcome).localName()`, which is both the `localName` argument
`addWorktreeForBranch` wants (`GitStatusService.java:289-296`) and the
label handed to `openNewWorktreeSession` — so a worktree adopted as
`origin/feat/login` is tabbed `feat/login`. Create is only enabled in
Existing mode when the outcome is `Ready`, so the cast cannot fail.

`branchCreatedHere` becomes `mode == NEW` — the same value
`existing.isEmpty()` yields today (`MainWorkspace.java:1791`), now read
from the authority instead of inferred. Adopting a remote-only branch does
mint a local ref, but that ref tracks a remote somebody else owns;
removing the worktree must not offer to delete it.

## MCP: `worktree_create` gains `existing`

A boolean argument, default `false`. The default path is untouched, so
every current caller behaves exactly as today.

```
worktree_create { branch: "feat/login" }
  -> git worktree add <dir> -b feat/login [--end-of-options <start_point>]

worktree_create { branch: "feat/login", existing: true }
  -> git worktree add <dir> --end-of-options feat/login
  -> or, for a remote-only branch:
     git worktree add <dir> -b feat/login --track --end-of-options origin/feat/login
```

The flag is explicit rather than auto-detected. The modal can derive its
mode because a human sees the result before pressing Create; an agent
cannot. An agent that meant "new branch" and hit a name collision would,
under auto-detection, silently inherit someone else's commits and carry on
working in them.

### The descriptor, verbatim

Without this the whole MCP half is inert — a model cannot emit a property
that is not in the schema, and today's description says "Creates a **new**
worktree" (`McpToolRouter.java:134-140`). `schemaBoolean` already exists
(`:810`).

```java
descriptor("worktree_create",
        "Creates a worktree in the caller's repository: a new branch by default, or a "
                + "checkout of a branch that already exists when 'existing' is true. An existing "
                + "branch must not be checked out in another worktree.",
        JsonObject.empty()
                .put("branch", schemaString("Branch name. With existing=true, names a branch that "
                        + "already exists -- local, or remote-tracking, which is adopted as a local "
                        + "tracking branch."))
                .put("existing", schemaBoolean("Check out an existing branch instead of creating one. "
                        + "Defaults to false. Cannot be combined with start_point."))
                .put("start_point", schemaString("Optional start point (commit-ish) for the new "
                        + "branch. Only valid when existing is false.")),
        "branch"),
```

No existing test would catch a missing `existing`. `McpServerTest`'s only
tools/list assertion is a `contains()` loop over tool *names*
(`McpServerTest.java:217-226`) — `"existing"` would pass on the
descriptor's own prose — and `McpToolRouterReadTest`'s descriptor test
pins `worktree_create → List.of("branch")`, i.e. *required* arguments
only (`:89-119`), so an optional property is invisible to it. A new case
in `McpToolRouterReadTest` asserts `existing` inside `worktree_create`'s
`properties` object, with type `boolean`.

### `existing` is parsed strictly

`optionalBooleanArg` returns the default for **any** non-`JsonBoolean`
value (`McpToolRouter.java:758-766`). The same file documents that some
clients stringify every argument — that is why `optionalIntArg` exists
(`:186-192`) — and `optionalStringArg` deliberately rejects a wrong-typed
argument rather than treating it as absent (`:736-755`).

Reusing `optionalBooleanArg` would reintroduce the exact failure the
explicit flag prevents: `{"branch":"feat/login","existing":"true"}`
coerces to `false` and git creates a **brand-new branch off the caller's
HEAD** with none of the branch's commits, reported indistinguishably from
adoption. So `existing` is read by a strict reader that refuses a
non-boolean with `Argument 'existing' must be a boolean (true or false).`
The two other `optionalBooleanArg` call sites (`:312`, `:422`) are out of
scope; the helper's laxity is worth its own fix.

### Validation is asymmetric, deliberately

`existing: false` keeps `BranchNames.validate` unchanged. `existing: true`
does not call it: its remote-shadow rule would reject `origin/feat/login`,
a legitimate way to name a branch that exists, and its refname rules vet a
name being minted, not a lookup key.

What replaces it is `BranchCheckout.resolve`, which returns `NoSuchBranch`
for anything the catalog does not know — so the only strings reaching git
are refs git itself listed — plus the `Unmintable` guard on the derived
name, which `BranchNames` alone would have missed. Blank is still refused
by `requiredStringArg`. `registry.maySpawn(caller)` still gates the whole
tool, before either branch (`McpToolRouter.java:512-515`).

`start_point` alongside `existing: true` is refused **before**
`chargeWorktree`, so nothing is charged and nothing is refunded. A blank
`start_point` is already absent by then — `optionalStringArg` maps blank
to empty (`:749-754`) — so it does not conflict.

### The new SPI method

On `McpSessionContext`, beside `createWorktree`:

```java
record ExistingBranchWorktree(Path path, String branch, Optional<String> tracking) { }

ExistingBranchWorktree createWorktreeOnExistingBranch(ManagedSessionId caller, String branch)
        throws McpToolException;
```

`WorkspaceMcpSessionContext` implements it next to `createWorktree`
(`WorkspaceMcpSessionContext.java:526-537`): require the repository,
refuse a remote one with the message that path already uses, load the
catalog, resolve through `BranchCheckout`, then call
`gitStatusService.addWorktreeForBranch` — already written and already
covered (`GitStatusService.java:289-320`, `GitStatusServiceTest.java:416-455`).

The directory derives from `Ready.localName()`. `branch` in the response
is the **resolved local name**, not the raw argument — deliberately unlike
the create path, which echoes the argument (`McpToolRouter.java:539`),
because here they can differ and the agent needs the name that now exists
to feed into `session_start`. `tracking` is `JsonNull` for a local branch,
via the existing `optionalString` helper (`:769-771`):

```json
{ "path": "/Users/x/dev/wt/drydock-login",
  "branch": "feat/login",
  "tracking": "origin/feat/login" }
```

### The refund rule keys on "was the add issued", not on the clock

`git worktree add` creates the target directory and the
`.git/worktrees/<name>` admin entry before it finishes. If the call fails
*after* that point, `translate` turns it into a plain `McpToolException`
(`WorkspaceMcpSessionContext.java:644`), the router refunds
(`McpToolRouter.java:532-534`), and the agent's retry hits
`fatal: '<dir>' already exists` — charged nothing, told nothing, and
permanently stuck.

**The discriminator is whether the add process was started, not the exit
code.** `exitCode == -1` looks like the test and is not: `GitStatusService`
also uses `-1` for an `IOException` from `builder.start()` — git never
launched — and `prepareWorktreeParent` throws
`GitCommandFailedException(List.of("mkdir", …), -1, …)` *before any spawn*,
from inside both worktree calls (`:265-278`, called at `:245` and `:301`).
Both provably created nothing. Keying the refund on `-1` would charge an
agent for a read-only worktrees directory four times over and then tell it
to go look for a worktree that does not exist. `GitStatusService.run` maps
its own timeout *and* interrupt to `-1` as well (`:719-724`), and
`WorktreeService` maps its timeout the same way (`:757-759`), so the pre-add catalog load can produce
`-1` too.

**The flag rides on the existing exception; no new type, no hierarchy
edit.** A new type does not work here. `GitCommandFailedException` is
`final` and `GitException` is `sealed … permits` (`GitException.java:20-23`,
`GitCommandFailedException.java:12`), so a subclass needs surgery on a
shipped hierarchy. And `ProcessTimeoutException` is itself `final` with
**eleven** catch sites; one of them is load-bearing in a way that is easy to
miss — `WorktreeService.mergeBlocking` catches the timeout-derived
`GitCommandFailedException` *on purpose*, because a killed `git merge`
still leaves a verdict for `verify()` to establish (`:431-445`). A sibling
exception would sail past it and turn a timed-out merge into a raw
failure. `GitCommandInterruptedException` already exists in the `permits`
list for the interrupt case, and `ProcessRunner.run`'s checked
`InterruptedException` (`:109-112`) is what every caller uses to restore
the interrupt flag.

So `GitCommandFailedException` gains one field —
`Outcome { KNOWN_FAILED, UNKNOWN }`, nested in the exception and read
through `outcome()`, defaulting to `KNOWN_FAILED` on the existing
three-argument constructor (`GitCommandFailedException.java:18`, its only
one, used at 36 sites all inside `app.drydock.git`) — and `GitStatusService.run`'s three catch arms set
it, which is exactly where the answer already is:

| arm | meaning | outcome |
|---|---|---|
| `ProcessTimeoutException` (`:719-721`) | child started, then killed | `UNKNOWN` |
| `InterruptedException` (`:722-724`) | child started, then interrupted | `UNKNOWN` |
| `IOException` (`:715-718`) | `builder.start()` failed; git never ran | `KNOWN_FAILED` |
| `prepareWorktreeParent` (`:265-278`) | no spawn at all | `KNOWN_FAILED` |

`ProcessRunner` is untouched, `ProcessTimeoutException` keeps its eleven
callers, and `mergeBlocking` keeps its verdict.

`WorkspaceMcpSessionContext` reads the flag in a private
`joinAddBy(future, deadlineNanos, directory)` used **only** for the add.
It cannot be done in the shared `joinBy`: that helper unwraps
`ExecutionException` itself and hands the cause to the static `translate`,
which collapses `GitCommandFailedException` to
`"git failed: " + stderrExcerpt()` (`:617-618,644-645`) — and `translate`
serves every tool in the class, so it cannot special-case the add without
mislabelling `repos_list` and the rest. `joinAddBy` is `private static Path joinAddBy(CompletableFuture<Path>,
long deadlineNanos, Path directory) throws McpToolException`, and it
mirrors `joinBy`'s structure (`:605-620`) with **four** exits rather than
three, because `joinBy` has two separate expiry paths and both must be
covered:

| arm | `joinBy` today | `joinAddBy` |
|---|---|---|
| entry check, `remaining <= 0` | `DeadlineExceededException` | `McpWorktreeMayExistException` |
| `TimeoutException` from `get` | `DeadlineExceededException` | `McpWorktreeMayExistException` |
| `InterruptedException` | bare `McpToolException` | `McpWorktreeMayExistException` |
| `ExecutionException` | `translate(cause)` | `UNKNOWN` → `McpWorktreeMayExistException`; else `translate(cause)` |

The entry check counts as "may exist" because `joinAddBy` is only ever
called with an already-submitted future. Missing the `TimeoutException`
arm is the easy mistake: it would hand back a `DeadlineExceededException`,
which is a plain `McpToolException`, and the router would refund while the
add ran on — exactly the hole this section closes.

The `ExecutionException` arm must unwrap `CompletionException` the way
`translate` does (`:630-633`) *before* testing
`instanceof GitCommandFailedException g && g.outcome() == UNKNOWN`. Today
`addWorktreeForBranch` is a bare `supplyAsync` (`GitStatusService.java:291-292`)
so the cause arrives unwrapped, but the moment it is composed the test
would silently fall through to the refunding path. Everything that is not
`UNKNOWN` goes to `translate` unchanged. `translate` is reachable — both
are `private static` members of the same class.

`McpWorktreeMayExistException` is a public top-level class in
`app.drydock.mcp` extending `McpToolException`; it cannot be a sibling of
`DeadlineExceededException`, which is a *private nested* class of
`WorkspaceMcpSessionContext` (`:623`). Its message:
`git worktree add was interrupted; a worktree may exist at <dir> — check before retrying.`
The router catches it **without refunding** (Java enforces the catch
order, since it is a subclass). The cost of being wrong is one of
`MAX_WORKTREES_PER_SESSION = 4` (`McpSessionRegistry.java:34`); the cost
of the alternative is a free worktree and a poisoned retry.

Because the catalog wait and the add wait are separate call sites — the
first `joinBy`, the second `joinAddBy` — *which one threw is the phase*.
Expiry during the catalog load propagates uncaught and refunds; expiry
during the add does not. No flag is needed to tell them apart.

Everything else — a clean non-zero exit, a launch failure, an unknown
branch, an occupied branch, a remote repository — refunds as today. One
narrow gap stays open and is worth naming: a `git worktree add` killed by
an *external* signal reports `128+n` through `exitValue()`, taking the
clean-exit branch and refunding, while git's `atexit` cleanup never ran.
An ordinary non-zero exit does clean up fully, so this is signals only.

### The budget must fit inside the client's 60-second abort

`createWorktreeOnExistingBranch` gets a private
`EXISTING_BRANCH_TIMEOUT_SECONDS = 50`, shared across every wait via one
`joinBy` deadline per the rule the `join` Javadoc states (`:586-590`).
Private, unlike `START_SESSION_TIMEOUT_SECONDS`, which is public only
because `MainWorkspace` must derive a provably smaller budget from it
(`:81-88`) — nothing here hops to the FX thread.

Fifty, not sixty-five, because **Claude aborts the POST at a hard sixty
seconds** and Drydock's MCP reaches Claude only. `McpConfigWriter` writes
an `"type":"http"` server with no per-server `timeout`
(`McpConfigWriter.java:112-118`), and in the installed client the HTTP
transport wraps each request in an `AbortController` with
`timeoutMs = Math.max(ygf(server), mv())`, where `ygf` falls back to the
constant `60000` and `mv()` is `MCP_TIMEOUT ?? 30000` — so 60s, described
in its own help text as a "Hard wall-clock limit per call; progress
notifications do not extend it". Drydock's own `McpServer` imposes no
request bound (`McpServer.java:129-131`), so nothing else would have
caught this.

A 65s budget is therefore unreachable: the client gives up first, the
handler keeps running, the reply is written to a dead socket, and
`McpWorktreeMayExistException` never reaches anyone. Fifty seconds keeps
the designed failure inside the window. Worst case is
`listBranchesBlocking`'s two sequential spawns (`:545,550`) plus the add,
each 15s plus up to 4s of reader-join after a kill —
`READER_JOIN_AFTER_KILL_MILLIS = 2000` is per reader thread and both are
joined in sequence (`ProcessRunner.java:38,103-104`) — so a naive
3 × 19 ≈ 57s. The real ceiling is lower: a killed spawn aborts the whole chain, so at
most one of the three can ever pay its reader-join, putting the true worst
case near 49s. Fifty is therefore a
budget the honest path fits inside, and the expiry arm exists for the case
where something outside this arithmetic hangs.

`createWorktree` adopts the same non-refunding exception, since its own
15s add under a 20s join has the identical hole. **That is a behaviour
change to a shipped tool** and lands as its own commit inside piece 2, not
folded into the new feature.

### Errors an agent can act on

Verbatim, in `translate`'s register — the human acts on the worktree, the
agent is never told to run a repo-wide mutation:

- Unknown name: `No branch named 'feat/login' in this repository; omit existing to create it.`
- In use: `'feat/login' is already checked out in the worktree at /x.`
- Locked: `'feat/login' is checked out in the worktree at /x, which is locked; the human can unlock it from the UI.`
- Stale: `'feat/login' is checked out in a stale worktree at /x; the human can prune it from the UI.`
- Unmintable: `Checking out 'origin/origin/main' would create the local branch 'origin/main', which ` + `BranchNameRules.shortClause(refusal)` + `.` — the same frame for every clause, so a `LEADING_DASH` adoption reads `…, which starts with '-'.`
- `start_point` conflict: `start_point cannot be combined with existing: true; an existing branch already has its history.`
- Remote repository: the message `createWorktree` already gives.
- Interrupted add: `McpWorktreeMayExistException`'s message above.

## Testing

**This project has TestFX.** `app/build.gradle.kts:97-106` pulls in
`testfx-core`, `testfx-junit5` and Monocle, and `tasks.test` runs headless
through Monocle (`glass.platform`, `monocle.platform`, `javafx.headless`,
`headless.geometry`, `:126-130`). Twenty-two test classes use it today,
several driving real `KeyCode` events through a live scene
(`ReviewDestinationViewTest`, `SearchRailViewTest`). The stale comment at
`NewWorktreeState.java:13-14` that says otherwise is corrected as part of
this work.

**`NewWorktreeStateTest`,** rebuilt around the `Mode` parameter: every
hint row; `createDisabled` as an OR, including the four rules carried
forward; the four preview forms in lockstep with the commands
`MainWorkspace` issues; and explicitly, the two regressions a first-match
reading would cause — occupied branch *plus* blank directory still shows
the occupancy hint, and pressing Create does not wipe it.

**New `BranchNameRulesTest`:** every clause; the **evaluation order**,
asserting the message today's `validate` produces for names that violate
two clauses at once — `origin/a..b` and `refs/heads/a..b` across groups,
`foo.lock/.bar` (component-major) and `a^b~c` (leftmost-in-name) within
them; the longest-remote-wins tie break; case-insensitive whole-component
matching; slash-containing remote names; `agentMessage` reproducing all
twelve strings verbatim; and `humanSentence`/`shortClause` for all twelve,
since both are user-visible. The order cases are what make piece 1
behaviour-preserving in fact rather than in intent — `BranchNamesTest`
cannot catch a flip, having eleven tests and exactly one `contains`
assertion.

**New `BranchCheckoutTest`:** the four outcomes; that a *local* branch
named `origin/main` resolves `Ready`, not `Unmintable`; that the
`origin/origin/main` remote ref resolves `Unmintable`; that a remote ref
named `origin/-foo` resolves `Unmintable` rather than reaching `-b -foo`;
blank, null and untrimmed text; and `dropdownLabel` reproducing
`BranchRefConverter.describe`'s exact output for all four branch states,
plus the unmintable-row decoration.

**Moved tests.** `BranchRefConverterTest`'s occupancy assertions move to
`BranchCheckoutTest`. `describe` is deleted, so nothing delegation-tests
it; what stays in `BranchRefConverterTest` is the identity contract
(`toString`/`fromString`), which is separately load-bearing
(`BranchRefConverter.java:12-18`). `BranchNamesTest` keeps
every message assertion (the messages do not change) and gains nothing;
the shadow *classification* cases are duplicated, not moved, because both
the rule and its caller need pinning.

**View tests — new `NewWorktreeModalTest` (TestFX + Monocle):** `⌘E` flips
the mode with the caret in the branch editor; clicking the selected
segment does not clear the mode; the two pickers swap `visible`/`managed`;
focus follows the mode; each control keeps its own text across a round
trip; the directory does not re-derive into `drydock-worktree` when the
newly active control is blank; segments and **Check it out instead** are
disabled while a creation is in flight, and `⌘E` itself is inert then —
disabling controls does not disable a `KEY_PRESSED` filter, so the gate
needs its own case; that the offer selects the resolved `BranchRef` rather
than round-tripping its name, asserted with a local `origin/foo` alongside
a remote-tracking `origin/foo` — and asserted on the **preview**
(`-b foo --track origin/foo` versus `git worktree add <dir> origin/foo`),
since checking the picker's value alone stays green while `derive`
resolves the wrong ref; that an ENTER commit on an occupied name still
yields the occupancy hint, and on an unknown name still yields
`no-such`; that a ⟳ which takes the selected branch flips to the
occupancy hint; that focus lands on the newly active
control after every `setMode` path; the offer's label names its target when the
resolved ref differs from the typed text and omits it when it does not,
and clicking it lands in Existing mode with that ref selected; that the modal opens in New-branch mode; that **Fork from** is
hidden in Existing mode and shown in New; that ⟳ stays visible in both;
the mode-aware `onRefresh` wording; and the catalog-reload
editor-wipe sequence does not rewrite the visible directory.

**MCP behaviour — `WorkspaceMcpSessionContextTest`,** which already stands
up real git repositories: adoption of a free local branch; of a
remote-only branch, asserting the `--track` command and the `tracking`
field; refusal of an occupied branch; of an unknown branch; and of the
`origin/origin/main` shadow case. These cannot live in
`McpToolRouterWorktreeTest`, which runs against `FakeMcpSessionContext` —
it fabricates a path from the branch string and carries one canned
`failure` field (`FakeMcpSessionContext.java:41-43,207-217`), so those
assertions would only prove that a canned string echoes.

**MCP argument handling — `McpToolRouterWorktreeTest`:** `existing:
"true"` is refused rather than coerced; `existing` + `start_point` is
refused *before* the charge, asserted by budget state and by the verbatim
conflict message; the default path is
unchanged; ordinary failures refund and `McpWorktreeMayExistException`
does not. `FakeMcpSessionContext` gains a settable canned
`ExistingBranchWorktree` and a per-method failure so these outcomes can be
expressed. A new `McpToolRouterReadTest` case asserts `existing` in
`worktree_create`'s `properties`.

**The refund discriminator — `GitStatusServiceTest` and
`WorkspaceMcpSessionContextTest`.** The router test above only proves the
catch order, since it runs on the fake. What has to be pinned where the
real code is: that a `git worktree add` killed at `PROCESS_TIMEOUT` yields
`Outcome.UNKNOWN` and reaches the agent as `McpWorktreeMayExistException`
with no refund, and — the half that produced this finding — that
`prepareWorktreeParent`'s mkdir failure and a failed `builder.start()`
both stay `KNOWN_FAILED` and **do** refund. `createWorktree` gets the same
pair, since it adopts the same rule.

**Also pinned:** that the response's `branch` is the resolved local name
rather than the raw argument, and that `tracking` is `JsonNull` for a
local adoption; all three occupancy sentences (in use, locked, stale), not
just one; `joinAddBy`'s four arms, including the `TimeoutException` and
interrupt doors, not just the `ExecutionException` one; that New mode with a remote-only match shows no
hint, leaves Create enabled, and offers `Check out origin/feat/login
instead`; that typing `origin/main` where local `main`
exists produces `unmintable-new` beside an offer labelled `Check out main
instead`; and that an unmintable dropdown row is
disabled, not merely relabelled.

**`MainWorkspaceTest`** (or the nearest existing home) pins
`branchCreatedHere == (mode == NEW)`, since it decides whether removing a
worktree may delete the branch.

**Not automated:** that `⌘E` does not fire while the `ComboBox` popup is
open (a documented limit, not a behaviour worth a robot test); that the
revived `.seg-toggle` styling, the hidden radio dot, the new focus ring
and `.worktree-hint-action` read correctly in both themes; that the `⌘E` tooltip and overlay row say the same thing
as the binding; and the 50s budget against the client's 60s abort, which
needs a wedged git to observe. Eyeball checks at review time.

## Landing order

Pieces 2 and 3 both depend on piece 1; neither depends on the other.

1. **`BranchNameRules` + `BranchCheckout`** — behaviour-preserving **if
   the clause order is preserved**, which is why `BranchNameRulesTest`'s
   order cases land here rather than later. This is the riskiest of the
   three per line changed: it moves twelve message-producing clauses out
   from behind a suite that only asserts *that* they throw. The rule set
   leaves `BranchNames`, which keeps its messages word for word;
   `dropdownLabel` replaces `BranchRefConverter.describe`, whose only
   caller is the modal's cell factory (`NewWorktreeModal.java:152`);
   `resolve`, `Outcome`, `BranchCheckout.unmintable` and
   `BranchCatalog.localBranch` arrive with no caller yet. It touches the
   modal, so it is not a no-op diff, and one behaviour does change
   deliberately: a tie between two matching remotes now resolves to the
   longest rather than to an arbitrary element of a `Set` whose iteration
   order is salted per JVM run.
2. **MCP `existing`** — the descriptor, the strict reader, the SPI method,
   the deadline and the refund rule. Carries all the irreversible risk and
   is exercisable without touching the UI. The `createWorktree` refund
   change is a separate commit within it.
3. **The modal switch** — the largest diff, and the only part needing view
   tests. The two modal behaviour changes ride here, not in piece 1: the
   create path gains the name checks it never had, and remote adoption
   starts refusing `Unmintable`.

## What the adversarial review changed

Nine rounds, three reviewers each (fact-check, mechanism, spec quality).
Every finding was verified against the code before being accepted.
Entries below reference hint rows by the numbering in force at the time;
the table has used stable names since round 6.

### Rounds 1-2

**Five claims in earlier drafts were false.**

1. *"There is no TestFX in this project."* It has TestFX, Monocle and 22
   test classes using them. The claim was used to excuse leaving `⌘E`, the
   picker swap and focus-follows-mode unverified.
2. *"Under `existing: true` nothing is minted from agent text."* Remote
   adoption mints `-b <localName>`, and a branch named `origin/main` on
   remote `origin` derives exactly the shadowing name `BranchNames` exists
   to refuse — **a live bug in the modal today**.
3. *"`ToggleButton.fire()` guards on `getToggleGroup() == null ||
   !isSelected()`."* This was a round-1 *refutation* that was itself
   wrong. Disassembling the JavaFX 26 jar shows the guard is on
   `RadioButton.fire()`; `ToggleButton.fire()` toggles unconditionally, so
   the null-mode state is real. Hence `RadioButton` segments.
4. *"`tasks.test` sets `testfx.headless`."* It sets Monocle's properties;
   `testfx.headless` is in the `run` task behind `-PheadlessTest`.
   `DrydockApplicationTest` was also cited as a TestFX key-event test; it
   is fifteen lines asserting a window title.
5. *"Only `⌘⇧L` and `⌘,` are gated on `isShowingModal()`."* Esc consults
   it too, and the cited line range stopped short of four of the chords it
   listed.

**Design defects that would have shipped.**

6. `existing` read through `optionalBooleanArg` would silently coerce
   `"true"` to `false` and create a new branch off HEAD.
7. The refund guard was keyed on deadline expiry — the failure that
   almost never fires, since `ProcessRunner` kills each spawn at 15s well
   inside the join. The reachable case is an add killed mid-write, which
   refunded. Re-keyed on outcome knowledge — first as `exitCode == -1`, which round 3 then overturned (see 24).
8. The shadow rule was applied to the derived name *unconditionally*,
   refusing free local branches where no `-b` is emitted — a dead end with
   no rename, which would have made the branch created by finding 2
   impossible to clean up.
9. …and never applied where the modal mints raw typed text, so
   `origin/hotfix` was still creatable. The rule now follows minting.
10. The 13-row table collapsed hint and disabled-state into one ladder,
    which would have deleted shipped hints (occupied branch + blank
    directory showed nothing). Now two computations.
11. `derive` picks the preview from `existing.isPresent()`, so the new
    states would preview the *other* mode's command, including `-b nope`.
12. Nothing carried the resolved branch from `derive` to the button press,
    so the "second hidden oracle" the design abolishes came back. The
    record was given `Optional<Ready>`, which round 3 then overturned as lossy (see 27).
13. The `worktree_create` descriptor was never updated — the MCP half
    would have shipped inert.
14. One `refusal(Outcome)` string could not serve both surfaces; the
    agent-facing text would have told agents to run `git worktree prune`,
    contradicting `translate`'s shipped "the human can unlock it from the
    UI".
15. `⌘E` and **Check it out instead** were the only controls not gated on
    `creatingInFlight`.
16. One shared editor listener drives `deriveDirectory`, so the hidden
    control's catalog-reload wipe rewrites the visible directory.
17. Re-deriving into a blank control rewrites the directory to
    `drydock-worktree`.
18. `onRefresh`'s "Create would now make a new one" is false in Existing
    mode.
19. An empty **Fork from** already disables Create with no hint.
20. The MCP tests listed ran against `FakeMcpSessionContext` and could not
    fail. Behaviour tests moved to `WorkspaceMcpSessionContextTest`.
21. `start_point` + `existing` was specified as both refunding and not.
22. Response shape, `label`'s contract, `shadowsRemote`'s return type,
    `resolve`'s null/blank contract, `McpWorktreeMayExistException`'s home
    and message, `EXISTING_BRANCH_TIMEOUT_SECONDS`'s visibility and
    arithmetic, `.worktree-hint-action`'s absence from `app.css`, the fate
    of `BranchRefConverterTest` and `BranchNamesTest`, and landing piece 1's
    claim to be a pure extraction — all unspecified or wrong, all now
    stated.
23. Smaller corrections: `showCreating` does not restore the Create label
    (only `showError` does); `creatingInFlight` is at
    `NewWorktreeState.java:75`, not in the `:70-72` range; the combo
    listener calls `deriveDirectory` regardless of *mode*, not
    unconditionally; `Mode` has three callers, not one; and the mode is
    written from four places, not three.

**Raised and refuted** — recorded so they are not re-opened: the package
direction of the `BranchCheckout` extraction is fine (`app.drydock.git`
gains no `mcp` dependency); the four `Outcome`s are exhaustive, and
`Occupied`/`Unmintable` are mutually exclusive by construction;
`existing: true` skipping `BranchNames.validate` is safe because only
catalog-listed refs reach git; `McpBudgetExhaustedException extends
Exception`, not `McpToolException`, so the refund catch cannot swallow it;
nothing new blocks the FX thread; a stale *available* verdict falls
through to git's own refusal, which surfaces and refunds correctly; and
`⌘E` collides with nothing in `app/src/main` or in JavaFX 26's Mac text
bindings.

### Round 3

24. **`exitCode == -1` is not "the add was issued".** `prepareWorktreeParent`
    throws `-1` before any spawn, and an `IOException` from
    `builder.start()` — git never launched — is `-1` too. Keying the
    refund on it would charge an agent four times for a read-only
    worktrees directory and send it looking for a worktree that does not
    exist. The discriminator moved to a typed exception thrown only where
    the process is known to have started.
25. **65 seconds is past the client's hard abort.** Claude's HTTP MCP
    transport aborts each POST at 60s (`ygf` → `60000`,
    `max(…, MCP_TIMEOUT ?? 30000)`), and `McpConfigWriter` sets no
    per-server `timeout`. The budget would have been unreachable, the
    reply written to a dead socket, and the non-refunding exception dead
    code. Now 50s.
26. **`translate` erases what the rule reads.** It is `private static`,
    shared by every tool in the class, and flattens
    `GitCommandFailedException` to a string — so the add needs its own
    catch, which also supplies the directory the message names.
    `joinBy`'s `InterruptedException` arm was a third unaddressed door to
    the same unknown outcome.
27. **`Optional<Ready>` was a lossy projection.** `resolve` answers
    `Occupied` for a branch that exists but is checked out elsewhere, so
    New-branch mode pointed at an occupied name had no hint and an enabled
    Create running `-b` on a name git rejects — a regression against
    shipped behaviour. The record carries the whole `Outcome` now, and
    h5 explains the state.
28. **The derived name is minted in option position.** `-b <localName>`
    precedes `--end-of-options`, so a remote ref named `origin/-foo`
    yields `-b -foo` and a git usage dump. A shadow-only check would not
    have caught it, which is why the whole rule set moved rather than one
    clause — and why the modal's create path now gets eleven checks, not
    one.
29. **The seed would have opened in an error state.** In a repository with
    a remote named `feat`, the pre-filled `feat/` matches the shadow rule
    on open. Name checks now wait until the name is well-formed (h3).
30. **EXISTING with a blank picker said nothing** — the same dead end the
    design congratulated itself for fixing on **Fork from**, on the more
    common path, since every `⌘E` into an untouched picker lands there.
    Now h8.
31. **The blocking-hint set was never identified**, so "the hint is one of
    the blocking hints above" was unimplementable. Rows are marked `†`.
32. **No listed test could catch a missing `existing`.**
    `McpServerTest`'s tools/list check is a `contains()` over tool names;
    `McpToolRouterReadTest` pins required arguments only.
33. **`describe` was both "replaced" and "delegation-tested".** It is
    deleted; the cell factory calls `dropdownLabel`.
34. **The RadioButton CSS is four properties and a focus ring**, not "one
    rule": modena paints `.radio-button > .radio` as a full button, and
    neutralising it removes the only focus indicator a keyboard-operable
    switch has.
35. Smaller: the reader-join is 2s *per thread* and both are joined, so a
    killed spawn costs 4s; `ToggleButton.fire()` does have a *disabled*
    guard, just not a selection one; `schemaBoolean` is declared at
    `McpToolRouter.java:810`, `:126` is a use site; `slug("feat/")` is
    already `worktree`, so the directory illustration was impossible;
    `BranchNames` picks an arbitrary remote when several match, now
    longest-wins; `CreateHandler`'s `branch` in EXISTING mode is
    `Ready.localName()`, which was unstated; and the MCP refusal
    sentences, the `start_point` conflict message and `resolve`'s
    null/blank/whitespace contract are now written out.

**Also refuted in round 3** — `Occupied`/`Unmintable` exclusivity survived
a direct attack from real catalog states; the modal's state field is never
stale at press time, because every editor wipe fires `refreshState()`;
`RadioButton` in a `ToggleGroup` has no user route to a null selection by
mouse, Space or traversal; and Drydock's own `McpServer` imposes no
request bound, so the 60s limit is purely client-side.


### Round 4

36. **The new exception types did not compile.**
    `GitCommandFailedException` is `final` and `GitException` is
    `sealed … permits`, so `GitOutcomeUnknownException` needed surgery on
    a shipped hierarchy; `ProcessTimeoutException` is `final` with nine
    catch sites, one of which — `WorktreeService.mergeBlocking` — catches
    the timeout-derived failure *on purpose* so `verify()` can still
    establish a verdict. A sibling type would have turned a timed-out
    merge into a raw failure. Replacing `ProcessRunner`'s checked
    `InterruptedException` would also have stopped every caller restoring
    the interrupt flag, and made the existing
    `GitCommandInterruptedException` unreachable. The flag is now one
    additive field on `GitCommandFailedException`, set by the three catch
    arms that already know the answer.
37. **The add-specific catch could not see what it was testing.** `joinBy`
    unwraps `ExecutionException` itself and hands the cause to the static
    `translate`, so the type is gone before any caller-side catch runs,
    and `joinBy`'s interrupt arm throws a bare `McpToolException`
    indistinguishable from an ordinary git failure. There is now a
    `joinAddBy` used only for the add, which owns all three doors.
38. **`validate` has thirteen refusals, not eleven.** The two omitted —
    blank branch and null remotes — had no home in the two-constant `Kind`
    enum, which also could not reproduce the three messages that quote an
    offending character or component. `Refusal` is now a per-clause enum
    plus a token, and the null-remotes precondition stays in `validate`.
39. **The clause order is load-bearing and was unstated.** `origin/a..b`
    reports the shadow message today; nothing in `BranchNamesTest` would
    notice a flip. The order is now normative and tested.
40. **h3 recreated the defect h8/h9 exist to remove — in the opening
    state.** The seed `feat/` ends in `/`, so the modal would have opened
    with a filled form, a dead Create and a blank hint.
41. **New mode would have refused a create the MCP tool allows.**
    `lookup` qualifies a bare name by each remote, so `login` with only
    `origin/login` present resolved `Ready` and disabled Create, offering
    only checkout — while `worktree_create {branch:"login"}` runs `-b
    login` happily. h5/h6 now test for a *local* branch.
42. **`Unmintable` could not produce its own message** (it lacked the
    ref), and `dropdownLabel(BranchRef)` could not compute the decoration
    the test list assigned to it. Both now take what they need.
43. **The `†` legend did not mark the blocking set** — h1, h2 and h9 block
    and were unmarked. Every row but the last blocks, and `createDisabled`
    is now stated as a formula rather than a prose OR.
44. **`describe` was deleted in the body and delegation-tested in the
    Testing section** — round 3's finding 33 surviving half-applied.
45. Corrections: the `~57s` worst case is unreachable (a killed spawn
    throws immediately; the true ceiling is ~49s, under the budget);
    `.settings-radio` is at `app.css:2117-2131`; `ProcessRunner`'s
    interrupt path is `:109-112`, not `:100-105`; `optionalIntArg`'s body
    starts at `:192`; `slug` is at `WorktreeNaming.java:24-34`; and the
    appendix header said "two rounds" while carrying three.

**Also refuted in round 4** — the `origin/-foo` chain was reproduced
end-to-end against git 2.49 (`check-ref-format` accepts it, a plain clone
creates it, and the command dies with ``error: unknown switch `o'``,
exit 255, leaving nothing behind); a `GitCommandFailedException` subclass
would not have broken any existing catch or pattern switch, only the
declaration; no race exists in classifying which phase a deadline expired
in, because the two waits are separate call sites; and `CreateHandler`'s
`branch` really is used as both the `localName` argument and the session
label today, so passing `Ready.localName()` changes nothing.

### Round 5

46. **`dropdownLabel` could not compute the string assigned to it.** It
    was given the `Refusal` but not the derived `localName`, which needs
    the remotes list. It now takes the whole `Unmintable`, and
    `BranchCheckout.unmintable(catalog, ref)` supplies the per-**row**
    verdict the cell factory needs — `resolve` answers only for the
    editor's text, while the factory renders every row.
47. **Three wording registers, one method.** `agentMessage`,
    `humanSentence` and a short clause fragment are all required and only
    the first two existed. `shortClause` is added, and all twelve clauses
    are now written out in a table rather than "and so on".
48. **The component clauses are component-major.** `foo.lock/.bar`
    reports the `.lock` refusal today because the first component is
    judged completely before the second is looked at; the flat ladder in
    revision 5 would have reported the leading-dot one. `FORBIDDEN_CHAR`
    is likewise leftmost-in-the-name, not first-in-the-charset. Since
    piece 1 rests entirely on order preservation, and every order case
    listed was cross-group, none would have caught it.
49. **"Load failed" as a first-match hint row deleted a shipped hint.**
    `applyCatalogFailure` never clears `catalog`, so after a successful
    load and a failed ⟳ the modal still holds one and today still shows
    the occupancy hint. It is no longer a row, only a `createDisabled`
    term.
50. **The offer would have switched to a branch the hint never named.**
    Keyed on `Outcome`, typing `origin/main` where local `main` exists
    resolves through `lookup`'s prefix-strip pass, so h6 warned about
    `origin/main` while **Check it out instead** silently switched to
    `main` — the ordinary case for any branch that exists both locally and
    on origin. h4/h5 and `switchOffer` now share one exact-local test,
    `BranchCatalog.localBranch(String)`, so the two can never disagree.
51. **`joinAddBy` has four arms, not three.** `joinBy` expires in two
    places — the entry check *and* `catch (TimeoutException)` — and
    missing the second would refund while the add ran on. The
    `ExecutionException` arm must also unwrap `CompletionException` before
    testing the flag.
52. Corrections: `ProcessTimeoutException` has **eleven** catch sites
    (two are multi-catch arms), not nine; `-origin/x` is not a
    double-violating name and could not have pinned any order;
    `WorktreeService` maps interrupt to `GitCommandInterruptedException`,
    not to `-1`; a killed spawn does *not* throw immediately — it joins
    both readers first, and the ~49s ceiling holds because a kill aborts
    the chain, not because the join is skipped; `.settings-radio` is at
    `app.css:2116-2130`; `GitCommandFailedException` has exactly one
    constructor; `schemaBoolean` is declared at `McpToolRouter.java:810`;
    h12 asserted a button state the table does not govern; and four
    `humanSentence` clauses are unreachable from the modal, which is now
    stated rather than left as an apparent duplication.

**Also refuted in round 5** — the `Outcome` field is genuinely additive
(one constructor, 36 call sites all inside `app.drydock.git`, no
exhaustive switch or record pattern anywhere); its three-arm mapping is
exact, since `ProcessRunner` throws `IOException` only from
`builder.start()` and both other arms are strictly post-spawn;
`BranchNameRules.check` always has its remotes in New mode, because h1
pre-empts h6 whenever the catalog is absent; `onRefresh`'s warning goes to
the error line, not the hint, so it cannot disable Create; and the
router's catch order is compiler-enforced.


### Round 6

53. **The `loading` row dropped a guard shipped code has.** Keyed on
    `catalog == null` alone, a failed **first** load — git missing, repo
    unmounted, a 15s timeout — would have shown "Loading branches…" for
    ever beside an error line saying the listing failed. Today's condition
    is `catalog == null && !catalogFailed` (`NewWorktreeState.java:64-65`),
    and `NewWorktreeModal.java:375-376` carries a comment about exactly
    this state. Round 5's fix reasoned only about the
    loaded-then-failed-⟳ case and conflated the two. Narrowing the
    condition alone would have been wrong too: the state then falls
    through to rows that dereference a null catalog. Two rows now, in
    order.
54. **`switchOffer` contradicted its own test.** The exact-local rule
    produced no offer for a remote-only match, while the test list
    required one — and two adjacent paragraphs gave the button different
    sources, stale text from before round 5's rewrite. The rule is now
    "the resolved ref is named what the user typed", which covers both
    spellings, keeps round 5's fix (the `origin/main`-with-local-`main`
    case still yields no offer), and restores the route today's derived
    mode gives for a full remote spelling — which the exact-local test
    would have stranded behind a shadow refusal.
55. **Renumbering debris, twice over.** Deleting a row in revision 6 left
    six references one row too high, including `shortClause`'s Javadoc —
    which an implementer copies verbatim — and a sentence claiming the
    already-exists row carried the space message. A `†` legend survived
    from revision 4 referring to markers no longer in the table and to an
    "h13" that no longer existed, leaving "a blocking hint" undefined for
    the second time. Rows are named now, which is the actual fix.
56. **`REFNAME` outlived the enum that had it.** The MCP `Unmintable`
    message specified only the shadow clause and deferred the rest to a
    `Clause` constant round 4 had already replaced, leaving the live
    `origin/-foo` path unspecified. One frame plus `shortClause` now
    covers all twelve.
57. Corrections: the record's `Outcome` while the catalog is null is
    `NoSuchBranch(text)`, and `resolve` is not called there;
    `unmintable-existing`'s hint ends with a period; `localBranch` lands
    in piece 1; the whole-`Outcome` rationale is restated in
    Existing-mode terms, since New mode no longer consults `lookup`;
    `WorktreeService` routes interrupts to `GitCommandInterruptedException`
    rather than `-1`, so only its timeout arm shares `DiffService`'s
    exposure; holding the last state in a field is no longer specified as
    the only option; and three untested claims — the modal opens in New
    mode, **Fork from** hides, ⟳ stays — are now pinned.

**Also refuted in round 6** — `joinAddBy` was written out against the real
signatures and works: all three of `get`'s checked exceptions are covered,
`translate` is reachable, `McpToolException` is non-final so the subclass
is legal, the router's catch order is compiler-enforced, and `Path` serves
both adds. The `Outcome` field is additive and its three-arm mapping
exact; `createWorktree` adopts it through the same private `run`. The
component-major order is implementable with no ambiguity, including a name
violating both a whole-name and a component clause. All four "unreachable"
clauses are genuinely unreachable, including via paste, since
`TextInputControl.filterInput` strips control characters. And every claim
in the twelve-clause table matches `BranchNames` clause for clause.

### Round 7

58. **The offer hid itself instead of naming its target.** Keyed on "the
    resolved ref is named what the user typed", it vanished for the
    commonest flow of all: typing `feat/login` after fetching, with only
    `origin/feat/login` in the catalog, resolves through `lookup`'s fourth
    pass to a ref named `origin/feat/login`. No hint, no offer, an enabled
    Create — and `-b feat/login <base>`, a branch off the fork point
    rather than the remote's history, where today's modal checks the
    remote branch out. Two other places in the spec asserted the offer
    would be there. A matching name can only arise from passes 1 and 2,
    both of which already produce a hint, so the rule could never have
    produced the "alternative, not a correction" state it described.
    The button now states its own target, which closes round 5's
    mismatch by construction rather than by suppression.
59. **`setMode` never recomputed anything.** `mode` is the only input to
    `derive` with no listener behind it, and the directory re-derivation
    that might have refreshed incidentally is skipped while the new
    control is blank and never runs after a manual directory edit. So
    `⌘E` after hand-editing the directory would have left the hint, the
    preview, **Fork from**'s visibility and the button state on the old
    mode — and Create, fed from the last computed state, would have run
    the old mode's action, which is also a live route to a
    `ClassCastException` on the `Ready` cast.
60. **`onRefresh`'s New-mode wording is false in every reachable case.**
    "Create would now make a new one" described a real flip under the
    derived mode; under an explicit one, New mode always creates, so
    nothing is *now* true. Round 5 fixed only the Existing half.
61. Corrections: three non-row readers do observe the placeholder
    `Outcome` while the catalog is null — all tolerate it, and the
    directory fallback is now named rather than claimed absent; the
    whole-`Outcome` rationale is finally restated in Existing-mode terms,
    which round 6 said it had done; the `NewWorktreeModal` comment about
    a failed first load is at `:375-376`; the offer-copies-catalog-spelling
    test is replaced by one that pins the label; the `start_point`
    conflict message and the `ComboBox`-popup limit are now covered; and
    the two out-of-scope lists are collected at the end instead of buried
    inside round sections.

**Also refuted in round 7** — the two catalog-absent rows partition
`catalog == null` exhaustively under every ordering of load, ⟳, mode
switch and typing, and ⟳ is disabled during the initial load so no fourth
state exists. `createDisabled` remains consistent with the named rows, no
numbered-row or `†` debris survives outside this appendix, whitespace and
the literal-local-`origin/x` cases behave, and the twelve-clause table
still matches `BranchNames` clause for clause — including that the shadow
check is whole-component, so a remote named `origin` does not match a
branch called `originals/x`.

### Round 8

62. **The offer round-tripped a name through an ambiguous namespace.** It
    copied `ref().name()` into the picker, and Existing mode re-resolved
    that string — but `lookup` tries local-exact before remote-exact,
    while the offer had reached its ref through pass 4. With a local
    branch named `origin/foo` beside a remote-tracking `origin/foo` — the
    shape the shadow bug above creates — typing `foo` offered the remote
    ref and delivered the local typo branch: stale commits, no tracking,
    and nothing on screen to tell the two rows apart. The offer now hands
    over the `BranchRef`, and Existing mode prefers the picker's selected
    value whenever its name still matches the editor text.
63. **Three sites still described the suppression rule round 7 removed**,
    including a pinned test asserting *no* offer for the `origin/main`
    case. Written test-first, that would have re-implemented the rule
    finding 58 deleted.
64. **`onRefresh` was made mode-aware in wording only.** It calls
    `branchText()` twice, so left alone it would compare the hidden
    picker's leftover text and warn about a branch the user is not
    naming — the defect "only the active control drives derivation"
    exists to prevent. It is a third mode-aware reader now.
65. **Focus after the offer was unowned.** The offer is the one control
    that hides itself as a consequence of being pressed, so a keyboard
    user activating it with Space would have been left focused on a node
    that no longer exists. Focus is a duty of `setMode`, not of the `⌘E`
    handler.
66. Corrections: the label comparison is against stripped text, so a
    trailing space cannot make the button name the branch just typed; the
    claim that the offer is "the only control that can appear beside
    either an enabled or a disabled Create" was simply false (Fork-from
    and the error line both do) and is now stated as what it is; `⌘E`'s
    in-flight gate needs its own test, since disabling controls does not
    disable a `KEY_PRESSED` filter; the SPI's remote-repository refusal
    joins the context test list; and the round-7 appendix section, the
    round counts in the revision note and the appendix intro, and the
    count of shipped defects fixed are all corrected.

**Also refuted in round 8** — `setMode` ending in `refreshState()`
introduces no loop or stale read: it is idempotent, `RadioButton.fire()`'s
guard means re-clicking the selected segment fires nothing, and the field
is written before `refreshState()` reads the active control. The benign
double-derive through the directory listener is harmless, and
`derivingDirectory` still keeps it from reading as a manual edit. The
directory cannot re-derive to a different path across the offer, since
`slug` keeps only the last path segment. Every claim in the five-row
resolution table traces correctly through `lookup`'s four passes, and the
catalog-absent rows, `createDisabled`, the `Ready` cast and the preview
all survive revision 8 unchanged — `setMode`'s new `refreshState()` closes
round 7's stale-state route into the cast rather than opening one.

### Round 9

67. **The selected-value rule trusted a value JavaFX fabricates.**
    `ComboBoxPopupControl` commits on ENTER through
    `setValue(converter.fromString(text))`, and `BranchRefConverter`
    returns a free local `BranchRef` for arbitrary text — then the skin
    rewrites the editor from it, so the name always matches. Typing an
    occupied branch and pressing Enter would have resolved `Ready` and
    enabled Create; an unknown name would have resolved `Ready` instead of
    `NoSuchBranch`. The converter's own Javadoc licenses this ("the
    catalog lookup — not this converter — decides what it means"); the
    rule broke that contract without amending it.
68. **A reload does not invalidate a stale selection either.**
    `ComboBoxSelectionModel`'s `setAll` recovery scans for an `equals`
    match and does nothing when none is found, so a branch taken between
    loads leaves the picker holding its free snapshot — silent where
    today's text resolution flips to the occupancy hint. Both routes are
    closed by requiring the catalog to still hold a ref with the same
    `(name, remote)` and resolving from *the catalog's* instance.
69. **The rule had no plumbing.** `derive` is a pure static over six
    parameters with no `BranchRef` among them, and `BranchCheckout` had no
    ref-shaped entry point — so the only implementable reading was
    `resolve(catalog, selected.name())`, which is the local-exact-first
    defect of finding 62 restored. `derive` takes the selected ref now,
    and `resolve(catalog, ref)` exists.
70. **`setMode` requested focus before the control it targets was
    visible.** `Scene.requestFocus` does nothing unless the node
    `isTreeVisible()`, and every other visibility swap in this modal is a
    `refreshState()` product — which runs last. Following that precedent
    would have made the focus move a silent no-op and left the offer path
    landing on the modal root, the very state finding 65 closes. The swap
    is `setMode`'s now, before the focus request.
71. **`onRefresh` straddles a fetch.** Mode-awareness fixes which control
    it reads but not that the mode can change between the two reads —
    `⌘E` is gated on `creatingInFlight`, not `refreshInFlight`. It
    captures the mode with `matchedBefore` and stays silent if it changed.
72. Corrections: a hidden `Button` does **not** keep focus — the scene
    traverses to the next node, so the argument for `setMode` owning focus
    is that focus goes somewhere arbitrary, not that it sticks; the offer
    test must assert the preview rather than the picker's value, which
    stays green while `derive` resolves the wrong ref; and "the modal's
    complete absence of branch-name validation" overstated — it does check
    blank, trailing slash and space today, silently; what it lacks is
    refname and shadow validation.

**Also refuted in round 9** — `switchOffer` is untouched by the picker's
value, since New mode never reads it. The `Ready` cast still cannot throw.
The preview's four forms remain in lockstep with
`addWorktreeForBranchBlocking`, which keys `-b … --track` off
`branch.remote()`. Directory derivation is unchanged across both the offer
and `⌘E`, because `slug` keeps only the last path segment. `setValue` and
the editor cannot get durably out of sync, since `updateDisplayNode`
writes the converter's string back. There is no `setMode` recursion:
`setSelected` fires no `ActionEvent` and `RadioButton.fire()` guards the
listener variant. `⌘E` collides with neither the ENTER commit nor the
popup, and the constructor's initial focus request does not conflict with
`setMode`. The `origin/foo` ambiguity, all four legs, and all four
shipped-defect claims were re-verified against the code.

## Known, out of scope

`⌘1`-`⌘4` switch the workspace view behind an
open modal, and `⇧/` replaces an open modal outright. `optionalBooleanArg`
is lax at two other call sites (`McpToolRouter.java:312,422`).
`AgentSelector` has the `ToggleButton` null-selection bug this design
avoids. A `git worktree add` killed by an external signal exits `128+n`
and refunds while leaving admin state behind. Quitting Drydock mid-add
abandons the handler after `CLOSE_AWAIT_TERMINATION_SECONDS = 2`. None is
this change's business; all are worth their own fix.

`DiffService` (`:505-511`) has the same IOException/timeout/interrupt shape
and will keep `KNOWN_FAILED` for a child that started and was killed;
`WorktreeService` (`:752-769`) shares the timeout arm but routes interrupts
to `GitCommandInterruptedException`, so only its timeout is affected.
Nothing reads the flag there today — only `joinAddBy` does — but if it ever spreads, those are the sites that
need the same decision.



