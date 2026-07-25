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
        String trimmed = prompt.stripLeading();
        if (trimmed.isEmpty()) {
            throw new McpToolException("prompt must not be blank");
        }
        char first = trimmed.charAt(0);
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
