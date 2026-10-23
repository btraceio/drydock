package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The Review tab's visibility predicate (Q8): the tab shows in a window
 * only when a Drydock session covers that window's checkout, so windows
 * with no Drydock session carry no empty fifth tab.
 *
 * <p>Registered as the {@code changesViewContent} entry's
 * {@code predicateClassName}. {@code ChangesViewContentEP.newPredicateInstance}
 * (263 bytecode) constructs this class through the same Project-constructor
 * injection path as the provider and requires
 * {@code java.util.function.Predicate<Project>} — this class is written
 * against exactly that.</p>
 *
 * <p><strong>In-memory only.</strong> The check reads the plugin
 * service's already-loaded state — never the session catalog, never the
 * shared projects file — so the platform's tab-visibility call path spawns
 * no git and touches no file. A session's coverage is decided by
 * <em>real-path</em> comparison of its working directory with the project
 * base path (macOS lists {@code /private/var/…} where a temporary
 * directory is {@code /var/…}), the same comparison {@link ReviewTabScopes}
 * uses to find a checkout's own entry.</p>
 *
 * <p>Q8's fallback: if the live check (AC24) shows the platform does not
 * re-evaluate this predicate, the tab is registered always and the empty
 * state ({@link ReviewTabStatus#NO_SESSION}) carries the burden instead —
 * the decision belongs to AC24, not to this class.</p>
 */
final class ReviewTabVisibility implements Predicate<Project> {

    /** Injected by the platform's predicate construction; the window evaluated is the {@link #test} argument. */
    private final Project project;

    ReviewTabVisibility(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    /**
     * Whether {@code window} should show the Review tab: the plugin service
     * finished initializing and some in-memory session's working directory
     * is the window's base path. Never throws — a half-initialized service
     * or an unreadable state hides the tab; it must not break the host view.
     */
    @Override
    public boolean test(Project window) {
        if (window == null || window.isDisposed()) {
            return false;
        }
        String base = window.getBasePath();
        if (base == null) {
            return false; // a default (directory-less) project is never a session's checkout
        }
        try {
            DrydockPluginService plugin = DrydockPluginService.getInstance();
            return plugin.initialization().isDone() && covers(plugin, Path.of(base));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether any in-memory session's working directory is {@code base} by
     * real path. Package-private: the tab's provider (T13) shows the same
     * rule's negative as the {@link ReviewTabStatus#NO_SESSION} state, so
     * the predicate and the empty state can never disagree.
     */
    static boolean covers(DrydockPluginService plugin, Path base) {
        Path realBase = realPath(base);
        if (realBase == null) {
            return false; // the base path itself is gone: nothing can cover it
        }
        for (ManagedAgentSession session : sessions(plugin)) {
            Path real = realPath(session.workingDirectory());
            if (real != null && real.equals(realBase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The in-memory session list: the service's already-loaded state, never
     * a fresh read. Any failure (the store not yet published, a service
     * still initializing) reads as "no sessions" rather than throwing.
     */
    static List<ManagedAgentSession> sessions(DrydockPluginService plugin) {
        try {
            return plugin.stateStore().state().sessions();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The real path, or {@code null} when it cannot be resolved — a session
     * whose directory is gone does not cover anything, and a base path that
     * cannot be resolved cannot be covered.
     */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}
