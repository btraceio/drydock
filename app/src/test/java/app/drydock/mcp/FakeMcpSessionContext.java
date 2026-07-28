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
import java.util.function.UnaryOperator;

/** Hand-written fake for {@link McpSessionContext}; the build has no mocking library. */
final class FakeMcpSessionContext implements McpSessionContext {

    Optional<Path> repositoryRoot = Optional.empty();
    Optional<Path> worktreePath = Optional.empty();
    Optional<String> baseBranch = Optional.of("main");
    final List<ReviewAnnotation> annotations = new ArrayList<>();

    /**
     * Which review scopes each caller may address. Findings are keyed by
     * scope handle now, so the fake models the registry's authorization
     * boundary rather than a session-owns-annotation relation that no longer
     * exists; a caller with no entry here sees nothing.
     */
    final Map<ManagedSessionId, Set<String>> addressableScopes = new HashMap<>();
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

    /** As a real session's status does; cleared to model a claude that has exited. */
    boolean sessionRunning = true;

    /**
     * Run inside {@link #mutateAnnotation}, before the transform sees the
     * stored value -- the test's way of landing the human's concurrent write
     * inside the router's read-modify-write window.
     */
    Runnable beforeMutate = () -> { };

    @Override
    public Optional<Path> repositoryRoot(ManagedSessionId caller) {
        return repositoryRoot;
    }

    @Override
    public boolean sessionRunning(ManagedSessionId caller) {
        return sessionRunning;
    }

    @Override
    public Optional<Path> worktreePath(ManagedSessionId caller) {
        return worktreePath;
    }

    @Override
    public Optional<String> baseBranch(ManagedSessionId caller) {
        return baseBranch;
    }

    /**
     * Honors the interface contract: the findings of every scope this caller
     * may address. An unscoped fake would let a cross-scope test pass for the
     * wrong reason -- the router would have to filter again, putting the
     * authorization boundary back into the adapter layer.
     */
    @Override
    public List<ReviewAnnotation> annotations(ManagedSessionId caller) {
        Set<String> allowed = addressableScopes.getOrDefault(caller, Set.of());
        return annotations.stream()
                .filter(annotation -> allowed.contains(annotation.scopeId()))
                .toList();
    }

    // ---- review scopes ------------------------------------------------------

    /** Review scopes this fake knows about, by handle. */
    final Map<String, app.drydock.review.ReviewScope> reviewScopes = new HashMap<>();

    /** The diff {@link #reviewDiff} returns. */
    app.drydock.git.UnifiedDiff reviewDiff = new app.drydock.git.UnifiedDiff(List.of());

    /** The last intent grouping {@link #putIntents} received. */
    final Map<String, List<app.drydock.review.ReviewIntent>> intents = new HashMap<>();

    final List<app.drydock.review.ReviewVerdict> verdicts = new ArrayList<>();
    final Set<String> submitted = new LinkedHashSet<>();

    @Override
    public Optional<app.drydock.review.ReviewScope> reviewScope(String scopeId, ManagedSessionId caller) {
        if (!addressableScopes.getOrDefault(caller, Set.of()).contains(scopeId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(reviewScopes.get(scopeId));
    }

    @Override
    public app.drydock.git.UnifiedDiff reviewDiff(app.drydock.review.ReviewScope scope) {
        return reviewDiff;
    }

    @Override
    public void putIntents(String scopeId, List<app.drydock.review.ReviewIntent> newIntents) {
        intents.put(scopeId, List.copyOf(newIntents));
    }

    @Override
    public void upsertFindings(List<ReviewAnnotation> findings) {
        for (ReviewAnnotation finding : findings) {
            annotations.removeIf(existing -> existing.key().equals(finding.key()));
            annotations.add(finding);
        }
    }

    @Override
    public List<ReviewAnnotation> findingsOf(String scopeId) {
        return annotations.stream().filter(finding -> finding.scopeId().equals(scopeId)).toList();
    }

    @Override
    public List<app.drydock.review.ReviewVerdict> verdictsOf(String scopeId) {
        return verdicts.stream().filter(verdict -> verdict.scopeId().equals(scopeId)).toList();
    }

    @Override
    public boolean reviewSubmitted(String scopeId) {
        return submitted.contains(scopeId);
    }

    @Override
    public List<PromptStep> promptHistory(app.drydock.review.ReviewScope scope) {
        return List.of();
    }

    /** Grants {@code caller} access to {@code scopeId}, as the scope registry does. */
    void grant(ManagedSessionId caller, String scopeId) {
        addressableScopes.computeIfAbsent(caller, key -> new LinkedHashSet<>()).add(scopeId);
    }

    /** Mirrors {@code AnnotationStore.mutate}: the transform sees the STORED value, never the caller's. */
    @Override
    public Optional<ReviewAnnotation> mutateAnnotation(ReviewAnnotation.Key key,
                                                       UnaryOperator<ReviewAnnotation> transform) {
        beforeMutate.run();
        for (int i = 0; i < annotations.size(); i++) {
            if (annotations.get(i).key().equals(key)) {
                ReviewAnnotation updated = transform.apply(annotations.get(i));
                annotations.set(i, updated);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    /** Unconditional replace, for tests setting up a starting value. */
    void store(ReviewAnnotation annotation) {
        annotations.replaceAll(existing -> existing.key().equals(annotation.key()) ? annotation : existing);
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
