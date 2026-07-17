package app.cpm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The subset of workspace UI state relevant to Milestone 4 (plan section
 * 10.3): which repository is selected, the sidebar width, and which
 * repository nodes are expanded.
 *
 * <p>Plan section 10.3 also lists workspace-state fields that belong to
 * later milestones (selected session, open tabs, right-pane width, last
 * selected file, file-preview visibility, Git-pane visibility). Those are
 * deliberately not represented here yet, per plan rule 27.2 ("do not
 * scaffold later milestones before the current milestone works") --
 * adding them later is a additive, backwards-compatible JSON schema
 * change (new object members), not a breaking one.</p>
 */
public record WorkspaceUiState(
        Optional<RepositoryId> selectedRepositoryId,
        double sidebarWidth,
        Set<RepositoryId> expandedRepositoryIds,
        UiTheme theme
) {
    /** Design default 288px (handoff README section 2), clamped 220-520 at the SplitPane. */
    public static final double DEFAULT_SIDEBAR_WIDTH = 288.0;

    public WorkspaceUiState {
        Objects.requireNonNull(selectedRepositoryId, "selectedRepositoryId");
        expandedRepositoryIds = Set.copyOf(Objects.requireNonNull(expandedRepositoryIds, "expandedRepositoryIds"));
        Objects.requireNonNull(theme, "theme");
    }

    public static WorkspaceUiState empty() {
        return new WorkspaceUiState(Optional.empty(), DEFAULT_SIDEBAR_WIDTH, Set.of(), UiTheme.DARK);
    }

    public WorkspaceUiState withSelectedRepositoryId(Optional<RepositoryId> newSelectedRepositoryId) {
        return new WorkspaceUiState(newSelectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme);
    }

    public WorkspaceUiState withSidebarWidth(double newSidebarWidth) {
        return new WorkspaceUiState(selectedRepositoryId, newSidebarWidth, expandedRepositoryIds, theme);
    }

    public WorkspaceUiState withExpandedRepositoryIds(Set<RepositoryId> newExpandedRepositoryIds) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, newExpandedRepositoryIds, theme);
    }

    public WorkspaceUiState withTheme(UiTheme newTheme) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, newTheme);
    }
}
