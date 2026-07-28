package app.drydock.mcp;

import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.review.AnnotationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headlessly testable half of {@link WorkspaceMcpSessionContext}: its path
 * handling. Runs against real temporary Git repositories and real symlinks, in
 * the style of {@code WorktreeServiceTest} -- the symlink behaviour this covers
 * is unreachable from {@link McpToolRouter}'s tests, because their fake context
 * returns real paths by contract.
 *
 * <p>NOT covered here (needs a running window): {@code startSession}, which
 * delegates to {@code MainWorkspace.startAgentSession} and opens a tab.</p>
 */
class WorkspaceMcpSessionContextTest {

    private final GitStatusService gitStatusService = new GitStatusService();
    private final WorktreeService worktreeService = new WorktreeService();
    private AnnotationStore annotationStore;

    @AfterEach
    void tearDown() {
        gitStatusService.close();
        worktreeService.close();
        if (annotationStore != null) {
            annotationStore.close();
        }
    }

    // ---- realWorktreesOf ----------------------------------------------------

    /**
     * The half of {@code session_start}'s threat model that only this class can
     * close: a symlink swapped in <em>under</em> a recorded worktree path. The
     * entry must be reported as what the link now resolves to, so the router's
     * exact-path comparison sees the swap, rather than as the recorded name,
     * which would silently vouch for a directory git no longer points at.
     */
    @Test
    void realWorktreesOfResolvesASymlinkSwappedUnderARecordedPath(@TempDir Path repoDir,
                                                                  @TempDir Path worktreeParent,
                                                                  @TempDir Path decoyParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path recorded = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/swap").get()
                .toRealPath();
        Path decoy = Files.createDirectory(decoyParent.resolve("decoy")).toRealPath();

        // Swap the real worktree directory for a symlink pointing elsewhere.
        // `git worktree list` keeps reporting the RECORDED path either way.
        deleteRecursively(recorded);
        Files.createSymbolicLink(recorded, decoy);

        List<Path> resolved = contextFor(repo).realWorktreesOf(caller(repo));

        assertTrue(resolved.contains(decoy),
                "the swapped link must be reported as its target: " + resolved);
        assertFalse(resolved.contains(recorded),
                "the recorded (now symlink) path must not be reported verbatim: " + resolved);
    }

    /** An honest worktree is reported at its real path, so a symlinked argument compares equal. */
    @Test
    void realWorktreesOfReportsRealPaths(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/honest").get();

        List<Path> resolved = contextFor(repo).realWorktreesOf(caller(repo));

        assertTrue(resolved.contains(worktree.toRealPath()), String.valueOf(resolved));
    }

    /**
     * {@code git worktree list --porcelain}'s first stanza IS the main
     * checkout, so it arrives in the same list as the real worktrees. It must
     * not be reported: this list is {@code session_start}'s membership test,
     * and an agent starting a session in the repository root would put a second
     * {@code claude} in the tree the human is working in.
     */
    @Test
    void realWorktreesOfExcludesTheMainCheckout(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/real").get();

        List<Path> resolved = contextFor(repo).realWorktreesOf(caller(repo));

        assertFalse(resolved.contains(repo.toRealPath()),
                "the main checkout must not be offered as a worktree: " + resolved);
        assertEquals(List.of(worktree.toRealPath()), resolved);
    }

    /** A recorded worktree whose directory is gone is no longer a member of anything. */
    @Test
    void realWorktreesOfSkipsAVanishedWorktree(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/gone").get();
        Path realWorktree = worktree.toRealPath();
        deleteRecursively(worktree);

        List<Path> resolved = contextFor(repo).realWorktreesOf(caller(repo));

        assertFalse(resolved.contains(realWorktree), String.valueOf(resolved));
        assertFalse(resolved.contains(worktree), String.valueOf(resolved));
    }

    // ---- excerpt ------------------------------------------------------------

    @Test
    void excerptReturnsTheRequestedWindow(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Files.writeString(repo.resolve("lines.txt"), "one\ntwo\nthree\nfour\nfive\n");

        Optional<String> excerpt = contextFor(repo).excerpt(caller(repo), "lines.txt", 3, 1);

        assertEquals(Optional.of("two\nthree\nfour"), excerpt);
    }

    @Test
    void excerptClampsTheWindowAtTheFileEdges(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Files.writeString(repo.resolve("lines.txt"), "one\ntwo\n");

        assertEquals(Optional.of("one\ntwo"), contextFor(repo).excerpt(caller(repo), "lines.txt", 1, 5));
    }

    @Test
    void excerptIsEmptyForAMissingFileOrAnOutOfRangeLine(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Files.writeString(repo.resolve("lines.txt"), "one\n");
        WorkspaceMcpSessionContext context = contextFor(repo);

        assertTrue(context.excerpt(caller(repo), "nope.txt", 1, 2).isEmpty());
        assertTrue(context.excerpt(caller(repo), "lines.txt", 9, 2).isEmpty());
        assertTrue(context.excerpt(caller(repo), "lines.txt", 0, 2).isEmpty());
    }

    /**
     * The excerpt is a review aid, not a file-read tool: it never leaves the
     * caller's worktree.
     *
     * <p>The {@code ../} case writes its bait at {@code repoDir.getParent()},
     * i.e. exactly where {@code "../secret.txt"} resolves to. Without that the
     * assertion would pass on the file simply not existing, and would keep
     * passing with the containment check deleted.</p>
     */
    @Test
    void excerptRefusesAPathThatEscapesTheWorktree(@TempDir Path repoDir, @TempDir Path outside) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Files.writeString(repoDir.getParent().resolve("secret.txt"), "climbed out\n");
        Files.writeString(outside.resolve("secret.txt"), "top secret\n");
        WorkspaceMcpSessionContext context = contextFor(repo);

        assertTrue(context.excerpt(caller(repo), "../secret.txt", 1, 0).isEmpty());
        assertTrue(context.excerpt(caller(repo), outside.resolve("secret.txt").toString(), 1, 0).isEmpty());
    }

    /** ...including through a symlink that lives inside the worktree but points out of it. */
    @Test
    void excerptRefusesASymlinkOutOfTheWorktree(@TempDir Path repoDir, @TempDir Path outside) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret\n");
        Files.createSymbolicLink(repo.resolve("link.txt"), secret);

        assertTrue(contextFor(repo).excerpt(caller(repo), "link.txt", 1, 0).isEmpty());
    }

    // ---- sessions_list ------------------------------------------------------

    /**
     * A session's stored working directory is whatever path it was opened with,
     * while {@code git worktree list} reports realpaths. On macOS {@code /var}
     * is a symlink to {@code /private/var}, so keying the branch lookup
     * lexically reported a null branch for essentially every session.
     */
    @Test
    void sessionsListResolvesTheWorkingDirectoryBeforeMatchingItsBranch(@TempDir Path repoDir,
                                                                        @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/named").get();
        // Deliberately the UNRESOLVED path, exactly as a real session records it.
        session = sessionIn(localRepository(repo), worktree);

        List<McpSessionContext.SessionSummary> summaries = contextFor(repo).sessions();

        assertEquals(1, summaries.size());
        assertEquals(Optional.of("feat/named"), summaries.get(0).branch(),
                "the branch must be found through the symlinked working directory");
    }

    // ---- repositories -------------------------------------------------------

    /**
     * A remote repository reports no git state, while a local one alongside it
     * still does.
     *
     * <p>Named for what it actually checks: the empty fields alone do NOT prove
     * the {@code ssh} probe was skipped, because {@code statusOf} swallows a
     * failed probe into the same empty {@link Optional}. That the probe is
     * skipped -- {@link GitStatusService} has no cache, so one status call per
     * remote repository would open one {@code ssh} while the HTTP handler waits
     * -- is enforced by the {@code isRemote()} branch in {@code repositories()}
     * and is not what this test can distinguish.</p>
     */
    @Test
    void remoteRepositoriesCarryNoGitStateWhileLocalOnesDo(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        SshRemote unreachable = new SshRemote("nobody@invalid.invalid", "/srv/app");
        Repository remote = new Repository(RepositoryId.newId(), unreachable.placeholderRoot(), "remote-repo",
                Instant.now(), Instant.now(), RepositorySettings.DEFAULT, unreachable);

        List<McpSessionContext.RepoSummary> summaries =
                contextWith(repo, List.of(localRepository(repo), remote)).repositories();

        McpSessionContext.RepoSummary remoteSummary = summaries.stream()
                .filter(McpSessionContext.RepoSummary::remote)
                .findFirst()
                .orElseThrow();
        assertTrue(remoteSummary.branch().isEmpty());
        assertTrue(remoteSummary.dirty().isEmpty());
        assertTrue(remoteSummary.ahead().isEmpty());
        assertTrue(remoteSummary.behind().isEmpty());

        McpSessionContext.RepoSummary localSummary = summaries.stream()
                .filter(summary -> !summary.remote())
                .findFirst()
                .orElseThrow();
        assertEquals(Optional.of("main"), localSummary.branch(), "a local repository still reports git state");
    }

    // ---- one deadline per tool call -----------------------------------------

    /**
     * Each local repository used to get its own 20-second {@code join} slice,
     * so N repositories could hold the HTTP connection for 20N seconds. One
     * deadline now bounds the whole call, as it already does in {@code
     * MainWorkspace.findWorktreeOwner}: an expiry fails the call rather than
     * letting the next repository start a fresh slice.
     */
    @Test
    void reposListFailsOnceItsSharedDeadlineIsGone(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        WorkspaceMcpSessionContext context = contextFor(repo);

        assertFalse(context.repositories(System.nanoTime() + TimeUnit.SECONDS.toNanos(20)).isEmpty(),
                "a live deadline must still answer");

        McpToolException failure = assertThrows(McpToolException.class,
                () -> context.repositories(System.nanoTime() - 1));
        assertTrue(failure.getMessage().contains("did not respond in time"), failure.getMessage());
    }

    @Test
    void sessionsListFailsOnceItsSharedDeadlineIsGone(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        WorkspaceMcpSessionContext context = contextFor(repo);

        assertFalse(context.sessions(System.nanoTime() + TimeUnit.SECONDS.toNanos(20)).isEmpty(),
                "a live deadline must still answer");

        McpToolException failure = assertThrows(McpToolException.class,
                () -> context.sessions(System.nanoTime() - 1));
        assertTrue(failure.getMessage().contains("did not respond in time"), failure.getMessage());
    }

    // ---- fixtures -----------------------------------------------------------

    private Repository repository;
    private ManagedAgentSession session;

    private Repository localRepository(Path root) throws IOException {
        if (repository == null) {
            repository = new Repository(RepositoryId.newId(), root.toRealPath(), "example",
                    Instant.now(), Instant.now(), RepositorySettings.DEFAULT);
        }
        return repository;
    }

    private ManagedSessionId caller(Path root) throws IOException {
        localRepository(root);
        if (session == null) {
            session = sessionIn(repository, root.toRealPath());
        }
        return session.id();
    }

    private static ManagedAgentSession sessionIn(Repository owner, Path workingDirectory) {
        Instant now = Instant.now();
        return new ManagedAgentSession(ManagedSessionId.newId(), owner.id(), AgentKind.CLAUDE, "example session",
                Optional.empty(), Optional.empty(), workingDirectory, Optional.empty(),
                SessionStatus.RUNNING, now, now, Optional.empty(), PrState.NONE, Optional.empty(), true);
    }

    private WorkspaceMcpSessionContext contextFor(Path root) throws IOException {
        return contextWith(root, List.of(localRepository(root)));
    }

    /** No providers discovered: display names fall back to the title-cased kind. */
    private final app.drydock.agent.api.AgentRegistry agentRegistry =
            new app.drydock.agent.api.AgentRegistry(List.of(), null);

    private WorkspaceMcpSessionContext contextWith(Path root, List<Repository> repositories) throws IOException {
        caller(root);
        annotationStore = new AnnotationStore(root.resolve("annotations.json"));
        return new WorkspaceMcpSessionContext(
                () -> List.of(session),
                () -> repositories,
                annotationStore,
                new app.drydock.review.ReviewScopeRegistry(),
                agentRegistry,
                new app.drydock.review.IntentGrouping(),
                new app.drydock.git.DiffService(),
                gitStatusService,
                worktreeService,
                UserConfig::empty,
                (worktree, prompt) -> CompletableFuture.failedFuture(
                        new UnsupportedOperationException("no window in this test")));
    }

    // ---- git helpers (mirrors WorktreeServiceTest) ---------------------------

    private static Path initCommittedRepo(Path directory) throws Exception {
        runGit(directory, "init", "--initial-branch=main");
        runGit(directory, "config", "user.email", "test@example.com");
        runGit(directory, "config", "user.name", "Test");
        Files.writeString(directory.resolve("README.md"), "hello\n");
        runGit(directory, "add", ".");
        runGit(directory, "commit", "-m", "initial");
        return directory;
    }

    private static void runGit(Path workingDirectory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
