package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.SubmitPlan;
import app.drydock.terminal.api.Shortcut;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.ui.review.SessionReviewView;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8: the session tab's fourth sub-tab. {@link OpenSessionTab} needs a
 * native surface (its {@link TerminalBridge} trio), so this mirrors {@code
 * SessionHeaderLayoutTest}'s headless approach -- a real {@link Stage}/{@link
 * Scene} via {@link ApplicationTest}, with no-op {@link TerminalRuntime}/
 * {@link TerminalHostView} fakes standing in for the native side, which
 * every path exercised here (construction, sub-tab switching, the review
 * button's text) never actually calls into.
 */
class OpenSessionTabReviewSubTabTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 200, 200));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    /** The Claude native surface's host for the tab most recently built by {@link #newTab}. */
    private RecordingHost lastHost;

    /**
     * A focusable node beside the tab, standing in for the sidebar: the
     * refocus tests need somewhere real for focus to go, since a
     * {@code requestFocus} on nothing leaves it where it was. Built on the FX
     * thread, never in a field initializer -- constructing any {@code Control}
     * on the JUnit worker before the toolkit is up poisons that class's static
     * initializer for every later test in the same JVM.
     */
    private Button elsewhere;

    /**
     * Puts {@code tab}'s content in the showing scene. Focus is a scene-level
     * property, so a board that was never attached can never be focused --
     * without this the refocus assertions would be vacuously false.
     */
    private void showInScene(OpenSessionTab tab) {
        elsewhere = new Button("elsewhere");
        StackPane root = (StackPane) stage.getScene().getRoot();
        root.getChildren().setAll(tab.tab.getContent(), elsewhere);
    }

    private static void waitForFxEvents() {
        WaitForAsyncUtils.waitForFxEvents();
    }

    private OpenSessionTab newTab() {
        OpenSessionTab[] holder = new OpenSessionTab[1];
        interact(() -> {
            lastHost = new RecordingHost();
            holder[0] = new OpenSessionTab(ManagedSessionId.newId(), "test-session", "Claude",
                    AgentKind.CLAUDE, false, Optional.empty(), stage, fakeRuntime(), lastHost);
        });
        return holder[0];
    }

    private final ReviewScopeRegistry scopeRegistry = new ReviewScopeRegistry();

    /** Any real scope: these tests care that the board HAS one, never which. */
    private ReviewScope someLocalScope() {
        return scopeRegistry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/feature")),
                "main", "feature/x", Optional.empty(), Optional.empty()));
    }

    private SessionReviewView newReviewView() {
        SessionReviewView[] holder = new SessionReviewView[1];
        interact(() -> holder[0] = new SessionReviewView(new StubHost(), diffService, null));
        return holder[0];
    }

    @Test
    void theReviewViewIsNotBuiltUntilTheSubTabIsVisited() {
        AtomicInteger built = new AtomicInteger();
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(() -> {
            built.incrementAndGet();
            return newReviewView();
        }));

        assertEquals(0, built.get(), "a diff column per open session, eagerly, is a cost nobody asked for");

        interact(() -> {
            tab.showSubTab(OpenSessionTab.SubTab.REVIEW);
            tab.showSubTab(OpenSessionTab.SubTab.CLAUDE);
            tab.showSubTab(OpenSessionTab.SubTab.REVIEW);
        });

        assertEquals(1, built.get(), "built once, then reused");
    }

    @Test
    void theReviewShortcutSelectsTheReviewSubTab() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(this::newReviewView));

        interact(() -> tab.diagRunShortcut(Shortcut.REVIEW_SUB_TAB));

        assertEquals(OpenSessionTab.SubTab.REVIEW, tab.activeSubTab());
    }

    @Test
    void theBadgeIsAbsentRatherThanZeroWhenNoReviewerHasRun() {
        OpenSessionTab tab = newTab();

        interact(() -> tab.setReviewBadge(Optional.empty()));

        assertEquals("Review", tab.diagReviewButtonText());
    }

    @Test
    void theBadgeShowsOpenFindings() {
        OpenSessionTab tab = newTab();

        interact(() -> tab.setReviewBadge(Optional.of(3)));

        assertEquals("Review ◨3", tab.diagReviewButtonText());
    }

    /**
     * Task 11: the sub-tab button and {@code ⌘4} reach {@code showSubTab}
     * directly, never {@code showReviewSubTab}. Hanging scope resolution off
     * the latter alone is exactly what left the board saying "Resolving this
     * session's review scopes…" forever, so every route has to ask. A route
     * that names no chip asks with an empty choice -- the host falls back to
     * the persisted one.
     */
    @Test
    void everyRouteIntoTheSubTabAsksForScopes() {
        List<Optional<SessionReviewScopes.Choice>> asked = new ArrayList<>();
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.setReviewViewFactory(this::newReviewView);
            tab.setOnReviewShown(asked::add);
        });

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));
        assertEquals(List.of(Optional.empty()), asked,
                "the sub-tab button and ⌘4 say where, not which chip");

        interact(() -> {
            tab.showSubTab(OpenSessionTab.SubTab.CLAUDE);
            tab.showReviewSubTab(SessionReviewScopes.Choice.PULL_REQUEST);
        });
        assertEquals(List.of(Optional.empty(), Optional.of(SessionReviewScopes.Choice.PULL_REQUEST)), asked,
                "a gesture that names a chip must ask for that chip -- exactly once");
    }

    /**
     * Re-resolving spawns git AND a gh network call, so the two "already
     * here" cases are told apart: asking for a chip the board is not showing
     * re-resolves, while a plain refocus of the chip it IS showing does not.
     */
    @Test
    void askingForTheChipTheBoardAlreadyShowsDoesNotReResolveIt() {
        List<Optional<SessionReviewScopes.Choice>> asked = new ArrayList<>();
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.setReviewViewFactory(this::newReviewView);
            tab.setOnReviewShown(asked::add);
            tab.showSubTab(OpenSessionTab.SubTab.REVIEW);
        });
        SessionReviewView board = tab.reviewView().orElseThrow();
        ReviewScope pr = scopeRegistry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, Path.of("/repo"), Optional.of(Path.of("/wt/feature")),
                "main", "feature/x", Optional.of(new ReviewScope.PullRequestRef(42, Optional.empty())),
                Optional.empty()));
        interact(() -> board.showScopes(
                new SessionReviewScopes.Scopes(someLocalScope(), Optional.of(pr)),
                SessionReviewScopes.Choice.PULL_REQUEST));
        asked.clear();

        interact(() -> tab.showReviewSubTab(SessionReviewScopes.Choice.PULL_REQUEST));
        assertEquals(List.of(), asked, "it is already showing that chip: this gesture is a refocus");

        interact(() -> tab.showReviewSubTab(SessionReviewScopes.Choice.LOCAL));
        assertEquals(List.of(Optional.of(SessionReviewScopes.Choice.LOCAL)), asked,
                "the other chip is a real request, even from the sub-tab that is already showing");
    }

    /**
     * A transient git or {@code gh} failure must not be terminal. The routes
     * that name no chip (⌘4, the sub-tab button) early-return out of {@code
     * showSubTab} when REVIEW is already active, so without a retry the board
     * would keep reading "Review is not available for this session" until the
     * user switched sub-tabs away and back. The chip-naming gestures always
     * retried; these have to as well.
     */
    @Test
    void repeatingTheGestureRetriesAfterTheBoardCouldNotResolve() {
        List<Optional<SessionReviewScopes.Choice>> asked = new ArrayList<>();
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.setReviewViewFactory(this::newReviewView);
            tab.setOnReviewShown(asked::add);
            tab.showSubTab(OpenSessionTab.SubTab.REVIEW);
        });
        SessionReviewView board = tab.reviewView().orElseThrow();
        interact(() -> board.showUnavailable("git exploded"));
        asked.clear();

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));

        assertEquals(List.of(Optional.empty()), asked,
                "⌘4 on a board that could not resolve is a retry, not a refocus of a dead board");
    }

    /**
     * The other half of the same rule: once the board HAS a scope, repeating
     * the no-chip gesture is a refocus and must not spawn git and gh again.
     */
    @Test
    void repeatingTheGestureOnAResolvedBoardDoesNotReResolve() {
        List<Optional<SessionReviewScopes.Choice>> asked = new ArrayList<>();
        OpenSessionTab tab = newTab();
        interact(() -> {
            tab.setReviewViewFactory(this::newReviewView);
            tab.setOnReviewShown(asked::add);
            tab.showSubTab(OpenSessionTab.SubTab.REVIEW);
        });
        interact(() -> tab.reviewView().orElseThrow().showScopes(
                new SessionReviewScopes.Scopes(someLocalScope(), Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        asked.clear();

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));

        assertEquals(List.of(), asked);
    }

    /**
     * Task 11, deferred from Task 8: {@code showSubTab} early-returns when the
     * asked-for sub-tab is already active, and the refocus it does on the way
     * out used to handle only the two native sub-tabs. So "I clicked the
     * sidebar, now let me work in the board again" -- {@code ⌘4} or the Review
     * button while REVIEW is already showing -- left the board unfocused, and
     * its entire single-letter key table dead, because those shortcuts are an
     * event filter on the view itself.
     */
    @Test
    void showingReviewWhileItIsAlreadyShowingRefocusesTheBoard() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(this::newReviewView));
        interact(() -> showInScene(tab));

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));
        waitForFxEvents();
        SessionReviewView board = tab.reviewView().orElseThrow();
        assertTrue(board.isFocused(), "the first visit focuses the board (Task 8)");

        // Focus wanders off the board the way a sidebar click moves it.
        interact(() -> elsewhere.requestFocus());
        waitForFxEvents();
        assertFalse(board.isFocused(), "the test's own precondition: focus really did leave the board");

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));
        waitForFxEvents();

        assertTrue(board.isFocused(), "asking for the sub-tab that is already showing must reclaim its keyboard");
    }

    /** The same hole in {@link OpenSessionTab#focus()}, which window refocus and re-picking a session reach. */
    @Test
    void refocusingTheTabRefocusesTheBoardItIsShowing() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(this::newReviewView));
        interact(() -> showInScene(tab));

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));
        waitForFxEvents();
        SessionReviewView board = tab.reviewView().orElseThrow();
        interact(() -> elsewhere.requestFocus());
        waitForFxEvents();
        assertFalse(board.isFocused());

        interact(tab::focus);
        waitForFxEvents();

        assertTrue(board.isFocused());
    }

    /**
     * The board must actually replace what is on screen, not merely become
     * the tab's {@link OpenSessionTab#reviewView()} -- deleting {@code
     * content.setCenter(view)} from the {@code REVIEW} branch of {@code
     * showSubTab} leaves every one of the other tests in this class passing,
     * which is exactly why it needs its own pin.
     */
    @Test
    void showingReviewPutsTheBoardInTheTabsContentCenter() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(this::newReviewView));

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));

        SessionReviewView view = tab.reviewView().orElseThrow();
        assertEquals(view, ((BorderPane) tab.tab.getContent()).getCenter(),
                "the built review view must be the tab content's center node, not just a built-and-forgotten view");
    }

    /**
     * The native Claude surface overlays the JavaFX scene (see {@code
     * TerminalBridge}), so showing Review must also tell it to hide -- else
     * it keeps painting over the board underneath it, a visible defect no
     * assertion on {@code content}'s center alone would catch. Deleting
     * {@code bridge.setTerminalSubTabActive(false)} from the {@code REVIEW}
     * branch leaves this the only test in the class that fails.
     */
    @Test
    void showingReviewHidesTheClaudeNativeSurface() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(this::newReviewView));
        // Establishes workspaceWantsVisible=true first: TerminalBridge starts
        // with it false, so the native host's computed visibility would be
        // false regardless of the REVIEW branch under test, and the
        // assertion below would pass even with the fix deleted.
        interact(() -> tab.setVisible(true));
        assertTrue(lastHost.lastVisible, "setup: the Claude surface must be visible before switching away from it");

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));

        assertFalse(lastHost.lastVisible,
                "the Claude native surface must be told to hide, or it paints over the Review board");
    }

    /** A produced member with no coverage otherwise: empty, present, then empty again after teardown. */
    @Test
    void theReviewViewIsEmptyBeforeVisitPresentAfterAndEmptyAfterDisposal() {
        OpenSessionTab tab = newTab();
        interact(() -> tab.setReviewViewFactory(this::newReviewView));

        assertEquals(Optional.empty(), tab.reviewView());

        interact(() -> tab.showSubTab(OpenSessionTab.SubTab.REVIEW));

        assertTrue(tab.reviewView().isPresent());

        interact(tab::disposeNativeResources);

        assertEquals(Optional.empty(), tab.reviewView());
    }

    private static TerminalRuntime fakeRuntime() {
        return new TerminalRuntime() {
            @Override
            public void tick() {
            }

            @Override
            public void setFocus(boolean focused) {
            }

            @Override
            public void updateConfig(Path configFile) {
            }

            @Override
            public TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec) {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public void close() {
            }
        };
    }

    /** A no-op {@link TerminalHostView} that records the last {@link #setVisible} call it saw. */
    private static final class RecordingHost implements TerminalHostView {
        boolean lastVisible;

        @Override
        public void setFrame(double x, double y, double width, double height) {
        }

        @Override
        public void setVisible(boolean visible) {
            this.lastVisible = visible;
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public void setKeyEventListener(KeyEventListener listener) {
        }

        @Override
        public void setScrollEventListener(ScrollEventListener listener) {
        }

        @Override
        public void setMousePosEventListener(MousePosEventListener listener) {
        }

        @Override
        public void setMouseButtonEventListener(MouseButtonEventListener listener) {
        }

        @Override
        public void close() {
        }
    }

    /**
     * A {@link SessionReviewView.Host} whose methods are never exercised by
     * these tests -- Task 8 builds, hosts, disposes and badges the view
     * without wiring it to real scopes (Task 11's job), and {@link
     * SessionReviewView}'s constructor and {@link SessionReviewView#showResolving}
     * call none of these.
     */
    private static final class StubHost implements SessionReviewView.Host {
        @Override
        public Optional<Region> bodyFor(ReviewScope scope) {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> openFindings(ReviewScope scope) {
            return Optional.empty();
        }

        @Override
        public void showShortcuts() {
        }

        @Override
        public boolean openInExplorer(ReviewScope scope, Path file, int line) {
            return false;
        }

        @Override
        public List<ReviewAnnotation> findings(ReviewScope scope) {
            return List.of();
        }

        @Override
        public List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff) {
            return List.of();
        }

        @Override
        public Optional<ReviewVerdict> verdict(ReviewScope scope, String hunkDigest) {
            return Optional.empty();
        }

        @Override
        public void setVerdict(ReviewScope scope, ReviewIntent intent, List<String> hunkDigests,
                               Optional<ReviewVerdict.Decision> decision) {
        }

        @Override
        public String currentBase(ReviewScope scope) {
            return SessionReviewView.UNRESOLVED_BASE;
        }

        @Override
        public BaseMove.Delta baseMove(ReviewScope scope, String recordedBase) {
            return new BaseMove.Delta(true, new TreeSet<>());
        }

        @Override
        public void setResolved(ReviewScope scope, ReviewAnnotation finding, boolean resolved) {
        }

        @Override
        public void postMessage(ReviewScope scope, ReviewAnnotation finding, String body) {
        }

        @Override
        public void addComment(ReviewScope scope, ReviewAnnotation annotation) {
        }

        @Override
        public void setPostToPr(ReviewScope scope, ReviewAnnotation finding, boolean post) {
        }

        @Override
        public void applyPatch(ReviewScope scope, ReviewAnnotation finding) {
        }

        @Override
        public void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity) {
        }

        @Override
        public void askAgentToFix(ReviewScope scope, ReviewIntent intent, List<ReviewAnnotation> findings) {
        }

        @Override
        public void submit(ReviewScope scope, SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions) {
        }

        @Override
        public boolean runReview(ReviewScope scope) {
            return false;
        }
    }
}
