package app.drydock.github;

import app.drydock.process.ProcessRunner;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * GitHub deep links for a checkout (a reader's ask): whether {@code origin}
 * points at github.com, the checkout's HEAD sha, and the
 * {@code github.com}/{@code github.dev} blob URLs for a file at a line --
 * the same {@code #L<line>} anchor GitHub documents for permalinks, with a
 * commit sha rather than a branch ref so the file's shape cannot drift from
 * what the review was reading.
 *
 * <p>Everything here shells out through {@link ProcessRunner} (two quick
 * {@code git} queries per resolve, run per click rather than cached: ~10ms
 * each, and never stale), and opens the URL with the platform's browser
 * opener. All methods are async; nothing may run on the FX thread. When the
 * remote is not github.com -- an internal GitLab, a bare SSH alias -- every
 * answer is empty, and the UI says "not available" rather than inventing a
 * URL that 404s.</p>
 */
public final class GitHubLinkService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GitHubLinkService.class.getName());

    /** Both git queries are single-process quick lookups; a longer run is a hung credential prompt. */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Where the {@code git} executable is. Supplied, not discovered: the
     * workspace already found git once and every other service answers to
     * the same locator, so a second discovery here would be a second truth.
     */
    private final java.util.function.Supplier<java.util.Optional<Path>> gitExecutable;

    /** @param gitExecutable where git is; empty means every lookup reports unavailable. */
    public GitHubLinkService(java.util.function.Supplier<java.util.Optional<Path>> gitExecutable) {
        this.gitExecutable = gitExecutable;
    }

    public GitHubLinkService() {
        this(() -> Optional.of(Path.of("git")));
    }

    /** Where the links point: {@code https://github.com/<owner>/<repo>}. */
    public record Remote(String owner, String repo) { }

    /** The resolved targets of one file/line: the GitHub permalink and the github.dev editor. */
    public record Link(String githubUrl, String vscodeUrl) { }

    @Override
    public void close() {
        // Stateless: every query runs on its own virtual thread; nothing to
        // shut down.
    }

    /**
     * The checkout's GitHub remote, parsed from {@code git remote get-url
     * origin}, empty when no remote, git failed, or origin is not
     * github.com. Never blocks the FX thread.
     */
    public CompletableFuture<Optional<Remote>> remoteOf(Path checkout) {
        return CompletableFuture.supplyAsync(() -> {
            // Every argument is a fixed literal ("origin" included), so none
            // can arrive as a leading dash -- and git's driver has no
            // --end-of-options marker at all (proven: "unknown option").
            List<String> command = List.of("remote", "get-url", "origin");
            try {
                String url = git(checkout, command);
                return parseRemote(url.strip());
            } catch (Exception e) {
                // Not "an empty repo": a failed lookup could be anything from
                // "no remote" to "git missing". Logged so the log says what
                // the buttons cannot -- the UI only shows "not available".
                LOG.log(Level.DEBUG, "Could not resolve a GitHub remote for " + checkout, e);
                return Optional.<Remote>empty();
            }
        });
    }

    /**
     * The checkout's HEAD sha ({@code git rev-parse HEAD}), empty when git
     * cannot say one. Per-click, so a session that keeps committing links to
     * the commit the reader is actually on.
     */
    public CompletableFuture<Optional<String>> headShaOf(Path checkout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                            // Same literal-arguments rule as remoteOf above.
                return Optional.of(git(checkout, List.of("rev-parse", "HEAD")).strip());
            } catch (Exception e) {
                LOG.log(Level.DEBUG, "Could not resolve HEAD for " + checkout, e);
                return Optional.<String>empty();
            }
        });
    }

    /**
     * Resolves {@code file:line} of the checkout to its two URLs, empty
     * when any part of the chain is missing. The remote and the sha resolve
     * in sequence (the sha is only meaningful for a github remote).
     */
    public CompletableFuture<Optional<Link>> linkOf(Path checkout, String file, int line) {
        return remoteOf(checkout).thenCompose(remote -> remote.isEmpty()
                ? CompletableFuture.completedFuture(Optional.<Link>empty())
                : headShaOf(checkout).thenApply(sha -> {
                    if (sha.isEmpty()) {
                        return Optional.<Link>empty();
                    }
                    return Optional.of(buildLink(remote.get(), sha.get(), file, line));
                }));
    }

    /** The two URLs a GitHub file/line maps to, pure so tests pin their shape. */
    static Link buildLink(Remote remote, String sha, String file, int line) {
        String repo = remote.owner() + "/" + remote.repo();
        String path = file.startsWith("/") ? file.substring(1) : file;
        return new Link(
                "https://github.com/" + repo + "/blob/" + sha + "/" + path + "#L" + line,
                "https://github.dev/" + repo + "/blob/" + sha + "/" + path + "#L" + line);
    }

    /** Opens {@code url} in the browser. Off the FX thread. */
    public CompletableFuture<Void> openInBrowser(String url) {
        return CompletableFuture.runAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            // A URL is always scheme://..., so it cannot parse as an option
            // flag -- no end-of-options marker, and "open" (like xdg-open)
            // has none.
            List<String> command = os.contains("win")
                    ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                    : List.of(os.contains("linux") ? "xdg-open" : "open", url);
            try {
                ProcessRunner.run(command, null, OPEN_TIMEOUT);
            } catch (Exception e) {
                // The URL itself is on the clipboard-shaped failure: logged,
                // so the reader can paste it -- the open must not fail
                // silently, but it also cannot do more than log here.
                LOG.log(Level.WARNING, "Could not open " + url, e);
            }
        });
    }

    /**
     * Parses {@code origin}'s URL into a GitHub identity, if it is one.
     * Accepts the shapes GitHub hands out: {@code https://github.com/{owner}/{repo}[.git]},
     * {@code git@github.com:{owner}/{repo}[.git]}, and {@code ssh://git@github.com/{owner}/{repo}[.git]}.
     */
    static Optional<Remote> parseRemote(String url) {
        String trimmed = url.strip();
        // Strip a trailing .git once; the owner/name split follows it.
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
        }
        // Both https and scp-shaped ssh put {owner}/{repo} after "github.com"
        // or "github.com:", never earlier -- so one marker covers both.
        int marker = trimmed.indexOf("github.com");
        if (marker < 0) {
            return Optional.empty();
        }
        String rest = trimmed.substring(marker + "github.com".length());
        if (rest.startsWith(":") || rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1 || rest.indexOf('/', slash + 1) >= 0) {
            // No owner, no name, or a path deeper than owner/name: not a
            // repository URL this can link.
            return Optional.empty();
        }
        return Optional.of(new Remote(rest.substring(0, slash), rest.substring(slash + 1)));
    }

    private String git(Path checkout, List<String> command) throws Exception {
        Path executable = gitExecutable.get().orElseThrow(() ->
                new IllegalStateException("no git executable"));
        List<String> full = new ArrayList<>(List.of(
                executable.toString(), "-C", checkout.toString()));
        full.addAll(command);
        app.drydock.process.ProcessResult result =
                ProcessRunner.run(full, null, GIT_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + command + " exited " + result.exitCode()
                    + ": " + result.stderr());
        }
        return result.stdout();
    }
}