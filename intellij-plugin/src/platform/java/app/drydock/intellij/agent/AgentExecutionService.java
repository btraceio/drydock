package app.drydock.intellij.agent;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * One window's view of the agents it shows.
 *
 * <p>The processes belong to {@link AgentExecutionRegistry}; this service
 * answers "what does <em>this</em> window show" and is the window's
 * {@link AgentConsoleHost} for the session service — starting an agent here
 * shows it here. It is also the Disposer parent of every console this window
 * builds, and its disposal is the backstop that stops whatever the window
 * still shows when no close path decided otherwise.</p>
 *
 * <p>Nothing here persists anything; the registry reports an agent's end to
 * the session service that started it.</p>
 */
public final class AgentExecutionService implements AgentConsoleHost, Disposable {

    private final Project project;

    public AgentExecutionService(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public static AgentExecutionService getInstance(Project project) {
        return project.getService(AgentExecutionService.class);
    }

    private static AgentExecutionRegistry registry() {
        return AgentExecutionRegistry.getInstance();
    }

    /** The live-or-just-ended execution for {@code sessionId}, if this window shows it. */
    public Optional<AgentExecution> execution(ManagedSessionId sessionId) {
        return registry().execution(sessionId)
                .filter(execution -> execution.host().map(project::equals).orElse(false));
    }

    /** Whether {@code sessionId} runs and this window shows it. */
    public boolean isRunning(ManagedSessionId sessionId) {
        return execution(sessionId).map(AgentExecution::isRunning).orElse(false);
    }

    /** Every session this window shows, running or stopped. */
    public List<ManagedSessionId> knownSessions() {
        return registry().shownIn(project);
    }

    /** Every running session this window shows (what the close confirmation asks about). */
    public List<ManagedSessionId> runningSessions() {
        return registry().runningShownIn(project);
    }

    /** Starts one agent, shown in this window. Off the EDT. */
    @Override
    public CompletableFuture<Void> startConsole(ManagedSessionId sessionId, Path workingDirectory,
                                                LaunchPlan plan) {
        // The end recorder, not the session service: the agent can outlive
        // this window, and the service would keep the window alive with it.
        return registry().start(sessionId, workingDirectory, plan, project,
                        DrydockStartupActivity.sessions(project).endRecorder())
                .thenApply(execution -> null);
    }

    @Override
    public void stopConsole(ManagedSessionId sessionId) {
        stop(sessionId);
    }

    @Override
    public void forgetConsole(ManagedSessionId sessionId) {
        registry().forget(sessionId);
    }

    /** Stops {@code sessionId}'s agent, wherever it is shown. */
    public void stop(ManagedSessionId sessionId) {
        registry().stop(sessionId);
    }

    /** Stops every agent this window shows. */
    public void stopAll() {
        runningSessions().forEach(this::stop);
    }

    @Override
    public void dispose() {
        registry().hostClosed(project);
    }
}
