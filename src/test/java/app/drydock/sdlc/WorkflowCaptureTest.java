package app.drydock.sdlc;

import app.drydock.sdlc.BoundedWorkflowCapture.CaptureException;
import app.drydock.sdlc.WorkflowPathSelector.Root;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowSelectionPlan.FileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded byte capture over temporary trees; nothing here claims immunity to a hostile concurrent writer. */
class WorkflowCaptureTest {

    @TempDir
    Path tmp;
    Path repo;

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectory(tmp.resolve("repo"));
    }

    private Path write(String rel, byte[] content) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.write(p, content);
    }

    private WorkflowSelectionPlan plan(String dir) throws Exception {
        return WorkflowPathSelector.select(repo, List.of(new Root(dir, true)));
    }

    private CaptureException refused(WorkflowSelectionPlan plan, BoundedWorkflowCapture.Probe probe) {
        return assertThrows(CaptureException.class, () -> BoundedWorkflowCapture.capture(repo, plan, probe));
    }

    @Test
    void capturesContentHashesExecBitsLinksAndDirectories() throws Exception {
        write("skill/a.txt", "alpha".getBytes());
        Path run = write("skill/run.sh", "#!/bin/sh\n".getBytes());
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createSymbolicLink(repo.resolve("skill/b.txt"), Path.of("a.txt"));
        WorkflowBundle b = BoundedWorkflowCapture.capture(repo, plan("skill"));
        assertEquals(2, b.fileCount());
        assertEquals(1, b.linkCount());
        assertEquals(1, b.directoryCount());
        WorkflowBundle.File a = b.entries().stream().filter(WorkflowBundle.File.class::isInstance)
                .map(WorkflowBundle.File.class::cast).filter(f -> f.path().equals("skill/a.txt")).findFirst().orElseThrow();
        assertEquals(WorkflowBundle.sha256Hex("alpha".getBytes()), a.sha256());
        assertFalse(a.executable());
        assertTrue(b.entries().stream().anyMatch(e -> e instanceof WorkflowBundle.File f && f.executable()));
        assertTrue(b.entries().contains(new WorkflowBundle.Link("skill/b.txt", "a.txt")));
    }

    @Test
    void fileCeilingExactAndPlusOneAndHugeBinaryStopsEarly() throws Exception {
        write("s/f.bin", new byte[(int) WorkflowLimits.FILE_BYTES]);
        assertEquals(WorkflowLimits.FILE_BYTES, BoundedWorkflowCapture.capture(repo, plan("s")).totalBytes());
        write("s/f.bin", new byte[(int) WorkflowLimits.FILE_BYTES + 1]);
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, refused(plan("s"), null).category());
        // Sparse 64 GiB file: the counted read must stop at the ceiling instead of materializing it.
        try (var ch = Files.newByteChannel(write("huge/h.bin", new byte[0]), StandardOpenOption.WRITE)) {
            ch.position(64L << 30);
            ch.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, refused(plan("huge"), null).category());
    }

    @Test
    void aggregateCeilingExactAndPlusOne() throws Exception {
        for (int i = 0; i < 4; i++) {
            write("s/f" + i, new byte[(int) WorkflowLimits.FILE_BYTES]);
        }
        assertEquals(WorkflowLimits.AGGREGATE_FILE_BYTES, BoundedWorkflowCapture.capture(repo, plan("s")).totalBytes());
        write("s/extra", new byte[1]);
        CaptureException e = refused(plan("s"), null);
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, e.category());
        assertFalse(e.retryable());
    }

    @Test
    void promptCeilingExactAndPlusOne() throws Exception {
        write("p.md", new byte[WorkflowLimits.PROMPT_BYTES]);
        assertEquals(WorkflowLimits.PROMPT_BYTES, BoundedWorkflowCapture.readPrompt(repo, "p.md").length);
        write("p.md", new byte[WorkflowLimits.PROMPT_BYTES + 1]);
        assertEquals(ProblemCategory.LIMIT_EXCEEDED,
                assertThrows(CaptureException.class, () -> BoundedWorkflowCapture.readPrompt(repo, "p.md")).category());
    }

    @Test
    void overlappingIncludesAreDedupedAndConflictsRejected() throws Exception {
        write("s/a.txt", "one".getBytes());
        WorkflowSelectionPlan overlap = WorkflowPathSelector.select(repo, List.of(new Root("s", true), new Root("s/a.txt", false)));
        assertEquals(1, BoundedWorkflowCapture.capture(repo, overlap).fileCount());
        WorkflowSelectionPlan dup = new WorkflowSelectionPlan(List.of(new FileEntry("s/a.txt", false), new FileEntry("s/a.txt", false)), Map.of());
        assertEquals(1, BoundedWorkflowCapture.capture(repo, dup).fileCount());
        WorkflowSelectionPlan conflict = new WorkflowSelectionPlan(List.of(new FileEntry("s/a.txt", false),
                new FileEntry("s/a.txt", true)), Map.of());
        assertEquals(ProblemCategory.SOURCE_UNREADABLE, refused(conflict, null).category());
        WorkflowSelectionPlan typed = new WorkflowSelectionPlan(List.of(new FileEntry("s/a.txt", false),
                new WorkflowSelectionPlan.DirectoryEntry("s/a.txt")), Map.of());
        assertFalse(refused(typed, null).retryable());
    }

    @Test
    void disappearanceAfterPlanIsChangedAndRealOpenErrorIsNeverMissing() throws Exception {
        Path f = write("s/a.txt", "one".getBytes());
        Path g = write("s/b.txt", "two".getBytes());
        WorkflowSelectionPlan plan = plan("s");
        assertTrue(refused(plan, path -> Files.delete(g)).sourceChanged());
        write("s/b.txt", "two".getBytes());
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("---------"));
        try {
            if (!Files.isReadable(f)) {
                CaptureException e = refused(plan, null);
                assertEquals(ProblemCategory.SOURCE_UNREADABLE, e.category());
                assertFalse(e.sourceChanged());
            }
        } finally {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void providerWithoutSecureDirectoryStreamCaptures() throws Exception {
        Path zip = tmp.resolve("fs-" + System.nanoTime() + ".zip");
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            Path root = fs.getPath("/repo");
            Files.createDirectories(root.resolve("s"));
            Files.writeString(root.resolve("s/a.txt"), "zip bytes");
            try (var ds = Files.newDirectoryStream(root)) {
                assertFalse(ds instanceof SecureDirectoryStream);
            }
            WorkflowSelectionPlan plan = WorkflowPathSelector.select(root, List.of(new Root("s", true)));
            WorkflowBundle b = BoundedWorkflowCapture.capture(root, plan);
            assertArrayEquals("zip bytes".getBytes(), ((WorkflowBundle.File) b.entries().stream()
                    .filter(WorkflowBundle.File.class::isInstance).findFirst().orElseThrow()).content());
        }
    }

    @Test
    void controlledChangesDuringCaptureRefuseRetryably() throws Exception {
        Path a = write("s/a.txt", "one".getBytes());
        write("s/b.txt", "two".getBytes());
        WorkflowSelectionPlan plan = plan("s");
        CaptureException size = refused(plan, p -> Files.write(a, "one-longer".getBytes()));
        assertTrue(size.sourceChanged() && size.retryable());
        write("s/a.txt", "one".getBytes());
        assertTrue(refused(plan, p -> Files.writeString(repo.resolve("s/new.txt"), "x")).retryable());
        Files.delete(repo.resolve("s/new.txt"));
        write("s/a.txt", "one".getBytes());
        CaptureException exec = refused(plan, p -> Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rwxr-xr-x")));
        assertTrue(exec.retryable());
        Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(2, BoundedWorkflowCapture.capture(repo, plan).fileCount());
    }

    @Test
    void rootAndParentIdentityChangesRefuse() throws Exception {
        write("s/a.txt", "one".getBytes());
        WorkflowSelectionPlan plan = plan("s");
        Path moved = tmp.resolve("moved");
        CaptureException swapped = refused(plan, p -> {
            Files.move(repo.resolve("s"), moved);
            Files.createSymbolicLink(repo.resolve("s"), moved);
        });
        assertTrue(swapped.retryable());
        Files.delete(repo.resolve("s"));
        Files.move(moved, repo.resolve("s"));
        WorkflowSelectionPlan rootPlan = plan("s");
        Path other = tmp.resolve("repo-old");
        CaptureException root = refused(rootPlan, p -> {
            Files.move(repo, other);
            Files.createDirectory(repo);
            Files.createDirectory(repo.resolve("s"));
            Files.writeString(repo.resolve("s/a.txt"), "one");
        });
        assertTrue(root.sourceChanged());
    }
}
