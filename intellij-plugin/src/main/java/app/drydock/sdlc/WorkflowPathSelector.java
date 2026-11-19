package app.drydock.sdlc;

import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowSelectionPlan.DirectoryEntry;
import app.drydock.sdlc.WorkflowSelectionPlan.Entry;
import app.drydock.sdlc.WorkflowSelectionPlan.FileEntry;
import app.drydock.sdlc.WorkflowSelectionPlan.LinkEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure path logic of portable containment: lexical rejection and manual
 * no-follow enumeration under count/depth/path-length ceilings. Symbolic links
 * are resolved by {@code readSymbolicLink} (chains up to {@link WorkflowLimits#TRAVERSAL_DEPTH},
 * cycles rejected) and kept only as contained, relative {@link LinkEntry}s whose destination is
 * itself selected. It reads no file bytes. Containment is static (a trusted registered checkout);
 * no hostile concurrent-writer immunity is provided or claimed.
 */
public final class WorkflowPathSelector {

    /** A selected repository-relative source: a directory (skill) or a single file. */
    public record Root(String path, boolean directory) {
    }

    /** Static rejection; the message never contains absolute host paths. */
    public static final class SelectionException extends Exception {
        private final ProblemCategory category;

        SelectionException(ProblemCategory category, String message) {
            super(message);
            this.category = category;
        }

        public ProblemCategory category() {
            return category;
        }
    }

    private final Path realRoot;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> mapping = new TreeMap<>();
    /** Top-level links kept in the plan: canonical link path to canonical destination. */
    private final Map<String, String> linkDestinations = new LinkedHashMap<>();

    private WorkflowPathSelector(Path realRoot) {
        this.realRoot = realRoot;
    }

    public static WorkflowSelectionPlan select(Path registeredRoot, List<Root> roots) throws SelectionException {
        Path real;
        try {
            real = registeredRoot.toRealPath();
        } catch (IOException e) {
            throw fail(ProblemCategory.SOURCE_UNREADABLE, "registered root is not readable");
        }
        WorkflowPathSelector s = new WorkflowPathSelector(real);
        for (Root r : roots) {
            s.selectRoot(r);
        }
        for (Map.Entry<String, String> l : s.linkDestinations.entrySet()) {
            if (!s.entries.containsKey(l.getValue())) {
                throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "link destination is not selected: " + safe(l.getKey()));
            }
        }
        return new WorkflowSelectionPlan(new ArrayList<>(s.entries.values()), s.mapping);
    }

    private static SelectionException fail(ProblemCategory c, String m) {
        return new SelectionException(c, m);
    }

    // ---- lexical ----

    private static List<String> parse(String path) throws SelectionException {
        if (path == null || path.isEmpty()) {
            throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "empty path");
        }
        if (path.startsWith("/") || path.startsWith("~") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.indexOf('$') >= 0 || path.indexOf('`') >= 0 || path.indexOf('%') >= 0) {
            throw fail(ProblemCategory.ESCAPES_ROOT, "absolute, URL or expansion path rejected");
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "control character in path");
            }
        }
        return normalize(List.of(), path);
    }

    /** Applies {@code text} to {@code base} lexically; a leading escape is rejected. */
    private static List<String> normalize(List<String> base, String text) throws SelectionException {
        List<String> out = new ArrayList<>(base);
        for (String seg : text.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (out.isEmpty()) {
                    throw fail(ProblemCategory.ESCAPES_ROOT, "path escapes the repository: " + safe(text));
                }
                out.remove(out.size() - 1);
            } else {
                out.add(seg);
            }
        }
        return out;
    }

    private static String safe(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s.replaceAll("\\p{Cntrl}", "?");
    }

    private static String join(List<String> segs) {
        return String.join("/", segs);
    }

    private static List<String> child(List<String> parent, String name) {
        List<String> l = new ArrayList<>(parent);
        l.add(name);
        return l;
    }

    // ---- selection ----

    private void selectRoot(Root r) throws SelectionException {
        List<String> logical = parse(r.path());
        if (logical.isEmpty()) {
            throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "repository root cannot be selected");
        }
        for (String c : logical) {
            if (c.equals(".git")) {
                throw fail(ProblemCategory.SPECIAL_FILE, ".git administrative entry");
            }
        }
        List<String> canon = walk(List.of(), join(logical), new ArrayList<>());
        BasicFileAttributes a = attrs(canon);
        mapping.put(join(logical), join(canon));
        if (r.directory() && a.isDirectory()) {
            enumerate(canon, 0);
        } else if (!r.directory() && a.isRegularFile()) {
            put(file(canon));
        } else if (!a.isDirectory() && !a.isRegularFile()) {
            throw fail(ProblemCategory.SPECIAL_FILE, "special file selected: " + safe(r.path()));
        } else {
            throw fail(ProblemCategory.SOURCE_UNREADABLE, "wrong entry type: " + safe(r.path()));
        }
    }

    /**
     * Resolves {@code text} against the canonical directory {@code base} component by component, expanding
     * every symbolic link met (chains share {@code stack}); returns a canonical link-free root-relative path.
     */
    private List<String> walk(List<String> base, String text, List<String> stack) throws SelectionException {
        List<String> cur = new ArrayList<>(base);
        String rel = text;
        if (text.startsWith("/")) {
            Path abs = Path.of(text).normalize();
            if (!abs.startsWith(realRoot)) {
                throw fail(ProblemCategory.ESCAPES_ROOT, "absolute link outside the repository: " + safe(join(base)));
            }
            cur.clear();
            rel = realRoot.relativize(abs).toString().replace('\\', '/');
        }
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (cur.isEmpty()) {
                    throw fail(ProblemCategory.ESCAPES_ROOT, "link escapes the repository: " + safe(text));
                }
                cur.remove(cur.size() - 1);
                continue;
            }
            if (seg.equals(".git")) {
                throw fail(ProblemCategory.SPECIAL_FILE, ".git administrative entry");
            }
            cur.add(seg);
            if (attrs(cur).isSymbolicLink()) {
                cur = followLink(cur, stack);
            }
        }
        return cur;
    }

    /** Resolves the link at canonical {@code link} to its canonical destination; top-level links are recorded. */
    private List<String> followLink(List<String> link, List<String> stack) throws SelectionException {
        String key = join(link);
        if (stack.contains(key)) {
            throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "cyclic symbolic link: " + safe(key));
        }
        if (stack.size() >= WorkflowLimits.TRAVERSAL_DEPTH) {
            throw fail(ProblemCategory.LIMIT_EXCEEDED, "link chain deeper than " + WorkflowLimits.TRAVERSAL_DEPTH);
        }
        String text;
        try {
            text = Files.readSymbolicLink(abs(link)).toString();
        } catch (IOException e) {
            throw fail(ProblemCategory.SOURCE_UNREADABLE, "link unreadable: " + safe(key));
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "control character in link text");
            }
        }
        boolean top = stack.isEmpty();
        stack.add(key);
        List<String> dest = walk(link.subList(0, link.size() - 1), text, stack);
        stack.remove(stack.size() - 1);
        if (dest.isEmpty()) {
            throw fail(ProblemCategory.UNSUPPORTED_SOURCE, "link to the repository root: " + safe(key));
        }
        if (top) {
            put(new LinkEntry(key, relative(link.subList(0, link.size() - 1), dest)));
            linkDestinations.put(key, join(dest));
        }
        return dest;
    }

    private static String relative(List<String> parent, List<String> dest) {
        int k = 0;
        while (k < parent.size() && k < dest.size() && parent.get(k).equals(dest.get(k))) {
            k++;
        }
        List<String> out = new ArrayList<>();
        for (int i = k; i < parent.size(); i++) {
            out.add("..");
        }
        out.addAll(dest.subList(k, dest.size()));
        return out.isEmpty() ? "." : join(out);
    }

    private void enumerate(List<String> dir, int depth) throws SelectionException {
        put(new DirectoryEntry(join(dir)));
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(abs(dir))) {
            for (Path p : ds) {
                names.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            throw fail(ProblemCategory.SOURCE_UNREADABLE, "directory unreadable: " + safe(join(dir)));
        }
        names.sort(null);
        for (String name : names) {
            if (name.equals(".git")) {
                throw fail(ProblemCategory.SPECIAL_FILE, ".git administrative entry");
            }
            List<String> c = child(dir, name);
            BasicFileAttributes a = attrs(c);
            if (a.isSymbolicLink()) {
                followLink(c, new ArrayList<>());
            } else if (a.isDirectory()) {
                if (depth + 1 > WorkflowLimits.TRAVERSAL_DEPTH) {
                    throw fail(ProblemCategory.LIMIT_EXCEEDED, "tree deeper than " + WorkflowLimits.TRAVERSAL_DEPTH);
                }
                enumerate(c, depth + 1);
            } else if (a.isRegularFile()) {
                put(file(c));
            } else {
                throw fail(ProblemCategory.SPECIAL_FILE, "special file: " + safe(join(c)));
            }
        }
    }

    private FileEntry file(List<String> path) {
        return new FileEntry(join(path), Files.isExecutable(abs(path)));
    }

    private void put(Entry e) throws SelectionException {
        String[] segs = e.path().split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segs.length - 1; i++) {
            prefix.append(i == 0 ? "" : "/").append(segs[i]);
            putOne(new DirectoryEntry(prefix.toString()));
        }
        putOne(e);
    }

    static void checkPathLength(String path) throws SelectionException {
        if (path.getBytes(StandardCharsets.UTF_8).length > WorkflowLimits.PATH_UTF8_BYTES) {
            throw fail(ProblemCategory.LIMIT_EXCEEDED, "path longer than " + WorkflowLimits.PATH_UTF8_BYTES + " bytes");
        }
    }

    private void putOne(Entry e) throws SelectionException {
        checkPathLength(e.path());
        Entry old = entries.get(e.path());
        if (old != null) {
            if (!old.equals(e)) {
                throw fail(ProblemCategory.SOURCE_UNREADABLE, "conflicting entries: " + safe(e.path()));
            }
            return;
        }
        if (entries.size() >= WorkflowLimits.BUNDLE_ENTRIES) {
            throw fail(ProblemCategory.LIMIT_EXCEEDED, "more than " + WorkflowLimits.BUNDLE_ENTRIES + " entries");
        }
        entries.put(e.path(), e);
    }

    private Path abs(List<String> segs) {
        Path p = realRoot;
        for (String s : segs) {
            p = p.resolve(s);
        }
        return p;
    }

    private BasicFileAttributes attrs(List<String> segs) throws SelectionException {
        try {
            return Files.readAttributes(abs(segs), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw fail(ProblemCategory.SOURCE_UNREADABLE, "missing, broken or unreadable: " + safe(join(segs)));
        }
    }
}
