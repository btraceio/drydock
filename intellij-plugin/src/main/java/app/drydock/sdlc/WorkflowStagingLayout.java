package app.drydock.sdlc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes a {@link WorkflowBundle} into a caller-supplied private per-session directory as
 * {@code <name>/tree/...} (the preserved repository-relative tree) plus a separate generated
 * {@code <name>/instructions.md}. Everything is built in a hidden pending sibling, made read-only, and published
 * with one atomic rename; any failure removes the partial output. Only {@code java.nio.file} is used: no process,
 * shell, checkout or global harness state is touched, and nothing staged is ever executed.
 */
public final class WorkflowStagingLayout {

    public static final String TREE_DIR = "tree";
    public static final String INSTRUCTION_FILE = "instructions.md";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    /** Published locations; {@code root} is the read-only namespace to hand to a lease. */
    public record Published(Path root, Path tree, Path instructionFile) {
    }

    private WorkflowStagingLayout() {
    }

    public static Published publish(Path sessionDir, String name, WorkflowBundle bundle, String instructions)
            throws IOException {
        Objects.requireNonNull(sessionDir, "sessionDir");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(instructions, "instructions");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("unsafe staging name: " + name);
        }
        if (bundle.entries().size() > WorkflowLimits.BUNDLE_ENTRIES
                || bundle.totalBytes() > WorkflowLimits.AGGREGATE_FILE_BYTES) {
            throw new IOException("bundle exceeds staging limits");
        }
        if (Files.isSymbolicLink(sessionDir)) {
            throw new IOException("session directory must not be a symlink: " + sessionDir);
        }
        Files.createDirectories(sessionDir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        Path target = sessionDir.resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Path pending = Files.createTempDirectory(sessionDir, ".pending-");
        try {
            Path tree = Files.createDirectory(pending.resolve(TREE_DIR));
            List<Path> dirs = new ArrayList<>(List.of(tree));
            List<WorkflowBundle.Link> links = new ArrayList<>();
            for (WorkflowBundle.Entry e : bundle.entries()) {
                switch (e) {
                    case WorkflowBundle.Directory d -> ensureDir(tree, d.path(), dirs);
                    case WorkflowBundle.File f -> writeFile(tree, f, dirs);
                    case WorkflowBundle.Link l -> links.add(l);
                }
            }
            for (WorkflowBundle.Link l : links) {
                Path link = tree.resolve(l.path());
                ensureDir(tree, parentOf(l.path()), dirs);
                Files.createSymbolicLink(link, Path.of(l.target()));
            }
            Path instr = pending.resolve(INSTRUCTION_FILE);
            Files.write(instr, instructions.getBytes(StandardCharsets.UTF_8));
            Files.setPosixFilePermissions(instr, PosixFilePermissions.fromString("r--r--r--"));
            dirs.add(pending);
            dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path d : dirs) {
                Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("r-xr-xr-x"));
            }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic publication unsupported: " + target, e);
            }
        } catch (IOException | RuntimeException e) {
            try {
                remove(pending);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return new Published(target, target.resolve(TREE_DIR), target.resolve(INSTRUCTION_FILE));
    }

    /** Deletes a staged (possibly read-only) tree without following links; a missing path is a no-op. */
    public static void remove(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(root)) {
            all = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | RuntimeException first) {
            makeWritable(root);
            try (Stream<Path> walk = Files.walk(root)) {
                all = walk.sorted(Comparator.reverseOrder()).toList();
            }
        }
        for (Path p : all) {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(p, OWNER_ONLY);
            }
        }
        for (Path p : all) {
            Files.deleteIfExists(p);
        }
    }

    private static void makeWritable(Path root) throws IOException {
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(root, OWNER_ONLY);
            try (Stream<Path> children = Files.list(root)) {
                for (Path c : children.toList()) {
                    makeWritable(c);
                }
            }
        }
    }

    private static void writeFile(Path tree, WorkflowBundle.File f, List<Path> dirs) throws IOException {
        byte[] content = f.content();
        if (!WorkflowBundle.sha256Hex(content).equals(f.sha256())) {
            throw new IOException("content hash mismatch: " + f.path());
        }
        ensureDir(tree, parentOf(f.path()), dirs);
        Path out = tree.resolve(f.path());
        Files.write(out, content, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString(f.executable() ? "r-xr-xr-x" : "r--r--r--"));
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static void ensureDir(Path tree, String rel, List<Path> dirs) throws IOException {
        Path cur = tree;
        for (String seg : rel.isEmpty() ? new String[0] : rel.split("/")) {
            cur = cur.resolve(seg);
            if (Files.isSymbolicLink(cur)) {
                throw new IOException("directory entry collides with a link: " + rel);
            }
            if (!Files.isDirectory(cur)) {
                Files.createDirectory(cur);
                dirs.add(cur);
            }
        }
    }
}
