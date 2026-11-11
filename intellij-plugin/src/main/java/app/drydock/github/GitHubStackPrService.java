package app.drydock.github;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.git.GhCliService;
import app.drydock.git.GhExecutableLocator;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * The stacked pull request write (S3 of docs/StackedPrExecution.md): one
 * DRAFT pull request per task branch, its base the prerequisite task's
 * branch -- or the repository's default branch for a stack root. Runs
 * {@code gh api --method POST repos/{owner}/{repo}/pulls} with a JSON
 * payload file, the exact ownership/timeout discipline of {@link
 * GitHubIssueMirrorService} (the app's other GitHub write). {@code gh} is
 * optional: missing or unauthenticated is a visible {@link Outcome.Unavailable},
 * a refused POST a visible {@link Outcome.Rejected} that never retries by
 * itself -- the caller decides whether a retry is safe, since a killed POST
 * may already have reached GitHub.
 */
public final class GitHubStackPrService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GitHubStackPrService.class.getName());

    /** A write: as long as a review post, longer than a read. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    /** What creating the pull request resolved to. */
    public sealed interface Outcome permits Created, Rejected, Unavailable {
    }

    /** The pull request exists; {@code url} is its page. */
    public record Created(int number, String url) implements Outcome {
        public Created {
            Objects.requireNonNull(url, "url");
        }
    }

    /** {@code gh} ran and GitHub refused, or whether the PR landed could not be confirmed. */
    public record Rejected(String message) implements Outcome {
        public Rejected {
            Objects.requireNonNull(message, "message");
        }
    }

    /** {@code gh} is missing or not authenticated; nothing was attempted. */
    public record Unavailable(String message) implements Outcome {
        public Unavailable {
            Objects.requireNonNull(message, "message");
        }
    }

    private final ExecutorService executor;
    private volatile Optional<Path> cachedExecutable;

    public GitHubStackPrService() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Creates one DRAFT pull request: {@code head} stacked on {@code base},
     * titled and described by the task, in the repository {@code root}
     * belongs to, or in {@code repository} when one is configured
     * ({@code OWNER/REPO} or {@code HOST/OWNER/REPO}).
     */
    public CompletableFuture<Outcome> create(Path root, Optional<String> repository,
            String head, String base, String title, String body) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        return CompletableFuture.supplyAsync(
                () -> createBlocking(root, repository, head, base, title, body), executor);
    }

    Outcome createBlocking(Path root, Optional<String> repository, String head, String base,
            String title, String body) {
        Path gh = locate().orElse(null);
        if (gh == null) {
            return new Unavailable("gh is not installed");
        }
        return createBlocking(gh, root, repository, head, base, title, body);
    }

    /**
     * The blocking body with the gh executable injected, so tests run the
     * guard and the process-bound path without gh installed. Starts with the
     * invalid-repository guard, which refuses before any process runs.
     */
    Outcome createBlocking(Path gh, Path root, Optional<String> repository, String head,
            String base, String title, String body) {
        if (repository.filter(value -> !GhCliService.validRepository(value)).isPresent()) {
            return new Rejected("The configured GitHub repository is not OWNER/REPO or HOST/OWNER/REPO");
        }
        String payload = JsonWriter.write(payload(title, body, head, base));
        Path tempFile;
        try {
            tempFile = Files.createTempFile("drydock-stack-pr-", ".json");
        } catch (IOException e) {
            return new Rejected("Could not create the pull request payload file: " + e.getMessage());
        }
        try {
            try {
                Files.writeString(tempFile, payload, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new Rejected("Could not write the pull request payload: " + e.getMessage());
            }
            ProcessResult result;
            try {
                result = ProcessRunner.run(command(gh, repository, tempFile), root, PROCESS_TIMEOUT);
            } catch (IOException e) {
                LOG.log(Level.INFO, "Could not launch gh to create a pull request: " + e.getMessage());
                return new Rejected("Could not run gh: " + e.getMessage());
            } catch (ProcessTimeoutException e) {
                // A killed POST may already have reached GitHub.
                LOG.log(Level.INFO, "gh api POST pulls timed out after " + PROCESS_TIMEOUT.toSeconds()
                        + "s and was killed");
                return new Rejected("Creating the pull request timed out after "
                        + PROCESS_TIMEOUT.toSeconds() + "s. Whether it was created is unknown -- check"
                        + " the repository's pull requests before retrying.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Rejected("Creating the pull request was interrupted. Whether it was created"
                        + " is unknown -- check the repository's pull requests before retrying.");
            }
            return ghOutcome(result);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Could not delete pull request payload temp file " + tempFile, e);
            }
        }
    }

    /**
     * The argv: {@code gh api --method POST repos/{owner}/{repo}/pulls
     * --input <file>}, with {@code --hostname} for a {@code HOST/OWNER/REPO}
     * setting. Without a setting the endpoint keeps gh's {@code {owner}/{repo}}
     * placeholders, which gh fills from the working directory's remote. Pure.
     */
    static List<String> command(Path gh, Optional<String> repository, Path payload) {
        List<String> command = new ArrayList<>(List.of(gh.toString(), "api", "--method", "POST"));
        String endpoint = "repos/{owner}/{repo}/pulls";
        if (repository.isPresent()) {
            String[] parts = repository.get().split("/");
            if (parts.length == 3) {
                command.add("--hostname");
                command.add(parts[0]);
                endpoint = "repos/" + parts[1] + "/" + parts[2] + "/pulls";
            } else {
                endpoint = "repos/" + parts[0] + "/" + parts[1] + "/pulls";
            }
        }
        command.add(endpoint);
        command.add("--input");
        command.add(payload.toString());
        return command;
    }

    /**
     * The POST payload: one DRAFT pull request with {@code head} stacked on
     * {@code base}, titled and described by the task. Pure.
     */
    static JsonObject payload(String title, String body, String head, String base) {
        return JsonObject.empty()
                .put("title", new JsonString(title))
                .put("body", new JsonString(body))
                .put("head", new JsonString(head))
                .put("base", new JsonString(base))
                .put("draft", new JsonBoolean(true));
    }

    /**
     * A gh run's outcome. A non-zero exit is a visible refusal -- unless
     * stderr shows an authentication problem, which is {@link Unavailable};
     * a zero exit delegates to {@link #parseCreated}. Pure aside from the
     * INFO log of the refusal.
     */
    static Outcome ghOutcome(ProcessResult result) {
        if (result.exitCode() != 0) {
            String excerpt = ProcessRunner.excerpt(result.stderr());
            LOG.log(Level.INFO, "gh api POST pulls exited " + result.exitCode()
                    + (excerpt.isBlank() ? "" : ": " + excerpt));
            if (excerpt.contains("HTTP 401")) {
                return new Unavailable(excerpt);
            }
            return new Rejected(excerpt.isBlank() ? "gh exited " + result.exitCode() : excerpt);
        }
        return parseCreated(result.stdout());
    }

    /**
     * GitHub's answer to a created pull request. A confirmed creation needs
     * BOTH a non-blank {@code html_url} AND a numeric {@code number}:
     * without either, the PR may well exist, but the creation cannot be
     * confirmed, so it is {@link Rejected} with the reason spelled out.
     */
    static Outcome parseCreated(String stdout) {
        try {
            if (JsonParser.parse(stdout) instanceof JsonObject obj
                    && obj.get("html_url") instanceof JsonString url && !url.value().isBlank()
                    && obj.get("number") instanceof JsonNumber n) {
                return new Created(n.asInt(), url.value());
            }
            return new Rejected("GitHub's answer names no pull request URL or number; whether it"
                    + " was created is unknown -- check the repository's pull requests before"
                    + " retrying.");
        } catch (JsonParseException | NumberFormatException e) {
            return new Rejected("GitHub's answer did not parse; whether the pull request was created"
                    + " is unknown -- check the repository's pull requests before retrying.");
        }
    }

    /** The gh executable, resolved once and cached, as the mirror service does. */
    private Optional<Path> locate() {
        Optional<Path> cached = cachedExecutable;
        if (cached != null) {
            return cached;
        }
        Optional<Path> located = GhExecutableLocator.locate(LoginShellEnvironment.currentRealPath());
        cachedExecutable = located;
        return located;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
