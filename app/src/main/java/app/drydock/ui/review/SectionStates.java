package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.HunkDigest;
import app.drydock.review.IntentHunks;
import app.drydock.review.RecheckDispatch;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.VerdictMerge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

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
     * @param decision what its hunks merge to, empty while any is unread --
     *              includes a stale hunk's verdict; the decision is not
     *              what staleness puts in question
     * @param settledHunks how many of its hunks carry a verdict that is
     *              NOT stale (spec §9.2) -- a hunk whose base has moved in
     *              a way that could matter does not count here, the same
     *              rule {@link #settledHunkCount} applies globally, so a
     *              card's own "n/total" and the verdict bar's progress line
     *              cannot disagree about what is actually settled
     * @param recordedHunks how many of its hunks carry ANY verdict at all,
     *              stale or not (Task 18 follow-up, correction 6a). A section
     *              with one stale-approved hunk and one genuinely unread one
     *              has {@code settledHunks() == 0} -- correct for "is this
     *              settled" -- but a card that reads NOTHING at all still
     *              understates what happened: one hunk WAS recorded, it is
     *              only its freshness in question, and {@code ⚠ base moved}
     *              is the only thing on the card that says so. This is what
     *              the progress LABEL reads instead, so "1/2 hunks" survives
     *              a stale hunk the way the numeric decision does not have to.
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
                        int recordedHunks, int totalHunks, Staleness staleness,
                        List<String> settledElsewhere, boolean hunksMissing) {

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
            return new SectionState(Optional.empty(), 0, 0, 0, Staleness.UNKNOWN, List.of(), false);
        }

        /**
         * A section whose hunk ids name nothing in the current diff. Hunk ids
         * are positional ({@code h_<file>_<index>}), so a re-diff can strand
         * a grouping the agent supplied earlier; the card has to SAY so,
         * because a section with no settleable hunks can never be approved
         * and would otherwise refuse Submit forever with no visible reason.
         */
        static SectionState notInDiff() {
            return new SectionState(Optional.empty(), 0, 0, 0, Staleness.FRESH, List.of(), true);
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
     *
     * <p>{@code graph} is empty both before one has been requested and while
     * it is still building off the FX thread (see {@link
     * SessionReviewView.Host#intents}) -- staleness widening falls back to a
     * section's own files rather than ever triggering a build itself.</p>
     */
    record Board(ReviewScope scope, UnifiedDiff diff, List<ReviewIntent> sections,
                Optional<ChangeGraph> graph) {
        Board {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(diff, "diff");
            Objects.requireNonNull(graph, "graph");
            sections = List.copyOf(sections);
        }

        /** Convenience for callers with no graph on hand -- most tests. */
        Board(ReviewScope scope, UnifiedDiff diff, List<ReviewIntent> sections) {
            this(scope, diff, sections, Optional.empty());
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

    /**
     * How many of {@link #distinctDigests} carry a verdict that is not
     * stale (spec §9.2). A stale verdict does not count toward "everything
     * settled" -- {@link SessionReviewView#submitReview} refuses one, so a
     * progress line and nav hint that counted it would tell the reader the
     * opposite of what the key does: "all settled -- ⏎ submits" over a
     * section Submit is about to refuse.
     */
    int settledHunkCount(Board board) {
        Set<String> stale = staleDigests(board);
        int settled = 0;
        for (String digest : distinctDigests(board)) {
            if (host.verdict(board.scope(), digest).isPresent() && !stale.contains(digest)) {
                settled++;
            }
        }
        return settled;
    }

    /**
     * Every digest among the counted sections' hunks whose verdict is stale
     * in a way that could matter (spec §9.2) -- what {@link
     * #settledHunkCount} excludes. Computed directly over each counted
     * section's own hunks and file list, the same inputs {@link #stateOf}
     * already uses per section, rather than re-deriving a section from a
     * bare digest: a hunk shared by two sections is asked once per section
     * here, but a digest already marked stale by one is not re-checked by
     * the other, since {@code MOVED} could only be found the same way
     * twice.
     */
    private Set<String> staleDigests(Board board) {
        Set<String> stale = new LinkedHashSet<>();
        String base = host.currentBase(board.scope());
        for (ReviewIntent intent : counted(board)) {
            Collection<String> files = filesAffectingScope(board, intent);
            for (String digest : digestsOf(board, intent)) {
                if (stale.contains(digest)) {
                    continue;
                }
                Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
                if (verdict.isPresent()
                        && stalenessOf(board, verdict.get(), base, files) == Staleness.MOVED) {
                    stale.add(digest);
                }
            }
        }
        return stale;
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
        Collection<String> files = filesAffectingScope(board, intent);
        List<Optional<ReviewVerdict>> perHunk = new ArrayList<>();
        Set<String> elsewhere = new LinkedHashSet<>();
        Staleness staleness = Staleness.FRESH;
        int settled = 0;
        int recorded = 0;
        for (String digest : digests) {
            Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
            perHunk.add(verdict);
            if (verdict.isPresent()) {
                recorded++;
                // MOVED outranks UNKNOWN outranks FRESH: one hunk known to
                // have moved is the strongest thing true of the section.
                Staleness hunk = stalenessOf(board, verdict.get(), base, files);
                if (hunk == Staleness.MOVED
                        || (hunk == Staleness.UNKNOWN && staleness == Staleness.FRESH)) {
                    staleness = hunk;
                }
                // A stale verdict still merges into the section's DECISION
                // (perHunk, below) -- the decision persists, only its
                // freshness is in question -- but does not count toward
                // the numeric "n/total", the same exclusion
                // settledHunkCount applies globally (spec §9.2). Without
                // this a card could read "3/3 hunks" while the verdict
                // bar's own progress line, one floor up, read "2/3" for
                // the identical section. recordedHunks is the escape hatch:
                // it counts this hunk anyway, so the card's PROSE progress
                // label does not understate to zero just because the one
                // thing it has to say is stale (spec correction 6a).
                if (hunk != Staleness.MOVED) {
                    settled++;
                }
                collectSharingSections(board, digest, intent, elsewhere);
            }
        }
        return new SectionState(VerdictMerge.derive(perHunk), settled, recorded, digests.size(),
                staleness, List.copyOf(elsewhere), false);
    }

    /**
     * Asks the agent which approvals a base move disturbed, at most once per
     * move (spec §9.7).
     *
     * <p>Driven from the render pass rather than from the moment the move is
     * detected, because that is where staleness is already known: a section
     * whose {@link SectionState#staleness()} is not {@code FRESH} is exactly
     * one the move survived {@link BaseMove#couldMatter}'s file filter for.
     * Gating on that reuses the relevance test instead of repeating it, and a
     * move touching nothing this scope reads spends no subagent.</p>
     *
     * <p>The render pass runs many times per move, so the guard cannot be the
     * annotation store: {@link AnnotationStore#assessedAffected} reads the
     * same for "assessed unaffected" and for "never asked", and therefore
     * cannot see a dispatch still in flight. {@link RecheckDispatch} is that
     * memory. A hand-off that returned false is released again, since it
     * reached no terminal and no human is present to notice.</p>
     */
    void requestRechecks(Board board, RecheckDispatch dispatch) {
        String base = host.currentBase(board.scope());
        if (SessionReviewView.UNRESOLVED_BASE.equals(base)) {
            // Not a revision, so there is no base PAIR to ask about. The
            // reader already sees these as stale-until-confirmed.
            return;
        }
        Set<String> recordedBases = new LinkedHashSet<>();
        for (ReviewIntent intent : board.sections()) {
            if (stateOf(board, intent).staleness() == Staleness.FRESH) {
                continue;
            }
            for (String digest : digestsOf(board, intent)) {
                host.verdict(board.scope(), digest)
                        .filter(verdict -> verdict.staleAgainst(base))
                        .ifPresent(verdict -> recordedBases.add(verdict.baseCommit()));
            }
        }
        for (String from : recordedBases) {
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
     * MOVED}, never the reverse. The filter is lexical and admits it cannot
     * see a base change that alters behaviour without touching a file this
     * scope names; this is the only thing that can. An agent's "unaffected"
     * reaches nothing here, by construction rather than by a branch: it is
     * indistinguishable from never having been asked.</p>
     */
    private Staleness stalenessOf(Board board, ReviewVerdict verdict, String base,
                                  Collection<String> files) {
        if (!verdict.staleAgainst(base)) {
            // Not a move at all, so there is no move for a recheck to be
            // about: a verdict recorded against the current base is fresh
            // whatever any agent said about some earlier pair.
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
     * intent}: its own files ({@link #filesOf}), plus -- when the scope's
     * {@link ChangeGraph} is already in hand -- the files declaring symbols
     * those files reference (spec §9.2's second half). Falls back to {@link
     * #filesOf} alone when the graph is absent (still building, failed, or
     * never requested for a reviewer-supplied grouping): widening is an
     * improvement over the narrower set, never a reason to build one.
     */
    private static Collection<String> filesAffectingScope(Board board, ReviewIntent intent) {
        List<String> own = filesOf(board, intent);
        Optional<ChangeGraph> graph = board.graph();
        if (graph.isEmpty()) {
            return own;
        }
        SortedSet<String> widened = new TreeSet<>(own);
        for (String file : own) {
            widened.addAll(graph.get().filesReferencedBy(file));
        }
        return widened;
    }

    /**
     * The mark of the FIRST other section sharing {@code digest} (in rail
     * order), so a count that advanced without the reader touching this card
     * is explained.
     *
     * <p>Not conditioned on the sibling being fully settled. A sibling that
     * settled one shared hunk moves this card's count by exactly as much as a
     * fully settled one does, and leaving that case unmarked solves the
     * "state changing on its own" problem only for the easy half of it.</p>
     *
     * <p><strong>Named at most once, never every sharer</strong> (spec
     * correction 6b). A verdict is keyed {@code (scopeId, hunkDigest)} alone
     * -- nothing records WHICH section's card the reader actually settled it
     * through -- so with three or more sections sharing one hunk there is no
     * way to single out the one that "reviewed" it; naming all of them
     * credited sections that, as far as this model can tell, reviewed
     * nothing. Stopping at the first candidate is the fix this can honestly
     * make without inventing provenance a verdict does not carry: with
     * exactly one other sharer -- every case this class is tested against
     * today -- it names that same one section as before.</p>
     */
    private void collectSharingSections(Board board, String digest, ReviewIntent self,
                                        Set<String> into) {
        for (ReviewIntent other : board.sections()) {
            if (other.id().equals(self.id()) || !other.countsTowardProgress()) {
                continue;
            }
            if (digestsOf(board, other).contains(digest)) {
                into.add(sectionMark(other.number()));
                return;
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

    /**
     * The digest of {@code intent}'s FIRST hunk -- its anchor, and where
     * selecting the section scrolls the diff column to (see {@code
     * SessionReviewView#revealCurrentIntent}). The ultimate fallback for
     * HUNK mode (see {@link #digestOfCurrentHunk}): once nothing is
     * selected and nothing is left unsettled, this is what {@code a}/
     * {@code r}/{@code u} still have something to act on. Empty for a
     * section with no resolvable hunk at all.
     */
    Optional<String> digestOfAnchorHunk(Board board, ReviewIntent intent) {
        List<String> digests = digestsOf(board, intent);
        return digests.isEmpty() ? Optional.empty() : Optional.of(digests.get(0));
    }

    /**
     * The first of {@code intent}'s hunks with no verdict yet. The middle
     * fallback for HUNK mode: with the diff column acting and no gutter
     * selection open, {@code a} has to walk forward through what is still
     * unread rather than park on the anchor hunk forever -- pressing it
     * once approves hunk one, pressing it again must not re-approve hunk
     * one a second time while hunks two and up sit unread.
     */
    Optional<String> digestOfFirstUnsettledHunk(Board board, ReviewIntent intent) {
        for (String digest : digestsOf(board, intent)) {
            if (host.verdict(board.scope(), digest).isEmpty()) {
                return Optional.of(digest);
            }
        }
        return Optional.empty();
    }

    /**
     * The digest HUNK mode acts on (spec §9.6), in priority order: the hunk
     * under the diff column's gutter selection when one is open ({@code
     * selectionKey}, {@code "<file> <lineKey>"} -- see {@link
     * ReviewDiffColumn#currentLineSelection}); else the section's first
     * unsettled hunk, so the reader can walk forward with repeated presses
     * of {@code a}/{@code r} rather than re-settling the same hunk forever;
     * else its anchor hunk, so a fully-settled section still has something
     * for {@code u} to undo.
     */
    Optional<String> digestOfCurrentHunk(Board board, ReviewIntent intent,
                                          Optional<String> selectionKey) {
        return selectionKey.flatMap(key -> selectionFile(key)
                        .flatMap(file -> selectionLineKey(key)
                                .flatMap(lineKey -> digestOfLine(board, file, lineKey))))
                .or(() -> digestOfFirstUnsettledHunk(board, intent))
                .or(() -> digestOfAnchorHunk(board, intent));
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
     * {@code intent}'s own anchor file (see {@link #fileOf}).
     */
    Optional<String> currentFileOf(Board board, ReviewIntent intent, Optional<String> selectionKey) {
        return selectionKey.flatMap(SectionStates::selectionFile)
                .or(() -> fileOf(board, intent));
    }

    /**
     * The file the diff column is anchored on when {@code intent} is
     * selected -- the fallback for {@link #currentFileOf} once nothing is
     * selected. The intent's own anchor file when it names one, else the
     * first file it covers at all (see {@link #filesOf}).
     */
    private Optional<String> fileOf(Board board, ReviewIntent intent) {
        return intent.anchor().map(ReviewIntent.Anchor::file)
                .or(() -> filesOf(board, intent).stream().findFirst());
    }

    private static Optional<String> selectionFile(String key) {
        int lastSpace = key.lastIndexOf(' ');
        return lastSpace < 0 ? Optional.empty() : Optional.of(key.substring(0, lastSpace));
    }

    private static Optional<String> selectionLineKey(String key) {
        int lastSpace = key.lastIndexOf(' ');
        return lastSpace < 0 ? Optional.empty() : Optional.of(key.substring(lastSpace + 1));
    }

    /**
     * Every hunk digest of {@code file} across the WHOLE diff, in diff
     * order -- not just the slice one section names. {@code ⇧A}/{@code ⇧R}
     * settle the file regardless of which section(s) claim its hunks.
     */
    List<String> digestsOfFile(Board board, String file) {
        for (UnifiedDiff.FileDiff candidate : board.diff().files()) {
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

    /**
     * The digests {@code a}/{@code r}/{@code u} act on for {@code intent}
     * right now (spec §9.6): {@code wholeFile} is {@code ⇧A}/{@code ⇧R} and
     * always wins over {@code unit}; otherwise {@code unit} decides between
     * the whole section and {@link #digestOfCurrentHunk}'s one hunk.
     */
    List<String> digestsForAction(Board board, ReviewIntent intent, SessionReviewView.SettleUnit unit,
                                   boolean wholeFile, Optional<String> selectionKey) {
        if (wholeFile) {
            return currentFileOf(board, intent, selectionKey)
                    .map(file -> digestsOfFile(board, file))
                    .orElse(List.of());
        }
        return unit == SessionReviewView.SettleUnit.HUNK
                ? digestOfCurrentHunk(board, intent, selectionKey).map(List::of).orElse(List.of())
                : digestsOf(board, intent);
    }

    /**
     * The recorded base of a stale verdict in {@code intent}, for the
     * verdict bar's banner -- the first one found whose base no longer
     * matches {@code board}'s scope's current one. Callers only ask this
     * once {@link Staleness#MOVED} is already established, so one is
     * guaranteed to exist; the current base is the fallback only because a
     * method that returns nothing here is worse than one that occasionally
     * repeats a base that did not move.
     */
    String oldBaseOf(Board board, ReviewIntent intent) {
        String current = host.currentBase(board.scope());
        for (String digest : digestsOf(board, intent)) {
            Optional<ReviewVerdict> verdict = host.verdict(board.scope(), digest);
            if (verdict.isPresent() && verdict.get().staleAgainst(current)) {
                return verdict.get().baseCommit();
            }
        }
        return current;
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
