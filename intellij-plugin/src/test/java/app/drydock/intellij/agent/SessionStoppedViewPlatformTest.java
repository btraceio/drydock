package app.drydock.intellij.agent;

import com.intellij.execution.process.NopProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import javax.swing.plaf.LayerUI;

import java.awt.AWTEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * The stopped, dimmed body: the retained console stays readable, swallows
 * every key and click, and is never disposed by the view that borrowed it.
 */
public class SessionStoppedViewPlatformTest extends BasePlatformTestCase {

    private TerminalExecutionConsole console;
    private SessionStoppedView view;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        console = new TerminalExecutionConsole(getProject(), 80, 24, null);
        Disposer.register(getTestRootDisposable(), console);
        // The console only renders what an attached process feeds it.
        NopProcessHandler output = new NopProcessHandler();
        console.attachToProcess(output);
        output.startNotify();
        output.notifyTextAvailable("last agent output\r\n", ProcessOutputTypes.STDOUT);
        view = new SessionStoppedView(console, "Not running");
    }

    public void testInputIsBlockedBeforeItReachesTheConsole() {
        JComponent target = console.getComponent();
        KeyEvent key = new KeyEvent(target, KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, 'x');
        MouseEvent click = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false);

        assertTrue("layer listens for keys and mouse",
                (view.layer().getLayerEventMask() & AWTEvent.KEY_EVENT_MASK) != 0
                        && (view.layer().getLayerEventMask() & AWTEvent.MOUSE_EVENT_MASK) != 0);
        dispatch(key);
        dispatch(click);

        assertTrue("key swallowed", key.isConsumed());
        assertTrue("click swallowed", click.isConsumed());
    }

    public void testOutputStaysReadableUnderTheDimming() throws Exception {
        // print() is applied asynchronously by the terminal's own thread.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!screen().contains("last agent output") && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(25);
        }

        assertTrue(screen(), screen().contains("last agent output"));
        assertSame(console.getComponent(), view.layer().getView());
        assertTrue(UIUtil.isAncestor(view.component(), console.getComponent()));
        assertEquals("Not running", view.footerText());
        view.setFooter("Stopped 2m ago");
        assertEquals("Stopped 2m ago", view.footerText());
    }

    public void testTheConsoleOutlivesTheViewAndCanBeHandedOn() {
        view.release();

        assertFalse("release must not dispose the execution's console", Disposer.isDisposed(console));
        assertFalse(UIUtil.isAncestor(view.component(), console.getComponent()));
        SessionStoppedView next = new SessionStoppedView(console, "again");
        assertTrue(UIUtil.isAncestor(next.component(), console.getComponent()));
        assertFalse(Disposer.isDisposed(console));
    }

    private String screen() {
        return console.getTerminalWidget().getTerminalTextBuffer().getScreenLines();
    }

    @SuppressWarnings("unchecked")
    private void dispatch(AWTEvent event) {
        ((LayerUI<JComponent>) view.layer().getUI()).eventDispatched(event, view.layer());
    }
}
