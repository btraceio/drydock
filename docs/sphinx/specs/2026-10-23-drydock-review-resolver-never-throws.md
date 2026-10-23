# Spec: drydock-review-resolver-never-throws

Run: `fix-pr-review-action-20261023-seal1`. Design: `.sphinx/address/design.md`.

## Problem

1. **F-007b09b98e43.** `PullRequestReview.ReviewPrompt.drydockReview` builds its
   resolver as `supplyAsync(..., DrydockPluginService.getInstance().executor())`.
   The executor argument is evaluated synchronously, outside any guard. If the
   service is null, or the executor is shut down, an NPE or a
   `RejectedExecutionException` escapes `resolve()`. It propagates through
   `SessionDialogs.launchSession`'s `thenCompose`, so the launch fails and no
   session starts. That breaks the documented "must not throw" contract.
   `drydockInstruction` also calls `getInstance()` outside its try.
2. **F-08cf98c07e72.** `PullRequestReviewTest` does not pin the resolution
   behaviour. Only `userMessage` on unresolved prompts is tested. Without a
   seam for the platform lookups, nothing exercises resolve().
3. **F-5c1d246d33a8.** If the source window is disposed during checkout,
   `openAndLaunch` is either dropped (`invokeLater` expires on `from.getDisposed()`)
   or only logs at INFO. Either way the fresh `pr-<n>` worktree is orphaned, no
   dialog appears, and the next Review of that PR fails on the existing path.
4. **F-40119f4f9149.** `ReviewRequest.INIT_WAIT_SECONDS` and `SCOPE_WAIT_SECONDS`
   are public with no documented external consumer. The premise "no consumer"
   is wrong: `app.drydock.intellij.tasks.PullRequestReview`, in another package,
   reads both. So the fix is to document that consumer, not to narrow the
   modifier.

## Correct behaviour

- **R1:** `ReviewPrompt.resolve(session)` never throws and never returns a future
  that completes exceptionally. A synchronous throw from the resolver, or an
  exceptional completion, becomes `noPrompt(new Failed(cause(e)))`: an
  `Optional.empty()` result plus a recorded complaint.
- **R2:** `complaint` stays `volatile`, and only `noPrompt` writes it.
- **R3:** `userMessage` reports a recorded complaint before it considers
  `NoTools`, whatever `holdsAccess` is.
- **R4:** a `fixed(template)` prompt resolves at once to `Optional.of(template)`,
  has `needsTools == false`, and never records a complaint.
- **R5:** constructing `drydockReview(agent)`, the one-arg production factory,
  performs no `DrydockPluginService.getInstance()` call. Every platform lookup is
  lazy, at resolve time.
- **R6:** failure taxonomy.
  - Init future failed, timed out, or its supplier threw a RuntimeException →
    `NotReady`.
  - Backend absent, not ready, or its supplier threw a RuntimeException → `NotReady`.
  - The executor rejects or throws, the resolver throws synchronously, or an
    unexpected Throwable completes the future → `Failed(cause)`.
  - Scope resolve failed, timed out, or was interrupted, or `supportsSubagents`
    threw → `Failed(cause)`.
- **R7:** when the source window is gone at `openAndLaunch`, the unused worktree
  is removed. If removal fails, an application-level error dialog names the PR,
  the worktree path, the reason, and how to remove the worktree. There is never a
  silent orphan.
- **R8:** that removal is non-forced (`WorktreeService.remove`, never
  `removeDiscardingChanges`), and the `pr-<n>` branch is kept
  (`Optional.empty()`).
- **R9:** worktree removal runs on a pooled thread, never on the EDT.
- **R10:** there is one source of truth for the 30s bounds. `PullRequestReview`
  keeps referencing `ReviewRequest.INIT_WAIT_SECONDS` and `SCOPE_WAIT_SECONDS`,
  and the field javadoc names that consumer.

## Constraints

- `SessionDialogs`, `PrCheckoutService`, `WorktreeService`, and the
  `selectReviewAgent` logic stay unchanged.
- `ReviewRequest` changes are javadoc only: no change to any modifier or value.
- The seam follows the existing `ReviewRequest.forBackend` DI style: suppliers
  and predicates, package-private for tests.
- Java logging uses `System.Logger`. Dialogs go through
  `ApplicationManager.invokeLater` with `ModalityState.nonModal()`.
- Bounded waits: `REMOVE_TIMEOUT_SECONDS = 120`, matching TaskActions and SessionDone.
- The existing tests in `PullRequestReviewTest` must keep passing unchanged.

## Scope

### Primary fixes
- `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/PullRequestReview.java:249`.
  Defect class: an eager, unguarded platform lookup in a resolver documented as
  never throwing. Fix: make the lookups lazy behind an injectable seam, and add
  a choke-point guard in `resolve()` (F-007b09b98e43).
- `intellij-plugin/src/test/java/app/drydock/intellij/tasks/PullRequestReviewTest.java`.
  Defect class: missing test coverage of the resolution paths. Fix: add resolve()
  tests through the new package-private seam (F-08cf98c07e72).
- `intellij-plugin/src/platform/java/app/drydock/intellij/tasks/PullRequestReview.java:174`
  (and the invokeLater at :167-168). Defect class: an orphaned resource on a
  disposed-owner path. Fix: drop the expiry, remove the worktree, and show a
  dialog on failure (F-5c1d246d33a8).
- `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewRequest.java:39-41`.
  Defect class: an undocumented public API widening. Fix: document the
  cross-package consumer in the javadoc (F-40119f4f9149).

### Auto-expanded sibling fixes
None identified.
- The resolver-function `launchSession` overload has only one caller,
  `PullRequestReview.openAndLaunch`, at SessionDialogs.java:146. The other
  overload passes a completed future.
- `ReviewPrompt.fixed` already returns a completed future.
- `holdsAccess(started)` in the post-launch Task.Backgroundable is not on the
  launch chain, so it cannot cancel the session. It was assessed and excluded,
  as the design states.

## Assumptions
- F-007: a synchronous resolver or executor failure maps to `Failed(cause)`, not
  `NotReady`, as the finding suggests. A null service seen through the
  production executor lambda therefore surfaces as `Failed(<NPE message>)`.
  Through the init supplier, it surfaces as `NotReady`.
- F-5c1d: there is no relaunch in another project (the class javadoc explains
  the focus fight). Cleanup is reported with a dialog only when it fails.
- F-4011: keep the constants `public`, because narrowing them would not compile.
  Document the consumer instead.
- F-5c1d has no unit test, because `openAndLaunch` needs the IDE platform.
  Verification is by compile plus review.
