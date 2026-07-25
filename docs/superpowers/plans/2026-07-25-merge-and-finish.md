# Merge-and-finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Finish ▸ → "Merge into &lt;base&gt;" merge the worktree's branch and then close the work out — worktree removed, branch deleted, session closed, tab and sidebar row gone — reporting exactly what happened in a modal the user dismisses.

**Architecture:** Three layers. (1) `WorktreeService` gains a pre-flight probe of the main checkout and a merge call that *verifies what actually happened* instead of trusting git's exit code. (2) Two FX-free classes hold everything worth testing: `MergeFinishDecision` maps state → next step + user-visible copy, and `WorktreeSessionCleanup` composes the destructive sequence into a per-step outcome. (3) `MergeAndFinishFlow` is a thin JavaFX shell that runs the async calls and renders the modal.

**Tech Stack:** Java 26, JavaFX 26, JUnit 5, Gradle. Git is invoked as a child process through `WorktreeService`'s existing `run(List<String>)` helper (which wraps `ProcessRunner` with a 15s timeout).

Spec: `docs/superpowers/specs/2026-07-25-merge-and-finish-design.md`. Read it first; it explains *why* the merge oracle is a parent-set check and not `merge-base --is-ancestor`.

## Global Constraints

- **AGENTS.md is binding.** Read `/Users/jbachorik/src/drydock/AGENTS.md` before Task 5. The rules this plan leans on: nothing blocking on the FX thread; every user-triggered async operation shows progress immediately; **every** completion path — success, error, and early return — clears the progress state; no hand-rolled `ProcessBuilder`; every git argument list is a list, never a shell string; positional revision/branch arguments are preceded by `--end-of-options`; a failed command is never silently equal to an empty result.
- **No test in this repository touches JavaFX.** Do not add a JavaFX test dependency or boot the toolkit in a test. Logic that needs testing goes in an FX-free class (the codebase's existing pattern: `NewWorktreeState` tested by `NewWorktreeStateTest`).
- Test command: `./gradlew :app:test --tests '<fully.qualified.TestClass>'`. Compile check: `./gradlew :app:compileJava`.
- Java records and sealed interfaces are the codebase idiom for result types (see `GitBranchState`).
- Javadoc on every new public/package-private type and every non-obvious method, in the existing house style: explain *why*, name the failure it prevents.
- Copy strings are asserted verbatim in tests. Where this plan gives a string, use it exactly — including capitalisation and the `·` (U+00B7) separator.
- Commit after every task with the message given in that task's final step.

---

### Task 1: Pre-flight probe of the main checkout

**Files:**
- Modify: `app/src/main/java/app/drydock/git/WorktreeService.java`
- Test: `app/src/test/java/app/drydock/git/WorktreeServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `WorktreeService.MergeTarget` (record: `Optional<String> baseBranch`, `String baseHeadOid`, `Optional<String> branchTipOid`, `MergeTarget.InProgress inProgress`), `MergeTarget.InProgress` (enum: `NONE, MERGE, REBASE, CHERRY_PICK, REVERT, BISECT`), `CompletableFuture<MergeTarget> inspectMergeTarget(Path mainCheckout, String branch)`, `MergeTarget inspectMergeTargetBlocking(Path mainCheckout, String branch)` (package-private), `CompletableFuture<Boolean> isWorktreeClean(Path worktree)`.

- [ ] **Step 1: Write the failing tests**

Add to `WorktreeServiceTest`:

```java
    @Test
    void inspectMergeTargetReportsTheBaseBranchHeadAndBranchTip(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/x").get();
        String head = runGitCapture(repo, "rev-parse", "HEAD").strip();

        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/x").get();

        assertEquals(Optional.of("main"), target.baseBranch());
        assertEquals(head, target.baseHeadOid());
        assertEquals(Optional.of(head), target.branchTipOid());
        assertEquals(WorktreeService.MergeTarget.InProgress.NONE, target.inProgress());
    }

    @Test
    void inspectMergeTargetReportsADetachedMainCheckout(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "checkout", "--detach");

        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "main").get();

        assertTrue(target.baseBranch().isEmpty());
    }

    @Test
    void inspectMergeTargetReportsAMissingBranch(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/never-existed").get();

        assertTrue(target.branchTipOid().isEmpty());
    }

    @Test
    void inspectMergeTargetReportsAMergeAlreadyInProgress(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        conflictingBranch(repo, worktreeParent, "feat/conflict");
        // Leaves MERGE_HEAD behind: the merge stops, we do not abort it.
        runGitAllowingFailure(repo, "merge", "--no-ff", "feat/conflict");

        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/conflict").get();

        assertEquals(WorktreeService.MergeTarget.InProgress.MERGE, target.inProgress());
    }

    @Test
    void inspectMergeTargetReportsARebaseInProgress(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        conflictingBranch(repo, worktreeParent, "feat/rebase");
        runGitAllowingFailure(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "rebase", "feat/rebase");

        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/rebase").get();

        assertEquals(WorktreeService.MergeTarget.InProgress.REBASE, target.inProgress());
    }

    @Test
    void isWorktreeCleanIgnoresADirtySubmoduleButReportsUntrackedFiles(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub").get();
        initSubmodulesIn(worktree);
        // Exactly Drydock's own third_party/ghostty situation: the build
        // leaves modified content inside the submodule on every run.
        Files.writeString(worktree.resolve("vendor").resolve("lib.txt"), "patched by the build\n");

        assertTrue(service.isWorktreeClean(worktree).get());

        Files.writeString(worktree.resolve("scratch.txt"), "untracked\n");

        assertFalse(service.isWorktreeClean(worktree).get());
    }
```

Add these two test helpers next to `initCommittedRepo`:

```java
    /**
     * Creates {@code branch} in a worktree with a commit that conflicts with
     * a commit made on the main checkout's README, so a merge or rebase of
     * it always stops.
     */
    private static void conflictingBranch(Path repo, Path worktreeParent, String branch)
            throws IOException, InterruptedException {
        Path worktree = worktreeParent.resolve(branch.replace('/', '-'));
        runGit(repo, "worktree", "add", worktree.toString(), "-b", branch);
        Files.writeString(worktree.resolve("README.md"), "worktree version\n");
        runGit(worktree, "add", "README.md");
        runGit(worktree, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "wt change");
        Files.writeString(repo.resolve("README.md"), "main version\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "main change");
    }

    /** For git commands whose non-zero exit is the point of the fixture. */
    private static void runGitAllowingFailure(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.git.WorktreeServiceTest'`
Expected: compilation failure — `cannot find symbol: method inspectMergeTarget(Path,String)`.

- [ ] **Step 3: Implement the probe**

In `WorktreeService`, add the record next to `Worktree`:

```java
    /**
     * The main checkout's merge-relevant state, captured in one pass
     * immediately before a merge is attempted, and again afterwards to
     * decide what actually happened.
     *
     * <p>{@link #baseBranch()} is empty when HEAD is detached -- a merge
     * committed there is reachable only from the reflog once the source
     * branch is deleted, so it is a refusal, not a label. {@link
     * #branchTipOid()} is empty when the branch is gone. {@link
     * #inProgress()} is what keeps a merge the <em>user</em> left open from
     * being mistaken for one of ours: our own {@code git merge} exits 128
     * ("Merging is not possible because you have unmerged files") while
     * {@code MERGE_HEAD} sits there from their merge.</p>
     */
    public record MergeTarget(Optional<String> baseBranch, String baseHeadOid,
                              Optional<String> branchTipOid, InProgress inProgress) {

        /** A sequencer operation already running in the main checkout. */
        public enum InProgress { NONE, MERGE, REBASE, CHERRY_PICK, REVERT, BISECT }
    }
```

Add the public/blocking pair and helpers (place them just before `mergeIntoBase`):

```java
    /**
     * Captures the main checkout's merge-relevant state on this service's
     * background executor. Never throws for "not on a branch" or "branch
     * missing" -- those are reported in the result, because the caller has
     * distinct user-facing copy for each.
     */
    public CompletableFuture<MergeTarget> inspectMergeTarget(Path mainCheckout, String branch) {
        return CompletableFuture.supplyAsync(() -> inspectMergeTargetBlocking(mainCheckout, branch), executor);
    }

    /** Synchronous form of {@link #inspectMergeTarget}, package-private for tests. */
    MergeTarget inspectMergeTargetBlocking(Path mainCheckout, String branch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        return inspectMergeTarget(git, mainCheckout, branch);
    }

    private static MergeTarget inspectMergeTarget(Path git, Path mainCheckout, String branch) {
        // --quiet: a detached HEAD is an empty result, not an error.
        Optional<String> baseBranch = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")));
        List<String> headCommand = List.of(
                git.toString(), "-C", mainCheckout.toString(), "rev-parse", "HEAD");
        ProcessResult head = run(headCommand);
        if (head.exitCode() != 0) {
            throw new GitCommandFailedException(headCommand, head.exitCode(), ProcessRunner.excerpt(head.stderr()));
        }
        // refs/heads/ so a tag or a file of the same name can never answer
        // for the branch, and so the argument cannot start with '-'.
        Optional<String> tip = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "rev-parse", "--verify", "--quiet", "--end-of-options", "refs/heads/" + branch)));
        return new MergeTarget(baseBranch, head.stdout().strip(), tip, inProgressIn(git, mainCheckout));
    }

    /**
     * Which sequencer operation the main checkout is in the middle of.
     * Rebase is checked first: a conflicted rebase records {@code
     * REBASE_HEAD} plus a {@code rebase-merge}/{@code rebase-apply}
     * directory rather than {@code MERGE_HEAD}, and the directory is the
     * signal that survives every git version we support.
     */
    private static MergeTarget.InProgress inProgressIn(Path git, Path mainCheckout) {
        Path gitDir = absoluteGitDir(git, mainCheckout);
        if (Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply"))) {
            return MergeTarget.InProgress.REBASE;
        }
        if (Files.exists(gitDir.resolve("MERGE_HEAD"))) {
            return MergeTarget.InProgress.MERGE;
        }
        if (Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))) {
            return MergeTarget.InProgress.CHERRY_PICK;
        }
        if (Files.exists(gitDir.resolve("REVERT_HEAD"))) {
            return MergeTarget.InProgress.REVERT;
        }
        if (Files.exists(gitDir.resolve("BISECT_LOG"))) {
            return MergeTarget.InProgress.BISECT;
        }
        return MergeTarget.InProgress.NONE;
    }

    private static Path absoluteGitDir(Path git, Path checkout) {
        List<String> command = List.of(
                git.toString(), "-C", checkout.toString(), "rev-parse", "--absolute-git-dir");
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return Path.of(result.stdout().strip());
    }

    /** The command's first output line, or empty when it failed or printed nothing. */
    private static Optional<String> firstLine(ProcessResult result) {
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String value = result.stdout().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value.lines().findFirst().orElse(value));
    }

    /**
     * Whether the worktree at {@code worktree} holds uncommitted work,
     * asked with the same predicate {@link #remove} uses -- see {@link
     * #isClean(Path, Path)} for why {@code --ignore-submodules=dirty}
     * matters here: gating the merge on {@code GitStatus.dirty()} instead
     * would leave the action permanently disabled in any repository with a
     * build-patched submodule, Drydock's own included.
     */
    public CompletableFuture<Boolean> isWorktreeClean(Path worktree) {
        return CompletableFuture.supplyAsync(() -> {
            Path git = locator.locate()
                    .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
            return isClean(git, worktree);
        }, executor);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.git.WorktreeServiceTest'`
Expected: PASS, including the pre-existing tests in that class.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/WorktreeService.java app/src/test/java/app/drydock/git/WorktreeServiceTest.java
git commit -m "Probe the main checkout before merging a worktree branch"
```

---

### Task 2: A merge that verifies what actually happened

**Files:**
- Modify: `app/src/main/java/app/drydock/git/WorktreeService.java` (replace `mergeIntoBase`/`mergeIntoBaseBlocking` at :219-240)
- Test: `app/src/test/java/app/drydock/git/WorktreeServiceTest.java` (replace the two `mergeIntoBase*` tests at :292-324)

**Interfaces:**
- Consumes: `MergeTarget`, `inspectMergeTargetBlocking`, `inProgressIn`, `firstLine` from Task 1.
- Produces: sealed `WorktreeService.MergeVerdict` with `Merged(String mergeCommitOid)`, `AlreadyMerged()`, `Conflicted(List<String> unmergedPaths)`, `NotMerged()`, `Refused(String detail)`, `Indeterminate(String detail)`; `CompletableFuture<MergeVerdict> merge(Path mainCheckout, String branch, MergeTarget target)`; `CompletableFuture<MergeVerdict> verifyMerge(Path mainCheckout, MergeTarget target)`. `mergeIntoBase` is **removed**.

- [ ] **Step 1: Write the failing tests**

Delete `mergeIntoBaseMergesTheBranchIntoTheMainCheckout` and `mergeIntoBaseFailsInsteadOfResolvingAConflict`. Add:

```java
    @Test
    void mergeRecordsARealMergeCommitOfTheRecordedTip(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/mergeable").get();
        commitFile(worktree, "feature.txt", "new feature\n", "add feature");
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/mergeable").get();

        WorktreeService.MergeVerdict verdict = service.merge(repo, "feat/mergeable", target).get();

        WorktreeService.MergeVerdict.Merged merged =
                assertInstanceOf(WorktreeService.MergeVerdict.Merged.class, verdict);
        assertEquals(runGitCapture(repo, "rev-parse", "HEAD").strip(), merged.mergeCommitOid());
        assertTrue(Files.exists(repo.resolve("feature.txt")));
    }

    @Test
    void mergeOfAnUpToDateBranchIsAlreadyMerged(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/nothing").get();
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/nothing").get();

        assertInstanceOf(WorktreeService.MergeVerdict.AlreadyMerged.class,
                service.merge(repo, "feat/nothing", target).get());
    }

    @Test
    void mergeReportsTheConflictedPaths(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        conflictingBranch(repo, worktreeParent, "feat/conflict");
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/conflict").get();

        WorktreeService.MergeVerdict.Conflicted conflicted = assertInstanceOf(
                WorktreeService.MergeVerdict.Conflicted.class, service.merge(repo, "feat/conflict", target).get());
        assertEquals(List.of("README.md"), conflicted.unmergedPaths());
    }

    @Test
    void mergeStoppedByAPreMergeCommitHookIsRefusedNotConflicted(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/hooked").get();
        commitFile(worktree, "feature.txt", "new feature\n", "add feature");
        // git commit does NOT re-run pre-merge-commit, so treating this as a
        // conflict and telling an agent to "just commit" would override the veto.
        Path hook = Path.of(runGitCapture(repo, "rev-parse", "--absolute-git-dir").strip())
                .resolve("hooks").resolve("pre-merge-commit");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/hooked").get();

        assertInstanceOf(WorktreeService.MergeVerdict.Refused.class, service.merge(repo, "feat/hooked", target).get());
    }

    @Test
    void verifyMergeAcceptsStrategyOursBecauseItIsStillARealMergeCommit(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/ours").get();
        commitFile(worktree, "feature.txt", "new feature\n", "add feature");
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/ours").get();
        // `merge -s ours` makes merge-base --is-ancestor pass while the tree
        // contains none of the branch's work: ancestry is not the oracle.
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "merge", "-s", "ours", "--no-ff", "feat/ours");

        assertInstanceOf(WorktreeService.MergeVerdict.Merged.class, service.verifyMerge(repo, target).get());
        assertFalse(Files.exists(repo.resolve("feature.txt")));
    }

    @Test
    void verifyMergeIsIndeterminateWhenTheBaseBranchChanged(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/drift").get();
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/drift").get();
        runGit(repo, "checkout", "-b", "release/2.0");

        assertInstanceOf(WorktreeService.MergeVerdict.Indeterminate.class, service.verifyMerge(repo, target).get());
    }

    @Test
    void verifyMergeIsNotMergedAfterAnAbort(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        conflictingBranch(repo, worktreeParent, "feat/aborted");
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/aborted").get();
        assertInstanceOf(WorktreeService.MergeVerdict.Conflicted.class,
                service.merge(repo, "feat/aborted", target).get());

        runGit(repo, "merge", "--abort");

        assertInstanceOf(WorktreeService.MergeVerdict.NotMerged.class, service.verifyMerge(repo, target).get());
    }
```

Add the missing helper next to `initCommittedRepo`:

```java
    private static void commitFile(Path checkout, String name, String content, String message)
            throws IOException, InterruptedException {
        Files.writeString(checkout.resolve(name), content);
        runGit(checkout, "add", name);
        runGit(checkout, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }
```

Note on `verifyMergeAcceptsStrategyOursBecauseItIsStillARealMergeCommit`: `merge -s ours` *does* create a real merge commit with both parents, so the parent-set check accepts it — the test asserts that (`Merged`) while documenting that the tree is empty of the branch's work. This is the honest boundary of the oracle: it proves a merge commit of the recorded tip exists on the expected branch, not that the content is what the user wanted. The states it *does* reject are `checkout <branch>`, `reset --hard`, and base drift, covered by the other two tests.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.git.WorktreeServiceTest'`
Expected: compilation failure — `cannot find symbol: class MergeVerdict`.

- [ ] **Step 3: Implement the verdict, the merge, and the verification**

Replace `mergeIntoBase`/`mergeIntoBaseBlocking` (:219-240) with:

```java
    /**
     * What a merge attempt actually did, established by inspecting the
     * repository rather than by trusting an exit code or parsing stderr.
     *
     * <p>Ancestry ({@code merge-base --is-ancestor}) is deliberately NOT
     * the success oracle: a bare {@code checkout <branch>} or a {@code
     * reset --hard} in the main checkout makes it true, and both are
     * reachable by the agent that resolves conflicts -- after which the
     * caller would delete the branch that holds the only copy of the work.
     * {@link Merged} requires a commit on the expected base branch whose
     * parents are the recorded pre-merge HEAD and the recorded branch
     * tip.</p>
     */
    public sealed interface MergeVerdict {

        /** A real merge commit of the recorded tip now sits on the base branch. */
        record Merged(String mergeCommitOid) implements MergeVerdict { }

        /** The branch was already merged before the attempt; nothing to do but clean up. */
        record AlreadyMerged() implements MergeVerdict { }

        /** The merge stopped with unmerged index entries; {@code unmergedPaths} is never empty. */
        record Conflicted(List<String> unmergedPaths) implements MergeVerdict { }

        /** Nothing in progress and the branch is not merged -- from a poll, an aborted merge. */
        record NotMerged() implements MergeVerdict { }

        /** Git declined: a hook veto, a dirty base checkout, an unknown revision. */
        record Refused(String detail) implements MergeVerdict { }

        /** A repository state we did not predict; never treated as success. */
        record Indeterminate(String detail) implements MergeVerdict { }
    }

    /**
     * Runs {@code git merge --no-ff <branch>} in the main checkout and then
     * {@linkplain #verifyMerge verifies} the result. {@code target} must be
     * the {@link MergeTarget} captured immediately before this call: its
     * recorded HEAD and branch tip are what the verification is against.
     *
     * <p>The future completes exceptionally only for infrastructure
     * failures (no git executable, an unreadable repository). A merge that
     * did not happen is a {@link MergeVerdict}, not an exception, because
     * every outcome has its own user-facing copy and its own next step.</p>
     */
    public CompletableFuture<MergeVerdict> merge(Path mainCheckout, String branch, MergeTarget target) {
        return CompletableFuture.supplyAsync(() -> {
            Path git = locator.locate()
                    .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
            // --end-of-options: a branch name that looks like an option must
            // reach git as a branch name, never be parsed as a flag.
            List<String> command = List.of(
                    git.toString(), "-C", mainCheckout.toString(),
                    "merge", "--no-ff", "--end-of-options", branch);
            ProcessResult result = run(command);
            MergeVerdict verdict = verify(git, mainCheckout, target);
            if (verdict instanceof MergeVerdict.NotMerged) {
                return result.exitCode() == 0
                        ? new MergeVerdict.Indeterminate(
                                "git reported success but " + branch + " is not merged")
                        : new MergeVerdict.Refused(ProcessRunner.excerpt(result.stderr()));
            }
            return verdict;
        }, executor);
    }

    /**
     * Re-verifies {@code target} without touching the repository -- the poll
     * used while an agent resolves conflicts in the main checkout.
     */
    public CompletableFuture<MergeVerdict> verifyMerge(Path mainCheckout, MergeTarget target) {
        return CompletableFuture.supplyAsync(() -> {
            Path git = locator.locate()
                    .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
            return verify(git, mainCheckout, target);
        }, executor);
    }

    private static MergeVerdict verify(Path git, Path mainCheckout, MergeTarget target) {
        String tip = target.branchTipOid().orElseThrow(() -> new IllegalArgumentException(
                "inspectMergeTarget found no branch tip; the merge should never have been attempted"));
        BaseState now = baseStateOf(git, mainCheckout);
        if (!now.branch().equals(target.baseBranch())) {
            return new MergeVerdict.Indeterminate("the main checkout is no longer on "
                    + target.baseBranch().orElse("the branch it was on"));
        }
        if (!now.headOid().equals(target.baseHeadOid())) {
            List<String> parents = parentsOf(git, mainCheckout, now.headOid());
            if (parents.contains(target.baseHeadOid()) && parents.contains(tip)) {
                return new MergeVerdict.Merged(now.headOid());
            }
            return new MergeVerdict.Indeterminate(target.baseBranch().orElse("the base branch")
                    + " moved to a commit that is not a merge of the branch");
        }
        List<String> unmerged = linesOf(run(List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "diff", "--name-only", "--diff-filter=U")));
        if (!unmerged.isEmpty()) {
            return new MergeVerdict.Conflicted(unmerged);
        }
        if (now.inProgress() == MergeTarget.InProgress.MERGE) {
            // (now.inProgress() comes from BaseState -- see baseStateOf below.)
            // MERGE_HEAD with a clean index: git stopped before committing
            // (a pre-merge-commit hook veto, --no-commit). Not a conflict --
            // and `git commit` would not re-run that hook.
            return new MergeVerdict.Refused("git stopped before committing the merge");
        }
        if (isAncestor(git, mainCheckout, tip)) {
            return new MergeVerdict.AlreadyMerged();
        }
        return new MergeVerdict.NotMerged();
    }

    /** The base branch, its HEAD oid, and any sequencer operation in progress. */
    private record BaseState(Optional<String> branch, String headOid, MergeTarget.InProgress inProgress) { }

    /**
     * The main checkout's own state, without the branch-tip lookup: what
     * verification reads. Split out so {@code verify} never has to name a
     * branch it does not care about.
     */
    private static BaseState baseStateOf(Path git, Path mainCheckout) {
        // --quiet: a detached HEAD is an empty result, not an error.
        Optional<String> branch = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")));
        List<String> headCommand = List.of(
                git.toString(), "-C", mainCheckout.toString(), "rev-parse", "HEAD");
        ProcessResult head = run(headCommand);
        if (head.exitCode() != 0) {
            throw new GitCommandFailedException(headCommand, head.exitCode(), ProcessRunner.excerpt(head.stderr()));
        }
        return new BaseState(branch, head.stdout().strip(), inProgressIn(git, mainCheckout));
    }

    private static boolean isAncestor(Path git, Path mainCheckout, String oid) {
        return run(List.of(git.toString(), "-C", mainCheckout.toString(),
                "merge-base", "--is-ancestor", "--end-of-options", oid, "HEAD")).exitCode() == 0;
    }

    /** The parent oids of {@code oid}, from {@code rev-list --parents -n1}. */
    private static List<String> parentsOf(Path git, Path mainCheckout, String oid) {
        List<String> command = List.of(git.toString(), "-C", mainCheckout.toString(),
                "rev-list", "--parents", "-n", "1", "--end-of-options", oid);
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        List<String> tokens = List.of(result.stdout().strip().split("\\s+"));
        return tokens.size() <= 1 ? List.of() : tokens.subList(1, tokens.size());
    }

    private static List<String> linesOf(ProcessResult result) {
        if (result.exitCode() != 0 || result.stdout().isBlank()) {
            return List.of();
        }
        return result.stdout().strip().lines().toList();
    }
```

While adding `baseStateOf`, refactor Task 1's `inspectMergeTarget(Path, Path, String)` to call it and add only the branch-tip lookup on top, so the `symbolic-ref`/`rev-parse HEAD`/`inProgressIn` sequence exists once.

Also update the class javadoc at :29-31: `mergeIntoBase` no longer exists. Replace that sentence with a description of `merge`/`verifyMerge` and why the verdict is verified rather than inferred.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.git.WorktreeServiceTest'`
Expected: PASS. `./gradlew :app:compileJava` will now FAIL in `WorktreeLifecycleController.handoffMerge` (:276) because `mergeIntoBase` is gone — that is expected and is fixed in Task 6. To keep the tree compiling between tasks, in this task only, change `handoffMerge`'s body to:

```java
        tab.restoreFinishButton();
        tab.showTransientNotice("⏺ Merge is being rebuilt — see Task 6 of the merge-and-finish plan.");
```

and delete the now-unused body below it. Task 6 deletes the method outright.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/WorktreeService.java app/src/test/java/app/drydock/git/WorktreeServiceTest.java app/src/main/java/app/drydock/ui/WorktreeLifecycleController.java
git commit -m "Verify what a worktree merge actually did instead of trusting its exit code"
```

---

### Task 3: Tell a failed branch deletion apart from a failed worktree removal

**Files:**
- Create: `app/src/main/java/app/drydock/git/BranchNotDeletedException.java`
- Modify: `app/src/main/java/app/drydock/git/WorktreeService.java` (`removeBlocking`, the branch step at :196-206)
- Test: `app/src/test/java/app/drydock/git/WorktreeServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BranchNotDeletedException extends GitException`, with `String branch()` and `String stderrExcerpt()`. Note `GitCommandFailedException` is `final`, so this is a sibling, not a subclass.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void removeReportsAFailedBranchDeletionDistinctly(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/kept").get();
        // A second worktree on the branch keeps `git branch -D` from
        // succeeding after the first worktree is gone.
        Path second = worktreeParent.resolve("second");
        runGit(repo, "worktree", "add", second.toString(), "feat/kept");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/kept")).join());

        BranchNotDeletedException failure =
                assertInstanceOf(BranchNotDeletedException.class, completion.getCause());
        assertEquals("feat/kept", failure.branch());
        assertFalse(Files.exists(worktree));
        assertTrue(runGitCapture(repo, "branch", "--list", "feat/kept").contains("feat/kept"));
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.git.WorktreeServiceTest'`
Expected: compilation failure — `cannot find symbol: class BranchNotDeletedException`.

- [ ] **Step 3: Implement**

Create `BranchNotDeletedException.java`:

```java
package app.drydock.git;

/**
 * The worktree was removed but {@code git branch -D} then refused, so the
 * branch outlived it. Distinct from {@link GitCommandFailedException} --
 * which is {@code final}, hence a sibling rather than a subclass -- because
 * the two halves of {@link WorktreeService#remove} have different
 * consequences: a surviving branch is a cosmetic leftover the caller can
 * report and move on from, while a surviving worktree means the cleanup did
 * not happen and the session must be kept.
 */
public final class BranchNotDeletedException extends GitException {

    private final String branch;
    private final String stderrExcerpt;

    BranchNotDeletedException(String branch, int exitCode, String stderrExcerpt) {
        super("Could not delete branch " + branch + " (exit " + exitCode + ")"
                + (stderrExcerpt.isBlank() ? "" : System.lineSeparator() + "stderr: " + stderrExcerpt));
        this.branch = branch;
        this.stderrExcerpt = stderrExcerpt;
    }

    public String branch() {
        return branch;
    }

    public String stderrExcerpt() {
        return stderrExcerpt;
    }
}
```

In `removeBlocking`, replace the throw at :203-205 with:

```java
            if (deleted.exitCode() != 0) {
                throw new BranchNotDeletedException(branch.get(), deleted.exitCode(),
                        ProcessRunner.excerpt(deleted.stderr()));
            }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.git.WorktreeServiceTest'`
Expected: PASS.

`RepositorySidebar`'s six `remove`/`removeForced` call sites need no change: they pass `deletableBranchOf(worktree)`, which is empty for every row reachable from them, and their `else` branch already routes anything that is not `WorktreeNotCleanException`/`WorktreeLockedException` to `UiErrors.show`. Verify by reading `RepositorySidebar.java:862-880` — do not modify it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/BranchNotDeletedException.java app/src/main/java/app/drydock/git/WorktreeService.java app/src/test/java/app/drydock/git/WorktreeServiceTest.java
git commit -m "Report a failed branch deletion separately from a failed worktree removal"
```

---

### Task 4: The decision table and every string the user sees

**Files:**
- Create: `app/src/main/java/app/drydock/ui/MergeFinishDecision.java`
- Test: `app/src/test/java/app/drydock/ui/MergeFinishDecisionTest.java`

**Interfaces:**
- Consumes: `WorktreeService.MergeTarget`, `WorktreeService.MergeVerdict` (Tasks 1-2); `WorktreeSessionCleanup.CleanupOutcome` (Task 5) — **declare that record in this task** as specified below and let Task 5 use it, so this task compiles alone.
- Produces: sealed `MergeFinishDecision.Next` with `Merge()`, `HandOff(String headline, String prompt)`, `KeepWaiting()`, `CleanUp()`, `Done(String headline, String detail)`, `Stopped(String headline, String detail)`; statics `forPreflight`, `forVerdict`, `forCleanup`, `forTimeout`; `CleanupOutcome(boolean worktreeRemoved, BranchResult branch, boolean sessionDeleted, Optional<String> detail)` and `BranchResult { DELETED, KEPT_NOT_OURS, DELETE_FAILED, NOT_ATTEMPTED }`.

This class is FX-free on purpose: it is where the flow's behaviour is tested, since no test in this repository may touch JavaFX.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/app/drydock/ui/MergeFinishDecisionTest.java`:

```java
package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;
import app.drydock.git.WorktreeService.MergeVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge-and-finish flow's decisions and copy. Every destructive step of
 * the flow is gated on one of these verdicts, so this is where "never delete
 * on an unconfirmed merge" is actually enforced and asserted.
 */
class MergeFinishDecisionTest {

    private static final String OID = "1111111111111111111111111111111111111111";
    private static final String TIP = "2222222222222222222222222222222222222222";

    private static MergeTarget target(Optional<String> base, Optional<String> tip,
                                      MergeTarget.InProgress inProgress) {
        return new MergeTarget(base, OID, tip, inProgress);
    }

    private static MergeTarget healthyTarget() {
        return target(Optional.of("main"), Optional.of(TIP), MergeTarget.InProgress.NONE);
    }

    @Test
    void aCleanWorktreeOnTheExpectedBaseMerges() {
        assertInstanceOf(MergeFinishDecision.Next.Merge.class,
                MergeFinishDecision.forPreflight(healthyTarget(), "main", "feat/x", true));
    }

    @Test
    void anUncleanWorktreeStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(healthyTarget(), "main", "feat/x", false));

        assertEquals("The worktree has uncommitted changes", stopped.headline());
        assertTrue(stopped.detail().contains("Nothing was merged"));
    }

    @Test
    void aDetachedMainCheckoutStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.empty(), Optional.of(TIP), MergeTarget.InProgress.NONE),
                        "main", "feat/x", true));

        assertEquals("The main checkout is not on a branch", stopped.headline());
    }

    @Test
    void aDriftedBaseBranchStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("release/2.0"), Optional.of(TIP), MergeTarget.InProgress.NONE),
                        "main", "feat/x", true));

        assertEquals("The main checkout is on release/2.0, not main", stopped.headline());
    }

    @Test
    void anOperationInProgressStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("main"), Optional.of(TIP), MergeTarget.InProgress.CHERRY_PICK),
                        "main", "feat/x", true));

        assertEquals("The main checkout has a cherry-pick in progress", stopped.headline());
    }

    @Test
    void aMissingBranchStopsWithoutMerging() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forPreflight(
                        target(Optional.of("main"), Optional.empty(), MergeTarget.InProgress.NONE),
                        "main", "feat/x", true));

        assertEquals("Branch feat/x no longer exists", stopped.headline());
    }

    @Test
    void aMergedVerdictCleansUp() {
        assertInstanceOf(MergeFinishDecision.Next.CleanUp.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.Merged(OID), "/repo", "feat/x", "main", false));
        assertInstanceOf(MergeFinishDecision.Next.CleanUp.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.AlreadyMerged(), "/repo", "feat/x", "main", false));
    }

    @Test
    void aConflictHandsOffWithAFencedPrompt() {
        MergeFinishDecision.Next.HandOff handOff = assertInstanceOf(MergeFinishDecision.Next.HandOff.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Conflicted(List.of("README.md", "src/A.java")),
                        "/repo", "feat/x", "main", false));

        assertEquals("Conflicts in 2 files — Claude is resolving them in the main checkout…",
                handOff.headline());
        assertTrue(handOff.prompt().contains("git -C /repo"));
        assertTrue(handOff.prompt().contains("Do not modify this worktree"));
        assertTrue(handOff.prompt().contains("merge --abort"));
    }

    @Test
    void aConflictAfterTheHandOffKeepsWaiting() {
        assertInstanceOf(MergeFinishDecision.Next.KeepWaiting.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Conflicted(List.of("README.md")),
                        "/repo", "feat/x", "main", true));
    }

    @Test
    void anAbortedMergeDuringTheHandOffStopsWithoutDeletingAnything() {
        MergeFinishDecision.Next.Stopped stopped = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.NotMerged(), "/repo", "feat/x", "main", true));

        assertEquals("The merge was abandoned", stopped.headline());
        assertTrue(stopped.detail().contains("Nothing was deleted"));
    }

    @Test
    void unknownStatesKeepWaitingDuringTheHandOffButStopBeforeIt() {
        assertInstanceOf(MergeFinishDecision.Next.KeepWaiting.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.Indeterminate("HEAD moved"), "/repo", "feat/x", "main", true));
        assertInstanceOf(MergeFinishDecision.Next.KeepWaiting.class, MergeFinishDecision.forVerdict(
                new MergeVerdict.Refused("hook said no"), "/repo", "feat/x", "main", true));

        MergeFinishDecision.Next.Stopped refused = assertInstanceOf(MergeFinishDecision.Next.Stopped.class,
                MergeFinishDecision.forVerdict(new MergeVerdict.Refused("hook said no"),
                        "/repo", "feat/x", "main", false));
        assertEquals("Could not merge feat/x into main", refused.headline());
        assertEquals("hook said no", refused.detail());
    }

    @Test
    void fullCleanupReportsEveryStep() {
        MergeFinishDecision.Next.Done done = MergeFinishDecision.forCleanup(
                new MergeFinishDecision.CleanupOutcome(true, MergeFinishDecision.BranchResult.DELETED,
                        true, Optional.empty()),
                "feat/x", "main", false);

        assertEquals("✓ Merged feat/x into main", done.headline());
        assertEquals("worktree removed · branch feat/x deleted · session closed", done.detail());
    }

    @Test
    void resolvedConflictsAreCreditedInTheHeadline() {
        MergeFinishDecision.Next.Done done = MergeFinishDecision.forCleanup(
                new MergeFinishDecision.CleanupOutcome(true, MergeFinishDecision.BranchResult.DELETED,
                        true, Optional.empty()),
                "feat/x", "main", true);

        assertEquals("✓ Merged feat/x into main — conflicts resolved by Claude", done.headline());
    }

    @Test
    void aKeptBranchAndAKeptWorktreeAreReportedHonestly() {
        assertEquals("worktree removed · branch feat/x kept (already existed) · session closed",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        true, MergeFinishDecision.BranchResult.KEPT_NOT_OURS, true, Optional.empty()),
                        "feat/x", "main", false).detail());

        assertEquals("worktree removed · branch feat/x kept (could not delete) · session closed",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        true, MergeFinishDecision.BranchResult.DELETE_FAILED, true, Optional.empty()),
                        "feat/x", "main", false).detail());

        assertEquals("worktree kept — it has uncommitted changes · branch feat/x kept · session left open",
                MergeFinishDecision.forCleanup(new MergeFinishDecision.CleanupOutcome(
                        false, MergeFinishDecision.BranchResult.NOT_ATTEMPTED, false,
                        Optional.of("it has uncommitted changes")), "feat/x", "main", false).detail());
    }

    @Test
    void aTimeoutDeletesNothingAndSaysWhereToLook() {
        MergeFinishDecision.Next.Stopped stopped =
                MergeFinishDecision.forTimeout("feat/x", "main", Optional.of("HEAD moved"));

        assertEquals("Merge not confirmed after 5 minutes", stopped.headline());
        assertTrue(stopped.detail().contains("check the terminal"));
        assertTrue(stopped.detail().contains("Nothing was deleted"));
        assertTrue(stopped.detail().contains("HEAD moved"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.MergeFinishDecisionTest'`
Expected: compilation failure — `cannot find symbol: class MergeFinishDecision`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/app/drydock/ui/MergeFinishDecision.java`:

```java
package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;
import app.drydock.git.WorktreeService.MergeVerdict;

import java.util.List;
import java.util.Optional;

/**
 * The merge-and-finish flow's decisions and its user-visible copy, with no
 * JavaFX in sight (see docs/superpowers/specs/2026-07-25-merge-and-finish-design.md).
 *
 * <p>The flow deletes a worktree, a branch and a session, so the rule that
 * matters is that {@link Next.CleanUp} is reachable from exactly two
 * verdicts -- {@link MergeVerdict.Merged} and {@link
 * MergeVerdict.AlreadyMerged} -- and from nothing else. Keeping that rule
 * here, in a class no test needs a toolkit to exercise, is why it is
 * separate from {@code MergeAndFinishFlow}.</p>
 */
final class MergeFinishDecision {

    private MergeFinishDecision() {
    }

    /** What the flow does next, and what it puts on screen while doing it. */
    sealed interface Next {

        /** Pre-flight passed; run the merge. */
        record Merge() implements Next { }

        /** Conflicts: send {@code prompt} to the session's Claude, then poll. */
        record HandOff(String headline, String prompt) implements Next { }

        /** The poll saw nothing conclusive; wait and probe again. */
        record KeepWaiting() implements Next { }

        /** The merge is confirmed; run the destructive cleanup. */
        record CleanUp() implements Next { }

        /** Terminal success. {@code detail} reports every cleanup step. */
        record Done(String headline, String detail) implements Next { }

        /** Terminal stop. Nothing destructive has run. */
        record Stopped(String headline, String detail) implements Next { }
    }

    /** What the cleanup managed to do, step by step. */
    record CleanupOutcome(boolean worktreeRemoved, BranchResult branch, boolean sessionDeleted,
                          Optional<String> detail) {
    }

    /** The fate of the branch. {@code NOT_ATTEMPTED}: the worktree survived, so nothing was tried. */
    enum BranchResult { DELETED, KEPT_NOT_OURS, DELETE_FAILED, NOT_ATTEMPTED }

    /**
     * Whether to merge at all. Every refusal here happens before anything is
     * written: a detached or drifted main checkout would take the merge
     * commit somewhere the user did not ask for, an operation already in
     * progress would make our own merge's failure unreadable, and an unclean
     * worktree cannot survive the cleanup that follows a successful merge.
     */
    static Next forPreflight(MergeTarget target, String expectedBase, String branch, boolean worktreeClean) {
        if (target.baseBranch().isEmpty()) {
            return new Next.Stopped("The main checkout is not on a branch",
                    "Its HEAD is detached, so a merge committed there would be lost once " + branch
                            + " is deleted. Check out " + expectedBase
                            + " in the main checkout, then finish again. Nothing was merged.");
        }
        String actualBase = target.baseBranch().get();
        if (!actualBase.equals(expectedBase)) {
            return new Next.Stopped("The main checkout is on " + actualBase + ", not " + expectedBase,
                    "Check out " + expectedBase + " there, then finish again. Nothing was merged.");
        }
        if (target.inProgress() != MergeTarget.InProgress.NONE) {
            return new Next.Stopped("The main checkout has a " + describe(target.inProgress()) + " in progress",
                    "Finish or abort it there, then finish again. Nothing was merged.");
        }
        if (target.branchTipOid().isEmpty()) {
            return new Next.Stopped("Branch " + branch + " no longer exists",
                    "There is nothing to merge. Nothing was merged.");
        }
        if (!worktreeClean) {
            return new Next.Stopped("The worktree has uncommitted changes",
                    "Finishing removes the worktree, which would discard them. Commit or discard them first,"
                            + " then finish again. Nothing was merged.");
        }
        return new Next.Merge();
    }

    private static String describe(MergeTarget.InProgress inProgress) {
        return switch (inProgress) {
            case NONE -> "operation";
            case MERGE -> "merge";
            case REBASE -> "rebase";
            case CHERRY_PICK -> "cherry-pick";
            case REVERT -> "revert";
            case BISECT -> "bisect";
        };
    }

    /**
     * What a verdict means. {@code afterHandOff} is the difference between
     * "this is the answer" and "the agent is still working": once Claude has
     * been asked to resolve the conflicts, anything short of a confirmed
     * merge or an explicit abandonment is a reason to wait, never a reason
     * to delete.
     */
    static Next forVerdict(MergeVerdict verdict, String mainCheckout, String branch, String base,
                           boolean afterHandOff) {
        return switch (verdict) {
            case MergeVerdict.Merged ignored -> new Next.CleanUp();
            case MergeVerdict.AlreadyMerged ignored -> new Next.CleanUp();
            case MergeVerdict.Conflicted conflicted -> afterHandOff
                    ? new Next.KeepWaiting()
                    : new Next.HandOff(
                            "Conflicts in " + conflicted.unmergedPaths().size()
                                    + (conflicted.unmergedPaths().size() == 1 ? " file" : " files")
                                    + " — Claude is resolving them in the main checkout…",
                            handOffPrompt(mainCheckout, branch, base));
            case MergeVerdict.NotMerged ignored -> afterHandOff
                    ? new Next.Stopped("The merge was abandoned",
                            "The merge of " + branch + " into " + base
                                    + " was aborted in the main checkout. Nothing was deleted.")
                    : new Next.Stopped("Nothing was merged",
                            "Git left " + base + " unchanged and reported no conflicts. Nothing was deleted.");
            case MergeVerdict.Refused refused -> afterHandOff
                    ? new Next.KeepWaiting()
                    : new Next.Stopped("Could not merge " + branch + " into " + base, refused.detail());
            case MergeVerdict.Indeterminate indeterminate -> afterHandOff
                    ? new Next.KeepWaiting()
                    : new Next.Stopped("Could not confirm the merge", indeterminate.detail());
        };
    }

    /**
     * The conflict hand-off prompt. Fenced deliberately: the agent's cwd is
     * the worktree, not the main checkout, and the shortcuts it is told to
     * avoid ({@code merge --abort}, {@code reset --hard}, {@code checkout},
     * {@code -s ours}) are exactly the ones that would leave the repository
     * looking merged without the work being in it.
     */
    private static String handOffPrompt(String mainCheckout, String branch, String base) {
        return "The merge of '" + branch + "' into '" + base + "' stopped on conflicts in the main checkout at "
                + mainCheckout + ". Resolve the conflicted files there using `git -C " + mainCheckout
                + " …` and complete the merge with `git -C " + mainCheckout + " commit`."
                + " Do not modify this worktree."
                + " Do not run `merge --abort`, `reset --hard`, `checkout`, or `merge -s ours`"
                + " — if you cannot resolve the conflicts, say so and stop.";
    }

    /**
     * The terminal success copy, worded from what the cleanup actually did
     * rather than from what was intended -- a merge that landed is never
     * reported as a failure because the cleanup was partial, and a session
     * that is still open is never reported as closed.
     */
    static Next.Done forCleanup(CleanupOutcome outcome, String branch, String base, boolean conflictsResolved) {
        String headline = "✓ Merged " + branch + " into " + base
                + (conflictsResolved ? " — conflicts resolved by Claude" : "");
        String worktree = outcome.worktreeRemoved()
                ? "worktree removed"
                : "worktree kept — " + outcome.detail().orElse("git refused to remove it");
        String branchDetail = switch (outcome.branch()) {
            case DELETED -> "branch " + branch + " deleted";
            case KEPT_NOT_OURS -> "branch " + branch + " kept (already existed)";
            case DELETE_FAILED -> "branch " + branch + " kept (could not delete)";
            case NOT_ATTEMPTED -> "branch " + branch + " kept";
        };
        String session = outcome.sessionDeleted() ? "session closed" : "session left open";
        return new Next.Done(headline, String.join(" · ", worktree, branchDetail, session));
    }

    /** The poll gave up. Says where the merge might be, and that nothing was destroyed. */
    static Next.Stopped forTimeout(String branch, String base, Optional<String> lastProbeDetail) {
        return new Next.Stopped("Merge not confirmed after 5 minutes",
                "The merge of " + branch + " into " + base
                        + " may still be open in the main checkout — check the terminal. Nothing was deleted."
                        + lastProbeDetail.map(detail -> " Last check: " + detail).orElse(""));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.MergeFinishDecisionTest'`
Expected: PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/MergeFinishDecision.java app/src/test/java/app/drydock/ui/MergeFinishDecisionTest.java
git commit -m "Decide and word the merge-and-finish flow outside JavaFX"
```

---

### Task 5: One shared destructive sequence

**Files:**
- Create: `app/src/main/java/app/drydock/ui/WorktreeSessionCleanup.java`
- Test: `app/src/test/java/app/drydock/ui/WorktreeSessionCleanupTest.java`

**Interfaces:**
- Consumes: `MergeFinishDecision.CleanupOutcome`, `MergeFinishDecision.BranchResult` (Task 4); `BranchNotDeletedException` (Task 3).
- Produces: `WorktreeSessionCleanup(WorktreeRemoval, SessionDeletion)`; nested functional interfaces `WorktreeRemoval { CompletableFuture<Void> remove(Path repositoryRoot, Path worktree, Optional<String> branch); }` and `SessionDeletion { CompletableFuture<Void> delete(ManagedSessionId sessionId); }`; `CompletableFuture<CleanupOutcome> run(ManagedSessionId sessionId, Path repositoryRoot, Path worktreeRoot, String branch, boolean mayDeleteBranch)`.

Narrow interfaces rather than the real `WorktreeService`/`SessionManager` so this — the app's most destructive sequence — is testable with fakes and no git.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/app/drydock/ui/WorktreeSessionCleanupTest.java`:

```java
package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.BranchNotDeletedException;
import app.drydock.git.WorktreeNotCleanException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The destructive tail of both the Finish panel's Delete and the
 * merge-and-finish flow. The invariant asserted here -- the session is
 * deleted only once its worktree is gone -- is what keeps a worktree that
 * still holds files from losing the terminal and conversation the user
 * needs to deal with it.
 */
class WorktreeSessionCleanupTest {

    private static final Path REPO = Path.of("/repo");
    private static final Path WORKTREE = Path.of("/repo/../wt-x");
    private final ManagedSessionId sessionId = ManagedSessionId.newId();
    private final List<ManagedSessionId> deleted = new ArrayList<>();

    private WorktreeSessionCleanup cleanup(WorktreeSessionCleanup.WorktreeRemoval removal) {
        return new WorktreeSessionCleanup(removal, id -> {
            deleted.add(id);
            return CompletableFuture.completedFuture(null);
        });
    }

    @Test
    void aCleanRunRemovesTheWorktreeDeletesTheBranchAndClosesTheSession() throws Exception {
        List<Optional<String>> requested = new ArrayList<>();
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) -> {
            requested.add(branch);
            return CompletableFuture.completedFuture(null);
        });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertEquals(List.of(Optional.of("feat/x")), requested);
        assertTrue(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.DELETED, outcome.branch());
        assertTrue(outcome.sessionDeleted());
        assertEquals(List.of(sessionId), deleted);
    }

    @Test
    void aBranchWeDoNotOwnIsNeverPassedToGit() throws Exception {
        List<Optional<String>> requested = new ArrayList<>();
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) -> {
            requested.add(branch);
            return CompletableFuture.completedFuture(null);
        });

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", false).get();

        assertEquals(List.of(Optional.<String>empty()), requested);
        assertEquals(MergeFinishDecision.BranchResult.KEPT_NOT_OURS, outcome.branch());
        assertTrue(outcome.sessionDeleted());
    }

    @Test
    void aFailedBranchDeletionStillClosesTheSession() throws Exception {
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) ->
                CompletableFuture.failedFuture(new BranchNotDeletedException("feat/x", 1, "checked out")));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertTrue(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.DELETE_FAILED, outcome.branch());
        assertTrue(outcome.sessionDeleted());
        assertEquals(List.of(sessionId), deleted);
    }

    @Test
    void aSurvivingWorktreeKeepsTheSessionOpen() throws Exception {
        WorktreeSessionCleanup subject = cleanup((repo, worktree, branch) ->
                CompletableFuture.failedFuture(new WorktreeNotCleanException(WORKTREE)));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertFalse(outcome.worktreeRemoved());
        assertEquals(MergeFinishDecision.BranchResult.NOT_ATTEMPTED, outcome.branch());
        assertFalse(outcome.sessionDeleted());
        assertTrue(deleted.isEmpty());
        assertTrue(outcome.detail().orElseThrow().contains("uncommitted"));
    }

    @Test
    void aFailedSessionDeletionIsReportedNotSwallowed() throws Exception {
        WorktreeSessionCleanup subject = new WorktreeSessionCleanup(
                (repo, worktree, branch) -> CompletableFuture.completedFuture(null),
                id -> CompletableFuture.failedFuture(new IllegalStateException("state file locked")));

        MergeFinishDecision.CleanupOutcome outcome =
                subject.run(sessionId, REPO, WORKTREE, "feat/x", true).get();

        assertTrue(outcome.worktreeRemoved());
        assertFalse(outcome.sessionDeleted());
        assertTrue(outcome.detail().orElseThrow().contains("state file locked"));
    }
}
```

`WorktreeNotCleanException`'s constructor is package-private in `app.drydock.git`, so it cannot be constructed from `app.drydock.ui`. Before writing this test, widen it to `public` (it is already a public class with a public accessor, and the sidebar already branches on the type), and note that in the commit message. If widening is unwanted, use `new BranchNotDeletedException(...)` for that test's failure and assert on `worktreeRemoved()` only — but prefer widening, because the copy under test is specifically the unclean-worktree wording.

`ManagedSessionId.newId()` — check the actual factory name in `app/src/main/java/app/drydock/domain/ManagedSessionId.java` and use whatever it is.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.WorktreeSessionCleanupTest'`
Expected: compilation failure — `cannot find symbol: class WorktreeSessionCleanup`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/app/drydock/ui/WorktreeSessionCleanup.java`:

```java
package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.BranchNotDeletedException;
import app.drydock.git.WorktreeNotCleanException;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The one implementation of "this worktree session is finished": remove the
 * worktree, delete the branch when it is ours, close the session. Used by
 * both the Finish panel's Delete and the merge-and-finish flow -- the app's
 * most destructive sequence exists once, and reports per step instead of
 * throwing one opaque failure.
 *
 * <p>Invariant: the session is deleted only if the worktree is gone. A
 * branch that could not be deleted is a leftover; a worktree that survived
 * still holds files the user has to look at, and the session's terminal and
 * conversation are how they look at them.</p>
 *
 * <p>Collaborators are narrow interfaces rather than {@code WorktreeService}
 * and {@code SessionManager} so this class is testable with fakes and no
 * git. Runs entirely off the FX thread; the caller renders the outcome.</p>
 */
final class WorktreeSessionCleanup {

    /** {@code WorktreeService::remove}. */
    interface WorktreeRemoval {
        CompletableFuture<Void> remove(Path repositoryRoot, Path worktree, Optional<String> branch);
    }

    /** {@code SessionManager::deleteSession}. */
    interface SessionDeletion {
        CompletableFuture<Void> delete(ManagedSessionId sessionId);
    }

    private final WorktreeRemoval removal;
    private final SessionDeletion deletion;

    WorktreeSessionCleanup(WorktreeRemoval removal, SessionDeletion deletion) {
        this.removal = removal;
        this.deletion = deletion;
    }

    /**
     * Runs the sequence and reports what each step managed to do. The
     * returned future never completes exceptionally: every failure is part
     * of the outcome, because the caller has copy for each and a partial
     * cleanup is not an error the user can retry blindly.
     */
    CompletableFuture<MergeFinishDecision.CleanupOutcome> run(ManagedSessionId sessionId, Path repositoryRoot,
                                                              Path worktreeRoot, String branch,
                                                              boolean mayDeleteBranch) {
        Optional<String> branchToDelete = mayDeleteBranch ? Optional.of(branch) : Optional.empty();
        return removal.remove(repositoryRoot, worktreeRoot, branchToDelete)
                .handle((ignored, failure) -> classify(failure, mayDeleteBranch))
                .thenCompose(partial -> partial.worktreeRemoved()
                        ? closeSession(sessionId, partial)
                        : CompletableFuture.completedFuture(partial));
    }

    private static MergeFinishDecision.CleanupOutcome classify(Throwable failure, boolean mayDeleteBranch) {
        if (failure == null) {
            return new MergeFinishDecision.CleanupOutcome(true,
                    mayDeleteBranch ? MergeFinishDecision.BranchResult.DELETED
                            : MergeFinishDecision.BranchResult.KEPT_NOT_OURS,
                    false, Optional.empty());
        }
        Throwable cause = UiErrors.unwrap(failure);
        if (cause instanceof BranchNotDeletedException branchFailure) {
            // The worktree is gone; only `git branch -D` refused.
            return new MergeFinishDecision.CleanupOutcome(true,
                    MergeFinishDecision.BranchResult.DELETE_FAILED, false,
                    Optional.of(branchFailure.stderrExcerpt()));
        }
        String detail = cause instanceof WorktreeNotCleanException
                ? "it has uncommitted changes"
                : Optional.ofNullable(cause.getMessage()).orElse(cause.getClass().getSimpleName());
        return new MergeFinishDecision.CleanupOutcome(false,
                MergeFinishDecision.BranchResult.NOT_ATTEMPTED, false, Optional.of(detail));
    }

    private CompletableFuture<MergeFinishDecision.CleanupOutcome> closeSession(
            ManagedSessionId sessionId, MergeFinishDecision.CleanupOutcome partial) {
        return deletion.delete(sessionId).handle((ignored, failure) -> {
            if (failure == null) {
                return new MergeFinishDecision.CleanupOutcome(partial.worktreeRemoved(), partial.branch(),
                        true, partial.detail());
            }
            Throwable cause = UiErrors.unwrap(failure);
            String detail = Optional.ofNullable(cause.getMessage()).orElse(cause.getClass().getSimpleName());
            return new MergeFinishDecision.CleanupOutcome(partial.worktreeRemoved(), partial.branch(),
                    false, Optional.of(detail));
        });
    }
}
```

Check `UiErrors.unwrap`'s visibility; if it is private, add a package-private `static Throwable unwrap(Throwable)` there or inline the `CompletionException`/`ExecutionException` unwrapping in a private helper here.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.WorktreeSessionCleanupTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/WorktreeSessionCleanup.java app/src/test/java/app/drydock/ui/WorktreeSessionCleanupTest.java app/src/main/java/app/drydock/git/WorktreeNotCleanException.java
git commit -m "Extract the worktree-session cleanup so it reports per step"
```

---

### Task 6: The flow, the modal, and the panel

**Files:**
- Create: `app/src/main/java/app/drydock/ui/MergeAndFinishFlow.java`
- Modify: `app/src/main/java/app/drydock/ui/WorktreeLifecycleController.java` (delete `handoffMerge`; refactor `handoffDelete` onto `WorktreeSessionCleanup`; add the in-flight guard; pass `worktreeClean` into the panel context)
- Modify: `app/src/main/java/app/drydock/ui/FinishWorktreePanel.java` (disabled action variant; merge caption)
- Modify: `app/src/main/resources/app/drydock/ui/app.css`

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: `MergeAndFinishFlow(WorktreeService, SessionManager, ModalLayer, WorktreeSessionCleanup, Function<ManagedSessionId, OpenSessionTab> openTab, Runnable onSessionsChanged, Consumer<ManagedSessionId> onSessionDeleted, Runnable onFinished)` and `void start(ManagedSessionId sessionId, Path repositoryRoot, Path worktreeRoot, String branch, String base)`.

There is no unit test for this task — it is JavaFX, and this repository does not test JavaFX. Its logic lives in Tasks 4 and 5, already covered. Verification is Step 5's manual run.

- [ ] **Step 1: Write the flow**

Create `app/src/main/java/app/drydock/ui/MergeAndFinishFlow.java`:

```java
package app.drydock.ui;

import app.drydock.app.SessionManager;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.WorktreeService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Merge-and-finish: the Finish panel's "Merge into &lt;base&gt;" from the
 * click to the sidebar row disappearing (see
 * docs/superpowers/specs/2026-07-25-merge-and-finish-design.md).
 *
 * <p>A thin JavaFX shell. Every decision and every string comes from {@link
 * MergeFinishDecision}, and the destructive tail from {@link
 * WorktreeSessionCleanup}, both FX-free and unit-tested; what is left here
 * is the async plumbing and one modal.</p>
 *
 * <p>Two lifecycle rules, both learned from the flow this replaces. First,
 * the flow owns its own liveness: the old {@code handoffMerge} returned
 * silently whenever the session's tab had closed, which -- now that the
 * progress indication is a modal rather than a header pill -- would strand
 * that modal and leave the native terminals hidden. The tab is updated only
 * opportunistically. Second, the modal layer is shared and dismissible, so
 * the flow replaces only a modal it still owns; if the user dismissed it and
 * opened something else, the result degrades to a transient notice rather
 * than yanking their modal away.</p>
 */
final class MergeAndFinishFlow {

    /** Matches the PR hand-off's cadence and cap: every 4s, up to 5 minutes. */
    private static final Duration POLL_INTERVAL = Duration.seconds(4);
    private static final int POLL_MAX_ATTEMPTS = 75;

    private final WorktreeService worktreeService;
    private final SessionManager sessionManager;
    private final ModalLayer modalLayer;
    private final WorktreeSessionCleanup cleanup;
    private final Function<ManagedSessionId, OpenSessionTab> openTab;
    private final Runnable onSessionsChanged;
    private final Consumer<ManagedSessionId> onSessionDeleted;
    private final Runnable onFinished;

    private ManagedSessionId sessionId;
    private Path repositoryRoot;
    private Path worktreeRoot;
    private String branch;
    private String base;
    private WorktreeService.MergeTarget target;
    private boolean conflictsHandedOff;
    private Optional<String> lastProbeDetail = Optional.empty();
    /** The node this flow currently owns in the shared modal layer. */
    private Region ownModal;

    MergeAndFinishFlow(WorktreeService worktreeService, SessionManager sessionManager, ModalLayer modalLayer,
                       WorktreeSessionCleanup cleanup, Function<ManagedSessionId, OpenSessionTab> openTab,
                       Runnable onSessionsChanged, Consumer<ManagedSessionId> onSessionDeleted,
                       Runnable onFinished) {
        this.worktreeService = worktreeService;
        this.sessionManager = sessionManager;
        this.modalLayer = modalLayer;
        this.cleanup = cleanup;
        this.openTab = openTab;
        this.onSessionsChanged = onSessionsChanged;
        this.onSessionDeleted = onSessionDeleted;
        this.onFinished = onFinished;
    }

    /** FX thread. Shows progress before the first git call, per AGENTS.md. */
    void start(ManagedSessionId sessionId, Path repositoryRoot, Path worktreeRoot, String branch, String base) {
        this.sessionId = sessionId;
        this.repositoryRoot = repositoryRoot;
        this.worktreeRoot = worktreeRoot;
        this.branch = branch;
        this.base = base;
        showBusy("Checking the main checkout…");
        worktreeService.inspectMergeTarget(repositoryRoot, branch)
                .thenCombine(worktreeService.isWorktreeClean(worktreeRoot), PreflightData::new)
                .whenComplete((data, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        stop("Could not inspect the repository", messageOf(ex));
                        return;
                    }
                    this.target = data.target();
                    apply(MergeFinishDecision.forPreflight(data.target(), base, branch, data.worktreeClean()));
                }));
    }

    private record PreflightData(WorktreeService.MergeTarget target, boolean worktreeClean) { }

    /** FX thread. Executes one decision. */
    private void apply(MergeFinishDecision.Next next) {
        switch (next) {
            case MergeFinishDecision.Next.Merge ignored -> runMerge();
            case MergeFinishDecision.Next.HandOff handOff -> handOff(handOff);
            case MergeFinishDecision.Next.KeepWaiting ignored -> pollAgain(0);
            case MergeFinishDecision.Next.CleanUp ignored -> runCleanup();
            case MergeFinishDecision.Next.Done done -> done(done);
            case MergeFinishDecision.Next.Stopped stopped -> stop(stopped.headline(), stopped.detail());
        }
    }

    private void runMerge() {
        showBusy("Merging " + branch + " into " + base + "…");
        worktreeService.merge(repositoryRoot, branch, target)
                .whenComplete((verdict, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        stop("Could not merge " + branch + " into " + base, messageOf(ex));
                        return;
                    }
                    apply(MergeFinishDecision.forVerdict(verdict, repositoryRoot.toString(), branch, base, false));
                }));
    }

    private void handOff(MergeFinishDecision.Next.HandOff handOff) {
        conflictsHandedOff = true;
        showBusy(handOff.headline());
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab == null) {
            // No terminal to hand off to: the merge is open in the main
            // checkout and only the user can finish it.
            stop("Conflicts need resolving", "The merge of " + branch + " into " + base
                    + " is open in the main checkout at " + repositoryRoot
                    + ", but this session's terminal is closed. Resolve it there. Nothing was deleted.");
            return;
        }
        tab.sendPrompt(handOff.prompt());
        pollAgain(0);
    }

    private void pollAgain(int attempt) {
        if (attempt >= POLL_MAX_ATTEMPTS) {
            MergeFinishDecision.Next.Stopped stopped =
                    MergeFinishDecision.forTimeout(branch, base, lastProbeDetail);
            stop(stopped.headline(), stopped.detail());
            return;
        }
        PauseTransition wait = new PauseTransition(POLL_INTERVAL);
        wait.setOnFinished(e -> worktreeService.verifyMerge(repositoryRoot, target)
                .whenComplete((verdict, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        // A probe failure is never a verdict: record it for the
                        // timeout message and keep waiting.
                        lastProbeDetail = Optional.of(messageOf(ex));
                        pollAgain(attempt + 1);
                        return;
                    }
                    MergeFinishDecision.Next next =
                            MergeFinishDecision.forVerdict(verdict, repositoryRoot.toString(), branch, base, true);
                    if (next instanceof MergeFinishDecision.Next.KeepWaiting) {
                        pollAgain(attempt + 1);
                        return;
                    }
                    apply(next);
                })));
        wait.play();
    }

    private void runCleanup() {
        showBusy("Removing worktree…");
        cleanup.run(sessionId, repositoryRoot, worktreeRoot, branch,
                        sessionManager.mayDeleteBranchOf(worktreeRoot))
                .whenComplete((outcome, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        stop("Merged, but the cleanup failed", messageOf(ex));
                        return;
                    }
                    if (outcome.sessionDeleted()) {
                        onSessionDeleted.accept(sessionId);
                    }
                    onSessionsChanged.run();
                    apply(MergeFinishDecision.forCleanup(outcome, branch, base, conflictsHandedOff));
                }));
    }

    // ---- Modal rendering ----------------------------------------------------

    private void showBusy(String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label label = new Label(message);
        label.getStyleClass().add("finish-action-caption");
        label.setWrapText(true);
        VBox box = new VBox(10, spinner, label);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("modal");
        box.setMaxWidth(360);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        render(box);
    }

    private void done(MergeFinishDecision.Next.Done outcome) {
        render(terminalModal(outcome.headline(), outcome.detail(), "merge-flow-headline"));
        onFinished.run();
    }

    private void stop(String headline, String detail) {
        render(terminalModal("✗ " + headline, detail, "merge-flow-headline-error"));
        onFinished.run();
    }

    private Region terminalModal(String headlineText, String detailText, String headlineStyleClass) {
        Label headline = new Label(headlineText);
        headline.getStyleClass().add(headlineStyleClass);
        headline.setWrapText(true);
        Label detail = new Label(detailText);
        detail.getStyleClass().add("merge-flow-detail");
        detail.setWrapText(true);
        Button done = new Button("Done");
        done.getStyleClass().add("worktree-create-button");
        done.setDefaultButton(true);
        done.setOnAction(e -> modalLayer.close());
        HBox actions = new HBox(done);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(12, headline, detail, actions);
        box.getStyleClass().add("modal");
        box.setMaxWidth(460);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        return box;
    }

    /**
     * Puts {@code node} in the shared modal layer, but only if this flow
     * still owns what is showing (or nothing is): dismissing the progress
     * modal does not cancel the work, so by the time a result arrives the
     * user may have opened an unrelated modal -- {@code ModalLayer.show}
     * would replace it and drop its {@code onClosed} on the floor.
     */
    private void render(Region node) {
        boolean ownsLayer = ownModal != null && ownModal.getParent() != null;
        if (ownsLayer || !modalLayer.isShowingModal()) {
            ownModal = node;
            modalLayer.show(node);
            return;
        }
        ownModal = null;
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            tab.showTransientNotice("⏺ Merge-and-finish finished — reopen Finish ▸ for the details.");
        }
    }

    private static String messageOf(Throwable failure) {
        Throwable cause = UiErrors.unwrap(failure);
        return Optional.ofNullable(cause.getMessage()).orElse(cause.getClass().getSimpleName());
    }
}
```

Note the `stop`/`done` asymmetry to preserve: both call `onFinished.run()`, which is what clears the controller's in-flight guard. Every terminal path goes through one of them — there is no `return` in this class that skips both.

- [ ] **Step 2: Wire it into the controller**

In `WorktreeLifecycleController`:

1. Add fields:

```java
    /** Sessions with a merge-and-finish flow running; a second Finish must not start another. */
    private final Set<ManagedSessionId> mergeInFlight = new HashSet<>();
    private final WorktreeSessionCleanup cleanup;
```

and in the constructor: `this.cleanup = new WorktreeSessionCleanup(worktreeService::remove, sessionManager::deleteSession);`

2. Delete `handoffMerge` entirely (including the placeholder body Task 2 left) and replace the panel action with:

```java
                        @Override
                        public void mergeIntoBase() {
                            startMergeAndFinish(sessionId, worktreeRoot, branch, base);
                        }
```

3. Add:

```java
    /**
     * Starts the merge-and-finish flow, at most one per session: the Finish
     * panel closes before its action runs and the flow's modal is
     * dismissible without cancelling the work, so a second click would
     * otherwise run a second cleanup over an already-deleted worktree and
     * report an error after the first flow reported success.
     */
    private void startMergeAndFinish(ManagedSessionId sessionId, Path worktreeRoot, String branch, String base) {
        Repository repository = sessionById(sessionId).flatMap(repositoryFor).orElse(null);
        if (repository == null || modalLayer == null || !mergeInFlight.add(sessionId)) {
            return;
        }
        new MergeAndFinishFlow(worktreeService, sessionManager, modalLayer, cleanup, openTab,
                onSessionsChanged, onSessionDeleted, () -> mergeInFlight.remove(sessionId))
                .start(sessionId, repository.root(), worktreeRoot, branch, base);
    }
```

4. In `showFinishPanel`, refuse to open while a flow is running — add immediately after the `session == null || modalLayer == null` guard:

```java
        if (mergeInFlight.contains(sessionId)) {
            return;
        }
```

5. In `showFinishPanel`'s inspection, add the clean probe and pass it to the panel. Change the `thenCombine` chain to also combine `worktreeService.isWorktreeClean(worktreeRoot)` (extend the local `Inspection` record with `boolean worktreeClean`), and pass `data.worktreeClean()` as the new last argument of `FinishWorktreePanel.Context`.

6. Refactor `handoffDelete`'s tail onto the shared cleanup, replacing the `worktreeService.remove(...)` call and its `whenComplete` body with:

```java
        cleanup.run(sessionId, repository.root(), worktreeRoot, branch,
                        sessionManager.mayDeleteBranchOf(worktreeRoot))
                .whenComplete((outcome, ex) -> Platform.runLater(() -> {
                    if (ex != null || !outcome.worktreeRemoved()) {
                        tab.restoreFinishButton();
                        UiErrors.show("Could not remove the worktree",
                                ex != null ? ex : new IllegalStateException(
                                        outcome.detail().orElse("git refused to remove it")));
                        return;
                    }
                    tab.showHandoffDone("Removed");
                    if (outcome.sessionDeleted()) {
                        onSessionDeleted.accept(sessionId);
                    }
                    onSessionsChanged.run();
                }));
```

The 1.2s `PauseTransition` that used to delay the row removal goes away with it; the "✓ Removed" pill now disappears with the tab. If that reads as too abrupt when you run it, keep the pause around the `onSessionDeleted`/`onSessionsChanged` pair only — never around the git call.

7. Update the class javadoc: merge no longer hands off to Claude except for conflicts, and the flow lives in `MergeAndFinishFlow`.

- [ ] **Step 3: Update the Finish panel**

In `FinishWorktreePanel`:

1. Add `boolean worktreeClean` as the last component of `Context`.
2. Add to `Context`:

```java
        /**
         * The merge action's caption. States the whole outcome up front --
         * this is the one place the user is told that a merge also deletes
         * things.
         */
        String mergeCaption() {
            if (!worktreeClean) {
                return "Commit or discard the worktree's changes first";
            }
            return "Runs git merge --no-ff, then removes the worktree, "
                    + (branchWillBeDeleted ? "deletes " + branch + " " : "keeps " + branch + " ")
                    + "and closes this session";
        }
```

3. In the `NONE` case, replace the merge action with:

```java
                getChildren().add(context.worktreeClean()
                        ? action("Merge into " + context.base(), context.mergeCaption(),
                                "finish-action-accent", () -> runAndClose(actions::mergeIntoBase, onClose))
                        : disabledAction("Merge into " + context.base(), context.mergeCaption()));
```

4. Add:

```java
    /**
     * A non-clickable action box. Used for a merge that cannot run yet: the
     * reason belongs where the action is, not behind a click that fails.
     */
    private static Region disabledAction(String titleText, String captionText) {
        Label title = new Label(titleText);
        title.getStyleClass().add("finish-action-title");
        Label caption = new Label(captionText);
        caption.getStyleClass().add("finish-action-caption");
        caption.setWrapText(true);
        VBox box = new VBox(2, title, caption);
        box.getStyleClass().addAll("finish-action-box", "finish-action-disabled");
        return box;
    }
```

5. Update the class javadoc's `NONE` bullet: merge is now merge-and-finish, and is disabled while the worktree is unclean.

- [ ] **Step 4: Add the CSS**

Append to the "Finish panel" section of `app/src/main/resources/app/drydock/ui/app.css`, after `.finish-action-destructive:hover` (:1511-1513):

```css
.finish-action-disabled {
    -fx-opacity: 0.45;
    -fx-cursor: default;
}
.finish-action-disabled:hover {
    -fx-background-color: transparent;
}
.merge-flow-headline {
    -fx-text-fill: -drydock-text;
    -fx-font-size: 14px;
    -fx-font-weight: 600;
}
.merge-flow-headline-error {
    -fx-text-fill: -drydock-error;
    -fx-font-size: 14px;
    -fx-font-weight: 600;
}
.merge-flow-detail {
    -fx-text-fill: -drydock-text-dim;
    -fx-font-size: 12px;
}
```

- [ ] **Step 5: Verify by running the app**

Run: `./gradlew :app:run`

Walk these five, in this order:

1. **Happy path.** Create a worktree session, commit something in it, Finish ▸ → "Merge into main". Expect: modal narrates "Checking the main checkout…" → "Merging…" → "Removing worktree…" → "✓ Merged &lt;branch&gt; into main / worktree removed · branch &lt;branch&gt; deleted · session closed" with Done. After Done: tab gone, sidebar row gone, `git -C <repo> log --oneline -1` shows the merge commit, `git -C <repo> branch --list <branch>` is empty.
2. **Dirty worktree.** Leave an uncommitted change in the worktree, open Finish ▸. Expect the merge action greyed out with "Commit or discard the worktree's changes first", and Delete still working.
3. **Base drift.** With a session open, `git -C <repo> checkout -b release/tmp`, then Finish ▸ → Merge. Expect "✗ The main checkout is on release/tmp, not main", nothing merged, nothing deleted.
4. **Conflict hand-off.** Make the worktree and the main checkout edit the same line, commit both, then Merge. Expect the modal to switch to "Conflicts in 1 file — Claude is resolving them…", a prompt to appear in the terminal, and — once Claude commits the merge — the flow to continue into cleanup on its own and end at "✓ Merged … — conflicts resolved by Claude".
5. **Dismissal.** During a merge, press Esc, then open "New worktree" from the sidebar. Expect the flow's result NOT to replace that modal — you get the tab notice instead — and the New worktree modal to still work.

Record any deviation as a follow-up; do not paper over it in the copy.

- [ ] **Step 6: Full test suite and commit**

Run: `./gradlew :app:test`
Expected: PASS, whole suite.

```bash
git add app/src/main/java/app/drydock/ui/MergeAndFinishFlow.java app/src/main/java/app/drydock/ui/WorktreeLifecycleController.java app/src/main/java/app/drydock/ui/FinishWorktreePanel.java app/src/main/resources/app/drydock/ui/app.css
git commit -m "Merge into base now finishes the worktree and says so"
```
