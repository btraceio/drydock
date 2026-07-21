package app.drydock.domain;

import java.util.List;
import java.util.Objects;

/**
 * The full persisted application state (plan section 17). Immutable: every
 * mutation (add/remove repository, change UI state, add/remove a managed
 * session) produces a new {@code ApplicationState} via {@link
 * #withRepositories} / {@link #withUi} / {@link #withSessions}.
 *
 * <p>{@code sessions} holds only the persisted {@link ManagedClaudeSession}
 * metadata (plan section 10.2). It deliberately does not yet include any
 * open-tab/UI-open-state tracking -- that belongs to {@link WorkspaceUiState}
 * in a later milestone step, once the terminal-tab UI itself exists (plan
 * rule 27.2: do not scaffold later milestones before the current one
 * works).</p>
 */
public record ApplicationState(
        List<Repository> repositories,
        List<ManagedClaudeSession> sessions,
        WorkspaceUiState ui
) {

    public ApplicationState {
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        Objects.requireNonNull(ui, "ui");
    }

    public static ApplicationState empty() {
        return new ApplicationState(List.of(), List.of(), WorkspaceUiState.empty());
    }

    public ApplicationState withRepositories(List<Repository> newRepositories) {
        return new ApplicationState(newRepositories, sessions, ui);
    }

    public ApplicationState withSessions(List<ManagedClaudeSession> newSessions) {
        return new ApplicationState(repositories, newSessions, ui);
    }

    public ApplicationState withUi(WorkspaceUiState newUi) {
        return new ApplicationState(repositories, sessions, newUi);
    }
}
