package app.drydock.intellij.review;

import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;

import javax.swing.JComponent;
import java.awt.Window;
import java.util.Objects;

/**
 * Where the Review tab's diff preview lives (AC17): the placement rule
 * as a pure function, the {@link Host} the tab feeds, and the selection
 * feed that keeps one preview tracking the tree's current selection.
 *
 * <p><strong>Placement.</strong> {@link #useSplitter} mirrors the
 * platform rule exactly (bytecode-verified on IU-263.7031.23,
 * {@code ChangesViewContentManager$Companion
 * .shouldHaveSplitterDiffPreview}): the preview sits in a splitter beside
 * the tree when the tool window's content is horizontal or when the
 * Commit tool window is not enabled, and in an editor-tab preview
 * otherwise. Production asks the platform itself through
 * {@code ChangesViewContentManager.shouldHaveSplitterDiffPreview(project,
 * isContentVertical)} — T13's call site; this pure mirror is what the
 * headless decision-matrix test pins.</p>
 *
 * <p><strong>Hosting (the encoded fallback, and why).</strong> The
 * plan's first choice was the platform's tree-handler stack
 * ({@code TreeHandlerEditorDiffPreview} over
 * {@code ChangeViewDiffRequestProcessor}). Bytecode on 263 rules that
 * out: {@code ChangeViewDiffRequestProcessor} carries
 * {@code @ApiStatus.Internal} members, the editor-tab half opens through
 * the diff editor-tab machinery keyed on a {@code VirtualFile} — built
 * for real {@code Change} trees, not Drydock's prebuilt requests — and
 * the whole pattern is pull-based (producers read the tree selection on
 * the EDT) while this tab's wiring is push-based (T13 builds the request
 * on a background executor and pushes it). Both hosts are therefore the
 * plan's encoded fallback: one reused
 * {@link DiffManager#createRequestPanel} panel each, splitter case in
 * the tab, editor-tab case in one reused preview surface. Both are fed
 * T6's requests, which carry {@code CUSTOM_DIFF_COMPUTER},
 * {@code FORCE_READ_ONLY}, {@code SCROLL_TO_LINE} and the Drydock
 * request key ({@link ReviewDiffRequests#REQUEST_KEY}; verified live on
 * {@code SimpleDiffRequest} in the P2 spike). The placement rule and the
 * {@link Host} interface are unchanged; only the host changed. The live
 * half is AC24, which may swap the editor-tab host alone
 * ({@code kb/find-263-diff-api-internal} — the internal-visibility risk
 * this fallback exists for; {@code kb/find-p2-spikes-live-results} —
 * the UX finding that put a preview beside the tree).</p>
 *
 * <p><strong>Threading.</strong> {@link Host#show} is called on the EDT
 * — the caller hops (T13); the hosts enforce it. Nothing in this tab
 * opens the platform's floating diff window (AC17's grep gate).</p>
 */
public final class ReviewDiffPreview {

    private ReviewDiffPreview() {
    }

    /**
     * The placement decision, mirroring the platform rule: splitter when
     * the tool window content is horizontal or the Commit tool window is
     * not enabled; editor-tab preview only when the content is vertical
     * <em>and</em> the Commit tool window is enabled.
     *
     * @param contentVertical true when the host tool window stacks its
     *                        content vertically (docked left/right)
     * @param commitToolWindowEnabled whether the Commit tool window is
     *                        enabled for the project
     */
    public static boolean useSplitter(boolean contentVertical, boolean commitToolWindowEnabled) {
        return !contentVertical || !commitToolWindowEnabled;
    }

    /**
     * The preview surface the tab feeds. One preview exists per tab; a
     * selection re-targets it, it is never re-created per file.
     *
     * <p>{@code request} is T6's {@link ReviewDiffRequests#toRequest}
     * output, so it already carries the review user data
     * ({@code CUSTOM_DIFF_COMPUTER}, {@code FORCE_READ_ONLY},
     * {@code SCROLL_TO_LINE}, the Drydock request key). {@link #show}
     * runs on the EDT — the caller hops; implementations enforce it.
     * {@link #dispose} releases the preview exactly once; {@code show}
     * after {@code dispose} does nothing.</p>
     */
    public interface Host {
        /** Re-targets the preview to {@code request}, on the EDT. */
        void show(SimpleDiffRequest request);

        /** Releases the preview; idempotent. */
        void dispose();
    }

    /**
     * Creates the preview host for a resolved placement.
     *
     * @param project the tab's project
     * @param parent the disposal the panel registers on (the platform's
     * {@code DiffManagerImpl.createRequestPanel} registers the panel on
     * it — bytecode); {@link Host#dispose()} releases the panel early,
     * so either order is safe
     * @param splitter the placement decision from {@link #useSplitter}
     * @param owner the window the preview restores focus to; may be null
     */
    public static Host createHost(Project project, Disposable parent, boolean splitter, Window owner) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(parent, "parent");
        return splitter ? new SplitterHost(project, parent, owner)
                : new EditorTabHost(project, parent, owner);
    }

    /**
     * The component the host renders its preview into, for the tab's
     * layout (T13 embeds it beside the tree on the splitter branch, and
     * reads it at assembly time, before any dispose). Package-private on
     * purpose: the pinned {@link Host} surface stays
     * {@code show}/{@code dispose}; the layout lives in this package.
     */
    static JComponent component(Host host) {
        Objects.requireNonNull(host, "host");
        if (host instanceof SplitterHost splitter) {
            return splitter.component();
        }
        if (host instanceof EditorTabHost editorTab) {
            return editorTab.component();
        }
        throw new IllegalArgumentException("not a ReviewDiffPreview host: " + host);
    }

    /**
     * Feeds selection-built requests to one preview host: only the
     * current selection's request is ever shown, in arrival order (AC17's
     * "tree selection drives one preview"). The tab's wiring (T13) is
     * push-based — the request is built on a background executor and
     * pushed when ready — so a completion that lands after the user has
     * moved to another row must not repaint the preview over the newer
     * selection.
     *
     * <p>Both methods take the same selection token (whatever T13 uses
     * to identify a selection — a tree row), compared by {@code equals}.
     * Both are called on the EDT; {@link #requestReady} forwards to
     * {@link Host#show}.</p>
     */
    public static final class SelectionPreview {

        private final Host host;
        /** The selection whose request may still be shown; null until the first selection. */
        private Object current;

        public SelectionPreview(Host host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        /**
         * The user picked {@code selectionId}: requests for any earlier
         * selection are stale from here on.
         */
        public void selection(Object selectionId) {
            current = Objects.requireNonNull(selectionId, "selectionId");
        }

        /**
         * A selection's request finished building: shown, in arrival
         * order, iff it is still the current selection. A request that
         * arrives before any selection has been made is dropped —
         * nothing has been picked to preview.
         */
        public void requestReady(Object selectionId, SimpleDiffRequest request) {
            Objects.requireNonNull(selectionId, "selectionId");
            Objects.requireNonNull(request, "request");
            if (!Objects.equals(current, selectionId)) {
                return;
            }
            host.show(request);
        }
    }

    // ---- hosts (the encoded fallback: one reused request panel each) ------

    /**
     * The splitter branch: the panel exists from assembly time, because
     * its component is embedded in the tab layout beside the tree (the
     * platform's {@code PreviewDiffSplitterComponent} pattern — the
     * tab's own layout, built by T13, embeds {@link #component(Host)}).
     */
    static final class SplitterHost implements Host {

        private final DiffRequestPanel panel;
        private boolean disposed;

        SplitterHost(Project project, Disposable parent, Window owner) {
            panel = DiffManager.getInstance().createRequestPanel(project, parent, owner);
        }

        @Override
        public void show(SimpleDiffRequest request) {
            requireEdt();
            if (disposed) {
                return;
            }
            panel.setRequest(Objects.requireNonNull(request, "request"));
        }

        @Override
        public void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            Disposer.dispose(panel);
        }

        JComponent component() {
            return panel.getComponent();
        }
    }

    /**
     * The editor-tab branch (vertical content in the enabled Commit tool
     * window): one reused preview that re-targets on every selection,
     * created on first use — nothing exists until there is something to
     * show, the way the platform's editor-tab preview opens on demand.
     * Fallback note: the platform opens a real editor tab through
     * internal machinery keyed on a {@code VirtualFile}; this host keeps
     * one reused panel whose component T13 can surface, and is the half
     * AC24's live check may replace without touching the splitter branch.
     */
    static final class EditorTabHost implements Host {

        private final Project project;
        private final Disposable parent;
        private final Window owner;

        /** One reused panel, created on first use; EDT-confined (like the rest of the preview). */
        private DiffRequestPanel panel;
        private boolean disposed;

        EditorTabHost(Project project, Disposable parent, Window owner) {
            this.project = project;
            this.parent = parent;
            this.owner = owner;
        }

        @Override
        public void show(SimpleDiffRequest request) {
            requireEdt();
            if (disposed) {
                return;
            }
            panel().setRequest(Objects.requireNonNull(request, "request"));
        }

        @Override
        public void dispose() {
            disposed = true;
            if (panel != null) {
                Disposer.dispose(panel);
            }
        }

        JComponent component() {
            if (disposed) {
                return null;
            }
            return panel().getComponent();
        }

        private DiffRequestPanel panel() {
            if (panel == null) {
                panel = DiffManager.getInstance().createRequestPanel(project, parent, owner);
            }
            return panel;
        }
    }

    private static void requireEdt() {
        Application application = ApplicationManager.getApplication();
        if (application != null && !application.isDispatchThread()) {
            throw new IllegalStateException("Host.show must be called on the EDT (the caller hops)");
        }
    }
}
