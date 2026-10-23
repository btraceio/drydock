package app.drydock.intellij.review;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Review tab's placeholder states (AC22's model half): every state
 * renders its own message, and every terminal state — including the
 * failure and empty ones — replaces the loading placeholder, so nothing
 * stays stranded behind it. Pure JUnit on the enum: no IDE, no threading.
 */
class ReviewTabStatusTest {

    @Test
    void everyAc22StateRendersItsOwnMessage() {
        List<ReviewTabStatus> ac22 = List.of(
                ReviewTabStatus.BACKEND_NOT_READY,
                ReviewTabStatus.NO_SESSION,
                ReviewTabStatus.NOT_GIT,
                ReviewTabStatus.DIFF_FAILED,
                ReviewTabStatus.EMPTY_DIFF);

        long distinct = ac22.stream().map(ReviewTabStatus::message).distinct().count();
        assertEquals(ac22.size(), distinct, "each AC22 state must have its own message");
        for (ReviewTabStatus state : ac22) {
            assertFalse(state.message().isBlank(), state + " must render a message");
        }
    }

    @Test
    void everyTerminalStateReplacesTheLoadingPlaceholder() {
        for (ReviewTabStatus state : ReviewTabStatus.values()) {
            if (state == ReviewTabStatus.LOADING) {
                assertFalse(state.terminal(), "LOADING is the placeholder, not a replacer");
            } else {
                assertTrue(state.terminal(), state + " must clear the loading placeholder");
            }
        }
    }

    @Test
    void loadingIsAVisiblePlaceholder() {
        assertEquals("Loading review…", ReviewTabStatus.LOADING.message());
        assertFalse(ReviewTabStatus.LOADING.message().isBlank());
    }

    @Test
    void readyRendersNothingBecauseTheTreeReplacesThePlaceholder() {
        assertTrue(ReviewTabStatus.READY.message().isBlank());
        assertTrue(ReviewTabStatus.READY.terminal());
    }

    @Test
    void diffFailedCarriesTheCauseMessage() {
        assertEquals("Could not compute the diff: git blew up",
                ReviewTabStatus.DIFF_FAILED.message(new IllegalStateException("git blew up")));
    }

    @Test
    void diffFailedUnwrapsTheCompletionExceptionTheDiffFutureFailsWith() {
        // DiffService.diff fails its future with the GitException wrapped
        // in a CompletionException; the rendered text names the git failure.
        CompletionException wrapped = new CompletionException(new IllegalStateException("exit 128"));
        assertEquals("Could not compute the diff: exit 128",
                ReviewTabStatus.DIFF_FAILED.message(wrapped));
    }

    @Test
    void diffFailedNamesTheCauseClassWhenItHasNoMessage() {
        assertEquals("Could not compute the diff: NullPointerException",
                ReviewTabStatus.DIFF_FAILED.message(new CompletionException(new NullPointerException())));
    }

    @Test
    void otherStatesIgnoreTheCause() {
        for (ReviewTabStatus state : Arrays.stream(ReviewTabStatus.values())
                .filter(s -> s != ReviewTabStatus.DIFF_FAILED).toList()) {
            assertEquals(state.message(),
                    state.message(new IllegalStateException("irrelevant")),
                    state + " ignores the cause");
        }
    }

    /**
     * AC15: the changed-since notice survives the re-run's own LOADING
     * report (which the reload posts right after it) and is replaced only
     * by the fresh result's terminal state.
     */
    @Test
    void theChangedSinceNoticeSurvivesTheReloadsLoadingReportUntilTheFreshResult() {
        assertEquals(ReviewTabStatus.CHANGED_SINCE_MESSAGE, ReviewTabStatus.placeholderText(
                ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message(), true));
        assertEquals(ReviewTabStatus.LOADING.message(), ReviewTabStatus.placeholderText(
                ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message(), false));
        for (ReviewTabStatus terminal : Arrays.stream(ReviewTabStatus.values())
                .filter(s -> s != ReviewTabStatus.LOADING).toList()) {
            assertEquals(terminal.message(), ReviewTabStatus.placeholderText(terminal, terminal.message(), true),
                    terminal + " (the fresh result) replaces the notice");
        }
    }
}
