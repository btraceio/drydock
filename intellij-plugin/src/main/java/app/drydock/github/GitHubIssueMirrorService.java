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
    public record Created(int number, String url) implements Outcome {
        public Created {
            Objects.requireNonNull(url, "url");
        }
    }

    /** {@code gh} ran and GitHub refused, or whether the issue landed could not be confirmed. */
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

    public GitHubIssueMirrorService() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
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
                result = ProcessRunner.run(command(gh, repository, tempFile), root, PROCESS_TIMEOUT);
            } catch (IOException e) {
                LOG.log(Level.INFO, "Could not launch gh to create an issue: " + e.getMessage());
                return new Rejected("Could not run gh: " + e.getMessage());
            } catch (ProcessTimeoutException e) {
                // A killed POST may already have reached GitHub.
                LOG.log(Level.INFO, "gh api POST issues timed out after " + PROCESS_TIMEOUT.toSeconds()
                        + "s and was killed");
                return new Rejected("Creating the GitHub issue timed out after " + PROCESS_TIMEOUT.toSeconds()
                        + "s. Whether it was created is unknown -- check the repository's issues before retrying.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Rejected("Creating the GitHub issue was interrupted. Whether it was created is unknown"
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
            return parseCreated(result.stdout());
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
        try {
            if (JsonParser.parse(stdout) instanceof JsonObject obj
                    && obj.get("html_url") instanceof JsonString url && !url.value().isBlank()) {
                int number = obj.get("number") instanceof JsonNumber n ? n.asInt() : 0;
                return new Created(number, url.value());
            }
        } catch (JsonParseException | NumberFormatException e) {
            LOG.log(Level.DEBUG, "Unparseable gh api issue response", e);
        }
        return new Rejected("GitHub answered without the new issue's address; check the repository's issues"
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
