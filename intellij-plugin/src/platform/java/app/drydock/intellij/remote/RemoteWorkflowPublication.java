package app.drydock.intellij.remote;

import app.drydock.domain.ManagedSessionId;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.MaterializedWorkflow.RemoteReference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.sdlc.WorkflowArchiveEncoder;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.BoxShell.BoxResult;
import app.drydock.workspace.CommandRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Publishes a captured {@link WorkflowBundle} to a workspace box: the {@link WorkflowArchiveEncoder} archive
 * is copied by {@link BoxShell#copy} into a fresh private remote pending directory, its SHA-256 is verified
 * remotely; the bundled {@code workflow_stage.py} then publishes it under
 * {@code <base>/<ns>/workflows/<finalName>} . Everything runs through the injected {@link CommandRunner}; no
 * credential ever appears in argv. Cancellation or a late completion after cancel removes whatever was
 * published, so nothing usable survives a cancelled call. The result carries remote path text only.
 */
public final class RemoteWorkflowPublication {
    static final Duration COPY_TIMEOUT = Duration.ofSeconds(60);
    static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(90);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final String ARCHIVE_NAME = "workflow.zip";
    private static final Pattern OK_TRUE = Pattern.compile("\"ok\"\\s*:\\s*true");
    private static final Pattern OK_FALSE = Pattern.compile("\"ok\"\\s*:\\s*false");
    private static final int SMALL_CAP = 64 * 1024;

    public sealed interface Outcome {
    }

    /** Published; {@code references} are destination-typed remote path text for the immutable tree. */
    public record Published(String remoteRoot, List<RemoteReference> references) implements Outcome {
    }

    public record Failed(String reason) implements Outcome {
    }

    /** Cancelled (before or after remote publication); the remote artifacts were removed. */
    public record Cancelled() implements Outcome {
    }

    private final CommandRunner runner;
    private final BoxShell shell;
    private final String stager;
    private final Duration overall;

    public RemoteWorkflowPublication(CommandRunner runner, String stagerRemotePath) {
        this(runner, stagerRemotePath, OVERALL_TIMEOUT);
    }

    RemoteWorkflowPublication(CommandRunner runner, String stagerRemotePath, Duration overall) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.shell = new BoxShell(runner);
        this.stager = Objects.requireNonNull(stagerRemotePath, "stagerRemotePath");
        this.overall = Objects.requireNonNull(overall, "overall");
    }

    public Outcome publish(String host, String installationId, ManagedSessionId session, String finalName,
                           WorkflowBundle bundle, Cancellation cancel) throws InterruptedException {
        for (String n : new String[] {installationId, finalName}) {
            if (n == null || !NAME.matcher(n).matches()) {
                return new Failed("unsafe remote name");
            }
        }
        WorkflowArchiveEncoder.Archive archive;
        try {
            archive = WorkflowArchiveEncoder.encode(bundle);
        } catch (IOException e) {
            return new Failed("archive refused: " + e.getMessage());
        }
        long deadline = System.nanoTime() + overall.toNanos();
        Path scratch = null;
        String pendingDir = null;
        String published = null;
        try {
            if (cancel.isCancelled()) {
                return new Cancelled();
            }
            String home = probe(host, List.of("sh", "-c", "printf %s \"$HOME\""), deadline);
            if (home == null || !home.startsWith("/")) {
                return new Failed("remote home unavailable");
            }
            String base = home + "/.local/share";
            String ns = "drydock/" + installationId + "/sessions/" + session;
            String pending = "p-" + UUID.randomUUID();
            String nsDir = base + "/" + ns;
            pendingDir = nsDir + "/pending/" + pending;
            // umask 077 makes every created directory owner-only, which the stager requires of the namespace.
            if (probe(host, List.of("sh", "-c", "umask 077; mkdir -p \"$1\" && mkdir \"$2\"", "sh",
                    nsDir + "/pending", pendingDir), deadline) == null) {
                return new Failed("remote pending directory unavailable");
            }
            scratch = Files.createTempDirectory("drydock-wf-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path local = Files.write(scratch.resolve(ARCHIVE_NAME), archive.bytes());
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
            if (cancel.isCancelled()) {
                return new Cancelled();
            }
            BoxResult copied = shell.copy(local, host, pendingDir + "/" + ARCHIVE_NAME, within(COPY_TIMEOUT, deadline));
            if (!(copied instanceof BoxResult.Ok)) {
                return new Failed("archive copy failed: " + copied.describe());
            }
            String sum = probe(host, List.of("sha256sum", "--", pendingDir + "/" + ARCHIVE_NAME), deadline);
            if (sum == null || !sum.startsWith(archive.sha256() + " ")) {
                return new Failed("archive hash mismatch after transfer");
            }
            if (cancel.isCancelled()) {
                return new Cancelled();
            }
            // from here a tree may exist remotely until the stager's outcome is known to be a clean refusal
            published = base + "/" + ns + "/workflows/" + finalName;
            ProcessResult staged = exec(host, List.of("python3", stager, base, ns, pending, ARCHIVE_NAME, finalName),
                    deadline, OVERALL_TIMEOUT);
            String out = staged == null || staged.exitCode() != 0 ? "" : staged.stdout();
            if (OK_FALSE.matcher(out).find()) {
                published = null;
                return new Failed("remote staging refused");
            }
            if (!OK_TRUE.matcher(out).find()) {
                return new Failed("remote staging outcome unknown");
            }
            if (cancel.isCancelled()) {
                return new Cancelled();
            }
            String root = published;
            published = null;
            return new Published(root, List.of(new RemoteReference(Role.RESOURCE, root)));
        } catch (IOException e) {
            return new Failed("local archive staging failed");
        } finally {
            cleanup(host, pendingDir, published);
            if (scratch != null) {
                deleteQuietly(scratch);
            }
        }
    }

    private String probe(String host, List<String> args, long deadline) throws InterruptedException {
        ProcessResult r = exec(host, args, deadline, BoxShell.SHORT_COMMAND_TIMEOUT);
        return r == null || r.exitCode() != 0 ? null : r.stdout().strip();
    }

    /** One bounded remote command; null when the budget is spent, the command timed out or it could not run. */
    private ProcessResult exec(String host, List<String> args, long deadline, Duration cap)
            throws InterruptedException {
        Duration limit = within(cap, deadline);
        if (limit.isZero()) {
            return null;
        }
        try {
            return runner.run(SshCommandBuilder.remoteCommand(host, args),
                    new ProcessRunner.Options(null, limit, true, Map.of()), SMALL_CAP);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Duration within(Duration cap, long deadline) {
        long left = deadline - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(Math.min(cap.toNanos(), left));
    }

    /** Best-effort, own 10 s budget: removes the pending directory and any tree published on a cancel or ambiguous outcome. */
    private void cleanup(String host, String pendingDir, String published) {
        if (pendingDir == null) {
            return;
        }
        List<String> rm = new ArrayList<>(List.of("rm", "-rf", "--", pendingDir));
        if (published != null) {
            rm.add(published);
        }
        try {
            runner.run(SshCommandBuilder.remoteCommand(host, rm),
                    new ProcessRunner.Options(null, BoxShell.SHORT_COMMAND_TIMEOUT, true, Map.of()), SMALL_CAP);
        } catch (IOException | RuntimeException e) {
            // unreachable remote cleanup stays debt for the session owner's startup sweep
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // scratch under the OS temp directory; nothing remote depends on it
        }
    }
}
