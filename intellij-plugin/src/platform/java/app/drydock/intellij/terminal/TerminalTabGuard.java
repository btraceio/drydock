package app.drydock.intellij.terminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Closes terminal tabs the Reworked Terminal's tab restoration re-creates on
 * top of a live Drydock session tab.
 *
 * <p>Constraint (verified against the 2025.3.3 terminal plugin bytecode):
 * creating a tab through {@code TerminalToolWindowTabBuilder} stores it in the
 * terminal's durable backend tab list, and the tool window's
 * {@code restoreTabs} — scheduled when the tool window is shown, which
 * {@code requestFocus(true)} triggers right after a launch — re-creates
 * EVERY backend tab it sees without comparing the UI. A session launch
 * therefore produces two identically named tabs, both running the agent
 * command; the same mechanism is what re-runs persisted commands after an
 * IDE restart.</p>
 *
 * <p>This guard hooks the public {@link TerminalTabsManagerListener}: any
 * added tab that is NOT one of the launcher's registered session tabs but
 * carries a registered session's tab name is a restoration duplicate and is
 * closed. A tab the user creates themselves has a default name ("Local N")
 * and is never touched; the rule is name-against-the-live-registry, exactly
 * the startup guard's rule, but event-driven and immediate. Every
 * restoration duplicate is closed this way, including the ones that appear
 * after an IDE restart — the startup sweep in {@code DrydockSessionService}
 * stays as the backstop.</p>
 */
final class TerminalTabGuard implements TerminalTabsManagerListener {

    private static final Logger LOG = System.getLogger(TerminalTabGuard.class.getName());

    private final Project project;
    private final SessionTabRegistry registry;

    private TerminalTabGuard(Project project, SessionTabRegistry registry) {
        this.project = project;
        this.registry = registry;
    }

    /**
     * Installs the guard for the project's launcher: exactly one guard per
     * project, disposed with it.
     */
    static void install(Project project, SessionTabRegistry registry) {
        Disposable disposable = Disposer.newDisposable(project, "Drydock terminal tab guard");
        TerminalToolWindowTabsManager.getInstance(project).addListener(disposable, new TerminalTabGuard(project, registry));
    }

    @Override
    public void tabAdded(TerminalToolWindowTab tab) {
        if (tab == null) {
            return;
        }
        // The registry keys on the tab object itself: a lookup hit is OUR
        // tab (a launch or a legit adopt); a miss with a session's tab name
        // is a restoration duplicate.
        if (registry.lookup(tab) != null) {
            return;
        }
        String name = tabName(tab);
        boolean duplicate = registry.tabs().stream()
                .anyMatch(handle -> name.equals(tabName(handle.tab())));
        if (!duplicate) {
            return;
        }
        // Listener callbacks may arrive off the EDT depending on the
        // restoration path; closing must not.
        ApplicationManager.getApplication().invokeLater(
                () -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    LOG.log(Level.INFO, "Closing a restoration duplicate of terminal tab " + name);
                    TerminalToolWindowTabsManager.getInstance(project).closeTab(tab);
                },
                ModalityState.any());
    }

    /** 2025.3's TerminalToolWindowTab has no name accessor; the Content does (T10's javap finding). */
    private static String tabName(TerminalToolWindowTab tab) {
        return tab.getContent() == null ? "" : tab.getContent().getTabName();
    }
}
