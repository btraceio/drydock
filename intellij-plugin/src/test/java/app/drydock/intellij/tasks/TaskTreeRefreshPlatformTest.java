package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.Deferred;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The tree's real shared Refresh action, headless: its {@code actionPerformed}
 * is driven with real {@link AnActionEvent}s — a toolbar event and a popup
 * event, the two places the one action instance sits in — against a
 * recording {@link TaskTreePanel.RefreshTarget}, so the scope each place
 * really dispatches is what is asserted (F8): global from the toolbar
 * whatever the tree selection, one project's own listing only from a
 * section's context menu.
 *
 * <p>No {@code gh}, no network, no rebuild: the recording target stands in
 * for the panel's caches, so nothing here reads or writes any real state —
 * no catalog, no provider or IDE stores. The context/toolbar split comes
 * from the event's own {@code isFromContextMenu} semantics, the same the
 * production popup and toolbar deliver. This is a headless dispatch check,
 * not a visual one: nothing here is rendered on screen.</p>
 */
public class TaskTreeRefreshPlatformTest extends BasePlatformTestCase {

    private static final Path REGISTERED_ROOT = Path.of("/work/profiling-backend");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");

    /** Records what one refresh gesture dispatched, in order (F8). */
    private static final class RecordingRefresh implements TaskTreePanel.RefreshTarget {

        final List<String> calls = new ArrayList<>();

        @Override
        public void invalidateAll() {
            calls.add("all");
        }

        @Override
        public void invalidateIssues(Path root) {
            calls.add("issues:" + root);
        }

        @Override
        public void invalidatePullRequests(Path root) {
            calls.add("pullRequests:" + root);
        }

        @Override
        public void requestListing(SectionKind kind, Path root) {
            calls.add("listing:" + kind + ":" + root);
        }

        @Override
        public void requestRebuild(boolean changedWorktrees) {
            calls.add("rebuild:" + changedWorktrees);
        }
    }

    /** A listing section with its deferred child, as the tree shows one. */
    private static Section section(SectionKind kind) {
        return new Section(kind, List.of(new Deferred("Reading…")));
    }

    private final RecordingRefresh target = new RecordingRefresh();
    private AnAction refresh;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        refresh = TaskTreePanel.refreshAction(target);
    }

    /** The event a toolbar button or a popup menu item would deliver. */
    private AnActionEvent event(ActionUiKind uiKind, String place, TaskNode node, Path root) {
        SimpleDataContext.Builder context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(TaskTreePanel.SELECTED_NODE, node);
        if (root != null) {
            context.add(TaskTreePanel.SELECTED_PROJECT_ROOT, root);
        }
        return AnActionEvent.createEvent(context.build(), new Presentation(), place, uiKind, null);
    }

    public void testTheToolbarRefreshStaysGlobalEvenWithASectionSelected() {
        refresh.actionPerformed(event(ActionUiKind.TOOLBAR, ActionPlaces.TOOLBAR,
                section(SectionKind.ISSUES), REGISTERED_ROOT));

        // A section being selected in the tree must not scope the toolbar's
        // gesture: it drops every project's listings and re-lists worktrees.
        assertEquals(List.of("all", "rebuild:true"), target.calls);
    }

    public void testAnIssuesSectionContextMenuRefreshTouchesOnlyThatProject() {
        refresh.actionPerformed(event(ActionUiKind.POPUP, ActionPlaces.POPUP,
                section(SectionKind.ISSUES), REGISTERED_ROOT));

        assertEquals(List.of("issues:" + REGISTERED_ROOT,
                "listing:ISSUES:" + REGISTERED_ROOT, "rebuild:false"), target.calls);
    }

    public void testAPullRequestSectionContextMenuRefreshTouchesOnlyThatProject() {
        refresh.actionPerformed(event(ActionUiKind.POPUP, ActionPlaces.POPUP,
                section(SectionKind.PULL_REQUESTS), REGISTERED_ROOT));

        assertEquals(List.of("pullRequests:" + REGISTERED_ROOT,
                "listing:PULL_REQUESTS:" + REGISTERED_ROOT, "rebuild:false"), target.calls);
    }

    public void testAProjectRowContextMenuRefreshKeepsTheGlobalScope() {
        ProjectRow row = new ProjectRow(SharedProject.local(
                RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW),
                false, false, List.of(), List.of(), 0);

        refresh.actionPerformed(event(ActionUiKind.POPUP, ActionPlaces.POPUP, row, REGISTERED_ROOT));

        // The project row's menu entry names a whole project, so it keeps
        // the toolbar's scope.
        assertEquals(List.of("all", "rebuild:true"), target.calls);
    }
}
