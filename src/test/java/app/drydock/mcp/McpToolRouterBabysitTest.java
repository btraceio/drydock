package app.drydock.mcp;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitStartRefusal;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.BabysitStateFile;
import app.drydock.babysit.BabysitStateTransforms;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCiRerunService;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static app.drydock.mcp.JsonPeek.bool;
import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.num;
import static app.drydock.mcp.JsonPeek.requiredNames;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The babysitting tools through the router: listing and routing follow the
 * delegate rules, and the delegate's identity gate, persisted rerun cap and
 * escalation budget hold -- over a real {@link BabysitStateFile} in a temp
 * directory and a hand-written context whose pairing lookup and escalation
 * write are the core transforms the platform runs.
 */
class McpToolRouterBabysitTest {

    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    static final String OTHER = "89abcdef0123456789abcdef0123456789abcdef";

    @TempDir Path dir;
    private final ManagedSessionId babysitter = ManagedSessionId.newId();
    private final ManagedSessionId watched = ManagedSessionId.newId();
    private final RepositoryId repository = RepositoryId.newId();
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private FakeBabysitContext babysit;
    private BabysitStateFile stateFile;
    private McpToolRouter router;

    /** Futures already complete; state is an in-memory ApplicationState under the platform's transforms. */
    static final class FakeBabysitContext implements BabysitToolContext {
        final AtomicReference<ApplicationState> state = new AtomicReference<>(ApplicationState.empty());
        PullRequestLookup lookup = new PullRequestLookup.Open(42, "feat/login");
        StartOutcome start;
        final List<String> starts = new ArrayList<>();
        final Deque<CompletableFuture<GhCiRerunService.Outcome>> reruns = new ArrayDeque<>();
        final List<Long> rerunCalls = new ArrayList<>();
        final List<BabysitPairing> rerunPairings = new ArrayList<>();
        RuntimeException escalationFailure;
        RuntimeException escalationDispatchFailure;
        boolean refuseEscalation;
        int escalationDispatchCalls;
        /** Thrown synchronously by the dispatch, as a shut-down executor's supplyAsync would. */
        RuntimeException lookupDispatchFailure;
        RuntimeException startDispatchFailure;
        GhCiRerunService.RunRead run = new GhCiRerunService.RunHead(5, HEAD, "feat/login");
        PullRequestHead head = new PullRequestHead.Known(HEAD, "feat/login");
        final List<Long> runReads = new ArrayList<>();
        int headReads;
        /** Runs inside readRun (a stop racing the lookup) and inside the rerun POST. */
        Runnable onReadRun = () -> { };
        Runnable onRerun = () -> { };

        @Override
        public CompletableFuture<PullRequestLookup> openPullRequestOf(ManagedSessionId caller) {
            if (lookupDispatchFailure != null) {
                throw lookupDispatchFailure;
            }
            return CompletableFuture.completedFuture(lookup);
        }

        @Override
        public CompletableFuture<StartOutcome> startBabysitter(ManagedSessionId watchedId, int prNumber,
                                                               String prBranch) {
            if (startDispatchFailure != null) {
                throw startDispatchFailure;
            }
            starts.add(watchedId + "#" + prNumber + "@" + prBranch);
            return CompletableFuture.completedFuture(start);
        }

        @Override
        public CompletableFuture<GhCiRerunService.RunRead> readRun(BabysitPairing pairing, long runId) {
            runReads.add(runId);
            onReadRun.run();
            return CompletableFuture.completedFuture(run);
        }

        @Override
        public CompletableFuture<PullRequestHead> pullRequestHeadOf(BabysitPairing pairing) {
            headReads++;
            return CompletableFuture.completedFuture(head);
        }

        @Override
        public CompletableFuture<Optional<BabysitPairing>> activePairingOf(ManagedSessionId id) {
            return CompletableFuture.completedFuture(BabysitStateTransforms.activePairingOf(state.get(), id));
        }

        @Override
        public CompletableFuture<GhCiRerunService.Outcome> rerunFailedJobs(BabysitPairing pairing, long runId) {
            rerunCalls.add(runId);
            rerunPairings.add(pairing);
            onRerun.run();
            CompletableFuture<GhCiRerunService.Outcome> next = reruns.poll();
            return next != null ? next : CompletableFuture.completedFuture(new GhCiRerunService.Rerun());
        }

        @Override
        public CompletableFuture<EscalateOutcome> escalate(ManagedSessionId id, String message) {
            escalationDispatchCalls++;
            if (escalationDispatchFailure != null) {
                throw escalationDispatchFailure;
            }
            if (escalationFailure != null) {
                return CompletableFuture.failedFuture(escalationFailure);
            }
            if (refuseEscalation) {
                return CompletableFuture.completedFuture(new EscalateOutcome.Refused("state store refused"));
            }
            AtomicReference<Escalation> raised = new AtomicReference<>();
            state.updateAndGet(current -> {
                Optional<BabysitPairing> pairing = BabysitStateTransforms.activePairingOf(current, id);
                if (pairing.isEmpty()) {
                    return current;
                }
                Escalation escalation = new Escalation(UUID.randomUUID(), pairing.get().watchedId(), id,
                        pairing.get().prNumber(), message, Instant.parse("2026-11-18T10:15:30Z"), false);
                raised.set(escalation);
                return BabysitStateTransforms.withEscalationAppended(current, escalation);
            });
            return CompletableFuture.completedFuture(raised.get() == null
                    ? new EscalateOutcome.Refused("engagement stopped")
                    : new EscalateOutcome.Escalated(raised.get()));
        }
    }

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        registry = new McpSessionRegistry();
        registry.mint(babysitter, Spawn.FORBIDDEN);
        registry.mint(watched, Spawn.ALLOWED);
        babysit = new FakeBabysitContext();
        stateFile = new BabysitStateFile(BabysitStateFile.defaultFile(dir));
        router = new McpToolRouter(context, registry, McpToolRouter.BABYSIT_TOOLS, null,
                new BabysitTools(babysit, registry, stateFile));
    }

    private void pair(BabysitState state) {
        babysit.state.set(ApplicationState.empty().withPairings(List.of(
                new BabysitPairing(babysitter, watched, repository, 42, state))));
    }

    private static JsonObject args(Object... keyValues) {
        JsonObject args = JsonObject.empty();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            args.put((String) keyValues[i], value instanceof Number n ? JsonNumber.of(n.longValue())
                    : new JsonString((String) value));
        }
        return args;
    }

    private String refusal(ManagedSessionId caller, String tool, JsonObject args) {
        return assertThrows(McpToolException.class, () -> router.call(caller, tool, args)).getMessage();
    }

    private static Set<String> names(List<JsonValue> descriptors) {
        return descriptors.stream().map(d -> str(d, "name")).collect(Collectors.toSet());
    }

    // ---- listing and routing ----------------------------------------------------

    @Test
    void theDelegateAddsExactlyTheThreeNamesWithTheirSchemas() {
        List<JsonValue> descriptors = router.toolDescriptors();

        assertEquals(Set.of("babysit_start", "ci_rerun", "babysit_escalate"), names(descriptors));
        JsonValue rerun = descriptors.stream().filter(d -> str(d, "name").equals("ci_rerun")).findFirst()
                .orElseThrow();
        assertEquals("integer", str(field(field(field(rerun, "inputSchema"), "properties"), "runId"), "type"));
        assertEquals(List.of("runId"), requiredNames(rerun));
    }

    @Test
    void aFullSetPlusTheDelegateListsTheSixteenAndTheThree() {
        Set<String> allowed = new HashSet<>(names(new McpToolRouter(context, registry).toolDescriptors()));
        allowed.addAll(McpToolRouter.BABYSIT_TOOLS);
        McpToolRouter full = new McpToolRouter(context, registry, allowed, null,
                new BabysitTools(babysit, registry, stateFile));

        assertEquals(19, full.toolDescriptors().size());
    }

    @Test
    void withoutTheDelegateBabysitNamesAreUnknownAndCannotBeAllowed() {
        McpToolRouter plain = new McpToolRouter(context, registry);
        for (String name : McpToolRouter.BABYSIT_TOOLS) {
            assertFalse(names(plain.toolDescriptors()).contains(name));
            assertEquals("Unknown tool: " + name, assertThrows(McpToolException.class,
                    () -> plain.call(babysitter, name, JsonObject.empty())).getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolRouter(context, registry, Set.of("repos_list", "ci_rerun")));
    }

    @Test
    void anEndedSessionIsRefusedBeforeTheDelegateRuns() {
        pair(BabysitState.WATCHING);
        context.sessionRunning = false;

        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).startsWith("Session has ended"));
        assertEquals(List.of(), babysit.rerunCalls);
    }

    // ---- babysit_start ----------------------------------------------------------

    @Test
    void startResolvesTheCallersOwnPrAndReportsLaunching() throws Exception {
        BabysitPairing pairing = new BabysitPairing(babysitter, watched, repository, 42, BabysitState.WATCHING);
        babysit.start = new BabysitToolContext.StartOutcome.Launching(pairing);

        JsonValue answer = router.call(watched, "babysit_start", JsonObject.empty());

        assertEquals(List.of(watched + "#42@feat/login"), babysit.starts);
        assertEquals(babysitter.toString(), str(answer, "babysitterSessionId"));
        assertEquals(watched.toString(), str(answer, "watchedSessionId"));
        assertEquals("feat/login", str(answer, "prBranch"));
        assertTrue(bool(answer, "launching"));
    }

    @Test
    void startWithNoOpenPrRefusesWithTheLookupsReason() {
        babysit.lookup = new BabysitToolContext.PullRequestLookup.NotFound("no PR for this branch");

        assertEquals("no PR for this branch", refusal(watched, "babysit_start", JsonObject.empty()));
        assertEquals(List.of(), babysit.starts);
    }

    @Test
    void anExpectedPrNumberOnlyCrossChecksTheJoin() throws Exception {
        babysit.start = new BabysitToolContext.StartOutcome.Launching(
                new BabysitPairing(babysitter, watched, repository, 42, BabysitState.WATCHING));

        assertTrue(refusal(watched, "babysit_start", args("prNumber", 7)).contains("resolves to PR #42, not #7"));
        assertEquals(List.of(), babysit.starts);
        router.call(watched, "babysit_start", args("prNumber", "42"));
        assertEquals(List.of(watched + "#42@feat/login"), babysit.starts);
        assertTrue(refusal(watched, "babysit_start", args("prNumber", "-1")).contains("positive integer"));
    }

    @Test
    void aRefusedStartReportsTheRefusalString() {
        babysit.start = new BabysitToolContext.StartOutcome.Refused(BabysitStartRefusal.ALREADY_BABYSAT.message());

        assertEquals(BabysitStartRefusal.ALREADY_BABYSAT.message(),
                refusal(watched, "babysit_start", JsonObject.empty()));
    }

    // ---- ci_rerun ---------------------------------------------------------------

    @Test
    void aCallerThatIsNotTheBabysitterIsRefusedAndNothingIsCounted() {
        pair(BabysitState.WATCHING);

        assertTrue(refusal(watched, "ci_rerun", args("runId", 5)).contains("only for an active Drydock babysitter"));
        assertEquals(List.of(), babysit.rerunCalls);
        assertFalse(Files.exists(stateFile.file()));
    }

    @Test
    void aStoppedPairingIsRefused() {
        pair(BabysitState.STOPPED);

        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("only for an active"));
        assertEquals(List.of(), babysit.rerunCalls);
    }

    @Test
    void anEscalatedPairingMayStillRerun() throws Exception {
        pair(BabysitState.ESCALATED);

        assertEquals("rerun", str(router.call(babysitter, "ci_rerun", args("runId", 5)), "outcome"));
    }

    @Test
    void underTheCapGhIsCalledForThePairingAndTheCounterIncrements() throws Exception {
        pair(BabysitState.WATCHING);

        JsonValue answer = router.call(babysitter, "ci_rerun", args("runId", "123456789012", "checkName", "build"));

        assertEquals(List.of(123456789012L), babysit.rerunCalls);
        assertEquals(watched, babysit.rerunPairings.get(0).watchedId(), "the watched session is the pairing's");
        assertEquals(1, stateFile.read().contents().rerunCount(123456789012L));
        assertEquals(1, num(answer, "rerunCount"));
        assertEquals(2, num(answer, "rerunsLeft"));
        assertEquals("build", str(answer, "checkName"));
    }

    @Test
    void atTheCapTheRerunIsRefusedWithAnEscalateHintAndGhIsNotCalled() throws Exception {
        pair(BabysitState.WATCHING);
        for (int i = 0; i < BabysitStateFile.MAX_RERUNS_PER_RUN; i++) {
            router.call(babysitter, "ci_rerun", args("runId", 5));
        }

        String refused = refusal(babysitter, "ci_rerun", args("runId", 5));

        assertTrue(refused.contains("babysit_escalate"), refused);
        assertEquals(BabysitStateFile.MAX_RERUNS_PER_RUN, babysit.rerunCalls.size());
        assertEquals(BabysitStateFile.MAX_RERUNS_PER_RUN, stateFile.read().contents().rerunCount(5));
    }

    @Test
    void theCapSurvivesANewToolsInstanceOverTheSameFile() throws Exception {
        pair(BabysitState.WATCHING);
        for (int i = 0; i < BabysitStateFile.MAX_RERUNS_PER_RUN; i++) {
            router.call(babysitter, "ci_rerun", args("runId", 5));
        }
        McpToolRouter restarted = new McpToolRouter(context, new McpSessionRegistry(), McpToolRouter.BABYSIT_TOOLS,
                null, new BabysitTools(babysit, registry, new BabysitStateFile(stateFile.file())));

        assertThrows(McpToolException.class, () -> restarted.call(babysitter, "ci_rerun", args("runId", 5)));
    }

    @Test
    void anUnknownOutcomeKeepsTheCount() {
        pair(BabysitState.WATCHING);
        babysit.reruns.add(CompletableFuture.completedFuture(new GhCiRerunService.Unknown("timed out; unknown.")));
        babysit.reruns.add(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("still counts"));
        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("still counts"),
                "a failed context future is an unknown outcome too");
        assertEquals(2, stateFile.read().contents().rerunCount(5));
    }

    @Test
    void aRejectionOrUnavailabilityIsRefunded() {
        pair(BabysitState.WATCHING);
        babysit.reruns.add(CompletableFuture.completedFuture(new GhCiRerunService.Rejected("HTTP 403")));
        babysit.reruns.add(CompletableFuture.completedFuture(new GhCiRerunService.Unavailable("HTTP 401")));

        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("HTTP 403"));
        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("HTTP 401"));
        assertEquals(0, stateFile.read().contents().rerunCount(5));
    }

    @Test
    void aRunOfThePrsLiveHeadAndBranchIsRerunEvenWithAKnownHead() throws Exception {
        babysit.state.set(ApplicationState.empty().withPairings(List.of(
                new BabysitPairing(babysitter, watched, repository, 42, BabysitState.WATCHING).withKnownHead(HEAD))));

        assertEquals("rerun", str(router.call(babysitter, "ci_rerun", args("runId", 5)), "outcome"));
        assertEquals(List.of(5L), babysit.runReads);
        assertEquals(1, babysit.headReads, "the PR is read live: its head branch is part of the gate");
    }

    @Test
    void aSameCommitRunOfAnotherBranchIsRefusedBeforeCharging() {
        pair(BabysitState.WATCHING);
        babysit.run = new GhCiRerunService.RunHead(5, HEAD, "release/1.x");

        String refused = refusal(babysitter, "ci_rerun", args("runId", 5));

        assertTrue(refused.contains("release/1.x") && refused.contains("feat/login"), refused);
        assertTrue(refused.contains("Nothing was rerun or counted"), refused);
        assertEquals(List.of(), babysit.rerunCalls);
        assertFalse(Files.exists(stateFile.file()), "no charge was taken");
    }

    @Test
    void aStopDuringTheLookupPreventsTheRerun() {
        pair(BabysitState.WATCHING);
        babysit.onReadRun = () -> pair(BabysitState.STOPPED);

        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("only for an active"));
        assertEquals(List.of(), babysit.rerunCalls);
        assertFalse(Files.exists(stateFile.file()), "no charge was taken");
    }

    @Test
    void aRefundThatCannotBeRecordedSaysTheChargeRemains() {
        pair(BabysitState.WATCHING);
        babysit.reruns.add(CompletableFuture.completedFuture(new GhCiRerunService.Rejected("HTTP 403")));
        babysit.onRerun = () -> {
            try {
                Files.writeString(stateFile.file(), "{not json");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        String refused = refusal(babysitter, "ci_rerun", args("runId", 5));

        assertTrue(refused.contains("HTTP 403"), refused);
        assertTrue(refused.contains("still counts"), refused);
        assertFalse(refused.contains("not counted"), refused);
    }

    @Test
    void aRunOfAnotherCommitIsRefusedNamingBothShasAndNothingIsCountedOrRerun() {
        babysit.state.set(ApplicationState.empty().withPairings(List.of(
                new BabysitPairing(babysitter, watched, repository, 42, BabysitState.WATCHING).withKnownHead(HEAD))));
        babysit.run = new GhCiRerunService.RunHead(5, OTHER, "main");

        String refused = refusal(babysitter, "ci_rerun", args("runId", 5));

        assertTrue(refused.contains(OTHER) && refused.contains(HEAD), refused);
        assertTrue(refused.contains("Nothing was rerun or counted"), refused);
        assertEquals(List.of(), babysit.rerunCalls);
        assertFalse(Files.exists(stateFile.file()), "no charge was taken");
    }

    @Test
    void withoutAKnownHeadThePrsCurrentHeadIsReadAndMustMatch() throws Exception {
        pair(BabysitState.WATCHING);
        assertEquals("rerun", str(router.call(babysitter, "ci_rerun", args("runId", 5)), "outcome"));
        assertEquals(1, babysit.headReads);

        babysit.head = new BabysitToolContext.PullRequestHead.Known(OTHER, "feat/login");
        String refused = refusal(babysitter, "ci_rerun", args("runId", 5));
        assertTrue(refused.contains(OTHER) && refused.contains(HEAD), refused);
        assertEquals(1, babysit.rerunCalls.size());
        assertEquals(1, stateFile.read().contents().rerunCount(5));
    }

    @Test
    void aRunOrHeadThatCannotBeReadIsRefusedUncounted() {
        pair(BabysitState.WATCHING);
        babysit.run = new GhCiRerunService.RunUnreadable("gh: Not Found (HTTP 404)");
        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("HTTP 404"));

        babysit.run = new GhCiRerunService.RunHead(5, HEAD, "feat/login");
        babysit.head = new BabysitToolContext.PullRequestHead.Unresolved("gh pr view timed out");
        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("gh pr view timed out"));

        assertEquals(List.of(), babysit.rerunCalls);
        assertFalse(Files.exists(stateFile.file()));
    }

    @Test
    void aStartWhoseDispatchThrowsIsARefusalNotAnEscapedException() {
        babysit.lookupDispatchFailure = new RejectedExecutionException("executor shut down");
        assertTrue(refusal(watched, "babysit_start", JsonObject.empty()).contains("executor shut down"));

        babysit.lookupDispatchFailure = null;
        babysit.startDispatchFailure = new IllegalStateException("plugin disposed");
        assertTrue(refusal(watched, "babysit_start", JsonObject.empty()).contains("plugin disposed"));
        assertEquals(List.of(), babysit.starts);
    }

    @Test
    void aBadRunIdIsRefusedBeforeTheGate() {
        for (JsonObject bad : List.of(JsonObject.empty(), args("runId", 0), args("runId", "12abc"),
                args("runId", "99999999999999999999"), JsonObject.empty().put("runId", new JsonNumber("1.5")))) {
            assertThrows(McpToolException.class, () -> router.call(babysitter, "ci_rerun", bad));
        }
        assertEquals(List.of(), babysit.rerunCalls);
    }

    @Test
    void aBrokenStateFileRefusesWithoutRunningGh() throws Exception {
        pair(BabysitState.WATCHING);
        Files.createDirectories(stateFile.file().getParent());
        Files.writeString(stateFile.file(), "{\"schemaVersion\":9}");

        assertTrue(refusal(babysitter, "ci_rerun", args("runId", 5)).contains("nothing was rerun"));
        assertEquals(List.of(), babysit.rerunCalls);
    }

    // ---- babysit_escalate -------------------------------------------------------

    @Test
    void anEscalationIsAppendedForThePairingsWatchedSessionAndMarksItEscalated() throws Exception {
        pair(BabysitState.WATCHING);

        JsonValue answer = router.call(babysitter, "babysit_escalate",
                args("message", "The rebase needs a design call", "detail", "conflict in Auth.java"));

        ApplicationState after = babysit.state.get();
        assertEquals(1, after.escalations().size());
        Escalation escalation = after.escalations().get(0);
        assertEquals(watched, escalation.watchedId());
        assertEquals(42, escalation.prNumber());
        assertEquals("The rebase needs a design call\n\nconflict in Auth.java", escalation.message());
        assertEquals(BabysitState.ESCALATED, after.pairings().get(0).state());
        assertEquals(escalation.id().toString(), str(answer, "escalationId"));
        assertEquals(watched.toString(), str(answer, "watchedSessionId"));
    }

    @Test
    void anEscalationFromANonBabysitterIsRefusedAndNotCharged() throws Exception {
        pair(BabysitState.STOPPED);

        assertTrue(refusal(babysitter, "babysit_escalate", args("message", "x")).contains("only for an active"));
        assertEquals(List.of(), babysit.state.get().escalations());
        pair(BabysitState.WATCHING);
        for (int i = 0; i < McpSessionRegistry.MAX_ESCALATIONS_PER_SESSION; i++) {
            router.call(babysitter, "babysit_escalate", args("message", "m" + i));
        }
        assertEquals(McpSessionRegistry.MAX_ESCALATIONS_PER_SESSION, babysit.state.get().escalations().size(),
                "the refused call above spent nothing");
    }

    @Test
    void escalationBudgetRefundsOnlyKnownNotWrittenOutcomes() throws Exception {
        pair(BabysitState.WATCHING);

        babysit.escalationDispatchFailure = new RejectedExecutionException("executor shut down");
        for (int i = 0; i < McpSessionRegistry.MAX_ESCALATIONS_PER_SESSION + 5; i++) {
            int callsBefore = babysit.escalationDispatchCalls;
            assertTrue(refusal(babysitter, "babysit_escalate", args("message", "m"))
                    .contains("executor shut down"));
            assertEquals(callsBefore + 1, babysit.escalationDispatchCalls,
                    "a synchronous dispatch failure must leave the budget available");
        }

        babysit.escalationDispatchFailure = null;
        babysit.refuseEscalation = true;
        for (int i = 0; i < McpSessionRegistry.MAX_ESCALATIONS_PER_SESSION + 5; i++) {
            assertTrue(refusal(babysitter, "babysit_escalate", args("message", "m"))
                    .contains("state store refused"));
        }

        babysit.refuseEscalation = false;
        babysit.escalationFailure = new IllegalStateException("store down");
        for (int i = 0; i < McpSessionRegistry.MAX_ESCALATIONS_PER_SESSION; i++) {
            assertTrue(refusal(babysitter, "babysit_escalate", args("message", "m"))
                    .contains("store down"));
        }
        assertTrue(refusal(babysitter, "babysit_escalate", args("message", "one more"))
                .contains("already escalated"), "an exceptional post-dispatch outcome keeps its charge");
        assertEquals(List.of(), babysit.state.get().escalations());
    }

    @Test
    void anOversizedOrBlankOrControlCharacterMessageIsRefusedBeforeTheGate() {
        pair(BabysitState.WATCHING);

        assertTrue(refusal(babysitter, "babysit_escalate", args("message", "x".repeat(2001)))
                .contains("limit is 2000"));
        assertTrue(refusal(babysitter, "babysit_escalate", args("message", "  ")).contains("Missing required"));
        assertThrows(McpToolException.class,
                () -> router.call(babysitter, "babysit_escalate", args("message", "bell\u0007")));
        assertThrows(McpToolException.class, () -> router.call(babysitter, "babysit_escalate",
                args("message", "ok", "detail", "y".repeat(2001))));
        assertEquals(List.of(), babysit.state.get().escalations());
    }
}
