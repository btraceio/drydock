package app.drydock.intellij.review;

import app.drydock.review.ReviewAnnotation;

import com.intellij.collaboration.ui.codereview.editor.EditorComponentInlaysUtilKt;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.base.DiffViewerListener;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;

import java.awt.EventQueue;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.JComponent;
import kotlin.jvm.functions.Function1;

/**
 * The decoration half of the reusable diff (spec "Review surface and diff
 * inlays", AC1/AC6/AC7). {@link #viewerChanged} — the processor listener's
 * dispatch point — converts the active viewer through the injected
 * {@link ViewerHosts} seam, attaches a viewer-owned after-rediff listener,
 * and decorates only that viewer's editors, immediately when no rediff is
 * pending. Placement is {@link ReviewFindingPlacement}'s decision: exact
 * side ({@code n<N>} RIGHT, {@code o<N>} LEFT), or the file's first-hunk
 * fallback with the drift hint; {@code UnifiedDiffViewer} side lines are
 * converted with {@code transferLineToOneside}. Component inlays go through
 * the Java-callable {@code EditorComponentInlaysUtilKt.insertComponentAfter}
 * with a non-null renderer factory (263 rejects null — cairn
 * {@code intellij-findings-triage/2026-10-24-platform-inlay-gutter-api-263});
 * the severity gutter mark is a {@link RangeHighlighter}, removed through
 * the markup model because a highlighter is not a {@code Disposable} on 263.
 * Every rebuild releases the previous cards, inlays and highlighters
 * explicitly — each {@link FindingInlayCard} is disposed with its release so
 * a late completion on a detached card changes nothing, and each handle in
 * isolation so one failing release is logged and never strands the rest;
 * store-driven rebuild bursts coalesce into one pending rebuild; cards are
 * built for every unresolved finding of the decorated file, including
 * CONFIRMED/DISMISSED (Undo stays reachable). No timers; listeners are
 * owned by the viewer they watch.
 */
public final class ReviewDiffInlays {

    /** One file's decorations: path, current row keys, first-hunk fallback target, findings. */
    public record Decorations(
            String path,
            Set<String> rowKeys,
            ReviewDiffRequests.ScrollTarget firstHunkFallback,
            List<ReviewAnnotation> findings
    ) {
        public Decorations {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(firstHunkFallback, "firstHunkFallback");
            rowKeys = Set.copyOf(Objects.requireNonNull(rowKeys, "rowKeys"));
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        }
    }

    /**
     * Everything the layer needs from the active diff viewer (fakes pin it
     * in tests). {@link #oneSideLine} answers the oneside (unified) line
     * for a two-side {@code (side, line)} anchor — two-side viewers answer
     * the line unchanged — and negative means unmappable: render nothing.
     */
    public interface ViewerHost {
        Optional<String> requestPath();

        /** The editor a placement on {@code side} decorates; null when the viewer has none. */
        EditorHost editorOn(Side side);

        int oneSideLine(Side side, int line);

        boolean rediffPending();

        /** Registers the viewer-owned after-rediff callback. */
        void onAfterRediff(Runnable decorate);
    }

    /** Converts the platform's active viewer into a {@link ViewerHost}; null means "not decoratable". */
    public interface ViewerHosts {
        ViewerHost host(FrameDiffTool.DiffViewer viewer);
    }

    /** One editor the layer decorates; the production adapter wraps an {@link EditorEx}. */
    public interface EditorHost {
        int lineCount();

        /** Inserts the card component after {@code line}; the handle releases the inlay. */
        Owned insertCardAfter(int line, JComponent card);

        /** Adds the severity gutter mark at {@code line}; the handle removes the highlighter. */
        Owned addGutterMark(int line, ReviewAnnotation finding);
    }

    /** One decoration the layer owns: release() removes it (inlay dispose / highlighter removal). */
    public interface Owned {
        void release();
    }

    /** Builds one card per finding ({@link #triageCards} is the production one). */
    public interface CardFactory {
        JComponent card(ReviewAnnotation finding, Optional<String> driftHint);
    }

    /** The write side behind a card's buttons; each call carries the card so its owner clears progress. */
    public interface CardActions {
        void confirm(ReviewAnnotation.Key key, FindingInlayCard card);

        void dismiss(ReviewAnnotation.Key key, FindingInlayCard card);

        void undo(ReviewAnnotation.Key key, FindingInlayCard card);

        void fix(ReviewAnnotation.Key key, FindingInlayCard card);

        void comment(ReviewAnnotation.Key key, FindingInlayCard card, String text);
    }

    /** The EDT queue the layer hops through; production is the application's invokeLater. */
    public interface EdtQueue {
        void invokeLater(Runnable runnable);
    }

    /**
     * 263 rejects a null renderer factory before creating the inlay; this is
     * the platform default's own factory — no gutter icon on the inlay
     * itself, the severity mark comes from the range highlighter.
     */
    private static final Function1<Inlay<?>, GutterIconRenderer> NO_GUTTER_RENDERER = inlay -> null;

    /** An unexpected release failure is logged here, never silently swallowed. */
    private static final Logger LOG = System.getLogger(ReviewDiffInlays.class.getName());

    private final EdtQueue edt;
    private final ViewerHosts hosts;
    private final CardFactory cards;

    private ViewerHost viewer;
    private Decorations decorations;
    private final List<Owned> inlays = new ArrayList<>();
    private final List<Owned> marks = new ArrayList<>();
    /** The cards behind the live inlays: released with them, disposed first. */
    private final List<JComponent> liveCards = new ArrayList<>();
    private boolean rebuildPending;
    private boolean disposed;

    /** The production layer: platform hosts and the application's EDT queue. */
    public ReviewDiffInlays(EdtQueue edt, CardFactory cards) {
        this(edt, ReviewDiffInlays::hostFor, cards);
    }

    /** The testable layer: hosts and EDT queue are injected seams. */
    ReviewDiffInlays(EdtQueue edt, ViewerHosts hosts, CardFactory cards) {
        this.edt = Objects.requireNonNull(edt, "edt");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.cards = Objects.requireNonNull(cards, "cards");
    }

    /** The production EDT queue. */
    public static EdtQueue platformEdt() {
        return runnable -> ApplicationManager.getApplication().invokeLater(runnable);
    }

    /** The production card factory: a {@link FindingInlayCard} per finding, invoking {@code actions}. */
    public static CardFactory triageCards(Clock clock, CardActions actions) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(actions, "actions");
        return (finding, driftHint) -> {
            ReviewAnnotation.Key key = finding.key();
            FindingInlayCard[] card = new FindingInlayCard[1];
            FindingInlayCard.Callbacks callbacks = new FindingInlayCard.Callbacks() {
                @Override public void confirm() { actions.confirm(key, card[0]); }
                @Override public void dismiss() { actions.dismiss(key, card[0]); }
                @Override public void undo() { actions.undo(key, card[0]); }
                @Override public void fix() { actions.fix(key, card[0]); }
                @Override public void comment(String text) { actions.comment(key, card[0], text); }
            };
            card[0] = new FindingInlayCard(finding, driftHint, clock, callbacks);
            return card[0];
        };
    }

    /**
     * The processor listener's dispatch point. Hops to the EDT when the
     * platform callback arrives elsewhere, then re-targets decoration to
     * the viewer — releasing the previous viewer's marks explicitly,
     * attaching the viewer-owned after-rediff listener, and decorating
     * immediately when no rediff is pending.
     */
    public void viewerChanged(FrameDiffTool.DiffViewer viewer) {
        if (!EventQueue.isDispatchThread()) {
            edt.invokeLater(() -> viewerChanged(viewer));
            return;
        }
        ViewerHost host = hosts.host(viewer);
        releaseDecorations();
        if (disposed) {
            return;
        }
        this.viewer = host;
        if (host == null) {
            return; // no editor lines to address (binary, unknown viewer)
        }
        if (host.rediffPending()) {
            host.onAfterRediff(this::decorateNow);
        } else {
            decorateNow();
        }
    }

    /** Replaces the decorations and schedules a coalesced rebuild of the live viewer. */
    public void setDecorations(Decorations next) {
        requireEdt();
        if (disposed) {
            return;
        }
        decorations = Objects.requireNonNull(next, "next");
        scheduleRebuild();
    }

    /** A store change arrived: rebuild with the current decorations, coalesced. */
    public void rebuildRequested() {
        requireEdt();
        if (disposed) {
            return;
        }
        scheduleRebuild();
    }

    /** Releases every card, inlay and highlighter the layer owns; nothing survives disposal. */
    public void dispose() {
        requireEdt(); // releases Swing-owned decorations: an off-EDT dispose would race the EDT mutations
        disposed = true;
        releaseDecorations();
        decorations = null;
        viewer = null;
    }

    // ---- the rebuild --------------------------------------------------------

    private void scheduleRebuild() {
        if (rebuildPending) {
            return; // one pending rebuild absorbs the burst
        }
        rebuildPending = true;
        edt.invokeLater(() -> {
            rebuildPending = false;
            decorateNow();
        });
    }

    private void decorateNow() {
        if (!EventQueue.isDispatchThread()) {
            edt.invokeLater(this::decorateNow);
            return;
        }
        releaseDecorations();
        if (disposed) {
            return;
        }
        Decorations current = decorations;
        ViewerHost host = viewer;
        if (host == null || current == null) {
            return;
        }
        if (!host.requestPath().map(current.path()::equals).orElse(false)) {
            return; // the viewer shows another file's request: never paint A's findings on B's diff
        }
        for (ReviewAnnotation finding : current.findings()) {
            if (finding.resolved() || !finding.file().equals(current.path())) {
                continue; // every unresolved finding of this file, any triage (Undo stays reachable)
            }
            ReviewFindingPlacement.Placement placement = ReviewFindingPlacement.placement(
                    finding.startKey(), current.rowKeys(), current.firstHunkFallback());
            int line = host.oneSideLine(placement.side(), placement.line());
            EditorHost editor = host.editorOn(placement.side());
            if (editor == null || line < 0 || line >= editor.lineCount()) {
                continue; // no editor line to address; never a wrong-line guess
            }
            JComponent card = cards.card(finding, placement.driftHint());
            inlays.add(editor.insertCardAfter(line, card));
            liveCards.add(card);
            marks.add(editor.addGutterMark(line, finding));
        }
    }

    /**
     * Explicit removal: every prior card disposed, every prior inlay
     * disposed, every prior highlighter removed — each handle in isolation,
     * so one failing release is logged and never strands the rest, and the
     * lists end up empty whatever happened.
     */
    private void releaseDecorations() {
        disposeReleasedCards();
        releaseAll(inlays);
        releaseAll(marks);
    }

    /**
     * Disposes the cards the release detaches, before their inlays go: a
     * late completion on a detached card then changes nothing (T4's
     * disposal contract, invoked by the inlay's owner).
     */
    private void disposeReleasedCards() {
        List<JComponent> detached = List.copyOf(liveCards);
        liveCards.clear();
        for (JComponent card : detached) {
            try {
                disposeCard(card);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Disposing a released finding card failed", e);
            }
        }
    }

    /** Releases every handle even when an earlier one throws; the lists are cleared before any release runs. */
    private static void releaseAll(List<Owned> owned) {
        List<Owned> pending = List.copyOf(owned);
        owned.clear();
        for (Owned handle : pending) {
            try {
                handle.release();
            } catch (RuntimeException e) {
                // Complete cleanup outranks the first failure: the remaining
                // handles are still released and the unexpected failure is
                // logged, never silently swallowed.
                LOG.log(Level.WARNING, "Releasing a review diff decoration failed", e);
            }
        }
    }

    /** Disposes the layer's own card type; any other card component has no disposal contract. */
    static void disposeCard(JComponent card) {
        if (card instanceof FindingInlayCard findingCard) {
            findingCard.dispose();
        }
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewDiffInlays must be used on the EDT");
        }
    }

    // ---- production adapters ------------------------------------------------

    /** The production host conversion: unified and two-side text viewers only. */
    static ViewerHost hostFor(FrameDiffTool.DiffViewer viewer) {
        if (viewer instanceof UnifiedDiffViewer unified) {
            return new UnifiedHost(unified);
        }
        if (viewer instanceof TwosideTextDiffViewer twoside) {
            return new TwosideHost(twoside);
        }
        return null; // binary or unknown: no editor lines to decorate
    }

    private static Optional<String> pathOf(ContentDiffRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        ReviewDiffRequests.RequestContext context = request.getUserData(ReviewDiffRequests.CONTEXT_KEY);
        return context == null ? Optional.empty() : Optional.of(context.path());
    }

    private static DiffViewerListener afterRediff(Runnable decorate) {
        return new DiffViewerListener() {
            @Override
            protected void onAfterRediff() {
                decorate.run();
            }
        };
    }

    /** Shared plumbing for the two text viewers: request path, rediff state, after-rediff hook. */
    private abstract static class TextHost implements ViewerHost {
        abstract DiffViewerBase viewer();

        @Override public Optional<String> requestPath() { return pathOf(viewer().getRequest()); }

        @Override public boolean rediffPending() { return viewer().hasPendingRediff(); }

        @Override public void onAfterRediff(Runnable decorate) { viewer().addListener(afterRediff(decorate)); }
    }

    /** The unified host: one oneside editor; side lines map through transferLineToOneside. */
    private static final class UnifiedHost extends TextHost {
        private final UnifiedDiffViewer viewer;

        UnifiedHost(UnifiedDiffViewer viewer) {
            this.viewer = Objects.requireNonNull(viewer, "viewer");
        }

        @Override DiffViewerBase viewer() { return viewer; }

        @Override public EditorHost editorOn(Side side) {
            List<? extends Editor> editors = viewer.getHighlightEditors();
            if (editors.isEmpty() || !(editors.get(0) instanceof EditorEx editor)) {
                return null;
            }
            return editorHost(editor);
        }

        @Override public int oneSideLine(Side side, int line) { return viewer.transferLineToOneside(side, line); }
    }

    /** The two-side host: each placement decorates its own side's editor. */
    private static final class TwosideHost extends TextHost {
        private final TwosideTextDiffViewer viewer;

        TwosideHost(TwosideTextDiffViewer viewer) {
            this.viewer = Objects.requireNonNull(viewer, "viewer");
        }

        @Override DiffViewerBase viewer() { return viewer; }

        @Override public EditorHost editorOn(Side side) {
            EditorEx editor = viewer.getEditor(side);
            return editor == null ? null : editorHost(editor);
        }

        @Override public int oneSideLine(Side side, int line) { return line; }
    }

    /** The production editor adapter: the platform inlay and gutter-mark APIs. */
    static EditorHost editorHost(EditorEx editor) {
        return new EditorHost() {
            @Override
            public int lineCount() {
                return editor.getDocument().getLineCount();
            }

            @Override public Owned insertCardAfter(int line, JComponent card) {
                Inlay<?> inlay = EditorComponentInlaysUtilKt.insertComponentAfter(
                        editor, line, card, 0, NO_GUTTER_RENDERER);
                if (inlay == null) {
                    // InlayModel can refuse the inlay (verified on 263:
                    // addBlockElement answers null, and the Kotlin signature
                    // does not check it): the card never attached, so it is
                    // disposed here — no completion path may revive a
                    // never-shown card — and the handle is a safe no-op.
                    disposeCard(card);
                    return () -> { };
                }
                // Validity is the disposal check (an inlay goes invalid with
                // its editor); anything else that fails propagates and is
                // logged by the layer, not swallowed here.
                return () -> {
                    if (inlay.isValid()) {
                        inlay.dispose();
                    }
                };
            }

            @Override public Owned addGutterMark(int line, ReviewAnnotation finding) {
                RangeHighlighter highlighter = editor.getMarkupModel()
                        .addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null);
                highlighter.setGutterIconRenderer(new SeverityGutterIcon(finding));
                // Disposal checks: a disposed editor's markup model is gone
                // and the highlighter is invalidated with it. Anything else
                // that fails propagates and is logged by the layer.
                return () -> {
                    if (!editor.isDisposed() && highlighter.isValid()) {
                        editor.getMarkupModel().removeHighlighter(highlighter);
                    }
                };
            }
        };
    }

    /** The severity gutter mark: its icon, and the finding's title as the tooltip. */
    private static final class SeverityGutterIcon extends GutterIconRenderer {

        private final ReviewAnnotation finding;

        SeverityGutterIcon(ReviewAnnotation finding) {
            this.finding = Objects.requireNonNull(finding, "finding");
        }

        @Override public Icon getIcon() {
            return switch (finding.effectiveSeverity()) {
                case BLOCKING -> AllIcons.General.Error;
                case QUESTION -> AllIcons.General.QuestionDialog;
                case DEVIATION -> AllIcons.General.Warning;
                case NIT -> AllIcons.General.Information;
            };
        }

        @Override public String getTooltipText() { return finding.displayTitle(); }

        @Override public AnAction getClickAction() { return null; }

        @Override public boolean equals(Object other) {
            return other instanceof SeverityGutterIcon icon && finding.equals(icon.finding);
        }

        @Override public int hashCode() { return finding.hashCode(); }
    }
}
