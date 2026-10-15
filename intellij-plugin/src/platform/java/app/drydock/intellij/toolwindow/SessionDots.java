package app.drydock.intellij.toolwindow;

import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;

import javax.swing.Icon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session list's status-dot vocabulary (C15): one place that maps a
 * lifecycle status (optionally joined with live activity) to a color, a
 * status word, and a tooltip. All other status words in the tool window
 * resolve through here, so the dot, the row text, and the tooltip can never
 * disagree.
 *
 * <p>Rules the vocabulary enforces:</p>
 *
 * <ul>
 *   <li>Facet semantics come from {@link SessionStatusFacet} — the single
 *       status-to-facet mapping. In particular an ERROR status (FAILED,
 *       MISSING_WORKING_DIRECTORY, UNSUPPORTED_AGENT) is never read as
 *       "inactive"; those rows may be worth inspecting before deleting
 *       (kb/repo-menu-bulk-session-actions).</li>
 *   <li>Activity only labels a session in the RUNNING band: activity is a
 *       property of a live process, so a leftover state file must never
 *       label an exited or failed session "busy".</li>
 *   <li>Rendering is STATIC — a filled dot for anything worth attention, a
 *       hollow ring for the idle band, and no animation of any kind. The
 *       app's FX pulse driver burned 26h CPU per 70h uptime through exactly
 *       that mistake (btraceio--drydock/main/status-dot-pulse-cpu/
 *       find-status-dot-pulse-driver); the plugin does not repeat it.</li>
 * </ul>
 *
 * <p>No IntelliJ type appears here — only JDK {@code awt}/{@code swing} — so
 * the vocabulary is unit-testable without an IDE.</p>
 */
public final class SessionDots {

    /** IntelliJ's link blue; reads on both light and dark LaFs. */
    private static final Color RUNNING_BLUE = new Color(0x3574F0);

    private static final Color ERROR_RED = new Color(0xDB3B3B);

    /** Distinct from running blue and error red: "worth a badge". */
    private static final Color ATTENTION_AMBER = new Color(0xE0A030);

    private static final Color IDLE_GRAY = new Color(0x8C8C8C);

    private static final int DOT_DIAMETER = 8;

    /** Icons are immutable, so one cached instance per (color, filled) serves every render. */
    private static final Map<CacheKey, Icon> ICONS = new ConcurrentHashMap<>();

    private SessionDots() {
    }

    /** The dot's color. Attention outranks plain running; error outranks everything. */
    public static Color dotColor(SessionStatus status, Optional<SessionActivity> activity) {
        SessionStatusFacet facet = SessionStatusFacet.of(status);
        if (facet == SessionStatusFacet.ERROR) {
            return ERROR_RED;
        }
        if (facet == SessionStatusFacet.RUNNING
                && activity.orElse(SessionActivity.UNKNOWN) == SessionActivity.NEEDS_ATTENTION) {
            return ATTENTION_AMBER;
        }
        return facet == SessionStatusFacet.RUNNING ? RUNNING_BLUE : IDLE_GRAY;
    }

    /** The single-line status text of a row, e.g. {@code "Running · busy"}. */
    public static String statusText(SessionStatus status, Optional<SessionActivity> activity) {
        String word = statusWord(status);
        return activityWord(status, activity).isEmpty()
                ? word
                : word + " · " + activityWord(status, activity);
    }

    /** The row's tooltip: one sentence explaining the state in full words. */
    public static String tooltip(SessionStatus status, Optional<SessionActivity> activity) {
        return switch (status) {
            case STARTING, RUNNING -> "The agent's terminal is live"
                    + switch (activity.orElse(SessionActivity.UNKNOWN)) {
                        case BUSY -> ": the agent is working";
                        case NEEDS_ATTENTION -> ": waiting for you (permission prompt or notification)";
                        case IDLE -> ": waiting at the prompt";
                        case UNKNOWN -> "";
                    };
            case EXITED -> "The agent process exited on its own";
            case INACTIVE -> "Not running: the tab was closed while the agent was live, or the IDE restarted";
            case FAILED -> "The last launch or resume failed";
            case MISSING_WORKING_DIRECTORY -> "The session's working directory no longer exists";
            case UNSUPPORTED_AGENT -> "This Drydock build cannot run this session's agent";
        };
    }

    /** Lifecycle words; error statuses are named for what went wrong, never "inactive". */
    public static String statusWord(SessionStatus status) {
        return switch (status) {
            case STARTING -> "Starting";
            case RUNNING -> "Running";
            case INACTIVE -> "Inactive";
            case EXITED -> "Exited";
            case FAILED -> "Failed";
            case MISSING_WORKING_DIRECTORY -> "Missing directory";
            case UNSUPPORTED_AGENT -> "Unsupported agent";
        };
    }

    /**
     * The activity suffix, or {@code ""} when there is nothing honest to
     * append: no activity known, or the session is not in the running band.
     */
    private static String activityWord(SessionStatus status, Optional<SessionActivity> activity) {
        if (SessionStatusFacet.of(status) != SessionStatusFacet.RUNNING
                || activity.isEmpty()
                || activity.get() == SessionActivity.UNKNOWN) {
            return "";
        }
        return switch (activity.get()) {
            case BUSY -> "busy";
            case IDLE -> "idle";
            case NEEDS_ATTENTION -> "needs attention";
            case UNKNOWN -> "";
        };
    }

    /**
     * The static dot icon for a row: filled for the running and error bands,
     * a hollow ring for the idle band (mirrors the app's design vocabulary —
     * error always fills, so a failed idle session still shows a solid dot).
     */
    public static Icon dotIcon(SessionStatus status, Optional<SessionActivity> activity) {
        Color color = dotColor(status, activity);
        boolean filled = SessionStatusFacet.of(status) != SessionStatusFacet.IDLE;
        return ICONS.computeIfAbsent(new CacheKey(color, filled), DotIcon::new);
    }

    private record CacheKey(Color color, boolean filled) {
    }

    /** Paints one dot; no timers, no repaint scheduling, no animation. */
    private static final class DotIcon implements Icon {

        private final Color color;
        private final boolean filled;

        private DotIcon(CacheKey key) {
            this.color = key.color();
            this.filled = key.filled();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(color);
                if (filled) {
                    graphics.fillOval(x + 1, y + 1, DOT_DIAMETER, DOT_DIAMETER);
                } else {
                    graphics.drawOval(x + 1, y + 1, DOT_DIAMETER - 1, DOT_DIAMETER - 1);
                }
            } finally {
                graphics.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return DOT_DIAMETER + 2;
        }

        @Override
        public int getIconHeight() {
            return DOT_DIAMETER + 2;
        }

        @Override
        public String toString() {
            return "SessionDots.DotIcon[" + Integer.toHexString(color.getRGB()) + " filled=" + filled + "]";
        }
    }
}
