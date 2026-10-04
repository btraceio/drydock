package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.HunkDigest;
import app.drydock.review.RecheckDispatch;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.VerdictMerge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What the hunk diff says about a file and about the whole review, derived
 * from the per-hunk verdicts in the store (spec §9.1).
 *
 * <p>A verdict is keyed by a hunk's content digest, so nothing about a file
 * is stored: its decision, whether the base has moved under it and how much
 * of the review is settled are all worked out from the store on every
 * render. That derivation is this class, kept outside {@link
 * SessionReviewView}: its only inputs are a {@link SessionReviewView.Host}
 * and a {@link Board}, none of them scene graph, so it can be tested without
 * a {@code Stage}.</p>
 *
 * <p>Not thread-safe, and not required to be: it is called from the board's
 * render, which is the FX thread. Nothing here does I/O -- the two questions
 * that need git ({@link SessionReviewView.Host#currentBase} and {@link
 * SessionReviewView.Host#baseMove}) are answered from the host's own cache.
 * The one exception is {@link #requestRechecks}, which types a prompt into a
 * terminal; it is bounded to once per base move.</p>
 */
final class SectionStates {

    /**
     * Whether a base move since a verdict could have changed what was
     * approved.
     *
     * <p>Three states, not two. "The base moved under this" and "we cannot
     * say yet" are different claims, and while the delta is still being
     * computed off the FX thread only the second one is true -- warning then
     * would put a confirm-me banner on a review nobody has touched.</p>
     */
    enum Staleness {
        /** The base has not moved, or the move provably could not touch this file. */
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
     * What the board is showing right now: which scope, which diff, and the
     * scope's {@link ChangeGraph} when one has finished building.
     *
     * <p>Passed to every method rather than held as mutable state, so a
     * caller cannot derive one file against the scope now selected and the
     * next against the one before it.</p>
     *
     * <p>{@code graph} is empty both before one has been requested and while
     * it is still building off the FX thread -- staleness widening falls back
     * to a file's own path rather than ever triggering a build itself.</p>
     */
    record Board(ReviewScope scope, UnifiedDiff diff, Optional<ChangeGraph> graph) {
        Board {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(diff, "diff");
            Objects.requireNonNull(graph, "graph");
        }

        /** Convenience for callers with no graph on hand -- most tests. */
        Board(ReviewScope scope, UnifiedDiff diff) {
            this(scope, diff, Optional.empty());
        }
    }

    private final SessionReviewView.Host host;

    /**
     * One diff's hunk digests, memoized per file. Every render asks for them
     * several times over (progress, the current file's decision and
     * staleness, the rechecks), and each answer hashes hunks -- on a large
     * diff that is thousands of SHA-256s per keystroke, on the FX thread.
     * Emptied whenever the diff INSTANCE changes (identity, not equality),
     * since re-scoping and reloading both hand over a new one.
     */
    private UnifiedDiff digestedDiff;
    private final Map<String, List<String>> digestsByFile = new HashMap<>();

    SectionStates(SessionReviewView.Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * The files the hunk diff walks, in diff order: every file with at least
     * one hunk. A mode-only or binary change has nothing to settle, so it is
     * no stop for {@code [}/{@code ]} and no condition of Submit.
     */
    List<String> filesWithHunks(Board board) {
        return board.diff().files().stream()
                .filter(file -> !file.hunks().isEmpty())
                .map(UnifiedDiff.FileDiff::path)
                .toList();
    }

    /**
     * Every hunk digest of the diff, once each, in diff order -- what
     * progress is measured in. Distinct, because two hunks with identical
     * content in one file share a digest and so share a verdict.
     */
    List<String> distinctDigests(Board board) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String file : filesWithHunks(board)) {
            distinct.addAll(digestsOfFile(board, file));
        }
        return List.copyOf(distinct);
    }

    /**
     * How many of {@link #distinctDigests} carry a verdict that is not
     * stale (spec §9.2). A stale verdict does not count toward "everything
     * settled" -- {@link SessionReviewView#submitReview} refuses one, so a
     * progress line that counted it would read "all settled -- ⏎ submits"
     * over a review Submit is about to refuse.
     */
    int settledHunkCount(Board board) {
        String base = host.currentBase(board.scope());
        Set<String> settled = new LinkedHashSet<>();
        for (String file : filesWithHunks(board)) {
            Collection<String> affecting = filesAffecting(board, file);
            for (String digest : digestsOfFile(board, file)) {
                Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
                if (verdict.isPresent()
                        && stalenessOf(board, verdict.get(), base, affecting) != Staleness.MOVED) {
                    settled.add(digest);
                }
            }
        }
        return settled.size();
    }

    /**
     * What {@code file}'s hunks merge to (spec §9.1) -- {@link
     * VerdictMerge}'s rule over the verdicts of every hunk it has. Empty
     * while any of them is unread, unless one already requests changes.
     */
    Optional<ReviewVerdict.Decision> decisionOf(Board board, String file) {
        return VerdictMerge.derive(digestsOfFile(board, file).stream()
                .map(digest -> host.verdict(board.scope(), digest))
                .toList());
    }

    /**
     * Whether a base move since any of {@code file}'s verdicts could have
     * changed what was decided. MOVED outranks UNKNOWN outranks FRESH: one
     * hunk known to have moved is the strongest thing true of the file.
     */
    Staleness stalenessOf(Board board, String file) {
        String base = host.currentBase(board.scope());
        Collection<String> affecting = filesAffecting(board, file);
        Staleness staleness = Staleness.FRESH;
        for (String digest : digestsOfFile(board, file)) {
            Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
            if (verdict.isEmpty()) {
                continue;
            }
            Staleness hunk = stalenessOf(board, verdict.get(), base, affecting);
            if (hunk == Staleness.MOVED) {
                return Staleness.MOVED;
            }
            if (hunk == Staleness.UNKNOWN) {
                staleness = Staleness.UNKNOWN;
            }
        }
        return staleness;
    }

    /**
     * Whether {@code file}'s staleness is the agent's claim rather than
     * Drydock's measurement (spec §9.7: "Assessments render as claimed, not
     * measured"): true when any of its {@code MOVED} verdicts carries an
     * agent's "affected" recheck for that base pair. A move the file filter
     * found on its own is measured, so a file with no such assessment reads
     * as measured even when it is stale.
     */
    boolean stalenessClaimed(Board board, String file) {
        String base = host.currentBase(board.scope());
        Collection<String> affecting = filesAffecting(board, file);
        for (String digest : digestsOfFile(board, file)) {
            Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
            if (verdict.isPresent()
                    && stalenessOf(board, verdict.get(), base, affecting) == Staleness.MOVED
                    && host.assessedAffected(board.scope(), digest, verdict.get().baseCommit(), base)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks the agent which approvals a base move disturbed, at most once per
     * move (spec §9.7).
     *
     * <p>Driven from the render pass rather than from the moment the move is
     * detected, because that is where staleness is already known: only a
     * verdict whose staleness is {@code MOVED} is one the move survived
     * {@link BaseMove#couldMatter}'s file filter for, so a move touching
     * nothing this scope reads spends no subagent. Run in both modes: the
     * tour's step decisions are recorded as the same per-hunk verdicts.</p>
     *
     * <p>The render pass runs many times per move, so the guard cannot be the
     * annotation store: {@code AnnotationStore#assessedAffected} reads the
     * same for "assessed unaffected" and for "never asked", and therefore
     * cannot see a dispatch still in flight. {@link RecheckDispatch} is that
     * memory. A hand-off that returned false is released again, since it
     * reached no terminal and no human is present to notice.</p>
     */
    void requestRechecks(Board board, RecheckDispatch dispatch) {
        if (!host.supportsAutomaticRecheck(board.scope())) {
            // Spec §9.7: inline harnesses do not get one. Checked before
            // anything is claimed, so nothing accumulates for a scope that
            // can never be asked.
            return;
        }
        String base = host.currentBase(board.scope());
        if (SessionReviewView.UNRESOLVED_BASE.equals(base)) {
            // Not a revision, so there is no base PAIR to ask about. The
            // reader already sees these as stale-until-confirmed.
            return;
        }
        Set<String> recordedBases = new LinkedHashSet<>();
        for (String file : filesWithHunks(board)) {
            Collection<String> affecting = filesAffecting(board, file);
            for (String digest : digestsOfFile(board, file)) {
                host.verdict(board.scope(), digest)
                        // "unresolved" is not a revision on either side of the
                        // pair. The current base is refused above; a RECORDED
                        // one carries the same sentinel whenever the baseline
                        // was unresolved when the human settled the hunk.
                        // Checked BEFORE stalenessOf, which would otherwise
                        // hand the sentinel to the host as a base to diff.
                        .filter(verdict -> !SessionReviewView.UNRESOLVED_BASE
                                .equals(verdict.baseCommit()))
                        // The instruction says "for each APPROVED hunk", and a
                        // requested-changes verdict is not one. Written as "not
                        // CHANGES" rather than as a list of the approving
                        // decisions on purpose: nothing ENFORCES which values
                        // can be stored, so an enumeration would silently drop
                        // an approval the day a new writer produces one. This
                        // direction fails toward asking.
                        .filter(verdict -> verdict.decision() != ReviewVerdict.Decision.CHANGES)
                        // THE relevance test, per verdict. Each approval
                        // carries its OWN recorded base, and the host answers
                        // baseMove per base: one hunk's move can be MOVED
                        // while its neighbour's is still UNKNOWN (the git for
                        // that pair is in flight) or FRESH (that pair provably
                        // touched nothing this scope reads). A move that is
                        // PERMANENTLY unresolvable stays UNKNOWN and is never
                        // asked about: BaseMove.Delta cannot tell "in flight"
                        // from "gone".
                        .filter(verdict -> stalenessOf(board, verdict, base, affecting)
                                == Staleness.MOVED)
                        .ifPresent(verdict -> recordedBases.add(verdict.baseCommit()));
            }
        }
        for (String from : recordedBases) {
            if (host.assessedMove(board.scope(), from, base)) {
                // SOME assessment for this pair is already on disk. The
                // in-memory claim dies with the view, so without this a
                // restart re-asks forever, and the human still sees the
                // per-hunk stale mark either way.
                continue;
            }
            // A released claim is retried on the NEXT render, and every one
            // after it, until the hand-off lands: a recheck silently
            // abandoned is the failure this whole path exists to avoid.
            if (dispatch.claim(board.scope().id(), from, base)
                    && !host.dispatchRecheck(board.scope(), from, base)) {
                dispatch.release(board.scope().id(), from, base);
            }
        }
    }

    /**
     * Whether one verdict's base has moved under it, and whether that can be
     * told at all. An unresolvable delta is {@link Staleness#UNKNOWN}, never
     * {@code MOVED}: {@link BaseMove#couldMatter} answers true for it because
     * it is the safe direction for a DECISION, but it is not evidence of a
     * move and must not be rendered as one.
     *
     * <p>An agent's recheck ({@link SessionReviewView.Host#assessedAffected},
     * spec §9.7) is asked SECOND, after the base is known to have moved and
     * before the file-level filter gets to dismiss the move. That order is
     * the asymmetry: the agent can only turn what the filter would have
     * called {@code FRESH} -- or what it cannot resolve at all -- into {@code
     * MOVED}, never the reverse.</p>
     */
    private Staleness stalenessOf(Board board, ReviewVerdict verdict, String base,
                                  Collection<String> files) {
        if (!verdict.staleAgainst(base)) {
            return Staleness.FRESH;
        }
        if (host.assessedAffected(board.scope(), verdict.hunkDigest(), verdict.baseCommit(), base)) {
            return Staleness.MOVED;
        }
        BaseMove.Delta delta = host.baseMove(board.scope(), verdict.baseCommit());
        if (delta.unresolvable()) {
            return Staleness.UNKNOWN;
        }
        return BaseMove.couldMatter(delta, files) ? Staleness.MOVED : Staleness.FRESH;
    }

    /**
     * The files a base move has to touch before it can matter to {@code
     * file}'s verdicts: the file itself, plus -- when the scope's {@link
     * ChangeGraph} is already in hand -- the files declaring symbols it
     * references (spec §9.2's second half). Widening is an improvement over
     * the file alone, never a reason to build a graph.
     */
    private static Collection<String> filesAffecting(Board board, String file) {
        Optional<ChangeGraph> graph = board.graph();
        if (graph.isEmpty()) {
            return List.of(file);
        }
        SortedSet<String> widened = new TreeSet<>(List.of(file));
        widened.addAll(graph.get().filesReferencedBy(file));
        return widened;
    }

    /**
     * The first of {@code file}'s hunks with no verdict yet: {@code a} has to
     * walk forward through what is still unread rather than re-settle hunk
     * one forever.
     */
    Optional<String> digestOfFirstUnsettledHunk(Board board, String file) {
        for (String digest : digestsOfFile(board, file)) {
            if (host.verdict(board.scope(), digest).isEmpty()) {
                return Optional.of(digest);
            }
        }
        return Optional.empty();
    }

    /**
     * The digest {@code a}/{@code r} act on (spec §9.6), in priority order:
     * the hunk under the diff column's gutter selection when one is open
     * ({@code selectionKey}, {@code "<file> <lineKey>"} -- see {@link
     * ReviewDiffColumn#currentLineSelection}); else {@code file}'s first
     * unsettled hunk; else its first hunk, so a fully settled file still has
     * something to act on.
     */
    Optional<String> digestOfCurrentHunk(Board board, String file, Optional<String> selectionKey) {
        return selectionKey.flatMap(key -> selectionFile(key)
                        .flatMap(selected -> selectionLineKey(key)
                                .flatMap(lineKey -> digestOfLine(board, selected, lineKey))))
                .or(() -> digestOfFirstUnsettledHunk(board, file))
                .or(() -> digestsOfFile(board, file).stream().findFirst());
    }

    /** The digest of the hunk containing {@code file}'s line {@code lineKey}, if any. */
    private Optional<String> digestOfLine(Board board, String file, String lineKey) {
        for (UnifiedDiff.FileDiff candidate : board.diff().files()) {
            if (!candidate.path().equals(file)) {
                continue;
            }
            for (UnifiedDiff.Hunk hunk : candidate.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    if (line.lineKey().equals(lineKey)) {
                        return Optional.of(HunkDigest.of(file, hunk));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The file {@code ⇧A}/{@code ⇧R} settle every hunk of (spec §9.6): the
     * file under the diff column's gutter selection when one is open, else
     * {@code file}, the file the cursor is on.
     */
    String currentFileOf(String file, Optional<String> selectionKey) {
        return selectionKey.flatMap(SectionStates::selectionFile).orElse(file);
    }

    private static Optional<String> selectionFile(String key) {
        int lastSpace = key.lastIndexOf(' ');
        return lastSpace < 0 ? Optional.empty() : Optional.of(key.substring(0, lastSpace));
    }

    private static Optional<String> selectionLineKey(String key) {
        int lastSpace = key.lastIndexOf(' ');
        return lastSpace < 0 ? Optional.empty() : Optional.of(key.substring(lastSpace + 1));
    }

    /** Every hunk digest of {@code file}, in diff order; none for a file not in the diff. */
    List<String> digestsOfFile(Board board, String file) {
        if (board.diff() != digestedDiff) {
            digestedDiff = board.diff();
            digestsByFile.clear();
        }
        return digestsByFile.computeIfAbsent(file, key -> hashFile(board.diff(), key));
    }

    private static List<String> hashFile(UnifiedDiff diff, String file) {
        for (UnifiedDiff.FileDiff candidate : diff.files()) {
            if (candidate.path().equals(file)) {
                List<String> digests = new ArrayList<>();
                for (UnifiedDiff.Hunk hunk : candidate.hunks()) {
                    digests.add(HunkDigest.of(file, hunk));
                }
                return List.copyOf(digests);
            }
        }
        return List.of();
    }

    /** The file of the hunk with {@code digest}, if the diff has one. */
    Optional<String> fileOfDigest(Board board, String digest) {
        for (String file : filesWithHunks(board)) {
            if (digestsOfFile(board, file).contains(digest)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /**
     * The digests {@code a}/{@code r} act on with the cursor on {@code file}
     * (spec §9.6): {@code wholeFile} is {@code ⇧A}/{@code ⇧R}, every hunk
     * of {@link #currentFileOf}; otherwise {@link #digestOfCurrentHunk}'s
     * one hunk.
     */
    List<String> digestsForAction(Board board, String file, boolean wholeFile,
                                  Optional<String> selectionKey) {
        if (wholeFile) {
            return digestsOfFile(board, currentFileOf(file, selectionKey));
        }
        return digestOfCurrentHunk(board, file, selectionKey).map(List::of).orElse(List.of());
    }

    /**
     * The recorded base of a stale verdict in {@code file}, for the verdict
     * bar's banner -- the first one found whose base no longer matches the
     * scope's current one. Callers only ask once {@link Staleness#MOVED} is
     * established, so one is guaranteed to exist; the current base is the
     * fallback only because a method that returns nothing here is worse than
     * one that occasionally repeats a base that did not move.
     */
    String oldBaseOf(Board board, String file) {
        String current = host.currentBase(board.scope());
        for (String digest : digestsOfFile(board, file)) {
            Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
            if (verdict.isPresent() && verdict.get().staleAgainst(current)) {
                return verdict.get().baseCommit();
            }
        }
        return current;
    }
}
