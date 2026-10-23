package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.review.ReviewRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
