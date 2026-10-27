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
import app.drydock.intellij.review.ReviewRequest.NoTools;
import app.drydock.intellij.review.ReviewRequest.NotReady;
import app.drydock.intellij.review.ReviewRequest.Outcome;
import app.drydock.intellij.review.ReviewRequest.Refused;
import app.drydock.intellij.review.ReviewRequest.ScopeSource;
import app.drydock.intellij.review.ReviewRequest.Sent;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(new Sent(scope.id()), r.request(claude, true));
        assertEquals(new Sent(scope.id()), r.request(pi, true));

        assertEquals(List.of(ReviewInstructions.forScope(scope.id(), true),
                ReviewInstructions.forScope(scope.id(), false)), prompts);
    }

    @Test
    void aFindingsOnlyRequestSendsTheInstructionWithoutTheTour() {
        ReviewRequest r = delivering(PromptOutcome.DELIVERED);

        assertEquals(new Sent(scope.id()), r.request(session(AgentKind.CLAUDE), false));

        assertEquals(List.of(ReviewInstructions.forScope(scope.id(), true, ModelTiers.EMPTY, Optional.empty(),
                false)), prompts);
    }

    @Test
    void emptySourceIsNotReady() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null), Optional.empty(),
                k -> true, PromptOutcome.DELIVERED);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE), true));
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

        assertInstanceOf(NoTools.class, r.request(session(AgentKind.CLAUDE), true));
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

        assertInstanceOf(Refused.class, r.request(session(AgentKind.CLAUDE), true));
        assertTrue(scoped.isEmpty());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void notReadyWinsOverMissingAccess() {
        ReviewRequest r = new ReviewRequest(CompletableFuture.completedFuture(null),
                Optional::empty, id -> true, id -> false, k -> true, (id, prompt) -> PromptOutcome.DELIVERED,
                SHORT, SHORT);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE), true));
    }

    @Test
    void incompleteInitializationIsNotReady() {
        ReviewRequest r = request(new CompletableFuture<>(),
                Optional.of(s -> CompletableFuture.completedFuture(scope)), k -> true,
                PromptOutcome.DELIVERED);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE), true));
    }

    @Test
    void failedInitializationIsNotReady() {
        ReviewRequest r = request(CompletableFuture.failedFuture(new IllegalStateException("x")),
                Optional.of(s -> CompletableFuture.completedFuture(scope)), k -> true,
                PromptOutcome.DELIVERED);
        assertInstanceOf(NotReady.class, r.request(session(AgentKind.CLAUDE), true));
    }

    @Test
    void refusedPromptIsRefused() {
        assertInstanceOf(Refused.class, delivering(PromptOutcome.REFUSED).request(session(AgentKind.CLAUDE), true));
    }

    @Test
    void failedPromptIsFailed() {
        assertInstanceOf(Failed.class, delivering(PromptOutcome.FAILED).request(session(AgentKind.CLAUDE), true));
    }

    @Test
    void failedScopeFutureCarriesItsMessage() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> CompletableFuture.failedFuture(new IllegalStateException("no git"))),
                k -> true, PromptOutcome.DELIVERED);
        Outcome outcome = r.request(session(AgentKind.CLAUDE), true);
        assertEquals(new Failed("no git"), outcome);
        assertTrue(prompts.isEmpty());
    }

    @Test
    void incompleteScopeFutureTimesOut() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> new CompletableFuture<>()), k -> true, PromptOutcome.DELIVERED);
        assertEquals(new Failed("resolving the review scope timed out"),
                r.request(session(AgentKind.CLAUDE), true));
    }

    @Test
    void throwingScopeSourceIsFailedNotThrown() {
        ReviewRequest r = request(CompletableFuture.completedFuture(null),
                Optional.of(s -> {
                    throw new IllegalStateException("boom");
                }), k -> true, PromptOutcome.DELIVERED);
        assertEquals(new Failed("boom"), r.request(session(AgentKind.CLAUDE), true));
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

    private static ManagedAgentSession session(AgentKind kind) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(kind, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/repo")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
    }
}
