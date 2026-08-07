package app.drydock.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AGENTS.md: anything advertised here must be bound, and vice versa. The
 * Explorer's single-letter layer lives in SessionExplorerView.installShortcuts;
 * this pins both halves as one equality, so a row for a key nobody wired up
 * fails it exactly as loudly as a binding nobody told the reader about.
 */
class ShortcutsOverlayParityTest {

    /**
     * Rows in the "IN THE EXPLORER" section that are not this view's own
     * key bindings, so they're excluded from the equality rather than
     * silently mismatched against installShortcuts. Each is bound elsewhere
     * (or isn't a key at all) -- named here so the next row added to the
     * section has to be sorted into one side or the other on purpose.
     */
    private static final Set<String> NOT_THIS_VIEWS_OWN_BINDING = Set.of(
            "⌘[ / ⌘]", // trail navigation: bound in the global shortcut chain, not installShortcuts
            "Esc", // closing a peek: bound in the app-wide Esc unwind chain, not installShortcuts
            "click an underlined symbol" // a mouse gesture, not a keyboard binding
    );

    @Test
    void theExplorerAdvertisesExactlyTheKeysItBinds() {
        List<String> advertised = ShortcutsOverlay.diagKeysFor("IN THE EXPLORER");
        Set<String> advertisedOwnKeys = advertised.stream()
                .filter(keycap -> !NOT_THIS_VIEWS_OWN_BINDING.contains(keycap))
                .flatMap(keycap -> Arrays.stream(keycap.split(" / ")))
                .collect(Collectors.toSet());

        // installShortcuts' single-letter/action layer, read straight off the switch.
        Set<String> bound = Set.of("/", "d", "s", "z", "⏎", "u", "a");

        assertEquals(bound, advertisedOwnKeys,
                "the overlay's own-binding rows (left out: " + NOT_THIS_VIEWS_OWN_BINDING
                        + ") and what installShortcuts binds must be the same set -- "
                        + "a key present on only one side is either bound but not advertised, "
                        + "or advertised but bound to nothing");
    }
}
