package app.drydock.intellij.editorqa;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import com.intellij.collaboration.ui.codereview.editor.EditorComponentInlaysUtilKt;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;

import app.drydock.editorqa.EditorAskAnchor;
import app.drydock.editorqa.EditorAskAnchorValidator;
import app.drydock.editorqa.EditorAskAnchorValidator.DocumentView;
import app.drydock.editorqa.EditorAskAnchorValidator.TrackedUpdate;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.state.json.JsonValue;
import kotlin.jvm.functions.Function1;

/**
 * Shows each editor question as a foldable card below its last selected
 * line, in every normal editor of the file, and keeps it honest.
 *
 * <p><b>Lifecycle.</b> An {@link EditorAskEditorObserver} enumerates the
 * existing editors and follows create/release. Each editor has one state
 * (a generation counter, a document listener, a map of per-exchange-ID
 * handles); a handle owns one live non-greedy {@link RangeMarker}, one card
 * and one inlay, all released together. Every asynchronous completion
 * re-checks the state's generation and disposal before touching anything, so
 * a discarded editor is never updated. Model changes, attaches and edits
 * each coalesce into one pending refresh / one pending tracking drain.</p>
 *
 * <p><b>Never wrong, never lost.</b> A card appears only after the selection
 * is verified intact (live handle: {@link EditorAskAnchorValidator#retrack};
 * reopened document: {@code validate} then a <em>fresh</em> marker), hashed
 * on a background executor from an immutable text view. An invalid, replaced,
 * deleted or ambiguous selection simply has no card; the record stays in the
 * model and the store, and nothing searches for similar text. Edits above an
 * intact selection retrack the anchor (coalesced) and persist it, also when
 * the editor closes or the presenter shuts down. Nothing here writes the
 * document, so dirty and undo state are untouched.</p>
 */
public final class EditorAskInlayPresenter implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EditorAskInlayPresenter.class.getName());
    private static final Function1<Inlay<?>, GutterIconRenderer> NO_GUTTER = inlay -> null;

    /** Where a file belongs: its canonical checkout and checkout-relative path. */
    public record Location(String checkout, String relativeFile) {
        public Location {
            Objects.requireNonNull(checkout, "checkout");
            Objects.requireNonNull(relativeFile, "relativeFile");
        }
    }

    /** What the presenter needs from the plugin; production wiring supplies it. */
    public interface Source {
        /** The owning checkout of {@code file}; blocking (git), called off the EDT; empty when none. */
        Optional<Location> locate(Path file);

        /** Persists the best-known anchor of {@code id}; may block, called off the EDT. */
        void persistAnchor(String checkout, String id, JsonValue anchor);

        /** The "Open session" navigation for the exchange; EDT. */
        void openSession(Project project, EditorAskRecord record);
    }

    /** Immutable document content: copied text plus the stamp it was taken at. */
    private record View(String checkoutRoot, String relativeFile, CharSequence content, long stamp)
            implements DocumentView {
    }

    private static final class Handle {
        final String id;
        EditorAskRecord record;
        RangeMarker marker;
        EditorAskAnchor anchor;
        EditorAskInlayCard card;
        Runnable inlayRelease;
        int cardLine = -1;
        boolean verifying;
        boolean rejected;
        final EditorAskAnchorValidator.UpdateCoalescer coalescer = new EditorAskAnchorValidator.UpdateCoalescer();

        Handle(String id) {
            this.id = id;
        }
    }

    private static final class State {
        final Editor editor;
        final Disposable lifetime = Disposer.newDisposable("editor-ask-editor");
        final Map<String, Handle> handles = new HashMap<>();
        Location location;
        boolean locating;
        boolean disposed;
        long generation;

        State(Editor editor) {
            this.editor = editor;
        }
    }

    private final Project project;
    private final EditorAskUiModel model;
    private final Source source;
    private final Executor background;
    private final Executor edt;
    private final ZoneId zone;
    private final Disposable lifetime = Disposer.newDisposable("editor-ask-presenter");
    private final EditorAskEditorObserver observer;
    private final Runnable removeModelListener;
    private final Map<Editor, State> states = new HashMap<>();
    private final Set<CompletableFuture<Void>> inflight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private boolean closed;
    private boolean lifetimeDisposing;

    /** The production presenter: platform editors, the EDT queue. Create on the EDT. */
    public static EditorAskInlayPresenter platform(
            Project project, Disposable parent, EditorAskUiModel model, Source source, Executor background) {
        return new EditorAskInlayPresenter(project, parent, EditorAskEditorObserver.platformSources(), model,
                source, background,
                runnable -> ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any()),
                ZoneId.systemDefault());
    }

    public EditorAskInlayPresenter(Project project, Disposable parent, EditorAskEditorObserver.Sources sources,
                                   EditorAskUiModel model, Source source, Executor background, Executor edt,
                                   ZoneId zone) {
        this.project = Objects.requireNonNull(project, "project");
        this.model = Objects.requireNonNull(model, "model");
        this.source = Objects.requireNonNull(source, "source");
        this.background = Objects.requireNonNull(background, "background");
        this.edt = Objects.requireNonNull(edt, "edt");
        this.zone = Objects.requireNonNull(zone, "zone");
        Disposer.register(Objects.requireNonNull(parent, "parent"), lifetime);
        Disposer.register(lifetime, () -> {
            lifetimeDisposing = true;
            close();
        });
        this.observer = new EditorAskEditorObserver(project, sources, new EditorAskEditorObserver.Callback() {
            @Override public void attached(Editor editor) { attach(editor); }

            @Override public void released(Editor editor) { release(editor); }
        }, edt);
        this.removeModelListener = model.addListener(this::refreshLater);
        observer.start(lifetime);
    }

    // ---- adoption of a freshly captured selection --------------------------------

    /**
     * Takes over the live marker of a just-captured selection for exchange
     * {@code id} in {@code editor}, so the card follows the range from capture
     * through the response. EDT. Returns false -- the caller keeps ownership of
     * the snapshot -- if the editor is not attached or the ID is already held.
     */
    public boolean adopt(Editor editor, EditorAskCapture.Snapshot snapshot, Location where, String id) {
        State state = states.get(editor);
        if (closed || state == null || state.disposed || state.handles.containsKey(id)) {
            return false;
        }
        Handle handle = new Handle(id);
        handle.marker = snapshot.marker();
        try {
            handle.anchor = EditorAskAnchor.capture(where.checkout(), where.relativeFile(), snapshot.document(),
                    snapshot.range().startOffset(), snapshot.range().endOffset());
        } catch (RuntimeException e) {
            handle.rejected = true;
        }
        state.handles.put(id, handle);
        if (state.location == null) {
            state.location = where;
        }
        refreshLater();
        return true;
    }

    // ---- editor lifecycle --------------------------------------------------------

    private void attach(Editor editor) {
        if (closed || states.containsKey(editor)) {
            return;
        }
        State state = new State(editor);
        Disposer.register(lifetime, state.lifetime);
        states.put(editor, state);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) {
                onEdited(state);
            }
        }, state.lifetime);
        locate(state);
    }

    private void locate(State state) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(state.editor.getDocument());
        if (file == null || !file.isInLocalFileSystem() || state.locating) {
            return;
        }
        Path path = file.toNioPath();
        state.locating = true;
        long gen = state.generation;
        background(() -> {
            Optional<Location> found = Optional.empty();
            try {
                found = source.locate(path);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "locating the checkout of an editor failed", e);
            }
            Optional<Location> result = found;
            edt.execute(() -> {
                if (!current(state, gen)) {
                    return;
                }
                state.locating = false;
                result.ifPresent(l -> state.location = l);
                refresh(state);
            });
        });
    }

    /** The editor was released: persist the final tracked anchors, then free everything. */
    private void release(Editor editor) {
        State state = states.remove(editor);
        if (state != null) {
            disposeState(state);
        }
    }

    private void disposeState(State state) {
        if (state.disposed) {
            return;
        }
        // Pending tracking for an intact selection is flushed from an immutable
        // view before the marker goes (close/shutdown must not lose it).
        for (Handle h : state.handles.values()) {
            try {
                flushFinal(state, h);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "final anchor tracking failed", e);
            }
        }
        state.disposed = true;
        state.generation++;
        for (Handle h : state.handles.values()) {
            releaseHandle(h);
        }
        state.handles.clear();
        try {
            Disposer.dispose(state.lifetime);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor listener release failed", e);
        }
    }

    private boolean current(State state, long gen) {
        return !closed && !state.disposed && state.generation == gen && !state.editor.isDisposed();
    }

    private void background(Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        inflight.add(done);
        try {
            background.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "editor ask inlay work failed", e);
                } finally {
                    inflight.remove(done);
                    done.complete(null);
                }
            });
        } catch (RuntimeException e) {
            inflight.remove(done);
            LOG.log(Level.WARNING, "editor ask inlay work rejected", e);
        }
    }

    /** Waits for background tracking/persistence submitted so far (shutdown, tests); never on the EDT. */
    public void flush(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (CompletableFuture<Void> f : new ArrayList<>(inflight)) {
            try {
                f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "waiting for editor ask inlay work ended early", e);
                return;
            }
        }
    }

    // ---- refresh: model rows -> handles -> verified cards -------------------------

    /** Coalesces any number of model/attach events into one refresh pass on the EDT. */
    private void refreshLater() {
        if (closed || !refreshPending.compareAndSet(false, true)) {
            return;
        }
        edt.execute(() -> {
            refreshPending.set(false);
            if (!closed) {
                for (State state : new ArrayList<>(states.values())) {
                    refresh(state);
                }
            }
        });
    }

    private void refresh(State state) {
        if (closed || state.disposed || state.location == null || state.editor.isDisposed()) {
            return;
        }
        Location where = state.location;
        Set<String> seen = new HashSet<>();
        for (EditorAskUiModel.Row row : model.rows(where.checkout())) {
            EditorAskRecord rec = row.record();
            if (rec.isTab() || !where.relativeFile().equals(rec.relativeFile())) {
                continue;
            }
            seen.add(rec.id());
            Handle h = state.handles.get(rec.id());
            if (h == null) {
                h = new Handle(rec.id());
                h.anchor = EditorAskAnchor.fromJson(rec.anchor()).orElse(null);
                state.handles.put(rec.id(), h);
            }
            h.record = rec;
            if (h.rejected) {
                continue;
            }
            if (h.card != null) {
                h.card.update(rec);
            } else if (!h.verifying) {
                verify(state, h);
            }
        }
        // A record the model no longer mirrors (evicted) loses its card; one not yet mirrored keeps its marker.
        for (var it = state.handles.values().iterator(); it.hasNext(); ) {
            Handle h = it.next();
            if (h.record != null && !seen.contains(h.id)) {
                releaseHandle(h);
                it.remove();
            }
        }
    }

    private View view(State state) {
        Document doc = state.editor.getDocument();
        Location where = state.location;
        return new View(where.checkout(), where.relativeFile(), doc.getImmutableCharSequence(),
                doc.getModificationStamp());
    }

    /** Verifies a handle's selection off the EDT from an immutable view, then shows its card. */
    private void verify(State state, Handle h) {
        boolean live = h.marker != null;
        if (h.anchor == null || (live && !h.marker.isValid())) {
            reject(h);
            return;
        }
        h.verifying = true;
        long gen = state.generation;
        View view = view(state);
        EditorAskAnchor anchor = h.anchor;
        int start = live ? h.marker.getStartOffset() : -1;
        int end = live ? h.marker.getEndOffset() : -1;
        background(() -> {
            Optional<EditorAskAnchor> result = live
                    ? EditorAskAnchorValidator.retrack(anchor, new TrackedUpdate(view, start, end))
                    : EditorAskAnchorValidator.validate(anchor, view);
            edt.execute(() -> verified(state, gen, h, view.stamp(), live, result));
        });
    }

    private void verified(State state, long gen, Handle h, long stamp, boolean live, Optional<EditorAskAnchor> result) {
        h.verifying = false;
        if (!current(state, gen) || state.handles.get(h.id) != h) {
            return;
        }
        if (state.editor.getDocument().getModificationStamp() != stamp) {
            verify(state, h); // the text moved while hashing: judge the newer text
            return;
        }
        if (result.isEmpty()) {
            reject(h);
            return;
        }
        EditorAskAnchor verifiedAnchor = result.get();
        boolean changed = !verifiedAnchor.equals(h.anchor);
        h.anchor = verifiedAnchor;
        if (!live) {
            RangeMarker marker = state.editor.getDocument()
                    .createRangeMarker(verifiedAnchor.startOffset(), verifiedAnchor.endOffset());
            marker.setGreedyToLeft(false);
            marker.setGreedyToRight(false);
            h.marker = marker;
        } else if (changed) {
            persist(state, h, verifiedAnchor);
        }
        placeCard(state, h);
    }

    /** No restorable selection: no card, marker freed, record untouched. */
    private void reject(Handle h) {
        h.rejected = true;
        releaseHandle(h);
    }

    // ---- card placement ------------------------------------------------------------

    private void placeCard(State state, Handle h) {
        if (h.marker == null || !h.marker.isValid() || !(state.editor instanceof EditorEx editor)) {
            reject(h);
            return;
        }
        Document doc = editor.getDocument();
        int line = doc.getLineNumber(
                EditorAskAnchorValidator.cardLineOffset(h.marker.getStartOffset(), h.marker.getEndOffset()));
        if (h.card != null && h.cardLine == line) {
            return;
        }
        removeCard(h);
        EditorAskRecord rec = h.record;
        EditorAskInlayCard card = new EditorAskInlayCard(rec, zone, () -> source.openSession(project, h.record));
        Inlay<?> inlay = EditorComponentInlaysUtilKt.insertComponentAfter(editor, line, card, 0, NO_GUTTER);
        if (inlay == null) {
            card.dispose();
            return;
        }
        h.card = card;
        h.cardLine = line;
        h.inlayRelease = () -> {
            if (inlay.isValid()) {
                inlay.dispose();
            }
        };
    }

    private void removeCard(Handle h) {
        if (h.card != null) {
            h.card.dispose();
            h.card = null;
        }
        Runnable release = h.inlayRelease;
        h.inlayRelease = null;
        h.cardLine = -1;
        if (release != null) {
            try {
                release.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "card release failed", e);
            }
        }
    }

    /** Frees the handle's card, inlay and marker; each step isolated, idempotent. */
    private void releaseHandle(Handle h) {
        removeCard(h);
        RangeMarker marker = h.marker;
        h.marker = null;
        if (marker != null) {
            try {
                marker.dispose();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "range marker release failed", e);
            }
        }
    }

    // ---- tracking edits above an intact selection -----------------------------------

    private void onEdited(State state) {
        if (closed || state.disposed || state.location == null) {
            return;
        }
        long gen = state.generation;
        for (Handle h : new ArrayList<>(state.handles.values())) {
            if (h.rejected || h.card == null || h.marker == null) {
                continue; // not shown yet: verification judges the text it is given
            }
            if (!h.marker.isValid()) {
                reject(h); // the selection was deleted or replaced
                continue;
            }
            TrackedUpdate update = new TrackedUpdate(view(state), h.marker.getStartOffset(), h.marker.getEndOffset());
            if (h.coalescer.submit(update)) {
                background(() -> drain(state, gen, h));
            }
        }
    }

    private void drain(State state, long gen, Handle h) {
        Optional<TrackedUpdate> update = h.coalescer.drain();
        EditorAskAnchor before = h.anchor;
        if (update.isEmpty() || before == null) {
            return;
        }
        Optional<EditorAskAnchor> result = EditorAskAnchorValidator.retrack(before, update.get());
        long stamp = ((View) update.get().view()).stamp();
        edt.execute(() -> {
            if (!current(state, gen) || state.handles.get(h.id) != h
                    || state.editor.getDocument().getModificationStamp() != stamp) {
                return; // a newer edit has its own pending update
            }
            if (result.isEmpty()) {
                reject(h); // the text at the range is no longer the selection
                return;
            }
            if (!result.get().equals(h.anchor)) {
                h.anchor = result.get();
                persist(state, h, h.anchor);
            }
            placeCard(state, h);
        });
    }

    /**
     * Editor close / shutdown: tracks the last edits from an immutable view
     * and persists the anchor without hopping back to the (going) editor.
     */
    private void flushFinal(State state, Handle h) {
        if (h.rejected || h.card == null || h.marker == null || !h.marker.isValid()
                || h.anchor == null || state.location == null) {
            return;
        }
        TrackedUpdate update = new TrackedUpdate(view(state), h.marker.getStartOffset(), h.marker.getEndOffset());
        EditorAskAnchor before = h.anchor;
        String checkout = state.location.checkout();
        background(() -> EditorAskAnchorValidator.retrack(before, update).ifPresent(anchor -> {
            if (!anchor.equals(before)) {
                persistNow(checkout, h.id, anchor);
            }
        }));
    }

    private void persist(State state, Handle h, EditorAskAnchor anchor) {
        String checkout = state.location.checkout();
        background(() -> persistNow(checkout, h.id, anchor));
    }

    private void persistNow(String checkout, String id, EditorAskAnchor anchor) {
        try {
            source.persistAnchor(checkout, id, anchor.toJson());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "persisting an editor ask anchor failed", e);
        }
    }

    // ---- shutdown --------------------------------------------------------------------

    /** Releases every editor's markers, cards and listeners, each in isolation. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            edt.execute(this::close);
            return;
        }
        closed = true;
        removeModelListener.run();
        try {
            observer.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor observer close failed", e);
        }
        for (State state : new ArrayList<>(states.values())) {
            try {
                disposeState(state);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "editor state release failed", e);
            }
        }
        states.clear();
        if (!lifetimeDisposing) {
            Disposer.dispose(lifetime);
        }
    }

    /** Test/diagnostic: how many editors are attached. */
    int attachedEditors() {
        return states.size();
    }

    /** Test/diagnostic: the exchange IDs currently shown as cards in {@code editor}. */
    Set<String> shownIds(Editor editor) {
        State state = states.get(editor);
        Set<String> ids = new HashSet<>();
        if (state != null) {
            state.handles.forEach((id, h) -> {
                if (h.card != null) {
                    ids.add(id);
                }
            });
        }
        return ids;
    }

    /** Test/diagnostic: the card of {@code id} in {@code editor}, or null. */
    EditorAskInlayCard cardOf(Editor editor, String id) {
        State state = states.get(editor);
        Handle h = state == null ? null : state.handles.get(id);
        return h == null ? null : h.card;
    }

    /** Test/diagnostic: the document line hosting the card of {@code id}, or -1. */
    int cardLineOf(Editor editor, String id) {
        State state = states.get(editor);
        Handle h = state == null ? null : state.handles.get(id);
        return h == null ? -1 : h.cardLine;
    }
}
