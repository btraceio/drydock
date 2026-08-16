package app.drydock.domain;

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
 * written one simply has no entry.</p>
 *
 * <p>{@code sessions} holds only the persisted {@link ManagedAgentSession}
 * metadata (plan section 10.2). It deliberately does not yet include any
 * open-tab/UI-open-state tracking -- that belongs to {@link WorkspaceUiState}
 * in a later milestone step, once the terminal-tab UI itself exists (plan
 * rule 27.2: do not scaffold later milestones before the current one
 * works).</p>
 */
public record ApplicationState(
        List<Repository> repositories,
        List<ManagedAgentSession> sessions,
        WorkspaceUiState ui,
        List<HandoffBrief> handoffBriefs
) {

    public ApplicationState {
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        Objects.requireNonNull(ui, "ui");
        handoffBriefs = List.copyOf(Objects.requireNonNull(handoffBriefs, "handoffBriefs"));
    }

    public static ApplicationState empty() {
        return new ApplicationState(List.of(), List.of(), WorkspaceUiState.empty(), List.of());
    }

    public ApplicationState withRepositories(List<Repository> newRepositories) {
        return new ApplicationState(newRepositories, sessions, ui, handoffBriefs);
    }

    public ApplicationState withSessions(List<ManagedAgentSession> newSessions) {
        return new ApplicationState(repositories, newSessions, ui, handoffBriefs);
    }

    public ApplicationState withUi(WorkspaceUiState newUi) {
        return new ApplicationState(repositories, sessions, newUi, handoffBriefs);
    }

    public ApplicationState withHandoffBriefs(List<HandoffBrief> newHandoffBriefs) {
        return new ApplicationState(repositories, sessions, ui, newHandoffBriefs);
    }
}
