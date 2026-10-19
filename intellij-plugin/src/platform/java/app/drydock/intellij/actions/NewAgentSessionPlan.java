package app.drydock.intellij.actions;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.SessionMode;
import app.drydock.git.WorktreeNaming;

import java.util.Locale;
import java.util.Optional;

/**
 * Every decision the New Agent Session dialog makes, as pure functions.
 *
 * <p>The dialog's rules are the part of it worth pinning: which placement a
 * mode defaults to, which of two warnings a writing session in the current
 * tree earns, and what the primary button is therefore called. All three are
 * stated in the UX handoff as copy, and copy is exactly what drifts when it
 * lives inside Swing listeners. The dialog places these results; it does not
 * derive them.</p>
 */
public final class NewAgentSessionPlan {

    /** Where a new session runs. */
    public enum Placement {
        /** The open project's own worktree, shared with the user's edits. */
        HERE,
        /** A worktree created for it, opened as its own window. */
        NEW_WORKTREE
    }

    private NewAgentSessionPlan() {
    }

    /**
     * Whether the dialog offers {@code kind} as an agent.
     *
     * <p>Every agentic CLI, and not {@link AgentKind#TERMINAL}: that kind is a
     * plain shell, so a dialog that asks "What should the agent do?" and
     * gives a task to whatever it starts has nothing to give the task to. The
     * handoff names exactly Claude, Codex and pi.</p>
     */
    public static boolean offersAgent(AgentKind kind) {
        return kind != AgentKind.TERMINAL;
    }

    /**
     * The default placement for {@code mode}.
     *
     * <p>A writing agent gets its own worktree, so it cannot collide with the
     * user's edits; a read-only one runs here, because isolating a session
     * that will not write anything buys nothing and costs a checkout.</p>
     */
    public static Placement defaultPlacement(SessionMode mode) {
        return mode == SessionMode.READ_ONLY ? Placement.HERE : Placement.NEW_WORKTREE;
    }

    /**
     * The branch name suggested for a new worktree: the task's first three
     * words, slugged.
     *
     * <p>Three words is enough to recognise the branch a week later and short
     * enough to live in a directory name. Returns empty for a task that offers
     * nothing usable, so the caller can leave the field for the human rather
     * than inventing a name like {@code worktree} that says nothing.</p>
     */
    public static Optional<String> branchSuggestion(String task) {
        if (task == null || task.isBlank()) {
            return Optional.empty();
        }
        String[] words = task.strip().split("\\s+");
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < Math.min(3, words.length); i++) {
            first.append(' ').append(words[i]);
        }
        String slug = WorktreeNaming.slug(first.toString().toLowerCase(Locale.ROOT));
        // slug() answers "worktree" when nothing usable survives; that is a
        // sensible directory name and a meaningless branch name.
        return "worktree".equals(slug) ? Optional.empty() : Optional.of(slug);
    }

    /**
     * The warning a writing session in the current worktree earns, or empty
     * when there is nothing to warn about.
     *
     * <p>Only {@link SessionMode#WRITES} in {@link Placement#HERE} warns: a
     * read-only session cannot collide with anything, and a session in its own
     * new worktree has the tree to itself. The two texts are different
     * problems, not severities — one is two agents overwriting each other, the
     * other is an agent overwriting the human.</p>
     */
    public static Optional<String> warning(SessionMode mode, Placement placement, boolean anotherWriterActive) {
        if (mode != SessionMode.WRITES || placement != Placement.HERE) {
            return Optional.empty();
        }
        return Optional.of(anotherWriterActive
                ? "Another writing session is active in this worktree. Two writers can overwrite each other."
                : "A writing agent here shares the tree with your own edits.");
    }

    /**
     * The session's initial title: the task's first line, bounded.
     *
     * <p>Initial, because the agent renames the session once it knows what it
     * is doing and that name is what the tab and the Agents list then show.
     * Until then the task is the only thing anyone can recognise it by — far
     * better than a numbered placeholder.</p>
     */
    public static String sessionTitle(String task) {
        if (task == null || task.isBlank()) {
            return "Agent session";
        }
        String firstLine = task.strip().lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) {
            return "Agent session";
        }
        return firstLine.length() <= TITLE_LIMIT
                ? firstLine
                : firstLine.substring(0, TITLE_LIMIT).stripTrailing() + "…";
    }

    /** Long enough to be recognisable, short enough for an editor tab. */
    private static final int TITLE_LIMIT = 48;

    /**
     * The primary button's text.
     *
     * <p>{@code Start anyway} whenever a warning is showing, so the button
     * names the risk being accepted rather than reading like a neutral
     * confirmation.</p>
     */
    public static String primaryLabel(Placement placement, boolean warned) {
        if (warned) {
            return "Start anyway";
        }
        return placement == Placement.NEW_WORKTREE ? "Create & Open Window" : "Start Here";
    }
}
