# Manual terminal test checklist

Plan section 22.4 ("Manual terminal test checklist") and section 7 ("Gate
0D: Run an interactive shell"). This file is the checked-in, living record
of that checklist. Each item is marked:

- **VERIFIED (automated)** -- proven by `./gradlew gate0dSpike` (Task 6 /
  Gate 0D) or `./gradlew gate0cSpike` (Task 5 / Gate 0C), reading back the
  terminal's actual rendered cell content via `ghostty_surface_read_text`
  (not just "no crash"/screenshots). See docs/native-integration.md, "Task
  6 / Gate 0D" for full log excerpts and exactly what each check asserts.
- **VERIFIED (manual)** -- confirmed by a human watching a real window
  (fill in date/notes when done).
- **UNVERIFIABLE HEADLESSLY** -- genuinely requires a human at a real
  keyboard/screen/mouse/pasteboard; explained why below.
- **NOT YET RUN** -- not attempted yet.

Run the automated checklist with:

```bash
./gradlew gate0dSpike
```

Leave the window open for manual driving instead with:

```bash
./gradlew gate0dSpike -Papp.drydock.gate0d.interactive
```

## Results (last automated run: 2026-07-14, 12/12 checks passing)

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1 | shell (`/bin/zsh -l` spawns, prompt renders) | VERIFIED (automated) | `ghostty_surface_read_text` shows non-blank viewport, then a real macOS login banner + prompt (`Last login: ... \njbachorik@...-MacBook-Pro ~ %`) after startup. |
| 2 | Claude Code | NOT YET RUN | Out of scope for Task 6 (plan section 7, Gate 0E is a separate later gate). |
| 3 | Vim | VERIFIED (automated) | `vim` launched via typed keys; screen content while running showed the real startup banner (`VIM - Vi IMproved`, `version 9.1.1752`) and `~` empty-line gutter markers; exited cleanly via Escape + `:q!`, shell usable immediately after. |
| 4 | coloured output | PARTIALLY VERIFIED (automated) + UNVERIFIABLE HEADLESSLY (colour itself) | Automated: a `printf` wrapped in SGR (`\033[31m...\033[0m`) survives escape-sequence parsing and its text renders correctly as its own output row. NOT verified: that red pixels are actually drawn -- `ghostty_surface_read_text` returns decoded cell *text*, not colour attributes or pixels. Needs a human (or a pixel-level automated check, not attempted) to confirm colour. |
| 5 | Unicode | VERIFIED (automated) | `echo` of an accented character (café) round-trips correctly through the terminal's cell grid. |
| 6 | emoji | VERIFIED (automated) | Same command also included ☃ (U+2603) and 😀 (U+1F600, a surrogate pair / wide glyph); both came back intact in `ghostty_surface_read_text`. Glyph *rendering* (correct double-width cell allocation, actual pixels) not verified -- see item 4's caveat, same limitation. |
| 7 | selection | UNVERIFIABLE HEADLESSLY | Requires a real mouse-drag gesture (or the equivalent OS-level synthetic mouse event sequence, not attempted) over rendered glyphs; `ghostty_surface_has_selection`/`read_selection` exist and could programmatically *set* a selection via `ghostty_surface_mouse_button`/`mouse_pos`, but faithfully reproducing a user's click-drag-release and confirming the *visual* highlight requires a human. |
| 8 | clipboard | UNVERIFIABLE HEADLESSLY | Requires the real macOS pasteboard. Also a known implementation gap carried over from Gate 0C (see docs/native-integration.md): `read_clipboard_cb`/`write_clipboard_cb` are wired as ABI-correct no-ops, so OSC 52 / Cmd+C / Cmd+V do not actually round-trip yet -- this needs real implementation before it can even be manually tested end-to-end. |
| 9 | resizing | VERIFIED (automated) | Not just "the draw call didn't crash" (Gate 0C's ceiling) -- confirmed the resize actually reaches the pty: `$COLUMNS` read back as 112 before a widen-resize and 187 after, from the shell itself (SIGWINCH round-trip). |
| 10 | alternate screen | VERIFIED (automated) | Covered by the vim test (vim's TUI runs in the alternate screen); screen content while vim was running was categorically different from, and the shell prompt/history were not visible during, the vim session. |
| 11 | Ctrl+C | VERIFIED (automated) | `sleep 30; echo GATE0D_SLEEP_FINISHED_NORMALLY` was interrupted ~1s in via a synthesized Ctrl+C key event; a subsequent command's output appeared on its own terminal row within ~2s (proving the shell returned to an interactive prompt), and `GATE0D_SLEEP_FINISHED_NORMALLY` never appeared as an output row (proving `sleep 30` did NOT run to completion). |
| 12 | Ctrl+D | VERIFIED (automated) | `ghostty_surface_process_exited()` was `false` immediately before sending Ctrl+D on an empty prompt line, and `true` ~1.2s after -- the login shell actually exited. |
| 13 | Cmd+C | UNVERIFIABLE HEADLESSLY | Real macOS Cmd+key combo through the actual AppKit responder chain (this spike drives `ghostty_surface_key`/`ghostty_surface_text` directly, bypassing AppKit) plus the same clipboard-no-op gap as item 8. |
| 14 | Cmd+V | UNVERIFIABLE HEADLESSLY | Same as Cmd+C. |
| 15 | Option+arrow | UNVERIFIABLE HEADLESSLY | This spike can synthesize `GHOSTTY_MODS_ALT` on a key event (`sendKey(..., MODS_ALT, ...)` -- not attempted this task, since verifying its *effect* (word-boundary cursor jump in zsh's line editor) would need the same `hasOutputLine`-style technique as the other line-editing checks and was left for a follow-up rather than this already-large task). A real Option-key combo through AppKit (`macos-option-as-alt` config interaction, dead-key/composition edge cases) still needs a human. |
| 16 | Home/End | UNVERIFIABLE HEADLESSLY (not attempted) | Native macOS keycodes verified against `src/input/keycodes.zig` during this task (Home=0x73/115, End=0x77/119, PageUp=0x74/116, PageDown=0x79/121) but not yet exercised in the automated checklist; mechanically identical to the arrow-key check already proven to work, so low risk, just not done here. |
| 17 | Page Up/Page Down | UNVERIFIABLE HEADLESSLY (not attempted) | Same as Home/End -- scrollback-position effects in particular are hard to assert on headlessly without also implementing scrollback-aware reads, which `ghostty_surface_read_text`'s `GHOSTTY_POINT_VIEWPORT` mode does not by itself distinguish from `GHOSTTY_POINT_SCREEN`/scrollback offsets; would need real investigation. |
| 18 | application tab switching | UNVERIFIABLE HEADLESSLY | This project has no tab UI yet (out of scope per plan section 3, "Initial Scope") and even once it exists, OS-level Cmd+Tab / window-tab switching needs a human. |
| 19 | application hide/show | UNVERIFIABLE HEADLESSLY | Real macOS app lifecycle event (Cmd+H, Dock click) with no meaningful headless equivalent; `ghostty_surface_set_occlusion` exists and could be called directly, but that only proves the *call* doesn't crash, not that a real hide/show gesture triggers it correctly. |
| 20 | sleep/wake | UNVERIFIABLE HEADLESSLY | Requires actually sleeping the physical machine; not attempted for obvious practical reasons (would interrupt this development session and any concurrent user work). |
| 21 | external display disconnect | UNVERIFIABLE HEADLESSLY | Requires real display hardware changes. |
| 22 | Retina scaling | PARTIALLY VERIFIED (automated, indirect) | `stage.getOutputScaleX()` was read and passed through to `ghostty_surface_new`/resize calls correctly (scale=2.0 observed on this Retina machine, consistent across Gate 0C and Gate 0D runs) -- but actually *changing* scale (e.g. by moving the window to a different-DPI display) was not exercised; needs a human with a second, differently-scaled display, or a display profile switch. |

## A real bug this checklist caught (and fixed)

While building the automated Gate 0D checks, every "special key" (Enter,
Backspace, Tab, Escape, arrows, Ctrl+C, Ctrl+D) silently failed the first
time they were actually exercised end-to-end. Root cause: `ghostty_surface_key`'s
`ghostty_input_key_s.keycode` field is **not** a `GHOSTTY_KEY_*` C enum
ordinal -- `src/apprt/embedded.zig`'s `KeyEvent.core()` looks the incoming
`keycode` up against `input.keycodes.entries[].native`, i.e. it must be the
raw **platform-native virtual keycode** (e.g. macOS keycode 36 = Return, 51
= Backspace/Delete, 53 = Escape, 123-126 = arrows -- verified against
`third_party/ghostty/src/input/keycodes.zig`'s macOS "native" column, not
guessed).

`app.drydock.terminal.Gate0cSpike` (Task 5 / Gate 0C) had this exact bug in its
`SPECIAL_KEYS` map, translating a real AppKit keycode into a
`GHOSTTY_KEY_*` ordinal and passing *that* as `keycode` -- e.g. it sent 53
(`GHOSTTY_KEY_BACKSPACE`'s ordinal) for the Backspace key, and macOS
keycode 53 actually means Escape, so every real physical Backspace
keystroke would silently have acted like Escape instead. This was never
caught by Gate 0C's own automated check because that check only exercised
a single plain typed character (`'q'`), which goes through the entirely
separate `ghostty_surface_text` codepath and never touches `keycode` at
all. Both `Gate0cSpike` and `Gate0dSpike` are fixed now (native macOS
keycodes passed straight through, see their Javadoc); see
docs/native-integration.md, "Task 6 / Gate 0D" for the full log evidence
(before/after) and `app.drydock.terminal.ghostty.GhosttySurface.sendCharKey`'s
Javadoc for a second, related finding (`ghostty_surface_text` is
paste-only semantics, and gets wrapped in bracketed-paste markers once the
shell enables bracketed paste -- ordinary typed characters must go through
`ghostty_surface_key`/`sendCharKey` instead).

## Settings — opening and closing the modal

1. Look at the title bar. The gear button renders between the `?` shortcuts
   button and the theme toggle — if it is missing or in the wrong slot, the
   layout wiring in `TitleBar`/`AppShell` has regressed.
2. Click the gear button. Settings opens — this is the only way to reach it
   without the keyboard, so it must work on its own, not just as a backup
   for ⌘,.
3. Close it, then press ⌘, instead. Settings opens the same way — both
   entry points must land on the same modal, not two different ones.
4. With Settings open, press ⌘, again. Nothing stacks and nothing flickers;
   a second modal on top of the first would mean the ⌘, handler stopped
   checking `modalLayer().isShowingModal()` before opening.
5. Close Settings, open New worktree, then press ⌘,. New worktree is left
   untouched and Settings does not appear over it — ⌘, must stay inert
   while any other modal owns the screen, or it could silently replace a
   form the user is mid-way through filling in.
6. Open Settings again and press Esc. It closes — Esc must reach the modal
   layer's generic close handling, not just the terminal.
7. Reopen it and click the × in the header. It closes.
8. Reopen it and click **Done**. It closes. All three (Esc, ×, Done) are
   equivalent exits — there is no OK/Cancel distinction to get wrong.
9. Press `?` to open the shortcuts overlay and find the **Settings** row —
   it must read `⌘,`, confirming the shortcut is documented where a user
   would actually look for it.

## Settings — unchanged at the default size

1. Quit and relaunch with no settings changed (interface size at its 13.0
   default). Compare the app's overall look to before this feature existed
   — every row, button, and font should look exactly the same. The interface
   slider's default position deliberately returns the bundled, unscaled
   stylesheet with no generated copy in play, so this is the one setting
   combination most likely to silently break something structural if it
   didn't.

## Settings — legibility in both themes

1. Open Settings in Dark. Read the **Dark**/**Light** radio captions and
   drag both sliders. The captions must be clearly readable text (not
   near-black on the dark background) and the slider tracks/thumbs must be
   visibly distinct from the modal's backdrop — stock JavaFX controls
   default to a light-mode look that app.css does not otherwise override,
   so this is the one place in the app that regresses if that gap is ever
   reopened.
2. Toggle to Light (closing Settings first, since the theme cannot change
   while it's open — see the next item) and reopen Settings. Repeat the
   same check: captions and slider tracks must be legible against the
   light backdrop too.
3. With Settings open in either theme, press ⌘⇧L. Nothing happens — the
   theme is locked while the modal is showing, specifically so the radio
   (which reads the theme once, at construction) can never drift from
   reality with no way to click it back.

## Settings — worktrees directory round trip

1. Open Settings. The **Directory** field starts disabled showing
   "Loading…", then resolves to either empty (no directory configured yet)
   or the previously saved path — never left stuck at "Loading…", which
   would mean `UserConfig.loadAsync`'s future never completed.
2. Click **Browse…** and pick a directory. The field fills in with the
   chosen absolute path and both the field and the button briefly disable
   while the save is in flight, then re-enable.
3. Close Settings, then open **New worktree** for any repository. The
   proposed worktree path is nested under the directory chosen in step 2,
   not the old `~/dev/wt` default — proving `WorktreeNaming` actually reads
   the saved value back, not just that Settings wrote something.
4. Quit the app (or just inspect the file) and check
   `~/.drydock/config.json`. It contains a `worktreesDirectory` key with
   the chosen path — this setting has no automated coverage at all, so this
   manual step is the only check that the full write-then-read path works
   end to end.

## Settings — terminal font size

1. Open a Claude session so a ghostty surface is live.
2. Open Settings (⌘,) and drag **Terminal size**. It snaps to whole pixels
   only (no ".5" readout) — `TerminalThemes` rounds to an int internally, so
   a half-pixel readout would show a number the terminal cannot actually
   render. Drag it to 18.
3. The running terminal's text grows immediately — not only new sessions.
4. Toggle the theme (⌘⇧L). The terminal re-themes and **keeps** size 18.
   (A regression here means the size went back to a per-surface override,
   which `ghostty_surface_update_config` discards.)
5. Quit and relaunch. The terminal opens at 18.

## Settings — interface font size

1. Open Settings and sweep **Interface size** across 11 → 16.
2. At both extremes check for clipping in: the title bar and traffic
   lights, the sidebar filter field, the icon buttons, a combo-box popup
   (New worktree ▸ Fork from), a right-click context menu, and a tooltip.
   The popups and menus must scale with everything else — they are separate
   scene graphs, and an implementation that only styled the main scene would
   leave them at 13px.
3. Fixed-height controls (filter field 32px, icon buttons 30px, title bar
   44px) do not grow; confirm their text still fits at 16.
4. Quit and relaunch. The size is restored with no visible re-layout flash.
## MCP server (spec 2026-07-25)

**Status: NOT YET RUN — UNVERIFIABLE HEADLESSLY.** Like Gate 0E (item 2
above), every step here needs a real `claude` binary and a real Claude
account: the tools are only reachable from inside a live session, and the
MCP handshake happens between `claude` and the app over loopback HTTP. The
automated suite covers the pieces either side of that boundary — the
`--mcp-config` flag assembly (`ClaudeAgentProviderMcpFlagTest`), the tool
router and its refusals (`McpToolRouter*Test`), the transport
(`McpServerTest`), the token registry (`McpSessionRegistryTest`), the
config file (`McpConfigWriterTest`) and the context's path handling
(`WorkspaceMcpSessionContextTest`) — but nothing in it proves a real
`claude` ever connects.

Walk this with a human at the keyboard:

1. Start a session in a local repository. Run `/mcp` and confirm a `drydock`
   server appears **connected**, listing six tools. *A protocol-level
   handshake failure would leave every unit test green and the feature
   inert, so this is the gate that matters most.*
2. Ask the session to call `repos_list`. Confirm it names your registered
   repositories and branches, and that a registered **remote** repository
   appears without dirty/ahead/behind.
3. In the Review view, leave an annotation on a changed line. Ask the session
   to read the review comments and address them. Confirm it reports the
   annotation text **and the excerpt**, and that the thread flips to
   "addressed" with a `Claude`-authored note.
4. **With the Review tab still open**, confirm the card updates live — the
   annotation-store change listener — and that clicking Resolve afterwards
   keeps the agent's note.
5. Confirm the summary line counts the addressed thread, and that the
   thread's button reads "Resolve", not "Reopen".
6. Ask the session to create a worktree and start a session in it. Confirm a
   new sidebar entry and terminal tab appear.
7. In that **new** session, run `/mcp`, then ask it to create a worktree.
   Confirm it is refused as an agent-started session — fan-out is depth 1.
8. Ask the original session to create five worktrees. Confirm the fifth is
   refused, naming the limit.
9. Ask a session to call `worktree_create` with branch `origin/main`. Confirm
   it is refused before git runs.
10. Ask a session to call `session_start` with a path outside the repository
    (e.g. `/tmp`). Confirm it is refused, naming the path.
11. Start a **remote SSH** session. Run `/mcp` and confirm no `drydock` server
    is listed. (An earlier draft of this checklist also asked you to confirm a
    banner saying Drydock tools are unavailable for remote sessions. No such
    banner exists — nothing in the implementation builds one, and the claim was
    withdrawn from the design; see the "Known gap" note under Scope in
    `docs/superpowers/specs/2026-07-25-drydock-mcp-server-design.md`. Do not
    treat its absence as a failure of this step.)
12. Close a session, then confirm its file under `<base>/mcp/` is gone and
    that a `curl` with its old token gets 401.
13. Let `claude` exit **on its own** — type `exit` in the session — and **leave
    the tab open**. Note the token from its `<base>/mcp/` file first, then
    confirm the file disappears within a second or two (the exit watcher's
    tick) and that a `curl` with that token gets 401 even though the terminal
    tab is still there reading its final output. *This is a different path from
    item 12: closing the tab revokes correctly, while a self-exit does not go
    through the surface-close path at all, so it needs its own release.*
14. Build the packaged app (`./gradlew :app:appImage`), launch it, and repeat
    step 1. **This is the only check that catches a missing `jdk.httpserver`
    in the jlink module list:** `:app:test` and `:app:run` both resolve
    `com.sun.net.httpserver` from the full JDK and would stay green while the
    shipped app failed to serve a single request.

## Session rename via MCP (spec 2026-08-05)

**Status: NOT YET RUN — UNVERIFIABLE HEADLESSLY.** The `session_rename` tool
lets a hosted `claude` agent rename its own session tab. Whether it actually
does so depends on the MCP client injecting the server's `instructions` field
into the agent's system prompt — no unit test can observe a real system
prompt — and the visible result is a relabel of live JavaFX UI
(`MainWorkspace` has no test harness; its constructor takes 15 collaborators
plus a live `Stage`). Walk this with a human at the keyboard:

1. Start a session in a local repository and give it work. Within its first
   few turns, confirm the agent renames its own tab unprompted, without
   being asked to. *Nothing else proves the MCP client actually injects the
   server's `instructions` field into the agent's system prompt — every
   automated test passes with the feature completely inert.*
2. When it does, confirm both the tab label and the sidebar row change. Do
   NOT expect the row to move: `RepositorySidebar.sessionsFor` sorts by name,
   but `SidebarChildren.classify` then re-bands the rows live-then-idle, each
   by `lastOpenedAt` descending, and that is what reaches the tree. A rename
   never changes a row's position. *(Verified 2026-08-05 by driving a real
   `session_rename` call against a running app: the tab label, the session
   header and the sidebar row all changed; the row did not move.)*
3. Rename a session to something long, e.g. 60 full-width CJK characters.
   Confirm the sidebar row and the session header text are actually
   **ellipsized** — look for the "…" character itself, not just a
   shorter-looking label — and separately confirm the window still narrows
   to its usual minimum width. Check both: `setMinWidth(0)` alone would
   satisfy the window-narrowing half of this check while the clamp silently
   did nothing to the text, so a check that only looks at window width
   cannot tell a correct fix from a half-finished one.
4. Enter pins a name; clicking away does not. This is now scriptable — the
   diag verbs fire the field's real handlers rather than calling the commit
   directly, so they would still catch the wiring being swapped:

   ```
   ./gradlew run \
     -Papp.drydock.diag.stateFile=<tmp>/state.json \
     -Papp.drydock.diag.autoCreateSession=true \
     -Papp.drydock.diag.repo=<repo> \
     -Papp.drydock.diag.tabScript="30:rename,32:renametext:Blur named this,34:renameblur,\
   60:rename,62:renametext:Human named this,64:renameenter"
   ```

   After the blur commit, `<tmp>/state.json` must show the new name with
   `namePinned: false`, and a `session_rename` MCP call must still succeed.
   After the Enter commit it must show `namePinned: true`, and the same call
   must be refused with the human's title quoted back. *(Verified 2026-08-05:
   blur → `namePinned=false`, agent rename returned `renamed`; Enter →
   `namePinned=true`, agent rename refused with "This session was named by
   the human ('Human named this')".)* Blur must never pin because an agent's
   `session_start` opens and selects a tab, which blurs any open editor.