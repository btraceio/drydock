package app.drydock.agent.providers.codex.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexExecHelpProbeTest {

    @Test
    void parserRequiresBothDetachedWriteCapabilities() {
        assertTrue(CodexExecHelpProbe.supportsDetachedWrite("Options: --add-dir <DIR> workspace-write"));
        assertFalse(CodexExecHelpProbe.supportsDetachedWrite("Options: --add-dir <DIR>"));
        assertFalse(CodexExecHelpProbe.supportsDetachedWrite("sandbox modes: workspace-write"));
        assertFalse(CodexExecHelpProbe.supportsDetachedWrite(""));
        assertFalse(CodexExecHelpProbe.supportsDetachedWrite(null));
    }

    @Test
    void aNonzeroHelpCommandIsDefinitivelyUnsupported(@TempDir Path dir) throws Exception {
        Path executable = writeExecutable(dir, "printf '%s\\n' '--add-dir workspace-write'\nexit 9\n");

        CodexExecHelpProbe.Result result = CodexExecHelpProbe.probe(executable);

        assertEquals(CodexExecHelpProbe.Outcome.UNSUPPORTED, result.outcome());
        assertTrue(result.cacheable());
        assertTrue(result.reason().contains("status 9"), result.reason());
    }

    @Test
    void outputBeyondThePerStreamLimitFailsClosedInsteadOfParsingTruncatedHelp(@TempDir Path dir) throws Exception {
        Path executable = writeExecutable(dir, "printf '%s\\n' '--add-dir workspace-write'\nhead -c 262145 /dev/zero\n");

        CodexExecHelpProbe.Result result = CodexExecHelpProbe.probe(executable);

        assertEquals(CodexExecHelpProbe.Outcome.FAILED, result.outcome());
        assertFalse(result.cacheable());
        assertTrue(result.reason().contains("exceeded 262144 bytes"), result.reason());
    }

    @Test
    void interruptionFailsClosedAndRestoresTheCallingThreadsInterruptFlag(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("probe-started");
        Path executable = writeExecutable(dir, "printf started > '" + marker.toString().replace("'", "'\\''")
                + "'\nexec sleep 30\n");
        AtomicReference<CodexExecHelpProbe.Result> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            result.set(CodexExecHelpProbe.probe(executable));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "codex-help-probe-test");
        caller.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(marker) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (!Files.exists(marker)) {
            caller.interrupt();
            caller.join(5_000);
            throw new AssertionError("probe executable was not started");
        }
        caller.interrupt();
        caller.join(5_000);

        assertFalse(caller.isAlive(), "the interrupted ProcessRunner call must terminate");
        assertEquals(CodexExecHelpProbe.Outcome.FAILED, result.get().outcome());
        assertTrue(interrupted.get(), "the probe must restore interruption before returning");
    }

    private static Path writeExecutable(Path directory, String body) throws Exception {
        Path executable = directory.resolve("codex");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        assertTrue(executable.toFile().setExecutable(true));
        return executable;
    }
}
