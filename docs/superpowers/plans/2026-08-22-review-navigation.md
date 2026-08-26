# Review Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Drydock's Review board group a change by its own structure, order it foundation-first, link related hunks, and key the human's approvals to content rather than to a grouping.

**Architecture:** One in-memory `ChangeGraph` over the scope's diff (changed symbols as nodes, references as edges) feeds four consumers — the section grouping, the reading path, the out-of-diff caller popover, and the base-move relevance filter. Reviewed state moves off the section and onto the hunk, keyed by a digest of its content, so sections may overlap freely and an agent may regroup without destroying the human's work. Parsing is tree-sitter where a grammar is loaded and lexical everywhere else, behind one interface with one edge-matching rule.

**Tech Stack:** Java 26, JavaFX 26, JUnit 5 + TestFX/Monocle (headless), Gradle. New runtime dependencies: `io.github.bonede:tree-sitter` and per-language grammar artifacts. No graph library — Kahn and Tarjan are hand-rolled.

**Spec:** `docs/superpowers/specs/2026-08-22-review-navigation-design.md`

## Global Constraints

- **Never block the FX thread.** Graph construction, parsing, native library loading and every `git` spawn run on a background executor; only `Platform.runLater` touches UI. Every user-triggered async op shows progress immediately and clears it on success, failure **and** early return.
- **All process spawns go through `app.drydock.process.ProcessRunner`** — argument list never a shell string, explicit timeout, `--end-of-options` before positional revision/path arguments, and a failed command is never silently equal to an empty result.
- **Determinism is a requirement, not a property.** No `HashMap`/`HashSet` iteration order anywhere in scanning, graph construction, grouping or sorting. `LinkedHashMap`, `LinkedHashSet`, `TreeMap` only. The same diff must produce a byte-identical grouping and reading path across two processes.
- **An agent may never clear a human's approval.** Agent input adds staleness or proposes; it never settles, resolves, or un-stales.
- **Anything advertised in `ShortcutsOverlay` must be bound, and vice versa.**
- **Never inline fully-qualified class names**; use imports.
- **Java toolchain 26**; source encoding UTF-8 (already pinned in `app/build.gradle.kts`).
- Test command shape: `./gradlew :app:test --tests "app.drydock.review.SomeTest"`. The full suite takes 14–20 minutes; always run the targeted subset during a task and the full suite only at a phase boundary.

## Phases

The plan is three independently shippable phases with a hard order.

| Phase | Tasks | Ships |
|---|---|---|
| **1 — Reviewed state moves to the hunk** | 1–7 | Approvals survive a re-diff correctly and sections become free to overlap. Works with today's grouping. |
| **2 — Graph-backed sections** | 8–15 | The rail stops reading `main/cpp · 12 files`. The highest-value phase; needs Phase 1 because §5.2's header conventions produce overlapping membership. |
| **3 — Reading path, links, recheck** | 16–23 | Order, entry points, hunk-to-hunk links, and the agent staleness recheck. |

**One dependency is deliberately deferred across a phase boundary:** §9.2's relevance filter intersects a base delta against the scope's own files *and* the files declaring symbols its hunks reference. The second half needs the `ChangeGraph`, which is Phase 2. Task 5 implements the first half; Task 15 widens it. This is called out again in both tasks.

## File Structure

**New — `app/src/main/java/app/drydock/review/`**

| File | Responsibility |
|---|---|
| `HunkDigest.java` | The content identity of one hunk: `sha256(path + context + changed lines)`. Pure. |
| `VerdictMerge.java` | Derives a group's decision from its members' — any `CHANGES` wins, `APPROVED` needs all. Extracted from `AnnotationStore` so it is testable without a store. |
| `RecheckAssessment.java` | An agent's statement about whether a base move affects one approved hunk. |
| `BaseMove.java` | Resolves whether a base move can matter: `git diff --name-only`, intersected with the scope's files (Task 5) and its referenced declarations (Task 15). |
| `SymbolScan.java` | One file's declarations and uses. Two implementations behind it: tree-sitter and lexical. |
| `GrammarRegistry.java` | Extension → tree-sitter grammar. A missing grammar is the lexical path, not an error. |
| `ChangeGraph.java` | Changed symbols and their references, built from a `UnifiedDiff`. |
| `Graphs.java` | Kahn topological sort and Tarjan SCC, both with a caller-supplied total tie-break. |
| `Sections.java` | Components + header conventions + hub titles + dependency order. Sections may overlap. |
| `ReadingPath.java` | Order, entry points and links over a `ChangeGraph`. |
| `OutOfDiffFanIn.java` | One bounded `git grep -n -F -f`, kept with its locations. |

**Modified**

| File | Change |
|---|---|
| `review/ReviewVerdict.java` | Keyed by `(scopeId, hunkDigest)`; carries the `(base, head)` it was given against. |
| `review/AnnotationStore.java` | Verdicts stored per hunk digest; `migrateLegacyVerdicts` deleted; assessments persisted. |
| `review/FallbackIntents.java` | Graph-backed grouping, with today's (kind, directory) clustering as its own fallback. |
| `review/ReviewIntent.java` | `reads` field. |
| `ui/review/ReviewVerdictBar.java` | Progress in hunks; acting unit named; stale banner and submit refusal. |
| `ui/review/ReviewIntentRail.java` | Derived section state, `✓ reviewed in ①` markers, PATH mode. |
| `ui/review/ReviewDiffColumn.java` | Link footer rows; caller popover. |
| `ui/review/SessionReviewView.java` | Wiring, focus-scoped settle actions. |
| `mcp/ReviewToolCodec.java`, `mcp/McpToolRouter.java` | `reads`, `sections` include, `review_recheck`. |
| `ui/ShortcutsOverlay.java` | `p`, `⇧A`, `⇧R`; `a`/`r`/`u` reworded to name their unit. |
| `app/build.gradle.kts` | tree-sitter core + grammar artifacts. |

---

# Phase 1 — Reviewed state moves to the hunk

### Task 1: `HunkDigest` — the content identity of a hunk

**Files:**
- Create: `app/src/main/java/app/drydock/review/HunkDigest.java`
- Test: `app/src/test/java/app/drydock/review/HunkDigestTest.java`

**Interfaces:**
- Consumes: `app.drydock.git.UnifiedDiff.FileDiff`, `UnifiedDiff.Hunk`, `UnifiedDiff.Line`
- Produces: `static String HunkDigest.of(String path, UnifiedDiff.Hunk hunk)` → 64-char lowercase hex

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What an approval is pinned to (spec §9.2). A digest that ignores context
 * lets an approval stand over code whose surroundings moved; a digest that
 * covers the whole file re-reviews hunks nobody touched. These tests pin
 * both edges of that window.
 */
class HunkDigestTest {

    private static UnifiedDiff.Line ctx(int line, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(line), OptionalInt.of(line), text);
    }

    private static UnifiedDiff.Line add(int line, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(line), text);
    }

    private static UnifiedDiff.Hunk hunk(List<UnifiedDiff.Line> lines) {
        return new UnifiedDiff.Hunk("@@ -1,3 +1,4 @@", lines);
    }

    @Test
    void theSameContentInTheSamePathDigestsIdentically() {
        UnifiedDiff.Hunk left = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk right = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));

        assertEquals(HunkDigest.of("src/a.c", left), HunkDigest.of("src/a.c", right));
    }

    /** A hunk that only moved is the same code, and stays approved. */
    @Test
    void movingAHunkWithoutChangingItKeepsTheDigest() {
        UnifiedDiff.Hunk before = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk after = hunk(List.of(ctx(41, "int a;"), add(42, "int b;")));

        assertEquals(HunkDigest.of("src/a.c", before), HunkDigest.of("src/a.c", after));
    }

    /**
     * The reason context is in the digest: a hunk means what it means in
     * place, so an edit to the line above it must unsettle the approval even
     * though the changed lines are byte-identical.
     */
    @Test
    void changingOnlyAContextLineChangesTheDigest() {
        UnifiedDiff.Hunk before = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk after = hunk(List.of(ctx(1, "long a;"), add(2, "int b;")));

        assertNotEquals(HunkDigest.of("src/a.c", before), HunkDigest.of("src/a.c", after));
    }

    @Test
    void changingAChangedLineChangesTheDigest() {
        UnifiedDiff.Hunk before = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));
        UnifiedDiff.Hunk after = hunk(List.of(ctx(1, "int a;"), add(2, "int c;")));

        assertNotEquals(HunkDigest.of("src/a.c", before), HunkDigest.of("src/a.c", after));
    }

    /** Identical hunks in two files are two different things to approve. */
    @Test
    void thePathIsPartOfTheIdentity() {
        UnifiedDiff.Hunk both = hunk(List.of(ctx(1, "int a;"), add(2, "int b;")));

        assertNotEquals(HunkDigest.of("src/a.c", both), HunkDigest.of("src/b.c", both));
    }

    /** The line's KIND matters: an added line and a deleted one are not the same review. */
    @Test
    void addAndDeleteOfTheSameTextDigestDifferently() {
        UnifiedDiff.Hunk added = hunk(List.of(add(1, "int b;")));
        UnifiedDiff.Hunk deleted = hunk(List.of(new UnifiedDiff.Line(
                UnifiedDiff.Line.Kind.DEL, OptionalInt.of(1), OptionalInt.empty(), "int b;")));

        assertNotEquals(HunkDigest.of("src/a.c", added), HunkDigest.of("src/a.c", deleted));
    }

    @Test
    void theDigestIsLowercaseHexOfFixedWidth() {
        String digest = HunkDigest.of("src/a.c", hunk(List.of(add(1, "x"))));

        assertEquals(64, digest.length());
        assertEquals(digest.toLowerCase(java.util.Locale.ROOT), digest);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.HunkDigestTest"`
Expected: FAIL — `cannot find symbol: class HunkDigest`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The content identity of one hunk: what an approval is valid for
 * (spec §9.2).
 *
 * <p>Covers the file path, the hunk's changed lines <em>and</em> its context
 * lines. Context is included because a hunk means what it means in place --
 * change the line above it and its changed lines are byte-identical, so a
 * changed-lines-only digest would leave an approval standing over code whose
 * surroundings moved. It stops at the context window rather than the whole
 * file: a file-wide digest would unsettle every hunk whenever a file is
 * touched again, re-reviewing code nobody changed.</p>
 *
 * <p>Line NUMBERS are deliberately excluded. A hunk that only moved is the
 * same code and stays approved; that is the whole reason this is not the
 * positional line key findings use.</p>
 */
public final class HunkDigest {

    private HunkDigest() {
    }

    /** The digest {@code hunk} in {@code path} is approved under. */
    public static String of(String path, UnifiedDiff.Hunk hunk) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(hunk, "hunk");
        StringBuilder material = new StringBuilder(path).append('\n');
        for (UnifiedDiff.Line line : hunk.lines()) {
            // The kind is part of the material: an added line and a deleted
            // line carrying the same text are not the same thing to approve.
            material.append(line.kind().name()).append(' ').append(line.text()).append('\n');
        }
        return hex(material.toString());
    }

    private static String hex(String material) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; its absence is not a
            // condition this application can meaningfully continue past.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.HunkDigestTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/HunkDigest.java \
        app/src/test/java/app/drydock/review/HunkDigestTest.java
git commit -m "An approval is pinned to a hunk's content, not its position

The digest covers the path, the changed lines and the surrounding context.
Context is in because a hunk means what it means in place: change the line
above it and its changed lines are byte-identical, so a changed-lines-only
digest leaves the approval standing over code whose surroundings moved. It
stops at the context window because a file-wide digest re-reviews hunks
nobody touched. Line numbers are out, so a hunk that only moved stays
approved -- which is the reason this is not the positional line key findings
are anchored to."
```

---

### Task 2: `ReviewVerdict` is keyed by content and remembers its base

**Files:**
- Modify: `app/src/main/java/app/drydock/review/ReviewVerdict.java`
- Test: `app/src/test/java/app/drydock/review/ReviewVerdictTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (the digest is a plain `String` here)
- Produces: `ReviewVerdict(String scopeId, String hunkDigest, Decision decision, Optional<String> note, Instant at, String baseCommit, String headCommit)`; `ReviewVerdict.Key(String scopeId, String hunkDigest)`; `ReviewVerdict.key()`; `boolean staleAgainst(String currentBase)`; `ReviewVerdict confirmedAgainst(String currentBase, String currentHead, Instant at)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verdict names what it was given against (spec §9.2). A digest over the
 * hunk's own text cannot see the base move underneath it, so the base is
 * recorded and staleness is derived from it -- and "confirm still good"
 * rewrites the recorded base rather than storing a fourth state.
 */
class ReviewVerdictTest {

    private static ReviewVerdict approvedAt(String base) {
        return new ReviewVerdict("scope-1", "digest-1", ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, base, "head-1");
    }

    @Test
    void aVerdictIsKeyedByScopeAndHunkDigest() {
        assertEquals(new ReviewVerdict.Key("scope-1", "digest-1"), approvedAt("base-1").key());
    }

    @Test
    void aVerdictGivenAgainstTheCurrentBaseIsNotStale() {
        assertFalse(approvedAt("base-1").staleAgainst("base-1"));
    }

    @Test
    void aVerdictGivenAgainstAnOlderBaseIsStale() {
        assertTrue(approvedAt("base-1").staleAgainst("base-2"));
    }

    /**
     * Confirming rewrites the recorded base. Keeping a separate "confirmed"
     * flag would mean two sources of truth for the same question, and the
     * next base move would have to remember to clear it.
     */
    @Test
    void confirmingRewritesTheRecordedBaseAndClearsStaleness() {
        ReviewVerdict confirmed = approvedAt("base-1")
                .confirmedAgainst("base-2", "head-2", Instant.ofEpochSecond(10));

        assertFalse(confirmed.staleAgainst("base-2"));
        assertEquals("base-2", confirmed.baseCommit());
        assertEquals("head-2", confirmed.headCommit());
        assertEquals(ReviewVerdict.Decision.APPROVED, confirmed.decision());
        assertEquals("digest-1", confirmed.hunkDigest());
    }

    @Test
    void aBlankHunkDigestIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReviewVerdict(
                "scope-1", "  ", ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, "base-1", "head-1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewVerdictTest"`
Expected: FAIL — constructor arity mismatch, `hunkDigest()` and `staleAgainst` not found

- [ ] **Step 3: Write minimal implementation**

Replace the record header, `Key`, and compact constructor in `ReviewVerdict.java`, keeping `Decision` exactly as it is:

```java
public record ReviewVerdict(String scopeId, String hunkDigest, Decision decision,
                            Optional<String> note, Instant at,
                            String baseCommit, String headCommit) {

    public ReviewVerdict {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(hunkDigest, "hunkDigest");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(headCommit, "headCommit");
        if (scopeId.isBlank() || hunkDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "a verdict is keyed by (scopeId, hunkDigest); neither may be blank");
        }
    }

    public Key key() {
        return new Key(scopeId, hunkDigest);
    }

    /** {@code (scopeId, hunkDigest)} -- a hunk's content is its identity (spec §9.2). */
    public record Key(String scopeId, String hunkDigest) {
        public Key {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(hunkDigest, "hunkDigest");
        }
    }

    /**
     * Whether the base has moved since this was given. Only a candidate for
     * staleness: whether the move could actually matter is
     * {@link BaseMove}'s question, not this record's.
     */
    public boolean staleAgainst(String currentBase) {
        return !baseCommit.equals(currentBase);
    }

    /**
     * "Confirm still good": the same decision, re-dated, recorded against the
     * base it has now been judged against. Rewriting the base rather than
     * storing a confirmed flag keeps one source of truth for staleness --
     * a flag would have to be cleared by the next base move, and forgetting
     * to is a silently-approved-stale-code bug.
     */
    public ReviewVerdict confirmedAgainst(String currentBase, String currentHead, Instant when) {
        return new ReviewVerdict(scopeId, hunkDigest, decision, note, when, currentBase, currentHead);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewVerdictTest"`
Expected: PASS (5 tests). `AnnotationStore` and the UI will not compile yet — Task 3 fixes the store, Task 6 the UI. If the module fails to compile, stop and complete Task 3 before re-running.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReviewVerdict.java \
        app/src/test/java/app/drydock/review/ReviewVerdictTest.java
git commit -m "A verdict is keyed by hunk content and names the base it was given against

Two changes, one record. The key moves from intentId to a hunk content
digest, so a verdict no longer belongs to a grouping and an agent regrouping
cannot orphan it. And the (base, head) it was judged against is recorded,
because a digest over a hunk's own text cannot see the base move underneath
it -- a rebase leaves every hunk byte-identical while the code they sit on
changed.

Confirm-still-good rewrites the recorded base rather than setting a
confirmed flag. A flag would be a second source of truth that the next base
move has to remember to clear, and forgetting is a silently-approved-stale-
code bug."
```

---

### Task 3: `AnnotationStore` stores verdicts per hunk, and the migration goes

**Files:**
- Modify: `app/src/main/java/app/drydock/review/AnnotationStore.java` (verdict accessors ~178–184, `putVerdict` ~302, `migrateLegacyVerdicts` ~312–410, JSON encode ~601–612, JSON decode ~855–877, `SCHEMA_VERSION` line 75)
- Test: `app/src/test/java/app/drydock/review/AnnotationStoreVerdictKeyTest.java`

**Interfaces:**
- Consumes: `ReviewVerdict` (Task 2)
- Produces: `Optional<ReviewVerdict> verdict(String scopeId, String hunkDigest)`; `List<ReviewVerdict> verdictsFor(String scopeId)` (unchanged signature); `void putVerdict(ReviewVerdict)`; `void clearVerdict(String scopeId, String hunkDigest)`; `void flushPendingSaves()` (already exists)

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verdicts are stored under a hunk's content, not under a grouping
 * (spec §9.1). The round trip is what makes an approval outlive the process
 * that recorded it, and the base/head it was given against has to survive
 * with it or staleness cannot be derived on the next launch.
 */
class AnnotationStoreVerdictKeyTest {

    private static ReviewVerdict approved(String digest, String base) {
        return new ReviewVerdict("scope-1", digest, ReviewVerdict.Decision.APPROVED,
                Optional.of("looks right"), Instant.parse("2026-08-22T00:00:00Z"), base, "head-1");
    }

    @Test
    void aVerdictRoundTripsThroughDiskWithItsBaseAndHead() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putVerdict(approved("digest-a", "base-1"));
        store.flushPendingSaves();

        AnnotationStore reloaded = new AnnotationStore(file);
        Optional<ReviewVerdict> read = reloaded.verdict("scope-1", "digest-a");

        assertTrue(read.isPresent());
        assertEquals("base-1", read.get().baseCommit());
        assertEquals("head-1", read.get().headCommit());
        assertEquals(Optional.of("looks right"), read.get().note());
        assertEquals(ReviewVerdict.Decision.APPROVED, read.get().decision());
    }

    /**
     * The property that makes overlapping sections possible (spec §5.6): one
     * hunk shown in three sections is one digest, so it is one flag.
     */
    @Test
    void oneDigestIsOneFlagHoweverManySectionsShowIt() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);

        store.putVerdict(approved("shared-digest", "base-1"));

        assertEquals(1, store.verdictsFor("scope-1").size());
        assertTrue(store.verdict("scope-1", "shared-digest").isPresent());
    }

    @Test
    void clearingRemovesTheVerdictForThatDigestOnly() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putVerdict(approved("digest-a", "base-1"));
        store.putVerdict(approved("digest-b", "base-1"));

        store.clearVerdict("scope-1", "digest-a");

        assertEquals(List.of("digest-b"),
                store.verdictsFor("scope-1").stream().map(ReviewVerdict::hunkDigest).toList());
    }

    /**
     * A v3 entry names an intentId and no digest. There are none in the wild
     * (which is why no migration is written), but a file carrying one must
     * be skipped rather than crash the load -- lenient decoding is the
     * store's existing contract.
     */
    @Test
    void aPreDigestVerdictEntryIsSkippedNotFatal() throws IOException {
        Path file = Files.createTempDirectory("drydock-verdicts").resolve("annotations.json");
        Files.writeString(file, """
                {"schemaVersion":3,"annotations":[],"submitted":[],
                 "verdicts":[{"scopeId":"scope-1","intentId":"auto:change:src",
                              "verdict":"approved","at":"2026-08-01T00:00:00Z"}]}
                """);

        AnnotationStore store = new AnnotationStore(file);

        assertEquals(List.of(), store.verdictsFor("scope-1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.AnnotationStoreVerdictKeyTest"`
Expected: FAIL — `verdict(String, String)` still resolves against `intentId`, and the encoder writes no `hunkDigest`

- [ ] **Step 3: Write minimal implementation**

Bump the version and rename the parameter (line 75 and the accessors):

```java
    private static final int SCHEMA_VERSION = 4;
```

```java
    public synchronized Optional<ReviewVerdict> verdict(String scopeId, String hunkDigest) {
        return Optional.ofNullable(verdicts.get(new ReviewVerdict.Key(scopeId, hunkDigest)));
    }

    /** {@code u}: undoes the verdict on one hunk. */
    public void clearVerdict(String scopeId, String hunkDigest) {
        if (clearVerdictInternal(scopeId, hunkDigest)) {
            fireChanged(null);
        }
    }

    private synchronized boolean clearVerdictInternal(String scopeId, String hunkDigest) {
        if (verdicts.remove(new ReviewVerdict.Key(scopeId, hunkDigest)) != null) {
            persistAsync();
            return true;
        }
        return false;
    }
```

Encoder — replace the `intentId` line and add the two commits:

```java
            obj.put("hunkDigest", new JsonString(verdict.hunkDigest()));
            obj.put("verdict", new JsonString(verdict.decision().wireName()));
            verdict.note().ifPresent(note -> obj.put("note", new JsonString(note)));
            obj.put("at", new JsonString(verdict.at().toString()));
            obj.put("base", new JsonString(verdict.baseCommit()));
            obj.put("head", new JsonString(verdict.headCommit()));
```

Decoder — `requireString(obj, "hunkDigest")` replaces `intentId`; an entry without one is skipped by the existing `catch`:

```java
                result.add(new ReviewVerdict(
                        requireString(obj, "scopeId"),
                        requireString(obj, "hunkDigest"),
                        ReviewVerdict.Decision.fromWire(requireString(obj, "verdict"))
                                .orElseThrow(() -> new IllegalArgumentException("unknown verdict")),
                        optionalString(obj, "note"),
                        Instant.parse(requireString(obj, "at")),
                        requireString(obj, "base"),
                        requireString(obj, "head")));
```

Delete `migrateLegacyVerdicts`, `migrateLegacyVerdictsInternal`, `LEGACY_FILE_INTENT_PREFIX` and their callers. Keep the private `merge(List<ReviewVerdict>)` helper — Task 4 extracts it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.AnnotationStoreVerdictKeyTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/AnnotationStore.java \
        app/src/test/java/app/drydock/review/AnnotationStoreVerdictKeyTest.java
git commit -m "Verdicts are stored per hunk digest, and the legacy migration is deleted

Schema 4. A verdict entry carries hunkDigest, base and head instead of
intentId; a v3 entry naming an intentId is skipped by the existing lenient
decode rather than failing the load.

migrateLegacyVerdicts goes with it. It carried verdicts from the old
file:<path> intent ids onto directory-clustered intents, and with the key no
longer naming a grouping there is nothing for it to carry and no caller left
to call it. Its merge helper survives -- it answers how a group's decision
follows from its members, which is now a live question rather than a
migration one."
```

---

### Task 4: `VerdictMerge` — a section's state is derived from its hunks

**Files:**
- Create: `app/src/main/java/app/drydock/review/VerdictMerge.java`
- Modify: `app/src/main/java/app/drydock/review/AnnotationStore.java` (delete the private `merge`)
- Test: `app/src/test/java/app/drydock/review/VerdictMergeTest.java`

**Interfaces:**
- Consumes: `ReviewVerdict` (Task 2)
- Produces: `static Optional<ReviewVerdict.Decision> VerdictMerge.derive(List<Optional<ReviewVerdict>> hunkVerdicts)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a section's state follows from its hunks (spec §9.1). The asymmetry is
 * the point and it is not new -- it is the rule migrateLegacyVerdicts was
 * written around, promoted from a one-off migration to the live derivation:
 * "something in here needs work" survives any redrawing of the group, while
 * approving a group claims the human read all of it.
 */
class VerdictMergeTest {

    private static Optional<ReviewVerdict> of(ReviewVerdict.Decision decision) {
        return Optional.of(new ReviewVerdict("s", "d" + decision.ordinal(), decision,
                Optional.empty(), Instant.EPOCH, "base", "head"));
    }

    private static final Optional<ReviewVerdict> UNSETTLED = Optional.empty();

    @Test
    void everyHunkApprovedApprovesTheSection() {
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.APPROVED),
                                            of(ReviewVerdict.Decision.APPROVED))));
    }

    /** Any changes request survives however the group is drawn. */
    @Test
    void oneChangesRequestMakesTheWholeSectionChanges() {
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.APPROVED),
                                            of(ReviewVerdict.Decision.CHANGES))));
    }

    /**
     * The outcome this must never produce: approving code nobody looked at.
     * A section with one unread hunk is not approved, it is unsettled.
     */
    @Test
    void oneUnsettledHunkLeavesTheSectionUnsettled() {
        assertEquals(Optional.empty(),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.APPROVED), UNSETTLED)));
    }

    /** But a changes request outranks an unread hunk: it is already true. */
    @Test
    void changesWinsEvenWithAnUnsettledHunkPresent() {
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.CHANGES), UNSETTLED)));
    }

    @Test
    void autoApprovalCountsAsSettledAndIsReportedAsItself() {
        assertEquals(Optional.of(ReviewVerdict.Decision.AUTO_APPROVED),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.AUTO_APPROVED),
                                            of(ReviewVerdict.Decision.AUTO_APPROVED))));
    }

    /** A human approval outranks the agent's assertion in the label. */
    @Test
    void aMixOfHumanAndAutoApprovalReadsAsApproved() {
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED),
                VerdictMerge.derive(List.of(of(ReviewVerdict.Decision.AUTO_APPROVED),
                                            of(ReviewVerdict.Decision.APPROVED))));
    }

    @Test
    void anEmptySectionHasNoDecision() {
        assertEquals(Optional.empty(), VerdictMerge.derive(List.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.VerdictMergeTest"`
Expected: FAIL — `cannot find symbol: class VerdictMerge`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A section's decision, derived from its hunks' (spec §9.1).
 *
 * <p>The merge is deliberately asymmetric, and the asymmetry is inherited
 * rather than invented: it is the rule {@code AnnotationStore}'s legacy
 * verdict migration was written around, promoted from a one-off carry to the
 * live derivation now that sections overlap and cannot own a verdict of
 * their own.</p>
 *
 * <ul>
 *   <li>Any {@code CHANGES} makes the section {@code CHANGES}. "Something in
 *       here needs work" stays true of a section however it is drawn.</li>
 *   <li>An approval needs EVERY hunk settled. Approving a section is a claim
 *       that the human read all of it, so one unread hunk leaves it
 *       unsettled. Silently approving code nobody looked at is the one
 *       outcome this must never produce.</li>
 * </ul>
 */
public final class VerdictMerge {

    private VerdictMerge() {
    }

    /**
     * The section's decision, or empty when its hunks do not support one.
     * {@code hunkVerdicts} carries one entry per hunk in the section, empty
     * where that hunk is unsettled.
     */
    public static Optional<ReviewVerdict.Decision> derive(
            List<Optional<ReviewVerdict>> hunkVerdicts) {
        Objects.requireNonNull(hunkVerdicts, "hunkVerdicts");
        if (hunkVerdicts.isEmpty()) {
            return Optional.empty();
        }
        boolean anyUnsettled = false;
        boolean anyHumanApproval = false;
        for (Optional<ReviewVerdict> verdict : hunkVerdicts) {
            if (verdict.isEmpty()) {
                anyUnsettled = true;
                continue;
            }
            switch (verdict.get().decision()) {
                // Checked before the unsettled test: a changes request is
                // already true of the section, and waiting for the rest to be
                // read before saying so would hide it exactly when it matters.
                case CHANGES -> {
                    return Optional.of(ReviewVerdict.Decision.CHANGES);
                }
                case APPROVED -> anyHumanApproval = true;
                case AUTO_APPROVED -> { }
            }
        }
        if (anyUnsettled) {
            return Optional.empty();
        }
        return Optional.of(anyHumanApproval
                ? ReviewVerdict.Decision.APPROVED
                : ReviewVerdict.Decision.AUTO_APPROVED);
    }
}
```

Then delete the private `merge(...)` from `AnnotationStore` (it went unused with Task 3's deletion).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.VerdictMergeTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/VerdictMerge.java \
        app/src/main/java/app/drydock/review/AnnotationStore.java \
        app/src/test/java/app/drydock/review/VerdictMergeTest.java
git commit -m "A section's decision is derived from its hunks, not stored

Sections overlap, so a section cannot own a verdict -- a hunk shown in three
of them would need three. The decision is derived instead, by the asymmetric
merge the legacy migration was already written around: any CHANGES makes the
section CHANGES because that is true however the group is drawn, and an
approval needs every hunk settled because approving a section claims the
human read all of it.

Extracted from AnnotationStore so it can be tested without a store, and
because it is no longer a migration detail but the rule the rail renders."
```

---

### Task 5: `BaseMove` — staleness only when the base move could matter

**Files:**
- Create: `app/src/main/java/app/drydock/review/BaseMove.java`
- Test: `app/src/test/java/app/drydock/review/BaseMoveTest.java`

**Interfaces:**
- Consumes: `app.drydock.process.ProcessRunner`, `ProcessResult`, `ProcessTimeoutException`
- Produces: `record BaseMove.Delta(boolean unresolvable, java.util.SortedSet<String> changedFiles)`; `static Delta between(Path worktree, String oldBase, String newBase)`; `static boolean couldMatter(Delta delta, java.util.Collection<String> scopeFiles)`

**Deferred by one phase, deliberately:** spec §9.2 intersects the delta against the scope's files **and** the files declaring symbols its hunks reference. The second half needs the `ChangeGraph`, which is Phase 2. This task implements the first half; **Task 15 widens it**. The `couldMatter` signature takes a `Collection<String>` precisely so Task 15 can pass a wider set without changing callers.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which base moves are worth telling the reviewer about (spec §9.2).
 * Marking every verdict stale on any base move treats "main advanced in an
 * unrelated subsystem" the same as "main rewrote a function this hunk
 * calls", and on an active repository the first is nearly all of them --
 * which is how a confirm button becomes reflex.
 *
 * <p>{@code between} spawns git and is covered by the running-app pass;
 * what is unit-tested here is the decision the spawn feeds.</p>
 */
class BaseMoveTest {

    private static BaseMove.Delta delta(String... files) {
        return new BaseMove.Delta(false, new TreeSet<>(List.of(files)));
    }

    @Test
    void aBaseMoveTouchingOnlyUnrelatedFilesCannotMatter() {
        assertFalse(BaseMove.couldMatter(delta("docs/README.md", "web/app.ts"),
                List.of("src/guards.cpp", "src/guards.h")));
    }

    @Test
    void aBaseMoveTouchingAFileThisScopeChangesMatters() {
        assertTrue(BaseMove.couldMatter(delta("docs/README.md", "src/guards.h"),
                List.of("src/guards.cpp", "src/guards.h")));
    }

    /**
     * Failing safe is the only defensible default for a signal about what was
     * read: if the old base cannot be resolved -- a force-push, a collected
     * commit -- everything is a candidate.
     */
    @Test
    void anUnresolvableOldBaseMattersRegardlessOfFiles() {
        assertTrue(BaseMove.couldMatter(new BaseMove.Delta(true, new TreeSet<>()),
                List.of("src/guards.cpp")));
    }

    @Test
    void anEmptyDeltaCannotMatter() {
        assertFalse(BaseMove.couldMatter(delta(), List.of("src/guards.cpp")));
    }

    /** A scope with no files is not a reason to mark anything. */
    @Test
    void aScopeWithNoFilesCannotBeAffected() {
        assertFalse(BaseMove.couldMatter(delta("src/guards.h"), List.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.BaseMoveTest"`
Expected: FAIL — `cannot find symbol: class BaseMove`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether a base move can have changed what an approval was given for
 * (spec §9.2).
 *
 * <p>Marking every verdict stale on any base move spends the reviewer's
 * attention on commits that provably could not matter, and a
 * "confirm still good" button clicked reflexively is worth less than no
 * button. So the base delta is intersected first.</p>
 *
 * <p>The intersection is file-level and lexical. A base change that alters
 * behaviour without touching a file the scope names or references will not
 * mark anything -- drydock does not index the repository, so it cannot see
 * that far. Closing that gap is the agent recheck's job, not this class's.</p>
 */
public final class BaseMove {

    private static final Logger LOG = Logger.getLogger(BaseMove.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private BaseMove() {
    }

    /**
     * What a base move touched. {@code unresolvable} means the old base could
     * not be diffed -- a force-push, or a collected commit -- and is NOT the
     * same as an empty delta.
     */
    public record Delta(boolean unresolvable, SortedSet<String> changedFiles) {
        public Delta {
            Objects.requireNonNull(changedFiles, "changedFiles");
            changedFiles = new TreeSet<>(changedFiles);
        }
    }

    /** The files {@code oldBase..newBase} touched. Blocking; never call on the FX thread. */
    public static Delta between(Path worktree, String oldBase, String newBase) {
        List<String> command = List.of("git", "diff", "--name-only", "--end-of-options",
                oldBase + ".." + newBase);
        try {
            ProcessResult result = ProcessRunner.run(command, worktree, TIMEOUT);
            if (result.exitCode() != 0) {
                LOG.log(Level.WARNING, "git diff for base move failed: "
                        + ProcessRunner.excerpt(result.stderr()));
                return new Delta(true, new TreeSet<>());
            }
            SortedSet<String> files = new TreeSet<>();
            for (String line : result.stdout().split("\n")) {
                String path = line.strip();
                if (!path.isEmpty()) {
                    files.add(path);
                }
            }
            return new Delta(false, files);
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "git diff for base move timed out", e);
            return new Delta(true, new TreeSet<>());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "git diff for base move could not run", e);
            return new Delta(true, new TreeSet<>());
        }
    }

    /**
     * Whether {@code delta} could have changed the meaning of code in
     * {@code scopeFiles}.
     *
     * <p>{@code scopeFiles} is a {@link Collection} rather than the scope's
     * own file list so that the set can widen -- Phase 2 adds the files
     * declaring symbols the scope's hunks reference -- without moving any
     * caller.</p>
     */
    public static boolean couldMatter(Delta delta, Collection<String> scopeFiles) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(scopeFiles, "scopeFiles");
        if (delta.unresolvable()) {
            return true;
        }
        for (String file : scopeFiles) {
            if (delta.changedFiles().contains(file)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.BaseMoveTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/BaseMove.java \
        app/src/test/java/app/drydock/review/BaseMoveTest.java
git commit -m "A base move marks approvals stale only when it could matter

Marking every verdict stale on any base move treats main advancing in an
unrelated subsystem the same as main rewriting a function this hunk calls.
On an active repository the first is nearly all of them, and that is how a
confirm button becomes reflex. One git diff --name-only, intersected with
the scope's files, decides.

Failing safe where it cannot decide: an unresolvable old base -- a
force-push, a collected commit -- marks everything, because for a signal
about what was read there is no defensible alternative. Two more honest
limits: the intersection is file-level and lexical, so a base change that
alters behaviour without touching a named file marks nothing, and the
scope-file set is a Collection so Phase 2 can widen it to the files
declaring symbols these hunks reference without moving a caller."
```

---

### Task 6: The rail and the verdict bar read hunks, not sections

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewVerdictBar.java` (progress label ~259, `render` ~221, `showSubmitRefused` ~208)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java` (card rendering)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (`renderVerdictBar` ~765, `renderSelectedScope` ~495)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewHunkProgressTest.java`

**Interfaces:**
- Consumes: `HunkDigest.of` (Task 1), `VerdictMerge.derive` (Task 4), `AnnotationStore.verdict(scopeId, digest)` (Task 3), `ReviewVerdict.staleAgainst` (Task 2), `BaseMove.couldMatter` (Task 5)
- Produces: `ReviewVerdictBar.showProgress(int settledHunks, int totalHunks)`; `ReviewIntentRail` card state `SectionState(Optional<ReviewVerdict.Decision> decision, int settledHunks, int totalHunks, boolean stale, List<String> settledElsewhere)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewVerdict;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overlapping sections break the old arithmetic (spec §5.6): the sum of
 * section sizes exceeds the number of hunks, so "3 of 5 intents settled"
 * measures nothing. Progress counts distinct hunks, and a hunk settled in
 * one section shows as settled in the other.
 */
class ReviewHunkProgressTest extends ApplicationTest {

    private FakeReviewHost host;
    private SessionReviewView view;

    private static UnifiedDiff.Hunk hunk(String text) {
        return new UnifiedDiff.Hunk("@@ -1,1 +1,1 @@", List.of(new UnifiedDiff.Line(
                UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), text)));
    }

    @Override
    public void start(Stage stage) throws Exception {
        host = new FakeReviewHost(java.nio.file.Files
                .createTempDirectory("drydock-progress").resolve("annotations.json"));
        // One shared file placed in two sections, plus one file of its own:
        // three hunks total, four section slots.
        host.diff = new UnifiedDiff(List.of(
                new UnifiedDiff.FileDiff("src/guards.h", "M", 1, 0, false, false,
                        List.of(hunk("class JmpCtxScope;"))),
                new UnifiedDiff.FileDiff("src/guards.cpp", "M", 1, 0, false, false,
                        List.of(hunk("void install();"))),
                new UnifiedDiff.FileDiff("src/profiler.cpp", "M", 1, 0, false, false,
                        List.of(hunk("resolve();")))));
        view = new SessionReviewView(host, new app.drydock.git.DiffService(), null);
        stage.setScene(new Scene(view, 1400, 900));
        stage.show();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String progressText() {
        return lookup(".review-verdict-progress-label").queryAll().stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .map(Label::getText).findFirst().orElse("");
    }

    @Test
    void progressCountsDistinctHunksNotSectionSlots() {
        assertTrue(progressText().contains("0/3"),
                "expected three distinct hunks, got: " + progressText());
    }

    @Test
    void settlingASharedHunkAdvancesProgressExactlyOnce() {
        String shared = HunkDigest.of("src/guards.h", hunk("class JmpCtxScope;"));
        host.annotations().putVerdict(new ReviewVerdict(host.scopeId(), shared,
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                host.baseCommit(), host.headCommit()));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(progressText().contains("1/3"),
                "a hunk in two sections is one flag, got: " + progressText());
    }

    @Test
    void anUnsettledHunkLeavesItsSectionUnsettled() {
        assertEquals(Optional.empty(), view.sectionStateForTest(0).decision());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewHunkProgressTest"`
Expected: FAIL — `sectionStateForTest` not found; progress label still reads `0/2 intents settled`

- [ ] **Step 3: Write minimal implementation**

In `ReviewVerdictBar`, replace the progress label text and rename the setter:

```java
    /** Progress is counted in distinct hunks: sections overlap, so their sizes do not sum. */
    void showProgress(int settledHunks, int totalHunks) {
        this.settledCount = settledHunks;
        this.totalCount = totalHunks;
        render();
    }
```

```java
        progressLabel.setText(settledCount + "/" + totalCount + " hunks reviewed");
```

In `SessionReviewView`, add the per-section derivation and expose it for tests:

```java
    /** One section's rendered state, derived from its hunks (spec §9.1). */
    record SectionState(Optional<ReviewVerdict.Decision> decision, int settledHunks,
                        int totalHunks, boolean stale, List<String> settledElsewhere) {
    }

    SectionState sectionStateForTest(int sectionIndex) {
        return sectionState(intents().get(sectionIndex));
    }

    private SectionState sectionState(ReviewIntent intent) {
        List<Optional<ReviewVerdict>> perHunk = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        boolean stale = false;
        for (String digest : digestsOf(intent)) {
            Optional<ReviewVerdict> verdict = host.annotations().verdict(scopeId(), digest);
            perHunk.add(verdict);
            if (verdict.isPresent() && verdict.get().staleAgainst(currentBase())
                    && BaseMove.couldMatter(baseDelta(), filesOf(intent))) {
                stale = true;
            }
            settlingSectionOf(digest).ifPresent(elsewhere::add);
        }
        long settled = perHunk.stream().filter(Optional::isPresent).count();
        return new SectionState(VerdictMerge.derive(perHunk), (int) settled,
                perHunk.size(), stale, List.copyOf(elsewhere));
    }
```

`digestsOf(intent)` maps the intent's hunk ids through `HunkDigest.of`; `settlingSectionOf(digest)` returns the number of the first *other* section whose hunks include that digest and which is settled, so the rail can render `✓ reviewed in ①`. Distinct-hunk progress is the union of every section's digests, counted once.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewHunkProgressTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ app/src/test/java/app/drydock/ui/review/ReviewHunkProgressTest.java
git commit -m "Progress counts hunks, and a section's state is derived from them

Sections overlap, so the sum of their sizes exceeds the number of hunks and
n/m intents settled measures nothing. The bar counts distinct hunks; a
section's decision comes from VerdictMerge over its own; and a hunk settled
in one section renders as settled in the other, marked with where, so the
effect of settling is visible where it lands rather than looking like state
changing on its own."
```

---

### Task 7: Settle actions, the stale banner, and the shortcut strip

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (key handling)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewVerdictBar.java` (stale banner, acting-unit label)
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` (lines 46–59, the `IN REVIEW` section)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewSettleActionsTest.java`

**Interfaces:**
- Consumes: `SectionState` (Task 6), `ReviewVerdict.confirmedAgainst` (Task 2), `AnnotationStore.putVerdict` / `clearVerdict` (Task 3)
- Produces: `SessionReviewView.settleUnit()` → `enum SettleUnit { SECTION, HUNK, FILE }`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading is per hunk; settling usually is not (spec §9.6). The unit follows
 * focus rather than adding a parallel key set -- the same rule [ and ]
 * already follow -- and the bar names the unit, because a key whose target
 * depends on focus must say what it is about to do.
 */
class ReviewSettleActionsTest extends ReviewViewFixture {

    @Test
    void withTheRailFocusedApproveSettlesTheWholeSection() {
        focusRail();
        press(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(view.sectionStateForTest(0).totalHunks(),
                view.sectionStateForTest(0).settledHunks());
    }

    @Test
    void withTheDiffColumnFocusedApproveSettlesOneHunk() {
        focusDiffColumn();
        press(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, view.sectionStateForTest(0).settledHunks());
    }

    @Test
    void shiftApproveSettlesEveryHunkOfTheCurrentFile() {
        focusDiffColumn();
        press(KeyCode.SHIFT, KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(hunkCountOfCurrentFile(), view.sectionStateForTest(0).settledHunks());
    }

    /** Settling a shared hunk has to be visible where it lands. */
    @Test
    void settlingASectionShowsItsSharedHunksSettledInTheOtherSection() {
        focusRail();
        press(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(view.sectionStateForTest(1).settledElsewhere().contains("1"));
    }

    @Test
    void theBarNamesTheUnitAnActionWillHit() {
        focusRail();
        assertEquals(SessionReviewView.SettleUnit.SECTION, view.settleUnit());
        focusDiffColumn();
        assertEquals(SessionReviewView.SettleUnit.HUNK, view.settleUnit());
    }
}
```

Add the shared fixture `ReviewViewFixture` (base class holding `start`, `focusRail`, `focusDiffColumn`, `press`, `hunkCountOfCurrentFile`) alongside it, modelled on `FakeReviewHost`'s existing use in `ReviewCarriedOverVerdictTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewSettleActionsTest"`
Expected: FAIL — `SettleUnit` not found

- [ ] **Step 3: Write minimal implementation**

```java
    /** What {@code a} / {@code r} / {@code u} act on, decided by focus (spec §9.6). */
    enum SettleUnit { SECTION, HUNK, FILE }

    SettleUnit settleUnit() {
        return diffColumn.isFocusWithin() ? SettleUnit.HUNK : SettleUnit.SECTION;
    }

    private void onApprove(boolean wholeFile) {
        List<String> digests = wholeFile
                ? digestsOfCurrentFile()
                : switch (settleUnit()) {
                    case SECTION -> digestsOf(selectedIntent());
                    case HUNK -> List.of(digestOfCurrentHunk());
                    case FILE -> digestsOfCurrentFile();
                };
        Instant now = Instant.now();
        for (String digest : digests) {
            host.annotations().putVerdict(new ReviewVerdict(scopeId(), digest,
                    ReviewVerdict.Decision.APPROVED, Optional.empty(), now,
                    currentBase(), currentHead()));
        }
    }
```

`r` mints `CHANGES` the same way; `u` calls `clearVerdict` over the same digest list. The stale banner's *confirm still good* rewrites each stale verdict through `confirmedAgainst(currentBase(), currentHead(), Instant.now())`; *re-review* clears them. A section holding a stale verdict does not count as settled, so `ReviewVerdictBar.showSubmitRefused("approvals were given against an older base")` fires on `⏎`.

`ShortcutsOverlay`'s `IN REVIEW` section becomes:

```java
                    {"Approve (section, or hunk in the diff)", "a"},
                    {"Request changes (section, or hunk in the diff)", "r"},
                    {"Undo (section, or hunk in the diff)", "u"},
                    {"Approve every hunk in this file", "⇧A"},
                    {"Request changes on this file", "⇧R"},
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewSettleActionsTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/ app/src/test/java/app/drydock/ui/review/
git commit -m "Settle a hunk, a file or a section, and say which one a key will hit

Reading is per hunk; settling usually is not. The unit follows focus rather
than adding a parallel key set -- the rule [ and ] already follow -- so a
and r and u keep their keys and gain a defined effect on overlapping
sections, with SHIFT variants for the file. The bar names the unit, because
a key whose target depends on focus has to say what it is about to do.

Stale verdicts get their two answers: confirm still good rewrites the
recorded base, re-review clears them, and until one of those happens the
section does not count as settled and the submit refuses with a reason
rather than silently doing nothing."
```

---

### Phase 1 gate

- [ ] **Run the full suite:** `./gradlew :app:test` (14–20 minutes; run it from the controlling session, not a subagent — the 10-minute Bash ceiling will kill it)
- [ ] **Run the app** and confirm by screenshot, per `docs/` visual-verification practice: the verdict bar reading `n/m hunks reviewed`, a section showing `✓ reviewed in ①` on a shared hunk, and the stale banner with its two buttons at a realistic window width. The rail's cards have truncated before.
- [ ] **Confirm the deletion is safe:** `rg -n "migrateLegacyVerdicts" app/src` must return nothing.

---

# Phase 2 — Graph-backed sections

### Task 8: tree-sitter on the classpath, with a lexical fallback that is not an error

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block, after the `pty4j` line)
- Create: `app/src/main/java/app/drydock/review/GrammarRegistry.java`
- Test: `app/src/test/java/app/drydock/review/GrammarRegistryTest.java`

**Interfaces:**
- Produces: `Optional<TSLanguage> GrammarRegistry.forPath(String path)`; `boolean GrammarRegistry.nativeAvailable()`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grammar that is not on the classpath is the lexical path, not an error
 * (spec §10.2). That single rule is what keeps the shipped language set a
 * packaging decision rather than an architectural one -- the .app and the
 * jbang jar may ship different sets, and an unsupported language produces a
 * coarser surface rather than a broken one.
 */
class GrammarRegistryTest {

    @Test
    void aShippedLanguageResolvesToAGrammar() {
        assertTrue(GrammarRegistry.forPath("src/Main.java").isPresent());
    }

    @Test
    void anUnshippedLanguageResolvesToNothingWithoutThrowing() {
        assertTrue(GrammarRegistry.forPath("build/config.zig").isEmpty());
    }

    @Test
    void aFileWithNoExtensionResolvesToNothing() {
        assertTrue(GrammarRegistry.forPath("Makefile").isEmpty());
    }

    /** Case is not a language: .JAVA is Java. */
    @Test
    void extensionMatchingIsCaseInsensitive() {
        assertTrue(GrammarRegistry.forPath("src/Main.JAVA").isPresent());
    }

    @Test
    void aDirectoryEndingInAKnownExtensionIsNotAFile() {
        assertFalse(GrammarRegistry.forPath("vendor/foo.java/").isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.GrammarRegistryTest"`
Expected: FAIL — `cannot find symbol: class GrammarRegistry`

- [ ] **Step 3: Write minimal implementation**

`app/build.gradle.kts`, in `dependencies`:

```kotlin
    // Structural parsing for the Review board's change graph (docs/superpowers/
    // specs/2026-08-22-review-navigation-design.md §10). The core artifact
    // bundles aarch64/x86_64 macOS, x86_64 Windows and both Linux natives --
    // exactly the platforms this app supports -- and extracts the matching one
    // to ~/.tree-sitter/tree-sitter-lib/ on first use. A grammar missing from
    // the classpath is the lexical path (GrammarRegistry), not an error, so
    // this list is a packaging decision and may differ per artifact.
    implementation("io.github.bonede:tree-sitter:0.25.3")
    implementation("io.github.bonede:tree-sitter-java:0.23.4")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-python:0.23.4")
    implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
    implementation("io.github.bonede:tree-sitter-go:0.23.3")
    implementation("io.github.bonede:tree-sitter-rust:0.23.1")
    implementation("io.github.bonede:tree-sitter-c:0.23.2")
    implementation("io.github.bonede:tree-sitter-cpp:0.23.4")
```

```java
package app.drydock.review;

import org.treesitter.TSLanguage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extension to tree-sitter grammar (spec §10.2).
 *
 * <p><strong>A grammar that is absent is the lexical path, not an error.</strong>
 * That rule is what keeps the shipped language set a packaging decision
 * rather than an architectural one: the {@code .app} and the jbang jar may
 * ship different sets, and a language nobody packaged produces a coarser
 * change graph rather than a broken surface.</p>
 *
 * <p>Grammars are resolved reflectively and cached. Loading pulls a native
 * library out of the jar and {@code System.load}s it, so the first call for
 * a language is disk I/O -- never make it on the FX thread.</p>
 */
public final class GrammarRegistry {

    private static final Logger LOG = Logger.getLogger(GrammarRegistry.class.getName());

    /** Extension to the grammar class the artifact publishes, insertion-ordered for determinism. */
    private static final Map<String, String> GRAMMARS = new LinkedHashMap<>();

    static {
        GRAMMARS.put("java", "org.treesitter.TreeSitterJava");
        GRAMMARS.put("kt", "org.treesitter.TreeSitterKotlin");
        GRAMMARS.put("kts", "org.treesitter.TreeSitterKotlin");
        GRAMMARS.put("py", "org.treesitter.TreeSitterPython");
        GRAMMARS.put("js", "org.treesitter.TreeSitterJavascript");
        GRAMMARS.put("mjs", "org.treesitter.TreeSitterJavascript");
        GRAMMARS.put("ts", "org.treesitter.TreeSitterTypescript");
        GRAMMARS.put("tsx", "org.treesitter.TreeSitterTypescript");
        GRAMMARS.put("go", "org.treesitter.TreeSitterGo");
        GRAMMARS.put("rs", "org.treesitter.TreeSitterRust");
        GRAMMARS.put("c", "org.treesitter.TreeSitterC");
        GRAMMARS.put("h", "org.treesitter.TreeSitterCpp");
        GRAMMARS.put("cc", "org.treesitter.TreeSitterCpp");
        GRAMMARS.put("cpp", "org.treesitter.TreeSitterCpp");
        GRAMMARS.put("hpp", "org.treesitter.TreeSitterCpp");
    }

    private static final Map<String, Optional<TSLanguage>> CACHE = new LinkedHashMap<>();
    private static volatile boolean nativeFailed;

    private GrammarRegistry() {
    }

    /** Whether the native library loaded. False means every file takes the lexical path. */
    public static boolean nativeAvailable() {
        return !nativeFailed;
    }

    /** The grammar for {@code path}'s language, or empty when there is none. */
    public static synchronized Optional<TSLanguage> forPath(String path) {
        if (path == null || path.endsWith("/")) {
            return Optional.empty();
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return Optional.empty();
        }
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String className = GRAMMARS.get(extension);
        if (className == null) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(extension, key -> load(className));
    }

    private static Optional<TSLanguage> load(String className) {
        if (nativeFailed) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(className);
            return Optional.of((TSLanguage) type.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException e) {
            // The grammar was not packaged for this artifact. Normal, and the
            // lexical path handles it -- logging it per file would be noise.
            return Optional.empty();
        } catch (ReflectiveOperationException | UnsatisfiedLinkError | RuntimeException e) {
            // The native library could not load: unsupported arch, a failed
            // extraction, a CRC mismatch. Say it ONCE and fall back for
            // everything; per-file logging would bury it.
            if (!nativeFailed) {
                nativeFailed = true;
                LOG.log(Level.WARNING, "tree-sitter unavailable; the change graph "
                        + "falls back to lexical scanning for every file", e);
            }
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.GrammarRegistryTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/app/drydock/review/GrammarRegistry.java \
        app/src/test/java/app/drydock/review/GrammarRegistryTest.java
git commit -m "tree-sitter grammars are a packaging decision, not an architectural one

A grammar missing from the classpath resolves to empty and the file takes
the lexical path. That single rule is what lets the .app and the jbang jar
ship different language sets, and what makes an unsupported language produce
a coarser change graph rather than a broken surface.

Failures are told apart on purpose. A grammar class that is simply absent is
the normal case and logs nothing; a native library that cannot load -- wrong
arch, failed extraction, CRC mismatch -- logs once for the process and turns
every file lexical, because logging either one per file would bury the one
that matters.

The core artifact bundles aarch64/x86_64 macOS, x86_64 Windows and both
Linux natives, which is exactly the platform set this app supports."
```

---

### Task 9: `SymbolScan` — declarations and uses, two front ends, one shape

**Files:**
- Create: `app/src/main/java/app/drydock/review/SymbolScan.java`
- Test: `app/src/test/java/app/drydock/review/SymbolScanTest.java`

**Interfaces:**
- Consumes: `GrammarRegistry.forPath` (Task 8), `app.drydock.review.SymbolWords`, `UnifiedDiff.FileDiff`
- Produces: `record SymbolScan.Symbol(String name, String path, boolean declaration, boolean onChangedLine)`; `static List<Symbol> SymbolScan.of(UnifiedDiff.FileDiff file)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a file contributes to the change graph (spec §4.2). Tree-sitter tells
 * us a token is a declaration and another is a call; it does NOT tell us
 * which declaration a call resolves to, so it raises the precision of
 * classification and not the correctness of resolution. A file with no
 * grammar therefore still contributes uses -- it simply cannot claim to
 * declare anything, because a lexical scan cannot tell one from the other
 * without guessing.
 */
class SymbolScanTest {

    private static UnifiedDiff.FileDiff file(String path, String... addedLines) {
        List<UnifiedDiff.Line> lines = new java.util.ArrayList<>();
        int n = 1;
        for (String text : addedLines) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", addedLines.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,0 +1," + addedLines.length + " @@", lines)));
    }

    private static boolean has(List<SymbolScan.Symbol> symbols, String name, boolean declaration) {
        return symbols.stream().anyMatch(s -> s.name().equals(name)
                && s.declaration() == declaration);
    }

    @Test
    void aGrammarBackedFileDeclaresItsTypesAndMethods() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("src/Guards.java",
                "class JmpCtxScope {", "  void install() { helper(); }", "}"));

        assertTrue(has(symbols, "JmpCtxScope", true));
        assertTrue(has(symbols, "install", true));
        assertTrue(has(symbols, "helper", false));
    }

    /**
     * The honest floor: no grammar means uses only. Claiming a declaration
     * from a regex is exactly the guess this design refuses to make.
     */
    @Test
    void aFileWithNoGrammarContributesUsesButNoDeclarations() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("build/setup.zig",
                "const JmpCtxScope = struct {};"));

        assertTrue(has(symbols, "JmpCtxScope", false));
        assertFalse(has(symbols, "JmpCtxScope", true));
    }

    /** SymbolWords is the shared vocabulary; keywords are not symbols. */
    @Test
    void keywordsAndShortIdentifiersAreNotSymbols() {
        List<SymbolScan.Symbol> symbols = SymbolScan.of(file("build/setup.zig",
                "return id;"));

        assertFalse(symbols.stream().anyMatch(s -> s.name().equals("return")));
        assertFalse(symbols.stream().anyMatch(s -> s.name().equals("id")));
    }

    /** Context lines are scanned but marked, so an edge can require a changed line. */
    @Test
    void aSymbolOnAContextLineIsNotOnAChangedLine() {
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/Guards.java", "M", 0, 0,
                false, false, List.of(new UnifiedDiff.Hunk("@@ -1,1 +1,1 @@",
                        List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                                OptionalInt.of(1), OptionalInt.of(1), "helper();")))));

        assertTrue(SymbolScan.of(file).stream()
                .filter(s -> s.name().equals("helper")).noneMatch(SymbolScan.Symbol::onChangedLine));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.SymbolScanTest"`
Expected: FAIL — `cannot find symbol: class SymbolScan`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * One file's symbols: what it declares, what it uses, and whether each sits
 * on a changed line (spec §4.2).
 *
 * <p>Two front ends behind one shape. With a grammar, declarations come from
 * the parse tree. Without one, every occurrence is a <em>use</em> and the
 * file declares nothing -- a lexical scan cannot tell a declaration from a
 * call without guessing, and a wrong declaration would mint wrong edges
 * everywhere the name appears.</p>
 *
 * <p>Blocking: parsing and (on first use per language) a native library
 * load. Never call on the FX thread.</p>
 */
public final class SymbolScan {

    /** One symbol occurrence. */
    public record Symbol(String name, String path, boolean declaration, boolean onChangedLine) {
    }

    /** tree-sitter node types that introduce a name, across the shipped grammars. */
    private static final List<String> DECLARATION_NODES = List.of(
            "class_declaration", "interface_declaration", "record_declaration",
            "enum_declaration", "method_declaration", "constructor_declaration",
            "function_definition", "function_declarator", "function_declaration",
            "struct_specifier", "class_specifier", "enum_specifier", "type_definition",
            "field_declaration", "function_item", "struct_item", "enum_item", "impl_item",
            "class_definition", "type_alias_declaration", "object_declaration");

    private SymbolScan() {
    }

    /** {@code file}'s symbols, in source order. */
    public static List<Symbol> of(UnifiedDiff.FileDiff file) {
        Optional<TSLanguage> grammar = GrammarRegistry.forPath(file.path());
        List<Symbol> symbols = new ArrayList<>();
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                if (grammar.isPresent()) {
                    symbols.addAll(parsed(grammar.get(), file.path(), line.text(), changed));
                } else {
                    symbols.addAll(lexical(file.path(), line.text(), changed));
                }
            }
        }
        return List.copyOf(symbols);
    }

    /**
     * Line-at-a-time parsing. A diff line is not a compilation unit, so the
     * tree is usually an ERROR node with recognisable children -- which is
     * enough for "is this token introducing a name", the only question asked
     * here, and avoids reconstructing whole files from a diff.
     */
    private static List<Symbol> parsed(TSLanguage language, String path, String text,
                                       boolean changed) {
        List<Symbol> symbols = new ArrayList<>();
        TSParser parser = new TSParser();
        try {
            parser.setLanguage(language);
            TSTree tree = parser.parseString(null, text);
            walk(tree.getRootNode(), text, path, changed, false, symbols);
        } catch (RuntimeException e) {
            // A grammar that cannot parse a fragment is not a reason to lose
            // the file: fall back to the same lexical scan an ungrammared
            // file gets.
            return lexical(path, text, changed);
        }
        return symbols;
    }

    private static void walk(TSNode node, String text, String path, boolean changed,
                             boolean inDeclaration, List<Symbol> out) {
        boolean declaring = inDeclaration || DECLARATION_NODES.contains(node.getType());
        if ("identifier".equals(node.getType()) || "type_identifier".equals(node.getType())
                || "field_identifier".equals(node.getType())) {
            String name = text.substring(node.getStartByte(), node.getEndByte());
            if (SymbolWords.isSymbol(name)) {
                out.add(new Symbol(name, path, declaring, changed));
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), text, path, changed, declaring, out);
        }
    }

    private static List<Symbol> lexical(String path, String text, boolean changed) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = SymbolWords.IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            if (SymbolWords.isSymbol(name)) {
                symbols.add(new Symbol(name, path, false, changed));
            }
        }
        return symbols;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.SymbolScanTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/SymbolScan.java \
        app/src/test/java/app/drydock/review/SymbolScanTest.java
git commit -m "A file contributes what it declares and what it uses, however it is parsed

Two front ends, one shape. With a grammar, declarations come from the parse
tree. Without one, every occurrence is a use and the file declares nothing --
a lexical scan cannot tell a declaration from a call without guessing, and a
wrong declaration mints wrong edges everywhere that name appears.

That asymmetry is the honest reading of what tree-sitter buys: it tells us a
token is a declaration and another is a call, not which declaration a call
resolves to. It raises the precision of classification, not the correctness
of resolution, which is why an ungrammared file degrades to a usable graph
rather than to nothing."
```

---

### Task 10: `ChangeGraph` — one edge rule, whichever front end found the symbol

**Files:**
- Create: `app/src/main/java/app/drydock/review/ChangeGraph.java`
- Test: `app/src/test/java/app/drydock/review/ChangeGraphTest.java`

**Interfaces:**
- Consumes: `SymbolScan.of` (Task 9), `UnifiedDiff`
- Produces: `static ChangeGraph ChangeGraph.of(UnifiedDiff diff)`; `SortedSet<String> files()`; `SortedSet<String> declarationsIn(String file)`; `SortedSet<String> filesReferencedBy(String file)`; `SortedSet<String> filesReferencing(String file)`; `Optional<String> fileDeclaring(String symbol)`; `SortedSet<String> changedDeclarations()`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one matching rule (spec §4.2): a use resolves to a declaration only
 * when EXACTLY ONE changed declaration in the scope carries that name, and
 * only across files. Ambiguous names mint nothing -- a false edge sends a
 * reviewer to unrelated code and is worse than a missing one -- and
 * intra-file edges are noise from short-name matching.
 */
class ChangeGraphTest {

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        List<UnifiedDiff.Line> lines = new java.util.ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }

    @Test
    void aUniqueDeclarationUsedInAnotherFileMintsAnEdge() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"),
                file("src/Profiler.java", "void go() { new JmpCtxScope(); }"))));

        assertTrue(graph.filesReferencedBy("src/Profiler.java").contains("src/Guards.java"));
        assertTrue(graph.filesReferencing("src/Guards.java").contains("src/Profiler.java"));
    }

    /** Two declarations of one name cannot be told apart, so neither is linked. */
    @Test
    void anAmbiguousNameMintsNoEdge() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/A.java", "class Helper { }"),
                file("src/B.java", "class Helper { }"),
                file("src/C.java", "void go() { new Helper(); }"))));

        assertEquals(List.of(), List.copyOf(graph.filesReferencedBy("src/C.java")));
    }

    @Test
    void aReferenceWithinOneFileMintsNoEdge() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }", "void go() { new JmpCtxScope(); }"))));

        assertEquals(List.of(), List.copyOf(graph.filesReferencedBy("src/Guards.java")));
    }

    @Test
    void aDeclarationIsFoundByName() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"))));

        assertEquals(java.util.Optional.of("src/Guards.java"), graph.fileDeclaring("JmpCtxScope"));
    }

    /** Determinism: iteration order is a property this graph must keep (spec §9.5). */
    @Test
    void everyExposedCollectionIsSorted() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Z.java", "class Zed { }"),
                file("src/A.java", "void go() { new Zed(); }"))));

        assertEquals(List.of("src/A.java", "src/Z.java"), List.copyOf(graph.files()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ChangeGraphTest"`
Expected: FAIL — `cannot find symbol: class ChangeGraph`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The changed symbols of one scope and the references between them
 * (spec §4).
 *
 * <p>In memory, scope lifetime, rebuilt when the diff is re-read. Nothing is
 * persisted -- the reference implementation keeps a SQLite graph only because
 * it is a multi-process pipeline, and one process needs no file, no
 * invalidation story and no collection.</p>
 *
 * <p>Every exposed collection is sorted. Determinism is a requirement here,
 * not a property (spec §9.5), and hash iteration order is the cheapest way
 * to lose it.</p>
 */
public final class ChangeGraph {

    private final SortedSet<String> files;
    private final Map<String, SortedSet<String>> declarationsByFile;
    private final Map<String, String> fileByUniqueDeclaration;
    private final Map<String, SortedSet<String>> referencesOut;
    private final Map<String, SortedSet<String>> referencesIn;

    private ChangeGraph(SortedSet<String> files,
                        Map<String, SortedSet<String>> declarationsByFile,
                        Map<String, String> fileByUniqueDeclaration,
                        Map<String, SortedSet<String>> referencesOut,
                        Map<String, SortedSet<String>> referencesIn) {
        this.files = files;
        this.declarationsByFile = declarationsByFile;
        this.fileByUniqueDeclaration = fileByUniqueDeclaration;
        this.referencesOut = referencesOut;
        this.referencesIn = referencesIn;
    }

    /** Builds the graph for {@code diff}. Blocking; never call on the FX thread. */
    public static ChangeGraph of(UnifiedDiff diff) {
        Map<String, List<SymbolScan.Symbol>> scans = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            scans.put(file.path(), SymbolScan.of(file));
        }

        // A name declared in more than one changed file cannot be resolved,
        // so it is dropped rather than guessed at.
        Map<String, List<String>> declaringFiles = new TreeMap<>();
        Map<String, SortedSet<String>> declarationsByFile = new TreeMap<>();
        for (Map.Entry<String, List<SymbolScan.Symbol>> entry : scans.entrySet()) {
            for (SymbolScan.Symbol symbol : entry.getValue()) {
                if (symbol.declaration() && symbol.onChangedLine()) {
                    declaringFiles.computeIfAbsent(symbol.name(), key -> new ArrayList<>())
                            .add(entry.getKey());
                    declarationsByFile.computeIfAbsent(entry.getKey(), key -> new TreeSet<>())
                            .add(symbol.name());
                }
            }
        }
        Map<String, String> unique = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : declaringFiles.entrySet()) {
            List<String> distinct = entry.getValue().stream().distinct().toList();
            if (distinct.size() == 1) {
                unique.put(entry.getKey(), distinct.get(0));
            }
        }

        Map<String, SortedSet<String>> out = new TreeMap<>();
        Map<String, SortedSet<String>> in = new TreeMap<>();
        for (Map.Entry<String, List<SymbolScan.Symbol>> entry : scans.entrySet()) {
            for (SymbolScan.Symbol symbol : entry.getValue()) {
                String target = unique.get(symbol.name());
                // Cross-file only: an intra-file match is noise from
                // short-name matching, not a relationship worth showing.
                if (target == null || target.equals(entry.getKey())) {
                    continue;
                }
                out.computeIfAbsent(entry.getKey(), key -> new TreeSet<>()).add(target);
                in.computeIfAbsent(target, key -> new TreeSet<>()).add(entry.getKey());
            }
        }

        SortedSet<String> files = new TreeSet<>(scans.keySet());
        return new ChangeGraph(files, declarationsByFile, unique, out, in);
    }

    public SortedSet<String> files() {
        return java.util.Collections.unmodifiableSortedSet(files);
    }

    public SortedSet<String> declarationsIn(String file) {
        return declarationsByFile.getOrDefault(file, new TreeSet<>());
    }

    /** Files {@code file} references. */
    public SortedSet<String> filesReferencedBy(String file) {
        return referencesOut.getOrDefault(file, new TreeSet<>());
    }

    /** Files that reference {@code file}. */
    public SortedSet<String> filesReferencing(String file) {
        return referencesIn.getOrDefault(file, new TreeSet<>());
    }

    /** The one changed file declaring {@code symbol}, when exactly one does. */
    public Optional<String> fileDeclaring(String symbol) {
        return Optional.ofNullable(fileByUniqueDeclaration.get(symbol));
    }

    /** Every uniquely-declared changed symbol name. */
    public SortedSet<String> changedDeclarations() {
        return new TreeSet<>(fileByUniqueDeclaration.keySet());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.ChangeGraphTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ChangeGraph.java \
        app/src/test/java/app/drydock/review/ChangeGraphTest.java
git commit -m "The change graph resolves a name only when exactly one file declares it

One matching rule whichever front end found the symbol: a use resolves to a
declaration only when exactly one changed declaration in the scope carries
that name, and only across files. An ambiguous name mints nothing, because a
false edge sends a reviewer to unrelated code and is worse than a missing
one; an intra-file match is noise from short-name matching.

In memory and scope-lifetime, with no file behind it. The reference
implementation persists its graph only because it is a multi-process
pipeline; one process needs no invalidation story and nothing to collect.

Every exposed collection is sorted, because determinism here is a
requirement rather than a property and hash iteration order is the cheapest
way to lose it."
```

---

### Task 11: `Graphs` — Kahn and Tarjan, with a caller-supplied total tie-break

**Files:**
- Create: `app/src/main/java/app/drydock/review/Graphs.java`
- Test: `app/src/test/java/app/drydock/review/GraphsTest.java`

**Interfaces:**
- Produces: `static <T> List<List<T>> Graphs.topologicalOrder(SortedSet<T> nodes, Function<T, SortedSet<T>> dependsOn, Comparator<T> tieBreak)` — returns units in reading order, each unit a strongly-connected component (a single-element list for an ordinary node, several for a cycle)

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Order and cycles (spec §6.1). Foundation first: if A is referenced by B,
 * A is read before B. A cycle is collapsed into one named unit rather than
 * broken arbitrarily -- a cycle among changed symbols is a fact about the
 * change worth showing, and a silent arbitrary break is the unexplained
 * ordering this whole feature exists to remove.
 */
class GraphsTest {

    private static SortedSet<String> set(String... values) {
        return new TreeSet<>(List.of(values));
    }

    private static List<List<String>> order(Map<String, SortedSet<String>> dependsOn) {
        return Graphs.topologicalOrder(new TreeSet<>(dependsOn.keySet()),
                node -> dependsOn.getOrDefault(node, new TreeSet<>()),
                Comparator.naturalOrder());
    }

    @Test
    void aDependencyIsReadBeforeItsDependent() {
        assertEquals(List.of(List.of("guards"), List.of("profiler")),
                order(Map.of("profiler", set("guards"), "guards", set())));
    }

    @Test
    void independentNodesFallBackToTheTieBreak() {
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")),
                order(Map.of("c", set(), "a", set(), "b", set())));
    }

    @Test
    void aCycleBecomesOneUnitHoldingItsMembers() {
        List<List<String>> result = order(Map.of("a", set("b"), "b", set("a"), "c", set("a")));

        assertEquals(List.of("a", "b"), result.get(0));
        assertEquals(List.of("c"), result.get(1));
    }

    /**
     * Determinism, pinned: the same graph presented in a different insertion
     * order must produce the identical result (spec §9.5).
     */
    @Test
    void theOrderDoesNotDependOnInsertionOrder() {
        assertEquals(order(Map.of("a", set(), "b", set("a"), "c", set("b"))),
                order(Map.of("c", set("b"), "a", set(), "b", set("a"))));
    }

    @Test
    void anEmptyGraphOrdersToNothing() {
        assertEquals(List.of(), order(Map.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.GraphsTest"`
Expected: FAIL — `cannot find symbol: class Graphs`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Kahn and Tarjan (spec §2.3, §6.1).
 *
 * <p>Hand-rolled rather than taken from a graph library: what this design
 * asks of a graph is a topological sort, strongly-connected components and
 * reachability over tens of nodes, and a library costs a megabyte of
 * transitives, an entry in the jlink module list that a test pins against
 * jdeps, and a POM dependency the jbang jar bundles nothing of.</p>
 *
 * <p>The tie-break is supplied by the caller and must be TOTAL: two runs may
 * not order equal units differently (spec §9.5).</p>
 */
public final class Graphs {

    private Graphs() {
    }

    /**
     * {@code nodes} in reading order, foundation first. Each entry is one
     * unit: a single node, or the members of a cycle collapsed together and
     * ordered by {@code tieBreak}.
     */
    public static <T> List<List<T>> topologicalOrder(SortedSet<T> nodes,
                                                     Function<T, SortedSet<T>> dependsOn,
                                                     Comparator<T> tieBreak) {
        List<List<T>> components = stronglyConnected(nodes, dependsOn, tieBreak);

        Map<T, Integer> componentOf = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (T member : components.get(index)) {
                componentOf.put(member, index);
            }
        }

        // Condense to a DAG over components, then Kahn it.
        Map<Integer, SortedSet<Integer>> prerequisites = new TreeMap<>();
        Map<Integer, SortedSet<Integer>> dependents = new TreeMap<>();
        for (int index = 0; index < components.size(); index++) {
            prerequisites.put(index, new TreeSet<>());
            dependents.put(index, new TreeSet<>());
        }
        for (T node : nodes) {
            for (T prerequisite : dependsOn.apply(node)) {
                Integer from = componentOf.get(prerequisite);
                Integer to = componentOf.get(node);
                if (from == null || to == null || from.equals(to)) {
                    continue;
                }
                prerequisites.get(to).add(from);
                dependents.get(from).add(to);
            }
        }

        Comparator<Integer> byFirstMember =
                Comparator.comparing(index -> components.get(index).get(0), tieBreak);
        TreeSet<Integer> ready = new TreeSet<>(byFirstMember);
        for (int index = 0; index < components.size(); index++) {
            if (prerequisites.get(index).isEmpty()) {
                ready.add(index);
            }
        }

        List<List<T>> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            Integer next = ready.first();
            ready.remove(next);
            ordered.add(components.get(next));
            for (Integer dependent : dependents.get(next)) {
                SortedSet<Integer> remaining = prerequisites.get(dependent);
                remaining.remove(next);
                if (remaining.isEmpty()) {
                    ready.add(dependent);
                }
            }
        }
        return List.copyOf(ordered);
    }

    /** Tarjan, iterative so a deep graph cannot overflow the stack. */
    private static <T> List<List<T>> stronglyConnected(SortedSet<T> nodes,
                                                       Function<T, SortedSet<T>> edges,
                                                       Comparator<T> tieBreak) {
        Map<T, Integer> index = new LinkedHashMap<>();
        Map<T, Integer> lowLink = new LinkedHashMap<>();
        Deque<T> stack = new ArrayDeque<>();
        java.util.Set<T> onStack = new java.util.LinkedHashSet<>();
        List<List<T>> components = new ArrayList<>();
        int[] counter = {0};

        for (T root : nodes) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<T> work = new ArrayDeque<>();
            Deque<java.util.Iterator<T>> pending = new ArrayDeque<>();
            work.push(root);
            pending.push(edges.apply(root).iterator());
            index.put(root, counter[0]);
            lowLink.put(root, counter[0]++);
            stack.push(root);
            onStack.add(root);

            while (!work.isEmpty()) {
                T node = work.peek();
                java.util.Iterator<T> children = pending.peek();
                if (children.hasNext()) {
                    T child = children.next();
                    if (!nodes.contains(child)) {
                        continue;
                    }
                    if (!index.containsKey(child)) {
                        index.put(child, counter[0]);
                        lowLink.put(child, counter[0]++);
                        stack.push(child);
                        onStack.add(child);
                        work.push(child);
                        pending.push(edges.apply(child).iterator());
                    } else if (onStack.contains(child)) {
                        lowLink.put(node, Math.min(lowLink.get(node), index.get(child)));
                    }
                } else {
                    work.pop();
                    pending.pop();
                    if (!work.isEmpty()) {
                        T parent = work.peek();
                        lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(node)));
                    }
                    if (lowLink.get(node).equals(index.get(node))) {
                        List<T> component = new ArrayList<>();
                        T member;
                        do {
                            member = stack.pop();
                            onStack.remove(member);
                            component.add(member);
                        } while (!member.equals(node));
                        component.sort(tieBreak);
                        components.add(component);
                    }
                }
            }
        }
        components.sort(Comparator.comparing(c -> c.get(0), tieBreak));
        return components;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.GraphsTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/Graphs.java \
        app/src/test/java/app/drydock/review/GraphsTest.java
git commit -m "Kahn and Tarjan, hand-rolled, with a total tie-break

What this design asks of a graph is a topological sort, strongly-connected
components and reachability over tens of nodes. jgrapht-core costs 1.27MB
plus jheaps and an arbitrary-precision math transitive, an entry in the
jlink --add-modules list that RuntimeImageModuleListTest pins against jdeps,
and a POM dependency the jbang jar bundles nothing of. Three textbook
algorithms do not buy that.

A cycle collapses into one unit rather than being broken arbitrarily: a
cycle among changed symbols is a fact about the change worth showing, and a
silent arbitrary break is the unexplained ordering this feature exists to
remove. Tarjan is iterative so a deep graph cannot overflow the stack, and
the tie-break is caller-supplied and must be total -- two runs ordering equal
units differently is how the determinism requirement gets lost."
```

---

### Task 12: `Sections` — components, header conventions, hub titles, overlap

**Files:**
- Create: `app/src/main/java/app/drydock/review/Sections.java`
- Test: `app/src/test/java/app/drydock/review/SectionsTest.java`

**Interfaces:**
- Consumes: `ChangeGraph` (Task 10), `Graphs.topologicalOrder` (Task 11), `UnifiedDiff`
- Produces: `record Sections.Section(String title, List<String> files, List<String> hunkIds, Optional<String> hubSymbol, List<String> cycleWith)`; `static List<Section> Sections.of(UnifiedDiff diff, ChangeGraph graph)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sections follow the code's structure, not its folders (spec §5).
 *
 * <p>The failure this replaces, measured on a real C++ change: cards reading
 * "main/cpp · 12 files", "test/cpp · 4 files", "cpp/hotspot · 6 files" --
 * each individually correct and collectively saying nothing, because the
 * grouping had no structural input at all.</p>
 */
class SectionsTest {

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        List<UnifiedDiff.Line> lines = new java.util.ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }

    private static List<Sections.Section> sectionsOf(UnifiedDiff diff) {
        return Sections.of(diff, ChangeGraph.of(diff));
    }

    private static Sections.Section sectionContaining(List<Sections.Section> sections, String file) {
        return sections.stream().filter(s -> s.files().contains(file)).findFirst().orElseThrow();
    }

    /** The convention a C or C++ change is unreadable without. */
    @Test
    void aHeaderGroupsWithItsSameBasenameImplementation() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/guards.cpp", "void install() { }"))));

        assertTrue(sectionContaining(sections, "src/guards.h").files().contains("src/guards.cpp"));
    }

    /**
     * The counters.h case from the reference output: a header with no changed
     * symbol of its own still belongs with the file that pulls it in.
     */
    @Test
    void aHeaderGroupsWithAChangedImplementationThatReferencesIt() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/counters.h", "#define FAULTS 1"),
                file("src/profiler.cpp", "#include \"counters.h\"", "void go() { }"))));

        assertTrue(sectionContaining(sections, "src/profiler.cpp").files().contains("src/counters.h"));
    }

    /** Overlap is the point (spec §5.6): a shared header appears in both. */
    @Test
    void aFileNeededByTwoSectionsAppearsInBoth() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/a.cpp", "#include \"guards.h\"", "void a() { new JmpCtxScope(); }"),
                file("src/b.cpp", "#include \"guards.h\"", "void b() { new JmpCtxScope(); }"))));

        long appearances = sections.stream().filter(s -> s.files().contains("src/guards.h")).count();
        assertTrue(appearances >= 2, "a shared header must appear wherever it is needed");
    }

    /** Foundation first: the guard is read before what uses it. */
    @Test
    void sectionsAreOrderedByDependencyDirection() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))));

        assertEquals("src/guards.cpp", sections.get(0).files().get(0));
    }

    /** A test referencing a changed symbol lands with it -- no path-based split. */
    @Test
    void aTestReferencingAChangedSymbolIsInThatSymbolsSection() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("test/guards_ut.cpp", "void t() { new JmpCtxScope(); }"))));

        assertTrue(sectionContaining(sections, "src/guards.cpp")
                .files().contains("test/guards_ut.cpp"));
    }

    /** A test referencing nothing changed is its own section, honestly. */
    @Test
    void aTestReferencingNothingChangedFormsItsOwnSection() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("test/unrelated_ut.cpp", "void t() { checkSomethingElse(); }"))));

        assertEquals(List.of("test/unrelated_ut.cpp"),
                sectionContaining(sections, "test/unrelated_ut.cpp").files());
    }

    /** The name is the thing, not the folder. */
    @Test
    void aSectionIsTitledByItsHighestFanInChangedSymbol() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/a.cpp", "void a() { new JmpCtxScope(); }"),
                file("src/b.cpp", "void b() { new JmpCtxScope(); }"))));

        assertTrue(sections.get(0).title().startsWith("JmpCtxScope"),
                "expected a hub-symbol title, got: " + sections.get(0).title());
    }

    /** With nothing to consult, today's behaviour survives unchanged. */
    @Test
    void anEdgelessDiffFallsBackToDirectoryClustering() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("web/a.zzz", "nothing"), file("web/b.zzz", "nothing")));

        assertEquals(FallbackIntents.group(diff).size(), sectionsOf(diff).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.SectionsTest"`
Expected: FAIL — `cannot find symbol: class Sections`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The change's sections: connected components of the file-level reference
 * graph, plus the two conventions a C or C++ change is unreadable without
 * (spec §5.2).
 *
 * <p><strong>Sections overlap.</strong> A file appears in every section that
 * needs it to be understood -- a header belongs with its implementation AND
 * with everything that references it, and with disjoint membership one of
 * those has to lose. The reviewed flag is keyed to hunk content, so a file
 * shown three times is still read once (spec §5.6, §9).</p>
 *
 * <p>Tests are NOT split out. A test references the symbol under test, so
 * the graph already places it; splitting on {@code /test/} would be a path
 * heuristic drawing a boundary through a structurally sound group, which is
 * the very failure this class replaces.</p>
 */
public final class Sections {

    /** One section. {@code cycleWith} is non-empty when it is part of a dependency cycle. */
    public record Section(String title, List<String> files, List<String> hunkIds,
                          Optional<String> hubSymbol, List<String> cycleWith) {
        public Section {
            files = List.copyOf(files);
            hunkIds = List.copyOf(hunkIds);
            cycleWith = List.copyOf(cycleWith);
        }
    }

    private Sections() {
    }

    /** {@code diff}'s sections, in reading order. */
    public static List<Section> of(UnifiedDiff diff, ChangeGraph graph) {
        Map<String, SortedSet<String>> neighbours = neighbours(diff, graph);
        boolean anyEdge = neighbours.values().stream().anyMatch(set -> !set.isEmpty());
        if (!anyEdge) {
            // Nothing structural to consult: today's (kind, directory)
            // clustering is still the best available guess.
            return fromFallback(diff);
        }

        List<List<String>> units = Graphs.topologicalOrder(
                new TreeSet<>(neighbours.keySet()),
                file -> graph.filesReferencedBy(file),
                Comparator.naturalOrder());

        List<Section> sections = new ArrayList<>();
        for (List<String> unit : units) {
            Set<String> files = new LinkedHashSet<>(unit);
            for (String file : unit) {
                files.addAll(neighbours.getOrDefault(file, new TreeSet<>()));
            }
            List<String> ordered = new ArrayList<>(files);
            ordered.sort(Comparator.naturalOrder());
            // The unit's own members lead: they are what the section is
            // about, and the pulled-in neighbours are context.
            ordered.sort(Comparator.comparing(file -> unit.contains(file) ? 0 : 1));
            Optional<String> hub = hubOf(ordered, graph);
            sections.add(new Section(
                    title(ordered, hub),
                    ordered,
                    hunkIdsOf(diff, ordered),
                    hub,
                    unit.size() > 1 ? unit : List.of()));
        }
        return List.copyOf(sections);
    }

    /**
     * What each file is grouped with: its references, its same-basename
     * counterpart, and any changed file that references it at file level.
     */
    private static Map<String, SortedSet<String>> neighbours(UnifiedDiff diff, ChangeGraph graph) {
        Map<String, SortedSet<String>> result = new TreeMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            result.put(file.path(), new TreeSet<>());
        }
        for (String file : result.keySet()) {
            result.get(file).addAll(graph.filesReferencedBy(file));
            result.get(file).addAll(graph.filesReferencing(file));
        }
        // Header convention: same basename, different extension.
        for (String left : new TreeSet<>(result.keySet())) {
            for (String right : new TreeSet<>(result.keySet())) {
                if (!left.equals(right) && basename(left).equals(basename(right))) {
                    result.get(left).add(right);
                }
            }
        }
        // A header a changed file names in an include or import belongs with
        // it even when the header declares no changed symbol of its own.
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (String other : new TreeSet<>(result.keySet())) {
                if (!other.equals(file.path()) && mentionsFileName(file, other)) {
                    result.get(file.path()).add(other);
                    result.get(other).add(file.path());
                }
            }
        }
        return result;
    }

    private static boolean mentionsFileName(UnifiedDiff.FileDiff file, String other) {
        String name = other.substring(other.lastIndexOf('/') + 1);
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (line.text().contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String basename(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The section's most-referenced changed symbol: what the section is about. */
    private static Optional<String> hubOf(List<String> files, ChangeGraph graph) {
        String best = null;
        int bestFanIn = 0;
        for (String file : files) {
            for (String symbol : graph.declarationsIn(file)) {
                int fanIn = graph.filesReferencing(file).size();
                if (fanIn > bestFanIn || (fanIn == bestFanIn && best != null
                        && symbol.compareTo(best) < 0)) {
                    best = symbol;
                    bestFanIn = fanIn;
                }
            }
        }
        return Optional.ofNullable(bestFanIn > 0 ? best : null);
    }

    private static String title(List<String> files, Optional<String> hub) {
        String count = files.size() + (files.size() == 1 ? " file" : " files");
        return hub.map(symbol -> symbol + " · " + count)
                // No symbol dominates: the directory tail is still the most
                // specific true thing that can be said.
                .orElseGet(() -> FallbackIntents.directoryOf(files.get(0)) + " · " + count);
    }

    private static List<String> hunkIdsOf(UnifiedDiff diff, List<String> files) {
        List<String> ids = new ArrayList<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            if (!files.contains(file.path())) {
                continue;
            }
            for (int hunk = 0; hunk < file.hunks().size(); hunk++) {
                ids.add(ReviewIntent.hunkId(file.path(), hunk));
            }
        }
        return ids;
    }

    private static List<Section> fromFallback(UnifiedDiff diff) {
        List<Section> sections = new ArrayList<>();
        for (ReviewIntent intent : FallbackIntents.group(diff)) {
            sections.add(new Section(intent.title(), intent.files(), intent.hunkIds(),
                    Optional.empty(), List.of()));
        }
        return List.copyOf(sections);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.SectionsTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/Sections.java \
        app/src/test/java/app/drydock/review/SectionsTest.java
git commit -m "Sections follow the code's structure, not its folders

Measured on a real C++ change, the old grouping produced main/cpp · 12
files, test/cpp · 4 files, cpp/hotspot · 6 files: each card individually
correct, the rail collectively saying nothing, because (kind, directory) has
no structural input at all. Sections are now connected components of the
file-level reference graph, ordered foundation-first, titled by the
component's highest-fan-in changed symbol.

Two conventions carried in because a C or C++ change is unreadable without
them: a .h groups with its same-basename .cpp, and a header groups with any
changed file that names it even when the header declares no changed symbol
of its own -- the counters.h case.

Sections overlap. A header belongs with its implementation AND with
everything referencing it, and with disjoint membership one of those has to
lose. Tests are not split out: a test references the symbol under test, so
the graph already places it, and splitting on /test/ would draw a path-based
boundary through a structurally sound group -- the very failure this
replaces. With no edges to consult, today's directory clustering survives
untouched."
```

---

### Task 13: The rail renders computed sections, and determinism is pinned

**Files:**
- Modify: `app/src/main/java/app/drydock/review/IntentGrouping.java` (`intentsFor`)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (build the graph off the FX thread, hand it to `IntentGrouping`)
- Test: `app/src/test/java/app/drydock/review/SectionDeterminismTest.java`

**Interfaces:**
- Consumes: `Sections.of` (Task 12), `ChangeGraph.of` (Task 10)
- Produces: `List<ReviewIntent> IntentGrouping.intentsFor(String scopeId, UnifiedDiff diff, Optional<ChangeGraph> graph)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calling the computed layer stable is a claim the code has to keep
 * (spec §9.5). The cheapest way to lose it is a hash-ordered collection, and
 * the hardest place to notice is a single JVM, which usually agrees with
 * itself. The cross-process half of that check is the running-app pass; this
 * pins the in-process half and the shape the other half compares.
 */
class SectionDeterminismTest {

    private static UnifiedDiff diff() {
        List<UnifiedDiff.FileDiff> files = new java.util.ArrayList<>();
        for (String path : List.of("src/z.cpp", "src/a.cpp", "src/m.h", "src/m.cpp")) {
            files.add(new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                    List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                            UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1),
                            "void go() { helperOne(); }"))))));
        }
        return new UnifiedDiff(files);
    }

    private static List<String> titles() {
        UnifiedDiff diff = diff();
        return Sections.of(diff, ChangeGraph.of(diff)).stream()
                .map(Sections.Section::title).toList();
    }

    @Test
    void theSameDiffProducesTheSameSectionsEveryTime() {
        assertEquals(titles(), titles());
    }

    @Test
    void theSameDiffProducesTheSameHunkOrderEveryTime() {
        UnifiedDiff diff = diff();
        assertEquals(Sections.of(diff, ChangeGraph.of(diff)).stream()
                        .map(Sections.Section::hunkIds).toList(),
                Sections.of(diff, ChangeGraph.of(diff)).stream()
                        .map(Sections.Section::hunkIds).toList());
    }

    /** A reviewer's grouping still wins; the computed one is the fallback. */
    @Test
    void aReviewerGroupingIsNotRecomputed() {
        IntentGrouping grouping = new IntentGrouping();
        ReviewIntent supplied = new ReviewIntent("agent-1", 1, "Crash-protected resolve()",
                ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.HIGH, "",
                List.of(ReviewIntent.hunkId("src/a.cpp", 0)), java.util.Optional.empty(), false);
        grouping.set("scope-1", List.of(supplied));

        UnifiedDiff diff = diff();
        List<ReviewIntent> intents = grouping.intentsFor("scope-1", diff,
                java.util.Optional.of(ChangeGraph.of(diff)));

        assertEquals(List.of("Crash-protected resolve()"),
                intents.stream().map(ReviewIntent::title).toList());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.SectionDeterminismTest"`
Expected: FAIL — `intentsFor` takes two arguments

- [ ] **Step 3: Write minimal implementation**

```java
    /**
     * {@code scopeId}'s intents: the reviewer's grouping when there is one,
     * otherwise the computed sections -- and, with no graph to compute from,
     * {@link FallbackIntents}' clustering of {@code diff}.
     *
     * <p>A reviewer's grouping is never re-sorted or re-drawn. It came from
     * something that read the change; recomputing over it would be drydock
     * overruling the reviewer.</p>
     */
    public List<ReviewIntent> intentsFor(String scopeId, UnifiedDiff diff,
                                         Optional<ChangeGraph> graph) {
        List<ReviewIntent> supplied = byScope.get(scopeId);
        if (supplied != null) {
            return supplied;
        }
        if (graph.isEmpty()) {
            return FallbackIntents.group(diff);
        }
        List<ReviewIntent> computed = new ArrayList<>();
        int number = 1;
        for (Sections.Section section : Sections.of(diff, graph.get())) {
            computed.add(new ReviewIntent("computed:" + number, number,
                    section.title(), ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.NONE,
                    rationale(section), section.hunkIds(), Optional.empty(), false));
            number++;
        }
        return List.copyOf(computed);
    }

    /**
     * What a computed section says for itself with no agent to name it: the
     * structural facts, and the cycle when it is in one.
     */
    private static String rationale(Sections.Section section) {
        String base = section.files().size() + " files  ·  "
                + section.hunkIds().size() + " hunks  ·  grouped by drydock, no reviewer has run";
        return section.cycleWith().isEmpty()
                ? base
                : base + "  ·  in a dependency cycle with " + String.join(", ", section.cycleWith());
    }
```

In `SessionReviewView`, build the graph on the existing background executor when a diff arrives and pass `Optional.of(graph)` on the render path; while it is being built, pass `Optional.empty()` so the rail shows the directory clustering rather than nothing.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.SectionDeterminismTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/IntentGrouping.java \
        app/src/main/java/app/drydock/ui/review/SessionReviewView.java \
        app/src/test/java/app/drydock/review/SectionDeterminismTest.java
git commit -m "The rail renders computed sections when no reviewer has run

Three sources, one ladder: a reviewer's grouping wins and is never re-sorted,
because it came from something that read the change and recomputing over it
would be drydock overruling the reviewer. Otherwise the computed sections.
With no graph yet -- it is built off the FX thread and takes a moment -- the
directory clustering, so the rail is never empty while waiting.

A computed section says the structural facts for itself, including the cycle
it is in when it is in one, which is the part a directory title could never
carry."
```

---

### Task 14: `review_scope` offers the computed sections

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/ReviewToolCodec.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (the `review_scope` descriptor, ~90–99)
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterSectionsTest.java`

**Interfaces:**
- Consumes: `Sections.Section` (Task 12)
- Produces: `review_scope` accepts `include: "sections"`; the response gains `sections: [{title, files, hunkIds, hubSymbol?}]`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent has to be able to see the grouping it is being asked to name
 * (spec §5.5). An agent that cannot regroups from scratch and loses the
 * header conventions and the dependency order -- arriving back at prose
 * titles over structurally worse sections.
 */
class McpToolRouterSectionsTest extends McpRouterFixture {

    @Test
    void reviewScopeOmitsSectionsUnlessAsked() {
        String response = callReviewScope(scopeId(), null);

        assertFalse(response.contains("\"sections\""));
    }

    @Test
    void reviewScopeIncludesSectionsWhenAsked() {
        String response = callReviewScope(scopeId(), "sections");

        assertTrue(response.contains("\"sections\""));
        assertTrue(response.contains("\"hunkIds\""));
    }

    /** An unknown include is ignored, not an error: it is an optional read. */
    @Test
    void anUnknownIncludeIsIgnored() {
        String response = callReviewScope(scopeId(), "nonsense");

        assertFalse(response.contains("\"sections\""));
    }
}
```

Add `McpRouterFixture` beside it, modelled on the existing `McpToolRouterReviewTest` setup, exposing `scopeId()` and `callReviewScope(String scopeId, String include)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpToolRouterSectionsTest"`
Expected: FAIL — no `sections` key is ever emitted

- [ ] **Step 3: Write minimal implementation**

Descriptor gains the parameter:

```java
                                .put("include", schemaString("Optional extras, comma-separated. "
                                        + "\"sections\" returns drydock's computed grouping: "
                                        + "accept and name it, or regroup deliberately."))
```

Codec gains the encoder:

```java
    /** drydock's computed grouping, offered so an agent can accept-and-name it. */
    static JsonValue sectionsToJson(List<Sections.Section> sections) {
        List<JsonValue> entries = new ArrayList<>();
        for (Sections.Section section : sections) {
            JsonObject obj = JsonObject.empty();
            obj.put("title", new JsonString(section.title()));
            obj.put("files", new JsonArray(section.files().stream()
                    .map(file -> (JsonValue) new JsonString(file)).toList()));
            obj.put("hunkIds", new JsonArray(section.hunkIds().stream()
                    .map(id -> (JsonValue) new JsonString(id)).toList()));
            section.hubSymbol().ifPresent(hub -> obj.put("hubSymbol", new JsonString(hub)));
            entries.add(obj);
        }
        return new JsonArray(entries);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpToolRouterSectionsTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/mcp/ app/src/test/java/app/drydock/mcp/
git commit -m "review_scope can hand the agent the grouping it is being asked to name

An earlier draft deferred this on the grounds that the agent can read the
diff itself. The reference change settles it the other way: drydock now has
a grouping worth proposing, and an agent that cannot see it regroups from
scratch and loses the header conventions and the dependency order, arriving
back at prose titles over structurally worse sections.

Optional, and off by default -- the include exists so accept-and-name is the
cheap path and regrouping is the deliberate one. The agent's grouping still
wins when it sends one."
```

---

### Task 15: The relevance filter widens to referenced declarations

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (the `couldMatter` call site added in Task 6)
- Test: `app/src/test/java/app/drydock/review/BaseMoveTest.java` (add one case)

**Interfaces:**
- Consumes: `ChangeGraph.filesReferencedBy` (Task 10), `BaseMove.couldMatter` (Task 5)
- Produces: no new signature — this is the widening Task 5 was built to accept

- [ ] **Step 1: Write the failing test**

```java
    /**
     * The half Task 5 deferred: a base commit touching a file this scope does
     * not change but DOES reference can have moved the ground under an
     * approval, and the graph is what makes that visible.
     */
    @Test
    void aBaseMoveTouchingAReferencedButUnchangedFileMatters() {
        assertTrue(BaseMove.couldMatter(delta("src/support.h"),
                List.of("src/guards.cpp", "src/support.h")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.BaseMoveTest"`
Expected: PASS at the unit level (the signature already accepts a wider set) — the real gap is the *call site*, which still passes only the scope's own files. Confirm by inspection: `rg -n "couldMatter" app/src/main` must show the argument being widened in Step 3.

- [ ] **Step 3: Write minimal implementation**

At the call site in `SessionReviewView`, replace the `filesOf(intent)`
argument added in Task 6 with the wider set:

```java
    /**
     * Which files a base move has to touch before it can matter to this
     * scope: the files it changes, plus the files declaring symbols those
     * changes reference. Spec §9.2 -- the second half needs the graph, which
     * is why it arrives a phase after the first.
     */
    private Collection<String> filesAffectingScope() {
        SortedSet<String> relevant = new TreeSet<>(changedFiles());
        changeGraph().ifPresent(graph -> {
            for (String file : changedFiles()) {
                relevant.addAll(graph.filesReferencedBy(file));
            }
        });
        return relevant;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.BaseMoveTest" --tests "app.drydock.ui.review.ReviewHunkProgressTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/SessionReviewView.java \
        app/src/test/java/app/drydock/review/BaseMoveTest.java
git commit -m "Staleness also notices a base move in a file this scope only references

The half deferred at Phase 1: a base commit touching a file this scope does
not change but does reference can move the ground under an approval, and
only the change graph makes that visible. couldMatter already took a
Collection for exactly this, so the widening is at the call site and no
caller moved.

The filter stays file-level and lexical. A base change that alters behaviour
without touching a file this scope names or references still marks nothing --
that is §4.3's boundary, and closing it is the agent recheck's job."
```

---

### Phase 2 gate

- [ ] **Run the full suite:** `./gradlew :app:test` (from the controlling session)
- [ ] **Determinism across processes:** run `./gradlew :app:test --tests "app.drydock.review.SectionDeterminismTest"` twice in separate JVMs and diff the printed section titles. A hash-ordered collection usually agrees with itself inside one JVM, which is why this check has to leave it.
- [ ] **Run the app on a real C++ change** and screenshot the rail. The pass condition is that it no longer reads `main/cpp · 12 files` — it should name symbols, pair headers with implementations, and put a new guard's section ahead of the section using it.
- [ ] **Check the packaging cost:** `./gradlew :app:runtimeImage` and confirm the image grows by roughly 7 MB and still launches. `RuntimeImageModuleListTest` will fail if the jlink `--add-modules` list stopped covering the jar.

---

# Phase 3 — Reading path, links, recheck

### Task 16: `OutOfDiffFanIn` — one bounded `git grep`, locations kept

**Files:**
- Create: `app/src/main/java/app/drydock/review/OutOfDiffFanIn.java`
- Test: `app/src/test/java/app/drydock/review/OutOfDiffFanInTest.java`

**Interfaces:**
- Consumes: `ChangeGraph.changedDeclarations` (Task 10), `ProcessRunner`
- Produces: `record OutOfDiffFanIn.Occurrence(String file, int line, String text)`; `record OutOfDiffFanIn.Result(Map<String, List<Occurrence>> bySymbol, boolean unavailable)`; `static Result OutOfDiffFanIn.scan(Path worktree, ChangeGraph graph, Set<String> changedFiles)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strongest entry-point signal (spec §4.3): a changed symbol called from
 * OUTSIDE the change. A diff-scoped graph cannot see it, and the reference
 * implementation buys it with a repository-wide ingest this codebase has
 * twice refused to build. One bounded git grep gets it instead.
 *
 * <p>The locations are kept, not just counted: a fan-in with nowhere to click
 * is a statistic, not comprehension, and it lands exactly when a reviewer
 * wants to look.</p>
 */
class OutOfDiffFanInTest {

    @Test
    void parsingKeepsFileLineAndText() {
        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(
                "src/other.cpp:42:  JmpCtxScope guard;\n", Set.of("src/guards.cpp"));

        assertEquals(1, parsed.size());
        assertEquals("src/other.cpp", parsed.get(0).file());
        assertEquals(42, parsed.get(0).line());
        assertTrue(parsed.get(0).text().contains("JmpCtxScope"));
    }

    /** Occurrences inside the change are not "outside" it. */
    @Test
    void matchesInChangedFilesAreExcluded() {
        assertEquals(List.of(), OutOfDiffFanIn.parse(
                "src/guards.cpp:9:  JmpCtxScope guard;\n", Set.of("src/guards.cpp")));
    }

    @Test
    void aMalformedLineIsSkippedRatherThanFatal() {
        assertEquals(List.of(), OutOfDiffFanIn.parse("not a grep line\n", Set.of()));
    }

    /** A path containing a colon must not be truncated at it. */
    @Test
    void aPathContainingAColonParsesBackToItself() {
        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(
                "src/a:b.cpp:7:x();\n", Set.of());

        assertEquals("src/a:b.cpp", parsed.get(0).file());
        assertEquals(7, parsed.get(0).line());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.OutOfDiffFanInTest"`
Expected: FAIL — `cannot find symbol: class OutOfDiffFanIn`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where a changed symbol is used outside the change (spec §4.3).
 *
 * <p>One spawn for the whole scope, not one per symbol: every uniquely-named
 * changed declaration goes into a patterns file and {@code git grep -n -F -f}
 * reads them all at once.</p>
 *
 * <p>A lexical count of occurrences, not a call count -- said in the same
 * voice the symbol popover already uses. The locations are kept because a
 * fan-in with nowhere to click is a statistic rather than comprehension.</p>
 */
public final class OutOfDiffFanIn {

    private static final Logger LOG = Logger.getLogger(OutOfDiffFanIn.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public record Occurrence(String file, int line, String text) {
    }

    /** {@code unavailable} means the scan could not run: absent, not zero. */
    public record Result(Map<String, List<Occurrence>> bySymbol, boolean unavailable) {
    }

    private OutOfDiffFanIn() {
    }

    /** Blocking; never call on the FX thread. */
    public static Result scan(Path worktree, ChangeGraph graph, Set<String> changedFiles) {
        if (graph.changedDeclarations().isEmpty()) {
            return new Result(Map.of(), false);
        }
        Path patterns = null;
        try {
            patterns = Files.createTempFile("drydock-fanin", ".txt");
            Files.writeString(patterns, String.join("\n", graph.changedDeclarations()),
                    StandardCharsets.UTF_8);
            ProcessResult result = ProcessRunner.run(List.of("git", "grep", "-n", "-F", "-f",
                    patterns.toString(), "--end-of-options"), worktree, TIMEOUT);
            // git grep exits 1 for "no matches", which is a valid empty answer
            // and not a failure. Anything else is.
            if (result.exitCode() > 1) {
                LOG.log(Level.WARNING, "git grep for out-of-diff fan-in failed: "
                        + ProcessRunner.excerpt(result.stderr()));
                return new Result(Map.of(), true);
            }
            Map<String, List<Occurrence>> bySymbol = new TreeMap<>();
            List<Occurrence> all = parse(result.stdout(), changedFiles);
            for (String symbol : graph.changedDeclarations()) {
                List<Occurrence> hits = all.stream()
                        .filter(occurrence -> occurrence.text().contains(symbol)).toList();
                if (!hits.isEmpty()) {
                    bySymbol.put(symbol, hits);
                }
            }
            return new Result(bySymbol, false);
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "git grep for out-of-diff fan-in timed out", e);
            return new Result(Map.of(), true);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "git grep for out-of-diff fan-in could not run", e);
            return new Result(Map.of(), true);
        } finally {
            if (patterns != null) {
                try {
                    Files.deleteIfExists(patterns);
                } catch (IOException e) {
                    LOG.log(Level.FINE, "could not remove fan-in patterns file", e);
                }
            }
        }
    }

    /** Parses {@code path:line:text} rows, dropping anything inside the change. */
    static List<Occurrence> parse(String stdout, Set<String> changedFiles) {
        List<Occurrence> occurrences = new ArrayList<>();
        for (String row : stdout.split("\n")) {
            if (row.isBlank()) {
                continue;
            }
            // A path may contain ':', so the line number is the LAST colon
            // before the text, not the first.
            int second = -1;
            int first = row.indexOf(':');
            while (first >= 0) {
                int next = row.indexOf(':', first + 1);
                if (next < 0) {
                    break;
                }
                if (isDigits(row.substring(first + 1, next))) {
                    second = next;
                    break;
                }
                first = next;
            }
            if (first < 0 || second < 0) {
                continue;
            }
            String file = row.substring(0, first);
            if (changedFiles.contains(file)) {
                continue;
            }
            try {
                occurrences.add(new Occurrence(file,
                        Integer.parseInt(row.substring(first + 1, second)),
                        row.substring(second + 1).strip()));
            } catch (NumberFormatException e) {
                LOG.log(Level.FINE, "skipping unparseable git grep row");
            }
        }
        return List.copyOf(occurrences);
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.OutOfDiffFanInTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/OutOfDiffFanIn.java \
        app/src/test/java/app/drydock/review/OutOfDiffFanInTest.java
git commit -m "The strongest entry-point signal, without a repository index

A changed symbol called from outside the change is the signal a reviewer
most wants, and a diff-scoped graph cannot see it. The reference
implementation buys it by ingesting unchanged caller files; one bounded git
grep buys it here, with every uniquely-named changed declaration in a
patterns file so it is one spawn for the whole scope rather than one per
symbol.

The locations are kept, not just counted: a fan-in with nowhere to click is
a statistic rather than comprehension, and it lands exactly when a reviewer
wants to look. Exit code 1 is no-matches and a valid empty answer; anything
above it is a failure that is logged and reported as unavailable, because
absent and zero must not look the same."
```

---

### Task 17: `ReadingPath` — order, entry points, links

**Files:**
- Create: `app/src/main/java/app/drydock/review/ReadingPath.java`
- Test: `app/src/test/java/app/drydock/review/ReadingPathTest.java`

**Interfaces:**
- Consumes: `ChangeGraph` (Task 10), `Graphs.topologicalOrder` (Task 11), `OutOfDiffFanIn.Result` (Task 16), `Sections.Section` (Task 12)
- Produces: `record ReadingPath.Link(String kind, String targetHunkId, String label)`; `record ReadingPath.Step(String hunkId, String file, int sectionNumber, String reason, List<Link> links, boolean entryPoint)`; `static List<Step> ReadingPath.of(UnifiedDiff diff, ChangeGraph graph, List<Sections.Section> sections, OutOfDiffFanIn.Result fanIn)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where to start, what follows, and why (spec §6). Entry-point rank is
 * applied INSIDE the sort rather than as a marking pass afterwards: ordering
 * first and marking second lets "card 1" and "START HERE" disagree, and a
 * START HERE badge on card 4 reads as a bug rather than a design.
 */
class ReadingPathTest {

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        List<UnifiedDiff.Line> lines = new java.util.ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }

    private static List<ReadingPath.Step> pathOf(UnifiedDiff diff, OutOfDiffFanIn.Result fanIn) {
        ChangeGraph graph = ChangeGraph.of(diff);
        return ReadingPath.of(diff, graph, Sections.of(diff, graph), fanIn);
    }

    private static final OutOfDiffFanIn.Result NO_FAN_IN =
            new OutOfDiffFanIn.Result(Map.of(), false);

    @Test
    void theFoundationIsReadBeforeWhatUsesIt() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))), NO_FAN_IN);

        assertEquals("src/guards.cpp", path.get(0).file());
    }

    /** The first step and the entry point are the same step, by construction. */
    @Test
    void theFirstStepIsTheEntryPoint() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))), NO_FAN_IN);

        assertTrue(path.get(0).entryPoint());
        assertTrue(path.stream().skip(1).noneMatch(ReadingPath.Step::entryPoint));
    }

    /** Called from outside the change outranks everything else. */
    @Test
    void outOfDiffFanInOutranksInDegree() {
        OutOfDiffFanIn.Result fanIn = new OutOfDiffFanIn.Result(
                Map.of("PublicThing", List.of(new OutOfDiffFanIn.Occurrence("other.cpp", 1, "x"))),
                false);
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/api.cpp", "class PublicThing { };"),
                file("src/internal.cpp", "class Internal { };"))), fanIn);

        assertEquals("src/api.cpp", path.get(0).file());
    }

    /**
     * A tie-break for when the graph is silent, not an override of it: where
     * a test references changed code the edge already orders it.
     */
    @Test
    void aTestOnlySectionDoesNotBecomeTheEntryPoint() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("test/unrelated_ut.cpp", "void t() { somethingElse(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))), NO_FAN_IN);

        assertEquals("src/guards.cpp", path.get(0).file());
    }

    @Test
    void aStepLinksToWhatCallsIt() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"))), NO_FAN_IN);

        assertTrue(path.get(0).links().stream().anyMatch(link -> link.kind().equals("called by")));
    }

    /** Same-concept links name the symbol they share; a bare affinity says nothing. */
    @Test
    void sameConceptLinksNameTheSharedSymbol() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/a.cpp", "void a() { new JmpCtxScope(); }"),
                file("src/b.cpp", "void b() { new JmpCtxScope(); }"))), NO_FAN_IN);

        assertTrue(path.stream().flatMap(step -> step.links().stream())
                .filter(link -> link.kind().equals("same concept"))
                .anyMatch(link -> link.label().contains("JmpCtxScope")));
    }

    @Test
    void everyStepStatesWhyItSitsWhereItDoes() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"))), NO_FAN_IN);

        assertTrue(path.stream().noneMatch(step -> step.reason().isBlank()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ReadingPathTest"`
Expected: FAIL — `cannot find symbol: class ReadingPath`

- [ ] **Step 3: Write minimal implementation**

Implement `ReadingPath.of` as: rank each file by `(out-of-diff fan-in desc, in-degree desc, non-test first, non-leaf first, path asc)`; hand that comparator to `Graphs.topologicalOrder` as the tie-break so ranking happens *inside* the sort; walk the resulting units emitting one `Step` per hunk, in file order; mark only the first step `entryPoint`; and build links from `graph.filesReferencing` (`called by`), `graph.filesReferencedBy` (`calls`), and shared uniquely-declared symbols (`same concept`, labelled `both touch <symbol>`), cross-file only and deduplicated by target hunk id.

```java
    private static Comparator<String> rank(ChangeGraph graph, OutOfDiffFanIn.Result fanIn) {
        return Comparator
                .comparingInt((String file) -> -fanInOf(file, graph, fanIn))
                .thenComparingInt(file -> -graph.filesReferencing(file).size())
                .thenComparingInt(file -> isTest(file) ? 1 : 0)
                .thenComparingInt(file -> graph.filesReferencing(file).isEmpty() ? 1 : 0)
                .thenComparing(Comparator.naturalOrder());
    }
```

`isTest` reuses `FallbackIntents`' path rules (promote its private `isTest` to package-private rather than writing a second copy — two copies of this vocabulary drifted the last time they existed).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.ReadingPathTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReadingPath.java \
        app/src/main/java/app/drydock/review/FallbackIntents.java \
        app/src/test/java/app/drydock/review/ReadingPathTest.java
git commit -m "Where to start, what follows it, and why

Entry-point rank is the tie-break inside the Kahn sort, not a marking pass
after it. Ordering first and marking second lets the first card and the
START HERE badge disagree, and a badge on card 4 reads as a bug rather than
as a design.

Four signals in order: called from outside the change, then in-degree within
it, then not-a-test, then not-a-leaf. The test signal is a tie-break for
when the graph is silent rather than an override of it -- where a test
references changed code the edge already orders it, so the signal decides
only the case it should, a test-only section with nothing pointing into it.

Links carry their reason. Same-concept names the symbol two hunks share,
because a bare affinity score cannot say why it exists and every other
marker on this surface states its reason. isTest is promoted rather than
copied: two copies of that vocabulary drifted the last time they existed."
```

---

### Task 18: The rail gets a second mode on `p`

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java`
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (key handling)
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewPathModeTest.java`

**Interfaces:**
- Consumes: `ReadingPath.Step` (Task 17)
- Produces: `ReviewIntentRail.Mode { INTENTS, PATH }`; `void ReviewIntentRail.showPath(List<ReadingPath.Step> steps)`; `SessionReviewView.railMode()`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reading path is a MODE of the rail, not a fourth column (spec §7.1).
 * The width budget that ruled out a concept map rules out a new column just
 * as firmly, and RailLayout stays untouched.
 */
class ReviewPathModeTest extends ReviewViewFixture {

    @Test
    void pTogglesTheRailBetweenIntentsAndPath() {
        assertEquals(ReviewIntentRail.Mode.INTENTS, view.railMode());

        press(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(ReviewIntentRail.Mode.PATH, view.railMode());

        press(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(ReviewIntentRail.Mode.INTENTS, view.railMode());
    }

    /** One key, not a parallel set: [ and ] step whatever the rail lists. */
    @Test
    void bracketsStepHunksInPathModeAndSectionsInIntentsMode() {
        press(KeyCode.P);
        press(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, view.selectedPathStepForTest());
    }

    /** n keeps meaning "next unsettled", which is a property of hunks now. */
    @Test
    void nStillWalksUnsettledWorkInPathMode() {
        press(KeyCode.P);
        press(KeyCode.N);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(view.selectedPathStepForTest() >= 0);
    }

    @Test
    void everyPathRowStatesItsReason() {
        press(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(view.pathRowTextsForTest().stream().noneMatch(String::isBlank));
    }

    /** Advertised and bound must match. */
    @Test
    void theShortcutsOverlayAdvertisesP() {
        assertTrue(app.drydock.ui.ShortcutsOverlay.reviewShortcutKeys().contains("p"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewPathModeTest"`
Expected: FAIL — `ReviewIntentRail.Mode` not found

- [ ] **Step 3: Write minimal implementation**

Add `Mode { INTENTS, PATH }` and `showPath(List<ReadingPath.Step>)` to `ReviewIntentRail`, rendering one focusable `Button` per step carrying its section number, file, reason and link count. Bind `p` in `SessionReviewView` to flip the mode and re-render; route `[`/`]` to the rail's current list; keep `n` on unsettled hunks. Add to `ShortcutsOverlay`'s `IN REVIEW` block and expose the keys for the test:

```java
                    {"Reading path / intents", "p"},
```

```java
    /** The keys this overlay advertises for Review, so a test can hold the two in step. */
    public static java.util.List<String> reviewShortcutKeys() {
        return java.util.Arrays.stream(SECTIONS)
                .filter(section -> section.title().equals("IN REVIEW"))
                .flatMap(section -> java.util.Arrays.stream(section.rows()))
                .map(row -> row[1]).toList();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewPathModeTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/ app/src/test/java/app/drydock/ui/review/ReviewPathModeTest.java
git commit -m "p walks the change in reading order

A mode of the rail, not a fourth column: the width budget that ruled out a
concept map rules out a new column just as firmly, and RailLayout is
untouched. [ and ] step whatever the rail is currently listing -- the rule
they already followed -- so the mode costs one key rather than a parallel
set, and n keeps meaning next-unsettled, which is a property of hunks
regardless of what the rail shows.

Every row says why it sits where it does. A reading order the reader cannot
interrogate is just a different arbitrary order."
```

---

### Task 19: Links render under the hunk they belong to

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffRows.java` (row model)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewLinkRowTest.java`

**Interfaces:**
- Consumes: `ReadingPath.Link` (Task 17)
- Produces: `ReviewDiffRows` gains a `LINK` row kind carrying `ReadingPath.Link`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "What does this hunk have to do with the one I just read" (spec §7.2),
 * answered where the question is asked. Link rows are part of the hunk's row
 * model, so folding, density and the unchanged-run collapse apply to them
 * unchanged rather than needing their own copies.
 */
class ReviewLinkRowTest extends ReviewViewFixture {

    @Test
    void aHunkWithLinksGetsAFooterRowBeneathIt() {
        assertTrue(linkRowTexts().stream().anyMatch(text -> text.contains("called by")));
    }

    @Test
    void aLinkNamesItsTargetFileAndSymbolNotARawId() {
        assertTrue(linkRowTexts().stream().noneMatch(text -> text.contains("h_")));
    }

    @Test
    void clickingALinkSelectsTheTargetHunk() {
        clickFirstLinkRow();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("src/guards.cpp", view.selectedFileForTest());
    }

    /** One link per target, not one per shared symbol. */
    @Test
    void linksAreDeduplicatedByTargetHunk() {
        assertEquals(linkRowTexts().size(), linkRowTexts().stream().distinct().count());
    }

    @Test
    void aHunkWithNoLinksGetsNoFooterRow() {
        assertTrue(linkRowTextsFor("src/unrelated.cpp").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewLinkRowTest"`
Expected: FAIL — no link rows exist

- [ ] **Step 3: Write minimal implementation**

In `ReviewDiffRows`, add the row kind and its payload:

```java
    /**
     * A link to a related hunk, appended to its source hunk's rows so that
     * density, folding and the unchanged-run collapse apply to it with no
     * new cases -- a parallel rendering path would drift from this one at
     * the first thing they disagreed about.
     */
    public record LinkRow(ReadingPath.Link link) implements Row {
        @Override
        public Kind kind() {
            return Kind.LINK;
        }
    }
```

In `ReviewDiffColumn`, render it as a focusable control that jumps:

```java
    private Node linkRow(ReviewDiffRows.LinkRow row) {
        ReadingPath.Link link = row.link();
        // A label naming files and symbols, never a raw h_<file>_<n> id: the
        // reader is being told where to go, not shown a key.
        Button button = new Button(glyphFor(link.kind()) + "  " + link.label());
        button.getStyleClass().add("review-link-row");
        button.setFocusTraversable(true);
        button.setOnAction(event -> selectHunk(link.targetHunkId()));
        return button;
    }

    private static String glyphFor(String kind) {
        return switch (kind) {
            case "called by" -> "↳ called by";
            case "calls" -> "↳ calls";
            default -> "↔";
        };
    }
```

Build the rows when the column renders, deduplicated by target so a hunk
sharing three symbols with one target still gets one link:

```java
        Map<String, ReadingPath.Link> byTarget = new LinkedHashMap<>();
        for (ReadingPath.Link link : linksFor(hunkId)) {
            byTarget.putIfAbsent(link.targetHunkId(), link);
        }
        byTarget.values().forEach(link -> rows.add(new ReviewDiffRows.LinkRow(link)));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewLinkRowTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ app/src/test/java/app/drydock/ui/review/ReviewLinkRowTest.java
git commit -m "A hunk says what it has to do with the hunks around it

Link rows live in the hunk's own row model, so folding, density and the
unchanged-run collapse apply to them with no new cases -- the alternative
was a parallel rendering path that would have drifted from the first one it
disagreed with.

Labels name files and symbols rather than raw hunk ids, and there is one
link per target rather than one per shared symbol: a reviewer wants to know
where to go next, not how many reasons there are to go there."
```

---

### Task 20: The fan-in count opens the popover that already exists

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (occurrence popover)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java` (the count is clickable)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewFanInPopoverTest.java`

**Interfaces:**
- Consumes: `OutOfDiffFanIn.Result` (Task 16), the existing `openExplorerAt` / `searchInExplorer` bridge

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanical layer's job is not to be intelligent; it is to make sure
 * the reviewer knows which question to ask and to be one key away from
 * asking it (spec §7.4). A fan-in count with nowhere to click is a
 * statistic.
 */
class ReviewFanInPopoverTest extends ReviewViewFixture {

    @Test
    void clickingTheFanInCountListsTheCallersWithFileAndLine() {
        clickFanInCount();
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(popoverTexts().stream().anyMatch(text -> text.matches(".*:\\d+.*")));
    }

    /** No new interaction is invented: it is the same popover on a third source. */
    @Test
    void thePopoverOffersUsagesAndAskTheAgent() {
        clickFanInCount();
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(popoverTexts().stream().anyMatch(text -> text.contains("usages")));
        assertTrue(popoverTexts().stream().anyMatch(text -> text.contains("agent")));
    }

    /** Absent and zero must not look the same. */
    @Test
    void anUnavailableScanShowsNoCountRatherThanZero() {
        withFanInUnavailable();
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(railTexts().stream().anyMatch(text -> text.contains("0 places outside")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewFanInPopoverTest"`
Expected: FAIL — the count is a `Label`, not a control, and has no popover

- [ ] **Step 3: Write minimal implementation**

The count becomes a control rather than a label, and absent stays distinct
from zero:

```java
    /**
     * The fan-in affordance. An unavailable scan renders NOTHING rather than
     * a zero: "the scan could not run" and "nothing uses this" are different
     * facts and must not look the same.
     */
    private Optional<Node> fanInControl(String symbol, OutOfDiffFanIn.Result fanIn) {
        if (fanIn.unavailable()) {
            return Optional.empty();
        }
        List<OutOfDiffFanIn.Occurrence> occurrences =
                fanIn.bySymbol().getOrDefault(symbol, List.of());
        if (occurrences.isEmpty()) {
            return Optional.empty();
        }
        Button button = new Button("called from " + occurrences.size() + " places outside");
        button.getStyleClass().add("review-fanin-count");
        button.setFocusTraversable(true);
        button.setOnAction(event -> showOccurrencePopover(symbol, occurrences));
        return Optional.of(button);
    }
```

`showOccurrencePopover` is the popover the symbol lens already builds; it
takes the same `(file, line, text)` shape, so the change is the source of
the rows and nothing else. Its existing handlers stay wired as they are —
`⏎` to `openExplorerAt`, `u` to `searchInExplorer`, `a` to the agent prompt —
because inventing a second interaction for the same gesture is how two
popovers start disagreeing.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewFanInPopoverTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ app/src/test/java/app/drydock/ui/review/ReviewFanInPopoverTest.java
git commit -m "Called from 7 places outside the change, and you can see which seven

The same occurrence popover the symbol lens already uses, on a third source,
with its existing keys unchanged: enter opens the file, u lists usages, a
asks the agent -- with the question already pointed at the right file.

This is where the design is honest about its ceiling. A lexical occurrence
list cannot tell a reviewer whether a signature change breaks the caller it
just found, and nothing mechanical and diff-scoped can. What it can do is
put them one keystroke from the party that can answer.

An unavailable scan shows no count rather than a zero: absent and none must
not look the same."
```

---

### Task 21: `reads` — an agent may declare its own dependency order

**Files:**
- Modify: `app/src/main/java/app/drydock/review/ReviewIntent.java` (add `reads`)
- Modify: `app/src/main/java/app/drydock/mcp/ReviewToolCodec.java` (`intentsFromJson`, ~230–253)
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (the `review_intents` descriptor, ~100–108)
- Test: `app/src/test/java/app/drydock/mcp/ReviewIntentReadsTest.java`

**Interfaces:**
- Consumes: `Graphs.topologicalOrder` (Task 11)
- Produces: `ReviewIntent.reads()` → `List<String>`; `IntentGrouping.set` orders a supplied grouping by `reads` when present

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.mcp;

import app.drydock.review.IntentGrouping;
import app.drydock.review.ReviewIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The agent asserts, drydock renders the assertion and never verifies it --
 * the ReviewIntent.Collapse precedent (spec §8). With reads present the
 * rail's order is the agent's declared dependency order; without it, the
 * agent's array order stands.
 */
class ReviewIntentReadsTest {

    @Test
    void readsOrdersTheRailFoundationFirst() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("uses-it", "Crash-protected resolve()", List.of("the-guard")),
                intent("the-guard", "JmpCtxScope guard", List.of())));

        assertEquals(List.of("JmpCtxScope guard", "Crash-protected resolve()"),
                grouping.intentsFor("scope-1", emptyDiff(), java.util.Optional.empty())
                        .stream().map(ReviewIntent::title).toList());
    }

    @Test
    void withoutReadsTheAgentsArrayOrderStands() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("b", "Second", List.of()), intent("a", "First", List.of())));

        assertEquals(List.of("Second", "First"),
                grouping.intentsFor("scope-1", emptyDiff(), java.util.Optional.empty())
                        .stream().map(ReviewIntent::title).toList());
    }

    /** A cycle among asserted dependencies is named, not broken silently. */
    @Test
    void aReadsCycleIsKeptTogetherRatherThanBrokenArbitrarily() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("a", "A", List.of("b")), intent("b", "B", List.of("a"))));

        assertEquals(2, grouping.intentsFor("scope-1", emptyDiff(),
                java.util.Optional.empty()).size());
    }

    /**
     * A batch is all-or-nothing, so a reads naming nothing is rejected whole.
     *
     * <p>{@code parse} is the fixture's JSON helper -- the same
     * {@code JsonParser.parse(String)} the other codec tests use.</p>
     */
    @Test
    void readsNamingAnUnknownIntentRejectsTheBatch() {
        assertThrows(McpToolException.class,
                () -> ReviewToolCodec.intentsFromJson(parse("""
                        [{"id":"a","title":"A","hunkIds":[],"reads":["nonexistent"]}]
                        """)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.mcp.ReviewIntentReadsTest"`
Expected: FAIL — `ReviewIntent` has no `reads` component

- [ ] **Step 3: Write minimal implementation**

Add `List<String> reads` as the last component of `ReviewIntent` (copied defensively in the compact constructor, defaulting to `List.of()`); decode it in `intentsFromJson` and reject the batch when an entry names an id no intent in the same call carries; and in `IntentGrouping.set`, when any intent declares `reads`, order through `Graphs.topologicalOrder` before assigning `1..N`.

Descriptor:

```java
                                .put("intents", schemaString("Array of {id, title, kind, risk, "
                                        + "rationale, hunkIds, reads?, collapse?, autoApprove?}. "
                                        + "reads names the intents this one is built on; drydock "
                                        + "orders the rail by it and does not verify it."))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.mcp.ReviewIntentReadsTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReviewIntent.java \
        app/src/main/java/app/drydock/mcp/ app/src/test/java/app/drydock/mcp/ReviewIntentReadsTest.java
git commit -m "An agent may say which intents its intents are built on

One optional field, no new tool. drydock renders the assertion and never
verifies it, which is the ReviewIntent.Collapse precedent: the agent
asserts, the surface shows the assertion, and the evidence stays one click
away.

Three sources and one rendering path -- reads when it is there, the agent's
array order when it is not, the computed path when no agent ran. A reads
cycle is kept together and named rather than broken silently, for the same
reason a computed one is. And a reads naming an unknown intent rejects the
whole batch, because a batch is already all-or-nothing here: half a grouping
is worse than none."
```

---

### Task 22: `review_recheck` — the agent may add staleness, never remove it

**Files:**
- Create: `app/src/main/java/app/drydock/review/RecheckAssessment.java`
- Modify: `app/src/main/java/app/drydock/review/AnnotationStore.java` (persist assessments)
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java`, `ReviewToolCodec.java`
- Test: `app/src/test/java/app/drydock/review/RecheckAsymmetryTest.java`

**Interfaces:**
- Consumes: `ReviewVerdict` (Task 2), `BaseMove.Delta` (Task 5)
- Produces: `record RecheckAssessment(String scopeId, String hunkDigest, String fromBase, String toBase, boolean affected, String why, Instant at)`; `void AnnotationStore.putAssessment(RecheckAssessment)`; `boolean AnnotationStore.assessedAffected(String scopeId, String hunkDigest, String fromBase, String toBase)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The asymmetry (spec §9.7). "Affected" applies, because it can only ever
 * ADD reading and because it closes the blind spot the file-level relevance
 * filter admits to. "Unaffected" is advice, because an agent wrong THAT way
 * would cost an approval on code nobody re-read -- which is the outcome the
 * whole reviewed-state model refuses.
 */
class RecheckAsymmetryTest {

    private static AnnotationStore store() throws IOException {
        return new AnnotationStore(Files.createTempDirectory("drydock-recheck")
                .resolve("annotations.json"));
    }

    private static ReviewVerdict approved(String base) {
        return new ReviewVerdict("scope-1", "digest-1", ReviewVerdict.Decision.APPROVED,
                Optional.empty(), Instant.EPOCH, base, "head-1");
    }

    @Test
    void anAffectedAssessmentMarksAVerdictTheFilterWouldHaveMissed() throws IOException {
        AnnotationStore store = store();
        store.putVerdict(approved("base-1"));
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "resolve() now returns nullptr on failure", Instant.EPOCH));

        assertTrue(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
    }

    @Test
    void anUnaffectedAssessmentDoesNotClearTheVerdictsStaleness() throws IOException {
        AnnotationStore store = store();
        store.putVerdict(approved("base-1"));
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                false, "the base change is in an unrelated subsystem", Instant.EPOCH));

        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
        assertTrue(store.verdict("scope-1", "digest-1").orElseThrow().staleAgainst("base-2"),
                "an agent must not clear a human's approval");
    }

    /** An assessment is about one base pair; a later move is a new question. */
    @Test
    void anAssessmentDoesNotCarryToADifferentBasePair() throws IOException {
        AnnotationStore store = store();
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));

        assertFalse(store.assessedAffected("scope-1", "digest-1", "base-2", "base-3"));
    }

    @Test
    void assessmentsRoundTripThroughDisk() throws IOException {
        Path file = Files.createTempDirectory("drydock-recheck").resolve("annotations.json");
        AnnotationStore store = new AnnotationStore(file);
        store.putAssessment(new RecheckAssessment("scope-1", "digest-1", "base-1", "base-2",
                true, "why", Instant.EPOCH));
        store.flushPendingSaves();

        assertTrue(new AnnotationStore(file)
                .assessedAffected("scope-1", "digest-1", "base-1", "base-2"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.RecheckAsymmetryTest"`
Expected: FAIL — `cannot find symbol: class RecheckAssessment`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

import java.time.Instant;
import java.util.Objects;

/**
 * An agent's statement about whether one base move affects one approved hunk
 * (spec §9.7).
 *
 * <p>Keyed by the base PAIR it was made about: a later base move is a new
 * question, and carrying an old answer forward would be the agent answering
 * something it was never asked.</p>
 *
 * <p>Only {@code affected == true} has an effect. An agent may add staleness
 * -- that only ever asks for more reading, and it closes the blind spot the
 * file-level relevance filter admits to -- but it may never clear an
 * approval, which is the line the whole MCP surface is drawn around.</p>
 */
public record RecheckAssessment(String scopeId, String hunkDigest, String fromBase, String toBase,
                                boolean affected, String why, Instant at) {

    public RecheckAssessment {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(hunkDigest, "hunkDigest");
        Objects.requireNonNull(fromBase, "fromBase");
        Objects.requireNonNull(toBase, "toBase");
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(at, "at");
    }

    /** {@code (scopeId, hunkDigest, fromBase, toBase)}. */
    public record Key(String scopeId, String hunkDigest, String fromBase, String toBase) {
    }

    public Key key() {
        return new Key(scopeId, hunkDigest, fromBase, toBase);
    }
}
```

In `AnnotationStore`, add an `assessments` map keyed by `RecheckAssessment.Key`, persisted under a new `"assessments"` array (same lenient decode as verdicts), with:

```java
    /** Records an agent's recheck. Only an affected one has any effect (spec §9.7). */
    public void putAssessment(RecheckAssessment assessment) {
        putAssessmentInternal(assessment);
        fireChanged(null);
    }

    /** Whether the agent said this base move affects this hunk. */
    public synchronized boolean assessedAffected(String scopeId, String hunkDigest,
                                                 String fromBase, String toBase) {
        RecheckAssessment found = assessments.get(
                new RecheckAssessment.Key(scopeId, hunkDigest, fromBase, toBase));
        return found != null && found.affected();
    }
```

Register the tool:

```java
                descriptor("review_recheck",
                        "Assesses whether a base move still leaves approved hunks valid. "
                                + "affected=true marks them stale; affected=false is ADVICE and "
                                + "never clears a human's approval.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("assessments", schemaString("Array of {hunkId, affected, why}.")),
                        "scopeId", "assessments"),
```

The staleness test in `SessionReviewView` becomes `filterSaysStale || store.assessedAffected(...)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.RecheckAsymmetryTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ app/src/main/java/app/drydock/mcp/ \
        app/src/test/java/app/drydock/review/RecheckAsymmetryTest.java
git commit -m "An agent may add staleness to an approval, never take it away

The relevance filter is file-level and lexical and names its own blind spot:
a base change that alters behaviour without touching a file this scope names
is invisible to it. An agent has no such boundary, so it can answer the one
question neither the digest nor the intersection can.

The two directions carry different risk and are treated differently.
Affected applies -- it only adds reading, and it is how the blind spot
closes; an agent wrong that way costs a wasted re-read. Unaffected is advice
that never clears a verdict, because an agent wrong that way would cost an
approval on code nobody re-read. It is migrateLegacyVerdicts' asymmetry
pointed at a different question.

Keyed by the base pair it was made about, so a later move is a new question
rather than an old answer carried forward."
```

---

### Task 23: The recheck dispatches itself when the base moves

**Files:**
- Modify: `app/src/main/java/app/drydock/review/ReviewInstructions.java`
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (dispatch on base move)
- Test: `app/src/test/java/app/drydock/review/ReviewInstructionsRecheckTest.java`

**Interfaces:**
- Consumes: `AgentCapabilities.supportsSubagents`, `ReviewInstructions.forScope`
- Produces: `static String ReviewInstructions.forRecheck(String scopeId, String fromBase, String toBase, boolean supportsSubagents)`

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recheck is a small bounded task -- it reads one base delta and the stale
 * hunks, not the change -- which is why it earns a dispatch of its own
 * rather than a full re-review (spec §9.7).
 */
class ReviewInstructionsRecheckTest {

    @Test
    void theSubagentFormNamesBothBasesAndTheTool() {
        String instruction = ReviewInstructions.forRecheck("scope-1", "a1b2c3", "d4e5f6", true);

        assertTrue(instruction.contains("a1b2c3"));
        assertTrue(instruction.contains("d4e5f6"));
        assertTrue(instruction.contains("review_recheck"));
        assertTrue(instruction.contains("subagent"));
    }

    @Test
    void theInlineFormDoesTheSameWorkWithoutASubagent() {
        String instruction = ReviewInstructions.forRecheck("scope-1", "a1b2c3", "d4e5f6", false);

        assertTrue(instruction.contains("review_recheck"));
        assertFalse(instruction.contains("subagent"));
    }

    /** The agent must be told it cannot clear an approval, not left to infer it. */
    @Test
    void bothFormsSayThatUnaffectedIsAdviceOnly() {
        for (boolean subagents : new boolean[] {true, false}) {
            assertTrue(ReviewInstructions.forRecheck("s", "a", "b", subagents)
                    .contains("does not clear"));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewInstructionsRecheckTest"`
Expected: FAIL — `forRecheck` not found

- [ ] **Step 3: Write minimal implementation**

```java
    /**
     * What drydock asks when a base move has marked approvals stale
     * (spec §9.7). Bounded on purpose: the base delta and the stale hunks,
     * not the change.
     */
    public static String forRecheck(String scopeId, String fromBase, String toBase,
                                    boolean supportsSubagents) {
        Objects.requireNonNull(scopeId, "scopeId");
        String work = "for handle " + scopeId + ", read what changed between " + fromBase
                + " and " + toBase + ", and for each approved hunk it could affect call "
                + "review_recheck with affected and a one-line why. Marking a hunk affected "
                + "asks the human to read it again; marking one unaffected is advice and "
                + "does not clear their approval";
        return supportsSubagents
                ? "Dispatch a subagent to recheck stale approvals: " + work
                        + ". Report only its summary back here."
                : "Recheck the stale approvals in this worktree: " + work + ".";
    }
```

In `SessionReviewView`, when a base move marks anything stale, dispatch this through the existing `TerminalBridge.sendPrompt` path on the background executor. A harness without subagent support gets the inline form; a harness whose `mcpDelivery` is `NONE` gets no dispatch and no error.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewInstructionsRecheckTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReviewInstructions.java \
        app/src/main/java/app/drydock/ui/review/SessionReviewView.java \
        app/src/test/java/app/drydock/review/ReviewInstructionsRecheckTest.java
git commit -m "A base move asks the agent which approvals it actually disturbed

Dispatched automatically, so the assessment is usually already there when
the reviewer returns rather than arriving after a wait exactly when they
wanted to move on. It is a small bounded task by construction -- one base
delta and the stale hunks, not the change -- which is why it earns its own
dispatch instead of a full re-review.

The instruction says outright that unaffected does not clear an approval.
An agent should be told the rule rather than left to infer it from what the
tool happens to do.

This does not fix the accepted risk: the reviewer still clicks confirm. It
changes what they are looking at when they click -- nine of twelve visibly
uninteresting, three not. It does not make the mark trustworthy, it makes it
sorted."
```

---

---

### Task 24: Order and links say whether they were measured or claimed

**Files:**
- Create: `app/src/main/java/app/drydock/review/Provenance.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java`, `ReviewDiffColumn.java`
- Modify: `app/src/main/resources/app.css` (a `.provenance-claimed` modifier)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewProvenanceTest.java`

**Interfaces:**
- Consumes: `ReadingPath.Link` (Task 17), `ReviewIntent.reads` (Task 21), `RecheckAssessment` (Task 22)
- Produces: `enum Provenance { MEASURED, CLAIMED }`; `Provenance ReadingPath.Step.provenance()`; `Provenance ReadingPath.Link.provenance()`

**Ordering note:** this depends on Tasks 17, 18, 19 and 21 and could equally be done immediately after 21. It is last because it is the smallest change that touches the most rendering paths, and doing it once at the end beats threading it through four tasks as they land.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.review.Provenance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A measured edge and a claimed one fail differently (spec §6.5), and a
 * reviewer deciding how hard to squint at "③ depends on ①" has to know which
 * they are holding. A measured edge fails as a false unique-name match --
 * two unrelated things sharing a name -- and is checkable on the spot by
 * looking. A claimed edge fails as a plausible fabrication and is checkable
 * only against the code the agent says it read.
 *
 * <p>Not a new principle here, only its consistent application:
 * ReviewIntent.Collapse already renders the agent's assertion AS an
 * assertion, precisely because drydock does not verify it.</p>
 */
class ReviewProvenanceTest extends ReviewViewFixture {

    @Test
    void aComputedOrderIsMarkedMeasured() {
        assertEquals(Provenance.MEASURED, view.stepProvenanceForTest(0));
    }

    @Test
    void anAgentSuppliedOrderIsMarkedClaimed() {
        withAgentSuppliedIntentsDeclaringReads();

        assertEquals(Provenance.CLAIMED, view.stepProvenanceForTest(0));
    }

    @Test
    void computedLinksAreMarkedMeasured() {
        assertTrue(view.linksForTest().stream()
                .allMatch(link -> link.provenance() == Provenance.MEASURED));
    }

    /** The distinction has to be visible, not merely modelled. */
    @Test
    void aClaimedRowCarriesTheClaimedStyleClass() {
        withAgentSuppliedIntentsDeclaringReads();

        assertTrue(railRowStyleClasses().stream()
                .anyMatch(classes -> classes.contains("provenance-claimed")));
    }

    @Test
    void aMeasuredRowDoesNotCarryTheClaimedStyleClass() {
        assertTrue(railRowStyleClasses().stream()
                .noneMatch(classes -> classes.contains("provenance-claimed")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewProvenanceTest"`
Expected: FAIL — `cannot find symbol: class Provenance`

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.review;

/**
 * Where an ordering or a link came from (spec §6.5).
 *
 * <p>The two fail in ways a reviewer has to tell apart. A {@link #MEASURED}
 * edge fails as a false unique-name match and is checkable on the spot by
 * looking; a {@link #CLAIMED} one fails as a plausible fabrication and is
 * checkable only against the code the agent says it read.</p>
 *
 * <p>One rendering path, two visibly different warrants -- the treatment
 * {@code ReviewIntent.Collapse} already gets, applied consistently.</p>
 */
public enum Provenance {

    /** Computed here from the diff, by the rules in §4.2 and §4.3. */
    MEASURED("measured"),

    /** Asserted by the reviewing agent, through {@code review_intents} or {@code review_recheck}. */
    CLAIMED("claimed");

    private final String label;

    Provenance(String label) {
        this.label = label;
    }

    /** What the surface shows beside a marker carrying this warrant. */
    public String label() {
        return label;
    }

    /** The {@code app.css} modifier class, or none for the ordinary case. */
    public String styleClass() {
        return this == CLAIMED ? "provenance-claimed" : "";
    }
}
```

Add `Provenance provenance()` to `ReadingPath.Step` and `ReadingPath.Link`, set to `MEASURED` where `ReadingPath` computed them and `CLAIMED` where the order came from `reads` or a `RecheckAssessment`. In the rail and the diff column, apply `provenance().styleClass()` to the row and append the label to the row's tooltip, so the distinction is legible without adding a column.

`app.css`:

```css
/* A claimed ordering is the agent's assertion, not drydock's measurement.
   Dashed rather than coloured: the four risk encodings already compete for
   colour on this surface, and a fifth would be unreadable. */
.review-intent-card.provenance-claimed,
.review-link-row.provenance-claimed {
    -fx-border-style: segments(3, 3) line-cap round;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewProvenanceTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/Provenance.java \
        app/src/main/java/app/drydock/ui/review/ app/src/main/resources/app.css \
        app/src/test/java/app/drydock/ui/review/ReviewProvenanceTest.java
git commit -m "An ordering says whether drydock measured it or an agent claimed it

Consistency of rendering is right; consistency of warrant is not. A measured
edge fails as a false unique-name match -- two unrelated things sharing a
name -- and a reviewer can check it by looking. A claimed edge fails as a
plausible fabrication and is checkable only against the code the agent says
it read. Someone deciding how hard to squint at 'this depends on that' has
to know which of those they are holding.

Not a new principle, only its consistent application: ReviewIntent.Collapse
already renders the agent's assertion as an assertion, precisely because
drydock does not verify it. Order and links get the same treatment.

Dashed rather than coloured, because four risk encodings already compete for
colour on this surface and a fifth would be unreadable."
```

### Phase 3 gate

- [ ] **Run the full suite:** `./gradlew :app:test` (from the controlling session)
- [ ] **Screenshots, per the visual-verification practice**, at a realistic window width — the rail has truncated before and PATH rows carry more text than an intent card:
  - The rail in PATH mode.
  - A hunk carrying all three link kinds, at each of the three densities.
  - A named cycle.
  - The fan-in popover open from a rail card.
- [ ] **One end-to-end pass on a real PR:** review it, approve some sections, move the base, confirm the recheck dispatches and that its "affected" assessments mark hunks the file-level filter missed.
- [ ] **Confirm the accepted risk is visible, not hidden:** with a stale verdict present, `⏎` must refuse with a stated reason rather than silently doing nothing.
- [ ] **Confirm provenance is legible**, not just modelled: an agent-ordered rail and a computed one must be distinguishable in a screenshot without reading the tooltip.

---

## Notes for whoever executes this

- **Do not let a subagent run the full Gradle suite.** It takes 14–20 minutes and the Bash tool's ceiling is 10; give subagents the targeted `--tests` subset for their task and run the full suite from the controlling session at each phase gate.
- **Determinism failures usually look like flakiness.** If a section order or a reading path differs between runs, the cause is almost always a `HashMap`/`HashSet` that should have been `LinkedHashMap`/`TreeSet` — check that before suspecting the algorithm.
- **The spec records what was ruled out and why.** Before proposing a change to an approach here — a graph library, a positional anchor, splitting tests onto their own card, letting an agent clear an approval — read the corresponding section: each of those was considered and rejected for a reason that is written down.
