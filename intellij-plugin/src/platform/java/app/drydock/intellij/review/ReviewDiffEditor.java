package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.WorkbenchState;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.impl.DiffRequestProcessorListener;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;

import java.awt.EventQueue;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The review's one reusable diff: hosted in the review workbench tab
 * ({@link ReviewWorkbenchEditor}), whose every file selection reuses it.
 * {@link #show} swaps the current request into the live processor in place;
 * when the workbench was closed (its processor disposed) it reopens the
 * workbench, and the processor the new workbench editor creates
 * ({@link #host}) is seeded with the current request — a processor whose
 * request is null applies nothing on its first {@code selectNotify ->
 * updateRequest}, and the tab would stay blank until the second selection
 * (cairn: {@code intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam}).
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

    /**
     * The diff settings place of the review's diff: its viewer settings
     * (collapse unchanged, context range, viewer mode) are its own, apart
     * from the IDE's other diffs.
     */
    public static final String PLACE = "DrydockReviewWorkbench";

    /** What the diff says before a file is picked. */
    static final String NO_FILE_TEXT = "Select a file to see its diff.";

    /**
     * Opens (or reopens) the review workbench tab. Production opens the
     * project's {@link ReviewWorkbenchFile} through
     * {@code FileEditorManager.openFile}; the workbench editor the platform
     * creates then asks {@link #host} for the processor.
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
     * The production wiring: the project's {@link ReviewWorkbenchFile} opened
     * through {@code FileEditorManager}, requests built by
     * {@link ReviewDiffRequests}, viewer changes dispatched to
     * {@code inlays}.
     */
    public static ReviewDiffEditor platform(Project project, Disposable parent,
                                            ReviewDiffInlays inlays) {
        Objects.requireNonNull(inlays, "inlays");
        return platform(project, parent, inlays::viewerChanged);
    }

    /** As {@link #platform(Project, Disposable, ReviewDiffInlays)}, with every viewer change dispatched to {@code onViewerChanged}. */
    public static ReviewDiffEditor platform(Project project, Disposable parent,
                                            Consumer<FrameDiffTool.DiffViewer> onViewerChanged) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(onViewerChanged, "onViewerChanged");
        return new ReviewDiffEditor(
                () -> FileEditorManager.getInstance(project).openFile(ReviewWorkbenchFile.of(project), true),
                context -> ReviewDiffRequests.toRequest(project, parent, context),
                onViewerChanged);
    }

    /**
     * The diff for a newly created workbench editor: a processor seeded with
     * the current request ({@link #newProcessor}), disposed with
     * {@code parent}. EDT.
     */
    ReviewWorkbenchEditor.HostedDiff host(Project project, Disposable parent) {
        requireEdt();
        DiffRequestProcessor created = newProcessor(project);
        Disposer.register(parent, created);
        // A DiffVirtualFile tab's editor applies the seeded request on
        // selection; an embedded processor has no such editor, so it applies
        // it here, or it would show the platform's loading state until the
        // next selection.
        created.updateRequest();
        return new ReviewWorkbenchEditor.HostedDiff(created.getComponent(), created::getPreferredFocusedComponent);
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
     * Shows {@code view} (EDT): sets the place's collapse-unchanged setting
     * and shows the current request again, so the viewer refolds with the
     * new setting. Nothing to show yet only stores the setting.
     */
    public void showView(WorkbenchState.View view) {
        requireEdt();
        applyView(TextDiffSettingsHolder.TextDiffSettings.getSettings(PLACE), view);
        ReviewDiffRequests.RequestContext current = context;
        ProcessorHandle live = processor;
        if (current != null && live != null && !live.isDisposed()) {
            live.apply(requests.build(current));
        }
    }

    /** The view's setting (pure over the settings object): the whole file expands, changes only collapses. */
    static void applyView(TextDiffSettingsHolder.TextDiffSettings settings, WorkbenchState.View view) {
        settings.setExpandByDefault(view == WorkbenchState.View.FULL_FILE);
    }

    /** The view the place's stored setting stands for: the setting persists with the IDE's diff settings. */
    static WorkbenchState.View storedView() {
        return TextDiffSettingsHolder.TextDiffSettings.getSettings(PLACE).isExpandByDefault()
                ? WorkbenchState.View.FULL_FILE
                : WorkbenchState.View.CHANGES_ONLY;
    }

    /**
     * The request a newly created processor is seeded with: the current
     * context's request (the seeding the spike demanded), or, when no file
     * has been picked yet, a message asking for one ({@link #NO_FILE_TEXT};
     * the platform's loading request is internal API on 263). EDT — the
     * tab's processor is created while the EDT opens the tab.
     */
    DiffRequest seedRequest() {
        requireEdt();
        ReviewDiffRequests.RequestContext current = context;
        return current == null ? new MessageDiffRequest(NO_FILE_TEXT) : requests.build(current);
    }

    /** The processor the platform just created for the (re)opened tab; also the tests' install point. */
    void processorReady(ProcessorHandle created) {
        requireEdt();
        processor = Objects.requireNonNull(created, "created");
    }

    /**
     * The processor for a newly (re)opened workbench: seeded with
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
     * The live processor: holds the current request (the platform's
     * {@code SimpleDiffVirtualFile} shape), so its first
     * {@code selectNotify -> updateRequest} applies the seeded request and
     * every later {@link #apply} swaps the next request in place.
     */
    private static final class Processor extends DiffRequestProcessor implements ProcessorHandle {

        private DiffRequest request;

        Processor(Project project, DiffRequest seed) {
            super(project, PLACE);
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
