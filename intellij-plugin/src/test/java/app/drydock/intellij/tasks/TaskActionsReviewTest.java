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
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.mcp.ReviewInvocationBindings;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskActionsReviewTest {

    private final List<String> prompts = new ArrayList<>();
    private final ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
            ReviewScope.Kind.BRANCH, Path.of("/wt/task"), Optional.empty(), "main", "feat",
            Optional.empty(), Optional.empty()));

    private static TaskNode.TaskRow row(Optional<PullRequestTarget> associatedPr) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Fix login", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).targetPullRequest(associatedPr).build();
        return new TaskNode.TaskRow(session, Optional.empty(), true, Optional.empty(),
                new TaskPlacement.ThisWindow(), false, false);
    }

    private ReviewRequest request(PromptOutcome result) {
        return new ReviewRequest(CompletableFuture.completedFuture(null),
                () -> Optional.of(s -> CompletableFuture.completedFuture(scope)), id -> true, id -> true,
                k -> true, (id, prompt) -> {
                    prompts.add(prompt);
                    return result;
                });
    }

    private static Optional<ReviewRequest.Delivery> delivery() {
        return Optional.of(new ReviewRequest.Delivery((id, scopeId) -> true, new ReviewInvocationBindings(),
                id -> true, 1L, () -> 1L, Optional.empty()));
    }

    @Test
    void quickReviewIsLocalEvenWhenTheSessionCarriesAPullRequest() {
        PullRequestTarget pr = PullRequestTarget.fromUrl("https://github.com/acme/app/pull/42");
        TaskNode.TaskRow row = row(Optional.of(pr));

        ReviewIntent intent = TaskActions.quickReviewIntent(row, Optional.empty());

        assertTrue(intent.pullRequestTarget().isEmpty(), "incidental PR metadata stays local");
        assertEquals(ReviewIntent.Origin.TASK_ACTION, intent.origin());
        assertEquals(Optional.of(row.session().id()), intent.session());
        assertEquals(new ReviewIntent.Local(Path.of("/wt/task")), intent.checkout());
        assertEquals(Optional.of(pr), TaskActions.quickReviewIntent(row, Optional.of(pr)).pullRequestTarget(),
                "only an explicit selection picks the PR method");
    }

    @Test
    void progressTitleNamesTheSourceAndWorkflowBeforeDispatch() {
        TaskNode.TaskRow row = row(Optional.empty());
        String title = TaskActions.quickReviewTitle(row, TaskActions.quickReviewIntent(row, Optional.empty()));

        assertEquals("Starting review of Fix login (local changes, Drydock default)…", title);
    }

    @Test
    void aSentQuickReviewSubmitsOnceAndShowsTheSourceInTheResult() {
        TaskNode.TaskRow row = row(Optional.empty());
        TaskActions.QuickReviewResult result = TaskActions.quickReview(request(PromptOutcome.DELIVERED),
                TaskActionsReviewTest::delivery, TaskActions.quickReviewIntent(row, Optional.empty()),
                row.session());

        assertTrue(result.sent());
        assertTrue(result.text().contains("local changes") && result.text().contains("Drydock default"));
        assertEquals(1, prompts.size(), "exactly one submit");
    }

    @Test
    void anUnsentQuickReviewIsReportedAndAnAbsentWiringSendsNothing() {
        TaskNode.TaskRow row = row(Optional.empty());
        ReviewIntent intent = TaskActions.quickReviewIntent(row, Optional.empty());

        TaskActions.QuickReviewResult refused = TaskActions.quickReview(request(PromptOutcome.REFUSED),
                TaskActionsReviewTest::delivery, intent, row.session());
        TaskActions.QuickReviewResult notReady = TaskActions.quickReview(request(PromptOutcome.DELIVERED),
                Optional::empty, intent, row.session());

        assertFalse(refused.sent());
        assertFalse(notReady.sent());
        assertTrue(notReady.text().contains("Fix login"));
        assertEquals(1, prompts.size(), "the refused attempt reached the writer; the absent wiring did not");
    }

    @Test
    void aThrowingRequestIsAResultNotAnEscape() {
        TaskNode.TaskRow row = row(Optional.empty());
        AtomicInteger calls = new AtomicInteger();
        TaskActions.QuickReviewResult result = TaskActions.quickReview(request(PromptOutcome.DELIVERED), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("backend gone");
        }, TaskActions.quickReviewIntent(row, Optional.empty()), row.session());

        assertFalse(result.sent());
        assertTrue(result.text().contains("backend gone"));
        assertEquals(1, calls.get());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void theIdleTreeRaceStaysFocusOnlyAndLabelledAsNoNewReview() {
        TaskNode.TaskRow row = row(Optional.empty());
        ReviewIntent intent = TaskActions.quickReviewIntent(row, Optional.empty());

        assertEquals(WorktreeReview.ExistingAction.FOCUS_ONLY,
                WorktreeReview.existingAction(Optional.of(intent), false),
                "a Quick Review origin is never an explicit workbench Run");
        assertEquals("Focused existing session; no new review sent", WorktreeReview.FOCUSED_NOTICE);
        assertInstanceOf(ReviewIntent.LocalChanges.class, intent.target());
    }
}
