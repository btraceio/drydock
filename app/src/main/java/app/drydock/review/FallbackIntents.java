package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * back to so that Review works with no agent at all. The clustering and the
 * path inference live in {@link ChangedPaths}; this class only turns its
 * clusters into intents.</p>
 */
public final class FallbackIntents {

    /** Above this many changed lines a group is HIGH risk; below {@link #MED_CHURN}, LOW. */
    private static final int HIGH_CHURN = 400;
    private static final int MED_CHURN = 100;

    private FallbackIntents() {
    }

    /** {@code diff}'s files, clustered into intents. Empty diff, empty list. */
    public static List<ReviewIntent> group(UnifiedDiff diff) {
        List<ReviewIntent> intents = new ArrayList<>();
        int number = 1;
        for (ChangedPaths.Cluster cluster : ChangedPaths.clusters(diff)) {
            intents.add(toIntent(cluster, number++));
        }
        return List.copyOf(intents);
    }

    private static ReviewIntent toIntent(ChangedPaths.Cluster cluster, int number) {
        int churn = 0;
        for (UnifiedDiff.FileDiff file : cluster.files()) {
            churn += file.insertions() + file.deletions();
        }
        ReviewIntent.Kind kind = kindOf(cluster.kind());
        return new ReviewIntent("auto:" + kind.wireName() + ":" + cluster.directory(), number,
                cluster.title(), kind, risk(kind, churn),
                // No reads: the fallback is what runs when no agent has,
                // so there is no declared dependency order to carry.
                rationale(cluster), cluster.hunkIds(), Optional.empty(), false, List.of());
    }

    private static ReviewIntent.Kind kindOf(ChangedPaths.Kind kind) {
        return switch (kind) {
            case CHANGE -> ReviewIntent.Kind.CHANGE;
            case CONFIG -> ReviewIntent.Kind.CONFIG;
            case TESTS -> ReviewIntent.Kind.TESTS;
            case GENERATED -> ReviewIntent.Kind.GENERATED;
        };
    }

    private static ReviewIntent.Risk risk(ReviewIntent.Kind kind, int churn) {
        // Generated output and configuration are not read line by line,
        // so churn there says nothing about how much care the change
        // needs. Flagging a 5000-line lockfile HIGH would drown the one
        // group that genuinely is.
        if (kind == ReviewIntent.Kind.GENERATED) {
            return ReviewIntent.Risk.NONE;
        }
        if (churn > HIGH_CHURN) {
            return ReviewIntent.Risk.HIGH;
        }
        return churn > MED_CHURN ? ReviewIntent.Risk.MED : ReviewIntent.Risk.LOW;
    }

    private static String rationale(ChangedPaths.Cluster cluster) {
        List<UnifiedDiff.FileDiff> files = cluster.files();
        int insertions = files.stream().mapToInt(UnifiedDiff.FileDiff::insertions).sum();
        int deletions = files.stream().mapToInt(UnifiedDiff.FileDiff::deletions).sum();
        String where = files.size() == 1
                ? cluster.directory().isEmpty() ? "repository root" : cluster.directory()
                : files.size() + " files";
        return where + "  ·  +" + insertions + " −" + deletions
                + "  ·  grouped by drydock, no reviewer has run";
    }
}
