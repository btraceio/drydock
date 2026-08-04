package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
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
 * A {@link ReviewDestinationView.Host} backed by a real {@link AnnotationStore}
 * and {@link IntentGrouping}, so the view tests exercise the same store the
 * app does rather than a bag of stubs -- the (scopeId, id) keying is the
 * thing under test, and a stub would key however the test felt like.
 *
 * <p>Only the outward hand-offs (opening a session, sending a prompt to a
 * terminal) are recorded rather than performed; those have no counterpart in
 * a test JVM.</p>
 */
final class FakeReviewHost implements ReviewDestinationView.Host {

    final AnnotationStore store;
    final IntentGrouping intents = new IntentGrouping();

    final List<ManagedSessionId> openedSessions = new ArrayList<>();
    final List<String> handedOffPrompts = new ArrayList<>();
    final List<String> submittedScopes = new ArrayList<>();
    final List<Path> explorerJumps = new ArrayList<>();

    /** What {@link #intents} groups by when no reviewer has supplied a grouping. */
    UnifiedDiff diff = new UnifiedDiff(List.of());

    /** Whether the Explorer jump can succeed (no session bound = false). */
    boolean explorerAvailable;

    /** Set by tests that want the view's own body rather than the diff column. */
    Optional<Region> body = Optional.empty();

    FakeReviewHost(Path storeFile) {
        this.store = new AnnotationStore(storeFile);
    }

    /** How many times the empty state's Retry asked for a rescan. */
    int queueRetries;

    @Override
    public void refreshQueue() {
    }

    @Override
    public void retryQueueScan() {
        queueRetries++;
    }

    @Override
    public void openSession(ManagedSessionId sessionId) {
        openedSessions.add(sessionId);
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
    public Optional<String> sessionState(ReviewScope scope) {
        return scope.sessionId().map(id -> "running");
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
        List<ReviewIntent> grouped = intents.intentsFor(scope.id(), diff);
        // The real host migrates here too. A fake that skipped it would be
        // fine right up until the migration broke, which is the one moment a
        // fake earns its keep.
        store.migrateLegacyVerdicts(scope.id(), grouped);
        return grouped;
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
        store.putVerdict(new ReviewVerdict(scope.id(), intent.id(), decision.get(),
                Optional.empty(), Instant.now()));
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
    public void addComment(ReviewScope scope, String file, String lineKey, String body,
                           Optional<String> intentId) {
        Instant now = Instant.now();
        store.upsert(new ReviewAnnotation(scope.id(), "c_" + (++commentCount), intentId,
                file, lineKey, lineKey, Severity.NIT, app.drydock.review.Confidence.HIGH,
                Optional.empty(), "You", now, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("You", now, body)),
                Optional.empty(), AnnotationStatus.OPEN));
    }

    /** Sequential rather than random, so a test can name the comment it just made. */
    int commentCount;

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
    public void submit(ReviewScope scope) {
        submittedScopes.add(scope.id());
        store.markSubmitted(scope.id());
    }

    /** What {@link #launchCommandPreview} answers -- a non-Claude agent by default, since that is the case that regressed. */
    String launchCommand = "codex --cd <worktree>";

    /** Reviewers this fake offers; empty models "no reviewer configured". */
    final List<String> reviewers = new ArrayList<>();
    String selectedReviewer;
    final List<String> reviewRuns = new ArrayList<>();

    @Override
    public List<String> reviewers() {
        return List.copyOf(reviewers);
    }

    @Override
    public Optional<String> selectedReviewer() {
        return Optional.ofNullable(selectedReviewer);
    }

    @Override
    public void selectReviewer(String reviewer) {
        selectedReviewer = reviewer;
    }

    final List<String> checkoutRequests = new ArrayList<>();
    /** When set, the fake checkout reports this failure instead of succeeding. */
    String checkoutFailure;
    /** The patch "Read the patch only" returns; empty models gh being unable to read it. */
    Optional<UnifiedDiff> patch = Optional.empty();

    @Override
    public void startSessionAndReview(ReviewScope scope, java.util.function.Consumer<String> onFailed) {
        checkoutRequests.add(scope.id());
        if (checkoutFailure != null) {
            onFailed.accept(checkoutFailure);
        }
    }

    @Override
    public void readPatchOnly(ReviewScope scope, java.util.function.Consumer<Optional<UnifiedDiff>> onComplete) {
        onComplete.accept(patch);
    }

    /** The launch line the gate previews; tests set {@link #launchCommand} to whichever agent they model. */
    @Override
    public void launchCommandPreview(ReviewScope scope, java.util.function.Consumer<String> onReady) {
        onReady.accept(launchCommand);
    }

    @Override
    public String diagDiffSummary(ReviewScope scope) {
        return "0 files";
    }

    @Override
    public boolean runReview(ReviewScope scope) {
        if (reviewers.isEmpty()) {
            return false;
        }
        reviewRuns.add(scope.id());
        return true;
    }
}
