# Editable Explorer Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Session Explorer's code viewer editable, auto-saving quick touch-ups to disk while Claude concurrently edits the same worktree.

**Architecture:** Two new FX-free classes carry all the logic — `FileContent` (loads a file and decides structurally whether it is safe to edit) and `FileEditSession` (per-file save state machine, debounce, disk-change polling, all I/O on one single-threaded executor). `FileViewer` keeps its existing shape and gains the editable wiring, status chip, and conflict banner. Everything outside `ui/explorer/` is three shallow hunks.

**Tech Stack:** Java 25 (JavaFX 26), RichTextFX 0.11.7 (`CodeArea`), JUnit 5, Gradle.

**Spec:** `docs/superpowers/specs/2026-07-21-explorer-editable-files-design.md`

## Global Constraints

- Never block the JavaFX Application Thread. All file I/O runs on the viewer's executor; hop back with `Platform.runLater` only to touch UI. (AGENTS.md)
- Never inline fully-qualified Java class names; use imports. Sole exception: same-name collisions from different packages. (AGENTS.md)
- Every completion path — success, error, and early return — must clear the progress/`SAVING` state. Never leave a spinner or busy state stranded. (AGENTS.md)
- Anything with a background executor implements a close/flush that shutdown actually calls. (AGENTS.md)
- Debounce keystroke-driven rebuilds (~150 ms, as `SearchRail` does) and coalesce N async completions into one rebuild. (AGENTS.md)
- The Gradle build stays a single module: `settings.gradle.kts` remains `include(":app")`. Do not add subprojects or `module-info.java`.
- Test command: `./gradlew :app:test --tests '<pattern>'`. Compile check: `./gradlew :app:compileJava`.
- Commit messages use imperative sentence style (e.g. "Add strict UTF-8 file loading"), **not** `feat:`/`fix:` prefixes, matching this repo's history. Every commit message ends with the `Co-Authored-By` trailer shown in the commit steps.
- Files over 2 MB are truncated on load (existing `FileViewer.MAX_FILE_BYTES`); that limit does not change.
- Auto-save debounce is 2 s. Disk poll interval is 1.5 s. Re-highlight debounce is 150 ms. Diff-refresh coalescing window is 2 s.

---

### Task 1: `FileContent` — structural load and edit-eligibility

A file is editable only if it loaded whole and decoded as valid UTF-8. This task builds the loader that decides that from the bytes, so no later code has to guess from a file extension or sniff marker text out of the buffer.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/explorer/FileContent.java`
- Test: `app/src/test/java/app/drydock/ui/explorer/FileContentTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `record FileContent(String text, boolean truncated, boolean decoded, FileContent.Terminator terminator)`
  - `enum FileContent.Terminator { LF, CRLF, MIXED, NONE }`
  - `boolean FileContent.editable()`
  - `String FileContent.toDiskText(String editorText)`
  - `static FileContent FileContent.load(Path file, long maxBytes) throws IOException`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/explorer/FileContentTest.java`:

```java
package app.drydock.ui.explorer;

import app.drydock.ui.explorer.FileContent.Terminator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure file-loading assertions -- no FX toolkit needed. */
class FileContentTest {

    private static final long MAX = 1024;

    @Test
    void plainUtf8FileIsEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "class Hello {}\n");

        FileContent content = FileContent.load(file, MAX);

        assertEquals("class Hello {}\n", content.text());
        assertFalse(content.truncated());
        assertTrue(content.decoded());
        assertEquals(Terminator.LF, content.terminator());
        assertTrue(content.editable());
    }

    @Test
    void crlfFileIsNormalisedForTheEditorAndRestoredOnWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("windows.txt");
        Files.write(file, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));

        FileContent content = FileContent.load(file, MAX);

        assertEquals("a\nb\n", content.text(), "editor sees LF");
        assertEquals(Terminator.CRLF, content.terminator());
        assertTrue(content.editable());
        assertEquals("a\r\nX\r\n", content.toDiskText("a\nX\n"), "write restores CRLF");
    }

    @Test
    void lfFileWritesBackUnchanged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("unix.txt");
        Files.writeString(file, "a\nb\n");

        FileContent content = FileContent.load(file, MAX);

        assertEquals("a\nX\n", content.toDiskText("a\nX\n"));
    }

    @Test
    void mixedTerminatorsAreNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mixed.txt");
        Files.write(file, "a\r\nb\nc\n".getBytes(StandardCharsets.UTF_8));

        FileContent content = FileContent.load(file, MAX);

        assertEquals(Terminator.MIXED, content.terminator());
        assertFalse(content.editable(), "rewriting would touch every line");
    }

    @Test
    void invalidUtf8IsNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("latin1.txt");
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});

        FileContent content = FileContent.load(file, MAX);

        assertFalse(content.decoded());
        assertFalse(content.editable(), "lossy decode would destroy the file");
    }

    @Test
    void nulByteMeansBinaryAndIsNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("blob.bin");
        Files.write(file, new byte[] {'M', 'Z', 0, 'x'});

        FileContent content = FileContent.load(file, MAX);

        assertFalse(content.decoded());
        assertFalse(content.editable());
    }

    @Test
    void truncatedFileIsNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("huge.txt");
        Files.writeString(file, "x".repeat((int) MAX + 50));

        FileContent content = FileContent.load(file, MAX);

        assertTrue(content.truncated());
        assertFalse(content.editable(), "saving would delete the untruncated remainder");
        assertTrue(content.text().contains("truncated"), "the user must see why it is read-only");
    }

    @Test
    void emptyFileIsEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.txt");
        Files.writeString(file, "");

        FileContent content = FileContent.load(file, MAX);

        assertEquals(Terminator.NONE, content.terminator());
        assertTrue(content.editable());
    }

    @Test
    void missingFileThrows(@TempDir Path dir) {
        assertThrows(IOException.class, () -> FileContent.load(dir.resolve("nope.txt"), MAX));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.FileContentTest'`
Expected: FAIL — compilation error, `cannot find symbol: class FileContent`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/app/drydock/ui/explorer/FileContent.java`:

```java
package app.drydock.ui.explorer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One loaded file's text plus the structural facts that decide whether it
 * may be edited (spec decision 3). Eligibility is derived from the BYTES at
 * load time -- never from the file extension, and never by sniffing the
 * buffer for marker text, since a source file can legitimately contain the
 * words this class puts in a truncation notice.
 *
 * <p>Three things make a buffer unsafe to write back:</p>
 * <ul>
 *   <li><b>Truncated</b> -- saving would delete everything past the limit.</li>
 *   <li><b>Undecodable</b> -- a lenient UTF-8 decode turns every invalid
 *       byte into U+FFFD, so writing the buffer back destroys a binary or
 *       non-UTF-8 file. Decoding is therefore strict, and any NUL byte
 *       marks the content binary outright.</li>
 *   <li><b>Mixed line terminators</b> -- {@link org.fxmisc.richtext.CodeArea}
 *       works in LF, so a rewrite would normalise every line and turn a
 *       one-line touch-up into a whole-file diff.</li>
 * </ul>
 *
 * <p>{@link #text()} is always LF-normalised for the editor;
 * {@link #toDiskText(String)} restores the file's own terminator on the way
 * back out.</p>
 */
record FileContent(String text, boolean truncated, boolean decoded, Terminator terminator) {

    /** The file's dominant line terminator, captured at load and reapplied on write. */
    enum Terminator { LF, CRLF, MIXED, NONE }

    /** Whether this buffer may be written back to disk without losing or rewriting content. */
    boolean editable() {
        return decoded && !truncated && terminator != Terminator.MIXED;
    }

    /** Converts the editor's LF text back to the file's own terminator. */
    String toDiskText(String editorText) {
        return terminator == Terminator.CRLF ? editorText.replace("\n", "\r\n") : editorText;
    }

    /**
     * Reads {@code file}, truncating past {@code maxBytes}. Throws only when
     * the file cannot be read at all; an unreadable-content file (binary,
     * bad encoding) loads successfully as a non-{@link #editable()} buffer
     * so the viewer can still show it.
     */
    static FileContent load(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        boolean truncated = size > maxBytes;
        byte[] bytes;
        if (truncated) {
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes((int) maxBytes);
            }
        } else {
            bytes = Files.readAllBytes(file);
        }

        boolean decoded = !containsNul(bytes) && decodesAsUtf8(bytes);
        // Lenient decode for DISPLAY only; an undecodable buffer is never
        // written back, so the U+FFFD replacements cannot reach the file.
        String raw = new String(bytes, StandardCharsets.UTF_8);
        Terminator terminator = detectTerminator(raw);
        String text = terminator == Terminator.CRLF ? raw.replace("\r\n", "\n") : raw;
        if (truncated) {
            text = text + "\n\n… (truncated: file exceeds " + (maxBytes / (1024 * 1024)) + " MB)\n";
        }
        return new FileContent(text, truncated, decoded, terminator);
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean decodesAsUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Terminator detectTerminator(String raw) {
        boolean crlf = false;
        boolean loneLf = false;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                if (i > 0 && raw.charAt(i - 1) == '\r') {
                    crlf = true;
                } else {
                    loneLf = true;
                }
            }
        }
        if (crlf && loneLf) {
            return Terminator.MIXED;
        }
        if (crlf) {
            return Terminator.CRLF;
        }
        return loneLf ? Terminator.LF : Terminator.NONE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.FileContentTest'`
Expected: PASS — 9 tests.

Note on the truncation test: a truncated buffer is decoded as UTF-8 at an arbitrary byte boundary, which can split a multi-byte character and make `decoded` false. That is harmless — `editable()` is already false because `truncated` is true.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/FileContent.java \
        app/src/test/java/app/drydock/ui/explorer/FileContentTest.java
git commit -m "$(cat <<'EOF'
Add structural file loading for the Explorer viewer

FileContent decides edit-eligibility from the bytes: strict UTF-8
decoding, a NUL-byte binary check, the existing truncation limit, and the
file's dominant line terminator, which is normalised to LF for the editor
and restored on write. Nothing downstream has to guess from an extension
or sniff marker text out of the buffer.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `FileEditSession` — save state machine

The whole feature's logic, FX-free and fully tested: debounced auto-save, forced flush, disk-change polling, and the clean-reload/dirty-conflict split from spec decision 2.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/explorer/FileEditSession.java`
- Test: `app/src/test/java/app/drydock/ui/explorer/FileEditSessionTest.java`

**Interfaces:**
- Consumes: `FileContent` (Task 1) — `text()`, `editable()`, `toDiskText(String)`.
- Produces:
  - `enum FileEditSession.State { CLEAN, DIRTY, SAVING, CONFLICT, ERROR }`
  - `enum FileEditSession.PollOutcome { UNCHANGED, RELOAD, CONFLICT, MISSING }`
  - `record FileEditSession.PollResult(PollOutcome outcome, String text)`
  - `FileEditSession(Path file, FileContent initial, ScheduledExecutorService executor, Duration debounce, long maxBytes)` — throws `IllegalArgumentException` for a non-`editable()` buffer, so callers must gate on `FileContent.editable()` first
  - `State state()`, `void edit(String text)`, `CompletableFuture<Void> flush()`,
    `void flushBlocking(Duration timeout)`, `CompletableFuture<PollResult> poll()`,
    `CompletableFuture<Void> keepMine()`, `CompletableFuture<String> takeDisk()`,
    `void setOnStateChanged(Consumer<State>)`, `void setOnSaveFailed(Consumer<IOException>)`,
    `IOException lastError()`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/explorer/FileEditSessionTest.java`:

```java
package app.drydock.ui.explorer;

import app.drydock.ui.explorer.FileEditSession.PollOutcome;
import app.drydock.ui.explorer.FileEditSession.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure save-state-machine assertions against a temp dir -- no FX toolkit needed. */
class FileEditSessionTest {

    private static final long MAX = 1024 * 1024;
    /**
     * Long enough that the debounce NEVER fires on its own during a test.
     * Every test but {@link #debounceSavesWithoutAnExplicitFlush} drives the
     * write explicitly; a short debounce here would race an auto-save
     * against the external edit the conflict tests are setting up.
     */
    private static final Duration IDLE_DEBOUNCE = Duration.ofSeconds(30);
    private static final Duration SHORT_DEBOUNCE = Duration.ofMillis(40);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "file-edit-test");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private FileEditSession sessionFor(Path file) throws IOException {
        return new FileEditSession(file, FileContent.load(file, MAX), executor, IDLE_DEBOUNCE);
    }

    /** Bumps mtime past any filesystem granularity so a same-size edit is still observed. */
    private static void writeExternally(Path file, String text) throws IOException {
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
    }

    @Test
    void freshlyLoadedBufferIsClean(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");

        FileEditSession session = sessionFor(file);

        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void flushWritesTheExactBytes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("two\n");
        assertEquals(State.DIRTY, session.state());
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals("two\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void flushIsIdempotentWhenClean(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.flush().get(5, TimeUnit.SECONDS);
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals("one\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void debounceSavesWithoutAnExplicitFlush(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, SHORT_DEBOUNCE);
        CountDownLatch clean = new CountDownLatch(1);
        session.setOnStateChanged(state -> {
            if (state == State.CLEAN) {
                clean.countDown();
            }
        });

        session.edit("auto\n");

        assertTrue(clean.await(5, TimeUnit.SECONDS), "debounce should have saved");
        assertEquals("auto\n", Files.readString(file));
    }

    @Test
    void crlfFileKeepsItsTerminatorOnSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.txt");
        Files.write(file, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
        FileEditSession session = sessionFor(file);

        session.edit("a\nX\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals("a\r\nX\r\n", Files.readString(file));
    }

    @Test
    void externalChangeWhileCleanReportsReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        writeExternally(file, "claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.RELOAD, result.outcome());
        assertEquals("claude\n", result.text());
    }

    @Test
    void externalChangeWhileDirtyReportsConflictAndDoesNotWrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.CONFLICT, result.outcome());
        assertEquals(State.CONFLICT, session.state());
        // Auto-save must be disarmed: an explicit flush writes nothing either.
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("claude\n", Files.readString(file), "conflict must not overwrite");
    }

    @Test
    void ownWriteIsNotMistakenForAnExternalChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        session.flush().get(5, TimeUnit.SECONDS);
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.UNCHANGED, result.outcome());
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void keepMineWritesAndClearsTheConflict(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        session.poll().get(5, TimeUnit.SECONDS);
        session.keepMine().get(5, TimeUnit.SECONDS);

        assertEquals("mine\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void takeDiskDiscardsTheBuffer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        session.poll().get(5, TimeUnit.SECONDS);
        String restored = session.takeDisk().get(5, TimeUnit.SECONDS);

        assertEquals("claude\n", restored);
        assertEquals(State.CLEAN, session.state());
        assertEquals("claude\n", Files.readString(file));
    }

    @Test
    void deletedFileReportsMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        Files.delete(file);
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.MISSING, result.outcome());
    }

    @Test
    void writeFailureEntersErrorWithoutLosingTheBuffer(@TempDir Path dir) throws Exception {
        Path readOnlyDir = Files.createDirectory(dir.resolve("locked"));
        Path file = readOnlyDir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        assertTrue(file.toFile().setWritable(false), "test needs a non-writable file");

        session.edit("mine\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals(State.ERROR, session.state());
        assertNotNull(session.lastError());
        assertEquals("one\n", Files.readString(file), "failed write must not truncate the file");

        // The buffer survives: making the file writable and retrying saves it.
        assertTrue(file.toFile().setWritable(true));
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("mine\n", Files.readString(file));
    }

    @Test
    void flushBlockingReturnsOnlyAfterTheBytesAreOnDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("blocking\n");
        session.flushBlocking(TIMEOUT);

        assertEquals("blocking\n", Files.readString(file));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.FileEditSessionTest'`
Expected: FAIL — compilation error, `cannot find symbol: class FileEditSession`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/app/drydock/ui/explorer/FileEditSession.java`:

```java
package app.drydock.ui.explorer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * One open file's save state machine (spec: "Editable Explorer files with
 * auto-save"). Owns every byte this feature reads or writes; holds no
 * JavaFX types, so it is unit-testable the way {@link SyntaxHighlighter}
 * is, and the viewer stays a thin layer over it.
 *
 * <p><b>Concurrency invariant.</b> Every write, stamp capture and
 * {@link #poll()} runs on the single-threaded {@code executor} the viewer
 * owns. That serialization is what stops a session mistaking its own write
 * for an external one: the stamp capture that follows a write cannot
 * interleave with a poll. {@link #poll()} additionally returns immediately
 * while {@link State#SAVING}.</p>
 *
 * <p>Writes go directly through {@link Files#write} rather than
 * temp-file + {@code ATOMIC_MOVE}: atomic replace would reset permissions
 * and swap the inode under anything watching the file, and these are
 * in-place edits of tracked source files in a git worktree.</p>
 */
final class FileEditSession {

    /** {@link #CONFLICT} and {@link #ERROR} both disarm auto-save until the user acts. */
    enum State { CLEAN, DIRTY, SAVING, CONFLICT, ERROR }

    enum PollOutcome { UNCHANGED, RELOAD, CONFLICT, MISSING }

    /** {@code text} carries the disk content for {@link PollOutcome#RELOAD}, else null. */
    record PollResult(PollOutcome outcome, String text) { }

    /** Identity of the file as this session last saw it (spec: change detection is mtime + size). */
    private record DiskStamp(FileTime modified, long size) { }

    private final Path file;
    private final FileContent content;
    private final ScheduledExecutorService executor;
    private final Duration debounce;

    /** Guarded by the executor's single thread, except the volatile reads exposed to the FX thread. */
    private volatile State state = State.CLEAN;
    private volatile IOException lastError;
    private String pendingText;
    private DiskStamp stamp;
    private ScheduledFuture<?> armedSave;

    private Consumer<State> onStateChanged = state -> { };
    private Consumer<IOException> onSaveFailed = error -> { };

    FileEditSession(Path file, FileContent content, ScheduledExecutorService executor, Duration debounce) {
        this.file = file;
        this.content = content;
        this.executor = executor;
        this.debounce = debounce;
        this.pendingText = content.text();
        this.stamp = readStampQuietly();
    }

    /** Invoked on the executor thread; the viewer hops to the FX thread itself. */
    void setOnStateChanged(Consumer<State> handler) {
        this.onStateChanged = handler == null ? state -> { } : handler;
    }

    /** Invoked on the executor thread; the viewer hops to the FX thread itself. */
    void setOnSaveFailed(Consumer<IOException> handler) {
        this.onSaveFailed = handler == null ? error -> { } : handler;
    }

    State state() {
        return state;
    }

    /** The failure behind {@link State#ERROR}, for the viewer's banner. */
    IOException lastError() {
        return lastError;
    }

    Path file() {
        return file;
    }

    /**
     * Records an edit and (re)arms the debounce. A file in {@link
     * State#CONFLICT} stays there -- the user must resolve it first -- but
     * the newest text is still kept, so {@link #keepMine()} writes what
     * they actually have.
     */
    void edit(String text) {
        synchronized (this) {
            pendingText = text;
            if (armedSave != null) {
                armedSave.cancel(false);
            }
            if (state == State.CONFLICT) {
                return;
            }
            setState(State.DIRTY);
            armedSave = executor.schedule(this::writeIfDirty, debounce.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Writes immediately, cancelling any armed debounce. Completes normally
     * even when the write fails -- failure is reported through {@link
     * #setOnSaveFailed} and {@link State#ERROR}, so a forced flush on a
     * teardown path can never propagate an exception into it.
     */
    CompletableFuture<Void> flush() {
        synchronized (this) {
            if (armedSave != null) {
                armedSave.cancel(false);
                armedSave = null;
            }
        }
        return CompletableFuture.runAsync(this::writeIfDirty, executor);
    }

    /**
     * Awaits {@link #flush()}, bounded. The shutdown path's entry point:
     * the viewer's executor threads are daemons, so a fire-and-forget flush
     * is killed mid-write at JVM exit (the failure {@code
     * AnnotationStore.flushPendingSaves} exists to prevent).
     */
    void flushBlocking(Duration timeout) {
        try {
            flush().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            // Reported through onSaveFailed already; shutdown must proceed.
        }
    }

    /**
     * Compares the file's current identity against the stamp this session
     * last adopted. Clean + changed means Claude edited a file we are not
     * holding edits for, so the viewer can silently reload; dirty + changed
     * is a genuine conflict and disarms auto-save.
     */
    CompletableFuture<PollResult> poll() {
        return CompletableFuture.supplyAsync(() -> {
            if (state == State.SAVING) {
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
            DiskStamp current;
            try {
                current = readStamp();
            } catch (IOException e) {
                // Deleted underneath us, or unreadable: either way the viewer
                // must ask rather than silently recreate the file.
                return new PollResult(PollOutcome.MISSING, null);
            }
            if (current.equals(stamp)) {
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
            if (state == State.DIRTY || state == State.CONFLICT) {
                setState(State.CONFLICT);
                return new PollResult(PollOutcome.CONFLICT, null);
            }
            try {
                String text = FileContent.load(file, Long.MAX_VALUE).text();
                stamp = current;
                return new PollResult(PollOutcome.RELOAD, text);
            } catch (IOException e) {
                return new PollResult(PollOutcome.MISSING, null);
            }
        }, executor);
    }

    /** Conflict resolution: the user's buffer wins; write it and adopt the resulting stamp. */
    CompletableFuture<Void> keepMine() {
        return CompletableFuture.runAsync(() -> {
            setState(State.DIRTY);
            writeIfDirty();
        }, executor);
    }

    /** Conflict resolution: disk wins; discard the buffer and hand the disk text back for reload. */
    CompletableFuture<String> takeDisk() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String text = FileContent.load(file, Long.MAX_VALUE).text();
                synchronized (this) {
                    pendingText = text;
                }
                stamp = readStampQuietly();
                setState(State.CLEAN);
                return text;
            } catch (IOException e) {
                fail(e);
                return null;
            }
        }, executor);
    }

    // ---- Executor-thread internals -----------------------------------------

    private void writeIfDirty() {
        String text;
        synchronized (this) {
            armedSave = null;
            // CLEAN: nothing to write. SAVING: a write is already in flight.
            // CONFLICT: disarmed until the user resolves it. ERROR is the
            // exception -- an explicit flush after a failed write IS the
            // retry, so it must be allowed through.
            if (state != State.DIRTY && state != State.ERROR) {
                return;
            }
            text = pendingText;
        }
        setState(State.SAVING);
        try {
            Files.write(file, content.toDiskText(text).getBytes(StandardCharsets.UTF_8));
            // Same executor task as the write: no poll can observe the gap.
            stamp = readStamp();
            lastError = null;
            setState(State.CLEAN);
        } catch (IOException e) {
            fail(e);
        }
    }

    private void fail(IOException e) {
        lastError = e;
        setState(State.ERROR);
        onSaveFailed.accept(e);
    }

    private void setState(State next) {
        if (state != next) {
            state = next;
            onStateChanged.accept(next);
        }
    }

    private DiskStamp readStamp() throws IOException {
        return new DiskStamp(Files.getLastModifiedTime(file), Files.size(file));
    }

    private DiskStamp readStampQuietly() {
        try {
            return readStamp();
        } catch (IOException e) {
            return new DiskStamp(FileTime.fromMillis(0), -1);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.FileEditSessionTest'`
Expected: PASS — 13 tests.

If `writeFailureEntersErrorWithoutLosingTheBuffer` fails because the test runs as a user who can write to read-only files (running as root), that test is environment-dependent; keep it and note the requirement rather than weakening it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/FileEditSession.java \
        app/src/test/java/app/drydock/ui/explorer/FileEditSessionTest.java
git commit -m "$(cat <<'EOF'
Add the Explorer file save state machine

FileEditSession owns every read and write behind editable Explorer files:
a debounced auto-save, a forced flush with a blocking variant for
shutdown, and mtime+size polling that reloads when the buffer is clean but
raises a conflict -- disarming auto-save -- when it is dirty. All of it is
serialized on one executor thread so a session cannot mistake its own
write for one of Claude's.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Route `FileViewer` through `FileContent`, and let `DiffOverlay` invalidate

Pure refactor plus one new method. No user-visible change yet: the viewer stays read-only, but it now loads through `FileContent` and knows per-tab whether the file *could* be edited, and the diff overlay can finally be told its cache is stale.

`ChangedLineService.changedSet` memoizes per `(root, scope, base)` and only drops entries via `invalidate(Path)`. Without this task, calling `DiffOverlay.changedSet()` after a save returns the same cached future and the green markers never move.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/DiffOverlay.java`
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java` (`readFile` at :246, `openFile` at :171)

**Interfaces:**
- Consumes: `FileContent.load(Path, long)`, `FileContent.editable()` (Task 1).
- Produces: `void DiffOverlay.invalidate()`; tab property key `"drydock.content"` holding the `FileContent` of each open tab.

- [ ] **Step 1: Add `DiffOverlay.invalidate()`**

In `app/src/main/java/app/drydock/ui/explorer/DiffOverlay.java`, add after `changedSet()`:

```java
    /**
     * Drops this checkout's cached changed-line maps so the next {@link
     * #changedSet()} re-runs git. Needed after the Explorer writes a file:
     * {@link ChangedLineService} memoizes per (root, scope, base) and would
     * otherwise keep handing back the pre-save map, leaving the green
     * markers frozen.
     *
     * <p>The cache is shared with the Review tab, so each invalidation
     * costs both views a fresh diff -- callers coalesce.</p>
     */
    public void invalidate() {
        changedLineService.invalidate(checkoutRoot);
    }
```

- [ ] **Step 2: Replace `readFile` with a `FileContent` load**

In `FileViewer.java`, delete the `readFile(Path)` method (:246-260) and replace it with:

```java
    /**
     * Loads {@code file} for display. Content problems (binary, bad
     * encoding, oversized) come back as a non-{@link FileContent#editable()}
     * buffer rather than an exception; only an unreadable file falls back to
     * the error placeholder, which is likewise never editable.
     */
    private FileContent loadFile(Path file) {
        try {
            return FileContent.load(file, MAX_FILE_BYTES);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not read " + file, e);
            return new FileContent("Could not read " + file + ": " + e.getMessage(),
                    false, false, FileContent.Terminator.NONE);
        }
    }
```

Remove the now-unused imports `java.nio.charset.StandardCharsets` and `java.nio.file.Files` if nothing else in the file uses them.

- [ ] **Step 3: Load through it in `openFile`**

In `FileViewer.openFile`, replace the virtual-thread body (`String text = readFile(file);` through the `Platform.runLater` block) with:

```java
        Thread.ofVirtual().start(() -> {
            FileContent content = loadFile(file);
            String text = content.text();
            SyntaxHighlighter.Language language =
                    SyntaxHighlighter.Language.fromFileName(file.getFileName().toString());
            var spans = SyntaxHighlighter.computeHighlighting(text, language);
            // Search-match highlighting is a SECOND style layer on top of
            // the lexer spans (handoff: "match highlight as a second style
            // layer"), merged by union so the token color survives.
            if (highlightQuery != null && !highlightQuery.isBlank()) {
                spans = spans.overlay(matchSpans(text, highlightQuery), (base, match) -> {
                    if (match.isEmpty()) {
                        return base;
                    }
                    List<String> merged = new ArrayList<>(base);
                    merged.addAll(match);
                    return merged;
                });
            }
            var styled = spans;
            Platform.runLater(() -> {
                tab.getProperties().put("drydock.content", content);
                area.replaceText(text);
                if (text.length() > 0) {
                    area.setStyleSpans(0, styled);
                }
                jumpToLine.ifPresent(line -> scrollTo(tab, line));
            });
        });
```

- [ ] **Step 4: Verify nothing regressed**

Run: `./gradlew :app:compileJava && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all existing tests pass. The viewer behaves exactly as before.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/DiffOverlay.java \
        app/src/main/java/app/drydock/ui/explorer/FileViewer.java
git commit -m "$(cat <<'EOF'
Load viewer files through FileContent; let DiffOverlay invalidate

The viewer now carries each tab's FileContent, so edit-eligibility is a
structural fact rather than something later code has to infer. DiffOverlay
gains invalidate(), without which a post-save refresh would return
ChangedLineService's memoized map and leave the green markers frozen.

No behaviour change yet: the viewer stays read-only.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Editable buffers, auto-save, and the status chip

The first task with a user-visible deliverable: you can type into an eligible file and it lands on disk. Includes the terminal-focus fix, without which every keystroke goes to Claude instead of the editor.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:1001-1005`
- Modify: `app/src/main/resources/app/drydock/ui/app.css`

**Interfaces:**
- Consumes: `FileEditSession` (Task 2) — constructor, `edit`, `flush`, `state`, `setOnStateChanged`, `setOnSaveFailed`, `lastError`; `FileContent.editable()` (Task 1).
- Produces: `void FileViewer.flushPendingEdits(Duration)` (used by Task 6); tab property key `"drydock.session"` holding each tab's `FileEditSession`.

- [ ] **Step 1: Add the executor, session map, and status chip fields**

In `FileViewer.java`, add these fields next to the existing `openFiles` map:

```java
    /** Auto-save debounce (spec decision 1: long enough to be ceremony-free, short enough to be safe). */
    private static final Duration SAVE_DEBOUNCE = Duration.ofSeconds(2);

    /**
     * ONE single-threaded executor for every open file's I/O. Single-threaded
     * is load-bearing, not incidental: it serializes each session's write
     * against its own stamp capture and polls (see FileEditSession's
     * concurrency invariant). Daemon so a missed close cannot hang the JVM --
     * the shutdown path still flushes explicitly (flushPendingEdits).
     */
    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "explorer-file-io");
        thread.setDaemon(true);
        return thread;
    });

    /** Edit sessions of the open editable tabs, keyed by absolute path (read-only tabs have none). */
    private final Map<Path, FileEditSession> sessions = new LinkedHashMap<>();

    /** Breadcrumb status chip: read-only / editable / unsaved / saved (spec decision 4). */
    private final Label statusChip = new Label("read-only");
```

Add the imports: `java.time.Duration`, `java.util.concurrent.Executors`, `java.util.concurrent.ScheduledExecutorService`, `javafx.animation.PauseTransition`, `javafx.util.Duration` — note the collision: `javafx.util.Duration` and `java.time.Duration` cannot both be imported. Import `java.time.Duration` and qualify the JavaFX one as `javafx.util.Duration` at its single use in the `PauseTransition` below (this is the documented same-name-different-package exception in AGENTS.md).

- [ ] **Step 2: Make eligible areas editable and wire the dirty listener**

**Leave `area.setEditable(false);` exactly where it is** — `CodeArea` defaults to *editable*, so removing that line would make binary, oversized and mixed-terminator files typable. Eligible tabs are opted back in by `attachEditing` below.

Add this in `openFile`'s `Platform.runLater` block, immediately before `replaceTextQuietly(tab, area, text);`:

```java
                if (content.editable()) {
                    attachEditing(tab, area, file, content);
                }
                updateStatusChip();
```

Then add the two methods:

```java
    /**
     * Turns a loaded tab into an editable one: an auto-saving {@link
     * FileEditSession} behind the {@link CodeArea}.
     *
     * <p>{@code suppressDirty} guards every PROGRAMMATIC text replacement
     * (this initial load, and every reload). {@code textProperty} cannot
     * tell a keystroke from a {@code replaceText}, so without the guard a
     * freshly opened file would immediately mark itself dirty and schedule a
     * write the user never asked for -- and a reload would re-arm a write of
     * the content it had just superseded.</p>
     */
    private void attachEditing(Tab tab, CodeArea area, Path file, FileContent content) {
        FileEditSession session =
                new FileEditSession(file, content, ioExecutor, SAVE_DEBOUNCE, MAX_FILE_BYTES);
        sessions.put(file, session);
        tab.getProperties().put("drydock.session", session);

        session.setOnStateChanged(state -> Platform.runLater(() -> {
            updateStatusChip();
            updateDirtyDot(tab);
            if (state == FileEditSession.State.CLEAN) {
                onSaved();
            }
        }));
        session.setOnSaveFailed(error -> Platform.runLater(this::updateStatusChip));

        area.setEditable(true);
        area.textProperty().addListener((obs, oldText, newText) -> {
            if (Boolean.TRUE.equals(tab.getProperties().get("drydock.suppressDirty"))) {
                return;
            }
            session.edit(newText);
            updateDirtyDot(tab);
            updateStatusChip();
        });
        // Cmd+S forces an immediate flush (spec decision 1).
        area.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
                session.flush();
                event.consume();
            }
        });
    }

    /** Replaces a tab's text without marking it dirty; see {@link #attachEditing}. */
    private void replaceTextQuietly(Tab tab, CodeArea area, String text) {
        tab.getProperties().put("drydock.suppressDirty", Boolean.TRUE);
        try {
            area.replaceText(text);
        } finally {
            tab.getProperties().put("drydock.suppressDirty", Boolean.FALSE);
        }
    }
```

In the load's `Platform.runLater`, replace `area.replaceText(text);` with `replaceTextQuietly(tab, area, text);`.

- [ ] **Step 3: Flush on blur, tab switch, and tab close**

In `FileViewer`'s constructor, extend the existing selection listener:

```java
        fileTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            flushSession(oldTab);
            updateBreadcrumb(newTab);
            updateEmptyState();
            updateDiffBanner();
            updateStatusChip();
        });
```

In `openFile`'s `tab.setOnClosed(...)`, flush before dropping the session:

```java
        tab.setOnClosed(e -> {
            flushSession(tab);
            sessions.remove(file);
            openFiles.remove(file);
            updateEmptyState();
            updateStatusChip();
        });
```

Add, alongside the other helpers:

```java
    /** Forces a pending save out for {@code tab} (blur / tab switch / close). Null- and read-only-safe. */
    private void flushSession(Tab tab) {
        if (tab != null && tab.getProperties().get("drydock.session") instanceof FileEditSession session) {
            session.flush();
        }
    }

    /**
     * Blocks until every open file's pending edits are on disk. The shutdown
     * path's entry point -- {@link #ioExecutor}'s thread is a daemon, so a
     * fire-and-forget flush is killed mid-write at JVM exit.
     */
    void flushPendingEdits(Duration timeout) {
        for (FileEditSession session : sessions.values()) {
            session.flushBlocking(timeout);
        }
    }
```

- [ ] **Step 4: Status chip and dirty dot**

Replace the `read-only` chip construction in `updateBreadcrumb` (:293-297) with the shared `statusChip`:

```java
        breadcrumb.getChildren().addAll(UiFormats.breadcrumbSegments(shown));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        breadcrumb.getChildren().addAll(spacer, statusChip, gutterToggle);
        updateStatusChip();
```

and add:

```java
    /** Drives the breadcrumb chip from the selected tab's session (spec decision 4). */
    private void updateStatusChip() {
        Tab selected = fileTabs.getSelectionModel().getSelectedItem();
        FileEditSession session = selected == null ? null
                : (FileEditSession) selected.getProperties().get("drydock.session");
        statusChip.getStyleClass().removeAll("read-only-chip", "editable-chip", "unsaved-chip",
                "saved-chip", "error-chip");
        if (session == null) {
            statusChip.setText("read-only");
            statusChip.getStyleClass().add("read-only-chip");
            return;
        }
        switch (session.state()) {
            case CLEAN -> {
                statusChip.setText("saved ✓");
                statusChip.getStyleClass().add("saved-chip");
                fadeChipToEditable();
            }
            case DIRTY, SAVING -> {
                statusChip.setText("unsaved");
                statusChip.getStyleClass().add("unsaved-chip");
            }
            case CONFLICT -> {
                statusChip.setText("conflict");
                statusChip.getStyleClass().add("error-chip");
            }
            case ERROR -> {
                statusChip.setText("save failed");
                statusChip.getStyleClass().add("error-chip");
            }
        }
    }

    /** "saved ✓" settles back to the resting "editable" state after a moment. */
    private void fadeChipToEditable() {
        if (chipReset != null) {
            chipReset.stop();
        }
        chipReset = new PauseTransition(javafx.util.Duration.seconds(2));
        chipReset.setOnFinished(e -> {
            Tab selected = fileTabs.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getProperties().get("drydock.session")
                    instanceof FileEditSession session && session.state() == FileEditSession.State.CLEAN) {
                statusChip.setText("editable");
                statusChip.getStyleClass().removeAll("saved-chip");
                statusChip.getStyleClass().add("editable-chip");
            }
        });
        chipReset.play();
    }

    /** A "•" on the file tab while it holds unsaved edits, so a background tab is not silently dirty. */
    private void updateDirtyDot(Tab tab) {
        if (!(tab.getProperties().get("drydock.session") instanceof FileEditSession session)) {
            return;
        }
        boolean dirty = session.state() == FileEditSession.State.DIRTY
                || session.state() == FileEditSession.State.CONFLICT;
        if (tab.getGraphic() instanceof HBox graphic) {
            graphic.getChildren().removeIf(node -> node.getStyleClass().contains("viewer-tab-dirty"));
            if (dirty) {
                Label dot = new Label("•");
                dot.getStyleClass().add("viewer-tab-dirty");
                graphic.getChildren().add(dot);
            }
        }
    }
```

Add the field `private PauseTransition chipReset;` next to `statusChip`, and the import `javafx.scene.input.KeyCode`.

- [ ] **Step 5: Add the `onSaved()` stub**

Task 5 fills this in with the coalesced diff refresh. For now:

```java
    /** Called after each successful save; Task 5 hangs the coalesced diff refresh here. */
    private void onSaved() {
        // Diff-overlay refresh is wired in the next task.
    }
```

- [ ] **Step 6: Fix the terminal focus release**

In `MainWorkspace.java`, the focus-owner handler currently releases the terminal's AppKit first responder only for `TextInputControl`. RichTextFX's `CodeArea` extends `GenericStyledArea`, which is a `Region` — not a `TextInputControl` — so without this the native terminal keeps the responder and every keystroke lands in Claude.

Replace lines 1001-1005:

```java
    public void onFocusOwnerChanged(Node owner) {
        // GenericStyledArea covers RichTextFX's CodeArea (the Explorer's now
        // editable code viewer), which is a Region rather than a
        // TextInputControl and would otherwise never release the responder.
        if (owner instanceof TextInputControl || owner instanceof GenericStyledArea<?, ?, ?>) {
            currentlySelected().ifPresent(OpenSessionTab::releaseTerminalFocus);
        }
    }
```

Add the import `org.fxmisc.richtext.GenericStyledArea;`.

- [ ] **Step 7: Add the chip and dot styles**

In `app/src/main/resources/app/drydock/ui/app.css`, after the existing `.read-only-chip` block (:1060):

```css
.editable-chip {
    -fx-background-color: -drydock-active-bg;
    -fx-background-radius: 100px;
    -fx-text-fill: -drydock-text-dim;
    -fx-font-size: 10px;
    -fx-padding: 1 8 1 8;
}
.unsaved-chip {
    -fx-background-color: -drydock-dirty-soft;
    -fx-background-radius: 100px;
    -fx-text-fill: -drydock-dirty;
    -fx-font-size: 10px;
    -fx-padding: 1 8 1 8;
}
.saved-chip {
    -fx-background-color: -drydock-running-soft;
    -fx-background-radius: 100px;
    -fx-text-fill: -drydock-running;
    -fx-font-size: 10px;
    -fx-padding: 1 8 1 8;
}
.error-chip {
    -fx-background-color: -drydock-error-soft;
    -fx-background-radius: 100px;
    -fx-text-fill: -drydock-error;
    -fx-font-size: 10px;
    -fx-padding: 1 8 1 8;
}
.viewer-tab-dirty {
    -fx-text-fill: -drydock-dirty;
    -fx-font-size: 14px;
}
```

- [ ] **Step 8: Verify by hand**

Run: `./gradlew :app:compileJava && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all tests pass.

Then: `./gradlew :app:run`, open a session, switch to Explorer (⌘2), open a `.java` file from the search rail, and check each of:
- typing changes the chip to `unsaved` and puts a `•` on the file tab
- ~2 s after you stop, the chip flips to `saved ✓` then settles to `editable`, and the dot clears
- the bytes are on disk (`git diff` in the worktree shows your edit)
- ⌘S saves immediately
- switching to another file tab saves the one you left
- a binary file (e.g. a `.png` under the search root) still shows `read-only` and refuses typing

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/FileViewer.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "$(cat <<'EOF'
Make eligible Explorer files editable with auto-save

Eligible tabs get an editable CodeArea backed by a FileEditSession: a 2s
debounce, forced flushes on blur, file-tab switch and close, and Cmd+S for
an immediate save. The breadcrumb chip and a per-tab dot show the state.

The dirty listener is suppressed around programmatic replaceText, which
textProperty cannot distinguish from a keystroke and which would otherwise
dirty a file on load.

MainWorkspace.onFocusOwnerChanged now also releases the terminal's AppKit
first responder for GenericStyledArea: RichTextFX's CodeArea is a Region,
not a TextInputControl, so without this every keystroke reached Claude
instead of the editor.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Disk polling, reload, conflict banner, and the coalesced diff refresh

Makes the viewer trustworthy while Claude works: a clean tab silently adopts Claude's edits, a dirty tab raises a conflict instead of clobbering them, and the green diff markers follow your saves.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java`
- Modify: `app/src/main/resources/app/drydock/ui/app.css`

**Interfaces:**
- Consumes: `FileEditSession.poll()`, `PollResult`, `PollOutcome`, `keepMine()`, `takeDisk()` (Task 2); `DiffOverlay.invalidate()` (Task 3); `replaceTextQuietly`, `ioExecutor`, `sessions` (Task 4).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Start and stop the poller with the scene**

Add to `FileViewer`'s constructor:

```java
        // Poll only while the viewer is actually in the scene graph.
        // OpenSessionTab.showSubTab swaps the tab's center node, so leaving
        // the Explorer detaches this viewer and nulls its scene -- which is
        // also the flush signal for the sub-tab-switch and tab-close cases
        // (spec: Lifecycle).
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                flushAllSessions();
                stopPolling();
            } else {
                startPolling();
            }
        });
```

Add the fields and methods:

```java
    /** How often open files are checked for external (Claude) edits. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(1500);

    private ScheduledFuture<?> poller;

    private void startPolling() {
        if (poller == null) {
            poller = ioExecutor.scheduleWithFixedDelay(this::pollOpenFiles,
                    POLL_INTERVAL.toMillis(), POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void stopPolling() {
        if (poller != null) {
            poller.cancel(false);
            poller = null;
        }
    }

    private void flushAllSessions() {
        for (FileEditSession session : sessions.values()) {
            session.flush();
        }
    }

    /** One round of external-change detection across every open editable tab. */
    private void pollOpenFiles() {
        for (Tab tab : List.copyOf(fileTabs.getTabs())) {
            if (tab.getProperties().get("drydock.session") instanceof FileEditSession session) {
                session.poll().thenAccept(result -> Platform.runLater(() -> applyPollResult(tab, session, result)));
            }
        }
    }
```

Add imports `java.util.concurrent.ScheduledFuture` and `java.util.concurrent.TimeUnit`.

- [ ] **Step 2: Apply a poll result**

```java
    /**
     * Clean tab + changed file: silently adopt Claude's edits, so an open
     * tab never goes stale. Dirty tab + changed file: raise the conflict
     * banner and leave both versions intact (auto-save is already disarmed
     * inside the session).
     */
    private void applyPollResult(Tab tab, FileEditSession session, FileEditSession.PollResult result) {
        switch (result.outcome()) {
            case UNCHANGED -> { }
            case RELOAD -> reload(tab, result.text());
            case CONFLICT -> showConflictBanner(tab, session);
            case MISSING -> showMissingBanner(tab, session);
        }
        updateStatusChip();
        updateDirtyDot(tab);
    }

    /**
     * Replaces a clean tab's text with the disk content, holding the caret
     * where it was.
     *
     * <p>Undo history is forgotten deliberately: {@code replaceText} pushes
     * onto RichTextFX's UndoManager, so one ⌘Z after a reload the user did
     * not notice would restore their stale buffer -- which auto-save would
     * then write over Claude's edits.</p>
     */
    private void reload(Tab tab, String text) {
        if (!(tab.getContent() instanceof VirtualizedScrollPane<?> pane)
                || !(pane.getContent() instanceof CodeArea area)) {
            return;
        }
        int paragraph = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        replaceTextQuietly(tab, area, text);
        area.getUndoManager().forgetHistory();
        int safeParagraph = Math.max(0, Math.min(paragraph, area.getParagraphs().size() - 1));
        int safeColumn = Math.max(0, Math.min(column, area.getParagraphLength(safeParagraph)));
        area.moveTo(safeParagraph, safeColumn);
        rehighlight(tab, area, text);
    }
```

- [ ] **Step 3: Conflict and missing banners**

Add the banner row next to the existing diff banner. Fields:

```java
    // -- Conflict / error banner (spec: Error handling) ---------------------
    private final HBox editBanner = new HBox(8);
    private final Label editBannerLabel = new Label();
    private final Button editBannerPrimary = new Button();
    private final Button editBannerSecondary = new Button();
```

In the constructor, after the diff-banner setup:

```java
        editBannerLabel.getStyleClass().add("viewer-edit-banner-label");
        editBannerPrimary.getStyleClass().add("viewer-diff-base-switch");
        editBannerPrimary.setFocusTraversable(false);
        editBannerSecondary.getStyleClass().add("viewer-diff-base-switch");
        editBannerSecondary.setFocusTraversable(false);
        editBanner.getChildren().setAll(editBannerLabel, editBannerPrimary, editBannerSecondary);
        editBanner.setAlignment(Pos.CENTER_LEFT);
        editBanner.getStyleClass().add("viewer-edit-banner");
        editBanner.setVisible(false);
        editBanner.setManaged(false);
```

In `updateEmptyState`, include it in the top box:

```java
            topBox.getChildren().setAll(breadcrumb, diffBanner, editBanner);
```

Then the banner methods:

```java
    private void showConflictBanner(Tab tab, FileEditSession session) {
        String name = fileNameOf(tab);
        editBannerLabel.setText(name + " changed on disk while you were editing it.");
        editBannerPrimary.setText("keep mine");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.keepMine();
        });
        editBannerSecondary.setText("reload");
        editBannerSecondary.setOnAction(e -> {
            hideEditBanner();
            session.takeDisk().thenAccept(text -> Platform.runLater(() -> {
                if (text != null) {
                    reload(tab, text);
                }
            }));
        });
        showEditBanner();
    }

    private void showMissingBanner(Tab tab, FileEditSession session) {
        editBannerLabel.setText(fileNameOf(tab) + " is no longer on disk.");
        editBannerPrimary.setText("keep mine");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.keepMine();
        });
        editBannerSecondary.setText("close tab");
        editBannerSecondary.setOnAction(e -> {
            hideEditBanner();
            fileTabs.getTabs().remove(tab);
        });
        showEditBanner();
    }

    /** Surfaces a failed write; the buffer is kept and the next edit or ⌘S retries. */
    private void showSaveErrorBanner(Tab tab, FileEditSession session) {
        IOException error = session.lastError();
        editBannerLabel.setText("Could not save " + fileNameOf(tab) + ": "
                + (error == null ? "unknown error" : error.getMessage()));
        editBannerPrimary.setText("retry");
        editBannerPrimary.setOnAction(e -> {
            hideEditBanner();
            session.flush();
        });
        editBannerSecondary.setVisible(false);
        editBannerSecondary.setManaged(false);
        showEditBanner();
    }

    private void showEditBanner() {
        editBanner.setVisible(true);
        editBanner.setManaged(true);
    }

    private void hideEditBanner() {
        editBanner.setVisible(false);
        editBanner.setManaged(false);
        editBannerSecondary.setVisible(true);
        editBannerSecondary.setManaged(true);
    }

    private String fileNameOf(Tab tab) {
        Path file = (Path) tab.getProperties().get("drydock.file");
        return file == null ? "This file" : file.getFileName().toString();
    }
```

Wire the error case: in `attachEditing`, change the save-failure handler to

```java
        session.setOnSaveFailed(error -> Platform.runLater(() -> {
            updateStatusChip();
            showSaveErrorBanner(tab, session);
        }));
```

- [ ] **Step 4: Debounced re-highlighting**

```java
    /** Re-highlight debounce, matching SearchRail's keystroke-debounce convention. */
    private static final Duration HIGHLIGHT_DEBOUNCE = Duration.ofMillis(150);

    private PauseTransition highlightDebounce;

    /**
     * Recomputes the lexer spans for {@code area} off the FX thread. The
     * search-match layer is deliberately not re-derived: it is a load-time
     * artifact of "open from search result", and recomputing it while the
     * user types would fight the caret.
     */
    private void rehighlight(Tab tab, CodeArea area, String text) {
        Path file = (Path) tab.getProperties().get("drydock.file");
        if (file == null) {
            return;
        }
        SyntaxHighlighter.Language language =
                SyntaxHighlighter.Language.fromFileName(file.getFileName().toString());
        Thread.ofVirtual().start(() -> {
            var spans = SyntaxHighlighter.computeHighlighting(text, language);
            Platform.runLater(() -> {
                if (text.equals(area.getText()) && !text.isEmpty()) {
                    area.setStyleSpans(0, spans);
                }
            });
        });
    }

    private void scheduleRehighlight(Tab tab, CodeArea area) {
        if (highlightDebounce != null) {
            highlightDebounce.stop();
        }
        highlightDebounce = new PauseTransition(javafx.util.Duration.millis(HIGHLIGHT_DEBOUNCE.toMillis()));
        highlightDebounce.setOnFinished(e -> rehighlight(tab, area, area.getText()));
        highlightDebounce.play();
    }
```

In `attachEditing`'s text listener, add `scheduleRehighlight(tab, area);` after `session.edit(newText);`.

- [ ] **Step 5: Coalesced diff refresh**

Replace Task 4's `onSaved()` stub:

```java
    /** Coalescing window for the post-save diff refresh (the cache is shared with Review). */
    private static final Duration DIFF_REFRESH_COALESCE = Duration.ofSeconds(2);

    private PauseTransition diffRefreshDebounce;

    /**
     * Schedules a diff-overlay refresh after a save. Coalesced rather than
     * per-save: {@link DiffOverlay#invalidate()} drops a cache the Review tab
     * shares, so each refresh costs both views a fresh {@code git diff}, and
     * a 2s save debounce would otherwise mean a subprocess every couple of
     * seconds while typing.
     */
    private void onSaved() {
        if (diffOverlay == null) {
            return;
        }
        if (diffRefreshDebounce != null) {
            diffRefreshDebounce.stop();
        }
        diffRefreshDebounce = new PauseTransition(
                javafx.util.Duration.millis(DIFF_REFRESH_COALESCE.toMillis()));
        diffRefreshDebounce.setOnFinished(e -> {
            diffOverlay.invalidate();
            refreshDiffOverlay();
        });
        diffRefreshDebounce.play();
    }
```

- [ ] **Step 6: Banner styles**

In `app.css`, after the `.viewer-diff-banner-label` block:

```css
.viewer-edit-banner {
    -fx-background-color: -drydock-dirty-soft;
    -fx-border-color: transparent transparent -drydock-border transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 4 12 4 12;
}
.viewer-edit-banner-label {
    -fx-text-fill: -drydock-dirty;
    -fx-font-size: 11.5px;
}
```

- [ ] **Step 7: Verify by hand**

Run: `./gradlew :app:compileJava && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all tests pass.

Then `./gradlew :app:run` and check each of:
- open a file, leave it untouched, and edit it from another terminal (`echo x >> file`) — within ~1.5 s the tab shows the new content, caret held
- press ⌘Z right after that reload — it must **not** resurrect the pre-reload text
- type into a file, then edit the same file externally — the conflict banner appears, `keep mine` writes yours, `reload` takes disk
- save an edit and confirm the green changed-line markers update within a few seconds
- typing keeps syntax colouring in step

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/FileViewer.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "$(cat <<'EOF'
Reload, conflict, and diff refresh for edited Explorer files

Open files are polled every 1.5s while the Explorer is on screen. A clean
tab silently adopts Claude's edits -- forgetting undo history, so a stray
Cmd+Z cannot resurrect the stale buffer over them -- while a dirty tab
raises a keep-mine/reload banner and writes nothing until the user picks.
Deleted files and failed writes get their own banners.

Re-highlighting follows the 150ms keystroke debounce, and the post-save
diff refresh is coalesced over 2s because DiffOverlay.invalidate() drops a
cache the Review tab shares.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Shutdown flush chain

Scene detach covers sub-tab switches and tab closes, but application shutdown has no such signal — and `FileViewer`'s executor threads are daemons, so a pending write dies with the JVM. This adds the explicit, blocking chain.

`DrydockApplication.stop()` closes a flat list of services it holds directly and has no reference to any `FileViewer`; those are built lazily inside a closure in `MainWorkspace.createOpenSessionTab`. So `MainWorkspace` must track the viewers it creates.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:1100`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (`stop()`)

**Interfaces:**
- Consumes: `FileViewer.flushPendingEdits(Duration)` (Task 4).
- Produces: `void FileViewer.dispose()`, `void SessionExplorerView.flushPendingEdits()`, `void SessionExplorerView.dispose()`, `void MainWorkspace.flushExplorerEdits()`.

**Also in this task — executor disposal.** Task 4's review found that `FileViewer` creates a single-threaded daemon `ScheduledExecutorService` per session tab and nothing ever shuts it down, so every session tab whose Explorer was opened leaks a live `explorer-file-io` thread for the life of the process. Scene detach cannot be the trigger — it fires on every sub-tab switch, and the executor must survive re-attach.

Add `FileViewer.dispose()`: flush pending edits, stop the `chipReset` and any other running `PauseTransition`, then `ioExecutor.shutdown()`. Expose it through `SessionExplorerView.dispose()`. `MainWorkspace` already registers each Explorer it builds; key that registration by the owning tab so `removeTab` can dispose the right one, and have `flushExplorerEdits()` dispose the rest at shutdown.

- [ ] **Step 1: Expose the flush on `SessionExplorerView`**

In `SessionExplorerView.java`:

```java
    /** Bounded so a stuck filesystem cannot hang application shutdown. */
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Blocks until this Explorer's unsaved file edits are on disk. Called on
     * the shutdown path: the viewer's I/O threads are daemons, so a
     * fire-and-forget flush is killed mid-write at JVM exit.
     */
    public void flushPendingEdits() {
        viewer.flushPendingEdits(FLUSH_TIMEOUT);
    }
```

Note the import collision: this file already imports `javafx.util.Duration` for its collapse animation. Import `java.time.Duration` is therefore not possible — declare the constant as `private static final java.time.Duration FLUSH_TIMEOUT = java.time.Duration.ofSeconds(2);`, the same-name-different-package exception AGENTS.md allows.

- [ ] **Step 2: Track viewers in `MainWorkspace`**

Add the field next to the other tab-tracking state:

```java
    /**
     * Every Explorer built by {@link #createOpenSessionTab}'s factory, so
     * {@link #flushExplorerEdits()} can reach their unsaved file edits at
     * shutdown. Explorers are created lazily inside the factory closure and
     * are otherwise unreachable from here.
     */
    private final List<SessionExplorerView> openExplorers = new ArrayList<>();
```

Change the Explorer factory (:1100) from

```java
        openTab.setExplorerFactory(() -> new SessionExplorerView(searchRoot, searchService, overlay));
```

to

```java
        openTab.setExplorerFactory(() -> {
            SessionExplorerView explorer = new SessionExplorerView(searchRoot, searchService, overlay);
            openExplorers.add(explorer);
            return explorer;
        });
```

Add the method:

```java
    /**
     * Flushes every open Explorer's unsaved file edits. Invoked from {@code
     * DrydockApplication.stop()}; blocking and bounded per Explorer.
     */
    public void flushExplorerEdits() {
        for (SessionExplorerView explorer : openExplorers) {
            explorer.flushPendingEdits();
        }
    }
```

Add the import `app.drydock.ui.explorer.SessionExplorerView` if not already present, plus `java.util.ArrayList`/`java.util.List` as needed.

- [ ] **Step 3: Call it from shutdown**

In `DrydockApplication.stop()`, add as the **first** step — before the service closes, so the write goes out while everything it depends on is still alive:

```java
        if (mainWorkspace != null) {
            closeQuietly("Explorer file edits", mainWorkspace::flushExplorerEdits);
        }
```

- [ ] **Step 4: Verify**

Run: `./gradlew :app:compileJava && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all tests pass.

Then `./gradlew :app:run`: open a file in the Explorer, type an edit, and **immediately** quit the app (⌘Q) — well inside the 2 s debounce. Reopen the file (or `git diff` the worktree) and confirm the edit survived.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/main/java/app/drydock/DrydockApplication.java
git commit -m "$(cat <<'EOF'
Flush unsaved Explorer edits on application shutdown

Scene detach covers sub-tab switches and tab closes, but quitting has no
such signal and the viewer's I/O threads are daemons, so an edit still
inside its 2s debounce died with the JVM. DrydockApplication.stop() now
flushes through MainWorkspace to every open Explorer, blocking with a
bounded timeout, before the services it depends on are closed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Verification checklist

After Task 6, the whole feature should hold up to:

- [ ] `./gradlew :app:test` — `FileContentTest` (9) and `FileEditSessionTest` (13) pass alongside the existing suite.
- [ ] Typing in an eligible file saves within ~2 s; the chip and tab dot track it.
- [ ] ⌘S saves immediately; blur, file-tab switch, sub-tab switch (⌘1), tab close and ⌘Q each flush.
- [ ] A binary, oversized, or CRLF-mixed file stays `read-only` and rejects typing.
- [ ] A CRLF file edited in the viewer keeps CRLF on disk (`git diff` shows one changed line, not the whole file).
- [ ] An externally edited clean tab reloads within ~1.5 s, caret held, undo history cleared.
- [ ] An externally edited dirty tab raises the conflict banner and writes nothing until resolved.
- [ ] Green changed-line markers update within a few seconds of a save.
- [ ] Keystrokes reach the editor, not Claude, whenever the code area has focus.

## Known limitations (from the spec, not defects)

- The same absolute path open in two session tabs gives two independent auto-savers that observe each other's writes. Self-correcting through clean-reload, but the buffers can ping-pong while both are dirty.
- Change detection is mtime + size, so a same-size external edit within the filesystem's timestamp granularity is missed. APFS's nanosecond timestamps make this vanishingly unlikely.
