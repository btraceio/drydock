package app.drydock.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argv the submit's PR lookups hand gh: {@code gh pr view|diff <flags> -- <n>}. Without the
 * {@code pr} subcommand gh has no such command, and it rejects {@code --end-of-options}; both made
 * the fresh-head check answer nothing.
 */
class GitHubReviewServicePrCommandTest {

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theHeadLookupIsAPrViewWithTheNumberAfterTheOptions(@TempDir Path dir) throws Exception {
        Path receipt = scriptedGh(dir, "{\"headRefOid\":\"abc123\"}");

        Optional<String> out = GitHubReviewService.runGhWith(dir.resolve("bin/gh"), dir, "view",
                List.of("--json", "headRefOid"), 73);

        assertEquals(Optional.of("{\"headRefOid\":\"abc123\"}\n"), out);
        assertTrue(Files.readString(receipt).contains("args=pr view --json headRefOid -- 73"), Files.readString(receipt));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theDiffLookupIsAPrDiff(@TempDir Path dir) throws Exception {
        Path receipt = scriptedGh(dir, "diff --git a/x b/x");

        GitHubReviewService.runGhWith(dir.resolve("bin/gh"), dir, "diff", List.of(), 73);

        assertTrue(Files.readString(receipt).contains("args=pr diff -- 73"), Files.readString(receipt));
    }

    private static Path scriptedGh(Path dir, String answer) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path receipt = dir.resolve("receipt.txt");
        Files.writeString(bin.resolve("gh"),
                "#!/bin/sh\nprintf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                        + "printf '%s\\n' '" + answer + "'\nexit 0\n");
        bin.resolve("gh").toFile().setExecutable(true);
        return receipt;
    }
}
