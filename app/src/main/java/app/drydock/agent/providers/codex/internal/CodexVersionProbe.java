package app.drydock.agent.providers.codex.internal;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Probes {@code codex --version} via {@link ProcessRunner} (AGENTS.md: never a
 * raw {@link ProcessBuilder}) and parses the {@code "codex-cli 0.144.5"} style
 * output down to just the version token. Mirrors how {@code
 * ClaudeCapabilityService} invokes {@code claude --version}.
 */
public final class CodexVersionProbe {

    /** Generous for a --version probe, but never unbounded. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final String CODEX_CLI_PREFIX = "codex-cli ";

    private CodexVersionProbe() {
    }

    /** Returns the parsed version, or {@code "unknown"} on any failure/timeout/missing executable. */
    public static String probe(Path codexExecutable) {
        if (codexExecutable == null) {
            return "unknown";
        }
        try {
            ProcessResult result = ProcessRunner.run(
                    List.of(codexExecutable.toString(), "--version"), null, PROCESS_TIMEOUT);
            if (result.exitCode() != 0) {
                return "unknown";
            }
            String firstLine = result.stdout().lines().findFirst().orElse("");
            return parseVersion(firstLine);
        } catch (IOException | ProcessTimeoutException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    /** Pure parse of a single {@code --version} output line, stripped of the {@code "codex-cli "} prefix if present. */
    static String parseVersion(String line) {
        if (line == null) {
            return "unknown";
        }
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return "unknown";
        }
        if (stripped.startsWith(CODEX_CLI_PREFIX)) {
            String token = stripped.substring(CODEX_CLI_PREFIX.length()).strip();
            return token.isEmpty() ? "unknown" : token;
        }
        return stripped;
    }
}
