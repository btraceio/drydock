package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Triage;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code review_finding} lands proposals; the human's triage outlives a re-run. */
class McpToolRouterTriageTest {

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
        context.reviewDiff = new UnifiedDiff(List.of());
    }

    @Test
    void aNewAgentFindingIsProposed() throws Exception {
        router.call(caller, "review_finding", findingArgs("f1", "question", null));

        assertEquals(Triage.PROPOSED, context.findingsOf(SCOPE).get(0).triage());
    }

    @Test
    void aReRunKeepsTheHumansTriage() throws Exception {
        router.call(caller, "review_finding", findingArgs("f1", "question", null));
        context.mutateAnnotation(new ReviewAnnotation.Key(SCOPE, "f1"),
                f -> f.withTriage(Triage.DISMISSED));

        router.call(caller, "review_finding", findingArgs("f1", "question", null));

        assertEquals(Triage.DISMISSED, context.findingsOf(SCOPE).get(0).triage());
    }

    @Test
    void withheldByIsStored() throws Exception {
        router.call(caller, "review_finding", findingArgs("f1", "nit", "f_parent"));

        assertEquals(Optional.of("f_parent"), context.findingsOf(SCOPE).get(0).withheldBy());
    }

    @Test
    void aBlockingFindingIsNeverWithheld() {
        McpToolException refused = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_finding", findingArgs("f_block", "blocking", "f_parent")));

        assertTrue(refused.getMessage().contains("a blocking finding is never withheld: f_block"),
                refused.getMessage());
        assertTrue(context.findingsOf(SCOPE).isEmpty());
    }

    @Test
    void reviewStateCarriesTheTriage() throws Exception {
        router.call(caller, "review_finding", findingArgs("f1", "question", "f_parent"));

        JsonValue result = router.call(caller, "review_state", args("scopeId", SCOPE));

        JsonValue state = ((JsonArray) ((JsonObject) result).get("findings")).elements().get(0);
        assertEquals("proposed", ((JsonString) ((JsonObject) state).get("triage")).value());
        assertEquals("f_parent", ((JsonString) ((JsonObject) state).get("withheldBy")).value());
    }

    private static JsonObject findingArgs(String id, String severity, String withheldBy) {
        JsonObject anchor = JsonObject.empty();
        anchor.put("file", new JsonString("src/Main.java"));
        anchor.put("startKey", new JsonString("n42"));
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(id));
        obj.put("anchor", anchor);
        obj.put("severity", new JsonString(severity));
        obj.put("confidence", new JsonString("high"));
        obj.put("body", new JsonString("body text"));
        if (withheldBy != null) {
            obj.put("withheldBy", new JsonString(withheldBy));
        }
        JsonObject args = JsonObject.empty();
        args.put("scopeId", new JsonString(SCOPE));
        args.put("findings", new JsonArray(List.of(obj)));
        return args;
    }

    private static JsonObject args(String key, String value) {
        JsonObject obj = JsonObject.empty();
        obj.put(key, new JsonString(value));
        return obj;
    }
}
