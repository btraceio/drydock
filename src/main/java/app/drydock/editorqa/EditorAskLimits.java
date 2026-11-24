package app.drydock.editorqa;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import app.drydock.mcp.McpToolException;
import app.drydock.mcp.PromptSafety;

/**
 * Every fixed bound of the editor ask feature, and the checks that enforce
 * them. The bound values are the contract's ("Wire contract and privacy");
 * each one is a rejection threshold, never a clipping point: text over a
 * bound is refused with a reason, because a silently clipped selection is a
 * question about code the user did not select.
 *
 * <p>Two flavors of character safety, both enforced here:</p>
 *
 * <ul>
 *   <li><em>Line/terminal data</em> -- checkout roots, relative paths and
 *     the model name -- rejects every ISO control character, including
 *     LF, CR and tab: these values land in argv, path resolution and
 *     single-line labels, where any control byte can reframe the line.</li>
 *   <li><em>Text data</em> -- selected text, question and answer -- allows
 *     LF, CR and tab (source selections routinely contain all three) and
 *     rejects every other ISO control, including NUL and ESC.</li>
 * </ul>
 *
 * <p>Both flavors additionally reject unpaired UTF-16 surrogates and the
 * Unicode bidi-control characters: neither survives the ASCII-escaped JSON
 * envelope intact, and direction-reordering input is deceptive wherever it
 * is later rendered or quoted.</p>
 */
public final class EditorAskLimits {

    /** Longest canonical checkout root, in UTF-8 bytes. */
    public static final int MAX_ROOT_BYTES = 4096;

    /** Longest checkout-relative file path, in UTF-8 bytes. */
    public static final int MAX_RELATIVE_PATH_BYTES = 4096;

    /** Longest submitted selection, in UTF-8 bytes. May be all whitespace, never empty. */
    public static final int MAX_SELECTION_BYTES = 16 * 1024;

    /** Longest question, in UTF-8 bytes. Nonblank. */
    public static final int MAX_QUESTION_BYTES = 2 * 1024;

    /** Longest harness model override, in UTF-8 bytes, and always a single line. */
    public static final int MAX_MODEL_BYTES = 256;

    /** Longest final encoded prompt line, in characters (ASCII, so bytes). */
    public static final int MAX_PROMPT_BYTES = 32 * 1024;

    /** Longest settled-outcome reason, in UTF-16 units. Plugin-authored, still bounded. */
    public static final int MAX_REASON_CHARS = 500;

    /** Oldest settled records evicted past this count, per checkout, by acceptance sequence. */
    public static final int MAX_SETTLED_RECORDS = 128;

    /** Unfinished records (queued/submitting/awaiting) protected from eviction, per checkout. */
    public static final int MAX_UNFINISHED_RECORDS = 32;

    /** Longest persistent history partition, in bytes. */
    public static final long MAX_PARTITION_BYTES = 32L * 1024 * 1024;

    private EditorAskLimits() {
    }

    /**
     * Why an input was refused. Unchecked, so every check can be used
     * inline; the message names the field and the violated bound, in the
     * style of {@link PromptSafety}.
     */
    public static final class RejectedInputException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RejectedInputException(String message) {
            super(message);
        }
    }

    /**
     * Validates a canonical checkout root and returns it unchanged.
     *
     * @throws RejectedInputException if blank, over {@link #MAX_ROOT_BYTES}
     *         UTF-8 bytes, or carrying a line/terminal control character
     */
    public static String checkRoot(String root) {
        Objects.requireNonNull(root, "root");
        checkNonblank(root, "root");
        checkByteBound(root, MAX_ROOT_BYTES, "root");
        checkUnicodeSafety(root, "root", false);
        return root;
    }

    /**
     * Validates a checkout-relative file path and returns it unchanged.
     * Relative-ness itself is the capture resolver's guarantee (the path is
     * derived from a verified containment check, never user-typed), so this
     * only enforces the byte bound and character safety.
     *
     * @throws RejectedInputException if blank, over
     *         {@link #MAX_RELATIVE_PATH_BYTES} UTF-8 bytes, or carrying a
     *         line/terminal control character
     */
    public static String checkRelativePath(String path) {
        Objects.requireNonNull(path, "path");
        checkNonblank(path, "path");
        checkByteBound(path, MAX_RELATIVE_PATH_BYTES, "path");
        checkUnicodeSafety(path, "path", false);
        return path;
    }

    /**
     * Validates the exact selected text and returns it unchanged.
     *
     * @throws RejectedInputException if empty, over {@link #MAX_SELECTION_BYTES}
     *         UTF-8 bytes, or carrying a control character other than
     *         LF/CR/tab, an unpaired surrogate or a bidi control
     */
    public static String checkSelection(String selection) {
        Objects.requireNonNull(selection, "selection");
        if (selection.isEmpty()) {
            throw new RejectedInputException("selection must not be empty (whitespace-only is allowed)");
        }
        checkByteBound(selection, MAX_SELECTION_BYTES, "selection");
        checkUnicodeSafety(selection, "selection", true);
        return selection;
    }

    /**
     * Validates the question and returns it unchanged.
     *
     * @throws RejectedInputException if blank, over {@link #MAX_QUESTION_BYTES}
     *         UTF-8 bytes, or carrying a control character other than
     *         LF/CR/tab, an unpaired surrogate or a bidi control
     */
    public static String checkQuestion(String question) {
        Objects.requireNonNull(question, "question");
        checkNonblank(question, "question");
        checkByteBound(question, MAX_QUESTION_BYTES, "question");
        checkUnicodeSafety(question, "question", true);
        return question;
    }

    /**
     * Validates a harness model override and returns it unchanged. A null
     * or blank model is "unset" (the flag is omitted) and passes; a present
     * model is one line, bounded, and free of line/terminal controls.
     *
     * @throws RejectedInputException if over {@link #MAX_MODEL_BYTES} UTF-8
     *         bytes, or carrying any control character
     */
    public static String checkModel(String model) {
        if (model == null || model.isBlank()) {
            return model;
        }
        checkByteBound(model, MAX_MODEL_BYTES, "model");
        checkUnicodeSafety(model, "model", false);
        return model;
    }

    /**
     * Validates the final encoded prompt line and returns it unchanged: one
     * line of printable ASCII, at most {@link #MAX_PROMPT_BYTES} characters.
     * The ASCII-escaped envelope guarantees the ASCII property for the data
     * it carries; this check is the last gate before the prompt is handed
     * to a harness, so it re-derives the property from the actual bytes.
     *
     * @throws RejectedInputException if over-long or carrying a non-ASCII
     *         or control character
     */
    public static String checkPrompt(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.length() > MAX_PROMPT_BYTES) {
            throw new RejectedInputException("prompt is " + prompt.length()
                    + " characters; the limit is " + MAX_PROMPT_BYTES
                    + ". Shorten the selection or the question -- drydock will not clip a prompt.");
        }
        for (int i = 0; i < prompt.length(); i++) {
            char c = prompt.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new RejectedInputException("prompt must be printable ASCII on a single line"
                        + " (found U+" + String.format("%04X", (int) c) + " at index " + i + ")");
            }
        }
        return prompt;
    }

    /**
     * Validates the settled-outcome reason and returns it unchanged.
     *
     * @throws RejectedInputException if blank, over {@link #MAX_REASON_CHARS}
     *         UTF-16 units, or carrying any control character
     */
    public static String checkReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        checkNonblank(reason, "reason");
        if (reason.length() > MAX_REASON_CHARS) {
            throw new RejectedInputException("reason is " + reason.length()
                    + " characters; the limit is " + MAX_REASON_CHARS);
        }
        checkUnicodeSafety(reason, "reason", false);
        return reason;
    }

    /**
     * Validates the full answer arriving from the harness and returns it
     * unchanged. Delegates the length and ISO-control rules to
     * {@link PromptSafety#checkInboundText} (at most 8,000 UTF-16 units, no
     * ISO controls other than LF/CR/tab) and adds the same Unicode safety
     * every other field gets: no unpaired surrogates, no bidi controls.
     *
     * @throws RejectedInputException if blank, over-long, or unsafe
     */
    public static String checkAnswer(String answer) {
        Objects.requireNonNull(answer, "answer");
        checkNonblank(answer, "answer");
        try {
            PromptSafety.checkInboundText(answer, "answer");
        } catch (McpToolException e) {
            throw new RejectedInputException(e.getMessage());
        }
        checkUnicodeSafety(answer, "answer", true);
        return answer;
    }

    private static void checkNonblank(String value, String field) {
        if (value.isBlank()) {
            throw new RejectedInputException(field + " must not be blank");
        }
    }

    private static void checkByteBound(String value, int maxBytes, String field) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new RejectedInputException(field + " is " + bytes + " UTF-8 bytes; the limit is "
                    + maxBytes + ". Shorten it -- drydock will not truncate " + field + ".");
        }
    }

    private static void checkUnicodeSafety(String text, String field, boolean allowLineControls) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= Character.MIN_SUPPLEMENTARY_CODE_POINT) {
                continue;
            }
            char c = (char) cp;
            boolean allowedControl = allowLineControls && (c == '\n' || c == '\r' || c == '\t');
            if (Character.isISOControl(c) && !allowedControl) {
                throw new RejectedInputException(field + " must not contain control characters (found U+"
                        + String.format("%04X", cp) + " at index " + (i - 1) + ")");
            }
            if (Character.isSurrogate(c)) {
                throw new RejectedInputException(field + " must not contain unpaired UTF-16 surrogates"
                        + " (found one at index " + (i - 1) + ")");
            }
            if (isBidiControl(cp)) {
                throw new RejectedInputException(field + " must not contain bidi-control characters"
                        + " (found U+" + String.format("%04X", cp) + " at index " + (i - 1) + ")");
            }
        }
    }

    /**
     * The directional-formatting and invisible direction-mark characters
     * (Arabic letter mark, LRM/RLM, LRE/RLE/PDF/LRO/RLO and the four
     * isolates): the set a "trojan source" scan refuses, since any of them
     * can reorder the text a reader sees around it.
     */
    private static boolean isBidiControl(int cp) {
        return cp == 0x061C || cp == 0x200E || cp == 0x200F
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069);
    }
}
