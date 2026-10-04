package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ChangeGraph;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Triage;
import app.drydock.review.UsageProvider;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.HunkOverride;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.StepImpact;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.StepVerdicts;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFindings;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourMigration;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.ui.UiErrors;
import app.drydock.ui.nav.LexicalUsageProvider;
import app.drydock.ui.nav.NavigationTrail;
import app.drydock.ui.nav.SymbolPeekService;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * The guided tour's state and behaviour (spec §5), for one {@link
 * SessionReviewView}: which step is current, the outline and the step panel
 * it renders into, the step marks over the diff column, every step's measured
 * impact, the risk-check queue, the "Building tour…" wait, and the tour's
 * keys.
 *
 * <p>The view keeps the layout and the mode switch, and reaches the tour
 * only through this class. The tour has three collaborators: the board
 * around it, only through {@link View}; the host ({@link
 * SessionReviewView.Host}), for the agent hand-offs, the hunk verdicts the
 * tour derives, and the tour record, which lives in the host's store
 * ({@link SessionReviewView.Host#tour}, {@link
 * SessionReviewView.Host#updateTour}) and stays its one writer; and
 * the {@link ReviewDiffColumn} it reveals rows in and marks steps over.
 * Everything here runs on the FX thread except the impact computation and
 * callee resolution, which run off it and hop back.</p>
 */
final class TourController {

    private static final Logger LOG = System.getLogger(TourController.class.getName());

    /** What the tour needs from the board around it. All calls happen on the FX thread. */
    interface View {
        Optional<ReviewScope> selectedScope();

        /** Either of the session's scopes by id, selected or not. */
        Optional<ReviewScope> scopeById(String scopeId);

        /** The selected scope's review diff, once it has loaded. */
        Optional<UnifiedDiff> loadedDiff();

        /** {@code scope}'s findings minus those a tour withholds. */
        List<ReviewAnnotation> visibleFindings(ReviewScope scope);

        /** Whether the board shows the tour rather than the hunk diff. */
        boolean touring();

        /** {@code scopeId}'s change graph, once built. */
        Optional<ChangeGraph> graph(String scopeId);

        /** Why no graph is coming for {@code diff}: its build failed and none is running. */
        Optional<String> graphFailure(String scopeId, UnifiedDiff diff);

        /** {@code scopeId}'s caller scan, once it has landed. */
        Optional<OutOfDiffFanIn.Result> fanIn(String scopeId);

        Optional<ReviewNavigation> navigation();

        /** The new-side line numbers of each review-diff file's ADD rows. */
        Map<Path, Set<Integer>> changedLinesOfReviewDiff();

        boolean trailEmpty();

        void pushWaypoint(Path file, String label, int line, Optional<String> lineKey);

        /** Puts nothing on the verdict bar, with no progress. */
        void clearVerdictBar();

        /** The keys that work now, for the hint strip; empty when there is no tour to act on. */
        void showKeyHints(List<TourKeyHints.Hint> hints);

        /** {@code h}: hides the key hints, or shows them again. */
        void toggleKeyHints();

        /** Puts the current step on the verdict bar, with progress in steps. */
        void showStepOnVerdictBar(ReviewVerdictBar.Target target, Optional<ReviewVerdict.Decision> decision,
                                  int settled, int total);

        /** A navigation outcome over the diff column. */
        void notice(String message);

        /** A peek at a file the column does not show. */
        void openLocationPeek(Path relativePath, int line);

        boolean peekOpen();

        /** {@code v}: tour ↔ hunk diff. */
        void toggleMode();

        /** {@code ⇧D}: the search rail's scope, when there is a search rail. */
        void toggleSearchScope();

        /** The top bar's Run review. */
        void runReview();

        /** The failure's "Open diff review". */
        void openDiffReview();

        void refreshReviewState();

        void triageFinding(ReviewScope scope, ReviewAnnotation finding, Triage triage, Optional<String> reason);

        /** Opens the MCP activity panel if there is one and it is hidden; true when it was opened. */
        boolean showMcpPanel();

        /** Hides the MCP activity panel if it shows. */
        void hideMcpPanel();
    }

    private final SessionReviewView.Host host;
    private final View view;
    private final ReviewDiffColumn diffColumn;
    private final Executor impactExecutor;

    private final TourRefreshDispatch tourRefreshDispatch = new TourRefreshDispatch();
    /** The review diff each scope's tour was last checked against; see {@link #migrateTour}. */
    private final Map<String, UnifiedDiff> tourCheckedDiffByScope = new HashMap<>();

    private final TourOutline outline = new TourOutline();
    private final StepHost stepHost = new StepHost();
    private final StepPanel stepPanel = new StepPanel(stepHost);

    /** Which of the current step's anchors {@code .} / {@code ,} last revealed. */
    private int anchorIndex;

    private final RiskCheckQueue riskQueue = new RiskCheckQueue(new RiskDispatcher());

    /** The step the panel shows; null until a tour renders, re-picked when it stops resolving. */
    private String currentStepId;

    /**
     * The scope whose Run review is building a tour, while "Building tour…"
     * shows. A scope id rather than a flag, so the other chip's scope does
     * not claim a tour is coming for it.
     */
    private Optional<String> tourPendingScopeId = Optional.empty();

    /** Why the last Run review produced no tour, for the scope it ran on. */
    private record TourFailure(String scopeId, String message) {
    }

    private Optional<TourFailure> tourFailure = Optional.empty();

    /** How long "Building tour…" waits before offering Retry / Open diff review. */
    private final PauseTransition tourWait = new PauseTransition(Duration.minutes(15));

    /** Whether the MCP panel was opened by the tour wait (and so is closed by it), not by {@code \}. */
    private boolean mcpOpenedForTour;

    /** Whether the diff column carries step marks, so DIFF mode clears them once rather than per refresh. */
    private boolean tourMarksShown;

    /** The outline footer's Acknowledge for files without line changes; per view, not persisted. */
    private boolean filesWithoutChangesAcknowledged;

    /**
     * What the outline, the step panel and the step marks last rendered.
     * {@link #render} runs on every store write -- an agent's finding
     * included -- and rebuilding the panel each time would drop a half-typed
     * answer and its focus, so each is redrawn only when its input changed.
     * Null whenever a message replaced the content.
     */
    private List<TourOutline.Row> shownRows;
    private String shownRowsCurrent;
    private StepView shownStepView;
    private List<ReviewAnnotation> shownTriage;
    private List<ReviewAnnotation> shownBanner;
    private boolean shownBannerShelved;
    private TourRecord shownMarksRecord;
    private String shownMarksStepId;
    /** The anchor the shown marks treat as active; see {@link #anchorIndex}. */
    private int shownMarksAnchor;
    private StepPanel.ImpactView shownImpact;

    /**
     * Stands in for the caller scan in {@link StepImpact#of} until the scan
     * has landed, so edges and callees can be measured meanwhile; the step
     * panel shows "Finding callers…" for as long as an entry was computed
     * from this instance (compared by identity).
     */
    private static final OutOfDiffFanIn.Result CALLERS_PENDING =
            new OutOfDiffFanIn.Result(Map.of(), Optional.empty());

    private static final StepImpact NO_IMPACT =
            new StepImpact(List.of(), List.of(), List.of(), List.of(), Optional.empty());

    /**
     * Every step's measured impact, computed off the FX thread on the
     * view's graph executor from the graph and scan the board already
     * built -- never a scan of its own -- and recomputed only when one of
     * its inputs is replaced. {@link #impactsInFlight} is the computation
     * running now; a completion that is no longer it is dropped.
     */
    private ImpactEntry impacts;
    private ImpactEntry impactsInFlight;

    /** What {@link #impacts} was computed from: the diff, graph and scan by identity, the tour by value. */
    private record ImpactEntry(String scopeId, UnifiedDiff diff, ChangeGraph graph, OutOfDiffFanIn.Result fanIn,
                               ReviewTour tour, Map<String, StepImpact> byStep, Set<String> declaring) {
        boolean sameBoard(String otherScopeId, UnifiedDiff otherDiff, ChangeGraph otherGraph, ReviewTour otherTour) {
            return scopeId.equals(otherScopeId) && diff == otherDiff && graph == otherGraph && tour.equals(otherTour);
        }

        boolean computedFrom(String otherScopeId, UnifiedDiff otherDiff, ChangeGraph otherGraph,
                             OutOfDiffFanIn.Result otherFanIn, ReviewTour otherTour) {
            return fanIn == otherFanIn && sameBoard(otherScopeId, otherDiff, otherGraph, otherTour);
        }
    }

    /**
     * Callee declarations resolved for one (scope, diff), by name: a
     * declaration does not depend on which step asked, so steps share them.
     * Reset when the scope or diff changes, which is also what drops a
     * resolution that lands after the reviewer moved on.
     */
    private final Map<String, Optional<UsageProvider.Usage>> calleeResolutions = new HashMap<>();
    private final Set<String> calleesResolving = new HashSet<>();
    private String calleeScopeId;
    private UnifiedDiff calleeDiff;
    /** At most one re-render pending for however many callee resolutions land together. */
    private boolean calleeRenderQueued;

    /** Set by {@link #close}; a background completion after it touches nothing. */
    private volatile boolean closed;

    /**
     * @param impactExecutor where every step's impact is measured -- the
     *                       view's graph executor, never the FX thread
     */
    TourController(SessionReviewView.Host host, View view, ReviewDiffColumn diffColumn, Executor impactExecutor) {
        this.host = host;
        this.view = view;
        this.diffColumn = diffColumn;
        this.impactExecutor = impactExecutor;
        outline.setOnSelected(this::selectStep);
        outline.setOnAcknowledge(() -> {
            filesWithoutChangesAcknowledged = true;
            render();
        });
        tourWait.setOnFinished(event -> onTourWaitExpired());
        diffColumn.setOnClaimSelected(this::selectClaim);
    }

    TourOutline outline() {
        return outline;
    }

    StepPanel stepPanel() {
        return stepPanel;
    }

    /** The host the step panel acts through. */
    StepPanel.Host stepHost() {
        return stepHost;
    }

    /** Stops the wait and the risk queue; background completions after this do nothing. */
    void close() {
        closed = true;
        tourWait.stop();
        riskQueue.close();
    }

    // ---- scope and mode ------------------------------------------------------

    Optional<TourRecord> currentTour() {
        return view.selectedScope().flatMap(host::tour);
    }

    private Optional<TourFailure> failureForSelection() {
        return view.selectedScope().flatMap(scope -> tourFailure.filter(f -> f.scopeId().equals(scope.id())));
    }

    /** Whether the last Run review on the selected scope produced no tour. */
    boolean failedForSelection() {
        return failureForSelection().isPresent();
    }

    /** Whether a Run review is building a tour for the selected scope. */
    boolean pending() {
        return view.selectedScope().map(scope -> tourPendingScopeId.filter(scope.id()::equals).isPresent())
                .orElse(false);
    }

    /** The incoming scope opens on its own first unsettled step. */
    void resetForScope() {
        currentStepId = null;
        filesWithoutChangesAcknowledged = false;
    }

    /** Back to the step's first anchor, with no "back to step" pill. */
    void resetAnchor() {
        stepPanel.hideBackPill();
        anchorIndex = 0;
    }

    /** Ends "Building tour…" once {@code tour} has arrived for the scope it was building for. */
    void endWaitIfArrived(Optional<TourRecord> tour) {
        if (tour.isPresent() && pending()) {
            endTourWait();
        }
    }

    /** The hunk diff is showing: no step header, no step marks. */
    void clearFromDiffColumn() {
        diffColumn.setStepHeader(Optional.empty());
        if (tourMarksShown) {
            diffColumn.setStepMarkSource(null);
            tourMarksShown = false;
        }
    }

    /**
     * A store write (or a diff change) landed: the risk queue moves on once
     * its in-flight check is no longer awaiting the agent.
     */
    void syncRiskQueue(ReviewScope scope) {
        String scopeId = scope.id();
        Optional<TourRecord> tourNow = host.tour(scope);
        if (tourNow.isPresent()) {
            riskQueue.onTourChanged(scopeId, checkId -> checkStatus(tourNow, checkId));
            // An answer a previous run left awaiting the agent has no request
            // behind it any more; ask again so it is judged or times out into
            // Retry. enqueue is idempotent for one already queued or in flight.
            for (TourStep step : tourNow.get().tour().steps()) {
                for (TourCheck check : step.checks()) {
                    if (tourNow.get().progress(step.id()).check(check.id()).status()
                            == CheckProgress.Status.AWAITING_AGENT) {
                        riskQueue.enqueue(scopeId, check.id());
                    }
                }
            }
        }
    }

    // ---- Run review's wait ---------------------------------------------------

    /** Run review was handed over and no tour exists yet: "Building tour…" until one arrives. */
    void startWaiting(String scopeId) {
        tourFailure = Optional.empty();
        tourPendingScopeId = Optional.of(scopeId);
        tourWait.playFromStart();
        openMcpPanelForTour();
    }

    /** Run review produced no tour for {@code scopeId}, and why. */
    void failRun(String scopeId, String message) {
        tourFailure = Optional.of(new TourFailure(scopeId, message));
    }

    /** The reader opened or closed the MCP panel; the tour wait must not close it. */
    void readerOwnsMcpPanel() {
        mcpOpenedForTour = false;
    }

    /** The "Building tour…" wait running out now. */
    void expireWait() {
        tourWait.stop();
        onTourWaitExpired();
    }

    private void onTourWaitExpired() {
        Optional<String> pending = tourPendingScopeId;
        endTourWait();
        pending.filter(scopeId -> view.scopeById(scopeId).flatMap(host::tour).isEmpty())
                .ifPresent(scopeId -> tourFailure = Optional.of(new TourFailure(scopeId, "No tour arrived.")));
        render();
    }

    /** Ends "Building tour…": the tour arrived or the wait ran out. */
    private void endTourWait() {
        tourPendingScopeId = Optional.empty();
        tourWait.stop();
        if (mcpOpenedForTour) {
            mcpOpenedForTour = false;
            view.hideMcpPanel();
        }
    }

    /** Shows the MCP activity panel while the tour is built, unless the reader already has it open. */
    private void openMcpPanelForTour() {
        if (view.showMcpPanel()) {
            mcpOpenedForTour = true;
        }
    }

    // ---- rendering -----------------------------------------------------------

    /** Renders the selected scope's tour, when the tour is showing. */
    void render() {
        render(currentTour());
    }

    void render(Optional<TourRecord> tour) {
        if (!view.touring()) {
            return;
        }
        if (tour.isEmpty()) {
            Optional<TourFailure> failure = failureForSelection();
            if (pending()) {
                outline.showMessage("Building tour…", Optional.empty(), () -> { });
                stepPanel.showMessage("The agent is writing the tour. Its MCP calls show below.");
            } else if (failure.isPresent()) {
                outline.showFailure(failure.get().message(), view::runReview, view::openDiffReview);
                stepPanel.showMessage("No tour to show. Retry, or review the hunk diff.");
            } else {
                outline.showMessage("No tour yet.", Optional.of("Run review"), view::runReview);
                stepPanel.showMessage("Run review asks this session's agent for a guided tour.");
            }
            shownRows = null;
            shownStepView = null;
            shownTriage = null;
            shownBanner = null;
            shownImpact = null;
            outline.setNotice(Optional.empty());
            outline.setFooter(0, true);
            diffColumn.setStepHeader(Optional.empty());
            view.clearVerdictBar();
            view.showKeyHints(List.of());
            if (tourMarksShown) {
                diffColumn.setStepMarkSource(null);
                tourMarksShown = false;
            }
            return;
        }
        TourRecord record = tour.get();
        if (currentStepId == null || record.tour().step(currentStepId).isEmpty()) {
            currentStepId = firstUnsettled(record).orElse(record.tour().steps().getFirst().id());
            anchorIndex = 0;
            // The first step shown starts an empty trail, so walking back
            // from the next one has somewhere to land.
            if (view.trailEmpty()) {
                pushStepWaypoint(record, record.tour().step(currentStepId).orElseThrow());
            }
        }
        List<TourOutline.Row> rows = record.tour().steps().stream()
                .map(step -> new TourOutline.Row(step.id(), record.tour().number(step.id()), step.title(),
                        TourOutline.stateOf(record.progress(step.id()))))
                .toList();
        if (!rows.equals(shownRows) || !currentStepId.equals(shownRowsCurrent)) {
            outline.setRows(rows, currentStepId);
            shownRows = rows;
            shownRowsCurrent = currentStepId;
        }
        outline.setNotice(record.shelved()
                ? Optional.of("Shelved — waiting for the author's changes")
                : Optional.empty());
        outline.setFooter(filesWithoutLineChanges(), filesWithoutChangesAcknowledged);
        TourStep step = record.tour().step(currentStepId).orElseThrow();
        StepProgress progress = record.progress(step.id());
        List<ReviewAnnotation> onStep = stepFindings(step);
        boolean refreshSent = progress.stale() && view.selectedScope()
                .map(selected -> tourRefreshDispatch.claimed(selected.id(), record.tour().diffFingerprint()))
                .orElse(false);
        StepView stepView = new StepView(step, record.tour().number(step.id()), record.tour().steps().size(),
                progress, StepGate.unmet(step, progress, onStep, record), refreshSent);
        diffColumn.setStepHeader(Optional.of("step " + stepView.number() + " of " + stepView.total() + " · "
                + step.title()));
        List<ReviewAnnotation> findings = view.selectedScope().map(view::visibleFindings).orElse(List.of());
        if (TourFindings.needsBanner(record, findings)) {
            List<ReviewAnnotation> blockers = TourFindings.blockers(findings);
            if (!blockers.equals(shownBanner) || record.shelved() != shownBannerShelved) {
                stepPanel.showBanner(blockers, record.shelved());
                shownBanner = blockers;
                shownBannerShelved = record.shelved();
                shownStepView = null;
                shownTriage = null;
                shownImpact = null;
            }
        } else {
            if (shownBanner != null || !stepView.equals(shownStepView)) {
                stepPanel.show(stepView);
                shownStepView = stepView;
                shownBanner = null;
            }
            List<ReviewAnnotation> proposals = onStep.stream()
                    .filter(finding -> finding.triage() == Triage.PROPOSED)
                    .toList();
            if (!proposals.equals(shownTriage)) {
                stepPanel.showTriage(proposals);
                shownTriage = proposals;
            }
            StepPanel.ImpactView impactView = impactView(record, step);
            if (!impactView.equals(shownImpact)) {
                stepPanel.showImpact(impactView);
                shownImpact = impactView;
            }
        }
        if (!tourMarksShown || !record.equals(shownMarksRecord) || !currentStepId.equals(shownMarksStepId)
                || anchorIndex != shownMarksAnchor) {
            diffColumn.setStepMarkSource(new LiveTourMarks(record, currentStepId, anchorIndex));
            tourMarksShown = true;
            shownMarksRecord = record;
            shownMarksStepId = currentStepId;
            shownMarksAnchor = anchorIndex;
        }
        view.showKeyHints(TourKeyHints.hintsFor(TourKeyHints.contextOf(step, progress),
                (int) step.anchors().stream().filter(TourAnchor::hasNote).count()));
        renderTourVerdictBar(record, step, progress);
        syncTourVerdicts(record);
    }

    /**
     * What the step panel's impact section shows for {@code step}: the
     * agent's notes always, the measured part once {@link #impacts} has been
     * computed for this board (the previous scan's while a newer one is
     * being folded in), and the callee resolutions that have landed.
     */
    private StepPanel.ImpactView impactView(TourRecord record, TourStep step) {
        Optional<ReviewScope> scope = view.selectedScope();
        Optional<UnifiedDiff> diff = view.loadedDiff();
        ChangeGraph graph = scope.flatMap(each -> view.graph(each.id())).orElse(null);
        if (scope.isEmpty() || diff.isEmpty()) {
            return new StepPanel.ImpactView(step.impactNotes(), NO_IMPACT, Map.of(), true, Optional.empty(), true);
        }
        String scopeId = scope.get().id();
        if (graph == null) {
            Optional<String> failure = view.graphFailure(scopeId, diff.get());
            if (failure.isPresent()) {
                // No graph is coming for this diff: say why, never "Finding callers…" for good.
                StepImpact unparsed = new StepImpact(List.of(), List.of(), List.of(), List.of(),
                        Optional.of("the change could not be parsed: " + failure.get()));
                return new StepPanel.ImpactView(step.impactNotes(), unparsed, Map.of(), false, Optional.empty(),
                        true);
            }
            return new StepPanel.ImpactView(step.impactNotes(), NO_IMPACT, Map.of(), true, Optional.empty(), true);
        }
        OutOfDiffFanIn.Result fanIn = view.fanIn(scopeId).orElse(CALLERS_PENDING);
        ImpactEntry entry = impacts;
        if (entry == null || !entry.computedFrom(scopeId, diff.get(), graph, fanIn, record.tour())) {
            requestImpacts(new ImpactEntry(scopeId, diff.get(), graph, fanIn, record.tour(), Map.of(), Set.of()));
        }
        if (entry == null || !entry.sameBoard(scopeId, diff.get(), graph, record.tour())) {
            return new StepPanel.ImpactView(step.impactNotes(), NO_IMPACT, Map.of(), true, Optional.empty(), true);
        }
        StepImpact measured = entry.byStep().getOrDefault(step.id(), NO_IMPACT);
        Optional<String> calleesUnavailable = view.navigation().isEmpty()
                ? Optional.of("no checkout to search")
                : Optional.empty();
        Map<String, Optional<UsageProvider.Usage>> callees = new HashMap<>();
        if (calleesUnavailable.isEmpty()) {
            resolveCallees(scopeId, diff.get(), measured.calleesToResolve());
            for (String name : measured.calleesToResolve()) {
                Optional<UsageProvider.Usage> resolved = calleeResolutions.get(name);
                if (resolved != null) {
                    callees.put(name, resolved);
                }
            }
        }
        return new StepPanel.ImpactView(step.impactNotes(), measured, callees, entry.fanIn() == CALLERS_PENDING,
                calleesUnavailable, entry.declaring().contains(step.id()));
    }

    /** Computes every step's impact for {@code key}'s inputs off the FX thread, unless that is already running. */
    private void requestImpacts(ImpactEntry key) {
        ImpactEntry running = impactsInFlight;
        if (running != null
                && running.computedFrom(key.scopeId(), key.diff(), key.graph(), key.fanIn(), key.tour())) {
            return;
        }
        impactsInFlight = key;
        CompletableFuture
                .supplyAsync(() -> {
                    Map<String, StepImpact> byStep = new HashMap<>();
                    Set<String> declaring = new HashSet<>();
                    AnchorIndex index = AnchorIndex.of(key.diff());
                    for (TourStep each : key.tour().steps()) {
                        byStep.put(each.id(), StepImpact.of(each, key.tour(), key.diff(), key.graph(), key.fanIn()));
                        boolean declares = key.graph().declarationSites().stream()
                                .anyMatch(site -> each.anchors().stream()
                                        .anyMatch(anchor -> index.contains(anchor, site.file(), site.lineKey())));
                        if (declares) {
                            declaring.add(each.id());
                        }
                    }
                    return new ImpactEntry(key.scopeId(), key.diff(), key.graph(), key.fanIn(), key.tour(),
                            Map.copyOf(byStep), Set.copyOf(declaring));
                }, impactExecutor)
                .whenComplete((computedEntry, failure) -> {
                    if (closed) {
                        return;
                    }
                    Platform.runLater(() -> {
                        if (closed || impactsInFlight != key) {
                            return;
                        }
                        impactsInFlight = null;
                        if (failure == null) {
                            impacts = computedEntry;
                            render();
                            return;
                        }
                        LOG.log(Level.WARNING, "Could not measure the tour's impact for scope "
                                + key.scopeId(), failure);
                        StepImpact unmeasured = new StepImpact(List.of(), List.of(), List.of(), List.of(),
                                Optional.of("the impact could not be measured: " + UiErrors.message(failure)));
                        Map<String, StepImpact> computed = new HashMap<>();
                        Set<String> everyStep = new HashSet<>();
                        for (TourStep each : key.tour().steps()) {
                            computed.put(each.id(), unmeasured);
                            everyStep.add(each.id());
                        }
                        // Unknown what each step declares: every one says why it is unmeasured.
                        impacts = new ImpactEntry(key.scopeId(), key.diff(), key.graph(), key.fanIn(),
                                key.tour(), Map.copyOf(computed), Set.copyOf(everyStep));
                        render();
                    });
                });
    }

    /**
     * Resolves the callees not yet resolved or resolving, through the
     * lexical {@link UsageProvider}, off the FX thread. Each landing is
     * recorded on the FX thread and the tour re-rendered once per batch of
     * landings, so the rows turn from "resolving…" as they arrive.
     */
    private void resolveCallees(String scopeId, UnifiedDiff diff, List<String> names) {
        if (!scopeId.equals(calleeScopeId) || calleeDiff != diff) {
            calleeScopeId = scopeId;
            calleeDiff = diff;
            calleeResolutions.clear();
            calleesResolving.clear();
        }
        List<String> wanted = names.stream()
                .filter(name -> !calleeResolutions.containsKey(name) && !calleesResolving.contains(name))
                .toList();
        Optional<ReviewNavigation> navigation = view.navigation();
        if (wanted.isEmpty() || navigation.isEmpty()) {
            return;
        }
        ReviewNavigation nav = navigation.get();
        UsageProvider provider = new LexicalUsageProvider(new SymbolPeekService(nav.root(), nav.search()),
                view.changedLinesOfReviewDiff());
        for (String name : wanted) {
            calleesResolving.add(name);
            provider.declaration(name).whenComplete((found, failure) -> {
                if (closed) {
                    return;
                }
                Platform.runLater(() -> {
                    if (closed || !scopeId.equals(calleeScopeId) || calleeDiff != diff) {
                        return;
                    }
                    calleesResolving.remove(name);
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not resolve the declaration of " + name, failure);
                    }
                    calleeResolutions.put(name, failure == null ? found : Optional.empty());
                    if (!calleeRenderQueued) {
                        calleeRenderQueued = true;
                        Platform.runLater(() -> {
                            calleeRenderQueued = false;
                            if (!closed) {
                                render();
                            }
                        });
                    }
                });
            });
        }
    }

    /** The selected scope's findings, minus withheld ones, that lie on {@code step} in the review diff. */
    private List<ReviewAnnotation> stepFindings(TourStep step) {
        Optional<ReviewScope> scope = view.selectedScope();
        Optional<UnifiedDiff> diff = view.loadedDiff();
        if (scope.isEmpty() || diff.isEmpty()) {
            return List.of();
        }
        return TourFindings.onStep(step, view.visibleFindings(scope.get()), AnchorIndex.of(diff.get()));
    }

    /**
     * The verdict bar in tour mode: the current step as its unit, progress
     * in steps decided -- passed, overridden or sent back with changes
     * requested, and not stale ({@link TourRecord#unsettledSteps}, which
     * {@code n} walks too). A step with changes requested is not left to
     * review; whether Submit accepts the tour stays the submit path's
     * own rule, not this count. The bar only labels the step; its buttons
     * route back through the view's verdict host, whose tour branches act
     * on the current step and never read the target.
     */
    private void renderTourVerdictBar(TourRecord record, TourStep step, StepProgress progress) {
        ReviewVerdictBar.Target target = new ReviewVerdictBar.Target("tour:" + step.id(),
                record.tour().number(step.id()) + " · " + step.title());
        Optional<ReviewVerdict.Decision> decision = switch (progress.decision()) {
            case PASSED, OVERRIDDEN -> Optional.of(ReviewVerdict.Decision.APPROVED);
            case CHANGES -> Optional.of(ReviewVerdict.Decision.CHANGES);
            case NONE -> Optional.empty();
        };
        int total = record.tour().steps().size();
        view.showStepOnVerdictBar(target, decision, total - record.unsettledSteps().size(), total);
    }

    /** Files of the review diff with no hunk at all (mode or binary changes); spec §3's footer. */
    private int filesWithoutLineChanges() {
        return view.loadedDiff().map(diff -> (int) diff.files().stream()
                        .filter(file -> file.hunks().isEmpty())
                        .count())
                .orElse(0);
    }

    private Optional<String> firstUnsettled(TourRecord record) {
        return record.unsettledSteps().stream().map(TourStep::id).findFirst();
    }

    // ---- verdict sync and migration -------------------------------------------

    /**
     * Writes the hunk verdicts the tour derives. Derived from the REVIEW
     * diff only -- never the whole-file display diff -- because digests and
     * verdicts are keyed by it.
     */
    private void syncTourVerdicts(TourRecord record) {
        view.loadedDiff().ifPresent(diff -> view.selectedScope().ifPresent(scope -> {
            AnchorIndex index = AnchorIndex.of(diff);
            TourRecord seeded = seedPreTourVerdicts(scope, record, index);
            host.applyTourVerdicts(scope, StepVerdicts.derive(seeded, index));
        }));
    }

    /**
     * Verdicts set in the hunk diff before the scope had a tour would be
     * cleared by the first derivation, which covers every hunk. So the first
     * time a tour is synced, each stored verdict is carried over as a hunk
     * override -- the same thing a hunk-diff verdict set while the tour
     * exists becomes.
     *
     * <p>"First time" is the record's persisted {@link TourRecord#seeded}
     * flag, set in the same store write as the seeds, so it holds across a
     * restart and a fresh view. A record seeded already -- including a
     * re-posted tour, which {@code review_tour} marks seeded because the
     * verdicts stored then are the previous tour's derivations -- is never
     * seeded again: a step passed, its hunks derived APPROVED, then undone
     * with {@code u} would otherwise look exactly like a fresh tour over
     * human verdicts. A record from before the flag existed that already
     * has overrides or a decided step is marked seeded without seeds, for
     * the same reason.</p>
     */
    private TourRecord seedPreTourVerdicts(ReviewScope scope, TourRecord record, AnchorIndex index) {
        if (record.seeded()) {
            return record;
        }
        boolean untouched = record.hunkOverrides().isEmpty() && record.progress().values().stream()
                .allMatch(progress -> progress.decision() == StepProgress.Decision.NONE);
        Map<String, HunkOverride> seeds = new LinkedHashMap<>();
        if (untouched) {
            for (AnchorIndex.HunkRef hunk : index.hunks()) {
                // A human's decisions only; an automatic approval is not one.
                host.verdict(scope, hunk.digest())
                        .filter(verdict -> verdict.decision() == ReviewVerdict.Decision.APPROVED
                                || verdict.decision() == ReviewVerdict.Decision.CHANGES)
                        .ifPresent(verdict -> seeds.put(hunk.digest(),
                                new HunkOverride(verdict.decision(), "set in the hunk diff before the tour")));
            }
        }
        host.updateTour(scope, current -> {
            if (current.seeded()) {
                return current;
            }
            TourRecord next = current.withSeeded(true);
            for (Map.Entry<String, HunkOverride> seed : seeds.entrySet()) {
                next = next.withHunkOverride(seed.getKey(), Optional.of(seed.getValue()));
            }
            return next;
        });
        return host.tour(scope).orElse(record);
    }

    /**
     * Carries {@code scopeId}'s stored tour onto a review diff that moved
     * under it ({@link TourMigration}), through the tour store's one writer,
     * then asks the agent to re-issue what is stale ({@link
     * #requestTourRefresh}).
     *
     * <p>Only a NEW diff instance for the scope is migrated onto: a scope
     * flip or a re-selection republishes the diff it already had, and a tour
     * the agent posted since then was validated against a fresher diff than
     * that one -- migrating it back would undo it. A diff with untracked
     * files filtered out is not the review diff the agent sees, so it is
     * skipped entirely; turning them back on publishes the real one.</p>
     */
    void migrateTour(String scopeId, UnifiedDiff diff) {
        boolean newDiff = tourCheckedDiffByScope.get(scopeId) != diff;
        tourCheckedDiffByScope.put(scopeId, diff);
        Optional<ReviewScope> scope = view.scopeById(scopeId);
        if (scope.isEmpty() || diffColumn.hidesUntracked(scopeId)) {
            return;
        }
        String fingerprint = TourFingerprint.of(diff);
        boolean moved = host.tour(scope.get())
                .map(record -> !record.tour().diffFingerprint().equals(fingerprint))
                .orElse(false);
        if (newDiff && moved) {
            host.updateTour(scope.get(), record -> record.tour().diffFingerprint().equals(fingerprint)
                    ? record
                    : TourMigration.migrate(record, diff).record());
        }
        requestTourRefresh(scope.get(), fingerprint, diff);
    }

    /**
     * Asks the agent -- once per (scope, diff) -- to re-issue the stale steps
     * of a tour current for {@code diff} and cover its uncovered hunks.
     *
     * <p>Gated like the automatic recheck: an inline harness is never asked
     * unprompted, and a busy agent is not interrupted. A gated request takes
     * no claim, so the next publish of the diff asks again; a hand-off that
     * fails releases its claim for the same reason.</p>
     */
    private void requestTourRefresh(ReviewScope scope, String fingerprint, UnifiedDiff diff) {
        Optional<TourRecord> current = host.tour(scope)
                .filter(record -> record.tour().diffFingerprint().equals(fingerprint));
        if (current.isEmpty()) {
            return;
        }
        TourRecord record = current.get();
        List<String> stale = record.tour().steps().stream()
                .map(TourStep::id)
                .filter(id -> record.progress(id).stale())
                .toList();
        List<String> uncovered = TourMigration.uncoveredHunkIds(record, AnchorIndex.of(diff));
        if (stale.isEmpty() && uncovered.isEmpty()) {
            return;
        }
        if (!host.supportsAutomaticRecheck(scope) || host.agentActivity(scope) == SessionActivity.BUSY) {
            return;
        }
        if (tourRefreshDispatch.claim(scope.id(), fingerprint)
                && !host.dispatchTourRefresh(scope, stale, uncovered.size())) {
            tourRefreshDispatch.release(scope.id(), fingerprint);
        }
    }

    /**
     * A human's request to bring the tour onto the current diff: re-issue
     * the stale steps and cover the uncovered hunks. Sent whatever the
     * automatic gating ({@link #requestTourRefresh}) would say -- the click
     * is the authorisation -- and it takes the per-diff claim, so the
     * automatic path does not ask a second time. A hand-off that fails
     * releases the claim -- only one this call took: a claim the automatic
     * path already holds stands for a request still out, and a failed retry
     * must not take it back. True when the request was handed over.
     */
    boolean askForTourRefresh(ReviewScope scope) {
        Optional<UnifiedDiff> diff = view.loadedDiff();
        Optional<TourRecord> tour = host.tour(scope);
        if (diff.isEmpty() || tour.isEmpty()) {
            return false;
        }
        TourRecord record = tour.get();
        String fingerprint = TourFingerprint.of(diff.get());
        List<String> stale = record.tour().steps().stream()
                .map(TourStep::id)
                .filter(id -> record.progress(id).stale())
                .toList();
        int uncovered = TourMigration.uncoveredHunkIds(record, AnchorIndex.of(diff.get())).size();
        boolean took = tourRefreshDispatch.claim(scope.id(), fingerprint);
        if (host.dispatchTourRefresh(scope, stale, uncovered)) {
            return true;
        }
        if (took) {
            tourRefreshDispatch.release(scope.id(), fingerprint);
        }
        return false;
    }

    // ---- keys and the verdict bar's tour branches ------------------------------

    /**
     * The tour's keys, ahead of the hunk diff's table. Only in TOUR mode;
     * there {@code ⇧F} -- the findings margin's key -- is inert, and so are
     * the step keys while no
     * tour has arrived, rather than acting on a hunk-diff cursor nobody can
     * see.
     */
    boolean handleShortcut(KeyEvent event) {
        if (!view.touring()) {
            return false;
        }
        switch (event.getCode()) {
            case V -> {
                view.toggleMode();
                return true;
            }
            case F -> {
                return event.isShiftDown();
            }
            case D -> {
                // ⇧D is the search rail's scope here; plain d stays density.
                if (event.isShiftDown()) {
                    view.toggleSearchScope();
                    return true;
                }
            }
            case B -> {
                backToStep();
                return true;
            }
            case H -> {
                view.toggleKeyHints();
                return true;
            }
            case PERIOD -> {
                moveAnchor(1);
                return true;
            }
            case COMMA -> {
                moveAnchor(-1);
                return true;
            }
            default -> { }
        }
        int digit = switch (event.getCode()) {
            case DIGIT1 -> 1;
            case DIGIT2 -> 2;
            case DIGIT3 -> 3;
            case DIGIT4 -> 4;
            default -> 0;
        };
        boolean stepKey = digit > 0 || switch (event.getCode()) {
            case A, R, U, N, OPEN_BRACKET, CLOSE_BRACKET -> true;
            default -> false;
        };
        if (!stepKey) {
            return false;
        }
        Optional<TourRecord> tour = currentTour();
        if (tour.isEmpty()) {
            return true;
        }
        TourRecord record = tour.get();
        if (digit > 0) {
            // A peek card covers the panel: a digit must not answer a check
            // the reader cannot see.
            if (!view.peekOpen() && !stepPanel.answerByKey(digit)) {
                jumpToClaim(record, digit - 1);
            }
            return true;
        }
        switch (event.getCode()) {
            case A -> {
                // ⇧A approves a whole file in the hunk diff; a step has no file to widen to.
                if (!event.isShiftDown()) {
                    passCurrentStep(record);
                }
            }
            case R -> {
                if (!event.isShiftDown()) {
                    decideCurrentStep(StepProgress.Decision.CHANGES, Optional.empty());
                }
            }
            case U -> decideCurrentStep(StepProgress.Decision.NONE, Optional.empty());
            case N -> firstUnsettled(record).ifPresent(this::selectStep);
            case OPEN_BRACKET -> moveStep(record, -1);
            case CLOSE_BRACKET -> moveStep(record, 1);
            default -> { }
        }
        return true;
    }

    /** The verdict bar's Approve in the tour: {@link #passCurrentStep}. */
    void approveCurrentStep() {
        currentTour().ifPresent(this::passCurrentStep);
    }

    /** The verdict bar's Request changes (or undo, with {@code NONE}) in the tour. */
    void decideFromBar(StepProgress.Decision decision) {
        if (currentTour().isPresent()) {
            decideCurrentStep(decision, Optional.empty());
        }
    }

    /** The verdict bar's next-unsettled in the tour. */
    void selectFirstUnsettled() {
        currentTour().flatMap(this::firstUnsettled).ifPresent(this::selectStep);
    }

    /** The verdict bar's ‹ › in the tour. */
    void moveStep(int delta) {
        currentTour().ifPresent(record -> moveStep(record, delta));
    }

    /** The step jump of {@link SessionReviewView#refuseSubmitFromTour}; false when there is no step to jump to. */
    boolean jumpForSubmitRefusal(List<String> digests, boolean staleBase) {
        Optional<TourRecord> tour = currentTour();
        Optional<String> target = tour.flatMap(record -> {
            Optional<String> covering = staleBase
                    ? record.tour().steps().stream()
                            .map(TourStep::id)
                            .filter(id -> record.progress(id).hunkDigests().stream().anyMatch(digests::contains))
                            .findFirst()
                    : Optional.empty();
            return covering.or(() -> firstUnsettled(record));
        });
        target.ifPresent(this::selectStep);
        return target.isPresent();
    }

    /**
     * {@code a}: passes the current step only when nothing stands between it
     * and passing (spec §5); otherwise focus moves to the first unmet
     * requirement, so the key always does something visible.
     */
    private void passCurrentStep(TourRecord record) {
        Optional<TourStep> step = record.tour().step(currentStepId);
        if (step.isEmpty()) {
            return;
        }
        if (view.selectedScope().map(scope -> TourFindings.needsBanner(record, view.visibleFindings(scope)))
                .orElse(false)) {
            // The banner stands instead of the step: there is nothing to pass yet.
            stepPanel.focusBanner();
            return;
        }
        if (!stepRendered(step.get())) {
            stepPanel.showTransient(SessionReviewView.STEP_NOT_RENDERED);
            return;
        }
        List<ReviewAnnotation> findings = stepFindings(step.get());
        Optional<StepGate.Unmet> unmet = StepGate.unmet(step.get(), record.progress(step.get().id()), findings,
                record);
        if (unmet.isPresent()) {
            stepPanel.focusUnmet(unmet.get());
            return;
        }
        // The gate again, on the record as it is when written: `record` is a
        // snapshot, and a check reset by a re-issue or a verdict that landed
        // since must not be passed over.
        String stepId = step.get().id();
        updateCurrentTour(current -> current.tour().step(stepId)
                .filter(now -> StepGate.unmet(now, current.progress(stepId), findings, current).isEmpty())
                .map(now -> current.withProgress(current.progress(stepId)
                        .withDecision(StepProgress.Decision.PASSED, Optional.empty())))
                .orElse(current));
        currentTour().flatMap(this::firstUnsettled).ifPresent(this::selectStep);
    }

    private void decideCurrentStep(StepProgress.Decision decision, Optional<String> reason) {
        String stepId = currentStepId;
        if (stepId == null) {
            return;
        }
        updateCurrentTour(record -> record.tour().step(stepId).isEmpty()
                ? record
                : record.withProgress(record.progress(stepId).withDecision(decision, reason)));
    }

    /**
     * "Approve without passing" on the current step, with the reviewer's
     * reason.
     *
     * <p>A stale step's progress is keyed to the digests of hunks that have
     * since changed, so an override recorded as it stands would decide
     * nothing: the step would count as unsettled and its verdicts would
     * name hunks the diff no longer has. The reviewer is approving the code
     * as it is now -- the whole file is on screen -- so the override re-keys
     * the step to the hunks its anchors cover in the current review diff and
     * clears the stale mark, and the agent is no longer asked to re-issue
     * it. When an anchor no longer resolves there is no "code as it is now"
     * to approve, and the panel says so instead.</p>
     */
    private void overrideCurrentStep(String reason) {
        String stepId = currentStepId;
        Optional<TourRecord> tour = currentTour();
        if (stepId == null || tour.isEmpty() || tour.get().tour().step(stepId).isEmpty()) {
            return;
        }
        Optional<UnifiedDiff> diff = view.loadedDiff();
        if (tour.get().progress(stepId).stale()) {
            TourStep step = tour.get().tour().step(stepId).orElseThrow();
            if (diff.isEmpty() || !step.anchors().stream().allMatch(AnchorIndex.of(diff.get())::resolves)) {
                stepPanel.showTransient(SessionReviewView.STALE_STEP_GONE);
                return;
            }
        }
        if (!stepRendered(tour.get().tour().step(stepId).orElseThrow())) {
            stepPanel.showTransient(SessionReviewView.STEP_NOT_RENDERED);
            return;
        }
        updateCurrentTour(record -> record.tour().step(stepId).map(step -> {
            StepProgress now = record.progress(stepId);
            if (!now.stale()) {
                return record.withProgress(now.withDecision(StepProgress.Decision.OVERRIDDEN, Optional.of(reason)));
            }
            AnchorIndex index = AnchorIndex.of(diff.orElseThrow());
            if (!step.anchors().stream().allMatch(index::resolves)) {
                return record;
            }
            return record.withProgress(new StepProgress(stepId, StepProgress.fresh(step, index).hunkDigests(),
                    now.checks(), StepProgress.Decision.OVERRIDDEN, Optional.of(reason), false));
        }).orElse(record));
    }

    /**
     * Whether every changed row {@code step} anchors has a row on screen. The
     * whole-file view truncates at the column's row cap, and passing or
     * overriding a step is a claim the reader saw its code -- the rule the
     * hunk diff's settle follows too ({@link SessionReviewView#HUNK_NOT_RENDERED}).
     * Rows are taken from the review diff; their line keys are the same in
     * the whole-file diff the column renders.
     */
    private boolean stepRendered(TourStep step) {
        Optional<UnifiedDiff> diff = view.loadedDiff();
        if (diff.isEmpty()) {
            return false;
        }
        AnchorIndex index = AnchorIndex.of(diff.get());
        List<String> keys = index.changedRows().stream()
                .filter(row -> step.anchors().stream()
                        .anyMatch(anchor -> index.contains(anchor, row.file(), row.lineKey())))
                .map(row -> row.file() + " " + row.lineKey())
                .toList();
        return diffColumn.rendersLines(keys);
    }

    /** Applies {@code transform} to the selected scope's tour, then re-renders it (and so re-syncs verdicts). */
    private void updateCurrentTour(UnaryOperator<TourRecord> transform) {
        view.selectedScope().ifPresent(scope -> host.updateTour(scope, transform));
        render();
    }

    // ---- step navigation -------------------------------------------------------

    void selectStep(String stepId) {
        showStep(stepId, true);
    }

    /**
     * Makes {@code stepId} current and reveals its first anchor. {@code
     * pushWaypoint} is false only when the trail itself is the one moving
     * -- a walk along it must not add to it.
     */
    void showStep(String stepId, boolean pushWaypoint) {
        Optional<TourRecord> tour = currentTour();
        Optional<TourStep> step = tour.flatMap(record -> record.tour().step(stepId));
        if (step.isEmpty()) {
            return;
        }
        currentStepId = stepId;
        anchorIndex = 0;
        stepPanel.hideBackPill();
        if (pushWaypoint) {
            pushStepWaypoint(tour.get(), step.get());
        }
        render(tour);
        revealAnchor(step.get(), 0);
    }

    private void moveStep(TourRecord record, int delta) {
        List<TourStep> steps = record.tour().steps();
        int index = Math.max(0, record.tour().number(currentStepId) - 1);
        selectStep(steps.get(Math.clamp(index + delta, 0, steps.size() - 1)).id());
    }

    private void revealAnchor(TourStep step, int anchorIndex) {
        if (anchorIndex >= 0 && anchorIndex < step.anchors().size()) {
            TourAnchor anchor = step.anchors().get(anchorIndex);
            diffColumn.revealLine(anchor.file(), anchor.startKey());
        }
    }

    /** Back in the tour after {@code v}: the current step's first anchor. */
    void revealCurrentStep() {
        currentTour().flatMap(record -> record.tour().step(currentStepId))
                .ifPresent(step -> revealAnchor(step, 0));
    }

    /**
     * The step a "Step N" waypoint names, when it still does: a number alone
     * could name a different step of a re-posted tour, so the step only
     * counts when its first anchor is still the waypoint's file.
     */
    Optional<TourStep> stepOfWaypoint(NavigationTrail.Waypoint waypoint) {
        if (!waypoint.label().startsWith("Step ")) {
            return Optional.empty();
        }
        int number;
        try {
            number = Integer.parseInt(waypoint.label().substring("Step ".length()).strip());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return currentTour().map(record -> record.tour().steps())
                .filter(steps -> number >= 1 && number <= steps.size())
                .map(steps -> steps.get(number - 1))
                .filter(step -> !step.anchors().isEmpty()
                        && Path.of(step.anchors().getFirst().file()).equals(waypoint.file()));
    }

    private void pushStepWaypoint(TourRecord record, TourStep step) {
        if (step.anchors().isEmpty()) {
            return;
        }
        TourAnchor anchor = step.anchors().getFirst();
        view.pushWaypoint(Path.of(anchor.file()), "Step " + record.tour().number(step.id()),
                lineOf(anchor.startKey()), Optional.of(anchor.startKey()));
    }

    /** {@code n12} / {@code o12} -> 12; the waypoint's file line, a fallback to its key. */
    private static int lineOf(String lineKey) {
        try {
            return Integer.parseInt(lineKey.substring(1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return 1;
        }
    }

    /**
     * Shows the "↩ back to step N" pill. Shown after a navigation that
     * leaves the step rather than by measuring which rows the virtualized
     * list has on screen, which it does not report reliably.
     */
    void leftStep() {
        if (!view.touring() || currentStepId == null) {
            return;
        }
        currentTour().ifPresent(record -> stepPanel.showBackPill(record.tour().number(currentStepId)));
    }

    /** {@code b}: the current step's first anchor, and the pill goes. */
    private void backToStep() {
        currentTour().flatMap(record -> record.tour().step(currentStepId)).ifPresent(step -> {
            anchorIndex = 0;
            stepPanel.hideBackPill();
            revealAnchor(step, 0);
        });
    }

    /** {@code .} / {@code ,}: the next / previous anchor of the current step, wrapping. */
    private void moveAnchor(int delta) {
        currentTour().flatMap(record -> record.tour().step(currentStepId)).ifPresent(step -> {
            int count = step.anchors().size();
            if (count == 0) {
                return;
            }
            anchorIndex = Math.floorMod(anchorIndex + delta, count);
            stepPanel.hideBackPill();
            render(currentTour());
            revealAnchor(step, anchorIndex);
        });
    }

    /**
     * A digit with no check to answer: the step's claim of that number, when
     * the step makes claims. Without notes there is nothing numbered on screen
     * for the digit to name, so it stays inert as it always was.
     */
    private void jumpToClaim(TourRecord record, int index) {
        record.tour().step(currentStepId).ifPresent(step -> {
            if (index < step.anchors().size() && step.anchors().stream().anyMatch(TourAnchor::hasNote)) {
                anchorIndex = index;
                stepPanel.hideBackPill();
                render(Optional.of(record));
                revealAnchor(step, index);
            }
        });
    }

    /**
     * A click on a claim's callout: that claim becomes the active one. The
     * viewport stays put -- the callout the reader clicked is already on screen.
     */
    private void selectClaim(int index) {
        currentTour().flatMap(record -> record.tour().step(currentStepId)).ifPresent(step -> {
            if (index >= 0 && index < step.anchors().size()) {
                anchorIndex = index;
                stepPanel.hideBackPill();
                render(currentTour());
            }
        });
    }

    // ---- diagnostics -----------------------------------------------------------

    /** Which of the current step's anchors was last revealed. */
    int anchorIndex() {
        return anchorIndex;
    }

    /** The step the tour is on, or null. */
    String currentStepId() {
        return currentStepId;
    }

    // ---- collaborators ---------------------------------------------------------

    /**
     * Step marks for the rows the column actually renders. Built lazily per
     * rendered diff, because the whole-file display diff lands after the
     * mode switch and its rows differ from the review diff's.
     */
    private final class LiveTourMarks implements ReviewDiffColumn.StepMarkSource {
        private final TourRecord record;
        private final String stepId;
        private final int activeAnchor;
        private UnifiedDiff builtFor;
        private TourMarks marks = TourMarks.none();

        LiveTourMarks(TourRecord record, String stepId, int activeAnchor) {
            this.record = record;
            this.stepId = stepId;
            this.activeAnchor = activeAnchor;
        }

        @Override
        public Optional<StepMark> markAt(String file, String lineKey) {
            UnifiedDiff rendered = diffColumn.renderedDiff();
            if (rendered == null) {
                return Optional.empty();
            }
            if (rendered != builtFor) {
                marks = TourMarks.of(record, rendered, stepId, activeAnchor);
                builtFor = rendered;
            }
            return marks.markAt(file, lineKey);
        }
    }

    /** The step panel's window onto the tour, with the scope filled in. */
    private final class StepHost implements StepPanel.Host {
        @Override
        public void answerChoice(String checkId, int choiceIndex) {
            updateCurrentTour(record -> record.tour().stepOfCheck(checkId)
                    .flatMap(step -> step.check(checkId).map(check -> {
                        StepProgress p = record.progress(step.id());
                        return record.withProgress(p.withCheck(
                                StepGrading.answerChoice(check, p.check(check.id()), choiceIndex)));
                    }))
                    .orElse(record));
        }

        @Override
        public void submitRisk(String checkId, String answer) {
            updateCurrentTour(record -> record.tour().stepOfCheck(checkId)
                    .map(step -> {
                        StepProgress p = record.progress(step.id());
                        return record.withProgress(p.withCheck(
                                StepGrading.submitRisk(p.check(checkId), answer)));
                    })
                    .orElse(record));
            enqueueIfAwaiting(checkId);
        }

        @Override
        public void override(String reason) {
            overrideCurrentStep(reason);
        }

        @Override
        public void requestRefresh() {
            Optional<ReviewScope> scope = view.selectedScope();
            if (scope.isEmpty()) {
                return;
            }
            stepPanel.showTransient("Asking the agent to refresh the tour…");
            if (askForTourRefresh(scope.get())) {
                render();
                stepPanel.showTransient("Asked the agent to re-write the stale steps.");
            } else {
                stepPanel.showTransient("Could not reach this session's agent. Approve without passing, "
                        + "or settle these hunks in the hunk diff (v).");
            }
        }

        @Override
        public void postMessage(ReviewAnnotation finding, String body) {
            view.selectedScope().ifPresent(scope -> {
                host.postMessage(scope, finding, body);
                view.refreshReviewState();
            });
        }

        @Override
        public void goToAnchor(int index) {
            currentTour().flatMap(record -> record.tour().step(currentStepId)).ifPresent(step -> {
                anchorIndex = index;
                stepPanel.hideBackPill();
                render(currentTour());
                revealAnchor(step, index);
            });
        }

        @Override
        public void backToStep() {
            TourController.this.backToStep();
        }

        @Override
        public void openLocation(String file, int line) {
            Path relativePath;
            try {
                relativePath = Path.of(file);
            } catch (InvalidPathException e) {
                // Agent-supplied text: say so rather than fail silently.
                view.notice("Cannot open " + file + ": not a file path");
                return;
            }
            view.openLocationPeek(relativePath, line);
        }

        @Override
        public void selectStep(String stepId) {
            TourController.this.selectStep(stepId);
        }

        @Override
        public void retryRisk(String checkId) {
            updateCurrentTour(record -> record.tour().stepOfCheck(checkId)
                    .map(step -> {
                        StepProgress p = record.progress(step.id());
                        return record.withProgress(p.withCheck(StepGrading.retryRisk(p.check(checkId))));
                    })
                    .orElse(record));
            enqueueIfAwaiting(checkId);
        }

        @Override
        public void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason) {
            view.selectedScope().ifPresent(scope -> {
                view.triageFinding(scope, finding, triage, reason);
                // The real host refreshes on the store write as well; the
                // panel must not depend on that to drop a triaged finding.
                view.refreshReviewState();
            });
        }

        @Override
        public void revealFinding(ReviewAnnotation finding) {
            diffColumn.revealLine(finding.file(), finding.startKey());
        }

        @Override
        public void sendBack(List<ReviewAnnotation> confirmedBlockers) {
            Optional<ReviewScope> scope = view.selectedScope();
            if (scope.isEmpty()) {
                return;
            }
            if (host.sendFindingsToAuthor(scope.get(), confirmedBlockers)) {
                host.updateTour(scope.get(), record -> record.withShelved(true));
                view.refreshReviewState();
            } else {
                stepPanel.showTransient("No session to send them to: the author's agent is not running here.");
            }
        }

        @Override
        public void reviewAnyway() {
            updateCurrentTour(record -> record.withReviewAnyway(true));
        }
    }

    /** Hands a check the reviewer just answered to the risk queue, if it really is awaiting the agent. */
    private void enqueueIfAwaiting(String checkId) {
        Optional<ReviewScope> scope = view.selectedScope();
        if (scope.isPresent() && checkStatus(currentTour(), checkId)
                .filter(status -> status == CheckProgress.Status.AWAITING_AGENT).isPresent()) {
            riskQueue.enqueue(scope.get().id(), checkId);
        }
    }

    private static Optional<CheckProgress.Status> checkStatus(Optional<TourRecord> record, String checkId) {
        return record.flatMap(r -> r.tour().stepOfCheck(checkId)
                .map(step -> r.progress(step.id()).check(checkId).status()));
    }

    /** Maps the queue's scope ids back onto the scope the host knows. */
    private final class RiskDispatcher implements RiskCheckQueue.Dispatcher {
        @Override
        public SessionActivity activity(String scopeId) {
            return view.scopeById(scopeId).map(host::agentActivity).orElse(SessionActivity.UNKNOWN);
        }

        @Override
        public boolean dispatch(String scopeId, String checkId) {
            return view.scopeById(scopeId).map(scope -> host.dispatchRiskCheck(scope, checkId)).orElse(false);
        }

        @Override
        public void timedOut(String scopeId, String checkId) {
            view.scopeById(scopeId).ifPresent(scope -> {
                host.updateTour(scope, record -> record.tour().stepOfCheck(checkId)
                        .map(step -> {
                            StepProgress p = record.progress(step.id());
                            return record.withProgress(p.withCheck(
                                    StepGrading.markAgentUnavailable(p.check(checkId))));
                        })
                        .orElse(record));
                if (view.selectedScope().filter(selected -> selected.id().equals(scopeId)).isPresent()) {
                    render();
                }
            });
        }
    }
}
