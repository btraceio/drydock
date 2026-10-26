package app.drydock.intellij.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.util.Side;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inlay layer's decisions (T6, AC1/AC6/AC7's decoration half) through
 * fakes: two-side editor choice ({@code n<N>} RIGHT, {@code o<N>} LEFT),
 * unified line mapping through the oneside transfer, after-rediff timing,
 * the drift fallback and hint from T3, unresolved-findings policy
 * (CONFIRMED/DISMISSED keep their cards), coalesced and idempotent rebuilds
 * with explicit release of the previous inlays and highlighters, and the
 * EDT/lifecycle contracts. Headless: the platform adapters
 * ({@code hostFor}/{@code editorHost}) are the thin live-checked bridge.
 */
class ReviewDiffInlaysTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    // ---- fixtures -----------------------------------------------------------

    private static final class FakeEdt implements ReviewDiffInlays.EdtQueue {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void invokeLater(Runnable runnable) {
            queued.add(runnable);
        }

        void pump() {
            List<Runnable> run = List.copyOf(queued);
            queued.clear();
            run.forEach(Runnable::run);
        }
    }

    private static final class FakeInlay implements ReviewDiffInlays.Owned {
        boolean released;

        @Override
        public void release() {
            released = true;
        }
    }

    /** An inlay whose release fails the way a real teardown race can: the later handles must still be released. */
    private static final class ThrowingInlay implements ReviewDiffInlays.Owned {
        boolean released;

        @Override
        public void release() {
            released = true;
            throw new IllegalStateException("the inlay was already gone");
        }
    }

    private static final class FakeGutterMark implements ReviewDiffInlays.Owned {
        boolean removed;

        @Override
        public void release() {
            removed = true;
        }
    }

    /** One recorded decoration of one editor: the line, the finding, its drift hint, its handles. */
    private static final class Inserted {
        final int line;
        final ReviewAnnotation finding;
        final Optional<String> driftHint;
        final FakeInlay inlay = new FakeInlay();
        final FakeGutterMark mark = new FakeGutterMark();

        Inserted(int line, ReviewAnnotation finding, Optional<String> driftHint) {
            this.line = line;
            this.finding = finding;
            this.driftHint = driftHint;
        }
    }

    private static final class FakeEditor implements ReviewDiffInlays.EditorHost {
        final List<Inserted> inserted = new ArrayList<>();
        int lineCount = 100;

        @Override
        public int lineCount() {
            return lineCount;
        }

        @Override
        public ReviewDiffInlays.Owned insertCardAfter(int line, javax.swing.JComponent card) {
            RecordingCards.Card content = (RecordingCards.Card) card;
            Inserted entry = new Inserted(line, content.finding, content.driftHint);
            inserted.add(entry);
            return entry.inlay;
        }

        @Override
        public ReviewDiffInlays.Owned addGutterMark(int line, ReviewAnnotation finding) {
            return inserted.stream()
                    .filter(i -> i.line == line && i.finding.equals(finding))
                    .findFirst().orElseThrow().mark;
        }

        List<Integer> lines() {
            return inserted.stream().map(i -> i.line).toList();
        }
    }

    private static final class RecordingCards implements ReviewDiffInlays.CardFactory {
        final List<ReviewAnnotation> asked = new ArrayList<>();

        @Override
        public javax.swing.JComponent card(ReviewAnnotation finding, Optional<String> driftHint) {
            asked.add(finding);
            return new Card(finding, driftHint);
        }

        static final class Card extends JPanel {
            final ReviewAnnotation finding;
            final Optional<String> driftHint;

            Card(ReviewAnnotation finding, Optional<String> driftHint) {
                this.finding = finding;
                this.driftHint = driftHint;
            }
        }
    }

    /**
     * A configurable viewer host: two-side (per-side editors, identity line
     * mapping) by default, or unified (one editor, a fixed oneside mapping).
     * Any {@link ReviewDiffInlays.EditorHost} can stand in for a side, so a
     * test can pin a special-purpose editor without the recording-card cast.
     */
    private static final class FakeViewer implements ReviewDiffInlays.ViewerHost {
        final Map<Side, ReviewDiffInlays.EditorHost> editors = new LinkedHashMap<>();
        final List<Runnable> afterRediff = new ArrayList<>();
        String requestPath;
        boolean rediffPending;
        int unifiedLine = -1;

        FakeViewer(String requestPath, ReviewDiffInlays.EditorHost left, ReviewDiffInlays.EditorHost right) {
            this.requestPath = requestPath;
            editors.put(Side.LEFT, left);
            editors.put(Side.RIGHT, right);
        }

        FakeViewer unifiedOneside(String requestPath, FakeEditor single, int onesideLine) {
            this.requestPath = requestPath;
            this.unifiedLine = onesideLine;
            editors.put(Side.LEFT, single);
            editors.put(Side.RIGHT, single);
            return this;
        }

        @Override
        public Optional<String> requestPath() {
            return Optional.ofNullable(requestPath);
        }

        @Override
        public ReviewDiffInlays.EditorHost editorOn(Side side) {
            return editors.get(side);
        }

        @Override
        public int oneSideLine(Side side, int line) {
            return unifiedLine >= 0 ? unifiedLine : line;
        }

        @Override
        public boolean rediffPending() {
            return rediffPending;
        }

        @Override
        public void onAfterRediff(Runnable decorate) {
            afterRediff.add(decorate);
        }
    }

    /** Real {@link FindingInlayCard}s (T4's component), so the release/disposal contract runs against the real card. */
    private static final class RealCards implements ReviewDiffInlays.CardFactory {
        final List<FindingInlayCard> built = new ArrayList<>();

        @Override
        public javax.swing.JComponent card(ReviewAnnotation finding, Optional<String> driftHint) {
            FindingInlayCard card = new FindingInlayCard(finding, driftHint,
                    Clock.fixed(NOW, ZoneOffset.UTC), new FindingInlayCard.Callbacks() {
                        @Override public void confirm() { }
                        @Override public void dismiss() { }
                        @Override public void undo() { }
                        @Override public void fix() { }
                        @Override public void comment(String text) { }
                    });
            built.add(card);
            return card;
        }
    }

    /** An editor that records the real cards it is handed and hands out plain recording handles. */
    private static final class CardEditor implements ReviewDiffInlays.EditorHost {
        final List<javax.swing.JComponent> cards = new ArrayList<>();
        final List<FakeInlay> inlays = new ArrayList<>();

        @Override
        public int lineCount() {
            return 100;
        }

        @Override
        public ReviewDiffInlays.Owned insertCardAfter(int line, javax.swing.JComponent card) {
            cards.add(card);
            FakeInlay inlay = new FakeInlay();
            inlays.add(inlay);
            return inlay;
        }

        @Override
        public ReviewDiffInlays.Owned addGutterMark(int line, ReviewAnnotation finding) {
            return new FakeGutterMark();
        }
    }

    /** The first inserted inlay's release throws; every later handle is a plain recording one. */
    private static final class ThrowingEditor implements ReviewDiffInlays.EditorHost {
        ThrowingInlay first;
        final List<FakeInlay> laterInlays = new ArrayList<>();
        final List<FakeGutterMark> marks = new ArrayList<>();

        @Override
        public int lineCount() {
            return 100;
        }

        @Override
        public ReviewDiffInlays.Owned insertCardAfter(int line, javax.swing.JComponent card) {
            if (first == null) {
                return first = new ThrowingInlay();
            }
            FakeInlay inlay = new FakeInlay();
            laterInlays.add(inlay);
            return inlay;
        }

        @Override
        public ReviewDiffInlays.Owned addGutterMark(int line, ReviewAnnotation finding) {
            FakeGutterMark mark = new FakeGutterMark();
            marks.add(mark);
            return mark;
        }
    }

    private static final class FixedHosts implements ReviewDiffInlays.ViewerHosts {
        final ReviewDiffInlays.ViewerHost host;

        FixedHosts(ReviewDiffInlays.ViewerHost host) {
            this.host = host;
        }

        @Override
        public ReviewDiffInlays.ViewerHost host(FrameDiffTool.DiffViewer viewer) {
            return host;
        }
    }

    private static final FrameDiffTool.DiffViewer ANY_VIEWER = new FrameDiffTool.DiffViewer() {
        @Override
        public javax.swing.JComponent getComponent() {
            return null;
        }

        @Override
        public javax.swing.JComponent getPreferredFocusedComponent() {
            return null;
        }

        @Override
        public FrameDiffTool.ToolbarComponents init() {
            return null;
        }

        @Override
        public void dispose() {
        }
    };

    private static ReviewAnnotation finding(String id, String file, String startKey, Triage triage,
                                            AnnotationStatus status) {
        return new ReviewAnnotation(
                "s1", id, file, startKey, startKey,
                Severity.BLOCKING, Confidence.HIGH, Optional.of("Leak " + id), "reviewer",
                NOW.minus(Duration.ofHours(2)), List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("reviewer",
                        NOW.minus(Duration.ofHours(2)), "This leaks.")), Optional.empty(), status,
                Optional.empty(), false, triage, Optional.empty());
    }

    /** Two-side decorations with the first-hunk fallback at RIGHT line 7. */
    private static ReviewDiffInlays.Decorations decorations(String path, List<ReviewAnnotation> findings,
                                                            String... rowKeys) {
        return new ReviewDiffInlays.Decorations(path, Set.of(rowKeys),
                new ReviewDiffRequests.ScrollTarget(Side.RIGHT, 7), findings);
    }

    private static ReviewDiffInlays layer(ReviewDiffInlays.ViewerHost host) {
        return new ReviewDiffInlays(new FakeEdt(), new FixedHosts(host), new RecordingCards());
    }

    // ---- two-side choice and mapping ----------------------------------------

    @Test
    void twoSideFindingsDecorateTheirOwnSideEditorAtTheirOwnLine() {
        FakeEditor left = new FakeEditor();
        FakeEditor right = new FakeEditor();
        FakeViewer viewer = new FakeViewer("src/A.java", left, right);
        ReviewDiffInlays inlays = layer(viewer);
        ReviewAnnotation onNew = finding("f1", "src/A.java", "n2", Triage.PROPOSED, AnnotationStatus.OPEN);
        ReviewAnnotation onOld = finding("f2", "src/A.java", "o3", Triage.PROPOSED, AnnotationStatus.OPEN);

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java", List.of(onNew, onOld), "n2", "o3"));
            inlays.viewerChanged(ANY_VIEWER);
        });

        assertEquals(List.of(1), right.lines());
        assertEquals(List.of(2), left.lines());
    }

    // ---- unified mapping ----------------------------------------------------

    @Test
    void unifiedFindingsDecorateTheSingleEditorAtTheTransferredOnesideLine() {
        FakeEditor single = new FakeEditor();
        FakeViewer viewer = new FakeViewer(null, new FakeEditor(), new FakeEditor())
                .unifiedOneside("src/A.java", single, 42);
        ReviewDiffInlays inlays = layer(viewer);
        ReviewAnnotation onNew = finding("f1", "src/A.java", "n2", Triage.PROPOSED, AnnotationStatus.OPEN);
        ReviewAnnotation onOld = finding("f2", "src/A.java", "o3", Triage.PROPOSED, AnnotationStatus.OPEN);

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java", List.of(onNew, onOld), "n2", "o3"));
            inlays.viewerChanged(ANY_VIEWER);
        });

        assertEquals(List.of(42, 42), single.lines());
    }

    // ---- after-rediff timing --------------------------------------------------

    @Test
    void decorationWaitsForAfterRediffWhenOneIsPending() {
        FakeEditor right = new FakeEditor();
        FakeViewer viewer = new FakeViewer("src/A.java", new FakeEditor(), right);
        viewer.rediffPending = true;
        ReviewDiffInlays inlays = layer(viewer);

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1"));
            inlays.viewerChanged(ANY_VIEWER);
        });
        assertTrue(right.inserted.isEmpty(), "nothing decorates before the rediff completes");

        onEdt(() -> viewer.afterRediff.forEach(Runnable::run));
        assertEquals(List.of(0), right.lines());
    }

    @Test
    void decorationRunsImmediatelyWhenNoRediffIsPendingAndNoViewerPaintsNothing() {
        FakeEditor right = new FakeEditor();
        FakeViewer viewer = new FakeViewer("src/A.java", new FakeEditor(), right);
        ReviewDiffInlays inlays = layer(viewer);

        onEdt(() -> inlays.setDecorations(decorations("src/A.java",
                List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                "n1")));
        assertTrue(right.inserted.isEmpty(), "no viewer yet: nothing decorates");

        onEdt(() -> inlays.viewerChanged(ANY_VIEWER));
        assertEquals(List.of(0), right.lines(), "no rediff pending: decorated immediately");
    }

    // ---- drift fallback and finding policy -----------------------------------

    @Test
    void aDriftedFindingRendersAtTheFirstHunkFallbackWithTheDriftHint() {
        FakeEditor right = new FakeEditor();
        FakeViewer viewer = new FakeViewer("src/A.java", new FakeEditor(), right);
        ReviewDiffInlays inlays = layer(viewer);

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n50", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n2"));
            inlays.viewerChanged(ANY_VIEWER);
        });

        assertEquals(1, right.inserted.size());
        assertEquals(7, right.inserted.get(0).line, "the first-hunk fallback target, never a guessed line");
        assertEquals(Optional.of(ReviewTabModel.DRIFT_HINT), right.inserted.get(0).driftHint);
    }

    @Test
    void everyUnresolvedFindingKeepsItsCardIncludingConfirmedAndDismissed() {
        FakeEditor right = new FakeEditor();
        FakeViewer viewer = new FakeViewer("src/A.java", new FakeEditor(), right);
        ReviewDiffInlays inlays = layer(viewer);
        List<ReviewAnnotation> findings = List.of(
                finding("proposed", "src/A.java", "n2", Triage.PROPOSED, AnnotationStatus.OPEN),
                finding("confirmed", "src/A.java", "n3", Triage.CONFIRMED, AnnotationStatus.OPEN),
                finding("dismissed", "src/A.java", "n4", Triage.DISMISSED, AnnotationStatus.OPEN),
                finding("resolved", "src/A.java", "n5", Triage.PROPOSED, AnnotationStatus.FIXED),
                finding("elsewhere", "src/B.java", "n6", Triage.PROPOSED, AnnotationStatus.OPEN));

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java", findings, "n2", "n3", "n4", "n5", "n6"));
            inlays.viewerChanged(ANY_VIEWER);
        });

        assertEquals(List.of("proposed", "confirmed", "dismissed"),
                right.inserted.stream().map(i -> i.finding.id()).toList());
    }

    @Test
    void aViewerShowingAnotherFilesRequestDecoratesNothing() {
        FakeEditor right = new FakeEditor();
        FakeViewer viewer = new FakeViewer("src/B.java", new FakeEditor(), right);
        ReviewDiffInlays inlays = layer(viewer);

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1"));
            inlays.viewerChanged(ANY_VIEWER);
        });

        assertTrue(right.inserted.isEmpty());
    }

    @Test
    void aLineTheEditorCannotAddressRendersNothing() {
        FakeEditor shortRight = new FakeEditor();
        shortRight.lineCount = 2;
        FakeViewer viewer = new FakeViewer("src/A.java", new FakeEditor(), shortRight);
        ReviewDiffInlays inlays = layer(viewer);

        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n5", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n5"));
            inlays.viewerChanged(ANY_VIEWER);
        });

        assertTrue(shortRight.inserted.isEmpty(),
                "an anchor beyond the editor's lines is skipped, never guessed");
    }

    // ---- coalescing, idempotence, disposal -----------------------------------

    @Test
    void aBurstOfRebuildRequestsPaintsOnce() {
        FakeEdt edt = new FakeEdt();
        FakeEditor right = new FakeEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)),
                new RecordingCards());
        ReviewDiffInlays.Decorations decorations = decorations("src/A.java",
                List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                "n1");

        onEdt(() -> {
            inlays.viewerChanged(ANY_VIEWER);
            inlays.setDecorations(decorations);
            inlays.rebuildRequested();
            inlays.rebuildRequested();
            inlays.rebuildRequested();
            edt.pump();
        });

        assertEquals(1, right.inserted.size());
    }

    @Test
    void aRebuildReleasesThePreviousInlaysAndHighlightersExplicitly() {
        FakeEdt edt = new FakeEdt();
        FakeEditor right = new FakeEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)),
                new RecordingCards());

        onEdt(() -> {
            inlays.viewerChanged(ANY_VIEWER);
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1"));
            edt.pump();
        });
        List<Inserted> first = List.copyOf(right.inserted);

        onEdt(() -> {
            inlays.rebuildRequested();
            edt.pump();
        });

        assertTrue(first.get(0).inlay.released, "the prior inlay is disposed");
        assertTrue(first.get(0).mark.removed, "the prior highlighter is removed");
        assertEquals(2, right.inserted.size(), "the rebuild re-inserts exactly once more");
        assertFalse(right.inserted.get(1).inlay.released, "the new batch is live");
    }

    @Test
    void disposeReleasesEverythingAndStopsTheLayer() {
        FakeEdt edt = new FakeEdt();
        FakeEditor right = new FakeEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)),
                new RecordingCards());

        onEdt(() -> {
            inlays.viewerChanged(ANY_VIEWER);
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1"));
            edt.pump();
            List<Inserted> live = List.copyOf(right.inserted);

            inlays.dispose();

            assertTrue(live.get(0).inlay.released);
            assertTrue(live.get(0).mark.removed);
            right.inserted.clear();

            inlays.setDecorations(decorations("src/A.java", List.of(), "n1"));
            inlays.rebuildRequested();
            edt.pump();
            inlays.viewerChanged(ANY_VIEWER);
        });
        assertTrue(right.inserted.isEmpty(), "a disposed layer decorates nothing ever again");
    }

    /**
     * The T6-fix regression: releasing an inlay must dispose its card too,
     * so the owner's late async completion on the detached card changes
     * nothing (T4's disposal contract, invoked by the inlay's owner).
     */
    @Test
    void aReleasedCardIsDisposedSoALateCompletionChangesNothing() {
        FakeEdt edt = new FakeEdt();
        RealCards cards = new RealCards();
        CardEditor right = new CardEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)), cards);

        onEdt(() -> {
            inlays.viewerChanged(ANY_VIEWER);
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1"));
            edt.pump();
        });
        FindingInlayCard live = cards.built.get(0);

        onEdt(() -> button(live, "Confirm").doClick()); // an action goes in flight
        onEdt(() -> assertTrue(live.isBusy(), "the action is in flight on the live card"));

        onEdt(() -> { // the rebuild releases the inlay — and disposes the card with it
            inlays.rebuildRequested();
            edt.pump();
        });
        assertTrue(right.inlays.get(0).released, "the prior inlay is disposed");

        onEdt(live::complete); // the owner's late completion
        onEdt(() -> assertFalse(button(live, "Confirm").isEnabled(),
                "a late completion on the detached card is inert: the controls stay dead"));

        FindingInlayCard rebuilt = cards.built.get(1); // the rebuild's fresh card
        onEdt(() -> button(rebuilt, "Confirm").doClick());
        onEdt(inlays::dispose);
        onEdt(rebuilt::complete);
        onEdt(() -> assertFalse(button(rebuilt, "Confirm").isEnabled(),
                "dispose releases the card too: a late completion changes nothing"));
    }

    @Test
    void aThrowingReleaseNeverStrandsTheLaterHandles() {
        FakeEdt edt = new FakeEdt();
        ThrowingEditor right = new ThrowingEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)),
                new RecordingCards());

        onEdt(() -> {
            inlays.viewerChanged(ANY_VIEWER);
            inlays.setDecorations(decorations("src/A.java", List.of(
                    finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN),
                    finding("f2", "src/A.java", "n2", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1", "n2"));
            edt.pump();
        });

        onEdt(() -> { // the first inlay's release throws; the rest must still be released
            inlays.rebuildRequested();
            edt.pump();
        });

        assertTrue(right.first.released);
        assertTrue(right.laterInlays.get(0).released,
                "a failing release never strands the later inlays");
        assertTrue(right.marks.get(0).removed);
        assertTrue(right.marks.get(1).removed,
                "a failing inlay release never strands the highlighters");
        // the lists were cleared despite the throw: the rebuild inserted a fresh batch
        assertEquals(3, right.laterInlays.size(),
                "the rebuild's two fresh inlays joined the old one's slot list");
        assertEquals(4, right.marks.size(), "two released marks plus the rebuild's two");
        assertFalse(right.laterInlays.get(1).released, "the new batch is live");
    }

    @Test
    void disposeSurvivesAThrowingReleaseAndClearsEverything() {
        FakeEdt edt = new FakeEdt();
        ThrowingEditor right = new ThrowingEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)),
                new RecordingCards());

        onEdt(() -> {
            inlays.viewerChanged(ANY_VIEWER);
            inlays.setDecorations(decorations("src/A.java", List.of(
                    finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN),
                    finding("f2", "src/A.java", "n2", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1", "n2"));
            edt.pump();
        });

        onEdt(inlays::dispose); // the first inlay's release throws inside dispose

        assertTrue(right.first.released);
        assertTrue(right.laterInlays.get(0).released,
                "dispose releases every inlay even when one release fails");
        assertTrue(right.marks.get(0).removed);
        assertTrue(right.marks.get(1).removed);

        // the layer is stopped and holds nothing: a later rebuild paints nothing
        onEdt(() -> {
            inlays.setDecorations(decorations("src/A.java",
                    List.of(finding("f1", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN)),
                    "n1"));
            inlays.rebuildRequested();
            edt.pump();
            inlays.viewerChanged(ANY_VIEWER);
        });
        assertEquals(1, right.laterInlays.size(), "a disposed layer decorates nothing ever again");
    }

    // ---- EDT contract ---------------------------------------------------------

    @Test
    void theLayerAssertsTheEdtAndHopsOffEdtViewerChanges() {
        FakeEdt edt = new FakeEdt();
        FakeEditor right = new FakeEditor();
        ReviewDiffInlays inlays = new ReviewDiffInlays(edt,
                new FixedHosts(new FakeViewer("src/A.java", new FakeEditor(), right)),
                new RecordingCards());
        ReviewDiffInlays.Decorations next = decorations("src/A.java", List.of(), "n1");

        assertThrows(IllegalStateException.class, () -> runOffEdt(() -> inlays.setDecorations(next)));
        assertThrows(IllegalStateException.class, () -> runOffEdt(inlays::rebuildRequested));
        assertThrows(IllegalStateException.class, () -> runOffEdt(inlays::dispose),
                "dispose mutates Swing-owned decorations: it asserts the EDT too");

        // a viewer change off the EDT hops through the queue instead of throwing
        runQuietOffEdt(() -> inlays.viewerChanged(ANY_VIEWER));
        assertEquals(1, edt.queued.size());
        onEdt(edt::pump);
        assertTrue(right.inserted.isEmpty(), "no decorations yet: the hop ran, painted nothing");
    }

    // ---- the production card factory -----------------------------------------

    @Test
    void theTriageCardFactoryHandsCardActionsTheFindingKeyAndTheCard() {
        RecordingActions actions = new RecordingActions();
        ReviewAnnotation finding = finding("f1", "src/A.java", "n2", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        FindingInlayCard[] card = new FindingInlayCard[1];

        onEdt(() -> card[0] = (FindingInlayCard) ReviewDiffInlays.triageCards(
                Clock.fixed(NOW, ZoneOffset.UTC), actions).card(finding, Optional.empty()));
        onEdt(() -> button(card[0], "Confirm").doClick());

        assertEquals(finding.key(), actions.key);
        assertEquals(card[0], actions.card);
    }

    private static final class RecordingActions implements ReviewDiffInlays.CardActions {
        ReviewAnnotation.Key key;
        FindingInlayCard card;

        @Override
        public void confirm(ReviewAnnotation.Key key, FindingInlayCard card) {
            this.key = key;
            this.card = card;
        }

        @Override
        public void dismiss(ReviewAnnotation.Key key, FindingInlayCard card) {
        }

        @Override
        public void undo(ReviewAnnotation.Key key, FindingInlayCard card) {
        }

        @Override
        public void fix(ReviewAnnotation.Key key, FindingInlayCard card) {
        }

        @Override
        public void comment(ReviewAnnotation.Key key, FindingInlayCard card, String text) {
        }
    }

    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JButton found = button(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------------

    private static void onEdt(Runnable op) {
        if (EventQueue.isDispatchThread()) {
            op.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the EDT", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("the EDT runnable failed", e.getCause());
        }
    }

    private static void runOffEdt(Runnable op) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        runQuietOffEdt(() -> {
            try {
                op.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        Throwable failure = thrown.get();
        if (failure == null) {
            throw new AssertionError("the off-EDT probe did not throw, but it must");
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new AssertionError("the off-EDT probe threw " + failure, failure);
    }

    private static void runQuietOffEdt(Runnable op) {
        Thread other = new Thread(op);
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted joining the off-EDT probe", e);
        }
    }
}
