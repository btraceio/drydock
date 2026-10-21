package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Hosts the scope-aware file browser for a task's changed files. */
public final class TaskChangesToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final System.Logger LOG = System.getLogger(TaskChangesToolWindowFactory.class.getName());
    static final String ID = "Drydock Changes";
    private static final String LAST_SESSION_KEY = "drydock.taskChanges.lastSession";
    private static final Key<LatestValueDelivery<TaskChanges.Prepared>> DELIVERY =
            Key.create("drydock.taskChanges.delivery");
    private static final Key<TaskChangesPanel> PANEL = Key.create("drydock.taskChanges.panel");
    private static final Key<ManagedSessionId> RESTORING = Key.create("drydock.taskChanges.restoring");

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LOG.log(System.Logger.Level.INFO, () -> "Creating Drydock Changes content: project=" + project.getName());
        TaskChangesPanel panel = new TaskChangesPanel(project);
        project.putUserData(PANEL, panel);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        Consumer<TaskChanges.Prepared> display = panel::show;
        boolean replayed = delivery(project).attach(display);
        LOG.log(System.Logger.Level.INFO, () -> "Attached Drydock Changes panel: project=" + project.getName()
                + ", replayedPreparedData=" + replayed);
        Disposer.register(content, () -> {
            delivery(project).detach(display);
            if (project.getUserData(PANEL) == panel) {
                project.putUserData(PANEL, null);
            }
        });
        Disposer.register(content, panel);
        project.getMessageBus().connect(content).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        if (event.getNewFile() instanceof AgentConsoleFile console
                                && !delivery(project).hasValue()
                                && project.getUserData(RESTORING) == null) {
                            restoreCandidate(project, panel, console.sessionId(), "selected agent tab");
                        }
                    }
                });
        toolWindow.getContentManager().addContent(content);
        if (!replayed) {
            // Tool-window content can be created before editor restoration has
            // selected the persisted agent tab. Defer one EDT turn, then use
            // that tab as the fallback when older plugin versions left no
            // explicit Changes selection behind.
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.getUserData(PANEL) == panel && !delivery(project).hasValue()) {
                    restoreRememberedOrSelected(project, panel);
                }
            }, ModalityState.nonModal(), project.getDisposed());
        }
    }

    static void show(Project project, TaskChanges.Prepared prepared, boolean activate) {
        project.putUserData(RESTORING, null);
        PropertiesComponent.getInstance(project).setValue(LAST_SESSION_KEY, prepared.sessionId().toString());
        boolean delivered = delivery(project).publish(prepared);
        LOG.log(System.Logger.Level.INFO, () -> "Published Drydock Changes data: project=" + project.getName()
                + ", task=" + prepared.taskName() + ", deliveredToAttachedPanel=" + delivered);
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow == null) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Drydock Changes tool window is not registered for project=" + project.getName());
            return;
        }
        if (activate) {
            LOG.log(System.Logger.Level.INFO, () -> "Activating Drydock Changes tool window: project="
                    + project.getName() + ", available=" + toolWindow.isAvailable()
                    + ", visible=" + toolWindow.isVisible());
            toolWindow.activate(null);
        }
    }

    static void restoreFailed(Project project, ManagedSessionId sessionId, String message, boolean forget) {
        if (sessionId.equals(project.getUserData(RESTORING))) {
            project.putUserData(RESTORING, null);
        }
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        if (forget && sessionId.toString().equals(properties.getValue(LAST_SESSION_KEY))) {
            properties.unsetValue(LAST_SESSION_KEY);
        }
        TaskChangesPanel panel = project.getUserData(PANEL);
        if (panel != null) {
            panel.showRestoreFailure(message);
        }
    }

    private static void restoreRememberedOrSelected(Project project, TaskChangesPanel panel) {
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        String persisted = properties.getValue(LAST_SESSION_KEY);
        try {
            ManagedSessionId sessionId;
            String source;
            if (persisted != null && !persisted.isBlank()) {
                sessionId = ManagedSessionId.of(persisted);
                source = "remembered selection";
            } else {
                sessionId = selectedAgentSession(project).orElse(null);
                source = "selected agent tab";
            }
            if (sessionId == null) {
                LOG.log(System.Logger.Level.INFO, () -> "No Drydock Changes session to restore: project="
                        + project.getName() + ", rememberedSelection=false, selectedAgentTab=false");
                return;
            }
            restoreCandidate(project, panel, sessionId, source);
        } catch (IllegalArgumentException e) {
            properties.unsetValue(LAST_SESSION_KEY);
            panel.showRestoreFailure("The remembered Drydock session id is invalid");
            LOG.log(System.Logger.Level.WARNING, "Discarded invalid remembered Drydock Changes session id", e);
        }
    }

    private static void restoreCandidate(Project project, TaskChangesPanel panel,
                                         ManagedSessionId sessionId, String source) {
        if (project.getUserData(RESTORING) != null) {
            return;
        }
        project.putUserData(RESTORING, sessionId);
        LOG.log(System.Logger.Level.INFO, () -> "Selected Drydock Changes restore candidate: project="
                + project.getName() + ", session=" + sessionId + ", source=" + source);
        panel.showLoading("Restoring task changes…");
        TaskChanges.restore(project, sessionId);
    }

    private static Optional<ManagedSessionId> selectedAgentSession(Project project) {
        return selectedAgentSession(FileEditorManager.getInstance(project).getSelectedFiles());
    }

    static Optional<ManagedSessionId> selectedAgentSession(VirtualFile[] selectedFiles) {
        for (VirtualFile file : selectedFiles) {
            if (file instanceof AgentConsoleFile console) {
                return Optional.of(console.sessionId());
            }
        }
        return Optional.empty();
    }

    private static LatestValueDelivery<TaskChanges.Prepared> delivery(Project project) {
        LatestValueDelivery<TaskChanges.Prepared> existing = project.getUserData(DELIVERY);
        if (existing != null) {
            return existing;
        }
        LatestValueDelivery<TaskChanges.Prepared> created = new LatestValueDelivery<>();
        synchronized (DELIVERY) {
            existing = project.getUserData(DELIVERY);
            if (existing == null) {
                project.putUserData(DELIVERY, created);
                return created;
            }
            return existing;
        }
    }

    static final class LatestValueDelivery<T> {
        private T latest;
        private Consumer<T> listener;

        boolean publish(T value) {
            latest = Objects.requireNonNull(value);
            if (listener != null) {
                listener.accept(value);
                return true;
            }
            return false;
        }

        boolean attach(Consumer<T> newListener) {
            listener = Objects.requireNonNull(newListener);
            if (latest != null) {
                newListener.accept(latest);
                return true;
            }
            return false;
        }

        void detach(Consumer<T> oldListener) {
            if (listener == oldListener) {
                listener = null;
            }
        }
        boolean hasValue() {
            return latest != null;
        }
    }
}
