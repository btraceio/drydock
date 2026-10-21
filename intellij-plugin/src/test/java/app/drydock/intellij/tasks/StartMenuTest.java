package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.StartMenu.Item;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code Start ▸} on a worktree row (cross-project tabs, frame F1). */
class StartMenuTest {

    @Test
    void aForeignWorktreeOffersHereOwnWindowAndReadOnly() {
        assertEquals(List.of(
                        new Item("Start in this window", Optional.of("Tab opens here, tagged dd-jmc"),
                                OpenIn.THIS_WINDOW, SessionMode.WRITES),
                        new Item("Start in its own window", Optional.of("Opens dd-jmc · fix/chunk_memory"),
                                OpenIn.OWN_WINDOW, SessionMode.WRITES),
                        new Item("Read-only, in this window", Optional.empty(), OpenIn.THIS_WINDOW,
                                SessionMode.READ_ONLY)),
                StartMenu.items("dd-jmc", "fix/chunk_memory", false));
    }

    @Test
    void theWindowsOwnWorktreeOffersAPlainStart() {
        List<Item> items = StartMenu.items("java-profiler", "pr-857", true);

        assertEquals(new Item("Start", Optional.empty(), OpenIn.THIS_WINDOW, SessionMode.WRITES), items.get(0));
        assertEquals(List.of("Start", "Read-only, in this window"), items.stream().map(Item::text).toList());
    }
}
