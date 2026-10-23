package app.drydock.intellij.review;

import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * The Review tab's placeholder states (AC22): one per thing that can keep
 * the tree from showing, each with the message it renders.
 *
 * <p>This is a pure value type — no platform class, no callback — so the
 * whole state surface is testable headless. The refresher (and the
 * provider) report a state and the text to render; every state except
 * {@link #LOADING} is terminal in the sense that it <em>replaces</em> the
 * loading placeholder: nothing stays stranded behind a spinner
 * ("every completion path clears the progress state", repository rule).</p>
 *
 * <p>{@link #DIFF_FAILED}'s rendered text carries the failure's own
 * message ({@link #message(Throwable)}); the failure itself, with the git
 * stderr excerpt its exception carries, is logged at WARNING by {@link
 * ReviewTabRefresher} ({@code DiffService} only throws), so the full
 * excerpt never needs to reach the UI. {@link #READY} renders nothing — the tree
 * replaces the placeholder outright.</p>
 */
public enum ReviewTabStatus {

    /** The first-load placeholder: shown before any background work starts. */
    LOADING("Loading review…"),

    /** The review backend has not started (or has closed). */
    BACKEND_NOT_READY("Drydock review is not ready"),

    /** No Drydock session covers this checkout. */
    NO_SESSION("No Drydock session for this checkout"),

    /** The directory is not a git checkout. */
    NOT_GIT("This directory is not a git checkout"),

    /** The diff run failed; the text carries the cause's message. */
    DIFF_FAILED("Could not compute the diff"),

    /** The diff ran but there is nothing in it. */
    EMPTY_DIFF("No changes to review"),

    /** The tree is up to date; the placeholder is gone, so there is nothing to render. */
    READY("");

    /**
     * AC15's notice: the selected file changed after the diff was taken, so
     * its fragments were not fed and a re-run is under way.
     */
    public static final String CHANGED_SINCE_MESSAGE = "Changed since the diff was computed — reloading";

    private final String message;

    ReviewTabStatus(String message) {
        this.message = message;
    }

    /**
     * The text this state renders. Blank for {@link #READY}: the snapshot
     * replaces the placeholder, so ready has no message of its own.
     */
    public String message() {
        return message;
    }

    /**
     * The text this state renders when a failure caused it. Only
     * {@link #DIFF_FAILED} uses the cause — its text is
     * {@code "Could not compute the diff: <cause message>"} — every other
     * state ignores it. A {@link CompletionException} (how
     * {@code DiffService.diff} fails) is unwrapped to its cause first.
     */
    public String message(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        if (this != DIFF_FAILED) {
            return message;
        }
        return message + ": " + rootMessage(cause);
    }

    /**
     * Whether this state replaces the loading placeholder (AC22: "every one
     * clears the loading placeholder"). Only {@link #LOADING} is not
     * terminal — it is the placeholder the others replace.
     */
    public boolean terminal() {
        return this != LOADING;
    }

    /**
     * The text the tab's status area shows for a refresh report. While
     * AC15's changed-since notice is pending, the re-run's own
     * {@link #LOADING} report keeps {@link #CHANGED_SINCE_MESSAGE} on
     * screen instead of the plain loading text — the notice is replaced
     * only by the fresh result's terminal state. Pure.
     */
    static String placeholderText(ReviewTabStatus state, String text, boolean changedSincePending) {
        if (state == LOADING && changedSincePending) {
            return CHANGED_SINCE_MESSAGE;
        }
        return text;
    }

    /** The cause's own message, unwrapping the future's wrapper; a cause without one is named by class. */
    private static String rootMessage(Throwable cause) {
        Throwable t = cause;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
