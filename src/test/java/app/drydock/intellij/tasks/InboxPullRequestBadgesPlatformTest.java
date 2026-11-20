package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.InboxModel.Group;
import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.SessionItem;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class InboxPullRequestBadgesPlatformTest extends BasePlatformTestCase {
    private static final PullRequestTarget TARGET = new PullRequestTarget("github.com/DataDog/java-profiler", 898);

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
    }

    private static SessionItem item(Optional<PullRequestTarget> target, BranchPullRequest pr) {
        var session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), "Session",
                AgentBinding.unlaunched(AgentKind.PI), SessionWorkspace.inRepository(Path.of("/tmp/wt")), Instant.EPOCH)
                .targetPullRequest(target).build();
        return new SessionItem(new TaskRow(session, Optional.empty(), false, Optional.of("brave-dewy-alpaca"),
                new TaskPlacement.Idle(false), false, false, true, Optional.ofNullable(pr)),
                Optional.empty(), List.of());
    }

    public void testEachLifecycleIconIsPaintedBesideTheNumberAndResetWhenUnknown() {
        InboxList list = new InboxList(new InboxList.Actions() {
            @Override public void activate(InboxModel.Item item) { }
            @Override public void openPullRequest(InboxModel.Item item) { }
            @Override public void pickProject(InboxModel.ProjectRef project) { }
            @Override public void togglesChanged() { }
        });
        Group group = new Group(GroupKind.IDLE, "Idle", List.of());
        for (PrLifecycle state : PrLifecycle.values()) {
            for (boolean draft : new boolean[] {false, true}) {
                var pr = new BranchPullRequest(898, state, draft, "head", "PR", Optional.of(TARGET.url()));
                var item = item(Optional.of(TARGET), pr);
                var entry = new InboxList.Row(group, item);
                Component painted = list.getCellRenderer().getListCellRendererComponent(list, entry, 0, false, false);
                JLabel badge = label(painted, "#898");
                assertNotNull(badge);
                assertSame(TaskTreeRenderer.pullRequestIcon(TaskTreeRenderer.badgeKind(pr)).orElse(null),
                        badge.getIcon());
                if (state != PrLifecycle.UNKNOWN) {
                    assertNotNull(badge.getIcon());
                    assertTrue(badge.getIcon().getIconWidth() > 0);
                }
            }
        }
    }

    public void testExplicitTargetNumberAndUrlNeverFollowAStaleBranchBadge() {
        var wrongBranch = new BranchPullRequest(943, PrLifecycle.MERGED, false, "brave-dewy-alpaca", "Other",
                Optional.of("https://github.com/DataDog/java-profiler/pull/943"));
        var item = item(Optional.of(TARGET), wrongBranch);
        var badge = InboxList.pullRequestLabel(item).orElseThrow();
        assertEquals(898, badge.number());
        assertTrue(badge.link());
        assertEquals(TaskTreeRenderer.PullRequestBadgeKind.UNKNOWN, badge.kind());
        assertEquals(Optional.of(TARGET.url()), TaskTreeRenderer.pullRequestUrl(item.row()));
        assertEquals(943, InboxList.pullRequestLabel(item(Optional.empty(), wrongBranch)).orElseThrow().number());
        assertEquals(898, InboxList.pullRequestLabel(item(Optional.of(TARGET), null)).orElseThrow().number());
    }

    private static JLabel label(Component component, String text) {
        if (component instanceof JLabel label && text.equals(label.getText())) {
            return label;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = label(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
