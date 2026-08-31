package app.drydock.agent.providers.claude.internal;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.drydock.agent.api.EvalTokenResolver;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

/**
 * Runs an eval-mode Claude session inside a container so the eval-specific
 * {@code x-target-account: eval} request header can be carried in a local
 * settings file, instead of injected through the host's omlx_proxy.
 *
 * <p><b>Why a container at all.</b> Claude Code's managed settings
 * ({@code /Library/Application Support/ClaudeCode/managed-settings.json} on
 * macOS, {@code /etc/claude-code/managed-settings.json} on Linux) are the
 * highest-precedence scope and cannot be overridden by env vars or
 * {@code --settings}. They pin {@code ANTHROPIC_BASE_URL} at the host
 * omlx_proxy and carry {@code ANTHROPIC_CUSTOM_HEADERS}; drydock cannot add
 * a header to them. But inside a container there is no managed-settings
 * file, so a settings file drydock seeds in {@code CLAUDE_CONFIG_DIR} is the
 * only source of truth. That seed is the host's managed settings (so auth,
 * base URL, and telemetry stay the same) overlaid onto the user's personal
 * settings (so permissions, model, theme, and enabledPlugins carry over),
 * with the host-bound members stripped and two eval edits applied: the base
 * URL is rewritten to {@code host.docker.internal} so the container can still
 * reach the host's omlx_proxy, and {@code x-target-account: eval} is appended
 * to {@code ANTHROPIC_CUSTOM_HEADERS}. The auth token is resolved on the host
 * (where {@code ddtool} lives) and passed in as {@code ANTHROPIC_API_KEY},
 * so {@code apiKeyHelper} is dropped from the seed.</p>
 *
 * <p><b>Mirroring the host setup.</b> A bare factory container would
 * re-confirm everything (onboarding, trust dialogs, permission prompts)
 * and lack the user's skills and plugins, which is not a session anyone
 * wants. {@link #mirrorPersonalConfig} therefore seeds the config dir with
 * a copy of {@code ~/.claude.json} (onboarding/trust/project state, so
 * nothing asks again) and symlinks the personal content dirs
 * ({@code skills}, {@code agents}, {@code commands}, {@code CLAUDE.md})
 * into {@code ~/.claude}; {@link #wrap} bind-mounts {@code ~/.claude}
 * read-only at its original host path so the symlinks and the plugin
 * state's absolute install paths resolve unchanged. The {@code plugins}
 * dir is mirrored separately ({@link #mirrorPluginState}): its state files
 * are copied per-session (writable, so the session can materialize
 * marketplaces and auto-install plugins without touching the host store)
 * while its content dirs are symlinked read-only.
 * Directory-source marketplaces -- materialized in
 * {@code ~/.claude/plugins/known_marketplaces.json} or only declared in
 * the user's {@code extraKnownMarketplaces} -- that live outside
 * {@code ~/.claude} get their own read-only mounts, for the same reason; a
 * declaration that has not been materialized on the host yet has its
 * known-marketplaces entry fabricated into the per-session copy, because
 * the plugin catalog is read at startup only from known marketplaces and
 * an eval session's config dir is fresh every time (a hand-registered
 * marketplace would otherwise never load in its first -- only -- run).
 * Members that reference host-only tooling (hooks calling {@code rtk},
 * {@code ddtool}-backed MCP servers) are stripped, not mirrored: in the
 * slim image they would fail on every tool call.</p>
 *
 * <p><b>Container runtime.</b> Targets the {@code docker} CLI against
 * whatever context is active -- Colima on macOS, a local or remote daemon
 * on Linux. The image is built once by the {@code claudeEvalImage} Gradle
 * task; {@link #probe} checks both that the daemon answers and that the
 * image is present, so {@code evalAvailable()} honestly gates the UI
 * checkbox.</p>
 *
 * <p><b>Mounts.</b> A git worktree's {@code .git} is a file pointing back
 * into the main repo's object store via an absolute path, so for git to
 * work inside the container both the worktree and the main repo root are
 * bind-mounted at their original host paths (option 4a). The activity-hook
 * script, the activity state directory, and the per-session MCP config
 * file are likewise mounted at their host paths, so the existing
 * {@code --settings}/{@code --mcp-config} flags resolve unchanged and the
 * host-side activity watcher keeps reading the same files. The personal
 * config mounts are read-only (see above); everything else is writable
 * because the session owns its state there.</p>
 *
 * <p>All methods are blocking and must be called off the JavaFX application
 * thread. {@link #probe} is run once at provider init (background) and its
 * result cached.</p>
 */
public class ClaudeEvalContainer {

    private static final Logger LOG = System.getLogger(ClaudeEvalContainer.class.getName());

    /** The image tag built by the {@code claudeEvalImage} Gradle task. */
    private static final String IMAGE = System.getProperty(
            "app.drydock.eval.claude.image", "drydock-claude-eval:latest");

    /**
     * Managed-settings locations, in precedence order (macOS first, then Linux).
     * Resolved per call (not cached at class-load) so a test can point the
     * {@code app.drydock.eval.claude.managedSettings} system property at a
     * fixture before calling {@link #seedSettings}.
     */
    private static List<Path> managedSettingsPaths() {
        String override = System.getProperty("app.drydock.eval.claude.managedSettings");
        if (override != null && !override.isBlank()) {
            return List.of(Path.of(override));
        }
        return List.of(
                Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"),
                Path.of("/etc/claude-code/managed-settings.json"));
    }

    /**
     * The user's home, where {@code ~/.claude} (personal settings, skills,
     * plugins, marketplaces) and {@code ~/.claude.json} (onboarding/trust
     * state) live. Overridable via system property so tests can point at a
     * fixture home; defaults to {@code user.home}, resolved per call.
     */
    private static Path home() {
        String override = System.getProperty("app.drydock.eval.claude.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"));
    }

    private static Path userClaudeDir() {
        return home().resolve(".claude");
    }

    /** Settings member holding the host's ddtool auth helper; dead weight in
     * the container, where auth arrives as {@code ANTHROPIC_API_KEY} env. */
    private static final String API_KEY_HELPER_KEY = "apiKeyHelper";

    /**
     * Personal config entries mirrored into the container via a symlink at
     * the same name inside the config dir. {@code settings.json} is absent:
     * it is the seed drydock writes (merged from the user's copy, with the
     * eval edits). {@code .claude.json} likewise: copied, not symlinked,
     * because the container session updates it (trust, history) and those
     * updates must die with the session, not leak into the host copy.
     * {@code plugins} is absent: it gets special treatment
     * (see {@link #mirrorPluginState}) -- its state files must be writable
     * per-session while its content dirs stay shared read-only.
     */
    private static final List<String> MIRRORED_ENTRIES = List.of(
            "skills", "agents", "commands", "CLAUDE.md");

    /**
     * Plugin-state files, copied per-session so the container session can
     * update them (marketplace materialization, plugin auto-install) without
     * touching the host's store. Small (kilobytes).
     */
    private static final List<String> PLUGIN_STATE_FILES = List.of(
            "known_marketplaces.json", "installed_plugins.json", "config.json",
            "blocklist.json", "plugin-catalog-cache.json");

    /**
     * Plugin-content dirs, symlinked into the host's {@code ~/.claude/plugins}
     * (read-only in the container via the {@code ~/.claude} mount): marketplaces
     * can be gigabytes, so copying is out of the question and the content is
     * read-mostly anyway. {@code repos} holds plugin checkout copies (may be
     * absent). {@code data} is NOT here: it is session-writable state, so it
     * is created as a fresh empty dir per session.
     */
    private static final List<String> PLUGIN_CONTENT_DIRS = List.of(
            "marketplaces", "cache", "repos");

    /**
     * User-scope settings members that cannot work inside the slim eval
     * image and would only break sessions there:
     * <ul>
     * <li>{@code hooks} -- the user's hooks call host-only tools
     * ({@code rtk}, python3 scripts); a failing PreToolUse hook fires on
     * every tool call.
     * <li>{@code mcpServers} / {@code disabledMcpServers} -- host binaries
     * ({@code ddtool}, {@code npx}) are not in the image; every server
     * would fail to start.
     * <li>{@code apiKeyHelper} -- auth is passed as {@code ANTHROPIC_API_KEY}.
     * </ul>
     * Everything else the user has locally (permissions, model, theme,
     * {@code enabledPlugins}, statusLine, ...) carries over so the session
     * behaves like the host one.
     */
    private static final List<String> STRIPPED_SETTINGS_MEMBERS = List.of(
            "hooks", "mcpServers", "disabledMcpServers", API_KEY_HELPER_KEY);

    /** Members stripped from the copied {@code .claude.json} for the same
     * reasons (the host binary / auth paths they reference are absent in
     * the container). */
    private static final List<String> STRIPPED_CLAUDE_JSON_MEMBERS = List.of(
            "mcpServers", "disabledMcpServers", API_KEY_HELPER_KEY);

    /** The header that routes traffic to the eval account. */
    private static final String EVAL_HEADER = "x-target-account: eval";

    /** Base URL rewritten to reach the host's omlx_proxy from inside the container. */
    private static final String CONTAINER_BASE_URL = "http://host.docker.internal:4000";

    private static final String AUTH_TOKEN_ENV = "ANTHROPIC_API_KEY";
    private static final String CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR";
    private static final String CUSTOM_HEADERS_KEY = "ANTHROPIC_CUSTOM_HEADERS";
    private static final String BASE_URL_KEY = "ANTHROPIC_BASE_URL";
    private static final String HELPER_TTL_KEY = "CLAUDE_CODE_API_KEY_HELPER_TTL_MS";

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** {@code gitdir: <path>} in a worktree's {@code .git} file. */
    private static final Pattern GITDIR_LINE = Pattern.compile("^gitdir:\\s+(.+)$");

    private final Path stateDirectory;
    private final EvalTokenResolver tokenResolver;
    /** sessionKey -> setup; populated by {@link #mark}, read by the command builder, cleared by {@link #unmark}. */
    private final Map<String, EvalSetup> setups = new ConcurrentHashMap<>();

    public ClaudeEvalContainer(Path stateDirectory) {
        this(stateDirectory, new app.drydock.agent.providers.claude.internal.DtoolEvalTokenResolver());
    }

    /** For tests: inject the token resolver (e.g. a stub returning a fixed token). */
    public ClaudeEvalContainer(Path stateDirectory, EvalTokenResolver tokenResolver) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
        this.tokenResolver = Objects.requireNonNull(tokenResolver, "tokenResolver");
    }

    /** The per-session eval setup: the resolved auth token, its JWT expiry, and the seeded config dir. */
    public record EvalSetup(String token, Instant tokenExpiry, Path configDir) { }

    /**
     * Whether the container runtime is reachable and the eval image is
     * present. Best-effort: any failure (refused, timeout, non-zero,
     * missing image) is {@code false}, so eval mode is disabled rather than
     * half-working.
     */
    public boolean probe() {
        try {
            ProcessResult res = ProcessRunner.run(
                    List.of("docker", "image", "inspect", IMAGE),
                    null, java.time.Duration.ofSeconds(5));
            if (res.exitCode() != 0) {
                LOG.log(Level.DEBUG, () -> "eval image probe failed (exit " + res.exitCode()
                        + "); run ./gradlew claudeEvalImage. stderr: "
                        + ProcessRunner.excerpt(res.stderr()));
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.log(Level.DEBUG, () -> "docker probe failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Seeds the config dir for {@code sessionKey}: writes a settings.json
     * derived from the host's managed settings (with the eval header and
     * rewritten base URL), resolves a fresh auth token, and stashes the
     * result for the command builder. Idempotent -- re-resolves a fresh
     * token on every call (so a resume refreshes an expiring token).
     *
     * @return the setup, or {@code Optional#empty()} if the token could not
     *         be resolved (the launch will then fail loudly in the command
     *         builder rather than silently shipping an unauthenticated
     *         container)
     */
    public Optional<EvalSetup> mark(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        Path configDir = stateDirectory.resolve("eval").resolve(sessionKey);
        try {
            Files.createDirectories(configDir);
            seedSettings(configDir);
            mirrorPersonalConfig(configDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, () -> "Could not seed eval config dir " + configDir + ": " + e.getMessage());
            return Optional.empty();
        }
        Optional<EvalTokenResolver.ResolvedToken> resolved = tokenResolver.resolveToken();
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        EvalTokenResolver.ResolvedToken token = resolved.get();
        EvalSetup setup = new EvalSetup(token.token(), token.expiry().orElse(null), configDir);
        setups.put(sessionKey, setup);
        return Optional.of(setup);
    }

    /** Reverses {@link #mark}: deletes the per-session config dir and drops the stash. Idempotent. */
    public void unmark(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        setups.remove(sessionKey);
        Path configDir = stateDirectory.resolve("eval").resolve(sessionKey);
        try {
            deleteRecursively(configDir);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not delete eval config dir " + configDir + ": " + e.getMessage());
        }
    }

    /** The stashed setup for {@code sessionKey}, or empty if {@link #mark} has not run (or failed). */
    public Optional<EvalSetup> setupFor(String sessionKey) {
        return Optional.ofNullable(setups.get(sessionKey));
    }

    /**
     * Writes the inner claude command to {@code <configDir>/entrypoint.sh}
     * and builds the {@code docker run} wrapper that executes it inside the
     * container. The inner command is written to a file (not passed as a
     * docker CMD argument) to avoid nested shell-quoting: the inner command
     * already single-quotes its own arguments, and embedding it inside
     * another {@code sh -c '...'} would require escaping those quotes.
     *
     * @param innerCommand   the bare {@code claude ...} command string the
     *                       provider would normally run on the host
     * @param worktree       the session's working directory (a worktree or
     *                       a repo root), mounted at its own path
     * @param mcpConfigFile  the per-session {@code --mcp-config} file, if any
     * @param hooksDir       the activity-hook script + settings dir
     *                       ({@code <stateDir>/hooks})
     * @param activityDir    the activity state-word dir ({@code <stateDir>/activity})
     */
    public String wrap(EvalSetup setup, String innerCommand, Path worktree, Optional<Path> mcpConfigFile,
                       Path hooksDir, Path activityDir) throws IOException {
        // The auth token is written to an owner-only file in the mounted
        // config dir, and the entrypoint exports it from there -- it never
        // appears in `ps` on the host or in the command preview the UI
        // shows. A 6-hour-lived credential on the argv would be a real
        // exposure (AGENTS.md: "a credential must never be a command-line
        // argument"), and the command string is reachable from log lines
        // and the preview pane.
        Path tokenFile = setup.configDir().resolve("auth-token");
        writeOwnerOnly(tokenFile, setup.token());

        // The entrypoint reads the token from the file, exports it, then
        // runs the bare `claude ...` command. The image's ENTRYPOINT is
        // `sh`, so `docker run <img> <entrypoint>` becomes
        // `sh <entrypoint>` -- no extra `sh` here, or it would be
        // `sh sh <entrypoint>` and fail with "cannot open sh".
        Path entrypoint = setup.configDir().resolve("entrypoint.sh");
        Files.writeString(entrypoint,
                "#!/bin/sh\n"
                + "export " + AUTH_TOKEN_ENV + "=\"$(cat " + shellQuote(tokenFile.toString()) + ")\"\n"
                + innerCommand + "\n",
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS: the container's sh will still read it; the exec bit is cosmetic then.
        }

        Path mainRepoRoot = resolveMainRepoRoot(worktree);
        StringBuilder cmd = new StringBuilder("docker run --rm -it");
        cmd.append(" --add-host=host.docker.internal:host-gateway");
        cmd.append(" -e ").append(CONFIG_DIR_ENV).append('=').append(shellQuote(setup.configDir().toString()));
        cmd.append(" -v ").append(shellQuote(setup.configDir().toString()))
                .append(':').append(shellQuote(setup.configDir().toString()));
        cmd.append(" -v ").append(shellQuote(mainRepoRoot.toString()))
                .append(':').append(shellQuote(mainRepoRoot.toString()));
        if (!mainRepoRoot.equals(worktree)) {
            cmd.append(" -v ").append(shellQuote(worktree.toString()))
                    .append(':').append(shellQuote(worktree.toString()));
        }
        cmd.append(" -v ").append(shellQuote(hooksDir.toString()))
                .append(':').append(shellQuote(hooksDir.toString()));
        cmd.append(" -v ").append(shellQuote(activityDir.toString()))
                .append(':').append(shellQuote(activityDir.toString()));
        // The config-dir symlinks (mirrorPersonalConfig) resolve against the
        // user's ~/.claude, so it must exist in the container at its original
        // host path -- read-only, so a container session can never mutate the
        // host's skills, plugins or marketplace clones (auto-updates run on
        // the host, where they belong).
        if (Files.isDirectory(userClaudeDir())) {
            cmd.append(" -v ").append(shellQuote(userClaudeDir().toString()))
                    .append(':').append(shellQuote(userClaudeDir().toString()))
                    .append(":ro");
        }
        // Directory-source marketplaces live outside ~/.claude (e.g.
        // ~/.trajectory/claude-marketplace, a plugin repo checkout) at their
        // own host paths; known_marketplaces.json names those paths, and the
        // container can only load them if they are mounted where the
        // installed-plugins state expects to find them.
        for (Path marketplace : extraMarketplacePaths()) {
            cmd.append(" -v ").append(shellQuote(marketplace.toString()))
                    .append(':').append(shellQuote(marketplace.toString()))
                    .append(":ro");
        }
        mcpConfigFile.ifPresent(f -> cmd.append(" -v ").append(shellQuote(f.toString()))
                .append(':').append(shellQuote(f.toString())));
        cmd.append(" -w ").append(shellQuote(worktree.toString()));
        cmd.append(' ').append(IMAGE);
        cmd.append(' ').append(shellQuote(entrypoint.toString()));
        return cmd.toString();
    }

    /**
     * Directory-source marketplace paths that need their own bind mount:
     * the {@code installLocation}s from
     * {@code ~/.claude/plugins/known_marketplaces.json}, plus the directory
     * paths declared in the user's {@code extraKnownMarketplaces} -- an
     * in-progress registration may not be materialized into
     * known_marketplaces.json yet. Only paths that exist, are directories,
     * and live outside {@code ~/.claude} are included (the ones inside are
     * already covered by its mount). Best-effort: unreadable or malformed
     * sources contribute nothing. Sorted, so the built command string is
     * deterministic.
     */
    static List<Path> extraMarketplacePaths() {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        collectMarketplaceInstallLocations(paths);
        collectDeclaredMarketplacePaths(paths);
        List<Path> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    /** installLocation paths from known_marketplaces.json into {@code paths}. */
    private static void collectMarketplaceInstallLocations(LinkedHashSet<Path> paths) {
        Path known = userClaudeDir().resolve("plugins").resolve("known_marketplaces.json");
        if (!Files.isReadable(known)) {
            return;
        }
        try {
            JsonValue parsed = JsonParser.parse(Files.readString(known, StandardCharsets.UTF_8));
            if (!(parsed instanceof JsonObject root)) {
                return;
            }
            for (JsonValue entry : root.members().values()) {
                if (!(entry instanceof JsonObject market)) {
                    continue;
                }
                if (!(market.members().get("installLocation") instanceof JsonString loc)) {
                    continue;
                }
                Path p = Path.of(loc.value());
                if (Files.isDirectory(p) && !p.startsWith(userClaudeDir())) {
                    paths.add(p);
                }
            }
        } catch (IOException | JsonParseException e) {
            LOG.log(Level.WARNING, () -> "Could not read known marketplaces " + known + ": " + e.getMessage());
        }
    }

    /** Directory paths from the user's extraKnownMarketplaces into {@code paths}. */
    private static void collectDeclaredMarketplacePaths(LinkedHashSet<Path> paths) {
        Optional<JsonObject> extra = readExtraKnownMarketplaces();
        if (extra.isEmpty()) {
            return;
        }
        for (JsonValue entry : extra.get().members().values()) {
            if (!(entry instanceof JsonObject market)
                    || !(market.members().get("source") instanceof JsonObject src)
                    || !(src.members().get("source") instanceof JsonString kind)
                    || !"directory".equals(kind.value())
                    || !(src.members().get("path") instanceof JsonString path)) {
                continue;
            }
            Path p = Path.of(path.value());
            if (Files.isDirectory(p) && !p.startsWith(userClaudeDir())) {
                paths.add(p);
            }
        }
    }



    /**
     * Builds {@code <configDir>/settings.json} so the container session
     * starts with the user's personal settings (permissions, model, theme,
     * enabledPlugins, statusLine, ...) instead of a bare factory config:
     * the base is the user's {@code ~/.claude/settings.json}, the managed
     * settings are overlaid on top (managed wins -- it pins the base URL
     * and telemetry), the host-bound members are stripped (see
     * {@link #STRIPPED_SETTINGS_MEMBERS}), and the eval edits are applied
     * last. If neither file is readable the seed is an empty object, as
     * before. Package-private for unit testing.
     *
     * <p>Mutates freshly parsed {@link JsonObject}s in place; the records'
     * compact constructors copy their member maps, so the mutation never
     * reaches the sources.
     */
    void seedSettings(Path configDir) throws IOException {
        JsonObject root = readUserSettings().orElseGet(JsonObject::empty);
        overlayManagedSettings(root);
        for (String key : STRIPPED_SETTINGS_MEMBERS) {
            root.members().remove(key);
        }
        JsonObject env = root.members().containsKey("env")
                ? asObject(root.members().get("env"))
                : new JsonObject(new LinkedHashMap<>());
        // Rewrite base URL so the container reaches the host's omlx_proxy.
        putMember(env, BASE_URL_KEY, new JsonString(CONTAINER_BASE_URL));
        // Append the eval header to the custom-headers block.
        String headers = env.members().containsKey(CUSTOM_HEADERS_KEY)
                ? asString(env.members().get(CUSTOM_HEADERS_KEY)) : "";
        if (!headers.isEmpty() && !headers.endsWith("\n")) {
            headers += "\n";
        }
        if (!containsHeader(headers, EVAL_HEADER)) {
            headers += EVAL_HEADER + "\n";
        }
        putMember(env, CUSTOM_HEADERS_KEY, new JsonString(headers));
        // The token is passed as ANTHROPIC_API_KEY env, so the helper is dead weight.
        env.members().remove(HELPER_TTL_KEY);
        putMember(root, "env", env);

        Path target = configDir.resolve("settings.json");
        writeAtomically(target, JsonWriter.write(root));
    }

    /** The user's personal {@code ~/.claude/settings.json}, if readable. */
    private Optional<JsonObject> readUserSettings() {
        Path p = userClaudeDir().resolve("settings.json");
        try {
            if (Files.isReadable(p)) {
                JsonValue parsed = JsonParser.parse(Files.readString(p, StandardCharsets.UTF_8));
                return parsed instanceof JsonObject o ? Optional.of(o) : Optional.empty();
            }
        } catch (IOException | JsonParseException e) {
            LOG.log(Level.WARNING, () -> "Could not read user settings " + p + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Overlays the managed settings onto {@code root} (in place): managed
     * wins per member; the two {@code env} maps merge per key, also with
     * managed winning, because managed pins the base URL and auth
     * plumbing that the eval edits then rewrite.
     */
    private void overlayManagedSettings(JsonObject root) throws IOException {
        JsonObject managed = readManagedSettings().orElseGet(JsonObject::empty);
        for (Map.Entry<String, JsonValue> e : managed.members().entrySet()) {
            if ("env".equals(e.getKey())) {
                JsonObject managedEnv = asObject(e.getValue());
                JsonObject rootEnv = root.members().containsKey("env")
                        ? asObject(root.members().get("env")) : new JsonObject(new LinkedHashMap<>());
                rootEnv.members().putAll(managedEnv.members());
                root.members().put("env", rootEnv);
            } else {
                root.members().put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Mirrors the user's personal Claude Code state into the config dir:
     * <ul>
     * <li>{@code .claude.json} -- copied (owner-only), with the host-bound
     * members stripped, so the session starts with the host's onboarding,
     * trust-dialog and project state instead of re-confirming everything.
     * Copied, not symlinked: the session updates it, and those updates
     * belong to the ephemeral session, not the host copy.
     * <li>{@code skills}, {@code plugins}, {@code agents}, {@code commands},
     * {@code CLAUDE.md} -- symlinked into the user's {@code ~/.claude}; the
     * symlink targets resolve inside the container because
     * {@link #wrap} bind-mounts {@code ~/.claude} at its original path.
     * </ul>
     * Best-effort per entry: a missing or unreadable source leaves the
     * entry absent, never fails the launch.
     */
    void mirrorPersonalConfig(Path configDir) throws IOException {
        mirrorClaudeJson(configDir);
        mirrorPluginState(configDir);
        for (String entry : MIRRORED_ENTRIES) {
            Path source = userClaudeDir().resolve(entry);
            if (!Files.exists(source)) {
                continue;
            }
            Path link = configDir.resolve(entry);
            try {
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, source);
            } catch (IOException | UnsupportedOperationException e) {
                // No symlink support (exotic FS): the entry is simply absent
                // in the container; not worth failing the launch over.
                LOG.log(Level.DEBUG, () -> "Could not symlink eval config entry "
                        + entry + ": " + e.getMessage());
            }
        }
    }

    /**
     * Builds {@code <configDir>/plugins} so the container sees the host's
     * plugin state but owns its session updates: state files are copied
     * (writable, die with the session), content dirs are symlinked to the
     * host's read-only store.
     *
     * <p>On top of the plain copy, {@code known_marketplaces.json} gains an
     * entry for every directory-source marketplace the user declared in
     * {@code extraKnownMarketplaces} but that has not been materialized on
     * the host yet (an in-progress registration, hand-added to settings
     * before any host session ran {@code claude plugin marketplace add}).
     * Without the entry, the marketplace's plugins cannot load in the
     * session that first sees the declaration: the plugin catalog is read
     * at startup only from known marketplaces, and the container cannot
     * materialize the entry itself into the host's read-only store (on the
     * host, the next session does it; each eval session would otherwise be
     * that "first session" forever, because its config dir is fresh).
     * Fabricating the entry here is safe exactly because the state file is
     * per-session: worst case, if Claude Code's schema moves on, the
     * container re-materializes from the declaration into the copy and the
     * plugin loads one resume later -- the same degraded bootstrap the
     * host itself has.
     */
    void mirrorPluginState(Path configDir) throws IOException {
        Path hostPlugins = userClaudeDir().resolve("plugins");
        if (!Files.isDirectory(hostPlugins)) {
            return;
        }
        Path target = configDir.resolve("plugins");
        Files.createDirectories(target);
        for (String file : PLUGIN_STATE_FILES) {
            Path source = hostPlugins.resolve(file);
            if (Files.isRegularFile(source)) {
                Files.copy(source, target.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Session-writable plugin data; host data is not carried over (it is
        // per-machine state, not content).
        Files.createDirectories(target.resolve("data"));
        for (String dir : PLUGIN_CONTENT_DIRS) {
            Path source = hostPlugins.resolve(dir);
            if (!Files.isDirectory(source)) {
                continue;
            }
            Path link = target.resolve(dir);
            try {
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, source);
            } catch (IOException | UnsupportedOperationException e) {
                LOG.log(Level.DEBUG, () -> "Could not symlink plugin content dir "
                        + dir + ": " + e.getMessage());
            }
        }
        fabricateDeclaredMarketplaces(target.resolve("known_marketplaces.json"));
    }

    /**
     * Rewrites {@code knownMarketplacesFile} (the per-session copy) with an
     * entry added for every directory-source {@code extraKnownMarketplaces}
     * declaration from the user's settings that is missing from it. See
     * {@link #mirrorPluginState} for why the entry must exist up front. The
     * entry shape matches what {@code claude plugin marketplace add} writes
     * for a directory source (verified against a host-materialized file):
     * {@code source}, {@code installLocation} (== the declared path) and
     * {@code lastUpdated}.
     */
    private void fabricateDeclaredMarketplaces(Path knownMarketplacesFile) throws IOException {
        Optional<JsonObject> extra = readExtraKnownMarketplaces();
        if (extra.isEmpty()) {
            return;
        }
        JsonObject known;
        try {
            if (Files.isReadable(knownMarketplacesFile)) {
                JsonValue parsed = JsonParser.parse(
                        Files.readString(knownMarketplacesFile, StandardCharsets.UTF_8));
                if (!(parsed instanceof JsonObject o)) {
                    return;
                }
                known = o;
            } else {
                known = JsonObject.empty();
            }
        } catch (IOException | JsonParseException e) {
            LOG.log(Level.WARNING, () -> "Could not read " + knownMarketplacesFile
                    + "; extra marketplaces not materialized: " + e.getMessage());
            return;
        }
        boolean added = false;
        for (Map.Entry<String, JsonValue> e : extra.get().members().entrySet()) {
            String name = e.getKey();
            if (known.has(name) || !(e.getValue() instanceof JsonObject market)) {
                continue;
            }
            JsonValue sourceMember = market.members().get("source");
            // Only directory sources can be mirrored without cloning.
            if (!(sourceMember instanceof JsonObject src)
                    || !(src.members().get("source") instanceof JsonString kind)
                    || !"directory".equals(kind.value())
                    || !(src.members().get("path") instanceof JsonString path)) {
                continue;
            }
            Path dir = Path.of(path.value());
            if (!Files.isDirectory(dir)) {
                LOG.log(Level.DEBUG, () -> "extraKnownMarketplaces \"" + name + "\" points at "
                        + dir + ", which does not exist; skipping");
                continue;
            }
            JsonObject entry = new JsonObject(new LinkedHashMap<>());
            entry.put("source", src);
            entry.put("installLocation", new JsonString(dir.toString()));
            entry.put("lastUpdated", new JsonString(Instant.now().toString()));
            known.put(name, entry);
            added = true;
        }
        if (added) {
            Files.writeString(knownMarketplacesFile, JsonWriter.write(known), StandardCharsets.UTF_8);
        }
    }

    /**
     * The {@code extraKnownMarketplaces} object from the user's personal
     * settings, if present and an object. Empty otherwise.
     */
    private static Optional<JsonObject> readExtraKnownMarketplaces() {
        Path settings = userClaudeDir().resolve("settings.json");
        try {
            if (!Files.isReadable(settings)) {
                return Optional.empty();
            }
            JsonValue parsed = JsonParser.parse(Files.readString(settings, StandardCharsets.UTF_8));
            if (parsed instanceof JsonObject o
                    && o.members().get("extraKnownMarketplaces") instanceof JsonObject extra) {
                return Optional.of(extra);
            }
        } catch (IOException | JsonParseException e) {
            LOG.log(Level.DEBUG, () -> "Could not read user settings for extra marketplaces: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Copies {@code ~/.claude.json} into the config dir, owner-only, with the
     * host-bound members stripped. Falls back to a raw copy if the file is
     * unreadable or unparseable -- a stale-ish state file is still better
     * than re-running every confirmation dialog.
     */
    private void mirrorClaudeJson(Path configDir) throws IOException {
        Path source = home().resolve(".claude.json");
        if (!Files.isReadable(source)) {
            return;
        }
        Path target = configDir.resolve(".claude.json");
        try {
            JsonValue parsed = JsonParser.parse(Files.readString(source, StandardCharsets.UTF_8));
            if (parsed instanceof JsonObject o) {
                for (String key : STRIPPED_CLAUDE_JSON_MEMBERS) {
                    o.members().remove(key);
                }
                writeOwnerOnly(target, JsonWriter.write(o));
                return;
            }
        } catch (IOException | JsonParseException e) {
            LOG.log(Level.WARNING, () -> "Could not parse " + source + " for the eval mirror "
                    + "(copying verbatim): " + e.getMessage());
        }
        writeOwnerOnly(target, Files.readString(source, StandardCharsets.UTF_8));
    }

    /** True if {@code headers} already names the {@code name: value} header line. */
    private static boolean containsHeader(String headers, String header) {
        String name = header.substring(0, header.indexOf(':'));
        for (String line : headers.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void putMember(JsonObject obj, String key, JsonValue value) {
        obj.members().put(key, value);
    }

    private static JsonObject asObject(JsonValue v) {
        return v instanceof JsonObject o ? o : new JsonObject(new LinkedHashMap<>());
    }

    private static String asString(JsonValue v) {
        return v instanceof JsonString s ? s.value() : "";
    }

    private Optional<JsonObject> readManagedSettings() {
        for (Path p : managedSettingsPaths()) {
            try {
                if (Files.isReadable(p)) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    JsonValue parsed = JsonParser.parse(content);
                    return parsed instanceof JsonObject o ? Optional.of(o) : Optional.empty();
                }
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, () -> "Could not read managed settings " + p + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the main repo root backing a worktree. A worktree's {@code .git}
     * is a file reading {@code gitdir: <mainRepo>/.git/worktrees/<name>}; the
     * main repo root is three {@code getParent()} calls up from that path. If
     * {@code .git} is a directory (not a worktree), the worktree itself is
     * the repo root.
     */
    static Path resolveMainRepoRoot(Path worktree) {
        Path git = worktree.resolve(".git");
        if (Files.isDirectory(git)) {
            return worktree;
        }
        try {
            String content = Files.readString(git, StandardCharsets.UTF_8).strip();
            Matcher m = GITDIR_LINE.matcher(content);
            if (m.matches()) {
                Path gitdir = Path.of(m.group(1).trim());
                // <mainRepo>/.git/worktrees/<name> -> up three levels -> <mainRepo>
                Path mainRepo = gitdir.getParent() != null ? gitdir.getParent().getParent() : null;
                if (mainRepo != null && mainRepo.getParent() != null) {
                    mainRepo = mainRepo.getParent();
                }
                if (mainRepo != null && Files.isDirectory(mainRepo.resolve(".git"))) {
                    return mainRepo;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not read worktree .git file at " + git + ": " + e.getMessage());
        }
        // Fallback: mount only the worktree. git operations inside the
        // container may fail, but the working directory is correct.
        LOG.log(Level.WARNING, () -> "Eval container: could not resolve the main repo root backing worktree "
                + worktree + "; mounting only the worktree. Git operations inside the container may fail.");
        return worktree;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Writes {@code content} to an owner-only file (the auth token). */
    private static void writeOwnerOnly(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS: best-effort; the file is in an owner-only dir.
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** POSIX single-quote, so paths with spaces (e.g. "Application Support") survive the shell. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
