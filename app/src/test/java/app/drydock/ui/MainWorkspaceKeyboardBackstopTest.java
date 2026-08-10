package app.drydock.ui;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct, non-TestFX coverage of {@link MainWorkspace}'s static {@code
 * reviewKeyboardBackstop(boolean, Node, KeyEvent, java.util.function.Predicate)}
 * -- the routing DECISION behind the scene-level backstop that replays a
 * Review shortcut when focus has drifted off {@code ReviewDestinationView}'s
 * subtree (see that method's Javadoc). The production wiring (the real
 * scene filter in {@code DrydockApplication}, a real {@code
 * ReviewDestinationView}) needs a live app to exercise -- the diag-driver
 * transcript in the fix report is the evidence for that -- but the decision
 * itself is pure once the review root and the replay function are taken as
 * parameters, so it does not need one.
 *
 * <p>This exists because every TestFX test of Review's keyboard table gives
 * the view focus directly ({@code interact(view::requestFocus)}), which
 * means the backstop is never actually on the path in any of those tests --
 * they would all pass unchanged with the backstop deleted. These tests are
 * what is left to catch a regression in the backstop's own routing logic,
 * including the two it shipped with: replaying {@code ENTER} off-subtree
 * (which hijacked the sidebar's own Enter-to-open-row binding into Submit)
 * and swallowing {@code Shift+/} ("?", the global shortcuts overlay) before
 * its owner ever saw it.</p>
 */
class MainWorkspaceKeyboardBackstopTest {

    private final Pane reviewRoot = new Pane();
    private final Region reviewChild = new Region();
    private final Region sidebarish = new Region();

    MainWorkspaceKeyboardBackstopTest() {
        reviewRoot.getChildren().add(reviewChild);
    }

    @Test
    void targetInsideReviewsSubtreeIsNeverReplayed() {
        KeyEvent event = keyAt(KeyCode.OPEN_BRACKET, reviewChild, false);
        boolean[] replayCalled = {false};

        boolean handled = MainWorkspace.reviewKeyboardBackstop(true, reviewRoot, event, e -> {
            replayCalled[0] = true;
            return true;
        });

        assertFalse(handled);
        assertFalse(replayCalled[0],
                "a target already inside Review's own subtree must be left to its node-level filter, "
                        + "or the same keystroke would be acted on twice");
    }

    @Test
    void anAllowListedKeyOffSubtreeIsReplayedAndItsResultThreadedThrough() {
        KeyEvent event = keyAt(KeyCode.CLOSE_BRACKET, sidebarish, false);

        assertTrue(MainWorkspace.reviewKeyboardBackstop(true, reviewRoot, event, e -> true),
                "']' is Review's own shortcut table and must be replayed off-subtree");
        assertFalse(MainWorkspace.reviewKeyboardBackstop(true, reviewRoot, event, e -> false),
                "the backstop must return exactly what the replay decided, not override it");
    }

    /**
     * The regression this fix's undo semantics depend on: {@code u} has to
     * reach {@code ReviewDestinationView.handleShortcut} off-subtree too,
     * or undoing a verdict recorded while focus had drifted away would be
     * silently dead the same way every other shortcut used to be.
     */
    @Test
    void uIsReplayedOffSubtree() {
        KeyEvent event = keyAt(KeyCode.U, sidebarish, false);
        assertTrue(MainWorkspace.reviewKeyboardBackstop(true, reviewRoot, event, e -> true));
    }

    /**
     * {@code ENTER} is deliberately NOT in the allow-list: replaying it
     * off-subtree used to fire Submit over {@code RepositorySidebar}'s own
     * Enter-to-open-row binding whenever a reader clicked a sidebar row --
     * exactly the focus drift this backstop exists to serve -- and pressed
     * Enter to open it. The replay function is never even called: this is
     * an allow-list, not a deny-list that happens to reject Enter today.
     */
    @Test
    void enterOffSubtreeIsNeverReplayed() {
        KeyEvent event = keyAt(KeyCode.ENTER, sidebarish, false);
        boolean[] replayCalled = {false};

        boolean handled = MainWorkspace.reviewKeyboardBackstop(true, reviewRoot, event, e -> {
            replayCalled[0] = true;
            return true;
        });

        assertFalse(handled);
        assertFalse(replayCalled[0], "ENTER must never reach the replay -- it is not allow-listed");
    }

    /**
     * {@code Shift+/} ("?") IS allow-listed under the plain {@code SLASH}
     * key code (modifiers are not part of the allow-list check), so this
     * pins that the backstop still returns exactly what the replay decides
     * for it -- which is where {@code ReviewDestinationView.handleShortcut}'s
     * own {@code isShiftDown()} check (added alongside this fix) actually
     * does the excluding, by yielding false. The stub below mirrors that
     * real behaviour rather than re-implementing the production check, so
     * this proves the backstop does not independently swallow or invert
     * "?" before that check gets a say.
     */
    @Test
    void shiftSlashIsReplayedButTheOverallResultStillFollowsWhatHandleShortcutWouldDecide() {
        KeyEvent shiftSlash = keyAt(KeyCode.SLASH, sidebarish, true);
        java.util.function.Predicate<KeyEvent> likeRealHandleShortcut =
                e -> !e.isShiftDown() && e.getCode() == KeyCode.SLASH;

        assertFalse(MainWorkspace.reviewKeyboardBackstop(true, reviewRoot, shiftSlash, likeRealHandleShortcut),
                "'?' must fall through to the global shortcuts overlay, not be consumed here");
    }

    @Test
    void nothingIsReplayedWhenReviewIsNotTheShowingTab() {
        KeyEvent event = keyAt(KeyCode.CLOSE_BRACKET, sidebarish, false);
        boolean[] replayCalled = {false};

        boolean handled = MainWorkspace.reviewKeyboardBackstop(false, reviewRoot, event, e -> {
            replayCalled[0] = true;
            return true;
        });

        assertFalse(handled);
        assertFalse(replayCalled[0]);
    }

    private static KeyEvent keyAt(KeyCode code, Node target, boolean shiftDown) {
        KeyEvent base = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                shiftDown, false, false, false);
        return (KeyEvent) base.copyFor(base.getSource(), target);
    }
}
