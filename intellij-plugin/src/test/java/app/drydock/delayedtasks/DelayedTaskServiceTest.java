package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.*;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.mcp.*;
import app.drydock.projects.SharedProject;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.tasks.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The T12b committed matrix: the accepted DelayedTaskService (T12a) over real
 * files under TempDir roots, no mocking, per the six contract items.
 */
class DelayedTaskServiceTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // 1. Init happy path: the root is created with the right layout, and a create round-trip commits.
    @Test
    void initCreatesTheStoreAndARoundTripCommits(@TempDir Path root) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(service.initialize());
            assertTrue(Files.isDirectory(root) && await(service.snapshot()).documents().isEmpty(),
                    "initialization creates the store root and it scans empty");
            TaskId prerequisite = await(service.create("Refresh the token", List.of(), repository))
                    .document().id();
            TaskToolContext.Outcome created = await(service.create("Fix the login redirect",
                    List.of(prerequisite.text()), repository));
            assertFalse(created.refused(), created.refusal());
            TaskId id = created.document().id();
            assertTrue(id.text().startsWith("fix-the-login-redirect-"), id.text());
            assertTrue(Files.exists(committedFile(root, repository, id)),
                    "the layout is <root>/<repository-id>/<id>.md");
            TaskDocument stored = await(service.snapshot()).byId().get(id);
            assertNotNull(stored, "the committed write is what the next scan sees");
            assertEquals(List.of(prerequisite.text()), stored.after());
            assertTrue(stored.sessions().isEmpty() && stored.doneBy() == DoneBy.HUMAN,
                    "a created task carries no sessions and defaults to human validation");
        }
    }

    // 1a. A prerequisite that names no task refuses the create, and nothing is written.
    @Test
    void unresolvedPrerequisiteRefusesTheCreate(@TempDir Path root) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(service.initialize());
            TaskToolContext.Outcome refused = await(service.create("Follow up",
                    List.of("<this-pr-110-task-id>"), repository));
            assertTrue(refused.refused(), "an unresolved reference is refused");
            assertTrue(refused.refusal().contains("'<this-pr-110-task-id>'"), refused.refusal());
            assertTrue(await(service.snapshot()).documents().isEmpty(), "nothing was created");
        }
    }

    // 2. Init failure: an unwritable root degrades visibly; init re-attempts safely.
    @Test
    void unwritableRootDegradesAndInitIsReattemptable(@TempDir Path dir) throws Exception {
        Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            try (DelayedTaskService service = new DelayedTaskService(locked.resolve("tasks"),
                    executor, List::of, List::of)) {
                assertTrue(failureOf(service.initialize()).length() > 0, "init fails visibly, never hangs");
                assertTrue(failureOf(service.snapshot()).contains("failed to initialize"),
                        "reads refuse visibly with the reason");
                assertTrue(failureOf(service.create("Never lands", List.of(), RepositoryId.newId()))
                        .contains("failed to initialize"), "mutations fail visibly, never silently");
                assertTrue(failureOf(service.initialize()).length() > 0, "a second attempt does not crash");
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
                await(service.initialize());
                assertTrue(await(service.snapshot()).documents().isEmpty(), "the same service recovers");
            }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // 3. Early dispose is safe, and a dispose persists what committed writes wrote.
    @Test
    void earlyDisposeIsSafeAndPersistsCommittedWrites(@TempDir Path root) throws Exception {
        ParkingExecutor parking = new ParkingExecutor();
        try (DelayedTaskService neverInitialized = new DelayedTaskService(root, parking, List::of, List::of)) {
            assertDoesNotThrow(neverInitialized::close, "dispose before init or use never throws");
            parking.release(); // the parked init lands after close and is discarded
            assertTrue(failureOf(neverInitialized.snapshot()).contains("is disposed"));
        }
        TaskId written;
        try (DelayedTaskService writer = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(writer.initialize());
            TaskToolContext.Outcome created = await(writer.create("Flushed by dispose", List.of(), RepositoryId.newId()));
            assertFalse(created.refused(), created.refusal());
            written = created.document().id();
            writer.close();
            assertDoesNotThrow(writer::close, "close is idempotent");
        }
        try (DelayedTaskService reopened = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(reopened.initialize());
            TaskDocument stored = await(reopened.snapshot()).byId().get(written);
            assertNotNull(stored, "the write committed before dispose survives a reopen");
            assertEquals("Flushed by dispose", stored.title());
        }
    }

    // 4. Every MCP caller maps to the AGENT actor; pre-init and disposed tool use fail visibly.
    @Test
    void agentActorMappingAndVisibleToolFailures(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repository = RepositoryId.newId();
        ManagedSessionId caller = ManagedSessionId.newId();
        ManagedAgentSession session = session(caller, repository, worktree, Optional.empty());
        Repository alpha = repository(repository, worktree, "Project Alpha");
        try (DelayedTaskService service = new DelayedTaskService(store, executor,
                () -> List.of(session), () -> List.of(alpha)); TaskStore direct = new TaskStore(store)) {
            await(service.initialize());
            TaskDocument assigned = direct.create(repository, "Assigned follow up", List.of(caller.toString()));
            JsonValue review = service.taskTools().call(caller, "task_update",
                    args("id", assigned.id().text(), "status", "done"));
            assertEquals("review", textOf(review, "status"),
                    "an agent's done on a done_by: human task commits review");
            String onDisk = Files.readString(
                    store.resolve(repository.toString()).resolve(assigned.id().text() + ".md"));
            assertTrue(onDisk.contains("status: review") && onDisk.contains("done_by: human"),
                    "review is the stored status; done_by stays human");
        }
        try (DelayedTaskService notReady = new DelayedTaskService(dir.resolve("parked"),
                command -> { }, () -> List.of(session), () -> List.of(alpha))) { // init never runs
            assertTrue(assertThrows(McpToolException.class, () -> notReady.taskTools().call(caller,
                    "task_list", JsonObject.empty())).getMessage().contains("has not initialized yet"),
                    "pre-init tool use fails visibly");
            notReady.close();
            assertTrue(assertThrows(McpToolException.class, () -> notReady.taskTools().call(caller,
                    "task_list", JsonObject.empty())).getMessage().contains("is disposed"),
                    "disposed-state tool use fails visibly");
        }
    }

    // 5. No host-home writes: the override redirects the root, and no root writes outside its temp dir.
    @Test
    void overrideRedirectsTheRootAndNothingEscapesTempRoots(@TempDir Path injected,
            @TempDir Path override) throws Exception {
        Path realHome = Path.of(System.getProperty("user.home"), ".drydock", "tasks");
        String homeBefore = stateOf(realHome);
        String original = System.getProperty(DelayedTaskService.OVERRIDE_PROPERTY);
        System.setProperty(DelayedTaskService.OVERRIDE_PROPERTY, override.toString());
        try (DelayedTaskService redirected = new DelayedTaskService(executor, List::of, List::of);
                DelayedTaskService atInjectedRoot = new DelayedTaskService(injected, executor,
                        List::of, List::of)) {
            assertEquals(override.toAbsolutePath().normalize(), redirected.root(),
                    "the production root resolves the override, never the host home");
            await(redirected.initialize());
            RepositoryId repository = RepositoryId.newId();
            TaskToolContext.Outcome created = await(redirected.create("Redirected task", List.of(), repository));
            assertTrue(Files.exists(committedFile(override, repository, created.document().id())),
                    "the override root's write lands under it");
            await(atInjectedRoot.initialize());
            TaskToolContext.Outcome other = await(atInjectedRoot.create("Injected task", List.of(), repository));
            assertTrue(Files.exists(committedFile(injected, repository, other.document().id())),
                    "an injected root's writes stay inside it");
            assertEquals(homeBefore, stateOf(realHome), "nothing was created under the real home");
        } finally {
            if (original == null) System.clearProperty(DelayedTaskService.OVERRIDE_PROPERTY);
            else System.setProperty(DelayedTaskService.OVERRIDE_PROPERTY, original);
        }
    }

    // 6. Caller repo resolution: registered roots resolve (worktree fallback included),
    // unresolvable roots yield empty, and the create defaults and refusal paths follow.
    @Test
    void callerRepositoryResolutionResolvesAndRefuses(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        RepositoryId alphaId = RepositoryId.newId();
        RepositoryId betaId = RepositoryId.newId();
        ManagedSessionId resident = ManagedSessionId.newId();
        ManagedSessionId visitor = ManagedSessionId.newId();
        ManagedSessionId nested = ManagedSessionId.newId();
        ManagedSessionId worktreeSession = ManagedSessionId.newId();
        RepositoryId unregisteredId = RepositoryId.newId();
        List<ManagedAgentSession> sessions = List.of(
                session(resident, alphaId, worktree, Optional.empty()),
                session(visitor, unregisteredId, elsewhere, Optional.empty()),
                session(nested, alphaId, worktree.resolve("nested"), Optional.of(worktree)),
                session(worktreeSession, alphaId, elsewhere, Optional.empty()));
        List<Repository> repositories = List.of(repository(alphaId, worktree, "Project Alpha"),
                repository(betaId, dir.resolve("beta"), "Beta Project"),
                repository(RepositoryId.newId(), dir.resolve("duplicate"), "Project Alpha!"),
                repository(RepositoryId.newId(), dir.resolve("nonslug"), "---"));
        try (DelayedTaskService service = new DelayedTaskService(store, executor,
                () -> sessions, () -> repositories)) {
            await(service.initialize());
            assertEquals(Optional.of(alphaId), await(service.defaultRepository(resident)),
                    "a working root that is a registered repository resolves its id");
            assertEquals(Optional.of(alphaId), await(service.defaultRepository(nested)),
                    "an unregistered working root falls back to its registered worktree root");
            assertEquals(Optional.of(alphaId), await(service.defaultRepository(worktreeSession)),
                    "an unregistered working root with a registered owning repository resolves"
                            + " through the session's repositoryId (a detached worktree session)");
            assertTrue(await(service.defaultRepository(visitor)).isEmpty(),
                    "an unresolvable working root with an unregistered owning repository yields empty");
            assertTrue(await(service.repositoryBySlug("project-alpha")).isEmpty(),
                    "an ambiguous slug yields empty (accepted deviation)");
            String created = textOf(service.taskTools().call(resident, "task_create",
                    args("title", "Resident default")), "id");
            assertTrue(Files.exists(committedFile(store, alphaId, TaskId.of(created))),
                    "a create without a project lands in the caller's resolved repository");
            assertTrue(await(service.registeredProjectSlugs()).equals(List.of("beta-project")),
                    "the offer names only slugs that resolve uniquely: the ambiguous"
                            + " project-alpha pair is omitted, and a name with no slug"
                            + " characters (---) contributes nothing rather than null");
            String refusal = assertThrows(McpToolException.class, () -> service.taskTools().call(visitor,
                    "task_create", args("title", "Visitor task"))).getMessage();
            assertTrue(refusal.contains("no default project"),
                    "an unresolvable caller with an unregistered owning repository has no create default");
            assertTrue(refusal.contains("beta-project") && !refusal.contains("project-alpha"),
                    "the no-default refusal offers only a slug that, when passed, resolves: "
                            + "the ambiguous pair would dead-end in the second refusal");
            assertTrue(assertThrows(McpToolException.class, () -> service.taskTools().call(resident,
                    "task_create", args("title", "Ambiguous", "project", "project-alpha")))
                    .getMessage().contains("No registered project 'project-alpha'"),
                    "an ambiguous slug refuses the create, never picks a winner");
        }
    }

    // 7. The confirmed plan's persisted stack shape merges every stage's units per
    //    repository -- a later stage never overwrites an earlier stage's shape -- and
    //    the merged shape survives the plan store round trip.
    @Test
    void confirmingTwoStagesOfOneRepositoryMergesBothStagesUnits(@TempDir Path root) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        PlanStore.PlanRecord record;
        TaskId target;
        TaskId stageOne;
        TaskId stageTwo;
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(service.initialize());
            TaskDocument first = await(service.create("Stage one work", List.of(), repository)).document();
            TaskDocument second = await(service.create("Stage two work", List.of(), repository)).document();
            target = second.id();
            stageOne = first.id();
            stageTwo = second.id();
            StartPlan.Segment firstSegment = new StartPlan.Segment(repository, "Project Alpha", List.of(first));
            StartPlan.Segment secondSegment = new StartPlan.Segment(repository, "Project Alpha", List.of(second));
            StartPlan plan = new StartPlan(target, List.of(firstSegment, secondSegment),
                    List.of(new StartPlan.Stage(1, List.of(firstSegment)),
                            new StartPlan.Stage(2, List.of(secondSegment))),
                    List.of(), List.of(), List.of());
            PlanLaunchChoices choices = new PlanLaunchChoices(target, List.of(1, 2),
                    Map.of(repository, choice(root)), Map.of());
            record = await(service.persistConfirmedPlan(plan, choices,
                    new PlanLaunch.Origin("win-1", root)));
        }
        List<PlanLaunch.StackedUnit> merged = record.plan().launch().stacks().get(repository);
        assertNotNull(merged, "the repository's merged stack shape is persisted");
        assertEquals(2, merged.size(),
                "both stages' units survive the merge; a later stage never overwrites an earlier stage");
        assertEquals(List.of(stageOne), merged.get(0).tasks(), "stage 1's unit is present");
        assertEquals(List.of(stageTwo), merged.get(1).tasks(), "stage 2's unit is appended, not replacing");
        assertTrue(merged.get(0).isRoot() && merged.get(1).isRoot(),
                "independent tasks decompose to root units");
        assertEquals(2, record.plan().launch().selections().size(),
                "one launch selection per planned task");
        try (PlanStore reloaded = new PlanStore(root)) {
            PlanStore.PlanRecord persisted = await(reloaded.load(target)).orElseThrow();
            assertEquals(record.plan().launch().stacks(), persisted.plan().launch().stacks(),
                    "the merged shape is what a reopen reads back");
        }
    }

    // 7a. One segment's fork decomposes into launch units whose stack base and waits
    //     are the dd/<task> branch names, derived through the naming convention the
    //     launch and PR machinery share.
    @Test
    void confirmingAForkSplitsTheSegmentIntoUnitsWithDdBranchNames(@TempDir Path root) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(service.initialize());
            TaskDocument base = await(service.create("Shared base", List.of(), repository)).document();
            TaskDocument left = await(service.create("Left chain",
                    List.of(base.id().text()), repository)).document();
            TaskDocument right = await(service.create("Right chain",
                    List.of(base.id().text()), repository)).document();
            StartPlan.Segment segment = new StartPlan.Segment(repository, "Project Alpha",
                    List.of(base, left, right));
            StartPlan plan = new StartPlan(right.id(), List.of(segment),
                    List.of(new StartPlan.Stage(1, List.of(segment))),
                    List.of(), List.of(), List.of());
            PlanLaunchChoices choices = new PlanLaunchChoices(right.id(), List.of(1),
                    Map.of(repository, choice(root)), Map.of());
            PlanStore.PlanRecord record = await(service.persistConfirmedPlan(plan, choices,
                    new PlanLaunch.Origin("win-1", root)));
            List<PlanLaunch.StackedUnit> units = record.plan().launch().stacks().get(repository);
            assertEquals(2, units.size(), "a fork is one linear chain plus the split-off dependent");
            assertEquals(List.of(base.id(), left.id()), units.get(0).tasks(),
                    "the maximal chain keeps both members");
            assertTrue(units.get(0).isRoot(), "the chain from an unprerequisited task is a root unit");
            assertEquals(List.of(right.id()), units.get(1).tasks(), "the fork's other dependent opens its own unit");
            assertEquals("dd/" + base.id().text(), units.get(1).baseBranch(),
                    "the stack base is the dd/ branch of the shared prerequisite");
            assertEquals(List.of("dd/" + base.id().text()), units.get(1).waitBranches(),
                    "the unit waits for the same dd/ branch");
        }
    }

    // S4 claim surface: a DONE prerequisite whose dd/<id> branch is gone (never pushed
    // here, so the probe finds nothing) refuses terminally and accurately, releases the
    // in-flight guard, never registers the terminal gate (a manual claim refusal must
    // not gate auto-start), and the claim surfaces bypass the gate.
    @Test
    void aDonePrerequisiteMissingBranchRefusesTerminallyAndReleasesTheGuard(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument done = await(service.create("Finished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(done.id(), TaskState.Actor.HUMAN, TaskStatus.DONE));
            TaskDocument followUp = await(service.create("Follow up work",
                    List.of(done.id().text()), repositoryId)).document();

            TaskToolContext.Outcome refused =
                    await(service.claim(ManagedSessionId.newId(), followUp.id()));
            assertTrue(refused.refused(), refused.refusal());
            assertTrue(refused.refusal().contains("no longer exists"), refused.refusal());
            assertTrue(refused.refusal().contains("Tasks tree"), refused.refusal());
            assertFalse(refused.refusal().contains("\n"), "a refusal is a single-line value");
            assertFalse(DelayedTaskStart.IN_FLIGHT.contains(followUp.id()),
                    "every refusal exit releases the in-flight guard");
            assertFalse(service.terminallyRefused(followUp.id()),
                    "a manual claim never registers the terminal gate; a human-repaired"
                            + " condition unblocks auto-start without a restart");

            TaskToolContext.Outcome again =
                    await(service.claim(ManagedSessionId.newId(), followUp.id()));
            assertTrue(again.refused(), again.refusal());
            assertEquals(refused.refusal(), again.refusal(),
                    "the claim surfaces bypass the refusal gate and probe again immediately");
        }
    }

    // S4 claim surface: a non-DONE prerequisite's missing branch keeps the existing
    // retryable wording verbatim and never registers the candidate terminally.
    @Test
    void aNonDonePrerequisiteMissingBranchKeepsTheRetryableWording(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument review = await(service.create("Unfinished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(review.id(), TaskState.Actor.HUMAN, TaskStatus.REVIEW));
            TaskDocument followUp = await(service.create("Follow up work",
                    List.of(review.id().text()), repositoryId)).document();

            TaskToolContext.Outcome refused =
                    await(service.claim(ManagedSessionId.newId(), followUp.id()));
            assertTrue(refused.refused(), refused.refusal());
            assertTrue(refused.refusal().contains("does not exist in the repository"),
                    refused.refusal());
            assertTrue(refused.refusal().contains(
                            "claim again once the prerequisite's session has pushed it"),
                    refused.refusal());
            assertFalse(service.terminallyRefused(followUp.id()),
                    "a non-DONE missing branch is retryable, never terminal");
            assertFalse(DelayedTaskStart.IN_FLIGHT.contains(followUp.id()),
                    "every refusal exit releases the in-flight guard");
        }
    }

    /** A snapshot under construction for the pure gate tests (the ClaimLaunchTest
      * Store pattern): plain documents, no git, no IDE. */
    private static final class SnapshotStore {
        final List<TaskDocument> documents = new ArrayList<>();

        void open(RepositoryId repository, String id, TaskStatus status, List<String> sessions,
                String... after) {
            documents.add(new TaskDocument(TaskId.of(id), repository, "Task " + id, status,
                    DoneBy.HUMAN, Optional.of(Instant.parse("2026-11-11T00:00:00Z")),
                    List.of(after), sessions, List.of(), "", ""));
        }

        TaskSnapshot snapshot() {
            Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
            documents.forEach(document -> byId.put(document.id(), document));
            List<RepositoryId> repositories = documents.stream().map(TaskDocument::repository)
                    .distinct().toList();
            return new TaskSnapshot(documents, repositories, byId, Map.of(), List.of(), List.of());
        }

        TaskGraph graph() {
            return TaskGraph.of(snapshot(), repository -> "repo");
        }
    }

    // S4 auto-start gate, pure: terminal beats everything, transient conditions skip
    // silently, validate refusals are classified by healability (the S5 cross-repo
    // refusal is retryable, everything else terminal), nothing is cached.
    @Test
    void theAutoStartGateClassifiesEveryCandidateBeforeAnyProbe() {
        RepositoryId repository = RepositoryId.newId();
        RepositoryId elsewhere = RepositoryId.newId();
        Map<TaskId, TaskDocument> terminal = new ConcurrentHashMap<>();

        // 1. Terminal beats everything, even a changed document.
        SnapshotStore store = new SnapshotStore();
        store.open(repository, "a", TaskStatus.DONE, List.of());
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        TaskDocument candidate = store.snapshot().byId().get(TaskId.of("b"));
        terminal.put(TaskId.of("b"), candidate);
        DelayedTaskService.Gate gate = DelayedTaskService.autoStartGate(TaskId.of("b"),
                candidate.withTitle("Changed title"), store.graph(),
                Set.of(), Set.of(), terminal);
        assertEquals(DelayedTaskService.GateKind.TERMINAL_CACHED, gate.kind(),
                "a terminally refused candidate never re-fires, even on a document change");
        assertTrue(terminal.containsKey(TaskId.of("b")), "the terminal entry survives");

        // 2. Transient in-flight: silent skip.
        terminal.clear();
        gate = DelayedTaskService.autoStartGate(TaskId.of("b"), candidate, store.graph(),
                Set.of(), Set.of(TaskId.of("b")), terminal);
        assertEquals(DelayedTaskService.GateKind.TRANSIENT_SKIP, gate.kind());
        assertTrue(terminal.isEmpty(), "transient conditions write no entry");

        // 3. Transient live-session ownership: silent skip.
        SnapshotStore owned = new SnapshotStore();
        owned.open(repository, "a", TaskStatus.DONE, List.of());
        owned.open(repository, "b", TaskStatus.OPEN, List.of("session-1"), "a");
        TaskDocument ownedCandidate = owned.snapshot().byId().get(TaskId.of("b"));
        gate = DelayedTaskService.autoStartGate(TaskId.of("b"), ownedCandidate,
                owned.graph(), Set.of("session-1"), Set.of(), terminal);
        assertEquals(DelayedTaskService.GateKind.TRANSIENT_SKIP, gate.kind());
        assertTrue(terminal.isEmpty(), "transient conditions write no entry");

        // 4. An unhealable validate refusal (not open) is terminal, not healable.
        SnapshotStore closed = new SnapshotStore();
        closed.open(repository, "a", TaskStatus.DONE, List.of());
        closed.open(repository, "b", TaskStatus.REVIEW, List.of(), "a");
        TaskDocument closedCandidate = closed.snapshot().byId().get(TaskId.of("b"));
        gate = DelayedTaskService.autoStartGate(TaskId.of("b"), closedCandidate,
                closed.graph(), Set.of(), Set.of(), terminal);
        assertEquals(DelayedTaskService.GateKind.VALIDATE_REFUSED, gate.kind());
        assertNotNull(gate.refusal());
        assertFalse(gate.refusal().contains("\n"), "a refusal is a single-line value");
        assertFalse(gate.healable(), "a not-open candidate's refusal is structural");

        // 4b. An unhealable validate refusal (no in-repository prerequisite).
        SnapshotStore orphan = new SnapshotStore();
        orphan.open(repository, "b", TaskStatus.OPEN, List.of());
        TaskDocument orphanCandidate = orphan.snapshot().byId().get(TaskId.of("b"));
        gate = DelayedTaskService.autoStartGate(TaskId.of("b"), orphanCandidate,
                orphan.graph(), Set.of(), Set.of(), terminal);
        assertEquals(DelayedTaskService.GateKind.VALIDATE_REFUSED, gate.kind());
        assertFalse(gate.healable(), "no in-repository prerequisite never heals on its own");

        // 4c. The S5 cross-repo refusal is healable: it re-probes on every
        //     publication and heals when the cross-repo prerequisite reaches DONE.
        SnapshotStore crossRepo = new SnapshotStore();
        crossRepo.open(elsewhere, "x", TaskStatus.REVIEW, List.of());
        crossRepo.open(repository, "b", TaskStatus.OPEN, List.of(), "x");
        TaskDocument crossCandidate = crossRepo.snapshot().byId().get(TaskId.of("b"));
        gate = DelayedTaskService.autoStartGate(TaskId.of("b"), crossCandidate,
                crossRepo.graph(), Set.of(), Set.of(), terminal);
        assertEquals(DelayedTaskService.GateKind.VALIDATE_REFUSED, gate.kind());
        assertTrue(gate.healable(), "the S5 cross-repo refusal heals when the cross-repo"
                + " prerequisite reaches DONE");
        assertNotNull(gate.refusal());

        // 5. Anything else proceeds to the launch.
        SnapshotStore chain = new SnapshotStore();
        chain.open(repository, "a", TaskStatus.DONE, List.of());
        chain.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        TaskSnapshot snapshot = chain.snapshot();
        TaskDocument b = snapshot.byId().get(TaskId.of("b"));
        gate = DelayedTaskService.autoStartGate(TaskId.of("b"), b, chain.graph(),
                Set.of(), Set.of(), terminal);
        assertEquals(DelayedTaskService.GateKind.PROCEED, gate.kind());
        assertFalse(gate.healable());
    }

    // S4 post-outcome handling: nothing is ever cached -- a refusal reports the
    // distinguishable terminal wording when an entry exists, the plain wording
    // otherwise; success logs nothing.
    @Test
    void applyLaunchOutcomeRefusesWithoutCaching() {
        RepositoryId repository = RepositoryId.newId();
        Map<TaskId, TaskDocument> terminal = new ConcurrentHashMap<>();
        SnapshotStore store = new SnapshotStore();
        store.open(repository, "a", TaskStatus.DONE, List.of());
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        TaskSnapshot snapshot = store.snapshot();
        TaskDocument b = snapshot.byId().get(TaskId.of("b"));

        // Success logs nothing and mutates nothing.
        terminal.put(TaskId.of("b"), b);
        assertNull(DelayedTaskService.applyLaunchOutcome(terminal, TaskId.of("b"), null));
        assertTrue(terminal.containsKey(TaskId.of("b")), "the terminal map is never cleared");

        // Refusal with a terminal entry: the distinguishable wording.
        String logged = DelayedTaskService.applyLaunchOutcome(terminal, TaskId.of("b"),
                "the prerequisite branch is gone");
        assertTrue(logged.contains("will not auto-start"), logged);

        // Refusal without a terminal entry: the plain wording, byte-stable, and
        // nothing written anywhere (there is no cache left to write to).
        terminal.clear();
        logged = DelayedTaskService.applyLaunchOutcome(terminal, TaskId.of("b"),
                "the prerequisite branch is gone");
        assertEquals("The follow-up 'b' did not auto-start: the prerequisite branch is gone",
                logged);
        assertTrue(terminal.isEmpty(), "a plain refusal is never cached");
    }

    /** The JUL handler the log-asserting tests attach: every record the service
      * logs lands here while the fixture is attached. Thread-safe: service logs
      * can land on executor threads while the test awaits the future. */
    private static final class LogRecorder extends java.util.logging.Handler {
        private final List<java.util.logging.LogRecord> records =
                Collections.synchronizedList(new ArrayList<>());

        LogRecorder attach() {
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(
                    "app.drydock.delayedtasks.DelayedTaskService");
            logger.setLevel(java.util.logging.Level.ALL);
            logger.addHandler(this);
            return this;
        }

        void detach() {
            java.util.logging.Logger.getLogger(
                    "app.drydock.delayedtasks.DelayedTaskService").removeHandler(this);
        }

        @Override
        public void publish(java.util.logging.LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        long count(String messagePart, java.util.logging.Level level) {
            synchronized (records) {
                return records.stream().filter(record -> record.getLevel() == level
                        && record.getMessage().contains(messagePart)).count();
            }
        }
    }

    // S4 terminal contract: one pass over 257 unhealably refused candidates registers
    // ALL of them -- the terminal map has no overflow valve; entries live until restart.
    @Test
    void aSinglePassRegistersEveryTerminallyRefusedCandidateWithoutEviction(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument done = await(service.create("Finished prerequisite", List.of(),
                    repositoryId)).document();
            await(service.updateStatus(done.id(), TaskState.Actor.HUMAN, TaskStatus.DONE));
            List<TaskId> candidates = new ArrayList<>();
            for (int i = 0; i < 257; i++) {
                candidates.add(await(service.create("Follow up " + i,
                        List.of(done.id().text()), repositoryId)).document().id());
            }
            TaskSnapshot snapshot = await(service.snapshot());
            service.autoStartFollowUps(snapshot, done.id(), Set.of(done.id()));
            for (TaskId candidate : candidates) {
                assertTrue(service.terminallyRefused(candidate),
                        "candidate " + candidate.text() + " is registered: the terminal map"
                                + " never evicts within a pass");
            }
        }
    }

    // S4 classification: terminal-vs-retryable must not depend on the waits' order --
    // a candidate with a missing DONE wait classifies terminal whichever order the
    // waits come in, and the auto-start pass registers the marker terminally.
    @Test
    void terminalClassificationDoesNotDependOnTheWaitsOrder(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        LogRecorder recorder = new LogRecorder().attach();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument done = await(service.create("Finished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(done.id(), TaskState.Actor.HUMAN, TaskStatus.DONE));
            TaskDocument review = await(service.create("Unfinished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(review.id(), TaskState.Actor.HUMAN, TaskStatus.REVIEW));
            TaskDocument nonDoneFirst = await(service.create("Follow up A",
                    List.of(review.id().text(), done.id().text()), repositoryId)).document();
            TaskDocument doneFirst = await(service.create("Follow up B",
                    List.of(done.id().text(), review.id().text()), repositoryId)).document();
            TaskSnapshot snapshot = await(service.snapshot());
            service.autoStartFollowUps(snapshot, done.id(), Set.of(done.id(), review.id()));
            assertTrue(service.terminallyRefused(nonDoneFirst.id()),
                    "a missing DONE wait is terminal even when a non-DONE wait precedes it");
            assertTrue(service.terminallyRefused(doneFirst.id()),
                    "the same classification when the DONE wait comes first");
            assertEquals(2, recorder.count("no longer exists", java.util.logging.Level.INFO),
                    "both candidates report the terminal DONE-branch wording");
            assertEquals(0, recorder.count("does not exist in the repository",
                    java.util.logging.Level.INFO),
                    "neither candidate is classified retryable");
        } finally {
            recorder.detach();
        }
    }

    // S4 healing: the S5 cross-repo refusal is never cached -- every publication
    // re-probes validate (an unrelated snapshot change re-fires it), and once the
    // cross-repo prerequisite reaches DONE the refusal stops and the candidate
    // registers terminally for its fresh, unhealable reason.
    @Test
    void aHealableValidateRefusalReprobesOnEveryPublication(@TempDir Path dir) throws Exception {
        RepositoryId here = RepositoryId.newId();
        RepositoryId there = RepositoryId.newId();
        LogRecorder recorder = new LogRecorder().attach();
        try (DelayedTaskService service = new DelayedTaskService(dir.resolve("store"), executor,
                List::of, () -> List.of(repository(here, dir.resolve("worktree"), "Here")))) {
            await(service.initialize());
            TaskDocument cross = await(service.create("Cross-repo prerequisite",
                    List.of(), there)).document();
            await(service.updateStatus(cross.id(), TaskState.Actor.HUMAN, TaskStatus.REVIEW));
            TaskDocument followUp = await(service.create("Follow up work",
                    List.of(cross.id().text()), here)).document();
            TaskId planId = cross.id();
            service.autoStartFollowUps(await(service.snapshot()), planId, Set.of(cross.id()));
            assertEquals(1, recorder.count("waits only on unfinished",
                    java.util.logging.Level.INFO), "the healable S5 refusal is logged once");
            assertFalse(service.terminallyRefused(followUp.id()),
                    "a healable refusal is never registered terminally");

            // An unrelated snapshot change (no document change for the candidate or
            // its waits) must re-probe: the INFO fires again.
            await(service.create("Unrelated task", List.of(), here));
            service.autoStartFollowUps(await(service.snapshot()), planId, Set.of(cross.id()));
            assertEquals(2, recorder.count("waits only on unfinished",
                    java.util.logging.Level.INFO),
                    "the healable refusal re-probes on the next publication");

            // Once the cross-repo prerequisite reaches DONE the S5 refusal stops
            // firing; the fresh reason (no in-repository prerequisite) is unhealable.
            await(service.updateStatus(cross.id(), TaskState.Actor.HUMAN, TaskStatus.DONE));
            service.autoStartFollowUps(await(service.snapshot()), planId, Set.of(cross.id()));
            assertEquals(2, recorder.count("waits only on unfinished",
                    java.util.logging.Level.INFO), "the healed refusal never fires again");
            assertTrue(service.terminallyRefused(followUp.id()),
                    "the fresh refusal (no in-repository prerequisite) is terminal");
        } finally {
            recorder.detach();
        }
    }

    // The confirmed trigger (F-b6def7eed8e1's class): a missing-branch refusal is
    // never cached -- the next pass re-probes and re-logs it even though NO task
    // document changed. (A branch appearing changes no document either, so the
    // re-probe is what lets a pushed branch unblock auto-start.)
    @Test
    void aMissingBranchRefusalIsNeverCachedAndReprobesOnTheNextPass(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        LogRecorder recorder = new LogRecorder().attach();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument review = await(service.create("Unfinished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(review.id(), TaskState.Actor.HUMAN, TaskStatus.REVIEW));
            TaskDocument followUp = await(service.create("Follow up work",
                    List.of(review.id().text()), repositoryId)).document();
            TaskSnapshot snapshot = await(service.snapshot());
            service.autoStartFollowUps(snapshot, review.id(), Set.of(review.id()));
            assertEquals(1, recorder.count("does not exist in the repository",
                    java.util.logging.Level.INFO), "the missing-branch refusal is logged once");
            // The SAME snapshot, no document change anywhere: the probe re-fires.
            service.autoStartFollowUps(snapshot, review.id(), Set.of(review.id()));
            assertEquals(2, recorder.count("does not exist in the repository",
                    java.util.logging.Level.INFO),
                    "the second pass re-probes: no cache blocks the confirmed trigger");
            assertFalse(service.terminallyRefused(followUp.id()),
                    "a retryable missing-branch refusal is never registered terminally");
        } finally {
            recorder.detach();
        }
    }

    // S4 lazy inheritance: the plan's agents are read only when a pass reaches a
    // PROCEED candidate -- never for a pass whose candidates are all gate-skipped,
    // and at most once per plan per passing pass. The observable load signal is the
    // WARNING stackAgentsOf logs for an unreadable plan (one per load).
    @Test
    void thePlanAgentsAreLoadedLazilyOnlyWhenACandidateProceeds(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        LogRecorder recorder = new LogRecorder().attach();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument done = await(service.create("Finished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(done.id(), TaskState.Actor.HUMAN, TaskStatus.DONE));
            TaskDocument followUp = await(service.create("Follow up work",
                    List.of(done.id().text()), repositoryId)).document();
            TaskSnapshot snapshot = await(service.snapshot());

            // Pass 1 reaches PROCEED (the DONE-missing-branch terminal marker):
            // exactly one agent-inheritance load.
            service.autoStartFollowUps(snapshot, done.id(), Set.of(done.id()));
            long afterProceed = recorder.count("The agent inheritance of plan",
                    java.util.logging.Level.WARNING);
            assertEquals(1, afterProceed, "a passing pass loads the plan's agents once");

            // Pass 2 over the same snapshot: the candidate is TERMINAL_CACHED --
            // zero loads (the eager pre-fix code loaded before the gate loop).
            service.autoStartFollowUps(snapshot, done.id(), Set.of(done.id()));
            assertEquals(afterProceed, recorder.count("The agent inheritance of plan",
                    java.util.logging.Level.WARNING),
                    "a fully gate-skipped pass loads the plan's agents never");

            // A pass with two PROCEED candidates still loads once (memoized).
            TaskDocument review = await(service.create("Unfinished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(review.id(), TaskState.Actor.HUMAN, TaskStatus.REVIEW));
            await(service.create("Follow up one", List.of(review.id().text()), repositoryId));
            await(service.create("Follow up two", List.of(review.id().text()), repositoryId));
            service.autoStartFollowUps(await(service.snapshot()), review.id(), Set.of(review.id()));
            assertEquals(afterProceed + 1, recorder.count("The agent inheritance of plan",
                    java.util.logging.Level.WARNING),
                    "two PROCEED candidates in one pass share one memoized load");
        } finally {
            recorder.detach();
        }
    }

    // S4 audit trail: a refused tool-path claim logs one INFO line naming the
    // calling session, the claimed task and the reason; the tree's Claim action
    // (claimFromUi) stays unattributed.
    @Test
    void aRefusedToolPathClaimLogsAnAuditLineAndClaimFromUiDoesNot(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repositoryId = RepositoryId.newId();
        LogRecorder recorder = new LogRecorder().attach();
        try (DelayedTaskService service = new DelayedTaskService(store, executor, List::of,
                () -> List.of(repository(repositoryId, worktree, "Project Alpha")))) {
            await(service.initialize());
            TaskDocument review = await(service.create("Unfinished prerequisite",
                    List.of(), repositoryId)).document();
            await(service.updateStatus(review.id(), TaskState.Actor.HUMAN, TaskStatus.REVIEW));
            TaskDocument followUp = await(service.create("Follow up work",
                    List.of(review.id().text()), repositoryId)).document();
            ManagedSessionId caller = ManagedSessionId.newId();
            TaskToolContext.Outcome refused = await(service.claim(caller, followUp.id()));
            assertTrue(refused.refused(), refused.refusal());
            long auditLines = recorder.count("was refused:", java.util.logging.Level.INFO);
            assertEquals(1, auditLines, "a refused tool-path claim leaves exactly one trace");
            boolean namedCaller = recorder.records.stream()
                    .filter(record -> record.getLevel() == java.util.logging.Level.INFO
                            && record.getMessage().contains("was refused:"))
                    .anyMatch(record -> record.getMessage().contains(caller.toString())
                            && record.getMessage().contains(followUp.id().text())
                            && record.getMessage().contains(refused.refusal()));
            assertTrue(namedCaller, "the audit line names the caller, the task and the reason");

            await(service.claimFromUi(followUp.id()));
            assertEquals(auditLines, recorder.count("was refused:", java.util.logging.Level.INFO),
                    "the unattributed claim surface logs no audit line");
        } finally {
            recorder.detach();
        }
    }

    /** The launch choice a confirmation freezes for one segment: a plain value, as the
      * New Session dialog's Configure action returns. */
    private static NewTaskPlan.Choice choice(Path folder) {
        return new NewTaskPlan.Choice("Plan choice", Optional.of("Do the work."), AgentKind.CLAUDE,
                SessionMode.WRITES, NewTaskPlan.WorkIn.NEW_WORKTREE, "dd/plan", "origin/main", folder,
                NewTaskPlan.OpenIn.OWN_WINDOW, Optional.empty(), Optional.empty(), Optional.empty());
    }

    // 1b. The created task stores the SHARED registry's id: an install-catalog id whose
    // root the shared list carries is remapped at create, an id the shared list carries
    // stays, and an id neither registry maps stays too (the mirror resolves it by root).
    @Test
    void createStampsTheSharedIdForAnInstallCatalogRepository(@TempDir Path root) throws Exception {
        RepositoryId sharedId = RepositoryId.newId();
        RepositoryId installId = RepositoryId.newId();
        Path checkout = root.resolve("checkout");
        SharedProject shared = SharedProject.local(sharedId, checkout, "java-profiler", Instant.EPOCH);
        Repository install = repository(installId, checkout, "java-profiler");
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of,
                () -> List.of(install), () -> List.of(shared))) {
            await(service.initialize());
            TaskToolContext.Outcome created = await(service.create("Follow up", List.of(), installId));
            assertFalse(created.refused(), created.refusal());
            assertEquals(sharedId, created.document().repository(),
                    "the task stores the shared registry's id for the same checkout");
            assertTrue(Files.exists(committedFile(root, sharedId, created.document().id())),
                    "the task file lands under the shared id's directory");
        }
    }

    @Test
    void createKeepsAnIdTheSharedListAlreadyCarries(@TempDir Path root) throws Exception {
        RepositoryId sharedId = RepositoryId.newId();
        Path checkout = root.resolve("checkout");
        SharedProject shared = SharedProject.local(sharedId, checkout, "java-profiler", Instant.EPOCH);
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of,
                List::of, () -> List.of(shared))) {
            await(service.initialize());
            TaskToolContext.Outcome created = await(service.create("Follow up", List.of(), sharedId));
            assertFalse(created.refused(), created.refusal());
            assertEquals(sharedId, created.document().repository());
        }
    }

    @Test
    void createKeepsTheCallerIdWhenNothingMaps(@TempDir Path root) throws Exception {
        RepositoryId installId = RepositoryId.newId();
        Repository install = repository(installId, root.resolve("checkout"), "java-profiler");
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of,
                () -> List.of(install), List::of)) {
            await(service.initialize());
            TaskToolContext.Outcome created = await(service.create("Follow up", List.of(), installId));
            assertFalse(created.refused(), created.refusal());
            assertEquals(installId, created.document().repository(),
                    "a project only the install knows is not remapped");
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Awaits a future that must complete exceptionally; returns the cause's message. */
    private static String failureOf(CompletableFuture<?> future) {
        try {
            fail("the future completed normally: " + future.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException c && c.getCause() != null ? c.getCause() : e;
            return String.valueOf(cause.getMessage());
        }
        return null;
    }

    /** One catalog session at the given paths, idle in the live catalog. */
    private static ManagedAgentSession session(ManagedSessionId id, RepositoryId repository,
            Path workingDirectory, Optional<Path> worktreeRoot) {
        return new ManagedAgentSession(id, repository, "session " + id, AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(workingDirectory, worktreeRoot, false), SessionStatus.INACTIVE,
                Instant.now(), Instant.now(), Optional.empty(), PrLink.none(), false, Optional.empty());
    }

    private static Repository repository(RepositoryId id, Path root, String displayName) {
        return new Repository(id, root, displayName, Instant.now(), Instant.now(), RepositorySettings.DEFAULT);
    }

    /** The committed file path of one task: <root>/<repository-id>/<id>.md. */
    private static Path committedFile(Path root, RepositoryId repository, TaskId id) {
        return root.resolve(repository.toString()).resolve(id.text() + ".md");
    }

    /** The scoped home-untouched input: existence plus the sorted entry names. */
    private static String stateOf(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return "true:" + entries.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return "false:[]"; // a missing home directory cannot have been written to
        }
    }

    /** An executor that parks every command until released, so init runs late. */
    private static final class ParkingExecutor implements Executor {
        private final List<Runnable> parked = new ArrayList<>();
        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void execute(Runnable command) {
            if (released.get()) command.run();
            else parked.add(command);
        }

        /** Lets the parked commands run; the parked initialization completes. */
        void release() {
            released.set(true);
            parked.forEach(Runnable::run);
        }
    }

    private static JsonObject args(Object... pairs) {
        JsonObject out = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1] instanceof JsonValue json ? json
                    : new JsonString((String) pairs[i + 1]));
        }
        return out;
    }

    private static String textOf(JsonValue value, String key) {
        JsonValue member = value instanceof JsonObject object ? object.get(key) : null;
        return member instanceof JsonString string ? string.value()
                : member instanceof JsonValue.JsonBoolean bool ? Boolean.toString(bool.value()) : null;
    }
}
