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

> **Revision note.** Adversarially reviewed in one round by three
> reviewers (fact-check, mechanism, spec quality). Eighteen findings were
> confirmed and are corrected in place; "What the adversarial review
> changed" at the end records each one, including three claims in the
> first draft that were simply false and one latent bug in shipped code
> that the review surfaced.

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
means to someone who has just fetched. Both surfaces treat it that way,
subject to the shadow rule below.

What is deliberately excluded is the fork: naming an existing branch and
getting a new branch based on it. That is the create path, and it stays
the create path.

## One oracle: `BranchCheckout`

Both surfaces need the same four-way answer about a piece of text, and one
of the four is a hazard neither surface currently checks. So resolution
moves into a new class, `app.drydock.git.BranchCheckout`, and both
surfaces render its outcome rather than re-deciding:

```java
public sealed interface Outcome {
    record Ready(BranchRef ref, String localName, Optional<String> tracking) implements Outcome { }
    record NoSuchBranch(String text)                                        implements Outcome { }
    record Occupied(BranchRef ref)                                          implements Outcome { }
    record ShadowsRemote(String localName, String remote)                   implements Outcome { }
}

static Outcome resolve(BranchCatalog catalog, String text);
static Optional<String> refusal(Outcome outcome);   // the sentence, for the hint and the MCP error
static String label(BranchRef branch);              // the short dropdown-row suffix
```

`Ready.tracking` is present only for a remote-tracking ref, naming the ref
the new local branch will follow.

### The shadow rule, which is a live bug today

`BranchNames.validate` (in `app.drydock.mcp`) refuses to mint a local
branch whose first path component matches a remote, because
`refs/heads/origin/main` shadows `refs/remotes/origin/main` for every
short-name lookup — so a later `git merge origin/main` silently targets
the agent's ref. `git worktree add` exits 0 and warns only on stderr. The
class Javadoc records that this was verified against real git
(`BranchNames.java:9-29`).

The first draft of this design argued that `existing: true` is safe from
that hazard because "nothing is minted from agent text". **That is
false**, and the same falsehood is shipped in the modal today:

- `listBranchesBlocking` names a remote-tracking ref by stripping
  `refs/remotes/` (`GitStatusService.java:562-564`), so a branch actually
  named `origin/main`, pushed to remote `origin`, is listed as
  `origin/origin/main`.
- `merge` keeps it, because no local branch is called `origin/main`
  (`BranchCatalog.java:76`).
- `localName` strips the longest matching remote prefix
  (`BranchCatalog.java:98-110`), yielding `origin/main`.
- `addWorktreeForBranchBlocking` then runs
  `-b origin/main --track --end-of-options origin/origin/main`
  (`GitStatusService.java:303-311`), creating exactly the shadowing ref
  `BranchNames` exists to prevent.

The hazard is a property of the **minted** name, not of where the name
came from. So `BranchCheckout.resolve` applies the remote-shadow rule to
the derived `localName` and returns `ShadowsRemote` — refusing in the
modal and over MCP alike, and closing the existing hole in the modal as
part of working here.

To keep one copy of that rule, the whole-first-component comparison moves
out of `BranchNames` into `BranchCheckout.shadowsRemote(String,
Collection<String>)`, and `BranchNames.validate` calls it. Its
case-insensitivity and its "a remote name may itself contain a slash"
handling are load-bearing and move unchanged.

### One copy of the occupancy vocabulary

The locked / prunable / in-use trichotomy is currently written twice — as
a sentence in `NewWorktreeState.blockedHint` (`NewWorktreeState.java:84-95`)
and as a short row suffix in `BranchRefConverter.describe`
(`BranchRefConverter.java:36-52`). After this change there would be three
copies unless they are consolidated, so both move onto `BranchCheckout`:
`refusal` produces the sentence (modal hint and MCP error, verbatim in
both), `label` produces the row suffix. `BranchRefConverter.describe`
becomes a call to `BranchCheckout.label`. AGENTS.md requires shared
presentation logic to live in one utility with no per-view copies
(`AGENTS.md:83-85`); this is that.

The three cases stay distinct because they are escaped differently:
`git worktree unlock`, `git worktree prune`, and nothing.

## The modal: an explicit mode switch

A two-button `ToggleGroup` — **New branch** / **Existing branch** — sits
directly under the modal title, above the branch row. `app.css` already
carries `.seg-toggle` and `.seg-toggle-button` rules that nothing in
`main` references (`app.css:944-962`); this revives them rather than
adding a third toggle look beside `.agent-toggle`. The modal opens in
**New branch** mode, so the seeded `feat/` and existing muscle memory
survive unchanged.

The mode lives in a `Mode` field on the modal, written from exactly three
places: the two toggle handlers, `⌘E`, and **Check it out instead**.
`derive` reads it as a parameter. Clicking the already-selected segment
does **not** deselect it — `ToggleButton.fire()` guards on
`getToggleGroup() == null || !isSelected()` — so there is no null-mode
state to handle, and no `selectedToggleProperty` listener is needed.

### Two pickers, swapped — never one control reconfigured

Toggling `ComboBox.editable` at runtime makes the skin swap its editor out
from under any bound property. That is the same class of trap already
documented in the modal, where setting `promptText` on the editor rather
than the `ComboBox` throws because the skin binds one to the other
(`NewWorktreeModal.java:159-165`). So the two modes get two controls,
swapped by `visible`/`managed`:

- **New branch**: a plain `TextField`, seeded `feat/`, plus the existing
  **Fork from** combo.
- **Existing branch**: the `ComboBox<BranchRef>` as it is today —
  editable, so a repository with hundreds of branches is still typeable;
  every branch listed; occupied ones disabled and labelled by
  `BranchCheckout.label` with the reason and the holding path. **Fork
  from** is hidden.

The ⟳ refresh button stays visible in **both** modes: New-branch mode
depends on a fresh catalog just as much, because that is what makes the
collision warning below correct. Initial focus is the New-branch
`TextField`, with the caret at the end, exactly as the constructor does
today (`NewWorktreeModal.java:237-240`). The Create button reads "Create
worktree" in both modes — `showCreating` and `showError` hardcode that
string when they restore it (`NewWorktreeModal.java:70,435`), and a
mode-dependent label would silently revert after any failure.

Each control keeps its own text, so flipping back and forth does not
clobber what was typed in the other. Occupied branches stay in the list
rather than being filtered out: a user hunting for a branch they know
exists must be told where it went, not shown a shorter list.

### Only the active control drives derivation

`deriveDirectory` and `refreshState` read the **active mode's** control.
This is not cosmetic. `applyCatalog` does `items.setAll(…)` and repairs
the editor on a later pulse, because the skin nulls a value that is not
among the new items and re-syncs the editor from that null
(`NewWorktreeModal.java:335-351`). Both the wipe and the repair fire the
combo editor's text listener, which today calls `deriveDirectory`
unconditionally (`:166-171`).

Concretely, with one shared listener: select `feat/login` in Existing
mode, `⌘E` back to New, type `feat/other`, press ⟳; if `feat/login` was
checked out elsewhere meanwhile, its reloaded `BranchRef` is no longer
`equals` the selected value (the occupancy fields differ), the value is
cleared, the editor is wiped, the listener fires with `""`, and the
**visible** directory — derived from the New-mode `TextField` — becomes
`<repo>-worktree`. So the inactive control's listener must not derive.

Re-derivation is also **skipped while the newly active control is blank**:
`WorktreeNaming.slug("")` returns `"worktree"` (`WorktreeNaming.java:22-33`),
so deriving on a mode switch into an untouched Existing picker would
rewrite `…/drydock-feat` to `…/drydock-worktree` and back again on the way
out. The "unless manually edited" rule (`NewWorktreeModal.java:88-89`) is
unchanged. In existing mode the directory derives from
`Ready.localName()`, so picking `origin/feat/login` or `feat/login`
proposes the same path.

### Mode becomes an input to `derive`, not an output

`NewWorktreeState.derive` gains a `Mode` parameter and loses
`branchLabel` — the switch says that now, and the branch row's label
becomes a plain "Branch". `baseVisible` collapses to `mode == NEW`. The
record gains `switchOffer`: true when New-branch mode holds a name the
catalog already knows.

`Mode` is a two-constant enum nested in `NewWorktreeState`, so the record
and its only caller share one definition.

| # | mode | condition | hint | Create |
|---|---|---|---|---|
| 1 | either | catalog still loading | `Loading branches…` | disabled |
| 2 | either | catalog load failed | — (the error line speaks) | disabled |
| 3 | either | directory blank | — | disabled |
| 4 | either | creation in flight | — | disabled |
| 5 | NEW | branch blank, ends `/`, or contains a space | — | disabled |
| 6 | NEW | **Fork from** blank | `Pick a branch to fork from.` | disabled |
| 7 | NEW | name already exists | `feat/login already exists.` + **Check it out instead** | disabled |
| 8 | NEW | otherwise | — | enabled |
| 9 | EXISTING | branch blank | — | disabled |
| 10 | EXISTING | `NoSuchBranch` | `No branch named 'nope'.` | disabled |
| 11 | EXISTING | `Occupied` | `BranchCheckout.refusal` | disabled |
| 12 | EXISTING | `ShadowsRemote` | `BranchCheckout.refusal` | disabled |
| 13 | EXISTING | `Ready` | — | enabled |

Rows 3, 4, 5 and 6 are today's rules (`NewWorktreeState.java:70-72`),
carried forward — they are listed because the first draft's table omitted
them while claiming to be exhaustive, which would have deleted four live
tests and shipped a Create button that runs `-b feat/` with no base.

Row 6 is new wording for an existing dead end: `baseField` is filled
asynchronously from `getStatus` and only when the repo is `OnBranch`
(`NewWorktreeModal.java:118-123`), so on a detached HEAD, an unborn
branch, or a failed status call, Create is disabled today with **nothing
on screen explaining it**.

Row 7 is the case the explicit switch creates. With a derived mode,
typing an existing name silently changed what Create would do; with an
explicit one, it is a collision the user must resolve. **Check it out
instead** is a separate `Button`, shown when `switchOffer` is true,
sitting beside the hint label with style class `.worktree-hint-action`. It
copies `Ready.ref().name()` — the catalog's spelling, not the raw typed
text — into the existing picker and flips the mode. It is the only
programmatic mode change in the modal, and it happens on a button press,
never as a side effect of typing. A control that moves itself under the
user is what the explicit switch exists to stop.

The mirror-image offer is deliberately **not** built: row 10 says so and
disables Create, with no "create it instead" button. One directed shortcut
earns its place because it rescues the flow the derived mode used to give
for free; the reverse never existed, and a second self-modifying control
is a second thing that can move under the user.

`refreshState()` remains the single writer of `createButton.setDisable`,
for the reason its own comment gives (`NewWorktreeModal.java:402-408`),
and it gains two more subjects: the mode toggles and the **Check it out
instead** button are both disabled while `creatingInFlight`. Without that,
`⌘E` during a creation re-derives the directory (rows above), the creation
fails, `showError` re-enables Create (`:433-437`), and the user's second
click targets a *different* directory than the failure message names.

### The preview must branch on mode, not on the lookup

`derive` currently picks the preview from `existing.isPresent()`
(`NewWorktreeState.java:49-58`). Left alone, that shows the wrong command
on exactly the two rows the mode switch introduces: row 7 would preview
the checkout form for a disabled create, and row 10 would preview
`-b nope` — the create command the mode was built to refuse. The preview's
lockstep contract (`NewWorktreeState.java:17-24`) is with *what Create
would run*, so it branches on mode:

```
NEW                 $ git worktree add <dir> -b <branch> [<base>]
EXISTING, Ready, local     $ git worktree add <dir> <name>
EXISTING, Ready, remote    $ git worktree add <dir> -b <local> --track <remote>
EXISTING, not Ready        the last form it could resolve, or blank
```

`--end-of-options` stays omitted from the preview for the reason already
recorded there: git forbids refs starting with `-`, so it can never change
the meaning of anything typeable here, and previewing it would only
obscure the command a reader is meant to recognise.

### The refresh warning becomes mode-aware

`onRefresh` currently says "That branch no longer exists on the remote —
Create would now make a new one." when `--prune` removes the selected ref
(`NewWorktreeModal.java:312-316`). Under an explicit mode that sentence is
false in Existing mode, where row 10 *disables* Create. The message is
chosen by mode: New keeps today's wording; Existing reads "That branch no
longer exists on the remote — pick another."

### ⌘E flips the mode

`⌘E` flips the switch — it acts on the mode, not on the active control —
installed as a `KEY_PRESSED` filter on the modal itself, gated on
`creatingInFlight` like every other control. The chord is free: the global
scene filter handles `⌘N ⌘R ⌘1-4 ⌘0 ⌘[ ⌘] ⌘↑ ⌘↓ ⌘F ⌘⇧L ⌘,`, Esc and `⇧/`
(`DrydockApplication.java:809-916`), no `KeyCode.E` appears anywhere in
`app/src/main`, and it is not a JavaFX Mac text-editing binding — so it
works with the caret sitting in the branch editor, unlike `⌥←/→`, `⌘←/→`
and `⌘A/C/V/X/Z`.

Reusing `⌘1`/`⌘2` was rejected: scene-level filters fire during capture,
root-down, so the global handler sees those keys before any modal handler
can. Only `⌘⇧L`, `⌘,` and the Esc branch consult `isShowingModal()`;
making `⌘1`/`⌘2` work inside the modal would mean gating the view-switch
keys too. (That gating is worth doing — `⌘1` today switches the workspace
view behind an open modal — but it is a separate bug and not this
change's business.)

Focus follows the mode, so `⌘E` then typing lands in the newly shown
picker. Two honest limits: the filter does not fire while the
`ComboBox` popup is open, because popup key events route through the
popup's own window rather than the main scene; and `⇧/` cannot be used to
*read* the shortcut while the modal is up, because `ModalLayer.show` does
`getChildren().setAll(modal)` (`ModalLayer.java:65-71`) and would replace
the half-filled modal. So the chord is also carried as a `Tooltip` on the
toggle, where it is discoverable in place, in addition to the overlay
section AGENTS.md requires (`AGENTS.md:86-88`; the overlay already carries
context sections at `ShortcutsOverlay.java:43,63`):

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
invalidated by a ⟳ refresh would fall through to `-b` — the silent mode
swap this design exists to abolish.

So `CreateHandler` takes the `Mode` and, for `EXISTING`, the resolved
`BranchCheckout.Outcome.Ready`. `MainWorkspace` branches on the mode
alone. Create is only enabled in Existing mode when the outcome is
`Ready` (row 13), so there is no "mode says existing but nothing
resolved" call to make.

`branchCreatedHere` becomes `mode == NEW` — the same value
`existing.isEmpty()` yields (`MainWorkspace.java:1791`), now read from the
authority instead of inferred. Adopting a remote-only branch does mint a
local ref, but that ref tracks a remote somebody else owns; removing the
worktree must not offer to delete it.

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

### `existing` is parsed strictly, not with `optionalBooleanArg`

`optionalBooleanArg` returns the default for **any** non-`JsonBoolean`
value (`McpToolRouter.java:758-766`). The same file already documents that
some clients stringify every argument — that is why `optionalIntArg`
exists (`:186-191`) — and `optionalStringArg` deliberately rejects a
wrong-typed argument rather than treating it as absent, so the agent is
told "must be a string" instead of the more confusing "missing"
(`:736-755`).

Reusing `optionalBooleanArg` here would reintroduce the exact failure the
explicit flag prevents: `{"branch":"feat/login","existing":"true"}`
coerces to `false`, `BranchNames.validate` passes, and git creates a
**brand-new branch off the caller's HEAD** with none of the branch's
commits — reported as `{"path":…,"branch":"feat/login"}`, indistinguishable
from adoption. `existing: 1` and `existing: "yes"` fail identically.

So `existing` is read by a strict reader that refuses a non-boolean with
`Argument 'existing' must be a boolean (true or false).`, following
`optionalStringArg`'s doctrine. The two existing `optionalBooleanArg`
call sites (`:312`, `:422`) are out of scope and unchanged; the helper's
laxity is worth revisiting separately.

### Validation is asymmetric, deliberately

`existing: false` keeps `BranchNames.validate` unchanged. `existing: true`
does not call it, because its remote-shadow rule would reject
`origin/feat/login` — a legitimate way to name a branch that exists — and
its refname rules vet a name being minted, not a lookup key.

What replaces it is not nothing. The text goes through
`BranchCheckout.resolve`, which returns `NoSuchBranch` for anything the
catalog does not know, so the only strings that reach git are refs git
itself listed — and `ShadowsRemote` for the derived-name hazard above,
which `BranchNames` alone would have missed. Blank is still refused.
`registry.maySpawn(caller)` still applies: it gates the whole tool, before
either branch (`McpToolRouter.java:512-515`).

`start_point` alongside `existing: true` is refused **before**
`chargeWorktree`, so nothing is charged and nothing is refunded. An
existing branch already has its history, and quietly dropping the argument
would leave the agent believing it forked. A blank `start_point` is
already absent by the time it is seen — `optionalStringArg` maps blank to
empty (`:749-754`) — so it does not conflict.

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

The directory derives from `Ready.localName()`, so `origin/feat/login` and
`feat/login` land on the same path, and `WorktreeNaming` slugs it as it
does for the create path.

`branch` in the response is the **resolved local name**, not the raw
argument — deliberately unlike the create path, which echoes the argument
(`McpToolRouter.java:539`), because here they can differ and the agent
needs the name that now exists to feed into `session_start`. `tracking` is
`JsonNull` for a local branch, via the existing `optionalString` helper
(`:769-771`), rather than omitted:

```json
{ "path": "/Users/x/dev/wt/drydock-login",
  "branch": "feat/login",
  "tracking": "origin/feat/login" }
```

### The deadline, and why the refund rule changes

This path spawns git up to four times where `createWorktree` spawns once.
`listBranchesBlocking` runs `git remote` and then `git for-each-ref`
**sequentially** (`GitStatusService.java:545,550`), `worktreeService.list`
runs concurrently with them, and `git worktree add` follows. Each spawn is
bounded by `PROCESS_TIMEOUT = 15s` (`GitStatusService.java:39`), against
`JOIN_TIMEOUT_SECONDS = 20` (`WorkspaceMcpSessionContext.java:75`).

Under the current 20s join the failure is not a clean timeout. `joinBy`
throws `DeadlineExceededException`, which is an `McpToolException`
(`:623-627`), so the router refunds (`McpToolRouter.java:532-534`) — while
`git worktree add` **keeps running on the git executor and succeeds**. The
agent is told the call failed, the charge is returned, and a worktree it
does not know about sits on disk, poisoning the retry with "directory
already exists".

Two changes:

1. `createWorktreeOnExistingBranch` gets its own
   `EXISTING_BRANCH_TIMEOUT_SECONDS = 50`, shared across every wait via a
   single `joinBy` deadline, per the rule the `join` Javadoc states —
   anything that waits more than once per tool call shares one deadline
   (`:586-590`). Fifty seconds covers the worst case (15 + 15 sequential
   for the listing, then 15 for the add) with margin, and is bounded, not
   unbounded.
2. A timeout that strikes **after `git worktree add` has been issued**
   throws `McpWorktreeMayExistException extends McpToolException`, which
   the router catches **without refunding**, with a message naming the
   directory so the agent can look. Over-charging for a worktree that may
   exist is the right side to err on; refunding for one that does exist
   hands out free worktrees and a corrupted retry.

The same hazard exists on the `createWorktree` path with less margin (one
15s spawn under a 20s join), so it adopts the same non-refunding
exception. Everything that fails *before* the add is issued still refunds.

### Errors an agent can act on

- Unknown name: `No branch named 'feat/login' in this repository; omit existing to create it.`
- Occupied: `BranchCheckout.refusal`, verbatim — the reason and the
  holding path, including the `git worktree unlock` / `git worktree prune`
  advice, which is as actionable for an agent as for a human.
- Shadows a remote: the name that would be minted and the remote it would
  shadow.
- Remote repository: the message `createWorktree` already gives.

The budget keeps its current shape otherwise: `chargeWorktree` before the
context call, `refundWorktree` in the `McpToolException` catch — with the
`McpWorktreeMayExistException` carve-out above, and with the `start_point`
conflict refused before the charge.

## Testing

**This project has TestFX.** `app/build.gradle.kts:97-106` pulls in
`testfx-core`, `testfx-junit5` and Monocle, `tasks.test` sets
`testfx.headless` (`:224`), and 23 test classes use it today — several
driving real `KeyCode` events through a live scene
(`ReviewDestinationViewTest`, `SearchRailViewTest`,
`DrydockApplicationTest`). The first draft of this design claimed the
opposite and used it to excuse leaving `Node`-only behaviour unverified.
The claim came from a stale comment in `NewWorktreeState.java:13-14`,
which this work corrects.

**Pure logic — `NewWorktreeStateTest`,** rebuilt around the `Mode`
parameter: one case per row of the 13-row table, including the four
carried-forward rules the first draft dropped, plus the four preview forms
held in lockstep with the commands `MainWorkspace` actually issues.

**New `BranchCheckoutTest`:** the four outcomes; the shadow case built
from a repository with remote `origin` and a branch named `origin/main`,
asserting `ShadowsRemote` rather than a `-b origin/main` command; the
three occupancy wordings; and `label` matching what
`BranchRefConverter.describe` produced before the move.

**View tests — new `NewWorktreeModalTest` (TestFX + Monocle):** `⌘E`
flips the mode with the caret in the branch editor; the two pickers swap
`visible`/`managed`; focus follows the mode; each control keeps its own
text across a round trip; the directory does not re-derive into
`drydock-worktree` when the newly active control is blank; the toggles and
**Check it out instead** are disabled while a creation is in flight; and
the catalog-reload editor-wipe scenario does not rewrite the visible
directory.

**MCP behaviour — `WorkspaceMcpSessionContextTest`,** which already stands
up real git repositories: adoption of a free local branch; of a remote-only
branch, asserting the `--track` command and the `tracking` field; refusal
of an occupied branch, asserting the reason and path; of an unknown branch;
and of the `origin/origin/main` shadow case. These cannot live in
`McpToolRouterWorktreeTest`, which runs against `FakeMcpSessionContext` —
it fabricates a path from the branch string and carries one canned
`failure` field (`FakeMcpSessionContext.java:41-43,207-217`), so those
assertions would only prove that a canned string echoes.

**MCP argument handling — `McpToolRouterWorktreeTest`:** `existing:
"true"` is refused rather than coerced; `existing` + `start_point` is
refused *before* the charge, asserted by budget state; the default path is
unchanged; failures before the add refund and
`McpWorktreeMayExistException` does not. `FakeMcpSessionContext` gains a
settable canned `ExistingBranchWorktree` and a per-method failure so these
outcomes can be expressed.

**Not automated:** that the revived `.seg-toggle` styling reads correctly
in both themes, and that the `⌘E` tooltip and overlay row say the same
thing as the binding. Both are eyeball checks at review time.

## Landing order

Three pieces, landable and verifiable independently, in this order:

1. **`BranchCheckout`** — pure extraction plus the shadow rule, with
   `BranchNames` and `BranchRefConverter` rewired to it. No behaviour
   change except that the modal now refuses the shadow case. Fixes a live
   bug on its own.
2. **MCP `existing`** — carries all of the irreversible-behaviour risk
   (budget, deadlines, a worktree on disk) and can be exercised without
   touching the UI.
3. **The modal switch** — the largest diff, the least risk, and the only
   part needing view tests.

## What the adversarial review changed

One round, three reviewers with distinct lenses (fact-check, mechanism,
spec quality). Every finding below was verified against the code before
being accepted; the ones the reviewers raised and I refuted are listed
last, so nobody re-opens them.

**Three claims in the first draft were false.**

1. *"There is no TestFX in this project."* It has TestFX, Monocle and 23
   test classes using them (`app/build.gradle.kts:97-106,224`). The draft
   used the false claim to excuse leaving `⌘E`, the picker swap and
   focus-follows-mode unverified. The Testing section is rewritten around
   a real `NewWorktreeModalTest`. The claim was inherited from a stale
   comment at `NewWorktreeState.java:13-14`, which this work corrects.
2. *"Under `existing: true` nothing is minted from agent text."* The
   remote-adoption path mints `-b <localName>`, and a branch named
   `origin/main` on remote `origin` derives exactly the shadowing name
   `BranchNames` exists to refuse. This is a **live bug in the modal
   today**, not just a gap in the proposal. Hence `BranchCheckout` and its
   `ShadowsRemote` outcome.
3. *"Only `⌘⇧L` and `⌘,` are gated on `isShowingModal()`."* The Esc
   branch consults it too (`DrydockApplication.java:818`), and the cited
   range stopped at `⌘F` (line 880) while four of the listed chords live
   at 881-901. Both corrected.

**Defects the design would have shipped.**

4. `existing` read through `optionalBooleanArg` would silently coerce
   `"true"` to `false` and create a new branch off HEAD, reported
   indistinguishably from adoption. Now parsed strictly.
5. One 20s join over four git spawns: on timeout the router refunds while
   `git worktree add` succeeds anyway, leaving an unknown worktree and a
   poisoned retry. Now a 50s shared deadline plus a non-refunding
   `McpWorktreeMayExistException`.
6. `derive` picks the preview from `existing.isPresent()`, so the two new
   table rows would preview the *other* mode's command — including
   `-b nope` for a mode that forbids creating. The preview now branches on
   mode.
7. The press-time `catalog.lookup` at `NewWorktreeModal.java:216` is a
   second hidden mode oracle that would silently undo the explicit switch
   after a ⟳ refresh. `CreateHandler` now takes the `Mode`.
8. The decision table dropped four rules `derive` enforces today while
   claiming to be exhaustive — "one case per row" would have deleted four
   live tests and shipped a Create button running `-b feat/` with no base.
   Restored as rows 3-6.
9. `⌘E` and **Check it out instead** were the only controls not gated on
   `creatingInFlight`; flipping mid-creation re-derives the directory, so
   the retry after a failure would target a different one than the error
   names.
10. One shared editor listener drives `deriveDirectory`, so the hidden
    control's catalog-reload wipe rewrites the visible directory. Only the
    active control derives now.
11. Re-deriving on a mode switch into a blank control rewrites the
    directory to `drydock-worktree`, because `WorktreeNaming.slug("")`
    returns `"worktree"`. Re-derivation is skipped while blank.
12. `onRefresh`'s "Create would now make a new one" is false in Existing
    mode, where Create is disabled. Now mode-aware.
13. Row 6's dead end already exists unexplained: an empty **Fork from**
    (detached HEAD, unborn branch, failed status) disables Create with no
    hint. Now says why.
14. The MCP tests listed could not fail — router tests run against
    `FakeMcpSessionContext`, which fabricates paths and has one canned
    `failure`. Behaviour tests moved to `WorkspaceMcpSessionContextTest`.
15. `start_point` + `existing: true` was specified as both refunding and
    not. Now refused before the charge.
16. Response shape was ambiguous: `branch` now returns the resolved local
    name (unlike the create path, deliberately), `tracking` is `JsonNull`
    when absent.
17. `BranchRefConverter.describe` would have become a third copy of the
    occupancy trichotomy the extraction was meant to deduplicate. Folded
    onto `BranchCheckout.label`.
18. Unstated modal decisions that each have a code consequence: ⟳ in both
    modes, Create's label constant, initial focus, the "Check it out
    instead" node's identity and style class, blank-text handling in
    Existing mode, and `Mode` ownership. All now specified.

**Raised and refuted** — checked, not defects, recorded so they are not
re-opened: `ToggleGroup` cannot be deselected to null (`ToggleButton.fire()`
guards on `!isSelected()`); `⌘E` collides with nothing in `app/src/main`
or in JavaFX's Mac text bindings; a hidden `ComboBox` still gets its skin
and editor, so configuring it while invisible is safe; nothing new blocks
the FX thread (`createWorktreeOnExistingBranch` runs on the MCP request
thread and composes futures on the git executor); and a stale *available*
verdict falls through to git's own refusal, which surfaces and refunds
correctly.

**Known, out of scope.** `⌘1`-`⌘4` switch the workspace view behind an
open modal, and `⇧/` replaces an open modal outright rather than layering.
`optionalBooleanArg`'s laxity affects two other call sites
(`McpToolRouter.java:312,422`). None is this change's business; all three
are worth their own fix.
