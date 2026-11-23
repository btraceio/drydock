package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real bundled review-data reader on temporary trees. */
class RemoteReviewDataHelperTest {

    private static final Path HELPER = Path.of("src/main/resources/drydock/remote/review_data.py")
            .toAbsolutePath();

    @TempDir
    Path tmp;
    Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(tmp.resolve("root"));
    }

    private ProcessResult run(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", HELPER.toString()));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(cmd, new ProcessRunner.Options(null, Duration.ofSeconds(60), true, Map.of()));
    }

    private ProcessResult read(String rel, String... extra) throws Exception {
        List<String> a = new ArrayList<>(List.of("read", root.toString(), rel));
        a.addAll(List.of(extra));
        return run(a.toArray(String[]::new));
    }

    private ProcessResult excerpt(String rel, String line, String ctx) throws Exception {
        return run("excerpt", root.toString(), rel, line, ctx);
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String field(ProcessResult r, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"[^\"]*\"|[^,}]*)").matcher(r.stdout());
        assertTrue(m.find(), r.stdout() + r.stderr());
        return m.group(1).replace("\"", "");
    }

    private static byte[] b64(ProcessResult r) {
        return Base64.getDecoder().decode(field(r, "b64"));
    }

    private void assertCode(ProcessResult r, String code) {
        assertEquals(1, r.exitCode(), r.stdout() + r.stderr());
        assertEquals("{\"ok\":false,\"code\":\"" + code + "\"}", r.stdout().trim(), r.stderr());
    }

    private Path write(String rel, byte[] body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, body);
        return p;
    }

    @Test
    void binaryAndCrlfBytesRoundTripWithWorkingByteHash() throws Exception {
        byte[] body = {0, 'a', '\r', '\n', (byte) 0xff, (byte) 0xfe, '\r', '\n'};
        write("d/bin.dat", body);
        ProcessResult r = read("d/bin.dat");
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        assertArrayEquals(body, b64(r));
        assertEquals(sha(body), field(r, "sha256"));
        assertEquals("true", field(r, "binary"));
        assertEquals(String.valueOf(body.length), field(r, "size"));
        assertEquals(0, read("d/bin.dat", sha(body)).exitCode());
    }

    @Test
    void excerptKeepsCrlfAndRefusesBinaryNonUtf8AndMissingLine() throws Exception {
        write("t.txt", "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8));
        ProcessResult r = excerpt("t.txt", "2", "1");
        assertEquals(0, r.exitCode(), r.stdout());
        assertEquals("one\r\ntwo\r\nthree\r\n", new String(b64(r), StandardCharsets.UTF_8));
        assertEquals("1", field(r, "start"));
        assertEquals("3", field(r, "end"));
        assertEquals("two\r\n", new String(b64(excerpt("t.txt", "2", "0")), StandardCharsets.UTF_8));
        assertCode(excerpt("t.txt", "4", "0"), "line-out-of-range");
        write("bin", new byte[]{'a', 0, 'b'});
        assertCode(excerpt("bin", "1", "0"), "binary");
        write("latin", new byte[]{(byte) 0xe9, '\n'});
        assertCode(excerpt("latin", "1", "0"), "not-text");
    }

    @Test
    void symlinkTraversalAndAdminPathsAreRefused() throws Exception {
        Path outside = Files.writeString(tmp.resolve("secret"), "secret");
        write("ok.txt", new byte[]{'x'});
        Files.createSymbolicLink(root.resolve("leaf"), outside);
        Files.createDirectory(tmp.resolve("outdir"));
        Files.writeString(tmp.resolve("outdir/f"), "secret");
        Files.createSymbolicLink(root.resolve("dirlink"), tmp.resolve("outdir"));
        Files.createSymbolicLink(root.resolve("rootlink"), root);
        write(".git/config", new byte[]{'c'});
        write("sub/.git/HEAD", new byte[]{'h'});
        assertCode(read("leaf"), "unavailable");
        assertCode(read("dirlink/f"), "unavailable");
        assertCode(read("../secret"), "unsafe-path");
        assertCode(read("sub/../ok.txt"), "unsafe-path");
        assertCode(read("./ok.txt"), "unsafe-path");
        assertCode(read("/etc/passwd"), "unsafe-path");
        assertCode(read(root.resolve("ok.txt").toString()), "unsafe-path");
        assertCode(read("a//b"), "unsafe-path");
        assertCode(read("a\\b"), "unsafe-path");
        assertCode(read(".git/config"), "unsafe-path");
        assertCode(read("sub/.git/HEAD"), "unsafe-path");
        assertCode(read("sub"), "not-regular");
        assertCode(run("read", root.resolve("rootlink").toString(), "ok.txt"), "root-unavailable");
        assertCode(run("read", "relative", "ok.txt"), "bad-arguments");
    }

    @Test
    void gitComponentsAreRefusedCaseInsensitively() throws Exception {
        write(".git/config", new byte[]{'c'});
        write("sub/.git/HEAD", new byte[]{'h'});
        for (String rel : new String[]{".GIT/config", ".GiT/config", "sub/.GIT/HEAD", "Sub/.gIt/HEAD", ".GIT"}) {
            assertCode(read(rel), "unsafe-path");
            assertCode(excerpt(rel, "1", "0"), "unsafe-path");
        }
    }

    @Test
    void pathOptionsAndUnknownOperationsAreLiteralNotInterpreted() throws Exception {
        write("-x", new byte[]{'o'});
        write("--help", new byte[]{'h'});
        assertEquals("o", new String(b64(read("-x"))));
        assertEquals("h", new String(b64(read("--help"))));
        assertCode(run("--help"), "bad-arguments");
        assertCode(run("cat", root.toString(), "-x"), "bad-arguments");
        assertCode(run("read", root.toString()), "bad-arguments");
        assertCode(run("excerpt", root.toString(), "-x", "1", "0", "0", "0", "0"), "bad-arguments");
        assertCode(excerpt("-x", "+1", "0"), "bad-arguments");
        assertCode(excerpt("-x", "0", "0"), "bad-arguments");
        assertCode(excerpt("-x", "1", "-1"), "bad-arguments");
    }

    @Test
    void concurrentEditAndReplacedRootAreStale() throws Exception {
        Path p = write("f.txt", "before".getBytes(StandardCharsets.UTF_8));
        String pinned = sha("before".getBytes(StandardCharsets.UTF_8));
        String rootId = field(read("f.txt"), "root_id");
        assertEquals(0, read("f.txt", pinned, rootId).exitCode());
        Files.writeString(p, "after!");
        assertCode(read("f.txt", pinned), "stale");
        assertCode(run("excerpt", root.toString(), "f.txt", "1", "0", pinned), "stale");
        Path moved = tmp.resolve("moved");
        Files.move(root, moved);
        Files.createDirectory(root);
        write("f.txt", "before".getBytes(StandardCharsets.UTF_8));
        assertCode(read("f.txt", pinned, rootId), "root-replaced");
    }

    @Test
    void fileSizeCeilingsAreExactAndPlusOne() throws Exception {
        write("max", new byte[8 << 20]);
        write("max1", new byte[(8 << 20) + 1]);
        assertEquals(0, read("max").exitCode());
        assertCode(read("max1"), "too-large");
        byte[] text = new byte[4 << 20];
        for (int i = 0; i < text.length; i += 2) {
            text[i] = 'a';
            text[i + 1] = '\n';
        }
        write("ext", text);
        assertEquals("a\n", new String(b64(excerpt("ext", "1", "0")), StandardCharsets.UTF_8));
        write("ext1", Arrays.copyOf(text, (4 << 20) + 1));
        assertCode(excerpt("ext1", "1", "0"), "too-large");
    }

    @Test
    void excerptWindowAndPathCeilingsAreExactAndPlusOne() throws Exception {
        byte[] w = new byte[64 << 10];
        Arrays.fill(w, (byte) 'a');
        write("w", w);
        byte[] w1 = new byte[(64 << 10) + 1];
        Arrays.fill(w1, (byte) 'a');
        write("w1", w1);
        assertEquals(64 << 10, b64(excerpt("w", "1", "0")).length);
        assertCode(excerpt("w1", "1", "0"), "too-large");
        write("c", "x\n".repeat(60).getBytes(StandardCharsets.UTF_8));
        assertEquals(0, excerpt("c", "30", "20").exitCode());
        assertCode(excerpt("c", "30", "21"), "bad-arguments");
        String a = "a".repeat(255), b = "b".repeat(100), leaf = "c".repeat(155);
        String exact = a + "/" + a + "/" + a + "/" + b + "/" + leaf;
        assertEquals(1024, exact.getBytes(StandardCharsets.UTF_8).length);
        // The fixture cannot be created under a long temp root (PATH_MAX); the path-length gate
        // runs before any open, so the exact-length path passes it and ends as plain unavailable.
        assertCode(read(exact), "unavailable");
        assertCode(read(exact + "c"), "unsafe-path");
        String deep31 = "d/".repeat(31) + "f";
        write(deep31, new byte[]{'k'});
        assertEquals(0, read(deep31).exitCode());
        assertCode(read("d/" + deep31), "unsafe-path");
    }
}
