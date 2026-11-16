package app.drydock.git;

import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.MAC, OS.LINUX})
class GhCliPullRequestTargetTest {
    private static Path gh(Path dir, String output, int exit) throws Exception {
        Path script = dir.resolve("scripted-gh");
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + dir.resolve("args") + "'\n"
                + "printf '%s' '" + output + "'\nexit " + exit + "\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    @Test
    void queriesExactRepositoryAndNumberNotAHeadBranch(@TempDir Path dir) throws Exception {
        Path script = gh(dir, """
                {"number":898,"state":"CLOSED","isDraft":false,"headRefName":"main",
                 "title":"A fork PR","url":"https://github.com/DataDog/java-profiler/pull/898"}
                """, 0);
        var result = GhCliService.pullRequestWith(script, dir,
                Optional.of("github.com/DataDog/java-profiler"), 898).orElseThrow();
        assertEquals(898, result.number());
        assertEquals(PrLifecycle.CLOSED, result.state());
        assertEquals(List.of("pr", "view", "898", "--json", "number,state,isDraft,headRefName,title,url",
                "--repo", "github.com/DataDog/java-profiler"), Files.readAllLines(dir.resolve("args")));
    }

    @Test
    void failedAndMalformedLookupsDoNotReturnAnotherPr(@TempDir Path dir) throws Exception {
        assertTrue(GhCliService.pullRequestWith(gh(dir, "offline", 1), dir, Optional.of("o/r"), 898).isEmpty());
        assertTrue(GhCliService.pullRequestWith(gh(dir, "not json", 0), dir, Optional.of("o/r"), 898).isEmpty());
        assertTrue(GhCliService.pullRequestWith(gh(dir,
                "{\"number\":943,\"headRefName\":\"branch\"}", 0), dir, Optional.of("o/r"), 898).isEmpty());
        Files.delete(dir.resolve("args"));
        assertThrows(IllegalArgumentException.class,
                () -> GhCliService.pullRequestWith(dir.resolve("scripted-gh"), dir, Optional.of("--bad"), 898));
        assertFalse(Files.exists(dir.resolve("args")));
    }
}
