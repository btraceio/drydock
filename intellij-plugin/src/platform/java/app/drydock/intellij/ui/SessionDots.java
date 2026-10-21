package app.drydock.intellij.ui;

import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;

import javax.swing.Icon;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The status-dot vocabulary: one place that maps a lifecycle status
 * (optionally joined with live activity) to a color, a status word, and a
 * tooltip. Every status word drydock shows — the agent strip's bold state
 * word, the Agents popup rows, the Welcome tab — resolves through here, so
 * the dot, the row text, and the tooltip can never disagree.
 *
 * <p>The words are the ones the UX handoff names: <em>Working</em>,
 * <em>At prompt</em>, <em>Needs you</em>, <em>Not running</em>, and the error
 * facet's own words. They fold activity into the lifecycle rather than
 * appending it, because the surfaces that show them have room for one word,
 * not two.</p>
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
    public static final Color RUNNING_BLUE = new Color(0x3574F0);

    public static final Color ERROR_RED = new Color(0xDB3B3B);

    /** Distinct from running blue and error red: "worth a badge". */
    public static final Color ATTENTION_AMBER = new Color(0xE0A030);

    public static final Color IDLE_GRAY = new Color(0x8C8C8C);

    public static final int DOT_DIAMETER = 8;

    /** Icons are immutable, so one cached instance per (color, filled) serves every render. */
    private static final Map<CacheKey, Icon> DOT_ICONS = new ConcurrentHashMap<>();

    /** The tab mark varies only by status color, so repaints share immutable icons. */
    private static final Map<Color, Icon> DRYDOCK_ICONS = new ConcurrentHashMap<>();

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

    /**
     * The one word for a session's state: {@code Working} / {@code At prompt}
     * / {@code Needs you} for the running band (by activity), {@code Not
     * running} for the idle band, and the error facet's own word otherwise.
     *
     * <p>A running session whose activity cannot be read — an agent with no
     * activity reporter, or one whose first report has not landed — is
     * {@code Running}. The handoff names no word for that case, and the
     * alternatives both assert something unknown: {@code Working} claims the
     * agent is busy when the user may be looking at an idle prompt, and
     * {@code At prompt} claims the reverse. The dot stays filled blue either
     * way, which is the part that is actually known.</p>
     */
    public static String statusWord(SessionStatus status, Optional<SessionActivity> activity) {
        if (status == SessionStatus.STOPPING) {
            return statusWord(status);
        }
        if (SessionStatusFacet.of(status) != SessionStatusFacet.RUNNING) {
            return statusWord(status);
        }
        return switch (activity.orElse(SessionActivity.UNKNOWN)) {
            case BUSY -> "Working";
            case IDLE -> "At prompt";
            case NEEDS_ATTENTION -> "Needs you";
            case UNKNOWN -> "Running";
        };
    }

    /** The row's tooltip: one sentence explaining the state in full words. */
    public static String tooltip(SessionStatus status, Optional<SessionActivity> activity) {
        return switch (status) {
            case STARTING, RUNNING -> "The agent's console is live"
                    + switch (activity.orElse(SessionActivity.UNKNOWN)) {
                        case BUSY -> ": the agent is working";
                        case NEEDS_ATTENTION -> ": waiting for you (permission prompt or notification)";
                        case IDLE -> ": waiting at the prompt";
                        case UNKNOWN -> "";
                    };
            case STOPPING -> "The agent is being deactivated";
            case IDLE -> "Not running: no live execution, and the session can be resumed";
            case EXITED -> "Not running: the agent process exited on its own";
            case INACTIVE -> "Not running: Drydock deactivated it, or the IDE restarted. Nothing restarts on its own";
            case FAILED -> "The last launch or resume failed";
            case MISSING_WORKING_DIRECTORY -> "The session's working directory no longer exists";
            case UNSUPPORTED_AGENT -> "This Drydock build cannot run this session's agent";
        };
    }

    /**
     * Lifecycle words without activity; error statuses are named for what
     * went wrong, never "inactive". {@code INACTIVE} and {@code EXITED} share
     * one word: from the user's side a session the IDE stopped and one whose
     * agent exited are both simply not running, and the difference is carried
     * by the strip's "stopped &lt;when/why&gt;" line instead.
     */
    public static String statusWord(SessionStatus status) {
        return switch (status) {
            case STARTING -> "Starting";
            case RUNNING -> "Running";
            case STOPPING -> "Deactivating";
            case IDLE, INACTIVE, EXITED -> "Not running";
            case FAILED -> "Failed";
            case MISSING_WORKING_DIRECTORY -> "Missing directory";
            case UNSUPPORTED_AGENT -> "Unsupported agent";
        };
    }

    /**
     * The static dot icon: filled for the running and error bands,
     * a hollow ring for the idle band (mirrors the app's design vocabulary —
     * error always fills, so a failed idle session still shows a solid dot).
     */
    public static Icon dotIcon(SessionStatus status, Optional<SessionActivity> activity) {
        Color color = dotColor(status, activity);
        boolean filled = SessionStatusFacet.of(status) != SessionStatusFacet.IDLE;
        return DOT_ICONS.computeIfAbsent(new CacheKey(color, filled), DotIcon::new);
    }

    /**
     * The Drydock robot mark in the session's status color. Agent console tabs
     * use this instead of an anonymous dot so they remain visibly associated
     * with the Drydock tool window even while that window is closed.
     */
    public static Icon drydockIcon(SessionStatus status, Optional<SessionActivity> activity) {
        Color color = dotColor(status, activity);
        return DRYDOCK_ICONS.computeIfAbsent(color, DrydockIcon::new);
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

    /** Paints the same 16 px robot-and-dock silhouette as {@code drydock.svg}. */
    private static final class DrydockIcon implements Icon {

        private static final int SIZE = 16;

        private final Color color;

        private DrydockIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                graphics.translate(x, y);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(color);
                graphics.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                Path2D.Float spark = new Path2D.Float();
                spark.moveTo(8.0, 1.2);
                spark.curveTo(8.2, 2.0, 8.4, 2.2, 9.2, 2.4);
                spark.curveTo(8.4, 2.6, 8.2, 2.8, 8.0, 3.6);
                spark.curveTo(7.8, 2.8, 7.6, 2.6, 6.8, 2.4);
                spark.curveTo(7.6, 2.2, 7.8, 2.0, 8.0, 1.2);
                spark.closePath();
                graphics.fill(spark);

                graphics.draw(new Line2D.Float(8.0f, 3.8f, 8.0f, 5.5f));
                graphics.draw(new RoundRectangle2D.Float(3.1f, 5.6f, 9.8f, 6.6f, 4.0f, 4.0f));
                graphics.fill(new Ellipse2D.Float(5.15f, 7.85f, 2.1f, 2.1f));
                graphics.fill(new Ellipse2D.Float(8.75f, 7.85f, 2.1f, 2.1f));
                graphics.draw(new Line2D.Float(1.6f, 14.3f, 14.4f, 14.3f));
            } finally {
                graphics.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public String toString() {
            return "SessionDots.DrydockIcon[" + Integer.toHexString(color.getRGB()) + "]";
        }
    }
}
