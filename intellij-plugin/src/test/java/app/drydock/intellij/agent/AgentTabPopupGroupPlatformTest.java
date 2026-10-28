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

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

import java.time.Instant;

import java.util.Map;

import java.util.Optional;

/**
 * The agent tab's context-popup contribution over a real catalog, in a
 * booted IDE (A10, A7, A9, A12): the registered {@code Drydock.AgentTabPopup}
 * group sits inside the stock {@code EditorTabPopupMenu}, contributes nothing
 * for a non-agent tab (or a tab whose session is not loaded), and for a
 * seeded stopped agent tab carries exactly Lifecycle, Rename and Done —
 * separated, with one trailing separator before the stock entries, no
 * leading or doubled separator, and no section whose facts do not apply
 * (no OwnProject unhosted, no Changes stopped, no Transfer stopped).
 *
 * <p>The popup events are the platform's own shape: the right-clicked tab's
 * {@code VIRTUAL_FILE} and the project, at {@code EDITOR_TAB_POPUP} with
 * {@code ActionUiKind.POPUP} — the context the SDK proof showed the popup
 * carries. Everything here is a <em>headless platform check</em>: the real
 * {@code getChildren()} and the real registered {@code update()}. No menu is
 * ever shown, nothing is performed, no agent is launched — the rendering of
 * the popup on screen belongs to the visual verification stage, and the
 * running-session Changes and Transfer cases are T12's pure table.</p>
 */
public class AgentTabPopupGroupPlatformTest extends BasePlatformTestCase {

    private ManagedAgentSession sessionA;
    private ManagedAgentSession sessionB;
    private AgentConsoleFile fileA;
    private AgentConsoleFile fileB;

    @Override
    protected void setUp() throws Exception {
        // The guard runs BEFORE super.setUp(): before anything in this test
        // can initialize the plugin, and before any catalog write below.
        IsolatedCatalog.assertSandboxed();
        System.out.println("T15 sandbox: config=" + PathManager.getConfigPath()
                + ", projectsFile=" + System.getProperty("drydock.projectsFile"));
        super.setUp();

        // Two stopped sessions in the sandboxed catalog: A carries an agent
        // session id (a conversation to resume into), B does not.
        sessionA = stopped("PopupA", Optional.of("pi-conversation-popup"));
        sessionB = stopped("PopupB", Optional.empty());
        IsolatedCatalog.seed(sessionA);
        IsolatedCatalog.seed(sessionB);
        fileA = AgentConsoleFileSystem.getInstance().fileFor(sessionA.id());
        fileB = AgentConsoleFileSystem.getInstance().fileFor(sessionB.id());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            IsolatedCatalog.remove(sessionA.id(), sessionB.id());
        } finally {
            super.tearDown();
        }
    }

    public void testTheStockTabPopupCarriesTheDrydockContributionOnTheEdt() {
        ActionManager actions = ActionManager.getInstance();
        AnAction contribution = actions.getAction("Drydock.AgentTabPopup");
        assertNotNull("Drydock.AgentTabPopup must be registered", contribution);
        assertSame("the contribution must be the dynamic group class",
                AgentTabPopupGroup.class, contribution.getClass());
        assertEquals("the popup group enumerates its children on the EDT (it reads "
                        + "the catalog, the registry and per-editor facts)",
                ActionUpdateThread.EDT, contribution.getActionUpdateThread());

        AnAction stock = actions.getAction("EditorTabPopupMenu");
        assertTrue("EditorTabPopupMenu must be registered as a group the "
                + "contribution can ride", stock instanceof DefaultActionGroup);
        boolean contained = false;
        for (AnAction child : ((DefaultActionGroup) stock).getChildren(stockEvent())) {
            contained |= child == contribution;
        }
        assertTrue("EditorTabPopupMenu must contain Drydock.AgentTabPopup "
                + "(add-to-group only, no stock entry touched)", contained);
    }

    public void testAnOrdinaryFileAnUnknownConsoleOrNoEventContributeNothing() {
        AgentTabPopupGroup group = popupGroup();
        assertEquals("an ordinary file's tab popup must keep its stock entries "
                        + "untouched (rejection 1)",
                0, group.getChildren(popupEvent(new LightVirtualFile("A.java"), getProject())).length);
        assertEquals("a console tab whose session is not in the loaded catalog "
                        + "has no Drydock commands",
                0, group.getChildren(popupEvent(
                        AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId()),
                        getProject())).length);
        assertEquals("the platform enumerates some groups with no event; there "
                        + "is no target then either",
                0, group.getChildren(null).length);
    }

    public void testAStoppedAgentTabCarriesLifecycleRenameDoneSeparated() {
        AgentTabPopupGroup group = popupGroup();
        AnAction[] children = group.getChildren(popupEvent(fileA, getProject()));
        assertEquals("a stopped unhosted tab carries Lifecycle, Rename, Done and "
                        + "three separators — no more",
                6, children.length);
        assertSame("first member: the lifecycle, never a leading separator",
                registered("Lifecycle"), children[0]);
        assertTrue("a separator between Lifecycle and Rename", children[1] instanceof Separator);
        assertSame(registered("Rename"), children[2]);
        assertTrue("a separator between Rename and Done", children[3] instanceof Separator);
        assertSame(registered("Done"), children[4]);
        assertTrue("one trailing separator before the stock entries",
                children[5] instanceof Separator);

        // The sections whose facts do not apply contribute nothing — not even
        // their separator: unhosted means no OwnProject (A8); a stopped
        // session means no Changes and no Transfer (their running and
        // checkout-known cases are T12's pure table).
        for (AnAction child : children) {
            assertNotSame("unhosted: no OwnProject", registered("OwnProject"), child);
            assertNotSame("stopped: no Changes", registered("Changes"), child);
            assertNotSame("stopped: no Transfer", registered("Transfer"), child);
        }
    }

    public void testEachChildsRealUpdateGivesVisiblePopupText() {
        AnActionEvent e = popupEvent(fileA, getProject());
        Map<Class<?>, String> expected = Map.of(
                AgentTabActions.Lifecycle.class, "Resume",
                AgentTabActions.Rename.class, "Rename…",
                AgentTabActions.Done.class, "Done…");
        for (AnAction child : popupGroup().getChildren(e)) {
            if (child instanceof Separator) {
                continue;
            }
            Presentation presentation = new Presentation();
            child.update(AnActionEvent.createEvent(e.getDataContext(), presentation,
                    ActionPlaces.EDITOR_TAB_POPUP, ActionUiKind.POPUP, null));
            assertTrue("every offered command must be visible in the popup",
                    presentation.isVisible());
            assertTrue("an idle stopped session must leave the command enabled",
                    presentation.isEnabled());
            assertEquals("the popup text of " + child.getClass().getSimpleName(),
                    expected.get(child.getClass()), presentation.getText());
        }
    }

    public void testAStoppedSessionWithoutAConversationSaysStart() {
        // Same popup shape as A (both are stopped unhosted sessions); B has
        // no conversation recorded, so the lifecycle's own update says Start.
        AnAction[] children = popupGroup().getChildren(popupEvent(fileB, getProject()));
        assertSame(registered("Lifecycle"), children[0]);
        Presentation presentation = new Presentation();
        children[0].update(AnActionEvent.createEvent(context(fileB, getProject()), presentation,
                ActionPlaces.EDITOR_TAB_POPUP, ActionUiKind.POPUP, null));
        assertEquals("Start", presentation.getText());
        assertTrue(presentation.isVisible());
    }

    // ---- helpers -----------------------------------------------------------------

    /** The registered contribution, fetched the way the popup does. */
    private static AgentTabPopupGroup popupGroup() {
        AnAction group = ActionManager.getInstance().getAction("Drydock.AgentTabPopup");
        assertNotNull("Drydock.AgentTabPopup must be registered", group);
        assertTrue("Drydock.AgentTabPopup must be the dynamic group class",
                group instanceof AgentTabPopupGroup);
        return (AgentTabPopupGroup) group;
    }

    /** The registered shared action {@code Drydock.AgentTab.<id>}. */
    private static AnAction registered(String id) {
        AnAction action = ActionManager.getInstance().getAction("Drydock.AgentTab." + id);
        assertNotNull("Drydock.AgentTab." + id + " must be registered", action);
        return action;
    }

    /**
     * The popup's own context, the shape the SDK proof established: the
     * right-clicked tab's file and the project, at the popup place. Pure
     * data — no component hierarchy, so no data-manager walk is needed.
     */
    private static DataContext context(VirtualFile file, Project project) {
        return SimpleDataContext.builder()
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .add(CommonDataKeys.PROJECT, project)
                .build();
    }

    private static AnActionEvent popupEvent(VirtualFile file, Project project) {
        return AnActionEvent.createEvent(context(file, project), new Presentation(),
                ActionPlaces.EDITOR_TAB_POPUP, ActionUiKind.POPUP, null);
    }

    /** An event to enumerate a group's children with, like the platform's own group path. */
    private static AnActionEvent stockEvent() {
        return AnActionEvent.createEvent(DataContext.EMPTY_CONTEXT, new Presentation(),
                ActionPlaces.EDITOR_TAB_POPUP, ActionUiKind.POPUP, null);
    }

    /** A stopped session the way the existing test factories build them, with the given conversation id. */
    private static ManagedAgentSession stopped(String displayName, Optional<String> agentSessionId) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                RepositoryId.newId(), displayName, AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + displayName)), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
        return session.withAgentSessionId(agentSessionId);
    }
}
