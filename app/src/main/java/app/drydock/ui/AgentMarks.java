package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import javafx.scene.control.Label;

/**
 * The glyph-and-color half of agent identity: what a Claude row looks like
 * next to a Codex one. Every agent <em>name</em> comes from {@link
 * AgentLabels} instead, so there is one source per concern.
 *
 * <p>Deliberately split into pure lookups and one node factory: the lookups
 * are unit-tested, while anything building FX nodes needs a toolkit and is
 * covered by the visual pass.
 */
public final class AgentMarks {

    /** Text-presentation selector: keeps a glyph from resolving to a color emoji face. */
    private static final String TEXT_PRESENTATION = "︎";

    private AgentMarks() { }

    /** The per-agent mark, e.g. {@code ✳} for Claude. */
    public static String glyph(AgentKind kind) {
        return switch (kind) {
            case CLAUDE -> "✳" + TEXT_PRESENTATION;
            case CODEX -> "◈";
            case PI -> "π";
        };
    }

    /** The mark for a session whose persisted agent this build does not recognize. */
    public static String unknownGlyph() {
        return "?";
    }

    public static String styleClass(AgentKind kind) {
        return "agent-mark-" + kind.persistedName();
    }

    public static String unknownStyleClass() {
        return "agent-mark-unknown";
    }

    /**
     * The glyph for one session. A session with {@link
     * SessionStatus#UNSUPPORTED_AGENT} gets {@link #unknownGlyph()}: its
     * {@code agentKind()} is only a placeholder, so rendering it would put
     * the one mark known to be wrong on the session that has no agent at all.
     */
    public static String markText(ManagedAgentSession session) {
        return isUnknown(session) ? unknownGlyph() : glyph(session.agentKind());
    }

    /**
     * The sidebar row's mark. Carries no tooltip on purpose: the row already
     * installs a rich one, and a second tooltip here would replace it with a
     * poorer one exactly where the cursor lands on the row's left edge.
     */
    public static Label createMark(ManagedAgentSession session) {
        Label mark = new Label(markText(session));
        mark.getStyleClass().addAll("agent-mark",
                isUnknown(session) ? unknownStyleClass() : styleClass(session.agentKind()));
        return mark;
    }

    private static boolean isUnknown(ManagedAgentSession session) {
        return session.status() == SessionStatus.UNSUPPORTED_AGENT;
    }
}
