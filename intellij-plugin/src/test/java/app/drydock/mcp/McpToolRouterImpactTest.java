package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static app.drydock.mcp.JsonPeek.array;
import static app.drydock.mcp.JsonPeek.bool;
import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.num;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code review_scope}'s {@code impact} include (spec §8): measured callers
 * per changed declaration site and the signature-changed flag, built from
 * the same graph {@code sections} uses.
 */
class McpToolRouterImpactTest extends McpRouterFixture {

    @Test
    void impactIsOmittedUnlessAsked() {
        JsonValue result = callReviewScopeValue(scopeId(), "sections", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        assertFalse(((JsonObject) result).has("impact"));
    }

    @Test
    void includeImpactReturnsOneEntryPerChangedDeclarationSite() {
        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        JsonValue run = entryFor(result, "run");
        JsonValue declaredIn = field(run, "declaredIn");
        assertEquals("src/Widget.java", str(declaredIn, "file"));
        assertEquals("n2", str(declaredIn, "lineKey"));
        assertTrue(field(run, "calledFrom") instanceof JsonArray);
        assertFalse(((JsonObject) result).has("sections"), "impact alone must not drag sections in");
        assertFalse(((JsonObject) run).has("ambiguous"), "run is declared in one changed file");
        assertEquals(1, graphBuilds());
    }

    /** Callees inside the change: names the declaring hunk references in other changed files. */
    @Test
    void anEntryListsWhatItsDeclaringHunkCallsInsideTheChange() {
        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        List<String> calls = array(entryFor(result, "use"), "callsInChange").stream()
                .map(name -> ((JsonString) name).value())
                .toList();
        assertTrue(calls.contains("run"), "WidgetUser.use calls Widget.run: " + calls);
        assertEquals(List.of(), array(entryFor(result, "run"), "callsInChange"));
    }

    /** An edited declaration line (DEL + ADD) is one declaration: one entry, on its new row. */
    @Test
    void anEditedDeclarationLineIsOneEntry() {
        context.reviewDiff = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 1,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,3 +1,3 @@", List.of(
                        line(UnifiedDiff.Line.Kind.CONTEXT, 1, 1, "class Holder {"),
                        line(UnifiedDiff.Line.Kind.DEL, 2, 0, "    void foo(int a) { }"),
                        line(UnifiedDiff.Line.Kind.ADD, 0, 2, "    void foo(long a) { }"),
                        line(UnifiedDiff.Line.Kind.CONTEXT, 3, 3, "}")))))));

        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        List<JsonValue> foos = array(result, "impact").stream()
                .filter(entry -> "foo".equals(str(entry, "symbol")))
                .toList();
        assertEquals(1, foos.size(), "impact was " + array(result, "impact"));
        assertEquals("n2", str(field(foos.get(0), "declaredIn"), "lineKey"));
    }

    /** A name two changed files declare cannot be attributed: it says so, and is never flagged. */
    @Test
    void aNameDeclaredInTwoChangedFilesIsAmbiguousAndNeverFlagged() {
        context.reviewDiff = new UnifiedDiff(List.of(
                addedFile("src/Gamma.java", "public class Gamma { void shared() { } }"),
                addedFile("src/Delta.java", "public class Delta { void shared() { } }")));

        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        List<JsonValue> shared = array(result, "impact").stream()
                .filter(entry -> "shared".equals(str(entry, "symbol")))
                .toList();
        assertEquals(2, shared.size(), "one entry per declaring file: " + shared);
        for (JsonValue entry : shared) {
            assertTrue(bool(entry, "ambiguous"), "entry " + entry);
            assertFalse(bool(entry, "signatureChanged"), "entry " + entry);
        }
    }

    @Test
    void sectionsAndImpactTogetherBuildTheGraphOnce() {
        JsonValue result = callReviewScopeValue(scopeId(), "sections, impact", null,
                McpToolRouter.DEFAULT_SCOPE_BYTES);

        assertTrue(field(result, "impact") instanceof JsonArray);
        assertTrue(field(result, "sections") instanceof JsonArray);
        assertEquals(1, graphBuilds());
    }

    @Test
    void separateSectionsAndImpactReadsOfTheSameDiffShareOneGraphBuild() {
        callReviewScope(scopeId(), "sections");
        callReviewScope(scopeId(), "impact");

        assertEquals(1, graphBuilds());
    }

    /** The default scope's worktree does not exist, so the caller search cannot run, and says why. */
    @Test
    void aFailedCallerSearchIsReportedAsUnavailableNotAsNoCallers() {
        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        assertTrue(field(result, "impactUnavailable") instanceof JsonString reason && !reason.value().isBlank(),
                "the reason must be reported: " + result);
    }

    @Test
    void anUneditedOutsideCallerFlagsTheChangedDeclaration(@TempDir Path dir) throws Exception {
        bindScopeTo(repoWhereRunIsCalledFromOutside(dir));

        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        assertFalse(((JsonObject) result).has("impactUnavailable"), "the scan ran: " + result);
        JsonValue run = entryFor(result, "run");
        List<JsonValue> calledFrom = array(run, "calledFrom");
        assertEquals(1, calledFrom.size(), "calledFrom was " + calledFrom);
        assertEquals("src/Outside.java", str(calledFrom.get(0), "file"));
        assertEquals(1, num(calledFrom.get(0), "line"));
        assertFalse(bool(calledFrom.get(0), "inChangedFile"));
        assertTrue(bool(run, "signatureChanged"));
        assertEquals(1, num(run, "uneditedCallSites"));
    }

    /**
     * The swamping fix, end to end through the real grep: a file outside the
     * change that declares its own {@code run} is dropped whole -- its
     * occurrences are uses of its own symbol, not callers of the change's --
     * while a file that only calls {@code run} keeps its row.
     */
    @Test
    void aFileOutsideTheChangeThatDeclaresItsOwnRunIsDroppedWhole(@TempDir Path dir) throws Exception {
        Path repo = repoWhereRunIsCalledFromOutside(dir);
        Files.writeString(repo.resolve("src/OwnRun.java"),
                "class OwnRun {\n    void run() {\n    }\n    void go() { run(); }\n}\n");
        runGit(repo, "add", "-A");
        bindScopeTo(repo);

        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        JsonValue run = entryFor(result, "run");
        List<JsonValue> calledFrom = array(run, "calledFrom");
        assertEquals(1, calledFrom.size(), "only the genuine caller: " + calledFrom);
        assertEquals("src/Outside.java", str(calledFrom.get(0), "file"));
    }

    /**
     * Past the attribution cap a symbol is a count, not a list: calledFrom
     * empties, tooCommonToAttribute carries the number, and the
     * signature-changed signal survives (the change DID leave unedited
     * sites spelling the name).
     */
    @Test
    void aTooCommonSymbolIsACountNotARowPerOccurrence(@TempDir Path dir) throws Exception {
        Path repo = repoWhereRunIsCalledFromOutside(dir);
        for (int i = 1; i <= app.drydock.review.OutOfDiffFanIn.MAX_ATTRIBUTABLE; i++) {
            Files.writeString(repo.resolve("src/Caller" + i + ".java"),
                    "class Caller" + i + " { void go() { new Widget().run(); } }\n");
        }
        runGit(repo, "add", "-A");
        bindScopeTo(repo);

        JsonValue result = callReviewScopeValue(scopeId(), "impact", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        JsonValue run = entryFor(result, "run");
        assertEquals(List.of(), array(run, "calledFrom"), "51 rows would be a wall: " + result);
        assertEquals(app.drydock.review.OutOfDiffFanIn.MAX_ATTRIBUTABLE + 1,
                num(run, "tooCommonToAttribute"));
        assertTrue(bool(run, "signatureChanged"));
        assertEquals(app.drydock.review.OutOfDiffFanIn.MAX_ATTRIBUTABLE + 1, num(run, "uneditedCallSites"));
    }

    // ---- fixtures -----------------------------------------------------------

    private static JsonValue entryFor(JsonValue result, String symbol) {
        return array(result, "impact").stream()
                .filter(entry -> symbol.equals(str(entry, "symbol")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no impact entry for " + symbol + ": " + result));
    }

    private static UnifiedDiff.Line line(UnifiedDiff.Line.Kind kind, int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(kind, oldLine == 0 ? OptionalInt.empty() : OptionalInt.of(oldLine),
                newLine == 0 ? OptionalInt.empty() : OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.FileDiff addedFile(String path, String text) {
        return new UnifiedDiff.FileDiff(path, "A", 1, 0, false, false, List.of(new UnifiedDiff.Hunk("@@ -0,0 +1 @@",
                List.of(line(UnifiedDiff.Line.Kind.ADD, 0, 1, text)))));
    }

    /** A committed repository whose only file calls the fixture's {@code Widget.run}. */
    private static Path repoWhereRunIsCalledFromOutside(Path parent) throws Exception {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Outside.java"), "class Outside { void go() { new Widget().run(); } }\n");
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-m", "seed");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessResult result = ProcessRunner.run(command, repo, Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
        }
    }
}
