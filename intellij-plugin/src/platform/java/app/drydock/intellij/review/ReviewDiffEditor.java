package app.drydock.intellij.review;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.editor.DiffVirtualFile;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.impl.DiffRequestProcessorListener;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.LoadingDiffRequest;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

import java.awt.EventQueue;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The project-owned reusable editor-area diff (spec "Review surface and
 * diff inlays"): one {@link #file DiffVirtualFile} whose tab every file
 * selection reuses. {@link #show} swaps the current request into the live
 * processor in place; when the tab was closed (its processor disposed) it
 * reopens the tab, and the newly created processor is seeded with the
 * current request — a request kept only on the virtual file leaves the
 * processor's request null, so its first {@code selectNotify ->
 * updateRequest} applies nothing and the tab stays blank until the second
 * selection (cairn:
 * {@code intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam}).
 *
 * <p>The processor owns a {@link DiffRequestProcessorListener} parented to
 * the processor itself, so a closed tab retains nothing; each viewer change
 * dispatches the active viewer to the injected {@link ReviewDiffInlays}
 * consumer. Selection completions that arrive after a newer selection are
 * dropped ({@link #selection}/{@link #requestReady}); {@link #show} and both
 * gates run on the EDT (the caller of {@code requestReady} hops off the
 * executor). No {@code DiffExtension}, request-document matching, global
 * editor listener or Swing component walk — the spike disproved all four.</p>
 */
public final class ReviewDiffEditor {

    /** The tab's title before the first request names it (then the file path). */
    public static final String TAB_TITLE = "Drydock Review";

    /**
     * Opens (or reopens) the single reusable tab. Production opens the
     * editor-area tab through {@code FileEditorManager.openFile}, which is
     * what makes the platform create the processor via the file's
     * {@code createProcessor}.
     */
    public interface TabOpener {
        void open();
    }

    /**
     * Builds the platform request for a context. Production is
     * {@link ReviewDiffRequests#toRequest(Project, Disposable,
     * ReviewDiffRequests.RequestContext)}; tests inject a marker factory.
     */
    public interface RequestFactory {
        DiffRequest build(ReviewDiffRequests.RequestContext context);
    }

    /**
     * The live processor behind the open tab: swaps later requests into
     * place, and reports its disposal (the tab was closed). Production is
     * the {@link Processor} below; tests drive the swap/reopen decisions
     * through a fake.
     */
    public interface ProcessorHandle {
        /** Applies {@code request} in place (EDT). */
        void apply(DiffRequest request);

        boolean isDisposed();
    }

    private final TabOpener tabs;
    private final RequestFactory requests;
    private final Consumer<FrameDiffTool.DiffViewer> onViewerChanged;

    /** The one reusable virtual file; null in tests, which never open a tab. */
    private ReviewFile file;

    /** The current request context (read by getName on any thread). */
    private volatile ReviewDiffRequests.RequestContext context;

    /** The selection whose request may still be shown; null until the first selection. */
    private Object selection;

    /** The last created processor; may be disposed (tab closed). */
    private ProcessorHandle processor;

    ReviewDiffEditor(TabOpener tabs, RequestFactory requests,
                     Consumer<FrameDiffTool.DiffViewer> onViewerChanged) {
        this.tabs = Objects.requireNonNull(tabs, "tabs");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.onViewerChanged = Objects.requireNonNull(onViewerChanged, "onViewerChanged");
    }

    /**
     * The production wiring: one reusable {@link ReviewFile} whose tab is
     * opened through {@code FileEditorManager}, requests built by
     * {@link ReviewDiffRequests}, viewer changes dispatched to
     * {@code inlays}.
     */
    public static ReviewDiffEditor platform(Project project, Disposable parent,
                                            ReviewDiffInlays inlays) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(inlays, "inlays");
        ReviewDiffEditor[] self = new ReviewDiffEditor[1];
        self[0] = new ReviewDiffEditor(
                () -> FileEditorManager.getInstance(project).openFile(self[0].file, true),
                context -> ReviewDiffRequests.toRequest(project, parent, context),
                inlays::viewerChanged);
        self[0].file = new ReviewFile(self[0]);
        return self[0];
    }

    /**
     * The user picked {@code selectionId}: requests for any earlier
     * selection are stale from here on.
     */
    public void selection(Object selectionId) {
        requireEdt();
        selection = Objects.requireNonNull(selectionId, "selectionId");
    }

    /**
     * A selection's request finished building: shown, in arrival order,
     * iff it is still the current selection. A request that arrives before
     * any selection has been made is dropped — nothing has been picked.
     */
    public void requestReady(Object selectionId, ReviewDiffRequests.RequestContext context) {
        requireEdt();
        Objects.requireNonNull(selectionId, "selectionId");
        Objects.requireNonNull(context, "context");
        if (!Objects.equals(selection, selectionId)) {
            return;
        }
        show(context);
    }

    /**
     * Shows {@code context}: swapped into the live processor in place, or
     * (first show, or the tab was closed) the tab is (re)opened and the
     * processor it creates is seeded with the current request.
     */
    public void show(ReviewDiffRequests.RequestContext context) {
        requireEdt();
        Objects.requireNonNull(context, "context");
        this.context = context;
        ProcessorHandle live = processor;
        if (live != null && !live.isDisposed()) {
            // The swap path's only build: built once, applied in place.
            live.apply(requests.build(context));
            return;
        }
        // The tab is gone (or was never opened); the disposed processor is
        // dropped and the tab (re)opened. The request is built once — by the
        // seeding of the processor the platform creates — not twice (built
        // here, discarded, then rebuilt by seedRequest: every build creates
        // a document pair the platform never releases on 263).
        processor = null;
        tabs.open();
    }

    /**
     * The request a newly created processor is seeded with: the current
     * context's request (the seeding the spike demanded), or the platform's
     * own loading placeholder when no context exists yet. EDT — the tab's
     * processor is created while the EDT opens the tab.
     */
    DiffRequest seedRequest() {
        requireEdt();
        ReviewDiffRequests.RequestContext current = context;
        return current == null ? new LoadingDiffRequest() : requests.build(current);
    }

    /** The processor the platform just created for the (re)opened tab; also the tests' install point. */
    void processorReady(ProcessorHandle created) {
        requireEdt();
        processor = Objects.requireNonNull(created, "created");
    }

    /** The title the tab shows: the current file path, or the default title. */
    String currentTitle() {
        ReviewDiffRequests.RequestContext current = context;
        return current == null ? TAB_TITLE : current.path();
    }

    /**
     * The processor for a newly (re)opened tab: seeded with
     * {@link #seedRequest()}, reporting viewer changes to the injected
     * consumer through a listener parented to the processor itself.
     */
    DiffRequestProcessor newProcessor(Project project) {
        Processor processor = new Processor(project, seedRequest());
        processor.addListener((DiffRequestProcessorListener) () ->
                onViewerChanged.accept(processor.getActiveViewer()), processor);
        processorReady(processor);
        return processor;
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewDiffEditor must be used on the EDT");
        }
    }

    /**
     * The reusable editor-area tab: {@code DiffFileEditorProvider} accepts
     * it ({@code instanceof DiffViewerVirtualFile}) and calls
     * {@link #createProcessor} when the tab opens or reopens — the platform
     * route through which every processor is created.
     */
    private static final class ReviewFile extends DiffVirtualFile {

        private final ReviewDiffEditor owner;

        ReviewFile(ReviewDiffEditor owner) {
            super(TAB_TITLE);
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        @Override
        public DiffRequestProcessor createProcessor(Project project) {
            return owner.newProcessor(project);
        }

        @Override
        public String getName() {
            return owner.currentTitle();
        }
    }

    /**
     * The live processor: holds the current request (the platform's
     * {@code SimpleDiffVirtualFile} shape), so its first
     * {@code selectNotify -> updateRequest} applies the seeded request and
     * every later {@link #apply} swaps the next request in place.
     */
    private static final class Processor extends DiffRequestProcessor implements ProcessorHandle {

        private DiffRequest request;

        Processor(Project project, DiffRequest seed) {
            super(project);
            this.request = Objects.requireNonNull(seed, "seed");
        }

        @Override
        public synchronized void updateRequest(boolean scrollToChange,
                                               DiffUserDataKeysEx.ScrollToPolicy policy) {
            applyRequest(request, scrollToChange, policy);
        }

        @Override
        public synchronized void apply(DiffRequest next) {
            request = Objects.requireNonNull(next, "next");
            applyRequest(request, false, null);
        }
    }
}
