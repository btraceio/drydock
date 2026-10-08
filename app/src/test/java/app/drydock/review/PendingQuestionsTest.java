package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingQuestionsTest {

    @Test
    void anAnswerClosesItsQuestionAndNotifiesTheListener() {
        AtomicReference<PendingQuestions.AnsweredAsk> delivered = new AtomicReference<>();
        PendingQuestions questions = new PendingQuestions(delivered::set);
        PendingQuestions.PendingAsk ask = questions.mint("rs_1", "loadConfig");

        Optional<PendingQuestions.AnsweredAsk> answered =
                questions.answer(ask.questionId(), any -> true, "It memoizes the parser.");

        assertTrue(answered.isPresent());
        assertEquals("loadConfig", answered.get().ask().symbol());
        assertEquals("It memoizes the parser.", answered.get().answer());
        assertEquals("loadConfig", delivered.get().ask().symbol(),
                "the listener sees the same answer the caller gets");
        assertEquals(0, questions.size(), "an answered question is no longer pending");
    }

    @Test
    void aQuestionAnswersOnce() {
        PendingQuestions questions = new PendingQuestions(null);
        PendingQuestions.PendingAsk ask = questions.mint("rs_1", "loadConfig");

        assertTrue(questions.answer(ask.questionId(), any -> true, "first").isPresent());
        assertTrue(questions.answer(ask.questionId(), any -> true, "second").isEmpty(),
                "the second answer must not replace or duplicate the first -- the board already showed it");
    }

    @Test
    void anUnknownIdAnswersNothing() {
        PendingQuestions questions = new PendingQuestions(null);

        assertTrue(questions.answer("ask-999", any -> true, "guess").isEmpty());
    }

    /**
     * The authorization split: a caller the predicate rejects gets nothing,
     * and the question STAYS pending -- the session it was actually asked
     * of can still answer it. Rejecting by consuming the question would let
     * one session burn another's questions.
     */
    @Test
    void aDisallowedAnswerLeavesTheQuestionPending() {
        PendingQuestions questions = new PendingQuestions(null);
        PendingQuestions.PendingAsk mine = questions.mint("rs_mine", "loadConfig");

        assertTrue(questions.answer(mine.questionId(), ask -> false, "not my scope").isEmpty());
        assertEquals(1, questions.size(), "a rejected answer must not consume the question");

        assertTrue(questions.answer(mine.questionId(), ask -> true, "the session it was asked of").isPresent());
    }

    @Test
    void theRegistryIsBounded() {
        PendingQuestions questions = new PendingQuestions(null);
        for (int i = 0; i < PendingQuestions.MAX_PENDING + 5; i++) {
            questions.mint("rs_1", "sym" + i);
        }

        assertEquals(PendingQuestions.MAX_PENDING, questions.size(),
                "an agent that never answers must not grow the registry without limit");
    }

    @Test
    void idsAreSequentialAndCarryTheirQuestion() {
        PendingQuestions questions = new PendingQuestions(null);
        PendingQuestions.PendingAsk first = questions.mint("rs_1", "a");
        PendingQuestions.PendingAsk second = questions.mint("rs_2", "b");

        assertTrue(first.questionId().startsWith("ask-"));
        assertTrue(first.askedAt().isBefore(Instant.now().plusSeconds(1)));
        assertTrue(questions.answer(first.questionId(), ask -> "rs_1".equals(ask.scopeId()), "ok").isPresent(),
                "the scope predicate sees the ask's scope");
        assertTrue(questions.answer(second.questionId(), ask -> "rs_1".equals(ask.scopeId()), "no").isEmpty(),
                "the second question's scope does not satisfy rs_1");
    }
}
