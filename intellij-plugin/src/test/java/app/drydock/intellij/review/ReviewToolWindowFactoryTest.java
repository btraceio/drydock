package app.drydock.intellij.review;

import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure decisions of {@link ReviewToolWindowFactory}: which Changes
 * host to align with, whether one manager event should realign, and the
 * once-guard on the content's disposal. No IDE is booted — every decision
 * is a static function over ids, anchors and event kinds, exactly the
 * seams the live window exercises (the platform glue between them is
 * thin: look up the host, set the anchor, hop to the EDT).
 *
 * <p>Self-event filtering is the one non-obvious rule: Review's own
 * {@code setAnchor} fires SetToolWindowAnchor for its id, and a listener
 * reacting to it would realign to keep the anchor it just set — the
 * loop. Every case below pins that the window's own id is dropped before
 * any other rule runs. The availability scheduling seam pins the one
 * lifecycle rule that is not a pure decision: the availability
 * application must always be deferred, never run in place — a
 * synchronous whenComplete inside ToolWindowFactory.init would hit
 * ToolWindowImpl's setAvailable guard.</p>
 */
class ReviewToolWindowFactoryTest {

    private static final String SELF = "Drydock Review";

    // ---- the host preference -------------------------------------------------

    @Test
    void theCommitWindowIsPreferredOverVersionControl() {
        assertEquals("Commit", ReviewToolWindowFactory.preferredHostId(
                        List.of("Version Control", "Commit", "Project")),
                "both hosts available: the Commit window, the subtab's original host, comes first");
        assertEquals("Version Control", ReviewToolWindowFactory.preferredHostId(
                        List.of("Version Control", "Project")),
                "only Version Control available: the subtab's fallback when the Commit window is disabled");
    }

    @Test
    void noHostMeansNoAlignment() {
        assertNull(ReviewToolWindowFactory.preferredHostId(List.of("Project", "Gradle", "Terminal")),
                "no Changes host available: nothing to align with yet");
        assertNull(ReviewToolWindowFactory.preferredHostId(List.of()),
                "the hosts have not registered yet: the registration listener is what retries");
    }

    // ---- the target anchor ---------------------------------------------------

    @Test
    void theWindowMovesToAHostOnADifferentStripe() {
        assertEquals(ToolWindowAnchor.BOTTOM,
                ReviewToolWindowFactory.targetAnchor(ToolWindowAnchor.LEFT, ToolWindowAnchor.BOTTOM),
                "the Version Control host docked bottom: Review follows to the same stripe");
        assertEquals(ToolWindowAnchor.LEFT,
                ReviewToolWindowFactory.targetAnchor(ToolWindowAnchor.BOTTOM, ToolWindowAnchor.LEFT),
                "the Commit host docked left: Review follows back");
    }

    @Test
    void theWindowStaysWhereItIsWhenItAlreadyMatchesOrNoHostExists() {
        assertNull(ReviewToolWindowFactory.targetAnchor(ToolWindowAnchor.LEFT, ToolWindowAnchor.LEFT),
                "already on the host's stripe: a setAnchor would only fire the self event for nothing");
        assertNull(ReviewToolWindowFactory.targetAnchor(ToolWindowAnchor.LEFT, null),
                "no host to follow: alignment waits for a registration or availability event");
    }

    // ---- the event filter ----------------------------------------------------

    @Test
    void theWindowsOwnEventsNeverRealignIt() {
        for (ToolWindowManagerEventType type : ToolWindowManagerEventType.values()) {
            assertFalse(ReviewToolWindowFactory.reactsToEvent(SELF, SELF, type),
                    "the Review window's own " + type + " is dropped before any other rule runs");
        }
    }

    @Test
    void aHostsMovesAndAvailabilityFlipsRealignIt() {
        for (ToolWindowManagerEventType type : List.of(
                ToolWindowManagerEventType.SetToolWindowAnchor,
                ToolWindowManagerEventType.SetSideToolAndAnchor,
                ToolWindowManagerEventType.SetSideTool,
                ToolWindowManagerEventType.SetLayout,
                ToolWindowManagerEventType.ToolWindowAvailable,
                ToolWindowManagerEventType.ToolWindowUnavailable)) {
            assertTrue(ReviewToolWindowFactory.reactsToEvent(SELF, "Commit", type),
                    "the Commit host's " + type + " moves or re-ranks the alignment target");
            assertTrue(ReviewToolWindowFactory.reactsToEvent(SELF, "Version Control", type),
                    "the Version Control host's " + type + " moves or re-ranks the alignment target");
        }
    }

    @Test
    void otherWindowsAndQuietHostEventsDoNot() {
        for (ToolWindowManagerEventType type : ToolWindowManagerEventType.values()) {
            assertFalse(ReviewToolWindowFactory.reactsToEvent(SELF, "Project", type),
                    "another window's " + type + " is not a host signal");
        }
        assertFalse(ReviewToolWindowFactory.reactsToEvent(SELF, "Commit", ToolWindowManagerEventType.ActivateToolWindow),
                "the host being activated changes no placement");
        assertFalse(ReviewToolWindowFactory.reactsToEvent(SELF, "Commit", ToolWindowManagerEventType.HideToolWindow),
                "the host being hidden changes no placement");
    }

    @Test
    void aWindowlessEventOfARealignKindRealignsItButQuietWindowlessOnesDoNot() {
        for (ToolWindowManagerEventType type : List.of(
                ToolWindowManagerEventType.SetToolWindowAnchor,
                ToolWindowManagerEventType.SetSideToolAndAnchor,
                ToolWindowManagerEventType.SetSideTool,
                ToolWindowManagerEventType.SetLayout,
                ToolWindowManagerEventType.ToolWindowAvailable,
                ToolWindowManagerEventType.ToolWindowUnavailable)) {
            assertTrue(ReviewToolWindowFactory.reactsToEvent(SELF, null, type),
                    "a windowless " + type + " (263 publishes the anchor and side kinds"
                            + " through the windowless overload too): re-correct the placement");
        }
        for (ToolWindowManagerEventType type : Set.of(
                ToolWindowManagerEventType.ActivateToolWindow,
                ToolWindowManagerEventType.ShowToolWindow,
                ToolWindowManagerEventType.HideToolWindow,
                ToolWindowManagerEventType.MovedOrResized)) {
            assertFalse(ReviewToolWindowFactory.reactsToEvent(SELF, null, type),
                    "a windowless " + type + " carries no placement change to follow");
        }
    }

    @Test
    void aSelfCausedWindowlessAnchorEventCannotLoopBecauseAlignmentNoOpsOnceEqual() {
        assertTrue(ReviewToolWindowFactory.reactsToEvent(SELF, null, ToolWindowManagerEventType.SetToolWindowAnchor),
                "Review's own setAnchor can publish a windowless anchor event:"
                        + " the self-id filter cannot drop what named no window");
        assertNull(ReviewToolWindowFactory.targetAnchor(ToolWindowAnchor.LEFT, ToolWindowAnchor.LEFT),
                "the second guard: once Review sits at the host's anchor, the realign"
                        + " finds nothing to move and the loop is broken there");
    }

    // ---- the registration filter ---------------------------------------------

    @Test
    void onlyAChangesHostRegistrationRealigns() {
        assertTrue(ReviewToolWindowFactory.reactsToRegistration("Commit"),
                "the Commit window registered after Review: align now");
        assertTrue(ReviewToolWindowFactory.reactsToRegistration("Version Control"),
                "the Version Control window registered after Review: align now");
        assertFalse(ReviewToolWindowFactory.reactsToRegistration("Project"),
                "any other window's registration changes no host");
        assertFalse(ReviewToolWindowFactory.reactsToRegistration(SELF),
                "the window's own registration event needs no realignment: init aligned already");
    }

    // ---- the availability scheduling -----------------------------------------

    @Test
    void anAlreadyCompleteInitializationStillOnlyDefersTheAvailabilityApplication() {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        // A recording scheduler: captures the application, never runs it,
        // so "executed" stays 0 unless the code bypassed the scheduler.
        ReviewToolWindowFactory.onInitializationComplete(
                CompletableFuture.completedFuture(null), executed::incrementAndGet,
                update -> scheduled.incrementAndGet());

        assertEquals(1, scheduled.get(),
                "whenComplete fired synchronously and still only handed the application"
                        + " to the scheduler");
        assertEquals(0, executed.get(),
                "no setAvailable ran in place — a synchronous callback inside"
                        + " ToolWindowFactory.init is exactly what ToolWindowImpl forbids");
    }

    @Test
    void aLateInitializationCompletionIsDeferredToo() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        AtomicInteger scheduled = new AtomicInteger();

        ReviewToolWindowFactory.onInitializationComplete(pending, () -> { },
                update -> scheduled.incrementAndGet());
        assertEquals(0, scheduled.get(), "the plugin is still initializing: nothing to apply yet");
        pending.complete(null);

        assertEquals(1, scheduled.get(),
                "the late completion also goes through the scheduler, never in place");
    }

    // ---- the content's disposal -----------------------------------------------

    @Test
    void theProviderDisposalRunsOnceHoweverOftenTheContentIsDisposed() {
        AtomicInteger disposals = new AtomicInteger();
        ReviewToolWindowFactory.OnceDisposal disposal =
                new ReviewToolWindowFactory.OnceDisposal(disposals::incrementAndGet);

        disposal.dispose();
        disposal.dispose(); // the Disposer tree and the content release can both arrive

        assertEquals(1, disposals.get(),
                "the provider's teardown runs exactly once, whichever disposal path arrives twice");
    }

    // ---- the id ----------------------------------------------------------------

    @Test
    void theWindowIdIsThePinnedReviewTabName() {
        assertEquals(ReviewTabProvider.TAB_NAME, ReviewToolWindowFactory.TOOL_WINDOW_ID,
                "one name: the provider's tab, the opener's target and the descriptor must agree");
    }
}
