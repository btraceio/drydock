package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The starting-model picker of the start-session modals: an editable,
 * searchable combo over the selected harness's model suggestions, plus
 * whatever models this repository has launched with before.
 *
 * <p>Search works like the branch picker in {@link NewWorktreeModal}: typing
 * filters the popup's items (case-insensitive substring) and keeps the
 * combo's {@code value} in lockstep with the editor text, so the skin's
 * {@code updateDisplayNode} never writes a stale value back over the query
 * being typed (a {@link FilteredList} predicate change fires the same
 * items-change that would clobber it).</p>
 *
 * <p>Free text is a first-class input, not a fallback: Claude and Codex have
 * no model-listing command, so their suggestions are a handful of aliases or
 * nothing, and even Pi's catalog is a snapshot. The launch carries whatever
 * string is in the editor; empty means the harness's own default.</p>
 *
 * <p>The popup's list is the SELECTED harness's, never a merge: a harness's
 * suggestions replace the previous harness's wholesale on switch, because a
 * merged list reads as one harness's offering while carrying another's ids
 * (Pi's whole catalog under Claude's or Codex's toggle -- the defect this
 * scoping fixes). A harness whose catalog has not loaded yet shows an empty
 * list until it lands; the editor's remembered model stays usable throughout.</p>
 *
 * <p>Suggestions load off the FX thread ({@code pi --list-models} spawns a
 * subprocess; see {@code AgentRegistry#modelSuggestions}) and land in the
 * picker when they arrive; the picker is usable from the first keystroke with
 * just the remembered model.</p>
 */
final class ModelSelector extends VBox {

    /** The harness whose suggestions/pre-fill are currently shown. */
    private AgentKind currentKind;

    /**
     * What the editor held for each harness, so switching agents and back
     * does not silently drop a model the human typed by hand. Seeded per
     * harness from the repository's last-used model (see {@code setAgent}).
     */
    private final Map<AgentKind, String> textByKind = new HashMap<>();

    /**
     * Each harness's loaded suggestions, kept per kind so switching back
     * shows its own list again without a second load (see {@link #setAgent}).
     */
    private final Map<AgentKind, List<String>> suggestionsByKind = new EnumMap<>(AgentKind.class);

    private final AgentRegistry registry;
    private final Function<AgentKind, Optional<String>> lastUsedModel;
    private final ObservableList<String> source = FXCollections.observableArrayList();
    private final FilteredList<String> filtered = new FilteredList<>(source);
    private final ComboBox<String> combo = new ComboBox<>(filtered);

    /** True while this class itself writes the editor text, so the text listener does not treat it as a query. */
    private boolean applyingText;

    /** Harnesses whose suggestion load has been kicked off (one subprocess per harness per picker). */
    private final java.util.Set<AgentKind> requested = new java.util.HashSet<>();

    ModelSelector(AgentRegistry registry, Function<AgentKind, Optional<String>> lastUsedModel) {
        this(registry, lastUsedModel, () -> { });
    }

    /**
     * @param onModelCommit runs when the model is committed (popup selection,
     *                      Enter, or focus leaving the editor) -- the modal
     *                      rebuilds its command preview with it, so the preview
     *                      shows the command that will actually launch. Not
     *                      every keystroke: the preview rebuild may probe the
     *                      CLI, and a per-keystroke probe is exactly the eager
     *                      work the modals avoid elsewhere.
     */
    ModelSelector(AgentRegistry registry, Function<AgentKind, Optional<String>> lastUsedModel,
                  Runnable onModelCommit) {
        this.registry = registry;
        this.lastUsedModel = lastUsedModel;
        setSpacing(6);
        setMaxWidth(Double.MAX_VALUE);

        Label label = new Label("Model");
        label.getStyleClass().add("worktree-field-label");

        combo.setEditable(true);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getStyleClass().add("worktree-model-combo");
        combo.setPromptText("(harness default)");
        // Assigned before any listener can fire, same as NewWorktreeModal's
        // baseField: setAgent() reads it, and a construction-time listener
        // firing on the prompt would wipe the seed.
        combo.getEditor().textProperty().addListener((obs, oldText, newText) -> onTextChanged());
        combo.setOnAction(e -> onModelCommit.run());
        // An editable combo commits the editor to its value on Enter and on
        // popup selection, but not on focus loss -- clicking Start with typed
        // text and no Enter would preview/launch with the committed (old)
        // value while selected() reads the editor directly, so the two could
        // disagree. Commit on focus loss keeps the value equal to what is on
        // screen.
        combo.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                combo.commitValue();
                onModelCommit.run();
            }
        });
        combo.setTooltip(new Tooltip("Starting model for this session. Type to search; empty uses the "
                + "harness's own default."));

        getChildren().addAll(label, combo);
    }

    /**
     * Points the picker at {@code kind}: restores what the editor held for it
     * (seeded from the repository's last-used model on first visit), swaps
     * the popup's list to this harness's own suggestions, and kicks off the
     * suggestion load when it is the first visit. {@code TERMINAL} has no
     * model to pick, so the modal hides this whole selector for it.
     */
    void setAgent(AgentKind kind) {
        currentKind = kind;
        // First visit to this harness seeds from the repository's memory of
        // its last starting model; later visits restore what the editor held.
        applyText(textByKind.computeIfAbsent(kind, k -> lastUsedModel.apply(k).orElse("")));
        // The list is scoped to the harness: swapping it wholesale is what
        // keeps one harness's catalog from reading as another's offering.
        // setAll fires the same items-change a predicate change does, so the
        // filter and value are re-synced from the editor's CURRENT text
        // afterwards -- and an open popup is closed rather than left showing
        // the previous harness's items behind the new toggle.
        source.setAll(suggestionsByKind.getOrDefault(kind, List.of()));
        syncFilterToEditorText();
        combo.hide();
        if (requested.add(kind)) {
            loadSuggestions(kind);
        }
    }

    /** The model to launch with: the editor text, empty when the harness's own default is wanted. */
    Optional<String> selected() {
        String text = combo.getEditor().getText();
        String stripped = (text == null ? "" : text).strip();
        return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
    }

    /** Pure filter, unit-tested without a JavaFX thread: case-insensitive substring match. */
    static boolean matches(String item, String query) {
        return item.toLowerCase().contains(query.toLowerCase());
    }

    private void applyText(String text) {
        applyingText = true;
        try {
            combo.getEditor().setText(text);
            combo.setValue(text.isEmpty() ? null : text);
        } finally {
            applyingText = false;
        }
    }

    /**
     * The load-bearing lockstep from the branch picker, minus the converter:
     * a {@link FilteredList} predicate change fires the items-change the
     * ComboBox skin reacts to by re-syncing the editor from {@code value} --
     * so {@code value} must always equal the editor text, or the query being
     * typed is wiped. Suppressed while {@link #applyText} writes, and while
     * the combo is hidden (a hidden control's listener still fires).
     */
    private void onTextChanged() {
        if (applyingText) {
            return;
        }
        String text = combo.getEditor().getText();
        String query = (text == null ? "" : text).strip();
        if (currentKind != null) {
            textByKind.put(currentKind, query);
        }
        syncFilterToEditorText();
        if (combo.isVisible()) {
            if (query.isEmpty() || filtered.isEmpty()) {
                combo.hide();
            } else if (!combo.isShowing()) {
                combo.show();
            }
        }
    }

    /**
     * Re-applies the filter and the combo's value for the editor's CURRENT
     * text. Shared by {@link #onTextChanged} (every keystroke) and {@link
     * #setAgent} (whose {@code setAll} fires the same items-change a
     * predicate change would, with no keystroke to drive it).
     */
    private void syncFilterToEditorText() {
        String text = combo.getEditor().getText();
        String query = (text == null ? "" : text).strip();
        filtered.setPredicate(item -> query.isEmpty() || matches(item, query));
        String value = combo.getValue();
        if (value == null || !value.equals(query)) {
            combo.setValue(query.isEmpty() ? null : query);
        }
    }

    /**
     * Loads {@code kind}'s suggestions on a virtual thread (the probe may
     * spawn a subprocess and block); the picker stays usable meanwhile with
     * the remembered model. The result is stored per kind -- returning to a
     * harness shows its own list without a second load -- and swapped in
     * only when it is still the selected kind, so a slow catalog for an agent
     * the human already switched away from never lands in the wrong picker.
     */
    private void loadSuggestions(AgentKind kind) {
        Thread.ofVirtual().name("drydock-model-catalog-" + kind.persistedName()).start(() -> {
            var suggestions = registry.modelSuggestions(kind);
            javafx.application.Platform.runLater(() -> {
                // Deduped HERE, at store time, so the setAgent swap replays
                // the same list the load showed (a repeated id -- two catalog
                // sources listing the same model -- must appear once).
                suggestionsByKind.put(kind, List.copyOf(new java.util.LinkedHashSet<>(suggestions)));
                if (currentKind != kind) {
                    return;
                }
                source.setAll(suggestionsByKind.get(kind));
                syncFilterToEditorText();
            });
        });
    }
}
