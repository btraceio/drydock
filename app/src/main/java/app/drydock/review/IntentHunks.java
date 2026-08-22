package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The hunks a section covers, as the digests its verdicts are keyed by
 * (spec §9.1).
 *
 * <p>Sections overlap: the same hunk may sit in two of them, and the same
 * digest may therefore be produced by both. That is the whole point -- a
 * verdict is keyed by the hunk, not by whichever section the human happened
 * to be looking at -- so the join from a section to its verdicts is
 * many-to-one and every reader of it has to walk the diff the same way. It
 * is walked here, once, rather than in the view, the workspace and the MCP
 * router separately.</p>
 */
public final class IntentHunks {

    private IntentHunks() {
    }

    /**
     * The content digests of the hunks {@code intent} covers in {@code diff},
     * in diff order, each listed once.
     *
     * <p>Membership is asked of {@link ReviewIntent#containsHunk}, so an
     * intent that names no hunks at all covers the whole diff -- the same
     * rule the diff column filters by. Two byte-identical hunks in one file
     * collapse to a single digest, which is not a loss: they are the same
     * code, and one verdict is what settles both.</p>
     */
    public static List<String> digestsOf(ReviewIntent intent, UnifiedDiff diff) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(diff, "diff");
        // Insertion-ordered: the rail, the bar and review_state all read this
        // list, and a set with no order would give them three different ones.
        Set<String> digests = new LinkedHashSet<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            List<UnifiedDiff.Hunk> hunks = file.hunks();
            for (int index = 0; index < hunks.size(); index++) {
                if (intent.containsHunk(file.path(), index)) {
                    digests.add(HunkDigest.of(file.path(), hunks.get(index)));
                }
            }
        }
        return List.copyOf(digests);
    }
}
