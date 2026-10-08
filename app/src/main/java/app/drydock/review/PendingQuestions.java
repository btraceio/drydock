package app.drydock.review;

import app.drydock.domain.ManagedSessionId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The peek questions this app run has asked an agent and is still waiting
 * for an answer to (spec §4, "ask the agent during review").
 *
 * <p>The question itself is typed into the agent's terminal ({@code
 * sendPrompt}), so the agent's conversational answer would land in that
 * terminal -- off the review board where the question was asked. The
 * registry is the other half of that contract: the prompt names a question
 * id and asks the agent to deliver through the {@code review_ask_answer}
 * tool, which resolves the id here and hands the text back to the asking
 * view. Mirrors {@code review_answer} resolving a finding id into its
 * thread, the same request/response correlation the RISK-check verdict
 * flow uses.</p>
 *
 * <p>Deliberately in-memory: a pending ask is tied to one app run's live
 * sessions, not to anything a restart must reconstruct. Unanswered asks
 * die with the run, exactly as a typed question the agent never got to
 * does.</p>
 */
public final class PendingQuestions {

    /**
     * One question still waiting for its answer. The session it was asked
     * of is part of the question: an ask dies when that session exits, not
     * when any other thing about the app changes.
     */
    public record PendingAsk(String questionId, String scopeId, String symbol,
                             ManagedSessionId sessionId, Instant askedAt) {
    }

    /** A question and the answer that closed it. */
    public record AnsweredAsk(PendingAsk ask, String answer, Instant answeredAt) {
    }

    /**
     * Unanswered asks kept at once. Bounded so an agent (or a bug) that
     * never answers cannot grow the registry without limit; the eviction
     * drops the OLDEST, and the asking view never held a handle it could
     * rely on anyway -- it was told "the answer will appear here" and the
     * notice is the only waiting state there is.
     */
    static final int MAX_PENDING = 32;

    private final Map<String, PendingAsk> pending = new LinkedHashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final Consumer<AnsweredAsk> onAnswered;

    /**
     * @param onAnswered invoked with every answered ask, on the thread that
     *                   answered -- the MCP thread, so a UI listener must
     *                   do its own {@code Platform.runLater} hop; may be
     *                   {@code null} for a registry nobody listens to
     *                   (tests)
     */
    public PendingQuestions(Consumer<AnsweredAsk> onAnswered) {
        this.onAnswered = onAnswered;
    }

    /** Records a fresh question and returns the id its answer must name. */
    public synchronized PendingAsk mint(String scopeId, String symbol, ManagedSessionId sessionId) {
        PendingAsk ask = new PendingAsk("ask-" + counter.incrementAndGet(), scopeId, symbol,
                sessionId, Instant.now());
        pending.put(ask.questionId(), ask);
        while (pending.size() > MAX_PENDING) {
            Iterator<PendingAsk> oldest = pending.values().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        return ask;
    }

    /**
     * Closes one question with {@code answer}, atomically, and notifies
     * {@link #onAnswered}. Empty when there is no such pending question --
     * already answered, evicted, or minted in another app run -- and when
     * {@code allowed} rejects the ask, which is how the caller keeps
     * "another session's question" out of this one's hands <em>without
     * committing anything first</em>: a rejected ask stays pending, so the
     * session it was actually asked of can still answer it.
     */
    public synchronized Optional<AnsweredAsk> answer(String questionId, Predicate<PendingAsk> allowed,
                                                    String answer) {
        PendingAsk ask = pending.get(questionId);
        if (ask == null || !allowed.test(ask)) {
            return Optional.empty();
        }
        pending.remove(questionId);
        AnsweredAsk answered = new AnsweredAsk(ask, answer, Instant.now());
        if (onAnswered != null) {
            onAnswered.accept(answered);
        }
        return Optional.of(answered);
    }

    /** How many questions are still open. Visible for tests. */
    public synchronized int size() {
        return pending.size();
    }

    /**
     * Every question asked of the now-exited {@code sessionId}, removed
     * from the registry: the session they were asked of can no longer
     * answer them, so they stop being answerable at all -- a late {@code
     * review_ask_answer} for one of them must be refused as unknown
     * rather than resurrect it.
     */
    public synchronized List<PendingAsk> expireSession(ManagedSessionId sessionId) {
        List<PendingAsk> expired = new ArrayList<>();
        pending.values().removeIf(ask -> {
            if (ask.sessionId().equals(sessionId)) {
                expired.add(ask);
                return true;
            }
            return false;
        });
        return expired;
    }
}
