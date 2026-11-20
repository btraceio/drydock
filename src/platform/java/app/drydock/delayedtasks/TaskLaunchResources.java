package app.drydock.delayedtasks;

import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.McpAccess;
import app.drydock.domain.ManagedSessionId;
import app.drydock.tasks.TaskGuidance;
import app.drydock.tasks.TaskRun;
import app.drydock.tasks.TaskRuns;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Resolves the resources one launch carries (T29a, D13/D17/P7a) into the
 * {@link LaunchResources} its create, resume or transfer context takes:
 * the extra directories this session's RETAINED run receipts name -- the
 * prerequisite worktrees a dependent session reads as context, kept after
 * catalog deletion (D13) -- plus the installed task guidance when the
 * launch's MCP access actually reaches a task-serving surface.
 *
 * <p>Two sources, one gate each. The directories come from the receipts:
 * every row bound to the session id contributes its consumer paths in
 * receipt order, duplicates collapsing to the first occurrence (the
 * record's own rule). Each directory is verified on disk, and a receipt
 * whose directory cannot be resolved is a VISIBLE refusal of the launch,
 * never a silent empty-resources one -- a dependent task must not start
 * without the prerequisite context its receipt promised. The guidance
 * comes from the installed {@link TaskGuidance} tree and is attached only
 * when the launch carries task-tool-capable MCP access: the surface behind
 * the minted credentials must actually serve the task tools (the caller's
 * seam, answered from that surface's own serving set) AND the access must
 * carry its credential file. A launch without either is an ordinary launch
 * and gets no guidance, however many agent processes it starts; guidance
 * that is merely not installed is absent, not refused -- nothing is
 * blocked by an install that has not happened yet.
 *
 * <p>An extra directory is a fact about what the launch may read, not a
 * permission: nothing here marks the directories read-only or attaches
 * any access claim to them -- what the agent may do inside them is the
 * harness's and the session mode's business.
 *
 * <p>Threading: resolution is blocking file I/O (receipt scan, directory
 * verification, installed-bytes check) and runs on the launch path's
 * background executor, never the FX application thread (AGENTS.md). It
 * reads through a fresh {@link TaskRuns} over the store root's one writer
 * lock -- never a parallel channel -- and writes nothing anywhere.
 */
public final class TaskLaunchResources {

    private final Path storeRoot;
    private final TaskGuidance guidance;
    private final BooleanSupplier taskToolsOnMcpSurface;

    /**
     * One resolver: receipts under {@code storeRoot}, the installed
     * guidance at {@code guidance}, and {@code taskToolsOnMcpSurface}
     * answering whether the MCP surface a credentialed launch reaches
     * serves the task tools -- that surface's own serving set, upstream of
     * every provider; no tool list is restated here.
     */
    public TaskLaunchResources(Path storeRoot, TaskGuidance guidance,
            BooleanSupplier taskToolsOnMcpSurface) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
        this.guidance = Objects.requireNonNull(guidance, "guidance");
        this.taskToolsOnMcpSurface = Objects.requireNonNull(taskToolsOnMcpSurface, "taskToolsOnMcpSurface");
    }

    /**
     * The resources {@code session}'s next launch carries, resolved before
     * its context is built: the retained receipts' directories plus the
     * installed guidance when {@code mcpAccess} reaches a task-serving
     * surface. Empty when the session carries neither -- an ordinary
     * launch, byte-identical to the pre-task one. A receipt directory that
     * cannot be resolved, or a receipt registry that cannot be read,
     * refuses visibly ({@link ResourcesRefusal}), preventing the launch.
     */
    public Optional<LaunchResources> resolve(ManagedSessionId session, Optional<McpAccess> mcpAccess) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(mcpAccess, "mcpAccess");
        List<Path> extraDirectories = receiptDirectories(session);
        Optional<Path> taskGuidance = installedGuidance(mcpAccess);
        return extraDirectories.isEmpty() && taskGuidance.isEmpty()
                ? Optional.empty()
                : Optional.of(new LaunchResources(extraDirectories, taskGuidance));
    }

    /**
     * The extra directories this session's retained receipts name: every
     * consumer of every row bound to the session id, in receipt order,
     * each verified to be an existing directory. The registry's own
     * empty-over-unreadable refusal is passed through as a visible
     * refusal: proof of absence is never guessed here either.
     */
    private List<Path> receiptDirectories(ManagedSessionId session) {
        List<TaskRun> rows;
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            rows = runs.bySession(session.toString());
        } catch (TaskRuns.TaskRunsException e) {
            throw refusal("The task receipts of session " + session + " could not be read on "
                    + storeRoot + ": " + e.getMessage(), e);
        }
        List<Path> directories = new ArrayList<>();
        for (TaskRun row : rows) {
            for (Path consumer : row.consumers()) {
                Path directory = consumer.toAbsolutePath().normalize();
                if (!Files.isDirectory(directory)) {
                    throw refusal("Task '" + row.task().text() + "' names the extra directory " + directory
                            + " for session " + session + ", but it cannot be resolved (missing or not"
                            + " a directory); refusing the launch rather than starting without its"
                            + " prerequisite context", null);
                }
                directories.add(directory);
            }
        }
        return directories;
    }

    /**
     * The installed guidance for this launch, or empty: attached only when
     * the launch's MCP access carries its credential file AND the surface
     * behind it serves the task tools -- guidanced sessions are those
     * actually receiving task-tool-capable MCP access, not merely any
     * agent process. The installed-bytes check runs last, so the two cheap
     * gates answer first.
     */
    private Optional<Path> installedGuidance(Optional<McpAccess> mcpAccess) {
        if (mcpAccess.flatMap(McpAccess::credentialFile).isEmpty()
                || !taskToolsOnMcpSurface.getAsBoolean()
                || !guidance.isInstalled()) {
            return Optional.empty();
        }
        return Optional.of(guidance.guidanceDirectory());
    }

    private static ResourcesRefusal refusal(String message, Throwable cause) {
        return new ResourcesRefusal(message, cause);
    }

    /** One visible resolution refusal: an unreadable receipt registry, a missing required directory. */
    public static final class ResourcesRefusal extends RuntimeException {
        public ResourcesRefusal(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
