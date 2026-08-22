package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Review MCP surface: scope paging, intents, findings, answers and state. */
class McpToolRouterReviewTest {

    private static final String SCOPE = "rs_test";

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        McpSessionRegistry registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry);

        context.grant(caller, SCOPE);
        context.reviewScopes.put(SCOPE, new ReviewScope(SCOPE, ReviewScope.Kind.WORKTREE,
                Path.of("/repos/drydock"), Optional.of(Path.of("/wt/feat")), "master", "feat",
                Optional.empty(), Optional.empty(), Optional.empty()));
        context.reviewDiff = diff(12);
    }

    // ---- authorization ------------------------------------------------------

    /** Unknown and forbidden must be one answer, or handles become probeable. */
    @Test
    void aScopeThisSessionCannotAddressIsRefusedLikeAnUnknownOne() {
        McpToolException forbidden = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_scope", args("scopeId", "rs_someone_else")));
        McpToolException unknown = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_scope", args("scopeId", "rs_no_such_thing")));

        assertEquals(forbidden.getMessage().replace("rs_someone_else", "X"),
                unknown.getMessage().replace("rs_no_such_thing", "X"));
    }

    // ---- review_scope -------------------------------------------------------

    @Test
    void reviewScopeReturnsTheScopeItsFilesAndItsHunks() throws Exception {
        JsonValue result = router.call(caller, "review_scope", args("scopeId", SCOPE));

        assertEquals(SCOPE, str(field(result, "scope"), "id"));
        assertEquals("worktree", str(field(result, "scope"), "kind"));
        assertEquals(1, ((JsonArray) field(result, "files")).elements().size());
        assertEquals(12, ((JsonArray) field(result, "hunks")).elements().size());
        assertTrue(field(result, "cursor") instanceof JsonValue.JsonNull,
                "a complete read has no cursor");
    }

    /** Every anchor in every other call is one of these keys, so they must be present. */
    @Test
    void everyLineCarriesItsStableKey() throws Exception {
        JsonValue result = router.call(caller, "review_scope", args("scopeId", SCOPE));

        JsonValue firstHunk = ((JsonArray) field(result, "hunks")).elements().get(0);
        JsonValue firstLine = ((JsonArray) field(firstHunk, "lines")).elements().get(0);
        assertTrue(str(firstLine, "key").matches("[on]\\d+"), str(firstLine, "key"));
    }

    @Test
    void aByteBudgetPagesTheHunksAndReturnsACursor() throws Exception {
        JsonValue first = router.call(caller, "review_scope",
                args("scopeId", SCOPE, "maxBytes", "1500"));

        int firstPage = ((JsonArray) field(first, "hunks")).elements().size();
        assertTrue(firstPage > 0 && firstPage < 12, "expected a partial page, got " + firstPage);
        String cursor = str(first, "cursor");

        JsonValue second = router.call(caller, "review_scope",
                args("scopeId", SCOPE, "maxBytes", "1500", "cursor", cursor));

        assertTrue(((JsonArray) field(second, "hunks")).elements().size() > 0);
    }

    @Test
    void pagingEventuallyReachesTheEnd() throws Exception {
        int seen = 0;
        Optional<String> cursor = Optional.empty();
        for (int page = 0; page < 20; page++) {
            JsonValue result = cursor.isPresent()
                    ? router.call(caller, "review_scope",
                            args("scopeId", SCOPE, "maxBytes", "1500", "cursor", cursor.get()))
                    : router.call(caller, "review_scope", args("scopeId", SCOPE, "maxBytes", "1500"));
            seen += ((JsonArray) field(result, "hunks")).elements().size();
            JsonValue next = field(result, "cursor");
            if (next instanceof JsonValue.JsonNull) {
                assertEquals(12, seen, "every hunk must be delivered exactly once");
                return;
            }
            cursor = Optional.of(((JsonString) next).value());
        }
        throw new AssertionError("paging never terminated");
    }

    /**
     * A hunk bigger than the whole budget is returned truncated, never
     * dropped and never allowed to fail the call: one generated file must not
     * make a scope unreadable.
     */
    @Test
    void aSingleOversizedHunkIsTruncatedRatherThanFailingTheCall() throws Exception {
        List<UnifiedDiff.Line> huge = new ArrayList<>();
        for (int i = 1; i <= 4000; i++) {
            huge.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(i), "a very long generated line number " + i));
        }
        context.reviewDiff = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("gen.java", "A",
                4000, 0, false, false, List.of(new UnifiedDiff.Hunk("@@", huge)))));

        JsonValue result = router.call(caller, "review_scope",
                args("scopeId", SCOPE, "maxBytes", "2000"));

        JsonValue hunk = ((JsonArray) field(result, "hunks")).elements().get(0);
        assertTrue(((JsonBoolean) field(hunk, "truncated")).value(), "must be marked truncated");
        assertTrue(((JsonArray) field(hunk, "lines")).elements().size() < 4000);
    }

    /** A re-run must recognize its own earlier findings rather than duplicating them. */
    @Test
    void reviewScopeReportsPriorThreads() throws Exception {
        context.annotations.add(finding("f1", Severity.BLOCKING));

        JsonValue result = router.call(caller, "review_scope", args("scopeId", SCOPE));

        JsonArray prior = (JsonArray) field(result, "priorThreads");
        assertEquals(1, prior.elements().size());
        assertEquals("f1", str(prior.elements().get(0), "id"));
    }

    // ---- review_intents -----------------------------------------------------

    @Test
    void reviewIntentsStoresTheGroupingAndNumbersItDensely() throws Exception {
        JsonObject intents = JsonObject.empty();
        intents.put("scopeId", new JsonString(SCOPE));
        intents.put("intents", new JsonArray(List.of(
                intentJson("i1", "Cursor-exact drag tracking", "HIGH"),
                intentJson("i2", "Rename Cpm to Drydock", "NONE"))));

        router.call(caller, "review_intents", intents);

        List<ReviewIntent> stored = context.intents.get(SCOPE);
        assertEquals(List.of("i1", "i2"), stored.stream().map(ReviewIntent::id).toList());
        assertEquals(List.of(1, 2), stored.stream().map(ReviewIntent::number).toList());
        assertEquals(ReviewIntent.Risk.HIGH, stored.get(0).risk());
    }

    @Test
    void aCollapsedIntentKeepsItsEvidenceAndDoesNotCountTowardProgress() throws Exception {
        JsonObject collapse = JsonObject.empty();
        collapse.put("reason", new JsonString("identifier-rename"));
        collapse.put("evidence", new JsonString("syntax trees identical apart from identifier text"));
        collapse.put("hunkCount", JsonNumber.of(312));
        collapse.put("fileCount", JsonNumber.of(14));
        JsonObject intent = intentJson("i2", "Rename", "NONE");
        intent.put("collapse", collapse);
        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("intents", new JsonArray(List.of(intent)));

        router.call(caller, "review_intents", args);

        ReviewIntent stored = context.intents.get(SCOPE).get(0);
        assertEquals(312, stored.collapse().orElseThrow().hunkCount());
        assertFalse(stored.countsTowardProgress());
    }

    // ---- review_finding -----------------------------------------------------

    @Test
    void reviewFindingStoresAFindingAgainstItsScope() throws Exception {
        router.call(caller, "review_finding", findingArgs("f_leak_1", "blocking", "body text"));

        ReviewAnnotation stored = context.findingsOf(SCOPE).get(0);
        assertEquals("f_leak_1", stored.id());
        assertEquals(SCOPE, stored.scopeId());
        assertEquals(Severity.BLOCKING, stored.severity());
        assertEquals("Claude", stored.author());
        assertEquals("body text", stored.thread().get(0).text());
    }

    /** Idempotent on finding id, so a re-run keeps the conversation. */
    @Test
    void aReRunUpsertsAndKeepsTheHumansThreadOverrideAndResolution() throws Exception {
        router.call(caller, "review_finding", findingArgs("f1", "blocking", "first pass"));
        // The human replies, downgrades it, and resolves it.
        context.mutateAnnotation(new ReviewAnnotation.Key(SCOPE, "f1"), current -> current
                .withReply(new ReviewAnnotation.Message("You", Instant.EPOCH, "I disagree"))
                .withSeverityOverride(Severity.QUESTION)
                .withStatus(AnnotationStatus.RESOLVED));

        router.call(caller, "review_finding", findingArgs("f1", "blocking", "second pass"));

        List<ReviewAnnotation> stored = context.findingsOf(SCOPE);
        assertEquals(1, stored.size(), "a re-run upserts rather than duplicating");
        ReviewAnnotation finding = stored.get(0);
        assertEquals("second pass", finding.thread().get(0).text(), "the opening statement refreshes");
        assertEquals("I disagree", finding.thread().get(1).text(), "the human's reply survives");
        assertEquals(Optional.of(Severity.QUESTION), finding.severityOverride(),
                "the human's override is theirs, not the agent's to undo");
        assertEquals(AnnotationStatus.RESOLVED, finding.status(),
                "the human's resolution survives a re-run");
    }

    /** A batch with one bad entry must write nothing, not half a review. */
    @Test
    void aBatchWithOneBadFindingWritesNothing() {
        JsonObject good = findingJson("f_good", "nit", "fine");
        JsonObject bad = findingJson("f_bad", "nit", "fine");
        bad.put("anchor", new JsonString("not an object"));
        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("findings", new JsonArray(List.of(good, bad)));

        assertThrows(McpToolException.class, () -> router.call(caller, "review_finding", args));

        assertTrue(context.findingsOf(SCOPE).isEmpty(), "a partial review must not be stored");
    }

    /**
     * The diff is untrusted input and a finding may quote it verbatim; that
     * text can later be typed into a live terminal.
     */
    @Test
    void aFindingBodyCarryingControlCharactersIsRefused() {
        // A real ESC, written as an escape rather than a raw byte so the
        // hazard is visible in the source.
        McpToolException failure = assertThrows(McpToolException.class, () ->
                router.call(caller, "review_finding",
                        findingArgs("f1", "nit", "escape \u001B[2J and clear")));

        assertTrue(failure.getMessage().contains("control characters"), failure.getMessage());
        assertTrue(context.findingsOf(SCOPE).isEmpty());
    }

    /** Markup is stored and rendered as text; only control characters are refused. */
    @Test
    void aFindingBodyMayContainMarkupBecauseItIsRenderedAsText() throws Exception {
        router.call(caller, "review_finding",
                findingArgs("f1", "nit", "<script>alert(1)</script> and `backticks`"));

        assertEquals("<script>alert(1)</script> and `backticks`",
                context.findingsOf(SCOPE).get(0).thread().get(0).text());
    }

    @Test
    void aProposedPatchIsStoredButNeverApplied() throws Exception {
        JsonObject patch = JsonObject.empty();
        patch.put("unified", new JsonString("--- a\n+++ b\n"));
        patch.put("summary", new JsonString("one line in onRelease"));
        JsonObject finding = findingJson("f1", "blocking", "leaks");
        finding.put("patch", patch);
        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("findings", new JsonArray(List.of(finding)));

        router.call(caller, "review_finding", args);

        ReviewAnnotation stored = context.findingsOf(SCOPE).get(0);
        assertEquals("one line in onRelease", stored.patch().orElseThrow().summary());
        assertEquals(AnnotationStatus.OPEN, stored.status(),
                "storing a proposal must not mark anything as done");
    }

    @Test
    void askChipsSurviveTheRoundTrip() throws Exception {
        JsonObject ask = JsonObject.empty();
        ask.put("label", new JsonString("Why is it a leak?"));
        ask.put("question", new JsonString("Explain why this leaks."));
        JsonObject finding = findingJson("f1", "blocking", "leaks");
        finding.put("asks", new JsonArray(List.of(ask)));
        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("findings", new JsonArray(List.of(finding)));

        router.call(caller, "review_finding", args);

        assertEquals("Why is it a leak?", context.findingsOf(SCOPE).get(0).asks().get(0).label());
    }

    // ---- review_answer ------------------------------------------------------

    @Test
    void reviewAnswerAppendsToTheThread() throws Exception {
        context.annotations.add(finding("f1", Severity.BLOCKING));

        router.call(caller, "review_answer",
                args("scopeId", SCOPE, "findingId", "f1", "body", "Because the filter is never removed."));

        List<ReviewAnnotation.Message> thread = context.findingsOf(SCOPE).get(0).thread();
        assertEquals(2, thread.size());
        assertEquals("Claude", thread.get(1).author());
    }

    /** propose* are suggestions: the store changes when the HUMAN accepts, not when the agent asks. */
    @Test
    void proposalsAreRecordedInTheThreadButNeverApplied() throws Exception {
        context.annotations.add(finding("f1", Severity.BLOCKING));

        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("findingId", new JsonString("f1"));
        args.put("body", new JsonString("Fair, downgrading this."));
        args.put("proposeSeverity", new JsonString("question"));
        args.put("proposeResolve", new JsonBoolean(true));
        router.call(caller, "review_answer", args);

        ReviewAnnotation stored = context.findingsOf(SCOPE).get(0);
        assertEquals(Severity.BLOCKING, stored.effectiveSeverity(),
                "an agent may not downgrade its own finding");
        assertEquals(AnnotationStatus.OPEN, stored.status(), "an agent may not resolve its own finding");
        String reply = stored.thread().get(1).text();
        assertTrue(reply.contains("proposes severity: question"), reply);
        assertTrue(reply.contains("proposes resolving"), reply);
    }

    @Test
    void answeringAnUnknownFindingIsRejected() {
        McpToolException failure = assertThrows(McpToolException.class, () ->
                router.call(caller, "review_answer",
                        args("scopeId", SCOPE, "findingId", "nope", "body", "hi")));

        assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
    }

    // ---- review_state -------------------------------------------------------

    @Test
    void reviewStateReportsVerdictsFindingsAndSubmission() throws Exception {
        context.annotations.add(finding("f1", Severity.BLOCKING));
        context.verdicts.add(new ReviewVerdict(SCOPE, "i1", ReviewVerdict.Decision.CHANGES,
                Optional.of("needs a test"), Instant.EPOCH, "base-1", "head-1"));
        context.submitted.add(SCOPE);

        JsonValue result = router.call(caller, "review_state", args("scopeId", SCOPE));

        JsonValue intent = ((JsonArray) field(result, "intents")).elements().get(0);
        assertEquals("i1", str(intent, "id"));
        assertEquals("changes", str(intent, "verdict"));
        assertEquals("f1", str(((JsonArray) field(result, "findings")).elements().get(0), "id"));
        assertTrue(((JsonBoolean) field(result, "submitted")).value());
    }

    /** So a follow-up run fixes the right things and does not re-flag settled ones. */
    @Test
    void reviewStateShowsAResolvedFindingAsResolved() throws Exception {
        context.annotations.add(finding("f1", Severity.BLOCKING).withStatus(AnnotationStatus.RESOLVED));

        JsonValue result = router.call(caller, "review_state", args("scopeId", SCOPE));

        JsonValue finding = ((JsonArray) field(result, "findings")).elements().get(0);
        assertTrue(((JsonBoolean) field(finding, "resolved")).value());
    }

    // ---- fixtures -----------------------------------------------------------

    private static ReviewAnnotation finding(String id, Severity severity) {
        return new ReviewAnnotation(SCOPE, id, Optional.of("i1"), "src/Main.java", "n42", "n42",
                severity, Confidence.HIGH, Optional.of("Title"), "Claude", Instant.EPOCH,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false);
    }

    private static UnifiedDiff diff(int hunks) {
        List<UnifiedDiff.Hunk> list = new ArrayList<>();
        for (int h = 0; h < hunks; h++) {
            List<UnifiedDiff.Line> lines = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                int line = h * 10 + i;
                lines.add(new UnifiedDiff.Line(
                        i == 3 ? UnifiedDiff.Line.Kind.ADD : UnifiedDiff.Line.Kind.CONTEXT,
                        i == 3 ? OptionalInt.empty() : OptionalInt.of(line),
                        OptionalInt.of(line), "    source line " + line));
            }
            list.add(new UnifiedDiff.Hunk("@@ hunk " + h + " @@", lines));
        }
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/Main.java", "M", 1, 0, false, false, list)));
    }

    private static JsonObject intentJson(String id, String title, String risk) {
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(id));
        obj.put("title", new JsonString(title));
        obj.put("kind", new JsonString("change"));
        obj.put("risk", new JsonString(risk));
        obj.put("rationale", new JsonString("because"));
        return obj;
    }

    private static JsonObject findingJson(String id, String severity, String body) {
        JsonObject anchor = JsonObject.empty();
        anchor.put("file", new JsonString("src/Main.java"));
        anchor.put("startKey", new JsonString("n42"));
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(id));
        obj.put("intentId", new JsonString("i1"));
        obj.put("anchor", anchor);
        obj.put("severity", new JsonString(severity));
        obj.put("confidence", new JsonString("high"));
        obj.put("body", new JsonString(body));
        return obj;
    }

    private static JsonObject findingArgs(String id, String severity, String body) {
        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("findings", new JsonArray(List.of(findingJson(id, severity, body))));
        return args;
    }

    private static JsonObject args(String... pairs) {
        JsonObject obj = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.put(pairs[i], new JsonString(pairs[i + 1]));
        }
        return obj;
    }

    private static JsonValue field(JsonValue value, String key) {
        return ((JsonObject) value).get(key);
    }

    private static String str(JsonValue value, String key) {
        return ((JsonString) field(value, key)).value();
    }
}
