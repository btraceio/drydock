package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure filter and item model of {@link ModelField}; the combo itself needs a Swing/platform thread. */
class ModelFieldTest {

    @Test
    void filterMatchesASubstringAnywhere() {
        assertTrue(ModelField.matches("bedrock/openai.gpt-5.4", "gpt"));
        assertTrue(ModelField.matches("claude-sonnet-4-6", "sonnet"));
    }

    @Test
    void anEmptyQueryMatchesEverything() {
        assertTrue(ModelField.matches("anything", ""));
    }

    @Test
    void filterIsCaseInsensitiveBothWays() {
        assertTrue(ModelField.matches("GPT-5.4", "gpt"));
        assertTrue(ModelField.matches("opus", "OPUS"));
        assertTrue(ModelField.matches("OpUs", "oPuS"));
    }

    @Test
    void filterRejectsNonMatches() {
        assertFalse(ModelField.matches("claude", "xyz"));
        assertFalse(ModelField.matches("claude-sonnet-4-6", "opus"));
    }

    @Test
    void filterIsLocaleIndependent() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertTrue(ModelField.matches("CLI-MODEL", "cli"));
            assertTrue(ModelField.matches("gemini", "GEMINI"));
            assertTrue(ModelField.matches("GLM-5.2", "glm"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void suggestionModelNeverAutoSelects() {
        ModelField.SuggestionModel model = new ModelField.SuggestionModel();
        model.replaceAll(List.of("a", "b"), null);
        assertNull(model.getSelectedItem());
        assertEquals(2, model.getSize());
        assertEquals("b", model.getElementAt(1));
    }

    @Test
    void suggestionModelSelectsTheGivenTextEvenWhenItIsNotAnItem() {
        ModelField.SuggestionModel model = new ModelField.SuggestionModel();
        model.replaceAll(List.of("gpt-1", "gpt-2"), "gp");
        assertEquals("gp", model.getSelectedItem());
    }

    @Test
    void suggestionModelKeepsTheRawText() {
        ModelField.SuggestionModel model = new ModelField.SuggestionModel();
        model.replaceAll(List.of(), "gp ");
        assertEquals("gp ", model.getSelectedItem());
    }

    @Test
    void aRebuildFiresExactlyOneContentsChanged() {
        ModelField.SuggestionModel model = new ModelField.SuggestionModel();
        Events events = new Events();
        model.addListDataListener(events);
        model.replaceAll(List.of("a", "b", "c"), null);
        assertEquals(1, events.changed.get());
        assertEquals(0, events.added.get());
        assertEquals(0, events.removed.get());
        model.replaceAll(List.of("a"), "a");
        assertEquals(2, events.changed.get());
        assertEquals(0, events.added.get());
        assertEquals(0, events.removed.get());
    }

    @Test
    void anUnchangedSelectionFiresNothing() {
        ModelField.SuggestionModel model = new ModelField.SuggestionModel();
        Events events = new Events();
        model.addListDataListener(events);
        model.setSelectedItem("x");
        assertEquals(1, events.changed.get());
        model.setSelectedItem("x");
        assertEquals(1, events.changed.get());
        model.setSelectedItem(null);
        assertEquals(2, events.changed.get());
    }

    @Test
    void anOutOfRangeIndexReadsNull() {
        ModelField.SuggestionModel model = new ModelField.SuggestionModel();
        model.replaceAll(List.of("a", "b"), null);
        assertNull(model.getElementAt(5));
        assertNull(model.getElementAt(-1));
    }

    private static final class Events implements ListDataListener {
        final AtomicInteger changed = new AtomicInteger();
        final AtomicInteger added = new AtomicInteger();
        final AtomicInteger removed = new AtomicInteger();

        @Override
        public void intervalAdded(ListDataEvent e) {
            added.incrementAndGet();
        }

        @Override
        public void intervalRemoved(ListDataEvent e) {
            removed.incrementAndGet();
        }

        @Override
        public void contentsChanged(ListDataEvent e) {
            changed.incrementAndGet();
        }
    }
}
