package app.drydock.mcp;

import java.util.Set;

/**
 * Validates a branch name proposed by an agent-driven MCP tool call (e.g.
 * {@code worktree_create}) before it is handed to git.
 *
 * <p>The load-bearing case is a name that collides with a remote: creating
 * local branch {@code refs/heads/origin/main} <em>shadows</em>
 * {@code refs/remotes/origin/main} for every short-name lookup, so a later
 * {@code git merge origin/main} would silently target this agent-chosen
 * commit instead of the fetched ref. {@code git worktree add} exits 0 and
 * warns only on stderr, so nothing downstream would catch it. This was
 * verified empirically against real git before this class was written.</p>
 *
 * <p>The remote check compares the whole first path component against each
 * remote name, case-insensitively, for three reasons: (1) a remote name that
 * only prefixes the first component -- {@code "originals"} vs. remote
 * {@code "origin"} -- must not match, only a whole component does; (2) a ref
 * file such as {@code .git/refs/heads/origin/main} is a plain {@code open()}
 * on the macOS default case-insensitive filesystem, so a branch named
 * {@code "Origin/main"} lands in the very same place as {@code "origin/main"}
 * and shadows the remote just the same -- verified against real git 2.49,
 * where {@code git branch Origin/main HEAD} exits 0 and {@code origin/main}
 * then resolves to the agent-chosen commit instead of the fetched upstream
 * one; and (3) git permits a remote name that itself contains a slash (e.g.
 * {@code remote.foo/bar.url}), so the check cannot simply split the branch on
 * {@code "/"} and compare only the first segment.</p>
 *
 * <p>The remaining rules mirror {@code git check-ref-format --branch}. They
 * are applied locally rather than by spawning {@code git check-ref-format}:
 * this runs on every tool call, the rules are stable, and a per-call process
 * spawn to validate a string is not the kind of real work
 * {@code ProcessRunner} exists for.</p>
 */
public final class BranchNames {

    private BranchNames() {
    }

    /** Characters git's refname rules forbid anywhere in the name, besides control characters. */
    private static final String FORBIDDEN_CHARS = " ~^:?*[\\";

    /**
     * @throws McpToolException if {@code branch} is blank, {@code remoteNames}
     *         is {@code null}, {@code branch} is fully qualified (starts with
     *         {@code refs/}), shadows one of {@code remoteNames}, or violates
     *         git's refname rules.
     */
    public static void validate(String branch, Set<String> remoteNames) throws McpToolException {
        if (branch == null || branch.isBlank()) {
            throw new McpToolException("branch must not be blank");
        }
        if (remoteNames == null) {
            throw new McpToolException("remoteNames must not be null");
        }
        if (branch.startsWith("refs/")) {
            throw new McpToolException(
                    "branch must not be a fully qualified ref (starts with 'refs/'): " + branch);
        }

        for (String remote : remoteNames) {
            String remoteWithSlash = remote + "/";
            if (branch.regionMatches(true, 0, remoteWithSlash, 0, remoteWithSlash.length())) {
                throw new McpToolException("'" + branch + "' as a local branch shadows the remote-tracking ref '"
                        + remote + "'; pick a name that does not start with a remote name");
            }
        }

        String[] components = branch.split("/", -1);

        if (branch.contains("..")) {
            throw new McpToolException("branch name must not contain '..': " + branch);
        }
        if (branch.contains("@{")) {
            throw new McpToolException("branch name must not contain '@{': " + branch);
        }
        if (branch.contains("//")) {
            throw new McpToolException("branch name must not contain consecutive slashes: " + branch);
        }
        if (branch.startsWith("-")) {
            throw new McpToolException("branch name must not start with '-': " + branch);
        }
        if (branch.endsWith("/") || branch.endsWith(".")) {
            throw new McpToolException("branch name must not end with '/' or '.': " + branch);
        }
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (FORBIDDEN_CHARS.indexOf(c) >= 0 || Character.isISOControl(c)) {
                throw new McpToolException(
                        "branch name must not contain '" + c + "': " + branch);
            }
        }
        for (String component : components) {
            if (component.isEmpty()) {
                throw new McpToolException("branch name must not have an empty path component: " + branch);
            }
            if (component.startsWith(".")) {
                throw new McpToolException("branch name component must not start with '.': " + component);
            }
            if (component.endsWith(".lock")) {
                throw new McpToolException("branch name component must not end with '.lock': " + component);
            }
        }
    }
}
