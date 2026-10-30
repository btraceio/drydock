package app.drydock.intellij.agent;

import com.intellij.terminal.JBTerminalSystemSettingsProviderBase;
import com.jediterm.terminal.HyperlinkStyle;

/**
 * Terminal settings for an agent console: the platform's console defaults,
 * with the scrollback capped.
 *
 * <p>A console otherwise keeps as many history lines as the IDE's
 * {@code terminal.buffer.max.lines.count} advanced setting allows — a
 * setting shared with the IDE's own terminal, which a user may raise far
 * past what a dozen long-running agent consoles can afford. Each kept line
 * is a jediterm {@code TerminalLine} holding styled cell runs, much heavier
 * than its text. The cap applies the lower of that setting and
 * {@link #MAX_HISTORY_LINES}.</p>
 */
final class AgentTerminalSettings extends JBTerminalSystemSettingsProviderBase {

    /** At most this many scrollback lines per agent console. */
    static final int MAX_HISTORY_LINES = 5_000;

    @Override
    public int getBufferMaxLinesCount() {
        return capped(super.getBufferMaxLinesCount());
    }

    /**
     * What {@code TerminalExecutionConsole}'s own default provider changes
     * from the base, kept so this provider differs from it only in the cap.
     */
    @Override
    public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
        return HyperlinkStyle.HighlightMode.ALWAYS;
    }

    /** Pure. The IDE's setting, never above {@link #MAX_HISTORY_LINES}; a non-positive value means the cap. */
    static int capped(int ideSetting) {
        return ideSetting > 0 ? Math.min(ideSetting, MAX_HISTORY_LINES) : MAX_HISTORY_LINES;
    }
}
