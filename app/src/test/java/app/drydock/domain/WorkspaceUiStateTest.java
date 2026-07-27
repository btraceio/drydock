package app.drydock.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void withUiFontSizeLeavesEveryOtherFieldAlone() {
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withSidebarWidth(300)
                .withTheme(UiTheme.LIGHT)
                .withUiFontSize(15.5);

        assertEquals(15.5, updated.uiFontSize());
        assertEquals(300, updated.sidebarWidth());
        assertEquals(UiTheme.LIGHT, updated.theme());
        assertEquals(13.0, updated.terminalFontSize());
    }

    @Test
    void withTerminalFontSizeLeavesTheInterfaceSizeAlone() {
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withUiFontSize(16)
                .withTerminalFontSize(11);

        assertEquals(16, updated.uiFontSize());
        assertEquals(11, updated.terminalFontSize());
    }
}
