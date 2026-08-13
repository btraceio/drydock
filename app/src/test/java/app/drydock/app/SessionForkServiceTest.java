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
import app.drydock.git.WorktreeTransplant;
import app.drydock.handoff.HandoffStaleness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real repositories, because every invariant here is about what git actually
 * leaves on disk: that the outgoing worktree is untouched, that the dirty tree
 * crosses, and that a failure leaves neither worktree nor branch behind.
 */
class SessionForkServiceTest {

    private Path repositoryRoot;
    private Path worktreeParent;
    private ManagedAgentSession outgoing;

    private final Map<ManagedSessionId, HandoffBrief> briefs = new HashMap<>();
    private RecordingLauncher launcher;
    private FailableTransplant transplant;
    private SessionForkService service;

    /** Records what it was asked to start, and never starts anything real. */
    private static final class RecordingLauncher implements SessionForkService.Launcher {
        int startCount;
        String lastPrompt;
        AgentKind lastKind;
        ManagedSessionId lastParent;
        Path lastWorktree;

        @Override
        public ManagedSessionId start(Path worktree, AgentKind kind, String seedPrompt,
                                      ManagedSessionId forkedFrom) {
            startCount++;
            lastWorktree = worktree;
            lastKind = kind;
            lastPrompt = seedPrompt;
            lastParent = forkedFrom;
            return ManagedSessionId.newId();
        }
    }

    /** The real transplant, unless told to fail -- which is how rollback is exercised. */
    private static final class FailableTransplant implements SessionForkService.Transplanter {
        private final WorktreeTransplant real = new WorktreeTransplant();
        boolean failNext;

        @Override
        public int transplant(Path source, Path destination) {
            if (failNext) {
                throw new IllegalStateException("transplant refused");
            }
            return real.transplantBlocking(source, destination);
        }
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        repositoryRoot = Files.createDirectories(dir.resolve("repo"));
        worktreeParent = Files.createDirectories(dir.resolve("worktrees"));
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
        transplant = new FailableTransplant();
        service = new SessionForkService(
                new GitStatusService(),
                transplant,
                new GitExecutableLocator(),
                launcher,
                id -> Optional.ofNullable(briefs.get(id)),
                ignored -> repositoryRoot,
                branch -> worktreeParent.resolve(branch.replace('/', '-')),
                ForkJoinPool.commonPool());
    }

    @Test
    void forksOntoASiblingWorktreeRunningTheChosenAgent() {
        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertEquals(AgentKind.CODEX, launcher.lastKind);
        assertEquals(Optional.of(outgoing.id()), Optional.of(launcher.lastParent));
        assertNotEquals(outgoing.workingDirectory(), launcher.lastWorktree);
        assertTrue(Files.isDirectory(launcher.lastWorktree));
    }

    @Test
    void leavesTheOutgoingWorktreeExactlyAsItWas() throws Exception {
        Files.writeString(repositoryRoot.resolve("wip.txt"), "half done");
        String before = gitCapture(repositoryRoot, "status", "--porcelain");

        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "status", "--porcelain"));
        assertEquals("feat/work", gitCapture(repositoryRoot, "rev-parse", "--abbrev-ref", "HEAD").strip());
    }

    @Test
    void carriesTheOutgoingDirtyTreeIntoTheFork() throws Exception {
        // The rescue case: the uncommitted work is exactly what must survive.
        Files.writeString(repositoryRoot.resolve("wip.txt"), "half done");

        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertEquals("half done", Files.readString(launcher.lastWorktree.resolve("wip.txt")));
    }

    @Test
    void forkingToTheSameAgentIsAllowed() {
        // A wedged process is a reason to fork even when the harness is fine.
        service.forkBlocking(outgoing, AgentKind.CLAUDE);

        assertEquals(AgentKind.CLAUDE, launcher.lastKind);
    }

    @Test
    void seedsTheForkWithTheBriefWhenOneExists() {
        briefs.put(outgoing.id(), brief(outgoing.id(), "Ship the fork gesture"));

        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertTrue(launcher.lastPrompt.contains("Ship the fork gesture"), launcher.lastPrompt);
    }

    @Test
    void saysSoWhenNoBriefWasRecorded() {
        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertTrue(launcher.lastPrompt.contains("No handoff brief was recorded"), launcher.lastPrompt);
    }

    @Test
    void theSeedCarriesFactsDrydockDerivedItself() {
        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertTrue(launcher.lastPrompt.contains("add a.txt"), launcher.lastPrompt);
        assertTrue(launcher.lastPrompt.contains("derived by drydock"), launcher.lastPrompt);
    }

    @Test
    void theSeedsChangedFileListIsCappedAndSaysHowManyItLeftOut() throws Exception {
        // Thousands of untracked files must not become thousands of bullet
        // lines in the successor's very first prompt.
        for (int i = 0; i < 60; i++) {
            Files.writeString(repositoryRoot.resolve("scratch-" + i + ".txt"), "x");
        }

        service.forkBlocking(outgoing, AgentKind.CODEX);

        assertTrue(launcher.lastPrompt.contains("and 10 more"), launcher.lastPrompt);
        // A truncated list that did not say so would read as the whole tree.
        assertTrue(launcher.lastPrompt.lines().filter(l -> l.startsWith("- ")).count() < 60,
                launcher.lastPrompt);
    }

    @Test
    void suffixesTheBranchNameWhenTheNaturalOneIsTaken() throws Exception {
        git(repositoryRoot, "branch", "feat/work-codex");

        service.forkBlocking(outgoing, AgentKind.CODEX);

        String branch = gitCapture(launcher.lastWorktree, "rev-parse", "--abbrev-ref", "HEAD").strip();
        assertNotEquals("feat/work-codex", branch);
        assertTrue(branch.startsWith("feat/work-codex-"), branch);
    }

    @Test
    void aFailedTransplantLeavesNoWorktreeNoBranchAndNoSession() throws Exception {
        transplant.failNext = true;

        assertThrows(IllegalStateException.class, () -> service.forkBlocking(outgoing, AgentKind.CODEX));

        assertFalse(gitCapture(repositoryRoot, "worktree", "list").contains("feat-work-codex"));
        assertFalse(gitCapture(repositoryRoot, "branch", "--list", "feat/work-codex")
                .contains("feat/work-codex"));
        assertEquals(0, launcher.startCount, "no session may be started for a failed fork");
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
