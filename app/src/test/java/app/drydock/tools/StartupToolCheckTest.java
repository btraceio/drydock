package app.drydock.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupToolCheckTest {

    @Test
    void everythingPresentSatisfiesTheGate() {
        StartupToolCheck.Result result = StartupToolCheck.check(tool -> true);
        assertTrue(result.satisfied());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void missingGitIsReportedAlone() {
        StartupToolCheck.Result result = StartupToolCheck.check(
                tool -> tool != ExternalTool.GIT);
        assertTrue(result.satisfied() == false);
        assertEquals(1, result.missing().size());
        assertEquals(ExternalTool.GIT, result.missing().get(0));
    }

    @Test
    void noHarnessReportsEveryHarness() {
        StartupToolCheck.Result result = StartupToolCheck.check(
                tool -> tool == ExternalTool.GIT);
        assertTrue(result.satisfied() == false);
        assertEquals(ExternalTool.HARNESSES, List.copyOf(result.missing()));
    }

    @Test
    void oneHarnessAmongThreeSatisfiesTheHarnessRequirement() {
        StartupToolCheck.Result result = StartupToolCheck.check(
                tool -> tool == ExternalTool.GIT || tool == ExternalTool.CLAUDE);
        assertTrue(result.satisfied());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void diagModeDropsTheHarnessRequirementButNotGit() {
        // app.drydock.diag.command replaces the provider command: no agent
        // CLI needed, but git is still what every repository surface runs.
        StartupToolCheck.Result result = StartupToolCheck.check(tool -> false, false);
        assertTrue(result.satisfied() == false);
        assertEquals(1, result.missing().size());
        assertEquals(ExternalTool.GIT, result.missing().get(0));

        assertTrue(StartupToolCheck.check(tool -> tool == ExternalTool.GIT, false).satisfied());
    }

    // A compile-time guard more than a behavioral one: the record's
    // satisfied() must agree with missing().isEmpty() for every path above,
    // which the assertions pin down. This last test exercises the
    // everything-present shape directly.
    @Test
    void everythingPresentYieldsAnEmptyMissingList(@TempDir Path tempDir) throws IOException {
        Path git = tempDir.resolve("git");
        Files.writeString(git, "#!/bin/sh\nexit 0\n");
        git.toFile().setExecutable(true);
        assertTrue(Files.isExecutable(git));
        StartupToolCheck.Result result = StartupToolCheck.check(tool -> true);
        assertTrue(result.missing().isEmpty());
        assertTrue(result.satisfied());
    }
}
