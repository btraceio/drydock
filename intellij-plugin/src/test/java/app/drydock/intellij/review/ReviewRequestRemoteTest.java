package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.RemoteReviewCapability.Advice;
import app.drydock.intellij.review.RemoteReviewCapability.Inputs;
import app.drydock.intellij.review.RemoteReviewCapability.State;
import app.drydock.intellij.review.ReviewRequest.NoConsole;
import app.drydock.intellij.review.ReviewRequest.Outcome;
import app.drydock.intellij.review.ReviewRequest.RemoteUnavailable;
import app.drydock.intellij.review.ReviewRequest.Sent;
import app.drydock.mcp.RemoteReviewContextDelegates.Evidence;
import app.drydock.mcp.ReviewInvocationBindings;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
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
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remote/existing-session review gating in {@link ReviewRequest} (INV-25/48/58/61): zero sends unless runnable. */
class ReviewRequestRemoteTest {

    @TempDir
    Path tmp;

    private final List<String> prompts = new ArrayList<>();
    private final List<MaterializedWorkflow> staged = new ArrayList<>();
    private final ReviewInvocationBindings bindings = new ReviewInvocationBindings();
    private final ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
            ReviewScope.Kind.BRANCH, Path.of("/repo"), Optional.empty(), "main", "feat",
            Optional.empty(), Optional.empty()));
    private final ManagedAgentSession session = new ManagedAgentSession.Builder(
            app.drydock.domain.ManagedSessionId.newId(), RepositoryId.newId(), "Task",
            AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
            SessionWorkspace.inRepository(Path.of("/repo")), Instant.EPOCH)
            .status(SessionStatus.RUNNING).build();
    private int materializations;

    private static RemoteReviewCapability.Report report(Verdict v, Evidence live, boolean attached, State data) {
        return RemoteReviewCapability.assess(new Inputs(State.ready(), v, live, State.ready(), data,
                State.ready(), attached));
    }

    private static RemoteReviewCapability.Report ready(boolean attached) {
        return report(new Verdict.Ready(), Evidence.ALIVE, attached, State.ready());
    }

    private Outcome run(boolean configured, Supplier<RemoteReviewCapability.Report> remote, PromptOutcome result) {
        ReviewRequest request = new ReviewRequest(CompletableFuture.completedFuture(null),
                () -> Optional.of(s -> CompletableFuture.completedFuture(scope)), id -> true, id -> false,
                k -> true, (id, prompt) -> {
                    prompts.add(prompt);
                    return result;
                }, Duration.ofMillis(50), Duration.ofMillis(50));
        LocalWorkflowMaterializer local = new LocalWorkflowMaterializer(tmp.resolve("staging"), "s1",
                ResourceManifestJournal.open(tmp.resolve("journal")), Path.of("/mnt"), () -> 4L, Runnable::run);
        WorkflowMaterializer counting = (req, c) -> {
            materializations++;
            return local.materialize(req, c).thenApply(r -> {
                if (r instanceof WorkflowMaterializer.Success ok) {
                    staged.add(ok.workflow());
                }
                return r;
            });
        };
        Optional<ReviewRequest.Workflow> workflow = configured
                ? Optional.of(new ReviewRequest.Workflow(new SelectedWorkflow(RepositoryId.newId(),
                        WorkflowTarget.LOCAL_CHANGES_REVIEW, new SelectedWorkflow.SourceDescriptor(
                                SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty()),
                        "step", WorkflowBundle.empty(), "digest"), Destination.LOCAL, "inv", counting,
                        Cancellation.none(), (id, mw) -> { }))
                : Optional.empty();
        ReviewIntent intent = ReviewIntent.local(session.repositoryId(), new ReviewIntent.Local(Path.of("/repo")),
                Optional.of(session.id()), ReviewIntent.Origin.WORKBENCH, ReviewTour.Kind.TOUR);
        return request.request(intent, session, Optional.empty(), new ReviewRequest.Delivery(
                (id, s) -> true, bindings, id -> true, 4, () -> 4L, workflow, Optional.of(remote)));
    }

    @Test
    void detachedLegacyAndUnverifiableRefuseWithZeroSendsConfiguredOrDefault() {
        for (boolean configured : new boolean[]{true, false}) {
            Outcome detached = run(configured, () -> ready(false), PromptOutcome.DELIVERED);
            assertInstanceOf(NoConsole.class, detached);
            Outcome legacy = run(configured, () -> report(new Verdict.Unavailable(Reason.LEGACY_PROVEN, "no config"),
                    Evidence.ALIVE, true, State.ready()), PromptOutcome.DELIVERED);
            assertEquals(Advice.STOP_THEN_RESUME, assertInstanceOf(RemoteUnavailable.class, legacy).advice());
            assertTrue(ReviewRequest.message("T", legacy).orElseThrow().contains("Stop the task, then Resume"));
            Outcome unknown = run(configured, () -> report(new Verdict.Ready(), Evidence.UNKNOWN, true,
                    State.ready()), PromptOutcome.DELIVERED);
            assertEquals(Advice.CHECK_EVIDENCE_AND_RETRY, assertInstanceOf(RemoteUnavailable.class, unknown).advice());
            Outcome noData = run(configured, () -> report(new Verdict.Ready(), Evidence.ALIVE, true,
                    State.unavailable("diff unavailable")), PromptOutcome.DELIVERED);
            assertInstanceOf(RemoteUnavailable.class, noData);
            Outcome threw = run(configured, () -> { throw new IllegalStateException("x"); }, PromptOutcome.DELIVERED);
            assertInstanceOf(RemoteUnavailable.class, threw);
        }
        assertTrue(prompts.isEmpty());
        assertEquals(0, materializations);
        assertTrue(bindings.bindingOf(session.id(), scope.id()).isEmpty());
    }

    @Test
    void attachedEnrolledRunSendsExactlyOnceConfiguredAndDefault() {
        assertEquals(new Sent(scope.id()), run(true, () -> ready(true), PromptOutcome.DELIVERED));
        assertEquals(new Sent(scope.id()), run(false, () -> ready(true), PromptOutcome.DELIVERED));
        assertEquals(2, prompts.size());
    }

    @Test
    void capabilityLostAfterStagingReleasesTheLeaseAndSendsNothing() {
        int[] calls = {0};
        Outcome out = run(true, () -> ++calls[0] == 1 ? ready(true) : ready(false), PromptOutcome.DELIVERED);

        assertInstanceOf(NoConsole.class, out);
        assertEquals(1, materializations);
        assertEquals(WorkflowResourceLease.Owner.RELEASED, staged.get(0).lease().owner());
        assertTrue(prompts.isEmpty());
        assertTrue(bindings.bindingOf(session.id(), scope.id()).isEmpty());
    }
}
