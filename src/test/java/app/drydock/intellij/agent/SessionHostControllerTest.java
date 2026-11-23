package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostController.Layout;
import app.drydock.intellij.agent.SessionHostModel.Outcome;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The controller core: transitions, previous and intentional-only selection
 * commits, and passive adoption. No pane, loader or provider exists yet, so
 * nothing here launches or opens.
 */
public class SessionHostControllerTest extends BasePlatformTestCase {

    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final ManagedSessionId c = ManagedSessionId.newId();
    private final List<ManagedSessionId> events = new ArrayList<>();
    private SessionHostState state;
    private SessionHostController controller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        state = new SessionHostState();
        controller = new SessionHostController(getProject(), state, events::add);
        Disposer.register(getTestRootDisposable(), controller);
    }

    /** Plants a stored hint whose String identity a redundant write would replace. */
    private static String plant(SessionHostState target, ManagedSessionId id) {
        String hint = new String(id.toString());
        target.getState().selectedSessionId = hint;
        return hint;
    }

    private boolean hintUntouched(String planted) {
        return state.getState().selectedSessionId == planted;
    }

    public void testShowHoldAndCloseTransitions() {
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(a));
        assertTrue(controller.hold());
        assertEquals(Optional.of(a), controller.layout().held());
        assertEquals(Optional.empty(), controller.layout().primary());
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(b));
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(c));
        assertEquals("primary B to C leaves held A intact", Optional.of(a), controller.layout().held());
        assertFalse("occupied Hold is refused", controller.hold());
        assertEquals(Outcome.FOCUSED_HELD, controller.show(a));
        assertEquals(Slot.HELD, controller.layout().focusedSlot());
        assertEquals("showing the held session copies nothing", Optional.of(c), controller.layout().primary());
        controller.closeHeld();
        assertEquals(Optional.empty(), controller.layout().held());
        assertEquals("focus goes neutral, never to primary", Slot.NONE, controller.layout().focusedSlot());
    }

    public void testPreviousTogglesAndAnUnavailableTargetIsNotSubstituted() {
        assertEquals(Outcome.NO_PREVIOUS, controller.previous());
        controller.show(a);
        controller.show(b);
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.previous());
        assertEquals(Optional.of(a), controller.layout().primary());
        assertEquals("A/B toggles", Optional.of(b), controller.layout().previous());
        controller.invalidate(b);
        assertEquals(Outcome.UNAVAILABLE, controller.previous());
        assertEquals(Optional.of(a), controller.layout().current());
        assertEquals("history is kept, not a target", Optional.of(b), controller.layout().previous());
    }

    public void testOnlyAnIntentionalIdentityChangeWritesStateAndPublishes() {
        controller.show(a);
        assertEquals(List.of(a), events);
        assertEquals(Optional.of(a), state.selectedSession());
        String hint = state.getState().selectedSessionId;

        controller.show(a);
        controller.focus(a);
        assertEquals("same id: no second event", List.of(a), events);
        assertNull("same id pushes no history", controller.layout().previous().orElse(null));
        assertTrue("same id: no redundant state write", hintUntouched(hint));

        controller.show(b);
        assertEquals(List.of(a, b), events);
        assertEquals(Optional.of(b), state.selectedSession());
        assertEquals(Optional.of(a), controller.layout().previous());
        controller.previous();
        assertEquals("previous is intentional and committed", List.of(a, b, a), events);
        assertEquals(Optional.of(a), state.selectedSession());
    }

    public void testConstructorAdoptsTheRememberedIdPassively() {
        SessionHostState remembered = new SessionHostState();
        String hint = plant(remembered, a);
        List<ManagedSessionId> heard = new ArrayList<>();

        SessionHostController adopted = new SessionHostController(getProject(), remembered, heard::add);
        Disposer.register(getTestRootDisposable(), adopted);

        Layout layout = adopted.layout();
        assertEquals(Optional.of(a), layout.primary());
        assertEquals(Slot.NONE, layout.focusedSlot());
        assertEquals(Optional.empty(), layout.current());
        assertEquals(Optional.empty(), layout.previous());
        assertEquals(Optional.empty(), layout.held());
        assertEquals(List.of(), heard);
        assertSame("the stored hint was not rewritten", hint, remembered.getState().selectedSessionId);
        assertEquals("no focus permission was issued", 0, adopted.epoch());
        assertFalse("passive adoption issues no focus permission", adopted.focusPermitted(adopted.epoch()));
        assertEquals("adopted history alone is no target", Outcome.NO_PREVIOUS, adopted.previous());
        assertEquals(Outcome.UNAVAILABLE, adopted.focus(b));
        assertTrue(adopted.hold());
        assertEquals("adopted primary is then an ordinary pane", Optional.of(a), adopted.layout().held());
    }
}
