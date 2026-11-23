package app.drydock.intellij.agent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An agent's output so far, kept so a console built later — in another
 * window, or after the window that showed it closed — can be filled with
 * what the agent already printed.
 *
 * <p>Capped, because a TUI's history is unbounded: at most
 * {@link #MAX_LINES} lines or {@link #MAX_CHARS} characters, whichever is
 * reached first. The oldest output goes first, and a trimmed replay starts
 * with {@link #TRIMMED} so nobody reads a cut-off screen as the whole
 * conversation. The cap is in UTF-16 characters, not bytes: close enough to
 * the handoff's 8 MB for terminal output, and free to measure.</p>
 *
 * <p>Not thread-safe; {@link OutputRouter} holds the one lock it needs.</p>
 */
final class ReplayBuffer {

    static final int MAX_LINES = 20_000;
    static final int MAX_CHARS = 8 * 1024 * 1024;
    static final String TRIMMED = "… earlier output trimmed\r\n";

    private final int maxLines;
    private final int maxChars;
    /** Complete lines, each ending in its {@code '\n'}. */
    private final Deque<String> lines = new ArrayDeque<>();
    /** Output after the last {@code '\n'}. */
    private final StringBuilder partial = new StringBuilder();
    private long chars;
    private boolean trimmed;

    ReplayBuffer() {
        this(MAX_LINES, MAX_CHARS);
    }

    /** Test seam: small caps. */
    ReplayBuffer(int maxLines, int maxChars) {
        this.maxLines = maxLines;
        this.maxChars = maxChars;
    }

    void append(String text) {
        int start = 0;
        int newline;
        while ((newline = text.indexOf('\n', start)) >= 0) {
            partial.append(text, start, newline + 1);
            lines.addLast(partial.toString());
            partial.setLength(0);
            start = newline + 1;
        }
        partial.append(text, start, text.length());
        chars += text.length();
        trim();
    }

    private void trim() {
        while (lines.size() > maxLines || (chars > maxChars && !lines.isEmpty())) {
            chars -= lines.removeFirst().length();
            trimmed = true;
        }
        if (chars > maxChars) {
            // Only the unfinished last line is left, and it alone is too long.
            int cut = (int) (chars - maxChars);
            partial.delete(0, cut);
            chars -= cut;
            trimmed = true;
        }
    }

    /** Everything kept, oldest first, behind {@link #TRIMMED} when something was dropped. */
    String snapshot() {
        StringBuilder out = new StringBuilder((int) Math.min(Integer.MAX_VALUE - 64, chars + TRIMMED.length()));
        if (trimmed) {
            out.append(TRIMMED);
        }
        lines.forEach(out::append);
        return out.append(partial).toString();
    }

    boolean trimmed() {
        return trimmed;
    }
}
