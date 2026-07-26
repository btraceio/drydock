package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes the per-session {@code --mcp-config} file that tells a launched
 * {@code claude} where the local {@link McpServer} is listening and which
 * bearer token to send.
 *
 * <p>This is deliberately a separate file per session rather than an
 * addition to {@code ClaudeHookInstaller}'s shared settings file. That
 * settings file is written once at startup and injected into every
 * session via {@code --settings}, so it has nowhere to put a token that
 * differs per session. Modeled on {@code ClaudeHookInstaller}'s
 * atomic-write and stale-purge patterns.</p>
 *
 * <p>The file holds a bearer token, so it is created owner-readable only
 * ({@code rw-------}) -- including the temporary file used for the atomic
 * write, since setting permissions only on the final file would leave the
 * token briefly world-readable in between. The token is attribution, not
 * isolation: any process running as the same user can still read a
 * sibling session's config file. The {@code mcp/} directory holding them
 * is owner-only ({@code rwx------}) for the same reason.</p>
 *
 * <p>All methods perform filesystem I/O and must be invoked off the
 * JavaFX application thread (AGENTS.md).</p>
 */
public final class McpConfigWriter {

    private static final Logger LOG = System.getLogger(McpConfigWriter.class.getName());

    private static final FileAttribute<?> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /**
     * The {@code mcp/} directory exists solely to hold token files, so its
     * listing is a discovery vector of its own: an owner-only directory is not
     * traversable or listable by anyone else, whatever the umask would have
     * allowed.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final String SESSION_TOKEN_HEADER = "X-Drydock-Session-Token";

    private final Path baseDirectory;

    public McpConfigWriter(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    private Path mcpDirectory() {
        return baseDirectory.resolve("mcp");
    }

    private Path fileFor(ManagedSessionId sessionId) {
        return mcpDirectory().resolve(sessionId + ".json");
    }

    /**
     * Writes (or idempotently rewrites) the {@code --mcp-config} file for a
     * session, returning the path to pass on the command line. Performs
     * filesystem I/O; callers must invoke this off the JavaFX application
     * thread (AGENTS.md).
     */
    public Path writeFor(ManagedSessionId sessionId, String endpointUrl, String token) throws IOException {
        createMcpDirectory();
        Path target = fileFor(sessionId);
        writeAtomically(target, JsonWriter.write(configJson(endpointUrl, token)));
        return target;
    }

    /**
     * Creates {@code <base>/mcp/} owner-only, and tightens it when it is
     * already there: a directory left by an earlier run (or by a version that
     * inherited the umask) must not keep looser permissions just because it
     * exists.
     */
    private void createMcpDirectory() throws IOException {
        Path directory = mcpDirectory();
        Files.createDirectories(directory.getParent());
        try {
            Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException existing) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
        }
    }

    private static JsonValue configJson(String endpointUrl, String token) {
        Map<String, JsonValue> headers = new LinkedHashMap<>();
        headers.put(SESSION_TOKEN_HEADER, new JsonString(token));

        Map<String, JsonValue> drydockServer = new LinkedHashMap<>();
        drydockServer.put("type", new JsonString("http"));
        drydockServer.put("url", new JsonString(endpointUrl));
        drydockServer.put("headers", new JsonObject(headers));

        Map<String, JsonValue> mcpServers = new LinkedHashMap<>();
        mcpServers.put("drydock", new JsonObject(drydockServer));

        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("mcpServers", new JsonObject(mcpServers));
        return new JsonObject(root);
    }

    /**
     * Removes a session's config file, logging a WARNING rather than
     * throwing on failure: a leftover file is cosmetic next to failing a
     * session close. Performs filesystem I/O; callers must invoke this off
     * the JavaFX application thread (AGENTS.md).
     */
    public void delete(ManagedSessionId sessionId) {
        try {
            Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete mcp config for session " + sessionId + ": " + e.getMessage());
        }
    }

    /**
     * Drops every config file left behind by a previous app run. No
     * terminal process survives an app restart, so every file present at
     * startup is by definition stale -- and each holds a token that no
     * longer resolves to a running {@link McpServer}. Mirrors {@code
     * ClaudeHookInstaller.purgeStaleActivity}.
     *
     * <p>Tolerates a missing {@code mcp/} directory: on a first run it does
     * not exist yet, and {@link Files#list} would otherwise throw {@link
     * java.nio.file.NoSuchFileException}. Performs filesystem I/O; callers
     * must invoke this off the JavaFX application thread (AGENTS.md).</p>
     */
    public void purgeStale() {
        try (Stream<Path> stale = Files.list(mcpDirectory())) {
            for (Path file : stale.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (NoSuchFileException e) {
            // Expected, common case: on a first run mcp/ has never been
            // created. Nothing to purge, and nothing worth a WARNING.
        } catch (IOException e) {
            // Cosmetic cleanup: a genuine failure here must not prevent
            // startup, but is still worth surfacing.
            LOG.log(Level.WARNING, "Could not purge stale mcp configs: " + e.getMessage());
        }
    }

    /**
     * Temp-file-plus-rename, so a concurrently launching claude never reads
     * a partial file. Both the temp file and the target are created
     * owner-only, since the content carries a bearer token.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        Files.createFile(temp, OWNER_ONLY);
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
