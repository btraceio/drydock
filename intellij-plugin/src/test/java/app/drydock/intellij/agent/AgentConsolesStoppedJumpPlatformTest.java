package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.IsolatedCatalog;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertSame;
import static junit.framework.TestCase.fail;

/**
 * The stopped half of the own-project jump, in a booted IDE: a session with
 * no execution and no catalog entry — one a hosted tab cannot answer for —
 * still shows a console tab in its own project's window, and showing it
 * starts nothing.
 *
 * <p>The id is fresh, so it is in no catalog; the test writes none. Opening
 * the console file initializes the plugin and reads the (sandboxed) catalog,
 * the same read a reopened hosted tab performs.</p>
 *
 * <p>The file also pins the start path's show step: a show that fails while
 * a launch is starting must clear the session's starting mark, through the
 * same production guard that clears it when the background task is
 * refused.</p>
 *
 * <p>The test editor manager does not consult registered providers — it
 * opens every file as a text editor, which a content-less console file
 * cannot be (SDK proof: {@code TestEditorManagerImpl.openFileImpl3} reads
 * the provider from {@code FileEditorProvider.KEY} user data and falls back
 * to {@code doOpenTextEditor}, whose {@code FileDocumentManager.getDocument}
 * returns null for a {@code VirtualFileWithoutContent}). The test hands it
 * our provider the way a real editor manager would find it.</p>
 */
public class AgentConsolesStoppedJumpPlatformTest extends BasePlatformTestCase {

    private ManagedSessionId sessionId;
    private AgentConsoleFile file;

    @Override
    protected void setUp() throws Exception {
        // A fresh id: no catalog, no execution — the stopped case at its
        // barest, before any window opens.
        sessionId = ManagedSessionId.newId();
        super.setUp();
        file = AgentConsoleFileSystem.getInstance().fileFor(sessionId);
        // How the test editor manager finds the console's provider; without
        // it, it would open the file as a text editor with no document.
        file.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
    }

    @Override
    protected void tearDown() throws Exception {
        Project project = getProject();
        FileEditorManager files = FileEditorManager.getInstance(project);
        // The test editor manager's closeFile does not dispose a custom
        // provider's editor (its default disposeEditor is empty), so the
        // tab's own dispose runs here — its age ticker must not outlive the
        // test.
        for (FileEditor editor : files.getAllEditors(file)) {
            if (editor instanceof AgentConsoleEditor console) {
                console.dispose();
            }
        }
        files.closeFile(file);
        super.tearDown();
    }

    public void testShowStoppedOpensTheTabAndStartsNothing() {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();

        assertTrue("a fresh id has no execution", registry.execution(sessionId).isEmpty());

        AgentConsoles.showStopped(getProject(), getProject(), sessionId);

        // The test editor manager's isFileOpen only knows text editors (its
        // internal map is populated solely by doOpenTextEditor), so "the file
        // is open" here is the tab the splitter holds: our provider's editor
        // for this console file.
        FileEditor[] open = FileEditorManager.getInstance(getProject()).getAllEditors(file);
        assertEquals("the stopped session's tab is open in the target window", 1, open.length);
        assertTrue(open[0] instanceof AgentConsoleEditor);
        // show(target, id) is a show, never a start: nothing was launched, so
        // the guard layer's starting flag was never set and stays clear.
        assertTrue(registry.execution(sessionId).isEmpty());
        assertFalse(registry.isRunning(sessionId));
        assertFalse(registry.isStarting(sessionId));
    }

    public void testShowStoppedInTheSameWindowKeepsTheTabOpen() {
        // host == target: the tab is already where the jump lands, so the
        // host tab is not closed behind the jump's back.
        AgentConsoles.showStopped(getProject(), getProject(), sessionId);

        assertTrue(FileEditorManager.getInstance(getProject()).getAllEditors(file).length == 1);
    }

    public void testShowExistingWithoutALiveExecutionShowsNothingAndStartsNothing() {
        // The end race of Show agent (A6): the surface offered Show agent
        // while the execution ran elsewhere, and it ended before the click.
        // A fresh id is that state at its barest — no execution, no catalog —
        // and showing it must neither create an execution nor mark starting.
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();

        assertTrue("a fresh id has no execution", registry.execution(sessionId).isEmpty());

        assertEquals("nothing runs, so nothing can be shown",
                AgentConsoles.ShowOutcome.ENDED,
                AgentConsoles.showExisting(getProject(), sessionId));

        assertTrue("showExisting never creates an execution", registry.execution(sessionId).isEmpty());
        assertFalse(registry.isRunning(sessionId));
        assertFalse("showExisting never marks the session starting", registry.isStarting(sessionId));
    }

    public void testTheRealLifecycleHandlerDispatchesAPaintedShowAgentWhoseExecutionEndedAsShowNeverAStart() {
        // The end race through the REAL five-argument handler (A6): the
        // surface painted Show agent (detached), and the execution ended
        // before the click — the fresh id has none. The session carries a
        // conversation, so its CURRENT state is Resume: a dispatch on the
        // current state, or a Show agent routed to resumeOrStart, would take
        // the start path (mark starting, open the tab). The id is in no
        // catalog, so even that mutated path could launch nothing.
        IsolatedCatalog.assertSandboxed();
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        ManagedAgentSession session = new ManagedAgentSession.Builder(sessionId, RepositoryId.newId(),
                "Ended", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/Ended")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build()
                .withAgentSessionId(Optional.of("pi-conversation-ended"));
        SessionCommands.State paintedDetached = new SessionCommands.State(
                true, true, false, false, false, false, false, true);
        assertEquals("precondition: the painted state is Show agent",
                SessionCommands.Lifecycle.SHOW_AGENT, SessionCommands.lifecycle(paintedDetached));
        assertEquals("precondition: the current state would be Resume",
                SessionCommands.Lifecycle.RESUME,
                SessionCommands.lifecycle(SessionCommands.stateOf(session, Optional.empty(), false)));
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> {
            shown.add(message);
            return Messages.OK;
        }, getTestRootDisposable());

        SessionCommandHandlers.lifecycle(getProject(), session, paintedDetached, Optional.empty(), () -> {
        });

        assertEquals("the ended Show agent is reported, and nothing else is shown",
                List.of("Ended has already ended."), shown);
        assertTrue("no execution was created", registry.execution(sessionId).isEmpty());
        assertFalse(registry.isRunning(sessionId));
        assertFalse("nothing was marked starting", registry.isStarting(sessionId));
        assertEquals("no console tab was opened for a start",
                0, FileEditorManager.getInstance(getProject()).getAllEditors(file).length);
    }

    public void testAShowThatFailsDuringStartClearsStarting() {
        // The start path marks the session starting before it opens the
        // tab; a show that throws must leave that mark cleared — through the
        // production guard, not a test-only reset — and must propagate.
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        RuntimeException showFailed = new RuntimeException("show refused");
        try {
            AgentConsoles.start(getProject(), sessionId, "Resuming Drydock session…", "resume",
                    sessions -> CompletableFuture.<ManagedAgentSession>completedFuture(null),
                    (project, id) -> {
                        throw showFailed;
                    });
            fail("the show failure propagates to the caller");
        } catch (RuntimeException e) {
            assertSame(showFailed, e);
        }
        assertFalse("a failed show leaves nothing starting", registry.isStarting(sessionId));
        assertTrue("nothing was launched", registry.execution(sessionId).isEmpty());
    }
}
