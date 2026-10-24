package app.drydock.agent.providers.pi.internal;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates pi's launchable models via {@code pi --list-models} (the only
 * one of the three harnesses with a listing command), through
 * {@link ProcessRunner} (AGENTS.md: never a raw {@link ProcessBuilder}).
 * Mirrors {@link PiVersionProbe}.
 *
 * <p>The output is columnar -- {@code provider␠␠model␠␠context␠␠max-out␠␠thinking␠␠images}
 * -- with columns separated by runs of two or more spaces; the model id itself
 * never contains a run of spaces (ids are {@code provider/model} paths like
 * {@code ai-gw-bedrock/bedrock/openai.gpt-5.4}), so the second
 * two-space-separated field is the id. The header row is skipped by its exact
 * first two column labels ({@code provider}, {@code model}), not by position
 * or a bare prefix, so a future column insert does not shift the parse and a
 * provider named {@code provider-…} is kept.</p>
 */
public final class PiModelCatalog {

    /** Generous for a local catalog dump; never unbounded. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The first two columns of a data row: a provider token (group 1), a
     * two-space column gap, the model id (group 2), then the next column gap.
     * Applied line by line so a malformed row costs one line, not the whole
     * catalog.
     */
    private static final Pattern DATA_ROW = Pattern.compile("^(\\S+)\\s{2,}(\\S+)\\s{2,}");

    private PiModelCatalog() {
    }

    /** The model ids pi can launch with, or an empty list on any failure/timeout/missing executable. */
    public static List<String> list(Path piExecutable) {
        if (piExecutable == null) {
            return List.of();
        }
        try {
            ProcessResult result = ProcessRunner.run(
                    List.of(piExecutable.toString(), "--list-models"), null, PROCESS_TIMEOUT);
            if (result.exitCode() != 0) {
                return List.of();
            }
            return parse(result.stdout());
        } catch (IOException | ProcessTimeoutException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Pure parse of {@code pi --list-models} output, unit-tested without a subprocess. */
    static List<String> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        // LinkedHashSet: a repeated id (two catalog sources listing the same
        // model) must appear once, and first-seen order keeps the popup stable.
        Set<String> models = new LinkedHashSet<>();
        for (String line : output.split("\n")) {
            Matcher m = DATA_ROW.matcher(line);
            if (!m.find()) {
                continue;   // a malformed row costs one line, not the whole catalog
            }
            if (m.group(1).equals("provider") && m.group(2).equals("model")) {
                continue;   // the header row, recognised by its exact first two column labels
            }
            models.add(m.group(2));
        }
        return new ArrayList<>(models);
    }
}
