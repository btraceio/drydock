package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Receipt;
import app.drydock.workspace.RemoteEnrollmentEvidence.Target;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture /proc data served through a fake CommandRunner; no child is ever spawned. */
class RemoteEnrollmentProbeTest {

    private static final String HOST = "workspace-drydock-x";
    private static final String CONFIG = "/home/u/.drydock/s1/mcp.json";
    private static final String BOOT = "boot-1";
    private static final long PANE = 100, AGENT = 200, BRIDGE = 300;

    /** Remote world: path -> content (absent = ENOENT, "!denied" = EACCES); records the command words run. */
    private final Map<String, String> files = new HashMap<>();
    private final Map<String, ProcessResult> byCommand = new HashMap<>();
    private final List<String> ran = new ArrayList<>();
    private final CommandRunner runner = (argv, options, cap) -> {
        String joined = argv.get(argv.size() - 1);
        List<String> words = new ArrayList<>();
        for (String w : joined.split("' '")) {
            words.add(w.replace("'", ""));
        }
        ran.add(words.get(0));
        if (byCommand.containsKey(words.get(0))) {
            return byCommand.get(words.get(0));
        }
        if (words.get(0).equals("tmux")) {
            return new ProcessResult(0, PANE + "\n", "");
        }
        if (words.get(0).equals("cat") == false) {
            return new ProcessResult(0, "", "");
        }
        String content = files.get(words.get(1));
        if (content == null) {
            return new ProcessResult(1, "", "cat: " + words.get(1) + ": No such file or directory");
        }
        return content.equals("!denied") ? new ProcessResult(1, "", "cat: Permission denied")
                : new ProcessResult(0, content, "");
    };
    private final RemoteEnrollmentProbe probe = new RemoteEnrollmentProbe(runner);

    private static String stat(long ppid, long ticks) {
        return "1 (claude (x)) S " + ppid + " 1 1 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 " + ticks + " 0 0\n";
    }

    @BeforeEach
    void enrolledWorld() {
        files.put("/proc/sys/kernel/random/boot_id", BOOT + "\n");
        files.put("/proc/" + AGENT + "/stat", stat(PANE, 5000));
        files.put("/proc/" + BRIDGE + "/stat", stat(AGENT, 5100));
        files.put("/proc/" + AGENT + "/cmdline", cmdline("claude", "--mcp-config", CONFIG));
    }

    private static String cmdline(String... args) {
        return String.join("\0", args) + "\0";
    }

    private Target target(Receipt receipt, String nonce) {
        return new Target(HOST, "drydock-s1", AGENT, CONFIG, BRIDGE, nonce, receipt);
    }

    private static Receipt receipt(long ticks) {
        return new Receipt("n1", "conv-1", AGENT, BOOT, ticks);
    }

    private Verdict.Unverifiable unverifiable(Target t) throws InterruptedException {
        return assertInstanceOf(Verdict.Unverifiable.class, probe.probe(t));
    }

    @Test
    void enrolledProcessIsReadyOnlyWithMatchingReceiptAndLineage() throws Exception {
        assertInstanceOf(Verdict.Ready.class, probe.probe(target(receipt(5000), "n1")));
        assertTrue(unverifiable(target(null, null)).detail().contains("receipt"));
    }

    @Test
    void pidReuseAndStaleReceiptAreUnverifiableNotLegacy() throws Exception {
        assertTrue(unverifiable(target(receipt(4999), "n1")).detail().contains("PID reuse"));
        files.put("/proc/sys/kernel/random/boot_id", "boot-2\n");
        unverifiable(target(receipt(5000), "n1"));
    }

    @Test
    void inaccessibleOrMalformedEvidenceIsUnverifiableAndVanishedIsGone() throws Exception {
        files.put("/proc/" + AGENT + "/stat", "!denied");
        unverifiable(target(receipt(5000), "n1"));
        files.put("/proc/" + AGENT + "/stat", "garbage");
        unverifiable(target(receipt(5000), "n1"));
        byCommand.put("tmux", new ProcessResult(0, "abc\n", ""));
        unverifiable(target(receipt(5000), "n1"));
        byCommand.clear();
        files.remove("/proc/" + AGENT + "/stat");
        assertEquals(Reason.PROCESS_GONE,
                ((Verdict.Unavailable) probe.probe(target(receipt(5000), "n1"))).reason());
    }

    @Test
    void ancestryMustReachThePane() throws Exception {
        files.put("/proc/" + AGENT + "/stat", stat(999, 5000));
        files.put("/proc/999/stat", stat(1, 1));
        assertTrue(unverifiable(target(receipt(5000), "n1")).detail().contains("tmux pane"));
        files.put("/proc/999/stat", stat(PANE, 1));
        assertInstanceOf(Verdict.Ready.class, probe.probe(target(receipt(5000), "n1")));
    }

    @Test
    void cmdlineWithoutAnyMcpFlagIsLegacyProvenEvenWithoutReceipt() throws Exception {
        files.put("/proc/" + AGENT + "/cmdline", cmdline("claude", "--resume", "x"));
        assertEquals(Reason.LEGACY_PROVEN,
                ((Verdict.Unavailable) probe.probe(target(null, null))).reason());
        files.put("/proc/" + AGENT + "/cmdline", cmdline("claude", "--mcp-config=" + CONFIG));
        assertInstanceOf(Verdict.Ready.class, probe.probe(target(receipt(5000), "n1")));
    }

    @Test
    void malformedOrForeignCmdlineIsNeverLegacy() throws Exception {
        for (String bad : new String[]{"!denied", "", "claude\0--resume", cmdline("claude", "--mcp-config"),
                cmdline("claude", "--mcp-config", "/other.json"), cmdline("claude", "--mcp-config=")}) {
            files.put("/proc/" + AGENT + "/cmdline", bad);
            unverifiable(target(receipt(5000), "n1"));
        }
    }

    @Test
    void bridgeMustDescendFromAgentAndNonceMustMatch() throws Exception {
        files.put("/proc/" + BRIDGE + "/stat", stat(7, 5100));
        files.put("/proc/7/stat", stat(1, 1));
        assertTrue(unverifiable(target(receipt(5000), "n1")).detail().contains("agent"));
        files.put("/proc/" + BRIDGE + "/stat", stat(AGENT, 5100));
        assertTrue(unverifiable(target(receipt(5000), "other")).detail().contains("nonce"));
        unverifiable(target(receipt(5000), null));
    }

    @Test
    void preflightReportsEachToolSeparatelyAndOnlyRunsReadOnlyCommands() throws Exception {
        files.put("/proc/sys/kernel/random/boot_id", BOOT);
        byCommand.put("claude", new ProcessResult(0, "  --mcp-config <configs...>", ""));
        byCommand.put("git", new ProcessResult(127, "", "git: not found"));
        byCommand.put("python3", new ProcessResult(1, "", "ImportError"));
        byCommand.put("sshd", new ProcessResult(255, "", ""));
        var pre = probe.preflight(HOST);
        assertEquals(RemoteEnrollmentEvidence.Status.OK, pre.check("tmux").status());
        assertEquals(RemoteEnrollmentEvidence.Status.OK, pre.check("linux-proc").status());
        assertEquals(RemoteEnrollmentEvidence.Status.OK, pre.check("claude-mcp-config").status());
        assertEquals(RemoteEnrollmentEvidence.Status.MISSING, pre.check("git").status());
        assertEquals(RemoteEnrollmentEvidence.Status.FAILED, pre.check("python3").status());
        assertEquals(RemoteEnrollmentEvidence.Status.UNVERIFIABLE, pre.check("ssh-forwarding").status());
        byCommand.put("sshd", new ProcessResult(0, "allowtcpforwarding no\n", ""));
        assertEquals(RemoteEnrollmentEvidence.Status.FAILED, probe.preflight(HOST).check("ssh-forwarding").status());
        byCommand.put("sshd", new ProcessResult(0, "allowtcpforwarding yes\n", ""));
        assertEquals(RemoteEnrollmentEvidence.Status.OK, probe.preflight(HOST).check("ssh-forwarding").status());
        assertTrue(List.of("python3", "git", "tmux", "cat", "claude", "sshd").containsAll(ran), ran.toString());
    }

    @Test
    void runnerFailureIsUnverifiableNeverLegacyOrMissing() throws Exception {
        var broken = new RemoteEnrollmentProbe((argv, options, cap) -> {
            throw new IOException("boom");
        });
        assertInstanceOf(Verdict.Unverifiable.class, broken.probe(target(receipt(5000), "n1")));
        broken.preflight(HOST).checks().forEach(c ->
                assertEquals(RemoteEnrollmentEvidence.Status.UNVERIFIABLE, c.status(), c.name()));
    }
}
