package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewAnnotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Hand-written fake for {@link McpSessionContext}; the build has no mocking library. */
final class FakeMcpSessionContext implements McpSessionContext {

    Optional<Path> repositoryRoot = Optional.empty();
    Optional<Path> worktreePath = Optional.empty();
    Optional<String> baseBranch = Optional.of("main");
    final List<ReviewAnnotation> annotations = new ArrayList<>();
    final List<RepoSummary> repositories = new ArrayList<>();
    final List<SessionSummary> sessions = new ArrayList<>();
    final List<Path> worktrees = new ArrayList<>();
    final Set<String> remotes = new LinkedHashSet<>(Set.of("origin"));
    final Map<String, String> excerpts = new HashMap<>();
    final Map<String, Path> createdWorktrees = new HashMap<>();
    final List<Path> startedSessions = new ArrayList<>();
    final List<String> startedPrompts = new ArrayList<>();

    /** When set, {@link #createWorktree} and {@link #startSession} throw this. */
    McpToolException failure;

    @Override
    public Optional<Path> repositoryRoot(ManagedSessionId caller) {
        return repositoryRoot;
    }

    @Override
    public Optional<Path> worktreePath(ManagedSessionId caller) {
        return worktreePath;
    }

    @Override
    public Optional<String> baseBranch(ManagedSessionId caller) {
        return baseBranch;
    }

    @Override
    public List<ReviewAnnotation> annotations(ManagedSessionId caller) {
        return List.copyOf(annotations);
    }

    @Override
    public void updateAnnotation(ReviewAnnotation annotation) {
        annotations.replaceAll(existing -> existing.id().equals(annotation.id()) ? annotation : existing);
    }

    @Override
    public Optional<String> excerpt(ManagedSessionId caller, String file, int line, int context) {
        return Optional.ofNullable(excerpts.get(file + ":" + line));
    }

    @Override
    public List<RepoSummary> repositories() {
        return List.copyOf(repositories);
    }

    @Override
    public List<SessionSummary> sessions() {
        return List.copyOf(sessions);
    }

    @Override
    public Set<String> remoteNames(ManagedSessionId caller) {
        return Set.copyOf(remotes);
    }

    @Override
    public List<Path> realWorktreesOf(ManagedSessionId caller) {
        return List.copyOf(worktrees);
    }

    @Override
    public Path createWorktree(ManagedSessionId caller, String branch, Optional<String> startPoint)
            throws McpToolException {
        if (failure != null) {
            throw failure;
        }
        Path root = repositoryRoot.orElseThrow();
        Path created = root.resolveSibling("wt-" + branch.replace('/', '-'));
        createdWorktrees.put(branch, created);
        return created;
    }

    @Override
    public ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException {
        if (failure != null) {
            throw failure;
        }
        startedSessions.add(worktree);
        initialPrompt.ifPresent(startedPrompts::add);
        return ManagedSessionId.newId();
    }
}
