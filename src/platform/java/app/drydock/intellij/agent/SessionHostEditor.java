package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostController.Layout;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single "Drydock Sessions" editor: a disposable view over the window's
 * {@link SessionHostController}. A toolbar from the plugin.xml group
 * {@value #TOOLBAR_GROUP} (declared empty), a primary slot and, while a session
 * is held, a held slot, each hosting the retained {@link SessionPane} root of
 * that slot's session; the focused slot gets an accent border. A root has one
 * parent: it is detached before it is added here and when it leaves a slot or
 * the editor is disposed. Panes and consoles are never disposed or moved here.
 *
 * <p>Construction acquires the controller's lease with the editor as token; if
 * another editor holds it this one is an inert duplicate (nothing mounted,
 * nothing released). Disposal releases the lease (hide, detach); a reopened
 * editor re-reads the layout and last focused slot and mounts the same panes.
 * With no primary session the slot shows a placeholder and the empty
 * {@linkplain #allSessionsSlot() All Sessions entry slot}. UI thread only.</p>
 */
final class SessionHostEditor extends UserDataHolderBase implements FileEditor {

    static final String TOOLBAR_GROUP = "Drydock.SessionHost.Toolbar";
    private static final String TOOLBAR_PLACE = "Drydock.SessionHost";

    private final SessionHostController controller;
    private final boolean leased;
    private final SessionHostFile file = AgentConsoleFileSystem.getInstance().sessionHostFile();
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel body = new JPanel(new GridLayout(1, 0));
    private final JPanel primarySlot = new JPanel(new BorderLayout());
    private final JPanel heldSlot = new JPanel(new BorderLayout());
    private final JPanel allSessionsSlot = new JPanel(new BorderLayout());
    private final JPanel emptyPrimary = new JPanel(new BorderLayout());
    /** The emptied primary slot's placeholder; its text names why nothing is shown. */
    private final JBLabel emptyLabel = new JBLabel("No session shown", SwingConstants.CENTER);
    private final List<SessionPane> mounted = new ArrayList<>();
    private boolean disposed;

    SessionHostEditor(Project project, SessionHostController controller) {
        Objects.requireNonNull(project, "project");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.leased = controller.acquireLease(this);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                TOOLBAR_PLACE, toolbarGroup(), true);
        toolbar.setTargetComponent(root);
        // Right-aligned: the bar's commands (Hold Current Session, Close Held,
        // Previous) are window-level actions, so they read as window chrome
        // and sit at the window's trailing edge, away from the pane content.
        JPanel toolbarRow = new JPanel(new BorderLayout());
        toolbarRow.add(toolbar.getComponent(), BorderLayout.EAST);
        root.add(toolbarRow, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        emptyPrimary.add(emptyLabel, BorderLayout.CENTER);
        allSessionsSlot.setName("allSessionsSlot");
        emptyPrimary.add(allSessionsSlot, BorderLayout.SOUTH);
        if (leased) {
            project.getMessageBus().connect(this).subscribe(SessionHostListener.TOPIC,
                    new SessionHostListener() {
                        @Override
                        public void sessionSelected(ManagedSessionId sessionId) {
                            refreshLayout();
                            // The tab title names the now-current session; the
                            // platform re-queries the tab-title provider only
                            // when a file's presentation is marked dirty.
                            FileEditorManager.getInstance(project)
                                    .updateFilePresentation(file);
                        }
                    });
            refreshLayout();
        }
    }

    private static ActionGroup toolbarGroup() {
        return ActionManager.getInstance().getAction(TOOLBAR_GROUP) instanceof ActionGroup group
                ? group : new DefaultActionGroup();
    }

    /** The canonical Sessions document; EditorComposite requires a non-null override. */
    @Override
    public @NotNull VirtualFile getFile() { return file; }

    boolean leased() {
        return leased && !disposed;
    }

    JComponent allSessionsSlot() {
        return allSessionsSlot;
    }

    JComponent primarySlot() { return primarySlot; }

    JComponent heldSlot() { return heldSlot; }

    /** The emptied primary slot's placeholder label; the empty-state test reads its text. */
    JBLabel emptyStateLabel() {
        return emptyLabel;
    }

    /** Re-mounts the slots from the controller's current layout; a no-op when inert or disposed. */
    void refreshLayout() {
        if (!leased || disposed) {
            return;
        }
        emptyLabel.setText(controller.noRemainingSessions()
                ? "No active sessions" : "No session shown");
        Layout layout = controller.layout();
        List<SessionPane> wanted = new ArrayList<>();
        SessionPane primary = mountInto(primarySlot, layout.primary(), emptyPrimary, wanted);
        SessionPane held = mountInto(heldSlot, layout.held(), null, wanted);
        for (SessionPane old : List.copyOf(mounted)) {
            if (!wanted.contains(old)) {
                old.detachRoot();
            }
        }
        mounted.clear();
        mounted.addAll(wanted);
        body.removeAll();
        body.add(primarySlot);
        if (held != null) {
            body.add(heldSlot);
        }
        mark(primarySlot, primary != null && layout.focusedSlot() == Slot.PRIMARY);
        mark(heldSlot, held != null && layout.focusedSlot() == Slot.HELD);
        body.revalidate();
    }

    /** Fills {@code slot} with the pane of {@code id}, or {@code empty}; the mounted pane or null. */
    private SessionPane mountInto(JPanel slot, Optional<ManagedSessionId> id, JComponent empty,
                                  List<SessionPane> wanted) {
        SessionPane pane = id.map(controller::paneFor).orElse(null);
        JComponent content = pane != null ? pane.root() : empty;
        if (content == null) {
            slot.removeAll();
        } else if (content.getParent() != slot || slot.getComponentCount() != 1) {
            slot.removeAll();
            if (pane != null) {
                pane.detachRoot();
            }
            slot.add(content, BorderLayout.CENTER);
        }
        if (pane != null) {
            wanted.add(pane);
        }
        return pane;
    }

    private static void mark(JPanel slot, boolean focused) {
        slot.setBorder(focused
                ? BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor.namedColor("Component.focusColor", JBColor.BLUE))
                : JBUI.Borders.empty(0, 0, 2, 0));
    }

    @Override
    public @NotNull JComponent getComponent() { return root; }

    @Override
    public JComponent getPreferredFocusedComponent() {
        if (!leased || disposed) {
            return null;
        }
        // The focused session's terminal panel, when one can take the
        // keyboard: a bare pane root is a focusable JPanel, so every
        // platform-driven focus pass that asks the editor where the keyboard
        // goes (tab selection, window activation restore, openFile) would
        // otherwise land there and silently eat the keystrokes.
        return controller.focusedPane().flatMap(SessionPane::focusTarget)
                .orElseGet(() -> controller.focusedPane().map(SessionPane::root).orElse(primarySlot));
    }

    @Override
    public @NotNull String getName() { return SessionHostFile.TITLE; }
    @Override
    public void setState(@NotNull FileEditorState state) { }
    @Override
    public boolean isModified() { return false; }
    @Override
    public boolean isValid() { return !disposed; }
    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { }
    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { }

    /** Detaches only: releases the lease (if owned) and unparents the mounted roots; panes and consoles stay. */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (leased) {
            controller.releaseLease(this);
        }
        mounted.forEach(SessionPane::detachRoot);
        body.removeAll();
    }
}
