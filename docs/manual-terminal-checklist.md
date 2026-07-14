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
./gradlew gate0dSpike -Papp.cpm.gate0d.interactive
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

`app.cpm.terminal.Gate0cSpike` (Task 5 / Gate 0C) had this exact bug in its
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
(before/after) and `app.cpm.terminal.ghostty.GhosttySurface.sendCharKey`'s
Javadoc for a second, related finding (`ghostty_surface_text` is
paste-only semantics, and gets wrapped in bracketed-paste markers once the
shell enables bracketed paste -- ordinary typed characters must go through
`ghostty_surface_key`/`sendCharKey` instead).
