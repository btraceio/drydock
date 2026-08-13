package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real repositories throughout (AGENTS.md: real subprocesses, no mocks) --
 * every case here is about what git actually does with deletions, binary
 * content and ignore rules, which a fake would only restate.
 */
class WorktreeTransplantTest {

    private final WorktreeTransplant transplant = new WorktreeTransplant();

    @Test
    void carriesTrackedEditsAcrossAndLeavesThemUncommitted(@TempDir Path dir) throws Exception {
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.writeString(source.resolve("a.txt"), "edited");
        Path destination = committedRepo(dir.resolve("dst"), "a.txt", "original");

        transplant.transplantBlocking(source, destination);

        assertEquals("edited", Files.readString(destination.resolve("a.txt")));
        assertTrue(gitCapture(destination, "status", "--porcelain").contains("a.txt"),
                "the change must land uncommitted so the review rail sees a diff");
    }

    @Test
    void carriesUntrackedFilesIncludingNestedOnes(@TempDir Path dir) throws Exception {
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.writeString(source.resolve("new.txt"), "brand new");
        Files.createDirectories(source.resolve("deep/nested"));
        Files.writeString(source.resolve("deep/nested/file.txt"), "nested");
        Path destination = committedRepo(dir.resolve("dst"), "a.txt", "original");

        transplant.transplantBlocking(source, destination);

        assertEquals("brand new", Files.readString(destination.resolve("new.txt")));
        assertEquals("nested", Files.readString(destination.resolve("deep/nested/file.txt")));
    }

    @Test
    void carriesDeletions(@TempDir Path dir) throws Exception {
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.delete(source.resolve("a.txt"));
        Path destination = committedRepo(dir.resolve("dst"), "a.txt", "original");

        transplant.transplantBlocking(source, destination);

        assertFalse(Files.exists(destination.resolve("a.txt")), "a deletion is part of the dirty state");
    }

    @Test
    void carriesBinaryContentByteForByte(@TempDir Path dir) throws Exception {
        byte[] bytes = {0, 1, 2, (byte) 0xFF, 0, 3, (byte) 0x80};
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.write(source.resolve("blob.bin"), bytes);
        Path destination = committedRepo(dir.resolve("dst"), "a.txt", "original");

        transplant.transplantBlocking(source, destination);

        assertArrayEquals(bytes, Files.readAllBytes(destination.resolve("blob.bin")));
    }

    @Test
    void carriesAnEditToATrackedBinaryFile(@TempDir Path dir) throws Exception {
        byte[] original = {0, 1, 2};
        byte[] edited = {(byte) 0xFF, (byte) 0xFE, 9, 9};
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.write(source.resolve("tracked.bin"), original);
        git(source, "add", "tracked.bin");
        commit(source, "add binary");
        Path destination = clone(source, dir.resolve("dst"));
        Files.write(source.resolve("tracked.bin"), edited);

        transplant.transplantBlocking(source, destination);

        assertArrayEquals(edited, Files.readAllBytes(destination.resolve("tracked.bin")));
    }

    @Test
    void carriesAnEditToATrackedTextFileWhoseBytesAreNotUtf8(@TempDir Path dir) throws Exception {
        // The case a binary-file test cannot reach. git classifies a file as
        // binary only if it finds a NUL byte, so a Latin-1 .properties is
        // TEXT: --binary does not base85 it, and its hunks are emitted raw.
        // Routing that through a UTF-8 String would replace every high byte
        // with U+FFFD and either corrupt the fork or produce a patch git apply
        // rejects.
        byte[] latin1 = "greeting=caf\u00e9 na\u00efve\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] edited = "greeting=caf\u00e9 na\u00efve r\u00e9sum\u00e9\n".getBytes(StandardCharsets.ISO_8859_1);
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.write(source.resolve("messages.properties"), latin1);
        git(source, "add", "messages.properties");
        commit(source, "add latin-1 properties");
        Path destination = clone(source, dir.resolve("dst"));
        Files.write(source.resolve("messages.properties"), edited);

        transplant.transplantBlocking(source, destination);

        assertArrayEquals(edited, Files.readAllBytes(destination.resolve("messages.properties")));
    }

    @Test
    void skipsIgnoredFiles(@TempDir Path dir) throws Exception {
        Path source = committedRepo(dir.resolve("src"), ".gitignore", "build/\n");
        Files.createDirectory(source.resolve("build"));
        Files.writeString(source.resolve("build/out.o"), "artifact");
        Path destination = committedRepo(dir.resolve("dst"), ".gitignore", "build/\n");

        transplant.transplantBlocking(source, destination);

        assertFalse(Files.exists(destination.resolve("build/out.o")),
                "an ignored build artifact is not work worth carrying");
    }

    @Test
    void aCleanSourceCarriesNothingAndSucceeds(@TempDir Path dir) throws Exception {
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Path destination = committedRepo(dir.resolve("dst"), "a.txt", "original");

        assertEquals(0, transplant.transplantBlocking(source, destination));
    }

    @Test
    void worksWhenTheSourceHasNoCommitsYet(@TempDir Path dir) throws Exception {
        // No HEAD to diff against: every file is untracked, so the whole tree
        // crosses as untracked rather than as a patch.
        Path source = Files.createDirectories(dir.resolve("src"));
        git(source, "init", "-b", "main");
        Files.writeString(source.resolve("first.txt"), "content");
        Path destination = Files.createDirectories(dir.resolve("dst"));
        git(destination, "init", "-b", "main");

        transplant.transplantBlocking(source, destination);

        assertEquals("content", Files.readString(destination.resolve("first.txt")));
    }

    @Test
    void reportsHowManyFilesItCarried(@TempDir Path dir) throws Exception {
        Path source = committedRepo(dir.resolve("src"), "a.txt", "original");
        Files.writeString(source.resolve("a.txt"), "edited");     // 1 tracked
        Files.writeString(source.resolve("new.txt"), "new");      // 1 untracked
        Path destination = committedRepo(dir.resolve("dst"), "a.txt", "original");

        assertEquals(2, transplant.transplantBlocking(source, destination));
    }

    // ---- fixtures -----------------------------------------------------------

    private static Path committedRepo(Path repo, String file, String content) throws Exception {
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve(file), content);
        git(repo, "add", file);
        commit(repo, "initial commit");
        return repo;
    }

    /** A second worktree of the same history, so a patch from one applies to the other. */
    private static Path clone(Path source, Path destination) throws Exception {
        Files.createDirectories(destination.getParent() == null ? destination : destination.getParent());
        runGit(List.of("git", "clone", source.toString(), destination.toString()));
        return destination;
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(Arrays.asList(args));
        runGit(command);
    }

    private static String gitCapture(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    private static void runGit(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + output);
        }
    }
}
