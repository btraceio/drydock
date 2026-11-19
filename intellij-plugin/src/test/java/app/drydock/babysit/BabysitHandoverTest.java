package app.drydock.babysit;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.babysit.BabysitHandover.Ingest;
import app.drydock.babysit.BabysitHandover.OwnerStatus;
import app.drydock.babysit.BabysitHandover.Reattached;
import app.drydock.babysit.BabysitStateFile.Contents;
import app.drydock.babysit.BabysitStateFile.DetachedEngagement;
import app.drydock.babysit.BabysitStateFile.Detachment;
import app.drydock.babysit.BabysitStateFile.Owner;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionWorkspace;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.state.JsonApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The plugin's half of detached babysitting: the detach facts, the ownership answer, and the ingest. */
class BabysitHandoverTest {

    private static final Instant AT = Instant.parse("2026-11-18T22:00:00Z");
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path base;

    private final RepositoryId repositoryId = RepositoryId.newId();
    private final ManagedSessionId watched = ManagedSessionId.newId();
    private final ManagedSessionId babysitter = ManagedSessionId.newId();
    private ApplicationStateStore store;
    private BabysitStateFile stateFile;

    @BeforeEach
    void setUp() {
        store = ApplicationStateStore.forRepository(new JsonApplicationStateRepository(base.resolve("state.json")));
        store.update(state -> withEngagement(state, BabysitState.WATCHING));
        stateFile = new BabysitStateFile(BabysitStateFile.defaultFile(base));
    }

    @AfterEach
    void tearDown() {
        store.flush();
    }

    private ApplicationState withEngagement(ApplicationState state, BabysitState pairingState) {
        Repository repository = new Repository(repositoryId, base.resolve("repo"), "repo", AT, AT,
                RepositorySettings.DEFAULT);
        return state.withRepositories(List.of(repository))
                .withSessions(List.of(row(watched, "watched", Optional.of("conv-w"), base.resolve("repo")),
                        row(babysitter, "babysit PR #42", Optional.of("conv-b"), base.resolve("wt"))))
                .withPairings(List.of(new BabysitPairing(babysitter, watched, repositoryId, 42, pairingState,
                        Optional.of(HEAD))));
    }

    private ManagedAgentSession row(ManagedSessionId id, String name, Optional<String> conversation, Path dir) {
        return new ManagedAgentSession.Builder(id, repositoryId, name,
                new AgentBinding(AgentKind.CODEX, conversation, Optional.empty()),
                SessionWorkspace.inRepository(dir), AT).build();
    }

    private Escalation escalation(String text) {
        return new Escalation(UUID.randomUUID(), watched, babysitter, 42, text, AT, false);
    }

    private BabysitPairing pairing() {
        return store.state().pairings().get(0);
    }

    // ---- detach -----------------------------------------------------------------

    @Test
    void theDetachmentCarriesEveryActiveEngagementsContinuationFacts() {
        UUID generation = UUID.randomUUID();

        Detachment detachment = BabysitHandover.detachmentOf(store.state(), generation, AT,
                root -> Optional.of("o/r")).orElseThrow();

        assertEquals(generation, detachment.generation());
        assertEquals(AT, detachment.detachedAt());
        assertEquals(List.of(new DetachedEngagement(babysitter, watched, repositoryId, 42, base.resolve("repo"),
                Optional.of("o/r"), base.resolve("wt"), "codex", Optional.of("conv-b"), Optional.of(HEAD),
                Optional.empty())), detachment.engagements());
    }

    @Test
    void stoppedOrDanglingEngagementsAreNotDetached() {
        assertEquals(Optional.empty(), BabysitHandover.detachmentOf(withEngagement(ApplicationState.empty(),
                BabysitState.STOPPED), UUID.randomUUID(), AT, root -> Optional.empty()));
        ApplicationState dangling = store.state().withSessions(List.of(store.state().sessions().get(0)));
        assertEquals(Optional.empty(), BabysitHandover.detachmentOf(dangling, UUID.randomUUID(), AT,
                root -> Optional.empty()));
    }

    @Test
    void detachWritesTheDetachmentBeforeItSpawnsTheWatcherWithItsGeneration() {
        List<UUID> spawned = new ArrayList<>();

        Optional<UUID> generation = BabysitHandover.detach(store.state(), stateFile, root -> Optional.empty(), AT,
                gen -> {
                    assertTrue(stateFile.read().contents().current(gen), "written before the spawn");
                    spawned.add(gen);
                    return 4242;
                });

        assertEquals(List.of(generation.orElseThrow()), spawned);
    }

    @Test
    void aFailedSpawnAnswersNothingAndNeverThrows() {
        assertEquals(Optional.empty(), BabysitHandover.detach(store.state(), stateFile, root -> Optional.empty(), AT,
                gen -> {
                    throw new IOException("no java");
                }));
        assertEquals(Optional.empty(), BabysitHandover.detach(ApplicationState.empty(), stateFile,
                root -> Optional.empty(), AT, gen -> {
                    throw new AssertionError("nothing to detach, nothing to spawn");
                }));
    }

    // ---- ownership -------------------------------------------------------------

    @Test
    void theWatcherLockNotTheMarkerDecidesLiveness() {
        Owner owner = new Owner(1234, AT);

        assertEquals(OwnerStatus.LIVE, BabysitHandover.ownerStatus(Optional.of(owner), true));
        assertEquals(OwnerStatus.LIVE, BabysitHandover.ownerStatus(Optional.empty(), true), "not claimed yet");
        assertEquals(OwnerStatus.STALE, BabysitHandover.ownerStatus(Optional.of(owner), false));
        assertEquals(OwnerStatus.NONE, BabysitHandover.ownerStatus(Optional.empty(), false));

        assertTrue(BabysitHandover.isOwnerProcess(owner, Optional.of(AT)));
        assertFalse(BabysitHandover.isOwnerProcess(owner, Optional.of(AT.plusSeconds(1))), "a reused pid");
        assertFalse(BabysitHandover.isOwnerProcess(owner, Optional.empty()));
    }

    @Test
    void aWatcherLockHeldByAnotherProcessIsLiveAndReleasedWhenItDies() throws Exception {
        String java = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(java != null, "no java executable to start a second process");
        Path lock = BabysitLocks.watcherLockFile(base);
        Path argFile = base.resolve("holder.args");
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n");
        Process holder = new ProcessBuilder(java, "@" + argFile, BabysitLockHolderProcess.class.getName(),
                lock.toString()).redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                    StandardCharsets.UTF_8));
            assertEquals("locked", out.readLine());
            assertTrue(BabysitLocks.isHeld(lock));

            Reattached result = BabysitHandover.reattach(store, stateFile, base, AT);
            assertEquals(Optional.empty(), result.liveWatcher(), "no marker names it, so it cannot be followed");
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
        }
        assertFalse(BabysitLocks.isHeld(lock), "the OS released a dead watcher's lock");
    }

    // ---- ingest ----------------------------------------------------------------

    @Test
    void theIngestAppendsNewEscalationsOnceStopsEndedEngagementsAndSkipsInactiveOnes() {
        Escalation first = escalation("first");
        Escalation second = escalation("second");
        ApplicationState state = BabysitStateTransforms.withEscalationAppended(store.state(), first);

        ApplicationState after = BabysitHandover.applyIngest(state,
                new Ingest(List.of(first, second), Set.of(), Optional.empty()), e -> fail("nothing dropped"));
        assertEquals(List.of(first, second), after.escalations(), "de-duplicated by id");
        assertEquals(BabysitState.ESCALATED, after.pairings().get(0).state());

        ApplicationState ended = BabysitHandover.applyIngest(after,
                new Ingest(List.of(escalation("late")), Set.of(babysitter), Optional.empty()), e -> fail("kept"));
        assertEquals(BabysitState.STOPPED, ended.pairings().get(0).state());
        assertEquals(3, ended.escalations().size(), "appended before the engagement stops");

        List<Escalation> dropped = new ArrayList<>();
        Escalation afterTheEnd = escalation("after the end");
        ApplicationState nothing = BabysitHandover.applyIngest(ended,
                new Ingest(List.of(afterTheEnd), Set.of(), Optional.empty()), dropped::add);
        assertSame(ended, nothing, "an escalation of an ended engagement is dropped");
        assertEquals(List.of(afterTheEnd), dropped, "handed back to log outside the store's lock");
    }

    @Test
    void afterTheIngestOnlyWhatWasTakenIsCleared() {
        UUID generation = UUID.randomUUID();
        Escalation taken = escalation("taken");
        Escalation arrivedMeanwhile = escalation("meanwhile");
        Owner owner = new Owner(99, AT);
        Contents contents = new Contents(Map.of(77L, 2), List.of(taken, arrivedMeanwhile),
                Optional.of(owner), Optional.of(new Detachment(generation, AT, List.of(), true)));
        Ingest ingest = new Ingest(List.of(taken), Set.of(), Optional.of(generation));

        Contents after = BabysitHandover.afterIngest(contents, ingest, Optional.of(owner));

        assertEquals(List.of(arrivedMeanwhile), after.escalationQueue());
        assertEquals(Optional.empty(), after.owner());
        assertEquals(Optional.empty(), after.detachment());
        assertEquals(2, after.rerunCount(77), "rerun counts are shared, never ingested");

        Contents newer = BabysitHandover.afterIngest(contents, new Ingest(List.of(), Set.of(),
                Optional.of(UUID.randomUUID())), Optional.of(owner));
        assertTrue(newer.detachment().isPresent(), "a newer detachment is not cleared");
        Contents inForce = contents.withDetachment(Optional.of(new Detachment(generation, AT, List.of())));
        assertTrue(BabysitHandover.afterIngest(inForce, ingest, Optional.of(owner)).detachment().isPresent(),
                "a detachment still in force is a watcher's: never cleared");
        assertEquals(Optional.of(owner), BabysitHandover.afterIngest(contents, ingest,
                Optional.of(new Owner(100, AT))).owner(), "another watcher's marker is not cleared");
    }

    @Test
    void reattachIngestsTheQueueAndTheEndsClearsTheFileAndTakesLeftoverDropBoxes() throws Exception {
        UUID generation = UUID.randomUUID();
        Escalation queued = escalation("needs a decision");
        DetachedEngagement engagement = BabysitHandover.detachmentOf(store.state(), generation, AT,
                root -> Optional.empty()).orElseThrow().engagements().get(0).withEnded("PR #42 is MERGED");
        stateFile.update(c -> c.withEscalationQueued(queued).withOwner(Optional.of(new Owner(4242, AT)))
                .withDetachment(Optional.of(new Detachment(generation, AT, List.of(engagement)))));
        Path box = BabysitDropBox.directory(base, babysitter);
        Files.createDirectories(box);
        Files.writeString(box.resolve("escalation-x.md"), "left behind");
        Files.writeString(box.resolve("rerun-77"), "CI / test");

        Reattached result = BabysitHandover.reattach(store, stateFile, base, AT);

        assertTrue(result.stateChanged());
        assertEquals(Set.of(babysitter), result.ended());
        assertEquals(Optional.empty(), result.liveWatcher(), "the marker is stale: no lock is held");
        List<String> messages = store.state().escalations().stream().map(Escalation::message).toList();
        assertEquals(3, messages.size(), messages.toString());
        assertEquals("needs a decision", messages.get(0));
        assertTrue(messages.contains("left behind"), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("rerun CI run 77 (CI / test)")
                && m.contains("nothing was rerun")), messages.toString());
        assertEquals(BabysitState.STOPPED, pairing().state());
        assertEquals(Contents.empty(), stateFile.read().contents());
        assertFalse(Files.exists(box), "an emptied drop box is removed");

        // idempotent: a second re-attach changes nothing
        assertFalse(BabysitHandover.reattach(store, stateFile, base, AT).stateChanged());
    }

    @Test
    void aLiveWatchersDropBoxIsLeftToIt() throws Exception {
        Path box = BabysitDropBox.directory(base, babysitter);
        Files.createDirectories(box);
        Files.writeString(box.resolve("escalation-x.md"), "still being written");
        Owner owner = new Owner(ProcessHandle.current().pid(), AT);
        stateFile.update(c -> c.withOwner(Optional.of(owner)));
        try (BabysitLocks.Held held = BabysitLocks.tryLock(BabysitLocks.watcherLockFile(base)).orElseThrow()) {
            Reattached result = BabysitHandover.reattach(store, stateFile, base, AT);

            assertEquals(Optional.of(owner), result.liveWatcher());
        }
        assertTrue(Files.exists(box.resolve("escalation-x.md")));
        assertEquals(List.of(), store.state().escalations());
        assertEquals(Optional.empty(), stateFile.read().contents().owner(), "the marker is cleared all the same");
    }

    // ---- durability and fail-closed takeover (C1, C2, H1, H2) -----------------

    /** A repository whose saves fail until {@link #healthy} is set; {@link #onSave} runs inside each save. */
    private static final class FlakyRepository implements ApplicationStateRepository {
        final JsonApplicationStateRepository disk;
        volatile boolean healthy;
        volatile Runnable onSave = () -> { };

        FlakyRepository(Path file) {
            disk = new JsonApplicationStateRepository(file);
        }

        @Override
        public ApplicationState load() {
            return disk.load();
        }

        @Override
        public void save(ApplicationState state) {
            onSave.run();
            if (!healthy) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            disk.save(state);
        }
    }

    private UUID detachEndedEngagementWithQueueAndDropBox(String queuedText) throws IOException,
            BabysitStateFileException {
        UUID generation = UUID.randomUUID();
        DetachedEngagement engagement = BabysitHandover.detachmentOf(store.state(), generation, AT,
                root -> Optional.empty()).orElseThrow().engagements().get(0).withEnded("PR #42 is MERGED");
        Escalation queued = escalation(queuedText);
        stateFile.update(c -> c.withEscalationQueued(queued)
                .withDetachment(Optional.of(new Detachment(generation, AT, List.of(engagement)))));
        Path box = BabysitDropBox.directory(base, babysitter);
        Files.createDirectories(box);
        Files.writeString(box.resolve("escalation-x.md"), "left behind");
        return generation;
    }

    @Test
    void aFailedCatalogSaveLeavesEverySourceIntactAndALaterReattachTakesItOnce() throws Exception {
        FlakyRepository repository = new FlakyRepository(base.resolve("flaky-state.json"));
        store = ApplicationStateStore.forRepository(repository);
        store.update(state -> withEngagement(state, BabysitState.WATCHING));
        UUID generation = detachEndedEngagementWithQueueAndDropBox("needs a decision");
        Path request = BabysitDropBox.directory(base, babysitter).resolve("escalation-x.md");

        Reattached failed = BabysitHandover.reattach(store, stateFile, base, AT);

        assertFalse(failed.complete(), "the save failed: nothing is acknowledged");
        assertEquals(Optional.empty(), failed.hold(), "the revocation itself was durable: the takeover stands");
        Contents kept = stateFile.read().contents();
        assertEquals(1, kept.escalationQueue().size(), "the queue is not cleared before the catalog has it");
        assertTrue(kept.detachment().orElseThrow().revoked(), "revoked, with its ends still recorded");
        assertTrue(kept.detachment().orElseThrow().engagements().get(0).ended().isPresent());
        assertTrue(Files.exists(request), "a drop-box request is not deleted before the catalog has it");
        assertFalse(kept.current(generation), "no watcher may act on it any more");

        repository.healthy = true;
        Reattached retried = BabysitHandover.reattach(store, stateFile, base, AT);

        assertTrue(retried.complete());
        assertEquals(Contents.empty(), stateFile.read().contents());
        assertFalse(Files.exists(request));
        List<String> messages = new JsonApplicationStateRepository(base.resolve("flaky-state.json")).load()
                .escalations().stream().map(Escalation::message).toList();
        assertEquals(List.of("needs a decision", "left behind"), messages,
                "on disk, each once: the retry de-duplicates by the same (stable) ids");
        assertEquals(BabysitState.STOPPED, new JsonApplicationStateRepository(base.resolve("flaky-state.json"))
                .load().pairings().get(0).state());
    }

    @Test
    void theCatalogIsSavedBeforeTheStateFileIsClearedAndAReloadFromDiskHasIt() throws Exception {
        FlakyRepository repository = new FlakyRepository(base.resolve("ordered-state.json"));
        repository.healthy = true;
        store = ApplicationStateStore.forRepository(repository);
        store.update(state -> withEngagement(state, BabysitState.WATCHING));
        store.flush();
        detachEndedEngagementWithQueueAndDropBox("ordered");
        List<Integer> queueAtSave = new ArrayList<>();
        List<Boolean> requestAtSave = new ArrayList<>();
        Path request = BabysitDropBox.directory(base, babysitter).resolve("escalation-x.md");
        repository.onSave = () -> {
            queueAtSave.add(stateFile.read().contents().escalationQueue().size());
            requestAtSave.add(Files.exists(request));
        };

        assertTrue(BabysitHandover.reattach(store, stateFile, base, AT).complete());

        assertFalse(queueAtSave.isEmpty());
        assertTrue(queueAtSave.stream().allMatch(size -> size == 1), "every save ran while the queue still held it: "
                + queueAtSave);
        assertTrue(requestAtSave.stream().allMatch(exists -> exists), "and while the request file still existed");
        assertEquals(2, new JsonApplicationStateRepository(base.resolve("ordered-state.json")).load()
                .escalations().size(), "a reload from disk -- a crash right after -- has both");
    }

    @Test
    void aRevocationThatCannotBeWrittenHoldsThoseBabysittersAndTakesNothing() throws Exception {
        UUID generation = detachEndedEngagementWithQueueAndDropBox("held");
        // The state file reads, but no write can take its lock.
        Path lock = stateFile.file().resolveSibling(stateFile.file().getFileName() + ".lock");
        Files.deleteIfExists(lock);
        Files.createDirectory(lock);

        Reattached result = BabysitHandover.reattach(store, stateFile, base, AT);

        assertEquals(Optional.of(new BabysitHandover.Hold(false, Set.of(babysitter))), result.hold());
        assertTrue(result.hold().orElseThrow().covers(babysitter));
        assertFalse(result.hold().orElseThrow().covers(watched));
        assertFalse(result.complete());
        assertTrue(stateFile.read().contents().current(generation), "still the watcher's: not revoked");
        assertEquals(List.of(), store.state().escalations(), "nothing taken without the revocation");
    }

    @Test
    void anUnreadableStateFileHoldsEveryBabysitter() throws Exception {
        Files.createDirectories(stateFile.file().getParent());
        Files.writeString(stateFile.file(), "{not json");

        Reattached result = BabysitHandover.reattach(store, stateFile, base, AT);

        assertEquals(Optional.of(BabysitHandover.Hold.ALL), result.hold());
        assertTrue(result.hold().orElseThrow().covers(ManagedSessionId.newId()));
    }

    @Test
    void aSuccessfulReattachHoldsNobody() throws Exception {
        detachEndedEngagementWithQueueAndDropBox("fine");

        assertEquals(Optional.empty(), BabysitHandover.reattach(store, stateFile, base, AT).hold());
        assertEquals(Optional.empty(), BabysitHandover.reattach(store, stateFile, base, AT).hold(),
                "nothing to take is no hold either");
    }

    @Test
    void anEndTheWatcherTriesToRecordDuringTheIngestIsRefusedAndNothingUnseenIsDiscarded() throws Exception {
        FlakyRepository repository = new FlakyRepository(base.resolve("race-state.json"));
        repository.healthy = true;
        store = ApplicationStateStore.forRepository(repository);
        store.update(state -> withEngagement(state, BabysitState.WATCHING));
        store.flush();
        UUID generation = UUID.randomUUID();
        Escalation queued = escalation("queued before the re-attach");
        stateFile.update(c -> c.withEscalationQueued(queued).withDetachment(BabysitHandover.detachmentOf(
                store.state(), generation, AT, root -> Optional.empty())));
        Escalation lateQueued = escalation("queued by the watcher's last drain");
        List<Contents> seenByWatcher = new ArrayList<>();
        // Between the revocation and the acknowledgement (inside the durable save), the
        // watcher sees the PR closed and tries to record it, and queues one more escalation.
        repository.onSave = () -> {
            try {
                seenByWatcher.add(stateFile.update(c -> c.withEngagementEnded(generation, babysitter,
                        "PR #42 is CLOSED").withEscalationQueued(lateQueued)));
            } catch (BabysitStateFileException e) {
                throw new IllegalStateException(e);
            }
            repository.onSave = () -> { };
        };

        Reattached result = BabysitHandover.reattach(store, stateFile, base, AT);

        assertTrue(result.complete());
        assertEquals(Set.of(), result.ended(), "the revoked detachment recorded no end");
        assertTrue(seenByWatcher.get(0).detachment().orElseThrow().engagements().get(0).ended().isEmpty(),
                "the watcher's end write was refused: its generation was revoked first");
        assertEquals(List.of(lateQueued), stateFile.read().contents().escalationQueue(),
                "what was not ingested is not acknowledged");
        assertEquals(Optional.empty(), stateFile.read().contents().detachment());
        assertEquals(BabysitState.ESCALATED, pairing().state(), "not stopped: the IDE's monitor ends it on its own read");
    }

    @Test
    void aFollowUpDrainsOnlyTheExitedWatchersGenerationAndNeverANewerOne() throws Exception {
        UUID old = UUID.randomUUID();
        Owner exited = new Owner(4242, AT);
        DetachedEngagement ended = BabysitHandover.detachmentOf(store.state(), old, AT, root -> Optional.empty())
                .orElseThrow().engagements().get(0).withEnded("PR #42 is MERGED");
        Escalation last = escalation("its last run's");
        stateFile.update(c -> c.withEscalationQueued(last).withOwner(Optional.of(exited))
                .withDetachment(Optional.of(new Detachment(old, AT, List.of(ended), true))));

        // A quit raced the exit callback: a newer detachment and a newer watcher's marker.
        UUID newer = UUID.randomUUID();
        Owner next = new Owner(5151, AT.plusSeconds(60));
        Detachment fresh = BabysitHandover.detachmentOf(store.state(), newer, AT, root -> Optional.empty())
                .orElseThrow();
        Contents raced = stateFile.update(c -> c.withDetachment(Optional.of(fresh)).withOwner(Optional.of(next)));

        Reattached result = BabysitHandover.followUp(store, stateFile, base, AT, old, exited);

        Contents after = stateFile.read().contents();
        assertEquals(Optional.of(fresh), after.detachment(), "the newer generation is never revoked or cleared");
        assertTrue(after.current(newer));
        assertEquals(Optional.of(next), after.owner(), "the newer watcher's marker stays");
        assertEquals(List.of(), after.escalationQueue(), "the exited watcher's queue is taken");
        assertEquals(Set.of(), result.ended(), "the newer detachment's (absent) ends are not the old one's");
        assertEquals(List.of("its last run's"), store.state().escalations().stream().map(Escalation::message)
                .toList());
        assertEquals(raced.detachment(), after.detachment());
    }

    @Test
    void aFollowUpClearsTheRevokedGenerationAndItsOwnMarkerOnly() throws Exception {
        UUID old = UUID.randomUUID();
        Owner exited = new Owner(4242, AT);
        DetachedEngagement ended = BabysitHandover.detachmentOf(store.state(), old, AT, root -> Optional.empty())
                .orElseThrow().engagements().get(0).withEnded("PR #42 is MERGED");
        stateFile.update(c -> c.withOwner(Optional.of(exited))
                .withDetachment(Optional.of(new Detachment(old, AT, List.of(ended), true))));

        Reattached result = BabysitHandover.followUp(store, stateFile, base, AT, old, exited);

        assertEquals(Set.of(babysitter), result.ended());
        assertEquals(Contents.empty(), stateFile.read().contents());
        assertEquals(BabysitState.STOPPED, pairing().state());
    }
}
