package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary Git repositories (plan section
 * 22.2), covering exactly the facts {@link GitStatusService} extracts:
 * branch/detached-HEAD state, dirty, and ahead/behind vs. upstream.
 */
class GitStatusServiceTest {

    private final GitStatusService service = new GitStatusService();

    @Test
    void cleanRepositoryIsNotDirtyAndOnBranch(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        GitStatus status = getStatus(repo);

        assertEquals(new GitBranchState.OnBranch("main"), status.branch());
        assertFalse(status.dirty());
        assertTrue(status.upstream().isEmpty());
    }

    @Test
    void modifiedTrackedFileIsDirty(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        writeFile(repo, "README.md", "hello, modified\n");

        assertTrue(getStatus(repo).dirty());
    }

    @Test
    void untrackedFileIsDirty(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        writeFile(repo, "new-file.txt", "new content\n");

        assertTrue(getStatus(repo).dirty());
    }

    @Test
    void repositoryWithNoCommitsReportsBranchAndIsClean(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");

        GitStatus status = getStatus(repo);

        assertEquals(new GitBranchState.OnBranch("main"), status.branch());
        assertFalse(status.dirty());
    }

    @Test
    void repositoryPathContainingSpacesWorks(@TempDir Path tempDir) throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("my repo with spaces"));
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        writeFile(repo, "dirty.txt", "x\n");

        assertTrue(getStatus(repo).dirty());
    }

    @Test
    void nonGitDirectoryThrowsNotAGitRepositoryException(@TempDir Path notARepo) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.getStatus(notARepo).join());
        assertInstanceOf(NotAGitRepositoryException.class, completion.getCause());
        assertTrue(completion.getCause().getMessage().contains(notARepo.toString()));
    }

    @Test
    void missingGitExecutableThrowsGitExecutableNotFoundException(@TempDir Path repo) {
        GitExecutableLocator missingLocator = new GitExecutableLocator(Path.of("/nonexistent/git-does-not-exist"));
        GitStatusService serviceWithMissingGit = new GitStatusService(missingLocator);
        try {
            CompletionException completion = assertThrows(CompletionException.class,
                    () -> serviceWithMissingGit.getStatus(repo).join());
            assertInstanceOf(GitExecutableNotFoundException.class, completion.getCause());
        } finally {
            serviceWithMissingGit.close();
        }
    }

    @Test
    void detachedHeadIsReportedAsDetached(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        String commitOid = runGitCapture(repo, "rev-parse", "HEAD").trim();

        runGit(repo, "checkout", "--detach", commitOid);

        GitStatus status = getStatus(repo);
        assertInstanceOf(GitBranchState.Detached.class, status.branch());
    }

    @Test
    void aheadBehindReportedRelativeToUpstream(@TempDir Path remoteDir, @TempDir Path cloneDir) throws Exception {
        Path remote = remoteDir.resolve("remote.git");
        Files.createDirectory(remote);
        runGit(remote, "init", "--bare", "-b", "main");

        Path work = cloneDir.resolve("work");
        Files.createDirectory(work);
        initRepo(work, "main");
        writeFile(work, "README.md", "hello\n");
        runGit(work, "add", "README.md");
        commit(work, "initial commit");
        runGit(work, "remote", "add", "origin", remote.toString());
        runGit(work, "push", "-u", "origin", "main");

        // One local commit ahead of upstream.
        writeFile(work, "README.md", "hello, again\n");
        runGit(work, "add", "README.md");
        commit(work, "second commit");

        GitStatus status = getStatus(work);
        assertTrue(status.upstream().isPresent());
        assertEquals(1, status.upstream().get().ahead());
        assertEquals(0, status.upstream().get().behind());
    }

    @Test
    void resolveRepositoryRootReturnsTheTopLevelForARepositoryRoot(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");

        Path resolved = service.resolveRepositoryRoot(repo).get();

        assertEquals(repo.toRealPath(), resolved.toRealPath());
    }

    @Test
    void resolveRepositoryRootReturnsTheTopLevelFromASubdirectory(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        Path subdirectory = Files.createDirectories(repo.resolve("src/main"));

        Path resolved = service.resolveRepositoryRoot(subdirectory).get();

        assertEquals(repo.toRealPath(), resolved.toRealPath());
    }

    @Test
    void resolveRepositoryRootRejectsANonGitDirectory(@TempDir Path plainDir) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.resolveRepositoryRoot(plainDir).join());
        assertInstanceOf(NotAGitRepositoryException.class, completion.getCause());
    }

    @Test
    void createWorktreeCreatesDirectoryAndNewBranch(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = repoDir.resolve("repo");
        Files.createDirectory(repo);
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        Path worktreeDir = worktreeParent.resolve("wt/repo-feature");
        Path created = service.createWorktree(repo, worktreeDir, "feat/feature").get();

        assertTrue(Files.isDirectory(created));
        assertTrue(Files.exists(created.resolve("README.md")));
        GitStatus status = getStatus(created);
        assertEquals(new GitBranchState.OnBranch("feat/feature"), status.branch());
        assertFalse(status.dirty());
        assertTrue(runGitCapture(repo, "worktree", "list").contains("feat/feature"));
    }

    @Test
    void createWorktreeFailsWhenBranchAlreadyExists(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = repoDir.resolve("repo");
        Files.createDirectory(repo);
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "branch", "feat/taken");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.createWorktree(repo, worktreeParent.resolve("wt-taken"), "feat/taken").join());
        assertInstanceOf(GitCommandFailedException.class, completion.getCause());
    }

    @Test
    void createWorktreeOnNonGitDirectoryThrowsNotAGitRepositoryException(@TempDir Path notARepo,
                                                                          @TempDir Path worktreeParent) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.createWorktree(notARepo, worktreeParent.resolve("wt"), "feat/x").join());
        assertInstanceOf(NotAGitRepositoryException.class, completion.getCause());
    }

    @Test
    void createWorktreeForksFromAnExplicitStartPoint(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = repoDir.resolve("repo");
        Files.createDirectory(repo);
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "checkout", "-b", "develop");
        writeFile(repo, "develop-only.txt", "develop\n");
        runGit(repo, "add", "develop-only.txt");
        commit(repo, "develop-only commit");
        runGit(repo, "checkout", "main");

        Path worktreeDir = worktreeParent.resolve("wt-from-develop");
        Path created = service.createWorktree(repo, worktreeDir, "feat/from-develop", Optional.of("develop")).get();

        assertTrue(Files.exists(created.resolve("develop-only.txt")));
    }

    @Test
    void listLocalBranchesReportsEveryLocalBranch(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "branch", "develop");
        runGit(repo, "branch", "feat/x");

        List<String> branches = service.listLocalBranches(repo).get();

        assertTrue(branches.containsAll(List.of("main", "develop", "feat/x")));
        assertEquals(3, branches.size());
    }

    @Test
    void changeSummaryReportsCommitsAheadAndPerFileStats(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = repoDir.resolve("repo");
        Files.createDirectory(repo);
        initRepo(repo, "main");
        writeFile(repo, "README.md", "line1\nline2\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        Path worktree = service.createWorktree(repo, worktreeParent.resolve("wt"), "feat/change").get();
        writeFile(worktree, "README.md", "line1\nchanged\n");
        writeFile(worktree, "New.java", "class New {}\n");
        runGit(worktree, "add", "README.md", "New.java");
        commit(worktree, "change things");

        GitChangeSummary summary = service.getChangeSummary(worktree, "main").get();

        assertEquals(1, summary.commitsAhead());
        assertEquals(2, summary.files().size());
        GitChangeSummary.ChangedFile added = summary.files().stream()
                .filter(f -> f.path().equals("New.java")).findFirst().orElseThrow();
        assertEquals("A", added.kind());
        GitChangeSummary.ChangedFile modified = summary.files().stream()
                .filter(f -> f.path().equals("README.md")).findFirst().orElseThrow();
        assertEquals("M", modified.kind());
        assertEquals(1, modified.insertions());
        assertEquals(1, modified.deletions());
    }

    private GitStatus getStatus(Path repo) throws ExecutionException, InterruptedException {
        CompletableFuture<GitStatus> future = service.getStatus(repo);
        return future.get();
    }

    private static void initRepo(Path repo, String initialBranch) throws IOException, InterruptedException {
        runGit(repo, "init", "-b", initialBranch);
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void writeFile(Path repo, String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
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
        return output;
    }
}
