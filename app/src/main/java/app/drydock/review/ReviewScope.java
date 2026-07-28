package app.drydock.review;

import app.drydock.domain.ManagedSessionId;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One reviewable thing, addressed by an opaque handle rather than by a
 * session id (Review MCP schema §0).
 *
 * <p>The Review destination is cross-repo, but {@code McpSessionRegistry}
 * binds an MCP caller to exactly <em>one</em> session. A scope handle is
 * therefore the unit an agent addresses: {@link ReviewScopeRegistry} mints
 * it, the human grants it to a session, and every review MCP call carries
 * its {@link #id()}.</p>
 *
 * <p>{@link #worktree()} is empty only for a PR that has not been checked
 * out. That is the whole reason the checkout gate exists: no worktree means
 * no session, which means no agent caller and therefore no reviewer -- the
 * diff can still be read, but nothing can be asked of an agent.</p>
 */
public record ReviewScope(
        String id,
        Kind kind,
        Path repoRoot,
        Optional<Path> worktree,
        String base,
        String head,
        Optional<PullRequestRef> pr,
        Optional<ManagedSessionId> sessionId) {

    /** What is being reviewed; drives the queue icon and how the diff is resolved. */
    public enum Kind {
        /** An agent-authored (or human) checkout under {@code git worktree}. */
        WORKTREE,
        /** Uncommitted changes in a checkout ({@code ❯_}). */
        WORKING_TREE,
        /** A branch against its base, in the main checkout. */
        BRANCH,
        /** A GitHub pull request, checked out or not. */
        PR,
        /** A stack of dependent PRs. */
        STACK
    }

    /** The GitHub side of a {@link Kind#PR} scope. */
    public record PullRequestRef(int number, Optional<String> url) {
        public PullRequestRef {
            if (number <= 0) {
                throw new IllegalArgumentException("PR number must be positive: " + number);
            }
            Objects.requireNonNull(url, "url");
        }
    }

    public ReviewScope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(repoRoot, "repoRoot");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(pr, "pr");
        Objects.requireNonNull(sessionId, "sessionId");
        if (id.isBlank()) {
            throw new IllegalArgumentException("scope id must not be blank");
        }
        if (kind == Kind.PR && pr.isEmpty()) {
            throw new IllegalArgumentException("a PR scope must carry a PullRequestRef");
        }
    }

    /**
     * The checkout a diff is read from: the worktree when there is one,
     * otherwise the repository's main checkout. A not-checked-out PR
     * therefore still resolves to a directory git can be run in -- what it
     * does not resolve to is a directory containing the PR's code.
     */
    public Path diffRoot() {
        return worktree.orElse(repoRoot);
    }

    /** This scope with {@code session} bound (the checkout gate's result, M5). */
    public ReviewScope withSession(ManagedSessionId session) {
        return new ReviewScope(id, kind, repoRoot, worktree, base, head, pr,
                Optional.ofNullable(session));
    }

    /** This scope with {@code newWorktree} as its checkout. */
    public ReviewScope withWorktree(Path newWorktree) {
        return new ReviewScope(id, kind, repoRoot, Optional.ofNullable(newWorktree), base, head, pr,
                sessionId);
    }
}
