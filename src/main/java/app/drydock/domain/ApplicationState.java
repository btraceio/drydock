package app.drydock.domain;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.Escalation;

import java.util.List;
import java.util.Objects;

/**
 * The full persisted application state (plan section 17). Immutable: every
 * mutation (add/remove repository, change UI state, add/remove a managed
 * session) produces a new {@code ApplicationState} via {@link
 * #withRepositories} / {@link #withUi} / {@link #withSessions}.
 *
 * <p>{@code handoffBriefs} is keyed by session rather than held on {@link
 * ManagedAgentSession} on purpose: that record is already sixteen fields
 * with a {@code with*} accessor apiece, and a brief is a document with its
 * own write cadence, not part of session identity. A session that has never
 * written one simply has no entry. {@code pairings} and {@code escalations}
 * sit beside it for the same reason.</p>
 *
 * <p>{@code sessions} holds only the persisted {@link ManagedAgentSession}
 * metadata (plan section 10.2). Open-tab order and the active tab are kept
 * in {@link WorkspaceUiState} and restored at startup.</p>
 */
public record ApplicationState(
        List<Repository> repositories,
        List<ManagedAgentSession> sessions,
        List<Workflow> workflows,
        WorkspaceUiState ui,
        List<HandoffBrief> handoffBriefs,
        List<BabysitPairing> pairings,
        List<Escalation> escalations
) {

    public ApplicationState {
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        workflows = List.copyOf(Objects.requireNonNull(workflows, "workflows"));
        Objects.requireNonNull(ui, "ui");
        handoffBriefs = List.copyOf(Objects.requireNonNull(handoffBriefs, "handoffBriefs"));
        pairings = List.copyOf(Objects.requireNonNull(pairings, "pairings"));
        escalations = List.copyOf(Objects.requireNonNull(escalations, "escalations"));
    }

    /**
     * A state with no pairings and no escalations. Rebuilding an existing
     * state through this constructor silently drops both; use the {@code
     * with*} methods instead.
     */
    public ApplicationState(
            List<Repository> repositories,
            List<ManagedAgentSession> sessions,
            List<Workflow> workflows,
            WorkspaceUiState ui,
            List<HandoffBrief> handoffBriefs) {
        this(repositories, sessions, workflows, ui, handoffBriefs, List.of(), List.of());
    }

    public static ApplicationState empty() {
        return new ApplicationState(List.of(), List.of(), List.of(), WorkspaceUiState.empty(), List.of(),
                List.of(), List.of());
    }

    public ApplicationState withRepositories(List<Repository> newRepositories) {
        return new ApplicationState(newRepositories, sessions, workflows, ui, handoffBriefs, pairings, escalations);
    }

    public ApplicationState withSessions(List<ManagedAgentSession> newSessions) {
        return new ApplicationState(repositories, newSessions, workflows, ui, handoffBriefs, pairings, escalations);
    }

    public ApplicationState withWorkflows(List<Workflow> newWorkflows) {
        return new ApplicationState(repositories, sessions, newWorkflows, ui, handoffBriefs, pairings, escalations);
    }

    public ApplicationState withUi(WorkspaceUiState newUi) {
        return new ApplicationState(repositories, sessions, workflows, newUi, handoffBriefs, pairings, escalations);
    }

    public ApplicationState withHandoffBriefs(List<HandoffBrief> newHandoffBriefs) {
        return new ApplicationState(repositories, sessions, workflows, ui, newHandoffBriefs, pairings, escalations);
    }

    public ApplicationState withPairings(List<BabysitPairing> newPairings) {
        return new ApplicationState(repositories, sessions, workflows, ui, handoffBriefs, newPairings, escalations);
    }

    public ApplicationState withEscalations(List<Escalation> newEscalations) {
        return new ApplicationState(repositories, sessions, workflows, ui, handoffBriefs, pairings, newEscalations);
    }
}
