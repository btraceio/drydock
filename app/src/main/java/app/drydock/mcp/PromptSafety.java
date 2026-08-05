package app.drydock.mcp;

import java.util.Objects;

/**
 * Validates a prompt an MCP tool call wants to send into a hosted
 * {@code claude} session, before it is delivered, and validates
 * agent-authored text that drydock itself renders.
 *
 * <p>The prompt is not sent as an API message: {@code
 * MainWorkspace.sendTaskWhenReady} collapses its whitespace and types the
 * result as real keystrokes into the session's terminal via {@code
 * TerminalBridge.sendPrompt}, then presses Return. In the {@code claude} TUI
 * a leading {@code !} enters bash mode, a leading {@code /} invokes a slash
 * command, and a leading {@code #} is a memory directive -- none of those is
 * a model turn. An embedded newline or carriage return submits the text
 * before it, so the remainder of the prompt is typed as a second, unintended
 * line. Any other control character (including ESC, which can drive terminal
 * escape sequences) is refused outright rather than guessing at its effect.</p>
 */
public final class PromptSafety {

    private PromptSafety() {
    }

    /**
     * Longest text a single inbound finding field may carry. Not a security
     * boundary on its own -- the store would hold more -- but a reviewer
     * pasting a whole file into a title is a bug, and a bounded field keeps
     * one such call from making the margin unusable.
     */
    private static final int MAX_INBOUND_TEXT = 8000;

    /** Longest session title, in code points. Bounds what can enter a confirm dialog. */
    private static final int MAX_TITLE_CODE_POINTS = 60;

    /** Longest run of combining marks, so a title cannot grow vertically out of the tab rail. */
    private static final int MAX_CONSECUTIVE_MARKS = 2;

    /**
     * Validates text arriving <em>from</em> an agent -- a finding title, body
     * or evidence block (Review MCP schema, "Injection").
     *
     * <p>The diff an agent reads is untrusted input, and a finding may quote
     * it verbatim, so this text is adversarial by construction. Two distinct
     * hazards, and only one of them is about rendering:</p>
     *
     * <ul>
     *   <li>The margin renders findings as {@code Label} text, never as
     *       markup, so nothing here has to strip markup -- there is no
     *       renderer to confuse.</li>
     *   <li>The text can nevertheless reach a terminal later: "Ask the agent
     *       to fix it" and "Apply patch" type a finding's own words into a
     *       live session. Control characters are therefore refused here, at
     *       the boundary, rather than at that much later hand-off.</li>
     * </ul>
     *
     * <p>Unlike {@link #validate}, a leading {@code !}, {@code /} or
     * {@code #} is fine: those rules are about what the {@code claude} TUI
     * does with a line it is typed, and a finding body is not typed as a
     * line. Newlines and tabs are legitimate in a body and are allowed.</p>
     *
     * @return {@code text} unchanged, so callers can use this inline
     * @throws McpToolException if {@code text} is over-long or carries a
     *         control character other than newline, carriage return or tab
     */
    public static String checkInboundText(String text, String field) throws McpToolException {
        if (text == null) {
            return null;
        }
        if (text.length() > MAX_INBOUND_TEXT) {
            throw new McpToolException(field + " is " + text.length() + " characters; the limit is "
                    + MAX_INBOUND_TEXT);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if (Character.isISOControl(c)) {
                throw new McpToolException(field + " must not contain control characters (found one at index "
                        + i + "); a finding is rendered as text and may later be typed into a terminal");
            }
        }
        return text;
    }

    /**
     * Validates a session title an agent wants to write to its own tab
     * ({@code session_rename}), and returns it folded.
     *
     * <p>Unlike {@link #checkInboundText}, which is written for finding
     * bodies and deliberately permits {@code \n}, {@code \r} and {@code \t}:
     * a title is one line, and it lands somewhere a finding body never does.
     * It is the label of the tab rail and the sidebar row, and it is
     * interpolated into five confirm dialogs -- including
     * "Delete session \"...\"?" and the Start-new-conversation /
     * Delete-session pair. Text that can reorder, hide, or re-punctuate
     * itself there can make a destructive confirmation read as a reassurance.
     *
     * <p>The scan iterates code points. A {@code char} loop would miss the
     * supplementary-plane tag block U+E0020-U+E007F -- FORMAT characters
     * whose surrogates are neither FORMAT nor controls, and the current
     * invisible-text-smuggling vector.
     *
     * @return {@code title} with every Unicode space folded to U+0020, runs
     *         collapsed and the result trimmed -- the value that gets stored
     *         and compared, never the raw argument
     */
    public static String checkSessionTitle(String title) throws McpToolException {
        Objects.requireNonNull(title, "title");

        int marksInARow = 0;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);

            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new McpToolException("A session title must be one line of visible text; "
                        + "it may not contain control, invisible or direction-changing characters "
                        + "(found U+" + String.format("%04X", cp) + ").");
            }
            if (cp == '"') {
                throw new McpToolException("A session title may not contain a double quote: drydock "
                        + "shows it inside quotes in confirmation dialogs.");
            }
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                if (++marksInARow > MAX_CONSECUTIVE_MARKS) {
                    throw new McpToolException("A session title may not stack more than "
                            + MAX_CONSECUTIVE_MARKS + " combining marks on one character.");
                }
            } else {
                marksInARow = 0;
            }
        }

        String folded = fold(title);
        if (folded.isEmpty()) {
            throw new McpToolException("A session title must not be blank.");
        }
        int codePoints = folded.codePointCount(0, folded.length());
        if (codePoints > MAX_TITLE_CODE_POINTS) {
            throw new McpToolException("A session title may be at most " + MAX_TITLE_CODE_POINTS
                    + " characters; this one is " + codePoints + ".");
        }
        return folded;
    }

    /**
     * Every Unicode space separator to U+0020, runs collapsed, then trimmed.
     *
     * <p>Not {@code strip()} or {@code \s}: {@link Character#isWhitespace} is
     * false for U+00A0, U+2007 and U+202F, so a title made entirely of
     * non-breaking spaces would otherwise pass every check and render as a
     * blank tab and a blank "Delete session" dialog.</p>
     */
    /** The folding half of {@link #checkSessionTitle}, for comparing against already-stored names. */
    public static String foldForComparison(String title) {
        return fold(title);
    }

    private static String fold(String title) {
        StringBuilder folded = new StringBuilder(title.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            boolean isSpace = cp == ' ' || Character.isSpaceChar(cp);
            if (isSpace) {
                if (!lastWasSpace && folded.length() > 0) {
                    folded.append(' ');
                }
                lastWasSpace = true;
            } else {
                folded.appendCodePoint(cp);
                lastWasSpace = false;
            }
        }
        int end = folded.length();
        while (end > 0 && folded.charAt(end - 1) == ' ') {
            end--;
        }
        return folded.substring(0, end);
    }

    /**
     * @throws McpToolException if {@code prompt} is blank, contains an ASCII
     *         control character, or -- after leading whitespace is stripped --
     *         begins with {@code !}, {@code /}, or {@code #}.
     */
    public static void validate(String prompt) throws McpToolException {
        if (prompt == null || prompt.isBlank()) {
            throw new McpToolException("prompt must not be blank");
        }
        for (int i = 0; i < prompt.length(); i++) {
            char c = prompt.charAt(i);
            if (Character.isISOControl(c)) {
                throw new McpToolException(
                        "prompt must not contain control characters (found one at index " + i + ")");
            }
        }
        // Non-empty by construction: isBlank() above and stripLeading() here
        // both test Character.isWhitespace, so a fully whitespace prompt is
        // already gone.
        char first = prompt.stripLeading().charAt(0);
        if (first == '!') {
            throw new McpToolException(
                    "prompt must not start with '!': the claude TUI treats a leading '!' as bash mode, "
                            + "not a model turn");
        }
        if (first == '/') {
            throw new McpToolException(
                    "prompt must not start with '/': the claude TUI treats a leading '/' as a slash command, "
                            + "not a model turn");
        }
        if (first == '#') {
            throw new McpToolException(
                    "prompt must not start with '#': the claude TUI treats a leading '#' as a memory directive, "
                            + "not a model turn");
        }
    }
}
