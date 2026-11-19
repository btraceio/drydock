package app.drydock.sdlc;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real encoder output fed to the real bundled stager in temporary trees. */
class WorkflowArchiveRoundTripTest {
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
        home = Files.createDirectory(tmp.resolve("home"), priv());
        Path cur = home;
        for (String p : (NS + "/pending/p1").split("/")) {
            cur = Files.createDirectory(cur.resolve(p), priv());
        }
        ns = home.resolve(NS);
        pending = ns.resolve("pending/p1");
    }

    private static java.nio.file.attribute.FileAttribute<?> priv() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    private static WorkflowBundle.File file(String path, byte[] body, boolean exec) {
        return new WorkflowBundle.File(path, body, WorkflowBundle.sha256Hex(body), exec);
    }

    private ProcessResult stage(byte[] archive) throws Exception {
        Path a = pending.resolve("a.zip");
        Files.write(a, archive);
        Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rw-------"));
        return ProcessRunner.run(List.of("python3", HELPER.toString(), home.toString(), NS, "p1", "a.zip",
                "final1"), new ProcessRunner.Options(null, Duration.ofSeconds(60), true, Map.of()));
    }

    @Test
    void layoutHashesLinksAndExecBitsSurviveTheRealHelper() throws Exception {
        byte[] skill = "# skill\n".getBytes(StandardCharsets.UTF_8);
        byte[] bin = new byte[]{0, 1, 2, (byte) 0xff, 10};
        WorkflowBundle bundle = WorkflowBundle.of(List.of(
                new WorkflowBundle.Directory("skill"),
                file("skill/SKILL.md", skill, false),
                new WorkflowBundle.Directory("skill/bin"),
                file("skill/bin/run.sh", bin, true),
                new WorkflowBundle.Link("skill/latest", "SKILL.md"),
                new WorkflowBundle.Link("skill/bin/up", "../SKILL.md")));
        WorkflowArchiveEncoder.Archive archive = WorkflowArchiveEncoder.encode(bundle);
        assertEquals(6, archive.entryCount());
        assertEquals(WorkflowBundle.sha256Hex(archive.bytes()), archive.sha256());

        ProcessResult r = stage(archive.bytes());
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        Path out = ns.resolve("workflows/final1");
        assertArrayEquals(skill, Files.readAllBytes(out.resolve("skill/SKILL.md")));
        assertArrayEquals(bin, Files.readAllBytes(out.resolve("skill/bin/run.sh")));
        assertTrue(Files.getPosixFilePermissions(out.resolve("skill/bin/run.sh"))
                .contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(Files.getPosixFilePermissions(out.resolve("skill/SKILL.md"))
                .contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(Files.isSymbolicLink(out.resolve("skill/latest")));
        assertEquals("SKILL.md", Files.readSymbolicLink(out.resolve("skill/latest")).toString());
        assertEquals("../SKILL.md", Files.readSymbolicLink(out.resolve("skill/bin/up")).toString());
        assertArrayEquals(skill, Files.readAllBytes(out.resolve("skill/bin/up")));
        assertFalse(Files.exists(out.resolve(WorkflowArchiveEncoder.MANIFEST_NAME), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void encodingIsIndependentOfTheFilesystemAndDeterministic() throws Exception {
        WorkflowBundle bundle = WorkflowBundle.of(List.of(file("a.txt", new byte[]{1}, false)));
        assertEquals(WorkflowArchiveEncoder.encode(bundle).sha256(), WorkflowArchiveEncoder.encode(bundle).sha256());
    }

    @Test
    void capturedBytesThatDisagreeWithTheirHashAreRefused() {
        WorkflowBundle bad = WorkflowBundle.of(List.of(
                new WorkflowBundle.File("a.txt", new byte[]{1}, "0".repeat(64), false)));
        assertThrows(WorkflowArchiveEncoder.ArchiveException.class, () -> WorkflowArchiveEncoder.encode(bad));
    }

    @Test
    void wireCapIsExactAndLimitPlusOneIsRefused() throws Exception {
        byte[] noise = new byte[200_000];
        new Random(7).nextBytes(noise);
        WorkflowBundle bundle = WorkflowBundle.of(List.of(file("n.bin", noise, false)));
        int size = WorkflowArchiveEncoder.encode(bundle).bytes().length;
        assertEquals(size, WorkflowArchiveEncoder.encode(bundle, size).bytes().length);
        assertThrows(WorkflowArchiveEncoder.ArchiveException.class,
                () -> WorkflowArchiveEncoder.encode(bundle, size - 1));
        assertEquals(40L << 20, WorkflowArchiveEncoder.WIRE_CAP_BYTES);
    }

    @Test
    void entryCountLimitPlusOneIsRefusedAndTheLimitRoundTrips() throws Exception {
        java.util.ArrayList<WorkflowBundle.Entry> es = new java.util.ArrayList<>();
        for (int i = 0; i < WorkflowLimits.BUNDLE_ENTRIES; i++) {
            es.add(new WorkflowBundle.Directory("d" + i));
        }
        ProcessResult r = stage(WorkflowArchiveEncoder.encode(WorkflowBundle.of(es)).bytes());
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        es.add(new WorkflowBundle.Directory("extra"));
        assertThrows(WorkflowArchiveEncoder.ArchiveException.class,
                () -> WorkflowArchiveEncoder.encode(WorkflowBundle.of(es)));
    }
}
