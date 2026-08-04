package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Untracked, non-ignored files must appear in the {@link DiffScope#WORKING_TREE}
 * diff as new-file additions -- {@code git diff HEAD} alone never shows them,
 * so a brand-new file was invisible to Review (task 14). These tests exercise
 * the temp-index path in {@link DiffService#diffBlocking} against real
 * temporary Git repositories, in the style of {@link DiffServiceTest}.
 */
class DiffServiceUntrackedTest {

    private final DiffService service = new DiffService();

    @Test
    void anUntrackedFileAppearsAsANewFile(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        Files.writeString(repo.resolve("New.java"), "class New {}\n");

        UnifiedDiff diff = service.diff(repo, DiffScope.WORKING_TREE, "main").get();

        UnifiedDiff.FileDiff added = fileByPath(diff, "New.java");
        assertEquals("A", added.kind());
        assertTrue(added.hunks().stream()
                .flatMap(h -> h.lines().stream())
                .allMatch(line -> line.kind() == UnifiedDiff.Line.Kind.ADD));
    }

    @Test
    void trackedAndUntrackedChangesAppearTogether(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        Files.writeString(repo.resolve("README.md"), "one\nCHANGED\nthree\n");
        Files.writeString(repo.resolve("Extra.java"), "class Extra {}\n");

        UnifiedDiff diff = service.diff(repo, DiffScope.WORKING_TREE, "main").get();

        UnifiedDiff.FileDiff readme = fileByPath(diff, "README.md");
        assertEquals("M", readme.kind());
        UnifiedDiff.FileDiff extra = fileByPath(diff, "Extra.java");
        assertEquals("A", extra.kind());
    }

    @Test
    void anIgnoredFileIsNotReviewed(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\n");
        Files.createDirectories(repo.resolve("build"));
        Files.writeString(repo.resolve("build/out.o"), "binary-ish\n");
        Files.writeString(repo.resolve(".gitignore"), "build/\n");

        UnifiedDiff diff = service.diff(repo, DiffScope.WORKING_TREE, "main").get();

        assertFalse(diff.files().stream().anyMatch(f -> f.path().contains("out.o")));
    }

    @Test
    void theUsersIndexIsNotTouched(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\n");
        Files.writeString(repo.resolve("New.java"), "class New {}\n");

        String before = statusPorcelain(repo);
        service.diff(repo, DiffScope.WORKING_TREE, "main").get();
        String after = statusPorcelain(repo);

        assertEquals(before, after);
        assertTrue(after.lines().anyMatch(line -> line.equals("?? New.java")));
    }

    private static String statusPorcelain(Path repo) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "-C", repo.toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git status --porcelain failed (exit " + exitCode + "): " + output);
        }
        return output;
    }

    private static UnifiedDiff.FileDiff fileByPath(UnifiedDiff diff, String path) {
        return diff.files().stream().filter(f -> f.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no FileDiff for " + path + " in " + diff.files()));
    }

    private static Path initCommittedRepo(Path parent, String readmeContent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), readmeContent);
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
    }
}
