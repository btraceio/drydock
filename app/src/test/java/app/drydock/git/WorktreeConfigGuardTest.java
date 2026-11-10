package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real temporary Git repositories, in the style
 * of {@link WorktreeServiceTest}: detection of
 * {@code extensions.worktreeConfig} in a repository's shared config, and
 * the conservative fix that unsets it (restoring
 * {@code core.repositoryformatversion} only when no other extension
 * remains) or refuses when something legitimately needs it.
 */
class WorktreeConfigGuardTest {

    private final WorktreeConfigGuard guard = new WorktreeConfigGuard();

    @Test
    void cleanRepositoryIsNotFlagged(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        WorktreeConfigGuard.Flag flag = guard.check(repo).get();

        assertFalse(flag.set());
        assertTrue(flag.originPath().isEmpty());
    }

    @Test
    void flagIsDetectedWithItsOriginFile(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "extensions.worktreeConfig", "true");

        WorktreeConfigGuard.Flag flag = guard.check(repo).get();

        assertTrue(flag.set());
        assertTrue(flag.originPath().orElseThrow().endsWith(".git/config"));
    }

    @Test
    void fixRemovesTheFlagAndRestoresRepositoryFormatVersion(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        // What Claude Code's flip looks like on disk: the extension flag,
        // plus the format version bump that makes git read it at all.
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        runGit(repo, "config", "core.repositoryformatversion", "1");

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Fixed fixed = assertInstanceOf(WorktreeConfigGuard.FixResult.Fixed.class, result);
        assertTrue(fixed.versionRestored());
        assertFalse(guard.check(repo).get().set());
        assertEquals("0", runGitCapture(repo, "config", "core.repositoryformatversion").strip());
    }

    @Test
    void fixKeepsFormatVersionWhileAnotherExtensionRemains(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "core.repositoryformatversion", "1");
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        // A real extension git recognizes (and one matching this repo's
        // hash algo), so version 1 stays meaningful after the fix.
        runGit(repo, "config", "extensions.objectFormat", "sha1");

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Fixed fixed = assertInstanceOf(WorktreeConfigGuard.FixResult.Fixed.class, result);
        assertFalse(fixed.versionRestored());
        assertFalse(guard.check(repo).get().set());
        assertEquals("1", runGitCapture(repo, "config", "core.repositoryformatversion").strip());
        assertEquals("sha1", runGitCapture(repo, "config", "extensions.objectFormat").strip());
    }

    @Test
    void fixIsRefusedWhenASparseWorktreeNeedsTheExtension(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = worktreeParent.resolve("sparse-wt");
        runGit(repo, "worktree", "add", worktree.toString(), "-b", "feat/sparse");
        // git's own legitimate use of the extension: sparse checkout stores
        // its per-worktree config behind it (and sets the flag itself).
        runGit(worktree, "sparse-checkout", "init", "--cone");

        assertTrue(guard.check(repo).get().set());

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Refused refused =
                assertInstanceOf(WorktreeConfigGuard.FixResult.Refused.class, result);
        assertTrue(refused.reason().contains("sparse"));
        assertTrue(guard.check(repo).get().set());
    }

    @Test
    void fixIsRefusedWhileAClaudeWorktreeIsActive(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        runGit(repo, "worktree", "add", repo.resolve(".claude").resolve("worktrees")
                .resolve("session-1").toString(), "-b", "feat/claude");

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Refused refused =
                assertInstanceOf(WorktreeConfigGuard.FixResult.Refused.class, result);
        assertTrue(refused.reason().contains("Claude Code"));
        assertTrue(guard.check(repo).get().set());
    }

    @Test
    void staleClaudeWorktreeLeftoverDoesNotBlockTheFix(@TempDir Path repoDir) throws Exception {
        // The shape the drydock repo actually hit: a dead session's worktree
        // -- detached, unlocked, clean -- registered under .claude/worktrees.
        // It must not graveyard the fix.
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        String head = runGitCapture(repo, "rev-parse", "HEAD").strip();
        Path leftover = repo.resolve(".claude").resolve("worktrees").resolve("session-old");
        runGit(repo, "worktree", "add", "--detach", leftover.toString(), head);
        // Age the git dir beyond the live-activity window: a running session
        // touches the index, so a quiet index means nobody is home.
        Path gitdir = repo.resolve(".git").resolve("worktrees").resolve("session-old");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime
                .from(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
        Files.setLastModifiedTime(gitdir.resolve("index"), old);
        Files.setLastModifiedTime(gitdir, old);

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        assertInstanceOf(WorktreeConfigGuard.FixResult.Fixed.class, result);
        assertFalse(guard.check(repo).get().set());
    }

    @Test
    void lockedClaudeWorktreeBlocksTheFixEvenWhenQuiet(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        String head = runGitCapture(repo, "rev-parse", "HEAD").strip();
        Path locked = repo.resolve(".claude").resolve("worktrees").resolve("session-locked");
        runGit(repo, "worktree", "add", "--detach", locked.toString(), head);
        runGit(repo, "worktree", "lock", locked.toString());

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Refused refused =
                assertInstanceOf(WorktreeConfigGuard.FixResult.Refused.class, result);
        assertTrue(refused.reason().contains("Claude Code"));
    }

    @Test
    void dirtyOldClaudeWorktreeBlocksTheFix(@TempDir Path repoDir) throws Exception {
        // Old enough to look dead by mtime, but holding uncommitted work:
        // the dirty probe must still classify it as live.
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        String head = runGitCapture(repo, "rev-parse", "HEAD").strip();
        Path dirty = repo.resolve(".claude").resolve("worktrees").resolve("session-dirty");
        runGit(repo, "worktree", "add", "--detach", dirty.toString(), head);
        Files.writeString(dirty.resolve("uncommitted.txt"), "work in progress\n");
        Path gitdir = repo.resolve(".git").resolve("worktrees").resolve("session-dirty");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime
                .from(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
        Files.setLastModifiedTime(gitdir.resolve("index"), old);
        Files.setLastModifiedTime(gitdir, old);

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Refused refused =
                assertInstanceOf(WorktreeConfigGuard.FixResult.Refused.class, result);
        assertTrue(refused.reason().contains("Claude Code"));
    }

    @Test
    void fixIsRefusedWhenAWorktreeCarriesItsOwnConfigWorktree(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        // The third legitimate owner: a user's own `git config --worktree`
        // values in an ordinary (non-sparse, non-Claude) linked worktree.
        // Removing the flag makes git stop reading that file.
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "config", "extensions.worktreeConfig", "true");
        Path worktree = worktreeParent.resolve("per-wt");
        runGit(repo, "worktree", "add", worktree.toString(), "-b", "feat/per-wt");
        runGit(worktree, "config", "--worktree", "user.name", "Worktree User");

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        WorktreeConfigGuard.FixResult.Refused refused =
                assertInstanceOf(WorktreeConfigGuard.FixResult.Refused.class, result);
        assertTrue(refused.reason().contains("config.worktree"));
        assertTrue(guard.check(repo).get().set());
        assertEquals("Worktree User", runGitCapture(worktree, "config", "--worktree", "user.name").strip());
    }

    @Test
    void fixOnAnAlreadyCleanRepositoryReportsAlreadyClean(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);

        WorktreeConfigGuard.FixResult result = guard.fix(repo).get();

        assertInstanceOf(WorktreeConfigGuard.FixResult.AlreadyClean.class, result);
    }

    @Test
    void checkOnANonRepositoryFails(@TempDir Path notARepo) {
        // supplyAsync surfaces a throwing supplier as ExecutionException on get().
        ExecutionException execution = assertThrows(ExecutionException.class,
                () -> guard.check(notARepo).get());
        assertInstanceOf(GitCommandFailedException.class, execution.getCause());
    }

    // ---- fixtures ------------------------------------------------------------

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
        return output;
    }
}
