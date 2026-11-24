package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JPanel;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The leased editor's focus-owner watcher: a focus landing inside a mounted
 * pane root is a deliberate pane focus (announced through the controller's
 * focus publisher), a focus leaving the panes departs, a null owner is a
 * window switch and is ignored, and a disposed editor reacts to nothing.
 */
public class SessionHostEditorFocusPlatformTest extends BasePlatformTestCase {

    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final List<ManagedSessionId> focusEvents = new ArrayList<>();
    private SessionHostController controller;
    private SessionHostEditor editor;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        controller = new SessionHostController(getProject(), new SessionHostState(), id -> { },
                listener -> null, List::of, focusEvents::add);
        Disposer.register(getTestRootDisposable(), controller);
        editor = new SessionHostEditor(getProject(), controller);
        Disposer.register(getTestRootDisposable(), editor);
    }

    public void testAFocusInsideAPaneIsAPaneFocusAndLeavesOnLeaving() {
        controller.show(a);
        editor.refreshLayout();
        SessionPane pane = controller.paneFor(a);

        // show() left the focused slot at PRIMARY; a pane focus announces itself.
        editor.focusOwnerChanged(ownerEvent(pane.root()));
        assertEquals("the focused slot follows the focus", Slot.PRIMARY, controller.layout().focusedSlot());
        assertEquals("the pane focus is announced", List.of(a), focusEvents);

        editor.focusOwnerChanged(ownerEvent(null));
        assertEquals("a null owner preserves the focused slot", Slot.PRIMARY, controller.layout().focusedSlot());
        assertEquals("a null owner does not announce a departure", List.of(a), focusEvents);

        editor.focusOwnerChanged(ownerEvent(pane.focusTarget().orElseGet(pane::root)));
        assertEquals("another component of the same pane is no new focus", List.of(a), focusEvents);

        editor.focusOwnerChanged(ownerEvent(new JPanel()));
        assertEquals("focus outside the panes departs", Slot.NONE, controller.layout().focusedSlot());
        assertEquals("a departure announces nothing", List.of(a), focusEvents);

        editor.focusOwnerChanged(ownerEvent(null));
        assertEquals("a null owner is a window switch, not a departure",
                Slot.NONE, controller.layout().focusedSlot());
        assertEquals(List.of(a), focusEvents);
    }

    public void testAFocusIntoTheHeldPaneAnnouncesThatSession() {
        controller.show(a);
        editor.refreshLayout();
        controller.hold();
        controller.show(b);
        editor.refreshLayout();
        SessionPane held = controller.paneFor(a);

        editor.focusOwnerChanged(ownerEvent(held.root()));
        assertEquals("the held pane took the focus", Slot.HELD, controller.layout().focusedSlot());
        assertEquals(List.of(a), focusEvents);

        SessionPane primary = controller.paneFor(b);
        editor.focusOwnerChanged(ownerEvent(primary.root()));
        assertEquals("back to the primary pane", Slot.PRIMARY, controller.layout().focusedSlot());
        assertEquals(List.of(a, b), focusEvents);
    }

    public void testADisposedEditorReactsToNothing() {
        controller.show(a);
        editor.refreshLayout();
        SessionPane pane = controller.paneFor(a);
        Disposer.dispose(editor);

        editor.focusOwnerChanged(ownerEvent(pane.root()));
        assertEquals("the disposed watcher announces nothing", List.of(), focusEvents);
    }

    /** A synthetic focus-owner change as the shared {@link KeyboardFocusManager} would publish it. */
    private static PropertyChangeEvent ownerEvent(Component owner) {
        return new PropertyChangeEvent(KeyboardFocusManager.getCurrentKeyboardFocusManager(),
                "focusOwner", null, owner);
    }
}
