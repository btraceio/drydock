package app.drydock.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFileEnvelopeTest {

    private static final int MAX = TaskFileEnvelope.MAX_FILE_CODE_POINTS;

    /** The front matter must map back onto the exact source slice at frontStart. */
    private static String frontSlice(String source, TaskFileEnvelope envelope) {
        return source.substring(envelope.frontStart(), envelope.frontStart() + envelope.frontMatter().length());
    }

    @Test
    void lfSourceSplitsIntoExactRawSlices() throws Exception {
        String front = "title: Fix login redirect\nstatus: open            # comment\n";
        String body = "Free markdown. [[api-schema-x81c]]\nSecond line.\n";
        String source = "---\n" + front + "---\n" + body;
        TaskFileEnvelope envelope = TaskFileEnvelope.parse(source);
        assertEquals(front, envelope.frontMatter());
        assertEquals(body, envelope.body());
        assertEquals(4, envelope.frontStart());
        assertEquals(front, frontSlice(source, envelope));
    }

    @Test
    void crlfSourceSplitsIntoExactRawSlices() throws Exception {
        String front = "# comment\r\ntitle: 'Weird: title'\r\nafter:\r\n  - a\r\n";
        String body = "ünïcode ✓ body \uD83E\uDDE9\r\nwith CRLF\r\n";
        String source = "---\r\n" + front + "---\r\n" + body;
        TaskFileEnvelope envelope = TaskFileEnvelope.parse(source);
        assertEquals(front, envelope.frontMatter());
        assertEquals(body, envelope.body());
        assertEquals(5, envelope.frontStart());
        assertEquals(front, frontSlice(source, envelope));
    }

    @Test
    void astralTextSurvivesAndFrontStartIsAUtf16Offset() throws Exception {
        String source = "---\n# \uD83D\uDE00 comment\ntitle: Fix \uD83E\uDDE9 board\n---\nbody \uD83D\uDCBB\n";
        TaskFileEnvelope envelope = TaskFileEnvelope.parse(source);
        assertEquals("# \uD83D\uDE00 comment\ntitle: Fix \uD83E\uDDE9 board\n", envelope.frontMatter());
        assertEquals("body \uD83D\uDCBB\n", envelope.body());
        assertEquals(4, envelope.frontStart());
        assertEquals(envelope.frontMatter(), frontSlice(source, envelope));
    }

    @Test
    void closingFenceAtEofYieldsAnEmptyBody() throws Exception {
        TaskFileEnvelope lf = TaskFileEnvelope.parse("---\ntitle: t\n---");
        assertEquals("title: t\n", lf.frontMatter());
        assertEquals("", lf.body());
        TaskFileEnvelope crlf = TaskFileEnvelope.parse("---\r\ntitle: t\r\n---\r");
        assertEquals("title: t\r\n", crlf.frontMatter());
        assertEquals("", crlf.body());
    }

    @Test
    void innerLineStartingWithDashesIsNotAFence() throws Exception {
        String source = "---\ntitle: a\n---- not a fence\n---\nbody\n";
        TaskFileEnvelope envelope = TaskFileEnvelope.parse(source);
        assertEquals("title: a\n---- not a fence\n", envelope.frontMatter());
        assertEquals("body\n", envelope.body());
    }

    @Test
    void malformedDelimitersAreRejectedVisibly() {
        String[][] cases = {
                {"no opening fence", "title: t\n---\nbody"},
                {"no closing fence", "---\ntitle: t\nbody"},
                {"fence with trailing junk", "--- junk\ntitle: t\n---\n"},
                {"opening fence is not alone", "----\ntitle: t\n---\n"},
                {"opening fence at eof", "---"},
                {"lone CR after opening fence", "---\r"},
                {"CR not followed by LF", "---\rX\ntitle: t\n---\n"},
        };
        for (String[] oneCase : cases) {
            TaskFormatException failure = assertThrows(TaskFormatException.class,
                    () -> TaskFileEnvelope.parse(oneCase[1]), () -> "expected rejection: " + oneCase[0]);
            assertNotNull(failure.getMessage());
            assertFalse(failure.getMessage().isBlank(), () -> "visible message: " + oneCase[0]);
        }
    }

    @Test
    void wholeFileCodePointBoundIsEnforced() throws Exception {
        // Exactly at the bound ("---\n---\n" is 8 code points) is accepted.
        TaskFileEnvelope atBound = TaskFileEnvelope.parse("---\n---\n" + "x".repeat(MAX - 8));
        assertEquals("", atBound.frontMatter());
        assertEquals(MAX - 8, atBound.body().length());
        // One code point over is refused for size, before any fence is examined.
        TaskFormatException over = assertThrows(TaskFormatException.class,
                () -> TaskFileEnvelope.parse("---\n---\n" + "x".repeat(MAX - 7)), "one code point over");
        assertTrue(over.getMessage().contains("code points"), () -> "size message: " + over.getMessage());
        // Astral pairs count one code point each, not two UTF-16 units:
        // this file's UTF-16 length exceeds MAX, its code-point count does not.
        TaskFileEnvelope astralWithin = TaskFileEnvelope.parse("---\n---\n" + "\uD83D\uDE00".repeat(MAX / 2 + 1));
        assertEquals(MAX / 2 + 1, astralWithin.body().codePointCount(0, astralWithin.body().length()));
    }
}
