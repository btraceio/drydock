package app.drydock.agent.providers.claude.internal;

import app.drydock.agent.api.SphinxReviewSupport;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Probes whether the Claude Code installation carries the sphinx plugin, by
 * reading Claude's own plugin registry:
 * {@code ~/.claude/plugins/installed_plugins.json} (version-2 schema), whose
 * top-level {@code plugins} object is keyed {@code <name>@<marketplace>}.
 * The plugin is present when any key's name part is {@code sphinx} --
 * the marketplace is whatever installed it.
 *
 * <p>This covers user-scope installs only. A plugin enabled per project
 * (the repo's {@code .claude/settings.json} {@code enabledPlugins}) without a
 * user-scope install is not seen here -- an accepted false negative: the
 * affected affordance is hidden, never wrongly offered.</p>
 *
 * <p>Lenient by contract, like {@code PiModelCatalog}: an unreadable or
 * malformed file yields empty -- a broken plugin registry must cost the
 * affordance, not the app.</p>
 */
public final class ClaudeSphinxProbe {

    private static final Logger LOG = System.getLogger(ClaudeSphinxProbe.class.getName());

    private static final String PLUGIN_NAME = "sphinx";
    private static final String NAME_MARKETPLACE_SEPARATOR = "@";

    private ClaudeSphinxProbe() {
    }

    /**
     * The plugin registry Claude Code maintains: {@code drydock.claude.installedPluginsJson}
     * when set (test seam; the real file is user-global, so a test must be
     * able to point the reader elsewhere), else {@code
     * ~/.claude/plugins/installed_plugins.json}.
     */
    public static Path defaultInstalledPluginsJson() {
        String override = System.getProperty("drydock.claude.installedPluginsJson");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".claude", "plugins", "installed_plugins.json");
    }

    public static Optional<SphinxReviewSupport> probe() {
        return probe(defaultInstalledPluginsJson());
    }

    public static Optional<SphinxReviewSupport> probe(Path installedPluginsJson) {
        if (installedPluginsJson == null || !Files.isRegularFile(installedPluginsJson)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(installedPluginsJson);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read " + installedPluginsJson, e);
            return Optional.empty();
        }
        try {
            JsonValue parsed = JsonParser.parse(content);
            if (!(parsed instanceof JsonValue.JsonObject root)
                    || !(root.members().get("plugins") instanceof JsonValue.JsonObject plugins)) {
                return Optional.empty();
            }
            boolean installed = plugins.members().keySet().stream()
                    .anyMatch(key -> key.equals(PLUGIN_NAME)
                            || key.startsWith(PLUGIN_NAME + NAME_MARKETPLACE_SEPARATOR));
            return installed ? Optional.of(new SphinxReviewSupport(SphinxReviewSupport.Invocation.SLASH_COMMAND))
                    : Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not parse " + installedPluginsJson, e);
            return Optional.empty();
        }
    }
}
