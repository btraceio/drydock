package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link InboxViewState}: what it stores, and that a malformed entry is dropped, never a failure. */
class InboxViewStateTest {

    @Test
    void togglesFilterAndDismissalRoundTripThroughTheStoredShape() {
        InboxViewState state = new InboxViewState();
        state.setToggles(new InboxViewState.Toggles(Set.of(GroupKind.READY), Set.of(GroupKind.DONE),
                Set.of(GroupKind.RUNNING, GroupKind.IDLE)));
        ProjectRef ref = new ProjectRef(Path.of("/repos/alpha").toAbsolutePath(), "alpha");
        state.setFilter(Optional.of(ref));
        state.setBannerDismissed(true);

        InboxViewState reloaded = new InboxViewState();
        reloaded.loadState(state.getState());
        assertEquals(Set.of(GroupKind.READY), reloaded.toggles().collapsed());
        assertEquals(Set.of(GroupKind.DONE), reloaded.toggles().expanded());
        assertEquals(Set.of(GroupKind.RUNNING, GroupKind.IDLE), reloaded.toggles().showingAll());
        assertEquals(Optional.of(ref), reloaded.filter());
        assertTrue(reloaded.bannerDismissed());

        reloaded.setFilter(Optional.empty());
        assertEquals(Optional.empty(), reloaded.filter());
    }

    @Test
    void theGraphViewIsOnUntilTheUserSwitchesItOffAndThatSurvivesAReload() {
        InboxViewState state = new InboxViewState();
        assertFalse(state.byState(), "a fresh state shows dependency graphs");

        state.setByState(true);
        InboxViewState reloaded = new InboxViewState();
        reloaded.loadState(state.getState());
        assertTrue(reloaded.byState());

        reloaded.setByState(false);
        assertFalse(reloaded.byState());
    }

    @Test
    void theGroupTogglesRoundTripByNameAndUnknownNamesAreDropped() {
        InboxViewState state = new InboxViewState();
        state.setToggles(new InboxViewState.Toggles(Set.of(GroupKind.FLAGGED), Set.of(),
                Set.of(GroupKind.FLAGGED)));
        assertEquals(List.of("FLAGGED"), state.getState().collapsed, "stored by the enum's name");

        InboxViewState.Stored stored = state.getState();
        stored.collapsed = List.of("FLAGGED", "BABYSITTING", "NO_SUCH_GROUP");
        stored.showingAll = List.of("FLAGGED", "BABYSITTING", "NO_SUCH_GROUP");

        InboxViewState reloaded = new InboxViewState();
        reloaded.loadState(stored);
        assertEquals(Set.of(GroupKind.FLAGGED), reloaded.toggles().collapsed());
        assertEquals(Set.of(GroupKind.FLAGGED), reloaded.toggles().showingAll());
        assertFalse(GroupKind.FLAGGED.collapsedByDefault(), "the section opens expanded");
        assertTrue(GroupKind.FLAGGED.truncates());
    }

    @Test
    void malformedEntriesAreDropped() {
        InboxViewState.Stored stored = new InboxViewState.Stored();
        stored.collapsed = List.of("READY", "NO_SUCH_GROUP");
        stored.expanded = null;
        stored.filterRoot = "/repos/\u0000bad";
        stored.filterName = "bad";
        InboxViewState state = new InboxViewState();
        state.loadState(stored);

        assertEquals(Set.of(GroupKind.READY), state.toggles().collapsed());
        assertEquals(Set.of(), state.toggles().expanded());
        assertEquals(Optional.empty(), state.filter(), "an unreadable path is no filter");
        stored.filterRoot = "/repos/alpha";
        stored.filterName = null;
        assertEquals(Optional.empty(), state.filter(), "half a filter is no filter");
        assertFalse(state.bannerDismissed());
    }
}
