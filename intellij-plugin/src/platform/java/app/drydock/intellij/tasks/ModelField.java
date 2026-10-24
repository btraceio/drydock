package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.DocumentAdapter;

import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractListModel;
import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;

import java.awt.BorderLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The starting-model picker of the Drydock task dialogs: an editable,
 * searchable combo over the selected harness's model suggestions, plus
 * whatever models this repository has launched with before. The Swing twin
 * of the standalone app's {@code app.drydock.ui.ModelSelector}, with the
 * same contract.
 *
 * <p>Search filters the popup's items (case-insensitive substring, over the
 * retained {@code allItems}, so clearing the query restores the full list) while the
 * editor keeps the query being typed — the model list is a SUGGESTION, never
 * a whitelist. Free text is a first-class input: Claude and Codex have no
 * model-listing command, so their suggestions are a handful of aliases or
 * nothing, and even Pi's catalog is a snapshot. The launch carries whatever
 * string is in the editor; empty means the harness's own default.</p>
 *
 * <p>What the editor held is remembered per harness, so switching agents and
 * back does not silently drop a model the human typed by hand. Each
 * harness's first visit seeds from the repository's last-used model, and its
 * suggestion load runs once ({@code suggestions} may block — Pi spawns a
 * {@code pi --list-models} subprocess) on a pooled thread, merging in when
 * it arrives; the picker is usable from the first keystroke with just the
 * remembered model.</p>
 */
final class ModelField extends JPanel {

    /** Pure filter, unit-tested without a Swing thread: case-insensitive substring match, locale-independent (Locale.ROOT). */
    static boolean matches(String item, String query) {
        return item.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    /** What the editor held for each harness, so switching agents and back restores it. */
    private final Map<AgentKind, String> textByKind = new HashMap<>();

    /** Harnesses whose suggestion load has been kicked off (one subprocess per harness per picker). */
    private final Set<AgentKind> requested = new LinkedHashSet<>();

    private final Function<AgentKind, List<String>> suggestions;
    private final JComboBox<String> combo = new JComboBox<>();
    private final SuggestionModel model = new SuggestionModel();

    /** Every suggestion merged so far; the combo's items are always this list filtered by the editor query. */
    private final List<String> allItems = new ArrayList<>();

    /** True while this class itself writes the editor text or the items, so the listener stays idle. */
    private boolean updating;

    /** The harness whose suggestions/pre-fill are currently shown. */
    private AgentKind currentKind;

    ModelField(Function<AgentKind, List<String>> suggestions) {
        this.suggestions = suggestions;
        setLayout(new BorderLayout());
        combo.setEditable(true);
        combo.setModel(model);
        combo.setPrototypeDisplayValue("x".repeat(48));
        JTextComponent editor = (JTextComponent) combo.getEditor().getEditorComponent();
        editor.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                onTextChanged();
            }
        });
        // An editable combo commits the editor to its value on Enter and on
        // popup selection, but not on focus loss. The dialog reads the editor
        // directly, so the two can never disagree here — but committing keeps
        // the visible item in sync with what is on screen.
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(@NotNull FocusEvent e) {
                commitEditor();
            }
        });
        add(combo, BorderLayout.CENTER);
    }

    /**
     * Points the picker at {@code kind}: restores what the editor held for it
     * (seeded from the repository's last-used model on first visit) and kicks
     * off the suggestion load.
     */
    void setAgent(AgentKind kind, Function<AgentKind, Optional<String>> lastUsedModel) {
        currentKind = kind;
        setText(textByKind.computeIfAbsent(kind, k -> lastUsedModel.apply(k).orElse("")));
        if (requested.add(kind)) {
            loadSuggestions(kind);
        }
    }

    /** The model to launch with: the editor text, empty when the harness's own default is wanted. */
    Optional<String> selected() {
        String text = editorText();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** Pure substring filter, applied to the popup's items while the editor text is the query. */
    private void onTextChanged() {
        if (updating) {
            return;
        }
        String query = editorText();
        if (currentKind != null) {
            textByKind.put(currentKind, query);
        }
        applyFilter(query);
        if (!combo.isShowing()) {
            return; // a popup cannot anchor to an unshown combo (HeadlessException / IllegalComponentStateException)
        }
        if (query.isEmpty() || model.getSize() == 0) {
            combo.setPopupVisible(false);
        } else if (!combo.isPopupVisible()) {
            combo.setPopupVisible(true);
        }
    }

    /**
     * Rebuilds the popup's items from {@code allItems} to those matching
     * {@code query}, so a shorter query (or clearing it) brings back items an
     * earlier query hid. The selection is set to the RAW editor text, so the
     * combo UI's configureEditor writes back the identical string and never
     * mutates the document (which would throw inside the DocumentListener).
     */
    private void applyFilter(String query) {
        updating = true;
        try {
            List<String> items = new ArrayList<>();
            for (String item : allItems) {
                if (matches(item, query)) {
                    items.add(item);
                }
            }
            String raw = rawEditorText();
            model.replaceAll(items, raw.isEmpty() ? null : raw);
        } finally {
            updating = false;
        }
    }

    private void commitEditor() {
        String text = editorText();
        updating = true;
        try {
            model.setSelectedItem(text.isEmpty() ? null : text);
        } finally {
            updating = false;
        }
    }

    private void setText(String text) {
        updating = true;
        try {
            JTextComponent editor = (JTextComponent) combo.getEditor().getEditorComponent();
            editor.setText(text);
        } finally {
            updating = false;
        }
        applyFilter(editorText());
    }

    /** The editor's text exactly as typed (no strip), "" for null. */
    private String rawEditorText() {
        JTextComponent editor = (JTextComponent) combo.getEditor().getEditorComponent();
        String text = editor.getText();
        return text == null ? "" : text;
    }

    private String editorText() {
        return rawEditorText().strip();
    }

    /**
     * Merges the harness's suggestions in on a pooled thread (the probe may
     * spawn a subprocess and block); the picker stays usable meanwhile with
     * the remembered model. Guarded by kind, so a slow catalog for an agent
     * the human already switched away from never lands in the wrong picker.
     */
    private void loadSuggestions(AgentKind kind) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> found = suggestions.apply(kind);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (currentKind != kind) {
                    return;
                }
                for (String suggestion : found) {
                    if (!allItems.contains(suggestion)) {
                        allItems.add(suggestion);
                    }
                }
                // A query typed before the catalog arrived must still hold:
                // merged items are filtered against it like the rest (empty query = all).
                applyFilter(editorText());
            });
        });
    }

    /**
     * The combo's items, replaced wholesale and never auto-selecting: the selection is whatever the
     * editor holds, so a list-data event never rewrites the query being typed.
     * <p>Because the selection tracks the raw editor text, each rebuild makes {@code JComboBox}
     * fire ItemEvents and an ActionEvent per keystroke ({@code JComboBox#contentsChanged}).
     * Harmless while nothing listens on the private combo; do not add item/action listeners
     * to it without accounting for that.</p>
     */
    static final class SuggestionModel extends AbstractListModel<String> implements ComboBoxModel<String> {
        private List<String> items = List.of();
        private Object selected;

        @Override
        public int getSize() {
            return items.size();
        }

        @Override
        public String getElementAt(int index) {
            return index >= 0 && index < items.size() ? items.get(index) : null; // as DefaultComboBoxModel
        }

        @Override
        public Object getSelectedItem() {
            return selected;
        }

        @Override
        public void setSelectedItem(Object item) {
            if (!Objects.equals(selected, item)) {
                selected = item;
                fireContentsChanged(this, -1, -1);
            }
        }

        /** Swaps in {@code newItems} and sets {@code selection}, in ONE contentsChanged event. */
        void replaceAll(List<String> newItems, Object selection) {
            int last = Math.max(items.size(), newItems.size()) - 1;
            items = List.copyOf(newItems);
            selected = selection;
            fireContentsChanged(this, last < 0 ? -1 : 0, last);
        }
    }
}
