package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SubmitPlan;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Review destination driven end to end through the headless JavaFX
 * harness (Monocle + TestFX; see the Monocle block in
 * {@code app/build.gradle.kts}): real key events against a real Stage with
 * the real stylesheets applied.
 *
 * <p>Covers the part of the keyboard table (spec §5) that M1 binds, and the
 * hard rule that every interactive element is focus-traversable -- neither
 * is reachable from a pure unit test, and both are exactly what regresses
 * silently.</p>
 */
class ReviewDestinationViewTest extends ApplicationTest {

    /** Wider than the 1320px narrow threshold, so the rails start expanded. */
    private static final double SCENE_WIDTH = 1400;

    private FakeReviewHost host;
    private ReviewDestinationView view;
    private final app.drydock.git.DiffService diffService = new app.drydock.git.DiffService();

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(java.nio.file.Files.createTempDirectory("drydock-review-view")
                    .resolve("annotations.json"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, SCENE_WIDTH, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void jAndKWalkTheQueueAndClampAtBothEnds() {
        seedQueue();

        assertEquals("feat/a", selectedTitle());
        type(KeyCode.J);
        assertEquals("feat/b", selectedTitle());
        type(KeyCode.J);
        assertEquals("feat/c", selectedTitle());
        type(KeyCode.J);
        assertEquals("feat/c", selectedTitle(), "j must clamp at the last item, never wrap round");
        type(KeyCode.K);
        assertEquals("feat/b", selectedTitle());
        type(KeyCode.K);
        type(KeyCode.K);
        assertEquals("feat/a", selectedTitle(), "k must clamp at the first item");
    }

    @Test
    void qCollapsesAndReExpandsTheQueueRail() {
        seedQueue();
        double expanded = railWidth();
        assertTrue(expanded > ReviewQueueRail.COLLAPSED_WIDTH, "expected an expanded rail, got " + expanded);

        type(KeyCode.Q);
        assertEquals(ReviewQueueRail.COLLAPSED_WIDTH, settledRailWidth());

        type(KeyCode.Q);
        assertEquals(expanded, settledRailWidth());
    }

    /** Focus mode is a toggle, not a one-way collapse -- {@code f} twice must return the rails. */
    @Test
    void fTogglesFocusMode() {
        seedQueue();
        double expanded = railWidth();

        type(KeyCode.F);
        assertEquals(ReviewQueueRail.COLLAPSED_WIDTH, settledRailWidth());

        type(KeyCode.F);
        assertEquals(expanded, settledRailWidth());
    }

    @Test
    void oOpensTheBoundSessionAndDoesNothingWithoutOne() {
        ManagedSessionId session = ManagedSessionId.newId();
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        interact(() -> view.setItems(new QueueAssembly(List.of(
                item(registry, "feat/a", Optional.empty()),
                item(registry, "feat/b", Optional.of(session))), true, true), List.of("repo")));

        type(KeyCode.O);
        assertTrue(host.openedSessions.isEmpty(), "an item with no session must not open one");

        type(KeyCode.J);
        type(KeyCode.O);
        assertEquals(List.of(session), host.openedSessions);
    }

    /**
     * The handoff's hard rule: no {@code Label} + mouse handler. Every queue
     * row, and the rail's own collapse control, has to be reachable by
     * keyboard -- the prototype having zero focusable controls was a defect,
     * not a style.
     */
    @Test
    void everyQueueRowIsFocusTraversable() {
        seedQueue();

        List<Node> rows = new ArrayList<>(lookup(".review-queue-item").queryAll());
        assertEquals(3, rows.size());
        for (Node row : rows) {
            assertTrue(row instanceof Button, "queue rows must be real Buttons, got " + row.getClass());
            assertTrue(row.isFocusTraversable(), "queue row is not focus-traversable: " + row);
        }
        assertTrue(lookup(".panel-header").query().isFocusTraversable(),
                "the rail's collapse control is not focus-traversable");
    }

    /**
     * The count is derived, never stored: an item whose reviewer has never
     * run shows no badge at all, rather than a confident zero that would
     * read as "reviewed, nothing found".
     */
    @Test
    void onlyItemsWithFindingsCarryACountBadge() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewItem a = item(registry, "feat/a", Optional.empty());
        ReviewItem b = item(registry, "feat/b", Optional.empty());
        host.store.upsert(finding(b.scope().id(), "f1"));
        host.store.upsert(finding(b.scope().id(), "f2"));
        interact(() -> view.setItems(new QueueAssembly(List.of(a, b), true, true), List.of("repo")));

        List<String> badges = lookup(".review-queue-count").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
        assertEquals(List.of("2"), badges,
                "only the item whose reviewer has run carries a count");
    }

    static app.drydock.review.ReviewAnnotation finding(String scopeId, String id) {
        return finding(scopeId, id, app.drydock.review.Severity.QUESTION);
    }

    static app.drydock.review.ReviewAnnotation finding(String scopeId, String id,
                                                       app.drydock.review.Severity severity) {
        return new app.drydock.review.ReviewAnnotation(scopeId, id,
                Optional.of("file:src/Main.java"), "src/Main.java", "n1", "n1", severity,
                app.drydock.review.Confidence.HIGH, Optional.of("Title " + id), "Claude",
                java.time.Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new app.drydock.review.ReviewAnnotation.Message("Claude",
                        java.time.Instant.EPOCH, "body of " + id)),
                Optional.empty(), app.drydock.review.AnnotationStatus.OPEN,
                Optional.empty(), false);
    }

    @Test
    void anEmptyQueueShowsTheZeroStateInsteadOfCrashing() {
        // A complete scan with repositories configured is the case that
        // already existed before the four-way empty state split: an empty
        // queue still reads as "nothing to review", not as "no repositories".
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), List.of("repo")));

        assertTrue(lookup(".review-queue-item").queryAll().isEmpty());
        assertFalse(lookup(".review-placeholder-title").queryAll().isEmpty());
        assertEquals("Nothing to review", ((Label) lookup(".review-placeholder-title").query()).getText());
        // Keys against an empty queue must be inert, not throw.
        type(KeyCode.J);
        type(KeyCode.K);
        type(KeyCode.O);
    }

    @Test
    void aPullRequestWithNoCheckoutShowsTheCheckoutGate() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, Path.of("/repo"), Optional.empty(), "main", "feat/gateway",
                Optional.of(new ReviewScope.PullRequestRef(412, Optional.empty())), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.REQUESTED, "PR #412 feat/gateway",
                        "drydock · not checked out")), true, true), List.of("repo")));

        assertEquals("PR #412 has no session yet",
                ((Label) lookup(".review-placeholder-title").query()).getText());

        // The gate shows the commands drydock ACTUALLY runs. `gh pr checkout`
        // has no --worktree flag (checked against gh 2.96), and it always
        // operates on the working tree it is run in -- so the sequence is a
        // detached worktree first, then gh inside it. Printing the handoff's
        // command here would tell the reader to do something that fails.
        String commands = ((Label) lookup(".review-placeholder-mono").query()).getText();
        assertTrue(commands.contains("git worktree add --detach"), commands);
        assertTrue(commands.contains("gh pr checkout 412 --branch pr-412"), commands);
        assertFalse(commands.contains("--worktree\n") || commands.endsWith("--worktree"),
                "must not advertise a flag gh does not have: " + commands);
    }

    @Test
    void theGatePreviewsTheAgentThatWillActuallyRun() {
        // The launch line used to be a hard-coded "claude --cwd <worktree>",
        // which lied to every repository whose agent is not Claude -- and the
        // launch itself pinned AgentKind.CLAUDE to match. Both now come from
        // the repository's own agent, so the gate prints what the host says.
        host.launchCommand = "codex --cd /wt/pr-412";
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, Path.of("/repo"), Optional.empty(), "main", "feat/gateway",
                Optional.of(new ReviewScope.PullRequestRef(412, Optional.empty())), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.REQUESTED, "PR #412 feat/gateway",
                        "drydock · not checked out")), true, true), List.of("repo")));

        String commands = ((Label) lookup(".review-placeholder-mono").query()).getText();
        assertTrue(commands.contains("codex --cd /wt/pr-412"), commands);
        assertFalse(commands.contains("claude"), "must not name an agent it will not run: " + commands);
    }

    @Test
    void typingNarrowsTheRailAndDropsAnEmptiedGroupHeading() {
        seedMixedQueue();
        assertEquals(List.of("feat/a", "agent/issue-919", "agent/issue-920"), renderedTitles());
        assertEquals(List.of("MINE", "AGENTS"), renderedGroups());

        typeQuery("919");

        assertEquals(List.of("agent/issue-919"), renderedTitles());
        assertEquals(List.of("AGENTS"), renderedGroups(),
                "a group whose every row was filtered out must not keep its heading");
    }

    @Test
    void theFooterCountsWhatIsShownAgainstWhatExists() {
        seedMixedQueue();
        assertEquals("3 items", footerText());

        typeQuery("919");
        assertEquals("1 of 3 items", footerText());

        typeQuery("zzz");
        assertEquals("0 of 3 items", footerText());

        typeQuery("");
        assertEquals("3 items", footerText());
    }

    /**
     * An empty rail reads as a broken queue. The queue is fine; the query is
     * too narrow, and the rail has to say so.
     */
    @Test
    void noMatchesExplainsItselfInsteadOfShowingAnEmptyRail() {
        seedMixedQueue();
        typeQuery("zzz");

        assertTrue(renderedTitles().isEmpty());
        assertEquals("No queue item matches \"zzz\"",
                ((Label) lookup(".review-queue-no-match").query()).getText());
    }

    /**
     * A collapse can come from a window resize, so it cannot silently hide
     * rows: the 44px rail has no field and no footer to explain a gap, so it
     * shows everything and re-applies the query on the way back out.
     */
    @Test
    void collapsingSuspendsTheQueryAndExpandingRestoresIt() {
        seedMixedQueue();
        typeQuery("919");
        assertEquals(1, renderedTitles().size());

        type(KeyCode.Q);
        settledRailWidth();
        // Count rows, not titles: a collapsed row is an icon column with no
        // title label, so renderedTitles() is empty by construction there.
        assertEquals(3, renderedRows(), "a collapsed rail must render every item");
        assertFalse(lookup(".review-queue-filter").query().isVisible());

        type(KeyCode.Q);
        settledRailWidth();
        assertEquals(1, renderedTitles().size(), "the query returns with the rail");
        assertTrue(lookup(".review-queue-filter").query().isVisible());
    }

    /**
     * The centre panel never moves on a keystroke -- a selection is a real
     * git diff -- so a query that hides the selected row leaves both the
     * selection and the rendered diff alone.
     */
    @Test
    void aQueryThatHidesTheSelectionLeavesTheSelectionAlone() {
        seedMixedQueue();
        type(KeyCode.J);
        assertEquals("agent/issue-919", selectedTitle());
        String selected = view.diagSelectedScopeId().orElseThrow();

        typeQuery("feat");

        assertEquals(List.of("feat/a"), renderedTitles());
        assertNull(selectedTitle(), "the selected row is filtered out, so no rendered row is selected");
        assertEquals(Optional.of(selected), view.diagSelectedScopeId(),
                "the selection itself, and the diff it drives, must survive the filter");
    }

    @Test
    void jAndKWalkTheVisibleListWhenTheSelectionIsHidden() {
        seedMixedQueue();
        type(KeyCode.J);
        assertEquals("agent/issue-919", selectedTitle());

        // "feat" hides the selection. "agent" would NOT -- it matches both
        // AGENTS rows, so the selection would still be visible and the test
        // would exercise nothing.
        typeQuery("feat");
        type(KeyCode.J);

        assertEquals("feat/a", selectedTitle(),
                "j must enter the visible list, not step from the hidden row");

        typeQuery("920");
        type(KeyCode.K);

        assertEquals("agent/issue-920", selectedTitle(),
                "k must enter the visible list from its own end");
    }

    /**
     * A targeted navigation (⌘4, the sidebar's badge) must never land on a
     * row the user cannot see. A reassembly restoring its own selection must
     * never clear what the user typed.
     */
    @Test
    void revealAndSelectClearsTheQueryButAPlainSelectDoesNot() {
        seedMixedQueue();
        typeQuery("feat");
        String hidden = view.diagItems().get(1).scope().id();

        interact(() -> view.selectScope(hidden));

        assertEquals("", queryText(), "a targeted navigation clears the query");
        assertEquals("agent/issue-919", selectedTitle());

        typeQuery("feat");
        interact(() -> view.setItems(new QueueAssembly(view.diagItems(), true, true), List.of("repo")));

        assertEquals("feat", queryText(), "a reassembly must leave the query alone");
    }

    /**
     * A reassembly that drops the selected item must fall back to a row the
     * query still shows, not to {@code items.get(0)} -- an over-eager
     * fallback can land the centre panel on a completely unrelated item
     * while the rail still reads "No queue item matches ...".
     */
    @Test
    void aReassemblyThatDropsTheSelectionFallsBackToTheFirstVisibleItem() {
        seedMixedQueue();
        typeQuery("issue-9");
        assertEquals(List.of("agent/issue-919", "agent/issue-920"), renderedTitles());
        type(KeyCode.J);
        assertEquals("agent/issue-919", selectedTitle());

        // Reassembly: agent/issue-919's worktree is gone. feat/a is still
        // first in list order, but the query hides it -- the fallback must
        // not select a row the reviewer cannot even see.
        List<ReviewItem> withoutTheSelection = view.diagItems().stream()
                .filter(it -> !it.title().equals("agent/issue-919"))
                .toList();
        interact(() -> view.setItems(new QueueAssembly(withoutTheSelection, true, true), List.of("repo")));

        assertEquals("agent/issue-920", selectedTitle(),
                "fallback must be the first VISIBLE item, not items.get(0)");
    }

    /**
     * {@code revealAndSelect} exists so a targeted navigation never lands the
     * reviewer nowhere -- but a stale id (a worktree removed since the
     * navigation was queued) must leave the query untouched too, not just
     * the selection. Clearing the query while still selecting nothing is
     * strictly worse than doing neither.
     */
    @Test
    void revealAndSelectLeavesTheQueryAloneWhenTheTargetIsGone() {
        seedMixedQueue();
        typeQuery("feat");
        assertEquals("feat/a", selectedTitle());

        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope goneScope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/gone")),
                "master", "gone", Optional.empty(), Optional.empty()));

        interact(() -> view.selectScope(goneScope.id()));

        assertEquals("feat", queryText(), "a stale id must not clear the query it cannot honour");
        assertEquals("feat/a", selectedTitle(), "the selection must be unchanged");
    }

    @Test
    void slashFocusesTheFilterAndTypingIntoItDoesNotFireTheKeyTable() {
        seedMixedQueue();
        Optional<String> before = view.diagSelectedScopeId();

        type(KeyCode.SLASH);

        Node field = lookup(".review-queue-filter").query();
        assertTrue(field.isFocused(), "/ must focus the quick-search field");
        assertEquals("", queryText(), "/ must not type itself into the field it just focused");

        // j into the field is a j, not a selection move: Review's key table
        // returns early while a text input has focus. "j" matches nothing, so
        // no row renders -- read the selection through diagSelectedScopeId,
        // which sees the full list; selectedTitle() only sees rendered rows.
        press(KeyCode.J).release(KeyCode.J);
        assertEquals("j", queryText(), "j must land in the field as a character");
        assertEquals(before, view.diagSelectedScopeId(),
                "typing must never move the centre panel");
    }

    @Test
    void enterInTheFieldSelectsTheFirstMatch() {
        seedMixedQueue();
        assertEquals("feat/a", selectedTitle());

        typeQuery("agent");
        interact(() -> lookup(".review-queue-filter").query().requestFocus());
        press(KeyCode.ENTER).release(KeyCode.ENTER);

        assertEquals("agent/issue-919", selectedTitle());
    }

    @Test
    void escInTheFieldClearsTheQueryAndRestoresEveryRow() {
        seedMixedQueue();
        type(KeyCode.SLASH);
        typeQuery("919");
        assertEquals(1, renderedTitles().size());

        interact(() -> lookup(".review-queue-filter").query().requestFocus());
        press(KeyCode.ESCAPE).release(KeyCode.ESCAPE);

        assertEquals("", queryText());
        assertEquals(3, renderedTitles().size());
        assertFalse(lookup(".review-queue-filter").query().isFocused(),
                "Esc returns focus to the rail so the key table works again");
    }

    /**
     * The field does not exist while the rail is collapsed, so / must be
     * inert there -- and ⌘F must keep routing to the sidebar rather than
     * focusing something invisible.
     */
    @Test
    void withTheRailCollapsedSlashIsInertAndTheFilterIsUnavailable() {
        seedMixedQueue();
        type(KeyCode.Q);
        settledRailWidth();

        type(KeyCode.SLASH);

        assertFalse(lookup(".review-queue-filter").query().isFocused());
        assertFalse(view.queueFilterAvailable(),
                "⌘F must fall back to the sidebar while the rail is collapsed");
    }

    // ---- fixtures -----------------------------------------------------------

    private void seedQueue() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        interact(() -> view.setItems(new QueueAssembly(List.of(
                item(registry, "feat/a", Optional.empty()),
                item(registry, "feat/b", Optional.empty()),
                item(registry, "feat/c", Optional.empty())), true, true), List.of("repo")));
    }

    private static ReviewItem item(ReviewScopeRegistry registry, String head,
                                   Optional<ManagedSessionId> session) {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/" + head)),
                "master", head, Optional.empty(), session));
        return new ReviewItem(scope, ReviewItem.Group.MINE, head, "drydock · vs master");
    }

    /** Three items across two groups, so a query can empty a whole group. */
    private void seedMixedQueue() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        interact(() -> view.setItems(new QueueAssembly(List.of(
                item(registry, "feat/a", Optional.empty()),
                agentItem(registry, "agent/issue-919"),
                agentItem(registry, "agent/issue-920")), true, true), List.of("repo")));
    }

    private static ReviewItem agentItem(ReviewScopeRegistry registry, String head) {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/" + head)),
                "master", head, Optional.empty(), Optional.empty()));
        return new ReviewItem(scope, ReviewItem.Group.AGENTS, head, "drydock · vs master");
    }

    /** Replaces the filter field's text on the FX thread, as typing would. */
    private void typeQuery(String query) {
        interact(() -> ((TextField) lookup(".review-queue-filter").query())
                .setText(query));
    }

    private List<String> renderedTitles() {
        return lookup(".review-queue-item").queryAll().stream()
                .map(node -> (Parent) ((Button) node).getGraphic())
                .flatMap(graphic -> graphic.lookupAll(".review-queue-title").stream().findFirst().stream())
                .map(label -> ((Label) label).getText())
                .toList();
    }

    /** Rows regardless of collapse: a collapsed row renders an icon, not a title. */
    private int renderedRows() {
        return lookup(".review-queue-item").queryAll().size();
    }

    private List<String> renderedGroups() {
        return lookup(".review-queue-group").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private String footerText() {
        return ((Label) lookup(".review-queue-rail .review-rail-footer").query()).getText();
    }

    private String queryText() {
        return ((TextField) lookup(".review-queue-filter").query()).getText();
    }

    private void type(KeyCode key) {
        interact(view::requestFocus);
        press(key).release(key);
    }

    private double railWidth() {
        return ((Region) lookup(".review-queue-rail").query()).getWidth();
    }

    /**
     * The rail's collapse is a 160ms width {@link javafx.animation.Timeline},
     * so its width has to be read after the animation, not after the key
     * press. Polls rather than sleeping a fixed interval: a fixed wait is
     * either flaky or slow, and this settles as soon as the width stops
     * moving.
     */
    private double settledRailWidth() {
        double previous = Double.NaN;
        for (int i = 0; i < 60; i++) {
            sleep(20);
            double current = readRailWidth();
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    private double readRailWidth() {
        double[] width = new double[1];
        interact(() -> width[0] = railWidth());
        return width[0];
    }

    /** The title of the row carrying the {@code :selected} pseudo-class. */
    private String selectedTitle() {
        return lookup(".review-queue-item").queryAll().stream()
                .filter(node -> node.getPseudoClassStates().stream()
                        .anyMatch(state -> state.getPseudoClassName().equals("selected")))
                .findFirst()
                .map(node -> (Parent) ((Button) node).getGraphic())
                .flatMap(graphic -> graphic.lookupAll(".review-queue-title").stream().findFirst())
                .map(label -> ((Label) label).getText())
                .orElse(null);
    }

    // ---- submit -------------------------------------------------------------

    /**
     * The whole reason {@code submitReview()} builds the {@code DiffIndex}
     * from {@link ReviewDiffColumn#displayedDiff()} rather than from {@code
     * diagRows()}: a run of unchanged lines longer than {@code
     * ReviewDiffRows.COLLAPSE_THRESHOLD} is folded into a single {@code
     * CollapsedRun} row and disappears from the rendered rows entirely, but
     * every one of those lines is still a perfectly valid GitHub anchor.
     */
    @Test
    void submitBuildsTheDiffIndexFromTheRealDiffNotTheCollapsedRows() throws Exception {
        Path repo = repoWithALongUnchangedRun();
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "HEAD", "HEAD",
                Optional.empty(), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.MINE, "working", "drydock · vs HEAD")),
                true, true), List.of("repo")));

        awaitDiffLoaded();

        // field10 sits inside the collapsed run above the change at
        // field20 (context either side of it) -- rendered as a folded
        // "N unchanged" row, never as its own Line row.
        assertTrue(lookup(".review-collapsed-run").tryQuery().isPresent(),
                "the fixture must actually produce a collapsed run, or this test proves nothing");

        // Settle the one intent this single-file diff produces, then submit.
        type(KeyCode.A);
        type(KeyCode.ENTER);

        assertEquals(List.of(scope.id()), host.submittedScopes);
        assertEquals(List.of(ReviewVerdict.Decision.APPROVED), host.submittedDecisions.get(scope.id()));

        SubmitPlan.DiffIndex index = host.submittedIndexes.get(scope.id());
        assertTrue(index.positionOfKey().containsKey("Big.java n10"),
                "a line hidden behind a collapsed run must still be in the index built from the diff");
        assertTrue(index.hunkOfKey().containsKey("Big.java n10"));
        assertTrue(index.positionOfKey().containsKey("Big.java n20"),
                "the changed line itself must be indexed too");
    }

    /**
     * A scope with no PR is the merge-and-finish path (spec: Submit on an
     * agent's worktree hands it to Finish). This view has no visibility into
     * that fork -- it lives in {@code MainWorkspace.ReviewHost} -- but it
     * must still always reach {@code host.submit(...)}, which is what the
     * fork switches on.
     */
    @Test
    void submitReachesTheHostForANonPrScope() throws Exception {
        Path repo = repoWithALongUnchangedRun();
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "HEAD", "HEAD",
                Optional.empty(), Optional.empty()));
        assertTrue(scope.pr().isEmpty(), "this scope must not be a PR");
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.MINE, "working", "drydock · vs HEAD")),
                true, true), List.of("repo")));

        awaitDiffLoaded();
        type(KeyCode.A);
        type(KeyCode.ENTER);

        assertEquals(List.of(scope.id()), host.submittedScopes,
                "a non-PR scope must still reach host.submit -- MainWorkspace forks on scope.pr() from there");
    }

    /**
     * A PR scope whose diff FAILED to load (a real {@code DiffService} call
     * against a directory that is not a git repository) has nothing to
     * anchor a comment to, so Submit must keep refusing -- but visibly: the
     * bug this pins is the guard doing nothing at all with no explanation,
     * silently and for the rest of the session (see {@code
     * ReviewDestinationView#submitReview}).
     */
    @Test
    void submitOnAFailedDiffShowsAVisibleRefusalInsteadOfDoingNothing() throws Exception {
        Path notARepo = Files.createTempDirectory("drydock-review-view-not-a-repo");
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, notARepo, Optional.of(notARepo), "HEAD", "HEAD",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.MINE, "working", "drydock · vs HEAD")),
                true, true), List.of("repo")));

        // Wait for the diff to actually FAIL (not merely still be loading)
        // before pressing Submit, so this proves the FAILED branch of the
        // guard specifically, not the ordinary "still Diffing" window.
        awaitCondition(() -> lookup(".review-diff-message").queryAllAs(Label.class).stream()
                        .anyMatch(label -> label.getText().startsWith("Could not diff")),
                "the diff never failed; fixture must point at a non-repo directory");
        type(KeyCode.ENTER);

        Label refusal = awaitCondition(() -> {
            java.util.Optional<Node> found = lookup(".review-verdict-submit-refusal").tryQuery();
            return found.filter(Node::isVisible).map(Label.class::cast);
        }, "Submit on a failed diff never showed a visible refusal");
        assertTrue(refusal.getText().toLowerCase(java.util.Locale.ROOT).contains("diff"),
                "the message must say why: " + refusal.getText());
        assertTrue(host.submittedScopes.isEmpty(),
                "a PR scope with no diff to anchor comments to must not reach host.submit");
    }

    /**
     * The other half of the same fix: a non-PR scope posts no comments
     * either way ({@code Host#submit} routes it straight to the Finish
     * hand-off), so a FAILED diff must not leave it stuck refusing forever
     * the way {@link #submitOnAFailedDiffShowsAVisibleRefusalInsteadOfDoingNothing}
     * proves a PR scope correctly does.
     */
    @Test
    void submitOnAFailedDiffStillReachesTheHostForANonPrScope() throws Exception {
        Path notARepo = Files.createTempDirectory("drydock-review-view-not-a-repo-2");
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, notARepo, Optional.of(notARepo), "HEAD", "HEAD",
                Optional.empty(), Optional.empty()));
        assertTrue(scope.pr().isEmpty(), "this scope must not be a PR");
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.MINE, "working", "drydock · vs HEAD")),
                true, true), List.of("repo")));

        type(KeyCode.ENTER);

        awaitCondition(() -> !host.submittedScopes.isEmpty(),
                "a non-PR scope must still reach host.submit even after its diff failed to load");
        assertEquals(List.of(scope.id()), host.submittedScopes);
    }

    private <T> T awaitCondition(java.util.function.Supplier<java.util.Optional<T>> ready, String failureMessage) {
        for (int i = 0; i < 200; i++) {
            WaitForAsyncUtils.waitForFxEvents();
            java.util.Optional<T> result = ready.get();
            if (result.isPresent()) {
                return result.get();
            }
            sleep(25);
        }
        throw new AssertionError(failureMessage);
    }

    private void awaitCondition(java.util.function.BooleanSupplier ready, String failureMessage) {
        for (int i = 0; i < 200; i++) {
            WaitForAsyncUtils.waitForFxEvents();
            if (ready.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError(failureMessage);
    }

    /**
     * Polls until the real diff has landed in the column: a single {@code
     * waitForFxEvents()} races the async {@code DiffService} call, which
     * runs on its own executor and hops back via {@code Platform.runLater}.
     */
    private void awaitDiffLoaded() {
        for (int i = 0; i < 200; i++) {
            if (!view.diagAnchors(1).isEmpty()) {
                WaitForAsyncUtils.waitForFxEvents();
                return;
            }
            WaitForAsyncUtils.waitForFxEvents();
            sleep(25);
        }
        throw new AssertionError("the diff never arrived");
    }

    /** A change with more than {@code ReviewDiffRows.COLLAPSE_THRESHOLD} unchanged lines around it. */
    private static Path repoWithALongUnchangedRun() throws IOException, InterruptedException {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-review-view-run"));
        StringBuilder original = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            original.append("int field").append(i).append(" = ").append(i).append(";\n");
        }
        Files.writeString(repo.resolve("Big.java"), original.toString());
        runGit(repo, "add", "Big.java");
        runGit(repo, "commit", "-m", "big");
        Files.writeString(repo.resolve("Big.java"),
                original.toString().replace("int field20 = 20;", "int field20 = 999;"));
        return repo;
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
