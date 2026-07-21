package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary Git repositories, in the style
 * of {@link GitStatusServiceTest}: the three Review scopes of
 * {@link DiffService} plus the shared {@link ChangedLineService} map
 * (design handoff section C).
 */
class DiffServiceTest {

    private final DiffService service = new DiffService();

    @Test
    void workingTreeScopeSeesStagedAndUnstagedChanges(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        Files.writeString(repo.resolve("staged.txt"), "staged\n");
        runGit(repo, "add", "staged.txt");
        Files.writeString(repo.resolve("README.md"), "one\nCHANGED\nthree\n");

        UnifiedDiff diff = service.diff(repo, DiffScope.WORKING_TREE, "main").get();

        assertEquals(2, diff.files().size());
        UnifiedDiff.FileDiff readme = fileByPath(diff, "README.md");
        assertEquals("M", readme.kind());
        assertFalse(readme.staged());
        assertEquals(1, readme.insertions());
        assertEquals(1, readme.deletions());
        UnifiedDiff.FileDiff staged = fileByPath(diff, "staged.txt");
        assertEquals("A", staged.kind());
        assertTrue(staged.staged());
    }

    @Test
    void baseScopeDiffsTheWholeBranchWithLineNumbers(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\nthree\n");
        runGit(repo, "checkout", "-b", "feat/change");
        Files.writeString(repo.resolve("README.md"), "one\nCHANGED\nthree\nfour\n");
        runGit(repo, "add", "README.md");
        commit(repo, "change on branch");

        UnifiedDiff diff = service.diff(repo, DiffScope.BASE, "main").get();

        UnifiedDiff.FileDiff readme = fileByPath(diff, "README.md");
        assertEquals(1, readme.hunks().size());
        UnifiedDiff.Hunk hunk = readme.hunks().get(0);
        assertTrue(hunk.header().startsWith("@@"));

        UnifiedDiff.Line deleted = hunk.lines().stream()
                .filter(line -> line.kind() == UnifiedDiff.Line.Kind.DEL).findFirst().orElseThrow();
        assertEquals("two", deleted.text());
        assertEquals(OptionalInt.of(2), deleted.oldLine());
        assertEquals(OptionalInt.empty(), deleted.newLine());
        assertEquals("o2", deleted.lineKey());

        UnifiedDiff.Line changed = hunk.lines().stream()
                .filter(line -> line.kind() == UnifiedDiff.Line.Kind.ADD && line.text().equals("CHANGED"))
                .findFirst().orElseThrow();
        assertEquals(OptionalInt.of(2), changed.newLine());
        assertEquals("n2", changed.lineKey());

        UnifiedDiff.Line appended = hunk.lines().stream()
                .filter(line -> line.kind() == UnifiedDiff.Line.Kind.ADD && line.text().equals("four"))
                .findFirst().orElseThrow();
        assertEquals(OptionalInt.of(4), appended.newLine());
    }

    @Test
    void upstreamScopeDiffsAgainstTheUpstreamBranch(@TempDir Path remoteDir, @TempDir Path cloneDir)
            throws Exception {
        Path remote = Files.createDirectory(remoteDir.resolve("remote.git"));
        runGit(remote, "init", "--bare", "-b", "main");
        Path work = initCommittedRepo(cloneDir, "one\n");
        runGit(work, "remote", "add", "origin", remote.toString());
        runGit(work, "push", "-u", "origin", "main");

        Files.writeString(work.resolve("README.md"), "one\nlocal-only\n");
        runGit(work, "add", "README.md");
        commit(work, "ahead of upstream");

        UnifiedDiff diff = service.diff(work, DiffScope.UPSTREAM, "main").get();

        UnifiedDiff.FileDiff readme = fileByPath(diff, "README.md");
        assertEquals(1, readme.insertions());
    }

    @Test
    void cleanCheckoutProducesAnEmptyDiff(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\n");

        UnifiedDiff diff = service.diff(repo, DiffScope.WORKING_TREE, "main").get();

        assertTrue(diff.files().isEmpty());
    }

    @Test
    void nonGitDirectoryThrowsNotAGitRepositoryException(@TempDir Path notARepo) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.diff(notARepo, DiffScope.WORKING_TREE, "main").join());
        assertInstanceOf(NotAGitRepositoryException.class, completion.getCause());
    }

    @Test
    void changedLineServiceMapsAddedLinesByFileAndCaches(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir, "one\ntwo\n");
        runGit(repo, "checkout", "-b", "feat/lines");
        Files.writeString(repo.resolve("README.md"), "one\nCHANGED\nthree\n");
        runGit(repo, "add", "README.md");
        commit(repo, "change lines");

        ChangedLineService changedLines = new ChangedLineService(service);
        Map<Path, Set<Integer>> changed = changedLines.changedSet(repo, DiffScope.BASE, "main").get();

        assertEquals(Set.of(2, 3), changed.get(Path.of("README.md")));

        // Cached: a further commit is invisible until invalidate().
        Files.writeString(repo.resolve("Extra.java"), "class Extra {}\n");
        runGit(repo, "add", "Extra.java");
        commit(repo, "extra file");
        assertFalse(changedLines.changedSet(repo, DiffScope.BASE, "main").get()
                .containsKey(Path.of("Extra.java")));

        changedLines.invalidate(repo);
        assertTrue(changedLines.changedSet(repo, DiffScope.BASE, "main").get()
                .containsKey(Path.of("Extra.java")));
    }

    @Test
    void parseHandlesMultipleHunksAndNewFiles() {
        String diffText = """
                diff --git a/Sample.java b/Sample.java
                index 1111111..2222222 100644
                --- a/Sample.java
                +++ b/Sample.java
                @@ -1,3 +1,3 @@ class Sample {
                 context1
                -old
                +new
                @@ -10,2 +10,3 @@ void method() {
                 context2
                +appended
                 context3
                diff --git a/Fresh.java b/Fresh.java
                new file mode 100644
                index 0000000..3333333
                --- /dev/null
                +++ b/Fresh.java
                @@ -0,0 +1,2 @@
                +line one
                +line two
                """;

        UnifiedDiff diff = DiffService.parse(diffText, Set.of("Fresh.java"));

        assertEquals(2, diff.files().size());
        UnifiedDiff.FileDiff sample = diff.files().get(0);
        assertEquals("Sample.java", sample.path());
        assertEquals(2, sample.hunks().size());
        assertEquals(2, sample.insertions());
        assertEquals(1, sample.deletions());
        UnifiedDiff.Line appended = sample.hunks().get(1).lines().get(1);
        assertEquals(OptionalInt.of(11), appended.newLine());

        UnifiedDiff.FileDiff fresh = diff.files().get(1);
        assertEquals("A", fresh.kind());
        assertTrue(fresh.staged());
        assertEquals(OptionalInt.of(1), fresh.hunks().get(0).lines().get(0).newLine());
    }

    private static UnifiedDiff.FileDiff fileByPath(UnifiedDiff diff, String path) {
        return diff.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    private static Path initCommittedRepo(Path parent, String readmeContent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), readmeContent);
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        return repo;
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
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
