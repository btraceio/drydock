package app.drydock.domain;

import app.drydock.review.SessionReviewScopes;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The subset of workspace UI state persisted across application launches:
 * sidebar/repo state, cosmetic preferences, and the open session tabs.
 *
 * <p>{@code openSessionIds} records the tab-strip order of sessions that
 * were open when the app last shut down, and {@code selectedSessionId}
 * records which of those tabs was active. These are cosmetic UI fields:
 * a malformed entry must not make the whole state file look corrupt.</p>
 *
 * <p>{@code reviewScopeChoices} records, per session, which of the Review
 * sub-tab's scope chips (local changes vs. the pull request) was last
 * selected -- also cosmetic, so a malformed entry is skipped rather than
 * failing the decode (see {@code ApplicationStateCodec}).</p>
 */
public record WorkspaceUiState(
        Optional<RepositoryId> selectedRepositoryId,
        double sidebarWidth,
        Set<RepositoryId> expandedRepositoryIds,
        UiTheme theme,
        double uiFontSize,
        double terminalFontSize,
        List<ManagedSessionId> openSessionIds,
        Optional<ManagedSessionId> selectedSessionId,
        Map<ManagedSessionId, SessionReviewScopes.Choice> reviewScopeChoices,
        boolean reviewKeyHintsHidden
) {
    /** Design default 288px (handoff README section 2), clamped 220-520 at the SplitPane. */
    public static final double DEFAULT_SIDEBAR_WIDTH = 288.0;

    /**
     * The app.css {@code .root} base size, and therefore the divisor every
     * interface-scale factor is computed against (see {@code UiFontScale}).
     */
    public static final double DEFAULT_UI_FONT_SIZE = 13.0;

    /** Matches the interface default so terminals start visually consistent with the UI. */
    public static final double DEFAULT_TERMINAL_FONT_SIZE = 13.0;

    public WorkspaceUiState {
        Objects.requireNonNull(selectedRepositoryId, "selectedRepositoryId");
        expandedRepositoryIds = Set.copyOf(Objects.requireNonNull(expandedRepositoryIds, "expandedRepositoryIds"));
        Objects.requireNonNull(theme, "theme");
        openSessionIds = List.copyOf(Objects.requireNonNull(openSessionIds, "openSessionIds"));
        Objects.requireNonNull(selectedSessionId, "selectedSessionId");
        reviewScopeChoices = Map.copyOf(Objects.requireNonNull(reviewScopeChoices, "reviewScopeChoices"));
    }

    /** A state with the review key hints shown, which is what every state written before the field has. */
    public WorkspaceUiState(Optional<RepositoryId> selectedRepositoryId, double sidebarWidth,
                            Set<RepositoryId> expandedRepositoryIds, UiTheme theme, double uiFontSize,
                            double terminalFontSize, List<ManagedSessionId> openSessionIds,
                            Optional<ManagedSessionId> selectedSessionId,
                            Map<ManagedSessionId, SessionReviewScopes.Choice> reviewScopeChoices) {
        this(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme, uiFontSize, terminalFontSize,
                openSessionIds, selectedSessionId, reviewScopeChoices, false);
    }

    public static WorkspaceUiState empty() {
        return new WorkspaceUiState(Optional.empty(), DEFAULT_SIDEBAR_WIDTH, Set.of(), UiTheme.DARK,
                DEFAULT_UI_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE, List.of(), Optional.empty(), Map.of(), false);
    }

    public WorkspaceUiState withSelectedRepositoryId(Optional<RepositoryId> newSelectedRepositoryId) {
        return new WorkspaceUiState(newSelectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withSidebarWidth(double newSidebarWidth) {
        return new WorkspaceUiState(selectedRepositoryId, newSidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withExpandedRepositoryIds(Set<RepositoryId> newExpandedRepositoryIds) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, newExpandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withTheme(UiTheme newTheme) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, newTheme,
                uiFontSize, terminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withUiFontSize(double newUiFontSize) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                newUiFontSize, terminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withTerminalFontSize(double newTerminalFontSize) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, newTerminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withOpenSessionIds(List<ManagedSessionId> newOpenSessionIds) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, newOpenSessionIds, selectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withSelectedSessionId(Optional<ManagedSessionId> newSelectedSessionId) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, openSessionIds, newSelectedSessionId, reviewScopeChoices, reviewKeyHintsHidden);
    }

    public WorkspaceUiState withReviewScopeChoices(
            Map<ManagedSessionId, SessionReviewScopes.Choice> newReviewScopeChoices) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, openSessionIds, selectedSessionId, newReviewScopeChoices, reviewKeyHintsHidden);
    }

    /** Whether the reader hid the tour's key-hints strip; a global preference, not a per-session one. */
    public WorkspaceUiState withReviewKeyHintsHidden(boolean hidden) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize, openSessionIds, selectedSessionId, reviewScopeChoices, hidden);
    }
}
