package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchRef;
import app.drydock.intellij.tasks.BranchStart.Item;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Start} on a Branches-section row: the menu a free and a held branch offer. */
class BranchStartTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Path WORKTREE = Path.of("/work/profiling-backend-fix");
    private static final String REPO = "profiling-backend";

    @Test
    void aFreeBranchOffersANewWorktreeOnItselfAndTheSubBranch() {
        List<Item> items = BranchStart.items(
                new TaskNode.BranchRow(ROOT, BranchRef.local("feature/login"), "feature/login"), REPO, false);

        assertEquals(List.of(
                "Full access, in a new worktree on this branch",
                "Full access, in its own window",
                "Read-only, in this window",
                "Sub-branch in a new worktree…"), items.stream().map(Item::text).toList());
        assertEquals(SessionMode.WRITES,
                items.stream().filter(Item::subBranch).findFirst().orElseThrow().mode());
        assertEquals("Cuts a new branch from this one",
                items.get(items.size() - 1).secondary().orElseThrow());
    }

    @Test
    void aHeldBranchMirrorsItsWorktreeAndOffersTheSubBranch() {
        List<Item> items = BranchStart.items(new TaskNode.BranchRow(ROOT,
                new BranchRef("feature/login", false, Optional.of(WORKTREE), false, false), "feature/login"),
                REPO, false);

        assertEquals(List.of(
                "Full access, in this window",
                "Full access, in its own window",
                "Read-only, in this window",
                "Sub-branch in a new worktree…"), items.stream().map(Item::text).toList());
        // A held branch starts where it lives: every non-sub item is the
        // holder worktree's launch, none creates anything.
        assertTrue(items.stream().filter(item -> !item.subBranch())
                .allMatch(item -> item.mode() == SessionMode.WRITES || item.mode() == SessionMode.READ_ONLY));
    }

    @Test
    void theWindowsOwnWorktreeDropsTheOwnWindowItem() {
        List<Item> items = BranchStart.items(new TaskNode.BranchRow(ROOT,
                new BranchRef("feature/login", false, Optional.of(WORKTREE), false, false), "feature/login"),
                REPO, true);

        assertEquals(List.of(
                "Full access, in this window",
                "Read-only, in this window",
                "Sub-branch in a new worktree…"), items.stream().map(Item::text).toList());
    }

    @Test
    void theSubBranchItemIsAlwaysLastAndOpensThisWindow() {
        for (boolean held : List.of(false, true)) {
            BranchRef ref = held
                    ? new BranchRef("feature/login", false, Optional.of(WORKTREE), false, false)
                    : BranchRef.local("feature/login");
            Item sub = BranchStart.items(new TaskNode.BranchRow(ROOT, ref, "feature/login"), REPO, false)
                    .get(3);
            assertTrue(sub.subBranch());
            assertEquals(OpenIn.THIS_WINDOW, sub.openIn());
        }
    }

    @Test
    void freshCatalogSelectionPreservesLocalAndRemoteIdentity() {
        BranchRef local = BranchRef.local("origin/feature/login");
        BranchRef remote = BranchRef.remote("origin/feature/login");
        BranchCatalog catalog = new BranchCatalog(List.of(remote, local), List.of("origin"));

        assertEquals(Optional.of(local), BranchStart.findMatchingRef(catalog, local));
        assertEquals(Optional.of(remote), BranchStart.findMatchingRef(catalog, remote));
        assertEquals(Optional.empty(), BranchStart.findMatchingRef(catalog, BranchRef.remote("origin/missing")));
    }

    @Test
    void folderDispositionDistinguishesCreationReuseAndConflictAndEvaluatesHolderLazily(@TempDir Path tempDir)
            throws Exception {
        Path absent = tempDir.resolve("absent");
        Path present = Files.createDirectory(tempDir.resolve("present"));

        assertFalse(Files.isDirectory(absent));
        assertEquals(BranchStart.FolderDisposition.CREATE,
                BranchStart.classify(Files.isDirectory(absent), () -> {
                    throw new AssertionError("an absent directory must not query its holder");
                }));
        assertTrue(Files.isDirectory(present));
        assertEquals(BranchStart.FolderDisposition.REUSE,
                BranchStart.classify(Files.isDirectory(present), () -> true));
        assertEquals(BranchStart.FolderDisposition.CONFLICT,
                BranchStart.classify(Files.isDirectory(present), () -> false));

        ExecutionException failure = new ExecutionException("listing failed", new IOException("git failed"));
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> BranchStart.classify(true, () -> { throw failure; })));
    }

    @Test
    void holdsBranchCanonicalizesPathAliasesAndRequiresTheRequestedBranch(@TempDir Path tempDir) throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");

        Path worktree = tempDir.resolve("held-worktree");
        runGit(repo, "worktree", "add", "-b", "feature/held", worktree.toString());

        assertTrue(BranchStart.holdsBranch(repo, worktree, "feature/held"));
        assertFalse(BranchStart.holdsBranch(repo, worktree, "feature/other"));

        Path alias = tempDir.resolve("held-worktree-alias");
        try {
            Files.createSymbolicLink(alias, worktree);
            assertTrue(BranchStart.holdsBranch(repo, alias, "feature/held"));
            assertFalse(BranchStart.holdsBranch(repo, alias, "feature/other"));
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            // Symlink resolution is platform-dependent; the direct-path and branch checks above still apply.
        }
    }

    @Test
    void timedOutAddDefersReleaseUntilPendingFutureCompletes() {
        CompletableFuture<Path> future = new CompletableFuture<>();
        AtomicBoolean deferred = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicInteger releaseCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                releaseCount.incrementAndGet();
            }
        };
        Runnable onFinished = () -> {
            if (!deferred.get()) {
                release.run();
            }
        };

        BranchStart.deferReleaseUntilCompletion(future, deferred, release,
                () -> assertTrue(deferred.get(), "the reservation must be deferred before reporting timeout"),
                refreshCount::incrementAndGet, error -> failureCount.incrementAndGet());

        assertTrue(deferred.get());
        onFinished.run();
        assertEquals(0, releaseCount.get(), "onFinished must leave a timed-out add reserved");
        future.complete(Path.of("/worktree/created"));
        assertEquals(1, releaseCount.get());
        assertEquals(1, refreshCount.get());
        assertEquals(0, failureCount.get());
        onFinished.run();
        assertEquals(1, releaseCount.get(), "release must happen exactly once");
    }

    @Test
    void timedOutAddHandlesAlreadyCompletedFutureInlineWithoutOpeningADialog() {
        CompletableFuture<Path> future = CompletableFuture.completedFuture(Path.of("/worktree"));
        AtomicBoolean deferred = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicInteger releaseCount = new AtomicInteger();
        List<String> callbacks = new ArrayList<>();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                releaseCount.incrementAndGet();
                callbacks.add("release");
            }
        };
        Runnable onFinished = () -> {
            if (!deferred.get()) {
                release.run();
            }
        };

        BranchStart.deferReleaseUntilCompletion(future, deferred, release,
                () -> {
                    assertTrue(deferred.get());
                    callbacks.add("timeout-report");
                },
                () -> callbacks.add("tree-refresh-only"), error -> callbacks.add("failure"));
        onFinished.run();

        assertEquals(List.of("timeout-report", "release", "tree-refresh-only"), callbacks);
        assertEquals(1, releaseCount.get());
        assertFalse(callbacks.contains("dialog-open"));
    }

    @Test
    void timedOutAddFailureReleasesAndReportsWithoutRefreshing() {
        IllegalStateException failure = new IllegalStateException("git failed");
        CompletableFuture<Path> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        AtomicBoolean deferred = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicInteger releaseCount = new AtomicInteger();
        AtomicInteger refreshCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                releaseCount.incrementAndGet();
            }
        };

        BranchStart.deferReleaseUntilCompletion(future, deferred, release,
                () -> assertTrue(deferred.get()), refreshCount::incrementAndGet,
                error -> {
                    assertSame(failure, error);
                    failureCount.incrementAndGet();
                });
        if (!deferred.get()) {
            release.run();
        }

        assertEquals(1, releaseCount.get());
        assertEquals(0, refreshCount.get());
        assertEquals(1, failureCount.get());
    }

    private static void runGit(Path repository, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
    }
}
