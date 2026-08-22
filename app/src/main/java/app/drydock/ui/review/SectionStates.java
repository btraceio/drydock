package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.IntentHunks;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.VerdictMerge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a section of the review board says about itself, derived from the
 * hunks it covers (spec §9.1).
 *
 * <p>Sections overlap and a verdict is keyed by a hunk's content digest, so
 * nothing about a section is stored: its decision, its counts, whether the
 * base has moved under it and which of its neighbours settled a hunk it
 * shares are all worked out from the store on every render. That derivation
 * is this class, and it is deliberately outside {@link SessionReviewView}:
 * its only inputs are a {@link SessionReviewView.Host}, a {@link UnifiedDiff}
 * and a list of {@link ReviewIntent}, none of them scene graph, so it can be
 * tested without a {@code Stage} and read without the 1700 lines of view
 * around it.</p>
 *
 * <p>Not thread-safe, and not required to be: it is called from the board's
 * render, which is the FX thread. Nothing here blocks -- the two questions
 * that need git ({@link SessionReviewView.Host#currentBase} and {@link
 * SessionReviewView.Host#baseMove}) are answered from the host's own cache.</p>
 */
final class SectionStates {

    /**
     * Whether a base move since a verdict could have changed what was
     * approved.
     *
     * <p>Three states, not two. "The base moved under this" and "we cannot
     * say yet" are different claims, and while the delta is still being
     * computed off the FX thread only the second one is true -- warning then
     * would put a confirm-me banner on every settled card of a review nobody
     * has touched.</p>
     */
    enum Staleness {
        /** The base has not moved, or the move provably could not touch this section. */
        FRESH,
        /** The base moved and could have touched it: the reader has to confirm. */
        MOVED,
        /**
         * Cannot be told -- the delta is still in flight, or the old base can
         * no longer be diffed at all. Rendered as nothing, never as a
         * warning: an unanswered question is not a finding.
         */
        UNKNOWN
    }

    /**
     * One section's rendered state, derived from its hunks (spec §9.1).
     *
     * @param decision what its hunks merge to, empty while any is unread
     * @param settledHunks how many of its hunks carry a verdict
     * @param totalHunks how many hunks it covers at all
     * @param staleness whether a base move since a verdict could have changed
     *              what was approved
     * @param settledElsewhere the marks of the other sections sharing a
     *              settled hunk with this one, so a count that advanced
     *              without the reader touching this card is explained
     * @param hunksMissing whether this section names hunks and the diff has
     *              none of them -- a grouping that has drifted off the diff,
     *              which must not be mistaken for a section nobody has read
     */
    record SectionState(Optional<ReviewVerdict.Decision> decision, int settledHunks,
                        int totalHunks, Staleness staleness, List<String> settledElsewhere,
                        boolean hunksMissing) {

        SectionState {
            settledElsewhere = List.copyOf(settledElsewhere);
        }

        /**
         * A section nothing can be said about yet -- no diff, or no scope.
         * Distinct from a section with nothing settled: this one renders no
         * counts at all, because zero hunks reviewed and "not known yet" are
         * not the same claim.
         */
        static SectionState unknown() {
            return new SectionState(Optional.empty(), 0, 0, Staleness.UNKNOWN, List.of(), false);
        }

        /**
         * A section whose hunk ids name nothing in the current diff. Hunk ids
         * are positional ({@code h_<file>_<index>}), so a re-diff can strand
         * a grouping the agent supplied earlier; the card has to SAY so,
         * because a section with no settleable hunks can never be approved
         * and would otherwise refuse Submit forever with no visible reason.
         */
        static SectionState notInDiff() {
            return new SectionState(Optional.empty(), 0, 0, Staleness.FRESH, List.of(), true);
        }
    }

    /**
     * What the board is showing right now: which scope, which diff, and the
     * grouping over it.
     *
     * <p>Passed to every method rather than held as mutable state, so a
     * caller cannot derive one section against the scope now selected and its
     * neighbour against the one before it. The view assembles it once per
     * render from the same three things the rail is built from.</p>
     */
    record Board(ReviewScope scope, UnifiedDiff diff, List<ReviewIntent> sections) {
        Board {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(diff, "diff");
            sections = List.copyOf(sections);
        }
    }

    private final SessionReviewView.Host host;

    /**
     * One diff's hunk digests, memoized per intent. Every card of the rail
     * asks for its section's state on every rebuild, and each answer walks the
     * diff hashing hunks -- on a large diff that is thousands of SHA-256s per
     * keystroke, on the FX thread.
     *
     * <p>Keyed by the whole {@link ReviewIntent}, not by its id: a reviewer
     * may re-issue the same id over DIFFERENT hunks, and an id-keyed memo
     * would then answer with the hunks of a grouping that no longer exists.
     * Emptied whenever the diff INSTANCE changes (identity, not equality),
     * since re-scoping and reloading both hand over a new one.</p>
     */
    private UnifiedDiff digestedDiff;
    private final Map<ReviewIntent, List<String>> digestsByIntent = new LinkedHashMap<>();

    SectionStates(SessionReviewView.Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * The content digests of the hunks {@code intent} covers, memoized for
     * the diff they were taken from (see {@link #digestsByIntent}).
     */
    List<String> digestsOf(Board board, ReviewIntent intent) {
        UnifiedDiff diff = board.diff();
        if (diff != digestedDiff) {
            digestedDiff = diff;
            digestsByIntent.clear();
        }
        return digestsByIntent.computeIfAbsent(intent, key -> IntentHunks.digestsOf(key, diff));
    }

    /**
     * The sections progress is measured over and Submit demands a verdict on:
     * those that count toward progress AND still have a hunk in the diff.
     *
     * <p>Collapsed intents do not count toward progress: the point of the
     * collapse is that there is nothing to read. Neither does a section whose
     * hunk ids no longer resolve -- there is nothing to settle in it, so
     * counting it would make the review permanently incomplete.</p>
     */
    List<ReviewIntent> counted(Board board) {
        return board.sections().stream()
                .filter(ReviewIntent::countsTowardProgress)
                .filter(intent -> hasResolvableHunks(board, intent))
                .toList();
    }

    /**
     * Whether {@code intent} has any hunk in the current diff at all.
     *
     * <p>False for a section whose {@code hunkIds} name hunks the diff no
     * longer has -- ids are positional, so a re-diff strands them. Such a
     * section can never be settled (there is nothing to record a verdict
     * against), so it must not be counted toward progress or demanded by
     * Submit: doing so refuses Submit forever and jumps to the one card that
     * cannot be settled.</p>
     */
    boolean hasResolvableHunks(Board board, ReviewIntent intent) {
        return !digestsOf(board, intent).isEmpty();
    }

    /**
     * Every hunk the counted sections cover, once each -- what progress is
     * measured in. Sections overlap, so the sum of their sizes exceeds the
     * number of hunks and would let a shared hunk be settled twice
     * (spec §5.6).
     */
    List<String> distinctDigests(Board board) {
        Set<String> distinct = new LinkedHashSet<>();
        for (ReviewIntent intent : counted(board)) {
            distinct.addAll(digestsOf(board, intent));
        }
        return List.copyOf(distinct);
    }

    /** How many of {@link #distinctDigests} carry a verdict. */
    int settledHunkCount(Board board) {
        int settled = 0;
        for (String digest : distinctDigests(board)) {
            if (host.verdict(board.scope(), digest).isPresent()) {
                settled++;
            }
        }
        return settled;
    }

    /**
     * What a section's hunks merge to (spec §9.1) -- {@link VerdictMerge}'s
     * rule, over the verdicts of the hunks it covers.
     *
     * <p>Deliberately the light derivation, free of everything {@link
     * #stateOf} adds: it is what one section asks of ANOTHER, and asking
     * through the full state would recurse between two sections sharing a
     * hunk.</p>
     */
    Optional<ReviewVerdict.Decision> decisionOf(Board board, ReviewIntent intent) {
        return VerdictMerge.derive(digestsOf(board, intent).stream()
                .map(digest -> host.verdict(board.scope(), digest))
                .toList());
    }

    /** One section's rendered state, derived from its hunks (spec §9.1). */
    SectionState stateOf(Board board, ReviewIntent intent) {
        List<String> digests = digestsOf(board, intent);
        if (digests.isEmpty()) {
            // A section that names hunks none of which are in the diff is a
            // drifted grouping, not an unread section, and says so.
            return intent.hunkIds().isEmpty()
                    ? SectionState.unknown()
                    : SectionState.notInDiff();
        }
        String base = host.currentBase(board.scope());
        List<String> files = filesOf(board, intent);
        List<Optional<ReviewVerdict>> perHunk = new ArrayList<>();
        Set<String> elsewhere = new LinkedHashSet<>();
        Staleness staleness = Staleness.FRESH;
        int settled = 0;
        for (String digest : digests) {
            Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
            perHunk.add(verdict);
            if (verdict.isPresent()) {
                settled++;
                // MOVED outranks UNKNOWN outranks FRESH: one hunk known to
                // have moved is the strongest thing true of the section.
                Staleness hunk = stalenessOf(board, verdict.get(), base, files);
                if (hunk == Staleness.MOVED
                        || (hunk == Staleness.UNKNOWN && staleness == Staleness.FRESH)) {
                    staleness = hunk;
                }
                collectSharingSections(board, digest, intent, elsewhere);
            }
        }
        return new SectionState(VerdictMerge.derive(perHunk), settled, digests.size(),
                staleness, List.copyOf(elsewhere), false);
    }

    /**
     * Whether one verdict's base has moved under it, and whether that can be
     * told at all. An unresolvable delta is {@link Staleness#UNKNOWN}, never
     * {@code MOVED}: {@link BaseMove#couldMatter} answers true for it because
     * it is the safe direction for a DECISION, but it is not evidence of a
     * move and must not be rendered as one.
     */
    private Staleness stalenessOf(Board board, ReviewVerdict verdict, String base,
                                  List<String> files) {
        if (!verdict.staleAgainst(base)) {
            return Staleness.FRESH;
        }
        BaseMove.Delta delta = host.baseMove(board.scope(), verdict.baseCommit());
        if (delta.unresolvable()) {
            return Staleness.UNKNOWN;
        }
        return BaseMove.couldMatter(delta, files) ? Staleness.MOVED : Staleness.FRESH;
    }

    /**
     * The marks of the OTHER sections sharing {@code digest}, so a count that
     * advanced without the reader touching this card is explained.
     *
     * <p>Not conditioned on the sibling being fully settled. A sibling that
     * settled one shared hunk moves this card's count by exactly as much as a
     * fully settled one does, and leaving that case unmarked solves the
     * "state changing on its own" problem only for the easy half of it.</p>
     */
    private void collectSharingSections(Board board, String digest, ReviewIntent self,
                                        Set<String> into) {
        for (ReviewIntent other : board.sections()) {
            if (other.id().equals(self.id()) || !other.countsTowardProgress()) {
                continue;
            }
            if (digestsOf(board, other).contains(digest)) {
                into.add(sectionMark(other.number()));
            }
        }
    }

    /**
     * The files a section covers, for {@link BaseMove#couldMatter}. An intent
     * that names no hunks covers the whole diff (see {@link
     * ReviewIntent#containsHunk}), so its files are the diff's -- an empty
     * list there would read as "touches nothing" and quietly make every base
     * move irrelevant to it.
     */
    private static List<String> filesOf(Board board, ReviewIntent intent) {
        List<String> named = intent.files();
        if (!named.isEmpty()) {
            return named;
        }
        return board.diff().files().stream().map(UnifiedDiff.FileDiff::path).toList();
    }

    /** How a section is named in another section's card: its number, circled. */
    static String sectionMark(int number) {
        // U+2460 is (1); the run is twenty long, and beyond it a plain "#21"
        // is better than a glyph half the fonts on a machine do not carry.
        return number >= 1 && number <= 20
                ? String.valueOf((char) ('\u2460' + number - 1))
                : "#" + number;
    }
}
