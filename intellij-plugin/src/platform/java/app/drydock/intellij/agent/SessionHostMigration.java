package app.drydock.intellij.agent;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorOpenRequest;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * The project's passive converter from restored per-session tabs to the one
 * canonical {@link SessionHostFile}. A restored {@link AgentConsoleFile} editor
 * is an inert placeholder; its open notification (the public
 * {@link FileEditorManagerListener}) asks this service to make sure the
 * canonical document is open.
 *
 * <p><b>Passive.</b> The canonical document is opened or reused without
 * selecting it and without taking focus or a preview tab, so code the user had
 * selected stays selected. Which session the host shows is not decided here:
 * the host's controller adopts the one remembered NEW-format id, else shows no
 * selection. The legacy UUIDs, their count, order and selection never pick a
 * session, and nothing here starts, resumes or attaches an agent or opens a
 * session on startup. Once the canonical open has completed, the
 * placeholders are closed (see below); code documents are never touched.</p>
 *
 * <p><b>Closing.</b> At the EDT completion of the open, after the wait, a
 * {@link SessionLayoutSnapshot} records each window's selected code file, the current window and
 * the focus owner; only {@link AgentConsoleFile} tabs are closed, per window, with
 * {@code FileEditorManagerEx.closeFile(file, window)}, under a reentrancy guard; the snapshot is
 * then restored without stealing focus. A window whose selected tab was a placeholder has no code
 * target to preserve: it is not filled with another session and no split is created for it.
 * Nothing here activates a session, so the selection events this closing causes are not
 * intentional activations. A failed or cancelled open closes nothing.</p>
 *
 * <p><b>Coalescing.</b> Any number of legacy notifications, in one turn or many,
 * mark one pending request. At most one open is in flight; a notification
 * during it asks for one further pass after a successful completion. Duplicate,
 * unknown and malformed ids are all just "a legacy file opened" and need no
 * parsing. The in-flight marker is cleared on success, cancellation, failure
 * and disposal. A failure is logged and surfaced with a Retry action and drops
 * the pending request, so a retry is always a new legacy event or the user's
 * click, never a loop. EDT only.</p>
 */
public final class SessionHostMigration implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionHostMigration.class);
    private static final String NOTIFICATION_GROUP = "Drydock migration";

    private final Project project;
    private boolean pending;
    private boolean scheduled;
    private boolean inFlight;
    private boolean closing;
    private boolean disposed;
    /** Opens requested so far, for tests that assert coalescing. */
    private int opens;

    public SessionHostMigration(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public static SessionHostMigration getInstance(Project project) {
        return project.getService(SessionHostMigration.class);
    }

    /** The public listener: a legacy editor opening is the only trigger. */
    public static final class Listener implements FileEditorManagerListener {
        @Override
        public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
            if (file instanceof AgentConsoleFile) {
                getInstance(source.getProject()).legacyOpened();
            }
        }
    }

    /** A restored legacy editor opened: request one coalesced canonical open. */
    void legacyOpened() {
        if (disposed || closing) {
            return;
        }
        pending = true;
        schedule();
    }

    int opens() {
        return opens;
    }

    private void schedule() {
        if (scheduled || inFlight || !pending) {
            return;
        }
        scheduled = true;
        ApplicationManager.getApplication().invokeLater(this::reconcile, ModalityState.nonModal(), project.getDisposed());
    }

    private void reconcile() {
        scheduled = false;
        if (disposed || project.isDisposed() || inFlight || !pending) {
            return;
        }
        pending = false;
        if (FileEditorManager.getInstance(project).isFileOpen(AgentConsoleFileSystem.getInstance().sessionHostFile())) {
            closePlaceholders();
            return;
        }
        inFlight = true;
        opens++;
        var request = FileEditorOpenRequest.defaults().withReuseOpen(true).withSelectAsCurrent(false)
                .withRequestFocus(false).withUsePreviewTab(false);
        try {
            FileEditorManager.getInstance(project)
                    .requestOpenFile(AgentConsoleFileSystem.getInstance().sessionHostFile(), request)
                    .whenComplete((composite, error) -> ApplicationManager.getApplication().invokeLater(
                            () -> finished(error), ModalityState.nonModal(), project.getDisposed()));
        } catch (RuntimeException e) {
            finished(e);
        }
    }

    private void finished(Throwable error) {
        inFlight = false;
        if (disposed) {
            return;
        }
        if (error == null) {
            closePlaceholders();
            schedule();
            return;
        }
        pending = false;
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        LOG.warn("Opening the Drydock Sessions document failed; legacy session tabs stay as placeholders", cause);
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Drydock Sessions could not open",
                        "Restored session tabs stay as placeholders: " + cause.getMessage(), NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Retry", this::legacyOpened))
                .notify(project);
    }

    /** Snapshot after the wait, close only the placeholders, restore the code layout. */
    private void closePlaceholders() {
        if (closing || disposed || project.isDisposed()) {
            return;
        }
        closing = true;
        SessionLayoutSnapshot snapshot = new SessionLayoutSnapshot(project, this);
        try {
            snapshot.capture();
            snapshot.runOwnChange(this::closeLegacyTabs);
            snapshot.restore();
        } catch (RuntimeException e) {
            LOG.warn("Closing the legacy session tabs failed; they stay as placeholders", e);
        } finally {
            closing = false;
            Disposer.dispose(snapshot);
        }
    }

    private void closeLegacyTabs() {
        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        for (EditorWindow window : manager.getWindows()) {
            for (VirtualFile file : List.copyOf(window.getFileList())) {
                if (file instanceof AgentConsoleFile) {
                    manager.closeFile(file, window);
                }
            }
        }
    }

    @Override
    public void dispose() {
        closing = false;
        disposed = true;
        pending = false;
        scheduled = false;
        inFlight = false;
    }
}
