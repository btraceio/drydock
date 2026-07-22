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

## Git layer (`GitStatusService`)

`listLocalBranches` is replaced by `listBranches(repositoryRoot)` returning
`List<BranchRef>`:

```java
record BranchRef(String name,                 // "feat/minors" or "origin/feature/x"
                 boolean remote,
                 Optional<Path> checkedOutAt) // worktree holding it, if any
```

Assembled on the service executor from:

- `git for-each-ref --format=%(refname:short) refs/heads/ refs/remotes/`
- `git worktree list --porcelain`, whose `worktree <path>` / `branch
  refs/heads/<name>` record pairs populate `checkedOutAt`.

Symbolic `*/HEAD` refs (e.g. `origin/HEAD`) are filtered out. A remote
branch whose short name already exists locally is dropped from the list:
picking `origin/x` when local `x` exists should simply be `x`.

Ordering: local branches first, then remotes, each alphabetical.

New mutation:

```java
CompletableFuture<Path> addWorktreeForBranch(Path repositoryRoot, Path worktreeDirectory, BranchRef branch)
```

- local → `git worktree add <dir> --end-of-options <name>`
- remote → `git worktree add <dir> -b <localName> --track --end-of-options <name>`

where `localName` strips the first path segment (`origin/feature/x` →
`feature/x`). As with the existing create path, the parent directory is
created first and `--end-of-options` guards a ref that could look like a
flag.

`createWorktree(...)` — the `-b` + fork-from path — is unchanged, along
with all its callers.

New read-write helper for the refresh button:

```java
CompletableFuture<Void> fetchAll(Path repositoryRoot)   // git fetch --all --prune
```

It runs with a network-scale timeout rather than the 15s `PROCESS_TIMEOUT`
used for status queries.

Both new blocking forms are package-private (`…Blocking`) so tests can
assert on thrown exception types directly, matching the existing
convention.

## Modal behaviour (`NewWorktreeModal`)

`branchField` becomes an editable `ComboBox<BranchRef>` seeded with the
current `feat/` default, laid out in an `HBox` with a ⟳ refresh button to
its right. A cell factory and `StringConverter` render a taken branch as
`main — in use (~/src/olifer)`.

On every editor-text change the text is looked up against the loaded list —
exact match on `name`, and on `<remote>/<text>` for each remote — yielding
an `Optional<BranchRef>`:

- **empty → create mode.** Field label "New branch", the "Fork from" row
  visible, preview `$ git worktree add <dir> -b <branch> <base>`. Today's
  behaviour verbatim.
- **present → checkout mode.** Field label "Existing branch", the "Fork
  from" row hidden (`setVisible(false)` and `setManaged(false)` together),
  preview showing the checkout or `--track` command that will actually run.

Create is disabled whenever the matched branch has a `checkedOutAt`, with
the reason shown in the existing `errorLine` ("already checked out in
~/src/olifer"). Validation otherwise stays as-is: create mode needs a
non-empty branch that neither ends in `/` nor contains a space, plus a base
and a directory; checkout mode needs only the directory.

The ⟳ button disables itself and shows an in-flight label while `fetchAll`
runs, repopulates the items on success, and re-evaluates the current editor
text so mode and the disabled-state recompute. Failure surfaces in
`errorLine`. Every completion path — success, failure, and early return —
re-enables the button; no stranded progress state.

The optional start-task field applies to both modes.

## Directory derivation

`WorktreeNaming.slug` already drops everything before the last `/`, so
`origin/feature/x` and `feature/x` both slug to `x` — but only by accident
of the remote prefix having a slash. The branch name is normalized to its
local form *before* derivation, so the derived directory is identical
whether the user picked the local or the remote spelling of a branch.
Auto-derivation still stops permanently after a manual edit of the
directory field.

## Wiring (`MainWorkspace`)

`promptNewWorktree`'s `CreateHandler` gains the resolved
`Optional<BranchRef>`. Create mode calls `createWorktree` as today;
checkout mode calls `addWorktreeForBranch`. Both then call
`openNewWorktreeSession(repository, localBranchName, created, task)`
unchanged — the session is tagged with the local branch name in both cases.
Failures show inline and keep the modal open, as now.

## Testing

`GitStatusServiceTest`, against real temporary repositories:

- `listBranches` includes local and remote-tracking refs, excludes
  `origin/HEAD`, and omits a remote branch shadowed by a same-named local
  one.
- `checkedOutAt` is populated for a branch checked out in a second worktree
  and empty for an idle branch.
- `addWorktreeForBranch` on an existing local branch checks it out without
  creating a new ref (the ref's SHA is unchanged and no new ref appears).
- `addWorktreeForBranch` on a remote branch creates a local branch tracking
  it (`git config branch.<name>.remote` is set).
- Adding a branch already checked out elsewhere throws
  `GitCommandFailedException`.

Plus a pure unit test for local-name derivation from a remote ref
(`origin/feature/x` → `feature/x`, and a branch containing slashes under a
non-`origin` remote).

## Out of scope

- Auto-fetching when the modal opens (refresh is explicit).
- Detached-HEAD worktrees on a tag or SHA.
- Changing how merge, delete, or PR handoff work.
