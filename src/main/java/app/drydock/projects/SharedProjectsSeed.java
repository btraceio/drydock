package app.drydock.projects;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The first content of the shared projects list, taken from the standalone
 * app's own list — never from the plugin's, which registered every project
 * the IDE ever opened.
 *
 * <p>The shared list names main checkouts only. An app entry that is a
 * linked worktree is folded into its main checkout: dropped when the main
 * checkout is also registered, re-rooted onto it otherwise, keeping the
 * entry's id so the app's sessions stay linked. Remote entries are kept as
 * they are; an entry whose main checkout cannot be resolved (the folder is
 * gone) is kept as it is rather than lost.</p>
 *
 * <p>Reading the app's state here is read-only and decodes nothing but the
 * {@code repositories} array, so a state file written by a different app
 * version still seeds.</p>
 */
public final class SharedProjectsSeed {

    private static final Logger LOG = System.getLogger(SharedProjectsSeed.class.getName());

    private SharedProjectsSeed() {
    }

    /** Bounded wait for {@code git worktree list} when resolving a main checkout. */
    private static final long MAIN_CHECKOUT_TIMEOUT_SECONDS = 15;

    /**
     * The main checkout of the repository a folder belongs to, through
     * {@code git worktree list}: the folder itself for a main checkout,
     * empty when git cannot tell (the folder is gone, or it is not a
     * repository). The function blocks: call it off the UI thread.
     */
    public static Function<Path, Optional<Path>> mainCheckoutVia(WorktreeService worktrees) {
        return root -> {
            try {
                return worktrees.list(root).get(MAIN_CHECKOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                        .filter(Worktree::mainCheckout)
                        .map(Worktree::path)
                        .findFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "No main checkout for " + root + ": " + e);
                return Optional.empty();
            }
        };
    }

    /** {@code ~/Library/Application Support/ClaudeProjectManager}, where every app install keeps its state. */
    public static Path appDataRoot() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "ClaudeProjectManager");
    }

    /**
     * Converts the app's repositories. {@code mainCheckoutOf} maps a root to
     * its main checkout (itself for a main checkout), empty when git cannot
     * tell. Blocks only as long as {@code mainCheckoutOf} does.
     */
    public static List<SharedProject> fromRepositories(List<Repository> repositories,
                                                       Function<Path, Optional<Path>> mainCheckoutOf) {
        record Resolved(Repository repository, Path main, boolean linkedWorktree) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (Repository repository : repositories) {
            if (repository.isRemote()) {
                resolved.add(new Resolved(repository, repository.root(), false));
                continue;
            }
            Path main = mainCheckoutOf.apply(repository.root()).map(Path::normalize).orElse(repository.root());
            boolean linked = !RepositoryCatalog.canonicalize(main).equals(RepositoryCatalog.canonicalize(repository.root()));
            resolved.add(new Resolved(repository, main, linked));
        }
        // Main checkouts claim their roots first, so a worktree entry never
        // displaces the entry of the repository it belongs to.
        Set<Path> claimed = new HashSet<>();
        List<SharedProject> seeded = new ArrayList<>();
        for (boolean linkedPass : new boolean[] {false, true}) {
            for (Resolved entry : resolved) {
                if (entry.linkedWorktree() != linkedPass || !claimed.add(RepositoryCatalog.canonicalize(entry.main()))) {
                    continue;
                }
                Repository repository = entry.repository();
                String name = entry.linkedWorktree()
                        ? Optional.ofNullable(entry.main().getFileName()).map(Path::toString).orElse(repository.displayName())
                        : repository.displayName();
                seeded.add(new SharedProject(repository.id(), entry.main(), name, repository.addedAt(),
                        Optional.ofNullable(repository.remote())));
            }
        }
        return seeded;
    }

    /**
     * The most recently written app state file under {@code dataRoot}: the
     * shared {@code state.json} or an install's {@code install-*}/{@code
     * state.json}. Empty when there is none.
     */
    public static Optional<Path> newestAppStateFile(Path dataRoot) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(dataRoot.resolve("state.json"));
        if (Files.isDirectory(dataRoot)) {
            try (DirectoryStream<Path> installs = Files.newDirectoryStream(dataRoot, "install-*")) {
                for (Path install : installs) {
                    candidates.add(install.resolve("state.json"));
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, () -> "Could not list app installs in " + dataRoot + ": " + e);
            }
        }
        Path newest = null;
        FileTime newestTime = null;
        for (Path candidate : candidates) {
            try {
                FileTime time = Files.getLastModifiedTime(candidate);
                if (newestTime == null || time.compareTo(newestTime) > 0) {
                    newest = candidate;
                    newestTime = time;
                }
            } catch (IOException e) {
                // absent: not a candidate
            }
        }
        return Optional.ofNullable(newest);
    }

    /** The {@code repositories} of an app state file; empty when it cannot be read. Malformed entries are skipped. */
    public static List<Repository> readRepositoriesOnly(Path stateFile) {
        JsonValue root;
        try {
            root = JsonParser.parse(Files.readString(stateFile, StandardCharsets.UTF_8));
        } catch (IOException | JsonParseException e) {
            LOG.log(Level.WARNING, () -> "Could not read the app's projects from " + stateFile + ": " + e);
            return List.of();
        }
        if (!(root instanceof JsonObject object) || !(object.get("repositories") instanceof JsonArray array)) {
            return List.of();
        }
        List<Repository> repositories = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            try {
                if (element instanceof JsonObject entry) {
                    repositories.add(repository(entry));
                }
            } catch (IllegalArgumentException | DateTimeException | NullPointerException e) {
                LOG.log(Level.WARNING, () -> "Skipping a malformed app project in " + stateFile + ": " + e);
            }
        }
        return repositories;
    }

    private static Repository repository(JsonObject entry) {
        SshRemote remote = null;
        if (entry.get("remote") instanceof JsonObject remoteObject) {
            remote = new SshRemote(string(remoteObject, "host"), string(remoteObject, "path"));
        }
        return new Repository(RepositoryId.of(string(entry, "id")), Path.of(string(entry, "root")),
                string(entry, "displayName"), Instant.parse(string(entry, "addedAt")),
                Instant.parse(string(entry, "lastOpenedAt")), RepositorySettings.DEFAULT, remote);
    }

    private static String string(JsonObject object, String key) {
        if (object.get(key) instanceof JsonString value) {
            return value.value();
        }
        throw new IllegalArgumentException("missing \"" + key + "\"");
    }
}
