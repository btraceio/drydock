package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
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
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewTabScopesTest {

    private static final byte[] SECRET = new byte[32];

    private final WorktreeService worktrees = new WorktreeService();
    private final GitStatusService git = new GitStatusService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry(SECRET);
    private final List<ManagedAgentSession> known = new ArrayList<>();
    private final ReviewTabScopes tab =
            new ReviewTabScopes(worktrees, git, registry, () -> List.copyOf(known));

    @AfterEach
    void close() {
        worktrees.close();
        git.close();
    }

    @Test
    void aScopeBoundToSessionAStaysBoundToAWhenTheLinkNamesSessionB(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession a = session(wt);
        ManagedAgentSession b = session(wt);
        ReviewScope first = tab.resolve(wt, Optional.of(a)).get();

        ReviewScope loaded = tab.resolve(wt, Optional.of(b)).get();

        assertEquals(first.id(), loaded.id());
        assertEquals(Optional.of(a.id()), loaded.sessionId());
        assertTrue(registry.isAddressableBy(loaded.id(), a.id()));
        assertFalse(registry.isAddressableBy(loaded.id(), b.id()));
    }

    @Test
    void aCheckoutWithNoScopeBindsToTheLinkSessionOnly(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession b = session(wt);

        ReviewScope scope = tab.resolve(wt, Optional.of(b)).get();

        assertEquals(ReviewScope.Kind.WORKTREE, scope.kind());
        assertEquals(Optional.of(b.id()), scope.sessionId());
        assertTrue(registry.isAddressableBy(scope.id(), b.id()));
        assertFalse(registry.isAddressableBy(scope.id(), ManagedSessionId.newId()));
    }

    @Test
    void aTabOpenedDirectlyMintsAnUnboundScope(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);

        ReviewScope scope = tab.resolve(wt, Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKTREE, scope.kind());
        assertEquals(Optional.empty(), scope.sessionId());
        assertFalse(registry.isAddressableBy(scope.id(), ManagedSessionId.newId()));
    }

    @Test
    void anExistingScopeIsReturnedUnchanged(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        ManagedAgentSession a = session(wt);
        ReviewScope first = tab.resolve(wt, Optional.of(a)).get();

        ReviewScope second = tab.resolve(wt, Optional.empty()).get();

        assertEquals(first.id(), second.id());
        assertEquals(first, second);
        assertEquals(Optional.of(a.id()), second.sessionId());
    }

    @Test
    void theMainCheckoutResolvesToAWorkingTreeScope(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        ReviewScope scope = tab.resolve(repo, Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKING_TREE, scope.kind());
    }

    @Test
    void anExistingScopeIsFoundByItsPrNumber(@TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = wtParent.resolve("wt");
        runGit(repo, "worktree", "add", "-b", "pr-12", wt.toString());
        // Mint through git's own spelling of the paths (the real path on
        // macOS), the way every production mint does.
        List<Worktree> listed = worktrees.list(wt).get();
        Worktree main = listed.stream().filter(Worktree::mainCheckout).findFirst().orElseThrow();
        Worktree own = listed.stream()
                .filter(entry -> entry.branch().orElse("").equals("pr-12"))
                .findFirst().orElseThrow();
        // The identity the tab must find: this checkout's worktree scope, PR 12.
        ReviewScope withPr = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, main.path(), Optional.of(own.path()), "main", "pr-12",
                Optional.of(new ReviewScope.PullRequestRef(12, Optional.empty())), Optional.empty()));
        // The decoy this checkout must NOT resolve to: the same worktree, no PR.
        registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, main.path(), Optional.of(own.path()), "main", "pr-12",
                Optional.empty(), Optional.empty()));

        ReviewScope scope = tab.resolve(wt, Optional.empty()).get();

        assertEquals(withPr.id(), scope.id());
        assertEquals(Optional.of(12), scope.pr().map(ReviewScope.PullRequestRef::number));
    }

    @Test
    void theCheckoutIsMatchedByRealPath(
            @TempDir Path repoDir, @TempDir Path wtParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path wt = linkedWorktree(repo, wtParent);
        Path alias = Files.createSymbolicLink(wtParent.resolve("alias"), wt);

        ReviewScope throughAlias = tab.resolve(alias, Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKTREE, throughAlias.kind());
        assertEquals(wt.toRealPath(), throughAlias.worktree().orElseThrow().toRealPath());
        // Both spellings of one checkout are one scope, not two.
        assertEquals(throughAlias.id(), tab.resolve(wt, Optional.empty()).get().id());
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

        ReviewScope scope = tab.resolve(wt, Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKTREE, scope.kind());
        assertEquals(wt.toRealPath(), scope.worktree().orElseThrow().toRealPath());
    }

    @Test
    void aDirectoryOutsideGitCompletesExceptionally(@TempDir Path notARepo) {
        CompletionException e = assertThrows(CompletionException.class,
                () -> tab.resolve(notARepo, Optional.empty()).join());
        assertInstanceOf(NotAGitRepositoryException.class, e.getCause());
    }

    private static ManagedAgentSession session(Path dir) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(dir), Instant.EPOCH).build();
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
        Path wt = parent.resolve("wt");
        runGit(repo, "worktree", "add", "-b", "feat", wt.toString());
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
