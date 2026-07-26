package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.noArgs;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterWorktreeTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private final Path repo = Path.of("/repos/drydock");
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(repo);
        context.worktreePath = Optional.of(repo);
        context.worktrees.add(repo);
        registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry);
    }

    @Test
    void worktreeCreateReturnsThePathAndBranch() throws Exception {
        JsonValue result = router.call(caller, "worktree_create", args("branch", "feat/try-a"));

        assertEquals("feat/try-a", str(result, "branch"));
        assertEquals(context.createdWorktrees.get("feat/try-a").toString(), str(result, "path"));
    }

    @Test
    void worktreeCreatePassesAnExplicitStartPointThrough() throws Exception {
        router.call(caller, "worktree_create", args("branch", "feat/from-main", "start_point", "origin/main"));

        assertTrue(context.createdWorktrees.containsKey("feat/from-main"));
    }

    @Test
    void worktreeCreateRequiresABranchName() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", noArgs()));

        assertTrue(failure.getMessage().contains("branch"), failure.getMessage());
    }

    @Test
    void aBranchNameThatShadowsARemoteIsRefusedBeforeGitRuns() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "origin/main")));

        assertTrue(failure.getMessage().contains("origin"), failure.getMessage());
        assertTrue(context.createdWorktrees.isEmpty(), "git must not have been called");
    }

    @Test
    void aMalformedBranchNameIsRefusedBeforeGitRuns() {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "has space")));

        assertTrue(context.createdWorktrees.isEmpty());
    }

    @Test
    void worktreeCreateSurfacesTheUnderlyingGitFailureVerbatim() {
        context.failure = new McpToolException("A branch named 'feat/try-a' already exists.");

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/try-a")));

        assertEquals("A branch named 'feat/try-a' already exists.", failure.getMessage());
    }

    @Test
    void anAgentStartedSessionMayNotCreateWorktrees() {
        ManagedSessionId child = ManagedSessionId.newId();
        registry.mint(child, Spawn.FORBIDDEN);

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(child, "worktree_create", args("branch", "feat/deeper")));

        assertTrue(failure.getMessage().toLowerCase().contains("started by an agent")
                        || failure.getMessage().toLowerCase().contains("not permitted"),
                failure.getMessage());
        assertTrue(context.createdWorktrees.isEmpty());
    }

    @Test
    void theBudgetIsEnforcedAndNamesTheLimit() throws Exception {
        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            router.call(caller, "worktree_create", args("branch", "feat/try-" + i));
        }

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/one-too-many")));

        assertTrue(failure.getMessage().contains(String.valueOf(McpSessionRegistry.MAX_WORKTREES_PER_SESSION)),
                failure.getMessage());
    }

    @Test
    void aRefusedBranchNameDoesNotSpendBudget() throws Exception {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "origin/main")));

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            router.call(caller, "worktree_create", args("branch", "feat/try-" + i));
        }
    }

    @Test
    void aFailedGitCreateDoesNotSpendBudget() throws Exception {
        context.failure = new McpToolException("A branch named 'feat/x' already exists.");
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/x")));

        context.failure = null;
        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            router.call(caller, "worktree_create", args("branch", "feat/try-" + i));
        }
    }
}
