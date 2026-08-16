package app.drydock.mcp;

import java.util.List;
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

    /**
     * Per-slot cap for a handoff brief, in code points. Chosen so a full brief
     * costs a fraction of a file read -- the whole point of a living brief is
     * that keeping it current is cheaper than reconstructing it later.
     */
    public static final int MAX_HANDOFF_SLOT_CHARS = 2000;

    /** Whole-record cap, in code points. Binds before six full slots would. */
    public static final int MAX_HANDOFF_RECORD_CHARS = 8000;

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
     * Validates one {@code session_handoff} slot and returns it unchanged.
     *
     * <p>A slot is a body, not a title: {@code \n}, {@code \r} and {@code \t}
     * are permitted exactly as for finding bodies, and nothing is folded --
     * a brief's line structure is meaningful to the successor reading it,
     * where a tab in a tab label is not.</p>
     *
     * <p>Refuses rather than truncates. A silently clipped {@code nextStep} is
     * a brief that lies, and the agent that wrote it has no way to notice.</p>
     */
    public static String checkHandoffSlot(String field, String text) throws McpToolException {
        checkInboundText(text, field);
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints > MAX_HANDOFF_SLOT_CHARS) {
            throw new McpToolException(field + " is " + codePoints + " characters; the limit is "
                    + MAX_HANDOFF_SLOT_CHARS + ". Shorten it -- drydock will not truncate a brief, "
                    + "because a clipped slot is a brief that lies.");
        }
        return text;
    }

    /**
     * Refuses a brief whose slots each fit but which together exceed {@link
     * #MAX_HANDOFF_RECORD_CHARS}. Six full slots would be 12,000 code points,
     * so this cap is the one that actually binds.
     */
    public static void checkHandoffRecordSize(List<String> slots) throws McpToolException {
        int total = 0;
        for (String slot : slots) {
            total += slot.codePointCount(0, slot.length());
        }
        if (total > MAX_HANDOFF_RECORD_CHARS) {
            throw new McpToolException("The whole brief is " + total + " characters; the limit is "
                    + MAX_HANDOFF_RECORD_CHARS + ". Shorten the longest slots.");
        }
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
                throw new McpToolException(unrenderableMessage(cp));
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

    /** The folding half of {@link #checkSessionTitle}, for comparing against already-stored names. */
    public static String foldForComparison(String title) {
        return fold(title);
    }

    /** U+200D ZERO WIDTH JOINER: what welds a multi-part emoji together. */
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    /**
     * Why a code point was refused, phrased so the caller can act on it.
     *
     * <p>The joiner gets its own sentence because it is the one refusal an
     * honest agent trips over. It is {@code FORMAT}, like a bidi override, so
     * the same rule catches both -- but a model writing a family or profession
     * emoji is not smuggling anything, and "found U+200D" gives it nothing to
     * act on. It then retries blind until its rename budget runs out.</p>
     *
     * <p>Note what is NOT here: an emoji's variation selector (U+FE0F and its
     * neighbours) is a {@code NONSPACING_MARK}, not {@code FORMAT}, so plain
     * emoji and their colour presentation forms pass rule 1 untouched. Only
     * the glued sequences are refused, which is why the advice is "use a
     * single emoji" rather than "drop the emoji".</p>
     */
    private static String unrenderableMessage(int codePoint) {
        if (codePoint == ZERO_WIDTH_JOINER) {
            return "A session title may not contain a zero-width joiner (U+200D), so multi-part "
                    + "emoji -- professions, families, composed flags -- cannot be used. A single "
                    + "emoji is fine.";
        }
        return "A session title must be one line of visible text; it may not contain control, "
                + "invisible or direction-changing characters (found U+"
                + String.format("%04X", codePoint) + ").";
    }

    /**
     * Every Unicode space separator to U+0020, runs collapsed, then trimmed.
     *
     * <p>Not {@code strip()} or {@code \s}: {@link Character#isWhitespace} is
     * false for U+00A0, U+2007 and U+202F, so a title made entirely of
     * non-breaking spaces would otherwise pass every check and render as a
     * blank tab and a blank "Delete session" dialog.</p>
     */
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
