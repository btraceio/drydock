package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private OpenSessionTab newTab() {
        OpenSessionTab[] holder = new OpenSessionTab[1];
        interact(() -> holder[0] = new OpenSessionTab(ManagedSessionId.newId(), "test-session", "Claude",
                AgentKind.CLAUDE, false, Optional.empty(), stage, fakeRuntime(), fakeHost()));
        return holder[0];
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

    private static TerminalHostView fakeHost() {
        return new TerminalHostView() {
            @Override
            public void setFrame(double x, double y, double width, double height) {
            }

            @Override
            public void setVisible(boolean visible) {
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
        };
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
        public Optional<ReviewVerdict> verdict(ReviewScope scope, ReviewIntent intent) {
            return Optional.empty();
        }

        @Override
        public void setVerdict(ReviewScope scope, ReviewIntent intent, Optional<ReviewVerdict.Decision> decision) {
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
