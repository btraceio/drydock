package app.cpm.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
}
