package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryCatalog;
import app.drydock.intellij.agent.JumpPlan;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The raise-or-open of a directory's IDE window, shared by the session
 * jump ({@code AgentConsoles.jumpToOwnWindow}) and the checkout window
 * action ({@code TaskWindows.openCheckoutWindow}). Split in two halves
 * because the two callers wrap them differently around their own
 * bookkeeping: {@link #scan(Path)} runs on a background thread (it scans
 * the disk and the open projects), {@link #raiseOrOpen(Project, Path,
 * String, Scan)} runs on the EDT and raises the window the scan found, or
 * opens one when none shows the directory.
 *
 * <p>The rule both callers share: any open window showing the directory —
 * whichever window asked — is raised, never a second copy of an
 * already-open project. A folder gone since the click, and a window that
 * did not open, are each said in {@code from} instead of doing nothing.</p>
 */
public final class RaiseOrOpenWindow {

    private static final System.Logger LOG = System.getLogger(RaiseOrOpenWindow.class.getName());

    private RaiseOrOpenWindow() {
    }

    /** What the background scan found: whether the directory is there, and the open window showing it, if any. */
    public record Scan(boolean folderExists, Optional<Project> openWindow) {
    }

    /**
     * The open window showing {@code canonical}, if any. Pure;
     * {@code canonicalBase} gives a window's canonical project directory,
     * empty for a window without one.
     */
    static <W> Optional<W> findOpenWindow(List<W> windows, Function<W, Optional<Path>> canonicalBase,
                                          Path canonical) {
        return windows.stream()
                .filter(window -> canonicalBase.apply(window).map(canonical::equals).orElse(false))
                .findFirst();
    }

    /**
     * The background half: canonicalizes {@code directory}, checks it still
     * exists, and looks for an open window on it. A window whose base path
     * cannot be canonicalized is skipped; a failure to check the directory
     * itself is reported to the caller as a folder-gone. Call off the EDT.
     */
    public static Scan scan(Path directory) {
        try {
            Path canonical = RepositoryCatalog.canonicalize(directory);
            if (!Files.isDirectory(canonical)) {
                return new Scan(false, Optional.empty());
            }
            Optional<Project> open = findOpenWindow(
                    Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                            .filter(project -> !project.isDisposed() && project.getBasePath() != null)
                            .toList(),
                    RaiseOrOpenWindow::canonicalBase,
                    canonical);
            return new Scan(true, open);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Could not look for a window of " + directory + ": " + e);
            return new Scan(false, Optional.empty());
        }
    }

    /**
     * A window's canonical project directory, empty for a window without one
     * or one whose base path cannot be canonicalized; the window is then
     * skipped instead of failing the whole scan.
     */
    private static Optional<Path> canonicalBase(Project project) {
        String base = project.getBasePath();
        if (base == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RepositoryCatalog.canonicalize(Path.of(base)));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Skipping a window whose base path cannot be canonicalized: " + base + ": " + e);
            return Optional.empty();
        }
    }

    /**
     * The EDT half: raises the window the scan found, or opens one on
     * {@code directory} (through {@link TaskWindows#openAsProject(Path)})
     * when no window shows it, and focuses it. When the folder is gone or
     * the window does not open, says so in {@code from} and returns empty;
     * {@code name} labels the directory in either dialog.
     */
    public static Optional<Project> raiseOrOpen(Project from, Path directory, String name, Scan scan) {
        if (!scan.folderExists()) {
            if (!from.isDisposed()) {
                Messages.showErrorDialog(from, JumpPlan.folderGone(name, directory), "Drydock");
            }
            return Optional.empty();
        }
        Project target = scan.openWindow().filter(project -> !project.isDisposed())
                .orElseGet(() -> TaskWindows.openAsProject(directory));
        if (target == null || target.isDisposed()) {
            if (!from.isDisposed()) {
                Messages.showErrorDialog(from, "The window for " + name + " did not open.", "Drydock");
            }
            return Optional.empty();
        }
        ProjectUtil.focusProjectWindow(target, true);
        return Optional.of(target);
    }
}
