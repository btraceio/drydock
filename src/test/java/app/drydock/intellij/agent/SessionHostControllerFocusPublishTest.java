package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostModel.Outcome;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * An accepted focus announces {@code sessionFocused} even when the shown
 * identity is unchanged (no {@code sessionSelected} rides along), and a
 * refused one announces nothing.
 */
public class SessionHostControllerFocusPublishTest extends BasePlatformTestCase {

    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final List<ManagedSessionId> selections = new ArrayList<>();
    private final List<ManagedSessionId> focusEvents = new ArrayList<>();
    private SessionHostController controller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        controller = new SessionHostController(getProject(), new SessionHostState(),
                selections::add, listener -> null, List::of, focusEvents::add);
        Disposer.register(getTestRootDisposable(), controller);
    }

    public void testAnAcceptedFocusAnnouncesEvenWhenTheIdentityIsUnchanged() {
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(a));
        assertEquals("the identity change is a selection", List.of(a), selections);
        assertEquals("a show is not a focus", List.of(), focusEvents);

        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.focus(a));
        assertEquals("the unchanged identity publishes no selection", List.of(a), selections);
        assertEquals("the focus is announced", List.of(a), focusEvents);

        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.focus(a));
        assertEquals("every accepted focus announces", List.of(a, a), focusEvents);
        assertEquals("and never a second selection", List.of(a), selections);
    }

    public void testAFocusOfAPaneThatIsNotOnScreenAnnouncesNothing() {
        controller.show(a);
        assertEquals(Outcome.UNAVAILABLE, controller.focus(b));
        assertEquals("a refused focus announces nothing", List.of(), focusEvents);
        assertEquals("and writes no selection", List.of(a), selections);
    }

    public void testFocusingTheHeldSessionMovesTheIdentityAndAnnouncesTheFocus() {
        controller.show(a);
        assertTrue(controller.hold());
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(b));
        assertEquals(List.of(a, b), selections);

        assertEquals(Outcome.FOCUSED_HELD, controller.focus(a));
        assertEquals("the held session's focus changes the identity too",
                List.of(a, b, a), selections);
        assertEquals(List.of(a), focusEvents);
    }
}
