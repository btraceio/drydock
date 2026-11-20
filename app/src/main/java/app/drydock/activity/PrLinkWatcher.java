package app.drydock.activity;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the PR references the agent harnesses write (see {@code
 * ClaudeHookInstaller}'s {@code drydock-pr-scan.sh} and pi's {@code
 * drydock-pr-scan.ts}) and turns them into {@link PrReference} values keyed
 * by agent session id -- the same one-file-per-session contract the activity
 * words follow, and the same polling shape as {@link SessionActivityWatcher}
 * (no watch infrastructure; a poll on the workspace's existing tick).
 *
 * <p>Each file holds one {@code owner/repo#<number>} reference per line,
 * deduplicated by the writer. The writer is deliberately repo-blind: the
 * harness does not know which repository a session belongs to, so it reports
 * every PR URL it saw and the fold (in the workspace) keeps only the ones
 * matching the session's own repository. Any number is plausible: the agent
 * may have opened the PR, or merely mentioned one it created earlier.</p>
 *
 * <p>All I/O runs on a background executor (AGENTS.md: never block the
 * JavaFX application thread on filesystem work), so {@link #poll()} returns
 * a future rather than a map.</p>
 */
public final class PrLinkWatcher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PrLinkWatcher.class.getName());

    /** One PR reference as reported by a harness: a GitHub slug and a PR number. */
    public record PrReference(String owner, String repo, int number) {

        /** {@code owner/repo}, lowercase -- GitHub slugs are case-insensitive. */
        public String slug() {
            return (owner + "/" + repo).toLowerCase(Locale.ROOT);
        }
    }

    /** GitHub owner/repo segments: alphanumeric plus {@code ._-}; no separators, no quotes. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A file is at most 64 deduplicated refs (~60 bytes each); anything longer is not ours. */
    private static final long MAX_FILE_BYTES = 8 * 1024;

    private static final int MAX_REFS = 64;

    private final Path prDirectory;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * What each agent session last reported, so unchanged files skip the map
     * rebuild. Cleared for ids the directory no longer mentions, mirroring
     * {@link SessionActivityWatcher}'s handling of sessions that stopped
     * reporting.
     */
    private final Map<String, List<PrReference>> observed = new ConcurrentHashMap<>();

    public PrLinkWatcher(Path prDirectory) {
        this(prDirectory, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers supplying (and owning the shutdown of) their own executor. */
    public PrLinkWatcher(Path prDirectory, ExecutorService executor) {
        this(prDirectory, executor, false);
    }

    private PrLinkWatcher(Path prDirectory, ExecutorService executor, boolean ownsExecutor) {
        this.prDirectory = Objects.requireNonNull(prDirectory, "prDirectory");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Reads every reference file in the directory. Files whose content is
     * unchanged since the previous poll report the same list instance, so
     * callers can skip work by identity.
     */
    public CompletableFuture<Map<String, List<PrReference>>> poll() {
        return CompletableFuture.supplyAsync(this::pollBlocking, executor);
    }

    private Map<String, List<PrReference>> pollBlocking() {
        List<Path> files;
        try (Stream<Path> listing = Files.list(prDirectory)) {
            files = listing.filter(PrLinkWatcher::plausibleRefFile).toList();
        } catch (IOException e) {
            // No directory yet simply means no harness has reported anything.
            LOG.log(Level.DEBUG, "PR reference poll failed: " + e.getMessage());
            files = List.of();
        }
        Map<String, List<PrReference>> current = new HashMap<>();
        for (Path file : files) {
            String agentSessionId = file.getFileName().toString();
            List<PrReference> refs = readRefs(file);
            if (refs.isEmpty()) {
                // Unreadable or oversized: report nothing rather than an empty
                // list, so a caller does not treat the id as present.
                observed.remove(agentSessionId);
                continue;
            }
            List<PrReference> previous = observed.get(agentSessionId);
            if (previous != null && previous.equals(refs)) {
                current.put(agentSessionId, previous);
            } else {
                current.put(agentSessionId, refs);
                observed.put(agentSessionId, refs);
            }
        }
        observed.keySet().retainAll(current.keySet());
        return Map.copyOf(current);
    }

    /**
     * Forgets one agent session's observations, e.g. after a conversation
     * rebind: the in-memory cache is cleared immediately and on-disk deletion
     * is scheduled, so a stale conversation's references can never re-apply
     * to the rebound session.
     */
    public void forget(String agentSessionId) {
        observed.remove(agentSessionId);
        try {
            executor.execute(() -> {
                try {
                    Files.deleteIfExists(prDirectory.resolve(agentSessionId));
                } catch (IOException e) {
                    // Cosmetic: the startup purge removes the file regardless.
                    LOG.log(Level.DEBUG, "Could not remove PR references for a rebound session: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutdown owns the directory's eventual cleanup; never block caller.
            LOG.log(Level.DEBUG, "Could not schedule PR reference deletion: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    /** Hidden files (a writer's in-flight {@code .tmp}) and subdirectories are not reports. */
    private static boolean plausibleRefFile(Path file) {
        String name = file.getFileName().toString();
        return !name.startsWith(".") && Files.isRegularFile(file);
    }

    private static List<PrReference> readRefs(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                LOG.log(Level.WARNING, "Ignoring oversized PR reference file " + file.getFileName());
                return List.of();
            }
            List<PrReference> refs = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseRef(line.strip()).ifPresent(refs::add);
                if (refs.size() == MAX_REFS) {
                    break;
                }
            }
            return List.copyOf(refs);
        } catch (IOException | RuntimeException e) {
            // A torn or malformed read reports nothing this poll; the next
            // write-then-rename cycle repairs it. Never worth surfacing.
            LOG.log(Level.DEBUG, "Unreadable PR reference file " + file.getFileName() + ": " + e.getMessage());
            return List.of();
        }
    }

    /** {@code owner/repo#1234} to a reference; anything else is skipped. */
    static Optional<PrReference> parseRef(String line) {
        int hash = line.lastIndexOf('#');
        if (hash <= 0 || hash == line.length() - 1) {
            return Optional.empty();
        }
        String slug = line.substring(0, hash);
        String number = line.substring(hash + 1);
        if (!number.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        int slash = slug.indexOf('/');
        if (slash <= 0 || slash == slug.length() - 1 || slug.indexOf('/', slash + 1) >= 0) {
            return Optional.empty();
        }
        String owner = slug.substring(0, slash);
        String repo = slug.substring(slash + 1);
        if (!SEGMENT.matcher(owner).matches() || !SEGMENT.matcher(repo).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PrReference(owner, repo, Integer.parseInt(number)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
