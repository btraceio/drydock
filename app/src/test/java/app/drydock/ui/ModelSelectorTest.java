package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic of {@link ModelSelector}; the combo behaviour itself needs a
 * JavaFX thread and is covered by the modal tests' manual scenarios.
 */
class ModelSelectorTest {

    @Test
    void filterMatchesCaseInsensitivelyAnywhere() {
        assertTrue(ModelSelector.matches("ai-gw-bedrock/bedrock/openai.gpt-5.4", "GPT-5"));
        assertTrue(ModelSelector.matches("claude-sonnet-4-6", "sonnet"));
        assertTrue(ModelSelector.matches("opus", ""));
    }

    @Test
    void filterRejectsNonMatches() {
        assertFalse(ModelSelector.matches("claude-sonnet-4-6", "opus"));
    }
}
