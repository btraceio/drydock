package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.tour.HunkTake;
import com.intellij.diff.util.Side;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.ActionLink;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The workbench's section view over a fake review: one card per hunk, the
 * collapsed "reviewed in §N" row and Show Again, Mark Reviewed for a hunk
 * and for the section, Full File, and the cards' lazily created diffs.
 */
public class ReviewWorkbenchSectionViewPlatformTest extends BasePlatformTestCase {

    private final List<String> calls = new ArrayList<>();
    private CompletableFuture<Optional<String>> marked = CompletableFuture.completedFuture(Optional.empty());

    private final ReviewWorkbenchSectionView.Actions actions = new ReviewWorkbenchSectionView.Actions() {
        @Override
        public CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(String path,
                                                                                        String scrollKey) {
            calls.add("context " + path + " " + scrollKey);
            return CompletableFuture.completedFuture(Optional.of(new ReviewDiffRequests.RequestContext(path,
                    ReviewDiffRequests.plan("a\nb\n", "a\nc\n", List.of(),
                            new ReviewDiffRequests.ScrollTarget(Side.RIGHT, 1)))));
        }

        @Override
        public CompletableFuture<Optional<String>> markReviewed(SectionsModel.Section section, List<String> digests) {
            calls.add("mark " + section.key() + " " + digests);
            return marked;
        }

        @Override
        public void fullFile(String path) {
            calls.add("fullFile " + path);
        }

        @Override
        public void showFinding(String findingId) {
            calls.add("showFinding " + findingId);
        }
    };

    private static SectionsModel.Hunk hunk(String id, boolean reviewed, Optional<SectionsModel.Settled> elsewhere) {
        return hunk(id, reviewed, elsewhere, Optional.empty());
    }

    private static SectionsModel.Hunk hunk(String id, boolean reviewed, Optional<SectionsModel.Settled> elsewhere,
                                           Optional<HunkTake> take) {
        return new SectionsModel.Hunk("src/" + id + ".java", 0, "src/" + id + ".java#0", "d-" + id, "3–4", "n3",
                0, 2, List.of(), reviewed, elsewhere, take);
    }

    private static final HunkTake TAKE = new HunkTake("d-A", "Calls bar() and drops its result.",
            HunkTake.Alignment.DIVERGES, "The intent says callers use the result.",
            List.of(new HunkTake.RedFlag("The result is ignored.", Optional.of("f1")),
                    new HunkTake.RedFlag("No test covers it.", Optional.empty())),
            Instant.EPOCH);

    public void testACardShowsTheTakeFoldsItAndLinksARedFlagToItsFinding() {
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(), actions);
        try {
            view.render(state(hunk("A", false, Optional.empty(), Optional.of(TAKE)), hunk("B", false, Optional.empty())));
            ReviewWorkbenchSectionView.TakePanel take = view.cards.get(0).take;
            assertTrue(take.isVisible());
            assertEquals(List.of("▾ Agent take · Diverges", "Calls bar() and drops its result.",
                    "The intent says callers use the result.", "⚑ The result is ignored.", "⚑ No test covers it."),
                    take.shownTexts());
            assertFalse("a hunk without a take shows none", view.cards.get(1).take.isVisible());

            List<ActionLink> links = new ArrayList<>();
            UIUtil.uiTraverser(take).filter(ActionLink.class).forEach(links::add);
            assertEquals("the header and the one red flag with a finding", 2, links.size());
            links.get(1).doClick();
            assertEquals(List.of("showFinding f1"), calls);

            take.toggle.doClick();
            assertEquals(List.of("▸ Agent take · Diverges"), take.shownTexts());
        } finally {
            Disposer.dispose(view);
        }
    }

    private static WorkbenchState state(SectionsModel.Hunk... hunks) {
        int reviewed = (int) List.of(hunks).stream().filter(SectionsModel.Hunk::reviewed).count();
        SectionsModel.Section section = new SectionsModel.Section("step:s2", 2, Optional.of("s2"), "Callers",
                "Why the callers change.", List.of(1), List.of(), List.of(hunks), reviewed, hunks.length, 0);
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, new ReviewTabModel.Snapshot(List.of(), 0, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT,
                new SectionsModel.Snapshot(SectionsModel.Source.TOUR, Optional.empty(), List.of(section)),
                new WorkbenchState.SectionsChoice(Optional.of(WorkbenchState.Navigator.SECTIONS),
                        Optional.of("step:s2")));
    }

    private static final SectionsModel.Settled IN_FIRST = new SectionsModel.Settled(1, "Widgets");

    public void testOneCardPerHunkAndACollapsedRowThatShowsAgain() {
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(), actions);
        try {
            view.render(state(hunk("A", false, Optional.empty()), hunk("B", true, Optional.of(IN_FIRST))));
            assertEquals(2, view.cards.size());
            assertEquals("src/A.java · 3–4 · −0 +2", view.cards.get(0).header.getText());
            assertEquals("reviewed in §1 · Widgets", view.cards.get(1).header.getText());

            click(view.cards.get(1).component, ReviewWorkbenchSectionView.SHOW_AGAIN);
            assertEquals("src/B.java · 3–4 · −0 +2", view.cards.get(1).header.getText());
            assertEquals(ReviewWorkbenchSectionView.REVIEWED, view.cards.get(1).markButton.getText());
            assertFalse(view.cards.get(1).markButton.isEnabled());
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testMarkSectionWritesTheUnsettledDigestsAndStaysBusyUntilDone() {
        marked = new CompletableFuture<>();
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(), actions);
        try {
            view.render(state(hunk("A", false, Optional.empty()), hunk("B", true, Optional.empty()),
                    hunk("C", false, Optional.empty())));
            view.markSection.doClick();
            assertEquals(List.of("mark step:s2 [d-A, d-C]"), calls);
            assertFalse("busy while marking", view.markSection.isEnabled());
            assertEquals(ReviewWorkbenchSectionView.MARKING, view.notice.getText());

            marked.complete(Optional.of("cannot approve while the open blocking finding f1 is unresolved"));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(view.markSection.isEnabled());
            assertEquals("cannot approve while the open blocking finding f1 is unresolved", view.notice.getText());
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testAHunksMarkAndFullFileGoToTheReview() {
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(), actions);
        try {
            view.render(state(hunk("A", false, Optional.empty())));
            view.cards.get(0).markButton.doClick();
            click(view.cards.get(0).component, ReviewWorkbenchSectionView.FULL_FILE);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("mark step:s2 [d-A]", "fullFile src/A.java"), calls);
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testACardInViewGetsItsDiffAndLosesItWhenTheSectionGoes() {
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(), actions);
        try {
            view.setSize(800, 900);
            view.render(state(hunk("A", false, Optional.empty())));
            layOut(view);
            view.updateLiveCards();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("context src/A.java n3"), calls);
            assertTrue("the card in view has its diff", view.cards.get(0).live());

            ReviewWorkbenchSectionView.Card card = view.cards.get(0);
            view.render(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                    new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                    WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT));
            assertFalse("a section that is no longer shown releases its editors", card.live());
            assertTrue(view.cards.isEmpty());
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testARenderWithTheSameLayoutKeepsTheLiveCards() {
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(), actions);
        try {
            view.setSize(800, 900);
            view.render(state(hunk("A", false, Optional.empty())));
            layOut(view);
            view.updateLiveCards();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            ReviewWorkbenchSectionView.Card card = view.cards.get(0);

            view.render(state(hunk("A", true, Optional.empty())));
            assertSame("a verdict only updates the card", card, view.cards.get(0));
            assertTrue(card.live());
            assertEquals(ReviewWorkbenchSectionView.REVIEWED, card.markButton.getText());
        } finally {
            Disposer.dispose(view);
        }
    }

    private static void layOut(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) {
                layOut(container);
            }
        }
    }

    private static void click(Container root, String text) {
        JButton button = find(root, text);
        assertNotNull("no button " + text, button);
        button.doClick();
    }

    private static JButton find(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container container) {
                JButton found = find(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
