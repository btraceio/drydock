package app.drydock.tasks;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonWriter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lossless metadata rewriting of task files (T03a): {@link #patch} replaces
 * only the known-key spans whose value actually changed and inserts missing
 * keys, never reserializing the front matter, so unknown keys, comments,
 * quoting styles, line endings and the body survive byte for byte (P1); a
 * no-op update returns the source text unchanged.
 *
 * <p>Pure text transformation: no disk I/O, locking, graph work or policy
 * decisions (the caller owns one serial writer per task file). Value quoting
 * reuses the core JSON writer -- a JSON string is a valid YAML double-quoted
 * scalar once the code points YAML reads as line breaks or refuses as
 * non-printables are re-escaped -- and every result is re-parsed through the
 * bounded reader before it is returned, so a serialization bug surfaces as a
 * checked {@link TaskFormatException}, never as an invalid task file. A
 * missing key inserted into flow-style root front matter goes inside the
 * braces, so a valid flow root stays valid.</p>
 *
 * <p>This is a deliberate cold path (one call per user-initiated edit or
 * session append, never per keystroke): patch builds an edit list, a fresh
 * {@link StringBuilder} and a full reparse instead of shaving allocations,
 * and nothing hot depends on it.</p>
 */
public final class TaskFilePatch {

    private TaskFilePatch() {
    }

    /**
     * Typed front-matter updates for {@link #patch}: every field that is
     * {@code null} is left unchanged, and a field set to the value the
     * document already carries (including a defaulted one) changes nothing,
     * so a no-op returns the source byte for byte. Non-null lists are
     * snapshotted (and their null entries refused) at construction, so later
     * caller mutations cannot change what a patch writes.
     */
    public record Updates(String title, TaskStatus status, DoneBy doneBy, Instant created,
            List<String> after, List<String> sessions) {

        public Updates {
            after = snapshotEntries(after, "after");
            sessions = snapshotEntries(sessions, "sessions");
        }
    }

    /** One replacement in the source: {@code [start, end)} becomes {@code text}. */
    private record Edit(int start, int end, String text) {
    }

    /**
     * Applies {@code updates} to the exact source text the {@code parsed}
     * result came from. The source is checked against the parsed raw
     * envelope first -- both fences and the raw front matter and body -- so
     * cached spans are never applied to a different or edited text. The
     * returned text re-parses to exactly the requested field values.
     */
    public static String patch(TaskFileReader.Parsed parsed, String source, Updates updates)
            throws TaskFormatException {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(updates, "updates");
        checkSource(parsed, source);
        TaskDocument doc = parsed.document();
        Map<String, TaskFileReader.Span> spans = parsed.knownKeySpans();
        List<Edit> edits = new ArrayList<>();
        List<String> inserted = new ArrayList<>();
        if (updates.title() != null && !updates.title().equals(doc.title())) {
            if (updates.title().isBlank()) {
                throw new TaskFormatException("Updated title must not be blank");
            }
            spanEdit(spans.get("title"), edits, inserted, "title: " + quoted(updates.title()));
        }
        if (updates.status() != null && updates.status() != doc.status()) {
            spanEdit(spans.get("status"), edits, inserted, "status: " + updates.status().storedName());
        }
        if (updates.doneBy() != null && updates.doneBy() != doc.doneBy()) {
            spanEdit(spans.get("done_by"), edits, inserted, "done_by: " + updates.doneBy().storedName());
        }
        if (updates.created() != null && !Optional.of(updates.created()).equals(doc.created())) {
            spanEdit(spans.get("created"), edits, inserted, "created: " + updates.created());
        }
        if (updates.after() != null && !updates.after().equals(doc.after())) {
            spanEdit(spans.get("after"), edits, inserted, "after: " + list(updates.after()));
        }
        if (updates.sessions() != null && !updates.sessions().equals(doc.sessions())) {
            spanEdit(spans.get("sessions"), edits, inserted, "sessions: " + list(updates.sessions()));
        }
        if (!inserted.isEmpty()) {
            edits.add(insertion(parsed, inserted));
        }
        String result = apply(source, edits);
        checkRoundTrip(doc, result, updates);
        return result;
    }

    /** Routes one serialized entry to a span replacement or to the insertion group. */
    private static void spanEdit(TaskFileReader.Span span, List<Edit> edits, List<String> inserted,
            String entry) {
        if (span == null) {
            inserted.add(entry);
        } else {
            edits.add(new Edit(span.start(), span.end(), entry));
        }
    }

    /**
     * Where missing keys are inserted: block-style roots get new lines at
     * the end of the front matter; a flow-style root ({@code {title: T}})
     * gets them inside the braces, where block lines would be invalid.
     * The inserted entries keep the surrounding commas and braces of the
     * flow root and the line ending of its last line, untouched.
     */
    private static Edit insertion(TaskFileReader.Parsed parsed, List<String> inserted) {
        TaskFileReader.Span front = parsed.frontMatter();
        String raw = parsed.document().rawFrontMatter();
        int brace = flowRootBrace(raw);
        if (brace >= 0) {
            int at = front.start() + brace + 1;
            return new Edit(at, at, String.join(", ", inserted) + ", ");
        }
        String nl = lastLineBreak(raw);
        return new Edit(front.end(), front.end(), String.join(nl, inserted) + nl);
    }

    /**
     * Index of the root flow mapping's opening brace, or {@code -1} for any
     * block-style root. Before the root node a front matter can only hold
     * comments, whitespace, an anchor or a tag, so the first content
     * character settles the style. The scan uses YAML's own blanks and line
     * breaks -- CR, LF, NEL, LS, PS, plus a leading BOM -- not Java's
     * whitespace; the reader accepts exactly those prefixes, and a comment
     * runs to the next YAML line break, not just LF.
     */
    private static int flowRootBrace(String front) {
        int i = 0;
        while (i < front.length()) {
            char c = front.charAt(i);
            if (isBlank(c)) {
                i++;
            } else if (c == '#') {
                while (i < front.length() && !isBreak(front.charAt(i))) {
                    i++;
                }
            } else if (c == '&' || c == '!') {
                while (i < front.length() && !isBlank(front.charAt(i))) {
                    i++;
                }
            } else {
                return c == '{' ? i : -1;
            }
        }
        return -1;
    }

    /** YAML line breaks, the characters that end a line (and a comment). */
    private static boolean isBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** YAML blanks and breaks, plus a BOM the reader tolerates before a root. */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\uFEFF' || isBreak(c);
    }

    /** Line ending of the front matter's last line, reused for block insertions. */
    private static String lastLineBreak(String raw) {
        int i = raw.lastIndexOf('\n');
        return i > 0 && raw.charAt(i - 1) == '\r' ? "\r\n" : "\n";
    }

    /**
     * Splices the edits in once, in one pass, with no offset drift. An
     * insertion ({@code start == end}) sorts before a replacement that
     * begins at the same offset, so a flow-root insertion and a first-key
     * replacement that share the offset after the brace apply cleanly
     * instead of being refused as an overlap.
     */
    private static String apply(String source, List<Edit> edits) throws TaskFormatException {
        edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        StringBuilder out = new StringBuilder(source.length() + 64);
        int prev = 0;
        for (Edit edit : edits) {
            if (edit.start() < prev || edit.end() > source.length()) {
                throw new TaskFormatException("Patch edits overlap or leave the source");
            }
            out.append(source, prev, edit.start()).append(edit.text());
            prev = edit.end();
        }
        return out.append(source, prev, source.length()).toString();
    }

    /**
     * Pins the source to the parsed envelope: both fences, the raw front
     * matter and the raw body must reproduce the text the reader kept, and
     * every known span must sit inside that front matter. Anything else is
     * a stale or foreign parse, never patched. Ranges are compared in place
     * -- no substring copies of potentially whole-file raw slices.
     */
    private static void checkSource(TaskFileReader.Parsed parsed, String source)
            throws TaskFormatException {
        TaskDocument doc = parsed.document();
        TaskFileReader.Span front = parsed.frontMatter();
        if (front.start() < 0 || front.end() < front.start() || front.end() > source.length()) {
            throw staleSource();
        }
        // The opening fence must be exactly '---' on the first line, and the
        // front matter must begin right after that line's break.
        boolean openingLf = front.start() == 4 && source.startsWith("---\n");
        boolean openingCrlf = front.start() == 5 && source.startsWith("---\r\n");
        if (!openingLf && !openingCrlf) {
            throw staleSource();
        }
        if (!regionEquals(source, front.start(), front.end(), doc.rawFrontMatter())) {
            throw staleSource();
        }
        int fence = front.end();
        if (fence + 3 > source.length() || !source.startsWith("---", fence)) {
            throw staleSource();
        }
        int afterFence = fence + 3;
        if (afterFence < source.length() && source.charAt(afterFence) == '\r') {
            afterFence++;
        }
        int bodyStart;
        if (afterFence < source.length()) {
            if (source.charAt(afterFence) != '\n') {
                throw staleSource();
            }
            bodyStart = afterFence + 1;
        } else {
            bodyStart = afterFence; // A closing fence at end of file yields an empty body.
        }
        if (!regionEquals(source, bodyStart, source.length(), doc.rawBody())) {
            throw staleSource();
        }
        for (Map.Entry<String, TaskFileReader.Span> entry : parsed.knownKeySpans().entrySet()) {
            TaskFileReader.Span span = entry.getValue();
            if (span.start() < front.start() || span.end() > front.end()) {
                throw new TaskFormatException("Known-key span of '" + entry.getKey()
                        + "' lies outside the front matter");
            }
        }
    }

    private static TaskFormatException staleSource() {
        return new TaskFormatException("Source does not match the parsed task envelope");
    }

    /** Equality of a source range against raw text, without copying either side. */
    private static boolean regionEquals(String source, int start, int end, String raw) {
        if (start < 0 || end < start || end > source.length() || raw.length() != end - start) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (source.charAt(start + i) != raw.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The patched text must still parse and carry exactly the fields asked for. */
    private static void checkRoundTrip(TaskDocument doc, String result, Updates updates)
            throws TaskFormatException {
        // The original creation-time fallback is passed through, so an
        // unchanged 'created' that never was a key in the source stays one.
        TaskDocument reparsed = TaskFileReader
                .parse(doc.id(), doc.repository(), result, doc.created().orElse(null)).document();
        if (updates.title() != null && !updates.title().equals(reparsed.title())
                || updates.status() != null && updates.status() != reparsed.status()
                || updates.doneBy() != null && updates.doneBy() != reparsed.doneBy()
                || updates.created() != null && !Optional.of(updates.created()).equals(reparsed.created())
                || updates.after() != null && !updates.after().equals(reparsed.after())
                || updates.sessions() != null && !updates.sessions().equals(reparsed.sessions())) {
            throw new TaskFormatException("Patched front matter does not round-trip");
        }
    }

    /**
     * Double-quoted scalar quoting: the core JSON writer handles JSON's own
     * escapes, then the code points it leaves raw that YAML reads as line
     * breaks (NEL, LS, PS) or refuses as non-printables (DEL, the C1 range)
     * -- plus a BOM, never trusted raw -- are re-escaped as YAML
     * backslash-u escapes, which the reader resolves back to the exact
     * scalar.
     */
    private static String quoted(String text) {
        // JsonWriter.write appends exactly one trailing line break.
        String written = JsonWriter.write(new JsonValue.JsonString(text));
        StringBuilder out = new StringBuilder(written.length());
        for (int i = 0; i < written.length(); i++) {
            char c = written.charAt(i);
            out.append(needsYamlEscape(c) ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    private static boolean needsYamlEscape(char c) {
        return c == '\u007F' || (c >= '\u0080' && c <= '\u009F')
                || c == '\u2028' || c == '\u2029' || c == '\uFEFF';
    }

    /** A flow-style list of quoted entries: valid inside both root styles. */
    private static String list(List<String> entries) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(quoted(entries.get(i)));
        }
        return out.append(']').toString();
    }

    /**
     * Snapshots a caller-supplied list, refusing null entries with an
     * explicit message instead of the bare {@code NullPointerException}
     * a bulk copy would throw on them.
     */
    private static List<String> snapshotEntries(List<String> entries, String field) {
        if (entries == null) {
            return null; // Null means "leave unchanged", which is not empty.
        }
        for (String entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Front-matter field '" + field + "' must not contain null entries");
            }
        }
        return List.copyOf(entries);
    }
}
