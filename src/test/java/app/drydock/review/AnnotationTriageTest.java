package app.drydock.review;

import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Triage: an agent's finding is a proposal until a human confirms it. */
class AnnotationTriageTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:00:00Z");

    private static ReviewAnnotation finding(String id, Severity severity, Triage triage,
                                            Optional<String> withheldBy, boolean postToPr) {
        return new ReviewAnnotation("rs_a", id, "src/Main.java", "n10", "n10",
                severity, Confidence.HIGH, Optional.of("Title"), "Claude", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", AT, "body")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), postToPr,
                triage, withheldBy);
    }

    @Test
    void aSchemaFiveFindingWithoutTriageDecodesAsConfirmed() {
        JsonObject entry = JsonObject.empty();
        entry.put("scopeId", new JsonString("rs_a"));
        entry.put("id", new JsonString("f_1"));
        entry.put("file", new JsonString("Sidebar.java"));
        entry.put("startKey", new JsonString("n40"));
        entry.put("endKey", new JsonString("n40"));
        entry.put("author", new JsonString("Claude"));
        entry.put("at", new JsonString("2026-08-05T10:00:00Z"));
        entry.put("status", new JsonString("OPEN"));
        entry.put("thread", new JsonArray(List.of()));
        JsonObject root = JsonObject.empty();
        root.put("annotations", new JsonArray(List.of(entry)));

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(root);

        assertEquals(1, decoded.size());
        assertEquals(Triage.CONFIRMED, decoded.get(0).triage());
        assertEquals(Optional.empty(), decoded.get(0).withheldBy());
    }

    @Test
    void aProposedFindingWithWithheldByRoundTrips() {
        ReviewAnnotation proposed = finding("f1", Severity.QUESTION, Triage.PROPOSED,
                Optional.of("f_other"), false);

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(JsonParser.parse(
                JsonWriter.write(AnnotationStore.toJson(List.of(proposed), List.of(), List.of()))));

        assertEquals(List.of(proposed), decoded);
        assertEquals(Triage.PROPOSED, decoded.get(0).triage());
        assertEquals(Optional.of("f_other"), decoded.get(0).withheldBy());
    }

    @Test
    void aDismissedFindingRoundTrips() {
        ReviewAnnotation dismissed = finding("f1", Severity.NIT, Triage.DISMISSED, Optional.empty(), false);

        List<ReviewAnnotation> decoded = AnnotationStore.fromJson(JsonParser.parse(
                JsonWriter.write(AnnotationStore.toJson(List.of(dismissed), List.of(), List.of()))));

        assertEquals(Triage.DISMISSED, decoded.get(0).triage());
    }

    @Test
    void onlyAConfirmedBlockingFindingBlocksApproval() {
        ReviewAnnotation proposed = finding("f1", Severity.BLOCKING, Triage.PROPOSED, Optional.empty(), false);

        assertFalse(proposed.blocksApproval(), "a proposal does not block");
        assertFalse(proposed.withTriage(Triage.DISMISSED).blocksApproval());
        assertTrue(proposed.withTriage(Triage.CONFIRMED).blocksApproval());
        assertFalse(proposed.counts());
        assertTrue(proposed.withTriage(Triage.CONFIRMED).counts());
    }

    @Test
    void theOtherWithersKeepTriageAndWithheldBy() {
        ReviewAnnotation base = finding("f1", Severity.QUESTION, Triage.PROPOSED, Optional.of("f_x"), false);

        List<ReviewAnnotation> derived = List.of(
                base.withStatus(AnnotationStatus.RESOLVED),
                base.withReply(new ReviewAnnotation.Message("You", AT, "hm")),
                base.withPostToPr(true),
                base.withSeverityOverride(Severity.NIT),
                base.withScopeId("rs_b"),
                base.withGithub(new ReviewAnnotation.GitHubComment(1L, "https://x", false)));

        for (ReviewAnnotation next : derived) {
            assertEquals(Triage.PROPOSED, next.triage());
            assertEquals(Optional.of("f_x"), next.withheldBy());
        }
        assertEquals(Optional.empty(), base.withWithheldBy(Optional.empty()).withheldBy());
    }

    @Test
    void theTwentyArgumentConstructorAndHumanFindingsAreConfirmed() {
        ReviewAnnotation human = ReviewAnnotation.human("rs_a", "f", "n1", "n1",
                new ReviewAnnotation.Message("You", AT, "why?"));

        assertEquals(Triage.CONFIRMED, human.triage());
        assertEquals(Optional.empty(), human.withheldBy());
    }

    @Test
    void submitPostsOnlyConfirmedFindingsAndCountsTheUntriaged() {
        ReviewAnnotation confirmed = finding("f_ok", Severity.QUESTION, Triage.CONFIRMED, Optional.empty(), true);
        ReviewAnnotation proposed = finding("f_prop", Severity.QUESTION, Triage.PROPOSED, Optional.empty(), true);
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Main.java n10", 0), Map.of("src/Main.java n10", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(confirmed, proposed), List.of(), index);

        assertEquals(List.of(confirmed.key()), plan.posting());
        assertEquals(Event.COMMENT, plan.preselected());
        assertEquals(1, SubmitPlan.untriagedCount(List.of(confirmed, proposed)));
        assertEquals(0, SubmitPlan.untriagedCount(
                List.of(confirmed, proposed.withStatus(AnnotationStatus.RESOLVED))));
    }
}
