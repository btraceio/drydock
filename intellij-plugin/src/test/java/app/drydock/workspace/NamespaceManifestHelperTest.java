package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Runs the real bundled manifest publisher against temporary HOME trees. */
class NamespaceManifestHelperTest {

    private static final Path HELPER = Path.of("src/main/resources/drydock/remote/namespace_manifest.py")
            .toAbsolutePath();
    private static final String TOKEN = "tok-SECRET-0123456789";

    @TempDir
    Path tmp;
    Path home;
    Path ns;
    Path pending;

    @BeforeEach
    void setUp() throws Exception {
        home = dir(tmp.resolve("home"));
        ns = home.resolve(".local/share/drydock/inst1/sessions/s1");
        Files.createDirectories(home.resolve(".local/share"));
        Path cur = home.resolve(".local/share");
        for (String p : new String[] {"drydock", "inst1", "sessions", "s1", "pending"}) {
            cur = dir(cur.resolve(p));
        }
        pending = ns.resolve("pending");
    }

    private static Path dir(Path p) throws Exception {
        return Files.createDirectory(p, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
    }

    private static String manifest(long generation, int port, String token) {
        return "{\"version\":1,\"installationId\":\"inst1\",\"managedSessionId\":\"s1\","
                + "\"agentSessionId\":\"agent-1\",\"launchNonce\":\"nonce\",\"epoch\":\"e1\","
                + "\"generation\":" + generation + ",\"bridgeProtocol\":1,\"port\":" + port
                + ",\"token\":\"" + token + "\"}";
    }

    private String published() throws Exception {
        return Files.readString(ns.resolve("connection.json"));
    }

    private Path pend(String name, String body, String perms) throws Exception {
        Path f = pending.resolve(name);
        Files.writeString(f, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(perms));
        return f;
    }

    private ProcessResult publish(String name, long generation) throws Exception {
        return run(List.of("python3", HELPER.toString(), home.toString(), "inst1", "s1", name,
                Long.toString(generation)));
    }

    private static ProcessResult run(List<String> argv) throws Exception {
        assertFalse(String.join(" ", argv).contains(TOKEN), "no secret in argv");
        return ProcessRunner.run(argv, new ProcessRunner.Options(null, Duration.ofSeconds(60), true, Map.of()));
    }

    private void assertRefused(ProcessResult r, String code, Path pendingFile) {
        assertEquals(1, r.exitCode(), r.stdout() + r.stderr());
        assertTrue(r.stdout().contains("\"code\": \"" + code + "\""), r.stdout() + r.stderr());
        assertFalse(r.stdout().contains(TOKEN) || r.stderr().contains(TOKEN), "secret never echoed");
        assertFalse(Files.exists(pendingFile, LinkOption.NOFOLLOW_LINKS), "pending file removed");
    }

    @Test
    void publishesPrivateManifestAndRemovesPending() throws Exception {
        Path f = pend("m1.json", manifest(1, 4100, TOKEN), "rw-------");
        ProcessResult r = publish("m1.json", 1);
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        assertTrue(r.stdout().contains("\"ok\": true"), r.stdout());
        assertFalse(r.stdout().contains(TOKEN) || r.stderr().contains(TOKEN));
        assertFalse(Files.exists(f), "pending removed");
        assertEquals(manifest(1, 4100, TOKEN), published());
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(ns.resolve("connection.json"))));
        try (var s = Files.list(ns)) {
            assertEquals(List.of("connection.json", "pending"), s.map(p -> p.getFileName().toString())
                    .sorted().toList(), "no temp file left");
        }
    }

    @Test
    void missingComponentsAreCreatedPrivate() throws Exception {
        try (var w = Files.walk(home.resolve(".local/share/drydock"))) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        ProcessResult r = publish("m1.json", 1);
        assertRefused(r, "pending-unreadable", ns.resolve("pending/m1.json"));
        for (Path p = pending; !p.equals(home.resolve(".local/share")); p = p.getParent()) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));
        }
    }

    @Test
    void badPendingFilesAreRefusedAndRemoved() throws Exception {
        Path wide = pend("wide.json", manifest(1, 4100, TOKEN), "rw-r-----");
        assertRefused(publish("wide.json", 1), "pending-unsafe", wide);
        Path big = pend("big.json", manifest(1, 4100, TOKEN) + " ".repeat(70 << 10), "rw-------");
        assertRefused(publish("big.json", 1), "manifest-too-large", big);
        Path target = tmp.resolve("outside.json");
        Files.writeString(target, manifest(1, 4100, TOKEN));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Path link = Files.createSymbolicLink(pending.resolve("link.json"), target);
        assertRefused(publish("link.json", 1), "pending-unreadable", link);
        assertTrue(Files.exists(target), "link target untouched");
        Path d = dir(pending.resolve("dir.json"));
        assertRefused(publish("dir.json", 1), "pending-unsafe", d);
        Path linked = pend("orig.json", manifest(1, 4100, TOKEN), "rw-------");
        Files.createLink(pending.resolve("hard.json"), linked);
        assertRefused(publish("hard.json", 1), "pending-unsafe", pending.resolve("hard.json"));
        assertFalse(Files.exists(ns.resolve("connection.json")), "nothing published from a bad pending file");
    }

    @Test
    void atomicReplaceFencesStaleAndEqualGenerations() throws Exception {
        pend("m1.json", manifest(1, 4100, TOKEN), "rw-------");
        assertEquals(0, publish("m1.json", 1).exitCode());
        Path inode = ns.resolve("connection.json");
        Object before = Files.getAttribute(inode, "unix:ino");
        pend("m2.json", manifest(2, 4200, "tok-two"), "rw-------");
        assertEquals(0, publish("m2.json", 2).exitCode());
        assertEquals(manifest(2, 4200, "tok-two"), published());
        assertFalse(before.equals(Files.getAttribute(inode, "unix:ino")), "replaced by rename, not rewritten");
        Path late = pend("late.json", manifest(1, 4300, "tok-late"), "rw-------");
        assertRefused(publish("late.json", 1), "stale-generation", late);
        Path same = pend("same.json", manifest(2, 4400, "tok-same"), "rw-------");
        assertRefused(publish("same.json", 2), "stale-generation", same);
        Path mismatch = pend("mis.json", manifest(5, 4500, "tok-mis"), "rw-------");
        assertRefused(publish("mis.json", 3), "stale-generation", mismatch);
        assertEquals(manifest(2, 4200, "tok-two"), published());
    }

    @Test
    void invalidSchemaIsRefusedRemovedAndNeverPublished() throws Exception {
        pend("seed.json", manifest(1, 4100, TOKEN), "rw-------");
        assertEquals(0, publish("seed.json", 1).exitCode());
        String good = manifest(2, 4200, "tok-two");
        String[][] bad = {
            {"miss", good.replace("\"port\":4200,", "")},
            {"extra", good.replace("}", ",\"x\":1}")},
            {"dup", good.replace("\"epoch\":\"e1\"", "\"epoch\":\"e1\",\"epoch\":\"e2\"")},
            {"deep", "[".repeat(5000)},
            {"foreign", good.replace("\"s1\"", "\"s2\"")},
            {"badport", good.replace("4200", "70000")},
            {"strport", good.replace("\"port\":4200", "\"port\":\"4200\"")},
            {"version", good.replace("\"version\":1", "\"version\":2")},
        };
        for (String[] b : bad) {
            Path f = pend(b[0] + ".json", b[1], "rw-------");
            assertRefused(publish(b[0] + ".json", 2), "schema-invalid", f);
        }
        assertEquals(manifest(1, 4100, TOKEN), published());
    }

    @Test
    void pendingOwnedBySomeoneElseIsRefusedAndRemoved() throws Exception {
        Path f = pend("m1.json", manifest(1, 4100, TOKEN), "rw-------");
        String script = "import os,sys\nsys.path.insert(0,sys.argv[1])\nimport namespace_manifest as m\n"
                + "orig=m.publish\n"
                + "def patched(*a):\n    os.getuid=lambda: os.geteuid()+1\n    return orig(*a)\n"
                + "m.publish=patched\nprint(m.main(['x']+sys.argv[2:]))\n";
        ProcessResult r = run(List.of("python3", "-c", script, HELPER.getParent().toString(), home.toString(),
                "inst1", "s1", "m1.json", "1"));
        assertTrue(r.stdout().contains("pending-unsafe"), r.stdout() + r.stderr());
        assertFalse(Files.exists(f), "pending removed");
    }

    @Test
    void unsafeNamespaceIsRefusedWithoutPublishing() throws Exception {
        Path f = pend("m1.json", manifest(1, 4100, TOKEN), "rw-------");
        Files.setPosixFilePermissions(ns, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertRefused(publish("m1.json", 1), "namespace-unsafe", Path.of("/nonexistent"));
        Files.setPosixFilePermissions(ns, PosixFilePermissions.fromString("rwx------"));

        Path sessions = ns.getParent();
        Path real = tmp.resolve("elsewhere");
        Files.move(sessions, real);
        Files.createSymbolicLink(sessions, real);
        assertRefused(publish("m1.json", 1), "namespace-unsafe", Path.of("/nonexistent"));
        assertFalse(Files.exists(real.resolve("s1/connection.json")), "nothing written through the link");
        assertTrue(Files.exists(real.resolve("s1/pending/m1.json")));
    }

    @Test
    void foreignOwnedHomeIsRefused() throws Exception {
        assumeFalse(System.getProperty("user.name").equals("root"));
        ProcessResult r = run(List.of("python3", HELPER.toString(), "/", "inst1", "s1", "m1.json", "1"));
        assertEquals(1, r.exitCode(), r.stdout() + r.stderr());
        assertTrue(r.stdout().contains("namespace-unsafe"), r.stdout());
    }

    @Test
    void secretsNeverInArgvOrEnvironment() throws Exception {
        pend("m1.json", manifest(1, 4100, TOKEN), "rw-------");
        List<String> argv = new ArrayList<>(List.of("python3", HELPER.toString(), home.toString(), "inst1",
                "s1", "m1.json", "1"));
        assertFalse(String.join(" ", argv).contains(TOKEN));
        assertEquals(0, run(argv).exitCode());
        String src = Files.readString(HELPER);
        assertFalse(src.contains("os.environ") || src.contains("stdin"), "helper reads neither env nor stdin");
    }
}
