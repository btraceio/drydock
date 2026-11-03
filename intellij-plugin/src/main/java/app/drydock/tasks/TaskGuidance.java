package app.drydock.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Materializes the bundled task-guidance skill -- the {@code /tasks/SKILL.md}
 * resource T42a ships with the plugin -- as a VERSIONED directory tree under
 * the plugin-owned state directory: {@code <state>/tasks-guidance/<version>/},
 * holding the layout the per-invocation launch flags consume: {@code
 * .claude-plugin/plugin.json} (the Claude plugin manifest) plus {@code
 * skills/drydock-tasks/SKILL.md} (the skill itself, byte-identical to the
 * bundled resource), so Claude's {@code --plugin-dir} takes the version
 * directory and pi's {@code --skill} takes the skill file inside it.
 *
 * <p>Why a filesystem copy at all. D17/P7a: the guidance must load from a
 * path a harness accepts at launch -- a jar URL is not one -- and it must
 * travel per invocation ({@code --plugin-dir}/{@code --skill}), never as
 * global harness configuration. So this class writes NOTHING outside the
 * state directory its caller injects: it never resolves the user home,
 * never touches a harness config ({@code ~/.claude}, {@code ~/.pi}, ...),
 * and the wiring that hands paths to launches is later work (T26), not a
 * file mutation here.</p>
 *
 * <p>Immutable bytes per version. The same version always materializes the
 * same bytes: the skill is the bundled resource verbatim and the manifest is
 * rendered deterministically from constants, so a re-install of the same
 * version is a no-op that verifies the installed bytes and never rewrites
 * them -- installed files keep their bytes and mtimes, and a mismatch is a
 * visible failure, never a silent rewrite that churns bytes under a reader.</p>
 *
 * <p>Install atomicity. The whole tree is staged in a sibling temp directory
 * under {@code tasks-guidance/} (same filesystem as the target), every staged
 * file fsynced before publication, then published by one directory move
 * ({@code ATOMIC_MOVE}, the TaskRunFiles discipline at directory granularity).
 * A failed install leaves no partial directory: the staging tree is deleted
 * and the target was never touched. Concurrent installs of the same version
 * converge -- the directory move is refused when the target exists, so one
 * writer wins, the loser discards its staging tree, and both verify the same
 * published bytes.</p>
 *
 * <p>Path stability and previews. Every lookup is a pure resolution: the
 * path for a version never changes, and resolving or reading it ({@link
 * #guidanceDirectory()}, {@link #claudePluginDirectory()}, {@link
 * #piSkillFile()}, {@link #isInstalled()}) creates nothing -- materialization
 * happens only on explicit {@link #install()}, which is plain blocking
 * filesystem I/O callable from any thread the caller chooses (off the FX
 * application thread in production, per AGENTS.md) and needs no EDT.</p>
 */
public final class TaskGuidance {

    private static final Logger LOG = System.getLogger(TaskGuidance.class.getName());

    /** The guidance content's version: the directory name and manifest version. */
    static final String CURRENT_VERSION = "1";

    private static final String GUIDANCE_ROOT_DIRECTORY = "tasks-guidance";
    private static final String SKILL_RESOURCE = "/tasks/SKILL.md";
    private static final String SKILL_NAME = "drydock-tasks";
    private static final Path SKILL_RELATIVE =
            Path.of("skills", SKILL_NAME, "SKILL.md");
    private static final Path MANIFEST_RELATIVE = Path.of(".claude-plugin", "plugin.json");

    /** A version names a directory under plugin-owned state; it is not free text. */
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private final Path stateDirectory;
    private final String version;
    private final String skillResource;

    /** The guidance this plugin build carries, under its own state directory. */
    public TaskGuidance(Path stateDirectory) {
        this(stateDirectory, CURRENT_VERSION, SKILL_RESOURCE);
    }

    /**
     * An explicit version and skill resource: the seam the acceptance
     * fixtures (T25b) use to exercise a second version installing alongside
     * the first and a failed install, without any production caller seeing it.
     */
    TaskGuidance(Path stateDirectory, String version, String skillResource) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(skillResource, "skillResource");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "version must be a short file-name-safe token: " + version);
        }
        this.version = version;
        this.skillResource = skillResource;
    }

    /** This guidance's version. */
    public String version() {
        return version;
    }

    /**
     * The versioned guidance directory, {@code
     * <state>/tasks-guidance/<version>} -- the provider-neutral location
     * handed to launches. Resolving it creates nothing; the path for a
     * version never changes.
     */
    public Path guidanceDirectory() {
        return stateDirectory.resolve(GUIDANCE_ROOT_DIRECTORY).resolve(version);
    }

    /**
     * The directory Claude's {@code --plugin-dir} takes: the version
     * directory itself, whose root holds {@code .claude-plugin/plugin.json}
     * and whose {@code skills/} subtree is the skill. Pure resolution.
     */
    public Path claudePluginDirectory() {
        return guidanceDirectory();
    }

    /**
     * The skill file pi's {@code --skill} takes, inside the same version
     * tree. Pure resolution; {@link #install()} is what makes it exist.
     */
    public Path piSkillFile() {
        return guidanceDirectory().resolve(SKILL_RELATIVE);
    }

    /**
     * The bundled skill text, read from the classpath -- the preview of what
     * {@link #install()} would materialize. Reads no installed file and
     * writes nothing anywhere.
     */
    public String bundledSkillText() throws IOException {
        return new String(bundledSkill(), StandardCharsets.UTF_8);
    }

    /**
     * Whether this version is installed with exactly the bundled bytes.
     * Read-only: it creates nothing (an absent install is answered, not
     * built), and bytes that do not match the bundle read as not-installed
     * rather than silently accepted.
     */
    public boolean isInstalled() {
        try {
            verifyInstalled();
            return true;
        } catch (IOException absentOrMismatched) {
            return false;
        }
    }

    /**
     * Materializes this version's guidance tree and returns its directory
     * ({@link #guidanceDirectory()}). Idempotent and byte-stable: when the
     * version is already installed with the bundled bytes this is a verify
     * that touches nothing, and when the installed bytes mismatch the bundle
     * it fails visibly rather than rewriting the installed files. On any
     * failure no partial directory remains. Blocking filesystem I/O; call it
     * off the FX application thread in production. Writes only under the
     * injected state directory.
     */
    public Path install() throws IOException {
        Path dir = guidanceDirectory();
        byte[] skill = bundledSkill();
        byte[] manifest = manifestBytes();
        if (Files.exists(dir)) { // same version present: verify, never rewrite
            verifyInstalled(skill, manifest);
            return dir;
        }
        Path parent = dir.getParent();
        Files.createDirectories(parent); // <state>/tasks-guidance, ours to make
        Path staging = Files.createTempDirectory(parent, "install-" + version + "-");
        IOException failure = null;
        boolean published = false;
        try {
            writeFile(staging.resolve(MANIFEST_RELATIVE), manifest);
            writeFile(staging.resolve(SKILL_RELATIVE), skill);
            try {
                Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
                published = true;
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                // staging and dir are siblings on one filesystem, so the
                // rename is atomic in practice; the fallback still refuses
                // to replace an existing target, keeping the race honest.
                Files.move(staging, dir);
                published = true;
            } catch (FileSystemException moveRefused) {
                // The loser of a concurrent install sees the target appear
                // under it; rename reports that as ENOTEMPTY/EEXIST, which
                // reaches here as FileSystemException subclasses (or, on some
                // filesystems, the bare exception). If the target is a
                // directory now, another install of this same version won:
                // discard ours and let the shared verify below hold both
                // racers to the same published bytes. Otherwise it is a real
                // failure: nothing partial exists and it propagates.
                if (!Files.isDirectory(dir)) {
                    throw moveRefused;
                }
            }
        } catch (IOException installFailure) {
            failure = installFailure;
        }
        if (!published) {
            discard(staging, failure); // no partial directory survives a failure
        }
        if (failure != null) {
            throw failure;
        }
        verifyInstalled(skill, manifest); // one install, or two racing: same bytes
        return dir;
    }

    /**
     * The installed bytes are exactly the bundled bytes: every layout file
     * present, byte-identical, in the version directory. Read-only.
     */
    private void verifyInstalled() throws IOException {
        verifyInstalled(bundledSkill(), manifestBytes());
    }

    private void verifyInstalled(byte[] skill, byte[] manifest) throws IOException {
        Path dir = guidanceDirectory();
        if (!Files.isDirectory(dir)) {
            throw new IOException("not a directory: " + dir);
        }
        expect(dir.resolve(MANIFEST_RELATIVE), manifest);
        expect(dir.resolve(SKILL_RELATIVE), skill);
    }

    private static void expect(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("installed guidance is missing " + file);
        }
        if (!Arrays.equals(Files.readAllBytes(file), expected)) {
            throw new IOException("installed guidance does not match the bundled bytes: "
                    + file);
        }
    }

    /** The bundled skill, read whole up front: a missing resource fails before anything is written. */
    private byte[] bundledSkill() throws IOException {
        try (InputStream in = TaskGuidance.class.getResourceAsStream(skillResource)) {
            if (in == null) {
                throw new IOException("bundled skill resource not found: " + skillResource);
            }
            return in.readAllBytes();
        }
    }

    /**
     * The plugin manifest, rendered from constants so the same version
     * always produces identical bytes. {@code version} is pattern-checked,
     * so it cannot escape the JSON string.
     */
    private byte[] manifestBytes() {
        return ("{\n"
                + "  \"name\": \"" + SKILL_NAME + "\",\n"
                + "  \"version\": \"" + version + "\",\n"
                + "  \"description\": \"Drydock delayed tasks: the four task tools, "
                + "dependency-aware planning and the review pause.\"\n"
                + "}\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One staged file, fsynced before the directory move publishes the tree
     * (the TaskRunFiles fsync-before-move discipline; the staged tree is
     * private until the move, so no reader ever sees this write).
     */
    private static void writeFile(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    /** Deletes the staging tree; cleanup failures never mask the real one. */
    private static void discard(Path staging, IOException primary) {
        try {
            deleteRecursively(staging);
        } catch (IOException cleanupFailure) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure);
            } else {
                LOG.log(Level.WARNING,
                        "Could not discard the staging directory " + staging, cleanupFailure);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            } catch (NoSuchFileException gone) {
                return; // deleted concurrently: already gone
            }
        }
        Files.deleteIfExists(path);
    }
}
