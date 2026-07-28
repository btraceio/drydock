package app.drydock.mcp;

/**
 * Validates a prompt an MCP tool call wants to send into a hosted
 * {@code claude} session, before it is delivered.
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
