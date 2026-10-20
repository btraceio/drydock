package app.drydock.search;

import app.drydock.search.SessionSearchService.FileHit;
import app.drydock.search.SessionSearchService.FileMatches;
import app.drydock.search.SessionSearchService.TextMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary directories/git repositories,
 * in the style of {@code GitStatusServiceTest}: both the git-backed path
 * ({@code git ls-files} / {@code git grep}) and the bounded-walk fallback
 * for plain directories.
 */
class SessionSearchServiceTest {

    private final SessionSearchService service = new SessionSearchService();

    @Test
    void textSearchGroupsMatchesByFileWithLineNumbers(@TempDir Path repo) throws Exception {
        initRepo(repo);
        writeFile(repo, "SessionStore.java", "class SessionStore {\n  // session cache\n  int size;\n}\n");
        writeFile(repo, "README.md", "This manages a session.\n");
        writeFile(repo, "Other.java", "class Other {}\n");
        runGit(repo, "add", ".");

        List<FileMatches> results = service.searchText(repo, "session").get();

        assertEquals(2, results.size());
        FileMatches store = results.stream()
                .filter(f -> f.relativePath().toString().equals("SessionStore.java")).findFirst().orElseThrow();
        assertEquals(2, store.matches().size());
        TextMatch first = store.matches().get(0);
        assertEquals(1, first.lineNumber());
        assertTrue(first.lineText().contains("SessionStore"));
        assertTrue(first.matchEnd() > first.matchStart());
    }

    @Test
    void fileSearchFiltersByBasenameCaseInsensitively(@TempDir Path repo) throws Exception {
        initRepo(repo);
        writeFile(repo, "SessionStore.java", "x\n");
        writeFile(repo, "theme.css", "y\n");
        Files.createDirectories(repo.resolve("src"));
        writeFile(repo, "src/SessionCodec.java", "z\n");
        runGit(repo, "add", ".");

        List<FileHit> hits = service.searchFiles(repo, "session").get();

        assertEquals(2, hits.size());
        assertTrue(hits.stream().anyMatch(h -> h.relativePath().toString().equals("src/SessionCodec.java")));
    }

    @Test
    void plainDirectoryFallsBackToWalk(@TempDir Path dir) throws IOException {
        writeFile(dir, "notes.txt", "find the needle here\n");
        Files.createDirectories(dir.resolve("node_modules"));
        writeFile(dir, "node_modules/skipped.txt", "needle\n");

        List<FileMatches> results = service.searchText(dir, "needle").join();

        assertEquals(1, results.size());
        assertEquals("notes.txt", results.get(0).relativePath().toString());
        assertEquals(1, results.get(0).matches().get(0).lineNumber());
    }

    @Test
    void binaryFilesAreSkippedInWalkFallback(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("blob.bin"), new byte[] {0, 1, 2, 'n', 'e', 'e', 'd', 'l', 'e'});
        writeFile(dir, "text.txt", "needle\n");

        List<FileMatches> results = service.searchText(dir, "needle").join();

        assertEquals(1, results.size());
        assertEquals("text.txt", results.get(0).relativePath().toString());
    }

    @Test
    void emptyQueryReturnsNoResults(@TempDir Path dir) throws IOException {
        writeFile(dir, "a.txt", "content\n");
        assertTrue(service.searchText(dir, "  ").join().isEmpty());
        assertTrue(service.searchFiles(dir, "").join().isEmpty());
    }

    private static void initRepo(Path repo) throws IOException, InterruptedException {
        runGit(repo, "init", "-b", "main");
    }

    private static void writeFile(Path root, String name, String content) throws IOException {
        Files.writeString(root.resolve(name), content);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
    }
}
