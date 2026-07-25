package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary Git repositories, in the style
 * of {@link GitStatusServiceTest}: worktree discovery via
 * {@code git worktree list --porcelain} and the one-click removal of an
 * unopened worktree (worktree handoff, section B "Discovering worktrees").
 */
class WorktreeServiceTest {

    private final WorktreeService service = new WorktreeService();
    private final GitStatusService gitStatusService = new GitStatusService();

    @Test
    void listReportsTheMainCheckoutFirst(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        List<WorktreeService.Worktree> worktrees = service.list(repo).get();

        assertEquals(1, worktrees.size());
        WorktreeService.Worktree main = worktrees.get(0);
        assertTrue(main.mainCheckout());
        assertEquals(Optional.of("main"), main.branch());
        assertEquals(repo.toRealPath(), main.path().toRealPath());
    }

    @Test
    void listDiscoversWorktreesCreatedOutsideTheApp(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path outside = worktreeParent.resolve("outside-wt");
        runGit(repo, "worktree", "add", outside.toString(), "-b", "feat/outside");

        List<WorktreeService.Worktree> worktrees = service.list(repo).get();

        assertEquals(2, worktrees.size());
        WorktreeService.Worktree discovered = worktrees.get(1);
        assertFalse(discovered.mainCheckout());
        assertEquals(Optional.of("feat/outside"), discovered.branch());
        assertEquals(outside.toRealPath(), discovered.path().toRealPath());
    }

    @Test
    void listReportsADetachedWorktreeWithoutABranch(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        String head = runGitCapture(repo, "rev-parse", "HEAD").trim();
        Path detachedDir = worktreeParent.resolve("detached-wt");
        runGit(repo, "worktree", "add", "--detach", detachedDir.toString(), head);

        List<WorktreeService.Worktree> worktrees = service.list(repo).get();

        WorktreeService.Worktree detached = worktrees.get(1);
        assertTrue(detached.detached());
        assertTrue(detached.branch().isEmpty());
    }

    @Test
    void listOnNonGitDirectoryThrowsNotAGitRepositoryException(@TempDir Path notARepo) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.list(notARepo).join());
        assertInstanceOf(NotAGitRepositoryException.class, completion.getCause());
    }

    @Test
    void removeDeletesTheWorktreeAndItsBranch(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/short-lived").get();

        service.remove(repo, worktree, Optional.of("feat/short-lived")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/short-lived").contains("feat/short-lived"));
    }

    @Test
    void removeWithoutABranchOnlyRemovesTheWorktree(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/keep-branch").get();

        service.remove(repo, worktree, Optional.empty()).get();

        assertFalse(Files.exists(worktree));
        assertTrue(runGitCapture(repo, "branch", "--list", "feat/keep-branch").contains("feat/keep-branch"));
    }

    @Test
    void removeRefusesTheMainCheckout(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, repo, Optional.of("main")).join());
        assertInstanceOf(IllegalArgumentException.class, completion.getCause());
        assertTrue(Files.exists(repo.resolve("README.md")));
    }

    @Test
    void removeOfADirtyWorktreeFailsInsteadOfDiscardingChanges(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/dirty").get();
        Files.writeString(worktree.resolve("uncommitted.txt"), "precious\n");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/dirty")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree));
    }

    @Test
    void forcedRemoveDeletesADirtyWorktreeAndItsBranch(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/force").get();
        Files.writeString(worktree.resolve("uncommitted.txt"), "expendable\n");

        service.removeForced(repo, worktree, Optional.of("feat/force")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/force").contains("feat/force"));
    }

    @Test
    void forcedRemoveStillRefusesTheMainCheckout(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.removeForced(repo, repo, Optional.of("main")).join());
        assertInstanceOf(IllegalArgumentException.class, completion.getCause());
        assertTrue(Files.exists(repo.resolve("README.md")));
    }

    @Test
    void removeOfALockedWorktreeSurfacesTheLockWithItsReasonInsteadOfForcing(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/locked").get();
        runGit(repo, "worktree", "lock", "--reason", "initializing", worktree.toString());

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/locked")).join());
        WorktreeLockedException locked = assertInstanceOf(WorktreeLockedException.class, completion.getCause());
        assertEquals(Optional.of("initializing"), locked.lockReason());
        assertTrue(Files.exists(worktree));
    }

    @Test
    void forcedRemoveDeletesALockedWorktreeAndItsBranch(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/locked-force").get();
        runGit(repo, "worktree", "lock", worktree.toString());

        service.removeForced(repo, worktree, Optional.of("feat/locked-force")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/locked-force").contains("feat/locked-force"));
    }

    /**
     * Pins the discriminator the force-fallback gate is built on: a
     * submodule only blocks a plain remove once it has been checked out
     * into the worktree, so a fresh worktree of a submodule-bearing
     * repository still takes the ordinary path.
     */
    @Test
    void removeUsesThePlainPathForAWorktreeWhoseSubmoduleIsUninitialized(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/no-subs").get();
        Path worktreeGitDir = Path.of(runGitCapture(worktree, "rev-parse", "--absolute-git-dir").strip());
        assertFalse(Files.isDirectory(worktreeGitDir.resolve("modules")),
                "precondition: the submodule must not be checked out into the worktree");

        service.remove(repo, worktree, Optional.of("feat/no-subs")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
    }

    /**
     * The same worktree, left dirty: the plain path must still refuse it.
     * Proves the uninitialized case never escalates to {@code --force},
     * which a bare success assertion above cannot distinguish.
     */
    @Test
    void removeOfADirtyUninitializedSubmoduleWorktreeStillFails(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/no-subs-dirty").get();
        Files.writeString(worktree.resolve("uncommitted.txt"), "precious\n");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/no-subs-dirty")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree.resolve("uncommitted.txt")));
    }

    @Test
    void removeSucceedsForAWorktreeWithAnInitializedSubmodule(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/subs").get();
        initSubmodulesIn(worktree);

        service.remove(repo, worktree, Optional.of("feat/subs")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/subs").contains("feat/subs"));
    }

    @Test
    void removeSucceedsForASubmoduleWorktreeWhoseOnlyDirtIsInsideTheSubmodule(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub-dirt").get();
        initSubmodulesIn(worktree);
        Files.writeString(worktree.resolve("vendor/lib.txt"), "patched by the build\n");

        service.remove(repo, worktree, Optional.of("feat/sub-dirt")).get();

        assertFalse(Files.exists(worktree));
    }

    @Test
    void removeOfADirtySubmoduleWorktreeFailsInsteadOfDiscardingChanges(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub-dirty").get();
        initSubmodulesIn(worktree);
        Files.writeString(worktree.resolve("uncommitted.txt"), "precious\n");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/sub-dirty")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree.resolve("uncommitted.txt")));
    }

    @Test
    void removeOfAWorktreeWithABumpedSubmodulePointerFailsInsteadOfDiscardingTheBump(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub-bump").get();
        initSubmodulesIn(worktree);
        // Moving the submodule's HEAD is uncommitted work in *this*
        // worktree's index, unlike mere dirt inside the submodule.
        runGit(worktree.resolve("vendor"), "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "commit", "--allow-empty", "-m", "vendored bump");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/sub-bump")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree));
    }

    @Test
    void removeSucceedsWhenTheWorktreeDirectoryWasDeletedFromDiskOutsideGit(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/vanished").get();
        deleteRecursively(worktree);

        service.remove(repo, worktree, Optional.of("feat/vanished")).get();

        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/vanished").contains("feat/vanished"));
    }

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

    /**
     * Fix round 2, finding 1: an interrupt during the merge spawn must not
     * be turned into a verdict by falling through to {@code verify(...)} --
     * unlike a timeout, an interrupt means the caller no longer wants any
     * more git commands run. A slow {@code pre-merge-commit} hook keeps the
     * top-level {@code git merge} process (and therefore
     * {@code Process.waitFor}) blocked long enough to interrupt the worker
     * thread from outside; {@code CompletableFuture.cancel} would not
     * interrupt the running task, so this drives {@code mergeBlocking}
     * directly on a thread of its own.
     */
    @Test
    void mergeBlockingPropagatesAnInterruptWithoutProducingAVerdict(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/interrupt").get();
        commitFile(worktree, "feature.txt", "new feature\n", "add feature");
        Path hook = Path.of(runGitCapture(repo, "rev-parse", "--absolute-git-dir").strip())
                .resolve("hooks").resolve("pre-merge-commit");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nsleep 5\nexit 0\n");
        hook.toFile().setExecutable(true);
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/interrupt").get();

        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.mergeBlocking(repo, "feat/interrupt", target);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        Thread.sleep(500); // let the hook's sleep start before interrupting
        worker.interrupt();
        worker.join(java.time.Duration.ofSeconds(10).toMillis());

        assertFalse(worker.isAlive(), "the worker thread should have unblocked on the interrupt");
        assertInstanceOf(GitCommandInterruptedException.class, thrown.get());
    }

    /**
     * Documents the honest boundary of the oracle: {@code Merged} proves a
     * merge commit <em>of the recorded tip</em> exists on the base branch,
     * not that its content landed in the tree. That is safe only because the
     * recorded tip stays reachable from the base branch either way -- the
     * later branch delete can never lose commits -- so accepting {@code -s
     * ours} here is a deliberate, narrow limitation, not a latent bug.
     */
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

    /**
     * Pins the parent-set check's negative branch: {@code reset --hard} onto
     * the branch's tip makes {@code merge-base --is-ancestor} pass (ancestry
     * is satisfied) while no merge commit of the recorded tip exists on the
     * base branch at all. Verified by hand that replacing the parent-set
     * check with a plain {@code isAncestor} call makes this test fail --
     * see the Task 2 fix-round-1 report.
     */
    @Test
    void verifyMergeIsIndeterminateAfterAResetHardOntoTheBranch(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/reset-hard").get();
        commitFile(worktree, "feature.txt", "new feature\n", "add feature");
        WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/reset-hard").get();

        runGit(repo, "reset", "--hard", "feat/reset-hard");

        assertInstanceOf(WorktreeService.MergeVerdict.Indeterminate.class, service.verifyMerge(repo, target).get());
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

    @Test
    void parseHandlesBranchDetachedAndBareStanzas() {
        String porcelain = """
                worktree /repos/main
                HEAD 1111111111111111111111111111111111111111
                branch refs/heads/main

                worktree /repos/wt-feature
                HEAD 2222222222222222222222222222222222222222
                branch refs/heads/feat/x

                worktree /repos/wt-detached
                HEAD 3333333333333333333333333333333333333333
                detached
                """;

        List<WorktreeService.Worktree> worktrees = WorktreeService.parse(porcelain);

        assertEquals(3, worktrees.size());
        assertTrue(worktrees.get(0).mainCheckout());
        assertEquals(Optional.of("main"), worktrees.get(0).branch());
        assertFalse(worktrees.get(1).mainCheckout());
        assertEquals(Optional.of("feat/x"), worktrees.get(1).branch());
        assertTrue(worktrees.get(2).detached());
        assertTrue(worktrees.get(2).branch().isEmpty());
    }

    @Test
    void parseReadsPrunableAndLockedAttributes() {
        String porcelain = """
                worktree /repo
                HEAD 1111111111111111111111111111111111111111
                branch refs/heads/main

                worktree /gone
                HEAD 2222222222222222222222222222222222222222
                branch refs/heads/ghost
                prunable gitdir file points to non-existent location

                worktree /held
                HEAD 3333333333333333333333333333333333333333
                branch refs/heads/held-branch
                locked

                """;

        List<WorktreeService.Worktree> worktrees = WorktreeService.parse(porcelain);

        assertEquals(3, worktrees.size());
        assertFalse(worktrees.get(0).prunable());
        assertFalse(worktrees.get(0).locked());
        assertTrue(worktrees.get(1).prunable());
        assertEquals(Optional.of("ghost"), worktrees.get(1).branch());
        assertTrue(worktrees.get(2).locked());
        assertFalse(worktrees.get(2).prunable());
        assertEquals(Optional.empty(), worktrees.get(2).lockReason());
    }

    @Test
    void parseReadsTheLockReasonWhenGitRecordsOne() {
        String porcelain = """
                worktree /repo
                HEAD 1111111111111111111111111111111111111111
                branch refs/heads/main

                worktree /held
                HEAD 2222222222222222222222222222222222222222
                branch refs/heads/held-branch
                locked initializing

                """;

        List<WorktreeService.Worktree> worktrees = WorktreeService.parse(porcelain);

        assertTrue(worktrees.get(1).locked());
        assertEquals(Optional.of("initializing"), worktrees.get(1).lockReason());
    }

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

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void commitFile(Path checkout, String name, String content, String message)
            throws IOException, InterruptedException {
        Files.writeString(checkout.resolve(name), content);
        runGit(checkout, "add", name);
        runGit(checkout, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial commit");
        return repo;
    }

    /**
     * A repository whose {@code vendor/} is a submodule, mirroring
     * Drydock's own vendored {@code third_party/ghostty}. {@code
     * protocol.file.allow} has to be re-enabled explicitly: git disables
     * {@code file://} submodule transport by default (CVE-2022-39253).
     */
    private static Path initRepoWithSubmodule(Path parent) throws IOException, InterruptedException {
        Path upstream = initCommittedRepo(Files.createDirectories(parent.resolve("upstream")));
        Files.writeString(upstream.resolve("lib.txt"), "vendored\n");
        runGit(upstream, "add", "lib.txt");
        runGit(upstream, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "lib");

        Path repo = initCommittedRepo(parent);
        runGit(repo, "-c", "protocol.file.allow=always",
                "submodule", "add", "--", upstream.toString(), "vendor");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "add submodule");
        return repo;
    }

    /**
     * Checks the submodules out inside {@code worktree}, which is what
     * makes git refuse a plain {@code worktree remove} on it.
     */
    private static void initSubmodulesIn(Path worktree) throws IOException, InterruptedException {
        runGit(worktree, "-c", "protocol.file.allow=always", "submodule", "update", "--init");
    }

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

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
        return output;
    }
}
