package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What can be said about a changed file from its path alone -- its kind
 * (production change, configuration, test, generated output), its directory
 * and name -- and the directory-and-kind clustering Review falls back to
 * when nothing better is known about how the files relate.
 *
 * <p>Everything here is inference from paths: drydock is guessing. {@link
 * Sections} uses the clustering when the change graph has no edges to group
 * by, and {@link ReadingPath} uses the kind to order entry points.</p>
 */
public final class ChangedPaths {

    private ChangedPaths() {
    }

    /**
     * What kind of change a path is. Only kinds a path can support are here:
     * "refactor" and "move" are claims about what a change does, and only
     * someone who has read the diff can make them.
     */
    public enum Kind {
        CHANGE, CONFIG, TESTS, GENERATED
    }

    /**
     * Files sharing a kind and a directory, in diff order. Kind is part of
     * the key so a test and the source it covers never share a cluster even
     * when they sit in the same directory -- "the change" and "the tests for
     * the change" are the two things a reviewer most wants to look at
     * separately.
     */
    public record Cluster(Kind kind, String directory, List<UnifiedDiff.FileDiff> files) {

        public Cluster {
            files = List.copyOf(files);
        }

        /** Every hunk id in the cluster, file by file in diff order. */
        public List<String> hunkIds() {
            List<String> ids = new ArrayList<>();
            for (UnifiedDiff.FileDiff file : files) {
                for (int hunk = 0; hunk < file.hunks().size(); hunk++) {
                    ids.add(HunkIds.hunkId(file.path(), hunk));
                }
            }
            return ids;
        }

        /**
         * The paths in the cluster that have at least one hunk, in diff
         * order -- the files {@link #hunkIds()} addresses. A binary or
         * mode-only change has no hunk to read or settle.
         */
        public List<String> pathsWithHunks() {
            return files.stream()
                    .filter(file -> !file.hunks().isEmpty())
                    .map(UnifiedDiff.FileDiff::path)
                    .toList();
        }

        /**
         * What the cluster reads as. A single file is named outright -- that
         * is the most specific true thing to say -- and a cluster is named by
         * its directory with a count, so two clusters can never read the same.
         */
        public String title() {
            if (files.size() == 1) {
                return fileName(files.get(0).path());
            }
            String where = directory.isEmpty() ? "repository root" : shortDirectory();
            return where + " · " + files.size() + " files";
        }

        /**
         * The last two segments of the directory. A full path clips to an
         * identical prefix in a narrow column; the tail is what actually
         * distinguishes one package from another.
         */
        private String shortDirectory() {
            String[] segments = directory.split("/");
            if (segments.length <= 2) {
                return directory;
            }
            return segments[segments.length - 2] + "/" + segments[segments.length - 1];
        }
    }

    /**
     * {@code diff}'s files clustered by kind and directory, in reading order
     * (production change first, then by directory). Empty diff, empty list.
     */
    public static List<Cluster> clusters(UnifiedDiff diff) {
        Map<ClusterKey, List<UnifiedDiff.FileDiff>> groups = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            ClusterKey key = new ClusterKey(kindOf(file.path()), directoryOf(file.path()));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
        }
        List<Cluster> ordered = new ArrayList<>();
        groups.forEach((key, files) -> ordered.add(new Cluster(key.kind(), key.directory(), files)));
        // Reading order, not diff order: the production change is what the
        // human came to review, and the tests and lockfiles that came with it
        // are context. Diff order is alphabetical by path, which buries the
        // one interesting group under whatever sorts first.
        ordered.sort((left, right) -> {
            int byKind = Integer.compare(readingOrder(left.kind()), readingOrder(right.kind()));
            return byKind != 0 ? byKind : left.directory().compareTo(right.directory());
        });
        return List.copyOf(ordered);
    }

    /** The fallback clustering as sections: one per cluster, no hub, no cycle. */
    public static List<Sections.Section> fallbackSections(UnifiedDiff diff) {
        List<Sections.Section> sections = new ArrayList<>();
        for (Cluster cluster : clusters(diff)) {
            sections.add(new Sections.Section(cluster.title(), cluster.pathsWithHunks(), cluster.hunkIds(),
                    Optional.empty(), List.of()));
        }
        return List.copyOf(sections);
    }

    private record ClusterKey(Kind kind, String directory) {
    }

    /**
     * Where a kind sits in reading order. Declared rather than taken from the
     * enum's own ordinal, which would silently reorder everything the next
     * time a kind is added.
     */
    static int readingOrder(Kind kind) {
        return switch (kind) {
            case CHANGE -> 0;
            case CONFIG -> 3;
            case TESTS -> 4;
            case GENERATED -> 5;
        };
    }

    /** The file's parent directory, or {@code ""} for a file at the repository root. */
    static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * What kind of change a path is, from the path alone.
     *
     * <p>Ordered most-specific first: a {@code package-lock.json} is
     * generated before it is configuration, and a test resource is a test
     * before it is a resource. The ordering is the whole logic here -- each
     * predicate on its own is trivial and every one of them overlaps with
     * the next.</p>
     */
    static Kind kindOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = fileName(lower);
        if (isGenerated(lower, name)) {
            return Kind.GENERATED;
        }
        if (isTest(lower, name)) {
            return Kind.TESTS;
        }
        if (isConfig(lower, name)) {
            return Kind.CONFIG;
        }
        return Kind.CHANGE;
    }

    private static boolean isGenerated(String lower, String name) {
        return lower.contains("/generated/")
                || lower.startsWith("generated/")
                || lower.contains("/node_modules/")
                || lower.contains("/vendor/")
                || lower.contains("/third_party/")
                || name.endsWith(".lock")
                || name.equals("package-lock.json")
                || name.equals("yarn.lock")
                || name.equals("cargo.lock")
                || name.equals("go.sum")
                || name.endsWith(".min.js")
                || name.endsWith(".min.css")
                || name.endsWith(".pb.go")
                || name.endsWith("_pb2.py")
                || name.endsWith(".g.dart");
    }

    /**
     * Whether {@code path} is a test path, by the same rules {@link #kindOf}
     * applies. Exposed for {@link ReadingPath}'s entry-point rank, which
     * needs the question without the kind: a vendored test is {@link
     * Kind#GENERATED} and still a test. A second copy of this vocabulary
     * drifted the last time one existed, which is the reason {@link
     * SymbolWords} is a class at all.
     */
    static boolean isTestPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return isTest(lower, fileName(lower));
    }

    private static boolean isTest(String lower, String name) {
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.startsWith("test/")
                || lower.startsWith("tests/")
                || lower.contains("/__tests__/")
                || lower.contains("/spec/")
                || name.endsWith("test.java")
                || name.endsWith("tests.java")
                || name.endsWith("test.kt")
                || name.endsWith("_test.go")
                || name.endsWith("_test.py")
                || name.startsWith("test_")
                || name.contains(".test.")
                || name.contains(".spec.");
    }

    private static boolean isConfig(String lower, String name) {
        return name.equals("dockerfile")
                || name.startsWith("dockerfile.")
                || name.equals("makefile")
                || name.startsWith(".git")
                || name.startsWith(".env")
                || lower.contains("/.github/")
                || endsWithAny(name, ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf",
                        ".properties", ".json", ".xml", ".gradle", ".gradle.kts", ".tf", ".tfvars");
    }

    private static boolean endsWithAny(String name, String... suffixes) {
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
