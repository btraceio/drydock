package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingQuestionsTest {

    private static final ManagedSessionId SESSION = ManagedSessionId.newId();
    private static final ManagedSessionId OTHER_SESSION = ManagedSessionId.newId();


    @Test
    void anAnswerClosesItsQuestionAndNotifiesTheListener() {
        AtomicReference<PendingQuestions.AnsweredAsk> delivered = new AtomicReference<>();
        PendingQuestions questions = new PendingQuestions(delivered::set);
        PendingQuestions.PendingAsk ask = questions.mint("rs_1", "loadConfig", SESSION);

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
        PendingQuestions.PendingAsk ask = questions.mint("rs_1", "loadConfig", SESSION);

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
        PendingQuestions.PendingAsk mine = questions.mint("rs_mine", "loadConfig", SESSION);

        assertTrue(questions.answer(mine.questionId(), ask -> false, "not my scope").isEmpty());
        assertEquals(1, questions.size(), "a rejected answer must not consume the question");

        assertTrue(questions.answer(mine.questionId(), ask -> true, "the session it was asked of").isPresent());
    }

    /**
     * The ask dies with the session it was asked of -- and only with that
     * one: expiring one session's questions must not touch another's.
     */
    @Test
    void aSessionExitExpiresOnlyThatSessionsAsks() {
        PendingQuestions questions = new PendingQuestions(null);
        PendingQuestions.PendingAsk mine = questions.mint("rs_1", "a", SESSION);
        PendingQuestions.PendingAsk theirs = questions.mint("rs_2", "b", OTHER_SESSION);

        java.util.List<PendingQuestions.PendingAsk> expired = questions.expireSession(SESSION);

        assertEquals(java.util.List.of(mine), expired, "the dying session's ask is handed back for a death notice");
        assertEquals(1, questions.size(), "the other session's ask survives");
        assertTrue(questions.answer(mine.questionId(), any -> true, "late").isEmpty(),
                "a late answer to a dead question is refused as unknown -- no resurrection");
        assertTrue(questions.answer(theirs.questionId(), any -> true, "still answerable").isPresent(),
                "the surviving session's ask is untouched by the other's death");
    }

    @Test
    void theRegistryIsBounded() {
        PendingQuestions questions = new PendingQuestions(null);
        for (int i = 0; i < PendingQuestions.MAX_PENDING + 5; i++) {
            questions.mint("rs_1", "sym" + i, SESSION);
        }

        assertEquals(PendingQuestions.MAX_PENDING, questions.size(),
                "an agent that never answers must not grow the registry without limit");
    }

    @Test
    void idsAreSequentialAndCarryTheirQuestion() {
        PendingQuestions questions = new PendingQuestions(null);
        PendingQuestions.PendingAsk first = questions.mint("rs_1", "a", SESSION);
        PendingQuestions.PendingAsk second = questions.mint("rs_2", "b", SESSION);

        assertTrue(first.questionId().startsWith("ask-"));
        assertTrue(first.askedAt().isBefore(Instant.now().plusSeconds(1)));
        assertTrue(questions.answer(first.questionId(), ask -> "rs_1".equals(ask.scopeId()), "ok").isPresent(),
                "the scope predicate sees the ask's scope");
        assertTrue(questions.answer(second.questionId(), ask -> "rs_1".equals(ask.scopeId()), "no").isEmpty(),
                "the second question's scope does not satisfy rs_1");
    }
}
