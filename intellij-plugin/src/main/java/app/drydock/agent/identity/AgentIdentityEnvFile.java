package app.drydock.agent.identity;

import app.drydock.agent.providers.AgentCommands;
import app.drydock.domain.ManagedSessionId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Writes the session-scoped env file the agent-identity launch prefix sources.
 *
 * <p>The file lives at {@code <base>/identity/env/<sessionId>.env}, owner-only
 * (0600) inside an owner-only directory, exactly the containment the per-session
 * MCP credential files get: the directory exists solely to hold these files, so
 * its listing is a discovery vector of its own.</p>
 *
 * <p>The file's life is milliseconds, by design. The launch prefix is
 * {@code set -ea; . <env>; rm -f <env>; exec <agent command>} — the shell
 * sources it into the environment and deletes it before exec'ing the agent, so
 * unlike the MCP token file (which the agent reads throughout its life) nothing
 * ever reads this file after the sourcing moment. No session-end delete path
 * exists because there is nothing left to delete; the only leftovers are from a
 * process killed between the write and the sourcing, and {@link #purgeStale()}
 * removes only files older than one minute, so a concurrent launch's fresh
 * file cannot be swept mid-sourcing.</p>
 *
 * <p>Every value is written single-quoted ({@link AgentCommands#shellQuote}),
 * since the file is sourced by a POSIX shell: a value's special characters must
 * reach the environment verbatim. {@link AgentIdentitySpec#parse} has already
 * ruled out newlines, so one line is one variable.</p>
 */
public final class AgentIdentityEnvFile {

    private static final Logger LOG = System.getLogger(AgentIdentityEnvFile.class.getName());

    private static final FileAttribute<?> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /**
     * The {@code identity/} tree exists solely to hold session secrets, so its
     * listing is a discovery vector of its own: an owner-only directory is not
     * traversable or listable by anyone else, whatever the umask would have
     * allowed.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final Duration STALE_FILE_AGE = Duration.ofMinutes(1);

    private final Path baseDirectory;

    public AgentIdentityEnvFile(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    private Path envDirectory() {
        return baseDirectory.resolve("identity").resolve("env");
    }

    private Path fileFor(ManagedSessionId sessionId) {
        return envDirectory().resolve(sessionId + ".env");
    }

    /**
     * Writes (idempotently rewrites) the env file for a session, returning its
     * path for the launch prefix. Performs filesystem I/O; callers run off the
     * EDT (the registry's {@code start} contract).
     */
    public Path write(ManagedSessionId sessionId, Map<String, String> variables) throws IOException {
        createEnvDirectory();
        Path target = fileFor(sessionId);
        writeAtomically(target, render(variables));
        return target;
    }

    /**
     * Deletes env-file leftovers older than one minute, preserving fresh files
     * that a concurrent IDE may still be sourcing. Tolerates a missing
     * directory (first run). Performs filesystem I/O; callers run off the EDT.
     */
    public void purgeStale() {
        Instant cutoff = Instant.now().minus(STALE_FILE_AGE);
        try (Stream<Path> stale = Files.list(envDirectory())) {
            for (Path file : stale.toList()) {
                try {
                    FileTime modified = Files.getLastModifiedTime(file);
                    if (modified.toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    // Preserve entries whose age cannot be established; they
                    // may belong to a concurrently launching session.
                    LOG.log(Level.WARNING, "Could not check or purge stale agent-identity env file " + file
                            + ": " + e.getMessage());
                }
            }
        } catch (NoSuchFileException e) {
            // Expected, common case: on a first run identity/env/ has never
            // been created. Nothing to purge, and nothing worth a WARNING.
        } catch (IOException e) {
            // Cosmetic cleanup: a genuine failure here must not prevent
            // startup, but is still worth surfacing.
            LOG.log(Level.WARNING, "Could not purge stale agent-identity env files: " + e.getMessage());
        }
    }

    /**
     * Deletes the session's env file, if it is still there — the cleanup for a
     * launch that failed between the write and the sourcing (the normal path
     * is the prefix's own {@code rm -f} after sourcing; see the class doc).
     * Best-effort: a leftover is purged at the next startup anyway. Performs
     * filesystem I/O; callers run off the EDT.
     */
    public void delete(ManagedSessionId sessionId) {
        try {
            Files.deleteIfExists(fileFor(sessionId));
            Files.deleteIfExists(fileFor(sessionId).resolveSibling(fileFor(sessionId).getFileName() + ".tmp"));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete agent-identity env file for session " + sessionId
                    + ": " + e.getMessage());
        }
    }

    /**
     * The file's rendered content: every variable as {@code KEY='value'}, in
     * iteration order (a {@link LinkedHashMap} keeps the credential command's
     * own order, plumbing appended after). Sourcing with {@code set -ea}
     * exports every line.
     */
    static String render(Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Drydock agent identity session environment.\n");
        sb.append("# Written by Drydock at launch, sourced (and deleted) by the session's launch prefix;\n");
        sb.append("# nothing reads this file after the launch moment.\n");
        variables.forEach((key, value) ->
                sb.append(key).append('=').append(AgentCommands.shellQuote(value)).append('\n'));
        return sb.toString();
    }

    private void createEnvDirectory() throws IOException {
        Path directory = envDirectory();
        Path identityDirectory = directory.getParent();
        Files.createDirectories(identityDirectory.getParent());
        try {
            Files.createDirectory(identityDirectory,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException existing) {
            Files.setPosixFilePermissions(identityDirectory, OWNER_ONLY_DIRECTORY);
        }
        try {
            Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException existing) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
        }
    }

    /**
     * Temp-file-plus-rename, so a concurrently launching session never sources
     * a partial file. Both the temp file and the target are created owner-only,
     * since the content carries a token.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        Files.createFile(temp, OWNER_ONLY);
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveFailure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                LOG.log(Level.WARNING, "Could not delete failed agent-identity env temp file " + temp
                        + ": " + cleanupFailure.getMessage());
            }
            throw moveFailure;
        }
    }
}
