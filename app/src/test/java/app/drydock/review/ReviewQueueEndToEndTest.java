package app.drydock.review;

import app.drydock.git.DiffService;
import app.drydock.git.DiffScope;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three reported cases, end to end over real git: local changes, a
 * develop-cut branch in a master-default repository, and a pull request
 * with and without a checkout.
 */
class ReviewQueueEndToEndTest {

    private final DiffService diffService = new DiffService();
    private final GitStatusService gitStatusService = new GitStatusService();
    private final IntentGrouping grouping = new IntentGrouping();

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void localChangesGroupIntoExactlyTheDirtyFiles() throws Exception {
        Path repo = Files.createTempDirectory("drydock-e2e-local");
        initRepo(repo, "main");
        write(repo, "A.java", "class A { int x = 1; }\n");
        write(repo, "B.java", "class B { int y = 1; }\n");
        write(repo, "C.java", "class C { int z = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        write(repo, "A.java", "class A { int x = 2; }\n");
        write(repo, "B.java", "class B { int y = 2; }\n");

        UnifiedDiff diff = diffService.diff(repo, DiffScope.WORKING_TREE, "main").get();
        List<ReviewIntent> intents = grouping.intentsFor("scope-local", diff);

        assertEquals(List.of("A.java", "B.java"),
                intents.stream().map(ReviewIntent::title).sorted().toList(),
                "the clean file must not become an intent");
    }

    @Test
    void aDevelopCutBranchDiffsAgainstDevelopNotMaster() throws Exception {
        Path repo = Files.createTempDirectory("drydock-e2e-base");
        initRepo(repo, "master");
        write(repo, "base.txt", "base\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "master");

        runGit(repo, "checkout", "-b", "develop");
        for (int i = 0; i < 8; i++) {
            write(repo, "infra" + i + ".txt", "infra\n");
            runGit(repo, "add", ".");
            runGit(repo, "commit", "-m", "infra " + i);
        }

        runGit(repo, "checkout", "-b", "feature/thing");
        write(repo, "feature.txt", "the feature\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "feature");

        var base = gitStatusService.reviewBase(repo, Optional.empty(), "master").get();
        assertEquals("develop", base.ref());

        UnifiedDiff diff = diffService.diff(repo, DiffScope.BASE, base.ref()).get();
        List<ReviewIntent> intents = grouping.intentsFor("scope-branch", diff);

        assertEquals(List.of("feature.txt"), intents.stream().map(ReviewIntent::title).toList(),
                "diffing against master would make this the whole of develop");
    }

    @Test
    void aCheckedOutPullRequestGroupsThePullRequestsFiles() throws Exception {
        Path repo = Files.createTempDirectory("drydock-e2e-pr");
        initRepo(repo, "main");
        write(repo, "base.txt", "base\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        runGit(repo, "checkout", "-b", "pr-7");
        write(repo, "changed-by-pr.txt", "pr\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "the pr");

        UnifiedDiff diff = diffService.diff(repo, DiffScope.BASE, "main").get();
        List<ReviewIntent> intents = grouping.intentsFor("scope-pr", diff);

        assertEquals(List.of("changed-by-pr.txt"), intents.stream().map(ReviewIntent::title).toList());
    }

    @Test
    void aPullRequestWithNoCheckoutHasNoIntentsAtAll() {
        ReviewScope gate = new ReviewScope("scope-gate", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty(), Optional.empty());

        assertTrue(intentsWithNoDiff(gate).isEmpty(),
                "there is no diff to group, and borrowing another scope's is the bug");
    }

    /** What the view does for a scope with no loaded diff: group nothing. */
    private List<ReviewIntent> intentsWithNoDiff(ReviewScope scope) {
        return grouping.intentsFor(scope.id(), new UnifiedDiff(List.of()));
    }

    private static void initRepo(Path repo, String branch) throws Exception {
        runGit(repo, "init", "-b", branch);
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
    }

    private static void write(Path repo, String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
