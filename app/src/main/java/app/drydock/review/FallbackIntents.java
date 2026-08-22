package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The grouping Review uses when no reviewer has proposed intents: files
 * clustered by directory and kind, each with an inferred kind tag and a risk
 * taken from how much of it changed.
 *
 * <p>The previous fallback was one intent per file, titled with the file's
 * full path. On this repository's own 45-file branch that produced 45 cards
 * whose titles all clipped to the same {@code app/src/main/java/app/dry…}
 * prefix, all tagged {@code change}, all carrying the same rationale and the
 * same flat heat bar. Every card was individually correct and the rail as a
 * whole was unreadable, which is the failure mode this class exists to
 * avoid: a grouping is only useful if its entries can be told apart.</p>
 *
 * <p>Everything here is inference from paths and line counts -- drydock is
 * guessing, and the rationale on each card says so. A reviewer's grouping
 * always wins (see {@link IntentGrouping}); this is what the surface falls
 * back to so that Review works with no agent at all.</p>
 */
public final class FallbackIntents {

    /** Above this many changed lines a group is HIGH risk; below {@link #MED_CHURN}, LOW. */
    private static final int HIGH_CHURN = 400;
    private static final int MED_CHURN = 100;

    private FallbackIntents() {
    }

    /** {@code diff}'s files, clustered into intents. Empty diff, empty list. */
    public static List<ReviewIntent> group(UnifiedDiff diff) {
        Map<GroupKey, Group> groups = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            GroupKey key = new GroupKey(kindOf(file.path()), directoryOf(file.path()));
            groups.computeIfAbsent(key, Group::new).add(file);
        }
        List<Group> ordered = new ArrayList<>(groups.values());
        // Reading order, not diff order: the production change is what the
        // human came to review, and the tests and lockfiles that came with it
        // are context. Diff order is alphabetical by path, which buries the
        // one interesting group under whatever sorts first.
        ordered.sort((left, right) -> {
            int byKind = Integer.compare(readingOrder(left.key.kind()), readingOrder(right.key.kind()));
            return byKind != 0 ? byKind : left.key.directory().compareTo(right.key.directory());
        });
        List<ReviewIntent> intents = new ArrayList<>();
        int number = 1;
        for (Group group : ordered) {
            intents.add(group.toIntent(number++));
        }
        return List.copyOf(intents);
    }

    /**
     * Where a kind sits in the rail. Declared rather than taken from the
     * enum's own ordinal, which is a wire-format concern and would silently
     * reorder the rail the next time a kind is added to it.
     */
    static int readingOrder(ReviewIntent.Kind kind) {
        return switch (kind) {
            case CHANGE -> 0;
            case REFACTOR -> 1;
            case MOVE -> 2;
            case CONFIG -> 3;
            case TESTS -> 4;
            case GENERATED -> 5;
        };
    }

    /**
     * Which file a group is keyed by. Kind is part of the key so a test and
     * the source it covers never share a card even when they sit in the same
     * directory -- "the change" and "the tests for the change" are the two
     * things a reviewer most wants to look at separately.
     */
    private record GroupKey(ReviewIntent.Kind kind, String directory) {
    }

    private static final class Group {

        private final GroupKey key;
        private final List<UnifiedDiff.FileDiff> files = new ArrayList<>();

        Group(GroupKey key) {
            this.key = key;
        }

        void add(UnifiedDiff.FileDiff file) {
            files.add(file);
        }

        ReviewIntent toIntent(int number) {
            List<String> hunkIds = new ArrayList<>();
            int churn = 0;
            for (UnifiedDiff.FileDiff file : files) {
                churn += file.insertions() + file.deletions();
                for (int hunk = 0; hunk < file.hunks().size(); hunk++) {
                    hunkIds.add(ReviewIntent.hunkId(file.path(), hunk));
                }
            }
            return new ReviewIntent(id(), number, title(), key.kind(), risk(churn),
                    rationale(churn), hunkIds, java.util.Optional.empty(), false);
        }

        private String id() {
            return "auto:" + key.kind().wireName() + ":" + key.directory();
        }

        /**
         * What the card reads. A single file is named outright -- that is the
         * most specific true thing to say -- and a cluster is named by its
         * directory with a count, so two cards can never read the same.
         */
        private String title() {
            if (files.size() == 1) {
                return fileName(files.get(0).path());
            }
            String directory = key.directory().isEmpty() ? "repository root" : shortDirectory();
            return directory + " · " + files.size() + " files";
        }

        /**
         * The last two segments of the directory. A full path clips to an
         * identical prefix on every card in the rail's width, which is the
         * defect this whole class is a response to; the tail is what actually
         * distinguishes one package from another.
         */
        private String shortDirectory() {
            String[] segments = key.directory().split("/");
            if (segments.length <= 2) {
                return key.directory();
            }
            return segments[segments.length - 2] + "/" + segments[segments.length - 1];
        }

        private ReviewIntent.Risk risk(int churn) {
            // Generated output and configuration are not read line by line,
            // so churn there says nothing about how much care the change
            // needs. Flagging a 5000-line lockfile HIGH would drown the one
            // group that genuinely is.
            if (key.kind() == ReviewIntent.Kind.GENERATED) {
                return ReviewIntent.Risk.NONE;
            }
            if (churn > HIGH_CHURN) {
                return ReviewIntent.Risk.HIGH;
            }
            return churn > MED_CHURN ? ReviewIntent.Risk.MED : ReviewIntent.Risk.LOW;
        }

        private String rationale(int churn) {
            int insertions = files.stream().mapToInt(UnifiedDiff.FileDiff::insertions).sum();
            int deletions = files.stream().mapToInt(UnifiedDiff.FileDiff::deletions).sum();
            String where = files.size() == 1
                    ? key.directory().isEmpty() ? "repository root" : key.directory()
                    : files.size() + " files";
            return where + "  ·  +" + insertions + " −" + deletions
                    + "  ·  grouped by drydock, no reviewer has run";
        }
    }

    // ---- path inference -----------------------------------------------------

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
     *
     * <p>{@link ReviewIntent.Kind#REFACTOR} and {@link ReviewIntent.Kind#MOVE}
     * are deliberately never inferred: both are claims about what the change
     * does, and a path cannot support that claim. Only a reviewer that has
     * read the diff can say so.</p>
     */
    static ReviewIntent.Kind kindOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = fileName(lower);
        if (isGenerated(lower, name)) {
            return ReviewIntent.Kind.GENERATED;
        }
        if (isTest(lower, name)) {
            return ReviewIntent.Kind.TESTS;
        }
        if (isConfig(lower, name)) {
            return ReviewIntent.Kind.CONFIG;
        }
        return ReviewIntent.Kind.CHANGE;
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
     * ReviewIntent.Kind#GENERATED} and still a test. A second copy of this
     * vocabulary drifted the last time one existed, which is the reason
     * {@link SymbolWords} is a class at all.
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
