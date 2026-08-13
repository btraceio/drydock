# A worktree can start on a branch that already exists

Opening a worktree on an existing branch already works in the desktop
app, and has since the create-worktree modal learned its create-vs-checkout
mode. What is missing is that the capability is **invisible in one surface
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

## The modal: an explicit mode switch

A two-button `ToggleGroup` — **New branch** / **Existing branch** — sits
directly under the modal title, above the branch row. `app.css` already
carries `.seg-toggle` and `.seg-toggle-button` rules that nothing in
`main` references (`app.css:944-962`); this revives them rather than
adding a third toggle look beside `.agent-toggle`. The modal opens in
**New branch** mode, so the seeded `feat/` and existing muscle memory
survive unchanged.

### Two pickers, swapped — never one control reconfigured

Toggling `ComboBox.editable` at runtime makes the skin swap its editor out
from under any bound property. That is the same class of trap already
documented in the modal, where setting `promptText` on the editor rather
than the `ComboBox` throws because the skin binds one to the other
(`NewWorktreeModal.java:159-165`). So the two modes get two controls,
swapped by `visible`/`managed`:

- **New branch**: a plain `TextField`, seeded `feat/`, plus the existing
  **Fork from** combo.
- **Existing branch**: the `ComboBox<BranchRef>` exactly as it is today —
  editable, so a repository with hundreds of branches is still typeable;
  every branch listed; occupied ones disabled and labelled by
  `BranchRefConverter.describe` with the reason and the holding path
  (`BranchRefConverter.java:36-52`). **Fork from** is hidden.

Each control keeps its own text, so flipping back and forth does not
clobber what was typed in the other. Occupied branches stay in the list
rather than being filtered out: a user hunting for a branch they know
exists must be told where it went, not shown a shorter list.

The worktree directory keeps auto-deriving until the user hand-edits it,
now from whichever control the active mode shows — and, in existing mode,
from `catalog.localName(ref)`, so picking `origin/feat/login` or
`feat/login` proposes the same directory. Switching mode re-derives it
under the same "unless manually edited" rule that governs it today
(`NewWorktreeModal.java:88-89`).

### Mode becomes an input to `derive`, not an output

`NewWorktreeState.derive` gains a `Mode` parameter and loses
`branchLabel` — the switch says that now, and the branch row's label
becomes a plain "Branch". `baseVisible` collapses to `mode == NEW`. The
record gains `switchOffer`: true when New-branch mode holds a name the
catalog already knows.

| mode | condition | hint | Create |
|---|---|---|---|
| NEW | name free | — | enabled |
| NEW | name exists | `feat/login already exists.` + **Check it out instead** | disabled |
| EXISTING | text resolves, available | — | enabled |
| EXISTING | text resolves, occupied | `BranchOccupancy.describe` | disabled |
| EXISTING | text resolves to nothing | `No branch named 'feat/login'.` | disabled |
| either | catalog loading, or load failed | as today | disabled |

The "name exists" row is the case the explicit switch creates. With a
derived mode, typing an existing name silently changed what Create would
do; with an explicit one, it is a collision the user must resolve. **Check
it out instead** copies the name into the existing picker and flips the
switch — the only programmatic mode change in the modal, and it happens on
a button press, never as a side effect of typing. A control that moves
itself under the user is what the explicit switch exists to stop.

`Mode` is a two-constant enum nested in `NewWorktreeState`, so the record
and its only caller share one definition.

`refreshState()` remains the single writer of `createButton.setDisable`,
for the reason its own comment gives (`NewWorktreeModal.java:402-408`).

The mirror-image offer is deliberately **not** built: Existing-branch mode
holding a name that resolves to nothing says so and disables Create, with
no "create it instead" button. One directed shortcut earns its place
because it rescues the flow the derived mode used to give for free; the
reverse never existed, and a second self-modifying control is a second
thing that can move under the user.

### The preview stays in lockstep

`preview` must keep naming the command that actually runs, down to
argument order — the contract `NewWorktreeState`'s Javadoc asserts and its
tests hold (`NewWorktreeState.java:17-24`). Three forms:

```
new                 $ git worktree add <dir> -b <branch> [<base>]
existing, local     $ git worktree add <dir> <name>
existing, remote    $ git worktree add <dir> -b <local> --track <remote>
```

`--end-of-options` stays omitted from the preview for the reason already
recorded there: git forbids refs starting with `-`, so it can never change
the meaning of anything typeable here, and previewing it would only
obscure the command a reader is meant to recognise.

### ⌘E switches modes

`⌘E` toggles whichever mode is active, installed as a `KEY_PRESSED` filter
on the modal itself. The chord is free: the global scene filter handles
`⌘N ⌘R ⌘1-4 ⌘0 ⌘[ ⌘] ⌘↑ ⌘↓ ⌘F ⌘⇧L ⌘,` and Esc
(`DrydockApplication.java:809-880`), and macOS text editing owns
`⌥←/→`, `⌘←/→` and `⌘A/C/V/X/Z` — none of which collide, so `⌘E` works
with the caret sitting in the branch editor.

Reusing `⌘1`/`⌘2` was rejected: scene-level filters fire during capture,
root-down, so the global handler sees those keys before any modal handler
can, and only `⌘⇧L` and `⌘,` are gated on `isShowingModal()`. Making them
work inside the modal would mean gating the view-switch keys too. (That
gating is worth doing — `⌘1` today switches the workspace view behind an
open modal — but it is a separate bug and not this change's business.)

Focus follows the mode, so `⌘E` then typing lands in the newly shown
picker. The overlay gains a section, because AGENTS.md requires that
anything advertised in `ShortcutsOverlay` is bound and anything bound is
advertised; the overlay already carries context sections for Review and
the Explorer (`ShortcutsOverlay.java:43,63`):

```
IN THE NEW-WORKTREE MODAL
  Switch new / existing branch      ⌘E
```

### What does not change

`branchCreatedHere` stays `existing.isEmpty()`
(`MainWorkspace.java:1791`). Adopting a remote-only branch does mint a
local ref, but that ref tracks a remote somebody else owns — removing the
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

### Validation is asymmetric, deliberately

`existing: false` keeps `BranchNames.validate`. `existing: true` does not
call it.

`BranchNames` vets a name being *minted* as a new ref, and its
load-bearing rule rejects anything whose first path component matches a
remote: creating `refs/heads/origin/main` shadows
`refs/remotes/origin/main` for every short-name lookup, so a later
`git merge origin/main` silently targets the agent's commit
(`BranchNames.java:9-29`). Applied to a lookup key, that rule would reject
`origin/feat/login` — a legitimate way to name a branch that exists.

Under `existing: true` nothing is minted from agent text. The argument is
a lookup key run through `BranchCatalog.lookup`, the same oracle the modal
uses, and what reaches git is a `BranchRef` git itself listed. The agent
controls only the needle, which never becomes a command argument. Blank is
still refused. `start_point` alongside `existing: true` is an error, not a
silent no-op: an existing branch already has its history, and quietly
dropping the argument would leave the agent believing it forked.

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
catalog under the shared join timeout, resolve, then call
`gitStatusService.addWorktreeForBranch` — already written and already
covered (`GitStatusService.java:289-320`, `GitStatusServiceTest.java:416-455`).

The directory derives from `catalog.localName(ref)`, so `origin/feat/login`
and `feat/login` land on the same path, and `WorktreeNaming` slugs it as it
does for the create path.

`tracking` is present only when a remote-only branch was adopted, naming
the ref the new local branch follows. The response is otherwise unchanged:

```json
{ "path": "/Users/x/dev/wt/drydock-login",
  "branch": "feat/login",
  "tracking": "origin/feat/login" }
```

### Errors an agent can act on

- Unknown name: `No branch named 'feat/login' in this repository; omit existing to create it.`
- Occupied: the reason and the holding path — the same three cases the
  modal distinguishes.
- Remote repository: the message `createWorktree` already gives.

The occupancy wording exists once today, privately, in
`NewWorktreeState.blockedHint` (`NewWorktreeState.java:84-95`). Two
surfaces need it now, so it moves to
`app.drydock.git.BranchOccupancy.describe(BranchRef)` returning
`Optional<String>` — empty when the branch is available — and both call
it. AGENTS.md requires shared presentation logic to live in one utility
with no per-view copies, and this is exactly that: three cases that must
stay distinct because they are escaped differently (`git worktree unlock`
vs. `git worktree prune` vs. nothing).

The budget keeps its current shape: `chargeWorktree` before the call,
`refundWorktree` in the `McpToolException` catch. Because resolution
happens inside `createWorktreeOnExistingBranch`, every new failure path is
inside that catch and refunds.

## Testing

There is no TestFX in this project, so nothing reachable only through a
`Node` is testable. That is why every decision stays in
`NewWorktreeState.derive` and the `⌘E` handler is a bare call into
`setMode`.

`NewWorktreeStateTest` is rebuilt around the mode parameter: one case per
row of the table above, plus the three preview forms held in lockstep with
the commands `MainWorkspace` actually issues.

`McpToolRouterWorktreeTest` gains: `existing: true` on a free local branch;
on a remote-only branch, asserting the `--track` form and the `tracking`
field; on an occupied branch, asserting the reason and path reach the
agent; on an unknown branch; with `start_point`, asserting the conflict;
the default path unchanged; and a refund assertion on each failure.

A new `BranchOccupancyTest` covers the three occupancy cases and the
available case, so the wording both surfaces depend on is pinned in one
place.

`GitStatusServiceTest` needs nothing new — `addWorktreeForBranch` is the
call this design reuses, not one it introduces.
