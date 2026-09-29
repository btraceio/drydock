package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One automatic recheck per base move, not one per render.
 *
 * <p>The store cannot answer this on its own: {@link
 * AnnotationStore#assessedAffected} returns false both for "assessed
 * unaffected" and for "never asked", so it cannot see a dispatch that has
 * gone out and not yet come back. Every re-render inside that window would
 * dispatch again. This is the record that closes it.</p>
 */
class RecheckDispatchTest {

    @Test
    void theFirstClaimOnAMoveSucceeds() {
        assertTrue(new RecheckDispatch().claim("scope-1", "a1b2c3", "d4e5f6"));
    }

    /** The window the store cannot see: dispatched, nothing assessed yet. */
    @Test
    void aSecondClaimOnTheSameMoveIsRefused() {
        RecheckDispatch dispatch = new RecheckDispatch();

        assertTrue(dispatch.claim("scope-1", "a1b2c3", "d4e5f6"));
        assertFalse(dispatch.claim("scope-1", "a1b2c3", "d4e5f6"));
    }

    /** Keyed by the base PAIR, so a later move is a new question. */
    @Test
    void aLaterBaseMoveIsANewQuestion() {
        RecheckDispatch dispatch = new RecheckDispatch();
        dispatch.claim("scope-1", "a1b2c3", "d4e5f6");

        assertTrue(dispatch.claim("scope-1", "d4e5f6", "999aaa"));
    }

    /**
     * Varies ONLY the destination. A move to a further base is a different
     * question about the same approval, and a key that dropped {@code
     * toBase} would call it already answered.
     */
    @Test
    void theSameStartingBaseMovingSomewhereElseIsANewQuestion() {
        RecheckDispatch dispatch = new RecheckDispatch();
        dispatch.claim("scope-1", "a1b2c3", "d4e5f6");

        assertTrue(dispatch.claim("scope-1", "a1b2c3", "999aaa"));
    }

    /**
     * Varies ONLY the origin. Two approvals in one scope can have been
     * recorded against different bases and now face the same current one --
     * two distinct moves, each owed its own recheck.
     */
    @Test
    void twoApprovalsWithDifferentRecordedBasesAreSeparateQuestions() {
        RecheckDispatch dispatch = new RecheckDispatch();
        dispatch.claim("scope-1", "a1b2c3", "999aaa");

        assertTrue(dispatch.claim("scope-1", "d4e5f6", "999aaa"));
    }

    @Test
    void aDifferentScopeClaimsIndependently() {
        RecheckDispatch dispatch = new RecheckDispatch();
        dispatch.claim("scope-1", "a1b2c3", "d4e5f6");

        assertTrue(dispatch.claim("scope-2", "a1b2c3", "d4e5f6"));
    }

    /**
     * A hand-off that did not happen must not be remembered as done, or the
     * scope never gets its recheck at all. Every existing caller of {@code
     * sendToBoundSession} checks its boolean for this reason.
     */
    @Test
    void releasingAFailedHandOffAllowsARetry() {
        RecheckDispatch dispatch = new RecheckDispatch();
        assertTrue(dispatch.claim("scope-1", "a1b2c3", "d4e5f6"));

        dispatch.release("scope-1", "a1b2c3", "d4e5f6");

        assertTrue(dispatch.claim("scope-1", "a1b2c3", "d4e5f6"));
    }

    /**
     * The three parts are joined, so a separator that could appear inside one
     * of them would let two different moves collide on one key. Scope handles
     * are arbitrary strings; commits are not.
     */
    @Test
    void movesThatDifferOnlyInWhereTheirPartsSplitDoNotCollide() {
        RecheckDispatch dispatch = new RecheckDispatch();
        assertTrue(dispatch.claim("s", "a-b", "c"));

        assertTrue(dispatch.claim("s-a", "b", "c"));
    }
}
