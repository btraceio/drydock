package app.drydock.sdlc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowStagingLayoutTest {

    @TempDir
    Path tmp;

    private static WorkflowBundle.File file(String path, String text, boolean exec) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return new WorkflowBundle.File(path, b, WorkflowBundle.sha256Hex(b), exec);
    }

    private static WorkflowBundle sample() {
        return WorkflowBundle.of(List.of(
                new WorkflowBundle.Directory("skills/a"),
                file("skills/a/SKILL.md", "# skill\nsee ../shared/x.txt\n", false),
                file("skills/a/run.sh", "#!/bin/sh\necho hi\n", true),
                file("skills/shared/x.txt", "shared", false),
                new WorkflowBundle.Directory("skills/empty"),
                new WorkflowBundle.Link("skills/a/shared", "../shared")));
    }

    private static void cleanup(Path root) throws IOException {
        WorkflowStagingLayout.remove(root);
    }

    @Test
    void publishesPreservedTreeWithHashParityLinksAndExecutableBits() throws IOException {
        Path session = tmp.resolve("session");
        var out = WorkflowStagingLayout.publish(session, "inv1-abc", sample(), "generated instructions");
        try {
            for (WorkflowBundle.Entry e : sample().entries()) {
                Path p = out.tree().resolve(e.path());
                switch (e) {
                    case WorkflowBundle.File f -> {
                        assertEquals(f.sha256(), WorkflowBundle.sha256Hex(Files.readAllBytes(p)));
                        assertEquals(f.executable(), Files.isExecutable(p), f.path());
                        assertFalse(Files.isWritable(p), f.path());
                    }
                    case WorkflowBundle.Directory d -> assertTrue(Files.isDirectory(p));
                    case WorkflowBundle.Link l -> {
                        assertTrue(Files.isSymbolicLink(p));
                        assertEquals(Path.of(l.target()), Files.readSymbolicLink(p));
                    }
                }
            }
            assertEquals("shared", Files.readString(out.tree().resolve("skills/a/shared/x.txt")));
            assertTrue(Files.isDirectory(out.tree().resolve("skills/empty")));
            assertFalse(Files.isWritable(out.tree().resolve("skills/a")));
            assertFalse(Files.isWritable(out.root()));
            assertArrayEquals("generated instructions".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(out.instructionFile()));
            assertFalse(Files.exists(out.tree().resolve(WorkflowStagingLayout.INSTRUCTION_FILE)));
            assertEquals(out.root(), out.instructionFile().getParent());
            try (Stream<Path> s = Files.list(session)) {
                assertEquals(List.of(out.root()), s.toList());
            }
            assertFalse(Files.getPosixFilePermissions(session).contains(PosixFilePermission.GROUP_READ));
        } finally {
            cleanup(out.root());
        }
        assertFalse(Files.exists(out.root()));
    }

    @Test
    void refusesToOverwriteAnExistingNamespace() throws IOException {
        Path session = tmp.resolve("session");
        var first = WorkflowStagingLayout.publish(session, "inv", sample(), "one");
        try {
            assertThrows(FileAlreadyExistsException.class,
                    () -> WorkflowStagingLayout.publish(session, "inv", sample(), "two"));
            assertEquals("one", Files.readString(first.instructionFile()));
        } finally {
            cleanup(first.root());
        }
    }

    @Test
    void failureRemovesPartialOutputIncludingReadOnlyFiles() throws IOException {
        Path session = tmp.resolve("session");
        byte[] bad = "tampered".getBytes(StandardCharsets.UTF_8);
        var bundle = WorkflowBundle.of(List.of(
                file("a/ok.txt", "ok", false),
                new WorkflowBundle.File("a/bad.txt", bad, WorkflowBundle.sha256Hex("other".getBytes()), false)));
        assertThrows(IOException.class, () -> WorkflowStagingLayout.publish(session, "inv", bundle, "x"));
        try (Stream<Path> s = Files.list(session)) {
            assertEquals(List.of(), s.toList());
        }
    }

    @Test
    void rejectsUnsafeNamesOversizedBundlesAndSymlinkedSession() throws IOException {
        Path session = tmp.resolve("session");
        for (String n : new String[] {"", "../x", "a/b", ".hidden", "a b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkflowStagingLayout.publish(session, n, WorkflowBundle.empty(), "x"));
        }
        var tooBig = new WorkflowBundle(List.of(), 0, 0, 0, WorkflowLimits.AGGREGATE_FILE_BYTES + 1);
        assertThrows(IOException.class, () -> WorkflowStagingLayout.publish(session, "n", tooBig, "x"));
        Path real = Files.createDirectory(tmp.resolve("real"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), real);
        assertThrows(IOException.class, () -> WorkflowStagingLayout.publish(link, "n", WorkflowBundle.empty(), "x"));
        try (Stream<Path> s = Files.list(real)) {
            assertEquals(List.of(), s.toList());
        }
    }

    @Test
    void stagingCodeUsesNoProcessOrShellApis() throws IOException {
        String src = Files.readString(Path.of("src/main/java/app/drydock/sdlc/WorkflowStagingLayout.java"));
        for (String banned : new String[] {"ProcessBuilder", "Runtime.getRuntime", "ProcessRunner", "/bin/sh"}) {
            assertFalse(src.contains(banned), banned);
        }
    }
}
