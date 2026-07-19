package app.cpm.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary Git repositories, in the style
 * of {@link GitStatusServiceTest}: worktree discovery via
 * {@code git worktree list --porcelain} and the one-click removal of an
 * unopened worktree (worktree handoff, section B "Discovering worktrees").
 */
class WorktreeServiceTest {

    private final WorktreeService service = new WorktreeService();
    private final GitStatusService gitStatusService = new GitStatusService();

    @Test
    void listReportsTheMainCheckoutFirst(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        List<WorktreeService.Worktree> worktrees = service.list(repo).get();

        assertEquals(1, worktrees.size());
        WorktreeService.Worktree main = worktrees.get(0);
        assertTrue(main.mainCheckout());
        assertEquals(Optional.of("main"), main.branch());
        assertEquals(repo.toRealPath(), main.path().toRealPath());
    }

    @Test
    void listDiscoversWorktreesCreatedOutsideTheApp(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path outside = worktreeParent.resolve("outside-wt");
        runGit(repo, "worktree", "add", outside.toString(), "-b", "feat/outside");

        List<WorktreeService.Worktree> worktrees = service.list(repo).get();

        assertEquals(2, worktrees.size());
        WorktreeService.Worktree discovered = worktrees.get(1);
        assertFalse(discovered.mainCheckout());
        assertEquals(Optional.of("feat/outside"), discovered.branch());
        assertEquals(outside.toRealPath(), discovered.path().toRealPath());
    }

    @Test
    void listReportsADetachedWorktreeWithoutABranch(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        String head = runGitCapture(repo, "rev-parse", "HEAD").trim();
        Path detachedDir = worktreeParent.resolve("detached-wt");
        runGit(repo, "worktree", "add", "--detach", detachedDir.toString(), head);

        List<WorktreeService.Worktree> worktrees = service.list(repo).get();

        WorktreeService.Worktree detached = worktrees.get(1);
        assertTrue(detached.detached());
        assertTrue(detached.branch().isEmpty());
    }

    @Test
    void listOnNonGitDirectoryThrowsNotAGitRepositoryException(@TempDir Path notARepo) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.list(notARepo).join());
        assertInstanceOf(NotAGitRepositoryException.class, completion.getCause());
    }

    @Test
    void removeDeletesTheWorktreeAndItsBranch(@TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/short-lived").get();

        service.remove(repo, worktree, Optional.of("feat/short-lived")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/short-lived").contains("feat/short-lived"));
    }

    @Test
    void removeWithoutABranchOnlyRemovesTheWorktree(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/keep-branch").get();

        service.remove(repo, worktree, Optional.empty()).get();

        assertFalse(Files.exists(worktree));
        assertTrue(runGitCapture(repo, "branch", "--list", "feat/keep-branch").contains("feat/keep-branch"));
    }

    @Test
    void removeRefusesTheMainCheckout(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, repo, Optional.of("main")).join());
        assertInstanceOf(IllegalArgumentException.class, completion.getCause());
        assertTrue(Files.exists(repo.resolve("README.md")));
    }

    @Test
    void removeOfADirtyWorktreeFailsInsteadOfDiscardingChanges(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/dirty").get();
        Files.writeString(worktree.resolve("uncommitted.txt"), "precious\n");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/dirty")).join());
        assertInstanceOf(GitCommandFailedException.class, completion.getCause());
        assertTrue(Files.exists(worktree));
    }

    @Test
    void parseHandlesBranchDetachedAndBareStanzas() {
        String porcelain = """
                worktree /repos/main
                HEAD 1111111111111111111111111111111111111111
                branch refs/heads/main

                worktree /repos/wt-feature
                HEAD 2222222222222222222222222222222222222222
                branch refs/heads/feat/x

                worktree /repos/wt-detached
                HEAD 3333333333333333333333333333333333333333
                detached
                """;

        List<WorktreeService.Worktree> worktrees = WorktreeService.parse(porcelain);

        assertEquals(3, worktrees.size());
        assertTrue(worktrees.get(0).mainCheckout());
        assertEquals(Optional.of("main"), worktrees.get(0).branch());
        assertFalse(worktrees.get(1).mainCheckout());
        assertEquals(Optional.of("feat/x"), worktrees.get(1).branch());
        assertTrue(worktrees.get(2).detached());
        assertTrue(worktrees.get(2).branch().isEmpty());
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial commit");
        return repo;
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
