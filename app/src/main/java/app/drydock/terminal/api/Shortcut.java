package app.drydock.terminal.api;

/** A neutral, app-level keyboard shortcut a terminal surface intercepts and reports to the UI. */
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
