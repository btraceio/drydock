package app.drydock.babysit;

import app.drydock.babysit.BabysitStateFile.DetachedEngagement;
import app.drydock.babysit.BabysitStateFile.Detachment;
import app.drydock.babysit.BabysitWatcher.RunOutcome;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCiRerunService;
import app.drydock.git.GitStatusService.RemoteBranchTip;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Mergeable;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detached watcher's loop with every spawning collaborator scripted: the
 * gh read, the remote tip, the headless run (which may play the agent and
 * write drop-box requests) and gh's run read and rerun.
 */
class BabysitWatcherTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER = "89abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path base;

    private final ManagedSessionId watched = ManagedSessionId.newId();
    private final ManagedSessionId babysitter = ManagedSessionId.newId();
    private final RepositoryId repositoryId = RepositoryId.newId();
    private final UUID generation = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final List<String> prompts = new ArrayList<>();
    private final List<Long> rerunPosts = new ArrayList<>();
    private final List<String> tipReads = new ArrayList<>();

    private BabysitStateFile stateFile;
    private PrSnapshot read;
    private RemoteBranchTip tip = new RemoteBranchTip(Optional.of(HEAD), Optional.empty());
    private Function<Path, RunOutcome> agent = log -> new RunOutcome.Completed(0);
    private GhCiRerunService.RunRead runRead = new GhCiRerunService.RunHead(77, HEAD, "feat/x");
    private GhCiRerunService.Outcome rerunOutcome = new GhCiRerunService.Rerun();
    private Runnable beforeTip = () -> { };
    private Runnable onRerunPost = () -> { };
    private BabysitWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        stateFile = new BabysitStateFile(BabysitStateFile.defaultFile(base));
        detach(Optional.of("conv-1"));
        watcher = new BabysitWatcher(stateFile, base, generation,
                (root, repository, pr) -> {
                    assertEquals(base.resolve("repo"), root);
                    assertEquals(Optional.of("o/r"), repository);
                    assertEquals(42, pr);
                    return read;
                },
                (root, branch) -> {
                    beforeTip.run();
                    tipReads.add(branch);
                    return tip;
                },
                (engagement, prompt, log) -> {
                    assertEquals(babysitter, engagement.babysitterId());
                    prompts.add(prompt);
                    return agent.apply(log);
                },
                new BabysitWatcher.Reruns() {
                    @Override
                    public GhCiRerunService.RunRead readRun(Path root, Optional<String> repository, long runId) {
                        return runRead;
                    }

                    @Override
                    public GhCiRerunService.Outcome rerun(Path root, Optional<String> repository, long runId) {
                        rerunPosts.add(runId);
                        onRerunPost.run();
                        return rerunOutcome;
                    }
                },
                now::get, 30_000);
    }

    private void detach(Optional<String> conversation) throws Exception {
        Files.createDirectories(base.resolve("wt"));
        DetachedEngagement engagement = new DetachedEngagement(babysitter, watched, repositoryId, 42,
                base.resolve("repo"), Optional.of("o/r"), base.resolve("wt"), "claude", conversation,
                Optional.of(HEAD), Optional.empty());
        stateFile.update(c -> c.withDetachment(Optional.of(new Detachment(generation, Instant.ofEpochMilli(now.get()),
                List.of(engagement)))));
    }

    private static Snapshot pr(PrLifecycle lifecycle, String head, Mergeable mergeable, Check... checks) {
        return new Snapshot(42, lifecycle, false, "main", "feat/x", head, mergeable, "CLEAN", List.of(checks));
    }

    private static Snapshot open(Mergeable mergeable, Check... checks) {
        return pr(PrLifecycle.OPEN, HEAD, mergeable, checks);
    }

    private static Check failing(String name) {
        return new Check(Check.Kind.CHECK_RUN, name, "COMPLETED", "FAILURE", Optional.of("CI"),
                Optional.of("https://github.com/o/r/actions/runs/77/job/1"));
    }

    private boolean tickWith(PrSnapshot next) {
        read = next;
        return watcher.tick();
    }

    private String delta(int index) throws Exception {
        String prompt = prompts.get(index);
        return Files.readString(Path.of(prompt.substring(prompt.indexOf("read ") + 5, prompt.indexOf(" and act only"))));
    }

    private Path box() {
        return BabysitDropBox.directory(base, babysitter);
    }

    /** An agent run that writes {@code name} with {@code text} into its drop box, as the delta tells it to. */
    private Function<Path, RunOutcome> agentWriting(String name, String text) {
        return log -> {
            try {
                Files.writeString(box().resolve(name), text);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new RunOutcome.Completed(0);
        };
    }

    @Test
    void theFirstReadIsASilentBaselineAndAConflictThenRunsTheBabysitterWithADetachedActDelta() throws Exception {
        assertTrue(tickWith(open(Mergeable.CONFLICTING, failing("test"))));
        assertEquals(List.of(), prompts);
        assertEquals(List.of(), tipReads);

        assertTrue(tickWith(open(Mergeable.MERGEABLE)));
        assertTrue(tickWith(open(Mergeable.CONFLICTING)));

        assertEquals(1, prompts.size());
        assertTrue(BabysitWakePolicy.isWakePointer(prompts.get(0)), prompts.get(0));
        String delta = delta(0);
        assertTrue(delta.contains("- conflict appeared"), delta);
        assertTrue(delta.contains("You MAY write"), delta);
        assertTrue(delta.contains("do NOT exist in this run"), delta);
        assertTrue(delta.contains(box().toString() + "/rerun-<runId>"), delta);
        assertTrue(delta.contains(box().toString() + "/escalation-"), delta);
        assertFalse(delta.contains("with the `ci_rerun` tool"), delta);
        assertTrue(Files.isDirectory(box()));

        // delivered: an unchanged PR runs nothing again
        assertTrue(tickWith(open(Mergeable.CONFLICTING)));
        assertEquals(1, prompts.size());
    }

    @Test
    void anUnboundConversationIsNeverResumedHeadlessly() throws Exception {
        detach(Optional.empty());
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(), prompts);
        assertEquals(List.of(), tipReads);
    }

    @Test
    void aRunLockHeldElsewhereSkipsTheRunAndKeepsTheEvent() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        try (BabysitLocks.Held held = BabysitLocks.tryLock(BabysitLocks.runLockFile(base, babysitter)).orElseThrow()) {
            tickWith(open(Mergeable.CONFLICTING));
            assertEquals(List.of(), prompts);
        }
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, prompts.size());
        assertTrue(delta(0).contains("- conflict appeared"));
    }

    @Test
    void aFailedRunKeepsTheEventAndBacksOffExponentiallyAndATimedOutOneCountsAsDelivered() throws Exception {
        agent = log -> new RunOutcome.Completed(1);
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, prompts.size());

        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, prompts.size(), "inside the backoff window nothing runs");

        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, prompts.size(), "after the first 30 s");

        // A good read in between does not reset the delivery streak: the second
        // failure doubles the wait, as failed reads do.
        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, prompts.size(), "30 s is inside the doubled 60 s window");

        now.addAndGet(30_000);
        agent = log -> new RunOutcome.TimedOut();
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(3, prompts.size());
        assertTrue(delta(2).contains("- conflict appeared"));

        now.addAndGet(60_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(3, prompts.size(), "the timed-out delta was delivered");
    }

    @Test
    void anUnsupportedHeadlessRunKeepsTheConflictPendingUntilLaterIdeDelivery() throws Exception {
        agent = log -> new RunOutcome.Unsupported("the installed CLI lacks a required capability");
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, prompts.size());

        now.addAndGet(29_999);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, prompts.size(), "unsupported headless execution backs off without acknowledging the event");

        now.addAndGet(1);
        agent = log -> new RunOutcome.Completed(0);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, prompts.size(), "the same conflict is retried once the backoff expires");
    }

    @Test
    void aMovedHeadGetsOneReadOnlyRunAndAGitFailureBacksOff() throws Exception {
        tip = new RemoteBranchTip(Optional.of(OTHER), Optional.empty());
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, prompts.size());
        assertTrue(delta(0).contains("You may NOT write"), delta(0));

        tip = new RemoteBranchTip(Optional.empty(), Optional.of("network down"));
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        int reads = tipReads.size();
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(reads, tipReads.size(), "backed off after the git failure");
        assertEquals(1, prompts.size());
    }

    @Test
    void aFailedReadBacksOffAndNeverEndsTheEngagement() {
        tickWith(open(Mergeable.MERGEABLE));
        assertTrue(tickWith(new PrSnapshot.Failed("gh timed out")));
        assertTrue(tickWith(new PrSnapshot.Unsupported()));

        assertTrue(stateFile.read().contents().detachment().orElseThrow().engagements().stream()
                .allMatch(engagement -> engagement.ended().isEmpty()));
        assertEquals(List.of(), prompts);
    }

    @Test
    void aMergedPullRequestRecordsTheEndAndTheWatcherStops() {
        tickWith(open(Mergeable.MERGEABLE));

        assertFalse(tickWith(pr(PrLifecycle.MERGED, HEAD, Mergeable.UNKNOWN)));

        DetachedEngagement engagement = stateFile.read().contents().detachment().orElseThrow().engagements().get(0);
        assertEquals(Optional.of("PR #42 is MERGED"), engagement.ended());
        assertFalse(watcher.tick(), "nothing left to watch");
    }

    @Test
    void aClearedGenerationStopsTheWatcherEvenRightBeforeARun() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        // The plugin re-attaches between the write check and the run.
        beforeTip = () -> {
            try {
                stateFile.update(c -> c.withDetachment(Optional.empty()));
            } catch (BabysitStateFileException e) {
                throw new IllegalStateException(e);
            }
        };
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(), prompts);
        assertTrue(watcher.stopRequested());
        assertFalse(watcher.tick());
    }

    @Test
    void aStateFileUnreadableUnderTheRunLockStopsTheRunNotPermitsIt() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        String good = Files.readString(stateFile.file());
        // A re-attach's revocation this watcher cannot read must never let a run start.
        beforeTip = () -> {
            try {
                Files.writeString(stateFile.file(), "{truncated");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(), prompts, "an unreadable file is a stop under the lock");
        assertFalse(watcher.mayRun());
        assertFalse(watcher.stopRequested(), "the idle wait keeps waiting rather than exit");

        beforeTip = () -> { };
        Files.writeString(stateFile.file(), good);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, prompts.size(), "readable and current again: the event was kept");
    }

    @Test
    void aRevokedGenerationStopsTheWatcherRightBeforeARun() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        beforeTip = () -> {
            try {
                stateFile.update(BabysitStateFile.Contents::withDetachmentRevoked);
            } catch (BabysitStateFileException e) {
                throw new IllegalStateException(e);
            }
        };
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(), prompts);
        assertTrue(watcher.stopRequested());
        assertFalse(watcher.tick());
    }

    @Test
    void aRequestStaysInTheDropBoxUntilItsEscalationIsQueuedThenOnceByItsStableId() throws Exception {
        Path lock = stateFile.file().resolveSibling(stateFile.file().getFileName() + ".lock");
        agent = log -> {
            try {
                Files.writeString(box().resolve("escalation-x.md"), "needs you");
                // No write can take the state file's lock now.
                Files.deleteIfExists(lock);
                Files.createDirectory(lock);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new RunOutcome.Completed(0);
        };
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(), stateFile.read().contents().escalationQueue());
        assertTrue(Files.exists(box().resolve("escalation-x.md")), "kept: its effect is not durable yet");

        Files.delete(lock);
        watcher.drainAll();
        watcher.drainAll();

        assertEquals(List.of("needs you"), stateFile.read().contents().escalationQueue().stream()
                .map(Escalation::message).toList());
        assertFalse(Files.exists(box().resolve("escalation-x.md")));
    }

    @Test
    void anEscalationFileIsQueuedForThePluginUnderTheEngagementsIdentity() throws Exception {
        agent = agentWriting("escalation-design.md", "The conflict needs a decision.\u0007\n\nI rebased nothing.");
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));

        List<Escalation> queue = stateFile.read().contents().escalationQueue();
        assertEquals(1, queue.size());
        assertEquals(watched, queue.get(0).watchedId());
        assertEquals(babysitter, queue.get(0).babysitterId());
        assertEquals(42, queue.get(0).prNumber());
        assertEquals("The conflict needs a decision. \n\nI rebased nothing.", queue.get(0).message());
        assertFalse(Files.exists(box().resolve("escalation-design.md")));
    }

    @Test
    void rerunsAreMediatedCappedAtThreeAndTheFourthBecomesAnEscalation() throws Exception {
        agent = agentWriting("rerun-77", "CI / test\n");
        tickWith(open(Mergeable.MERGEABLE));
        // Four runs (conflict appears, clears, appears, clears), each asking for a rerun of run 77.
        for (int round = 0; round < 4; round++) {
            tickWith(open(round % 2 == 0 ? Mergeable.CONFLICTING : Mergeable.MERGEABLE));
        }

        assertEquals(4, prompts.size());
        assertEquals(List.of(77L, 77L, 77L), rerunPosts);
        assertEquals(3, stateFile.read().contents().rerunCount(77));
        assertTrue(delta(1).contains("Rerun of run 77 (CI / test): its failed jobs were rerun (1 of 3)."), delta(1));
        assertTrue(delta(3).contains("(3 of 3)"), delta(3));
        List<Escalation> queue = stateFile.read().contents().escalationQueue();
        assertEquals(1, queue.size());
        assertTrue(queue.get(0).message().contains("still fails after 3 reruns"), queue.get(0).message());
    }

    @Test
    void aRerunOfARunOnAnotherCommitIsRefusedAndNotCounted() throws Exception {
        runRead = new GhCiRerunService.RunHead(77, OTHER, "feat/x");
        agent = agentWriting("rerun-77", "");
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        agent = log -> new RunOutcome.Completed(0);
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(), rerunPosts);
        assertEquals(0, stateFile.read().contents().rerunCount(77));
        assertTrue(delta(1).contains("refused: the run ran on commit " + OTHER), delta(1));
    }

    @Test
    void aSameCommitRunOfAnotherBranchIsRefusedAndNotCounted() throws Exception {
        runRead = new GhCiRerunService.RunHead(77, HEAD, "release/1.x");
        agent = agentWriting("rerun-77", "");
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        agent = log -> new RunOutcome.Completed(0);
        tickWith(open(Mergeable.MERGEABLE));

        assertEquals(List.of(), rerunPosts);
        assertEquals(0, stateFile.read().contents().rerunCount(77));
        assertTrue(delta(1).contains("refused: the run is for branch 'release/1.x', but PR #42's head branch is"
                + " 'feat/x'"), delta(1));
    }

    @Test
    void aRefundThatCannotBeRecordedIsReportedAsStillCounting() throws Exception {
        Path lock = stateFile.file().resolveSibling(stateFile.file().getFileName() + ".lock");
        agent = agentWriting("rerun-77", "");
        rerunOutcome = new GhCiRerunService.Rejected("HTTP 403");
        onRerunPost = () -> {
            try {
                Files.deleteIfExists(lock);
                Files.createDirectory(lock);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        Files.delete(lock);
        onRerunPost = () -> { };
        agent = log -> new RunOutcome.Completed(0);
        tickWith(open(Mergeable.MERGEABLE));

        assertEquals(1, stateFile.read().contents().rerunCount(77), "the charge remains");
        assertTrue(delta(1).contains("HTTP 403. The refund could not be recorded, so it still counts (1 of 3)."),
                delta(1));
        assertFalse(delta(1).contains("Not counted"), delta(1));
    }

    @Test
    void aRejectedRerunIsRefundedAndAnUnknownOneStillCounts() throws Exception {
        agent = agentWriting("rerun-77", "");
        rerunOutcome = new GhCiRerunService.Rejected("HTTP 403");
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(0, stateFile.read().contents().rerunCount(77));

        rerunOutcome = new GhCiRerunService.Unknown("timed out");
        tickWith(open(Mergeable.MERGEABLE));
        assertEquals(1, stateFile.read().contents().rerunCount(77));
        assertEquals(List.of(77L, 77L), rerunPosts);
    }

    @Test
    void drainAllTakesWhatALastRunLeftEvenAfterAReattach() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        Files.createDirectories(box());
        Files.writeString(box().resolve("escalation-late.md"), "late");
        stateFile.update(c -> c.withDetachment(Optional.empty()));

        watcher.drainAll();

        assertEquals(List.of("late"), stateFile.read().contents().escalationQueue().stream()
                .map(Escalation::message).toList());
    }
}
