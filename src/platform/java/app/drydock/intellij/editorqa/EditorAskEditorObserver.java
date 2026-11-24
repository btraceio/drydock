package app.drydock.intellij.editorqa;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;

/**
 * The per-project editor lifecycle of the inline Q&A cards: enumerates the
 * normal editors that already exist when it starts, then follows creation and
 * release (precedent {@code ReviewEditorHighlighters.platformSources}).
 *
 * <p>Only normal main editors of this project showing a real local file
 * qualify; diff, console, injected and in-memory editors never do. Every
 * notification is delivered through the injected EDT executor and
 * de-duplicated: an editor is attached once, released once, and an event for
 * an editor that is already gone, or that arrives after close, is dropped, so the
 * listener never hears about a discarded editor. It does no blocking work.</p>
 */
public final class EditorAskEditorObserver implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EditorAskEditorObserver.class.getName());

    /** Where editors come from; fakes pin it in tests. */
    public interface Sources {
        /** Every open editor, any project and kind. */
        List<Editor> editors();

        /** Registers the platform listener; the returned handle unregisters it. */
        Runnable register(Listener listener, Disposable parent);
    }

    /** The raw editor life events, delivered on the platform's thread. */
    public interface Listener {
        void editorCreated(Editor editor);

        void editorReleased(Editor editor);
    }

    /** The qualified-editor life the presenter reacts to; always on the EDT executor. */
    public interface Callback {
        void attached(Editor editor);

        void released(Editor editor);
    }

    private final Project project;
    private final Sources sources;
    private final Callback callback;
    private final Executor edt;
    private final Set<Editor> attached = new HashSet<>();
    private Runnable unregister = () -> { };
    private boolean closed;

    public EditorAskEditorObserver(Project project, Sources sources, Callback callback, Executor edt) {
        this.project = Objects.requireNonNull(project, "project");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.callback = Objects.requireNonNull(callback, "callback");
        this.edt = Objects.requireNonNull(edt, "edt");
    }

    /**
     * Registers for create/release, then attaches every existing qualified
     * editor. EDT only. The registration is tied to {@code parent}, so the
     * platform drops it with the project even if {@link #close} is missed.
     */
    public void start(Disposable parent) {
        unregister = sources.register(new Listener() {
            @Override public void editorCreated(Editor editor) { edt.execute(() -> attach(editor)); }

            @Override public void editorReleased(Editor editor) { edt.execute(() -> release(editor)); }
        }, Objects.requireNonNull(parent, "parent"));
        for (Editor editor : new ArrayList<>(sources.editors())) {
            attach(editor);
        }
    }

    private void attach(Editor editor) {
        if (closed || attached.contains(editor) || !eligible(project, editor)) {
            return;
        }
        attached.add(editor);
        try {
            callback.attached(editor);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor attach failed", e);
        }
    }

    private void release(Editor editor) {
        if (attached.remove(editor)) {
            try {
                callback.released(editor);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "editor release failed", e);
            }
        }
    }

    /** Stops listening and releases every attached editor, each in isolation. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            unregister.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor listener removal failed", e);
        }
        for (Editor editor : new ArrayList<>(attached)) {
            release(editor);
        }
    }

    /** A live normal main editor of {@code project} for a real local file backed by its document. */
    static boolean eligible(Project project, Editor editor) {
        if (editor == null || editor.isDisposed() || editor.getProject() != project
                || editor.getEditorKind() != EditorKind.MAIN_EDITOR
                || editor instanceof EditorWindow || editor.getDocument() instanceof DocumentWindow) {
            return false;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && !(file instanceof LightVirtualFile) && file.isValid() && !file.isDirectory()
                && file.getFileSystem() == LocalFileSystem.getInstance();
    }

    /** The production seam: {@code EditorFactory} enumeration and listener registration. */
    public static Sources platformSources() {
        return new Sources() {
            @Override public List<Editor> editors() {
                return List.of(EditorFactory.getInstance().getAllEditors());
            }

            @SuppressWarnings("removal") // explicit unregister for an early close; the parent covers project disposal
            @Override public Runnable register(Listener listener, Disposable parent) {
                EditorFactoryListener platform = new EditorFactoryListener() {
                    @Override public void editorCreated(EditorFactoryEvent event) {
                        listener.editorCreated(event.getEditor());
                    }

                    @Override public void editorReleased(EditorFactoryEvent event) {
                        listener.editorReleased(event.getEditor());
                    }
                };
                EditorFactory.getInstance().addEditorFactoryListener(platform, parent);
                return () -> EditorFactory.getInstance().removeEditorFactoryListener(platform);
            }
        };
    }
}
