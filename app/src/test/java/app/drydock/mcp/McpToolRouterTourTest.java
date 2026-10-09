package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.HunkOverride;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.num;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixture's diff: src/Widget.java and src/WidgetUser.java, both added,
 * one hunk each, rows n1..n5 and n1..n6 (see McpRouterFixture.parseableDiff).
 */
class McpToolRouterTourTest extends McpRouterFixture {

    private static final String CHECK = """
            {"id":"%s","kind":"trace","prompt":"What happens?",
             "choices":[{"text":"a"},{"text":"b"}],"answer":0,"explanation":"Because.",
             "alternates":[{"id":"%s_alt","kind":"risk","prompt":"Risk?","explanation":"E."}]}""";

    private static String step(String id, String file, String start, String end, String checkId) {
        return """
                {"id":"%s","title":"T %s","narrative":"Why.",
                 "anchors":[{"file":"%s","startKey":"%s","endKey":"%s"}],
                 "checks":[%s]}""".formatted(id, id, file, start, end, CHECK.formatted(checkId, checkId));
    }

    private JsonObject tourArgs(String... steps) {
        JsonObject args = JsonObject.empty().put("scopeId", new JsonValue.JsonString(scopeId()));
        args.put("steps", JsonParser.parse("[" + String.join(",", steps) + "]"));
        return args;
    }

    /**
     * As {@link #coveringArgs}, but step s1's check is a RISK, which a finding can be withheld behind. A
     * finding cannot be withheld behind a TRACE, and the fixture's added-only files cannot carry a PREDICT.
     */
    private JsonObject riskFirstCoveringArgs() {
        String riskStep = """
                {"id":"s1","title":"T s1","narrative":"Why.",
                 "anchors":[{"file":"src/Widget.java","startKey":"n1","endKey":"n5"}],
                 "checks":[{"id":"c1","kind":"risk","prompt":"Risk?","explanation":"E.",
                            "alternates":[{"id":"c1_alt","kind":"risk","prompt":"Again?","explanation":"E."}]}]}""";
        return tourArgs(riskStep, step("s2", "src/WidgetUser.java", "n1", "n6", "c2"));
    }

    private JsonObject coveringArgs() {
        return tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1"),
                step("s2", "src/WidgetUser.java", "n1", "n6", "c2"));
    }

    @Test
    void aCoveringTourIsStoredWithFreshProgress() throws Exception {
        JsonValue result = router.call(callerId(), "review_tour", coveringArgs());

        assertEquals(2, num(result, "steps"));
        assertEquals(2, num(result, "checks"));
        TourRecord stored = context.tourOf(scopeId()).orElseThrow();
        assertEquals(2, stored.tour().steps().size());
        assertEquals(1, stored.progress().get("s1").hunkDigests().size());
    }

    @Test
    void aPredictOverAnAddedOnlyStepIsRejectedAndStoresNothing() {
        String predict = CHECK.replace("\"kind\":\"trace\"", "\"kind\":\"predict\"");
        String predictStep = """
                {"id":"s1","title":"T","narrative":"Why.",
                 "anchors":[{"file":"src/Widget.java","startKey":"n1","endKey":"n5"}],
                 "checks":[%s]}""".formatted(predict.formatted("c1", "c1"));
        String second = step("s2", "src/WidgetUser.java", "n1", "n6", "c2");

        McpToolException error = assertThrows(McpToolException.class,
                () -> router.call(callerId(), "review_tour", tourArgs(predictStep, second)));

        assertTrue(error.getMessage().contains("check c1 is a PREDICT"), error.getMessage());
        assertTrue(error.getMessage().contains("TRACE"), error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty(), "nothing stored");
    }

    @Test
    void anUncoveredHunkRejectsTheWholeTourAndStoresNothing() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1"))));
        assertTrue(error.getMessage().contains("hunk h_src/WidgetUser.java_0"), error.getMessage());
        assertTrue(error.getMessage().contains("nothing stored"), error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty());
    }

    @Test
    void aMalformedStepIsRejectedNamingItsPath() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1").replace("\"answer\":0", "\"answer\":\"0\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].answer"), error.getMessage());
    }

    /**
     * A decode fault in one step no longer hides the faults of its siblings:
     * the rejection names every step's first fault, one per line, so one
     * resubmission fixes all of them instead of re-posting the whole tour
     * once per field (18 calls before one landed, observed live).
     */
    @Test
    void twoStepsWithDifferentFaultsAreBothNamed() {
        String brokenOne = step("s1", "src/Widget.java", "n1", "n5", "c1")
                .replace("\"answer\":0", "\"answer\":\"0\"");
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(brokenOne, step("s2", "src/WidgetUser.java", "n1", "n6", "c2")
                        .replace("\"prompt\":\"What happens?\"", "\"promt\":\"What happens?\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].answer"), error.getMessage());
        assertTrue(error.getMessage().contains("steps[1].checks[0].prompt"), error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty(), "nothing stored");
    }

    @Test
    void anImpactNoteOnALineThatDoesNotExistIsRejected() {
        context.excerptAnswer = Optional.empty();
        String noted = step("s1", "src/Widget.java", "n1", "n5", "c1")
                .replace("\"checks\":", "\"impactNotes\":[{\"file\":\"src/Other.java\",\"line\":4000,\"text\":\"x\"}],\"checks\":");
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(noted, step("s2", "src/WidgetUser.java", "n1", "n6", "c2"))));
        assertTrue(error.getMessage().contains("src/Other.java:4000"), error.getMessage());
    }

    /**
     * The swamping guard: an unbounded list of unverified name-match links
     * drowns the step panel, so a step carries at most 8 impact notes.
     */
    @Test
    void aStepWithMoreThanEightImpactNotesIsRejected() {
        StringBuilder notes = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            if (i > 1) {
                notes.append(',');
            }
            notes.append("{\"file\":\"src/Other.java\",\"line\":").append(i)
                    .append(",\"text\":\"note ").append(i).append("\"}");
        }
        String noted = step("s1", "src/Widget.java", "n1", "n5", "c1")
                .replace("\"checks\":", "\"impactNotes\":[" + notes + "],\"checks\":");
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(noted, step("s2", "src/WidgetUser.java", "n1", "n6", "c2"))));
        assertTrue(error.getMessage().contains("more than 8 impact notes"), error.getMessage());
    }

    @Test
    void reviewStateReportsTheTourAndWhetherItIsCurrent() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());

        JsonValue state = router.call(callerId(), "review_state", JsonPeek.args("scopeId", scopeId()));

        JsonValue tour = field(state, "tour");
        assertTrue(JsonPeek.bool(tour, "current"));
        assertEquals(2, JsonPeek.array(tour, "steps").size());
    }

    @Test
    void aTourIsRejectedWhenAStoredFindingIsWithheldByACheckOffItsLines() {
        context.upsertFindings(List.of(new ReviewAnnotation(scopeId(), "f1",
                "src/WidgetUser.java", "n2", "n2", Severity.QUESTION, Confidence.HIGH, Optional.empty(), "Claude",
                Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, Triage.PROPOSED,
                Optional.of("c1"))));

        McpToolException error = assertThrows(McpToolException.class,
                () -> router.call(callerId(), "review_tour", riskFirstCoveringArgs()));

        assertTrue(error.getMessage().contains(
                "finding f1 is withheld by check c1, which is not on a step covering src/WidgetUser.java n2"),
                error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty());
    }

    @Test
    void aFindingWithheldByACheckOffItsLinesIsRejectedOnceATourExists() throws Exception {
        router.call(callerId(), "review_tour", riskFirstCoveringArgs());

        McpToolException error = assertThrows(McpToolException.class,
                () -> router.call(callerId(), "review_finding", withheldFindingArgs("src/WidgetUser.java", "c1")));

        assertTrue(error.getMessage().contains(
                "finding f1 is withheld by check c1, which is not on a step covering src/WidgetUser.java n2"),
                error.getMessage());
        assertTrue(context.findingsOf(scopeId()).isEmpty());
    }

    @Test
    void aFindingWithheldByACheckOnItsLinesIsStored() throws Exception {
        router.call(callerId(), "review_tour", riskFirstCoveringArgs());

        router.call(callerId(), "review_finding", withheldFindingArgs("src/Widget.java", "c1"));

        assertEquals(Optional.of("c1"), context.findingsOf(scopeId()).getFirst().withheldBy());
    }

    /** Posts the covering tour, then leaves c2 awaiting the agent on its risk alternate with this answer. */
    private void awaitingAnswer(String answer) throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());
        TourRecord record = context.tourOf(scopeId()).orElseThrow();
        CheckProgress onRisk = new CheckProgress("c2", 1, CheckProgress.Status.OPEN, Optional.empty(),
                Optional.empty(), Optional.empty());
        CheckProgress waiting = StepGrading.submitRisk(onRisk, answer);
        context.putTour(record.withProgress(record.progress("s2").withCheck(waiting)));
    }

    private JsonObject checkArgs(String verdict) {
        return JsonPeek.args("scopeId", scopeId(), "checkId", "c2", "verdict", verdict, "reason", "It holds up.");
    }

    private CheckProgress storedC2() {
        return context.tourOf(scopeId()).orElseThrow().progress("s2").check("c2");
    }

    @Test
    void reviewStateListsTheAnswersAwaitingTheAgent() throws Exception {
        awaitingAnswer("an empty list");

        JsonValue state = router.call(callerId(), "review_state", JsonPeek.args("scopeId", scopeId()));

        JsonValue waiting = JsonPeek.array(field(state, "tour"), "awaitingAgent").getFirst();
        assertEquals("an empty list", JsonPeek.str(waiting, "answer"));
        assertEquals("c2", JsonPeek.str(waiting, "checkId"));
        assertEquals("s2", JsonPeek.str(waiting, "stepId"));
        assertEquals("Risk?", JsonPeek.str(waiting, "prompt"));
    }

    @Test
    void aHoldsVerdictPassesTheCheck() throws Exception {
        awaitingAnswer("an empty list");

        JsonValue result = router.call(callerId(), "review_check", checkArgs("holds"));

        assertEquals("PASSED", JsonPeek.str(result, "status"));
        assertEquals(CheckProgress.Status.PASSED, storedC2().status());
        assertEquals(Optional.of("It holds up."), storedC2().agentReason());
    }

    @Test
    void aVerdictWordOutsideTheVocabularyNamesTheAllowedOnes() throws Exception {
        awaitingAnswer("an empty list");

        McpToolException error = assertThrows(McpToolException.class,
                () -> router.call(callerId(), "review_check", checkArgs("nonsense")));

        assertTrue(error.getMessage().contains("holds, partly or doesNotHold"), error.getMessage());
    }

    @Test
    void aCheckThatIsOpenIsNotAwaitingAVerdict() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());

        McpToolException error = assertThrows(McpToolException.class,
                () -> router.call(callerId(), "review_check", checkArgs("holds")));

        assertTrue(error.getMessage().contains("is not awaiting a verdict"), error.getMessage());
    }

    @Test
    void anUnknownCheckIsRejected() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());

        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(),
                "review_check", JsonPeek.args("scopeId", scopeId(), "checkId", "zzz", "verdict", "holds",
                        "reason", "r")));

        assertTrue(error.getMessage().contains("no check zzz"), error.getMessage());
    }

    @Test
    void aRiskVerdictForAnOldFingerprintIsDropped() throws Exception {
        awaitingAnswer("an empty list");
        context.reviewDiff = new UnifiedDiff(List.of());

        JsonValue result = router.call(callerId(), "review_check", checkArgs("holds"));

        assertTrue(JsonPeek.bool(result, "dropped"));
        assertEquals(CheckProgress.Status.AWAITING_AGENT, storedC2().status());
    }

    private JsonObject withheldFindingArgs(String file, String checkId) {
        JsonObject finding = JsonParser.parse("""
                {"id":"f1","anchor":{"file":"%s","startKey":"n2"},"severity":"question",
                 "confidence":"high","body":"body text","withheldBy":"%s"}""".formatted(file, checkId))
                instanceof JsonObject obj ? obj : JsonObject.empty();
        JsonObject args = JsonObject.empty().put("scopeId", new JsonValue.JsonString(scopeId()));
        args.put("findings", new JsonValue.JsonArray(List.of(finding)));
        return args;
    }

    private JsonObject onlyStepsArgs(String... steps) {
        return tourArgs(steps).put("onlySteps", new JsonValue.JsonBoolean(true));
    }

    /** Posts the covering tour and passes s1. */
    private TourRecord postedWithS1Passed() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());
        TourRecord record = context.tourOf(scopeId()).orElseThrow();
        context.putTour(record.withProgress(record.progress("s1")
                .withDecision(StepProgress.Decision.PASSED, Optional.empty())));
        return context.tourOf(scopeId()).orElseThrow();
    }

    /** As {@link #postedWithS1Passed}, with s2 gone stale -- the only kind of step onlySteps may replace. */
    private TourRecord postedWithS1PassedAndS2Stale() throws Exception {
        TourRecord record = postedWithS1Passed();
        context.putTour(record.withProgress(record.progress("s2").withStale(true)));
        return context.tourOf(scopeId()).orElseThrow();
    }

    @Test
    void onlyStepsReplacingAStepThatIsNotStaleIsRejectedAndStoresNothing() throws Exception {
        TourRecord before = postedWithS1Passed();

        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                onlyStepsArgs(step("s1", "src/Widget.java", "n1", "n5", "c9"))));

        assertTrue(error.getMessage().contains(
                "step s1 is not stale; onlySteps only replaces stale steps or adds new ones"), error.getMessage());
        assertTrue(error.getMessage().contains("nothing stored"), error.getMessage());
        assertEquals(before, context.tourOf(scopeId()).orElseThrow());
    }

    @Test
    void onlyStepsReplacesTheNamedStepAndKeepsTheOthersProgress() throws Exception {
        postedWithS1PassedAndS2Stale();

        router.call(callerId(), "review_tour", onlyStepsArgs(step("s2", "src/WidgetUser.java", "n1", "n6", "c9")));

        TourRecord stored = context.tourOf(scopeId()).orElseThrow();
        assertEquals(List.of("s1", "s2"), stored.tour().steps().stream().map(TourStep::id).toList());
        assertTrue(stored.tour().step("s2").orElseThrow().check("c9").isPresent());
        assertEquals(StepProgress.Decision.PASSED, stored.progress("s1").decision());
        assertEquals(StepProgress.Decision.NONE, stored.progress("s2").decision());
    }

    @Test
    void anOnlyStepsMergeThatLeavesAHunkUncoveredIsRejectedAndStoresNothing() throws Exception {
        TourRecord before = postedWithS1PassedAndS2Stale();

        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                onlyStepsArgs(step("s2", "src/WidgetUser.java", "n1", "n3", "c9"))));

        assertTrue(error.getMessage().contains("hunk h_src/WidgetUser.java_0"), error.getMessage());
        assertTrue(error.getMessage().contains("nothing stored"), error.getMessage());
        assertEquals(before, context.tourOf(scopeId()).orElseThrow());
    }

    @Test
    void onlyStepsWithNoStoredTourIsAnError() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                onlyStepsArgs(step("s2", "src/WidgetUser.java", "n1", "n6", "c9"))));

        assertTrue(error.getMessage().contains("no tour"), error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty());
    }

    @Test
    void aFullRepostKeepsTheHunkOverridesAndTheSeededFlag() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());
        TourRecord first = context.tourOf(scopeId()).orElseThrow();
        assertFalse(first.seeded(), "a first tour still has the hunk diff's verdicts to seed");
        String digest = first.progress("s1").hunkDigests().getFirst();
        context.putTour(first.withHunkOverride(digest,
                Optional.of(new HunkOverride(ReviewVerdict.Decision.CHANGES, "set in the hunk diff"))));

        router.call(callerId(), "review_tour", coveringArgs());

        TourRecord second = context.tourOf(scopeId()).orElseThrow();
        assertEquals(Optional.of(new HunkOverride(ReviewVerdict.Decision.CHANGES, "set in the hunk diff")),
                Optional.ofNullable(second.hunkOverrides().get(digest)));
        assertFalse(second.seeded(), "an unseeded tour replaced before its first sync still gets seeded");
        assertEquals(StepProgress.Decision.NONE, second.progress("s1").decision());

        context.putTour(second.withSeeded(true));
        router.call(callerId(), "review_tour", coveringArgs());

        assertTrue(context.tourOf(scopeId()).orElseThrow().seeded(),
                "a seeded tour's re-post is not seeded again from the previous tour's derived verdicts");
    }

    @Test
    void onlyStepsReportsTheStepsStillStale() throws Exception {
        TourRecord posted = postedWithS1Passed();
        context.putTour(posted.withProgress(posted.progress("s1").withStale(true))
                .withProgress(posted.progress("s2").withStale(true)));

        JsonValue result = router.call(callerId(), "review_tour",
                onlyStepsArgs(step("s2", "src/WidgetUser.java", "n1", "n6", "c9")));

        assertEquals(List.of("s1"), JsonPeek.array(result, "staleRemaining").stream()
                .map(value -> ((JsonValue.JsonString) value).value()).toList());
    }

    @Test
    void anOnlyStepsMergeAppliesToTheRecordAsItIsWhenWritten() throws Exception {
        postedWithS1PassedAndS2Stale();
        // The reviewer undoes s1 while the agent's merge is in flight.
        context.beforeTourMutate = () -> {
            TourRecord now = context.tours.get(scopeId());
            context.tours.put(scopeId(), now.withProgress(now.progress("s1")
                    .withDecision(StepProgress.Decision.NONE, Optional.empty())));
        };

        router.call(callerId(), "review_tour", onlyStepsArgs(step("s2", "src/WidgetUser.java", "n1", "n6", "c9")));

        assertEquals(StepProgress.Decision.NONE, context.tourOf(scopeId()).orElseThrow().progress("s1").decision(),
                "the undo made between the read and the write survives");
    }

    @Test
    void aRepostAppliesToTheRecordAsItIsWhenWritten() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());
        TourRecord first = context.tourOf(scopeId()).orElseThrow();
        String digest = first.progress("s1").hunkDigests().getFirst();
        context.beforeTourMutate = () -> context.tours.put(scopeId(), context.tours.get(scopeId())
                .withHunkOverride(digest, Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "late"))));

        router.call(callerId(), "review_tour", coveringArgs());

        assertEquals(Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "late")),
                Optional.ofNullable(context.tourOf(scopeId()).orElseThrow().hunkOverrides().get(digest)));
    }

    @Test
    void aRiskVerdictAppliesToTheRecordAsItIsWhenWritten() throws Exception {
        awaitingAnswer("an empty list");
        context.beforeTourMutate = () -> {
            TourRecord now = context.tours.get(scopeId());
            context.tours.put(scopeId(), now.withProgress(now.progress("s1")
                    .withDecision(StepProgress.Decision.PASSED, Optional.empty())));
        };

        router.call(callerId(), "review_check", checkArgs("holds"));

        assertEquals(CheckProgress.Status.PASSED, storedC2().status());
        assertEquals(StepProgress.Decision.PASSED, context.tourOf(scopeId()).orElseThrow().progress("s1").decision(),
                "the pass made between the read and the write survives");
    }

    @Test
    void aRiskVerdictForACheckNoLongerAwaitingWhenWrittenIsRejected() throws Exception {
        awaitingAnswer("an empty list");
        context.beforeTourMutate = () -> {
            TourRecord now = context.tours.get(scopeId());
            context.tours.put(scopeId(), now.withProgress(now.progress("s2").withCheck(
                    CheckProgress.fresh("c2"))));
        };

        McpToolException error = assertThrows(McpToolException.class,
                () -> router.call(callerId(), "review_check", checkArgs("holds")));

        assertTrue(error.getMessage().contains("is not awaiting a verdict"), error.getMessage());
        assertEquals(CheckProgress.Status.OPEN, storedC2().status());
    }
}
