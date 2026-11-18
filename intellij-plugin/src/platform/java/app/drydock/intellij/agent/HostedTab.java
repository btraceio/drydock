package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A console tab showing an agent from another project or worktree than its
 * window's own (cross-project tabs, frame F2): tagged with the agent's repo,
 * and saying where it comes from.
 *
 * <p>The tag is a text prefix, not a coloured chip: an editor tab takes one
 * line of text and an icon, nothing else.</p>
 */
public final class HostedTab {

    private HostedTab() {
    }

    /**
     * Whether an agent working in {@code canonicalWorkingDirectory} is hosted
     * by a window whose project is {@code canonicalProjectBase}. Pure; both
     * paths canonical, since a worktree can be reached through a symlink.
     */
    public static boolean isHosted(Optional<Path> canonicalProjectBase, Path canonicalWorkingDirectory) {
        return canonicalProjectBase.map(base -> !base.equals(canonicalWorkingDirectory)).orElse(false);
    }

    /** {@code <repo> · <title>}: the tab's repo tag before the agent's title. */
    public static String title(String repository, String title) {
        return repository + " · " + title;
    }

    /** {@code <repo> · <branch> · hosted}. */
    public static String tooltip(SessionPlace place) {
        return place.label() + " · hosted";
    }

    /**
     * Where the agent {@code id} is from, if this window shows it hosted: the
     * window controller's resolved facts decide (a KNOWN answer with no
     * {@code hostedFrom} means "not hosted"); an unresolved answer yields
     * the {@code legacy} fallback. EDT-safe, memory-only.
     */
    static Optional<SessionPlace> placeIn(Project window, ManagedSessionId id) {
        return placeIn(AgentTabCommands.controllerFacts(window), id, Optional::empty);
    }

    static Optional<SessionPlace> placeIn(Function<ManagedSessionId, Optional<SessionPaneFacts>> facts,
                                          ManagedSessionId id, Supplier<Optional<SessionPlace>> legacy) {
        Optional<SessionPaneFacts> known = AgentTabCommands.resolvedFacts(facts, id);
        return known.isPresent() ? known.get().hostedFrom() : legacy.get();
    }
}
