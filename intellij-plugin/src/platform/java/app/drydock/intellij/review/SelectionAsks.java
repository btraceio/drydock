package app.drydock.intellij.review;

import app.drydock.review.AnnotationStore;
import app.drydock.review.DiffSelection;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The questions the reviewer asked about diff selections ("Ask Reviewer
 * About Selection…"), by question id, until the agent answers them through
 * {@code review_ask_answer}. {@link PendingQuestions} records no file range,
 * so the range and the question's text are kept here. An answer becomes a
 * human finding at the range whose thread is the question then the answer,
 * awaiting triage: an answered question is information, and the reviewer
 * decides whether it becomes a fix request. In memory only, like the
 * pending questions themselves. Thread-safe: answers arrive on the MCP
 * thread.
 */
final class SelectionAsks {

    /** The author an answer's message carries. */
    static final String ANSWER_AUTHOR = "Agent";

    /** One asked question: the scope, the range, the text, when it was asked. */
    record Asked(String scopeId, DiffSelection.Range range, String question, Instant at) {
        Asked {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(at, "at");
        }
    }

    private final AnnotationStore store;
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();

    SelectionAsks(AnnotationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    void remember(String questionId, Asked question) {
        asked.put(Objects.requireNonNull(questionId, "questionId"), Objects.requireNonNull(question, "question"));
    }

    /** Forgets a question that never reached the agent. */
    void forget(String questionId) {
        asked.remove(questionId);
    }

    /**
     * The pending questions' answer callback: an answer to a remembered
     * selection question becomes its finding; an answer to any other question
     * (an ask this registry did not make) changes nothing here.
     */
    void answered(PendingQuestions.AnsweredAsk answered) {
        Asked question = asked.remove(answered.ask().questionId());
        if (question != null) {
            store.upsert(findingFor(question, answered.answer(), answered.answeredAt()));
        }
    }

    /** The finding an answered question becomes (pure). */
    static ReviewAnnotation findingFor(Asked question, String answer, Instant answeredAt) {
        DiffSelection.Range range = question.range();
        return ReviewAnnotation.human(question.scopeId(), range.file(), range.startKey(), range.endKey(),
                        new ReviewAnnotation.Message("You", question.at(), question.question()))
                .withPostToPr(false)
                .withTriage(Triage.PROPOSED)
                .withReply(new ReviewAnnotation.Message(ANSWER_AUTHOR, answeredAt, answer));
    }

    /** The question remembered under {@code questionId}, if it is still unanswered. */
    Optional<Asked> pending(String questionId) {
        return Optional.ofNullable(asked.get(questionId));
    }
}
