package app.drydock.terminal.ghostty;

import java.util.Set;

/**
 * Pure key-translation policy for the embedded terminal: the macOS
 * virtual-keycode / ghostty-modifier vocabulary and the classification of
 * a raw AppKit key event into what the caller should do with it.
 *
 * <p>Extracted from {@code OpenSessionTab.onKeyEvent} (see
 * docs/plans/workspace-split-design.md) so the most bug-prone logic in the
 * terminal layer is unit-testable without live native resources. This
 * class is intentionally pure and static: no ghostty, AppKit, or JavaFX
 * dependency -- the caller performs the actual {@link GhosttySurface}
 * calls the returned {@link KeyAction} prescribes.</p>
 *
 * <p>Keycodes are raw macOS <em>platform</em> virtual keycodes, not
 * {@code GHOSTTY_KEY_*} ordinals -- {@code ghostty_input_key_s.keycode}
 * expects the former (see Gate0cSpike.SPECIAL_KEYS's Javadoc and
 * docs/native-integration.md, "Struct layout verification").</p>
 */
public final class GhosttyKeyTranslator {

    // -- macOS virtual keycodes ---------------------------------------------

    /** Return / Enter (raw macOS virtual keycode). */
    public static final int KEY_RETURN = 36;
    /** kVK_ANSI_D -- used by {@link GhosttySurface#closeGracefully}'s Ctrl+D exit request. */
    public static final int KEY_D = 2;

    // -- ghostty_input_mods_e bitmask ---------------------------------------

    public static final int MODS_SHIFT = 1;
    public static final int MODS_CTRL = 1 << 1;
    public static final int MODS_ALT = 1 << 2;
    public static final int MODS_SUPER = 1 << 3;

    // -- NSEvent.ModifierFlags bitmask --------------------------------------

    private static final int NS_SHIFT = 1 << 17;
    private static final int NS_CONTROL = 1 << 18;
    private static final int NS_OPTION = 1 << 19;
    private static final int NS_COMMAND = 1 << 20;

    /**
     * Native macOS virtual keycodes always forwarded as non-text key events
     * ({@link GhosttySurface#sendKey}) rather than typed characters.
     */
    private static final Set<Integer> SPECIAL_KEYS = Set.of(
            KEY_RETURN, // Return / Enter
            51,  // Delete (Backspace)
            48,  // Tab
            53,  // Escape
            123, // Left arrow
            124, // Right arrow
            125, // Down arrow
            126, // Up arrow
            115, // Home
            119, // End
            116, // Page Up
            121, // Page Down
            117  // Forward Delete (fn+Delete)
    );

    private GhosttyKeyTranslator() {
    }

    /** App-level shortcuts intercepted before the terminal sees the key (see {@link AppShortcut}). */
    public enum Shortcut {
        /** ⌘1 -- switch to the Terminal sub-tab. */
        TERMINAL_SUB_TAB,
        /** ⌘2 -- switch to the Explorer sub-tab. */
        EXPLORER_SUB_TAB,
        /** ⌘3 -- switch to the Review sub-tab. */
        REVIEW_SUB_TAB,
        /** ⌘⇧[ -- select the previous session tab. */
        PREVIOUS_SESSION_TAB,
        /** ⌘⇧] -- select the next session tab. */
        NEXT_SESSION_TAB,
        /** ⌘0 -- toggle the sidebar. */
        TOGGLE_SIDEBAR
    }

    /** What the caller should do with one raw AppKit key event. */
    public sealed interface KeyAction {
    }

    /**
     * The key is an app shortcut: swallow it (never forward to the
     * terminal), and perform {@code shortcut}'s action -- but only on
     * key-down; the matching key-up is swallowed without re-triggering.
     * These are intercepted here because while the terminal is focused its
     * native NSEvent monitor sees every key BEFORE JavaFX's scene filter.
     */
    public record AppShortcut(Shortcut shortcut, boolean keyDown) implements KeyAction {
    }

    /**
     * Forward as a non-text key event via {@link GhosttySurface#sendKey(int,
     * int, boolean, int)} -- a special key (Return, arrows, ...) or a
     * Ctrl/⌘-modified shortcut. {@code unshiftedCodepoint} carries the
     * key's base character on key-down (0 on key-up or when unavailable),
     * required by ghostty's Kitty-protocol encoder for modified letter keys
     * (see that sendKey overload's Javadoc).
     */
    public record ForwardKey(int keyCode, int mods, boolean keyDown, int unshiftedCodepoint)
            implements KeyAction {
    }

    /**
     * Ordinary typing: send each codepoint of {@code characters} through
     * the real keyboard codepath ({@link GhosttySurface#sendCharKey}), NOT
     * {@link GhosttySurface#sendText} paste semantics.
     */
    public record TypeCharacters(String characters, int mods) implements KeyAction {
    }

    /** Nothing to do (e.g. the key-up of a plain character). */
    public record Ignore() implements KeyAction {
    }

    private static final Ignore IGNORE = new Ignore();

    /** Whether {@code keyCode} is one of the always-forwarded special keys (see {@link #SPECIAL_KEYS}). */
    public static boolean isSpecialKey(int keyCode) {
        return SPECIAL_KEYS.contains(keyCode);
    }

    /** Translates an AppKit {@code NSEvent.ModifierFlags} bitmask into a ghostty {@code MODS_*} bitmask. */
    public static int translateModifiers(int nsModifierFlags) {
        int mods = 0;
        if ((nsModifierFlags & NS_SHIFT) != 0) mods |= MODS_SHIFT;
        if ((nsModifierFlags & NS_CONTROL) != 0) mods |= MODS_CTRL;
        if ((nsModifierFlags & NS_OPTION) != 0) mods |= MODS_ALT;
        if ((nsModifierFlags & NS_COMMAND) != 0) mods |= MODS_SUPER;
        return mods;
    }

    /**
     * Classifies one raw AppKit key event. Decision order (verbatim from
     * the original {@code OpenSessionTab.onKeyEvent}):
     *
     * <ol>
     *   <li>⌘-shortcuts ({@link Shortcut}) -- checked against {@code
     *       characters}, falling back to {@code unshiftedCharacters} when
     *       empty; with ⇧ held the resolved character for [ / ] is { / },
     *       so both forms match.</li>
     *   <li>Special keys and Ctrl/⌘ combos -- forwarded as key events,
     *       with the unshifted codepoint populated only on key-down.</li>
     *   <li>Plain characters on key-down -- typed per codepoint.</li>
     *   <li>Everything else -- ignored.</li>
     * </ol>
     */
    public static KeyAction translate(int keyCode, int nsModifierFlags, boolean keyDown,
                                      String characters, String unshiftedCharacters) {
        int mods = translateModifiers(nsModifierFlags);
        String shortcutChars = characters.isEmpty() ? unshiftedCharacters : characters;
        if ((mods & MODS_SUPER) != 0 && !shortcutChars.isEmpty()) {
            int cp = shortcutChars.codePointAt(0);
            Shortcut shortcut = switch (cp) {
                case '1' -> Shortcut.TERMINAL_SUB_TAB;
                case '2' -> Shortcut.EXPLORER_SUB_TAB;
                case '3' -> Shortcut.REVIEW_SUB_TAB;
                case '[', '{' -> Shortcut.PREVIOUS_SESSION_TAB;
                case ']', '}' -> Shortcut.NEXT_SESSION_TAB;
                case '0' -> Shortcut.TOGGLE_SIDEBAR;
                default -> null;
            };
            if (shortcut != null) {
                return new AppShortcut(shortcut, keyDown);
            }
        }
        boolean isShortcut = (mods & (MODS_CTRL | MODS_SUPER)) != 0;
        if (isSpecialKey(keyCode) || isShortcut) {
            int unshiftedCodepoint = (!keyDown || unshiftedCharacters.isEmpty())
                    ? 0 : unshiftedCharacters.codePointAt(0);
            return new ForwardKey(keyCode, mods, keyDown, unshiftedCodepoint);
        }
        if (keyDown && !characters.isEmpty()) {
            return new TypeCharacters(characters, mods);
        }
        return IGNORE;
    }
}
