package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.WorktreeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one end-to-end proof, against real git, that a commit landing on the
 * branch <em>after</em> the merge was verified is not destroyed by the
 * cleanup that follows.
 *
 * <p>The merge oracle ({@code WorktreeService.verify}) proves that a merge
 * commit of the tip recorded at pre-flight sits on the base branch. It cannot
 * prove anything about where {@code refs/heads/<branch>} points by the time
 * {@code git branch -D} runs, and on the conflict hand-off path minutes pass
 * in between with the session's Claude running inside the worktree. This test
 * builds exactly that history -- verified merge, then another commit on the
 * branch -- and asserts the branch, and therefore the commit, survives, with
 * copy that says so.</p>
 *
 * <p>FX-free by construction: {@link WorktreeSessionCleanup} and {@link
 * MergeFinishDecision} are the two classes involved, and neither touches the
 * toolkit.</p>
 */
class MergeFinishBranchDriftTest {

    @Test
    void aCommitLandingOnTheBranchAfterTheVerifiedMergeSurvivesTheCleanup(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path worktree = worktreeParent.resolve("wt");
        runGit(repo, "worktree", "add", worktree.toString(), "-b", "feat/x");
        commitFile(worktree, "feature.txt", "the work\n", "add feature");

        try (WorktreeService service = new WorktreeService()) {
            WorktreeService.MergeTarget target = service.inspectMergeTarget(repo, "feat/x").get();
            assertInstanceOf(WorktreeService.MergeVerdict.Merged.class,
                    service.merge(repo, "feat/x", target).get());

            // What the hand-off window makes realistic: the agent (whose cwd IS
            // the worktree) or the user commits to the branch while the flow polls.
            commitFile(worktree, "afterthought.txt", "written during the hand-off\n", "one more thing");
            String movedTip = runGitCapture(repo, "rev-parse", "feat/x").strip();
            assertNotEquals(target.branchTipOid().orElseThrow(), movedTip,
                    "precondition: the branch tip must have moved since the merge");
            // The oracle still says Merged -- it is answering about the recorded tip.
            assertInstanceOf(WorktreeService.MergeVerdict.Merged.class, service.verifyMerge(repo, target).get());

            Optional<String> currentTip = service.inspectMergeTarget(repo, "feat/x").get().branchTipOid();
            MergeFinishDecision.BranchDeletePlan plan =
                    MergeFinishDecision.forBranchDelete(true, target.branchTipOid(), currentTip);
            assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED, plan);

            List<ManagedSessionId> deleted = new ArrayList<>();
            WorktreeSessionCleanup cleanup = new WorktreeSessionCleanup(service::remove, id -> {
                deleted.add(id);
                return CompletableFuture.completedFuture(null);
            });
            ManagedSessionId sessionId = ManagedSessionId.newId();

            MergeFinishDecision.CleanupOutcome outcome =
                    cleanup.run(sessionId, repo, worktree, "feat/x", plan).get();

            assertTrue(outcome.worktreeRemoved(), "the worktree removal is still safe and must still happen");
            assertEquals(MergeFinishDecision.BranchResult.KEPT_MOVED, outcome.branch());
            assertEquals(List.of(sessionId), deleted);
            assertFalse(Files.exists(worktree));
            // The point of the whole exercise: the ref, and the commit only it
            // reaches, are still there.
            assertEquals(movedTip, runGitCapture(repo, "rev-parse", "feat/x").strip());
            assertFalse(isAncestorOfMain(repo, movedTip),
                    "precondition: the drifted commit is NOT in main, which is why deleting it would lose it");

            MergeFinishDecision.Next.Done done =
                    MergeFinishDecision.forCleanup(outcome, "feat/x", "main", true);
            assertEquals("✓ Merged feat/x into main — conflicts resolved by Claude", done.headline());
            assertEquals("worktree removed · branch feat/x kept — it moved since the merge · session closed",
                    done.detail());
        }
    }

    /** {@code merge-base --is-ancestor}'s exit code, as a boolean; anything but 0/1 is a fixture failure. */
    private static boolean isAncestorOfMain(Path repo, String oid) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "git", "-C", repo.toString(), "merge-base", "--is-ancestor", oid, "main")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode > 1) {
            throw new IOException("git merge-base --is-ancestor failed (exit " + exitCode + "): " + output);
        }
        return exitCode == 0;
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial commit");
        return repo;
    }

    private static void commitFile(Path checkout, String name, String content, String message)
            throws IOException, InterruptedException {
        Files.writeString(checkout.resolve(name), content);
        runGit(checkout, "add", name);
        runGit(checkout, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
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
