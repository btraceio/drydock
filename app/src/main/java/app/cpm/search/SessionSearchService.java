package app.cpm.search;

import app.cpm.git.GitExecutableLocator;
import app.cpm.process.ProcessResult;
import app.cpm.process.ProcessRunner;
import app.cpm.process.ProcessTimeoutException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Session-scoped file/text search for the Session Explorer (design handoff
 * "Worktrees &amp; Session Explorer" section A). Scope is always one
 * session's checkout (its working directory / worktree root), never global.
 *
 * <p>Prefers git plumbing ({@code git ls-files} for the file list, {@code
 * git grep} for text matches) because it is fast and respects
 * {@code .gitignore}; falls back to a bounded {@link Files#walk} for
 * directories that are not a git checkout (or when git is unavailable).
 * Everything runs on a background virtual-thread executor, mirroring
 * {@link app.cpm.git.GitStatusService} (plan section 18: never block the
 * FX thread).</p>
 */
public final class SessionSearchService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(SessionSearchService.class.getName());

    /** Bounds for the non-git fallback walk and result sizes (keep the rail snappy on huge trees). */
    private static final int MAX_FILES = 5000;
    private static final int MAX_MATCHES = 2000;
    private static final long MAX_TEXT_FILE_BYTES = 1024 * 1024;
    private static final List<String> SKIPPED_DIRECTORIES = List.of(".git", "node_modules", "build", ".gradle", "out", "target");

    /** ls-files/grep are quick local queries; a hung git must not park the rail's futures forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    /** One file-name search hit. */
    public record FileHit(Path file, Path relativePath) { }

    /** One matching line within a file; {@code matchStart}/{@code matchEnd} index the query inside {@code lineText}. */
    public record TextMatch(int lineNumber, String lineText, int matchStart, int matchEnd) { }

    /** All of one file's text matches, grouped (the rail renders one expandable row per file). */
    public record FileMatches(Path file, Path relativePath, List<TextMatch> matches) { }

    private final GitExecutableLocator gitLocator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public SessionSearchService() {
        this(new GitExecutableLocator(), Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public SessionSearchService(GitExecutableLocator gitLocator, ExecutorService executor) {
        this(gitLocator, executor, false);
    }

    private SessionSearchService(GitExecutableLocator gitLocator, ExecutorService executor, boolean ownsExecutor) {
        this.gitLocator = gitLocator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** Case-insensitive basename search over the session's file set. */
    public CompletableFuture<List<FileHit>> searchFiles(Path root, String query) {
        return CompletableFuture.supplyAsync(() -> searchFilesBlocking(root, query), executor);
    }

    /** Fixed-string, case-insensitive text search, grouped by file. */
    public CompletableFuture<List<FileMatches>> searchText(Path root, String query) {
        return CompletableFuture.supplyAsync(() -> searchTextBlocking(root, query), executor);
    }

    List<FileHit> searchFilesBlocking(Path root, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<Path> files = listFiles(root);
        List<FileHit> hits = new ArrayList<>();
        for (Path relative : files) {
            Path name = relative.getFileName();
            if (name != null && name.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(new FileHit(root.resolve(relative), relative));
                if (hits.size() >= MAX_FILES) {
                    break;
                }
            }
        }
        return hits;
    }

    List<FileMatches> searchTextBlocking(Path root, String query) {
        String needle = query.strip();
        if (needle.isEmpty()) {
            return List.of();
        }
        List<FileMatches> viaGit = gitGrep(root, needle);
        if (viaGit != null) {
            return viaGit;
        }
        return walkGrep(root, needle);
    }

    // ---- git-backed paths ---------------------------------------------------

    /** The session's file set: {@code git ls-files} when possible, else a bounded walk. Paths relative to root. */
    private List<Path> listFiles(Path root) {
        Path git = gitLocator.locate().orElse(null);
        if (git != null) {
            ProcessResult result = run(List.of(git.toString(), "-C", root.toString(),
                    "ls-files", "--cached", "--others", "--exclude-standard", "-z"));
            if (result != null && result.exitCode() == 0) {
                List<Path> files = new ArrayList<>();
                for (String entry : result.stdout().split("\u0000")) {
                    if (!entry.isEmpty()) {
                        files.add(Path.of(entry));
                        if (files.size() >= MAX_FILES) {
                            break;
                        }
                    }
                }
                return files;
            }
        }
        return walkFiles(root);
    }

    /** {@code git grep} text search; returns {@code null} when git is unusable here (caller falls back). */
    private List<FileMatches> gitGrep(Path root, String needle) {
        Path git = gitLocator.locate().orElse(null);
        if (git == null) {
            return null;
        }
        ProcessResult result = run(List.of(git.toString(), "-C", root.toString(),
                "grep", "-n", "-I", "-F", "-i", "--untracked", "-e", needle));
        if (result == null) {
            return null;
        }
        // Exit 1 with empty output = "no matches", a successful empty result;
        // anything else (not a repo, bad object, ...) falls back to the walk.
        if (result.exitCode() != 0 && !(result.exitCode() == 1 && result.stderr().isBlank())) {
            return null;
        }
        Map<Path, List<TextMatch>> grouped = new LinkedHashMap<>();
        int total = 0;
        for (String line : result.stdout().split("\n")) {
            if (line.isEmpty() || total >= MAX_MATCHES) {
                continue;
            }
            // Format: path:lineNumber:lineText (path may not contain ':' via
            // git grep's default quoting for ordinary repos; split on the
            // first two colons).
            int firstColon = line.indexOf(':');
            int secondColon = firstColon < 0 ? -1 : line.indexOf(':', firstColon + 1);
            if (secondColon < 0) {
                continue;
            }
            int lineNumber;
            try {
                lineNumber = Integer.parseInt(line.substring(firstColon + 1, secondColon));
            } catch (NumberFormatException e) {
                continue;
            }
            Path relative = Path.of(line.substring(0, firstColon));
            String text = line.substring(secondColon + 1);
            grouped.computeIfAbsent(relative, k -> new ArrayList<>())
                    .add(toMatch(lineNumber, text, needle));
            total++;
        }
        return toFileMatches(root, grouped);
    }

    // ---- bounded-walk fallbacks --------------------------------------------

    private List<Path> walkFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(SessionSearchService::notInSkippedDirectory)
                    .limit(MAX_FILES)
                    .forEach(files::add);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "File walk failed under " + root, e);
        }
        return files;
    }

    private List<FileMatches> walkGrep(Path root, String needle) {
        String lowered = needle.toLowerCase(Locale.ROOT);
        Map<Path, List<TextMatch>> grouped = new LinkedHashMap<>();
        int total = 0;
        for (Path relative : walkFiles(root)) {
            if (total >= MAX_MATCHES) {
                break;
            }
            Path file = root.resolve(relative);
            try {
                if (Files.size(file) > MAX_TEXT_FILE_BYTES || isProbablyBinary(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size() && total < MAX_MATCHES; i++) {
                    String line = lines.get(i);
                    if (line.toLowerCase(Locale.ROOT).contains(lowered)) {
                        grouped.computeIfAbsent(relative, k -> new ArrayList<>())
                                .add(toMatch(i + 1, line, needle));
                        total++;
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                // Unreadable / non-UTF-8 file: skip, keep searching.
            }
        }
        return toFileMatches(root, grouped);
    }

    private static boolean notInSkippedDirectory(Path relative) {
        for (Path segment : relative) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isProbablyBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4096);
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TextMatch toMatch(int lineNumber, String text, String needle) {
        int start = text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        int end = start < 0 ? 0 : start + needle.length();
        return new TextMatch(lineNumber, text, Math.max(start, 0), end);
    }

    private static List<FileMatches> toFileMatches(Path root, Map<Path, List<TextMatch>> grouped) {
        List<FileMatches> result = new ArrayList<>(grouped.size());
        for (Map.Entry<Path, List<TextMatch>> entry : grouped.entrySet()) {
            result.add(new FileMatches(root.resolve(entry.getKey()), entry.getKey(), List.copyOf(entry.getValue())));
        }
        return result;
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- process execution (shared ProcessRunner; null = could not run, caller falls back to the walk) ----

    private static ProcessResult run(List<String> command) {
        try {
            return ProcessRunner.run(command, null, PROCESS_TIMEOUT);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not launch git for session search: " + e.getMessage());
            return null;
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.DEBUG, "Session search git command timed out and was killed: "
                    + String.join(" ", command));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
