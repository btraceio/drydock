# Worktree from an existing branch

## Problem

The create-worktree modal can only start a worktree on a *new* branch: it
always runs `git worktree add <dir> -b <branch> [<start-point>]`. Resuming
work on a branch that already exists — one left behind by an earlier
session, or one someone else pushed — has no path through the UI.

The modal should also open a worktree on an existing branch, local or
remote, without inventing a new branch name.

## Approach

One combo box, not a mode toggle. The existing "Branch" field becomes an
editable `ComboBox` listing every branch; whether the modal creates a
branch or checks one out is *derived* from whether the entered text names
an existing branch. Nothing new for the user to learn: typing a fresh name
behaves exactly as it does today.

Remote branches are checked out as local tracking branches — the normal
intent — never detached.

Checking out a branch the app did not create breaks an invariant the
delete path silently relies on, so this design also has to make deletion
provenance-aware. See "Branch provenance".

## Branch listing

Three existing pieces compose; no new porcelain parser.

**Refs.** `GitStatusService.listBranches(repositoryRoot)` replaces
`listLocalBranches`, running:

```
git for-each-ref --format=%(refname)%09%(symref) refs/heads/ refs/remotes/
```

Refs with a non-empty `symref` are dropped. This is the *only* correct way
to drop `origin/HEAD`: `%(refname:short)` renders `refs/remotes/origin/HEAD`
as **`origin`** — not `origin/HEAD` — so any name-shaped filter both misses
it and leaves a phantom branch called `origin` in the list. Short names are
derived from the full `refname` by stripping `refs/heads/` or
`refs/remotes/`.

It returns `List<BranchRef>` with `checkedOutAt`/`stale` unpopulated:

```java
public record BranchRef(String name,          // "feat/minors" or "origin/feature/x"
                        boolean remote,
                        Optional<Path> checkedOutAt,
                        boolean stale)        // occupying worktree is prunable/missing
```

**Occupancy.** `WorktreeService.list` already parses `git worktree list
--porcelain` (`WorktreeService.parse`, which correctly skips `bare`
stanzas and is already tested). It gains two fields it currently discards —
`Worktree` grows `prunable` and `locked` booleans, parsed from the
`prunable`/`locked` attribute lines. Both are needed: a branch owned by a
deleted-on-disk worktree still blocks `git worktree add` with `fatal: 'x'
is already used by worktree at '<gone>'`, and presenting that as a live
checkout is a dead end with no way out.

**Composition.** A new `BranchCatalog` in `app.drydock.git`:

```java
static List<BranchRef> merge(List<BranchRef> refs, List<Worktree> worktrees)   // pure
static CompletableFuture<List<BranchRef>> load(GitStatusService, WorktreeService, Path root)
```

`merge` fills `checkedOutAt`/`stale` from the worktree list and drops a
remote branch whose *local name* already exists locally (picking `origin/x`
when local `x` exists should simply be `x`). Being pure, it is where the
listing rules get unit-tested. Ordering: local branches first, then
remotes, each alphabetical.

### Local name of a remote ref

Remote names may themselves contain slashes (`git remote add team/fork …`
is legal), so stripping the first path segment is wrong — it would turn
`team/fork/feature/x` into `fork/feature/x`. The local name is computed by
stripping the **longest matching `<remote>/` prefix** among the names
returned by `git remote`. This same rule feeds the shadowing check above
and the directory derivation below.

A local branch may also literally be named `origin/foo`, which is
string-identical to the remote-tracking short name for `foo` on `origin`.
`BranchRef` is therefore keyed on `(name, remote)`, and lookups prefer the
local ref.

## Creating the worktree

`GitStatusService.addWorktreeForBranch(root, worktreeDirectory, BranchRef)`:

- local → `git worktree add <dir> --end-of-options <name>`
- remote → `git worktree add <dir> -b <localName> --track --end-of-options <name>`

Both forms were verified against git 2.49.0; the second sets
`branch.<localName>.remote`/`.merge` as intended. As with the existing
create path, the parent directory is created first, and `--end-of-options`
keeps a ref that looks like a flag from being parsed as one.

`createWorktree(...)` — the `-b` + fork-from path — is unchanged.

## Refresh

`GitStatusService.fetchAll(root)` runs `git fetch --all --prune`. Three
things this needs that the current plumbing does not provide:

- **A longer timeout.** `GitStatusService.run(...)` hardcodes the 15s
  `PROCESS_TIMEOUT`; a `run(command, timeout)` overload is added and the
  fetch gets a network-scale `FETCH_TIMEOUT`.
- **No credential prompting.** `ProcessRunner` leaves stdin an open pipe
  nobody closes, so a fetch against an HTTPS remote needing credentials
  would block on the prompt for the whole timeout and then report a bare
  "timed out". `ProcessRunner.run` gains an option to redirect stdin from
  `/dev/null`, and the fetch spawns with `GIT_TERMINAL_PROMPT=0` so it
  fails fast with a message naming the auth failure.
- **Prune awareness.** `--prune` can delete the very remote-tracking ref
  the user has selected; see the mode section.

## Modal behaviour (`NewWorktreeModal`)

`branchField` becomes an editable `ComboBox<BranchRef>` seeded with the
current `feat/` default, in an `HBox` with a ⟳ refresh button to its right.

**The `StringConverter` must be the identity on `BranchRef.name`.** On an
editable `ComboBox`, selecting an item writes `converter.toString(item)`
into the editor, and mode is derived from editor text — so any decoration
in `toString` ("main — in use (…)") would flip the modal into create mode
with a space-containing name the moment the user picks a branch from the
dropdown. Decoration lives *only* in the `ListCell`/`ButtonCell` factory;
`fromString` is `toString`'s exact inverse.

Mode is recomputed by a single `refreshState()` on every editor change,
every list load, and every refresh. It looks the text up in the catalog —
exact match on `(name, local)` first, then `(name, remote)`, then
`<remote>/<text>` for each remote — yielding `Optional<BranchRef>`:

- **empty → create mode.** Field label "New branch", "Fork from" row
  visible, preview `$ git worktree add <dir> -b <branch> <base>`. Today's
  behaviour verbatim.
- **present → checkout mode.** Field label "Existing branch", "Fork from"
  row hidden (`setVisible(false)` and `setManaged(false)` together),
  preview showing the checkout or `--track` command that will run.

`refreshState()` is the **only** writer of `createButton`'s disabled state.
`showError` must not re-enable Create directly (it does today,
`NewWorktreeModal.java:189`) — otherwise a creation failure re-enables a
button the derived state says must stay disabled. Create is disabled when:
the catalog has not loaded yet; the matched branch has a `checkedOutAt`; or
create-mode validation fails (non-empty branch, no trailing `/`, no
spaces, plus base and directory).

**Two message slots, not one.** The blocking hint ("already checked out in
~/src/olifer", "stale worktree — run `git worktree prune`") is a derived
property of the current selection and gets its own label under the field.
`errorLine` keeps its existing job: transient failures from a submitted
action. Reusing one label for both leaves a stale creation error looking
like a blocking hint.

**Load gating.** The catalog loads asynchronously while the modal focuses
the branch field immediately (`NewWorktreeModal.java:156-159`), so a fast
typist can enter an existing branch name before the list arrives and get
create mode, `-b`, and `fatal: branch already exists`. Until the catalog
resolves, the field shows "Loading branches…" and Create is disabled; on
arrival `refreshState()` re-runs. A load *failure* surfaces in `errorLine`
— never a silent empty list, which would make every branch read as new.

**Refresh.** ⟳ disables itself and shows an in-flight state, reloads the
catalog, and re-runs `refreshState()`. If the text matched a branch before
the refresh and does not after (`--prune` removed the ref), the modal says
so rather than silently flipping to create mode. Every completion path —
success, failure, early return — re-enables the button.

The optional start-task field applies to both modes.

## Directory derivation

The branch name is normalized to its local form (per the longest-remote-
prefix rule) *before* `WorktreeNaming.defaultDirectory`, so the derived
directory is identical whether the user picked the local or the remote
spelling. Auto-derivation still stops permanently after a manual edit.

## Branch provenance

`WorktreeService.removeBlocking` force-deletes the branch with `git branch
-D --end-of-options <branch>` (`WorktreeService.java:182-188`). That is
safe today only because every worktree the app creates owns a branch it
just created. Checking out a pre-existing branch breaks that invariant:
resume `origin/colleagues-branch`, hit 🗑, and a branch the user did not
create is force-deleted.

`ManagedClaudeSession` gains `boolean branchCreatedHere`.
`SessionManager.prepareWorktreeSession` takes it as a parameter; the
create-mode path passes `true`, the checkout path `false`.

**Two call sites run `git branch -D`, not one.** Besides
`WorktreeLifecycleController.handoffDelete` (`:335`), the sidebar's 🗑 on an
*unopened* worktree row (`RepositorySidebar.java:796`, and its forced retry
at `:825`) deletes the branch of any worktree — including ones merely
discovered on disk that drydock never created. One predicate covers both:

```java
BranchOwnership.mayDeleteBranchOf(List<ManagedClaudeSession>, Path worktreeRoot)
```

— true only when a session for that worktree records `branchCreatedHere`.
Both sites pass `Optional.of(branch)` when it holds and `Optional.empty()`
otherwise: the worktree is removed, the branch survives. This also closes
the pre-existing hazard for discovered worktrees, which is a deliberate
behaviour change — the sidebar stops force-deleting branches of
externally-created worktrees.

Persistence follows the codec's established lenient pattern (as `prState`
and `remote` did): the field is added within schema version 2, and an
absent or malformed value decodes to **`true`**, since every already-
persisted session did create its own branch. No version bump.

## Testing

`GitStatusServiceTest` / a new `BranchCatalogTest`, against real temporary
repositories — including one *cloned* from another, so `refs/remotes/…`
and `origin/HEAD` actually exist (tests against a remote-less repo would
pass vacuously):

- `listBranches` includes local and remote-tracking refs and excludes the
  symref, with no phantom `origin` entry.
- `merge` populates `checkedOutAt` for a branch checked out in a second
  worktree, leaves it empty for an idle branch, and sets `stale` for a
  branch whose worktree directory was deleted.
- `merge` drops a remote branch shadowed by a same-named local one, and
  keeps a local branch literally named `origin/foo` distinct from
  `origin`'s `foo`.
- Local-name derivation strips the longest remote prefix, including a
  remote whose own name contains a slash.
- `addWorktreeForBranch` on an existing local branch checks it out without
  creating a new ref; on a remote branch it creates a local branch with
  `branch.<name>.remote` set.
- Adding a branch already checked out elsewhere throws
  `GitCommandFailedException`.

`WorktreeServiceTest` gains `prunable`/`locked` parsing cases.

**There is no TestFX and no FX toolkit in unit tests** —
`RemoteRepositoryModalTest` only exercises a pure static helper. So the
modal's logic has to live in toolkit-free units to be testable at all:
`BranchCatalog.lookup` (the mode oracle) and `BranchRefConverter`
(`javafx.util.StringConverter` instantiates fine without the toolkit). The
converter's identity-on-name property is the single most likely thing to
ship broken and gets its own test; the `ComboBox` is a thin delegate over
both.

What no unit test can reach — the mode flip on screen, the hidden "Fork
from" row, the refresh button's in-flight state — is verified by launching
the app against a scratch repository and validating a *series* of
screenshots across each interaction.

Codec test: a persisted session without `branchCreatedHere` decodes to
`true`.

## Out of scope

- Auto-fetching when the modal opens (refresh is explicit).
- Detached-HEAD worktrees on a tag or SHA. (A ref name that is both a tag
  and a branch resolves to the branch and warns on stderr; behaviour is
  correct, output is merely noisy.)
- Remote (SSH) repositories: `RepositorySidebar` already hides "New
  worktree" when `repository.isRemote()`, so none of these local-only
  commands are reachable for one.
- Changing merge or PR handoff. (Delete *is* in scope — see "Branch
  provenance".)
