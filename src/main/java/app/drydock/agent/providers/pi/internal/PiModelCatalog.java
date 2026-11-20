package app.drydock.agent.providers.pi.internal;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates pi's launchable models. Primary source: {@code
 * <agentDir>/models.json}, read directly -- the user's own provider/model
 * configuration, and every id in it is launchable by construction, so a file
 * read beats forking {@code pi --list-models} (a subprocess that needs
 * {@code node} on PATH and a 30 s bound, and that a Finder-launched IDE
 * process cannot satisfy). Fallback: the {@code pi --list-models} probe,
 * through {@link ProcessRunner} (AGENTS.md: never a raw {@code
 * ProcessBuilder}), for an install that relies on pi's bundled catalog
 * rather than a models.json.
 *
 * <p>Launchable model ids are the {@code id} fields verbatim -- the same
 * strings {@code pi --list-models} prints in its model column and
 * {@code --model} accepts. (Ids conventionally carry their upstream path,
 * e.g. {@code bedrock-openai/openai.gpt-5.4}; the provider key is a separate
 * column there, not part of the id.)</p>
 *
 * <p>The probe's output is columnar -- {@code provider␠␠model␠␠context…} --
 * with columns separated by runs of two or more spaces; the model id itself
 * never contains a run of spaces. The header row is skipped by its exact
 * first two column labels ({@code provider}, {@code model}), so a future
 * column insert does not shift the parse.</p>
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

    /**
     * The agent directory's models.json: {@code PI_CODING_AGENT_DIR} when
     * set (the same override pi itself honors), else {@code
     * ~/.pi/agent/models.json}.
     */
    public static Path defaultModelsJson() {
        // Test seam, mirroring the build's drydock.projectsFile: the real file
        // is user-global, so a test must be able to point the reader elsewhere.
        String override = System.getProperty("drydock.pi.modelsJson");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String agentDir = System.getenv("PI_CODING_AGENT_DIR");
        if (agentDir != null && !agentDir.isBlank()) {
            return Path.of(agentDir, "models.json");
        }
        return Path.of(System.getProperty("user.home"), ".pi", "agent", "models.json");
    }

    /**
     * The launchable model ids configured in {@code modelsJson}, in file
     * order: one id per {@code providers.<key>.models[].id} entry, verbatim.
     * An unreadable, malformed, or empty file yields
     * an empty list -- a suggestion, never a whitelist, and the caller falls
     * back to the CLI probe. Never throws for a malformed entry; a malformed
     * model entry costs itself, not the catalog.
     */
    public static List<String> fromConfig(Path modelsJson) {
        if (modelsJson == null || !Files.isRegularFile(modelsJson)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(modelsJson);
        } catch (IOException e) {
            return List.of();
        }
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(content);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!(parsed instanceof JsonValue.JsonObject root)
                || !(root.get("providers") instanceof JsonValue.JsonObject providers)) {
            return List.of();
        }
        Set<String> models = new LinkedHashSet<>();
        for (var provider : providers.members().entrySet()) {
            if (!(provider.getValue() instanceof JsonValue.JsonObject config)
                    || !(config.get("models") instanceof JsonValue.JsonArray entries)) {
                continue;
            }
            for (JsonValue entry : entries.elements()) {
                if (!(entry instanceof JsonValue.JsonObject model)) {
                    continue;
                }
                String id = str(model.get("id"), null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                models.add(id);
            }
        }
        return new ArrayList<>(models);
    }

    /**
     * The model ids pi can launch with, per the given probe command (already
     * shaped by the caller's {@code piProcessCommand}, which puts a resolved
     * {@code node} directory on PATH for the launcher script), or an empty
     * list on any failure/timeout/missing executable.
     */
    public static List<String> list(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        List<String> argv = new ArrayList<>(command);
        argv.add("--list-models");
        try {
            ProcessResult result = ProcessRunner.run(argv, null, PROCESS_TIMEOUT);
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

    private static String str(JsonValue value, String fallback) {
        if (value instanceof JsonValue.JsonString string) {
            String text = string.value();
            return text == null || text.isBlank() ? fallback : text;
        }
        return fallback;
    }
}
