package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real bundled stager on temporary trees with hand-built archives. */
class RemoteWorkflowStageHelperTest {

    private static final Path HELPER = Path.of("src/main/resources/drydock/remote/workflow_stage.py")
            .toAbsolutePath();
    private static final String NS = "drydock/inst1/sessions/s1";

    @TempDir
    Path tmp;
    Path home;
    Path ns;
    Path pending;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectory(tmp.resolve("home"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        ns = home.resolve(NS);
        pending = ns.resolve("pending/p1");
        privateDirs(pending);
    }

    private static void privateDirs(Path dir) throws Exception {
        Path cur = dir.getParent().getParent().getParent().getParent().getParent().getParent();
        for (Path p : cur.relativize(dir)) {
            cur = cur.resolve(p);
            if (!Files.exists(cur)) {
                Files.createDirectory(cur, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            }
        }
    }

    // ---- archive builders -------------------------------------------------------------------

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String file(String path, byte[] body, boolean exec, String declaredSha, long declaredSize)
            throws Exception {
        return "{\"path\":\"" + path + "\",\"type\":\"file\",\"sha256\":\"" + declaredSha + "\",\"size\":"
                + declaredSize + ",\"executable\":" + exec + "}";
    }

    private static String file(String path, byte[] body, boolean exec) throws Exception {
        return file(path, body, exec, sha(body), body.length);
    }

    private static String dir(String path) {
        return "{\"path\":\"" + path + "\",\"type\":\"dir\"}";
    }

    private static String link(String path, String target) {
        return "{\"path\":\"" + path + "\",\"type\":\"link\",\"target\":\"" + target + "\"}";
    }

    private static byte[] zip(List<String> manifest, Map<String, byte[]> members) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(".drydock-manifest.json"));
            z.write(("{\"version\":1,\"entries\":[" + String.join(",", manifest) + "]}")
                    .getBytes(StandardCharsets.UTF_8));
            for (var m : members.entrySet()) {
                z.putNextEntry(new ZipEntry(m.getKey()));
                z.write(m.getValue());
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> members(Object... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (byte[]) kv[i + 1]);
        }
        return m;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private ProcessResult stage(byte[] archive) throws Exception {
        Path a = pending.resolve("a.zip");
        Files.write(a, archive);
        Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rw-------"));
        return runStage();
    }

    private ProcessResult runStage() throws Exception {
        return ProcessRunner.run(List.of("python3", HELPER.toString(), home.toString(), NS, "p1", "a.zip",
                "final1"), new ProcessRunner.Options(null, Duration.ofSeconds(60), true, Map.of()));
    }

    private void assertRejected(ProcessResult r, String code) throws Exception {
        assertEquals(1, r.exitCode(), r.stdout() + r.stderr());
        assertTrue(r.stdout().contains("\"code\": \"" + code + "\""), r.stdout() + r.stderr());
        assertFalse(Files.exists(ns.resolve("workflows/final1"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(pending.resolve("a.zip")), "failed archive is removed");
        try (var s = Files.list(pending)) {
            assertEquals(0, s.count(), "no partial tree left behind");
        }
    }

    private void assertAccepted(ProcessResult r) {
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        assertTrue(r.stdout().contains("\"ok\": true"), r.stdout());
    }

    // ---- behaviour --------------------------------------------------------------------------

    @Test
    void publishesLayoutLinksAndExecutableBitsAtomically() throws Exception {
        byte[] run = b("#!/bin/sh\n"), doc = b("hello");
        ProcessResult r = stage(zip(List.of(dir("skill"), file("skill/run.sh", run, true),
                file("skill/doc.md", doc, false), link("skill/alias", "doc.md"), link("up", "skill")),
                members("skill/run.sh", run, "skill/doc.md", doc)));
        assertAccepted(r);
        Path out = ns.resolve("workflows/final1");
        assertEquals("hello", Files.readString(out.resolve("skill/doc.md")));
        assertEquals("r-x------", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                out.resolve("skill/run.sh"))));
        assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                out.resolve("skill/doc.md"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out)));
        assertEquals("doc.md", Files.readSymbolicLink(out.resolve("skill/alias")).toString());
        assertFalse(Files.exists(pending.resolve("a.zip")));
        assertTrue(r.stdout().contains("\"entries\": 5"), r.stdout());
    }

    @Test
    void traversalAbsoluteSpecialAndUnknownEntriesAreRejectedWithoutOutsideWrites() throws Exception {
        Path outside = tmp.resolve("evil");
        for (String bad : new String[] {"../evil", "/abs", "a/../../evil", ".git/config", "a\\\\b"}) {
            privateDirs(pending);
            byte[] body = b("x");
            assertRejected(stage(zip(List.of(file(bad, body, false)), members(bad, body))), "bad-path");
        }
        assertFalse(Files.exists(outside));
        assertRejected(stage(zip(List.of(), members("stray", b("x")))), "unknown-entry");
        assertRejected(stage(zip(List.of("{\"path\":\"p\",\"type\":\"fifo\"}"), members())), "unknown-entry");
        assertRejected(stage(zip(List.of(dir("d")), members("d/", new byte[0]))), "special-entry");
        assertRejected(stage(zip(List.of(link("l", "../../etc")), members())), "link-escape");
        assertRejected(stage(zip(List.of(link("l", "/etc")), members())), "link-escape");
        assertRejected(stage(zip(List.of(link("l", "nowhere")), members())), "link-escape");
        assertRejected(stage(zip(List.of(link("a", "b"), link("b", "a")), members())), "link-depth");
        // an entry below a link would write through it
        assertRejected(stage(zip(List.of(link("l", "."), file("l/x", b("x"), false)),
                members("l/x", b("x")))), "parent-not-directory");
        assertRejected(stage(zip(List.of(link("l", "d"), dir("d"), file("l/x", b("x"), false)),
                members("l/x", b("x")))), "parent-not-directory");
    }

    @Test
    void duplicateEntriesAreRejected() throws Exception {
        byte[] x = b("x");
        assertRejected(stage(zip(List.of(file("a", x, false), file("a", x, false)), members("a", x))),
                "duplicate-entry");
        byte[] raw = zip(List.of(file("aa.txt", x, false), file("ab.txt", x, false)),
                members("aa.txt", x, "ab.txt", x));
        String patched = new String(raw, StandardCharsets.ISO_8859_1).replace("ab.txt", "aa.txt");
        privateDirs(pending);
        assertRejected(stage(patched.getBytes(StandardCharsets.ISO_8859_1)), "duplicate-entry");
    }

    @Test
    void hashSizeAndZipBombMismatchesNeverPublish() throws Exception {
        byte[] x = b("x");
        assertRejected(stage(zip(List.of(file("a", x, false, sha(b("y")), 1)), members("a", x))),
                "hash-mismatch");
        // header claims 1 byte, stream inflates to 9 MiB: stopped at the declared size, not trusted
        byte[] big = new byte[9 << 20];
        assertRejected(stage(zip(List.of(file("a", x, false)), members("a", big))), "too-large");
        // aggregate over 32 MiB across individually legal 8 MiB files
        List<String> m = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            m.add(file("f" + i, x, false, sha(x), 8 << 20));
        }
        assertRejected(stage(zip(m, members())), "too-large");
    }

    @Test
    void exactCeilingsPassAndLimitPlusOneFails() throws Exception {
        byte[] eight = new byte[8 << 20];
        assertAccepted(stage(zip(List.of(file("big", eight, false)), members("big", eight))));
        List<String> m = new ArrayList<>();
        Map<String, byte[]> mem = new LinkedHashMap<>();
        for (int i = 0; i < 2048; i++) {
            m.add(file("f" + i, new byte[0], false));
            mem.put("f" + i, new byte[0]);
        }
        reset();
        assertAccepted(stage(zip(m, mem)));
        m.add(file("extra", new byte[0], false));
        mem.put("extra", new byte[0]);
        reset();
        assertRejected(stage(zip(m, mem)), "too-many-entries");
        String deep32 = "d/".repeat(31) + "f", deep33 = "d/".repeat(32) + "f";
        reset();
        assertAccepted(stage(zip(deepManifest(31, deep32), members(deep32, b("x")))));
        reset();
        assertRejected(stage(zip(deepManifest(32, deep33), members(deep33, b("x")))), "bad-path");
        reset();
        assertRejected(stage(zip(List.of(file("big", new byte[(8 << 20) + 1], false)),
                members("big", new byte[(8 << 20) + 1]))), "manifest-malformed");
        // wire cap: exactly 40 MiB reaches the ZIP parser, +1 is refused by size first
        Path a = pending.resolve("a.zip");
        for (long size : new long[] {40L << 20, (40L << 20) + 1}) {
            reset();
            try (RandomAccessFile f = new RandomAccessFile(a.toFile(), "rw")) {
                f.setLength(size);
            }
            Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rw-------"));
            assertRejected(runStage(), size == 40L << 20 ? "archive-malformed" : "archive-too-large");
        }
    }

    private static List<String> deepManifest(int dirs, String leaf) throws Exception {
        List<String> m = new ArrayList<>();
        for (int i = 1; i <= dirs; i++) {
            m.add(dir("d/".repeat(i - 1) + "d"));
        }
        m.add(file(leaf, b("x"), false));
        return m;
    }

    private static void rmRf(Path p) throws Exception {
        ProcessResult r = ProcessRunner.run(List.of("rm", "-rf", p.toString()),
                new ProcessRunner.Options(null, Duration.ofSeconds(30), true, Map.of()));
        assertEquals(0, r.exitCode(), r.stderr());
    }

    private void reset() throws Exception {
        for (Path p : new Path[] {ns.resolve("workflows/final1")}) {
            if (Files.exists(p)) {
                rmRf(p);
            }
        }
        Files.deleteIfExists(pending.resolve("a.zip"));
    }

    @Test
    void symlinkSwappedParentsAndWrongPermissionsAreRefused() throws Exception {
        // 0700 and ours: only O_NOFOLLOW (not the mode check) can refuse the link
        Path outside = Files.createDirectory(tmp.resolve("outside"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        byte[] x = b("x");
        byte[] ok = zip(List.of(file("a", x, false)), members("a", x));
        // pending directory replaced by a link to somewhere else
        rmRf(ns.resolve("pending"));
        Files.createSymbolicLink(ns.resolve("pending"), outside);
        Files.write(outside.resolve("a.zip"), ok);
        ProcessResult r = runStage();
        assertEquals(1, r.exitCode());
        assertTrue(r.stdout().contains("namespace-unsafe"), r.stdout());
        try (var s = Files.list(outside)) {
            assertEquals(List.of("a.zip"), s.map(p -> p.getFileName().toString()).toList());
        }
        // a namespace ancestor replaced by a link
        rmRf(home.resolve("drydock"));
        Files.createSymbolicLink(home.resolve("drydock"), outside);
        assertTrue(runStage().stdout().contains("namespace-unsafe"));
        assertFalse(Files.exists(outside.resolve("inst1")));
        Files.delete(home.resolve("drydock"));
        // a pending directory replaced by a link, a group-readable archive/namespace/pending dir
        privateDirs(pending);
        rmRf(pending);
        Files.createSymbolicLink(pending, outside);
        assertTrue(runStage().stdout().contains("pending-unsafe"));
        Files.delete(pending);
        privateDirs(pending);
        Files.write(pending.resolve("a.zip"), ok);
        Files.setPosixFilePermissions(pending.resolve("a.zip"), PosixFilePermissions.fromString("rw-r-----"));
        assertTrue(runStage().stdout().contains("archive-unsafe"));
        Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTrue(runStage().stdout().contains("pending-unsafe"));
        Files.setPosixFilePermissions(ns, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTrue(runStage().stdout().contains("namespace-unsafe"));
        assertFalse(Files.exists(ns.resolve("workflows")));
    }

    @Test
    void existingDestinationIsNeverOverwritten() throws Exception {
        byte[] x = b("x");
        byte[] z = zip(List.of(file("a", x, false)), members("a", x));
        assertAccepted(stage(z));
        Path kept = ns.resolve("workflows/final1/a");
        ProcessResult r = stage(z);
        assertEquals(1, r.exitCode());
        assertTrue(r.stdout().contains("already-published"), r.stdout());
        assertEquals("x", Files.readString(kept));
    }
}
