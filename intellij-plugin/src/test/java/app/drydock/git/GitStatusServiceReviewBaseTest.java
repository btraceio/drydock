package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The btrace shape: origin/HEAD says master, every branch is cut from
 * develop. Diffing against master rendered a five-file branch as fourteen
 * hundred files, and nothing on screen said why -- so the base now carries
 * how it was chosen.
 */
class GitStatusServiceReviewBaseTest {

    private final GitStatusService service = new GitStatusService();

    @Test
    void aBranchCutFromDevelopResolvesToDevelopAndSaysSo() throws Exception {
        Path repo = repoWithDevelopCutBranch();

        ReviewBase base = service.reviewBase(repo, Optional.empty(), "master").get();

        assertEquals("develop", base.ref());
        assertEquals(ReviewBase.Origin.FORKED_FROM, base.origin());
    }

    @Test
    void aDeclaredPullRequestBaseWins() throws Exception {
        Path repo = repoWithDevelopCutBranch();

        ReviewBase base = service.reviewBase(repo, Optional.of("master"), "master").get();

        assertEquals("master", base.ref());
        assertEquals(ReviewBase.Origin.PULL_REQUEST, base.origin());
    }

    @Test
    void anUnmeasurableRepositoryFallsBackAndAdmitsIt() throws Exception {
        // An empty repository: HEAD is unborn, so no candidate can be counted.
        Path repo = Files.createTempDirectory("drydock-unborn");
        runGit(repo, "init", "-b", "main");

        ReviewBase base = service.reviewBase(repo, Optional.empty(), "main").get();

        assertEquals("main", base.ref());
        assertEquals(ReviewBase.Origin.DEFAULT_UNMEASURED, base.origin());
    }

    /** master, then a develop cut from it that moved on, then a branch cut from develop. */
    private static Path repoWithDevelopCutBranch() throws Exception {
        Path repo = Files.createTempDirectory("drydock-base");
        runGit(repo, "init", "-b", "master");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("base.txt"), "base\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "master commit");

        runGit(repo, "checkout", "-b", "develop");
        for (int i = 0; i < 5; i++) {
            Files.writeString(repo.resolve("infra" + i + ".txt"), "infra\n");
            runGit(repo, "add", ".");
            runGit(repo, "commit", "-m", "infra " + i);
        }

        runGit(repo, "checkout", "-b", "feature/thing");
        Files.writeString(repo.resolve("feature.txt"), "feature\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "the feature");
        return repo;
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
