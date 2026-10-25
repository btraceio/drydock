package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless T4 tests for the triage write side (AC2–AC4, AC8's off-EDT
 * contract): every transition, the post-to-PR promotion rule, persisted
 * comments, the exact fix prompt, every delivery outcome and the worker
 * thread the mutations ran on. The store is a real one over a temp file,
 * flushed wherever persistence is claimed.
 */
class ReviewTriageActionsTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final ManagedSessionId SESSION =
            ManagedSessionId.of("7cfda2b6-d909-4f3a-a1b2-000000000001");

    /** An executor that records which thread actually ran each command. */
    private static final class RecordingExecutor implements Executor {
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final ExecutorService worker =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "triage-test-worker"));

        @Override
        public void execute(Runnable command) {
            worker.execute(() -> {
                threads.add(Thread.currentThread());
                command.run();
            });
        }
    }

    @TempDir
    Path temp;

    private AnnotationStore store;
    private RecordingExecutor executor;
    private ReviewTriageActions actions;
    private final AtomicReference<ManagedSessionId> submittedSession = new AtomicReference<>();
    private final List<String> submittedPrompts = new CopyOnWriteArrayList<>();
    private final AtomicReference<PromptOutcome> submitAnswer =
            new AtomicReference<>(PromptOutcome.DELIVERED);

    @BeforeEach
    void setUp() {
        store = new AnnotationStore(temp.resolve("annotations.json"));
        executor = new RecordingExecutor();
        submittedSession.set(null);
        submittedPrompts.clear();
        submitAnswer.set(PromptOutcome.DELIVERED);
        actions = new ReviewTriageActions(store, executor,
                (session, prompt) -> {
                    submittedSession.set(session);
                    submittedPrompts.add(prompt);
                    return submitAnswer.get();
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        // Only the disposal test closes the store; closing again would throw
        // from its already-shutdown save executor, and teardown must not race
        // @TempDir cleanup with a queued save either way.
        try {
            store.flushPendingSaves();
        } catch (RuntimeException expectedWhenClosed) {
            // already disposed by a test
        }
        try {
            store.close();
        } catch (RuntimeException expectedWhenClosed) {
            // already disposed by a test
        }
        executor.worker.shutdown();
    }

    // --- fixtures ------------------------------------------------------------

    private static ReviewAnnotation finding(String id, Triage triage, boolean postToPr) {
        return new ReviewAnnotation("rs_a", id, "src/Main.java", "n10", "n12",
                Severity.QUESTION, Confidence.HIGH, Optional.of("Title " + id), "Claude", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", AT, "body of " + id)),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), postToPr,
                triage, Optional.empty());
    }

    private static ReviewScope scope(Optional<ManagedSessionId> session) {
        return new ReviewScope("rs_a", ReviewScope.Kind.WORKTREE, Path.of("/repo"),
                Optional.of(Path.of("/repo/.wt/a")), "base0", "head0",
                Optional.empty(), session, Optional.empty());
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Future did not complete normally", e);
        }
    }

    private ReviewAnnotation stored(String id) {
        return store.byId("rs_a", id).orElseThrow();
    }

    // --- triage (AC2) --------------------------------------------------------

    @Test
    void confirmPromotesPostToPrOnTheTransitionIntoConfirmed() {
        store.upsert(finding("f1", Triage.PROPOSED, false));

        ReviewAnnotation confirmed = await(actions.confirm(new ReviewAnnotation.Key("rs_a", "f1")))
                .orElseThrow();

        assertEquals(Triage.CONFIRMED, confirmed.triage());
        assertTrue(confirmed.postToPr());
        assertEquals(Triage.CONFIRMED, stored("f1").triage());
        assertTrue(stored("f1").postToPr());
    }

    @Test
    void reconfirmCannotUndoALaterOptOut() {
        store.upsert(finding("f1", Triage.PROPOSED, false));
        await(actions.confirm(new ReviewAnnotation.Key("rs_a", "f1")));
        // The human opts out after confirming, as the margin's toggle allows.
        store.mutate(new ReviewAnnotation.Key("rs_a", "f1"),
                current -> current.withPostToPr(false));

        ReviewAnnotation reconfirmed = await(actions.confirm(new ReviewAnnotation.Key("rs_a", "f1")))
                .orElseThrow();

        assertEquals(Triage.CONFIRMED, reconfirmed.triage());
        assertFalse(reconfirmed.postToPr(), "a redundant confirm must not undo the opt-out");
        assertFalse(stored("f1").postToPr());
    }

    @Test
    void confirmOfAnAlreadyConfirmedFindingDoesNotPromote() {
        store.upsert(finding("f1", Triage.CONFIRMED, false));

        ReviewAnnotation confirmed = await(actions.confirm(new ReviewAnnotation.Key("rs_a", "f1")))
                .orElseThrow();

        assertEquals(Triage.CONFIRMED, confirmed.triage());
        assertFalse(confirmed.postToPr(), "only the transition into CONFIRMED promotes");
    }

    @Test
    void dismissKeepsEveryUnrelatedField() {
        ReviewAnnotation original = finding("f1", Triage.PROPOSED, true);
        store.upsert(original);

        ReviewAnnotation dismissed = await(actions.dismiss(new ReviewAnnotation.Key("rs_a", "f1")))
                .orElseThrow();

        assertEquals(Triage.DISMISSED, dismissed.triage());
        assertEquals(original.postToPr(), dismissed.postToPr());
        assertEquals(original.status(), dismissed.status());
        assertEquals(original.thread(), dismissed.thread());
        assertEquals(original.title(), dismissed.title());
        assertEquals(original.author(), dismissed.author());
        assertEquals(original.startKey(), dismissed.startKey());
        assertEquals(original.endKey(), dismissed.endKey());
        assertEquals(original.severity(), dismissed.severity());
        assertEquals(original.severityOverride(), dismissed.severityOverride());
        assertEquals(original.withheldBy(), dismissed.withheldBy());
        assertEquals(Triage.DISMISSED, stored("f1").triage());
    }

    @Test
    void undoReturnsToProposedAndKeepsEveryUnrelatedField() {
        ReviewAnnotation original = finding("f1", Triage.CONFIRMED, true);
        store.upsert(original);

        ReviewAnnotation undone = await(actions.undo(new ReviewAnnotation.Key("rs_a", "f1")))
                .orElseThrow();

        assertEquals(Triage.PROPOSED, undone.triage());
        // postToPr is a separate decision: undoing the triage does not undo
        // the promotion the confirm made.
        assertTrue(undone.postToPr());
        assertEquals(original.thread(), undone.thread());
        assertEquals(original.status(), undone.status());
        assertEquals(Triage.PROPOSED, stored("f1").triage());
    }

    @Test
    void triageOfAnUnknownKeyChangesNothing() {
        assertTrue(await(actions.confirm(new ReviewAnnotation.Key("rs_a", "gone"))).isEmpty());
        assertTrue(await(actions.dismiss(new ReviewAnnotation.Key("rs_a", "gone"))).isEmpty());
        assertTrue(await(actions.undo(new ReviewAnnotation.Key("rs_a", "gone"))).isEmpty());
        assertTrue(store.forScope("rs_a").isEmpty());
    }

    // --- comments (AC3) ------------------------------------------------------

    @Test
    void commentAppendsAYouMessageStampedByTheInjectedClock() {
        ReviewAnnotation original = finding("f1", Triage.PROPOSED, false);
        store.upsert(original);

        ReviewTriageActions.CommentOutcome outcome =
                await(actions.comment(new ReviewAnnotation.Key("rs_a", "f1"), "Looks wrong here"));

        assertEquals(ReviewTriageActions.CommentOutcome.State.APPENDED, outcome.state());
        ReviewAnnotation updated = outcome.finding();
        assertEquals(original.thread().size() + 1, updated.thread().size());
        ReviewAnnotation.Message reply = updated.thread().get(updated.thread().size() - 1);
        assertEquals(new ReviewAnnotation.Message("You", NOW, "Looks wrong here", Optional.empty()),
                reply);
        assertEquals(reply, stored("f1").thread().get(stored("f1").thread().size() - 1));
    }

    @Test
    void aBlankCommentIsRejectedWithoutAMutation() {
        store.upsert(finding("f1", Triage.PROPOSED, false));
        int workersBefore = executor.threads.size();

        ReviewTriageActions.CommentOutcome outcome =
                await(actions.comment(new ReviewAnnotation.Key("rs_a", "f1"), "   "));

        assertEquals(ReviewTriageActions.CommentOutcome.State.BLANK, outcome.state());
        assertNull(outcome.finding());
        assertEquals(1, stored("f1").thread().size(), "blank input must not append anything");
        assertEquals(workersBefore, executor.threads.size(), "blank rejection needs no worker");
    }

    @Test
    void aCommentOnAnUnknownKeyIsMissing() {
        ReviewTriageActions.CommentOutcome outcome =
                await(actions.comment(new ReviewAnnotation.Key("rs_a", "gone"), "hello"));

        assertEquals(ReviewTriageActions.CommentOutcome.State.MISSING, outcome.state());
        assertNull(outcome.finding());
    }

    // --- persistence (AC2/AC3 through the store) -----------------------------

    @Test
    void triageAndCommentsPersistAfterAFlushAndReload() {
        store.upsert(finding("f1", Triage.PROPOSED, false));
        store.upsert(finding("f2", Triage.PROPOSED, false));
        await(actions.confirm(new ReviewAnnotation.Key("rs_a", "f1")));
        await(actions.comment(new ReviewAnnotation.Key("rs_a", "f1"), "please fix"));
        await(actions.dismiss(new ReviewAnnotation.Key("rs_a", "f2")));
        store.flushPendingSaves();
        store.close();

        AnnotationStore reloaded = new AnnotationStore(temp.resolve("annotations.json"));
        try {
            ReviewAnnotation confirmed = reloaded.byId("rs_a", "f1").orElseThrow();
            assertEquals(Triage.CONFIRMED, confirmed.triage());
            assertTrue(confirmed.postToPr());
            ReviewAnnotation.Message last = confirmed.thread().get(confirmed.thread().size() - 1);
            assertEquals("You", last.author());
            assertEquals(NOW, last.at());
            assertEquals("please fix", last.text());
            assertEquals(Triage.DISMISSED, reloaded.byId("rs_a", "f2").orElseThrow().triage());
        } finally {
            reloaded.close();
        }
    }

    // --- ask-to-fix (AC4) ----------------------------------------------------

    @Test
    void aDeliveredAskSendsTheSharedPromptAndMarksEveryFindingSent() {
        List<ReviewAnnotation> findings =
                List.of(finding("f1", Triage.CONFIRMED, true), finding("f2", Triage.CONFIRMED, true));
        findings.forEach(store::upsert);

        ReviewTriageActions.FixOutcome outcome =
                await(actions.askToFix(scope(Optional.of(SESSION)), "src/Main.java", findings));

        assertEquals(ReviewTriageActions.FixOutcome.State.DELIVERED, outcome.state());
        assertEquals(1, submittedPrompts.size());
        assertEquals(SESSION, submittedSession.get());
        // The app's exact one-line prompt, built by the shared core builder.
        assertEquals("Address these review findings on \"src/Main.java\", "
                        + "then summarize what you changed: "
                        + "[1] src/Main.java n10: Title f1. [2] src/Main.java n10: Title f2.",
                submittedPrompts.get(0));
        assertEquals(2, outcome.findings().size());
        assertEquals(AnnotationStatus.SENT, stored("f1").status());
        assertEquals(AnnotationStatus.SENT, stored("f2").status());
        for (ReviewAnnotation sent : outcome.findings()) {
            assertEquals(AnnotationStatus.SENT, sent.status());
        }
    }

    @Test
    void anAskWithoutABoundSessionSendsNothingAndMarksNothing() {
        store.upsert(finding("f1", Triage.CONFIRMED, true));

        ReviewTriageActions.FixOutcome outcome = await(actions.askToFix(
                scope(Optional.empty()), "src/Main.java", List.of(stored("f1"))));

        assertEquals(ReviewTriageActions.FixOutcome.State.NO_SESSION, outcome.state());
        assertTrue(outcome.findings().isEmpty());
        assertTrue(submittedPrompts.isEmpty(), "no session means no submission attempt");
        assertEquals(AnnotationStatus.OPEN, stored("f1").status());
    }

    @Test
    void anEmptyAskIsRefusedBeforeAPromptIsBuilt() {
        ReviewTriageActions.FixOutcome outcome = await(actions.askToFix(
                scope(Optional.of(SESSION)), "src/Main.java", List.of()));

        assertEquals(ReviewTriageActions.FixOutcome.State.EMPTY, outcome.state());
        assertTrue(submittedPrompts.isEmpty());
    }

    @Test
    void aRefusedHandOffMarksNothing() {
        List<ReviewAnnotation> findings = List.of(finding("f1", Triage.CONFIRMED, true));
        findings.forEach(store::upsert);
        submitAnswer.set(PromptOutcome.REFUSED);

        ReviewTriageActions.FixOutcome outcome =
                await(actions.askToFix(scope(Optional.of(SESSION)), "src/Main.java", findings));

        assertEquals(ReviewTriageActions.FixOutcome.State.REFUSED, outcome.state());
        assertTrue(outcome.findings().isEmpty());
        assertEquals(AnnotationStatus.OPEN, stored("f1").status());
    }

    @Test
    void aFailedHandOffMarksNothing() {
        List<ReviewAnnotation> findings = List.of(finding("f1", Triage.CONFIRMED, true));
        findings.forEach(store::upsert);
        submitAnswer.set(PromptOutcome.FAILED);

        ReviewTriageActions.FixOutcome outcome =
                await(actions.askToFix(scope(Optional.of(SESSION)), "src/Main.java", findings));

        assertEquals(ReviewTriageActions.FixOutcome.State.FAILED, outcome.state());
        assertTrue(outcome.findings().isEmpty());
        assertEquals(AnnotationStatus.OPEN, stored("f1").status());
    }

    // --- typed-outcome invariants (direct construction) ----------------------

    @Test
    void aCommentOutcomeRejectsANullState() {
        assertThrows(NullPointerException.class,
                () -> new ReviewTriageActions.CommentOutcome(null, null));
        assertThrows(NullPointerException.class,
                () -> new ReviewTriageActions.CommentOutcome(null, finding("f1", Triage.PROPOSED, false)));
    }

    @Test
    void aCommentOutcomeCarriesAFindingExactlyOnAppended() {
        ReviewAnnotation annotation = finding("f1", Triage.PROPOSED, false);
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewTriageActions.CommentOutcome(
                        ReviewTriageActions.CommentOutcome.State.APPENDED, null),
                "APPENDED must carry the updated finding");
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewTriageActions.CommentOutcome(
                        ReviewTriageActions.CommentOutcome.State.BLANK, annotation),
                "BLANK carried nothing, so its finding must be null");
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewTriageActions.CommentOutcome(
                        ReviewTriageActions.CommentOutcome.State.MISSING, annotation),
                "MISSING carried nothing, so its finding must be null");
        assertEquals(annotation,
                new ReviewTriageActions.CommentOutcome(
                        ReviewTriageActions.CommentOutcome.State.APPENDED, annotation).finding());
        assertNull(new ReviewTriageActions.CommentOutcome(
                ReviewTriageActions.CommentOutcome.State.BLANK, null).finding());
    }

    @Test
    void aFixOutcomeRejectsANullStateAndANullList() {
        assertThrows(NullPointerException.class,
                () -> new ReviewTriageActions.FixOutcome(
                        null, List.of(finding("f1", Triage.CONFIRMED, true))));
        assertThrows(NullPointerException.class,
                () -> new ReviewTriageActions.FixOutcome(
                        ReviewTriageActions.FixOutcome.State.DELIVERED, null));
    }

    @Test
    void aFixOutcomeForbidsFindingsOutsideDeliveredAndAllowsAnEmptyDelivered() {
        // DELIVERED can legitimately be empty: every handed-off finding can
        // disappear from the store before its SENT mutation lands.
        ReviewTriageActions.FixOutcome emptyDelivered = new ReviewTriageActions.FixOutcome(
                ReviewTriageActions.FixOutcome.State.DELIVERED, List.of());
        assertEquals(ReviewTriageActions.FixOutcome.State.DELIVERED, emptyDelivered.state());
        assertTrue(emptyDelivered.findings().isEmpty());
        for (ReviewTriageActions.FixOutcome.State state : ReviewTriageActions.FixOutcome.State.values()) {
            if (state == ReviewTriageActions.FixOutcome.State.DELIVERED) {
                continue;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new ReviewTriageActions.FixOutcome(state,
                            List.of(finding("f1", Triage.CONFIRMED, true))),
                    "only DELIVERED carries findings, not " + state);
        }
    }

    @Test
    void aFixOutcomeDefensivelyCopiesTheHandedList() {
        ReviewAnnotation annotation = finding("f1", Triage.CONFIRMED, true);
        List<ReviewAnnotation> handed = new ArrayList<>();
        handed.add(annotation);

        ReviewTriageActions.FixOutcome outcome = new ReviewTriageActions.FixOutcome(
                ReviewTriageActions.FixOutcome.State.DELIVERED, handed);
        handed.clear();

        assertEquals(List.of(annotation), outcome.findings(),
                "mutating the caller's list must not leak into the outcome");
        assertThrows(UnsupportedOperationException.class,
                () -> outcome.findings().add(annotation),
                "the outcome's list is unmodifiable");
    }

    // --- threading and failures (AC8, never-pretend) --------------------------

    @Test
    void everyStoreMutationRunsOnTheInjectedExecutorNotTheCallerThread() {
        store.upsert(finding("f1", Triage.PROPOSED, false));
        List<Thread> mutationThreads = new CopyOnWriteArrayList<>();
        store.addChangeListener(key -> mutationThreads.add(Thread.currentThread()));

        await(actions.confirm(new ReviewAnnotation.Key("rs_a", "f1")));
        await(actions.comment(new ReviewAnnotation.Key("rs_a", "f1"), "note"));
        await(actions.askToFix(scope(Optional.of(SESSION)), "src/Main.java",
                List.of(stored("f1"))));

        assertFalse(executor.threads.isEmpty(), "the work must have gone through the executor");
        for (Thread thread : executor.threads) {
            assertEquals("triage-test-worker", thread.getName());
        }
        assertFalse(mutationThreads.isEmpty(), "mutations happened");
        for (Thread thread : mutationThreads) {
            assertEquals("triage-test-worker", thread.getName(),
                    "store mutations must run on the injected executor");
        }
        assertEquals(executor.threads.size(), mutationThreads.size(),
                "every executor command here was one store mutation");
    }

    @Test
    void aThrowingSubmitterFailsTheFutureAndMarksNothing() {
        List<ReviewAnnotation> findings = List.of(finding("f1", Triage.CONFIRMED, true));
        findings.forEach(store::upsert);
        ReviewTriageActions broken = new ReviewTriageActions(store, executor,
                (session, prompt) -> {
                    throw new IllegalStateException("registry is gone");
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        CompletableFuture<ReviewTriageActions.FixOutcome> future = broken.askToFix(
                scope(Optional.of(SESSION)), "src/Main.java", findings);
        ExecutionException failure =
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals(AnnotationStatus.OPEN, stored("f1").status(), "a failure must mark nothing");
    }

    @Test
    void aDisposedStoreRefusesLoudlyInsteadOfPretendingSuccess() {
        store.upsert(finding("f1", Triage.PROPOSED, false));
        store.close();

        CompletableFuture<Optional<ReviewAnnotation>> future =
                actions.confirm(new ReviewAnnotation.Key("rs_a", "f1"));
        ExecutionException failure =
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof RejectedExecutionException,
                "a closed store's save executor must refuse, not report success");
    }
}
