package app.drydock.intellij.agent;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorComposite;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A one-shot record of the editor split layout, so a step that disturbs it (the legacy-tab
 * cleanup) can put the user's code view back. EDT only; public SDK calls only.
 *
 * <p>Captured: per editor window, the selected file that is not a legacy {@link AgentConsoleFile},
 * the current window, and the window and component holding the keyboard focus. Restored without
 * stealing: {@code setSelectedComposite(file, false)} and {@code setAsCurrentWindow(false)} (what
 * {@code setCurrentWindow(focusEditor=false)} maps to); focus goes back only to a still-showing
 * owner in a surviving window, and only if focus has not moved outside the editor area.</p>
 *
 * <p>Never over a later user action: the snapshot carries its epoch. A selection event that leaves
 * the layout different from the one last captured or left behind, and {@link #invalidate()},
 * advance the epoch, and an older snapshot is dropped. Limitation: the manager publishes
 * {@code selectionChanged} after the call returns and not for a non-current window, and a pure
 * current-window click publishes nothing, so those user actions are invisible here; callers
 * call {@link #invalidate()} for actions they know of. Closed windows and files are skipped. A
 * restore consumes the snapshot. Disposal clears it and makes every later call a no-op.</p>
 */
public final class SessionLayoutSnapshot implements Disposable {

    /** What {@link #restore()} did: replayed, nothing to replay, rejected as stale, or disposed. */
    public enum Outcome { RESTORED, NOTHING, STALE, DISPOSED }

    private record Selection(EditorWindow window, VirtualFile file) { }

    /** The visible layout: every window's selected file, and the current window. */
    private record Layout(List<Selection> selections, EditorWindow current) { }

    private record Captured(long epoch, List<Selection> selections, EditorWindow current, EditorWindow focusWindow,
                            Component focusOwner) { }

    private final Project project;
    private final MessageBusConnection connection;
    private long epoch;
    private Layout settled;
    private boolean disposed;
    private Captured captured;

    public SessionLayoutSnapshot(@NotNull Project project, @NotNull Disposable parent) {
        this.project = Objects.requireNonNull(project, "project");
        Disposer.register(parent, this);
        connection = project.getMessageBus().connect(this);
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                // The manager publishes after the call returns, so an echo of a change this
                // helper made (or of the layout at capture) finds the layout unchanged.
                if (!layoutNow().equals(settled)) {
                    invalidate();
                }
            }
        });
    }

    /** Declares a later action the snapshot must not be replayed over. */
    public void invalidate() {
        if (!disposed) {
            epoch++;
        }
    }

    /**
     * Runs the caller's own layout-disturbing step (closing the legacy tabs)
     * without counting its selection changes as a user action: the layout it
     * leaves behind is the new baseline, and only a change away from that
     * baseline is a user action.
     */
    public void runOwnChange(Runnable step) {
        if (disposed) {
            return;
        }
        step.run();
        settled = layoutNow();
    }

    /** Records the current layout, replacing any earlier capture. */
    public void capture() {
        if (disposed || project.isDisposed()) {
            return;
        }
        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        List<Selection> selections = new ArrayList<>();
        for (Selection selection : selectionsOf(manager)) {
            if (!(selection.file() instanceof AgentConsoleFile)) {
                selections.add(selection);
            }
        }
        settled = layoutNow();
        Component focus = IdeFocusManager.getInstance(project).getFocusOwner();
        captured = new Captured(epoch, selections, manager.getCurrentWindow(), windowHolding(focus, selections), focus);
    }

    /** Replays the capture if it is still current; consumes it either way. */
    public Outcome restore() {
        if (disposed || project.isDisposed()) {
            return Outcome.DISPOSED;
        }
        Captured snapshot = captured;
        captured = null;
        if (snapshot == null) {
            return Outcome.NOTHING;
        }
        if (snapshot.epoch() != epoch) {
            return Outcome.STALE;
        }
        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        List<EditorWindow> live = List.of(manager.getWindows());
        runOwnChange(() -> {
            for (Selection selection : snapshot.selections()) {
                if (live.contains(selection.window()) && selection.window().isFileOpen(selection.file())) {
                    selection.window().setSelectedComposite(selection.file(), false);
                }
            }
            if (snapshot.current() != null && live.contains(snapshot.current())) {
                snapshot.current().setAsCurrentWindow(false);
            }
            Component owner = snapshot.focusOwner();
            if (owner != null && owner.isShowing() && live.contains(snapshot.focusWindow())
                    && !focusIsElsewhere(manager)) {
                IdeFocusManager.getInstance(project).requestFocus(owner, false);
            }
        });
        return Outcome.RESTORED;
    }

    /** The window whose selected editor contains the focused component, if any. */
    private static EditorWindow windowHolding(Component focus, List<Selection> selections) {
        if (focus == null) {
            return null;
        }
        for (Selection selection : selections) {
            EditorComposite composite = selection.window().getSelectedComposite(true);
            JComponent root = composite == null ? null : composite.getComponent();
            if (root != null && SwingUtilities.isDescendingFrom(focus, root)) {
                return selection.window();
            }
        }
        return null;
    }

    private Layout layoutNow() {
        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        return new Layout(selectionsOf(manager), manager.getCurrentWindow());
    }

    /** True when the keyboard has moved outside every editor window since the capture. */
    private boolean focusIsElsewhere(FileEditorManagerEx manager) {
        Component now = IdeFocusManager.getInstance(project).getFocusOwner();
        return now != null && windowHolding(now, selectionsOf(manager)) == null;
    }

    private static List<Selection> selectionsOf(FileEditorManagerEx manager) {
        List<Selection> all = new ArrayList<>();
        for (EditorWindow window : manager.getWindows()) {
            EditorComposite composite = window.getSelectedComposite(true);
            if (composite != null) {
                all.add(new Selection(window, composite.getFile()));
            }
        }
        return all;
    }

    @Override
    public void dispose() {
        disposed = true;
        captured = null;
    }
}
