package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.ChangeGraph;
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
import java.util.concurrent.atomic.AtomicInteger;

import static app.drydock.mcp.JsonPeek.field;

/**
 * Shared {@code review_scope} plumbing for tests that only need one bound
 * scope and a real, parseable diff -- modelled on {@link
 * McpToolRouterReviewTest}'s setup, but with actual source text rather than
 * placeholder lines, since a computed grouping (Task 12's {@code Sections})
 * needs something {@code ChangeGraph} can parse to produce a hub symbol.
 *
 * <p>The router here is wired through the package-private test-seam
 * constructor with a counting {@code graphBuilder}, so a test can assert
 * {@link ChangeGraph} was (or was not) actually built -- not merely that a
 * {@code "sections"} key is absent, which conflates "never built" with
 * "built and discarded".</p>
 */
class McpRouterFixture {

    private static final String SCOPE = "rs_sections";

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private final AtomicInteger graphBuilds = new AtomicInteger();
    private McpSessionRegistry registry;
    FakeMcpSessionContext context;
    McpToolRouter router;

    @BeforeEach
    void setUpFixture() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry, diff -> {
            graphBuilds.incrementAndGet();
            return ChangeGraph.of(diff);
        });

        context.grant(caller, SCOPE);
        bindScopeTo(Path.of("/wt/feat"));
        context.reviewDiff = parseableDiff();
    }

    /**
     * Points the bound scope's worktree at {@code worktree}. The default is
     * a path that does not exist, so the out-of-diff fan-in scan behind
     * {@code sections} reports "unavailable" and costs nothing; a test that
     * wants a REAL scan hands in a real repository.
     */
    void bindScopeTo(Path worktree) {
        context.reviewScopes.put(SCOPE, new ReviewScope(SCOPE, ReviewScope.Kind.WORKTREE,
                Path.of("/repos/drydock"), Optional.of(worktree), "master", "feat",
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    String scopeId() {
        return SCOPE;
    }

    /** How many times {@link ChangeGraph#of} actually ran, real work and all -- not merely what the wire shows. */
    int graphBuilds() {
        return graphBuilds.get();
    }

    /** Calls {@code review_scope} with the default byte budget, returning the raw JSON response as a string. */
    String callReviewScope(String scopeId, String include) {
        return JsonWriter.write(callReviewScopeValue(scopeId, include, null, McpToolRouter.DEFAULT_SCOPE_BYTES));
    }

    /** As above, but resuming from a prior page's cursor -- the default budget still applies. */
    String callReviewScope(String scopeId, String include, String cursor) {
        return JsonWriter.write(callReviewScopeValue(scopeId, include, cursor, McpToolRouter.DEFAULT_SCOPE_BYTES));
    }

    /** Full control, for a test that needs a small budget to force a genuine second page. */
    JsonValue callReviewScopeValue(String scopeId, String include, String cursor, int maxBytes) {
        JsonObject args = JsonObject.empty()
                .put("scopeId", new JsonString(scopeId))
                .put("maxBytes", new JsonString(String.valueOf(maxBytes)));
        if (include != null) {
            args.put("include", new JsonString(include));
        }
        if (cursor != null) {
            args.put("cursor", new JsonString(cursor));
        }
        try {
            return router.call(callerId(), "review_scope", args);
        } catch (McpToolException e) {
            throw new AssertionError(e);
        }
    }

    /** The cursor a {@code review_scope} response carries, or null for a complete read. */
    static String cursorOf(JsonValue response) {
        return field(response, "cursor") instanceof JsonString cursor ? cursor.value() : null;
    }

    /**
     * Rewires the router so building a section's {@link ChangeGraph} throws,
     * as a real parse edge case in {@code SymbolScan} would -- for a test
     * pinning that a {@code sections} failure degrades the whole call rather
     * than failing it.
     */
    void makeGraphBuildingFail() {
        router = new McpToolRouter(context, registry, diff -> {
            throw new IllegalStateException("synthetic parse failure");
        });
    }

    ManagedSessionId callerId() {
        return caller;
    }

    /**
     * Two real, cross-referencing Java files, so {@code ChangeGraph} has both
     * a hub symbol to title a section after AND a shared-foundation edge
     * (spec §5.6's overlap) -- {@code Widget} is pulled into {@code
     * WidgetUser}'s own section, which is what makes the sections payload
     * scale with sections-times-shared-files rather than plain file count.
     * Two files also means two hunks, so a small {@code maxBytes} can force
     * a genuine second page.
     */
    private static UnifiedDiff parseableDiff() {
        UnifiedDiff.FileDiff widget = file("src/Widget.java",
                "public class Widget {",
                "    void run() {",
                "        System.out.println(\"hi\");",
                "    }",
                "}");
        UnifiedDiff.FileDiff widgetUser = file("src/WidgetUser.java",
                "public class WidgetUser {",
                "    void use() {",
                "        Widget w = new Widget();",
                "        w.run();",
                "    }",
                "}");
        return new UnifiedDiff(List.of(widget, widgetUser));
    }

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "A", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }
}
