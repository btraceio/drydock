package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.RecheckAssessment;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.num;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code review_recheck} on the REAL tool path (spec §9.7).
 *
 * <p>Everything here goes in as the wire's positional {@code hunkId} and has
 * to come out as the content digest a verdict is keyed by. That translation
 * is the whole reason this file exists alongside {@code
 * RecheckAsymmetryTest}: the store's own tests hand it a digest directly and
 * exercise none of it, and a hunkId stored verbatim would sit in the
 * annotations file matching nothing the board ever asks for -- a recheck the
 * human believes happened and did not.</p>
 *
 * <p>The asymmetry is pinned here too, on the wire: an {@code affected:true}
 * lands as a mark, an {@code affected:false} lands as a record that marks
 * nothing, and neither one touches the verdict.</p>
 */
class McpToolRouterRecheckTest extends McpRouterFixture {

    private static final String WIDGET_HUNK = ReviewIntent.hunkId("src/Widget.java", 0);
    private static final String USER_HUNK = ReviewIntent.hunkId("src/WidgetUser.java", 0);

    /** The content digest of the fixture diff's first hunk -- what a verdict is keyed by. */
    private String widgetDigest() {
        UnifiedDiff.FileDiff file = context.reviewDiff.files().get(0);
        return HunkDigest.of(file.path(), file.hunks().get(0));
    }

    private String userDigest() {
        UnifiedDiff.FileDiff file = context.reviewDiff.files().get(1);
        return HunkDigest.of(file.path(), file.hunks().get(0));
    }

    /** An approval on {@code digest}, judged against {@code base}. */
    private void approve(String digest, String base) {
        context.verdicts.add(new ReviewVerdict(scopeId(), digest, ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, base, "head-1"));
    }

    private JsonValue recheck(String assessments) throws McpToolException {
        return router.call(callerId(), "review_recheck", JsonParser.parse("""
                {"scopeId":"%s","assessments":%s}
                """.formatted(scopeId(), assessments)));
    }

    // ---- ruling 1: the wire says hunkId, the store is keyed by hunkDigest ----

    /**
     * The one translation the plan's own store-level tests could not reach:
     * {@code h_src/Widget.java_0} is POSITIONAL, {@link HunkDigest} is
     * content-addressed and excludes line numbers, and what gets stored has
     * to be the second one.
     */
    @Test
    void theWireHunkIdIsStoredAsTheContentDigestAVerdictIsKeyedBy() throws Exception {
        approve(widgetDigest(), "base-1");

        recheck("""
                [{"hunkId":"%s","affected":true,"why":"resolve() now returns nullptr"}]
                """.formatted(WIDGET_HUNK));

        assertEquals(1, context.assessments.size());
        RecheckAssessment stored = context.assessments.get(0);
        assertEquals(widgetDigest(), stored.hunkDigest());
        assertNotEquals(WIDGET_HUNK, stored.hunkDigest(),
                "storing the positional id would match no verdict the board ever asks about");
        assertEquals(scopeId(), stored.scopeId());
        assertTrue(stored.affected());
        assertEquals("resolve() now returns nullptr", stored.why());
    }

    /**
     * Two hunks in the batch, so a handler that resolved everything to the
     * FIRST file's digest -- the shape a one-hunk fixture cannot tell from a
     * correct one -- is caught.
     */
    @Test
    void eachHunkIdResolvesToItsOwnHunkRatherThanTheFirst() throws Exception {
        approve(widgetDigest(), "base-1");
        approve(userDigest(), "base-1");

        recheck("""
                [{"hunkId":"%s","affected":true,"why":"a"},
                 {"hunkId":"%s","affected":true,"why":"b"}]
                """.formatted(WIDGET_HUNK, USER_HUNK));

        assertEquals(List.of(widgetDigest(), userDigest()),
                context.assessments.stream().map(RecheckAssessment::hunkDigest).toList());
        assertNotEquals(widgetDigest(), userDigest(), "the fixture must have two distinct digests");
    }

    /**
     * An unresolvable {@code hunkId} rejects the BATCH, naming the offending
     * id -- a file the diff does not have, an index past that file's hunk
     * count, and text that is not a hunk id at all. Skipping it silently is
     * the failure ruling 1 exists to prevent: absent and broken must not look
     * the same.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "h_src/Gone.java_0",        // a file this diff does not have
            "h_src/Widget.java_7",      // an index past that file's hunk count
            "h_src/Widget.java_-1",     // a negative index
            "not-a-hunk-id",            // not shaped like one at all
    })
    void aHunkIdNamingNothingInTheDiffRejectsTheWholeBatch(String bad) {
        approve(widgetDigest(), "base-1");

        McpToolException thrown = assertThrows(McpToolException.class, () -> recheck("""
                [{"hunkId":"%s","affected":true,"why":"a"},
                 {"hunkId":"%s","affected":true,"why":"b"}]
                """.formatted(WIDGET_HUNK, bad)));

        assertTrue(thrown.getMessage().contains(bad), thrown.getMessage());
        assertTrue(context.assessments.isEmpty(),
                "a batch with one bad entry must write nothing, not half a recheck");
    }

    /**
     * A hunk with no verdict has no {@code fromBase}, so there is no base
     * move to key an assessment by and nothing decided to undermine.
     * Refused, naming the id, rather than stored under a fabricated pair.
     */
    @Test
    void aHunkCarryingNoVerdictRejectsTheBatch() {
        McpToolException thrown = assertThrows(McpToolException.class, () -> recheck("""
                [{"hunkId":"%s","affected":true,"why":"a"}]
                """.formatted(WIDGET_HUNK)));

        assertTrue(thrown.getMessage().contains(WIDGET_HUNK), thrown.getMessage());
        assertTrue(context.assessments.isEmpty());
    }

    // ---- the base pair drydock derives --------------------------------------

    /**
     * {@code fromBase} is the base the hunk's OWN verdict was recorded
     * against and {@code toBase} is the scope's base now, so the key written
     * here is the key the board reads with. Two hunks approved against
     * DIFFERENT bases, so a handler taking one base for the whole batch is
     * caught.
     */
    @Test
    void theBasePairComesFromEachHunksOwnVerdictAndTheScopesCurrentBase() throws Exception {
        approve(widgetDigest(), "base-0");
        approve(userDigest(), "base-1");
        context.currentReviewBase = Optional.of("base-9");

        recheck("""
                [{"hunkId":"%s","affected":true,"why":"a"},
                 {"hunkId":"%s","affected":true,"why":"b"}]
                """.formatted(WIDGET_HUNK, USER_HUNK));

        assertEquals(List.of("base-0", "base-1"),
                context.assessments.stream().map(RecheckAssessment::fromBase).toList());
        assertEquals(List.of("base-9", "base-9"),
                context.assessments.stream().map(RecheckAssessment::toBase).toList());
    }

    /**
     * A base that does not resolve to a commit is not a base move anyone can
     * name, so the call is refused rather than recording a recheck against
     * a placeholder.
     */
    @Test
    void aBaseThatDoesNotResolveRefusesTheCall() {
        approve(widgetDigest(), "base-1");
        context.currentReviewBase = Optional.empty();

        McpToolException thrown = assertThrows(McpToolException.class, () -> recheck("""
                [{"hunkId":"%s","affected":true,"why":"a"}]
                """.formatted(WIDGET_HUNK)));

        assertTrue(thrown.getMessage().contains(scopeId()), thrown.getMessage());
        assertTrue(context.assessments.isEmpty());
    }

    // ---- the asymmetry, on the wire -----------------------------------------

    /**
     * {@code affected:false} is recorded -- it is what the agent said -- but
     * it marks nothing, and the response says so. The failure this guards is
     * an agent's "unaffected" quietly un-staling a verdict, which is a
     * human's approval standing over code nobody re-read.
     */
    @Test
    void anUnaffectedAssessmentIsRecordedAndMarksNothing() throws Exception {
        approve(widgetDigest(), "base-1");

        JsonValue response = recheck("""
                [{"hunkId":"%s","affected":false,"why":"unrelated subsystem"}]
                """.formatted(WIDGET_HUNK));

        assertEquals(1, num(response, "assessments"));
        assertEquals(0, num(response, "markedStale"));
        assertFalse(context.assessments.get(0).affected());
        // The verdict is untouched: still approved, still recorded against
        // the base it was judged on, so still stale against the new one.
        ReviewVerdict verdict = context.verdictsOf(scopeId()).get(0);
        assertEquals(ReviewVerdict.Decision.APPROVED, verdict.decision());
        assertEquals("base-1", verdict.baseCommit());
        assertTrue(verdict.staleAgainst("base-2"));
    }

    /** An omitted {@code affected} is the inert direction, never a mark nobody asserted. */
    @Test
    void anOmittedAffectedMarksNothing() throws Exception {
        approve(widgetDigest(), "base-1");

        JsonValue response = recheck("""
                [{"hunkId":"%s","why":"said nothing about it"}]
                """.formatted(WIDGET_HUNK));

        assertEquals(0, num(response, "markedStale"));
        assertFalse(context.assessments.get(0).affected());
    }

    @Test
    void anAffectedAssessmentIsReportedAsAMark() throws Exception {
        approve(widgetDigest(), "base-1");

        JsonValue response = recheck("""
                [{"hunkId":"%s","affected":true,"why":"resolve() now returns nullptr"}]
                """.formatted(WIDGET_HUNK));

        assertEquals(scopeId(), str(response, "scopeId"));
        assertEquals(1, num(response, "assessments"));
        assertEquals(1, num(response, "markedStale"));
    }

    // ---- ruling 2: why is agent text that gets rendered ----------------------

    /**
     * {@code why} is free text from an agent, stored, and shown to a human as
     * the reason a hunk was marked affected -- the same treatment {@code
     * intent.title}, {@code finding.body} and {@code evidence.code} already
     * get. A control character can reach a terminal through "Ask the agent to
     * fix it", so it is refused at the boundary.
     */
    @Test
    void aWhyCarryingAControlCharacterRejectsTheBatch() {
        approve(widgetDigest(), "base-1");

        McpToolException thrown = assertThrows(McpToolException.class, () -> recheck("""
                [{"hunkId":"%s","affected":true,"why":"before\\u001bafter"}]
                """.formatted(WIDGET_HUNK)));

        assertTrue(thrown.getMessage().contains("assessment.why"), thrown.getMessage());
        assertTrue(context.assessments.isEmpty());
    }

    // ---- shape --------------------------------------------------------------

    @Test
    void assessmentsMustBeAnArray() {
        approve(widgetDigest(), "base-1");

        assertThrows(McpToolException.class, () -> router.call(callerId(), "review_recheck",
                JsonParser.parse("""
                        {"scopeId":"%s","assessments":"h_src/Widget.java_0"}
                        """.formatted(scopeId()))));
        assertTrue(context.assessments.isEmpty());
    }

    @Test
    void theToolIsRegisteredWithItsRequiredArguments() {
        JsonValue tool = router.toolDescriptors().stream()
                .filter(descriptor -> "review_recheck".equals(str(descriptor, "name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("review_recheck is not registered"));

        assertEquals(List.of("scopeId", "assessments"), JsonPeek.requiredNames(tool));
        // The one thing an agent must not misread about this tool.
        assertTrue(str(tool, "description").contains("never clears"), str(tool, "description"));
    }
}
