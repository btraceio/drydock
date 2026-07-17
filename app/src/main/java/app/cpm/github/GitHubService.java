package app.cpm.github;

import app.cpm.git.GitExecutableLocator;
import app.cpm.git.GitExecutableNotFoundException;
import app.cpm.state.json.JsonParser;
import app.cpm.state.json.JsonValue;
import app.cpm.state.json.JsonValue.JsonArray;
import app.cpm.state.json.JsonValue.JsonNumber;
import app.cpm.state.json.JsonValue.JsonObject;
import app.cpm.state.json.JsonValue.JsonString;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub search + clone backend for the Clone-from-GitHub modal (design
 * handoff section 7). Search uses the public unauthenticated REST API
 * ({@code /search/repositories}, 10 results); pasting a full repository
 * URL (or {@code owner/name}) short-circuits the search. Cloning shells
 * out to the real {@code git clone} into a caller-chosen parent directory.
 *
 * <p>All methods return futures completed on this service's own virtual
 * thread executor; nothing here ever blocks the FX thread.</p>
 */
public final class GitHubService implements AutoCloseable {

    private static final String SEARCH_URL = "https://api.github.com/search/repositories?per_page=10&q=";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final GitExecutableLocator gitLocator = new GitExecutableLocator();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Searches GitHub repositories. A pasted URL ({@code https://github.com/owner/name[.git]})
     * or a plain {@code owner/name} is answered directly without hitting the search API.
     */
    public CompletableFuture<List<GitHubRepo>> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        Optional<GitHubRepo> direct = parseDirectReference(query);
        if (direct.isPresent()) {
            return CompletableFuture.completedFuture(List.of(direct.get()));
        }
        return CompletableFuture.supplyAsync(() -> searchBlocking(query), executor);
    }

    private static Optional<GitHubRepo> parseDirectReference(String query) {
        String candidate = query;
        if (candidate.startsWith("https://github.com/") || candidate.startsWith("http://github.com/")) {
            candidate = candidate.substring(candidate.indexOf("github.com/") + "github.com/".length());
        } else if (candidate.startsWith("git@github.com:")) {
            candidate = candidate.substring("git@github.com:".length());
        } else if (candidate.contains("://") || candidate.contains(" ")) {
            return Optional.empty();
        }
        if (candidate.endsWith(".git")) {
            candidate = candidate.substring(0, candidate.length() - ".git".length());
        }
        // owner/name, nothing else -- a bare word or deeper path is a search, not a reference.
        if (!candidate.matches("[\\w.-]+/[\\w.-]+")) {
            return Optional.empty();
        }
        // Only treat it as direct when the user clearly pasted a URL/remote;
        // a plain owner/name still searches (matches the prototype behavior).
        if (query.equals(candidate)) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepo(candidate, Optional.empty(), -1, Optional.empty(),
                "https://github.com/" + candidate + ".git"));
    }

    private List<GitHubRepo> searchBlocking(String query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        SEARCH_URL + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GitHubSearchException("GitHub search failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubSearchException("GitHub search interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new GitHubSearchException("GitHub search returned HTTP " + response.statusCode()
                    + (response.statusCode() == 403 ? " (rate limited -- try again in a minute)" : ""), null);
        }
        return parseSearchResponse(response.body());
    }

    private static List<GitHubRepo> parseSearchResponse(String body) {
        List<GitHubRepo> repos = new ArrayList<>();
        if (!(JsonParser.parse(body) instanceof JsonObject root)
                || !(root.get("items") instanceof JsonArray items)) {
            return repos;
        }
        for (JsonValue item : items.elements()) {
            if (!(item instanceof JsonObject obj)
                    || !(obj.get("full_name") instanceof JsonString fullName)
                    || !(obj.get("clone_url") instanceof JsonString cloneUrl)) {
                continue;
            }
            Optional<String> description = obj.get("description") instanceof JsonString d
                    ? Optional.of(d.value()) : Optional.empty();
            long stars = obj.get("stargazers_count") instanceof JsonNumber n ? n.asLong() : 0;
            Optional<String> language = obj.get("language") instanceof JsonString l
                    ? Optional.of(l.value()) : Optional.empty();
            repos.add(new GitHubRepo(fullName.value(), description, stars, language, cloneUrl.value()));
        }
        return repos;
    }

    /**
     * Clones {@code repo} into {@code parentDirectory/<repo-name>} with the
     * real {@code git clone}; completes with the created directory. Fails
     * (without touching anything) if the target directory already exists.
     */
    public CompletableFuture<Path> clone(GitHubRepo repo, Path parentDirectory) {
        return CompletableFuture.supplyAsync(() -> cloneBlocking(repo, parentDirectory), executor);
    }

    private Path cloneBlocking(GitHubRepo repo, Path parentDirectory) {
        Path git = gitLocator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(gitLocator.describeSearched()));
        Path target = parentDirectory.resolve(repo.defaultDirectoryName());
        if (Files.exists(target)) {
            throw new GitHubSearchException("Target directory already exists: " + target, null);
        }
        List<String> command = List.of(git.toString(), "clone", repo.cloneUrl(), target.toString());
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                String excerpt = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
                throw new GitHubSearchException("git clone failed (exit " + exit + "):\n" + excerpt.strip(), null);
            }
        } catch (IOException e) {
            throw new GitHubSearchException("Could not run git clone: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubSearchException("git clone interrupted", e);
        }
        return target;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
