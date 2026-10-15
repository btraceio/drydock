package app.drydock.intellij.toolwindow;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.terminal.SessionLifecycleMonitor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.AsyncProcessIcon;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * Registers the Drydock tool window (plan Q7: this window lists and focuses
 * agent sessions; the sessions themselves live as tabs of the standard
 * Terminal tool window, opened by the session service).
 *
 * <p>The content starts as a "Loading sessions…" placeholder with visible
 * progress (AC12), and is replaced — on the EDT, only after the plugin init
 * and the project's repository resolution are warm — by the real {@link
 * SessionsPanel} (task T12; T16 makes that panel the tool window's one
 * combined tab — sessions, worktrees, and purgable stale entries in a
 * single list). The failure path keeps the placeholder with the
 * error, the non-git early return keeps it with an explanation, and both
 * clear the spinner (the same AsyncProcessIcon removal is the stop path on
 * every completion branch).</p>
 *
 * <p>This factory is also where the {@link SessionLifecycleMonitor} comes to
 * life: it is constructed and started once the panel exists, subscribed by
 * the session service (the persistence half of the event contract, T10) and
 * by the panel's list model (the display half, T12), and registered for
 * disposal on the project so its poll loop and content listener never outlive
 * it (AGENTS "UI lifecycle hygiene").</p>
 */
public final class SessionsToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LoadingPlaceholder placeholder = new LoadingPlaceholder();
        Content content = newContent(placeholder);
        toolWindow.getContentManager().addContent(content);
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        // whenCompleteAsync even when the warm-up is already done, so the
        // completion never runs inline on the EDT. The repository resolution
        // re-runs the idempotent find-or-create the warm-up already performed
        // — the single writer's equals check makes the repeat free — because
        // the warm-up future itself does not carry the repository value.
        DrydockStartupActivity.warmup(project)
                .thenComposeAsync(unused -> DrydockStartupActivity.repositories(project).projectRepository(project),
                        plugin.executor())
                .whenCompleteAsync((repository, failure) -> ApplicationManager.getApplication().invokeLater(
                        () -> install(project, content, placeholder, repository, failure), ModalityState.any()),
                        plugin.executor());
    }

    /**
     * The tool window's single content (T17): untitled and non-closeable.
     * The platform's tab layout hides the content-tab strip for exactly
     * one content whose title is empty and whose tab label carries no
     * active icons (TabContentLayout's isToDrawTabs — the same rule that
     * leaves the Project view strip-free), so the panel renders under the
     * tool window's own "Drydock" title without a redundant inner header.
     * The closeable flag is cleared because it is what a tab-action
     * provider keys a close icon on; the pinned/closeable third parameter
     * of the factory covers the pin. Deliberately NOT the registry key
     * {@code debugger.new.tool.window.layout.dnd} (read as false by
     * default, and global to every tool window anyway): its single-content
     * mode additionally requires the internal SingleContentSupplier of the
     * new debugger UI — the wrong tool for one strip-free tab.
     */
    static Content newContent(JComponent placeholder) {
        Content content = ContentFactory.getInstance().createContent(placeholder, "", false);
        content.setCloseable(false);
        return content;
    }

    private void install(Project project, Content content, LoadingPlaceholder placeholder, Repository repository,
            Throwable failure) {
        if (failure != null) {
            placeholder.done(rootCause(failure));
            return;
        }
        if (repository == null) {
            // Default and scratch projects have no base directory; an
            // absent repo is an early return, not an error path.
            placeholder.message("This project is not a git repository; Drydock lists sessions per repository.");
            return;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        SessionListModel model = new SessionListModel(repository.id(),
                () -> plugin.stateStore().state().sessions(),
                command -> ApplicationManager.getApplication().invokeLater(command, ModalityState.any()));
        DrydockSessionService sessions = DrydockStartupActivity.sessions(project);
        SessionsPanel panel = new SessionsPanel(
                project, plugin.registry(), repository, model, sessions, sessions.tabRegistry());
        SessionLifecycleMonitor monitor = new SessionLifecycleMonitor(
                project, sessions.tabRegistry(), plugin.watcher(), plugin.executor(),
                id -> agentSessionIdOf(plugin.stateStore().state(), id));
        // Sink order matters and is deliberate: the session service (the
        // persistence half) applies its state update synchronously in the
        // event, so the model's coalesced rebuild — queued by the same event
        // and run later — already reads the new status.
        monitor.addSink(sessions);
        monitor.addSink(model);
        monitor.start();
        Disposer.register(project, monitor);
        content.setComponent(panel);
        // We are on the EDT here; the initial fill lands before first paint.
        model.refreshNow();
    }

    /**
     * Resolves the agent session id the monitor polls activity for. The id
     * lives in the persisted session record, not in the tab registry's
     * handles — the same seam split the monitor's constructor documents.
     */
    private static Optional<String> agentSessionIdOf(ApplicationState state, ManagedSessionId id) {
        for (ManagedAgentSession session : state.sessions()) {
            if (session.id().equals(id)) {
                return session.agentSessionId();
            }
        }
        return Optional.empty();
    }

    /**
     * The warm-up placeholder. The spinner is the platform's shared {@link
     * AsyncProcessIcon}: its animation is paint-driven (repaint cycles are
     * requested only while the icon is visible), so removing it from the
     * panel is the stop path on every completion branch — no stranded
     * indefinite animation.
     */
    private static final class LoadingPlaceholder extends JPanel {

        private final AsyncProcessIcon progress = new AsyncProcessIcon("Loading sessions");
        private final JLabel status = new JLabel("Loading sessions…");

        LoadingPlaceholder() {
            super(new BorderLayout(8, 8));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            add(progress, BorderLayout.WEST);
            add(status, BorderLayout.CENTER);
        }

        /** The warm-up failed: show the actual cause (spinner cleared). */
        void done(Throwable failure) {
            message("Drydock did not finish loading: " + failure.getMessage());
        }

        /** The warm-up completed without a panel to install (spinner cleared). */
        void message(String text) {
            remove(progress);
            status.setText(text);
            revalidate();
            repaint();
        }
    }

    /** Unwraps compose-chain wrappers so the placeholder shows the actual cause. */
    private static Throwable rootCause(Throwable failure) {
        return failure instanceof CompletionException layered && layered.getCause() != null
                ? rootCause(layered.getCause())
                : failure;
    }
}
