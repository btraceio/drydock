package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
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
    final List<String> submittedScopes = new ArrayList<>();
    final List<Path> explorerJumps = new ArrayList<>();

    /** What {@link #submit} was handed, keyed by scope id -- what the submit tests assert against. */
    final java.util.Map<String, app.drydock.review.SubmitPlan.DiffIndex> submittedIndexes = new java.util.HashMap<>();
    final java.util.Map<String, List<ReviewVerdict.Decision>> submittedDecisions = new java.util.HashMap<>();

    /** What {@link #intents} groups by when no reviewer has supplied a grouping. */
    UnifiedDiff diff = new UnifiedDiff(List.of());

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
    public List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff) {
        return intents.intentsFor(scope.id(), diff);
    }

    @Override
    public Optional<ReviewVerdict> verdict(ReviewScope scope, ReviewIntent intent) {
        return store.verdict(scope.id(), intent.id());
    }

    @Override
    public void setVerdict(ReviewScope scope, ReviewIntent intent,
                           Optional<ReviewVerdict.Decision> decision) {
        if (decision.isEmpty()) {
            store.clearVerdict(scope.id(), intent.id());
            return;
        }
        if (decision.get() == ReviewVerdict.Decision.APPROVED && blocked(scope, intent)) {
            return;
        }
        // TODO(task-6): intent.id() stands in for HunkDigest.of(...).
        // TODO(task-6): scope.base()/head() are ref names, not commit shas;
        // staleness is inert until these resolve.
        store.putVerdict(new ReviewVerdict(scope.id(), intent.id(), decision.get(),
                Optional.empty(), Instant.now(), scope.base(), scope.head()));
    }

    private boolean blocked(ReviewScope scope, ReviewIntent intent) {
        return store.forScope(scope.id()).stream()
                .filter(finding -> finding.intentId().map(id -> id.equals(intent.id())).orElse(true))
                .anyMatch(ReviewAnnotation::blocksApproval);
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

    @Override
    public void askAgentToFix(ReviewScope scope, ReviewIntent intent, List<ReviewAnnotation> findings) {
        if (findings.isEmpty()) {
            return;
        }
        handedOffPrompts.add(intent.title() + ": " + findings.size() + " findings");
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
