package app.drydock.agent.providers.pi.internal;

import app.drydock.agent.api.CandidateSource;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads/scans pi's per-cwd {@code ~/.pi/agent/sessions/--<enc-cwd>--/} session
 * files, parsing only the first line ({@code session} record) of each file.
 *
 * <p>Unlike Codex, pi buckets sessions into one directory per (encoded) cwd,
 * so discovery never needs a full-tree walk or date-bucket logic -- just the
 * single {@code encodeCwdDir(cwd)} directory. See {@link #encodeCwdDir(Path)}
 * for why the cwd must be canonicalized before encoding.</p>
 */
public final class PiSessionStore implements CandidateSource {

    private static final Logger LOG = System.getLogger(PiSessionStore.class.getName());
    private static final String SESSION_GLOB = "*.jsonl";

    private final Path sessionsRoot;

    public PiSessionStore() {
        this(defaultSessionsRoot());
    }

    public PiSessionStore(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    private static Path defaultSessionsRoot() {
        String explicitSessionDir = System.getenv("PI_CODING_AGENT_SESSION_DIR");
        if (explicitSessionDir != null && !explicitSessionDir.isBlank()) {
            return Path.of(explicitSessionDir);
        }
        String agentDir = System.getenv("PI_CODING_AGENT_DIR");
        if (agentDir != null && !agentDir.isBlank()) {
            return Path.of(agentDir, "sessions");
        }
        return Path.of(System.getProperty("user.home"), ".pi", "agent", "sessions");
    }

    public record SessionMeta(String id, Path cwd, Instant timestamp, Path file) {
    }

    /**
     * Encodes a cwd to pi's per-cwd directory name.
     *
     * <p>pi buckets sessions by the OS-resolved <em>real</em> path, not the
     * literal string passed to it (e.g. on macOS {@code /tmp} resolves to
     * {@code /private/tmp}). So the path is canonicalized via {@link
     * Path#toRealPath} first (falling back to {@code toAbsolutePath().normalize()}
     * if the path does not exist / cannot be resolved), then the leading
     * {@code /} is dropped, remaining {@code /} replaced with {@code -}, and
     * the result wrapped in {@code --}...{@code --}. Getting this wrong makes
     * discovery silently find nothing.</p>
     */
    public static String encodeCwdDir(Path cwd) {
        Path real = canonicalize(cwd);
        String s = real.toString();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        s = s.replace("/", "-");
        return "--" + s + "--";
    }

    /**
     * Every session in {@code sessionsRoot/encodeCwdDir(cwd)/} whose first-line
     * {@code type} is {@code "session"} and whose first-line {@code cwd} field
     * (canonicalized) equals {@code cwd} (canonicalized), newest first.
     */
    public List<SessionMeta> forWorkingDirectory(Path cwd) {
        Path canonicalCwd = canonicalize(cwd);
        List<SessionMeta> matches = new ArrayList<>();
        for (Path file : sessionFiles(cwd)) {
            readMeta(file).ifPresent(meta -> {
                if (meta.cwd().equals(canonicalCwd)) {
                    matches.add(meta);
                }
            });
        }
        matches.sort(Comparator.comparing(SessionMeta::timestamp).reversed());
        return matches;
    }

    /** The ids from {@link #forWorkingDirectory(Path)} -- a snapshot set. */
    public Set<String> idsFor(Path cwd) {
        Set<String> ids = new LinkedHashSet<>();
        for (SessionMeta meta : forWorkingDirectory(cwd)) {
            ids.add(meta.id());
        }
        return ids;
    }

    /**
     * Every session for {@code cwd} with {@code timestamp >= launchedAt}
     * whose id is not in {@code snapshotIds}, sorted earliest-first.
     */
    public List<SessionMeta> newCandidates(Path cwd, Instant launchedAt, Set<String> snapshotIds) {
        List<SessionMeta> candidates = new ArrayList<>();
        for (SessionMeta meta : forWorkingDirectory(cwd)) {
            if (!meta.timestamp().isBefore(launchedAt) && !snapshotIds.contains(meta.id())) {
                candidates.add(meta);
            }
        }
        candidates.sort(Comparator.comparing(SessionMeta::timestamp));
        return candidates;
    }

    /** {@link CandidateSource} view: same as {@link #idsFor(Path)}. */
    @Override
    public Set<String> snapshotIds(Path workingDirectory) {
        return idsFor(workingDirectory);
    }

    /** {@link CandidateSource} view: {@link #newCandidates(Path, Instant, Set)} mapped to ids. */
    @Override
    public List<String> newCandidateIds(Path workingDirectory, Instant launchedAt, Set<String> snapshotIds) {
        return newCandidates(workingDirectory, launchedAt, snapshotIds).stream()
                .map(SessionMeta::id)
                .toList();
    }

    /** Whether a session file whose name contains {@code id} exists in {@code cwd}'s directory. */
    public boolean existsForId(Path cwd, String id) {
        for (Path file : sessionFiles(cwd)) {
            if (file.getFileName().toString().contains(id)) {
                return true;
            }
        }
        return false;
    }

    private List<Path> sessionFiles(Path cwd) {
        Path dir = sessionsRoot.resolve(encodeCwdDir(cwd));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, SESSION_GLOB)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Failed to list " + dir, e);
            return List.of();
        }
        return files;
    }

    private Optional<SessionMeta> readMeta(Path file) {
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            firstLine = reader.readLine();
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.DEBUG, () -> "Failed to read first line of " + file, e);
            return Optional.empty();
        }
        if (firstLine == null || firstLine.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonValue root = JsonParser.parse(firstLine);
            if (!(root instanceof JsonValue.JsonObject rootObject)) {
                return Optional.empty();
            }
            String type = requireString(rootObject, "type");
            if (!"session".equals(type)) {
                return Optional.empty();
            }
            String id = requireString(rootObject, "id");
            String cwd = requireString(rootObject, "cwd");
            String timestamp = requireString(rootObject, "timestamp");
            if (id == null || cwd == null || timestamp == null) {
                return Optional.empty();
            }
            Path cwdPath = canonicalize(Path.of(cwd));
            Instant instant = Instant.parse(timestamp);
            return Optional.of(new SessionMeta(id, cwdPath, instant, file));
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "Failed to parse session record from " + file, e);
            return Optional.empty();
        }
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String requireString(JsonValue.JsonObject object, String key) {
        JsonValue value = object.get(key);
        if (value instanceof JsonValue.JsonString jsonString) {
            return jsonString.value();
        }
        return null;
    }
}
