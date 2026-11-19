package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.ReviewRequest.Failed;
import app.drydock.intellij.review.ReviewRequest.NoConsole;
import app.drydock.intellij.review.ReviewRequest.NoTools;
import app.drydock.intellij.review.ReviewRequest.NotReady;
import app.drydock.intellij.review.ReviewRequest.Outcome;
import app.drydock.intellij.review.ReviewRequest.Refused;
import app.drydock.intellij.review.ReviewRequest.ScopeSource;
import app.drydock.intellij.review.ReviewRequest.Sent;
import app.drydock.mcp.ReviewInvocationBindings;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewIntent;
import app.drydock.review.tour.ReviewTour;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.LocalWorkflowMaterializer;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer;
import app.drydock.sdlc.WorkflowResourceLease;
import app.drydock.sdlc.WorkflowTarget;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewRequestTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    private final List<String> prompts = new ArrayList<>();
    private final ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
            ReviewScope.Kind.BRANCH, Path.of("/repo"), Optional.empty(), "main", "feat",
            Optional.empty(), Optional.empty()));

    private ReviewRequest request(CompletableFuture<?> init, Optional<ScopeSource> source,
                                  Predicate<AgentKind> subagents, PromptOutcome result) {
        return new ReviewRequest(init, () -> source, id -> true, id -> true, subagents, (id, prompt) -> {
            prompts.add(prompt);
            return result;
        }, SHORT, SHORT);
    }

    private ReviewRequest delivering(PromptOutcome result) {
        return request(CompletableFuture.completedFuture(null),
                Optional.of(s -> CompletableFuture.completedFuture(scope)), k -> true, result);
    }

    @Test
    void deliveredRequestSendsForScopeUnchangedWithSubagentFlag() {
        ManagedAgentSession claude = session(AgentKind.CLAUDE);
        ManagedAgentSession pi = session(AgentKind.PI);
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> CompletableFuture.completedFuture(scope)),
                k -> k == AgentKind.CLAUDE, PromptOutcome.DELIVERED);

        assertEquals(new Sent(scope.id()), r.request(claude));
        assertEquals(new Sent(scope.id()), r.request(pi));

        assertEquals(List.of(ReviewInstructions.forScope(scope.id(), true),
                ReviewInstructions.forScope(scope.id(), false)), prompts);
    }

    @Test
    void emptySourceIsNotReady() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null), Optional.empty(),
                k -> true, PromptOutcome.DELIVERED);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE)));
        assertTrue(prompts.isEmpty());
    }

    @Test
    void sessionWithoutAccessGetsNoToolsAndNothingIsResolvedOrSent() {
        List<ManagedAgentSession> scoped = new ArrayList<>();
        ReviewRequest r = new ReviewRequest(CompletableFuture.completedFuture(null),
                () -> Optional.of(s -> {
                    scoped.add(s);
                    return CompletableFuture.completedFuture(scope);
                }), id -> true, id -> false, k -> true, (id, prompt) -> {
                    prompts.add(prompt);
                    return PromptOutcome.DELIVERED;
                }, SHORT, SHORT);

        assertInstanceOf(NoTools.class, r.request(session(AgentKind.CLAUDE)));
        assertTrue(scoped.isEmpty());
        assertTrue(prompts.isEmpty());
        assertTrue(ReviewRequest.message("Fix login", new NoTools()).orElseThrow().contains("Fix login"));
    }

    @Test
    void anAgentThatEndedIsRefusedNotNoToolsAndNothingIsResolvedOrSent() {
        List<ManagedAgentSession> scoped = new ArrayList<>();
        ReviewRequest r = new ReviewRequest(CompletableFuture.completedFuture(null),
                () -> Optional.of(s -> {
                    scoped.add(s);
                    return CompletableFuture.completedFuture(scope);
                }), id -> false, id -> false, k -> true, (id, prompt) -> {
                    prompts.add(prompt);
                    return PromptOutcome.DELIVERED;
                }, SHORT, SHORT);

        assertInstanceOf(Refused.class, r.request(session(AgentKind.CLAUDE)));
        assertTrue(scoped.isEmpty());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void notReadyWinsOverMissingAccess() {
        ReviewRequest r = new ReviewRequest(CompletableFuture.completedFuture(null),
                Optional::empty, id -> true, id -> false, k -> true, (id, prompt) -> PromptOutcome.DELIVERED,
                SHORT, SHORT);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE)));
    }

    @Test
    void incompleteInitializationIsNotReady() {
        ReviewRequest r = request(new CompletableFuture<>(),
                Optional.of(s -> CompletableFuture.completedFuture(scope)), k -> true,
                PromptOutcome.DELIVERED);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE)));
    }

    @Test
    void failedInitializationIsNotReady() {
        ReviewRequest r = request(CompletableFuture.failedFuture(new IllegalStateException("x")),
                Optional.of(s -> CompletableFuture.completedFuture(scope)), k -> true,
                PromptOutcome.DELIVERED);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE)));
    }

    @Test
    void refusedPromptIsRefused() {
        assertInstanceOf(Refused.class, delivering(PromptOutcome.REFUSED).request(session(AgentKind.CLAUDE)));
    }

    @Test
    void failedPromptIsFailed() {
        assertInstanceOf(Failed.class, delivering(PromptOutcome.FAILED).request(session(AgentKind.CLAUDE)));
    }

    @Test
    void failedScopeFutureCarriesItsMessage() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> CompletableFuture.failedFuture(new IllegalStateException("no git"))),
                k -> true, PromptOutcome.DELIVERED);
        Outcome outcome = r.request(session(AgentKind.CLAUDE));
        assertEquals(new Failed("no git"), outcome);
        assertTrue(prompts.isEmpty());
    }

    @Test
    void incompleteScopeFutureTimesOut() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> new CompletableFuture<>()), k -> true, PromptOutcome.DELIVERED);
        assertEquals(new Failed("resolving the review scope timed out"),
                r.request(session(AgentKind.CLAUDE)));
    }

    @Test
    void throwingScopeSourceIsFailedNotThrown() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> {
                    throw new IllegalStateException("boom");
                }), k -> true, PromptOutcome.DELIVERED);
        assertEquals(new Failed("boom"), r.request(session(AgentKind.CLAUDE)));
    }

    @Test
    void messagesDifferAndSentHasNone() {
        Optional<String> notReady = ReviewRequest.message("T", new NotReady());
        Optional<String> refused = ReviewRequest.message("T", new Refused());
        assertTrue(notReady.isPresent());
        assertTrue(refused.isPresent());
        assertNotEquals(notReady.get(), refused.get());
        assertTrue(ReviewRequest.message("T", new Sent("rs_x")).isEmpty());
        assertEquals("Drydock could not start the review for T: why",
                ReviewRequest.message("T", new Failed("why")).orElseThrow());
    }

    // ---- intent-driven workflow delivery ----

    private static final String PROJECT_TEXT = "step 1\n\u001b[201~ step 2\r\n```sh\nrm -rf x\n```";

    @TempDir
    Path tmp;

    private final AtomicLong generation = new AtomicLong(4);
    private final ReviewInvocationBindings bindings = new ReviewInvocationBindings();
    private final List<MaterializedWorkflow> staged = new ArrayList<>();
    private final List<MaterializedWorkflow> retained = new ArrayList<>();
    private int materializations;

    private WorkflowMaterializer materializer() {
        LocalWorkflowMaterializer local = new LocalWorkflowMaterializer(tmp.resolve("staging"), "s1",
                ResourceManifestJournal.open(tmp.resolve("journal")), Path.of("/mnt"), generation::get, Runnable::run);
        return (request, cancellation) -> {
            materializations++;
            return local.materialize(request, cancellation).thenApply(result -> {
                if (result instanceof WorkflowMaterializer.Success success) {
                    staged.add(success.workflow());
                }
                return result;
            });
        };
    }

    private ReviewRequest.Delivery delivery(boolean configured, boolean authorized, boolean console, long gen) {
        Optional<ReviewRequest.Workflow> workflow = configured
                ? Optional.of(new ReviewRequest.Workflow(selected(), Destination.LOCAL, "inv-1", materializer(),
                        Cancellation.none(), (id, mw) -> retained.add(mw)))
                : Optional.empty();
        return new ReviewRequest.Delivery((id, scopeId) -> authorized, bindings, id -> console, gen,
                generation::get, workflow);
    }

    private static SelectedWorkflow selected() {
        return new SelectedWorkflow(RepositoryId.newId(), WorkflowTarget.LOCAL_CHANGES_REVIEW,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty()),
                PROJECT_TEXT, WorkflowBundle.empty(), "digest");
    }

    private static ReviewIntent intent(ManagedAgentSession s, ReviewTour.Kind kind) {
        return ReviewIntent.local(s.repositoryId(), new ReviewIntent.Local(Path.of("/repo")),
                Optional.of(s.id()), ReviewIntent.Origin.WORKBENCH, kind);
    }

    @Test
    void configuredRunSubmitsOneSafeDirectiveAndStagesProjectTextByteExact() throws Exception {
        ManagedAgentSession s = session(AgentKind.CLAUDE);
        Outcome out = delivering(PromptOutcome.DELIVERED).request(intent(s, ReviewTour.Kind.TOUR), s,
                Optional.empty(), delivery(true, true, true, 4));

        assertEquals(new Sent(scope.id()), out);
        assertEquals(1, prompts.size());
        String line = prompts.get(0);
        assertTrue(line.chars().noneMatch(c -> c == '\n' || c == '\r' || c == 0x1b));
        assertTrue(line.contains(scope.id()));
        Path file = ((MaterializedWorkflow.LocalReference) staged.get(0).references().stream()
                .filter(r -> r.role() == MaterializedWorkflow.Role.INSTRUCTION_FILE).findFirst().orElseThrow()).path();
        assertTrue(line.contains(file.toString()));
        assertEquals(PROJECT_TEXT, Files.readString(file));
        assertEquals(PROJECT_TEXT.getBytes(StandardCharsets.UTF_8).length, Files.readAllBytes(file).length);
        assertEquals(ReviewTour.Kind.TOUR, bindings.bindingOf(s.id(), scope.id()).orElseThrow().kind());
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, staged.get(0).lease().owner());
        assertEquals(1, retained.size());
    }

    @Test
    void ambiguousSubmitKeepsBindingAndLease() {
        ManagedAgentSession s = session(AgentKind.CLAUDE);
        Outcome out = delivering(PromptOutcome.FAILED).request(intent(s, ReviewTour.Kind.TOUR), s,
                Optional.empty(), delivery(true, true, true, 4));

        assertEquals(new ReviewRequest.Ambiguous(scope.id()), out);
        assertEquals(1, prompts.size());
        assertTrue(bindings.bindingOf(s.id(), scope.id()).isPresent());
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, staged.get(0).lease().owner());
        assertTrue(ReviewRequest.message("T", out).orElseThrow().contains("not retried"));
    }

    @Test
    void knownNoSendRollsBackBindingAndReleasesLease() {
        ManagedAgentSession s = session(AgentKind.CLAUDE);
        Outcome out = delivering(PromptOutcome.REFUSED).request(intent(s, ReviewTour.Kind.TOUR), s,
                Optional.empty(), delivery(true, true, true, 4));

        assertInstanceOf(Refused.class, out);
        assertEquals(1, prompts.size());
        assertTrue(bindings.bindingOf(s.id(), scope.id()).isEmpty());
        assertEquals(WorkflowResourceLease.Owner.RELEASED, staged.get(0).lease().owner());
        assertTrue(retained.isEmpty());
    }

    @Test
    void foreignScopeNoConsoleAndForeignIntentSendAndStageNothing() {
        ManagedAgentSession s = session(AgentKind.CLAUDE);
        ReviewRequest r = delivering(PromptOutcome.DELIVERED);

        assertInstanceOf(Refused.class, r.request(intent(s, ReviewTour.Kind.TOUR), s, Optional.empty(),
                delivery(true, false, true, 4)));
        assertInstanceOf(NoConsole.class, r.request(intent(s, ReviewTour.Kind.TOUR), s, Optional.empty(),
                delivery(true, true, false, 4)));
        assertInstanceOf(Refused.class, r.request(intent(session(AgentKind.CLAUDE), ReviewTour.Kind.TOUR), s,
                Optional.empty(), delivery(true, true, true, 4)));

        assertTrue(prompts.isEmpty());
        assertEquals(0, materializations);
        assertTrue(bindings.bindingOf(s.id(), scope.id()).isEmpty());
    }

    @Test
    void supersededRequestReleasesStagingAndSendsNothing() {
        ManagedAgentSession s = session(AgentKind.CLAUDE);
        Outcome out = delivering(PromptOutcome.DELIVERED).request(intent(s, ReviewTour.Kind.TOUR), s,
                Optional.empty(), delivery(true, true, true, 3));

        assertInstanceOf(Failed.class, out);
        assertTrue(prompts.isEmpty());
        assertTrue(bindings.bindingOf(s.id(), scope.id()).isEmpty());
        assertTrue(staged.isEmpty());
    }

    @Test
    void defaultSectionsRunKeepsSingleLineFormAndInstallsBinding() {
        ManagedAgentSession s = session(AgentKind.CLAUDE);
        Outcome out = delivering(PromptOutcome.DELIVERED).request(intent(s, ReviewTour.Kind.SECTIONS), s,
                Optional.of(scope.id()), delivery(false, true, true, 4));

        assertEquals(new Sent(scope.id()), out);
        assertEquals(List.of(ReviewInstructions.forSections(scope.id(), true,
                ModelTiers.EMPTY, Optional.empty())), prompts);
        assertEquals(ReviewTour.Kind.SECTIONS, bindings.bindingOf(s.id(), scope.id()).orElseThrow().kind());
        assertEquals(0, materializations);
        assertFalse(prompts.get(0).contains("\n"));
    }

    private static ManagedAgentSession session(AgentKind kind) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(kind, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/repo")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
    }
}
