package app.drydock.process;

import app.drydock.app.LoginShellEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    @Test
    void capturesExitCodeAndBothStreams() throws Exception {
        ProcessResult result = ProcessRunner.run(
                List.of("/bin/sh", "-c", "echo out; echo err 1>&2; exit 3"),
                null, Duration.ofSeconds(15));

        assertEquals(3, result.exitCode());
        assertEquals("out", result.stdout().strip());
        assertEquals("err", result.stderr().strip());
    }

    @Test
    void runsInTheGivenWorkingDirectory(@TempDir Path dir) throws Exception {
        ProcessResult result = ProcessRunner.run(List.of("/bin/pwd"), dir, Duration.ofSeconds(15));

        assertEquals(0, result.exitCode());
        assertEquals(dir.toRealPath().toString(), result.stdout().strip());
    }

    @Test
    void killsAHungProcessAndThrowsProcessTimeoutException() {
        List<String> command = List.of("/bin/sleep", "60");
        long start = System.nanoTime();

        ProcessTimeoutException e = assertThrows(ProcessTimeoutException.class,
                () -> ProcessRunner.run(command, null, Duration.ofMillis(500)));

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 30_000, "timed out run must not wait for the child (" + elapsedMillis + "ms)");
        assertEquals(command, e.command());
        assertEquals(Duration.ofMillis(500), e.timeout());
    }

    @Test
    void excerptCapsLongText() {
        String longText = "x".repeat(5000);
        String excerpt = ProcessRunner.excerpt(longText);

        assertTrue(excerpt.length() < longText.length());
        assertTrue(excerpt.endsWith("..."));
        assertEquals("short", ProcessRunner.excerpt("  short  "));
    }

    @Test
    void discardInputClosesStdinSoAReaderExitsImmediately() throws Exception {
        // Without discardInput, `cat` blocks on the inherited pipe until the
        // timeout kills it. With it, cat sees EOF at once and exits 0.
        ProcessResult result = ProcessRunner.run(
                List.of("/bin/cat"),
                new ProcessRunner.Options(null, Duration.ofSeconds(5), true, Map.of()));

        assertEquals(0, result.exitCode());
        assertEquals("", result.stdout());
    }

    @Test
    void environmentEntriesReachTheChild() throws Exception {
        ProcessResult result = ProcessRunner.run(
                List.of("/bin/sh", "-c", "printf %s \"$DRYDOCK_TEST_VAR\""),
                new ProcessRunner.Options(null, Duration.ofSeconds(5), true,
                        Map.of("DRYDOCK_TEST_VAR", "set-by-test")));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("set-by-test"), result.stdout());
    }

    @Test
    void boundedRunDrainsButRefusesOversizedOutput() {
        ProcessRunner.Options options = new ProcessRunner.Options(
                null, Duration.ofSeconds(5), true, Map.of());

        IOException failure = assertThrows(IOException.class, () -> ProcessRunner.run(
                List.of("/usr/bin/printf", "%s", "x".repeat(2048)), options, 1024));

        assertTrue(failure.getMessage().contains("exceeded 1024 bytes"), failure.getMessage());
    }

    @Test
    void aChildRunsWithTheLoginShellsPathUnlessTheCallerSetsOne(@TempDir Path tools) throws Exception {
        // A superset of the real PATH, so the other tests in this JVM still find their tools.
        String original = LoginShellEnvironment.currentRealPath();
        String shell = original + ":" + tools;
        LoginShellEnvironment.useShellPath(shell);
        try {
            ProcessResult inherited = ProcessRunner.run(List.of("/bin/sh", "-c", "printf %s \"$PATH\""),
                    null, Duration.ofSeconds(15));
            assertEquals(shell, inherited.stdout(), "the login shell's PATH, not the JVM's");

            ProcessResult own = ProcessRunner.run(List.of("/bin/sh", "-c", "printf %s \"$PATH\""),
                    new ProcessRunner.Options(null, Duration.ofSeconds(15), false, Map.of("PATH", "/usr/bin:/bin")));
            assertEquals("/usr/bin:/bin", own.stdout(), "a caller's PATH wins");
        } finally {
            LoginShellEnvironment.useShellPath(original);
        }
    }

    @Test
    void aBlankShellPathChangesNothing() {
        String before = LoginShellEnvironment.currentRealPath();
        LoginShellEnvironment.useShellPath(" ");
        LoginShellEnvironment.useShellPath(null);
        assertEquals(before, LoginShellEnvironment.currentRealPath());
    }
}
