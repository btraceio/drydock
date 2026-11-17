package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRunnerTest {

    /** A fake ProcessRunner seam: records what the seam passed and replies with a canned result. */
    private static final class RecordingProduction extends CommandRunner.Production {
        final List<String> argv = new ArrayList<>();
        ProcessRunner.Options options;
        int maxOutputBytes;
        ProcessResult reply;
        Exception failure;

        @Override
        protected ProcessResult processRun(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            this.argv.addAll(argv);
            this.options = options;
            this.maxOutputBytes = maxOutputBytes;
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            return reply;
        }
    }

    @Test
    void productionDelegatesArgvOptionsAndCapUnchanged() {
        RecordingProduction production = new RecordingProduction();
        production.reply = new ProcessResult(0, "stdout", "stderr");
        List<String> argv = List.of("ssh", "-o", "BatchMode=yes", "--", "workspace-drydock-x", "true");
        ProcessRunner.Options options = new ProcessRunner.Options(Path.of("/tmp"), Duration.ofSeconds(10),
                true, Map.of("LANG", "C"));

        ProcessResult result;
        try {
            result = production.run(argv, options, 256 * 1024);
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("unexpected checked failure from the fake seam", e);
        }

        assertIterableEquals(argv, production.argv);
        assertSame(options, production.options);
        assertEquals(256 * 1024, production.maxOutputBytes);
        assertSame(production.reply, result, "the seam returns the ProcessRunner result unchanged");
    }

    @Test
    void productionPropagatesProcessRunnerFailureModes() {
        RecordingProduction production = new RecordingProduction();
        production.failure = new ProcessTimeoutException(List.of("workspaces", "list"), Duration.ofSeconds(30));
        ProcessTimeoutException timeout = assertThrows(ProcessTimeoutException.class,
                () -> production.run(List.of("workspaces", "list"),
                        new ProcessRunner.Options(null, Duration.ofSeconds(30), true, Map.of()), 4096));
        assertSame(production.failure, timeout);

        RecordingProduction notInstalled = new RecordingProduction();
        notInstalled.failure = new IOException("Cannot run program \"workspaces\": No such file or directory");
        IOException io = assertThrows(IOException.class,
                () -> notInstalled.run(List.of("workspaces", "list"),
                        new ProcessRunner.Options(null, Duration.ofSeconds(30), true, Map.of()), 4096));
        assertSame(notInstalled.failure, io);

        InterruptedException interrupted = assertThrows(InterruptedException.class,
                () -> new CommandRunner.Production() {
                    @Override
                    protected ProcessResult processRun(List<String> argv, ProcessRunner.Options options,
                            int maxOutputBytes) throws IOException, InterruptedException {
                        throw new InterruptedException("cancelled");
                    }
                }.run(List.of("ssh"), new ProcessRunner.Options(null, Duration.ofSeconds(1), false, Map.of()), 64));
        assertEquals("cancelled", interrupted.getMessage());
    }

    @Test
    void sharedProductionConstantIsTheDelegatingImplementation() {
        assertInstanceOf(CommandRunner.Production.class, CommandRunner.PRODUCTION,
                "PRODUCTION must be the pure-delegation implementation, so every caller inherits the same seam");
        assertTrue(CommandRunner.PRODUCTION != null);
    }
}
