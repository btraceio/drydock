package app.drydock.tasks;

import java.util.Objects;

/**
 * The raw envelope of one task file: the exact source text between the
 * front-matter {@code ---} fences and the body after the closing fence
 * line, plus the absolute UTF-16 source offset of the front matter. Pure
 * value extraction (P1) — no YAML interpretation, no I/O: the reader
 * composes bounded YAML from {@link #frontMatter()} and keeps
 * {@link #body()} byte for byte, and T03 patches against these raw slices.
 *
 * <p>Refuses visibly (checked {@link TaskFormatException}) instead of
 * guessing: a file that does not begin with an exactly-three-dash fence on
 * its own line, front matter without a closing fence line, or a file beyond
 * the whole-file code-point bound. Raw text — LF or CRLF line endings,
 * comments, astral characters — survives unchanged.</p>
 */
record TaskFileEnvelope(String frontMatter, int frontStart, String body) {

    /** Whole-file bound, in Unicode code points, for any task file the reader accepts. */
    static final int MAX_FILE_CODE_POINTS = 1 << 20;

    private static final String FENCE = "---";

    TaskFileEnvelope {
        if (frontMatter == null || body == null || frontStart < 0) {
            throw new IllegalArgumentException("invalid envelope");
        }
    }

    /**
     * Splits one task file into its raw envelope. The opening fence must be
     * exactly {@code ---} on the first line; the closing fence is a line
     * containing only {@code ---} (an optional {@code \r} before the line
     * break is tolerated, so CRLF files close with {@code ---\r\n}). A
     * closing fence at end of file yields an empty body.
     */
    static TaskFileEnvelope parse(String source) throws TaskFormatException {
        Objects.requireNonNull(source, "source");
        if (source.codePointCount(0, source.length()) > MAX_FILE_CODE_POINTS) {
            throw new TaskFormatException("Task file exceeds " + MAX_FILE_CODE_POINTS + " code points");
        }
        int afterOpening = endOfOpeningFenceLine(source);
        int lineStart = afterOpening;
        while (true) {
            int lineEnd = source.indexOf('\n', lineStart);
            int contentEnd = lineEnd < 0 ? source.length() : lineEnd;
            boolean matches = source.startsWith(FENCE, lineStart)
                    && (contentEnd - lineStart == FENCE.length()
                        || (contentEnd - lineStart == FENCE.length() + 1 && source.charAt(contentEnd - 1) == '\r'));
            if (matches) {
                String body = lineEnd < 0 ? "" : source.substring(lineEnd + 1);
                return new TaskFileEnvelope(source.substring(afterOpening, lineStart), afterOpening, body);
            }
            if (lineEnd < 0) {
                throw new TaskFormatException("Front matter has no closing '---' fence");
            }
            lineStart = lineEnd + 1;
        }
    }

    /** Position just after the opening {@code ---} fence's line break, or refuses the file. */
    private static int endOfOpeningFenceLine(String source) throws TaskFormatException {
        if (!source.startsWith(FENCE)) {
            throw new TaskFormatException("Task file must begin with a '---' front-matter fence");
        }
        int next = FENCE.length();
        if (next >= source.length()) {
            throw new TaskFormatException("Front matter has no closing '---' fence");
        }
        char first = source.charAt(next);
        if (first == '\n') {
            return next + 1;
        }
        if (first == '\r' && next + 1 < source.length() && source.charAt(next + 1) == '\n') {
            return next + 2;
        }
        throw new TaskFormatException("The opening front-matter fence must be exactly '---' on its own line");
    }
}
