# Editable Explorer files with auto-save

Date: 2026-07-21
Branch: `feat/editor`
Status: approved, ready for planning (revised after adversarial review)

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
   close, and application shutdown. `⌘S` forces an immediate flush.
2. **External changes** — reload if the buffer is clean, block if it is
   dirty. A clean tab silently adopts disk content (Claude's edits land
   in the viewer instead of going stale); a dirty tab stops auto-saving
   and shows a keep-mine/reload banner.
3. **Editability scope** — a file is editable only if it loaded whole and
   decoded as valid UTF-8. Truncated, unreadable, binary and
   invalid-UTF-8 buffers stay read-only.
4. **Status surface** — breadcrumb chip (`editable` / `unsaved` /
   `saved ✓`) plus a `•` dot on the file tab while dirty. Exceptional
   states use a banner row.
5. **Re-highlighting** — debounced (~150ms) while typing; the diff
   overlay is invalidated and refreshed after a save, on its own
   coalescing window.
6. **Lifecycle ownership** — scene detach drives the common teardown
   paths; shutdown needs an explicit flush call chain (see below).

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
| `flush()` | `CompletableFuture<Void>`; writes immediately, cancels the debounce. |
| `flushBlocking(Duration)` | Awaits the write; the shutdown path's entry point. |
| `poll()` | Compares the current stamp to disk. Clean + changed → `RELOAD`; dirty + changed → `CONFLICT`. No-op while `SAVING`. |
| `keepMine()` | Force-writes the buffer, adopts the resulting stamp. |
| `takeDisk()` | Discards the buffer, reports the disk content for reload. |

Outcomes are reported through a listener interface the viewer implements;
the viewer hops to the FX thread.

**Concurrency invariant.** The executor is *single-threaded*. Every write,
stamp capture and `poll()` runs on it, so a write and the stamp capture
that follows it cannot interleave with a poll — that serialization is
what stops the session from mistaking its own write for an external one.
`poll()` additionally returns immediately while `SAVING`.

Writes go directly through `Files.writeString`, **not** temp-file +
`ATOMIC_MOVE`. Atomic replace would reset permissions and swap the inode
under anything watching the file; for in-place edits of tracked source
files in a git worktree the direct write is the correct trade.

### Load result and eligibility

`FileViewer.readFile` currently returns a bare `String` with
`… (truncated: …)` or `Could not read <file>: …` appended for the
degraded cases. It is replaced by a record carrying the text plus the
structural facts: whether the file was truncated, whether it decoded
cleanly, and the dominant line terminator. **Eligibility is never decided
by sniffing the buffer for marker text** — a source file can legitimately
contain those strings.

Decoding uses a strict `CharsetDecoder` rather than `new String(bytes,
UTF_8)`. Lenient decoding replaces every invalid byte with U+FFFD, and
writing that back destroys the file; a decode failure, or any NUL byte in
the content, marks the tab read-only.

The dominant line terminator is recorded at load and reapplied on write,
so editing a CRLF file does not rewrite every line and turn a one-line
touch-up into a whole-file diff. Files with mixed terminators are
read-only.

### `FileViewer` changes

The only substantially edited existing file:

- Editable `CodeArea`s for eligible tabs; ineligible buffers keep
  `setEditable(false)` and the `read-only` chip.
- `textProperty` listener → `session.edit(text)`, mark dirty, arm the
  re-highlight debounce. The listener is **suppressed around every
  programmatic `replaceText`** (initial load and reload), which would
  otherwise mark a file dirty that the user never touched — and, on the
  reload path, immediately re-arm a write of the content that was just
  superseded.
- Reload calls `UndoManager.forgetHistory()`. Without it, one ⌘Z after a
  silent reload restores the stale buffer, which auto-save then writes
  over Claude's edits.
- Chip, per-tab dirty dot, and a conflict/error banner row alongside the
  existing `diffBanner` in `topBox`.
- Owns the single-threaded daemon `ScheduledExecutorService` driving the
  save debounce and the ~1.5s disk poll across open tabs.

### Diff overlay refresh

`ChangedLineService.changedSet` memoizes per `(root, scope, base)` and
only drops entries via `invalidate(Path)`, so re-calling
`DiffOverlay.changedSet()` after a save returns the same cached future
and the green markers never move. `DiffOverlay` gains an `invalidate()`
that calls through to the service; the viewer calls it before refreshing.

That cache is shared with the Review tab, so each invalidation costs both
views a fresh `git diff`. The refresh therefore runs on its own trailing
coalescing window (~2s) rather than once per save, per AGENTS.md's
"coalesce N async completions into one rebuild".

### Lifecycle

Scene detach is the mechanism behind three of decision 1's forced
flushes. `OpenSessionTab.showSubTab` swaps the tab's center node, so
leaving the Explorer removes the viewer from the scene graph and nulls
its `sceneProperty`; closing the session tab does the same. Re-attaching
restarts the poller. No cascade from `OpenSessionTab` is needed for these.

Shutdown is different and needs a real call chain, because
`DrydockApplication.stop()` closes a flat list of services it holds
directly and has no reference to any `FileViewer` — those are created
lazily inside a closure in `MainWorkspace.createOpenSessionTab`. The
chain is:

`DrydockApplication.stop()` → `MainWorkspace.flushExplorerEdits()` →
each registered `SessionExplorerView` → `FileViewer` →
`FileEditSession.flushBlocking(timeout)`

It must **block** with a bounded timeout. The executor's threads are
daemons, so a fire-and-forget flush is killed mid-write at JVM exit —
exactly the failure `AnnotationStore.flushPendingSaves` exists to
prevent. `stop()` invokes it through the existing `closeQuietly` wrapper
so a failure cannot skip the later closes.

### Focus fix (`MainWorkspace`)

`MainWorkspace.onFocusOwnerChanged` releases the terminal's AppKit
first-responder only for `TextInputControl`. RichTextFX's `CodeArea` is a
`Region`, not a `TextInputControl`, so focusing the code area does not
currently hand the keyboard back from the native terminal — keystrokes
would reach Claude instead of the editor. The check must also cover
RichTextFX's `GenericStyledArea`.

## Data flow

| Input | Effect |
|---|---|
| Keystroke | `CLEAN → DIRTY`; arm 2s debounce; arm 150ms re-highlight |
| Debounce / blur / tab-switch / scene-detach / ⌘S | `DIRTY → SAVING → CLEAN`; capture new stamp; schedule diff refresh |
| Poll tick | stamp changed + `CLEAN` → reload; stamp changed + `DIRTY` → `CONFLICT`; skipped while `SAVING` |
| Conflict choice | `keepMine()` → force write; `takeDisk()` → reload, discard buffer |

`CONFLICT` and `ERROR` both disarm auto-save for that file; no write
happens until the user resolves it or edits again.

Reload preserves the caret paragraph/column (clamped to the new length),
re-runs the lexer, and forgets undo history. The search-match style layer
is dropped on the first edit — it is a load-time artifact of "open from
search result" and re-deriving it during typing would fight the caret.

## Error handling

- **Write failure** (read-only file, permissions, disk full) → `ERROR`,
  banner `Could not save <name>: <reason>`, `LOG.WARNING` with the
  exception, buffer retained, retry on the next edit or ⌘S.
- **`poll()` throws `IOException`** (file deleted underneath) → banner
  `File no longer on disk — keep mine / close tab`. Deletion never
  triggers a silent recreate.
- **Ineligible buffers** are never editable; the exclusion is enforced at
  the single place the tab is built, from the load result's structural
  flags.

Per AGENTS.md: all I/O runs on the viewer's executor, never the FX
thread, and every completion path — success, failure, and early
returns — clears `SAVING` and updates the chip.

## Testing

`FileEditSessionTest` against a JUnit temp dir, FX-free like
`SyntaxHighlighterTest`:

- dirty → flush writes the exact bytes
- **a freshly loaded buffer is `CLEAN`** (programmatic load must not dirty)
- **byte-fidelity round-trip**: BOM, CRLF, and trailing-newline files
  survive an edit-and-save with only the edited region changed
- flush is idempotent when clean
- external change while clean → `RELOAD`
- external change while dirty → `CONFLICT` **and no write**
- `poll()` during `SAVING` does not report a conflict against the
  session's own write
- `keepMine` after conflict writes and clears the conflict
- `takeDisk` discards the buffer
- write failure on a read-only file → `ERROR` without losing the buffer
- a file containing invalid UTF-8 or a NUL byte loads read-only
- `flushBlocking` returns only after the bytes are on disk

The debounce takes an injected scheduler so tests drive time directly
rather than sleeping.

The FX layer (chip states, tab dot, banner wiring, undo reset) gets no
automated coverage, consistent with the rest of `ui/`, which has no
headless-FX harness. Manual verification: edit a file and confirm the
chip cycles and the bytes land; let Claude edit an open clean file and
confirm the tab reloads and the green markers move; edit a file Claude
then changes and confirm the conflict banner; ⌘Z after a reload must not
resurrect the stale buffer.

## Known limitations

- The same absolute path open in two session tabs gives two independent
  auto-savers that observe each other's writes. Self-correcting through
  clean-reload, but the two buffers can ping-pong while both are dirty.
- Change detection is last-modified-time plus size. A same-size
  external edit within the filesystem's timestamp granularity is missed;
  APFS's nanosecond timestamps make this vanishingly unlikely.

## Interaction with in-flight work

`main` carries a design and plan for the terminal-api abstraction
(Claude/Terminal sub-tab split). That work introduces an
`app.drydock.terminal.api` package and rewrites `OpenSessionTab`,
`TerminalBridge`, `GhosttyKeyTranslator` and `DrydockTerminalHost`. It
adds no Gradle modules and touches nothing under `ui/explorer/`.

This branch's footprint outside `ui/explorer/` is three small hunks, none
in the files that rewrite most heavily:

- `MainWorkspace.onFocusOwnerChanged` — the one-line focus fix, which the
  terminal split needs regardless.
- `MainWorkspace` — viewer registration in the existing Explorer factory,
  plus `flushExplorerEdits()`.
- `DrydockApplication.stop()` — one `closeQuietly` line.

`OpenSessionTab` is untouched.

## Out of scope

- Incremental re-highlighting. Full-file lexing on a 150ms debounce is
  adequate below the existing 2 MB truncation limit.
- Creating, renaming, or deleting files from the Explorer.
- Multi-cursor editing, formatting, refactoring, completion.
- Editing files outside the session's search root.
- Encoding conversion. Non-UTF-8 files are read-only, not transcoded.
