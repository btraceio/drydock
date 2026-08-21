package app.drydock.app;

import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.handoff.HandoffStaleness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real repositories, because every invariant here is about what git is left
 * NOT doing: no branch, no worktree, no transplant -- the outgoing worktree
 * and its dirty tree are exactly what the successor inherits, unmoved.
 */
class SessionHandoffServiceTest {

    private Path repositoryRoot;
    private Path seedDirectory;
    private ManagedAgentSession outgoing;

    private final Map<ManagedSessionId, HandoffBrief> briefs = new HashMap<>();
    private RecordingLauncher launcher;
    private FailableDeleter deleter;
    private RecordingRebinder rebinder;
    private SessionHandoffService service;

    /** Records what it was asked to start, and never starts anything real. */
    private static final class RecordingLauncher implements SessionHandoffService.Launcher {
        int startCount;
        String lastPrompt;
        AgentKind lastKind;
        ManagedAgentSession lastOutgoing;
        boolean failNext;
        final ManagedSessionId successorId = ManagedSessionId.newId();

        @Override
        public CompletableFuture<ManagedSessionId> start(ManagedAgentSession outgoing, AgentKind kind,
                                                         String seedPrompt) {
            if (failNext) {
                return CompletableFuture.failedFuture(new IllegalStateException("launch refused"));
            }
            startCount++;
            lastOutgoing = outgoing;
            lastKind = kind;
            lastPrompt = seedPrompt;
            return CompletableFuture.completedFuture(successorId);
        }
    }

    /**
     * The delete, unless told to fail -- which is how the abort is exercised.
     * Mirrors {@code SessionManager.deleteSession} by dropping the session's
     * brief along with it: without that, a seed composed AFTER the delete
     * would still see the brief and this fake could not catch the bug the
     * ordering exists to prevent.
     */
    private final class FailableDeleter implements SessionHandoffService.SessionDeleter {
        final List<ManagedSessionId> deleted = new ArrayList<>();
        boolean failNext;
        java.util.function.IntSupplier observeStartCount = () -> -1;
        int startCountAtDelete = -1;

        @Override
        public CompletableFuture<Void> delete(ManagedSessionId sessionId) {
            if (failNext) {
                return CompletableFuture.failedFuture(new IllegalStateException("state write refused"));
            }
            startCountAtDelete = observeStartCount.getAsInt();
            deleted.add(sessionId);
            briefs.remove(sessionId);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Records the rebind so ordering against the launch can be asserted. */
    private static final class RecordingRebinder implements SessionHandoffService.ScopeRebinder {
        ManagedSessionId from;
        ManagedSessionId to;

        @Override
        public void rebind(ManagedSessionId outgoing, ManagedSessionId successor) {
            from = outgoing;
            to = successor;
        }
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        repositoryRoot = Files.createDirectories(dir.resolve("repo"));
        seedDirectory = dir.resolve("state").resolve("handoff-seeds");
        git(repositoryRoot, "init", "-b", "main");
        Files.writeString(repositoryRoot.resolve("README.md"), "hello\n");
        git(repositoryRoot, "add", ".");
        commit(repositoryRoot, "initial");
        git(repositoryRoot, "checkout", "-b", "feat/work");
        Files.writeString(repositoryRoot.resolve("a.txt"), "committed\n");
        git(repositoryRoot, "add", ".");
        commit(repositoryRoot, "add a.txt");

        outgoing = session(repositoryRoot);
        launcher = new RecordingLauncher();
        deleter = new FailableDeleter();
        rebinder = new RecordingRebinder();
        service = new SessionHandoffService(
                new GitStatusService(),
                new GitExecutableLocator(),
                launcher,
                deleter,
                rebinder,
                id -> Optional.ofNullable(briefs.get(id)),
                id -> List.of("Rework the rail"),
                seedDirectory,
                ForkJoinPool.commonPool());
    }

    @Test
    void theSuccessorRunsInTheOutgoingSessionsOwnWorktree() {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(AgentKind.CODEX, launcher.lastKind);
        assertEquals(outgoing.id(), launcher.lastOutgoing.id());
        assertEquals(outgoing.workingDirectory(), launcher.lastOutgoing.workingDirectory());
    }

    @Test
    void nothingInTheWorkingTreeMovesOrChanges() throws Exception {
        // The rescue case: the uncommitted work is exactly what must survive,
        // and the surest way to keep it is never to copy it.
        Files.writeString(repositoryRoot.resolve("wip.txt"), "half done");
        String before = gitCapture(repositoryRoot, "status", "--porcelain");

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "status", "--porcelain"));
        assertEquals("feat/work", gitCapture(repositoryRoot, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertEquals("half done", Files.readString(repositoryRoot.resolve("wip.txt")));
    }

    @Test
    void noBranchIsCreated() throws Exception {
        String before = gitCapture(repositoryRoot, "branch", "--list");

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "branch", "--list"));
    }

    @Test
    void noWorktreeIsCreated() throws Exception {
        String before = gitCapture(repositoryRoot, "worktree", "list");

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "worktree", "list"));
    }

    @Test
    void theOutgoingSessionIsDeletedBeforeTheSuccessorStarts() {
        // `gitCapture` and `Files.*` throw; any test here that calls them
        // needs `throws Exception`, as the copied ones already do.
        // Two agent processes on one worktree is the one hazard this design
        // has no defence against beyond never creating it.
        deleter.observeStartCount = () -> launcher.startCount;

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(List.of(outgoing.id()), deleter.deleted);
        assertEquals(0, deleter.startCountAtDelete);
        assertEquals(1, launcher.startCount);
    }

    @Test
    void aFailedDeleteStartsNoSuccessor() {
        // The surface is closed but the metadata write lost: degraded, not
        // damaged. Starting anyway would put two sessions on one worktree.
        deleter.failNext = true;

        assertThrows(IllegalStateException.class, () -> service.handOffBlocking(outgoing, AgentKind.CODEX));

        assertEquals(0, launcher.startCount, "no session may be started for a failed handoff");
    }

    @Test
    void aFailedLaunchAfterTheDeleteHasAlreadyCommittedLeavesNoSuccessorEither() {
        // The trade-off this design accepts: the delete must precede the
        // launch (two agents on one worktree is the hazard it cannot
        // survive), so a launch failure in this window leaves the outgoing
        // session deleted with nothing that replaces it -- an orphaned seed
        // file, not an orphaned worktree.
        launcher.failNext = true;

        assertThrows(IllegalStateException.class, () -> service.handOffBlocking(outgoing, AgentKind.CODEX));

        assertEquals(List.of(outgoing.id()), deleter.deleted);
    }

    @Test
    void theOutgoingSessionsReviewScopesFollowItToTheSuccessor() {
        ManagedSessionId successor = service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(outgoing.id(), rebinder.from);
        assertEquals(successor, rebinder.to);
    }

    @Test
    void anAlreadyDeadSessionIsHandedOffWithoutSpecialCasing() {
        // The common rescue case. deleteSession closes nothing when there is
        // no active surface and removes the metadata just the same, so this
        // path needs no branch of its own -- assert that it has none.
        ManagedAgentSession dead = session(repositoryRoot).withStatus(SessionStatus.EXITED);

        service.handOffBlocking(dead, AgentKind.CODEX);

        assertEquals(List.of(dead.id()), deleter.deleted);
        assertEquals(1, launcher.startCount);
    }

    @Test
    void handingOffToTheSameAgentIsAllowed() {
        // A wedged process is a reason to hand off even when the harness is fine.
        service.handOffBlocking(outgoing, AgentKind.CLAUDE);

        assertEquals(AgentKind.CLAUDE, launcher.lastKind);
    }

    @Test
    void seedsTheSuccessorWithTheBriefWhenOneExists() throws Exception {
        briefs.put(outgoing.id(), brief(outgoing.id(), "Ship the handoff gesture"));

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertTrue(seedFileContents().contains("Ship the handoff gesture"), seedFileContents());
    }

    /**
     * The property this whole mechanism exists for. A prompt is TYPED as
     * keystrokes and MainWorkspace.sendTaskWhenReady collapses whitespace
     * first, because an embedded newline submits the line before it. A
     * multi-line seed would arrive as one run-on line with its structure gone.
     */
    @Test
    void thePromptIsASingleLineThatSurvivesWhitespaceCollapsing() {
        briefs.put(outgoing.id(), brief(outgoing.id(), "Ship the handoff gesture"));

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertFalse(launcher.lastPrompt.contains("\n"), launcher.lastPrompt);
        assertFalse(launcher.lastPrompt.contains("\r"), launcher.lastPrompt);
        assertFalse(launcher.lastPrompt.contains("\t"), launcher.lastPrompt);
        assertEquals(launcher.lastPrompt, launcher.lastPrompt.replaceAll("\\s+", " ").strip());
    }

    @Test
    void thePromptPointsAtTheSeedFileAndAsksForItsDeletion() {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertTrue(launcher.lastPrompt.contains(seedFile().toString()), launcher.lastPrompt);
        assertTrue(launcher.lastPrompt.toLowerCase(Locale.ROOT).contains("delete"), launcher.lastPrompt);
    }

    @Test
    void theSeedFileLivesOutsideTheWorktreeSoItNeverEntersTheDiff() throws Exception {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertFalse(seedFile().startsWith(outgoing.workingDirectory()), seedFile().toString());
        assertEquals("", gitCapture(repositoryRoot, "status", "--porcelain").strip());
    }

    @Test
    void theSeedFileIsOwnerOnlyBecauseABriefCanQuoteAnything() throws Exception {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(seedFile())));
    }

    @Test
    void staleSeedsAreSweptEvenThoughTheAgentWasAskedToDeleteTheirs() throws Exception {
        // The request to delete goes to an agent, so it is not a guarantee.
        service.handOffBlocking(outgoing, AgentKind.CODEX);
        Path seed = seedFile();

        service.sweepStaleSeeds(Instant.now().plus(Duration.ofDays(8)));

        assertFalse(Files.exists(seed), "an unread seed must not sit on disk forever");
    }

    @Test
    void aFreshSeedSurvivesTheSweep() throws Exception {
        service.handOffBlocking(outgoing, AgentKind.CODEX);
        Path seed = seedFile();

        service.sweepStaleSeeds(Instant.now());

        assertTrue(Files.exists(seed));
    }

    private Path seedFile() {
        try (var files = Files.list(seedDirectory)) {
            return files.findFirst().orElseThrow(() -> new AssertionError("no seed file written"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private String seedFileContents() throws IOException {
        return Files.readString(seedFile());
    }

    @Test
    void saysSoWhenNoBriefWasRecorded() throws Exception {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertTrue(seedFileContents().contains("No handoff brief was recorded"), seedFileContents());
    }

    @Test
    void theSeedCarriesFactsDrydockDerivedItself() throws Exception {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        String seed = seedFileContents();
        assertTrue(seed.contains("add a.txt"), seed);
        assertTrue(seed.contains("derived by drydock"), seed);
    }

    @Test
    void theSeedsChangedFileListIsCappedAndSaysHowManyItLeftOut() throws Exception {
        // Thousands of untracked files must not become thousands of bullet
        // lines in the successor's very first prompt.
        for (int i = 0; i < 60; i++) {
            Files.writeString(repositoryRoot.resolve("scratch-" + i + ".txt"), "x");
        }

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        String seed = seedFileContents();
        assertTrue(seed.contains("and 10 more"), seed);
        // A truncated list that did not say so would read as the whole tree.
        assertTrue(seed.lines().filter(l -> l.startsWith("- ")).count() < 60, seed);
    }

    @Test
    void theSeedCarriesTheOutgoingSessionsOpenReviewIntents() throws Exception {
        // The most concrete statement of what is still open; a successor that
        // re-litigates a settled intent does the work twice.
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertTrue(seedFileContents().contains("Rework the rail"), seedFileContents());
        assertTrue(seedFileContents().contains("Open review intents"), seedFileContents());
    }

    @Test
    void theSeedIsWrittenBeforeTheDeleteTakesTheBriefWithIt() throws Exception {
        briefs.put(outgoing.id(), brief(outgoing.id(), "Ship the handoff gesture"));

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertTrue(seedFileContents().contains("Ship the handoff gesture"), seedFileContents());
    }

    @Test
    void stalenessIsCleanWhenNothingMovedSinceTheBrief() throws Exception {
        String head = gitCapture(repositoryRoot, "rev-parse", "HEAD").strip();
        briefs.put(outgoing.id(), brief(outgoing.id(), "Goal", Optional.of(head)));

        assertFalse(service.stalenessBlocking(outgoing).shouldWarn());
    }

    @Test
    void stalenessCountsCommitsAndFilesSinceTheBrief() throws Exception {
        String head = gitCapture(repositoryRoot, "rev-parse", "HEAD").strip();
        briefs.put(outgoing.id(), brief(outgoing.id(), "Goal", Optional.of(head)));
        Files.writeString(repositoryRoot.resolve("b.txt"), "later\n");
        git(repositoryRoot, "add", ".");
        commit(repositoryRoot, "later work");

        HandoffStaleness staleness = service.stalenessBlocking(outgoing);

        assertTrue(staleness.shouldWarn());
        assertEquals(1, staleness.commitsSince());
    }

    @Test
    void aBriefWhoseCommitNoLongerExistsReadsAsNotStaleRatherThanBreaking() {
        // History can be rewritten under a brief. The banner must not throw.
        briefs.put(outgoing.id(), brief(outgoing.id(), "Goal",
                Optional.of("0000000000000000000000000000000000000000")));

        assertFalse(service.stalenessBlocking(outgoing).shouldWarn());
    }

    @Test
    void aMissingBriefAlwaysWarns() {
        assertTrue(service.stalenessBlocking(outgoing).shouldWarn());
    }

    // ---- fixtures -----------------------------------------------------------

    private ManagedAgentSession session(Path workingDirectory) {
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "Session 1",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(workingDirectory.toAbsolutePath().normalize(), Optional.empty(), true),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    private static HandoffBrief brief(ManagedSessionId id, String goal) {
        return brief(id, goal, Optional.of("abc1234"));
    }

    private static HandoffBrief brief(ManagedSessionId id, String goal, Optional<String> commit) {
        return new HandoffBrief(id, goal, "Wire the banner", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Instant.parse("2026-08-12T10:15:30Z"), commit,
                HandoffBrief.Author.AGENT);
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void git(Path repo, String... args) throws Exception {
        gitCapture(repo, args);
    }

    private static String gitCapture(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
