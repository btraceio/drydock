package app.drydock.mcp;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        assertEquals(1, graphBuilds());
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

    // ---- fixtures -----------------------------------------------------------

    private static JsonValue entryFor(JsonValue result, String symbol) {
        return array(result, "impact").stream()
                .filter(entry -> symbol.equals(str(entry, "symbol")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no impact entry for " + symbol + ": " + result));
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
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
