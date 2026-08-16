package app.drydock.mcp;

import java.nio.file.Path;

/**
 * A {@code git worktree add} whose wait ended without a verdict, so a
 * worktree may already exist at the target.
 *
 * <p>Its whole job is to be a distinct type: {@link McpToolRouter} refunds
 * the worktree budget for every {@link McpToolException} a create throws, and
 * refunding this one would be a trap. {@code git worktree add} creates the
 * directory and its {@code .git/worktrees} entry well before it finishes, so
 * an agent told "nothing happened" retries into
 * {@code fatal: '<dir>' already exists} -- charged nothing, told nothing, and
 * stuck there forever. Being wrong costs one of four worktrees; being wrong
 * the other way costs a free worktree and a permanently poisoned retry.</p>
 *
 * <p>Top-level rather than nested beside {@code DeadlineExceededException},
 * which is private to {@code WorkspaceMcpSessionContext}: the router has to
 * name this one in a {@code catch}.</p>
 */
public class McpWorktreeMayExistException extends McpToolException {

    public McpWorktreeMayExistException(Path directory) {
        super("git worktree add was interrupted; a worktree may exist at " + directory
                + " — check before retrying.");
    }
}
