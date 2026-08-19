package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A session managed by this application (plan section 10.2).
 *
 * <p>Four distinct identifiers can be in play for a single running agent
 * process, and the plan is explicit that they must never be conflated:</p>
 *
 * <ul>
 *   <li>{@link #id()} -- the application-assigned {@link ManagedSessionId},
 *       stable for the lifetime of this metadata entry regardless of
 *       whether the agent CLI has ever actually run for it;</li>
 *   <li>{@link #agentSessionId()} -- the session ID the CLI itself
 *       assigns/reports, used with {@code --resume <id>};</li>
 *   <li>{@link #agentSessionName()} -- an explicit name assigned via
 *       {@code -n <name>}, used with {@code --resume <name>} when no session
 *       ID is known yet;</li>
 *   <li>the OS process ID of a currently-running agent process --
 *       deliberately not modeled here at all: it is transient runtime state
 *       owned by whatever process-supervision component launches the
 *       terminal, not persisted session metadata.</li>
 * </ul>
 *
 * <h2>Why the components are grouped</h2>
 *
 * <p>This record reached sixteen flat components, each with a {@code with*}
 * method that restated every one of them -- so adding a component meant a dozen
 * near-identical edits, and a missed one was caught by the compiler rather than
 * prevented by design. Three groups now carry invariants that used to live only
 * in prose:</p>
 *
 * <ul>
 *   <li>{@link PrLink} -- a PR number may only exist alongside {@code OPEN} or
 *       {@code MERGED}. The paired {@code withPr} existed precisely because
 *       these changed together; now the illegal pairing cannot be built.</li>
 *   <li>{@link AgentBinding} -- which CLI runs this session and what that CLI
 *       calls it. An id from one tool's store means nothing to another.</li>
 *   <li>{@link SessionWorkspace} -- the paths, plus whether drydock created the
 *       branch there, which the delete paths already read together.</li>
 * </ul>
 *
 * <p>The flat leaf accessors ({@link #prState()}, {@link #workingDirectory()}
 * and friends) are kept as delegates: over a hundred call sites read them, and
 * making every reader spell out the group would be churn for no invariant
 * gained. Grouping is for <em>construction and mutation</em>, which is where
 * the illegal states and the duplication actually were.</p>
 *
 * <p>Mutation goes through {@link #toBuilder()}. The {@code with*} methods stay
 * as one-line delegates because {@code withStatus} alone has more than forty
 * call sites, and rewriting those would be noise.</p>
 *
 * <p>{@link #forkedFrom()} names the session this one was forked from when the
 * work was handed to a different agent, and is empty for every session a human
 * started directly. It is never mutated after creation: that is the whole
 * lineage model, so chain depth ("the third harness on this work") is derived
 * by walking links rather than stored.</p>
 *
 * <p>{@link #namePinned()} records that a human explicitly confirmed a rename
 * of this session. The {@code session_rename} MCP tool refuses once it is set:
 * a name the human typed is theirs. Set only by an explicit confirm (Enter in
 * the inline editor, OK in the Rename dialog) -- never by a focus-loss commit,
 * which an agent can provoke by opening a tab.</p>
 *
 * <p>{@link #evalMode()} records that this session runs on the "eval" account:
 * the launched agent CLI is made to send an {@code x-target-account: eval}
 * request header so plugin/extension testing traffic is not charged against
 * ordinary usage. Set at creation by the human (a checkbox in the Start-session
 * modal), persisted, honored on resume, and inherited by a fork. Agent-driven
 * ({@code session_start}) sessions are never eval. Injection is implemented for Pi
 * (bridge extension); for Claude it is deferred (see the provider's own docs for
 * why no non-invasive path exists) and for Codex it is unsupported (built-in
 * provider cannot carry extra headers), so an eval session of those kinds is
 * marked and badged but its requests are not rerouted.</p>
 */
public record ManagedAgentSession(
        ManagedSessionId id,
        RepositoryId repositoryId,
        String displayName,
        AgentBinding binding,
        SessionWorkspace workspace,
        SessionStatus status,
        Instant createdAt,
        Instant lastOpenedAt,
        Optional<Integer> lastExitCode,
        PrLink pr,
        boolean namePinned,
        Optional<ManagedSessionId> forkedFrom,
        boolean evalMode
) {

    public ManagedAgentSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastOpenedAt, "lastOpenedAt");
        Objects.requireNonNull(lastExitCode, "lastExitCode");
        Objects.requireNonNull(pr, "pr");
        Objects.requireNonNull(forkedFrom, "forkedFrom");

        if (displayName.isBlank()) {
            throw new IllegalArgumentException("ManagedAgentSession displayName must not be blank");
        }
    }

    /**
     * As the canonical constructor with {@code evalMode = false}; for callers
     * (and tests) that do not participate in eval mode.
     */
    public ManagedAgentSession(ManagedSessionId id, RepositoryId repositoryId, String displayName,
                               AgentBinding binding, SessionWorkspace workspace, SessionStatus status,
                               Instant createdAt, Instant lastOpenedAt, Optional<Integer> lastExitCode,
                               PrLink pr, boolean namePinned, Optional<ManagedSessionId> forkedFrom) {
        this(id, repositoryId, displayName, binding, workspace, status, createdAt, lastOpenedAt, lastExitCode,
                pr, namePinned, forkedFrom, false);
    }

    // ---- leaf accessors, delegating into the groups --------------------------

    public AgentKind agentKind() {
        return binding.kind();
    }

    public Optional<String> agentSessionId() {
        return binding.sessionId();
    }

    public Optional<String> agentSessionName() {
        return binding.sessionName();
    }

    public Path workingDirectory() {
        return workspace.workingDirectory();
    }

    public Optional<Path> worktreeRoot() {
        return workspace.worktreeRoot();
    }

    public boolean branchCreatedHere() {
        return workspace.branchCreatedHere();
    }

    public PrState prState() {
        return pr.state();
    }

    public Optional<Integer> prNumber() {
        return pr.number();
    }

    // ---- mutation -------------------------------------------------------------

    public Builder toBuilder() {
        return new Builder(this);
    }

    public ManagedAgentSession withDisplayName(String newDisplayName) {
        return toBuilder().displayName(newDisplayName).build();
    }

    public ManagedAgentSession withAgentKind(AgentKind newAgentKind) {
        return toBuilder().binding(binding.withKind(newAgentKind)).build();
    }

    public ManagedAgentSession withAgentSessionId(Optional<String> newAgentSessionId) {
        return toBuilder().binding(binding.withSessionId(newAgentSessionId)).build();
    }

    public ManagedAgentSession withAgentSessionName(Optional<String> newAgentSessionName) {
        return toBuilder().binding(binding.withSessionName(newAgentSessionName)).build();
    }

    public ManagedAgentSession withWorkingDirectory(Path newWorkingDirectory) {
        return toBuilder().workspace(workspace.withWorkingDirectory(newWorkingDirectory)).build();
    }

    public ManagedAgentSession withWorktreeRoot(Optional<Path> newWorktreeRoot) {
        return toBuilder().workspace(workspace.withWorktreeRoot(newWorktreeRoot)).build();
    }

    public ManagedAgentSession withStatus(SessionStatus newStatus) {
        return toBuilder().status(newStatus).build();
    }

    public ManagedAgentSession withLastOpenedAt(Instant newLastOpenedAt) {
        return toBuilder().lastOpenedAt(newLastOpenedAt).build();
    }

    public ManagedAgentSession withLastExitCode(Optional<Integer> newLastExitCode) {
        return toBuilder().lastExitCode(newLastExitCode).build();
    }

    /** PR state and number always change together, which {@link PrLink} now enforces. */
    public ManagedAgentSession withPr(PrState newPrState, Optional<Integer> newPrNumber) {
        return toBuilder().pr(PrLink.of(newPrState, newPrNumber)).build();
    }

    public ManagedAgentSession withNamePinned(boolean newNamePinned) {
        return toBuilder().namePinned(newNamePinned).build();
    }

    /** Set once, when a fork is created; never cleared. */
    public ManagedAgentSession withForkedFrom(Optional<ManagedSessionId> newForkedFrom) {
        return toBuilder().forkedFrom(newForkedFrom).build();
    }

    public ManagedAgentSession withEvalMode(boolean newEvalMode) {
        return toBuilder().evalMode(newEvalMode).build();
    }

    /**
     * The single construction path. Adding a component touches this class in
     * one place instead of once per {@code with*} method, which is the whole
     * reason it exists.
     */
    public static final class Builder {

        private ManagedSessionId id;
        private RepositoryId repositoryId;
        private String displayName;
        private AgentBinding binding;
        private SessionWorkspace workspace;
        private SessionStatus status;
        private Instant createdAt;
        private Instant lastOpenedAt;
        private Optional<Integer> lastExitCode;
        private PrLink pr;
        private boolean namePinned;
        private Optional<ManagedSessionId> forkedFrom;
        private boolean evalMode;

        private Builder(ManagedAgentSession from) {
            this.id = from.id;
            this.repositoryId = from.repositoryId;
            this.displayName = from.displayName;
            this.binding = from.binding;
            this.workspace = from.workspace;
            this.status = from.status;
            this.createdAt = from.createdAt;
            this.lastOpenedAt = from.lastOpenedAt;
            this.lastExitCode = from.lastExitCode;
            this.pr = from.pr;
            this.namePinned = from.namePinned;
            this.forkedFrom = from.forkedFrom;
            this.evalMode = from.evalMode;
        }

        /**
         * A brand-new session: everything one cannot exist without, with the
         * rest at the values a session that has never run holds. Nothing is
         * defaulted that would be a lie -- {@code now} is the caller's clock,
         * not this class's.
         */
        public Builder(ManagedSessionId id, RepositoryId repositoryId, String displayName,
                       AgentBinding binding, SessionWorkspace workspace, Instant now) {
            this.id = id;
            this.repositoryId = repositoryId;
            this.displayName = displayName;
            this.binding = binding;
            this.workspace = workspace;
            this.status = SessionStatus.INACTIVE;
            this.createdAt = now;
            this.lastOpenedAt = now;
            this.lastExitCode = Optional.empty();
            this.pr = PrLink.none();
            this.namePinned = false;
            this.forkedFrom = Optional.empty();
            this.evalMode = false;
        }

        public Builder id(ManagedSessionId value) {
            this.id = value;
            return this;
        }

        public Builder repositoryId(RepositoryId value) {
            this.repositoryId = value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder binding(AgentBinding value) {
            this.binding = value;
            return this;
        }

        public Builder workspace(SessionWorkspace value) {
            this.workspace = value;
            return this;
        }

        public Builder status(SessionStatus value) {
            this.status = value;
            return this;
        }

        public Builder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public Builder lastOpenedAt(Instant value) {
            this.lastOpenedAt = value;
            return this;
        }

        public Builder lastExitCode(Optional<Integer> value) {
            this.lastExitCode = value;
            return this;
        }

        public Builder pr(PrLink value) {
            this.pr = value;
            return this;
        }

        public Builder namePinned(boolean value) {
            this.namePinned = value;
            return this;
        }

        public Builder forkedFrom(Optional<ManagedSessionId> value) {
            this.forkedFrom = value;
            return this;
        }

        public Builder evalMode(boolean value) {
            this.evalMode = value;
            return this;
        }

        public ManagedAgentSession build() {
            return new ManagedAgentSession(id, repositoryId, displayName, binding, workspace, status,
                    createdAt, lastOpenedAt, lastExitCode, pr, namePinned, forkedFrom, evalMode);
        }
    }
}
