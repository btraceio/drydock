package app.cpm.terminal.ghostty;

import app.cpm.terminal.ghostty.GhosttyKeyTranslator.AppShortcut;
import app.cpm.terminal.ghostty.GhosttyKeyTranslator.ForwardKey;
import app.cpm.terminal.ghostty.GhosttyKeyTranslator.Ignore;
import app.cpm.terminal.ghostty.GhosttyKeyTranslator.KeyAction;
import app.cpm.terminal.ghostty.GhosttyKeyTranslator.Shortcut;
import app.cpm.terminal.ghostty.GhosttyKeyTranslator.TypeCharacters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GhosttyKeyTranslatorTest {

    // NSEvent.ModifierFlags device-independent masks.
    private static final int NS_SHIFT = 1 << 17;
    private static final int NS_CONTROL = 1 << 18;
    private static final int NS_OPTION = 1 << 19;
    private static final int NS_COMMAND = 1 << 20;

    // An arbitrary non-special keycode (kVK_ANSI_A = 0).
    private static final int KEY_A = 0;

    // ---- translateModifiers -------------------------------------------------

    @Test
    void translatesEachNsModifierToItsGhosttyBit() {
        assertEquals(0, GhosttyKeyTranslator.translateModifiers(0));
        assertEquals(GhosttyKeyTranslator.MODS_SHIFT, GhosttyKeyTranslator.translateModifiers(NS_SHIFT));
        assertEquals(GhosttyKeyTranslator.MODS_CTRL, GhosttyKeyTranslator.translateModifiers(NS_CONTROL));
        assertEquals(GhosttyKeyTranslator.MODS_ALT, GhosttyKeyTranslator.translateModifiers(NS_OPTION));
        assertEquals(GhosttyKeyTranslator.MODS_SUPER, GhosttyKeyTranslator.translateModifiers(NS_COMMAND));
    }

    @Test
    void translatesModifierCombinations() {
        assertEquals(GhosttyKeyTranslator.MODS_SHIFT | GhosttyKeyTranslator.MODS_SUPER,
                GhosttyKeyTranslator.translateModifiers(NS_SHIFT | NS_COMMAND));
        assertEquals(GhosttyKeyTranslator.MODS_SHIFT | GhosttyKeyTranslator.MODS_CTRL
                        | GhosttyKeyTranslator.MODS_ALT | GhosttyKeyTranslator.MODS_SUPER,
                GhosttyKeyTranslator.translateModifiers(NS_SHIFT | NS_CONTROL | NS_OPTION | NS_COMMAND));
    }

    @Test
    void ignoresUnrelatedNsFlagBits() {
        // e.g. NSEventModifierFlagCapsLock (1 << 16) and function (1 << 23).
        assertEquals(0, GhosttyKeyTranslator.translateModifiers((1 << 16) | (1 << 23)));
    }

    // ---- special keys -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {36, 51, 48, 53, 123, 124, 125, 126, 115, 119, 116, 121, 117})
    void specialKeysForwardAsKeyEventsEvenUnmodified(int keyCode) {
        KeyAction action = GhosttyKeyTranslator.translate(keyCode, 0, true, "", "");
        ForwardKey forward = assertInstanceOf(ForwardKey.class, action);
        assertEquals(keyCode, forward.keyCode());
        assertEquals(0, forward.mods());
        assertEquals(true, forward.keyDown());
    }

    @Test
    void plainLetterKeyIsNotSpecial() {
        assertEquals(false, GhosttyKeyTranslator.isSpecialKey(KEY_A));
        assertEquals(true, GhosttyKeyTranslator.isSpecialKey(GhosttyKeyTranslator.KEY_RETURN));
    }

    // ---- app shortcuts ------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "1, TERMINAL_SUB_TAB",
            "2, EXPLORER_SUB_TAB",
            "3, REVIEW_SUB_TAB",
            "[, PREVIOUS_SESSION_TAB",
            "{, PREVIOUS_SESSION_TAB",
            "], NEXT_SESSION_TAB",
            "}, NEXT_SESSION_TAB",
            "0, TOGGLE_SIDEBAR",
    })
    void commandShortcutsAreIntercepted(String character, Shortcut expected) {
        KeyAction action = GhosttyKeyTranslator.translate(18, NS_COMMAND, true, character, character);
        AppShortcut shortcut = assertInstanceOf(AppShortcut.class, action);
        assertEquals(expected, shortcut.shortcut());
        assertEquals(true, shortcut.keyDown());
    }

    @Test
    void shortcutKeyUpIsSwallowedWithoutRetriggering() {
        KeyAction action = GhosttyKeyTranslator.translate(18, NS_COMMAND, false, "1", "1");
        AppShortcut shortcut = assertInstanceOf(AppShortcut.class, action);
        assertEquals(Shortcut.TERMINAL_SUB_TAB, shortcut.shortcut());
        assertEquals(false, shortcut.keyDown());
    }

    @Test
    void shortcutMatchFallsBackToUnshiftedCharactersWhenCharactersEmpty() {
        KeyAction action = GhosttyKeyTranslator.translate(30, NS_COMMAND | NS_SHIFT, true, "", "]");
        AppShortcut shortcut = assertInstanceOf(AppShortcut.class, action);
        assertEquals(Shortcut.NEXT_SESSION_TAB, shortcut.shortcut());
    }

    @Test
    void shortcutCharactersWithoutCommandAreJustTyping() {
        KeyAction action = GhosttyKeyTranslator.translate(18, 0, true, "1", "1");
        TypeCharacters typed = assertInstanceOf(TypeCharacters.class, action);
        assertEquals("1", typed.characters());
    }

    @Test
    void unknownCommandComboForwardsAsShortcutKeyEvent() {
        // ⌘C is not an app shortcut; it must reach the terminal as a key
        // event carrying the unshifted codepoint (Kitty-protocol requirement).
        KeyAction action = GhosttyKeyTranslator.translate(8, NS_COMMAND, true, "c", "c");
        ForwardKey forward = assertInstanceOf(ForwardKey.class, action);
        assertEquals(8, forward.keyCode());
        assertEquals(GhosttyKeyTranslator.MODS_SUPER, forward.mods());
        assertEquals('c', forward.unshiftedCodepoint());
    }

    // ---- ctrl combos + unshifted codepoint policy ---------------------------

    @Test
    void ctrlComboForwardsWithUnshiftedCodepointOnKeyDown() {
        KeyAction action = GhosttyKeyTranslator.translate(8, NS_CONTROL, true, "", "c");
        ForwardKey forward = assertInstanceOf(ForwardKey.class, action);
        assertEquals(GhosttyKeyTranslator.MODS_CTRL, forward.mods());
        assertEquals('c', forward.unshiftedCodepoint());
        assertEquals(true, forward.keyDown());
    }

    @Test
    void unshiftedCodepointIsZeroOnKeyUp() {
        KeyAction action = GhosttyKeyTranslator.translate(8, NS_CONTROL, false, "", "c");
        ForwardKey forward = assertInstanceOf(ForwardKey.class, action);
        assertEquals(0, forward.unshiftedCodepoint());
        assertEquals(false, forward.keyDown());
    }

    @Test
    void unshiftedCodepointIsZeroWhenUnshiftedCharactersEmpty() {
        KeyAction action = GhosttyKeyTranslator.translate(GhosttyKeyTranslator.KEY_RETURN, 0, true, "\r", "");
        ForwardKey forward = assertInstanceOf(ForwardKey.class, action);
        assertEquals(0, forward.unshiftedCodepoint());
    }

    @Test
    void optionModifiedTypingIsNotAShortcutForward() {
        // ⌥ alone is neither Ctrl nor ⌘: a resolved character types normally
        // (e.g. ⌥e producing a dead-key-resolved character).
        KeyAction action = GhosttyKeyTranslator.translate(14, NS_OPTION, true, "é", "e");
        TypeCharacters typed = assertInstanceOf(TypeCharacters.class, action);
        assertEquals("é", typed.characters());
        assertEquals(GhosttyKeyTranslator.MODS_ALT, typed.mods());
    }

    // ---- plain typing -------------------------------------------------------

    @Test
    void plainCharacterKeyDownTypes() {
        KeyAction action = GhosttyKeyTranslator.translate(KEY_A, 0, true, "a", "a");
        TypeCharacters typed = assertInstanceOf(TypeCharacters.class, action);
        assertEquals("a", typed.characters());
        assertEquals(0, typed.mods());
    }

    @Test
    void shiftedCharacterKeepsShiftMods() {
        KeyAction action = GhosttyKeyTranslator.translate(KEY_A, NS_SHIFT, true, "A", "a");
        TypeCharacters typed = assertInstanceOf(TypeCharacters.class, action);
        assertEquals("A", typed.characters());
        assertEquals(GhosttyKeyTranslator.MODS_SHIFT, typed.mods());
    }

    @Test
    void multiCodepointCharactersPassThroughUnchanged() {
        String emoji = "👍"; // surrogate pair -- typed per codepoint by the caller
        KeyAction action = GhosttyKeyTranslator.translate(KEY_A, 0, true, emoji, "");
        TypeCharacters typed = assertInstanceOf(TypeCharacters.class, action);
        assertEquals(emoji, typed.characters());
    }

    @Test
    void plainCharacterKeyUpIsIgnored() {
        assertInstanceOf(Ignore.class, GhosttyKeyTranslator.translate(KEY_A, 0, false, "a", "a"));
    }

    @Test
    void keyDownWithNoCharactersIsIgnored() {
        // e.g. a bare modifier press or dead key with nothing resolved.
        assertInstanceOf(Ignore.class, GhosttyKeyTranslator.translate(KEY_A, 0, true, "", ""));
    }
}
