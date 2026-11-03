package app.drydock.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The T43a subprocess harness and the child-worker matrix in one class.
 * The parent side launches one child JVM per {@link #spawn}: an explicit
 * classpath, an argument list only (never a shell), every wait bounded
 * and a destroyForcibly on expiry -- ProcessRunner's contract, restated
 * here because the holders (an OS-lock owner, a plan driver killed
 * mid-flight) must stay alive on purpose and be killable mid-operation,
 * which a run-to-completion runner cannot express. The child side is the
 * {@link #main} worker entry over the real TaskStore, PlanStore and
 * TaskRuns on the temporary root the test passes; every outcome is one
 * plain stdout line (an OK/ERR line or a marker such as HELD) that the
 * parent reads from the child's redirected log. No home store, no
 * network, no platform classes.
 */
public final class TaskStoreProcessFixture {

    /** One launched child JVM: its redirected log, its bounded waits, its kill. */
    public static final class Child {
        private final Process process;
        private final Path log;

        private Child(Process process, Path log) {
            this.process = process;
            this.log = log;
        }

        /** True once the child printed the exact marker line, within the bound. */
        public boolean printed(String marker, long millis) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() - deadline < 0) {
                if (lines().contains(marker)) {
                    return true;
                }
                if (!process.isAlive()) {
                    return false;
                }
                Thread.sleep(50);
            }
            return false;
        }

        /** The child's merged stdout/stderr log lines so far. */
        public List<String> lines() throws IOException {
            return Files.isRegularFile(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        }

        /** Run to completion within the bound; expiry destroys the child and fails distinctly. */
        public int complete(String what, long millis) throws IOException, InterruptedException {
            if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError(what + " did not finish in " + millis + " ms: " + lines());
            }
            return process.exitValue();
        }

        /** Kills the child wherever it is (the crash scenario's lever), bounded. */
        public void kill(String what) throws InterruptedException {
            process.destroyForcibly();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError(what + " survived destroyForcibly");
            }
        }
    }

    private final String java;
    private final Path workDir;

    private TaskStoreProcessFixture(String java, Path workDir) {
        this.java = java;
        this.workDir = workDir;
    }

    /** One harness for this JVM's own java executable; null when none is locatable. */
    public static TaskStoreProcessFixture locate(Path workDir) {
        String java = ProcessHandle.current().info().command().orElse(null);
        return java == null ? null : new TaskStoreProcessFixture(java, workDir);
    }

    /** Launches one child JVM running this class's worker entry with workerArgs. */
    public Child spawn(String... workerArgs) throws IOException {
        // The classpath travels in an @argfile: as one inline argument it can
        // outgrow the per-argument cap, and inside the file's quoted string a
        // backslash is an escape (SharedProjectsFileTest's verified lesson).
        Path argFile = Files.createTempFile(workDir, "child", ".args");
        Files.writeString(argFile,
                "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n",
                StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("@" + argFile);
        command.add(TaskStoreProcessFixture.class.getName());
        command.addAll(List.of(workerArgs));
        Path log = Files.createTempFile(workDir, "child", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        return new Child(process, log);
    }

    /** The child JVM's entry: a worker mode, the store root, then the mode's own args. */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.out.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        switch (args[0]) {
            case "writer" -> writer(root, args[2], Integer.parseInt(args[3]));
            case "lock-holder" -> lockHolder(root, Long.parseLong(args[2]));
            case "task-op" -> taskOp(root);
            case "claim-holder" -> claimHolder(root, TaskId.of(args[2]));
            case "claim-attempt" -> claimAttempt(root, TaskId.of(args[2]));
            case "plan-driver" -> planDriver(root, TaskId.of(args[2]));
            default -> throw new IllegalArgumentException("unknown worker mode '" + args[0] + "'");
        }
    }

    private static void writer(Path root, String prefix, int count) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        try (TaskStore store = new TaskStore(root, TaskStore.NO_CONFLICTS)) {
            for (int i = 0; i < count; i++) {
                TaskDocument created = store.create(repository, "Two JVMs contend on " + prefix, List.of());
                store.update(created.id(),
                        new TaskFilePatch.Updates(null, TaskStatus.STARTED, null, null, null, null));
                store.appendNote(created.id(), "note from " + prefix + "-" + i);
                System.out.println("OK " + created.id().text());
            }
        }
    }

    private static void lockHolder(Path root, long millis) throws Exception {
        Path lockFile = root.resolveSibling(root.getFileName() + ".lock");
        Files.createDirectories(root.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            System.out.println("HELD");
            Thread.sleep(millis);
        }
    }

    private static void taskOp(Path root) throws Exception {
        try (TaskStore store = new TaskStore(root, TaskStore.NO_CONFLICTS, 800)) {
            TaskDocument created = store.create(RepositoryId.newId(), "must never be written", List.of());
            System.out.println("CREATED " + created.id().text());
        } catch (TaskStore.TaskStoreException e) {
            System.out.println("ERR " + e.getMessage());
        }
    }

    private static void claimHolder(Path root, TaskId plan) throws Exception {
        try (PlanStore store = new PlanStore(root)) {
            PlanStore.Claim claim = store.acquireClaim(plan).get(20, TimeUnit.SECONDS);
            System.out.println("CLAIMED");
            Thread.sleep(60_000);
            claim.close();
        }
    }

    private static void claimAttempt(Path root, TaskId plan) throws Exception {
        try (PlanStore store = new PlanStore(root)) {
            store.acquireClaim(plan).get(20, TimeUnit.SECONDS);
            System.out.println("CREATED-CLAIM");
        } catch (ExecutionException e) {
            System.out.println("ERR " + e.getCause().getMessage());
        }
    }

    private static void planDriver(Path root, TaskId plan) throws Exception {
        Path ownerCatalog = root.resolveSibling("owner-catalog.json");
        Files.writeString(ownerCatalog, "{}", StandardCharsets.UTF_8);
        try (PlanStore store = new PlanStore(root); TaskRuns runs = new TaskRuns(root)) {
            PlanStore.Claim claim = store.acquireClaim(plan).get(20, TimeUnit.SECONDS);
            runs.reserve(plan, "driver-session", root.resolveSibling("wt"), ownerCatalog, List.of());
            PlanStore.PlanRecord saved = store.save(claim, 0, runningPlan(plan), false)
                    .get(20, TimeUnit.SECONDS);
            System.out.println("SAVED rev=" + saved.revision() + " paused=" + saved.paused());
            Thread.sleep(60_000);
        }
    }

    private static RunningPlan runningPlan(TaskId target) {
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        selections.put(target, new PlanLaunch.Selection(AgentKind.CLAUDE,
                RepositoryId.of("11111111-1111-1111-1111-111111111111"), "drydock-plugin"));
        PlanLaunch launch = new PlanLaunch(target, List.of(1), selections, 7L, List.of("launch-a"), 0,
                new PlanLaunch.Origin("win-1", Path.of("/tmp/worktrees/driver")));
        return new RunningPlan(launch, List.of(new RunningPlan.StageState(1, List.of(target), false, false)),
                RunningPlan.Status.RUNNING);
    }
}
