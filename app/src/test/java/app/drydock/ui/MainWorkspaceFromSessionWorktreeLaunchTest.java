package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.git.BranchCheckout;
import app.drydock.git.BranchRef;
import app.drydock.git.GitStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainWorkspaceFromSessionWorktreeLaunchTest {

    @Test
    void existingLaunchChecksOutTheSelectedBranchWithoutClaimingOwnershipAndNewLaunchCreatesIt(
            @TempDir Path root) throws Exception {
        runGit(root, "init", "--initial-branch=main");
        runGit(root, "config", "user.name", "Test");
        runGit(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "seed\n");
        runGit(root, "add", "README.md");
        runGit(root, "commit", "-m", "initial");
        runGit(root, "branch", "existing-task-branch");

        Repository repository = new Repository(RepositoryId.newId(), root, "example",
                Instant.now(), Instant.now(), RepositorySettings.DEFAULT);
        // Per-run sandbox: the shared temp directory outlives runs, and a
        // fixed sibling path there makes a rerun's `git worktree add` fail
        // over the previous run's leftover.
        Path sandbox = Files.createTempDirectory("from-session-launch");
        Path existingWorktree = sandbox.resolve("existing-task-worktree");
        Path newWorktree = sandbox.resolve("new-task-worktree");
        NewTaskModal.NewTaskLaunch existing = launch(NewWorktreeState.Mode.EXISTING,
                new BranchCheckout.Outcome.Ready(BranchRef.local("existing-task-branch"),
                        "existing-task-branch", Optional.empty()),
                "existing-task-branch", "main", existingWorktree);
        NewTaskModal.NewTaskLaunch create = launch(NewWorktreeState.Mode.NEW,
                new BranchCheckout.Outcome.NoSuchBranch("new-task-branch"),
                "new-task-branch", "main", newWorktree);

        try (GitStatusService gitStatusService = new GitStatusService()) {
            Path checkedOut = MainWorkspace.createFromSessionWorktree(gitStatusService, repository, existing)
                    .get(30, TimeUnit.SECONDS);
            assertEquals(existingWorktree.toAbsolutePath().normalize(), checkedOut);
            assertEquals("existing-task-branch", git(checkedOut, "branch", "--show-current"));
            assertFalse(MainWorkspace.branchCreatedHere(existing));

            Path created = MainWorkspace.createFromSessionWorktree(gitStatusService, repository, create)
                    .get(30, TimeUnit.SECONDS);
            assertEquals(newWorktree.toAbsolutePath().normalize(), created);
            assertEquals("new-task-branch", git(created, "branch", "--show-current"));
            assertTrue(MainWorkspace.branchCreatedHere(create));
        }
    }

    private static NewTaskModal.NewTaskLaunch launch(NewWorktreeState.Mode mode,
                                                       BranchCheckout.Outcome outcome,
                                                       String branch, String base, Path directory) {
        return new NewTaskModal.NewTaskLaunch(NewTaskModal.Placement.NEW_WORKTREE, Optional.empty(), mode,
                Optional.of(outcome), branch, base, directory, Optional.empty(), Optional.empty(), Optional.empty(),
                AgentKind.CLAUDE, Optional.empty(), false);
    }

    private static void runGit(Path repository, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
    }

    private static String git(Path repository, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
        return output;
    }
}
