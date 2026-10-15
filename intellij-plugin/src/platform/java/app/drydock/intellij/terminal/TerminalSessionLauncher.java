package app.drydock.intellij.terminal;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.domain.ManagedSessionId;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Opens a Drydock agent session as a tab of the standard Terminal tool window
 * (plan Q7); the Drydock tool window only lists and focuses these tabs. The
 * agent process itself is spawned by the IDE terminal — the launcher only
 * hands a shell argv to the tab builder, so no child process is ever spawned
 * from here (C12).
 */
public interface TerminalSessionLauncher {

    /**
     * Opens a terminal tab named {@code tabName}, starting in
     * {@code workingDirectory}, running the shell command carried by
     * {@code plan} (see {@link ShellCommandAdapter}).
     *
     * <p>The {@code sessionId} ties the created tab to its managed session so
     * the returned handle (and every {@link SessionTabRegistry} entry) can
     * carry it. Note: the plan's original three-argument signature omitted it,
     * which left the handle's {@code sessionId()} unsatisfiable — the id must
     * reach the launcher with the open call (task report deviation 1).</p>
     *
     * <p>May be called from any thread. {@code createTab()} runs
     * {@code doCreateTab} synchronously in the calling thread and constructs
     * Swing components (a {@code TerminalToolWindowPanel} and a
     * {@code Content}), which is EDT-only work — so the whole builder chain
     * hops to the EDT and the returned future completes there (task report
     * deviation 2). The tab is registered in this launcher's
     * {@link #tabRegistry()} before the future completes, so a completed open
     * is always visible to the lifecycle monitor.</p>
     *
     * <p>{@code plan.supported() == false} is not checked here; callers reject
     * such plans before launching (AC11, enforced by the session service).</p>
     */
    CompletableFuture<TerminalSessionTab> open(
            ManagedSessionId sessionId, String tabName, Path workingDirectory, LaunchPlan plan);

    /**
     * The registry every tab opened by this launcher is filed in. One
     * launcher instance owns one registry; the lifecycle monitor and the tool
     * window read from it.
     */
    SessionTabRegistry tabRegistry();

    /**
     * A live terminal tab owned by a managed session.
     */
    interface TerminalSessionTab {

        /** The managed session this tab was opened for. */
        ManagedSessionId sessionId();

        /**
         * Opaque identity of the underlying tab, stable for the tab's
         * lifetime. The registry keys on it; callers that hold the IDE tab
         * object itself use that object as the key.
         */
        Object tabKey();

        /** The IDE tab. Touching its view/content is EDT work. */
        TerminalToolWindowTab tab();

        /**
         * Closes the tab; the IDE terminates the shell process the tab hosts
         * (AC13). {@code TerminalToolWindowTab} has no close of its own on
         * 253.3 — the tabs manager owns closure (task report deviation 3).
         */
        void close();
    }

    /**
     * Creates the IDE implementation for the given project. Exactly one
     * launcher (hence one registry) should serve a project.
     *
     * <p>Explicit {@code public}: interface members are implicitly public
     * (JLS 9.4) anyway — the keyword documents it, because the source-level
     * omission reads as package-private and has misled both a coder and a
     * checker.</p>
     */
    public static TerminalSessionLauncher forProject(Project project) {
        return new IntellijTerminalSessionLauncher(project);
    }
}

/**
 * IDE implementation. Package-private: construct via
 * {@link TerminalSessionLauncher#forProject}.
 */
final class IntellijTerminalSessionLauncher implements TerminalSessionLauncher {

    private final Project project;
    private final SessionTabRegistry registry = new SessionTabRegistry();

    IntellijTerminalSessionLauncher(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public SessionTabRegistry tabRegistry() {
        return registry;
    }

    @Override
    public CompletableFuture<TerminalSessionLauncher.TerminalSessionTab> open(
            ManagedSessionId sessionId, String tabName, Path workingDirectory, LaunchPlan plan) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(tabName, "tabName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(plan, "plan");
        // Pure expansion on the calling thread; nothing IDE-touching yet.
        List<String> shellArgv = ShellCommandAdapter.shellArgv(plan);
        String directory = workingDirectory.toString();

        CompletableFuture<TerminalSessionLauncher.TerminalSessionTab> result = new CompletableFuture<>();
        // createTab() constructs Swing components synchronously (verified in
        // the 253.3 bytecode: doCreateTab news a TerminalToolWindowPanel and a
        // Content in the calling thread), so the chain runs on the EDT.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                TerminalToolWindowTab tab = TerminalToolWindowTabsManager.getInstance(project)
                        .createTabBuilder()
                        .tabName(tabName)
                        .workingDirectory(directory)
                        .shellCommand(shellArgv)
                        .requestFocus(true)
                        .createTab();
                TerminalSessionLauncher.TerminalSessionTab handle =
                        new IntellijTerminalSessionTab(sessionId, tab);
                registry.register(handle);
                result.complete(handle);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, ModalityState.any());
        return result;
    }

    /**
     * The IDE-side handle. The tab object is its own registry key, so
     * anything holding the IDE tab (restored-tab guard, content listeners)
     * can look the handle up directly.
     */
    private final class IntellijTerminalSessionTab implements TerminalSessionLauncher.TerminalSessionTab {

        private final ManagedSessionId sessionId;
        private final TerminalToolWindowTab tab;

        IntellijTerminalSessionTab(ManagedSessionId sessionId, TerminalToolWindowTab tab) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.tab = Objects.requireNonNull(tab, "tab");
        }

        @Override
        public ManagedSessionId sessionId() {
            return sessionId;
        }

        @Override
        public Object tabKey() {
            return tab;
        }

        @Override
        public TerminalToolWindowTab tab() {
            return tab;
        }

        @Override
        public void close() {
            // closeTab() removes the tab's content from its ContentManager —
            // EDT-only mutation, so hop regardless of the calling thread.
            ApplicationManager.getApplication().invokeLater(
                    () -> TerminalToolWindowTabsManager.getInstance(project).closeTab(tab),
                    ModalityState.any());
        }
    }
}
