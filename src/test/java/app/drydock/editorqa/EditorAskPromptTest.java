package app.drydock.editorqa;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskLimits.RejectedInputException;
import app.drydock.editorqa.EditorAskPrompt.Envelope;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composed prompt is one line of printable ASCII that starts with a
 * letter, carries the minted ID, and its envelope decodes back to exactly
 * the submitted fields -- tabs, quotes, emoji, CR/LF, leading {@code /} and
 * {@code !} included.
 */
class EditorAskPromptTest {

    private static EditorAskRecord record(String selectedText, String question) {
        return new EditorAskRecord(EditorAskRecord.newId(), 7, ManagedSessionId.newId(), "src/Main.java",
                new EditorAskRecord.TextRange(10, 10 + selectedText.length(), 2, 4, 3, 9),
                selectedText, question, Instant.EPOCH, EditorAskRecord.Status.QUEUED,
                null, null, null, null);
    }

    @Test
    void promptIsOnePrintableAsciiLineStartingWithALetter() {
        String prompt = EditorAskPrompt.compose(record("return x + 1;", "what does this return?"));
        EditorAskLimits.checkPrompt(prompt);
        char first = prompt.charAt(0);
        assertTrue(Character.isLetter(first), () -> "prompt must start with a letter, got '" + first + "'");
        for (int i = 0; i < prompt.length(); i++) {
            final char c = prompt.charAt(i);
            final int at = i;
            assertTrue(c >= 0x20 && c <= 0x7E,
                    () -> "raw non-printable or non-ASCII character at index " + at + ": U+"
                            + String.format("%04X", (int) c));
        }
    }

    @Test
    void promptNamesTheToolOnceAndCarriesTheMintedId() {
        EditorAskRecord ask = record("x", "why?");
        String prompt = EditorAskPrompt.compose(ask);
        assertEquals(1, countOccurrences(prompt, "editor_ask_answer"));
        assertEquals(1, countOccurrences(prompt, ask.id()));
        assertTrue(prompt.contains("questionId\":\"" + ask.id()),
                () -> "the minted ID must appear as the envelope's questionId");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void envelopeRoundTripsTabsQuotesBackslashesAndLeadingTuiCharacters() {
        String selected = "a\tb\"c\\d/e!f\n\r second line";
        String question = "/what about this? !not-bash #not-memory";
        String prompt = EditorAskPrompt.compose(record(selected, question));
        Envelope envelope = EditorAskPrompt.decode(prompt);
        assertEquals(selected, envelope.text());
        assertEquals(question, envelope.question());
        assertEquals("src/Main.java", envelope.path());
        assertEquals(10, envelope.range().startOffset());
        assertEquals(10 + selected.length(), envelope.range().endOffset());
    }

    @Test
    void envelopeRoundTripsEmojiAndSupplementaryCharacters() {
        String selected = "call(\uD83D\uDE00, \"\uD83E\uDD16\");";
        String question = "what does \uD83E\uDD16 mean here?";
        String prompt = EditorAskPrompt.compose(record(selected, question));
        Envelope envelope = EditorAskPrompt.decode(prompt);
        assertEquals(selected, envelope.text());
        assertEquals(question, envelope.question());
    }

    @Test
    void envelopeRoundTripsExactRangeAndPath() {
        String selected = "int x";
        EditorAskRecord ask = new EditorAskRecord(EditorAskRecord.newId(), 0, ManagedSessionId.newId(),
                "weird but legal/naïve/файл.java",
                new EditorAskRecord.TextRange(100, 105, 9, 20, 10, 2),
                selected, "types?", Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null, null);
        Envelope envelope = EditorAskPrompt.decode(EditorAskPrompt.compose(ask));
        assertEquals(ask.relativeFile(), envelope.path());
        assertEquals(100, envelope.range().startOffset());
        assertEquals(105, envelope.range().endOffset());
        assertEquals(9, envelope.range().startLine());
        assertEquals(20, envelope.range().startColumn());
        assertEquals(10, envelope.range().endLine());
        assertEquals(2, envelope.range().endColumn());
    }

    @Test
    void composedPromptNeverContainsRawLfCrOrEsc() {
        EditorAskRecord ask = record("a\nb\rc\td with line breaks, escaped not raw", "q");
        String prompt = EditorAskPrompt.compose(ask);
        for (char c : prompt.toCharArray()) {
            assertNotEquals('\n', c);
            assertNotEquals('\r', c);
            assertNotEquals('\u001B', c);
        }
    }

    @Test
    void anOversizeEscapedSelectionIsRefusedNotClipped() {
        // 4,096 emoji are within the 16 KiB selection bound (4 UTF-8 bytes
        // each) but escape to 12 ASCII characters each, past the 32 KiB prompt.
        String selected = "\uD83D\uDE00".repeat(EditorAskLimits.MAX_SELECTION_BYTES / 4);
        assertEquals(EditorAskLimits.MAX_SELECTION_BYTES,
                selected.getBytes(StandardCharsets.UTF_8).length);
        EditorAskRecord ask = record(selected, "q");
        assertThrows(RejectedInputException.class, () -> EditorAskPrompt.compose(ask));
        // The record still exists unharmed: nothing was clipped.
        assertEquals(selected, ask.selectedText());
    }

    @Test
    void decodeRejectsPromptsWithoutAnEnvelope() {
        assertThrows(IllegalArgumentException.class, () -> EditorAskPrompt.decode("no envelope here"));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskPrompt.decode("Answer this. Envelope: {not json"));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskPrompt.decode("Answer this. Envelope: []"));
    }

    @Test
    void envelopeSurvivesAStrictParserRoundTripOnItsOwn() {
        EditorAskRecord ask = record("tab\tquote\"slash\\\\", "why\nnot?");
        String prompt = EditorAskPrompt.compose(ask);
        String envelope = prompt.substring(prompt.indexOf('{', prompt.indexOf(EditorAskPrompt.ENVELOPE_MARKER)));
        JsonValue parsed = JsonParser.parseStrict(envelope, 8);
        assertTrue(parsed instanceof JsonValue.JsonObject);
    }

    @Test
    void tabPromptOmitsPathRangeAndTextAndStaysOnePrintableAsciiLine() {
        EditorAskRecord tab = new EditorAskRecord(EditorAskRecord.newId(), 3, ManagedSessionId.newId(),
                null, null, null, "what is \u00e9 here?\n", Instant.EPOCH, EditorAskRecord.Status.QUEUED,
                null, null, null, null);
        String prompt = EditorAskPrompt.compose(tab);
        for (char c : prompt.toCharArray()) {
            assertTrue(c >= 0x20 && c <= 0x7E, "non-printable-ASCII char in tab prompt");
        }
        assertTrue(Character.isLetter(prompt.charAt(0)));
        assertTrue(prompt.contains(tab.id()));
        assertTrue(!prompt.contains("\"path\"") && !prompt.contains("\"range\"") && !prompt.contains("\"text\""));
        assertTrue(!prompt.contains("selected text") && !prompt.contains("file named"));
        Envelope e = EditorAskPrompt.decode(prompt);
        assertEquals(tab.id(), e.questionId());
        assertEquals(tab.question(), e.question());
        assertEquals(null, e.path());
        assertEquals(null, e.range());
        assertEquals(null, e.text());
    }

    @Test
    void partialNullTabShapeIsRefusedAndTabCannotCarryAnAnchor() {
        ManagedSessionId owner = ManagedSessionId.newId();
        assertThrows(NullPointerException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 1, owner,
                "a.java", null, null, "q", Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 1, owner,
                null, null, "x", "q", Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 1, owner,
                null, null, null, "q", Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null,
                JsonParser.parseStrict("{}", 2)));
    }
}
