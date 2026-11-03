package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance fixtures for {@link TaskFilePatch}, ported from the executed
 * T03a/T03b smoke harnesses. Assertions are byte equality on exact outputs
 * plus an independent reparse through {@link TaskFileReader} -- never the
 * patcher's own view of its result.
 */
class TaskFilePatchTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final TaskId ID = TaskId.of("fix-login-redirect-k3f9");
    private static final Instant CREATED = Instant.parse("2026-10-29T10:12:00Z");
    private static final String FLOW_ROOT = "---\n{title: T, extra: {x: [1, 2]}}\n---\nB\n";

    private static final String P1 = "---\n"
            + "title: Fix login redirect\n"
            + "status: open            # open | started | review | done | dropped\n"
            + "done_by: human          # agent | human (default human)\n"
            + "created: 2026-10-29T10:12:00Z\n"
            + "after: [api-schema-x81c, auth-lib:token-refresh-a2b0]\n"
            + "sessions: []            # session ids, appended by Drydock\n"
            + "---\n"
            + "Free markdown. [[api-schema-x81c]] links.\n";

    private static TaskFileReader.Parsed parse(String source) throws TaskFormatException {
        return TaskFileReader.parse(ID, REPO, source, null);
    }

    private static TaskFilePatch.Updates up(String title, TaskStatus status, DoneBy doneBy, Instant created,
            List<String> after, List<String> sessions) {
        return new TaskFilePatch.Updates(title, status, doneBy, created, after, sessions);
    }

    private static TaskDocument patched(String source, TaskFilePatch.Updates updates) throws TaskFormatException {
        return parse(TaskFilePatch.patch(parse(source), source, updates)).document();
    }

    private static TaskDocument document(String title, Instant created, List<String> after, String rawBody) {
        return new TaskDocument(ID, REPO, title, TaskStatus.OPEN, DoneBy.HUMAN,
                created == null ? Optional.empty() : Optional.of(created), after, List.of(), "", rawBody);
    }

    /** Asserts a refusal with a visible, non-blank message for one case label. */
    private static void assertRefused(String what, Executable call) {
        TaskFormatException failure = assertThrows(TaskFormatException.class, call, what);
        assertNotNull(failure.getMessage(), what);
        assertFalse(failure.getMessage().isBlank(), what);
    }

    @Test
    void noopPatchIsByteIdentical() throws Exception {
        TaskFileReader.Parsed parsed = parse(P1);
        assertEquals(P1, TaskFilePatch.patch(parsed, P1, up(null, null, null, null, null, null)),
                "all-null updates change nothing");
        assertEquals(P1, TaskFilePatch.patch(parsed, P1,
                up("Fix login redirect", TaskStatus.OPEN, DoneBy.HUMAN, CREATED, null, null)),
                "updates equal to current values change nothing");
    }

    @Test
    void fieldChangesPreserveUnknownKeysCommentsQuotingAndBodyBytes() throws Exception {
        // The exact expected output is the source with only the two known-key
        // spans rewritten: nothing else may move by a single byte.
        String source = "---\n"
                + "title: Old\n"
                + "# a comment\n"
                + "status: open\n"
                + "extra:\n"
                + "  nested: {a: 1, b: [x, y]}\n"
                + "  keep: 'me'\n"
                + "after:\n"
                + "  - one\n"
                + "  - two\n"
                + "---\n"
                + "Body \uD83E\uDDE9 stays.\n";
        String expected = source
                .replace("title: Old", "title: \"New: title\"")
                .replace("status: open", "status: started");
        String out = TaskFilePatch.patch(parse(source), source,
                up("New: title", TaskStatus.STARTED, null, null, null, null));
        assertEquals(expected, out);
        TaskDocument doc = parse(out).document();
        assertEquals("New: title", doc.title());
        assertEquals(TaskStatus.STARTED, doc.status());
        assertEquals(List.of("one", "two"), doc.after());
    }

    @Test
    void missingKeysAreInsertedAtTheEndOfABlockRoot() throws Exception {
        String source = "---\ntitle: T\n---\nBody.\n";
        String out = TaskFilePatch.patch(parse(source), source,
                up(null, TaskStatus.REVIEW, DoneBy.AGENT, CREATED, List.of("dep-1"), null));
        assertEquals("---\ntitle: T\nstatus: review\ndone_by: agent\ncreated: 2026-10-29T10:12:00Z\n"
                + "after: [\"dep-1\"]\n---\nBody.\n", out);
        TaskDocument doc = parse(out).document();
        assertEquals(TaskStatus.REVIEW, doc.status());
        assertEquals(DoneBy.AGENT, doc.doneBy());
        assertEquals(Optional.of(CREATED), doc.created());
        assertEquals(List.of("dep-1"), doc.after());
    }

    @Test
    void flowRootsTakeInsertionsInsideTheBracesAndShareOffsetsWithReplacements() throws Exception {
        // Insertion goes just after the brace; a first-key replacement starts
        // at the same offset, and the two must apply together, not conflict.
        String both = TaskFilePatch.patch(parse(FLOW_ROOT), FLOW_ROOT,
                up("New", TaskStatus.STARTED, null, null, null, null));
        assertEquals("---\n{status: started, title: \"New\", extra: {x: [1, 2]}}\n---\nB\n", both);
        String insertOnly = TaskFilePatch.patch(parse(FLOW_ROOT), FLOW_ROOT,
                up(null, TaskStatus.STARTED, null, null, List.of("a", "b:c"), null));
        assertEquals("---\n{status: started, after: [\"a\", \"b:c\"], title: T, extra: {x: [1, 2]}}\n---\nB\n",
                insertOnly);
        TaskDocument doc = parse(both).document();
        assertEquals("New", doc.title());
        assertEquals(TaskStatus.STARTED, doc.status());
        assertEquals(List.of("a", "b:c"), parse(insertOnly).document().after());
        // A full quoted/astral title replacement in a flow root round-trips.
        String title = "Flow \"quoted\" \uD83E\uDDE9 title";
        String quoted = TaskFilePatch.patch(parse("---\n{title: T, status: open}\n---\nB\n"),
                "---\n{title: T, status: open}\n---\nB\n", up(title, TaskStatus.DONE, null, null, null, null));
        assertEquals("---\n{title: \"Flow \\\"quoted\\\" \uD83E\uDDE9 title\", status: done}\n---\nB\n", quoted);
        assertEquals(title, parse(quoted).document().title());
    }

    @Test
    void crlfLineEndingsSurviveReplacementAndInsertion() throws Exception {
        String source = "---\r\ntitle: T\r\n# \uD83D\uDE00 comment\r\nextra: {a: 1}\r\nstatus: open\r\n---\r\nB\u00F6dy \uD83D\uDCBB\r\n";
        assertEquals(source.replace("status: open", "status: started"),
                TaskFilePatch.patch(parse(source), source, up(null, TaskStatus.STARTED, null, null, null, null)),
                "only the status span changes; every CRLF and astral char survives");
        String minimal = "---\r\ntitle: T\r\n---\r\nB\r\n";
        assertEquals("---\r\ntitle: T\r\nstatus: started\r\n---\r\nB\r\n",
                TaskFilePatch.patch(parse(minimal), minimal, up(null, TaskStatus.STARTED, null, null, null, null)),
                "an inserted key uses the front matter's own CRLF ending");
    }

    @Test
    void flowRootPrefixesCommentAnchorBomAndYamlBreaksStillPatch() throws Exception {
        // A flow root may be preceded only by comments, blanks, an anchor or a
        // tag; a BOM and the non-LF YAML breaks NEL/LS/PS count as blanks.
        String[][] cases = {
                {"comment containing a brace", "---\n# config {not-a-mapping}\n{title: T}\n---\nB\n"},
                {"anchor", "---\n&root {title: T}\n---\nB\n"},
                {"byte order mark", "---\n\uFEFF{title: T}\n---\nB\n"},
                {"NEL break", "---\n\u0085{title: T}\n---\nB\n"},
                {"line separator", "---\n\u2028{title: T}\n---\nB\n"},
                {"paragraph separator", "---\n\u2029{title: T}\n---\nB\n"},
        };
        for (String[] oneCase : cases) {
            String out = TaskFilePatch.patch(parse(oneCase[1]), oneCase[1],
                    up(null, TaskStatus.STARTED, null, null, null, null));
            assertEquals(TaskStatus.STARTED, parse(out).document().status(),
                    () -> "inserted key parses: " + oneCase[0]);
            assertTrue(out.contains("{status: started, title: T}"),
                    () -> "insertion went inside the braces: " + oneCase[0]);
        }
    }

    @Test
    void staleSourcesAndBlankTitlesAreRefusedVisibly() throws Exception {
        String source = "---\ntitle: T\nextra: 1\n---\nBody\n";
        TaskFileReader.Parsed parsed = parse(source);
        TaskFilePatch.Updates changeStatus = up(null, TaskStatus.DONE, null, null, null, null);
        String[][] stale = {
                {"different opening-fence line ending", "---\r\n" + source.substring(4)},
                {"junk before the fence", "junk" + source},
                {"one appended body character", source + "x"},
        };
        for (String[] oneCase : stale) {
            assertRefused("expected refusal: " + oneCase[0],
                    () -> TaskFilePatch.patch(parsed, oneCase[1], changeStatus));
        }
        assertRefused("blank title refused",
                () -> TaskFilePatch.patch(parse(P1), P1, up(" ", null, null, null, null, null)));
    }

    @Test
    void updateListsAreSnapshottedAndNullEntriesAreRefused() throws Exception {
        List<String> mutable = new ArrayList<>(List.of("x"));
        TaskFilePatch.Updates updates = up(null, null, null, null, mutable, null);
        mutable.add("later-mutation");
        assertEquals(List.of("x"), patched("---\ntitle: T\n---\nB\n", updates).after(),
                "the patch writes the list as it was at construction");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> up(null, null, null, null, Arrays.asList("a", null), null));
        assertEquals("Front-matter field 'after' must not contain null entries", failure.getMessage());
    }

    @Test
    void yamlSensitiveScalarsRoundTripThroughTheSharedQuoting() throws Exception {
        // NEL/LS/PS are YAML line breaks, DEL/C1 are non-printables, a BOM is
        // never trusted raw: all must survive patch and newFile.
        String special = "a\u0085b\u007Fc\u009Fd\u2028e\u2029f\uFEFFg\"h\\i\nj\tk";
        assertEquals(special, patched("---\ntitle: T\n---\nB\n", up(special, null, null, null, null, null)).title());
        assertEquals(special, parse(TaskFilePatch.newFile(document(special, null, List.of(), ""))).document().title());
    }

    @Test
    void defaultedFieldsAreNotMaterializedButChangedOnesAreWritten() throws Exception {
        String source = "---\ntitle: t\n---\n";
        TaskFileReader.Parsed parsed = TaskFileReader.parse(ID, REPO, source, CREATED);
        assertEquals(source, TaskFilePatch.patch(parsed, source,
                up(null, TaskStatus.OPEN, DoneBy.HUMAN, CREATED, List.of(), null)),
                "defaults equal to the document change nothing and create no keys");
        assertEquals(Optional.of(Instant.parse("2026-02-02T00:00:00Z")),
                parse(TaskFilePatch.patch(parsed, source,
                        up(null, null, null, Instant.parse("2026-02-02T00:00:00Z"), null, null))).document().created());
    }

    @Test
    void multilineTitleAndBothListStylesAreReplacedSpanForSpan() throws Exception {
        String multiline = "---\ntitle: Fix login\n  redirect\nextra: x\n---\nB\n";
        assertEquals("---\ntitle: \"Fixed\"\nextra: x\n---\nB\n",
                TaskFilePatch.patch(parse(multiline), multiline, up("Fixed", null, null, null, null, null)));
        String blockList = "---\ntitle: t\nafter:\n  - one\n  - two\nextra: x\n---\nB\n";
        String afterOut = TaskFilePatch.patch(parse(blockList), blockList,
                up(null, null, null, null, List.of("three"), null));
        assertEquals("---\ntitle: t\nafter: [\"three\"]\nextra: x\n---\nB\n", afterOut);
        assertEquals(List.of("three"), parse(afterOut).document().after());
        String flowList = "---\ntitle: t\nsessions: [s1]\n---\nB\n";
        String sessionsOut = TaskFilePatch.patch(parse(flowList), flowList,
                up(null, null, null, null, null, List.of("s1", "s2")));
        assertEquals("---\ntitle: t\nsessions: [\"s1\", \"s2\"]\n---\nB\n", sessionsOut);
        assertEquals(List.of("s1", "s2"), parse(sessionsOut).document().sessions());
    }

    @Test
    void newFileRendersKnownKeysInCanonicalOrderAndRoundTrips() throws Exception {
        TaskDocument doc = document("Fix login redirect", CREATED,
                List.of("api-schema-x81c", "auth-lib:token-refresh-a2b0"), "Body line.\n");
        String file = TaskFilePatch.newFile(doc);
        assertEquals("---\n"
                + "title: \"Fix login redirect\"\n"
                + "status: open\n"
                + "done_by: human\n"
                + "created: 2026-10-29T10:12:00Z\n"
                + "after: [\"api-schema-x81c\", \"auth-lib:token-refresh-a2b0\"]\n"
                + "sessions: []\n"
                + "---\n"
                + "Body line.\n", file);
        TaskDocument back = parse(file).document();
        assertEquals(doc.title(), back.title());
        assertEquals(doc.status(), back.status());
        assertEquals(doc.doneBy(), back.doneBy());
        assertEquals(doc.created(), back.created());
        assertEquals(doc.after(), back.after());
        assertEquals(doc.sessions(), back.sessions());
        assertEquals("Body line.\n", back.rawBody());
    }

    @Test
    void newFileKeepsCreatedVisibleToANullFallbackReparse() throws Exception {
        // The patcher's own guard re-parses with document.created() as the
        // fallback, so a dropped 'created:' line would pass it unnoticed (the
        // T03b checker's residual case). An external reparse with a NULL
        // fallback has no such crutch and must still see the instant.
        assertEquals(Optional.of(CREATED),
                parse(TaskFilePatch.newFile(document("Residual", CREATED, List.of(), ""))).document().created());
    }

    @Test
    void newFileOmitsAbsentCreatedAndKeepsAstralScalarsAndBodiesVerbatim() throws Exception {
        String absent = TaskFilePatch.newFile(document("No created", null, List.of(), ""));
        assertFalse(absent.contains("created:"), "an absent created is not invented");
        assertTrue(absent.contains("after: []\nsessions: []\n"), "empty lists render flow style");
        assertEquals(Optional.empty(), parse(absent).document().created());
        assertEquals("Fix \uD83E\uDDE9 board",
                parse(TaskFilePatch.newFile(document("Fix \uD83E\uDDE9 board", null, List.of(), "")))
                        .document().title());
        assertTrue(TaskFilePatch.newFile(document("Body check", null, List.of(), "first\r\nsecond \r\n"))
                .endsWith("---\nfirst\r\nsecond \r\n"), "raw body bytes, including CRLF, are kept");
    }

    @Test
    void appendNotePlacesNotesExactlyByBodyShape() throws Exception {
        // Rows: label, source, note, expected. Every expected string is the
        // full byte-exact output, so placement, separators and untouched
        // prefix bytes are all pinned at once.
        String[][] cases = {
                {"empty body", "---\ntitle: T\n---\n", "note one",
                        "---\ntitle: T\n---\n## Session notes\nnote one\n"},
                {"unterminated body", "---\ntitle: T\n---\ntrailing text", "note one",
                        "---\ntitle: T\n---\ntrailing text\n\n## Session notes\nnote one\n"},
                {"terminated body", "---\ntitle: T\n---\nbody line\n", "note one",
                        "---\ntitle: T\n---\nbody line\n\n## Session notes\nnote one\n"},
                {"unterminated heading", "---\ntitle: T\n---\n## Session notes", "note one",
                        "---\ntitle: T\n---\n## Session notes\nnote one\n"},
                {"existing note gains a paragraph", "---\ntitle: T\n---\n## Session notes\nnote one\n",
                        "note two", "---\ntitle: T\n---\n## Session notes\nnote one\n\nnote two\n"},
                {"note lands before the next heading",
                        "---\ntitle: T\n---\n## Session notes\nnote one\n## Other\n", "note two",
                        "---\ntitle: T\n---\n## Session notes\nnote one\n\nnote two\n## Other\n"},
                {"third note keeps earlier notes ordered and separated",
                        "---\ntitle: T\n---\n## Session notes\nnote one\n\nnote two\n## Other\n", "note three",
                        "---\ntitle: T\n---\n## Session notes\nnote one\n\nnote two\n\nnote three\n## Other\n"},
                {"CRLF file keeps CRLF separators", "---\r\ntitle: T\r\n---\r\nbody\r\n", "note one",
                        "---\r\ntitle: T\r\n---\r\nbody\r\n\r\n## Session notes\r\nnote one\r\n"},
                {"front matter and body bytes untouched",
                        "---\ntitle: T  # keep comment\nextra: {a: [x]}\n---\nbody\n", "note one",
                        "---\ntitle: T  # keep comment\nextra: {a: [x]}\n---\nbody\n\n## Session notes\nnote one\n"},
        };
        for (String[] oneCase : cases) {
            assertEquals(oneCase[3], TaskFilePatch.appendNote(oneCase[1], oneCase[2]),
                    () -> "expected placement: " + oneCase[0]);
        }
    }

    @Test
    void appendNoteOnACrlfFileIntroducesNoBareLf() throws Exception {
        String out = TaskFilePatch.appendNote("---\r\ntitle: T\r\n---\r\nbody\r\n", "note one");
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') {
                assertTrue(out.charAt(i - 1) == '\r', "no bare LF introduced in a CRLF file");
            }
        }
    }

    @Test
    void appendNoteRefusesBlankAndMultiLineNotesVisibly() {
        assertRefused("blank note refused", () -> TaskFilePatch.appendNote("---\ntitle: T\n---\n", "  "));
        assertRefused("embedded line break refused",
                () -> TaskFilePatch.appendNote("---\ntitle: T\n---\n", "line one\nline two"));
    }
}
