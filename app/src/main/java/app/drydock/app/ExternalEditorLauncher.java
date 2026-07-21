package app.drydock.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens a path in an external editor via a configurable command template
 * (plan section 14.4). Executes the resolved argument list directly through
 * {@link ProcessBuilder} -- never a shell string (plan section 21) -- so a
 * path containing spaces or shell metacharacters cannot be misinterpreted.
 *
 * <p>Known gap (documented rather than silently glossed over, per plan
 * section 27): there is no settings UI yet to let a user configure this
 * template (that belongs to a later milestone alongside the rest of the
 * Settings surface). {@link #DEFAULT_TEMPLATE} is a reasonable hardcoded
 * default (VS Code's {@code code <path>}) rather than a real user
 * preference. It also only substitutes {@code {file}}; plan section 14.4's
 * {@code {line}}/{@code {column}} placeholders are for the file browser
 * (Milestone 6) opening a specific line, not relevant to opening a whole
 * repository root from the sidebar.</p>
 */
public final class ExternalEditorLauncher {

    /** {@code code {file}} -- VS Code, a reasonable default with nothing configured yet. */
    public static final List<String> DEFAULT_TEMPLATE = List.of("code", "{file}");

    private final List<String> commandTemplate;

    public ExternalEditorLauncher() {
        this(DEFAULT_TEMPLATE);
    }

    public ExternalEditorLauncher(List<String> commandTemplate) {
        if (commandTemplate == null || commandTemplate.isEmpty()) {
            throw new IllegalArgumentException("commandTemplate must not be null or empty");
        }
        this.commandTemplate = List.copyOf(commandTemplate);
    }

    /**
     * Substitutes {@code {file}} with {@code path} in every template
     * argument and launches the resulting argument list as a plain
     * process. Throws {@link IOException} (the executable named in the
     * template is not found, or cannot be started) rather than swallowing
     * the failure -- the caller is expected to report it, per plan section
     * 20.
     */
    public void open(Path path) throws IOException {
        List<String> command = commandTemplate.stream()
                .map(arg -> arg.replace("{file}", path.toString()))
                .toList();
        new ProcessBuilder(command).start();
    }
}
