package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.IsolatedCatalog;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * The pane's identity lookup, keyed facts application and strip commands in a
 * booted IDE, over the sandboxed catalog ({@link IsolatedCatalog}). The only
 * effect boundary is {@link SessionPane.Lifecycle}: it records every dispatch
 * and forwards ONLY a Show agent to the real handler, so a wrong branch is
 * recorded, never a started provider. An execution is a local {@code sleep}
 * child, hosted by the default project when it must be detached.
 */
public class SessionPaneCommandsPlatformTest extends BasePlatformTestCase {

    /** Host that only serves cached facts and counts every other call, so a repaint's reach is visible. */
    private final class CountingHost implements SessionPane.Host {
        SessionPaneFacts cached;
        int factsReads, otherCalls;
        @Override public boolean focusPermitted(long epoch) { otherCalls++; return true; }
        @Override public long epoch() { otherCalls++; return 1; }
        @Override public void paneFocused(ManagedSessionId focused) { otherCalls++; }
        @Override public Optional<SessionPaneFacts> facts(ManagedSessionId asked) {
            factsReads++;
            return asked.equals(id) && cached != null ? Optional.of(cached) : Optional.empty();
        }
        @Override public void onStripAction(SessionPane pane, AgentConsoleStrip.StripAction action) { otherCalls++; }
    }

    private final AgentExecutionListener owner = new AgentExecutionListener() { };
    private final CountingHost host = new CountingHost();
    private AgentExecutionRegistry registry;
    private ManagedSessionId id;
    private ManagedAgentSession session;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        session = session(SessionStatus.RUNNING, Optional.empty());
        id = session.id();
        IsolatedCatalog.seed(session);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            registry.forget(id);
            waitFor(() -> registry.execution(id).isEmpty());
            IsolatedCatalog.remove(id);
        } finally {
            super.tearDown();
        }
    }

    private static ManagedAgentSession session(SessionStatus status, Optional<WorkspaceBoxBinding> box) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), "Pane commands",
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/pane-commands")), Instant.EPOCH)
                .status(status).workspaceBox(box).build();
    }

    private AgentExecution start(Project hostProject) throws Exception {
        var started = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30"), hostProject, owner);
        waitFor(started::isDone);
        return started.get(1, TimeUnit.SECONDS);
    }

    private SessionPane pane() { return new SessionPane(getProject(), id, host); }

    private static void waitFor(BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching("condition not met within 10 s", condition, 10); }

    private static SessionFactsLoader.Result result(ManagedSessionId of, long generation, SessionPaneFacts facts) {
        return new SessionFactsLoader.Result(of, generation, facts); }

    private static SessionPaneFacts known() {
        return new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false);
    }

    public void testSessionByIdFindsTheSeededRowAndSessionOfFileAgreesAndBothAreNullForAbsent() {
        ManagedSessionId absent = ManagedSessionId.newId();
        assertEquals(session, AgentConsoleTabPresentation.sessionById(id));
        assertSame("one shared index: the file resolver delegates to the same row",
                AgentConsoleTabPresentation.sessionById(id),
                AgentConsoleTabPresentation.sessionOf(AgentConsoleFileSystem.getInstance().fileFor(id)));
        assertNull(AgentConsoleTabPresentation.sessionById(absent));
        assertNull(AgentConsoleTabPresentation.sessionOf(AgentConsoleFileSystem.getInstance().fileFor(absent)));
        assertNull("not an agent console file", AgentConsoleTabPresentation.sessionOf(myFixture.configureByText("a.txt", "x").getVirtualFile()));
    }

    public void testFactsForAnotherIdOrAnObsoleteGenerationAreIgnoredAndNewerOnesApply() {
        SessionPane pane = pane();
        assertFalse("another session's result", pane.applyFacts(result(ManagedSessionId.newId(), 9, SessionPaneFacts.failed("other"))));
        assertEquals(SessionPane.Body.LOADING, pane.shown());
        assertFalse("the inert generation-0 result", pane.applyFacts(result(id, 0, SessionPaneFacts.unknown())));
        assertEquals(SessionPane.Body.LOADING, pane.shown());
        assertTrue(pane.applyFacts(result(id, 5, SessionPaneFacts.failed("boom"))));
        assertEquals(SessionPane.Body.FAILED, pane.shown());
        assertFalse("obsolete generation", pane.applyFacts(result(id, 4, known())));
        assertEquals("the older answer did not repaint", SessionPane.Body.FAILED, pane.shown());
        assertTrue("a newer generation replaces it", pane.applyFacts(result(id, 6, known())));
        assertEquals(SessionPane.Body.EMPTY, pane.shown());
        assertEquals("the title is the shared presentation's", "pane-commands · Pane commands", pane.title());
        assertEquals("applying facts reaches only the host's cached read", 0, host.otherCalls);
        pane.dispose();
        assertFalse("a result captured before disposal is not applied", pane.applyFacts(result(id, 7, SessionPaneFacts.failed("late"))));
        assertEquals("a disposed pane stays as it was", SessionPane.Body.EMPTY, pane.shown());
    }

    public void testRefreshReadsCachedFailedFactsWithNoFocusLoaderOrHostCall() {
        SessionPane pane = pane();
        host.cached = SessionPaneFacts.failed("probe died");
        pane.refresh();
        pane.refresh();
        assertEquals("the cached FAILED answer is shown as it is, never retried or replaced", SessionPane.Body.FAILED, pane.shown());
        assertEquals("one pure cached read per repaint", 2, host.factsReads);
        assertEquals("no focus, epoch, strip or selection call from a repaint", 0, host.otherCalls);
    }

    public void testAKnownAnswerWithNoCatalogRowIsSaidHonestlyAndNoSessionIsInvented() throws Exception {
        IsolatedCatalog.remove(id);
        SessionPane pane = pane();
        host.cached = known();
        pane.refresh();
        assertEquals(SessionPane.Body.DELETED, pane.shown());
        assertEquals("", pane.title());
    }

    public void testARemoteRowShowsItsBoxVerdictNotLocalActivity() throws Exception {
        IsolatedCatalog.remove(id);
        session = session(SessionStatus.RUNNING, Optional.of(new WorkspaceBoxBinding("box-one", "/Users/dev/clone")));
        id = session.id();
        IsolatedCatalog.seed(session);
        SessionPane pane = pane();
        host.cached = known();
        pane.refresh();
        assertEquals("clone · Pane commands · on box-one", pane.title());
        String text = UIUtil.findComponentsOfType(pane.strip(), JBLabel.class).stream()
                .map(JBLabel::getText).collect(Collectors.joining("|"));
        assertTrue("the strip names the box: " + text, text.contains("box-one"));
        assertFalse("no local activity word on a remote row: " + text, text.toLowerCase().contains("working"));
    }

    public void testAStaleShowClickNeverBecomesStartOrResume() throws Exception {
        AgentExecution execution = start(ProjectManager.getInstance().getDefaultProject());
        SessionPane pane = pane();
        host.cached = known();
        pane.refresh();   // painted: running elsewhere, so Show agent is offered
        execution.handler().destroyProcess();
        waitFor(() -> !execution.isRunning());
        // A refresh through a vanished row repaints no strip (its Show button stays); the invited intent must survive the gap.
        IsolatedCatalog.remove(id);
        pane.refresh();
        IsolatedCatalog.seed(session);
        List<SessionCommands.State> dispatched = new ArrayList<>();
        List<String> forwarded = new ArrayList<>();
        List<String> dialogs = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> { dialogs.add(message); return Messages.OK; }, getTestRootDisposable());
        SessionPane.Lifecycle spy = (project, current, presented, resumable, after) -> {
            assertSame("the pane's own project", getProject(), project);
            assertEquals("its own immutable id", id, current.id());
            dispatched.add(presented);
            // The effect boundary: only Show agent reaches the real handler; a Start or Resume is recorded and held.
            if (SessionCommands.lifecycle(presented) == SessionCommands.Lifecycle.SHOW_AGENT) {
                forwarded.add("show");
                SessionCommandHandlers.lifecycle(project, current, presented, resumable, after);
            }
        };
        pane.command(AgentConsoleStrip.StripAction.SHOW_AGENT, spy);
        assertEquals(1, dispatched.size());
        assertEquals("the click dispatches on what was painted, not on the ended state",
                SessionCommands.Lifecycle.SHOW_AGENT, SessionCommands.lifecycle(dispatched.get(0)));
        assertEquals("the real Show handler ran; no start or resume was reached", List.of("show"), forwarded);
        assertEquals(1, dialogs.size());
        assertTrue("it reports the end, starting nothing: " + dialogs, dialogs.get(0).contains("has already ended"));
        assertTrue("and no execution was started again", registry.execution(id).map(e -> !e.isRunning()).orElse(true));
    }

    public void testACommandOnADisposedPaneOrAnAbsentRowDoesNothing() throws Exception {
        SessionPane disposed = pane();
        List<String> calls = new ArrayList<>();
        SessionPane.Lifecycle spy = (project, current, presented, resumable, after) -> calls.add("run");
        disposed.dispose();
        disposed.command(AgentConsoleStrip.StripAction.RESUME, spy);
        IsolatedCatalog.remove(id);
        pane().command(AgentConsoleStrip.StripAction.RESUME, spy);
        assertEquals(List.of(), calls);
    }
}
