package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.argsWithFlag;
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

    /**
     * The refund is what makes a retry possible, so it must not happen when
     * the add may have already created the directory: the retry would hit
     * {@code fatal: '<dir>' already exists} forever, charged nothing and told
     * nothing. One of four worktrees is the price of being wrong; a free
     * worktree and a permanently poisoned retry is the price of the other way
     * round.
     */
    @Test
    void anAddThatMayHaveCreatedAWorktreeKeepsTheCharge() throws Exception {
        context.failure = new McpWorktreeMayExistException(Path.of("/wt/feat-x"));
        assertThrows(McpWorktreeMayExistException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/x")));

        context.failure = null;
        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION - 1; i++) {
            router.call(caller, "worktree_create", args("branch", "feat/try-" + i));
        }
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/one-too-many")));
    }

    // ---- existing: true -----------------------------------------------------

    @Test
    void existingChecksOutTheBranchAndReportsTheResolvedLocalName() throws Exception {
        context.adopted = new McpSessionContext.ExistingBranchWorktree(
                Path.of("/wt/drydock-login"), "feat/login", Optional.of("origin/feat/login"));

        JsonValue result = router.call(caller, "worktree_create",
                argsWithFlag("existing", true, "branch", "origin/feat/login"));

        assertEquals("/wt/drydock-login", str(result, "path"));
        // The resolved name, not the argument: they differ here, and it is the
        // name the agent has to hand to session_start.
        assertEquals("feat/login", str(result, "branch"));
        assertEquals("origin/feat/login", str(result, "tracking"));
        assertEquals(List.of("origin/feat/login"), context.adoptedBranches);
    }

    @Test
    void adoptingALocalBranchReportsANullTracking() throws Exception {
        context.adopted = new McpSessionContext.ExistingBranchWorktree(
                Path.of("/wt/drydock-login"), "feat/login", Optional.empty());

        JsonValue result = router.call(caller, "worktree_create",
                argsWithFlag("existing", true, "branch", "feat/login"));

        assertEquals(JsonNull.INSTANCE, JsonPeek.field(result, "tracking"));
    }

    /**
     * The failure the explicit flag exists to prevent. Some clients stringify
     * every argument; coerced to false, this would create a brand-new branch
     * off the caller's HEAD with none of the branch's commits and report it
     * indistinguishably from an adoption.
     */
    @Test
    void aStringifiedExistingIsRefusedRatherThanCoercedToFalse() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/login", "existing", "true")));

        assertEquals("Argument 'existing' must be a boolean (true or false).", failure.getMessage());
        assertTrue(context.adoptedBranches.isEmpty());
        assertTrue(context.createdWorktrees.isEmpty());
    }

    @Test
    void aStartPointWithExistingIsRefusedBeforeAnythingIsCharged() throws Exception {
        McpToolException failure = assertThrows(McpToolException.class, () -> router.call(caller, "worktree_create",
                argsWithFlag("existing", true, "branch", "feat/login", "start_point", "origin/main")));

        assertEquals("start_point cannot be combined with existing: true; an existing branch already "
                + "has its history.", failure.getMessage());
        context.adopted = new McpSessionContext.ExistingBranchWorktree(
                Path.of("/wt/x"), "feat/x", Optional.empty());
        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            router.call(caller, "worktree_create", args("branch", "feat/try-" + i));
        }
    }

    /**
     * The minting rules vet a name being created; with existing they would vet
     * a lookup key, and origin/feat/login is a legitimate way to name a branch
     * that already exists. What guards this path instead is the catalog: only
     * refs git itself listed get through.
     */
    @Test
    void anExistingBranchIsNotPutThroughTheNewBranchNameRules() throws Exception {
        context.adopted = new McpSessionContext.ExistingBranchWorktree(
                Path.of("/wt/drydock-main"), "main", Optional.of("origin/main"));

        router.call(caller, "worktree_create", argsWithFlag("existing", true, "branch", "origin/main"));

        assertEquals(List.of("origin/main"), context.adoptedBranches);
    }

    @Test
    void anExplicitFalseIsStillTheCreatePath() throws Exception {
        router.call(caller, "worktree_create", argsWithFlag("existing", false, "branch", "feat/login"));

        assertTrue(context.createdWorktrees.containsKey("feat/login"));
        assertTrue(context.adoptedBranches.isEmpty());
    }

    @Test
    void aFailedAdoptionRefundsButAnAdoptionThatMayHaveLeftAWorktreeDoesNot() throws Exception {
        context.adoptFailure = new McpToolException("No branch named 'feat/x' in this repository; "
                + "omit existing to create it.");
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", argsWithFlag("existing", true, "branch", "feat/x")));

        context.adoptFailure = new McpWorktreeMayExistException(Path.of("/wt/drydock-x"));
        assertThrows(McpWorktreeMayExistException.class,
                () -> router.call(caller, "worktree_create", argsWithFlag("existing", true, "branch", "feat/x")));

        // One charge survives, so only three of the four remain.
        context.adoptFailure = null;
        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION - 1; i++) {
            router.call(caller, "worktree_create", args("branch", "feat/try-" + i));
        }
        assertThrows(McpToolException.class,
                () -> router.call(caller, "worktree_create", args("branch", "feat/one-too-many")));
    }
}
