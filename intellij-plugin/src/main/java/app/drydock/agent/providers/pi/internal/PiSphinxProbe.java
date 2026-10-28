package app.drydock.agent.providers.pi.internal;

import app.drydock.agent.api.SphinxReviewSupport;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Probes whether the Pi installation carries the sphinx skill, in either
 * place pi loads skills from: the agent directory's own {@code
 * skills/sphinx/SKILL.md}, or any package listed in {@code
 * settings.json}'s {@code packages} array whose root ships {@code
 * skills/sphinx/SKILL.md} (a package entry is {@code npm:<name>} -- resolved
 * under the agent directory's {@code npm/node_modules} -- or a filesystem
 * path, absolute or relative to the agent directory).
 *
 * <p>Pi has no {@code /sphinx} prompt template (the package ships skills,
 * not prompts), so a positive probe reports {@link
 * SphinxReviewSupport.Invocation#SKILL_REQUEST}: the invocation is a prose
 * request the harness routes to the skill.</p>
 *
 * <p>Lenient by contract, like {@link PiModelCatalog}: an unreadable or
 * malformed settings file yields empty -- a broken config must cost the
 * affordance, not the app.</p>
 */
public final class PiSphinxProbe {

    private static final Logger LOG = System.getLogger(PiSphinxProbe.class.getName());

    private static final String SKILL_FILE = Path.of("skills", "sphinx", "SKILL.md").toString();
    private static final String NPM_PREFIX = "npm:";

    private PiSphinxProbe() {
    }

    public static Optional<SphinxReviewSupport> probe() {
        return probe(agentDir());
    }

    /**
     * The pi agent directory: {@code PI_CODING_AGENT_DIR} when set (the same
     * override pi itself honors, see {@link PiModelCatalog}), else {@code
     * ~/.pi/agent}. Overridable per call so tests stay hermetic.
     */
    public static Path agentDir() {
        String agentDir = System.getenv("PI_CODING_AGENT_DIR");
        if (agentDir != null && !agentDir.isBlank()) {
            return Path.of(agentDir);
        }
        return Path.of(System.getProperty("user.home"), ".pi", "agent");
    }

    public static Optional<SphinxReviewSupport> probe(Path agentDir) {
        if (agentDir == null) {
            return Optional.empty();
        }
        if (Files.isRegularFile(agentDir.resolve(SKILL_FILE))) {
            return Optional.of(skillSupport());
        }
        Path settings = agentDir.resolve("settings.json");
        if (!Files.isRegularFile(settings)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(settings);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read " + settings, e);
            return Optional.empty();
        }
        try {
            JsonValue parsed = JsonParser.parse(content);
            if (!(parsed instanceof JsonValue.JsonObject root)
                    || !(root.members().get("packages") instanceof JsonValue.JsonArray packages)) {
                return Optional.empty();
            }
            for (JsonValue entry : packages.elements()) {
                if (!(entry instanceof JsonValue.JsonString packageRef)
                        || packageRef.value().isBlank()) {
                    continue;
                }
                if (Files.isRegularFile(resolvePackageRoot(agentDir, packageRef.value()).resolve(SKILL_FILE))) {
                    return Optional.of(skillSupport());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not parse " + settings, e);
            return Optional.empty();
        }
    }

    /** A package entry's root on disk: {@code npm:<name>} under the agent dir's node_modules, else the path itself. */
    static Path resolvePackageRoot(Path agentDir, String packageRef) {
        if (packageRef.startsWith(NPM_PREFIX)) {
            return agentDir.resolve(Path.of("npm", "node_modules", packageRef.substring(NPM_PREFIX.length())));
        }
        Path path = Path.of(packageRef);
        return path.isAbsolute() ? path : agentDir.resolve(path);
    }

    private static SphinxReviewSupport skillSupport() {
        return new SphinxReviewSupport(SphinxReviewSupport.Invocation.SKILL_REQUEST);
    }
}
