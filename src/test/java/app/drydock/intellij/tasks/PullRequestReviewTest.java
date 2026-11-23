package app.drydock.intellij.tasks;

import app.drydock.review.tour.ReviewTour;
import app.drydock.review.ReviewInstructions;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.review.ReviewRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestReviewTest {

    private static Optional<AgentKind> select(Optional<AgentKind> defaultAgent, AgentKind... available) {
        return PullRequestReview.selectReviewAgent(defaultAgent, Set.of(available)::contains);
    }

    private static String noToolsMessage(String taskName) {
        return app.drydock.intellij.review.ReviewRequest.message(taskName,
                new app.drydock.intellij.review.ReviewRequest.NoTools()).orElseThrow();
    }

    @Test
    void aCustomPromptNeverComplainsAboutMissingReviewTools() {
        // The template is the harness's business; drydock's review backend
        // being absent must not produce a warning for it.
        PullRequestReview.ReviewPrompt prompt =
                PullRequestReview.ReviewPrompt.fixed("/sphinx review pr 664");
        assertEquals(Optional.empty(), prompt.userMessage("Review #664", false));
        assertEquals(Optional.empty(), prompt.userMessage("Review #664", true));
    }

    @Test
    void aDrydockPromptComplainsWhenTheSessionHoldsNoReviewTools() {
        // Also pins that the one-arg factory touches no platform service when constructed (no IDE here).
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE);
        // Not resolved here (that path needs the plugin service): the
        // armed-but-toolless case is what the launch is allowed to discover.
        assertEquals(Optional.of(noToolsMessage("Review #664")), prompt.userMessage("Review #664", false));
        assertEquals(Optional.empty(), prompt.userMessage("Review #664", true));
    }

    @Test
    void theInstructionAsksForTheKindOfReviewRequested() {
        assertEquals(ReviewInstructions.forSections("rs_1", true, ModelTiers.EMPTY, Optional.empty()),
                PullRequestReview.ReviewPrompt.instructionFor(ReviewTour.Kind.SECTIONS, "rs_1", true,
                        ModelTiers.EMPTY, Optional.empty()));
        assertEquals(ReviewInstructions.forScope("rs_1", false, ModelTiers.EMPTY, Optional.empty()),
                PullRequestReview.ReviewPrompt.instructionFor(ReviewTour.Kind.TOUR, "rs_1", false,
                        ModelTiers.EMPTY, Optional.empty()));
    }

    private static final String NAME = "Review #664";

    private static String message(ReviewRequest.Outcome outcome) {
        return ReviewRequest.message(NAME, outcome).orElseThrow();
    }

    private static CompletableFuture<Void> initialized() {
        return CompletableFuture.completedFuture(null);
    }

    /** Resolves {@code prompt} with no session: no path under test reaches one before a ready backend. */
    private static CompletableFuture<Optional<String>> resolveWithoutThrowing(PullRequestReview.ReviewPrompt prompt) {
        CompletableFuture<Optional<String>> resolved = assertDoesNotThrow(() -> prompt.resolve(null));
        assertTrue(resolved.isDone());
        assertFalse(resolved.isCompletedExceptionally());
        return resolved;
    }

    private static CompletableFuture<Optional<String>> resolveConfigured(PullRequestReview.ReviewPrompt prompt,
                                                                         List<String> sent) {
        // A resolved prompt would ride the launch as its initial prompt; record it as the "send".
        return prompt.resolve(null).thenApply(resolved -> {
            resolved.ifPresent(sent::add);
            return resolved;
        });
    }

    private static PullRequestReview.ReviewPrompt configured(
            java.util.function.Supplier<? extends CompletableFuture<?>> init,
            java.util.function.Supplier<Optional<app.drydock.intellij.review.ReviewBackend>> backend,
            java.util.concurrent.Executor executor) {
        return PullRequestReview.ReviewPrompt.configured(AgentKind.CLAUDE, scopeId -> "PROJECT METHOD " + scopeId,
                init, backend, kind -> true, executor);
    }

    @Test
    void aConfiguredReviewWithoutABackendRefusesBeforeSpawnAndSendsNothing() {
        List<String> sent = new java.util.ArrayList<>();
        PullRequestReview.ReviewPrompt prompt = configured(PullRequestReviewTest::initialized, Optional::empty,
                Runnable::run);
        CompletableFuture<Optional<String>> resolved = resolveConfigured(prompt, sent);
        assertTrue(prompt.refusesWithoutIntegration());
        assertTrue(resolved.isCompletedExceptionally());
        var failure = assertThrows(java.util.concurrent.CompletionException.class, resolved::join);
        assertInstanceOf(PullRequestReview.ReviewPrompt.ReviewRefused.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("review service is not running"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aConfiguredReviewRefusesOnEveryResolutionFailureNeverABareSession() {
        List<String> sent = new java.util.ArrayList<>();
        for (PullRequestReview.ReviewPrompt prompt : List.of(
                configured(() -> CompletableFuture.failedFuture(new IllegalStateException("init")), Optional::empty,
                        Runnable::run),
                configured(PullRequestReviewTest::initialized, () -> { throw new IllegalStateException("gone"); },
                        Runnable::run),
                configured(PullRequestReviewTest::initialized, Optional::empty,
                        command -> { throw new RejectedExecutionException("gone"); }))) {
            CompletableFuture<Optional<String>> resolved = resolveConfigured(prompt, sent);
            assertTrue(resolved.isCompletedExceptionally());
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    void theDefaultOnlyReviewKeepsTheBareSessionAndVisibleComplaintWithoutRefusing() {
        // The same absent backend that refuses a configured review: default-only (also default-once with no
        // project override consumed) resolves empty, the bare session starts, the complaint is shown.
        List<String> sent = new java.util.ArrayList<>();
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                PullRequestReviewTest::initialized, Optional::empty, kind -> true, Runnable::run);
        assertFalse(prompt.refusesWithoutIntegration());
        assertEquals(Optional.empty(), resolveConfigured(prompt, sent).join());
        assertTrue(sent.isEmpty());
        assertEquals(Optional.of(message(new ReviewRequest.NotReady())), prompt.userMessage(NAME, true));
    }

    @Test
    void anEditedProjectMethodIsConfiguredNotRelabelledDefault() {
        // Strictness follows the factory, never the text: a configured prompt whose methodology equals the
        // default instruction is still refused-without-integration.
        PullRequestReview.ReviewPrompt edited = PullRequestReview.ReviewPrompt.configured(AgentKind.CLAUDE,
                scopeId -> ReviewInstructions.forScope(scopeId, true, ModelTiers.EMPTY, Optional.empty()),
                PullRequestReviewTest::initialized, Optional::empty, kind -> true, Runnable::run);
        assertTrue(edited.refusesWithoutIntegration());
        assertTrue(edited.resolve(null).isCompletedExceptionally());
    }

    @Test
    void aSphinxFixedPromptIsNeverStrictAndNeedsNoIntegration() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.fixed("/sphinx review pr 664");
        assertFalse(prompt.refusesWithoutIntegration());
        assertEquals(Optional.of("/sphinx review pr 664"), prompt.resolve(null).join());
    }

    @Test
    void aFixedPromptResolvesAtOnceToItsTemplate() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.fixed("/sphinx review pr 664");
        CompletableFuture<Optional<String>> resolved = prompt.resolve(null);
        assertTrue(resolved.isDone());
        assertEquals(Optional.of("/sphinx review pr 664"), resolved.join());
        assertEquals(Optional.empty(), prompt.userMessage(NAME, false));
        assertEquals(Optional.empty(), prompt.userMessage(NAME, true));
    }

    @Test
    void withoutAReviewBackendTheSessionStartsBareAndTheServiceIsReportedNotRunning() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                PullRequestReviewTest::initialized, Optional::empty, kind -> true, Runnable::run);
        assertEquals(Optional.empty(), resolveWithoutThrowing(prompt).join());
        assertEquals(Optional.of(message(new ReviewRequest.NotReady())), prompt.userMessage(NAME, true));
        // The recorded complaint wins over the no-tools check.
        assertEquals(Optional.of(message(new ReviewRequest.NotReady())), prompt.userMessage(NAME, false));
    }

    @Test
    void aFailedInitializationIsNotReadyAndTheBackendIsNeverConsulted() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                () -> CompletableFuture.failedFuture(new IllegalStateException("init failed")),
                () -> { throw new AssertionError("backend must not be consulted after a failed init"); },
                kind -> true, Runnable::run);
        assertEquals(Optional.empty(), resolveWithoutThrowing(prompt).join());
        assertEquals(Optional.of(message(new ReviewRequest.NotReady())), prompt.userMessage(NAME, true));
    }

    @Test
    void anInitializationLookupThatThrowsIsNotReady() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                () -> { throw new NullPointerException("no service"); },
                () -> { throw new AssertionError("backend must not be consulted after a failed init"); },
                kind -> true, Runnable::run);
        assertEquals(Optional.empty(), resolveWithoutThrowing(prompt).join());
        assertEquals(Optional.of(message(new ReviewRequest.NotReady())), prompt.userMessage(NAME, true));
    }

    @Test
    void aBackendLookupThatThrowsIsNotReady() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                PullRequestReviewTest::initialized,
                () -> { throw new IllegalStateException("backend gone"); },
                kind -> true, Runnable::run);
        assertEquals(Optional.empty(), resolveWithoutThrowing(prompt).join());
        assertEquals(Optional.of(message(new ReviewRequest.NotReady())), prompt.userMessage(NAME, true));
    }

    @Test
    void anExecutorThatRejectsNeverFailsTheLaunchAndIsReportedAsFailed() {
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                PullRequestReviewTest::initialized, Optional::empty, kind -> true,
                command -> { throw new RejectedExecutionException("gone"); });
        assertEquals(Optional.empty(), resolveWithoutThrowing(prompt).join());
        assertEquals(Optional.of(message(new ReviewRequest.Failed("gone"))), prompt.userMessage(NAME, true));
        assertEquals(Optional.of(message(new ReviewRequest.Failed("gone"))), prompt.userMessage(NAME, false));
    }

    @Test
    void anUnexpectedErrorDuringResolutionIsReportedAsFailedNotPropagated() {
        // An Error escapes the RuntimeException-only guards, so the async step completes exceptionally.
        PullRequestReview.ReviewPrompt prompt = PullRequestReview.ReviewPrompt.drydockReview(AgentKind.CLAUDE,
                () -> { throw new AssertionError("boom"); }, Optional::empty, kind -> true, Runnable::run);
        assertEquals(Optional.empty(), resolveWithoutThrowing(prompt).join());
        assertEquals(Optional.of(message(new ReviewRequest.Failed("boom"))), prompt.userMessage(NAME, true));
    }

    @Test
    void aNonTerminalDefaultIsTakenAsIs() {
        assertEquals(Optional.of(AgentKind.CODEX), select(Optional.of(AgentKind.CODEX), AgentKind.CODEX));
        // Available is not even consulted for a usable default.
        assertEquals(Optional.of(AgentKind.PI), select(Optional.of(AgentKind.PI)));
    }

    @Test
    void aTerminalDefaultFallsBackToTheFirstAvailableAgent() {
        assertEquals(Optional.of(AgentKind.CLAUDE),
                select(Optional.of(AgentKind.TERMINAL), AgentKind.CLAUDE, AgentKind.TERMINAL));
    }

    @Test
    void noDefaultPicksTheFirstAvailableAgentInPreferenceOrder() {
        assertEquals(Optional.of(AgentKind.CODEX), select(Optional.empty(), AgentKind.TERMINAL, AgentKind.CODEX));
        assertEquals(Optional.of(AgentKind.PI), select(Optional.empty(), AgentKind.TERMINAL, AgentKind.PI));
    }

    @Test
    void terminalIsNeverSelectedEvenWhenItIsTheOnlyAvailableKind() {
        assertEquals(Optional.empty(), select(Optional.empty(), AgentKind.TERMINAL));
        assertEquals(Optional.empty(), select(Optional.of(AgentKind.TERMINAL), AgentKind.TERMINAL));
    }

    @Test
    void nothingAvailableYieldsEmpty() {
        assertTrue(select(Optional.empty()).isEmpty());
    }

    @Test
    void aRunningSessionOnTheWorktreeIsJoined(@TempDir Path dir) throws Exception {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(dir, SessionStatus.RUNNING)), dir).isPresent());
    }

    @Test
    void anEndedSessionOnTheWorktreeIsNotJoined(@TempDir Path dir) {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(dir, SessionStatus.INACTIVE)), dir).isEmpty());
    }

    @Test
    void aSessionOnAnotherDirectoryIsNotJoined(@TempDir Path dir, @TempDir Path other) {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(other, SessionStatus.RUNNING)), dir).isEmpty());
    }

    @Test
    void aGoneWorktreeJoinsNothing(@TempDir Path dir) {
        assertTrue(PullRequestReview.runningSessionFor(
                List.of(session(dir, SessionStatus.RUNNING)), dir.resolve("missing")).isEmpty());
    }

    private static ManagedAgentSession session(Path directory, SessionStatus status) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Review #12", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(directory), Instant.EPOCH).status(status).build();
    }

    // --- awaitModelChoice: the review model dialog hand-off -------------------------------------

    private static final long DEADLINE = 1_000;

    private static Optional<PullRequestReview.ModelChoice> await(
            CompletableFuture<PullRequestReview.ModelChoice> picked,
            AtomicBoolean claimed, BooleanSupplier abandoned, long now) {
        return PullRequestReview.awaitModelChoice(picked, claimed, abandoned, () -> now, DEADLINE, 1);
    }

    private static CompletableFuture<PullRequestReview.ModelChoice> picked(String model) {
        return CompletableFuture.completedFuture(
                new PullRequestReview.ModelChoice(AgentKind.CLAUDE, Optional.ofNullable(model),
                        Optional.of(ModelTiers.EMPTY)));
    }

    @Test
    void dialogOkWithAModelLaunchesWithIt() {
        assertEquals(
                Optional.of(new PullRequestReview.ModelChoice(AgentKind.CLAUDE, Optional.of("m"),
                        Optional.of(ModelTiers.EMPTY))),
                await(picked("m"), new AtomicBoolean(true), () -> false, 0));
    }

    @Test
    void dialogOkWithAnEmptyEditorLaunchesWithTheHarnessDefault() {
        Optional<PullRequestReview.ModelChoice> choice =
                await(picked(null), new AtomicBoolean(true), () -> false, 0);
        assertTrue(choice.isPresent(), "OK with an empty editor is a review, not a cancel");
        assertEquals(Optional.empty(), choice.get().model());
    }

    @Test
    void dialogCancelMeansNoReview() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        picked.cancel(false);
        assertEquals(Optional.empty(), await(picked, new AtomicBoolean(true), () -> false, 0));
    }

    @Test
    void dialogFailureMeansNoReview() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        picked.completeExceptionally(new RuntimeException("boom"));
        assertEquals(Optional.empty(), await(picked, new AtomicBoolean(true), () -> false, 0));
    }

    @Test
    void abandonedBeforeTheDialogOpensClaimsSoALateRunnableBacksOff() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        Optional<PullRequestReview.ModelChoice> choice = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> await(picked, claimed, () -> true, 0));
        assertEquals(Optional.empty(), choice);
        assertTrue(picked.isCancelled());
        assertTrue(claimed.get());
        assertFalse(claimed.compareAndSet(false, true), "a late EDT runnable must back off");
        assertFalse(picked.complete(picked("late").join()), "a late OK must be dropped, never launched");
    }

    @Test
    void abandonedWhileTheDialogIsOpenGivesUp() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean(true);
        Optional<PullRequestReview.ModelChoice> choice = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> await(picked, claimed, () -> true, 0));
        assertEquals(Optional.empty(), choice);
        assertTrue(picked.isCancelled());
        assertTrue(claimed.get());
    }

    @Test
    void aDialogThatNeverOpensIsBoundedByTheDeadline() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        Optional<PullRequestReview.ModelChoice> choice = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> await(picked, claimed, () -> false, 2_000));
        assertEquals(Optional.empty(), choice);
        assertTrue(claimed.get());
        assertTrue(picked.isCancelled());
    }

    @Test
    void theDeadlineDoesNotBoundThinkTimeOnceTheDialogIsOpen() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean(true);
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            picked.complete(
                    new PullRequestReview.ModelChoice(AgentKind.CLAUDE, Optional.of("late-ok"),
                            Optional.of(ModelTiers.EMPTY)));
        });
        Optional<PullRequestReview.ModelChoice> choice = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> await(picked, claimed, () -> false, 2_000));
        assertEquals(Optional.of("late-ok"), choice.get().model());
    }

    @Test
    void anInterruptedWaiterGivesUpAndKeepsTheInterrupt() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        Thread.currentThread().interrupt();
        try {
            assertEquals(Optional.empty(), await(picked, claimed, () -> false, 0));
            assertTrue(Thread.interrupted(), "the interrupt must be restored");
            assertTrue(picked.isCancelled());
            assertTrue(claimed.get());
            assertFalse(claimed.compareAndSet(false, true));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void beforeTheDeadlineTheWaiterKeepsWaitingWithoutClaiming() {
        CompletableFuture<PullRequestReview.ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            picked.complete(new PullRequestReview.ModelChoice(AgentKind.CLAUDE, Optional.of("m"),
                    Optional.of(ModelTiers.EMPTY)));
        });
        Optional<PullRequestReview.ModelChoice> choice = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> await(picked, claimed, () -> false, 0));
        assertEquals(Optional.of("m"), choice.get().model());
        assertFalse(claimed.get(), "the waiter must not claim before the deadline");
    }
}
