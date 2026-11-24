package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which harness answers editor Q&amp;A, resolved headlessly from the
 * application Q&amp;A settings ({@code EditorAskSettings} on the platform
 * side) and the {@link app.drydock.agent.api.AgentRegistry} availability
 * snapshot.
 *
 * <p>Deliberately separate from the ordinary picker's default resolution
 * ({@code AgentRegistry.resolveDefault}): the ordinary default follows the
 * repository's last-used agent and the global preference order, while Q&amp;A
 * keeps its own preference list -- Pi, Claude, Codex -- and never falls back
 * to the plain shell. {@link AgentKind#TERMINAL} is not an agent CLI at all;
 * a Q&amp;A question handed to a bare shell would have no answer tool to call,
 * so it is excluded from every resolution here. Neither does this class
 * consult {@code resolveDefault}: the coordinator reads the Q&amp;A snapshot
 * and the registry directly, never the ordinary default (INV-3).</p>
 *
 * <p>Model overrides are keyed by harness: a Pi override is sent only when
 * the resolved harness is Pi, never when Auto moves down to Claude or Codex.
 * A blank override means "the harness's own default" and omits the model
 * flag entirely. An existing conversation never re-resolves: {@link
 * #keepOriginal(AgentKind)} pins the resume to the binding's original
 * harness with no model flag, because a model flag on resume would start a
 * different conversation and a harness change would silently discard the
 * provider context (context loss is announced by the coordinator instead).</p>
 *
 * <p>Pure and headless: availability arrives as a predicate (production
 * wires {@code registry::isAvailable}, whose snapshot is probed off the UI
 * thread at registry construction), so this class never spawns a process
 * and never touches the platform.</p>
 */
public final class EditorAskHarnessPolicy {

    /**
     * The Q&amp;A-specific preference order applied when the user chose Auto:
     * Pi first, then Claude, then Codex. Not {@link AgentKind#preferenceOrder()}
     * -- that list serves the ordinary picker's availability default and puts
     * Claude first; Q&amp;A prefers Pi (the harness the answer-tool flow was
     * validated against first). Terminal is deliberately absent.
     */
    public static final List<AgentKind> QA_PREFERENCE =
            List.of(AgentKind.PI, AgentKind.CLAUDE, AgentKind.CODEX);

    /** The user's harness selection for Q&amp;A conversations. */
    public enum Choice {
        AUTO("auto", "Auto (Pi, then Claude, then Codex)"),
        PI("pi", "Pi"),
        CLAUDE("claude", "Claude"),
        CODEX("codex", "Codex");

        private final String persistedName;
        private final String label;

        Choice(String persistedName, String label) {
            this.persistedName = persistedName;
            this.label = label;
        }

        /** Stable name written into the application properties. */
        public String persistedName() {
            return persistedName;
        }

        /** Human-readable name for the settings page. */
        public String label() {
            return label;
        }

        /**
         * Lenient decode of the stored name: absent or unrecognized values
         * (including a legacy value naming a non-Q&amp;A kind such as
         * {@code terminal}) fall back to Auto rather than failing the page.
         */
        public static Choice fromPersisted(String value) {
            if (value == null) {
                return AUTO;
            }
            for (Choice choice : values()) {
                if (choice.persistedName.equals(value.strip())) {
                    return choice;
                }
            }
            return AUTO;
        }
    }

    /**
     * Where a fresh Q&amp;A conversation runs: a resolved harness with that
     * harness's own optional model, or a visible refusal. There is no
     * "substituted" outcome -- an unavailable pinned harness fails the ask
     * instead of silently moving to another CLI (INV-3).
     */
    public sealed interface FreshResolution {
    }

    /**
     * A fresh conversation on {@code harness}; {@code model} is that
     * harness's own override, empty to omit the model flag.
     */
    public record Fresh(AgentKind harness, Optional<String> model) implements FreshResolution {
        public Fresh {
            Objects.requireNonNull(harness, "harness");
            model = normalize(model);
        }
    }

    /** No usable harness; the reason is user-facing and points at Settings. */
    public record Disabled(String reason) implements FreshResolution {
        public Disabled {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * An existing conversation keeps its original harness: exact resume, no
     * model flag ever. The record deliberately carries no model -- the type
     * is the guarantee that a resume cannot leak a model override.
     */
    public record Resume(AgentKind harness) {
        public Resume {
            Objects.requireNonNull(harness, "harness");
        }
    }

    private EditorAskHarnessPolicy() {
    }

    /**
     * Resolves the harness for a fresh Q&amp;A conversation.
     *
     * <p>Auto walks {@link #QA_PREFERENCE} and takes the first harness the
     * availability predicate accepts; none accepted (Terminal can never be
     * accepted -- it is not in the list) is a visible {@link Disabled}. A
     * pinned harness must be available or the resolution fails with a reason
     * naming the pinned harness -- even when other harnesses are available,
     * because a silent substitution would answer the question with a
     * different agent than the user picked.</p>
     *
     * @param choice    the user's settings selection, never null
     * @param available availability of a local agentic provider (production:
     *                  {@code registry::isAvailable}); call only after the
     *                  registry has been probed off the UI thread
     * @param models    per-harness optional model override, blank already
     *                  normalized to empty by the settings reader
     */
    public static FreshResolution resolveFresh(Choice choice, Predicate<AgentKind> available,
                                               Function<AgentKind, Optional<String>> models) {
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(models, "models");
        return switch (choice) {
            case AUTO -> {
                for (AgentKind kind : QA_PREFERENCE) {
                    if (available.test(kind)) {
                        yield new Fresh(kind, models.apply(kind));
                    }
                }
                yield new Disabled("No Q&A agent (Pi, Claude, Codex) is installed."
                        + " Install one, or pick a harness in Settings → Tools → Drydock.");
            }
            case PI -> pinned(AgentKind.PI, available, models);
            case CLAUDE -> pinned(AgentKind.CLAUDE, available, models);
            case CODEX -> pinned(AgentKind.CODEX, available, models);
        };
    }

    /**
     * The resume intent for an existing conversation binding: keep the
     * original harness and never send a model flag. The caller (the
     * coordinator) decides whether the binding is actually live or exactly
     * resumable; anything less provable than that is a fresh conversation
     * under current settings with a context-loss notice, resolved by
     * {@link #resolveFresh}.
     */
    public static Resume keepOriginal(AgentKind boundHarness) {
        return new Resume(boundHarness);
    }

    private static FreshResolution pinned(AgentKind pinned, Predicate<AgentKind> available,
                                          Function<AgentKind, Optional<String>> models) {
        if (!available.test(pinned)) {
            return new Disabled("The pinned Q&A harness " + pinned.persistedName()
                    + " is not available. Install it, or change the harness in Settings → Tools → Drydock."
                    + " The question is not sent to another agent instead.");
        }
        return new Fresh(pinned, models.apply(pinned));
    }

    /**
     * A model override belongs to exactly one harness: blank (or a blank
     * handed in by a lenient caller) is no flag at all, never "inherit the
     * previous harness's model".
     */
    private static Optional<String> normalize(Optional<String> model) {
        return model == null ? Optional.empty()
                : model.map(String::strip).filter(stripped -> !stripped.isEmpty());
    }
}
