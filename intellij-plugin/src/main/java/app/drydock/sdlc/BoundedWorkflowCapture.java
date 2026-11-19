package app.drydock.sdlc;

import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowSelectionPlan.DirectoryEntry;
import app.drydock.sdlc.WorkflowSelectionPlan.Entry;
import app.drydock.sdlc.WorkflowSelectionPlan.FileEntry;
import app.drydock.sdlc.WorkflowSelectionPlan.LinkEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads the bytes of a {@link WorkflowSelectionPlan} with plain {@code java.nio.file}: bounded
 * channel reads counted while consuming, lexical path checks and duplicate-entry dedupe. Partial bytes
 * are dropped on refusal. A fingerprint of the root, its ancestors and every plan path is compared before and after the
 * reads, and each path is re-validated around its open; any difference refuses with a retryable
 * source-changed error. This detects controlled changes only: no hostile-writer immunity is claimed
 * and no SecureDirectoryStream is needed.
 */
public final class BoundedWorkflowCapture {

    /** Test seam invoked after a file's bytes were read, before the after-checks. */
    @FunctionalInterface
    public interface Probe {
        void afterRead(String path) throws IOException;
    }

    public static final class CaptureException extends Exception {
        private final ProblemCategory category;
        private final boolean sourceChanged;

        CaptureException(ProblemCategory category, boolean sourceChanged, String message) {
            super(message);
            this.category = category;
            this.sourceChanged = sourceChanged;
        }

        public ProblemCategory category() {
            return category;
        }

        /** True when the tree changed during capture; a later attempt may succeed. */
        public boolean sourceChanged() {
            return sourceChanged;
        }

        public boolean retryable() {
            return sourceChanged;
        }
    }

    private final Path root;

    private BoundedWorkflowCapture(Path root) {
        this.root = root;
    }

    public static WorkflowBundle capture(Path registeredRoot, WorkflowSelectionPlan plan) throws CaptureException {
        return capture(registeredRoot, plan, null);
    }

    public static WorkflowBundle capture(Path registeredRoot, WorkflowSelectionPlan plan, Probe probe) throws CaptureException {
        Path real;
        try {
            real = registeredRoot.toRealPath();
        } catch (IOException e) {
            throw unreadable("registered root is not readable");
        }
        return new BoundedWorkflowCapture(real).run(dedupe(plan.entries()), probe);
    }

    /** Reads one prompt file (selected relative path) under the 128 KiB prompt ceiling. */
    public static byte[] readPrompt(Path registeredRoot, String relativePath) throws CaptureException {
        try {
            BoundedWorkflowCapture c = new BoundedWorkflowCapture(registeredRoot.toRealPath());
            Path p = c.resolve(relativePath);
            byte[] bytes = c.read(p, WorkflowLimits.PROMPT_BYTES, new long[1], WorkflowLimits.PROMPT_BYTES);
            return bytes;
        } catch (IOException e) {
            throw unreadable("prompt source is not readable");
        }
    }

    private static List<Entry> dedupe(List<Entry> entries) throws CaptureException {
        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (Entry e : entries) {
            Entry old = byPath.putIfAbsent(e.path(), e);
            if (old != null && !old.equals(e)) {
                throw unreadable("conflicting entries: " + e.path());
            }
        }
        return new ArrayList<>(byPath.values());
    }

    private WorkflowBundle run(List<Entry> entries, Probe probe) throws CaptureException {
        List<WorkflowBundle.Entry> out = new ArrayList<>();
        long[] aggregate = new long[1];
        try {
            Map<String, String> before = fingerprint(entries);
            for (Entry e : entries) {
                Path p = resolve(e.path());
                checkParent(p);
                switch (e) {
                    case DirectoryEntry d -> out.add(new WorkflowBundle.Directory(d.path()));
                    case LinkEntry l -> out.add(new WorkflowBundle.Link(l.path(), l.target()));
                    case FileEntry f -> {
                        byte[] bytes = read(p, WorkflowLimits.FILE_BYTES, aggregate, WorkflowLimits.AGGREGATE_FILE_BYTES);
                        if (probe != null) {
                            probe.afterRead(f.path());
                        }
                        checkParent(p);
                        if (Files.isExecutable(p) != f.executable()) {
                            throw changed("executable bit changed: " + f.path());
                        }
                        out.add(new WorkflowBundle.File(f.path(), bytes, WorkflowBundle.sha256Hex(bytes), f.executable()));
                    }
                }
            }
            if (!before.equals(fingerprint(entries))) {
                throw changed("source tree changed during capture");
            }
            return WorkflowBundle.of(out);
        } catch (NoSuchFileException e) {
            throw changed("entry disappeared during capture");
        } catch (IOException | IllegalArgumentException e) {
            out.clear();
            throw unreadable("capture failed: " + e.getClass().getSimpleName());
        } catch (CaptureException e) {
            out.clear();
            throw e;
        }
    }

    // ---- validation ----

    /** Lexical check, then the absolute path under the root. */
    private Path resolve(String rel) throws CaptureException {
        Path p = root;
        for (String seg : rel.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.indexOf('\\') >= 0 || seg.indexOf('\0') >= 0) {
                throw new CaptureException(ProblemCategory.ESCAPES_ROOT, false, "entry path is not normalized: " + rel);
            }
            p = p.resolve(seg);
        }
        return p;
    }

    /** The parent must still canonicalize to its lexical location: no link swapped in on the way. */
    private void checkParent(Path p) throws IOException, CaptureException {
        Path parent = p.getParent();
        if (!parent.toRealPath().equals(parent)) {
            throw changed("parent directory changed: " + root.relativize(p));
        }
    }

    /** Identity/size/mtime/link-text per plan path, entry names for directories, key for root ancestors. */
    private Map<String, String> fingerprint(List<Entry> entries) throws IOException {
        Map<String, String> fp = new LinkedHashMap<>();
        for (Path a = root; a != null; a = a.getParent()) {
            fp.put("ancestor:" + a, String.valueOf(attrs(a).fileKey()));
        }
        fp.put("root", describe(root));
        for (Entry e : entries) {
            fp.put(e.path(), describe(root.resolve(e.path())));
        }
        return fp;
    }

    private static BasicFileAttributes attrs(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static String describe(Path p) throws IOException {
        BasicFileAttributes a = attrs(p);
        String kind = a.isSymbolicLink() ? "link:" + Files.readSymbolicLink(p) : a.isDirectory() ? "dir" : "file:" + a.size();
        String names = "";
        if (a.isDirectory()) {
            try (Stream<Path> s = Files.list(p)) {
                names = new TreeSet<>(s.map(x -> x.getFileName().toString()).toList()).toString();
            }
        }
        return a.fileKey() + "|" + kind + "|" + a.lastModifiedTime() + "|" + names;
    }

    // ---- bounded read ----

    private byte[] read(Path p, long fileLimit, long[] aggregate, long aggregateLimit) throws IOException, CaptureException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (SeekableByteChannel ch = open(p)) {
                ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            long n = 0;
            while (ch.read(buf) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CaptureException(ProblemCategory.CANCELLED, false, "capture cancelled");
                }
                n += buf.position();
                aggregate[0] += buf.position();
                if (n > fileLimit || aggregate[0] > aggregateLimit) {
                    throw new CaptureException(ProblemCategory.LIMIT_EXCEEDED, false,
                            n > fileLimit ? "file larger than " + fileLimit + " bytes" : "aggregate larger than " + aggregateLimit + " bytes");
                }
                sink.write(buf.array(), 0, buf.position());
                buf.clear();
            }
        } catch (NoSuchFileException e) {
            throw changed("entry disappeared: " + root.relativize(p));
        }
        return sink.toByteArray();
    }

    /** NOFOLLOW_LINKS where the provider supports it; lacking the option is not a rejection. */
    private static SeekableByteChannel open(Path p) throws IOException {
        try {
            return Files.newByteChannel(p, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return Files.newByteChannel(p, StandardOpenOption.READ);
        }
    }

    private static CaptureException changed(String message) {
        return new CaptureException(ProblemCategory.SOURCE_UNREADABLE, true, message);
    }

    private static CaptureException unreadable(String message) {
        return new CaptureException(ProblemCategory.SOURCE_UNREADABLE, false, message);
    }
}
