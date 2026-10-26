package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.FindingsPrompt;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Triage;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The write side of findings triage (spec P3, AC2–AC4): Confirm, Dismiss,
 * Undo, human comments and ask-the-agent-to-fix, all asynchronous over an
 * injected {@link AnnotationStore}, executor, clock and prompt submitter.
 *
 * <p>Every store mutation and every prompt submission runs on the injected
 * executor (the spec's threading table); nothing here touches Swing or the
 * EDT, so the same instance serves tests headlessly. Each operation returns
 * a future carrying a typed outcome -- never a boolean that could pretend a
 * failure was a success -- and a failed operation completes the future
 * exceptionally instead of reporting a made-up result. A store used after
 * {@link AnnotationStore#close()} refuses loudly: the background save
 * executor is gone, so the mutation surfaces as a
 * {@link java.util.concurrent.RejectedExecutionException} on the future.</p>
 *
 * <p>The triage semantics are the app's, mirrored deliberately: Confirm
 * promotes {@code postToPr} only on the transition into CONFIRMED (see
 * {@link #confirmedForPosting}), a comment is a
 * {@code Message(author="You")} appended to the finding's thread, and only
 * a {@link PromptOutcome#DELIVERED} hand-off marks findings
 * {@link AnnotationStatus#SENT} -- the plugin's stricter rule; the app
 * accepts more hand-off states, the plugin only counts a written prompt.</p>
 */
public final class ReviewTriageActions {

    private final AnnotationStore store;
    private final Executor executor;
    private final BiFunction<ManagedSessionId, String, PromptOutcome> promptSubmitter;
    private final Clock clock;

    public ReviewTriageActions(AnnotationStore store, Executor executor,
                               BiFunction<ManagedSessionId, String, PromptOutcome> promptSubmitter,
                               Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.promptSubmitter = Objects.requireNonNull(promptSubmitter, "promptSubmitter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- triage (AC2) --------------------------------------------------------

    /**
     * Confirms a finding: triage CONFIRMED, promoting {@code postToPr} when
     * this is the transition into CONFIRMED (a reconfirm can never undo a
     * later explicit opt-out -- {@link #confirmedForPosting}).
     */
    public CompletableFuture<Optional<ReviewAnnotation>> confirm(ReviewAnnotation.Key key) {
        return setTriage(key, Triage.CONFIRMED);
    }

    /** Dismisses a finding: triage DISMISSED, every other field untouched. */
    public CompletableFuture<Optional<ReviewAnnotation>> dismiss(ReviewAnnotation.Key key) {
        return setTriage(key, Triage.DISMISSED);
    }

    /** Undoes a triage decision: back to PROPOSED, every other field untouched. */
    public CompletableFuture<Optional<ReviewAnnotation>> undo(ReviewAnnotation.Key key) {
        return setTriage(key, Triage.PROPOSED);
    }

    /**
     * Sets a finding's triage on the executor. The future's value is the
     * updated finding, empty when no finding lives under {@code key} (the
     * store dropped it, or the caller held a stale card).
     */
    private CompletableFuture<Optional<ReviewAnnotation>> setTriage(ReviewAnnotation.Key key,
                                                                   Triage triage) {
        Objects.requireNonNull(key, "key");
        return onExecutor(() -> store.mutate(key, current -> confirmedForPosting(current, triage)));
    }

    /**
     * The app's {@code MainWorkspace.confirmedForPosting}, mirrored exactly:
     * only the transition into CONFIRMED promotes {@code postToPr} from
     * false, because a vouched-for finding that silently drops out of the
     * posted review was the complaint the default fixes -- while a redundant
     * confirm of an already-confirmed finding must not silently undo the
     * human's later opt-out. Static and pure so the rule is testable without
     * a workspace; keep this body and the app's in lockstep.
     */
    static ReviewAnnotation confirmedForPosting(ReviewAnnotation current, Triage triage) {
        ReviewAnnotation next = current.withTriage(triage);
        if (triage != Triage.CONFIRMED || current.triage() == Triage.CONFIRMED || current.postToPr()) {
            return next;
        }
        return next.withPostToPr(true);
    }

    // ---- comments (AC3) ------------------------------------------------------

    /**
     * The outcome of a comment submission: what the caller must surface, not
     * a boolean. {@code finding} is the updated annotation for
     * {@link State#APPENDED} and {@code null} otherwise. The compact
     * constructor enforces the pairing directly: a null state, a missing
     * finding on APPENDED or a stray finding on BLANK/MISSING is a
     * programming error, not an outcome.
     */
    public record CommentOutcome(State state, ReviewAnnotation finding) {

        public CommentOutcome {
            Objects.requireNonNull(state, "state");
            if ((state == State.APPENDED) != (finding != null)) {
                throw new IllegalArgumentException(
                        "finding must be present exactly when the comment was APPENDED, "
                                + "not " + state);
            }
        }

        /** What became of the comment. */
        public enum State {
            /** Appended to the thread; {@code finding} carries the result. */
            APPENDED,
            /** Blank input; nothing was written and nothing needs surfacing. */
            BLANK,
            /** No finding under the key; the card was stale. */
            MISSING
        }

        static CommentOutcome appended(ReviewAnnotation finding) {
            return new CommentOutcome(State.APPENDED, Objects.requireNonNull(finding, "finding"));
        }

        static CommentOutcome blank() {
            return new CommentOutcome(State.BLANK, null);
        }

        static CommentOutcome missing() {
            return new CommentOutcome(State.MISSING, null);
        }
    }

    /**
     * Appends the human's comment as {@code Message(author="You")} stamped
     * with the injected clock's now. Blank input is rejected without a
     * mutation; the future completes with {@link CommentOutcome.State#BLANK}.
     */
    public CompletableFuture<CommentOutcome> comment(ReviewAnnotation.Key key, String text) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            return CompletableFuture.completedFuture(CommentOutcome.blank());
        }
        return onExecutor(() -> store.mutate(key, current ->
                        current.withReply(new ReviewAnnotation.Message("You", clock.instant(), text)))
                .map(CommentOutcome::appended)
                .orElseGet(CommentOutcome::missing));
    }

    // ---- ask-to-fix (AC4) ----------------------------------------------------

    /**
     * The outcome of an ask-to-fix: a typed state, never a boolean. The
     * never-pretend rule -- a caller must be able to tell a human precisely
     * why nothing was sent. {@code findings} carries the SENT-marked
     * annotations for {@link State#DELIVERED} and is empty for every other
     * state; DELIVERED may itself be empty, because every handed-off finding
     * can disappear from the store before its SENT mutation lands. The
     * compact constructor enforces exactly that: a null state or list, or
     * findings on a non-DELIVERED state, is a programming error, not an
     * outcome. The list is defensively copied, so it is unmodifiable and
     * detached from whatever the caller passed in.
     */
    public record FixOutcome(State state, List<ReviewAnnotation> findings) {

        public FixOutcome {
            Objects.requireNonNull(state, "state");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            if (state != State.DELIVERED && !findings.isEmpty()) {
                throw new IllegalArgumentException(
                        "only DELIVERED carries findings, not " + state);
            }
        }

        /** What became of the ask. */
        public enum State {
            /** The prompt was written to the session; findings are now SENT. */
            DELIVERED,
            /** The scope has no bound session; nothing could be sent. */
            NO_SESSION,
            /** Nothing to hand over; refused before a prompt was built. */
            EMPTY,
            /** The session refused; nothing was written, nothing marked. */
            REFUSED,
            /** The write failed; the prompt was probably not received. */
            FAILED
        }

        static FixOutcome delivered(List<ReviewAnnotation> findings) {
            return new FixOutcome(State.DELIVERED, findings);
        }

        static FixOutcome of(State state) {
            return new FixOutcome(state, List.of());
        }
    }

    /**
     * Sends the scope's batched decisions to its bound agent in ONE prompt:
     * every unresolved, decided, not-yet-sent finding -- the CONFIRMED ones
     * as the fix list (each carrying the reviewer's last comment), the
     * DISMISSED ones as an explicit no-action list -- through the shared
     * {@link FindingsPrompt#forDecisions} bytes (never forked text),
     * submitted on the executor. One hand-off per review round, never one
     * prompt per click. Every included finding becomes {@link
     * AnnotationStatus#SENT} only when the submitter answers {@link
     * PromptOutcome#DELIVERED} -- REFUSED, FAILED, a missing bound session
     * and an empty batch are distinct outcomes that mark nothing, so a
     * refused send can simply be retried. A submitter that throws completes
     * the future exceptionally; nothing is marked either way.
     */
    /**
     * Whether {@code finding} is a pending decision: unresolved, decided
     * (CONFIRMED or DISMISSED) and not yet carried by a hand-off. The one
     * filter both the review tab's decisions bar
     * ({@code ReviewTabProvider#pendingDecisions}) and {@link #sendDecisions}
     * count through, so the bar can never advertise a batch the send would
     * refuse. Pure read — any thread.
     */
    static boolean isPendingDecision(ReviewAnnotation finding) {
        return !finding.resolved()
                && finding.triage() != Triage.PROPOSED
                && finding.status() != AnnotationStatus.SENT;
    }

    public CompletableFuture<FixOutcome> sendDecisions(ReviewScope scope, String subject) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(subject, "subject");
        Optional<ManagedSessionId> session = scope.sessionId();
        if (session.isEmpty()) {
            return CompletableFuture.completedFuture(FixOutcome.of(FixOutcome.State.NO_SESSION));
        }
        ManagedSessionId sessionId = session.get();
        return onExecutor(() -> {
            // Membership is read here, off the EDT, at send time: decisions
            // taken after the bar's last refresh are still included, and
            // already-SENT findings are never sent twice.
            List<ReviewAnnotation> decided = store.forScope(scope.id()).stream()
                    .filter(ReviewTriageActions::isPendingDecision)
                    .toList();
            if (decided.isEmpty()) {
                // The app's rule: an empty hand-off is refused outright,
                // before a prompt is even built -- nothing sent, nothing marked.
                return FixOutcome.of(FixOutcome.State.EMPTY);
            }
            List<ReviewAnnotation> toFix = decided.stream()
                    .filter(finding -> finding.triage() == Triage.CONFIRMED).toList();
            List<ReviewAnnotation> dismissed = decided.stream()
                    .filter(finding -> finding.triage() == Triage.DISMISSED).toList();
            PromptOutcome outcome = promptSubmitter.apply(sessionId,
                    FindingsPrompt.forDecisions(subject, toFix, dismissed));
            return switch (outcome) {
                case DELIVERED -> {
                    List<ReviewAnnotation> sent = new ArrayList<>(decided.size());
                    for (ReviewAnnotation finding : decided) {
                        // A finding another writer removed mid-flight is
                        // skipped, not resurrected; the rest are still SENT.
                        store.mutate(finding.key(),
                                current -> current.withStatus(AnnotationStatus.SENT))
                                .ifPresent(sent::add);
                    }
                    yield FixOutcome.delivered(sent);
                }
                case REFUSED -> FixOutcome.of(FixOutcome.State.REFUSED);
                case FAILED -> FixOutcome.of(FixOutcome.State.FAILED);
            };
        });
    }

    // ---- execution ----------------------------------------------------------

    /**
     * Runs {@code work} on the injected executor and completes the future
     * with its result; a thrown failure completes the future exceptionally
     * instead of being swallowed into a made-up outcome.
     */
    private <T> CompletableFuture<T> onExecutor(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(work.get());
            } catch (RuntimeException | Error e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
