package app.cpm.domain;

import java.util.List;
import java.util.Objects;

/**
 * The full persisted application state (plan section 17). Immutable: every
 * mutation (add/remove repository, change UI state) produces a new {@code
 * ApplicationState} via {@link #withRepositories} / {@link #withUi}.
 */
public record ApplicationState(
        List<Repository> repositories,
        WorkspaceUiState ui
) {

    public ApplicationState {
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        Objects.requireNonNull(ui, "ui");
    }

    public static ApplicationState empty() {
        return new ApplicationState(List.of(), WorkspaceUiState.empty());
    }

    public ApplicationState withRepositories(List<Repository> newRepositories) {
        return new ApplicationState(newRepositories, ui);
    }

    public ApplicationState withUi(WorkspaceUiState newUi) {
        return new ApplicationState(repositories, newUi);
    }
}
