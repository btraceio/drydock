package app.drydock.intellij.settings;

import app.drydock.agent.api.AgentKind;
import app.drydock.editorqa.EditorAskHarnessPolicy;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Optional;

/**
 * The editor Q&amp;A settings ("Ask Agent" from the editor), stored in the
 * IDE's application-level {@link PropertiesComponent} exactly like the
 * {@code WorkspaceSettings} precedent -- application-wide, not per-project,
 * and deliberately separate from the ordinary sessions' last-used agent and
 * model recording ({@code RepositorySettings} in {@code state.json}): a
 * Q&amp;A ask must never move the ordinary picker's defaults, and the
 * ordinary picker must never overwrite the Q&amp;A choice (INV-3).
 *
 * <p>Two key families:</p>
 *
 * <ul>
 *   <li>{@code drydock.editorAsk.harness} -- the Q&amp;A harness choice
 *       (Auto/Pi/Claude/Codex, {@code EditorAskHarnessPolicy.Choice});
 *       absent decodes to Auto, so a fresh install stores nothing;</li>
 *   <li>{@code drydock.editorAsk.model.<harness>} -- the optional model
 *       override per harness (Pi, Claude, Codex only). A model stays with
 *       its harness: the Pi key is read only when the resolved harness is
 *       Pi, so a Pi override can never leak into a Claude or Codex launch.
 *       A blank value clears the key, leaving the properties exactly as a
 *       fresh install.</li>
 * </ul>
 *
 * <p>This is IDE configuration, not a secret store; values are stored
 * verbatim after whitespace stripping. No Terminal key exists: a shell is
 * not a Q&amp;A harness (it has no answer tool), so the enum cannot express
 * it and the model map cannot carry it.</p>
 */
public final class EditorAskSettings {

    private static final String HARNESS_KEY = "drydock.editorAsk.harness";
    private static final String MODEL_PREFIX = "drydock.editorAsk.model.";

    private EditorAskSettings() {
    }

    /** The stored harness choice; absent or unrecognized decodes to Auto. */
    public static EditorAskHarnessPolicy.Choice harness() {
        return EditorAskHarnessPolicy.Choice.fromPersisted(
                PropertiesComponent.getInstance().getValue(HARNESS_KEY));
    }

    /** Sets the harness choice; writing Auto unsets the key, keeping a fresh install clean. */
    public static void setHarness(EditorAskHarnessPolicy.Choice choice) {
        if (choice == EditorAskHarnessPolicy.Choice.AUTO) {
            PropertiesComponent.getInstance().unsetValue(HARNESS_KEY);
        } else {
            PropertiesComponent.getInstance().setValue(HARNESS_KEY, choice.persistedName());
        }
    }

    /** The harness's model override, or empty for "the harness's own default". */
    public static Optional<String> model(AgentKind kind) {
        return Optional.ofNullable(modelKey(kind))
                .map(key -> PropertiesComponent.getInstance().getValue(key))
                .map(String::strip)
                .filter(stored -> !stored.isEmpty());
    }

    /**
     * Sets the model override for {@code kind}; a null or blank value clears
     * the key. Only the Q&amp;A harnesses carry a model: asking for the
     * plain shell's model is a caller bug and fails loudly.
     */
    public static void setModel(AgentKind kind, String model) {
        String key = modelKey(kind);
        if (key == null) {
            throw new IllegalArgumentException("Terminal is not a Q&A harness; it carries no model");
        }
        PropertiesComponent.getInstance().setValue(key, stripped(model));
    }

    /** The exact properties key for a harness's model, or null for a non-Q&amp;A kind. */
    public static String modelKey(AgentKind kind) {
        return switch (kind) {
            case PI -> MODEL_PREFIX + "pi";
            case CLAUDE -> MODEL_PREFIX + "claude";
            case CODEX -> MODEL_PREFIX + "codex";
            case TERMINAL -> null;
        };
    }

    private static String stripped(String value) {
        String stored = value == null ? null : value.strip();
        // A null value removes the key: clearing an override leaves the
        // properties exactly as a fresh install.
        return stored == null || stored.isEmpty() ? null : stored;
    }
}
