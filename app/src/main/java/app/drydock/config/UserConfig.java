package app.drydock.config;

import app.drydock.lsp.JdtServerManager;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User-editable settings read from {@code ~/.drydock/config.json}, separate
 * from the app's own {@code ApplicationState} (which the app writes itself
 * and never expects a human to hand-edit). Deliberately tiny: {@code
 * worktreesDirectory} -- the directory new worktrees are created under, in
 * place of the {@code <home>/dev/wt} default (see {@link
 * app.drydock.git.WorktreeNaming}) -- {@code openChangedFilesInSkim},
 * whether a file already part of the current change opens folded to its
 * signatures, and {@code languageServer} (see {@link LanguageServer}), the
 * opt-in tier-3 configuration.
 *
 * <p>{@link #load()} never throws for a missing or malformed config file:
 * it logs a warning for malformed input and falls back to {@link #empty()},
 * consistent with how {@code JsonApplicationStateRepository} treats a
 * corrupt state file. The same leniency applies field-by-field to the
 * {@code languageServer} component: a malformed object decodes to
 * "not configured" rather than failing the file (a user-editable file
 * has no failure that justifies losing the other settings).</p>
 */
public record UserConfig(Optional<Path> worktreesDirectory, boolean openChangedFilesInSkim,
                         LanguageServer languageServer) {

    /**
     * The language-server component (spec
     * docs/superpowers/specs/2026-10-08-lsp-tier3-usage-resolution.md,
     * section 3): {@code jdtHome} is the unpacked jdt.ls directory (the one
     * holding {@code plugins/} and {@code config_mac}), {@code javaHome} the
     * optional JDK the server runs under (absent = the {@code java} on
     * PATH). An absent {@code jdtHome} means tier 3 stays off -- nothing is
     * downloaded, nothing starts. {@code javaHome} has no settings row; it
     * is hand-editable in config.json and preserved by every update here.
     */
    public record LanguageServer(Optional<Path> jdtHome, Optional<Path> javaHome) {

        public LanguageServer {
            Objects.requireNonNull(jdtHome, "jdtHome");
            Objects.requireNonNull(javaHome, "javaHome");
        }

        /** Not configured: tier 3 off, both paths absent. */
        public static LanguageServer empty() {
            return new LanguageServer(Optional.empty(), Optional.empty());
        }
    }

    /**
     * The outcome of a validated language-server save (see {@link
     * #saveLanguageServerAsync}): written, or refused with the reason the
     * settings row shows inline.
     */
    public sealed interface LanguageServerSaveResult {

        record Saved() implements LanguageServerSaveResult {
        }

        /** The refusal reason, already a sentence (built by {@code JdtServerManager.validate}). */
        record Refused(String reason) implements LanguageServerSaveResult {
        }
    }

    private static final Logger LOG = System.getLogger(UserConfig.class.getName());

    /**
     * The two-argument form unaffected callers keep: a config with the
     * language server unset. NOT for read-modify-write updates -- a save
     * built through it resets the language-server component; the setters
     * that must preserve it (the application wiring) use the canonical
     * three-argument constructor.
     */
    public UserConfig(Optional<Path> worktreesDirectory, boolean openChangedFilesInSkim) {
        this(worktreesDirectory, openChangedFilesInSkim, LanguageServer.empty());
    }

    public static UserConfig empty() {
        // Skim-by-default is the Explorer delta's design (part 2); the
        // setting exists to turn it off, not to opt into it.
        return new UserConfig(Optional.empty(), true, LanguageServer.empty());
    }

    /** {@code ~/.drydock/config.json}. */
    public static Path defaultConfigFile() {
        return Path.of(System.getProperty("user.home"), ".drydock", "config.json");
    }

    public static UserConfig load() {
        return load(defaultConfigFile());
    }

    /**
     * As {@link #load()}, but off the caller's thread (plan section 18:
     * "file loading" must never block the JavaFX application thread) --
     * {@link #load()} does a synchronous stat + read, so callers on the FX
     * thread (e.g. the create-worktree modal) must use this instead.
     *
     * <p>Routed through {@link #SAVE_EXECUTOR} rather than an independent
     * virtual thread so a load is FIFO-ordered against every {@link
     * #saveAsync} queued before it: an unordered read could otherwise land
     * before a just-committed save's write and hand back the stale value
     * (e.g. close-then-reopen the settings modal right after editing the
     * worktrees directory).</p>
     */
    public static CompletableFuture<UserConfig> loadAsync() {
        CompletableFuture<UserConfig> future = new CompletableFuture<>();
        SAVE_EXECUTOR.execute(() -> {
            try {
                future.complete(load());
            } catch (Throwable t) {
                // load() already catches everything it knows how to handle
                // and falls back to empty(); anything reaching here is
                // unexpected. Completing exceptionally (instead of letting
                // it silently kill the thread) matters because the caller
                // -- the settings modal -- disables its controls until this
                // future completes one way or the other; an uncompleted
                // future would leave them stuck at "Loading…" forever.
                future.completeExceptionally(t);
                throw t;
            }
        });
        return future;
    }

    /** Package-visible for tests; reads and parses {@code configFile} directly. */
    static UserConfig load(Path configFile) {
        if (!Files.exists(configFile)) {
            return empty();
        }
        try {
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            JsonValue parsed = JsonParser.parse(text);
            if (!(parsed instanceof JsonObject root)) {
                throw new JsonParseException("Expected a JSON object at the top level");
            }
            Optional<Path> worktreesDirectory = root.get("worktreesDirectory") instanceof JsonString s
                    ? Optional.of(Path.of(s.value()).toAbsolutePath().normalize())
                    : Optional.empty();
            boolean openChangedFilesInSkim = !(root.get("openChangedFilesInSkim") instanceof JsonBoolean b)
                    || b.value();
            return new UserConfig(worktreesDirectory, openChangedFilesInSkim, languageServer(root));
        } catch (IOException | JsonParseException | InvalidPathException e) {
            LOG.log(Level.WARNING, "Config file " + configFile + " is missing, unreadable, or malformed; "
                    + "ignoring it and using defaults", e);
            return empty();
        }
    }

    /**
     * Writes {@code config} to {@code configFile}: temp file in the same
     * directory, then an atomic move, so a crash mid-write can never leave a
     * truncated config where a valid one was.
     *
     * <p>Members this build does not know about are read back from the
     * existing file and preserved -- the file is hand-editable, so silently
     * dropping a key a newer build (or the user) put there would be data
     * loss.</p>
     *
     * <p>Unlike {@link #load()}, a failure here throws: a save is a
     * user-visible action, and one that silently did nothing is worse than
     * one that reports why.</p>
     *
     * <p>{@code configFile} is resolved to an absolute path first so it
     * always has a parent directory to create the temp file in -- a bare
     * relative filename with no parent segment would otherwise force the
     * temp file into the JVM's default temp directory, which can be a
     * different filesystem and break {@link StandardCopyOption#ATOMIC_MOVE}.
     * A path that resolves to a filesystem root (no parent even after that)
     * is rejected outright rather than silently falling back.</p>
     */
    static void save(UserConfig config, Path configFile) throws IOException {
        Path resolvedConfigFile = configFile.toAbsolutePath().normalize();
        Path parent = resolvedConfigFile.getParent();
        if (parent == null) {
            throw new IOException("Cannot save config to a path with no parent directory: " + resolvedConfigFile);
        }

        JsonObject root = readExistingRootOrEmpty(resolvedConfigFile);
        root.members().remove("worktreesDirectory");
        config.worktreesDirectory().ifPresent(dir -> root.put("worktreesDirectory", new JsonString(dir.toString())));
        root.members().remove("openChangedFilesInSkim");
        root.put("openChangedFilesInSkim", new JsonBoolean(config.openChangedFilesInSkim()));
        writeLanguageServer(root, config.languageServer());

        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "config", ".json.tmp");
        try {
            Files.writeString(temp, JsonWriter.write(root), StandardCharsets.UTF_8);
            Files.move(temp, resolvedConfigFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Lenient decode of the {@code languageServer} component: absent, a
     * non-object, or members that are not path-shaped strings all decode to
     * {@link LanguageServer#empty()} rather than failing the file -- the
     * same per-field leniency {@code worktreesDirectory} gets, taken one
     * step further so a bad path cannot even discard the other members of
     * the object.
     */
    private static LanguageServer languageServer(JsonObject root) {
        if (!(root.get("languageServer") instanceof JsonObject server)) {
            return LanguageServer.empty();
        }
        return new LanguageServer(pathMember(server, "jdtHome"), pathMember(server, "javaHome"));
    }

    private static Optional<Path> pathMember(JsonObject server, String key) {
        if (!(server.get(key) instanceof JsonString s)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(s.value()).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            LOG.log(Level.WARNING, "Config member languageServer.{0} is not a usable path; ignoring it", key);
            return Optional.empty();
        }
    }

    /**
     * Persists the {@code languageServer} component under a {@code
     * "languageServer"} object, same preserve-unknown-members discipline as
     * the root: an existing object's members this build does not know about
     * survive, and a component with both paths absent removes the object
     * entirely (an unconfigured tier leaves no key behind, matching how an
     * empty worktreesDirectory is cleared rather than written as null).
     */
    private static void writeLanguageServer(JsonObject root, LanguageServer server) {
        JsonObject object = root.get("languageServer") instanceof JsonObject existing
                ? existing : JsonObject.empty();
        root.members().remove("languageServer");
        if (server.jdtHome().isEmpty() && server.javaHome().isEmpty()) {
            return;
        }
        object.members().remove("jdtHome");
        object.members().remove("javaHome");
        server.jdtHome().ifPresent(path -> object.put("jdtHome", new JsonString(path.toString())));
        server.javaHome().ifPresent(path -> object.put("javaHome", new JsonString(path.toString())));
        root.put("languageServer", object);
    }

    /** Best-effort read of {@code configFile}'s existing top-level object, for {@link #save} to preserve unknown members. */
    private static JsonObject readExistingRootOrEmpty(Path configFile) {
        if (Files.exists(configFile)) {
            try {
                if (JsonParser.parse(Files.readString(configFile, StandardCharsets.UTF_8))
                        instanceof JsonObject existing) {
                    return existing;
                }
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, "Existing config " + configFile
                        + " is unreadable or malformed; replacing it", e);
            }
        }
        return JsonObject.empty();
    }

    /**
     * Runs saves for {@link #saveAsync} one at a time. A plain "one virtual
     * thread per call" approach (as an earlier version of this class did)
     * lets concurrent saves race: the settings modal commits on both
     * field-blur and Browse, which can fire back-to-back, and with
     * unordered threads the stale call's {@code Files.move} can land after
     * the fresh one's, silently reverting the file. A single-thread executor
     * makes writes happen in submission order, same as {@code
     * AnnotationStore.saveExecutor}.
     */
    private static final ExecutorService SAVE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> Thread.ofVirtual().unstarted(runnable));

    /**
     * Newest-wins pending save, mirroring {@code AnnotationStore.pendingSnapshot}:
     * {@link #saveAsync} always writes the *whole* config, so a call that
     * arrives while an earlier one is still queued (not yet picked up by
     * {@link #SAVE_EXECUTOR}'s single thread) can simply replace it instead
     * of enqueuing a second, soon-to-be-overwritten write. Only one task is
     * ever queued at a time; {@link #runPendingSave} is what drains it.
     */
    private static final AtomicReference<PendingSave> PENDING_SAVE = new AtomicReference<>();

    private record PendingSave(UserConfig config, CompletableFuture<Void> future) {
    }

    /**
     * As {@link #save}, off the caller's thread -- the settings modal calls
     * this from the FX thread, where a synchronous write is forbidden. The
     * returned future completes exceptionally on failure so the caller can
     * surface it; it is never swallowed.
     *
     * <p>Concurrent calls coalesce onto {@link #PENDING_SAVE} rather than
     * racing independent threads (see {@link #SAVE_EXECUTOR}'s Javadoc). A
     * call that gets superseded before it is written still has its returned
     * future completed -- with the outcome of the save that superseded it --
     * so a caller awaiting it never hangs.</p>
     *
     * <p><b>Tests:</b> {@link #PENDING_SAVE} and {@link #SAVE_EXECUTOR} are
     * static, i.e. shared by the whole test JVM. A test that calls this must
     * call {@link #flushPendingSaves()} (in a {@code finally}, before its
     * {@code @TempDir} is removed) so a write cannot leak into, or race, an
     * unrelated test.</p>
     */
    /**
     * Changes one preference and writes the result, without any other
     * preference in the file getting lost on the way.
     *
     * <p>This is a read-modify-write, and doing it as {@code loadAsync()}
     * followed by {@code saveAsync()} is not safe however carefully the
     * caller writes it: those are two separate tasks, so two overlapping
     * updates -- the Explorer checkbox and a worktrees-directory commit, both
     * reachable from one modal -- can both read the same config before either
     * writes, and the later write silently drops the earlier one's field. The
     * whole read-mutate-write runs as ONE task on {@link #SAVE_EXECUTOR}
     * instead, whose single thread is what makes it atomic against every
     * other load and save.</p>
     *
     * <p>{@code mutate} runs on that thread: it must be a pure function of
     * the config it is handed, and must not touch the UI.</p>
     */
    public static CompletableFuture<Void> updateAsync(UnaryOperator<UserConfig> mutate) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SAVE_EXECUTOR.execute(() -> {
            try {
                save(mutate.apply(load()), defaultConfigFile());
                future.complete(null);
            } catch (IOException | RuntimeException e) {
                future.completeExceptionally(e);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t;
            }
        });
        return future;
    }

    /**
     * Validates and (only then) saves the {@code jdtHome} directory of the
     * language-server component, as ONE task on {@link #SAVE_EXECUTOR} --
     * the same single-thread FIFO ordering that makes {@link #updateAsync}
     * atomic, so a validated save cannot interleave with any load or other
     * save, and a load queued after it sees the validated value.
     *
     * <p>Validation (spec section 3, delegated to {@link
     * JdtServerManager#validate}) is BLOCKING -- filesystem checks plus the
     * Java probe, which runs {@code java -version} through ProcessRunner in
     * production ({@code JdtServerManager.processRunnerJavaProbe()}) and is
     * injected here so tests never run a real {@code java}. Never call this
     * on the JavaFX application thread; it returns a future instead.</p>
     *
     * <p>An empty {@code jdtHome} disables tier 3 and saves without
     * validation (there is nothing to validate); {@code javaHome} is carried
     * over from the file untouched -- it has no settings row. A refusal
     * completes with {@link LanguageServerSaveResult.Refused} and leaves the
     * file byte-unchanged: no write happens at all, so the prior config
     * survives intact. An I/O failure saving completes exceptionally.</p>
     */
    public static CompletableFuture<LanguageServerSaveResult> saveLanguageServerAsync(
            Optional<Path> jdtHome, JdtServerManager.JavaProbe javaProbe) {
        Objects.requireNonNull(javaProbe, "javaProbe");
        CompletableFuture<LanguageServerSaveResult> future = new CompletableFuture<>();
        SAVE_EXECUTOR.execute(() -> {
            try {
                UserConfig existing = load(defaultConfigFile());
                LanguageServer next = new LanguageServer(
                        jdtHome.map(path -> path.toAbsolutePath().normalize()),
                        existing.languageServer().javaHome());
                if (next.jdtHome().isPresent()) {
                    JdtServerManager.ValidationResult validation = JdtServerManager.validate(
                            new JdtServerManager.LaunchConfig(next.jdtHome().get(), next.javaHome().orElse(null)),
                            javaProbe);
                    if (validation instanceof JdtServerManager.ValidationResult.Invalid invalid) {
                        future.complete(new LanguageServerSaveResult.Refused(invalid.message()));
                        return; // refused: no write, the prior file stays byte-unchanged
                    }
                }
                save(new UserConfig(existing.worktreesDirectory(), existing.openChangedFilesInSkim(), next),
                        defaultConfigFile());
                future.complete(new LanguageServerSaveResult.Saved());
            } catch (IOException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Package-visible, and deliberately not public any more: every setter
     * goes through {@link #updateAsync}, because this one writes the WHOLE
     * record and a caller building a {@code UserConfig} from a single new
     * value silently resets every other preference in the file. Kept only
     * for the tests that exercise the coalescing directly.
     */
    static CompletableFuture<Void> saveAsync(UserConfig config) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        PendingSave superseded = PENDING_SAVE.getAndSet(new PendingSave(config, future));
        if (superseded == null) {
            SAVE_EXECUTOR.execute(UserConfig::runPendingSave);
        } else {
            future.whenComplete((ignoredValue, error) -> {
                if (error != null) {
                    superseded.future().completeExceptionally(error);
                } else {
                    superseded.future().complete(null);
                }
            });
        }
        return future;
    }

    private static void runPendingSave() {
        PendingSave pending = PENDING_SAVE.getAndSet(null);
        if (pending == null) {
            // Nothing left to do: a coalesced call already claimed this slot.
            return;
        }
        try {
            save(pending.config(), defaultConfigFile());
            pending.future().complete(null);
        } catch (IOException | RuntimeException e) {
            pending.future().completeExceptionally(e);
        } catch (Throwable t) {
            // Anything beyond IOException/RuntimeException is unexpected,
            // but this runs on SAVE_EXECUTOR's single background thread: an
            // uncaught throwable here would kill that thread silently and
            // every future saveAsync call would queue forever with its
            // future never completing (SAVE_EXECUTOR.execute would still
            // "succeed" -- ThreadPoolExecutor just replaces the dead worker
            // -- but PENDING_SAVE's coalescing means a caller waiting on
            // this specific future would hang). Complete it exceptionally
            // first so no caller of saveAsync/flushPendingSaves is left
            // waiting forever, then rethrow so the failure is still visible.
            pending.future().completeExceptionally(t);
            throw t;
        }
    }

    /**
     * Awaits every {@link #saveAsync} queued so far (AGENTS.md: a service
     * writing files from a background thread exposes a flush, so shutdown
     * and tests do not race a pending write). Submitting a no-op to {@link
     * #SAVE_EXECUTOR} and awaiting it works because the executor is single
     * threaded and FIFO: the no-op cannot run until every save task queued
     * ahead of it has finished, so awaiting the no-op transitively awaits
     * all of them -- same trick as {@code AnnotationStore.flushPendingSaves}.
     * Bounded, because a wedged disk must not hang shutdown -- the atomic
     * move means the worst case is a stale file, never a corrupt one.
     */
    public static void flushPendingSaves() {
        try {
            SAVE_EXECUTOR.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // saveAsync's own future already reported the failure to its caller.
        }
    }
}
