package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree));
    }

    @Test
    void forcedRemoveDeletesADirtyWorktreeAndItsBranch(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/force").get();
        Files.writeString(worktree.resolve("uncommitted.txt"), "expendable\n");

        service.removeForced(repo, worktree, Optional.of("feat/force")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/force").contains("feat/force"));
    }

    @Test
    void forcedRemoveStillRefusesTheMainCheckout(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.removeForced(repo, repo, Optional.of("main")).join());
        assertInstanceOf(IllegalArgumentException.class, completion.getCause());
        assertTrue(Files.exists(repo.resolve("README.md")));
    }

    /**
     * Pins the discriminator the force-fallback gate is built on: a
     * submodule only blocks a plain remove once it has been checked out
     * into the worktree, so a fresh worktree of a submodule-bearing
     * repository still takes the ordinary path.
     */
    @Test
    void removeUsesThePlainPathForAWorktreeWhoseSubmoduleIsUninitialized(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/no-subs").get();
        Path worktreeGitDir = Path.of(runGitCapture(worktree, "rev-parse", "--absolute-git-dir").strip());
        assertFalse(Files.isDirectory(worktreeGitDir.resolve("modules")),
                "precondition: the submodule must not be checked out into the worktree");

        service.remove(repo, worktree, Optional.of("feat/no-subs")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
    }

    /**
     * The same worktree, left dirty: the plain path must still refuse it.
     * Proves the uninitialized case never escalates to {@code --force},
     * which a bare success assertion above cannot distinguish.
     */
    @Test
    void removeOfADirtyUninitializedSubmoduleWorktreeStillFails(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/no-subs-dirty").get();
        Files.writeString(worktree.resolve("uncommitted.txt"), "precious\n");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/no-subs-dirty")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree.resolve("uncommitted.txt")));
    }

    @Test
    void removeSucceedsForAWorktreeWithAnInitializedSubmodule(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/subs").get();
        initSubmodulesIn(worktree);

        service.remove(repo, worktree, Optional.of("feat/subs")).get();

        assertFalse(Files.exists(worktree));
        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/subs").contains("feat/subs"));
    }

    @Test
    void removeSucceedsForASubmoduleWorktreeWhoseOnlyDirtIsInsideTheSubmodule(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub-dirt").get();
        initSubmodulesIn(worktree);
        Files.writeString(worktree.resolve("vendor/lib.txt"), "patched by the build\n");

        service.remove(repo, worktree, Optional.of("feat/sub-dirt")).get();

        assertFalse(Files.exists(worktree));
    }

    @Test
    void removeOfADirtySubmoduleWorktreeFailsInsteadOfDiscardingChanges(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub-dirty").get();
        initSubmodulesIn(worktree);
        Files.writeString(worktree.resolve("uncommitted.txt"), "precious\n");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/sub-dirty")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree.resolve("uncommitted.txt")));
    }

    @Test
    void removeOfAWorktreeWithABumpedSubmodulePointerFailsInsteadOfDiscardingTheBump(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initRepoWithSubmodule(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/sub-bump").get();
        initSubmodulesIn(worktree);
        // Moving the submodule's HEAD is uncommitted work in *this*
        // worktree's index, unlike mere dirt inside the submodule.
        runGit(worktree.resolve("vendor"), "-c", "user.name=Test", "-c", "user.email=test@example.com",
                "commit", "--allow-empty", "-m", "vendored bump");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.remove(repo, worktree, Optional.of("feat/sub-bump")).join());
        assertInstanceOf(WorktreeNotCleanException.class, completion.getCause());
        assertTrue(Files.exists(worktree));
    }

    @Test
    void removeSucceedsWhenTheWorktreeDirectoryWasDeletedFromDiskOutsideGit(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/vanished").get();
        deleteRecursively(worktree);

        service.remove(repo, worktree, Optional.of("feat/vanished")).get();

        assertEquals(1, service.list(repo).get().size());
        assertFalse(runGitCapture(repo, "branch", "--list", "feat/vanished").contains("feat/vanished"));
    }

    @Test
    void mergeIntoBaseMergesTheBranchIntoTheMainCheckout(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/mergeable").get();
        Files.writeString(worktree.resolve("feature.txt"), "new feature\n");
        runGit(worktree, "add", "feature.txt");
        runGit(worktree, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "add feature");

        service.mergeIntoBase(repo, "feat/mergeable").get();

        assertTrue(Files.exists(repo.resolve("feature.txt")));
        assertTrue(runGitCapture(repo, "log", "--oneline", "-1").toLowerCase(java.util.Locale.ROOT)
                .contains("merge"));
    }

    @Test
    void mergeIntoBaseFailsInsteadOfResolvingAConflict(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/conflict").get();
        Files.writeString(worktree.resolve("README.md"), "worktree version\n");
        runGit(worktree, "add", "README.md");
        runGit(worktree, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "wt change");
        Files.writeString(repo.resolve("README.md"), "main version\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "main change");

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.mergeIntoBase(repo, "feat/conflict").join());
        assertInstanceOf(GitCommandFailedException.class, completion.getCause());
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

    @Test
    void parseReadsPrunableAndLockedAttributes() {
        String porcelain = """
                worktree /repo
                HEAD 1111111111111111111111111111111111111111
                branch refs/heads/main

                worktree /gone
                HEAD 2222222222222222222222222222222222222222
                branch refs/heads/ghost
                prunable gitdir file points to non-existent location

                worktree /held
                HEAD 3333333333333333333333333333333333333333
                branch refs/heads/held-branch
                locked

                """;

        List<WorktreeService.Worktree> worktrees = WorktreeService.parse(porcelain);

        assertEquals(3, worktrees.size());
        assertFalse(worktrees.get(0).prunable());
        assertFalse(worktrees.get(0).locked());
        assertTrue(worktrees.get(1).prunable());
        assertEquals(Optional.of("ghost"), worktrees.get(1).branch());
        assertTrue(worktrees.get(2).locked());
        assertFalse(worktrees.get(2).prunable());
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial commit");
        return repo;
    }

    /**
     * A repository whose {@code vendor/} is a submodule, mirroring
     * Drydock's own vendored {@code third_party/ghostty}. {@code
     * protocol.file.allow} has to be re-enabled explicitly: git disables
     * {@code file://} submodule transport by default (CVE-2022-39253).
     */
    private static Path initRepoWithSubmodule(Path parent) throws IOException, InterruptedException {
        Path upstream = initCommittedRepo(Files.createDirectories(parent.resolve("upstream")));
        Files.writeString(upstream.resolve("lib.txt"), "vendored\n");
        runGit(upstream, "add", "lib.txt");
        runGit(upstream, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "lib");

        Path repo = initCommittedRepo(parent);
        runGit(repo, "-c", "protocol.file.allow=always",
                "submodule", "add", "--", upstream.toString(), "vendor");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "add submodule");
        return repo;
    }

    /**
     * Checks the submodules out inside {@code worktree}, which is what
     * makes git refuse a plain {@code worktree remove} on it.
     */
    private static void initSubmodulesIn(Path worktree) throws IOException, InterruptedException {
        runGit(worktree, "-c", "protocol.file.allow=always", "submodule", "update", "--init");
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
