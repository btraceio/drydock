package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.ReviewScope;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Shared {@code review_scope} plumbing for tests that only need one bound
 * scope and a real, parseable diff -- modelled on {@link
 * McpToolRouterReviewTest}'s setup, but with actual source text rather than
 * placeholder lines, since a computed grouping (Task 12's {@code Sections})
 * needs something {@code ChangeGraph} can parse to produce a hub symbol.
 */
class McpRouterFixture {

    private static final String SCOPE = "rs_sections";

    private final ManagedSessionId caller = ManagedSessionId.newId();
    FakeMcpSessionContext context;
    McpToolRouter router;

    @BeforeEach
    void setUpFixture() {
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
        context.reviewDiff = parseableDiff();
    }

    String scopeId() {
        return SCOPE;
    }

    /** Calls {@code review_scope}, returning the raw JSON response as a string. */
    String callReviewScope(String scopeId, String include) {
        JsonObject args = JsonObject.empty().put("scopeId", new JsonString(scopeId));
        if (include != null) {
            args.put("include", new JsonString(include));
        }
        try {
            JsonValue result = router.call(callerId(), "review_scope", args);
            return JsonWriter.write(result);
        } catch (McpToolException e) {
            throw new AssertionError(e);
        }
    }

    ManagedSessionId callerId() {
        return caller;
    }

    /** A single-file diff with real Java, so {@code ChangeGraph} finds a hub symbol to name a section after. */
    private static UnifiedDiff parseableDiff() {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        String[] added = {
                "public class Widget {",
                "    void run() {",
                "        System.out.println(\"hi\");",
                "    }",
                "}",
        };
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/Widget.java", "A",
                added.length, 0, false, false, List.of(new UnifiedDiff.Hunk("@@", lines)))));
    }
}
