# Editable Explorer files with auto-save

Date: 2026-07-21
Branch: `feat/editor`
Status: approved, ready for planning

## Problem

The Session Explorer's code viewer is read-only. `FileViewer.openFile`
builds every `CodeArea` with `setEditable(false)` and the breadcrumb ends
in a static `read-only` chip. Making a one-line touch-up to code visible
in the Explorer means leaving Drydock for an external editor.

The goal is quick in-place edits with no save ceremony, in a workspace
where Claude is concurrently editing the same files.

## Decisions

1. **Save timing** — a long idle debounce (2s after the last keystroke)
   *plus* forced flushes on blur, file-tab switch, sub-tab switch, tab
   close, and scene detach. `⌘S` forces an immediate flush. The debounce
   keeps it ceremony-free; the flushes guarantee nothing is lost when the
   tab goes away.
2. **External changes** — reload if the buffer is clean, block if it is
   dirty. A clean tab silently adopts disk content (Claude's edits land
   in the viewer instead of going stale); a dirty tab stops auto-saving
   and shows a keep-mine/reload banner.
3. **Editability scope** — everything except truncated and unreadable
   buffers, which stay read-only.
4. **Status surface** — breadcrumb chip (`editable` / `unsaved` /
   `saved ✓`) plus a `•` dot on the file tab while dirty. Exceptional
   states use a banner row.
5. **Re-highlighting** — debounced (~150ms) while typing; the diff
   overlay refreshes after each successful save.
6. **Lifecycle ownership** — the Explorer owns its own teardown; no
   cascade from `OpenSessionTab`.

## Architecture

### `FileEditSession` (new, FX-free)

`app.drydock.ui.explorer.FileEditSession`, one instance per open editable
file. Owns the save state machine and all disk I/O. No JavaFX types, so
it is unit-testable the way `SyntaxHighlighter` is.

State: `CLEAN | DIRTY | SAVING | CONFLICT | ERROR`, plus a `DiskStamp`
(last-modified time + size) captured at load and refreshed after every
successful write.

| Method | Behaviour |
|---|---|
| `edit(String text)` | Records pending text, (re)arms the 2s debounce. |
| `flush()` | `CompletableFuture<Void>`; writes immediately, cancels the debounce. The forced-flush hook, mirroring `AnnotationStore.flushPendingSaves`. |
| `poll()` | Compares the current stamp to disk. Clean + changed → `RELOAD`; dirty + changed → `CONFLICT`. |
| `keepMine()` | Force-writes the buffer, adopts the resulting stamp. |
| `takeDisk()` | Discards the buffer, reports the disk content for reload. |

Outcomes are reported through a listener interface the viewer implements;
the viewer hops to the FX thread.

Writes go directly through `Files.writeString`, **not** temp-file +
`ATOMIC_MOVE`. Atomic replace would reset permissions and swap the inode
under anything watching the file; for in-place edits of tracked source
files in a git worktree the direct write is the correct trade.

### `FileViewer` changes

The only substantially edited existing file:

- Editable `CodeArea`s for eligible tabs; truncated/unreadable buffers
  keep `setEditable(false)` and the `read-only` chip.
- `textProperty` listener → `session.edit(text)`, mark dirty, arm the
  re-highlight debounce.
- Chip, per-tab dirty dot, and a conflict/error banner row alongside the
  existing `diffBanner` in `topBox`.
- `refreshDiffOverlay()` after each successful save.
- Owns one daemon `ScheduledExecutorService` driving both the save
  debounce and the ~1.5s disk poll across open tabs.
- Flushes and stops polling when its `sceneProperty()` goes null, plus a
  hook from `DrydockApplication.stop()`.

Scene detach is the single mechanism behind three of the forced-flush
triggers. `OpenSessionTab.showSubTab` swaps the tab's center node, so
leaving the Explorer sub-tab removes the viewer from the scene graph and
nulls its `sceneProperty`; the same happens when the session tab is
closed. That is why decision 1's "sub-tab switch" flush needs no cascade
from `OpenSessionTab`, and why polling naturally idles whenever the
Explorer is not on screen.

### Focus fix (`MainWorkspace`)

`MainWorkspace.onFocusOwnerChanged` releases the terminal's AppKit
first-responder only for `TextInputControl`. RichTextFX's `CodeArea` is a
`Region`, not a `TextInputControl`, so focusing the code area does not
currently hand the keyboard back from the native terminal — keystrokes
would reach Claude instead of the editor. The check must also cover
RichTextFX's `GenericStyledArea`.

This is the only hunk outside `ui/explorer/`.

## Data flow

| Input | Effect |
|---|---|
| Keystroke | `CLEAN → DIRTY`; arm 2s debounce; arm 150ms re-highlight |
| Debounce / blur / tab-switch / scene-detach | `DIRTY → SAVING → CLEAN`; capture new stamp; refresh diff overlay |
| Poll tick | stamp changed + `CLEAN` → reload; stamp changed + `DIRTY` → `CONFLICT` |
| Conflict choice | `keepMine()` → force write; `takeDisk()` → reload, discard buffer |

`CONFLICT` and `ERROR` both disarm auto-save for that file; no write
happens until the user resolves it or edits again.

Reload preserves the caret paragraph/column (clamped to the new length)
and re-runs the lexer. The search-match style layer is dropped on the
first edit — it is a load-time artifact of "open from search result" and
re-deriving it during typing would fight the caret.

## Error handling

- **Write failure** (read-only file, permissions, disk full) → `ERROR`,
  banner `Could not save <name>: <reason>`, `LOG.WARNING` with the
  exception, buffer retained, retry on the next edit or explicit save.
- **`poll()` throws `IOException`** (file deleted underneath) → banner
  `File no longer on disk — keep mine / close tab`. Deletion never
  triggers a silent recreate.
- **Truncated / unreadable buffers** are never editable; the exclusion is
  enforced at the single place the tab is built.

Per AGENTS.md: all I/O runs on the viewer's executor, never the FX
thread, and every completion path — success, failure, and early
returns — clears `SAVING` and updates the chip.

## Testing

`FileEditSessionTest` against a JUnit temp dir, FX-free like
`SyntaxHighlighterTest`:

- dirty → flush writes the exact bytes
- flush is idempotent when clean
- external change while clean → `RELOAD`
- external change while dirty → `CONFLICT` **and no write**
- `keepMine` after conflict writes and clears the conflict
- `takeDisk` discards the buffer
- write failure on a read-only file → `ERROR` without losing the buffer

The debounce takes an injected scheduler so tests drive time directly
rather than sleeping.

The FX layer (chip states, tab dot, banner wiring) gets no automated
coverage, consistent with the rest of `ui/`, which has no headless-FX
harness. Manual verification: edit a file, confirm the chip cycles and
the bytes land; let Claude edit an open clean file and confirm the tab
reloads; edit a file Claude then changes and confirm the conflict banner.

## Interaction with in-flight work

`main` carries a design and plan for the terminal-api abstraction
(Claude/Terminal sub-tab split). That work introduces an
`app.drydock.terminal.api` package and rewrites `OpenSessionTab`,
`TerminalBridge`, `GhosttyKeyTranslator` and `DrydockTerminalHost`. It
adds no Gradle modules and touches nothing under `ui/explorer/`.

Decision 6 (Explorer-owned lifecycle) exists to keep this branch nearly
conflict-free against it: the sole shared hunk is the one-line
`onFocusOwnerChanged` fix, which the terminal split needs regardless.

## Out of scope

- Incremental re-highlighting. Full-file lexing on a 150ms debounce is
  adequate below the existing 2 MB truncation limit.
- Creating, renaming, or deleting files from the Explorer.
- Multi-cursor editing, formatting, refactoring, completion.
- Editing files outside the session's search root.
