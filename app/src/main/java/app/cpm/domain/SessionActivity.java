package app.cpm.domain;

/**
 * What a running session's Claude is currently doing, as reported by the
 * hooks installed by {@code app.cpm.claude.ClaudeHookInstaller}.
 *
 * <p>Deliberately orthogonal to {@link SessionStatus}, which models the
 * terminal process lifecycle (launched, exited, failed). The two answer
 * different questions and change on different schedules: a session is
 * {@link SessionStatus#RUNNING} for its entire life while cycling through
 * every value here many times. Folding them into one enum would multiply
 * out to states no caller wants ("exited but busy").</p>
 *
 * <p>Not persisted. Activity is a property of a live process, and a value
 * restored from disk after a restart could only ever be a lie -- the same
 * reasoning that makes {@code SessionManager} normalize stale RUNNING
 * statuses to INACTIVE on load.</p>
 */
public enum SessionActivity {

    /** No live signal: never launched, already exited, or hooks unavailable. */
    UNKNOWN,

    /** Claude finished its turn and is sitting at the prompt. */
    IDLE,

    /** Claude is working -- generating, or running tools. */
    BUSY,

    /**
     * Claude is blocked on a human: a permission prompt, or an idle
     * notification. This is the state worth a badge, since it is the only
     * one where the session makes no further progress until the user
     * returns to it.
     */
    NEEDS_ATTENTION;

    /** Maps a state word written by the hook script; unrecognized input is {@link #UNKNOWN}. */
    public static SessionActivity fromStateWord(String word) {
        if (word == null) {
            return UNKNOWN;
        }
        return switch (word.strip()) {
            case "busy" -> BUSY;
            case "idle" -> IDLE;
            case "attention" -> NEEDS_ATTENTION;
            default -> UNKNOWN;
        };
    }
}
