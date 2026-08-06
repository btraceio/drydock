package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGENTS.md: anything advertised here must be bound, and vice versa. The
 * Explorer's single-letter layer lives in SessionExplorerView.installShortcuts;
 * this pins the "and vice versa" half, which is the one that rotted.
 */
class ShortcutsOverlayParityTest {

    @Test
    void everyExplorerKeyTheViewBindsIsAdvertised() {
        List<String> advertised = ShortcutsOverlay.diagKeysFor("IN THE EXPLORER");
        for (String key : List.of("/", "d", "s", "z", "⏎ / u / a", "⌘[ / ⌘]", "Esc")) {
            assertTrue(advertised.contains(key),
                    "the Explorer binds " + key + " but the overlay does not mention it: " + advertised);
        }
    }
}
