package app.drydock.ui;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.PromptSafety;

import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialog is gated so it cannot produce a brief the store would reject:
 * the required slots really are required, and the same per-slot cap the MCP
 * tool enforces applies here too.
 */
class HandoffEditDialogTest extends ApplicationTest {

    @Override
    public void start(Stage stage) {
        // The dialog owns its own window; nothing to mount.
    }

    private static HandoffBrief existing() {
        return new HandoffBrief(ManagedSessionId.newId(), "Ship the fork gesture", "Wire the banner",
                Optional.empty(), Optional.empty(), Optional.of("Rejected an API proxy"), Optional.empty(),
                Instant.parse("2026-08-12T10:15:30Z"), Optional.of("abc1234"), HandoffBrief.Author.AGENT);
    }

    private static boolean okDisabled(HandoffEditDialog dialog) {
        return dialog.getDialogPane().lookupButton(ButtonType.OK).isDisabled();
    }

    @Test
    void anEmptyBriefCannotBeSaved() {
        interact(() -> {
            HandoffEditDialog dialog = new HandoffEditDialog("Session 1", Optional.empty());

            assertTrue(okDisabled(dialog), "goal and nextStep are required");
        });
    }

    @Test
    void bothRequiredSlotsMustBeFilled() {
        interact(() -> {
            HandoffEditDialog dialog = new HandoffEditDialog("Session 1", Optional.empty());

            dialog.goalField().setText("Ship it");
            assertTrue(okDisabled(dialog), "a goal alone is not a brief");

            dialog.nextStepField().setText("Wire the banner");
            assertFalse(okDisabled(dialog));
        });
    }

    @Test
    void whitespaceDoesNotCountAsFillingARequiredSlot() {
        interact(() -> {
            HandoffEditDialog dialog = new HandoffEditDialog("Session 1", Optional.empty());

            dialog.goalField().setText("   ");
            dialog.nextStepField().setText("   ");

            assertTrue(okDisabled(dialog));
        });
    }

    @Test
    void anOverlongSlotBlocksSavingRatherThanFailingLater() {
        interact(() -> {
            HandoffEditDialog dialog = new HandoffEditDialog("Session 1", Optional.empty());
            dialog.goalField().setText("Ship it");
            dialog.nextStepField().setText("Wire the banner");
            assertFalse(okDisabled(dialog));

            dialog.ruledOutField().setText("x".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS + 1));

            assertTrue(okDisabled(dialog), "the store would refuse this, so OK must too");
        });
    }

    @Test
    void anExistingBriefIsLoadedForEditingRatherThanStartingBlank() {
        interact(() -> {
            HandoffEditDialog dialog = new HandoffEditDialog("Session 1", Optional.of(existing()));

            assertEquals("Ship the fork gesture", dialog.goalField().getText());
            assertEquals("Wire the banner", dialog.nextStepField().getText());
            assertEquals("Rejected an API proxy", dialog.ruledOutField().getText());
            assertFalse(okDisabled(dialog));
        });
    }
}
