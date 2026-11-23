package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.review.AnnotationStore;
import app.drydock.review.DiffSelection;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SelectionAsks}: an answered selection question becomes a finding to triage at the asked range. */
class SelectionAsksTest {

    private static final DiffSelection.Range RANGE = new DiffSelection.Range("src/A.java", "n10", "n12", false);
    private static final Instant ASKED = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant ANSWERED = Instant.parse("2026-11-03T10:01:00Z");
    private static final ManagedSessionId SESSION = ManagedSessionId.of("b0c3a1e4-0000-4000-8000-000000000001");

    @TempDir
    Path temp;
    private AnnotationStore store;
    private SelectionAsks asks;

    @BeforeEach
    void setUp() {
        store = new AnnotationStore(temp.resolve("annotations.json"));
        asks = new SelectionAsks(store);
    }

    @AfterEach
    void tearDown() {
        store.flushPendingSaves();
        store.close();
    }

    @Test
    void anAnswerBecomesAFindingToTriageWithTheQuestionThenTheAnswer() {
        ReviewAnnotation finding = SelectionAsks.findingFor(
                new SelectionAsks.Asked("rs_a", RANGE, "Why synchronized?", ASKED), "It is shared.", ANSWERED);

        assertEquals("rs_a", finding.scopeId());
        assertEquals("src/A.java", finding.file());
        assertEquals("n10", finding.startKey());
        assertEquals("n12", finding.endKey());
        assertEquals(Triage.PROPOSED, finding.triage(), "information to triage, not an instruction yet");
        assertFalse(finding.postToPr());
        assertEquals(List.of("You: Why synchronized?", SelectionAsks.ANSWER_AUTHOR + ": It is shared."),
                finding.thread().stream().map(m -> m.author() + ": " + m.text()).toList());
    }

    @Test
    void theRealPendingQuestionsDeliverAnAnswerIntoTheStore() {
        PendingQuestions questions = new PendingQuestions(asks::answered);
        PendingQuestions.PendingAsk ask = questions.mint("rs_a", "src/A.java:n10..n12", SESSION);
        asks.remember(ask.questionId(), new SelectionAsks.Asked("rs_a", RANGE, "Why synchronized?", ASKED));

        questions.answer(ask.questionId(), pending -> true, "It is shared.");

        List<ReviewAnnotation> stored = store.forScope("rs_a");
        assertEquals(1, stored.size());
        assertEquals("It is shared.", stored.get(0).thread().get(1).text());
        assertTrue(asks.pending(ask.questionId()).isEmpty(), "answered: no longer pending");
    }

    @Test
    void anAnswerToAnotherOrAForgottenQuestionChangesNothing() {
        PendingQuestions questions = new PendingQuestions(asks::answered);
        PendingQuestions.PendingAsk other = questions.mint("rs_a", "peek", SESSION);
        PendingQuestions.PendingAsk forgotten = questions.mint("rs_a", "src/A.java:n10..n12", SESSION);
        asks.remember(forgotten.questionId(), new SelectionAsks.Asked("rs_a", RANGE, "q", ASKED));
        asks.forget(forgotten.questionId());

        questions.answer(other.questionId(), pending -> true, "a");
        questions.answer(forgotten.questionId(), pending -> true, "a");

        assertTrue(store.forScope("rs_a").isEmpty());
    }

    @Test
    void theDeliveryMessagesSayWhetherAnythingWasAsked() {
        assertTrue(ReviewTabProvider.askMessage(PromptOutcome.DELIVERED).contains("finding to triage"));
        assertTrue(ReviewTabProvider.askMessage(PromptOutcome.REFUSED).endsWith("nothing was asked."));
        assertTrue(ReviewTabProvider.askMessage(PromptOutcome.FAILED).endsWith("nothing was asked."));
    }
}
