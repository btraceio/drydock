package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the bundled framing helper through ProcessRunner (bounded lifetime), stdin from an exact-bytes file. */
class BridgeFramingProtocolTest {

    private static final Path HELPER = Path.of("src/main/resources/drydock/remote/bridge_framing.py")
            .toAbsolutePath();
    private static final int MAX_LINE = 1 << 20;

    @TempDir
    Path tmp;

    private ProcessResult run(byte[] stdin) throws Exception {
        Path in = tmp.resolve("in-" + System.nanoTime());
        Files.write(in, stdin);
        return ProcessRunner.run(List.of("/bin/sh", "-c", "exec python3 \"$0\" --serve < \"$1\"",
                HELPER.toString(), in.toString()),
                new ProcessRunner.Options(null, Duration.ofSeconds(30), true, Map.of()));
    }

    private ProcessResult run(String stdin) throws Exception {
        return run(stdin.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> lines(ProcessResult r) {
        return r.stdout().isEmpty() ? List.of() : List.of(r.stdout().split("\n", -1)).subList(0,
                r.stdout().split("\n", -1).length - 1);
    }

    private static String req(String id, String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}\n";
    }

    private void assertRejected(String line) throws Exception {
        ProcessResult r = run(line + "\n" + req("7", "ok"));
        List<String> out = lines(r);
        assertEquals(2, out.size(), r.stdout());
        assertTrue(out.get(0).contains("\"id\":null") && out.get(0).contains("\"error\""), out.get(0));
        assertTrue(out.get(1).contains("\"id\":7") && out.get(1).contains("\"result\""), out.get(1));
        assertFalse(r.stdout().contains("\"method\":\"bad\""), "rejected line must not be dispatched");
    }

    @Test
    void multipleLinesAndIdTypesArePreserved() throws Exception {
        ProcessResult r = run(req("1", "a") + req("\"s-2\"", "b")
                + "{\"jsonrpc\":\"2.0\",\"method\":\"note\"}\n" + req("3", "c"));
        assertEquals(0, r.exitCode());
        List<String> out = lines(r);
        assertEquals(3, out.size(), r.stdout());
        assertTrue(out.get(0).contains("\"id\":1,"));
        assertTrue(out.get(1).contains("\"id\":\"s-2\""));
        assertTrue(out.get(2).contains("\"id\":3,"));
        assertFalse(r.stdout().contains("note"), "notification must stay silent");
        assertEquals("", r.stderr());
    }

    @Test
    void unicodeAndBinaryAreHandledWithoutStdoutDiagnostics() throws Exception {
        String uni = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"k\":\"é😀\\n\"}}\n";
        ProcessResult r = run(uni);
        assertEquals(1, lines(r).size());
        assertTrue(r.stdout().chars().allMatch(c -> c < 128), "output is ASCII-escaped");
        assertTrue(r.stdout().contains("\\ud83d\\ude00"));
        for (byte[] bad : new byte[][] {{(byte) 0xff, (byte) 0xfe, 0x00}, "{\"a\":\"\\ud800\"}".getBytes()}) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(bad);
            b.write('\n');
            b.write(req("7", "ok").getBytes());
            ProcessResult br = run(b.toByteArray());
            assertEquals(2, lines(br).size(), br.stdout());
            assertTrue(lines(br).get(0).contains("\"error\""));
            assertTrue(br.stderr().startsWith("drydock-bridge:"));
            assertFalse(br.stderr().contains("ud800"));
        }
    }

    @Test
    void inputLineCapIsExactAndOversizeIsDiscardedWithoutDispatch() throws Exception {
        String head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[\"";
        String tail = "\"]}";
        String exact = head + "a".repeat(MAX_LINE - head.length() - tail.length()) + tail;
        assertEquals(MAX_LINE, exact.length());
        ProcessResult ok = run(exact + "\n");
        assertTrue(lines(ok).get(0).contains("\"result\""), "exactly 1 MiB is accepted");
        String over = exact.replace("\"a", "\"aa");
        ProcessResult r = run(over + "\n" + req("7", "ok"));
        List<String> out = lines(r);
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("\"error\"") && out.get(0).contains("\"id\":null"));
        assertTrue(out.get(1).contains("\"id\":7"));
        ProcessResult noNl = run(over); // oversize with no newline at all
        assertEquals(1, lines(noNl).size());
        assertTrue(lines(noNl).get(0).contains("\"error\""));
    }

    @Test
    void truncatedFinalLineIsNotDispatched() throws Exception {
        ProcessResult r = run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\"");
        assertEquals(1, lines(r).size());
        assertTrue(lines(r).get(0).contains("\"error\""));
        assertFalse(r.stdout().contains("\"result\""));
    }

    @Test
    void malformedBatchDuplicateAndBadIdsAreRejected() throws Exception {
        assertRejected("{not json");
        assertRejected("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\"}]");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"bad\"}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\",\"params\":{\"a\":1,\"a\":2}}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"bad\"}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"bad\"}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"bad\"}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"bad\"}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\",\"params\":NaN}");
        assertRejected("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"bad\"}");
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}");
    }

    @Test
    void depthLimitIsExactAndStringBracketsDoNotCount() throws Exception {
        // object -> params array nesting: top object depth 1, so params may nest 31 more levels.
        String ok = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":" + "[".repeat(31) + "]".repeat(31) + "}";
        assertTrue(lines(run(ok + "\n")).get(0).contains("\"result\""));
        String deep = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\",\"params\":" + "[".repeat(32) + "]".repeat(32) + "}";
        assertRejected(deep);
        assertRejected("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\",\"params\":" + "[".repeat(100_000));
        String brackets = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[\"" + "[".repeat(100) + "\"]}";
        assertTrue(lines(run(brackets + "\n")).get(0).contains("\"result\""));
    }

    @Test
    void outputCapIsEnforcedAndKeepsTheRequestId() throws Exception {
        int overhead = "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":\"\"}".length();
        int exact = (4 << 20) - overhead;
        ProcessResult r = run(bigRequest(exact) + bigRequest(exact + 1) + req("6", "ok"));
        List<String> out = lines(r);
        assertEquals(3, out.size());
        assertEquals(4 << 20, out.get(0).length());
        assertTrue(out.get(1).contains("\"id\":5") && out.get(1).contains("\"error\"") && out.get(1).length() < 200);
        assertTrue(out.get(2).contains("\"id\":6"));
        assertTrue(r.stderr().contains("output cap"));
    }

    private static String bigRequest(int n) {
        return "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"test/big\",\"params\":{\"n\":" + n + "}}\n";
    }

    @Test
    void emptyInputExitsCleanly() throws Exception {
        ProcessResult r = run(new byte[0]);
        assertEquals(0, r.exitCode());
        assertEquals("", r.stdout());
    }
}
