package app.drydock.agent.providers.codex.internal;

import app.drydock.agent.api.CandidateSource;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads/scans Codex's date-bucketed {@code ~/.codex/sessions/**} rollout
 * files, parsing only the first line ({@code session_meta}) of each rollout.
 *
 * <p>Deliberately reads no more than one line per file: rollouts can be
 * large transcripts, and only the {@code session_meta} header (id, cwd,
 * timestamp, source) is needed to answer "which rollout is this" questions.
 * A rollout whose first line is missing or malformed is skipped silently
 * (logged at {@link Level#DEBUG}) rather than treated as an error -- a
 * stray or half-written file must not break scanning for every other
 * session.</p>
 */
public final class CodexRolloutStore implements CandidateSource {

    private static final Logger LOG = System.getLogger(CodexRolloutStore.class.getName());
    private static final String ROLLOUT_GLOB = "rollout-*.jsonl";

    private final Path sessionsRoot;

    public CodexRolloutStore() {
        this(Path.of(System.getProperty("user.home"), ".codex", "sessions"));
    }

    public CodexRolloutStore(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    public record RolloutMeta(String id, Path cwd, Instant timestamp, String source, Path file) {
    }

    /**
     * Every rollout whose {@code session_meta.payload.cwd} equals {@code
     * cwd} and whose {@code source} is {@code "cli"}, newest first.
     */
    public List<RolloutMeta> forWorkingDirectory(Path cwd) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        List<RolloutMeta> matches = readMatching(rolloutFiles(), normalizedCwd);
        matches.sort(Comparator.comparing(RolloutMeta::timestamp).reversed());
        return matches;
    }

    /** The ids from {@link #forWorkingDirectory(Path)} -- a snapshot set. */
    public Set<String> idsFor(Path cwd) {
        Set<String> ids = new LinkedHashSet<>();
        for (RolloutMeta meta : forWorkingDirectory(cwd)) {
            ids.add(meta.id());
        }
        return ids;
    }

    /**
     * Every rollout for {@code cwd} with {@code timestamp >= launchedAt}
     * whose id is not in {@code snapshotIds}, sorted earliest-first.
     *
     * <p>The store does not know about claimed ids or ambiguity between
     * candidates -- that is {@code SnapshotClaimDiscovery}'s concern. Earliest
     * first (rather than newest first) so that, under concurrent same-cwd
     * launches, each launch's discovery tends to claim the rollout closest
     * to its own {@code launchedAt} rather than a later launch's fresher
     * one.</p>
     *
     * <p>Codex records {@code session_meta.payload.timestamp} at
     * sub-second (millisecond) precision (verified from real rollouts,
     * e.g. {@code 2026-07-23T19:27:59.508Z}), so the {@code timestamp >=
     * launchedAt} boundary does not exclude a session launched just before
     * its rollout file is stamped.</p>
     *
     * <p>This is the hot path -- {@code SnapshotClaimDiscovery.discover} polls it
     * up to ~20 times per launch -- so unlike {@link #forWorkingDirectory}
     * it does NOT walk the whole {@code sessionsRoot} tree. A just-launched
     * rollout lands in {@code launchedAt}'s {@code YYYY/MM/DD} date-bucket
     * directory, so only that bucket plus the adjacent day before and after
     * (computed in the system default zone, to be robust to the rollout
     * filename's local-looking time vs. the UTC {@code session_meta}
     * timestamp) are scanned -- at most 3 directories, regardless of how
     * much older history exists.</p>
     */
    public List<RolloutMeta> newCandidates(Path cwd, Instant launchedAt, Set<String> snapshotIds) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        List<Path> files = rolloutFilesInBuckets(dateBucketDirs(launchedAt));
        List<RolloutMeta> candidates = new ArrayList<>();
        for (RolloutMeta meta : readMatching(files, normalizedCwd)) {
            if (!meta.timestamp().isBefore(launchedAt) && !snapshotIds.contains(meta.id())) {
                candidates.add(meta);
            }
        }
        candidates.sort(Comparator.comparing(RolloutMeta::timestamp));
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
                .map(RolloutMeta::id)
                .toList();
    }

    /** Whether a rollout file whose name contains {@code id} exists. */
    public boolean existsForId(String id) {
        if (!Files.isDirectory(sessionsRoot)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(sessionsRoot)) {
            return files.anyMatch(path -> matchesRolloutName(path)
                    && path.getFileName().toString().contains(id));
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Failed to walk " + sessionsRoot + " while checking for id " + id, e);
            return false;
        }
    }

    private List<Path> rolloutFiles() {
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(sessionsRoot)) {
            return files.filter(CodexRolloutStore::matchesRolloutName).toList();
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Failed to walk " + sessionsRoot, e);
            return List.of();
        }
    }

    /** {@code launchedAt}'s date bucket plus the day before and after, in the system default zone. */
    private List<Path> dateBucketDirs(Instant launchedAt) {
        LocalDate date = launchedAt.atZone(ZoneId.systemDefault()).toLocalDate();
        List<Path> dirs = new ArrayList<>(3);
        for (LocalDate d : List.of(date.minusDays(1), date, date.plusDays(1))) {
            dirs.add(sessionsRoot.resolve(String.format("%04d/%02d/%02d",
                    d.getYear(), d.getMonthValue(), d.getDayOfMonth())));
        }
        return dirs;
    }

    /** Rollout files directly under the given date-bucket directories; a missing bucket contributes nothing. */
    private List<Path> rolloutFilesInBuckets(List<Path> bucketDirs) {
        List<Path> files = new ArrayList<>();
        for (Path dir : bucketDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(CodexRolloutStore::matchesRolloutName).forEach(files::add);
            } catch (IOException e) {
                LOG.log(Level.DEBUG, () -> "Failed to walk " + dir, e);
            }
        }
        return files;
    }

    private List<RolloutMeta> readMatching(List<Path> files, Path normalizedCwd) {
        List<RolloutMeta> matches = new ArrayList<>();
        for (Path file : files) {
            readMeta(file).ifPresent(meta -> {
                if (meta.source().equals("cli") && meta.cwd().equals(normalizedCwd)) {
                    matches.add(meta);
                }
            });
        }
        return matches;
    }

    private static boolean matchesRolloutName(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        return name.startsWith("rollout-") && name.endsWith(".jsonl");
    }

    private Optional<RolloutMeta> readMeta(Path file) {
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
            JsonValue payloadValue = rootObject.get("payload");
            if (!(payloadValue instanceof JsonValue.JsonObject payload)) {
                return Optional.empty();
            }
            String id = requireString(payload, "id");
            String cwd = requireString(payload, "cwd");
            String timestamp = requireString(payload, "timestamp");
            String source = requireString(payload, "source");
            if (id == null || cwd == null || timestamp == null || source == null) {
                return Optional.empty();
            }
            Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
            Instant instant = Instant.parse(timestamp);
            return Optional.of(new RolloutMeta(id, cwdPath, instant, source, file));
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "Failed to parse session_meta from " + file, e);
            return Optional.empty();
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
