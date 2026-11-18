package app.drydock.github;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.git.GhCliService;
import app.drydock.git.GhExecutableLocator;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Creates the GitHub issue a Drydock task is mirrored into, through
 * {@code gh api --method POST repos/<owner>/<repo>/issues}. The second class
 * besides {@link GitHubReviewService} that writes to GitHub; it keeps that
 * class's shape (own executor, payload in a temp file, distinct outcomes)
 * and {@link GhCliService} stays read-only.
 *
 * <p>Without a configured repository {@code gh} resolves
 * {@code {owner}/{repo}} from the checkout at {@code root}, exactly as the
 * tree's listings do; a {@code HOST/OWNER/REPO} setting adds
 * {@code --hostname HOST}.</p>
 */
public final class GitHubIssueMirrorService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GitHubIssueMirrorService.class.getName());

    /** A write: as long as a review post, longer than a read. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    /** What creating the issue resolved to. */
    public sealed interface Outcome permits Created, Rejected, Unavailable {
    }

    /** The issue exists; {@code url} is its page, the address the task records. */
    public record Created(int number, String url) implements Outcome, CreateResult {
        public Created {
            Objects.requireNonNull(url, "url");
        }
    }

    /** {@code gh} ran and GitHub refused, or whether the issue landed could not be confirmed. */
    public record Rejected(String message) implements Outcome, CreateResult {
        public Rejected {
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code gh} is missing or not authenticated; nothing was attempted. */
    public record Unavailable(String message) implements Outcome, CreateResult {
        public Unavailable {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * What a pinned create resolved to. Unlike {@link Outcome} it separates a
     * create whose effect is unknown ({@link Uncertain}) from a confirmed
     * refusal ({@link Rejected}); {@link #create} keeps the older shape.
     */
    public sealed interface CreateResult permits Created, Rejected, Unavailable, Uncertain {
    }

    /**
     * The POST was sent but its effect is unknown: it timed out or was
     * interrupted after the spawn, or succeeded without a usable address.
     * The issue may exist; no address is known.
     */
    public record Uncertain(String message) implements CreateResult {
        public Uncertain {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Result of the read-only destination probe; never carries an issue address. */
    public sealed interface Preparation permits Confirmed, PreparationFailed {
    }

    /** The repository gh resolved: {@code route()} is the explicit {@code HOST/OWNER/REPO} for creates. */
    public record Confirmed(String host, String owner, String repo, String webUrl) implements Preparation {
        public Confirmed {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(webUrl, "webUrl");
        }

        public String route() {
            return host + "/" + owner + "/" + repo;
        }
    }

    /** Why a destination could not be confirmed. */
    public enum FailureKind { INVALID_OVERRIDE, TOOL_MISSING, PROBE_FAILED, TIMEOUT, MALFORMED }

    /** The probe did not confirm a destination; {@code kind} separates tool missing from tool failed. */
    public record PreparationFailed(FailureKind kind, String message) implements Preparation {
        public PreparationFailed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
        }
    }

    /** The process boundary; production is {@link ProcessRunner#run(List, Path, Duration)}. */
    @FunctionalInterface
    interface Runner {
        ProcessResult run(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException;
    }

    private final ExecutorService executor;
    private final Runner runner;
    private volatile Optional<Path> cachedExecutable;

    public GitHubIssueMirrorService() {
        this(ProcessRunner::run, null);
    }

    /** Test seam: a fake process boundary and, when non-null, a fixed gh executable. */
    GitHubIssueMirrorService(Runner runner, Path gh) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.runner = Objects.requireNonNull(runner, "runner");
        this.cachedExecutable = gh == null ? null : Optional.of(gh);
    }

    /**
     * Reads {@code repos/OWNER/REPO} (GET, no writes) in {@code root}'s
     * checkout and confirms the actual host/owner/repo creates will target.
     * {@code override} is optional ({@code OWNER/REPO} or
     * {@code HOST/OWNER/REPO}); absent, gh infers the repository from the
     * checkout exactly as {@link #create} does.
     */
    public CompletableFuture<Preparation> prepare(Path root, Optional<String> override) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(override, "override");
        return CompletableFuture.supplyAsync(() -> prepareBlocking(root, override), executor);
    }

    Preparation prepareBlocking(Path root, Optional<String> override) {
        if (override.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return new PreparationFailed(FailureKind.INVALID_OVERRIDE,
                    "The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new PreparationFailed(FailureKind.TOOL_MISSING, "gh is not installed");
        }
        ProcessResult result;
        try {
            result = runner.run(probeCommand(gh, override), root, PROCESS_TIMEOUT);
        } catch (IOException e) {
            LOG.log(Level.INFO, "Could not launch gh to probe the repository: " + e.getMessage());
            return new PreparationFailed(FailureKind.TOOL_MISSING, "Could not run gh: " + e.getMessage());
        } catch (ProcessTimeoutException e) {
            return new PreparationFailed(FailureKind.TIMEOUT, "Reading the GitHub repository timed out after "
                    + PROCESS_TIMEOUT.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PreparationFailed(FailureKind.PROBE_FAILED, "Reading the GitHub repository was interrupted");
        }
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            LOG.log(Level.INFO, "gh api GET repos exited " + result.exitCode()
                    + (excerpt.isBlank() ? "" : ": " + excerpt));
            return new PreparationFailed(FailureKind.PROBE_FAILED,
                    excerpt.isBlank() ? "gh exited " + result.exitCode() : excerpt);
        }
        return parseRepository(result.stdout(), override);
    }

    /** The probe argv: {@code gh api --method GET [--hostname H] repos/O/R}. Pure. */
    static List<String> probeCommand(Path gh, Optional<String> override) {
        List<String> command = new ArrayList<>(List.of(gh.toString(), "api", "--method", "GET"));
        String endpoint = "repos/{owner}/{repo}";
        if (override.isPresent()) {
            String[] parts = override.get().split("/");
            if (parts.length == 3) {
                command.add("--hostname");
                command.add(parts[0]);
                endpoint = "repos/" + parts[1] + "/" + parts[2];
            } else {
                endpoint = "repos/" + parts[0] + "/" + parts[1];
            }
        }
        command.add(endpoint);
        return command;
    }

    /** Reads gh's repository answer into a confirmed route; the host is the override's or the web URL's. */
    static Preparation parseRepository(String stdout, Optional<String> override) {
        try {
            if (JsonParser.parse(stdout) instanceof JsonObject obj
                    && obj.get("html_url") instanceof JsonString url && !url.value().isBlank()
                    && obj.get("full_name") instanceof JsonString fullName) {
                String[] name = fullName.value().split("/", -1);
                String host = URI.create(url.value()).getHost();
                if (name.length == 2 && host != null) {
                    String overrideHost = override.map(v -> v.split("/")).filter(p -> p.length == 3)
                            .map(p -> p[0]).orElse(null);
                    String route = (overrideHost != null ? overrideHost : host) + "/" + name[0] + "/" + name[1];
                    if (GhCliService.validRepository(route)) {
                        return new Confirmed(overrideHost != null ? overrideHost : host, name[0], name[1],
                                url.value());
                    }
                }
            }
        } catch (JsonParseException | IllegalArgumentException e) {
            LOG.log(Level.DEBUG, "Unparseable gh api repository response", e);
        }
        return new PreparationFailed(FailureKind.MALFORMED,
                "GitHub answered without a usable repository name and address");
    }

    /**
     * Creates one issue in the confirmed repository {@code route}
     * ({@code HOST/OWNER/REPO}) from {@code root}'s checkout, reporting an
     * unknown effect as {@link Uncertain} rather than {@link Rejected}.
     */
    public CompletableFuture<CreateResult> createPinned(Path root, String route, String title, String body) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        return CompletableFuture.supplyAsync(
                () -> createResult(root, Optional.of(route), title, body), executor);
    }

    /**
     * Creates one issue with {@code title} and {@code body} in the
     * repository {@code root} belongs to, or in {@code repository} when one
     * is configured ({@code OWNER/REPO} or {@code HOST/OWNER/REPO}).
     */
    public CompletableFuture<Outcome> create(Path root, Optional<String> repository, String title, String body) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        return CompletableFuture.supplyAsync(() -> createBlocking(root, repository, title, body), executor);
    }

    Outcome createBlocking(Path root, Optional<String> repository, String title, String body) {
        return switch (createResult(root, repository, title, body)) {
            case Created created -> created;
            case Rejected rejected -> rejected;
            case Unavailable unavailable -> unavailable;
            case Uncertain uncertain -> new Rejected(uncertain.message());
        };
    }

    CreateResult createResult(Path root, Optional<String> repository, String title, String body) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new Unavailable("gh is not installed");
        }
        if (repository.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return new Rejected("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        String payload = JsonWriter.write(JsonObject.empty()
                .put("title", new JsonString(title))
                .put("body", new JsonString(body)));
        Path tempFile;
        try {
            tempFile = Files.createTempFile("drydock-issue-", ".json");
        } catch (IOException e) {
            return new Rejected("Could not create the issue payload file: " + e.getMessage());
        }
        try {
            try {
                Files.writeString(tempFile, payload, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new Rejected("Could not write the issue payload: " + e.getMessage());
            }
            ProcessResult result;
            try {
                result = runner.run(command(gh, repository, tempFile), root, PROCESS_TIMEOUT);
            } catch (IOException e) {
                LOG.log(Level.INFO, "Could not launch gh to create an issue: " + e.getMessage());
                return new Rejected("Could not run gh: " + e.getMessage());
            } catch (ProcessTimeoutException e) {
                // A killed POST may already have reached GitHub.
                LOG.log(Level.INFO, "gh api POST issues timed out after " + PROCESS_TIMEOUT.toSeconds()
                        + "s and was killed");
                return new Uncertain("Creating the GitHub issue timed out after " + PROCESS_TIMEOUT.toSeconds()
                        + "s. Whether it was created is unknown -- check the repository's issues before retrying.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Uncertain("Creating the GitHub issue was interrupted. Whether it was created is unknown"
                        + " -- check the repository's issues before retrying.");
            }
            if (result.exitCode() != 0) {
                String excerpt = ProcessRunner.excerpt(result.stderr());
                LOG.log(Level.INFO, "gh api POST issues exited " + result.exitCode()
                        + (excerpt.isBlank() ? "" : ": " + excerpt));
                if (excerpt.contains("HTTP 401")) {
                    return new Unavailable(excerpt);
                }
                return new Rejected(excerpt.isBlank() ? "gh exited " + result.exitCode() : excerpt);
            }
            return parseCreatedResult(result.stdout());
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Could not delete issue payload temp file " + tempFile, e);
            }
        }
    }

    /**
     * The argv: {@code gh api --method POST <endpoint> --input <file>}, with
     * {@code --hostname} for a {@code HOST/OWNER/REPO} setting. Without a
     * setting the endpoint keeps gh's {@code {owner}/{repo}} placeholders,
     * which gh fills from the working directory's remote. Pure.
     */
    static List<String> command(Path gh, Optional<String> repository, Path payload) {
        List<String> command = new ArrayList<>(List.of(gh.toString(), "api", "--method", "POST"));
        String endpoint = "repos/{owner}/{repo}/issues";
        if (repository.isPresent()) {
            String[] parts = repository.get().split("/");
            if (parts.length == 3) {
                command.add("--hostname");
                command.add(parts[0]);
                endpoint = "repos/" + parts[1] + "/" + parts[2] + "/issues";
            } else {
                endpoint = "repos/" + parts[0] + "/" + parts[1] + "/issues";
            }
        }
        command.add(endpoint);
        command.add("--input");
        command.add(payload.toString());
        return command;
    }

    /**
     * GitHub's answer to a created issue. Without an {@code html_url} the
     * issue may well exist, but there is no address to record, so it is
     * {@link Rejected} with the reason spelled out.
     */
    static Outcome parseCreated(String stdout) {
        return switch (parseCreatedResult(stdout)) {
            case Created created -> created;
            case Rejected rejected -> rejected;
            case Unavailable unavailable -> unavailable;
            case Uncertain uncertain -> new Rejected(uncertain.message());
        };
    }

    /** As {@link #parseCreated}, but a success without an address is {@link Uncertain}. */
    static CreateResult parseCreatedResult(String stdout) {
        try {
            if (JsonParser.parse(stdout) instanceof JsonObject obj
                    && obj.get("html_url") instanceof JsonString url && !url.value().isBlank()) {
                int number = obj.get("number") instanceof JsonNumber n ? n.asInt() : 0;
                return new Created(number, url.value());
            }
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh api issue response", e);
        }
        return new Uncertain("GitHub answered without the new issue's address; check the repository's issues"
                + " before retrying.");
    }

    private Optional<Path> locate() {
        Optional<Path> cached = cachedExecutable;
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = GhExecutableLocator.locate(LoginShellEnvironment.currentRealPath());
        cachedExecutable = found;
        return found;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
