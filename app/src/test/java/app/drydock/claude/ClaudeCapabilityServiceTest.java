package app.drydock.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClaudeCapabilityService} against a hand-written stub {@code
 * claude} shell script (plan section 22.1/22.2: real subprocesses, no
 * mocks), rather than depending on the actual installed Claude Code CLI
 * still being at a fixed version/help-text shape forever.
 */
class ClaudeCapabilityServiceTest {

    private Path writeStub(Path dir, String script) throws IOException {
        Path stub = dir.resolve("claude");
        Files.writeString(stub, script);
        assertTrue(stub.toFile().setExecutable(true));
        return stub;
    }

    @Test
    void detectsAllCapabilitiesWhenHelpAdvertisesThem(@TempDir Path tempDir) throws IOException {
        Path stub = writeStub(tempDir, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  echo "1.2.3 (Claude Code)"
                  exit 0
                fi
                if [ "$1" = "--help" ]; then
                  cat <<'EOF'
                Usage: claude [options]

                Options:
                  -n, --name <name>     Assign a name to the session
                  --resume [sessionId]  Resume a session
                  --fork-session        Fork the current session before resuming
                EOF
                  exit 0
                fi
                exit 1
                """);

        ClaudeCapabilityService service = new ClaudeCapabilityService(new ClaudeExecutableLocator(stub));
        ClaudeCapabilities capabilities = service.detectCapabilities().join();

        assertEquals("1.2.3 (Claude Code)", capabilities.version());
        assertTrue(capabilities.supportsName());
        assertTrue(capabilities.supportsResume());
        assertTrue(capabilities.supportsForkSession());
    }

    @Test
    void defaultsToUnsupportedWhenHelpDoesNotAdvertiseFlags(@TempDir Path tempDir) throws IOException {
        Path stub = writeStub(tempDir, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  echo "0.1.0"
                  exit 0
                fi
                if [ "$1" = "--help" ]; then
                  echo "Usage: claude [options]"
                  echo "  -h, --help  Show help"
                  exit 0
                fi
                exit 1
                """);

        ClaudeCapabilityService service = new ClaudeCapabilityService(new ClaudeExecutableLocator(stub));
        ClaudeCapabilities capabilities = service.detectCapabilities().join();

        assertEquals("0.1.0", capabilities.version());
        assertFalse(capabilities.supportsName());
        assertFalse(capabilities.supportsResume());
        assertFalse(capabilities.supportsForkSession());
    }

    @Test
    void missingClaudeExecutableThrowsClaudeExecutableNotFoundException() {
        ClaudeExecutableLocator missingLocator = new ClaudeExecutableLocator(Path.of("/nonexistent/claude-does-not-exist"));
        ClaudeCapabilityService service = new ClaudeCapabilityService(missingLocator);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.detectCapabilities().join());
        assertInstanceOf(ClaudeExecutableNotFoundException.class, completion.getCause());
        assertTrue(completion.getCause().getMessage().contains("not found"));
    }

    @Test
    void claudeVersionFailureThrowsClaudeVersionCheckFailedException(@TempDir Path tempDir) throws IOException {
        Path stub = writeStub(tempDir, """
                #!/bin/sh
                echo "boom: unsupported flag" 1>&2
                exit 7
                """);

        ClaudeCapabilityService service = new ClaudeCapabilityService(new ClaudeExecutableLocator(stub));

        CompletionException completion = assertThrows(CompletionException.class,
                () -> service.detectCapabilities().join());
        assertInstanceOf(ClaudeVersionCheckFailedException.class, completion.getCause());
        ClaudeVersionCheckFailedException failure = (ClaudeVersionCheckFailedException) completion.getCause();
        assertEquals(7, failure.exitCode());
        assertTrue(failure.stderrExcerpt().contains("boom"));
        assertEquals(stub, failure.executable());
    }
}
