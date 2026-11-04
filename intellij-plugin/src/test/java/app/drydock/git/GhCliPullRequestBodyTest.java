package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GhCliService#pullRequestBodyWith}: the argv gh receives, and what an empty or failed answer is. */
class GhCliPullRequestBodyTest {

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theBodyIsReadByThePrNumber(@TempDir Path dir) throws Exception {
        Path receipt = scriptedGh(dir, "{\"body\":\"Callers use bar().\\n\\nDetails.\"}", 0);

        assertEquals(Optional.of("Callers use bar().\n\nDetails."),
                GhCliService.pullRequestBodyWith(dir.resolve("bin/gh"), dir, 858));
        assertTrue(Files.readString(receipt).contains("args=pr view 858 --json body"));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aBlankBodyAFailureAndABadNumberAreNoDescription(@TempDir Path dir) throws Exception {
        scriptedGh(dir, "{\"body\":\"  \"}", 0);
        assertEquals(Optional.empty(), GhCliService.pullRequestBodyWith(dir.resolve("bin/gh"), dir, 1));

        Path failing = Files.createDirectories(dir.resolve("f"));
        scriptedGh(failing, "", 1);
        assertEquals(Optional.empty(), GhCliService.pullRequestBodyWith(failing.resolve("bin/gh"), failing, 1));

        Path receipt = scriptedGh(Files.createDirectories(dir.resolve("n")), "{}", 0);
        assertEquals(Optional.empty(), GhCliService.pullRequestBodyWith(dir.resolve("n/bin/gh"), dir, 0));
        assertFalse(Files.exists(receipt), "a non-positive number never reaches gh");
    }

    private static Path scriptedGh(Path dir, String answer, int exit) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path receipt = dir.resolve("receipt.txt");
        Files.writeString(bin.resolve("gh"),
                "#!/bin/sh\nprintf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                        + "printf '%s\\n' '" + answer + "'\n"
                        + "exit " + exit + "\n");
        bin.resolve("gh").toFile().setExecutable(true);
        return receipt;
    }
}
