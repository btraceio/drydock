package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.IntentGrouping;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import javafx.scene.layout.Region;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * A {@link SessionReviewView.Host} backed by a real {@link AnnotationStore}
 * and {@link IntentGrouping}, so the view tests exercise the same store the
 * app does rather than a bag of stubs -- the (scopeId, id) keying is the
 * thing under test, and a stub would key however the test felt like.
 *
 * <p>Only the outward hand-offs (jumping to the Explorer, sending a prompt to
 * a terminal) are recorded rather than performed; those have no counterpart
 * in a test JVM.</p>
 */
final class FakeReviewHost implements SessionReviewView.Host {

    final AnnotationStore store;
    final IntentGrouping intents = new IntentGrouping();

    final List<String> handedOffPrompts = new ArrayList<>();

    /** Every automatic recheck asked for, as {@code fromBase->toBase}. */
    final List<String> recheckDispatches = new ArrayList<>();

    /** Whether the recheck hand-off reaches a terminal; false stands in for a closed tab. */
    boolean recheckHandOffSucceeds = true;

    /** Whether this scope's agent may be asked automatically (spec §9.7). */
    boolean supportsAutomaticRecheck = true;
    final List<String> submittedScopes = new ArrayList<>();
    final List<Path> explorerJumps = new ArrayList<>();

    /** What {@link #submit} was handed, keyed by scope id -- what the submit tests assert against. */
    final java.util.Map<String, app.drydock.review.SubmitPlan.DiffIndex> submittedIndexes = new java.util.HashMap<>();
    final java.util.Map<String, List<ReviewVerdict.Decision>> submittedDecisions = new java.util.HashMap<>();

    /** What {@link #intents} groups by when no reviewer has supplied a grouping. */
    UnifiedDiff diff = new UnifiedDiff(List.of());

    /**
     * What {@code scope.base()} / {@code scope.head()} RESOLVE to. Refs are
     * branch names; a verdict stamped with one and compared against the same
     * one could never be stale, so the real host resolves them through git
     * and this fake stands in for that answer. Tests move {@link #baseCommit}
     * to make a verdict stale.
     */
    String baseCommit = "1".repeat(40);
    String headCommit = "2".repeat(40);

    /**
     * What a base move touched, as {@code BaseMove.between} would report it.
     * Empty and resolvable by default: a move that provably could not matter.
     */
    BaseMove.Delta baseDelta = new BaseMove.Delta(false, new TreeSet<>());

    /** Whether the Explorer jump can succeed (no session bound = false). */
    boolean explorerAvailable;

    /** Set by tests that want the view's own body rather than the diff column. */
    Optional<Region> body = Optional.empty();

    FakeReviewHost(Path storeFile) {
        this.store = new AnnotationStore(storeFile);
    }

    /**
     * Test convenience: writes {@code finding} into the store under {@code
     * scope}, stamping its scope id -- the board tests that seed synthetic
     * findings (rather than driving a real diff) go through this rather than
     * poking {@link #store} directly, so the (scopeId, id) keying stays in
     * one place.
     */
    void addFinding(ReviewScope scope, ReviewAnnotation finding) {
        store.upsert(finding.withScopeId(scope.id()));
    }

    @Override
    public Optional<Region> bodyFor(ReviewScope scope) {
        return body;
    }

    @Override
    public Optional<Integer> openFindings(ReviewScope scope) {
        return store.forScope(scope.id()).isEmpty()
                ? Optional.empty()
                : Optional.of((int) store.openCount(scope.id()));
    }

    @Override
    public void showShortcuts() {
    }

    @Override
    public boolean openInExplorer(ReviewScope scope, Path file, int line) {
        if (!explorerAvailable) {
            return false;
        }
        explorerJumps.add(file);
        return true;
    }

    @Override
    public List<ReviewAnnotation> findings(ReviewScope scope) {
        return store.forScope(scope.id());
    }

    @Override
    public List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff,
                                      Optional<ChangeGraph> graph) {
        return intents.intentsFor(scope.id(), diff, graph);
    }

    @Override
    public long groupingVersion(ReviewScope scope) {
        return intents.version(scope.id());
    }

    @Override
    public boolean hasReviewerGrouping(ReviewScope scope) {
        return intents.hasReviewerGrouping(scope.id());
    }

    @Override
    public Optional<ReviewVerdict> verdict(ReviewScope scope, String hunkDigest) {
        return store.verdict(scope.id(), hunkDigest);
    }

    @Override
    public void setVerdict(ReviewScope scope, ReviewIntent intent, List<String> hunkDigests,
                           Optional<ReviewVerdict.Decision> decision, boolean blocked) {
        if (decision.isEmpty()) {
            for (String digest : hunkDigests) {
                store.clearVerdict(scope.id(), digest);
            }
            return;
        }
        if (decision.get() == ReviewVerdict.Decision.APPROVED && blocked) {
            return;
        }
        for (String digest : hunkDigests) {
            store.putVerdict(new ReviewVerdict(scope.id(), digest, decision.get(),
                    Optional.empty(), Instant.now(), baseCommit, headCommit));
        }
    }

    @Override
    public void confirmStillGood(ReviewScope scope, List<String> hunkDigests) {
        Instant now = Instant.now();
        for (String digest : hunkDigests) {
            store.verdict(scope.id(), digest).ifPresent(verdict ->
                    store.putVerdict(verdict.confirmedAgainst(baseCommit, headCommit, now)));
        }
    }

    @Override
    public String currentBase(ReviewScope scope) {
        return baseCommit;
    }

    @Override
    public BaseMove.Delta baseMove(ReviewScope scope, String recordedBase) {
        return baseDelta;
    }

    /** Reads the real store, so a test drives this through {@code putAssessment}. */
    @Override
    public boolean assessedAffected(ReviewScope scope, String hunkDigest,
                                    String fromBase, String toBase) {
        return store.assessedAffected(scope.id(), hunkDigest, fromBase, toBase);
    }

    @Override
    public boolean supportsAutomaticRecheck(ReviewScope scope) {
        return supportsAutomaticRecheck;
    }

    /** Reads the real store, like {@link #assessedAffected}. */
    @Override
    public boolean assessedMove(ReviewScope scope, String fromBase, String toBase) {
        return store.assessedMove(scope.id(), fromBase, toBase);
    }

    @Override
    public boolean dispatchRecheck(ReviewScope scope, String fromBase, String toBase) {
        recheckDispatches.add(fromBase + "->" + toBase);
        return recheckHandOffSucceeds;
    }

    @Override
    public void setResolved(ReviewScope scope, ReviewAnnotation finding, boolean resolved) {
        store.mutate(finding.key(), current -> current.withStatus(
                resolved ? AnnotationStatus.RESOLVED : AnnotationStatus.OPEN));
    }

    @Override
    public void postMessage(ReviewScope scope, ReviewAnnotation finding, String body) {
        store.mutate(finding.key(), current -> current.withReply(
                new ReviewAnnotation.Message("You", Instant.now(), body)));
    }

    /** Written into the same store the real host uses, so the margin and pins pick it up. */
    @Override
    public void addComment(ReviewScope scope, ReviewAnnotation annotation) {
        store.upsert(annotation.withScopeId(scope.id()));
    }

    @Override
    public void setPostToPr(ReviewScope scope, ReviewAnnotation finding, boolean post) {
        store.mutate(finding.key(), current -> current.withPostToPr(post));
    }

    @Override
    public void applyPatch(ReviewScope scope, ReviewAnnotation finding) {
        finding.patch().ifPresent(patch -> {
            handedOffPrompts.add(patch.unified());
            store.mutate(finding.key(), current -> current.withStatus(AnnotationStatus.SENT));
        });
    }

    @Override
    public void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity) {
        store.mutate(finding.key(), current -> current.withSeverityOverride(severity));
    }

    /**
     * Whether a session is bound to hand work to. False models the real
     * "no session, or its tab is closed" case, which the real host reports
     * through {@code sendToBoundSession}'s own boolean.
     */
    boolean sessionBound = true;

    @Override
    public boolean askAgentToFix(ReviewScope scope, ReviewIntent intent, List<ReviewAnnotation> findings) {
        if (findings.isEmpty() || !sessionBound) {
            return false;
        }
        handedOffPrompts.add(intent.title() + ": " + findings.size() + " findings");
        return true;
    }

    @Override
    public void submit(ReviewScope scope, app.drydock.review.SubmitPlan.DiffIndex index,
                       List<ReviewVerdict.Decision> decisions) {
        submittedScopes.add(scope.id());
        submittedIndexes.put(scope.id(), index);
        submittedDecisions.put(scope.id(), decisions);
        store.markSubmitted(scope.id());
    }

    /** Whether a reviewer is available at all; empty models "no reviewer configured". */
    final List<String> reviewers = new ArrayList<>();
    final List<String> reviewRuns = new ArrayList<>();

    @Override
    public boolean runReview(ReviewScope scope) {
        if (reviewers.isEmpty()) {
            return false;
        }
        reviewRuns.add(scope.id());
        return true;
    }
}
