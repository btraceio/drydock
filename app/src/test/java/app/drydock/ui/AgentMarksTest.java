package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentMarksTest {

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), kind, "s",
                Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(),
                status, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), false, false);
    }

    @Test
    void everyKindHasItsOwnGlyphAndStyleClass() {
        Set<String> glyphs = new HashSet<>();
        Set<String> classes = new HashSet<>();
        for (AgentKind kind : AgentKind.values()) {
            glyphs.add(AgentMarks.glyph(kind));
            classes.add(AgentMarks.styleClass(kind));
        }
        assertEquals(AgentKind.values().length, glyphs.size(), "glyphs must be distinct");
        assertEquals(AgentKind.values().length, classes.size(), "style classes must be distinct");
        assertNotEquals(AgentMarks.unknownGlyph(), AgentMarks.glyph(AgentKind.CLAUDE));
    }

    @Test
    void styleClassesFollowTheAgentMarkNamingScheme() {
        assertEquals("agent-mark-claude", AgentMarks.styleClass(AgentKind.CLAUDE));
        assertEquals("agent-mark-codex", AgentMarks.styleClass(AgentKind.CODEX));
        assertEquals("agent-mark-pi", AgentMarks.styleClass(AgentKind.PI));
        assertEquals("agent-mark-unknown", AgentMarks.unknownStyleClass());
    }

    @Test
    void markTextUsesTheSessionsKind() {
        assertEquals(AgentMarks.glyph(AgentKind.CODEX),
                AgentMarks.markText(session(AgentKind.CODEX, SessionStatus.RUNNING)));
    }

    /**
     * An unrecognized agent's kind is only a placeholder (CLAUDE, per the
     * state decoder), so it must render the unknown mark -- not the one name
     * we know to be wrong.
     */
    @Test
    void anUnsupportedAgentRendersTheUnknownMark() {
        assertEquals(AgentMarks.unknownGlyph(),
                AgentMarks.markText(session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT)));
    }
}
