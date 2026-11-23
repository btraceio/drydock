package app.drydock.babysit;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.babysit.BabysitStateFile.DetachedEngagement;
import app.drydock.babysit.BabysitStateFile.Owner;
import app.drydock.babysit.BabysitWatcher.RunOutcome;
import app.drydock.git.GhCiRerunService;
import app.drydock.git.GitStatusService;
import app.drydock.git.PrMonitorRead;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The detached babysitting watcher process: {@code BabysitWatcherMain
 * <baseDir> <generation>}, started by the plugin at IDE quit through {@link
 * BabysitWatcherProcess}. It needs only the headless core and the JDK.
 *
 * <ol>
 *   <li>Takes the watcher lock ({@link BabysitLocks#watcherLockFile}) for its
 *       whole life, waiting while an older watcher still finishes a run --
 *       for as long as its own generation stays current, at most {@link
 *       #OWNER_WAIT}.</li>
 *   <li>Claims the state file: records itself as the owner (pid and process
 *       start) only while the detachment is its generation.</li>
 *   <li>Runs {@link BabysitWatcher} every {@link BabysitWatcher#PERIOD_MS}
 *       until it answers stop, sleeping in one-second slices that check the
 *       generation, so a re-attach stops an idle watcher within a second.</li>
 *   <li>On the way out drains the drop boxes once more, clears its own owner
 *       marker, releases the lock.</li>
 * </ol>
 *
 * <p>Headless runs: the provider's {@link
 * AgentProvider#buildHeadlessWriteCommand} through {@code ProcessRunner},
 * in the babysitter's worktree, stdin from {@code /dev/null}, at most {@link
 * #RUN_TIMEOUT}, output (the first {@link #MAX_RUN_OUTPUT} bytes of each
 * stream) to the run's log beside the deltas. The environment is the one the
 * plugin gave the watcher: the IDE's, with the login shell's PATH; {@code gh}
 * and {@code git} authenticate from it and from their own config files, as
 * no login shell runs.</p>
 */
public final class BabysitWatcherMain {

    private static final Logger LOG = System.getLogger(BabysitWatcherMain.class.getName());

    /** One headless babysitter run: a rebase, a fix, a push and a log read fit; a hang does not. */
    static final Duration RUN_TIMEOUT = Duration.ofMinutes(20);

    /** Bytes of each output stream of a headless run kept in its log. */
    static final int MAX_RUN_OUTPUT = 256 * 1024;

    /** How long a new watcher waits for an older one to finish its run (a run plus its gh and git margins). */
    static final Duration OWNER_WAIT = Duration.ofMinutes(25);

    private static final Duration READ_WAIT = Duration.ofSeconds(45);
    private static final Duration RERUN_WAIT = Duration.ofSeconds(45);

    private BabysitWatcherMain() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: BabysitWatcherMain <baseDir> <generation>");
            System.exit(2);
        }
        int code;
        try {
            code = run(Path.of(args[0]), UUID.fromString(args[1]));
        } catch (RuntimeException | Error e) {
            LOG.log(Level.ERROR, "The babysitting watcher failed", e);
            code = 1;
        }
        System.exit(code);
    }

    private static int run(Path baseDir, UUID generation) {
        BabysitStateFile stateFile = new BabysitStateFile(BabysitStateFile.defaultFile(baseDir));
        Optional<BabysitLocks.Held> lock = awaitWatcherLock(baseDir, stateFile, generation);
        if (lock.isEmpty()) {
            return 0;
        }
        try (BabysitLocks.Held held = lock.get();
                PrMonitorRead prs = new PrMonitorRead();
                GitStatusService git = new GitStatusService();
                GhCiRerunService gh = new GhCiRerunService()) {
            ProcessHandle self = ProcessHandle.current();
            Owner owner = new Owner(self.pid(), self.info().startInstant().orElseGet(Instant::now));
            try {
                if (!stateFile.update(c -> c.current(generation) ? c.withOwner(Optional.of(owner)) : c)
                        .current(generation)) {
                    LOG.log(Level.INFO, "Detachment " + generation + " is no longer current; nothing to watch");
                    return 0;
                }
            } catch (BabysitStateFileException e) {
                LOG.log(Level.ERROR, "Could not claim the babysitting state file", e);
                return 1;
            }
            LOG.log(Level.INFO, "Babysitting watcher " + owner.pid() + " watches detachment " + generation);
            Map<AgentKind, AgentProvider> providers = providers();
            BabysitWatcher watcher = new BabysitWatcher(stateFile, baseDir, generation,
                    (root, repository, pr) -> await(prs.read(root, repository, pr), READ_WAIT)
                            .orElseGet(() -> new PrSnapshot.Failed("The pull request read did not complete")),
                    git::remoteBranchTipBlocking,
                    (engagement, prompt, log) -> headless(providers, baseDir, engagement, prompt, log),
                    new BabysitWatcher.Reruns() {
                        @Override
                        public GhCiRerunService.RunRead readRun(Path root, Optional<String> repository, long runId) {
                            return await(gh.readRun(root, repository, runId), RERUN_WAIT).orElseGet(() ->
                                    new GhCiRerunService.RunUnreadable("the run read did not complete"));
                        }

                        @Override
                        public GhCiRerunService.Outcome rerun(Path root, Optional<String> repository, long runId) {
                            return await(gh.rerunFailedJobs(root, repository, runId), RERUN_WAIT).orElseGet(() ->
                                    new GhCiRerunService.Unknown("the rerun request did not complete"));
                        }
                    },
                    System::currentTimeMillis, BabysitMonitorRules.BACKOFF_INITIAL_MS);
            try {
                if (sleep(watcher, BabysitWatcher.FIRST_TICK_MS)) {
                    while (watcher.tick() && sleep(watcher, BabysitWatcher.PERIOD_MS)) {
                        // next poll
                    }
                }
            } finally {
                watcher.drainAll();
                try {
                    stateFile.update(c -> c.owner().filter(owner::equals).isPresent() ? c.withOwner(Optional.empty()) : c);
                } catch (BabysitStateFileException e) {
                    LOG.log(Level.WARNING, "Could not clear the watcher's owner marker; the plugin ignores a stale one", e);
                }
            }
            LOG.log(Level.INFO, "Babysitting watcher " + owner.pid() + " stops");
            return 0;
        }
    }

    /** The watcher lock, once free; empty when the generation stopped being current or the wait ran out. */
    private static Optional<BabysitLocks.Held> awaitWatcherLock(Path baseDir, BabysitStateFile stateFile,
                                                                UUID generation) {
        long deadline = System.nanoTime() + OWNER_WAIT.toNanos();
        while (true) {
            try {
                Optional<BabysitLocks.Held> lock = BabysitLocks.tryLock(BabysitLocks.watcherLockFile(baseDir));
                if (lock.isPresent()) {
                    return lock;
                }
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Could not take the babysitting watcher lock", e);
                return Optional.empty();
            }
            if (!stateFile.read().contents().current(generation)) {
                return Optional.empty();
            }
            if (System.nanoTime() > deadline) {
                LOG.log(Level.WARNING, "Another babysitting watcher kept the lock for " + OWNER_WAIT + "; giving up");
                return Optional.empty();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** Sleeps {@code millis} in one-second slices; false as soon as the watcher must stop. */
    private static boolean sleep(BabysitWatcher watcher, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (watcher.stopRequested()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !watcher.stopRequested();
    }

    /** Every provider on the class path, by kind, never {@code init}-ed; headless commands may probe CLI capabilities. */
    private static Map<AgentKind, AgentProvider> providers() {
        Map<AgentKind, AgentProvider> providers = new EnumMap<>(AgentKind.class);
        for (AgentProvider provider : ServiceLoader.load(AgentProvider.class, BabysitWatcherMain.class.getClassLoader())) {
            providers.putIfAbsent(provider.kind(), provider);
        }
        return providers;
    }

    static RunOutcome headless(Map<AgentKind, AgentProvider> providers, Path baseDir, DetachedEngagement engagement,
                               String prompt, Path log) {
        Optional<AgentProvider> provider = AgentKind.fromPersisted(engagement.agentKind()).map(providers::get);
        if (provider.isEmpty()) {
            return new RunOutcome.Unsupported("no provider for agent kind " + engagement.agentKind());
        }
        if (!Files.isDirectory(engagement.worktree())) {
            return new RunOutcome.Failed("the babysitter's worktree " + engagement.worktree() + " is gone");
        }
        Optional<List<String>> command = provider.get().buildHeadlessWriteCommand(engagement.worktree(),
                BabysitDelta.directory(baseDir), engagement.agentSessionId().orElseThrow(), prompt);
        if (command.isEmpty()) {
            return new RunOutcome.Unsupported(provider.get().displayName() + " has no headless write command available"
                    + " (no executable: " + provider.get().describeSearched()
                    + ", or the installed CLI lacks a required capability / its capability probe failed — see the watcher log)");
        }
        StringBuilder out = new StringBuilder("$ ").append(String.join(" ", command.get())).append('\n');
        RunOutcome outcome;
        try {
            ProcessResult result = ProcessRunner.runTruncating(command.get(),
                    new ProcessRunner.Options(engagement.worktree(), RUN_TIMEOUT, true, Map.of()), MAX_RUN_OUTPUT);
            out.append("exit ").append(result.exitCode()).append("\n--- stdout\n").append(result.stdout())
                    .append("\n--- stderr\n").append(result.stderr()).append('\n');
            outcome = new RunOutcome.Completed(result.exitCode());
        } catch (ProcessTimeoutException e) {
            out.append("timed out after ").append(RUN_TIMEOUT).append(" and killed\n");
            outcome = new RunOutcome.TimedOut();
        } catch (IOException e) {
            out.append("could not run: ").append(e.getMessage()).append('\n');
            outcome = new RunOutcome.Failed(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("interrupted\n");
            outcome = new RunOutcome.Failed("interrupted");
        }
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the headless run log " + log, e);
        }
        return outcome;
    }

    private static <T> Optional<T> await(CompletableFuture<T> future, Duration wait) {
        try {
            return Optional.ofNullable(future.get(wait.toNanos(), TimeUnit.NANOSECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.DEBUG, () -> "A watcher step did not complete: " + e);
            return Optional.empty();
        }
    }
}
