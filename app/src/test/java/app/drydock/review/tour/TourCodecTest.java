package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewVerdict;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourCodecTest {

    private static final String ONE_STEP = """
            [{"id":"s1","title":"Guard","narrative":"Why.",
              "anchors":[{"file":"src/A.java","startKey":"n1","endKey":"n22"}],
              "checks":[{"id":"c1","kind":"predict","prompt":"What happens?",
                         "choices":[{"text":"throws"},{"text":"returns","at":{"file":"src/C.java","line":88}}],
                         "answer":1,"explanation":"It returns.",
                         "alternates":[{"id":"c1b","kind":"risk","prompt":"Name a risk.","explanation":"Empty."}]}]}]
            """;

    @Test
    void anAgentStepDecodesWithChoicesLocationsAndAlternates() throws Exception {
        List<TourStep> steps = TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP));
        TourCheck check = steps.getFirst().checks().getFirst();
        assertEquals(TourCheck.Kind.PREDICT, check.kind());
        assertEquals(1, check.answer().getAsInt());
        assertEquals(new TourCheck.Location("src/C.java", 88), check.choices().get(1).at().orElseThrow());
        assertEquals(TourCheck.Kind.RISK, check.alternates().getFirst().kind());
    }

    /**
     * The agent's drafts put the correct answer first, and a choice list
     * whose first option is always right teaches the reader to stop reading
     * the options. The agent boundary scatters the key's position,
     * deterministically from the check id and the answer key, moving the
     * key with its choice; the alternates scatter the same way.
     */
    @Test
    void theCorrectAnswerIsScatteredOffItsDraftedPosition() throws Exception {
        String drafted = """
                [{"id":"s1","title":"Guard","narrative":"Why.",
                  "anchors":[{"file":"src/A.java","startKey":"n1"}],
                  "checks":[{"id":"qx9","kind":"trace","prompt":"Which line makes this safe?",
                             "choices":[{"text":"the guard"},{"text":"the loop"},{"text":"the cast"}],
                             "answer":0,"explanation":"The guard.",
                             "alternates":[{"id":"qx9a","kind":"trace","prompt":"Again?",
                                "choices":[{"text":"yes"},{"text":"no"}],"answer":1,"explanation":"No."}]}]}]
                """;
        List<TourStep> steps = TourCodec.stepsFromAgent(JsonParser.parse(drafted));
        TourCheck check = steps.getFirst().checks().getFirst();
        int target = Math.floorMod("qx9".hashCode(), 3);
        assertEquals(target, check.answer().getAsInt(), "the position is derived from id and key");
        assertNotEquals(0, target, "for this id the key must not stay first");
        assertEquals("the guard", check.choices().get(target).text(), "the key moved with its choice");
        TourCheck alternate = check.alternates().getFirst();
        assertEquals("no", alternate.choices().get(alternate.answer().getAsInt()).text(),
                "the alternate's key follows its choice too");
        assertEquals(check, TourCodec.stepsFromAgent(JsonParser.parse(drafted)).getFirst().checks().getFirst(),
                "the same post scatters the same way every time");
    }

    /**
     * The store decodes persisted tours through {@code recordFromJson},
     * which must NOT scatter: what is stored was scattered when it was
     * posted, and a second pass would move the key out from under the
     * progress the reviewer already recorded against these positions.
     */
    @Test
    void thePersistedFormDecodesWithoutScatteringAgain() throws Exception {
        String stored = """
                {"tour":{"scopeId":"rs_1","fingerprint":"fp","steps":[{"id":"s1","title":"T","narrative":"N.",
                  "anchors":[{"file":"src/A.java","startKey":"n1"}],
                  "checks":[{"id":"qx9","kind":"trace","prompt":"P?","choices":[{"text":"a"},{"text":"b"}],
                             "answer":0,"explanation":"E."}]}]},
                 "progress":{},"hunkOverrides":{},"hunkRows":{},"reviewAnyway":false,"shelved":false}
                """;
        TourCheck check = TourCodec.recordFromJson(JsonParser.parse(stored)).orElseThrow()
                .tour().steps().getFirst().checks().getFirst();
        assertEquals(0, check.answer().getAsInt(), "stored tours come back exactly as stored");
        assertEquals("a", check.choices().getFirst().text());
    }

    @Test
    void endKeyDefaultsToStartKey() throws Exception {
        List<TourStep> steps = TourCodec.stepsFromAgent(JsonParser.parse(
                ONE_STEP.replace("\"startKey\":\"n1\",\"endKey\":\"n22\"", "\"startKey\":\"n3\"")));
        assertEquals("n3", steps.getFirst().anchors().getFirst().endKey());
    }

    @Test
    void anAnchorNoteDecodesAndRoundTripsThroughThePersistedForm() throws Exception {
        String withNote = ONE_STEP.replace("\"endKey\":\"n22\"",
                "\"endKey\":\"n22\",\"note\":\"The loop walks the inline chain.\"");
        TourStep step = TourCodec.stepsFromAgent(JsonParser.parse(withNote)).getFirst();
        assertEquals("The loop walks the inline chain.", step.anchors().getFirst().note());
        assertTrue(step.anchors().getFirst().hasNote());

        TourStep restored = TourCodec.stepFromJson(TourCodec.stepToJson(step), "steps[0]");
        assertEquals(step.anchors(), restored.anchors());
    }

    @Test
    void anAnchorWithoutANoteHasNoneAndWritesNoNoteMember() throws Exception {
        TourStep step = TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP)).getFirst();
        assertEquals("", step.anchors().getFirst().note());
        assertFalse(step.anchors().getFirst().hasNote());
        assertFalse(JsonWriter.write(TourCodec.stepToJson(step)).contains("\"note\""),
                "an old-shape step must persist byte-for-byte as it did before the field existed");
    }

    @Test
    void anOverlongAnchorNoteIsRejectedNamingItsPath() {
        String huge = ONE_STEP.replace("\"endKey\":\"n22\"",
                "\"endKey\":\"n22\",\"note\":\"" + "x".repeat(TourValidator.MAX_ANCHOR_NOTE + 1) + "\"");
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(huge)));
        assertTrue(error.getMessage().contains("steps[0].anchors[0].note"), error.getMessage());
    }

    @Test
    void aNonIntegerAnswerIsRejectedNamingItsPath() {
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP.replace("\"answer\":1", "\"answer\":\"1\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].answer"), error.getMessage());
    }

    @Test
    void anUnknownKindIsRejected() {
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP.replace("\"predict\"", "\"quiz\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].kind"), error.getMessage());
    }

    @Test
    void anOverlongStringIsRejectedBeforeDecoding() {
        String huge = ONE_STEP.replace("\"Why.\"", "\"" + "x".repeat(TourValidator.MAX_NARRATIVE + 1) + "\"");
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(huge)));
        assertTrue(error.getMessage().contains("steps[0].narrative"), error.getMessage());
    }

    @Test
    void stepsMustBeAnArray() {
        assertThrows(TourCodec.InvalidTour.class, () -> TourCodec.stepsFromAgent(JsonParser.parse("{}")));
    }

    @Test
    void aRecordRoundTripsWithProgressAndOverrides() {
        UnifiedDiff diff = twoFileDiff();
        TourRecord record = TourRecord.fresh(coveringTour(diff), diff);
        String digest = AnchorIndex.of(diff).hunks().getFirst().digest();
        record = record.withProgress(record.progress("s1")
                        .withCheck(StepGrading.answerChoice(TourFixtures.predict("c1"),
                                record.progress("s1").check("c1"), 0))
                        .withDecision(StepProgress.Decision.OVERRIDDEN, Optional.of("trust me")))
                .withHunkOverride(digest, Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "diff view")))
                .withReviewAnyway(true);

        JsonValue json = TourCodec.recordToJson(record);
        TourRecord back = TourCodec.recordFromJson(JsonParser.parse(JsonWriter.write(json)))
                .orElseThrow();
        assertEquals(record, back);
    }

    @Test
    void aMalformedPersistedStepIsDroppedAndTheRestSurvive() {
        UnifiedDiff diff = twoFileDiff();
        String json = JsonWriter.write(TourCodec.recordToJson(TourRecord.fresh(coveringTour(diff), diff)))
                .replaceFirst("\"kind\"\\s*:\\s*\"predict\"", "\"kind\": \"bogus\"");
        TourRecord back = TourCodec.recordFromJson(JsonParser.parse(json)).orElseThrow();
        assertEquals(List.of("s2"), back.tour().steps().stream().map(TourStep::id).toList());
        assertTrue(back.progress().containsKey("s2"));
        assertTrue(!back.progress().containsKey("s1"), "progress of a dropped step is dropped too");
    }

    @Test
    void aRecordWithoutATourDecodesToNothing() {
        assertTrue(TourCodec.recordFromJson(JsonParser.parse("{\"progress\":{}}")).isEmpty());
    }

    @Test
    void theSeededFlagRoundTripsAndDefaultsToFalseWhenAbsent() {
        UnifiedDiff diff = twoFileDiff();
        TourRecord seeded = TourRecord.fresh(coveringTour(diff), diff).withSeeded(true);

        TourRecord back = TourCodec.recordFromJson(JsonParser.parse(JsonWriter.write(TourCodec.recordToJson(seeded))))
                .orElseThrow();
        assertTrue(back.seeded());
        assertEquals(seeded, back);

        String withoutFlag = JsonWriter.write(TourCodec.recordToJson(seeded))
                .replaceFirst(",\\s*\"seeded\"\\s*:\\s*true", "");
        TourRecord legacy = TourCodec.recordFromJson(JsonParser.parse(withoutFlag)).orElseThrow();
        assertTrue(!legacy.seeded(), "a record written before the flag existed is not seeded");
        assertTrue(!TourRecord.fresh(coveringTour(diff), diff).seeded());
    }
}
