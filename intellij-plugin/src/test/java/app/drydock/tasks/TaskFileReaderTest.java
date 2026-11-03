package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskFileReaderTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final TaskId ID = TaskId.of("fix-login-redirect-k3f9");
    private static final Instant FALLBACK = Instant.parse("2026-10-28T00:00:00Z");

    private static final String P1 = "---\n"
            + "title: Fix login redirect\n"
            + "status: open            # open | started | review | done | dropped\n"
            + "done_by: human          # agent | human (default human)\n"
            + "created: 2026-10-29T10:12:00Z\n"
            + "after: [api-schema-x81c, auth-lib:token-refresh-a2b0]\n"
            + "sessions: []            # session ids, appended by Drydock\n"
            + "---\n"
            + "Free markdown. [[api-schema-x81c]] links are clickable but create no relation.\n";

    private static TaskFileReader.Parsed parse(String source) throws TaskFormatException {
        return TaskFileReader.parse(ID, REPO, source, FALLBACK);
    }

    private static String spanText(String source, TaskFileReader.Span span) {
        return source.substring(span.start(), span.end());
    }

    @Test
    void p1SampleDocumentIsAcceptedWithSpansAndRawEnvelope() throws Exception {
        TaskFileReader.Parsed parsed = parse(P1);
        TaskDocument doc = parsed.document();
        assertEquals("Fix login redirect", doc.title());
        assertEquals(TaskStatus.OPEN, doc.status());
        assertEquals(DoneBy.HUMAN, doc.doneBy());
        assertEquals(Optional.of(Instant.parse("2026-10-29T10:12:00Z")), doc.created());
        assertEquals(List.of("api-schema-x81c", "auth-lib:token-refresh-a2b0"), doc.after());
        assertEquals(List.of(), doc.sessions());
        String frontMatter = "title: Fix login redirect\n"
                + "status: open            # open | started | review | done | dropped\n"
                + "done_by: human          # agent | human (default human)\n"
                + "created: 2026-10-29T10:12:00Z\n"
                + "after: [api-schema-x81c, auth-lib:token-refresh-a2b0]\n"
                + "sessions: []            # session ids, appended by Drydock\n";
        assertEquals(frontMatter, doc.rawFrontMatter());
        assertEquals("Free markdown. [[api-schema-x81c]] links are clickable but create no relation.\n",
                doc.rawBody());
        assertEquals(Set.of("title", "status", "done_by", "created", "after", "sessions"),
                parsed.knownKeySpans().keySet());
        Map<String, TaskFileReader.Span> spans = parsed.knownKeySpans();
        assertEquals("title: Fix login redirect", spanText(P1, spans.get("title")));
        assertEquals("status: open", spanText(P1, spans.get("status")));
        assertEquals("done_by: human", spanText(P1, spans.get("done_by")));
        assertEquals("created: 2026-10-29T10:12:00Z", spanText(P1, spans.get("created")));
        assertEquals("after: [api-schema-x81c, auth-lib:token-refresh-a2b0]", spanText(P1, spans.get("after")));
        assertEquals("sessions: []", spanText(P1, spans.get("sessions")));
        assertEquals(frontMatter, spanText(P1, parsed.frontMatter()));
        // Spans are real UTF-16 offsets into the original source, not parser marks.
        assertEquals(P1.indexOf("status: open"), spans.get("status").start());
        assertEquals(P1.indexOf("sessions: []"), spans.get("sessions").start());
    }

    @Test
    void minimalDocumentUsesDefaultsAndTheSuppliedCreationTime() throws Exception {
        TaskFileReader.Parsed parsed = parse("---\ntitle: Just a title\n---\nBody line.\n");
        TaskDocument doc = parsed.document();
        assertEquals(TaskStatus.OPEN, doc.status());
        assertEquals(DoneBy.HUMAN, doc.doneBy());
        assertEquals(Optional.of(FALLBACK), doc.created());
        assertEquals(List.of(), doc.after());
        assertEquals(List.of(), doc.sessions());
        assertEquals("Body line.\n", doc.rawBody());
        assertEquals(Set.of("title"), parsed.knownKeySpans().keySet());
        assertEquals("title: Just a title", spanText("---\ntitle: Just a title\n---\nBody line.\n",
                parsed.knownKeySpans().get("title")));
    }

    @Test
    void missingCreatedWithoutAFallbackStaysEmpty() throws Exception {
        TaskFileReader.Parsed parsed = TaskFileReader.parse(ID, REPO, "---\ntitle: t\n---\n", null);
        assertEquals(Optional.empty(), parsed.document().created());
    }

    @Test
    void blockAndFlowListsAndQuotedColonsAreAccepted() throws Exception {
        String source = "---\n"
                + "title: 'Weird: title'\n"
                + "after:\n"
                + "  - api-schema-x81c\n"
                + "  - auth-lib:token-refresh-a2b0\n"
                + "sessions: [s1, s2]\n"
                + "anchor: &a kept-as-unknown\n"
                + "---\n";
        TaskFileReader.Parsed parsed = parse(source);
        TaskDocument doc = parsed.document();
        assertEquals("Weird: title", doc.title());
        assertEquals(List.of("api-schema-x81c", "auth-lib:token-refresh-a2b0"), doc.after());
        assertEquals(List.of("s1", "s2"), doc.sessions());
        assertEquals("after:\n  - api-schema-x81c\n  - auth-lib:token-refresh-a2b0",
                spanText(source, parsed.knownKeySpans().get("after")));
        assertEquals("sessions: [s1, s2]", spanText(source, parsed.knownKeySpans().get("sessions")));
        assertEquals("title: 'Weird: title'", spanText(source, parsed.knownKeySpans().get("title")));
    }

    @Test
    void unknownKeysCommentsAndBodySurviveByteForByte() throws Exception {
        String front = "# leading comment\r\n"
                + "title: T\r\n"
                + "extra:\r\n"
                + "  nested: {a: 1, b: [x, y]}\r\n"
                + "  keep: 'me'\r\n"
                + "# trailing comment\r\n";
        String body = "ünïcode ✓ body \uD83E\uDDE9 with\r\nCRLF and [[api-schema-x81c]]\r\n";
        String source = "---\r\n" + front + "---\r\n" + body;
        TaskFileReader.Parsed parsed = parse(source);
        TaskDocument doc = parsed.document();
        assertEquals(front, doc.rawFrontMatter());
        assertEquals(body, doc.rawBody());
        assertEquals(Set.of("title"), parsed.knownKeySpans().keySet());
        assertEquals("title: T", spanText(source, parsed.knownKeySpans().get("title")));
    }

    @Test
    void astralCharactersDoNotShiftSpans() throws Exception {
        // One astral code point = two UTF-16 chars: a naive "mark index ==
        // String offset" assumption shifts every later span by one per
        // preceding astral character.
        String source = "---\n"
                + "# \uD83D\uDE00 comment before a known key\n"
                + "status: started\n"
                + "title: Fix \uD83E\uDDE9 board\n"
                + "---\n"
                + "body \uD83D\uDCBB\n";
        TaskFileReader.Parsed parsed = parse(source);
        Map<String, TaskFileReader.Span> spans = parsed.knownKeySpans();
        assertEquals("status: started", spanText(source, spans.get("status")));
        assertEquals(source.indexOf("status: started"), spans.get("status").start());
        assertEquals("title: Fix \uD83E\uDDE9 board", spanText(source, spans.get("title")));
        assertEquals(source.indexOf("title: Fix \uD83E\uDDE9 board"), spans.get("title").start());
        assertEquals("Fix \uD83E\uDDE9 board", parsed.document().title());
        assertEquals("body \uD83D\uDCBB\n", parsed.document().rawBody());
    }

    @Test
    void parsedKnownKeySpansAreImmutableAndCopiedFromCallerMaps() throws Exception {
        // A normally parsed result hands out an unmodifiable map.
        TaskFileReader.Parsed parsed = parse(P1);
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.knownKeySpans().remove("title"));
        // The canonical constructor copies a caller-owned map, so mutating
        // the original afterwards must not leak into Parsed.
        Map<String, TaskFileReader.Span> owned = new LinkedHashMap<>();
        owned.put("title", new TaskFileReader.Span(0, 5));
        TaskFileReader.Parsed defensive = new TaskFileReader.Parsed(parsed.document(), owned,
                new TaskFileReader.Span(0, 7));
        owned.put("rogue", new TaskFileReader.Span(6, 9));
        owned.put("title", new TaskFileReader.Span(100, 200));
        assertEquals(Set.of("title"), defensive.knownKeySpans().keySet());
        assertEquals(new TaskFileReader.Span(0, 5), defensive.knownKeySpans().get("title"));
        // Front-matter span and document data are retained regardless.
        assertEquals(parsed.document(), defensive.document());
        assertEquals(new TaskFileReader.Span(0, 7), defensive.frontMatter());
        assertThrows(UnsupportedOperationException.class,
                () -> defensive.knownKeySpans().put("x", new TaskFileReader.Span(0, 1)));
    }

    @Test
    void collectionBoundsCoverRootAndNestedMappings() throws Exception {
        // Root mapping exactly at the bound (title + 999 unknown keys) parses.
        TaskFileReader.Parsed atLimit = parse("---\ntitle: t\n"
                + mappingEntries(TaskFileReader.MAX_COLLECTION_SIZE - 1, "") + "---\n");
        assertEquals("t", atLimit.document().title());
        assertEquals(TaskStatus.OPEN, atLimit.document().status());
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: t\n"
                        + mappingEntries(TaskFileReader.MAX_COLLECTION_SIZE, "") + "---\n"),
                "oversized root mapping");
        // Same bound one level down, inside an unknown key's value.
        assertDoesNotThrowParse(
                "---\ntitle: t\nextra:\n"
                        + mappingEntries(TaskFileReader.MAX_COLLECTION_SIZE, "  ") + "---\n",
                "nested mapping within the limit");
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: t\nextra:\n"
                        + mappingEntries(TaskFileReader.MAX_COLLECTION_SIZE + 1, "  ") + "---\n"),
                "oversized nested mapping");
    }

    private static String mappingEntries(int count, String indent) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < count; i++) {
            entries.append(indent).append("k").append(i).append(": v\n");
        }
        return entries.toString();
    }

    @Test
    void multilineTitleAndBlockListKeepExactEntrySpans() throws Exception {
        String front = "title: Fix login\n"
                + "  redirect\n"
                + "after:\n"
                + "  - one\n"
                + "  - two\n"
                + "# trailing comment\n"
                + "extra: unknown\n";
        String source = "---\n" + front + "---\nBody stays whole.\n";
        TaskFileReader.Parsed parsed = parse(source);
        Map<String, TaskFileReader.Span> spans = parsed.knownKeySpans();
        assertEquals(Set.of("title", "after"), spans.keySet());
        // Spans stop at the value's last content character: neither the
        // trailing comment nor the unknown field is swallowed.
        assertEquals("title: Fix login\n  redirect", spanText(source, spans.get("title")));
        assertEquals("after:\n  - one\n  - two", spanText(source, spans.get("after")));
        assertEquals("Fix login redirect", parsed.document().title());
        assertEquals(List.of("one", "two"), parsed.document().after());
        assertEquals(front, parsed.document().rawFrontMatter());
        assertEquals("Body stays whole.\n", parsed.document().rawBody());
    }

    @Test
    void malformedDocumentsAreRejectedVisibly() {
        String[][] cases = {
                {"no opening fence", "title: t\n---\nbody"},
                {"no closing fence", "---\ntitle: t\nbody"},
                {"fence with trailing junk", "--- junk\ntitle: t\n---\n"},
                {"opening fence is not alone", "----\ntitle: t\n---\n"},
                {"empty front matter", "---\n---\n"},
                {"non-mapping front matter", "---\n- a\n- b\n---\n"},
                {"missing title", "---\nstatus: open\n---\n"},
                {"blank title", "---\ntitle: ''\n---\n"},
                {"duplicate known key", "---\ntitle: a\ntitle: b\n---\n"},
                {"duplicate unknown key", "---\ntitle: a\nx: 1\nx: 2\n---\n"},
                {"unknown status", "---\ntitle: a\nstatus: blocked\n---\n"},
                {"null status", "---\ntitle: a\nstatus:\n---\n"},
                {"non-string status", "---\ntitle: a\nstatus: 3\n---\n"},
                {"unknown done_by", "---\ntitle: a\ndone_by: team\n---\n"},
                {"non-instant created", "---\ntitle: a\ncreated: yesterday\n---\n"},
                {"numeric created", "---\ntitle: a\ncreated: 123456\n---\n"},
                {"scalar after", "---\ntitle: a\nafter: x\n---\n"},
                {"non-string list entry", "---\ntitle: a\nafter: [x, [y]]\n---\n"},
                {"mapping as title", "---\ntitle:\n  a: 1\n---\n"},
                {"second yaml document", "---\ntitle: a\n...\ntitle: b\n---\n"},
                {"complex key", "---\ntitle: a\n? [k]\n: v\n---\n"},
        };
        for (String[] oneCase : cases) {
            TaskFormatException failure = assertThrows(TaskFormatException.class,
                    () -> parse(oneCase[1]), () -> "expected rejection: " + oneCase[0]);
            assertNotNull(failure.getMessage());
            assertFalse(failure.getMessage().isBlank(), () -> "visible message: " + oneCase[0]);
        }
    }

    @Test
    void resourceBoundsRefuseUnboundedInput() {
        String deep = "[".repeat(64) + "]".repeat(64);
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: a\nextra: " + deep + "\n---\n"), "deep nesting");
        String shallow = "[".repeat(25) + "]".repeat(25);
        assertDoesNotThrowParse("---\ntitle: a\nextra: " + shallow + "\n---\n", "nesting within the limit");
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: &a t\nstatus: *a\n---\n"), "scalar alias");
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: t\na: &x [1, 2]\nb: [*x, *x]\n---\n"), "collection alias");
        String bigList = String.join(", ", Collections.nCopies(TaskFileReader.MAX_COLLECTION_SIZE + 1, "x"));
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: t\nafter: [" + bigList + "]\n---\n"), "oversized known list");
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: t\nextra: [" + bigList + "]\n---\n"), "oversized unknown collection");
        assertThrows(TaskFormatException.class,
                () -> parse("---\ntitle: t\n---\n" + "x".repeat(TaskFileReader.MAX_FILE_CODE_POINTS)),
                "oversized file");
    }

    private static void assertDoesNotThrowParse(String source, String what) {
        try {
            parse(source);
        } catch (TaskFormatException e) {
            throw new AssertionError("expected acceptance: " + what, e);
        }
    }
}
