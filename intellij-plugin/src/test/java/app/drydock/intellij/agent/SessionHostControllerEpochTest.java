package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostController.Layout;
import app.drydock.intellij.agent.SessionHostModel.Outcome;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The controller core's epoch fence, cached layout, background-availability
 * quietness and per-project service isolation. No pane, loader or provider
 * exists yet, so nothing here launches or opens.
 */
public class SessionHostControllerEpochTest extends BasePlatformTestCase {

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

    private boolean hintUntouched(String planted) {
        return state.getState().selectedSessionId == planted;
    }

    private void assertRevoked(String why, long older) {
        assertFalse(why + " revokes the older token", controller.focusPermitted(older));
        assertFalse(why + " revokes the current epoch", controller.focusPermitted(controller.epoch()));
    }

    public void testFocusTargetsOnlyAPaneOnScreen() {
        controller.show(a);
        controller.show(b);
        assertEquals("history alone is no target", Outcome.UNAVAILABLE, controller.focus(a));
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.focus(b));
        assertEquals(Optional.of(b), controller.layout().current());
    }

    public void testBackgroundAvailabilityAndHoldNeitherWriteNorPublish() {
        controller.show(a);
        controller.show(b);
        String hint = state.getState().selectedSessionId;
        events.clear();

        controller.focus(b);
        long older = controller.epoch();
        assertTrue("an accepted same-id focus issues permission", controller.focusPermitted(older));
        controller.hold();
        assertRevoked("hold", older);

        controller.focus(b);
        older = controller.epoch();
        assertTrue("a same-id focus of the held pane issues permission", controller.focusPermitted(older));
        controller.closeHeld();
        assertRevoked("close of the held pane", older);

        controller.show(b);
        older = controller.epoch();
        assertTrue("a same-id show issues permission", controller.focusPermitted(older));
        controller.invalidate(b);
        assertRevoked("invalidation of a shown pane", older);
        controller.revalidate(b);

        controller.invalidate(c);
        controller.invalidate(a);
        controller.revalidate(a);
        controller.focusDeparted();
        controller.hide();

        assertTrue("no state write", hintUntouched(hint));
        assertEquals(List.of(), events);
        assertEquals(Optional.of(b), controller.layout().current());
        assertEquals(Optional.of(a), controller.layout().previous());
        assertEquals("history without a pane is not shown", Optional.empty(), controller.layout().held());
    }

    public void testAMalformedOrAbsentRememberedIdAdoptsNothing() {
        SessionHostState bad = new SessionHostState();
        bad.getState().selectedSessionId = "not-a-uuid";
        SessionHostController malformed = new SessionHostController(getProject(), bad, events::add);
        Disposer.register(getTestRootDisposable(), malformed);
        assertEquals(Optional.empty(), malformed.layout().primary());
        assertEquals(Optional.empty(), controller.layout().primary());
        assertEquals(List.of(), events);
    }

    public void testLayoutIsCachedAndImmutableBetweenChanges() {
        Layout empty = controller.layout();
        assertSame(empty, controller.layout());
        controller.invalidate(c);
        controller.hide();
        assertSame("a no-op does not reallocate", empty, controller.layout());
        controller.show(a);
        Layout shown = controller.layout();
        assertNotSame(empty, shown);
        assertSame(shown, controller.layout());
        controller.show(a);
        assertSame("same-id show keeps the cached value", shown, controller.layout());
        assertEquals("an old value never mutates", Optional.empty(), empty.primary());
    }

    public void testNewerRequestHideDepartureAndDisposalFenceOldFocus() {
        assertFalse("construction issues no permission", controller.focusPermitted(controller.epoch()));
        controller.show(a);
        long first = controller.epoch();
        assertTrue(controller.focusPermitted(first));
        controller.show(b);
        assertFalse("a newer request voids the older", controller.focusPermitted(first));
        assertTrue(controller.focusPermitted(controller.epoch()));

        long beforeHide = controller.epoch();
        controller.hide();
        assertFalse("hide", controller.focusPermitted(beforeHide));
        assertFalse("even the new epoch while hidden", controller.focusPermitted(controller.epoch()));
        controller.show(c);
        assertTrue("a later show reveals the host", controller.focusPermitted(controller.epoch()));

        long beforeDeparture = controller.epoch();
        controller.focusDeparted();
        assertFalse("departure", controller.focusPermitted(beforeDeparture));
        assertFalse("the current epoch is revoked too", controller.focusPermitted(controller.epoch()));
        assertEquals(Slot.NONE, controller.layout().focusedSlot());

        controller.show(a);
        long live = controller.epoch();
        controller.dispose();
        assertFalse("disposal", controller.focusPermitted(live));
        assertFalse(controller.focusPermitted(controller.epoch()));
    }

    public void testRefusedRequestsCancelOlderFocusAndADisposedHostIgnoresEverything() {
        controller.show(a);
        controller.invalidate(c);
        String hint = state.getState().selectedSessionId;
        events.clear();
        Layout shown = controller.layout();

        long older = controller.epoch();
        assertTrue(controller.focusPermitted(older));
        assertEquals(Outcome.UNAVAILABLE, controller.show(c));
        assertRevoked("an unavailable show", older);

        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.focus(a));
        older = controller.epoch();
        assertTrue("an accepted focus issues permission", controller.focusPermitted(older));
        assertEquals(Outcome.NO_PREVIOUS, controller.previous());
        assertRevoked("an absent previous", older);

        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.focus(a));
        older = controller.epoch();
        assertTrue(controller.focusPermitted(older));
        assertEquals("a session not on screen is no focus target", Outcome.UNAVAILABLE, controller.focus(b));
        assertRevoked("a nonvisible focus", older);

        assertSame("a refused request changes no layout", shown, controller.layout());
        assertEquals(Optional.of(a), controller.layout().current());
        assertEquals(Optional.empty(), controller.layout().previous());
        assertTrue("no state write", hintUntouched(hint));
        assertEquals(List.of(), events);

        controller.dispose();
        events.clear();
        Layout last = controller.layout();
        assertEquals(Outcome.UNAVAILABLE, controller.show(b));
        assertEquals(Outcome.UNAVAILABLE, controller.focus(a));
        assertFalse(controller.hold());
        controller.invalidate(a);
        assertEquals(List.of(), events);
        assertSame(last, controller.layout());
        assertEquals(Optional.of(a), state.selectedSession());
    }

    public void testTheProductionServiceIsPerProjectAndPublishesOnItsOwnBus() {
        Project project = getProject();
        SessionHostController service = SessionHostController.getInstance(project);
        assertNotNull("declaratively registered", service);
        assertSame(service, SessionHostController.getInstance(project));
        Project other = ProjectManager.getInstance().getDefaultProject();
        assertNotSame("another project owns another host", service, SessionHostController.getInstance(other));

        List<ManagedSessionId> heard = new ArrayList<>();
        project.getMessageBus().connect(getTestRootDisposable()).subscribe(SessionHostListener.TOPIC,
                new SessionHostListener() {
                    @Override
                    public void sessionSelected(ManagedSessionId sessionId) {
                        heard.add(sessionId);
                    }
                });
        service.show(a);
        assertEquals(List.of(a), heard);
        assertEquals(Optional.of(a), SessionHostState.getInstance(project).selectedSession());
        assertEquals(Optional.empty(), SessionHostController.getInstance(other).layout().current());
        assertEquals(Optional.empty(), SessionHostState.getInstance(other).selectedSession());
    }
}
