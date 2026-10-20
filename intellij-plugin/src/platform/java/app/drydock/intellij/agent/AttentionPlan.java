package app.drydock.intellij.agent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The "needs you" notification's copy (handoff surface 4), as pure functions.
 *
 * <p>One agent: {@code Claude needs you}, with {@code <session title> ·
 * <repo> · <branch>} as the body — the agent's own worktree, never the
 * window that happens to show it. Several arriving together collapse into one
 * notification, {@code 2 agents need you}, one line per agent — a stack of
 * balloons for one moment of attention is noise the user has to dismiss one
 * by one.</p>
 */
public final class AttentionPlan {

    private AttentionPlan() {
    }

    /** One agent asking: who it is, and where. */
    public record Ask(String agent, String sessionTitle, String place) {
    }

    /** {@code Claude needs you}, or {@code 2 agents need you}. */
    public static String title(List<Ask> asks) {
        return asks.size() == 1 ? asks.get(0).agent() + " needs you" : asks.size() + " agents need you";
    }

    /** Plain text, one line per agent: {@code <session title> · <repo>}. */
    public static String plainBody(List<Ask> asks) {
        return asks.stream().map(AttentionPlan::line).collect(Collectors.joining("\n"));
    }

    /**
     * The balloon's body: the same lines as HTML. Session titles come from
     * the user and the agent, so they are escaped — a title is text, never
     * markup.
     */
    public static String htmlBody(List<Ask> asks) {
        return asks.stream().map(ask -> escape(line(ask))).collect(Collectors.joining("<br>"));
    }

    /**
     * The balloon's two actions: a session some window shows is raised there
     * or snoozed; one no window shows can be shown in the window the user is
     * in, or moved into its own (cross-project tabs, frame F5).
     */
    public static List<String> actions(boolean shown) {
        return shown ? List.of("Raise window", "Snooze") : List.of("Show here", "Open project window");
    }

    private static String line(Ask ask) {
        return ask.sessionTitle() + " · " + ask.place();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
