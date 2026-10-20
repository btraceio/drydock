package app.drydock.domain;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.SessionReviewScopes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The font-size fields default to the design's 13px base and copy
 * independently of every other field.
 */
class WorkspaceUiStateTest {

    @Test
    void emptyUsesTheThirteenPixelDesignBase() {
        WorkspaceUiState empty = WorkspaceUiState.empty();

        assertEquals(13.0, empty.uiFontSize());
        assertEquals(13.0, empty.terminalFontSize());
    }

    @Test
    void emptyHasNoOpenSessionsOrSelectedSession() {
        WorkspaceUiState empty = WorkspaceUiState.empty();

        assertEquals(List.of(), empty.openSessionIds());
        assertEquals(Optional.empty(), empty.selectedSessionId());
    }

    @Test
    void withUiFontSizeLeavesEveryOtherFieldAlone() {
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withSidebarWidth(300)
                .withTheme(UiTheme.LIGHT)
                .withUiFontSize(15.5);

        assertEquals(15.5, updated.uiFontSize());
        assertEquals(300, updated.sidebarWidth());
        assertEquals(UiTheme.LIGHT, updated.theme());
        assertEquals(13.0, updated.terminalFontSize());
        assertEquals(List.of(), updated.openSessionIds());
    }

    @Test
    void withTerminalFontSizeLeavesTheInterfaceSizeAlone() {
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withUiFontSize(16)
                .withTerminalFontSize(11);

        assertEquals(16, updated.uiFontSize());
        assertEquals(11, updated.terminalFontSize());
    }

    @Test
    void withOpenSessionIdsReplacesTheListAndLeavesSelectionAlone() {
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withSelectedSessionId(Optional.of(first))
                .withOpenSessionIds(List.of(first, second));

        assertEquals(List.of(first, second), updated.openSessionIds());
        assertEquals(Optional.of(first), updated.selectedSessionId());
    }

    @Test
    void withSelectedSessionIdLeavesOpenSessionsAlone() {
        ManagedSessionId id = ManagedSessionId.newId();
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withOpenSessionIds(List.of(id))
                .withSelectedSessionId(Optional.of(id));

        assertEquals(List.of(id), updated.openSessionIds());
        assertEquals(Optional.of(id), updated.selectedSessionId());
    }

    @Test
    void reviewScopeChoicesDefaultToEmpty() {
        assertTrue(WorkspaceUiState.empty().reviewScopeChoices().isEmpty());
    }

    @Test
    void reviewScopeChoicesAreCopiedNotAliased() {
        Map<ManagedSessionId, SessionReviewScopes.Choice> mutable = new HashMap<>();
        ManagedSessionId session = ManagedSessionId.newId();
        mutable.put(session, SessionReviewScopes.Choice.PULL_REQUEST);

        WorkspaceUiState state = WorkspaceUiState.empty().withReviewScopeChoices(mutable);
        mutable.clear();

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, state.reviewScopeChoices().get(session));
    }
}
