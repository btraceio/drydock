package app.drydock.agent.providers.pi.internal;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Probes {@code pi --version} via {@link ProcessRunner} (AGENTS.md: never a
 * raw {@link ProcessBuilder}) and parses the version output.
 * Mirrors {@link app.drydock.agent.providers.codex.internal.CodexVersionProbe}.
 */
public final class PiVersionProbe {

    /** Generous for a --version probe, but never unbounded. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    private PiVersionProbe() {
    }

    /** Returns the parsed version, or {@code "unknown"} on any failure/timeout/missing executable. */
    public static String probe(Path piExecutable) {
        if (piExecutable == null) {
            return "unknown";
        }
        try {
            ProcessResult result = ProcessRunner.run(
                    List.of(piExecutable.toString(), "--version"), null, PROCESS_TIMEOUT);
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

    /** Pure parse of a single {@code --version} output line. Handles both bare versions like {@code "0.71.1"} and prefixed formats like {@code "pi 0.71.1"}. */
    static String parseVersion(String line) {
        if (line == null) {
            return "unknown";
        }
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return "unknown";
        }
        // If the line contains a space (e.g., "pi 0.71.1"), return the last token (the version)
        if (stripped.contains(" ")) {
            String[] tokens = stripped.split("\\s+");
            return tokens[tokens.length - 1];
        }
        // Otherwise return the stripped line as-is (bare version like "0.71.1")
        return stripped;
    }
}
