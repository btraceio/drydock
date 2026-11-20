package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.ReviewBase;
import app.drydock.git.WorktreeService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionReviewScopeResolverTest {

    private static final byte[] SECRET = new byte[32];

    private final WorktreeService worktrees = new WorktreeService();
    private final GitStatusService git = new GitStatusService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry(SECRET);
    private final List<ManagedAgentSession> known = new ArrayList<>();
    private final SessionReviewScopeResolver resolver =
            new SessionReviewScopeResolver(worktrees, git, registry, () -> List.copyOf(known));

    @AfterEach
    void close() {
        worktrees.close();
        git.close();
    }

    @Test
    void linkedWorktreeResolvesToAWorktreeScopeBoundToTheSession(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession session = session(wt);

        ReviewScope scope = resolver.resolve(session).get();

        assertEquals(ReviewScope.Kind.WORKTREE, scope.kind());
        assertEquals(repo.toRealPath(), scope.repoRoot().toRealPath());
        assertEquals(wt.toRealPath(), scope.worktree().orElseThrow().toRealPath());
        assertEquals(session.id(), scope.sessionId().orElseThrow());
        assertTrue(registry.isAddressableBy(scope.id(), session.id()));
        assertFalse(registry.isAddressableBy(scope.id(), ManagedSessionId.newId()));
    }

    @Test
    void mainCheckoutResolvesToAWorkingTreeScope(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        ReviewScope scope = resolver.resolve(session(repo)).get();

        assertEquals(ReviewScope.Kind.WORKING_TREE, scope.kind());
    }

    @Test
    void aRegistryWithTheSameSecretMintsTheSameId(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession session = session(wt);
        SessionReviewScopeResolver second = new SessionReviewScopeResolver(
                worktrees, git, new ReviewScopeRegistry(SECRET), List::of);

        assertEquals(resolver.resolve(session).get().id(), second.resolve(session).get().id());
    }

    @Test
    void aDirectoryOutsideGitCompletesExceptionally(@TempDir Path notARepo) {
        CompletionException e = assertThrows(CompletionException.class,
                () -> resolver.resolve(session(notARepo)).join());
        assertInstanceOf(NotAGitRepositoryException.class, e.getCause());
    }

    @Test
    void aDeletedSiblingWorktreeDoesNotBreakResolution(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        Path sibling = wtParent.resolve("sibling");
        runGit(repo, "worktree", "add", "-b", "gone", sibling.toString());
        try (Stream<Path> files = Files.walk(sibling)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }

        ReviewScope scope = resolver.resolve(session(wt)).get();

        assertEquals(ReviewScope.Kind.WORKTREE, scope.kind());
        assertEquals(wt.toRealPath(), scope.worktree().orElseThrow().toRealPath());
    }

    @Test
    void aSecondSessionIsRefusedWhileTheHolderIsRunning(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession a = session(wt, SessionStatus.RUNNING, "HolderTask");
        known.add(a);
        ReviewScope scope = resolver.resolve(a).get();

        CompletionException e = assertThrows(CompletionException.class,
                () -> resolver.resolve(session(wt, SessionStatus.INACTIVE, "RefusedTask")).join());

        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("HolderTask"));
        assertFalse(e.getCause().getMessage().contains("RefusedTask"));
        assertTrue(registry.isAddressableBy(scope.id(), a.id()));
    }

    @Test
    void aSecondSessionRebindsTheScopeOfAnExitedHolder(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession a = session(wt, SessionStatus.EXITED);
        known.add(a);
        ReviewScope first = resolver.resolve(a).get();
        ManagedAgentSession b = session(wt, SessionStatus.INACTIVE);

        ReviewScope second = resolver.resolve(b).get();

        assertEquals(first.id(), second.id());
        assertTrue(registry.isAddressableBy(second.id(), b.id()));
        assertFalse(registry.isAddressableBy(second.id(), a.id()));
    }

    @Test
    void aPrCheckoutResolvesAgainstTheDeclaredBaseWhenTheLookupNamesThePullRequest(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent, "pr-12");
        // The declared base must be a branch git can resolve here (the same
        // rule reviewBase applies to the heuristic's candidates).
        runGit(repo, "branch", "feature-parent", "main");
        ManagedAgentSession session = session(wt);
        SessionReviewScopeResolver withPr = new SessionReviewScopeResolver(worktrees, git, registry,
                () -> List.copyOf(known),
                (root, branch) -> CompletableFuture.completedFuture(Optional.of(
                        new GhCliService.OpenPullRequest(12, "A PR", "pr-12", "feature-parent",
                                false, Optional.empty(), Optional.empty()))));

        ReviewScope scope = withPr.resolve(session).get();

        assertEquals("feature-parent", scope.base());
        assertEquals(Optional.of(ReviewBase.Origin.PULL_REQUEST), scope.baseOrigin());
        assertEquals(12, scope.pr().orElseThrow().number());
        assertEquals(session.id(), scope.sessionId().orElseThrow());
    }

    @Test
    void aPrCheckoutWithoutALookupOrWithAnEmptyOneKeepsTheForkPointBase(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent, "pr-12");
        ManagedAgentSession session = session(wt);

        // The 4-arg constructor: no lookup wired at all.
        ReviewScope scopeless = resolver.resolve(session).get();
        assertEquals(Optional.of(ReviewBase.Origin.FORKED_FROM), scopeless.baseOrigin());

        // A lookup wired but answering "no PR" mints the same identity and
        // the same fork-point base.
        SessionReviewScopeResolver withEmpty = new SessionReviewScopeResolver(worktrees, git, registry,
                () -> List.copyOf(known),
                (root, branch) -> CompletableFuture.completedFuture(Optional.empty()));
        ReviewScope scope = withEmpty.resolve(session).get();
        assertEquals(scopeless.id(), scope.id());
        assertEquals(Optional.of(ReviewBase.Origin.FORKED_FROM), scope.baseOrigin());
    }

    @Test
    void theLookupIsNotConsultedForAnOrdinaryBranch(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent, "feat");
        ManagedAgentSession session = session(wt);
        AtomicInteger lookups = new AtomicInteger();
        SessionReviewScopeResolver withPr = new SessionReviewScopeResolver(worktrees, git, registry,
                () -> List.copyOf(known), (root, branch) -> {
                    lookups.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                });

        withPr.resolve(session).get();

        assertEquals(1, lookups.get());
    }

    private static ManagedAgentSession session(Path dir) {
        return session(dir, SessionStatus.INACTIVE);
    }

    private static ManagedAgentSession session(Path dir, SessionStatus status) {
        return session(dir, status, "Task");
    }

    private static ManagedAgentSession session(Path dir, SessionStatus status, String name) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                name, AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(dir), Instant.EPOCH).status(status).build();
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static Path linkedWorktree(Path repo, Path parent) throws IOException, InterruptedException {
        return linkedWorktree(repo, parent, "feat");
    }

    private static Path linkedWorktree(Path repo, Path parent, String branch)
            throws IOException, InterruptedException {
        Path wt = parent.resolve("wt");
        runGit(repo, "worktree", "add", "-b", branch, wt.toString());
        Files.writeString(wt.resolve("change.txt"), "change\n");
        runGit(wt, "add", "change.txt");
        runGit(wt, "commit", "-m", "wt change");
        return wt;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
