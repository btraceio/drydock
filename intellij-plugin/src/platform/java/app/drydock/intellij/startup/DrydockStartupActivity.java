package app.drydock.intellij.startup;

import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.intellij.project.ProjectRepositoryService;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.AgentExecutionService;
import app.drydock.intellij.session.DrydockSessionService;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-project Drydock wiring (T11): constructs the project-scoped services
 * and pre-warms them with visible progress, so the first click in the
 * Drydock tool window never pays the state load or the repository probe.
 *
 * <p>{@code ProjectRepositoryService} and {@code DrydockSessionService}
 * cannot be registered as declarative {@code projectService} entries: their
 * constructors take the plugin service's executor and the window's execution
 * host, which the component container cannot supply. This activity constructs
 * exactly one instance per project and holds it in the project's user data;
 * {@link #repositories(Project)} and {@link #sessions(Project)} are the
 * accessors the dialogs and the console strip use.</p>
 *
 * <p>It starts nothing. Opening a project restores whatever console tabs the
 * platform persisted, all of them in their Not-running state, and no agent
 * runs until a human asks for one.</p>
 *
 * <p>Threading (AC7/AC12): every constructor here is cheap and EDT-safe
 * ({@link DrydockPluginService}'s own initialization runs on its executor);
 * the warm-up (plugin init, then the project's repository resolution)
 * runs off the EDT as futures, and the visible progress is a background
 * task whose completion — success, failure, timeout, or the non-git early
 * return — always clears it.</p>
 */
public final class DrydockStartupActivity implements StartupActivity {

    private static final Logger LOG = System.getLogger(DrydockStartupActivity.class.getName());

    /** Bounded wait for the warm-up; mirrors the plugin service's own init timeout. */
    private static final long PREWARM_TIMEOUT_SECONDS = 30;

    private static final Key<DrydockStartupActivity> INSTANCE = new Key<>("DrydockStartupActivity");

    /**
     * The extension instance the platform creates; it carries no state —
     * the per-project state lives in {@link #instance(Project)}. Without a
     * no-arg constructor the extension factory cannot instantiate the
     * class and the plugin fails to load ("Cannot create extension").
     */
    public DrydockStartupActivity() {
        this.repositories = null;
        this.sessions = null;
        this.warmup = null;
    }

    private final ProjectRepositoryService repositories;
    private final DrydockSessionService sessions;
    private final CompletableFuture<Void> warmup;

    private DrydockStartupActivity(Project project) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        this.repositories = new ProjectRepositoryService(plugin, plugin.executor());
        this.sessions = new DrydockSessionService(
                plugin, AgentExecutionService.getInstance(project), repositories, plugin.executor());
        // The session service is the persistence half of the execution
        // events: a terminated process has to reach the catalog whether or
        // not any console tab is open to see it.
        project.getMessageBus().connect(project).subscribe(AgentExecutionListener.TOPIC, this.sessions);
        // The repository resolution chains the plugin service's
        // initialization internally; composing explicitly keeps both halves
        // of the pre-warm visible in one future. A null repository (default
        // or scratch project) is a successful completion, not a failure.
        this.warmup = plugin.initialization()
                .thenComposeAsync(unused -> repositories.projectRepository(project), plugin.executor())
                .<Void>thenApply(repository -> null);
    }

    /**
     * The activity instance for {@code project}, constructing it (and
     * starting the warm-up) on first use — the tool window can open before
     * post-startup activities run, and it must not miss the services.
     */
    private static DrydockStartupActivity instance(Project project) {
        DrydockStartupActivity existing = project.getUserData(INSTANCE);
        if (existing != null) {
            return existing;
        }
        synchronized (DrydockStartupActivity.class) {
            existing = project.getUserData(INSTANCE);
            if (existing != null) {
                return existing;
            }
            DrydockStartupActivity created = new DrydockStartupActivity(project);
            project.putUserData(INSTANCE, created);
            return created;
        }
    }

    /** The project's repository identity service. */
    public static ProjectRepositoryService repositories(Project project) {
        return instance(project).repositories;
    }

    /** The project's session orchestrator. */
    public static DrydockSessionService sessions(Project project) {
        return instance(project).sessions;
    }

    /** Completes when the plugin init and the project's repository are warm. */
    public static CompletableFuture<Void> warmup(Project project) {
        return instance(project).warmup;
    }

    @Override
    public void runActivity(@NotNull Project project) {
        DrydockStartupActivity started = instance(project);
        // Visible progress for the warm-up (AC12): a cancellable background
        // task in the status bar; every exit path in awaitWarmup clears it.
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading Drydock sessions…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                started.awaitWarmup();
                // <repo> · <branch> for the surfaces that name this window on
                // the EDT, where git cannot be asked.
                ProjectIdentity.resolve(project);
            }
        });
    }

    private void awaitWarmup() {
        try {
            warmup.get(PREWARM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.log(Level.WARNING, "Drydock session warm-up failed: " + cause);
        } catch (TimeoutException e) {
            LOG.log(Level.WARNING,
                    "Drydock session warm-up did not complete within " + PREWARM_TIMEOUT_SECONDS + "s");
        }
    }
}
