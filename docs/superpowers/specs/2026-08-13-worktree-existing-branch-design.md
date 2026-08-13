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

> **Revision note.** Two adversarial review rounds, three reviewers per
> round (fact-check, mechanism, spec quality). Thirty-eight findings were
> confirmed and are corrected in place. "What the adversarial review
> changed" at the end records every one, including five claims in earlier
> drafts that were false — one of which was a *refutation* that turned out
> to be wrong, verified only by disassembling the JavaFX jar — and one
> latent bug in shipped code the review surfaced.

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

**The rule, stated once:** a name is checked when, and only when, it is
about to be minted with `-b`. That is the typed name in create mode, and
the derived `localName` in remote adoption. Never a local branch being
checked out.

## One classifier, two audiences: `BranchCheckout`

Resolution moves into `app.drydock.git.BranchCheckout`, and both surfaces
switch on its outcome:

```java
public sealed interface Outcome {
    record Ready(BranchRef ref, String localName, Optional<String> tracking) implements Outcome { }
    record NoSuchBranch(String text)                                        implements Outcome { }
    record Occupied(BranchRef ref)                                          implements Outcome { }
    record ShadowsRemote(String localName, String remote)                   implements Outcome { }
}

/** Never null-tolerant on catalog; blank text yields NoSuchBranch(""). */
static Outcome resolve(BranchCatalog catalog, String text);

/** The matched remote, when `name` would shadow one. */
static Optional<String> shadowsRemote(String name, Collection<String> remotes);

/** The dropdown row's full text: the name, plus why it cannot be picked. */
static String dropdownLabel(BranchRef branch);
```

`Ready.tracking` is present only for a remote-tracking ref.
`ShadowsRemote` can therefore only arise from remote adoption — by the
rule above, a local branch is never shadow-checked — and a remote ref is
never itself occupied (`merge` drops remote refs whose local counterpart
exists, `BranchCatalog.java:76`). **`Occupied` and `ShadowsRemote` are
mutually exclusive by construction**, so no precedence between them is
needed.

`resolve` requires a non-null catalog; the modal short-circuits while the
catalog is loading (see the hint rules) and never calls it with null.

`shadowsRemote` returns the *matched remote name*, because both callers
need it: `BranchNames.validate` names it in its message, and
`ShadowsRemote` carries it. `BranchNames` keeps its own message text and
its `McpToolException`; only the whole-first-component comparison moves —
case-insensitivity and "a remote name may itself contain a slash" travel
with it unchanged (`BranchNames.java:17-29`). `app.drydock.git` gains no
dependency on `app.drydock.mcp`; the direction is the other way.

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
this switch follows that precedent, and `app.css` gains one rule hiding
the radio dot inside `.seg-toggle-button`. Taking `ToggleButton` plus a
`selectedToggleProperty` listener that re-selects on null would also work;
`RadioButton` is chosen because the guarantee comes from JavaFX rather
than from a listener someone must remember not to delete.

The modal opens in **New branch** mode, so the seeded `feat/` and existing
muscle memory survive unchanged. `Mode` is a two-constant enum nested in
`NewWorktreeState`, shared by the record, `CreateHandler` and
`MainWorkspace`. It lives in a field on the modal, written from four
places — the two segment handlers, `⌘E`, and **Check it out instead** —
each of which writes the field *and* sets the segment's selection through
one private `setMode(Mode)` so the field and the switch can never diverge.

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

`deriveDirectory` and `refreshState` read the **active mode's** control.
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
`WorktreeNaming.slug("")` returns `"worktree"` (`WorktreeNaming.java:22-33`),
so deriving on a mode switch into an untouched Existing picker would
rewrite `…/drydock-feat` to `…/drydock-worktree` and back on the way out.
The "unless manually edited" rule (`NewWorktreeModal.java:88-89`) is
unchanged. In existing mode the directory derives from
`Ready.localName()`, so `origin/feat/login` and `feat/login` propose the
same path.

### `derive` computes two things independently

`NewWorktreeState.derive` gains a `Mode` parameter, loses `branchLabel`
(the switch says that now; the branch row's label becomes a plain
"Branch"), and gains two components: `switchOffer`, and
`Optional<Ready> resolved` — the resolution the Create handler will use,
so nothing re-resolves at press time. `baseVisible` collapses to
`mode == NEW`.

**The hint and the disabled state are separate computations, not one
ladder.** Today `hint` is derived from the branch alone, while
`dir.isEmpty()` and `creatingInFlight` feed only `blocked`
(`NewWorktreeState.java:64-70,75`). Collapsing them into a single
first-match table would delete shipped behaviour: clear the directory
field while an occupied branch is selected and the "Already checked out
in …" hint would vanish, leaving a disabled button explaining nothing.

**`hint`**, in this precedence order:

| mode | condition | hint |
|---|---|---|
| either | catalog still loading | `Loading branches…` |
| either | catalog load failed | — (the error line speaks) |
| NEW | name resolves to an existing branch | `feat/login already exists.` (+ **Check it out instead**) |
| NEW | name would shadow remote `origin` | `A branch named origin/x would shadow the remote 'origin'.` |
| NEW | **Fork from** blank | `Pick a branch to fork from.` |
| EXISTING | `NoSuchBranch`, text non-blank | `No branch named 'nope'.` |
| EXISTING | `Occupied` | today's `blockedHint` wording, unchanged |
| EXISTING | `ShadowsRemote` | `Checking out origin/origin/main would create local origin/main, shadowing the remote 'origin'.` |
| otherwise | | empty |

**`createDisabled`** is the OR of: catalog absent or failed; directory
blank; creation in flight; the hint is one of the blocking hints above;
and, per mode — NEW: branch blank, ends `/`, contains a space, or **Fork
from** blank; EXISTING: `resolved` is empty. Rows carried forward from
today are `NewWorktreeState.java:70-72` (directory, name shape, base) and
`:75` (in flight).

`Pick a branch to fork from.` is new wording for a dead end that already
ships: `baseField` is filled asynchronously from `getStatus` and only when
the repo is `OnBranch` (`NewWorktreeModal.java:118-123`), so on a detached
HEAD, an unborn branch, or a failed status call, Create is disabled today
with nothing on screen explaining it.

**Check it out instead** is a separate `Button`, shown when `switchOffer`
is true, beside the hint label, with a new `.worktree-hint-action` rule in
`app.css` — flat, link-coloured, no background, matching `.worktree-hint`'s
type size (`app.css:1426`). It copies `resolved.get().ref().name()` — the
catalog's spelling, not the raw typed text — into the existing picker and
calls `setMode(EXISTING)`. `switchOffer` is `mode == NEW && resolved.isPresent()`.

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
(`NewWorktreeModal.java:312-316`). Under an explicit mode that is false in
Existing mode, where Create is disabled. New keeps today's wording;
Existing reads "That branch no longer exists on the remote — pick
another."

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
void create(Mode mode, Optional<Ready> resolved, String branch, String base,
            Path directory, Optional<String> task, AgentKind agent);
```

fed from the `NewWorktreeState` that `refreshState()` last computed, which
the modal holds in a field. `MainWorkspace` branches on `mode` alone.
Create is only enabled in Existing mode when `resolved` is present, so
there is no "mode says existing but nothing resolved" call to make, and
the `Ready` cannot be staler than the last `refreshState()` — which every
catalog reload triggers.

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
(`:126`).

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

`McpServerTest`'s tools/list assertion gains `existing`.

### `existing` is parsed strictly

`optionalBooleanArg` returns the default for **any** non-`JsonBoolean`
value (`McpToolRouter.java:758-766`). The same file documents that some
clients stringify every argument — that is why `optionalIntArg` exists
(`:186-191`) — and `optionalStringArg` deliberately rejects a wrong-typed
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
are refs git itself listed — plus the `ShadowsRemote` guard on the derived
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

The first draft of this section guarded the wrong failure. Every git spawn
is hard-bounded *below* the join deadline: `ProcessRunner` kills the child
at `PROCESS_TIMEOUT = 15s` and throws `ProcessTimeoutException`
(`ProcessRunner.java:100-105`, `GitStatusService.java:39`), which
`GitStatusService.run` converts to
`GitCommandFailedException(command, -1, "timed out after 15s (killed)")`
(`:719-721`), and the git executor is
`newVirtualThreadPerTaskExecutor` (`:57`) so nothing queues. A deadline
expiry is therefore the *rare* failure; the common one is an add that git
was killed in the middle of.

Either way the damage is identical and it is the damage that matters:
`git worktree add` has already created the target directory and the
`.git/worktrees/<name>` admin entry before it dies, `translate` turns the
failure into a plain `McpToolException`
(`WorkspaceMcpSessionContext.java:644`), the router refunds
(`McpToolRouter.java:532-534`), and the agent's retry hits
`fatal: '<dir>' already exists`.

So the rule is about *outcome knowledge*, not about which clock fired:

- A failure from the add whose outcome is **unknown** — timeout, kill or
  interrupt, all of which `GitStatusService` reports as
  `GitCommandFailedException` with `exitCode == -1` (`:714-724`) — and a
  deadline expiry after the add was issued, both throw
  `McpWorktreeMayExistException`, a public top-level class in
  `app.drydock.mcp` extending `McpToolException`. (It cannot be a sibling
  of `DeadlineExceededException`, which is a *private nested* class of
  `WorkspaceMcpSessionContext`, `:623`.) Its message names the directory:
  `git worktree add was interrupted; a worktree may exist at <dir> — check before retrying.`
- The router catches it **without refunding**. Over-charging for a
  worktree that may exist is the right side to err on; refunding for one
  that does exist hands out free worktrees and a poisoned retry. The cost
  is one of `MAX_WORKTREES_PER_SESSION = 4` (`McpSessionRegistry.java:34`).
- Everything else — a clean non-zero exit, an unknown branch, an occupied
  branch, a remote repository — refunds as today.

`createWorktreeOnExistingBranch` gets a private
`EXISTING_BRANCH_TIMEOUT_SECONDS = 65`, shared across every wait via one
`joinBy` deadline per the rule the `join` Javadoc states (`:586-590`).
Private, unlike `START_SESSION_TIMEOUT_SECONDS`, which is public only
because `MainWorkspace` must derive a provably smaller budget from it
(`:81-88`) — nothing here hops to the FX thread, so nothing must be
smaller. The arithmetic: `listBranchesBlocking` runs `git remote` then
`git for-each-ref` **sequentially** (`:545,550`), `worktreeService.list`
runs concurrently, then the add — three bounded waits at 15s plus up to 2s
of reader-join after a kill each, so ~57s worst case. Sixty-five seconds
keeps expiry genuinely exceptional rather than routine; the connection can
be held that long only when git itself is wedged, and the alternative is
answering with a refund for a worktree that exists.

`createWorktree` adopts the same non-refunding exception, since its own
15s add under a 20s join has the identical hole. **That is a behaviour
change to a shipped tool** and lands as its own commit inside piece 2, not
folded into the new feature.

### Errors an agent can act on

- Unknown name: `No branch named 'feat/login' in this repository; omit existing to create it.`
- Occupied: the path, and `translate`'s existing register — the human
  unlocks or prunes from the UI, never an instruction for the agent to run
  a repo-wide mutation.
- Shadows a remote: the name that would be minted and the remote it would
  shadow.
- Remote repository: the message `createWorktree` already gives.
- Interrupted add: `McpWorktreeMayExistException`'s message above.

## Testing

**This project has TestFX.** `app/build.gradle.kts:97-106` pulls in
`testfx-core`, `testfx-junit5` and Monocle, and `tasks.test` runs headless
through Monocle (`glass.platform`, `monocle.platform`, `javafx.headless`,
`headless.geometry`, `:126-130`). Twenty-two test classes use it today,
several driving real `KeyCode` events through a live scene
(`ReviewDestinationViewTest`, `SearchRailViewTest`). Earlier drafts of
this design claimed the opposite, inheriting a stale comment at
`NewWorktreeState.java:13-14`, which this work corrects.

**`NewWorktreeStateTest`,** rebuilt around the `Mode` parameter: every
hint row; `createDisabled` as an OR, including the four rules carried
forward; the four preview forms in lockstep with the commands
`MainWorkspace` issues; and explicitly, the two regressions a first-match
reading would cause — occupied branch *plus* blank directory still shows
the occupancy hint, and pressing Create does not wipe it.

**New `BranchCheckoutTest`:** the four outcomes; `shadowsRemote` returning
the matched remote, including the case-insensitive and slash-containing
remote names inherited from `BranchNames`; that a *local* branch named
`origin/main` resolves `Ready`, not `ShadowsRemote`; that the
`origin/origin/main` remote ref resolves `ShadowsRemote`; and
`dropdownLabel` reproducing `BranchRefConverter.describe`'s exact output
for all four branch states.

**Moved tests.** `BranchRefConverterTest`'s occupancy assertions move to
`BranchCheckoutTest`; the class keeps one delegation test proving
`describe` still returns `dropdownLabel`'s text, since the converter's
identity contract is separately load-bearing. `BranchNamesTest` keeps
every message assertion (the messages do not change) and gains nothing;
the shadow *classification* cases are duplicated, not moved, because both
the rule and its caller need pinning.

**View tests — new `NewWorktreeModalTest` (TestFX + Monocle):** `⌘E` flips
the mode with the caret in the branch editor; clicking the selected
segment does not clear the mode; the two pickers swap `visible`/`managed`;
focus follows the mode; each control keeps its own text across a round
trip; the directory does not re-derive into `drydock-worktree` when the
newly active control is blank; segments and **Check it out instead** are
disabled while a creation is in flight; and the catalog-reload editor-wipe
sequence does not rewrite the visible directory.

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
refused *before* the charge, asserted by budget state; the default path is
unchanged; ordinary failures refund and `McpWorktreeMayExistException`
does not. `FakeMcpSessionContext` gains a settable canned
`ExistingBranchWorktree` and a per-method failure so these outcomes can be
expressed. `McpServerTest` asserts `existing` in the tools/list schema.

**Not automated:** that the revived `.seg-toggle` styling and the new
`.worktree-hint-action` read correctly in both themes, and that the `⌘E`
tooltip and overlay row say the same thing as the binding. Eyeball checks
at review time.

## Landing order

1. **`BranchCheckout`** — a genuine no-op extraction. `shadowsRemote`
   moves out of `BranchNames` (messages unchanged), `dropdownLabel`
   replaces `BranchRefConverter.describe`, `resolve` and `Outcome` are
   added with no caller yet. Nothing observable changes.
2. **MCP `existing`** — the descriptor, the strict reader, the SPI method,
   the deadline and the refund rule. Carries all the irreversible risk and
   is exercisable without touching the UI. The `createWorktree` refund
   change is a separate commit within it.
3. **The modal switch** — the largest diff, and the only part needing view
   tests. The two modal behaviour changes ride here, not in piece 1: the
   create path gains the shadow check it never had, and remote adoption
   starts refusing `ShadowsRemote`.

## What the adversarial review changed

Two rounds, three reviewers each (fact-check, mechanism, spec quality).
Every finding was verified against the code before being accepted.

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
   refunded. Now keyed on outcome knowledge (`exitCode == -1`).
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
    record now carries `Optional<Ready>`.
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
`Occupied`/`ShadowsRemote` are mutually exclusive by construction;
`existing: true` skipping `BranchNames.validate` is safe because only
catalog-listed refs reach git; `McpBudgetExhaustedException extends
Exception`, not `McpToolException`, so the refund catch cannot swallow it;
nothing new blocks the FX thread; a stale *available* verdict falls
through to git's own refusal, which surfaces and refunds correctly; and
`⌘E` collides with nothing in `app/src/main` or in JavaFX 26's Mac text
bindings.

**Known, out of scope.** `⌘1`-`⌘4` switch the workspace view behind an
open modal, and `⇧/` replaces an open modal outright. `optionalBooleanArg`
is lax at two other call sites (`McpToolRouter.java:312,422`).
`AgentSelector` has the `ToggleButton` null-selection bug this design
avoids. None is this change's business; all are worth their own fix.
