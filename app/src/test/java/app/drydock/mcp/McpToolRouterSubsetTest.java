package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static app.drydock.mcp.JsonPeek.array;
import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolRouterSubsetTest {

    private static final Set<String> REVIEW_NAMES = Set.of(
            "review_comments", "review_reply", "review_scope", "review_tour", "review_check",
            "review_finding", "review_answer", "review_ask_answer", "review_state",
            "review_recheck");

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
    }

    private static Set<String> names(List<JsonValue> descriptors) {
        return descriptors.stream().map(d -> str(d, "name")).collect(Collectors.toSet());
    }

    @Test
    void reviewSubsetListsExactlyTheTenReviewTools() {
        McpToolRouter router = new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS);

        List<JsonValue> descriptors = router.toolDescriptors();

        assertEquals(10, descriptors.size());
        assertEquals(REVIEW_NAMES, names(descriptors));
        assertEquals(REVIEW_NAMES, McpToolRouter.REVIEW_TOOLS);
    }

    @Test
    void hiddenToolsAreIndistinguishableFromUnknownOnes() {
        McpToolRouter router = new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS);

        for (String hidden : new String[] {"worktree_create", "sessions_list", "session_reclaim"}) {
            McpToolException e = assertThrows(McpToolException.class,
                    () -> router.call(caller, hidden, JsonObject.empty()));
            assertEquals("Unknown tool: " + hidden, e.getMessage());
        }
        McpToolException unknown = assertThrows(McpToolException.class,
                () -> router.call(caller, "no_such_tool", JsonObject.empty()));
        assertEquals("Unknown tool: no_such_tool", unknown.getMessage());
    }

    @Test
    void twoArgumentConstructorKeepsTheFullAdvertisedSet() throws Exception {
        McpToolRouter router = new McpToolRouter(context, registry);

        List<JsonValue> descriptors = router.toolDescriptors();

        assertEquals(16, descriptors.size());
        Set<String> expected = new HashSet<>(REVIEW_NAMES);
        expected.addAll(Set.of("worktree_create", "session_start", "session_rename",
                "session_handoff", "repos_list", "sessions_list"));
        assertEquals(expected, names(descriptors));
        // Unadvertised, but still dispatched: a full router must not call it unknown.
        context.sessionRunning = true;
        router.call(caller, "session_reclaim",
                JsonObject.empty().put("agentSessionId", new JsonString("new-conv")));
    }

    @Test
    void aNameThatIsNotAToolIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolRouter(context, registry, Set.of("review_state", "no_such_tool")));
    }

    @Test
    void toolsListOverHttpReturnsOnlyTheSubset() throws Exception {
        String token = registry.mint(caller, Spawn.ALLOWED);
        McpServer server = new McpServer(registry,
                new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS));
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Drydock-Session-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonValue result = field(JsonParser.parse(response.body()), "result");
            assertEquals(REVIEW_NAMES, names(array(result, "tools")));
        } finally {
            server.close();
        }
    }
}
