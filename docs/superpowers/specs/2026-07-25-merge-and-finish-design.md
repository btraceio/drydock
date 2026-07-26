# Merge-and-finish: one action that merges a worktree branch and closes it out

Date: 2026-07-25
Status: approved design, ready for an implementation plan

## Problem

Finish ▸ → "Merge into &lt;base&gt;" runs `git merge --no-ff` in the main checkout
and reports success as a "✓ Merged" pill in the session tab header for four
seconds (`WorktreeLifecycleController.handoffMerge`,
`OpenSessionTab.showHandoffDone`). Two things are wrong:

1. The success indication is easy to miss entirely — a small pill in the
   header of the tab you are already looking away from.
2. Nothing else happens. The worktree, the branch, the session, the tab and
   the sidebar row all survive a merge that, semantically, ended the work.
   The user must then re-open Finish ▸ and pick Delete.

## Decisions

Settled with the user; not up for revision during implementation.

- One action does everything: merge, then remove the worktree, delete the
  branch (when drydock owns it), delete the session, close the tab, drop the
  sidebar row. No separate confirmation step, no undo window.
- A dirty worktree blocks the action.
- A failed branch deletion does not stop the rest of the cleanup.
- A merge conflict is handed to the session's Claude, scoped to the main
  checkout with `git -C`.
- After the hand-off, drydock polls and continues the cleanup automatically
  once the merge is genuinely complete.
- Progress and confirmation are shown as a busy modal that becomes a success
  modal the user dismisses.
- Ignored files in the worktree (`.env`, build output) are deleted without a
  warning, exactly as today's Delete action already does.

## The flow

A new `MergeAndFinishFlow`, constructed per invocation by
`WorktreeLifecycleController` and given the services it needs
(`WorktreeService`, `GitStatusService`, `SessionManager`, `ModalLayer`, the
`openTab` lookup, `onSessionsChanged`, `onSessionDeleted`). The controller
keeps the tab header, the Finish panel, and the PR hand-off.

States, each rendered in the flow's own modal:

```
PREFLIGHT   "Checking the main checkout …"
MERGING     "Merging feat/x into main …"
CONFLICT    "Conflicts — Claude is resolving them in the main checkout …"
CLEANING    "Removing worktree …"
DONE        "✓ Merged feat/x into main"  + per-step detail  [ Done ]
STOPPED     "✗ <what went wrong>"        + what was left alone  [ Close ]
```

`MERGING` → `CLEANING` is the happy path. `CONFLICT` re-enters `CLEANING`
when the poll confirms the merge. Every path terminates in `DONE` or
`STOPPED`; there is no path that leaves the modal showing a spinner.

`CLEANING` shows one caption, not two. The cleanup is a single future
(`WorktreeSessionCleanup.run`) whose worktree → branch → session steps are
reported afterwards, per step, in `DONE`'s detail line; observing the
session-step boundary live would mean pushing a progress callback into the
app's most destructive sequence — a class deliberately free of UI concerns —
to flash a caption for the fraction of a second `deleteSession` takes. The
earlier "Closing session …" state was dropped for that reason (final review,
item 7).

Dismissing a `CLEANING`/`MERGING` modal with Esc does not cancel the work and
does not bring the modal back at the next stage: the flow remembers the
dismissal and only the terminal `DONE`/`STOPPED` render may re-show, because
an outcome must not be lost.

### Pre-flight

The merge target is verified immediately before merging, not from data
captured when the tab opened. New service call:

```java
CompletableFuture<MergeTarget> inspectMergeTarget(Path mainCheckout, String branch)

record MergeTarget(Optional<String> baseBranch,   // empty => detached HEAD
                   String baseHeadOid,
                   Optional<String> branchTipOid, // empty => branch missing
                   InProgress inProgress)         // NONE|MERGE|REBASE|CHERRY_PICK|REVERT|BISECT
```

Implemented with `git symbolic-ref --quiet --short HEAD`, `git rev-parse
HEAD`, `git rev-parse --verify --quiet <branch>`, `MERGE_HEAD` /
`CHERRY_PICK_HEAD` / `REVERT_HEAD` probes, and `rebase-merge` /
`rebase-apply` / `BISECT_LOG` under `git rev-parse --absolute-git-dir`.

The flow refuses, in `STOPPED`, when:

- `baseBranch` is empty. A detached main checkout would take the merge
  commit and then lose it with the branch — the reflog would be the only
  remaining reference. Note `branchNameOf` already renders this state as the
  literal string `"(detached)"`, so the panel currently offers
  "Merge into (detached)".
- `baseBranch` differs from the base the panel displayed. That label is
  resolved once at tab-header setup and captured in a closure; the user can
  have checked the main checkout out onto another branch since. Merging into
  the wrong branch and then deleting the source branch is unrecoverable in
  practice.
- `inProgress != NONE`. A merge the *user* left open makes our `git merge`
  exit 128 while `MERGE_HEAD` exists, which would otherwise read as our own
  conflict — and the hand-off would then tell Claude to commit it.
- `branchTipOid` is empty.
- The worktree is not clean.

The cleanliness gate uses `WorktreeService`'s own predicate, exposed as
`CompletableFuture<Boolean> isWorktreeClean(Path worktree)`, **not**
`GitStatus.dirty()`. `isClean` passes `--ignore-submodules=dirty` on purpose:
drydock's own `third_party/ghostty` is left modified by every build, so
gating on `GitStatus.dirty()` would leave the merge action permanently
disabled in this repository with nothing to commit.

The Finish panel renders the merge action disabled, with the caption
replaced by "Commit or discard the worktree's changes first", when the
pre-panel inspection already saw an unclean worktree. This requires
`FinishWorktreePanel.action()` to support a disabled variant; the panel's
existing `Context.dirty()` is only interpolated into the summary headline
today. The pre-flight check still runs — the panel's data is seconds old and
the consequence of acting on it is destructive.

### The merge and its success oracle

`mergeIntoBase` is replaced by a call that merges and then *verifies what
actually happened*, returning a verdict instead of throwing on non-zero:

```java
CompletableFuture<MergeVerdict> merge(Path mainCheckout, String branch, MergeTarget target)

sealed interface MergeVerdict {
    record Merged(String mergeCommitOid) implements MergeVerdict {}
    record AlreadyMerged() implements MergeVerdict {}
    record Conflicted(List<String> unmergedPaths) implements MergeVerdict {}
    record NotMerged() implements MergeVerdict {}                  // nothing in progress, not merged
    record Refused(String detail) implements MergeVerdict {}       // hook veto, dirty base, …
    record Indeterminate(String detail) implements MergeVerdict {} // state we did not predict
}
```

Verification, run against the main checkout after `git merge --no-ff`:

- `git symbolic-ref --short HEAD` must still be `target.baseBranch()`;
  otherwise `Indeterminate`.
- `git rev-parse HEAD` → `newHead`. If it moved, `git rev-list --parents -n1
  newHead` must list **both** `target.baseHeadOid()` and
  `target.branchTipOid()` as parents. That, and only that, is `Merged`.
- If `newHead` is unchanged and `git diff --name-only --diff-filter=U`
  reports paths, `Conflicted`.
- If `newHead` is unchanged, `MERGE_HEAD` exists and the index has no
  unmerged entries, the merge was stopped by policy rather than conflict —
  `Refused`. A `pre-merge-commit` hook exiting non-zero produces exactly
  this state, and `git commit` does not re-run that hook, so treating it as a
  conflict would silently override the veto.
- If `newHead` is unchanged, nothing is in progress, and
  `merge-base --is-ancestor <tip> HEAD` passes, the branch was already merged
  before the click: `AlreadyMerged`, which proceeds to cleanup. Ancestry is
  acceptable evidence *here* only because no agent has touched the repository
  between the pre-flight snapshot and this check.
- If `newHead` is unchanged, nothing is in progress and the tip is not an
  ancestor, `NotMerged`. Reached from the hand-off poll this means the merge
  was abandoned (`git merge --abort`) — sound only because the branch check,
  the parent check and the distinct probe-failure case have already ruled out
  the other ways to get here.
- Anything else: `Refused` with git's stderr excerpt, or `Indeterminate`.

A failure of the probe itself (git missing, main checkout gone,
non-zero `rev-parse`) is a distinct outcome, never folded into
`NotMerged` — during the hand-off it means "keep waiting", and it is
reported in the timeout message.

Ancestry (`merge-base --is-ancestor`) is deliberately *not* the oracle.
A `reset --hard` or a bare `git checkout feat` in the main checkout makes
ancestry true while no merge ever happened — and in the hand-off path an agent
can reach either state. The parent-set check cannot be satisfied without a
real merge commit of the recorded tip.

Two limitations of the parent-set check, recorded rather than papered over:

- `git merge -s ours` / `-X ours` is **accepted**. It creates a genuine merge
  commit of the recorded tip, so the parent-set check passes while the base
  tree contains none of the branch's work and the modal still says "✓ Merged".
  This is deliberate and it is safe in the only sense that matters here —
  nothing is lost, because the recorded tip stays reachable from the base
  branch — but it is not the same as "the work landed". Pinned by
  `verifyMergeAcceptsStrategyOursBecauseItIsStillARealMergeCommit`.
- The check is about the tip **recorded at pre-flight**. It says nothing about
  where the branch points minutes later, when the destructive step runs, so
  the branch tip is re-read immediately before `git branch -D` and any
  movement (or a re-read that failed) refuses the delete and reports "branch
  feat/x kept — it moved since the merge". See `MergeFinishDecision`
  `forBranchDelete` (final review, item 1).

`Merged` and `AlreadyMerged` proceed to cleanup. `Conflicted` enters the
hand-off. `Refused` and `Indeterminate` end in `STOPPED` with git's own
message; nothing is deleted.

### Conflict hand-off

The flow sends the session's Claude a prompt scoped to the main checkout and
explicitly fenced:

> The merge of `feat/x` into `main` stopped on conflicts in the main checkout
> at `/path/to/repo`. Resolve the conflicted files there using
> `git -C /path/to/repo …` and complete the merge with
> `git -C /path/to/repo commit`. Do not modify this worktree. Do not run
> `merge --abort`, `reset --hard`, `checkout`, or `merge -s ours` — if you
> cannot resolve the conflicts, say so and stop.

Then it polls `verifyMerge(mainCheckout, target)` — the same verification as
above, without re-running the merge — every 4s for up to 5 minutes:

- `Merged` → cleanup continues; `DONE` notes that Claude resolved conflicts.
- `Conflicted` → keep waiting.
- `NotMerged` → `STOPPED`: the merge was abandoned. Nothing is deleted.
- `Refused` / `Indeterminate` / probe failure → keep waiting; these are
  transient or unknown, never a reason to delete anything. A probe that
  errors is recorded and surfaced in the timeout message.
- On timeout → `STOPPED`: "Merge not confirmed — check the terminal. The
  merge may still be open in the main checkout." Nothing is deleted.

Two honest limitations, accepted rather than solved:

- `sendPrompt` (`OpenSessionTab.sendPrompt` → `TerminalBridge.sendPrompt` →
  `surface.submitLine`) has no notion of whether the agent is mid-turn or
  whether a permission dialog is on screen. The prompt can be swallowed, in
  which case the flow waits out its 5 minutes and reports "not confirmed".
  The existing PR hand-off shares this weakness; here the consequence is a
  stalled flow, never a wrong deletion, because the verdict is what gates the
  destructive half.
- The agent works outside its own cwd. The prompt is explicit about it, and
  the verdict check is what protects the user if the agent does something
  else.

The polling needs its own helper: `pollHandoffResult` is typed
`Supplier<CompletableFuture<Optional<T>>>` with a two-valued
present/absent contract and maps probe exceptions to "not confirmed yet", so
it cannot express "keep waiting" versus "stop now" versus "the probe itself
failed".

### Cleanup

One shared method, used by **both** this flow and `handoffDelete`:

```java
CompletableFuture<CleanupOutcome> cleanUpWorktreeSession(
        ManagedSessionId sessionId, Path repositoryRoot, Path worktreeRoot, String branch)

record CleanupOutcome(boolean worktreeRemoved, BranchResult branch, boolean sessionDeleted,
                      Optional<String> detail)
enum BranchResult { DELETED, KEPT_NOT_OURS, DELETE_FAILED }
```

It removes the worktree, deletes the branch when
`SessionManager.mayDeleteBranchOf` allows it, deletes the session, then runs
`onSessionDeleted` and `onSessionsChanged`.

Invariant: **the session is deleted only if the worktree is gone.** A branch
that could not be deleted does not stop it, but a worktree that survived
does — that worktree still holds files the user has to look at, and the
session's terminal and conversation are how they look. Closing it would
throw away the context needed to finish the job. Today's equivalent is a private
lambda body inside `handoffDelete`, gated on the tab still being open and
ignoring `deleteSession`'s failure — so "session closed" can be reported when
it was not. Duplicating the app's most destructive sequence into a second
implementation is what this extraction avoids; `handoffDelete` is refactored
onto it.

`handoffDelete` deliberately does **not** keep its old "✓ Removed" pill or its
1.2s presentation delay (an earlier draft of this spec said it would). With the
removal now confirmed synchronously, the pill would be negated in the same FX
pulse — by the tab disappearing, or by the Finish button being restored when
the session survived — so the tab disappearing is the feedback. The code says
so at the call site; do not "restore" either of them.

Branch-delete failure must be distinguishable from worktree-removal failure.
`WorktreeService.remove` throws one `GitCommandFailedException` for either
half, and six sidebar callers depend on its current contract, so the minimal
change is a dedicated `BranchNotDeletedException` (a subtype, so existing
callers keep their message) thrown at the branch-delete step only.

A worktree removal that fails *after* a committed merge is the expected case
in the conflict path — Claude has been running in that worktree for minutes
and any file it wrote there makes `git worktree remove` refuse with
`WorktreeNotCleanException`. `DONE` therefore words every step from
`CleanupOutcome` rather than from a fixed string:

```
✓ Merged feat/x into main
  worktree removed · branch feat/x deleted · session closed
  worktree removed · branch feat/x kept (already existed) · session closed
  worktree removed · branch feat/x kept (could not delete) · session closed
  worktree kept — it has uncommitted changes · branch feat/x kept · session left open
```

"branch kept (already existed)" comes from `mayDeleteBranchOf`, matching the
wording `FinishWorktreePanel.Context.deleteCaption()` already uses. A merge
that succeeded is never reported as a failure just because cleanup was
partial; the modal says what happened per step.

### Modal and lifecycle

- **The flow owns its liveness.** Every existing async completion and poll
  step returns silently when `openTab.apply(sessionId) == null`. Keeping that
  here would strand the modal — and the terminals hidden by
  `setTerminalsObscured(true)` — whenever the tab closes mid-flow, which
  AGENTS.md forbids outright. The flow proceeds on its own state; the tab is
  updated only when the lookup happens to return one.
- **One flow per session.** The controller holds a set of sessions with a
  flow in flight. `FinishWorktreePanel.runAndClose` closes the panel before
  running the action and modal dismissal does not cancel work, so without
  this guard Esc → Finish ▸ → Merge runs a second cleanup that fails on an
  already-deleted worktree and reports an error *after* the first flow
  reported success.
- **The success modal never steals the layer.** `ModalLayer.show` does
  `getChildren().setAll(modal)` and overwrites `onClosed` without invoking
  the previous one, so a flow completing while the user has since opened
  another modal would silently discard it. The flow only shows its terminal
  modal if the layer is showing its own node (the `busy.getParent() == null`
  idiom `showFinishPanel` already uses) or nothing at all; otherwise it falls
  back to the tab's transient notice and the sidebar refresh.
- **Errors render inside the flow's modal**, not through `UiErrors.show`,
  whose `alert.showAndWait()` spins a nested FX event loop underneath an
  in-scene modal while `PauseTransition` and `runLater` callbacks keep
  firing.
- Dismissing the progress modal does not cancel the work, per the decisions
  above; the terminal modal still appears, subject to the layer check.

## Files touched

- `git/WorktreeService.java` — `inspectMergeTarget`, `merge`/`verifyMerge`,
  `isWorktreeClean`, `BranchNotDeletedException`; `mergeIntoBase` removed.
- `ui/MergeAndFinishFlow.java` — new.
- `ui/WorktreeLifecycleController.java` — constructs the flow, holds the
  in-flight set, gains `cleanUpWorktreeSession`, `handoffDelete` refactored
  onto it, `handoffMerge` deleted.
- `ui/FinishWorktreePanel.java` — disabled action variant, merge caption
  states the full outcome.

## Tests

1. `WorktreeServiceTest` (real git, matching the file's existing style):
   `inspectMergeTarget` reports detached HEAD, a missing branch, and each
   in-progress operation; `merge` returns `Merged` with the right parents on
   a clean merge, `AlreadyMerged` when up to date, `Conflicted` with the
   unmerged paths on conflict, `Refused` for a `pre-merge-commit` veto;
   `verifyMerge` returns `Merged` after a conflict resolved and committed by
   hand (the hand-off's success path), `Merged` after `merge -s ours` (a real
   merge commit of the recorded tip — see the accepted limitation above),
   `Indeterminate` after a `reset --hard` and after the base branch is
   switched, and `NotMerged` after `merge --abort`; `isWorktreeClean` ignores a dirty
   submodule but
   reports a submodule commit bump; `remove` throws
   `BranchNotDeletedException` when only the branch deletion fails.
2. No test in this repository touches JavaFX, and the flow's decisions are
   what matter, so the decision logic and every string it produces live in
   an FX-free `MergeFinishDecision` that maps (pre-flight state | verdict |
   cleanup outcome | timeout) → next step + copy. Tested directly: unclean
   worktree, detached HEAD, base drift, each in-progress operation and a
   missing branch each stop without merging; `Conflicted` produces the
   hand-off prompt before the hand-off and "keep waiting" after it;
   `NotMerged` after the hand-off stops as abandoned; `Refused` and
   `Indeterminate` keep waiting during the hand-off and stop before it; each
   `CleanupOutcome` shape produces its own detail line.
3. `WorktreeSessionCleanup` takes the worktree removal and session deletion
   as narrow interfaces, so its outcome composition is tested with fakes and
   no git: branch-delete failure still deletes the session, worktree-removal
   failure does not, a branch that is not ours is reported as kept.

The FX shell — `MergeAndFinishFlow`'s modal rendering and async plumbing —
is not unit-tested, consistent with the rest of the `ui` package, and is
verified by running the app.

## Out of scope

- Pushing the base branch after the merge.
- Any change to the PR hand-off, the sidebar's own delete/force-delete
  flows, or the locked-worktree confirmation.
- Making `sendPrompt` aware of whether the agent is busy. Worth doing, but
  it is a terminal-bridge change that would benefit the PR hand-off equally
  and does not belong in this flow's scope.
- Keyboard reachability of the Finish panel's actions. They are `VBox`es
  with `setOnMouseClicked` rather than `Button`s; the new disabled variant
  does not make this worse, but fixing it is a separate change to the whole
  panel.
