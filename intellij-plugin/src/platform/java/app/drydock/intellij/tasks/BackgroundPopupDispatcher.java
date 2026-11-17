package app.drydock.intellij.tasks;

import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Routes the task tool window's context-menu triggers (INV-22/23): a mouse
 * popup trigger over blank Inbox/Projects space, including exposed viewport
 * whitespace below the rows, shows only a generic {@code New Task…} for the
 * owning window's project; a trigger over an occupied row/header/toggle, and
 * every keyboard-invoked popup, shows the supplied existing row group with
 * the content component's data context. The row group's policy is never
 * touched here, and the selection is never read or changed.
 *
 * <p>Hit testing runs on the EDT on pure Swing geometry. Exactly one popup
 * is shown per gesture: the first popup-trigger phase (press, release or
 * click, depending on the platform) wins and is consumed, so neither a later
 * phase nor another registered surface fires again. {@link #dispose()}
 * removes every listener this helper added and disables later dispatch,
 * including a retained {@code New Task…} action.
 */
final class BackgroundPopupDispatcher implements Disposable {

    /** Where a pointer lands in a content component. */
    enum Hit { OCCUPIED, BLANK }

    /** Shows an action popup; production uses the action system's popup menu. */
    @FunctionalInterface
    interface PopupShower {
        void show(ActionGroup group, String place, JComponent target, Component invoker, int x, int y);
    }

    private final @Nullable Project project;
    private final ActionGroup rowGroup;
    private final String place;
    private final PopupShower shower;
    private final Consumer<Project> launcher;
    private final Predicate<MouseEvent> keyboardInvoked;
    private final ActionGroup backgroundGroup;
    private final List<Runnable> registrations = new ArrayList<>();
    private volatile boolean disposed;
    /** Whether the current gesture (since its last press) already showed a popup. */
    private boolean gestureHandled;

    BackgroundPopupDispatcher(@Nullable Project project, ActionGroup rowGroup, String place) {
        this(project, rowGroup, place, BackgroundPopupDispatcher::showActionPopup,
                p -> TaskLauncher.open(p, Optional.empty(), Optional.empty()),
                event -> keyboardInvoked(event)
                        || IdeEventQueue.getInstance().getTrueCurrentEvent() instanceof KeyEvent);
    }

    /**
     * The platform's Show Context Menu action (keyboard) synthesizes a popup
     * press with no button held; a real press always holds its button.
     */
    static boolean keyboardInvoked(MouseEvent event) {
        return event.getID() == MouseEvent.MOUSE_PRESSED && (event.getModifiersEx() & (MouseEvent.BUTTON1_DOWN_MASK
                | MouseEvent.BUTTON2_DOWN_MASK | MouseEvent.BUTTON3_DOWN_MASK)) == 0;
    }

    BackgroundPopupDispatcher(@Nullable Project project, ActionGroup rowGroup, String place, PopupShower shower,
                              Consumer<Project> launcher, Predicate<MouseEvent> keyboardInvoked) {
        this.project = project;
        this.rowGroup = Objects.requireNonNull(rowGroup, "rowGroup");
        this.place = Objects.requireNonNull(place, "place");
        this.shower = Objects.requireNonNull(shower, "shower");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.keyboardInvoked = Objects.requireNonNull(keyboardInvoked, "keyboardInvoked");
        this.backgroundGroup = new DefaultActionGroup(new NewTaskHere());
    }

    /**
     * Routes popups for a tree or list and, when given, the viewport that
     * shows it, so whitespace the content does not cover is routed the same.
     */
    void register(JComponent content, @Nullable JViewport viewport) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed) {
            throw new IllegalStateException("BackgroundPopupDispatcher is disposed");
        }
        if (!(content instanceof JTree) && !(content instanceof JList<?>)) {
            throw new IllegalArgumentException("Only a JTree or JList can be routed: " + content);
        }
        attach(content, content);
        if (viewport != null) {
            attach(viewport, content);
        }
    }

    /** The dedicated blank-space group: only {@code New Task…}. */
    ActionGroup backgroundGroup() {
        return backgroundGroup;
    }

    /** Classifies {@code point}, in {@code content}'s coordinates, against its actual rows or cells. */
    static Hit classify(JComponent content, Point point) {
        return switch (content) {
            case JTree tree -> {
                int row = tree.getClosestRowForLocation(point.x, point.y);
                Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
                // The row is wide-selection: its full content width, its own height.
                yield bounds != null && point.x >= 0 && point.x < Math.max(tree.getWidth(), bounds.x + bounds.width)
                        && point.y >= bounds.y && point.y < bounds.y + bounds.height ? Hit.OCCUPIED : Hit.BLANK;
            }
            case JList<?> list -> {
                int index = list.locationToIndex(point);
                Rectangle cell = index < 0 ? null : list.getCellBounds(index, index);
                yield cell != null && cell.contains(point) ? Hit.OCCUPIED : Hit.BLANK;
            }
            default -> Hit.BLANK;
        };
    }

    @Override
    public void dispose() {
        disposed = true;
        registrations.forEach(Runnable::run);
        registrations.clear();
    }

    private void attach(Component surface, JComponent content) {
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!event.isConsumed()) {
                    gestureHandled = false;
                }
                dispatch(event, content);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dispatch(event, content);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                dispatch(event, content);
            }
        };
        surface.addMouseListener(listener);
        registrations.add(() -> surface.removeMouseListener(listener));
    }

    private void dispatch(MouseEvent event, JComponent content) {
        if (disposed || !event.isPopupTrigger() || event.isConsumed() || gestureHandled) {
            return;
        }
        gestureHandled = true;
        event.consume();
        boolean blank = !keyboardInvoked.test(event)
                && classify(content, SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), content))
                == Hit.BLANK;
        shower.show(blank ? backgroundGroup : rowGroup, place, content, event.getComponent(), event.getX(),
                event.getY());
    }

    private boolean launchable() {
        return !disposed && project != null && !project.isDisposed() && project.getBasePath() != null;
    }

    private static void showActionPopup(ActionGroup group, String place, JComponent target, Component invoker,
                                        int x, int y) {
        ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu(place, group);
        menu.setTargetComponent(target);
        menu.getComponent().show(invoker, x, y);
    }

    /** The blank-space entry: this window's project, the unpreset full session dialog. */
    private final class NewTaskHere extends DumbAwareAction {

        NewTaskHere() {
            super("New Task…", "Start an agent session in this window's project", null);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(launchable());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (launchable()) {
                launcher.accept(project);
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
