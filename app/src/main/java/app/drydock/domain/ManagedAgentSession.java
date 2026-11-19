package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 *
 *   <li>Beyond the primary {@link PrLink} a session may carry secondary PR
 *       links ({@link #additionalPrs()}): GitHub allows several open pull
 *       requests from one head branch (different bases), a session checked
 *       out from one PR can open its own PR from the same worktree, and a
 *       branch can gain PRs opened outside drydock. The <em>first</em> PR a
 *       session learns of stays primary -- it is the one the pr-&lt;n&gt;
 *       branch naming, review scope and finish flow keep operating on -- and
 *       every later PR appends as a secondary link. {@link #withObservedPr}
 *       is the single reconcile point: it updates whichever link carries the
 *       observed number, promotes an observed PR to primary only while no
 *       primary is known, and appends otherwise. At most
 *       {@link #MAX_ADDITIONAL_PR_LINKS} secondary links are retained: when
 *       full, the oldest merged secondary is evicted for a new observation,
 *       and a full list with no merged link is left unchanged. A secondary
 *       link always carries a number: an unnumbered PR is identifiable, an
 *       unnumbered secondary link is not.</li>
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
        List<PrLink> additionalPrs,
        boolean namePinned,
        Optional<ManagedSessionId> forkedFrom,
        boolean evalMode,
        Optional<WorkflowId> workflowId,
        boolean startedWithTask,
        Optional<ManagedSessionId> taskParent,
        Optional<TaskRelation> taskRelation
) {

    /** Maximum number of secondary pull-request links retained for one session. */
    public static final int MAX_ADDITIONAL_PR_LINKS = 16;

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
        Objects.requireNonNull(additionalPrs, "additionalPrs");
        Objects.requireNonNull(forkedFrom, "forkedFrom");
        Objects.requireNonNull(workflowId, "workflowId");

        if (displayName.isBlank()) {
            throw new IllegalArgumentException("ManagedAgentSession displayName must not be blank");
        }

        // The first PR a session learns of is the primary one; a secondary
        // link without a primary behind it would invert that ordering, and a
        // duplicated number would make "which link does this observation
        // belong to" ambiguous. Checked here so neither state can be built.
        List<PrLink> links = List.copyOf(additionalPrs);
        if (links.size() > MAX_ADDITIONAL_PR_LINKS) {
            throw new IllegalArgumentException("ManagedAgentSession has " + links.size()
                    + " secondary PR links; maximum is " + MAX_ADDITIONAL_PR_LINKS);
        }
        if (!links.isEmpty() && pr.number().isEmpty()) {
            throw new IllegalArgumentException(
                    "ManagedAgentSession carries " + links.size()
                            + " secondary PR links but no primary PR");
        }
        for (int i = 0; i < links.size(); i++) {
            PrLink link = links.get(i);
            if (link.number().isEmpty()) {
                throw new IllegalArgumentException(
                        "Secondary PR link at index " + i + " has no number");
            }
            if (pr.number().equals(link.number())) {
                throw new IllegalArgumentException(
                        "Secondary PR link duplicates the primary PR number "
                                + link.number().get());
            }
            for (int j = 0; j < i; j++) {
                if (links.get(j).number().equals(link.number())) {
                    throw new IllegalArgumentException(
                            "Duplicate secondary PR link for number " + link.number().get());
                }
            }
        }
        additionalPrs = links;

        // A task relation without the parent it relates to (or the reverse)
        // is a half-written edge: the sidebar could not render it and the
        // finish flow could not resolve a base branch for it.
        if (taskParent.isEmpty() != taskRelation.isEmpty()) {
            throw new IllegalArgumentException(
                    "ManagedAgentSession task relation must be present with its parent, or absent with it");
        }
    }
    /**
     * As the canonical constructor with {@code evalMode = false} and
     * {@code workflowId = Optional.empty()}; for callers (and tests) that
     * do not participate in eval mode or workflow grouping.
     */
    public ManagedAgentSession(ManagedSessionId id, RepositoryId repositoryId, String displayName,
                               AgentBinding binding, SessionWorkspace workspace, SessionStatus status,
                               Instant createdAt, Instant lastOpenedAt, Optional<Integer> lastExitCode,
                               PrLink pr, boolean namePinned, Optional<ManagedSessionId> forkedFrom) {
        this(id, repositoryId, displayName, binding, workspace, status, createdAt, lastOpenedAt, lastExitCode,
                pr, List.of(), namePinned, forkedFrom, false, Optional.empty(), false,
                Optional.empty(), Optional.empty());
    }

    /**
     * As the canonical constructor with {@code workflowId = Optional.empty()}
     * and {@code startedWithTask = false}; for callers (and tests) that do
     * not participate in workflow grouping or the resume prompt.
     */
    public ManagedAgentSession(ManagedSessionId id, RepositoryId repositoryId, String displayName,
                               AgentBinding binding, SessionWorkspace workspace, SessionStatus status,
                               Instant createdAt, Instant lastOpenedAt, Optional<Integer> lastExitCode,
                               PrLink pr, boolean namePinned, Optional<ManagedSessionId> forkedFrom,
                               boolean evalMode) {
        this(id, repositoryId, displayName, binding, workspace, status, createdAt, lastOpenedAt, lastExitCode,
                pr, List.of(), namePinned, forkedFrom, evalMode, Optional.empty(), false,
                Optional.empty(), Optional.empty());
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

    /** Secondary PR links, primary first not included; see the class doc for the ordering rule. */
    public List<PrLink> additionalPrs() {
        return additionalPrs;
    }

    /** Every linked PR, primary first. */
    public List<PrLink> prLinks() {
        if (additionalPrs.isEmpty()) {
            return pr.number().isPresent() ? List.of(pr) : List.of();
        }
        List<PrLink> all = new ArrayList<>(1 + additionalPrs.size());
        all.add(pr);
        all.addAll(additionalPrs);
        return List.copyOf(all);
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

    /**
     * Folds one PR observation (a state and the number it was observed for)
     * into the link set: updates the link that carries {@code number}, takes
     * the primary slot only while no primary number is known, and appends a
     * secondary link otherwise. At the {@link #MAX_ADDITIONAL_PR_LINKS} limit,
     * a novel observation evicts the oldest {@link PrState#MERGED} secondary;
     * if none is merged, it is not recorded. Returns {@code this} when the
     * observation changes nothing, so callers can detect "nothing to reconcile"
     * by identity and skip the persist/UI refresh.
     */
    public ManagedAgentSession withObservedPr(PrState observed, int number) {
        Optional<Integer> target = Optional.of(number);
        if (pr.number().equals(target)) {
            return pr.state() == observed ? this : withPr(observed, target);
        }
        for (int i = 0; i < additionalPrs.size(); i++) {
            PrLink link = additionalPrs.get(i);
            if (!link.number().equals(target)) {
                continue;
            }
            if (link.state() == observed) {
                return this;
            }
            List<PrLink> updated = new ArrayList<>(additionalPrs);
            updated.set(i, PrLink.of(observed, target));
            return toBuilder().additionalPrs(updated).build();
        }
        if (pr.number().isEmpty()) {
            // First PR this session is observed with: it becomes primary.
            return withPr(observed, target);
        }
        List<PrLink> appended = new ArrayList<>(additionalPrs);
        if (appended.size() >= MAX_ADDITIONAL_PR_LINKS) {
            int oldestMerged = -1;
            for (int i = 0; i < appended.size(); i++) {
                if (appended.get(i).state() == PrState.MERGED) {
                    oldestMerged = i;
                    break;
                }
            }
            if (oldestMerged < 0) {
                return this;
            }
            appended.remove(oldestMerged);
        }
        appended.add(PrLink.of(observed, target));
        return toBuilder().additionalPrs(appended).build();
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
     * Sets which workflow this session belongs to. Not set-once: a session can
     * be moved between workflows or unaffiliated. Moving it does not move its
     * per-session brief -- that stays with the session.
     */
    public ManagedAgentSession withWorkflowId(Optional<WorkflowId> newWorkflowId) {
        return toBuilder().workflowId(newWorkflowId).build();
    }

    /**
     * Records whether drydock injected a start prompt into this session's
     * harness when it was created (a human-typed task, a review instruction,
     * an MCP {@code session_start} prompt, a successor seed). Only such a
     * session gets the fixed resume prompt typed into a freshly spawned
     * harness on an explicit resume: a harness that never received a task
     * has nothing to continue.
     */
    public ManagedAgentSession withStartedWithTask(boolean newStartedWithTask) {
        return toBuilder().startedWithTask(newStartedWithTask).build();
    }

    /**
     * Sets this session's task relation to the session it was started from
     * (the "New task…" dialog on a session row). Both halves travel
     * together -- see the canonical-constructor invariant.
     */
    public ManagedAgentSession withTaskRelation(Optional<ManagedSessionId> newTaskParent,
                                                Optional<TaskRelation> newTaskRelation) {
        return toBuilder().taskParent(newTaskParent).taskRelation(newTaskRelation).build();
    }

    /** The session this task was started from, when it has one. */
    public Optional<ManagedSessionId> taskParent() {
        return taskParent;
    }

    /** How this task relates to its {@link #taskParent()}; present exactly when the parent is. */
    public Optional<TaskRelation> taskRelation() {
        return taskRelation;
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
        private List<PrLink> additionalPrs;
        private boolean namePinned;
        private Optional<ManagedSessionId> forkedFrom;
        private boolean evalMode;
        private Optional<WorkflowId> workflowId;
        private boolean startedWithTask;
        private Optional<ManagedSessionId> taskParent = Optional.empty();
        private Optional<TaskRelation> taskRelation = Optional.empty();

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
            this.additionalPrs = from.additionalPrs;
            this.namePinned = from.namePinned;
            this.forkedFrom = from.forkedFrom;
            this.evalMode = from.evalMode;
            this.workflowId = from.workflowId;
            this.startedWithTask = from.startedWithTask;
            this.taskParent = from.taskParent;
            this.taskRelation = from.taskRelation;
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
            this.additionalPrs = List.of();
            this.namePinned = false;
            this.forkedFrom = Optional.empty();
            this.evalMode = false;
            this.workflowId = Optional.empty();
            this.startedWithTask = false;
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

        public Builder additionalPrs(List<PrLink> value) {
            this.additionalPrs = List.copyOf(value);
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

        public Builder workflowId(Optional<WorkflowId> value) {
            this.workflowId = value;
            return this;
        }

        public Builder startedWithTask(boolean value) {
            this.startedWithTask = value;
            return this;
        }

        public Builder taskParent(Optional<ManagedSessionId> value) {
            this.taskParent = Objects.requireNonNull(value);
            return this;
        }

        public Builder taskRelation(Optional<TaskRelation> value) {
            this.taskRelation = Objects.requireNonNull(value);
            return this;
        }

        public ManagedAgentSession build() {
            return new ManagedAgentSession(id, repositoryId, displayName, binding, workspace, status,
                    createdAt, lastOpenedAt, lastExitCode, pr, additionalPrs, namePinned, forkedFrom,
                    evalMode, workflowId, startedWithTask, taskParent, taskRelation);
        }
    }
}
