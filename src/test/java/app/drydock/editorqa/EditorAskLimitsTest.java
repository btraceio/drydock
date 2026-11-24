package app.drydock.editorqa;

import java.time.Instant;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskLimits.RejectedInputException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bound of {@link EditorAskLimits} at its exact limit and one past
 * it, the character-safety rules, and the record-level integrity invariants
 * (canonical ID, answer/reason/status agreement) that rest on those limits.
 */
class EditorAskLimitsTest {

    @Test
    void rootAtLimitPassesAndOneByteMoreIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_ROOT_BYTES);
        assertSame(atLimit, EditorAskLimits.checkRoot(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkRoot("a".repeat(EditorAskLimits.MAX_ROOT_BYTES + 1)));
    }

    @Test
    void relativePathAtLimitPassesAndOneByteMoreIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_RELATIVE_PATH_BYTES);
        assertSame(atLimit, EditorAskLimits.checkRelativePath(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkRelativePath("a".repeat(EditorAskLimits.MAX_RELATIVE_PATH_BYTES + 1)));
    }

    @Test
    void selectionAtLimitPassesAndOneByteMoreIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_SELECTION_BYTES);
        assertSame(atLimit, EditorAskLimits.checkSelection(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkSelection("a".repeat(EditorAskLimits.MAX_SELECTION_BYTES + 1)));
    }

    @Test
    void questionAtLimitPassesAndOneByteMoreIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_QUESTION_BYTES);
        assertSame(atLimit, EditorAskLimits.checkQuestion(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkQuestion("a".repeat(EditorAskLimits.MAX_QUESTION_BYTES + 1)));
    }

    @Test
    void modelAtLimitPassesOneByteMoreAndMultibyteOverrunIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_MODEL_BYTES);
        assertSame(atLimit, EditorAskLimits.checkModel(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkModel("a".repeat(EditorAskLimits.MAX_MODEL_BYTES + 1)));
        // 128 two-byte characters are exactly 256 UTF-8 bytes; one more is over.
        assertEquals("é".repeat(128), EditorAskLimits.checkModel("é".repeat(128)));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkModel("é".repeat(129)));
    }

    @Test
    void answerBoundIsPromptSafetySPsEightThousandUtf16Units() {
        String atLimit = "a".repeat(8000);
        assertSame(atLimit, EditorAskLimits.checkAnswer(atLimit));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkAnswer("a".repeat(8001)));
    }

    @Test
    void reasonAtLimitPassesAndOneUnitMoreIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_REASON_CHARS);
        assertSame(atLimit, EditorAskLimits.checkReason(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkReason("a".repeat(EditorAskLimits.MAX_REASON_CHARS + 1)));
    }

    @Test
    void promptAtLimitPassesAndOneCharacterMoreIsRejected() {
        String atLimit = "a".repeat(EditorAskLimits.MAX_PROMPT_BYTES);
        assertSame(atLimit, EditorAskLimits.checkPrompt(atLimit));
        assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkPrompt("a".repeat(EditorAskLimits.MAX_PROMPT_BYTES + 1)));
    }

    @Test
    void selectionMayBeWhitespaceButNeverEmpty() {
        assertSame(" ", EditorAskLimits.checkSelection(" "));
        assertSame("\t\n", EditorAskLimits.checkSelection("\t\n"));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkSelection(""));
    }

    @Test
    void questionMustBeNonblank() {
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkQuestion(" "));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkQuestion("\t\n"));
    }

    @Test
    void textDataAllowsLfCrTabAndRejectsEveryOtherControl() {
        assertSame("a\nb\rc\td", EditorAskLimits.checkSelection("a\nb\rc\td"));
        assertSame("why?\nsecond line", EditorAskLimits.checkQuestion("why?\nsecond line"));
        assertSame("keep\r\nthis\ttoo", EditorAskLimits.checkAnswer("keep\r\nthis\ttoo"));
        for (String hostile : new String[] {"a\u0000b", "a\u001Bb", "a\u0001b", "a\u007Fb", "a\u0085b"}) {
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkSelection(hostile),
                    () -> "should reject U+" + String.format("%04X", (int) hostile.charAt(1)));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkQuestion(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkAnswer(hostile));
        }
    }

    @Test
    void pathRootAndModelRejectAllLineAndTerminalControls() {
        for (String hostile : new String[] {"a\nb", "a\rb", "a\tb", "a\u001Bb", "a\u0000b"}) {
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkRelativePath(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkRoot(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkModel(hostile));
        }
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkRoot(" "));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkRelativePath(" "));
    }

    @Test
    void unpairedSurrogatesAreRejectedButSupplementaryCharactersPass() {
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkSelection("a\uD800b"));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkSelection("a\uDC00b"));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkQuestion("\uD83D"));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkAnswer("a\uDE00b"));
        assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkRelativePath("a\uD800b"));
        String emoji = "family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66";
        assertSame(emoji, EditorAskLimits.checkSelection(emoji));
    }

    @Test
    void bidiControlsAreRejectedInEveryField() {
        for (String hostile : new String[] {"a\u202Eb", "a\u2066b", "a\u200Fb", "a\u061Cb"}) {
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkSelection(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkQuestion(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkAnswer(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkModel(hostile));
            assertThrows(RejectedInputException.class, () -> EditorAskLimits.checkRelativePath(hostile));
        }
    }

    @Test
    void blankOrNullModelMeansUnset() {
        assertEquals(null, EditorAskLimits.checkModel(null));
        assertEquals("", EditorAskLimits.checkModel(""));
        assertEquals("  ", EditorAskLimits.checkModel("  "));
    }

    @Test
    void answerDelegatesToPromptSafetyForControls() {
        // checkInboundText's own message, rethrown unchecked.
        RejectedInputException e = assertThrows(RejectedInputException.class,
                () -> EditorAskLimits.checkAnswer("a\u0001b"));
        assertTrue(e.getMessage().contains("control characters"));
    }

    // ---- record-level integrity, built on the limits ----

    private static EditorAskRecord record(EditorAskRecord.Status status, String reason, String answer, Instant answeredAt) {
        return new EditorAskRecord(EditorAskRecord.newId(), 3, ManagedSessionId.newId(), "src/Main.java",
                new EditorAskRecord.TextRange(10, 15, 2, 4, 3, 9), "selected", "what does this do?",
                Instant.EPOCH, status, reason, answer, answeredAt, null);
    }

    @Test
    void newIdIsCanonicalAndParseIdAcceptsOnlyTheCanonicalForm() {
        for (int i = 0; i < 16; i++) {
            String minted = EditorAskRecord.newId();
            assertEquals(minted, EditorAskRecord.parseId(minted));
        }
        String canonical = "editor-0f6a5cd2-1b3e-4c8d-9e2f-a1b2c3d4e5f6";
        assertEquals(canonical, EditorAskRecord.parseId(canonical));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskRecord.parseId("editor-0F6A5CD2-1B3E-4C8D-9E2F-A1B2C3D4E5F6"));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskRecord.parseId("{editor-0f6a5cd2-1b3e-4c8d-9e2f-a1b2c3d4e5f6}"));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskRecord.parseId("urn:uuid:0f6a5cd2-1b3e-4c8d-9e2f-a1b2c3d4e5f6"));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskRecord.parseId("0f6a5cd2-1b3e-4c8d-9e2f-a1b2c3d4e5f6"));
        assertThrows(IllegalArgumentException.class, () -> EditorAskRecord.parseId("editor-nothex"));
        assertThrows(NullPointerException.class, () -> EditorAskRecord.parseId(null));
    }

    @Test
    void recordConstructorEnforcesTheLimits() {
        assertThrows(RejectedInputException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 0,
                ManagedSessionId.newId(), "src/Main.java",
                new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1),
                "a".repeat(EditorAskLimits.MAX_SELECTION_BYTES + 1), "q", Instant.EPOCH,
                EditorAskRecord.Status.QUEUED, null, null, null, null));
        assertThrows(RejectedInputException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 0,
                ManagedSessionId.newId(), "src/Main.java",
                new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1), "s",
                "q", Instant.EPOCH,
                EditorAskRecord.Status.ANSWERED, null, "a".repeat(8001),
                Instant.parse("2026-01-01T00:00:00Z"), null));
        assertThrows(RejectedInputException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 0,
                ManagedSessionId.newId(), "a\u0000b",
                new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1), "s", "q", Instant.EPOCH,
                EditorAskRecord.Status.QUEUED, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 0,
                ManagedSessionId.newId(), "src/Main.java",
                new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1), "s", "q", Instant.EPOCH,
                EditorAskRecord.Status.QUEUED, null, "a".repeat(8001), null, null));
    }

    @Test
    void recordConstructorEnforcesRangeStatusAnswerAndReasonAgreement() {
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), 0,
                ManagedSessionId.newId(), "src/Main.java",
                new EditorAskRecord.TextRange(5, 5, 0, 5, 0, 5), "s", "q", Instant.EPOCH,
                EditorAskRecord.Status.QUEUED, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> record(EditorAskRecord.Status.QUEUED, "no reason allowed here", null, null));
        assertThrows(IllegalArgumentException.class, () -> record(EditorAskRecord.Status.AWAITING, null, "early", null));
        assertThrows(NullPointerException.class, () -> record(EditorAskRecord.Status.ANSWERED, null, "a", null));
        assertNotNull(record(EditorAskRecord.Status.FAILED_NOT_SENT, "harness refused the write", null, null));
        assertNotNull(record(EditorAskRecord.Status.ANSWERED, null, "the answer", Instant.parse("2026-01-01T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRecord("not-canonical", 0,
                ManagedSessionId.newId(), "f", new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1),
                "s", "q", Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRecord(EditorAskRecord.newId(), -1,
                ManagedSessionId.newId(), "f", new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1),
                "s", "q", Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null, null));
    }

    @Test
    void settledAndAnsweredPreserveIdentityAndData() {
        EditorAskRecord base = record(EditorAskRecord.Status.AWAITING, null, null, null);
        EditorAskRecord failed = base.settled(EditorAskRecord.Status.FAILED_NOT_SENT, "not sent");
        assertEquals(EditorAskRecord.Status.FAILED_NOT_SENT, failed.status());
        assertEquals("not sent", failed.failureReason());
        assertEquals(base.id(), failed.id());
        assertEquals(base.acceptanceSequence(), failed.acceptanceSequence());
        assertEquals(base.selectedText(), failed.selectedText());
        assertTrue(failed.isSettled());
        assertTrue(base.isSettled() == base.status().isSettled());

        EditorAskRecord answered = base.answered("the full answer", Instant.parse("2026-01-02T03:04:05Z"));
        assertEquals(EditorAskRecord.Status.ANSWERED, answered.status());
        assertEquals("the full answer", answered.answer());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), answered.answeredAt());
        assertTrue(answered.isSettled());
    }
}
