package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.mcp.ReviewInvocationBindings;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.tour.ReviewTour;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowResolution;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeReviewTest {

    /** The label names the branch it reviews — the worktree's own identity, never a PR number. */
    @Test
    void namedAfterTheBranch() {
        assertEquals("Review feat/balloon", WorktreeReview.displayName("feat/balloon"));
    }

    @Test
    void missingTargetDoesNotPreventReviewTargetResolution() throws InterruptedException {
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup = new CompletableFuture<>();
        lookup.complete(Optional.empty());

        assertTrue(WorktreeReview.resolveTarget(lookup, 123, 1, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void failedAndTimedOutLookupsFallBackToNoTarget() throws InterruptedException {
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("lookup failed"));
        assertTrue(WorktreeReview.resolveTarget(failed, 123, 1, TimeUnit.SECONDS).isEmpty());

        assertTrue(WorktreeReview.resolveTarget(new CompletableFuture<>(), 123, 0, TimeUnit.MILLISECONDS).isEmpty());
    }

    @Test
    void malformedPrUrlFallsBackToNoTarget() throws InterruptedException {
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup = new CompletableFuture<>();
        lookup.complete(Optional.of(pullRequest("not a pull request URL")));

        assertTrue(WorktreeReview.resolveTarget(lookup, 123, 1, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void successfulLookupPreservesTheTarget() throws InterruptedException {
        String url = "https://github.com/example/project/pull/123";
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup = new CompletableFuture<>();
        lookup.complete(Optional.of(pullRequest(url)));

        PullRequestTarget target = WorktreeReview.resolveTarget(lookup, 123, 1, TimeUnit.SECONDS).orElseThrow();

        assertEquals(123, target.number());
        assertEquals(url, target.url());
    }

    @Test
    void interruptionIsPropagatedAndRestored() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> WorktreeReview.resolveTarget(new CompletableFuture<>(), 123, 1, TimeUnit.SECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static GhCliService.BranchPullRequest pullRequest(String url) {
        return new GhCliService.BranchPullRequest(123, GhCliService.PrInfo.PrLifecycle.OPEN, false,
                "pr-123", "Example", Optional.of(url));
    }

    private final List<String> submitted = new ArrayList<>();
    private final ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
            ReviewScope.Kind.BRANCH, Path.of("/repo"), Optional.empty(), "main", "feat",
            Optional.empty(), Optional.empty()));
    private final ManagedAgentSession running = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
            RepositoryId.newId(), "Task", AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
            SessionWorkspace.inRepository(Path.of("/repo")), Instant.EPOCH).status(SessionStatus.INACTIVE).build();

    private ReviewRequest request(PromptOutcome result) {
        return new ReviewRequest(CompletableFuture.completedFuture(null), () -> Optional.of(s -> CompletableFuture.completedFuture(scope)),
                id -> true, id -> true, kind -> true, (id, prompt) -> {
                    submitted.add(prompt);
                    return result;
                });
    }

    private static ReviewIntent intent(ReviewIntent.Origin origin) {
        // No session on the intent: the explicit Run must bind it to the session that owns the checkout.
        return ReviewIntent.local(RepositoryId.newId(), new ReviewIntent.Local(Path.of("/repo")), Optional.empty(),
                origin, ReviewTour.Kind.SECTIONS);
    }

    private static ReviewRequest.Delivery delivery(boolean console) {
        return new ReviewRequest.Delivery((id, scopeId) -> true, new ReviewInvocationBindings(), id -> console,
                1L, () -> 1L, Optional.empty());
    }

    private static final WorktreeReview.Policy UNCONFIGURED = target -> new WorkflowResolution.Missing("none");

    private WorktreeReview.RunResult run(ReviewRequest request, WorktreeReview.Policy policy,
                                         Optional<ReviewRequest.Delivery> delivery, AtomicInteger deliveryCalls) {
        return WorktreeReview.explicitRun(request, policy, () -> {
            deliveryCalls.incrementAndGet();
            return delivery;
        }, intent(ReviewIntent.Origin.WORKBENCH), running, "feat");
    }

    @Test
    void explicitRunOnAnExistingSessionSubmitsExactlyOnceWithoutConfiguration() {
        WorktreeReview.RunResult result = run(request(PromptOutcome.DELIVERED), UNCONFIGURED,
                Optional.of(delivery(true)), new AtomicInteger());

        assertEquals(1, submitted.size());
        assertTrue(submitted.get(0).contains(scope.id()));
        assertEquals(Level.INFO, result.level());
        assertTrue(result.text().contains("local changes") && result.text().contains("Drydock default"));
        assertTrue(result.text().contains("not confirmed"), "DELIVERED is not an execution acknowledgment");
    }

    @Test
    void configuredWorkflowIsRefusedBeforeAnyDeliveryWiringOrSend() {
        AtomicInteger calls = new AtomicInteger();
        WorktreeReview.RunResult result = run(request(PromptOutcome.DELIVERED),
                target -> new WorkflowResolution.Selected(new SelectedWorkflow(RepositoryId.newId(),
                        WorkflowTarget.LOCAL_CHANGES_REVIEW, new SelectedWorkflow.SourceDescriptor(
                                SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty()),
                        "text", WorkflowBundle.empty(), "digest")), Optional.of(delivery(true)), calls);

        assertEquals(0, submitted.size());
        assertEquals(0, calls.get());
        assertEquals(Level.WARNING, result.level());
        assertTrue(result.text().contains("no review sent"));
    }

    @Test
    void invalidWorkflowIsRefusedNotSilentlyDefaulted() {
        WorktreeReview.RunResult result = run(request(PromptOutcome.DELIVERED),
                target -> new WorkflowResolution.Invalid(new WorkflowResolution.WorkflowProblem("k",
                        Optional.empty(), ProblemCategory.SCHEMA, "bad schema")),
                Optional.of(delivery(true)), new AtomicInteger());

        assertEquals(0, submitted.size());
        assertTrue(result.text().contains("bad schema") && result.text().contains("no review sent"));
    }

    @Test
    void targetFollowsTheIntent() {
        List<WorkflowTarget> asked = new ArrayList<>();
        run(request(PromptOutcome.DELIVERED), target -> {
            asked.add(target);
            return new WorkflowResolution.Missing("none");
        }, Optional.of(delivery(true)), new AtomicInteger());

        assertEquals(List.of(WorkflowTarget.LOCAL_CHANGES_REVIEW), asked);
    }

    @Test
    void missingConsoleMissingBackendAndAmbiguousSendAreVisibleAndNeverRetried() {
        WorktreeReview.RunResult noConsole = run(request(PromptOutcome.DELIVERED), UNCONFIGURED,
                Optional.of(delivery(false)), new AtomicInteger());
        assertEquals(0, submitted.size());
        assertTrue(noConsole.text().contains("not attached"));

        WorktreeReview.RunResult noBackend = run(request(PromptOutcome.DELIVERED), UNCONFIGURED, Optional.empty(),
                new AtomicInteger());
        assertEquals(0, submitted.size());
        assertEquals(Level.WARNING, noBackend.level());

        WorktreeReview.RunResult ambiguous = run(request(PromptOutcome.FAILED), UNCONFIGURED,
                Optional.of(delivery(true)), new AtomicInteger());
        assertEquals(1, submitted.size(), "one attempt, never replayed");
        assertTrue(ambiguous.text().contains("unconfirmed") && ambiguous.level() == Level.WARNING);
    }

    @Test
    void originDecidesWhetherARunningSessionIsInvokedOrOnlyFocused() {
        assertEquals(WorktreeReview.ExistingAction.RUN,
                WorktreeReview.existingAction(Optional.of(intent(ReviewIntent.Origin.WORKBENCH)), false));
        assertEquals(WorktreeReview.ExistingAction.FOCUS_ONLY,
                WorktreeReview.existingAction(Optional.of(intent(ReviewIntent.Origin.LOCAL_TREE)), false));
        assertEquals(WorktreeReview.ExistingAction.FOCUS_ONLY, WorktreeReview.existingAction(Optional.empty(), false));
        assertEquals(WorktreeReview.ExistingAction.SUBMIT_SPHINX,
                WorktreeReview.existingAction(Optional.of(intent(ReviewIntent.Origin.WORKBENCH)), true));
        assertEquals("Focused existing session; no new review sent", WorktreeReview.FOCUSED_NOTICE);
    }

    @Test
    void sourceNamesTheLocalChangesOrTheExplicitPullRequest() {
        ReviewIntent pr = intent(ReviewIntent.Origin.WORKBENCH).withTarget(new ReviewIntent.PullRequest(
                PullRequestTarget.fromUrl("https://github.com/example/project/pull/123")));

        assertEquals("PR #123", WorktreeReview.sourceOf(Optional.of(pr)));
        assertEquals("local changes", WorktreeReview.sourceOf(Optional.empty()));
    }

    @Test
    void entryShowsProgressImmediatelyAndRealPathIsProbedOnlyInTheBackground(@TempDir Path dir) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        List<String> titles = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        List<Thread> shownOn = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        WorktreeReview review = new WorktreeReview((from, title, body) -> {
            titles.add(title);
            worker.execute(() -> {
                try {
                    release.await();
                    body.accept(null);
                    finished.complete(null);
                } catch (Throwable t) {
                    finished.completeExceptionally(t);
                }
            });
        }, (project, message, level) -> {
            shown.add(message);
            shownOn.add(Thread.currentThread());
        });

        review.reviewWithModelDialog(null, dir, dir.resolve("gone"), "feat", intent(ReviewIntent.Origin.WORKBENCH));

        assertEquals(1, titles.size(), "progress is requested by the entry itself");
        assertTrue(titles.get(0).contains("local changes"), titles.get(0));
        assertTrue(shown.isEmpty(), "the missing-path check has not run on the entry thread");
        release.countDown();
        finished.get(5, TimeUnit.SECONDS);
        worker.shutdownNow();
        assertEquals(1, shown.size());
        assertTrue(shown.get(0).contains("no longer on disk"));
        assertFalse(shownOn.get(0) == Thread.currentThread());
    }

    @Test
    void realKeyResolvesAnExistingWorktreeAndIsEmptyForAGoneOne(@TempDir Path dir) {
        assertTrue(WorktreeReview.realKey(dir).isPresent());
        assertTrue(WorktreeReview.realKey(dir.resolve("gone")).isEmpty());
    }
}
