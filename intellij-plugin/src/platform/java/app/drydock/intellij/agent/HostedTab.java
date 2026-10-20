package app.drydock.intellij.agent;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A console tab showing an agent from another project or worktree than its
 * window's own (cross-project tabs, frame F2): tagged with the agent's repo,
 * and saying where it comes from.
 *
 * <p>The tag is a text prefix, not a coloured chip: an editor tab takes one
 * line of text and an icon, nothing else.</p>
 */
public final class HostedTab {

    /**
     * Set on a console file by the editor showing it, once it has worked out
     * (off the EDT) that the tab is hosted, so the tab's title and tooltip can
     * be painted without touching the disk.
     */
    static final Key<Facts> KEY = Key.create("drydock.hostedTab");

    /**
     * That the window with location hash {@code windowHash} shows the agent
     * hosted, and where the agent is from. The window is named by its hash,
     * not held: these live on an application-lifetime file, and a Project
     * reference would keep a closed window alive.
     */
    record Facts(String windowHash, SessionPlace place) {
        Facts {
            Objects.requireNonNull(windowHash, "windowHash");
            Objects.requireNonNull(place, "place");
        }

        boolean isFor(String hash) {
            return windowHash.equals(hash);
        }
    }

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

    /** The facts for {@code file} in {@code window}, if that window hosts it. EDT-safe. */
    static Optional<SessionPlace> placeIn(AgentConsoleFile file, Project window) {
        return Optional.ofNullable(file.getUserData(KEY))
                .filter(facts -> facts.isFor(window.getLocationHash()))
                .map(Facts::place);
    }
}
