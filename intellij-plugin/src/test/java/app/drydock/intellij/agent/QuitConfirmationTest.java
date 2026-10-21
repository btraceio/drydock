package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quit veto: the user's answer decides, and Drydock failing never keeps the IDE from quitting. */
class QuitConfirmationTest {

    @Test
    void theUsersAnswerDecides() {
        assertTrue(new QuitConfirmation(() -> true).canExitApplication());
        assertFalse(new QuitConfirmation(() -> false).canExitApplication());
    }

    @Test
    void aFailingQuestionNeverBlocksTheQuit() {
        assertTrue(new QuitConfirmation(() -> {
            throw new IllegalStateException("catalog not loaded");
        }).canExitApplication());
    }
}
