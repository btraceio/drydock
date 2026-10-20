package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Closing a window that shows agents (cross-project tabs, frame F4), as pure
 * functions: the question's copy, and what happens to each agent.
 *
 * <p>Once the IDE owns the processes, closing a window no longer has to stop
 * anything, so the default answer is to keep them running: they stay in the
 * Drydock list as {@code not shown} and any window can show them again.
 * Quitting the IDE is a different question and stays the MVP's
 * ({@link StopAgentsPlan}).</p>
 */
public final class CloseWindowPlan {

    /** Where "Don't ask again" remembers the answer; only Keep running is remembered. */
    public static final String REMEMBERED_KEY = "drydock.closeWindow.remembered";

    public static final String BODY =
            "Agents that keep running stay in the Drydock list as not shown. Open them from any window.";

    private CloseWindowPlan() {
    }

    /** The two ways to close. Cancel is not a decision; it keeps the window. */
    public enum Decision { KEEP_RUNNING, STOP_ALL }

    /** What closing does to the agents the window shows. */
    public record Outcome(Set<ManagedSessionId> release, Set<ManagedSessionId> stop) {
        public Outcome {
            release = Set.copyOf(release);
            stop = Set.copyOf(stop);
        }
    }

    /** {@code This window is showing <n> working agent(s)}. */
    public static String title(int working) {
        return "This window is showing " + working + (working == 1 ? " working agent" : " working agents");
    }

    /** {@code <repo> · this window} or {@code <repo> · hosted}, with {@code · needs you} when it asks. */
    public static String row(String repository, boolean hosted, boolean needsYou) {
        return repository + (hosted ? " · hosted" : " · this window") + (needsYou ? " · needs you" : "");
    }

    /**
     * The running sessions the close question asks about: all the window
     * shows except those moving to another window, whose outcome is neither
     * stop nor release ({@link #outcome}).
     */
    public static List<ManagedSessionId> askedAbout(List<ManagedSessionId> runningShownHere,
                                                    Set<ManagedSessionId> moving) {
        return runningShownHere.stream().filter(id -> !moving.contains(id)).toList();
    }

    /**
     * Which of {@code runningShownHere} are released (kept running, not
     * shown) and which are stopped.
     *
     * <p>{@code decision} is the answer given now, empty when no question was
     * asked — no agent was mid-task. Then the remembered answer applies, and
     * without one the agents at their prompt are stopped, as before hosting
     * existed: nothing is lost, and Resume brings each back.</p>
     *
     * <p>A session in {@code moving} is in neither set: its console is on its
     * way to another window, and stopping or releasing it now would undo the
     * move.</p>
     */
    public static Outcome outcome(Optional<Decision> decision, Optional<Decision> remembered,
                                  List<ManagedSessionId> runningShownHere, Set<ManagedSessionId> moving) {
        Objects.requireNonNull(moving, "moving");
        Decision applied = decision.or(() -> remembered).orElse(Decision.STOP_ALL);
        Set<ManagedSessionId> affected = new LinkedHashSet<>(runningShownHere);
        affected.removeAll(moving);
        return applied == Decision.KEEP_RUNNING
                ? new Outcome(affected, Set.of())
                : new Outcome(Set.of(), affected);
    }
}
