package app.drydock.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real bundled launch wrapper against a fixture /proc and a probe command. */
class LaunchWrapperTest {

    private static final Path WRAPPER = Path.of("src/main/resources/drydock/remote/launch_wrapper.py")
            .toAbsolutePath();

    @TempDir
    Path tmp;
    Path proc;

    @BeforeEach
    void setUp() throws Exception {
        proc = tmp.resolve("proc");
        Files.createDirectories(proc.resolve("sys/kernel/random"));
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("sys/kernel/random/boot_id"), "boot-1234\n");
        // comm with spaces and ')' must not shift the starttime field (22nd).
        Files.writeString(proc.resolve("self/stat"),
                "77 (py (x) y) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 424242 23 24\n");
    }

    private record Run(int exit, String err) {}

    private Run run(Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", WRAPPER.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile());
        pb.environment().put("DRYDOCK_PROC_ROOT", proc.toString());
        pb.environment().putAll(env);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Path errFile = tmp.resolve("stderr");
        pb.redirectError(errFile.toFile());
        int exit = pb.start().waitFor();
        return new Run(exit, Files.readString(errFile));
    }

    private String[] probe(Path receipt, String nonce, String conv) {
        // The exec'd command records its own pid, argv and environment.
        return new String[]{receipt.toString(), nonce, conv, "--", "sh", "-c",
                "echo $$ > \"$0\"; env > \"$1\"", tmp.resolve("pid").toString(), tmp.resolve("env").toString(),
                "--literal"};
    }

    @Test
    void writesPrivateReceiptThenExecsSameProcess() throws Exception {
        Path receipt = tmp.resolve("receipt.json");
        Run r = run(Map.of(), probe(receipt, "n-1", "conv-abc"));
        assertEquals(0, r.exit(), r.err());

        String j = Files.readString(receipt);
        assertTrue(j.contains("\"nonce\": \"n-1\""), j);
        assertTrue(j.contains("\"conversationId\": \"conv-abc\""), j);
        assertTrue(j.contains("\"bootId\": \"boot-1234\""), j);
        assertTrue(j.contains("\"startTicks\": 424242}"), j);
        // exec keeps the pid: the receipt pid is the pid the agent command saw as $$.
        assertTrue(j.contains("\"pid\": " + Files.readString(tmp.resolve("pid")).trim() + ","), j);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(receipt)));
        try (var left = Files.list(tmp)) {
            assertFalse(left.anyMatch(p -> p.getFileName().toString().contains(".tmp")));
        }
    }

    @Test
    void refusesEmptyOrBlankConversationIdWithoutReceiptOrExec() throws Exception {
        for (String conv : new String[]{"", "  "}) {
            Path receipt = tmp.resolve("r.json");
            Run r = run(Map.of(), probe(receipt, "n-1", conv));
            assertEquals(2, r.exit());
            assertTrue(r.err().contains("empty conversation id"), r.err());
            assertFalse(Files.exists(receipt));
            assertFalse(Files.exists(tmp.resolve("pid")), "command must not run");
        }
    }

    @Test
    void refusesWhenProcessIdentityOrReceiptDirIsUnavailable() throws Exception {
        Files.delete(proc.resolve("sys/kernel/random/boot_id"));
        Run r = run(Map.of(), probe(tmp.resolve("r.json"), "n-1", "c"));
        assertEquals(2, r.exit());
        assertFalse(Files.exists(tmp.resolve("pid")));

        Files.writeString(proc.resolve("sys/kernel/random/boot_id"), "b\n");
        r = run(Map.of(), probe(tmp.resolve("missing-dir/r.json"), "n-1", "c"));
        assertEquals(2, r.exit());
        assertFalse(Files.exists(tmp.resolve("pid")));
    }

    @Test
    void addsNothingToCommandArgvOrEnvironment() throws Exception {
        Path receipt = tmp.resolve("receipt.json");
        Run r = run(Map.of("DRYDOCK_TEST_MARK", "keep"), probe(receipt, "nonce-xyz", "conv-abc"));
        assertEquals(0, r.exit(), r.err());
        String env = Files.readString(tmp.resolve("env"));
        assertTrue(env.contains("DRYDOCK_TEST_MARK=keep"));
        // nonce / conversation id travel only in the wrapper's own argv and the private receipt.
        assertFalse(env.contains("nonce-xyz"));
        assertFalse(env.contains("conv-abc"));
        assertFalse(env.contains(receipt.toString()));
    }

    @Test
    void usesRealProcOnLinux() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("/proc/self/stat")));
        Path receipt = tmp.resolve("real.json");
        Run r = run(Map.of("DRYDOCK_PROC_ROOT", "/proc"), probe(receipt, "n", "c"));
        assertEquals(0, r.exit(), r.err());
        String j = Files.readString(receipt);
        assertTrue(j.contains("\"bootId\": \"" + Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim()
                + "\""), j);
        assertFalse(j.contains("\"startTicks\": 424242"), j);
    }
}
