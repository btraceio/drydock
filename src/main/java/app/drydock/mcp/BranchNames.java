package app.drydock.mcp;

import app.drydock.git.BranchNameRules;

import java.util.Set;

/**
 * Validates a branch name proposed by an agent-driven MCP tool call (e.g.
 * {@code worktree_create}) before it is handed to git.
 *
 * <p>The judging lives in {@link BranchNameRules}, because the same rules
 * govern the create-worktree modal and the local name derived when a
 * remote-tracking ref is adopted -- see that class for what each rule is for,
 * why the remote check compares whole path components case-insensitively, and
 * why the evaluation order is a contract rather than an accident.</p>
 *
 * <p>What stays here is the agent-facing surface: the {@code remoteNames}
 * precondition, which is about this caller rather than about the name, and
 * the translation of a refusal into the {@link McpToolException} wording
 * {@code worktree_create} has always returned.</p>
 */
public final class BranchNames {

    private BranchNames() {
    }

    /**
     * @throws McpToolException if {@code branch} is blank, {@code remoteNames}
     *         is {@code null}, {@code branch} is fully qualified (starts with
     *         {@code refs/}), shadows one of {@code remoteNames}, or violates
     *         git's refname rules.
     */
    public static void validate(String branch, Set<String> remoteNames) throws McpToolException {
        // Blank is tested before the precondition so that validate(null, null)
        // keeps complaining about the branch, as it always has.
        if (branch == null || branch.isBlank()) {
            throw new McpToolException("branch must not be blank");
        }
        if (remoteNames == null) {
            throw new McpToolException("remoteNames must not be null");
        }

        BranchNameRules.Refusal refusal = BranchNameRules.check(branch, remoteNames).orElse(null);
        if (refusal != null) {
            throw new McpToolException(BranchNameRules.agentMessage(branch, refusal));
        }
    }
}
