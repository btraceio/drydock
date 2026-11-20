package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.process.SshCommandBuilder;
import app.drydock.workspace.RemoteEnrollmentEvidence.Check;
import app.drydock.workspace.RemoteEnrollmentEvidence.Preflight;
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Receipt;
import app.drydock.workspace.RemoteEnrollmentEvidence.Status;
import app.drydock.workspace.RemoteEnrollmentEvidence.Target;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates from live Linux process evidence, read through {@link CommandRunner},
 * that a remote agent matches its launch receipt (INV-47): tmux pane ancestry
 * and PID start identity, the actual command line's MCP config, bridge
 * ancestry and the observed nonce. Only read-only commands run (INV-44). Unreadable,
 * malformed or stale evidence is {@link Verdict.Unverifiable}, never legacy.
 */
public final class RemoteEnrollmentProbe {

    private static final Duration TIMEOUT = BoxShell.SHORT_COMMAND_TIMEOUT;
    private static final int MAX_HOPS = 64;
    private static final String MCP_FLAG = "--mcp-config";

    private final CommandRunner runner;

    public RemoteEnrollmentProbe(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** Short-circuits {@link #probe} with the verdict that ended it. */
    private static final class Stop extends RuntimeException {
        final transient Verdict verdict;

        Stop(Verdict verdict) {
            super(null, null, false, false);
            this.verdict = verdict;
        }
    }

    /** One finished remote command; {@code ran} separates "ran and failed" from "could not run". */
    private record Out(boolean ran, int exit, String stdout, String stderr) {
        boolean ok() {
            return ran && exit == 0;
        }
    }

    public Verdict probe(Target t) throws InterruptedException {
        try {
            return validate(t);
        } catch (Stop stop) {
            return stop.verdict;
        }
    }

    private Verdict validate(Target t) throws InterruptedException {
        Set<Long> panes = panePids(t);
        long[] self = stat(t, t.agentPid());
        Receipt r = t.receipt();
        if (r != null) {
            Out boot = run(t.host(), "cat", "/proc/sys/kernel/random/boot_id");
            if (!boot.ok()) {
                throw unverifiable("boot id unreadable");
            }
            if (r.pid() != t.agentPid() || !r.bootId().equals(boot.stdout().trim())
                    || r.startTicks() != self[1]) {
                throw unverifiable("receipt does not match the live process (stale receipt or PID reuse)");
            }
        }
        requireAncestor(t, t.agentPid(), panes, "tmux pane");
        requireConfigured(t);
        if (r == null) {
            throw unverifiable("no launch receipt for the live process");
        }
        requireAncestor(t, t.bridgePid(), Set.of(t.agentPid()), "agent");
        if (!r.nonce().equals(t.observedNonce())) {
            throw unverifiable("observed nonce does not match the receipt");
        }
        return new Verdict.Ready();
    }

    /** Legacy only when the real command line is readable, NUL-terminated and carries no MCP flag at all. */
    private void requireConfigured(Target t) throws InterruptedException {
        Out out = run(t.host(), "cat", "/proc/" + t.agentPid() + "/cmdline");
        String raw = out.stdout();
        if (!out.ok() || raw.isEmpty() || !raw.endsWith("\0")) {
            throw unverifiable("command line unreadable or malformed");
        }
        String[] args = raw.substring(0, raw.length() - 1).split("\0", -1);
        boolean flagged = false;
        for (int i = 0; i < args.length; i++) {
            String value;
            if (args[i].equals(MCP_FLAG)) {
                value = i + 1 < args.length ? args[i + 1] : "";
            } else if (args[i].startsWith(MCP_FLAG + "=")) {
                value = args[i].substring(MCP_FLAG.length() + 1);
            } else {
                continue;
            }
            flagged = true;
            if (!value.equals(t.configPath())) {
                throw unverifiable("command line names another or empty MCP config");
            }
        }
        if (!flagged) {
            throw new Stop(new Verdict.Unavailable(Reason.LEGACY_PROVEN, "command line has no " + MCP_FLAG));
        }
    }

    /** Read-only tool probes, reported one by one; nothing is ever installed (INV-44). */
    public Preflight preflight(String host) throws InterruptedException {
        List<Check> checks = new ArrayList<>();
        checks.add(tool(host, "python3", "python3 stdlib", null, "python3", "-c", "import json, sys"));
        checks.add(tool(host, "git", "git", null, "git", "--version"));
        checks.add(tool(host, "tmux", "tmux", null, "tmux", "-V"));
        checks.add(tool(host, "linux-proc", "/proc", null, "cat", "/proc/sys/kernel/random/boot_id"));
        checks.add(tool(host, "claude-mcp-config", "claude " + MCP_FLAG, MCP_FLAG, "claude", "--help"));
        Out sshd = run(host, "sshd", "-T");
        String conf = sshd.stdout().toLowerCase();
        boolean on = conf.contains("allowtcpforwarding yes") || conf.contains("allowtcpforwarding remote");
        checks.add(!sshd.ok() ? new Check("ssh-forwarding", Status.UNVERIFIABLE, "sshd -T not readable here")
                : on ? new Check("ssh-forwarding", Status.OK, "remote forwarding allowed")
                : new Check("ssh-forwarding", Status.FAILED, "sshd does not allow remote forwarding"));
        return new Preflight(checks);
    }

    private Check tool(String host, String name, String label, String mustContain, String... argv)
            throws InterruptedException {
        Out out = run(host, argv);
        if (!out.ran()) {
            return new Check(name, Status.UNVERIFIABLE, label + " could not be checked");
        }
        if (out.exit() == 127 || (out.exit() != 0 && out.stderr().contains("No such file"))) {
            return new Check(name, Status.MISSING, label + " is missing");
        }
        boolean good = out.exit() == 0 && (mustContain == null || out.stdout().contains(mustContain));
        return new Check(name, good ? Status.OK : Status.FAILED, label + (good ? " is available" : " failed"));
    }

    private Set<Long> panePids(Target t) throws InterruptedException {
        Out out = run(t.host(), "tmux", "list-panes", "-t", t.tmuxSession(), "-F", "#{pane_pid}");
        if (!out.ok()) {
            throw unverifiable("tmux panes unreadable");
        }
        Set<Long> pids = new HashSet<>();
        for (String line : out.stdout().strip().split("\n")) {
            pids.add(parse(line.trim(), "pane pid"));
        }
        return pids;
    }

    /** {ppid, starttime} from {@code /proc/<pid>/stat}; a vanished pid ends the probe as PROCESS_GONE. */
    private long[] stat(Target t, long pid) throws InterruptedException {
        Out out = run(t.host(), "cat", "/proc/" + pid + "/stat");
        if (out.ran() && out.exit() != 0 && out.stderr().contains("No such file")) {
            throw new Stop(new Verdict.Unavailable(Reason.PROCESS_GONE, "process " + pid + " is gone"));
        }
        if (!out.ok()) {
            throw unverifiable("process " + pid + " is inaccessible");
        }
        String s = out.stdout();
        int close = s.lastIndexOf(')');
        if (close < 0) {
            throw unverifiable("malformed stat for process " + pid);
        }
        String[] f = s.substring(close + 1).trim().split("\\s+");
        if (f.length < 20) {
            throw unverifiable("malformed stat for process " + pid);
        }
        return new long[]{parse(f[1], "ppid"), parse(f[19], "start ticks")};
    }

    private void requireAncestor(Target t, long pid, Set<Long> ancestors, String what)
            throws InterruptedException {
        long current = pid;
        for (int hop = 0; hop < MAX_HOPS && current > 1; hop++) {
            if (ancestors.contains(current)) {
                return;
            }
            current = stat(t, current)[0];
        }
        if (ancestors.contains(current)) {
            return;
        }
        throw unverifiable("process " + pid + " is not under the " + what);
    }

    private Out run(String host, String... args) throws InterruptedException {
        List<String> argv = SshCommandBuilder.remoteCommand(host, List.of(args));
        try {
            ProcessResult r = runner.run(argv,
                    new ProcessRunner.Options(null, TIMEOUT, true, Map.of()), BoxShell.MAX_OUTPUT_BYTES);
            return new Out(r.exitCode() != 255, r.exitCode(), r.stdout(), r.stderr());
        } catch (ProcessTimeoutException | IOException e) {
            return new Out(false, -1, "", "");
        }
    }

    private static long parse(String s, String what) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw unverifiable("malformed " + what);
        }
    }

    private static Stop unverifiable(String detail) {
        return new Stop(new Verdict.Unverifiable(detail));
    }
}
