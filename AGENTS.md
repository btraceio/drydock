# Agent guidelines for this repository

## Blocking work is async, with progress indication

Never run blocking operations on the JavaFX Application Thread. This covers
process spawns (`git`, `gh`, `claude`), filesystem I/O (state/annotation
persistence, directory/transcript existence probes, worktree scans), and
network calls.

- Run the work on a background executor (`CompletableFuture` + the owning
  service's executor, or a virtual thread) and hop back with
  `Platform.runLater` only to touch UI.
- Every user-triggered async operation must show progress immediately:
  a busy modal (`MainWorkspace.busyModal`), a placeholder state
  ("Starting...", "Closing…", `showCreating()`, `showHandoffRunning`), or a
  disabled control with a progress label. The click must visibly do
  something before the result arrives.
- Every completion path — success, error, AND early return — must clear the
  progress state; never leave a spinner or busy modal stranded.
- Services that write files from a background thread must expose a flush
  (see `AnnotationStore.flushPendingSaves`) so tests and shutdown do not
  race pending writes.
