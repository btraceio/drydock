# Guided Review Tour Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Review walks a reviewer through an agent-written tour of the change, shown in whole files, where each step's approval is gated by checks the reviewer must pass.

**Architecture:** A new `app.drydock.review.tour` package holds the tour model, its validator, codec, progress/grading rules and a sibling-file store (`review-tours.json`). The bound agent posts a tour through a new MCP tool `review_tour`; Review's existing diff column renders an unlimited-context *display* diff (approvals stay keyed to the 12-line review diff), a tour outline replaces the intent rail and a step panel replaces the findings margin while in tour mode. Hunk verdicts are *derived* from step progress. Agent findings gain a triage state; peek, trail and search are promoted out of the Explorer into `app.drydock.ui.nav` and reused by Review.

**Tech Stack:** Java 26, JavaFX + TestFX 4.0.18 on Monocle (headless), JUnit 5 (no AssertJ, no Mockito), Gradle. Hand-written JSON (`app.drydock.state.json`).

**Spec:** `docs/superpowers/specs/2026-10-03-guided-review-tour-design.md` (commit `cbccd25`). Every implementer reads the spec section named in their task before starting.

## Global Constraints

These apply to every task. Each task repeats the ones it is most likely to break, because task briefs are extracted heading-to-heading.

- No new dependencies. No AssertJ, no Mockito: JUnit 5 `Assertions.*`, TestFX `ApplicationTest`.
- No inline fully-qualified class names; use imports (only same-name collisions excepted, e.g. `ReadingPath.Path` vs `java.nio.file.Path`).
- Never block the JavaFX Application Thread: process spawns, filesystem I/O and diff/graph computation run on an executor; hop back with `Platform.runLater` only to touch UI. Every user-triggered async action shows progress immediately and clears it on success, error **and** early return.
- Child processes go through `app.drydock.process.ProcessRunner` with a timeout.
- One writer per persistent file: `AnnotationStore` owns `annotations.json`, the new `TourStore` owns `review-tours.json`. Nobody else does load-then-save. Stores that write in the background expose `flushPendingSaves()` and are closed, exception-isolated, from `DrydockApplication.stop()` (after `McpServer`).
- Decoding persisted cosmetic/progress data is lenient: a malformed entry is skipped, never a reason to drop the file. Inbound agent JSON is validated strictly and rejected all-or-nothing with concrete messages.
- Every inbound agent text field passes `PromptSafety.checkInboundText(text, field)`.
- Primary actions are real `Button`s. Every key advertised in `ShortcutsOverlay` is bound and vice versa (`ShortcutsOverlayParityTest`). New Review keys also go into `MainWorkspace.REPLAYABLE_OFF_REVIEW_SUBTREE`.
- No `Animation.INDEFINITE` without a stop path tied to the node's lifecycle; `PauseTransition`s owned by a view are stopped in its `close()`.
- Digests, anchors and verdicts are computed **only** from the review diff (`DiffService.REVIEW_CONTEXT_LINES` = 12). The whole-file display diff is for rendering only.
- Commit style (AGENTS.md): subject is one declarative sentence describing the state after the commit, present tense, no trailing period, no `type(scope):` prefix (a bare area prefix such as "Review tour: " is allowed). Body: defect/absence first, then what it does now and why, then verification (command → result), then what was not covered. End with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Run single test classes with `./gradlew :app:test --tests "<fqcn>" --offline` (blocking, timeout 600000 ms). Never run the full suite from a subagent: it takes 14–20 minutes. The controller runs it in Task 22.

## Review Focus

Inputs the spec implies that no happy-path test exercises, most likely to bite first. Each has a pinning test in the task named.

1. **A deletion-only hunk or a deleted file** must be coverable by an anchor and count as covered (`o<old>` keys). Test: Task 1 `AnchorIndexTest.aDeletionOnlyHunkIsCoverableByOldLineKeys`, Task 2 `TourValidatorTest.aDeletedFileIsCoveredByOldKeys`.
2. **An agent that answers with 1-based `answer` indices, a string answer, or 5 choices** must get a rejection naming the check, not a silently wrong answer key. Tests: Task 2 `TourValidatorTest.anAnswerOutsideTheChoicesIsRejected`, Task 4 `TourCodecTest.aNonIntegerAnswerIsRejectedNamingItsPath` (the codec also caps choices at 4).
3. **A corrupt `review-tours.json`** must leave findings and verdicts in `annotations.json` untouched, and a single malformed step must not drop the rest of its tour. Test: Task 5 `TourStoreTest.aMalformedStepIsDroppedAndTheRestOfTheTourSurvives`, `TourStoreTest.aCorruptTourFileStartsEmptyWithoutTouchingOtherFiles`.
4. **Approving in the hunk diff while a tour exists** must not bypass the gate: it is recorded as a hunk override and listed at submit. Test: Task 10 `ReviewTourModeTest.anApprovalInTheHunkDiffIsRecordedAsAnOverride`.
5. **A RISK verdict that arrives after the diff changed** must be dropped, not applied to a different change. Test: Task 13 `McpToolRouterTourTest.aRiskVerdictForAnOldFingerprintIsDropped`.

---

## Phase A — Tour core (no UI)

### Task 1: Tour model, anchor index and diff fingerprint

**Read first:** spec §3 (model, coverage invariant).

**Constraints:** no new dependencies; JUnit 5 only; imports not FQCNs; digests come from `HunkDigest.of(path, hunk)` on the review diff.

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/TourAnchor.java`
- Create: `app/src/main/java/app/drydock/review/tour/ImpactNote.java`
- Create: `app/src/main/java/app/drydock/review/tour/TourCheck.java`
- Create: `app/src/main/java/app/drydock/review/tour/TourStep.java`
- Create: `app/src/main/java/app/drydock/review/tour/ReviewTour.java`
- Create: `app/src/main/java/app/drydock/review/tour/AnchorIndex.java`
- Create: `app/src/main/java/app/drydock/review/tour/TourFingerprint.java`
- Test: `app/src/test/java/app/drydock/review/tour/AnchorIndexTest.java`
- Test: `app/src/test/java/app/drydock/review/tour/TourFingerprintTest.java`
- Test helper: `app/src/test/java/app/drydock/review/tour/TourFixtures.java`

**Interfaces:**
- Consumes: `UnifiedDiff` (`FileDiff(path, kind, insertions, deletions, staged, untracked, hunks)`, `Hunk(header, lines)`, `Line(kind, oldLine, newLine, text)`, `Line.lineKey()`), `HunkDigest.of(String, UnifiedDiff.Hunk)`, `ReviewIntent.hunkId(String file, int index)`.
- Produces (used by every later task):
  - `record TourAnchor(String file, String startKey, String endKey)`
  - `record ImpactNote(String file, int line, String text)`
  - `record TourCheck(String id, Kind kind, String prompt, List<Choice> choices, OptionalInt answer, String explanation, List<TourCheck> alternates)` with `enum Kind { PREDICT, TRACE, RISK }` (`wireName()`, `static Optional<Kind> fromWire(String)`), `record Choice(String text, Optional<Location> at)`, `record Location(String file, int line)`, `TourCheck version(int attempt)`, `int versions()`
  - `record TourStep(String id, String title, String narrative, List<TourAnchor> anchors, List<ImpactNote> impactNotes, List<TourCheck> checks)` with `Optional<TourCheck> check(String checkId)`
  - `record ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps)` with `Optional<TourStep> step(String id)`, `Optional<TourStep> stepOfCheck(String checkId)`, `int number(String stepId)` (1-based, 0 if absent)
  - `final class AnchorIndex`: `static AnchorIndex of(UnifiedDiff diff)`, `boolean resolves(TourAnchor)`, `boolean contains(TourAnchor, String file, String lineKey)`, `List<HunkRef> hunksTouched(TourAnchor)`, `List<ChangedRow> changedRows()`, `List<HunkRef> hunks()`, `Optional<String> keyAtOrBefore(String file, int newLine)`; `record HunkRef(String file, int index, String digest)` with `String hunkId()`; `record ChangedRow(String file, String lineKey, int hunkIndex)`
  - `final class TourFingerprint`: `static String of(UnifiedDiff diff)`

- [ ] **Step 1: Write the test fixtures helper**

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Hand-built diffs and tours for the tour package's tests. */
final class TourFixtures {

    static final String SCOPE = "rs_tour";

    private TourFixtures() {
    }

    static Line ctx(int oldLine, int newLine, String text) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    static Line add(int newLine, String text) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    static Line del(int oldLine, String text) {
        return new Line(Line.Kind.DEL, OptionalInt.of(oldLine), OptionalInt.empty(), text);
    }

    static UnifiedDiff.Hunk hunk(Line... lines) {
        return new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(lines));
    }

    static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        int insertions = 0;
        int deletions = 0;
        for (UnifiedDiff.Hunk hunk : hunks) {
            for (Line line : hunk.lines()) {
                if (line.kind() == Line.Kind.ADD) {
                    insertions++;
                } else if (line.kind() == Line.Kind.DEL) {
                    deletions++;
                }
            }
        }
        return new UnifiedDiff.FileDiff(path, "M", insertions, deletions, false, false, List.of(hunks));
    }

    static UnifiedDiff.FileDiff deletedFile(String path, String... removed) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < removed.length; i++) {
            lines.add(del(i + 1, removed[i]));
        }
        return new UnifiedDiff.FileDiff(path, "D", 0, removed.length, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1 +0,0 @@", lines)));
    }

    /**
     * Two files: {@code src/A.java} with two hunks (hunk 0 adds n3, hunk 1
     * replaces o20 with n21) and {@code src/B.java} with one deletion-only
     * hunk (removes o5..o6).
     */
    static UnifiedDiff twoFileDiff() {
        UnifiedDiff.FileDiff a = file("src/A.java",
                hunk(ctx(1, 1, "class A {"), ctx(2, 2, "  int x;"), add(3, "  int y;"), ctx(3, 4, "}")),
                hunk(ctx(19, 20, "void f() {"), del(20, "  old();"), add(21, "  next();"), ctx(21, 22, "}")));
        UnifiedDiff.FileDiff b = file("src/B.java",
                hunk(ctx(4, 4, "a"), del(5, "b"), del(6, "c"), ctx(7, 5, "d")));
        return new UnifiedDiff(List.of(a, b));
    }

    static TourCheck predict(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.PREDICT, "Alternate?",
                List.of(choice("yes"), choice("no")), OptionalInt.of(1), "Because.", List.of());
        return new TourCheck(id, TourCheck.Kind.PREDICT, "What happens?",
                List.of(choice("it throws"), choice("it returns")), OptionalInt.of(1), "It returns early.",
                List.of(alternate));
    }

    static TourCheck risk(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.RISK, "Name another risk.",
                List.of(), OptionalInt.empty(), "Concurrency.", List.of());
        return new TourCheck(id, TourCheck.Kind.RISK, "What input breaks this?",
                List.of(), OptionalInt.empty(), "An empty list.", List.of(alternate));
    }

    static TourCheck.Choice choice(String text) {
        return new TourCheck.Choice(text, Optional.empty());
    }

    static TourStep step(String id, List<TourAnchor> anchors, TourCheck... checks) {
        return new TourStep(id, "Step " + id, "Why " + id + " exists.", anchors, List.of(), List.of(checks));
    }

    /** A tour covering every changed row of {@link #twoFileDiff()} in two steps. */
    static ReviewTour coveringTour(UnifiedDiff diff) {
        return new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/B.java", "o5", "o6")), risk("c2"))));
    }
}
```

- [ ] **Step 2: Write the failing tests**

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorIndexTest {

    private final AnchorIndex index = AnchorIndex.of(twoFileDiff());

    @Test
    void anAnchorResolvesOnlyWhenBothKeysAreRowsOfItsFileInOrder() {
        assertTrue(index.resolves(new TourAnchor("src/A.java", "n1", "n4")));
        assertFalse(index.resolves(new TourAnchor("src/A.java", "n4", "n1")), "backwards range");
        assertFalse(index.resolves(new TourAnchor("src/A.java", "n1", "n99")), "end key not a row");
        assertFalse(index.resolves(new TourAnchor("src/Nope.java", "n1", "n1")), "file not in diff");
    }

    @Test
    void containmentFollowsDiffRowOrderAcrossHunks() {
        TourAnchor whole = new TourAnchor("src/A.java", "n3", "n21");
        assertTrue(index.contains(whole, "src/A.java", "o20"), "a removed row between the endpoints");
        assertTrue(index.contains(whole, "src/A.java", "n20"));
        assertFalse(index.contains(whole, "src/A.java", "n1"));
        assertFalse(index.contains(whole, "src/B.java", "o5"), "other file");
    }

    @Test
    void aDeletionOnlyHunkIsCoverableByOldLineKeys() {
        TourAnchor deletion = new TourAnchor("src/B.java", "o5", "o6");
        assertTrue(index.resolves(deletion));
        assertEquals(List.of("src/B.java#0"), index.hunksTouched(deletion).stream()
                .map(ref -> ref.file() + "#" + ref.index()).toList());
    }

    @Test
    void changedRowsListsEveryAddAndDelInDiffOrder() {
        assertEquals(List.of("src/A.java n3", "src/A.java o20", "src/A.java n21", "src/B.java o5", "src/B.java o6"),
                index.changedRows().stream().map(row -> row.file() + " " + row.lineKey()).toList());
    }

    @Test
    void hunksTouchedNeedsAChangedRowInsideTheAnchor() {
        TourAnchor contextOnly = new TourAnchor("src/A.java", "n1", "n2");
        assertTrue(index.hunksTouched(contextOnly).isEmpty());
    }

    @Test
    void keyAtOrBeforeFindsTheNearestNewLineRow() {
        assertEquals("n21", index.keyAtOrBefore("src/A.java", 21).orElseThrow());
        assertEquals("n4", index.keyAtOrBefore("src/A.java", 10).orElseThrow());
        assertTrue(index.keyAtOrBefore("src/Nope.java", 3).isEmpty());
    }

    @Test
    void hunkIdsMatchTheReviewIntentFormat() {
        UnifiedDiff diff = twoFileDiff();
        assertEquals("h_src/A.java_1", AnchorIndex.of(diff).hunks().get(1).hunkId());
    }
}
```

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static app.drydock.review.tour.TourFixtures.add;
import static app.drydock.review.tour.TourFixtures.file;
import static app.drydock.review.tour.TourFixtures.hunk;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TourFingerprintTest {

    @Test
    void theSameDiffHasTheSameFingerprint() {
        assertEquals(TourFingerprint.of(twoFileDiff()), TourFingerprint.of(twoFileDiff()));
    }

    @Test
    void anyHunkContentChangeChangesTheFingerprint() {
        UnifiedDiff other = new UnifiedDiff(List.of(file("src/A.java", hunk(add(3, "  int z;")))));
        assertNotEquals(TourFingerprint.of(twoFileDiff()), TourFingerprint.of(other));
    }

    @Test
    void aFingerprintIsSixtyFourHexCharacters() {
        assertEquals(64, TourFingerprint.of(twoFileDiff()).length());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.AnchorIndexTest" --tests "app.drydock.review.tour.TourFingerprintTest" --offline`
Expected: compilation FAILURE (`TourAnchor`, `AnchorIndex`, `TourFingerprint` do not exist).

- [ ] **Step 4: Write the model records**

`TourAnchor.java`:

```java
package app.drydock.review.tour;

import java.util.Objects;

/**
 * One anchored range of a tour step: the rows of one file of the review diff
 * from {@code startKey} to {@code endKey} inclusive, in diff order.
 *
 * <p>Keys are the stable line keys findings already use ({@code n<newLine>}
 * for a row present in the post-image, {@code o<oldLine>} for a removed
 * row; see {@code UnifiedDiff.Line#lineKey}). A range over post-image line
 * numbers could not address a hunk that only removes lines.</p>
 */
public record TourAnchor(String file, String startKey, String endKey) {

    public TourAnchor {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(startKey, "startKey");
        Objects.requireNonNull(endKey, "endKey");
        if (file.isBlank() || startKey.isBlank() || endKey.isBlank()) {
            throw new IllegalArgumentException("anchor fields must not be blank");
        }
    }
}
```

`ImpactNote.java`:

```java
package app.drydock.review.tour;

import java.util.Objects;

/** The agent's claim about one location a step affects (provenance CLAIMED). */
public record ImpactNote(String file, int line, String text) {

    public ImpactNote {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");
    }
}
```

`TourCheck.java`:

```java
package app.drydock.review.tour;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One check that gates a tour step.
 *
 * <p>PREDICT and TRACE carry two to four choices and a 0-based answer index;
 * they are graded locally. RISK carries neither: the reviewer's free-text
 * answer is judged by the agent. A top-level check carries at least one
 * alternate, offered after a wrong answer; alternates carry none.</p>
 */
public record TourCheck(String id, Kind kind, String prompt, List<Choice> choices, OptionalInt answer,
                        String explanation, List<TourCheck> alternates) {

    public enum Kind {
        PREDICT("predict"),
        TRACE("trace"),
        RISK("risk");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<Kind> fromWire(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.wireName.equals(normalized)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** One answer option; {@code at} names the real call site a TRACE choice stands for. */
    public record Choice(String text, Optional<Location> at) {
        public Choice {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(at, "at");
        }
    }

    public record Location(String file, int line) {
        public Location {
            Objects.requireNonNull(file, "file");
        }
    }

    public TourCheck {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(explanation, "explanation");
        choices = List.copyOf(choices);
        alternates = List.copyOf(alternates);
    }

    /** Whether this check is graded locally against its answer key. */
    public boolean gradedLocally() {
        return kind != Kind.RISK;
    }

    /** How many versions this check has: itself plus its alternates. */
    public int versions() {
        return 1 + alternates.size();
    }

    /** Version {@code attempt}: 0 is this check, {@code k} is alternate {@code k - 1}. */
    public TourCheck version(int attempt) {
        if (attempt <= 0) {
            return this;
        }
        return alternates.get(Math.min(attempt, alternates.size()) - 1);
    }
}
```

`TourStep.java`:

```java
package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One step of a tour: what it covers, why it exists, and what checks gate it. */
public record TourStep(String id, String title, String narrative, List<TourAnchor> anchors,
                       List<ImpactNote> impactNotes, List<TourCheck> checks) {

    public TourStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(narrative, "narrative");
        anchors = List.copyOf(anchors);
        impactNotes = List.copyOf(impactNotes);
        checks = List.copyOf(checks);
    }

    /** The top-level check with {@code checkId}, or the top-level check owning that alternate id. */
    public Optional<TourCheck> check(String checkId) {
        for (TourCheck check : checks) {
            if (check.id().equals(checkId)) {
                return Optional.of(check);
            }
            for (TourCheck alternate : check.alternates()) {
                if (alternate.id().equals(checkId)) {
                    return Optional.of(check);
                }
            }
        }
        return Optional.empty();
    }
}
```

`ReviewTour.java`:

```java
package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An agent-written tour of one review scope, valid for the diff whose fingerprint it carries. */
public record ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps) {

    public ReviewTour {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(diffFingerprint, "diffFingerprint");
        steps = List.copyOf(steps);
    }

    public Optional<TourStep> step(String id) {
        return steps.stream().filter(step -> step.id().equals(id)).findFirst();
    }

    /** The step whose checks (or their alternates) include {@code checkId}. */
    public Optional<TourStep> stepOfCheck(String checkId) {
        return steps.stream().filter(step -> step.check(checkId).isPresent()).findFirst();
    }

    /** 1-based position of the step, or 0 when absent. */
    public int number(String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i + 1;
            }
        }
        return 0;
    }

    public ReviewTour withSteps(List<TourStep> newSteps) {
        return new ReviewTour(scopeId, diffFingerprint, newSteps);
    }

    public ReviewTour withFingerprint(String fingerprint) {
        return new ReviewTour(scopeId, fingerprint, steps);
    }
}
```

- [ ] **Step 5: Write `AnchorIndex` and `TourFingerprint`**

`AnchorIndex.java`:

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewIntent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where every line key sits in one diff, so tour anchors (key ranges) can be
 * resolved, compared and mapped to hunks.
 *
 * <p>Row order is diff order within a file -- context, removed and added
 * rows as the diff lists them, across hunks. That order is what "from
 * startKey to endKey" means, and it is the only order in which a removed
 * row has a place.</p>
 *
 * <p>Works on any diff of the scope: the review diff for coverage and
 * verdicts, the whole-file display diff for rendering marks. Keys are the
 * same in both because they are line numbers, not positions.</p>
 */
public final class AnchorIndex {

    public record HunkRef(String file, int index, String digest) {
        public String hunkId() {
            return ReviewIntent.hunkId(file, index);
        }
    }

    public record ChangedRow(String file, String lineKey, int hunkIndex) {
    }

    private record Row(int ordinal, int hunkIndex, boolean changed) {
    }

    private final Map<String, Map<String, Row>> rowsByFile = new LinkedHashMap<>();
    private final Map<String, List<Integer>> newLinesByFile = new HashMap<>();
    private final List<ChangedRow> changedRows = new ArrayList<>();
    private final List<HunkRef> hunks = new ArrayList<>();

    private AnchorIndex() {
    }

    public static AnchorIndex of(UnifiedDiff diff) {
        AnchorIndex index = new AnchorIndex();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            Map<String, Row> rows = new HashMap<>();
            List<Integer> newLines = new ArrayList<>();
            int ordinal = 0;
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                index.hunks.add(new HunkRef(file.path(), hunkIndex, HunkDigest.of(file.path(), hunk)));
                for (UnifiedDiff.Line line : hunk.lines()) {
                    boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                    rows.put(line.lineKey(), new Row(ordinal++, hunkIndex, changed));
                    line.newLine().ifPresent(newLines::add);
                    if (changed) {
                        index.changedRows.add(new ChangedRow(file.path(), line.lineKey(), hunkIndex));
                    }
                }
                hunkIndex++;
            }
            index.rowsByFile.put(file.path(), rows);
            index.newLinesByFile.put(file.path(), newLines);
        }
        return index;
    }

    /** Both keys are rows of the anchor's file and start does not come after end. */
    public boolean resolves(TourAnchor anchor) {
        Map<String, Row> rows = rowsByFile.get(anchor.file());
        if (rows == null) {
            return false;
        }
        Row start = rows.get(anchor.startKey());
        Row end = rows.get(anchor.endKey());
        return start != null && end != null && start.ordinal() <= end.ordinal();
    }

    /** Whether the row {@code file}/{@code lineKey} lies inside {@code anchor}. */
    public boolean contains(TourAnchor anchor, String file, String lineKey) {
        if (!anchor.file().equals(file) || !resolves(anchor)) {
            return false;
        }
        Map<String, Row> rows = rowsByFile.get(file);
        Row row = rows.get(lineKey);
        if (row == null) {
            return false;
        }
        return rows.get(anchor.startKey()).ordinal() <= row.ordinal()
                && row.ordinal() <= rows.get(anchor.endKey()).ordinal();
    }

    /** Hunks with at least one changed row inside {@code anchor}, in diff order. */
    public List<HunkRef> hunksTouched(TourAnchor anchor) {
        List<HunkRef> touched = new ArrayList<>();
        for (HunkRef hunk : hunks) {
            if (!hunk.file().equals(anchor.file())) {
                continue;
            }
            boolean hit = changedRows.stream()
                    .anyMatch(row -> row.file().equals(hunk.file()) && row.hunkIndex() == hunk.index()
                            && contains(anchor, row.file(), row.lineKey()));
            if (hit) {
                touched.add(hunk);
            }
        }
        return touched;
    }

    /** Every added and removed row of the diff, in diff order. */
    public List<ChangedRow> changedRows() {
        return List.copyOf(changedRows);
    }

    /** Every hunk of the diff, in diff order. */
    public List<HunkRef> hunks() {
        return List.copyOf(hunks);
    }

    /** The key of the row at {@code newLine}, or of the nearest post-image row before it. */
    public Optional<String> keyAtOrBefore(String file, int newLine) {
        List<Integer> newLines = newLinesByFile.get(file);
        if (newLines == null) {
            return Optional.empty();
        }
        int best = -1;
        for (int line : newLines) {
            if (line <= newLine && line > best) {
                best = line;
            }
        }
        return best < 0 ? Optional.empty() : Optional.of("n" + best);
    }
}
```

`TourFingerprint.java`:

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity of a review diff: SHA-256 over every hunk's {@link HunkDigest} in
 * diff order. Two diffs with the same hunks have the same fingerprint;
 * line-number shifts alone do not change it, because digests exclude them.
 */
public final class TourFingerprint {

    private TourFingerprint() {
    }

    public static String of(UnifiedDiff diff) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                sha.update(HunkDigest.of(file.path(), hunk).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) '\n');
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.AnchorIndexTest" --tests "app.drydock.review.tour.TourFingerprintTest" --offline`
Expected: BUILD SUCCESSFUL, 10 tests passed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/review/tour app/src/test/java/app/drydock/review/tour
git commit -F - <<'EOF'
Review tour: a tour model whose anchors are line-key ranges of the review diff

There was no way to describe a guided walk through a change: intents
group hunks but cannot point at the rows inside them, and a range over
post-image line numbers cannot address a hunk that only removes lines.

Adds ReviewTour / TourStep / TourCheck / TourAnchor / ImpactNote, an
AnchorIndex that orders every line key of a diff (context, removed and
added rows in diff order) so a key range can be resolved, compared and
mapped to the hunks it touches, and a TourFingerprint over hunk digests.

Verified: ./gradlew :app:test --tests "app.drydock.review.tour.*" --offline
-> 10 passed. Nothing consumes the model yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Tour validation

**Read first:** spec §3 (coverage invariant), §8 (`review_tour` validation list).

**Constraints:** pure code, no I/O (impact-note range checks need the worktree and live in the router, Task 6); error messages are full sentences naming the step/check/hunk id, because they go back to the agent verbatim.

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/TourValidator.java`
- Test: `app/src/test/java/app/drydock/review/tour/TourValidatorTest.java`

**Interfaces:**
- Consumes: Task 1 model, `AnchorIndex`.
- Produces: `static List<String> TourValidator.validate(ReviewTour tour, UnifiedDiff reviewDiff)`; constants `MAX_STEPS = 40`, `MAX_CHECKS_PER_STEP = 6`, `MAX_NARRATIVE = 1000`, `MAX_TITLE = 120`, `MAX_PROMPT = 1000`, `MAX_CHOICE = 300`, `MIN_CHOICES = 2`, `MAX_CHOICES = 4`. Task 12 adds a findings-aware overload.

Rules (each produces one message; validation reports **all** errors, not the first):
1. 1..`MAX_STEPS` steps; step ids unique; check ids (top-level and alternates) unique across the tour.
2. Each step: non-blank title ≤ `MAX_TITLE`; non-blank narrative ≤ `MAX_NARRATIVE`; ≥ 1 anchor; every anchor resolves; 1..`MAX_CHECKS_PER_STEP` checks (the reviewer chose "each step ends with a check").
3. Each check (and each alternate): non-blank prompt ≤ `MAX_PROMPT`; PREDICT/TRACE: `MIN_CHOICES..MAX_CHOICES` choices, each non-blank ≤ `MAX_CHOICE`, answer present and `0 <= answer < choices.size()`; RISK: no choices, no answer. Top-level checks have ≥ 1 alternate; alternates have none.
4. Coverage: every changed row of the review diff lies inside some anchor. One message per hunk with uncovered rows: `hunk h_<file>_<i>: rows <firstKey>..<lastKey> are in no step`.
5. Impact notes: non-blank file, `line >= 1`, non-blank text.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.choice;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.deletedFile;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.risk;
import static app.drydock.review.tour.TourFixtures.step;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourValidatorTest {

    private final UnifiedDiff diff = twoFileDiff();

    private static ReviewTour tour(UnifiedDiff diff, TourStep... steps) {
        return new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(steps));
    }

    private static boolean anyContains(List<String> errors, String fragment) {
        return errors.stream().anyMatch(error -> error.contains(fragment));
    }

    @Test
    void aTourCoveringEveryChangedRowIsValid() {
        assertEquals(List.of(), TourValidator.validate(coveringTour(diff), diff));
    }

    @Test
    void anUncoveredHunkIsNamedWithItsRows() {
        ReviewTour partial = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n4")), predict("c1")));
        List<String> errors = TourValidator.validate(partial, diff);
        assertTrue(anyContains(errors, "hunk h_src/A.java_1: rows o20..n21 are in no step"), errors.toString());
        assertTrue(anyContains(errors, "hunk h_src/B.java_0: rows o5..o6 are in no step"), errors.toString());
    }

    @Test
    void aDeletedFileIsCoveredByOldKeys() {
        UnifiedDiff deleted = new UnifiedDiff(List.of(deletedFile("src/Gone.java", "a", "b")));
        ReviewTour covering = tour(deleted,
                step("s1", List.of(new TourAnchor("src/Gone.java", "o1", "o2")), predict("c1")));
        assertEquals(List.of(), TourValidator.validate(covering, deleted));
    }

    @Test
    void anAnchorThatIsNotARowOfTheDiffIsRejected() {
        ReviewTour bad = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n99")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/A.java", "o20", "n21"), new TourAnchor("src/B.java", "o5", "o6")),
                        risk("c2")));
        assertTrue(anyContains(TourValidator.validate(bad, diff),
                "step s1: anchor src/A.java n1..n99 is not a range of rows of the diff"));
    }

    @Test
    void aStepWithoutChecksIsRejected() {
        ReviewTour bad = tour(diff, new TourStep("s1", "t", "n",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")),
                List.of(), List.of()));
        assertTrue(anyContains(TourValidator.validate(bad, diff), "step s1: needs at least one check"));
    }

    @Test
    void aCheckWithoutAnAlternateIsRejected() {
        TourCheck lonely = new TourCheck("c1", TourCheck.Kind.PREDICT, "p",
                List.of(choice("a"), choice("b")), OptionalInt.of(0), "e", List.of());
        ReviewTour bad = tour(diff, step("s1",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")), lonely));
        assertTrue(anyContains(TourValidator.validate(bad, diff), "check c1: needs at least one alternate"));
    }

    @Test
    void anAnswerOutsideTheChoicesIsRejected() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.PREDICT, "p",
                List.of(choice("a"), choice("b")), OptionalInt.of(0), "e", List.of());
        TourCheck off = new TourCheck("c1", TourCheck.Kind.PREDICT, "p",
                List.of(choice("a"), choice("b")), OptionalInt.of(2), "e", List.of(alternate));
        ReviewTour bad = tour(diff, step("s1",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")), off));
        assertTrue(anyContains(TourValidator.validate(bad, diff),
                "check c1: answer 2 is not one of its 2 choices (0-based)"));
    }

    @Test
    void aRiskCheckWithChoicesIsRejected() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.RISK, "p", List.of(), OptionalInt.empty(), "e",
                List.of());
        TourCheck bad = new TourCheck("c1", TourCheck.Kind.RISK, "p", List.of(choice("a"), choice("b")),
                OptionalInt.empty(), "e", List.of(alternate));
        ReviewTour tour = tour(diff, step("s1",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")), bad));
        assertTrue(anyContains(TourValidator.validate(tour, diff), "check c1: a risk check takes no choices"));
    }

    @Test
    void duplicateIdsAreRejected() {
        ReviewTour dup = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")), predict("c1")),
                step("s1", List.of(new TourAnchor("src/B.java", "o5", "o6")), predict("c1")));
        List<String> errors = TourValidator.validate(dup, diff);
        assertTrue(anyContains(errors, "step id s1 is used twice"), errors.toString());
        assertTrue(anyContains(errors, "check id c1 is used twice"), errors.toString());
    }

    @Test
    void tooManyStepsIsRejected() {
        List<TourStep> steps = new ArrayList<>();
        for (int i = 0; i <= TourValidator.MAX_STEPS; i++) {
            steps.add(step("s" + i, List.of(new TourAnchor("src/A.java", "n1", "n22"),
                    new TourAnchor("src/B.java", "o5", "o6")), predict("c" + i)));
        }
        assertTrue(anyContains(TourValidator.validate(tour(diff, steps.toArray(TourStep[]::new)), diff),
                "a tour has at most 40 steps"));
    }

    @Test
    void anOverlongNarrativeIsRejected() {
        TourStep longOne = new TourStep("s1", "t", "x".repeat(TourValidator.MAX_NARRATIVE + 1),
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")),
                List.of(), List.of(predict("c1")));
        assertTrue(anyContains(TourValidator.validate(tour(diff, longOne), diff),
                "step s1: narrative is longer than 1000 characters"));
    }

    @Test
    void anImpactNoteWithoutALineIsRejected() {
        TourStep noted = new TourStep("s1", "t", "n",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")),
                List.of(new ImpactNote("src/C.java", 0, "assumes non-null")), List.of(predict("c1")));
        assertTrue(anyContains(TourValidator.validate(tour(diff, noted), diff),
                "step s1: impact note on src/C.java needs a line of 1 or more"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.TourValidatorTest" --offline`
Expected: compilation FAILURE (`TourValidator` missing).

- [ ] **Step 3: Implement `TourValidator`**

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an agent's tour against the review diff before anything is stored.
 *
 * <p>Reports every problem, not the first, as full sentences naming the
 * step, check or hunk: the messages go back to the agent verbatim so it can
 * fix them in one resubmission.</p>
 *
 * <p>Coverage is checked per changed row, not per hunk: a step covering
 * three changed lines of a fifty-line hunk must not make the other
 * forty-seven count as reviewed.</p>
 */
public final class TourValidator {

    public static final int MAX_STEPS = 40;
    public static final int MAX_CHECKS_PER_STEP = 6;
    public static final int MAX_NARRATIVE = 1000;
    public static final int MAX_TITLE = 120;
    public static final int MAX_PROMPT = 1000;
    public static final int MAX_CHOICE = 300;
    public static final int MIN_CHOICES = 2;
    public static final int MAX_CHOICES = 4;

    private TourValidator() {
    }

    public static List<String> validate(ReviewTour tour, UnifiedDiff reviewDiff) {
        List<String> errors = new ArrayList<>();
        AnchorIndex index = AnchorIndex.of(reviewDiff);
        if (tour.steps().isEmpty()) {
            errors.add("a tour needs at least one step");
        }
        if (tour.steps().size() > MAX_STEPS) {
            errors.add("a tour has at most " + MAX_STEPS + " steps; this one has " + tour.steps().size());
        }
        Set<String> stepIds = new HashSet<>();
        Set<String> checkIds = new HashSet<>();
        for (TourStep step : tour.steps()) {
            if (!stepIds.add(step.id())) {
                errors.add("step id " + step.id() + " is used twice");
            }
            validateStep(step, index, checkIds, errors);
        }
        validateCoverage(tour, index, errors);
        return List.copyOf(errors);
    }

    private static void validateStep(TourStep step, AnchorIndex index, Set<String> checkIds, List<String> errors) {
        String where = "step " + step.id() + ": ";
        if (step.title().isBlank()) {
            errors.add(where + "needs a title");
        } else if (step.title().length() > MAX_TITLE) {
            errors.add(where + "title is longer than " + MAX_TITLE + " characters");
        }
        if (step.narrative().isBlank()) {
            errors.add(where + "needs a narrative");
        } else if (step.narrative().length() > MAX_NARRATIVE) {
            errors.add(where + "narrative is longer than " + MAX_NARRATIVE + " characters");
        }
        if (step.anchors().isEmpty()) {
            errors.add(where + "needs at least one anchor");
        }
        for (TourAnchor anchor : step.anchors()) {
            if (!index.resolves(anchor)) {
                errors.add(where + "anchor " + anchor.file() + " " + anchor.startKey() + ".." + anchor.endKey()
                        + " is not a range of rows of the diff");
            }
        }
        if (step.checks().isEmpty()) {
            errors.add(where + "needs at least one check");
        }
        if (step.checks().size() > MAX_CHECKS_PER_STEP) {
            errors.add(where + "has more than " + MAX_CHECKS_PER_STEP + " checks");
        }
        for (TourCheck check : step.checks()) {
            validateCheck(check, true, checkIds, errors);
        }
        for (ImpactNote note : step.impactNotes()) {
            if (note.file().isBlank()) {
                errors.add(where + "an impact note needs a file");
            } else if (note.line() < 1) {
                errors.add(where + "impact note on " + note.file() + " needs a line of 1 or more");
            }
            if (note.text().isBlank()) {
                errors.add(where + "impact note on " + note.file() + " needs text");
            }
        }
    }

    private static void validateCheck(TourCheck check, boolean topLevel, Set<String> checkIds, List<String> errors) {
        String where = "check " + check.id() + ": ";
        if (!checkIds.add(check.id())) {
            errors.add("check id " + check.id() + " is used twice");
        }
        if (check.prompt().isBlank()) {
            errors.add(where + "needs a prompt");
        } else if (check.prompt().length() > MAX_PROMPT) {
            errors.add(where + "prompt is longer than " + MAX_PROMPT + " characters");
        }
        if (check.kind() == TourCheck.Kind.RISK) {
            if (!check.choices().isEmpty()) {
                errors.add(where + "a risk check takes no choices");
            }
            if (check.answer().isPresent()) {
                errors.add(where + "a risk check takes no answer");
            }
        } else {
            int count = check.choices().size();
            if (count < MIN_CHOICES || count > MAX_CHOICES) {
                errors.add(where + "needs " + MIN_CHOICES + " to " + MAX_CHOICES + " choices; it has " + count);
            }
            for (TourCheck.Choice choice : check.choices()) {
                if (choice.text().isBlank() || choice.text().length() > MAX_CHOICE) {
                    errors.add(where + "every choice needs text of at most " + MAX_CHOICE + " characters");
                    break;
                }
            }
            if (check.answer().isEmpty()) {
                errors.add(where + "needs an answer (0-based index into its choices)");
            } else if (check.answer().getAsInt() < 0 || check.answer().getAsInt() >= count) {
                errors.add(where + "answer " + check.answer().getAsInt() + " is not one of its " + count
                        + " choices (0-based)");
            }
        }
        if (topLevel && check.alternates().isEmpty()) {
            errors.add(where + "needs at least one alternate, offered after a wrong answer");
        }
        if (!topLevel && !check.alternates().isEmpty()) {
            errors.add(where + "an alternate cannot have alternates of its own");
        }
        for (TourCheck alternate : check.alternates()) {
            validateCheck(alternate, false, checkIds, errors);
        }
    }

    private static void validateCoverage(ReviewTour tour, AnchorIndex index, List<String> errors) {
        Map<String, List<String>> uncoveredByHunk = new LinkedHashMap<>();
        for (AnchorIndex.ChangedRow row : index.changedRows()) {
            boolean covered = tour.steps().stream()
                    .flatMap(step -> step.anchors().stream())
                    .anyMatch(anchor -> index.contains(anchor, row.file(), row.lineKey()));
            if (!covered) {
                String hunkId = new AnchorIndex.HunkRef(row.file(), row.hunkIndex(), "").hunkId();
                uncoveredByHunk.computeIfAbsent(hunkId, id -> new ArrayList<>()).add(row.lineKey());
            }
        }
        uncoveredByHunk.forEach((hunkId, keys) -> errors.add("hunk " + hunkId + ": rows " + keys.getFirst()
                + ".." + keys.getLast() + " are in no step"));
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.TourValidatorTest" --offline`
Expected: 12 passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/tour/TourValidator.java app/src/test/java/app/drydock/review/tour/TourValidatorTest.java
git commit -F - <<'EOF'
Review tour: a tour is checked against the diff, every changed row covered

Nothing stood between an agent's tour and the reviewer: an anchor that
named no row, a choice check whose answer key pointed past its choices, or
a hunk no step covered would all have reached the UI.

TourValidator reports every problem as a sentence naming the step, check
or hunk, so the agent can fix them in one resubmission. Coverage is per
changed row, not per hunk: a step covering three lines of a fifty-line
hunk must not count the other forty-seven as reviewed. Each step needs at
least one check, and each check at least one alternate.

Verified: ./gradlew :app:test --tests "app.drydock.review.tour.TourValidatorTest"
--offline -> 12 passed. Impact-note range checks need the worktree and
are done by the MCP router, not here.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: Step progress, grading and derived verdicts

**Read first:** spec §3 (grading, wrong answers), §7 (verdicts derived from steps, overrides).

**Constraints:** pure, immutable records; a hunk is APPROVED only when **every** covering step has passed or been overridden; any covering step requesting changes makes it CHANGES.

**Design note (refines spec §5's "recorded as an override on every step covering that hunk"):** a hunk-diff approval is recorded as a *hunk* override, not a step override. Overriding whole steps would approve every other hunk those steps cover, which is the partial-step bug the spec set out to prevent. The submit sheet lists hunk overrides with step overrides (Task 20).

**Design note (RISK `partly`):** `partly` passes the check (the risk the reviewer named is real but incomplete) and the agent's reason is shown. `doesNotHold` fails it.

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/CheckProgress.java`
- Create: `app/src/main/java/app/drydock/review/tour/StepProgress.java`
- Create: `app/src/main/java/app/drydock/review/tour/HunkOverride.java`
- Create: `app/src/main/java/app/drydock/review/tour/TourRecord.java`
- Create: `app/src/main/java/app/drydock/review/tour/StepGrading.java`
- Create: `app/src/main/java/app/drydock/review/tour/StepGate.java`
- Create: `app/src/main/java/app/drydock/review/tour/StepVerdicts.java`
- Test: `app/src/test/java/app/drydock/review/tour/StepGradingTest.java`
- Test: `app/src/test/java/app/drydock/review/tour/StepVerdictsTest.java`

**Interfaces:**
- Consumes: Task 1 model, `AnchorIndex`, `ReviewVerdict.Decision` (`APPROVED`, `CHANGES`, `AUTO_APPROVED`).
- Produces:
  - `record CheckProgress(String checkId, int attempt, Status status, Optional<String> riskAnswer, Optional<String> agentReason, Optional<String> lastExplanation)`; `enum Status { OPEN, PASSED, EXHAUSTED, AWAITING_AGENT, AGENT_UNAVAILABLE, VOIDED }`; `static CheckProgress fresh(String checkId)`; `boolean settled()` (PASSED or VOIDED)
  - `record StepProgress(String stepId, List<String> hunkDigests, Map<String, CheckProgress> checks, Decision decision, Optional<String> overrideReason, boolean stale)`; `enum Decision { NONE, PASSED, CHANGES, OVERRIDDEN }`; `static StepProgress fresh(TourStep step, AnchorIndex index)`; `CheckProgress check(String checkId)`; `StepProgress withCheck(CheckProgress)`, `withDecision(Decision, Optional<String> reason)`, `withStale(boolean)`
  - `record HunkOverride(ReviewVerdict.Decision decision, String reason)`
  - `record TourRecord(ReviewTour tour, Map<String, StepProgress> progress, Map<String, HunkOverride> hunkOverrides, Map<String, List<String>> hunkRows, boolean reviewAnyway, boolean shelved)`; `static TourRecord fresh(ReviewTour tour, UnifiedDiff reviewDiff)`; `StepProgress progress(String stepId)`; `withProgress(StepProgress)`, `withHunkOverride(String digest, Optional<HunkOverride>)`, `withReviewAnyway(boolean)`, `withShelved(boolean)`, `withTour(ReviewTour)`
  - `StepGrading`: `static CheckProgress answerChoice(TourCheck check, CheckProgress progress, int choiceIndex)`, `static CheckProgress submitRisk(CheckProgress progress, String answer)`, `static CheckProgress applyRiskVerdict(TourCheck check, CheckProgress progress, RiskVerdict verdict, String reason)`, `static CheckProgress markAgentUnavailable(CheckProgress)`, `static CheckProgress retryRisk(CheckProgress)`, `static CheckProgress voided(CheckProgress)`; `enum RiskVerdict { HOLDS, PARTLY, DOES_NOT_HOLD }` with `wireName()` (`holds`, `partly`, `doesNotHold`) and `fromWire(String)`
  - `StepGate`: `record Unmet(Kind kind, String id, String message)`, `enum Kind { CHECK, TRIAGE, BLOCKER, STALE }`; `static Optional<Unmet> unmet(TourStep step, StepProgress progress)` (Task 12 adds a findings-aware overload)
  - `StepVerdicts`: `static Map<String, Optional<ReviewVerdict.Decision>> derive(TourRecord record, AnchorIndex reviewIndex)` keyed by hunk digest, covering every hunk of the index

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.review.tour;

import org.junit.jupiter.api.Test;

import java.util.List;

import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.risk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepGradingTest {

    @Test
    void theRightChoicePassesTheCheck() {
        TourCheck check = predict("c1");
        CheckProgress after = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 1);
        assertEquals(CheckProgress.Status.PASSED, after.status());
        assertTrue(after.settled());
    }

    @Test
    void aWrongChoiceMovesToTheAlternateAndKeepsTheExplanation() {
        TourCheck check = predict("c1");
        CheckProgress after = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 0);
        assertEquals(CheckProgress.Status.OPEN, after.status());
        assertEquals(1, after.attempt());
        assertEquals("It returns early.", after.lastExplanation().orElseThrow());
    }

    @Test
    void aWrongAnswerOnTheLastVersionExhaustsTheCheck() {
        TourCheck check = predict("c1");
        CheckProgress onAlternate = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 0);
        CheckProgress after = StepGrading.answerChoice(check, onAlternate, 0);
        assertEquals(CheckProgress.Status.EXHAUSTED, after.status());
        assertEquals("Because.", after.lastExplanation().orElseThrow());
    }

    @Test
    void answeringASettledCheckChangesNothing() {
        TourCheck check = predict("c1");
        CheckProgress passed = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 1);
        assertEquals(passed, StepGrading.answerChoice(check, passed, 0));
    }

    @Test
    void aRiskAnswerWaitsForTheAgent() {
        CheckProgress after = StepGrading.submitRisk(CheckProgress.fresh("c2"), "an empty list");
        assertEquals(CheckProgress.Status.AWAITING_AGENT, after.status());
        assertEquals("an empty list", after.riskAnswer().orElseThrow());
    }

    @Test
    void holdsAndPartlyPassButDoesNotHoldMovesOn() {
        TourCheck check = risk("c2");
        CheckProgress waiting = StepGrading.submitRisk(CheckProgress.fresh("c2"), "x");
        assertEquals(CheckProgress.Status.PASSED,
                StepGrading.applyRiskVerdict(check, waiting, StepGrading.RiskVerdict.HOLDS, "yes").status());
        CheckProgress partly = StepGrading.applyRiskVerdict(check, waiting, StepGrading.RiskVerdict.PARTLY, "half");
        assertEquals(CheckProgress.Status.PASSED, partly.status());
        assertEquals("half", partly.agentReason().orElseThrow());
        CheckProgress wrong = StepGrading.applyRiskVerdict(check, waiting, StepGrading.RiskVerdict.DOES_NOT_HOLD, "no");
        assertEquals(CheckProgress.Status.OPEN, wrong.status());
        assertEquals(1, wrong.attempt());
    }

    @Test
    void aVoidedCheckIsSettled() {
        assertTrue(StepGrading.voided(CheckProgress.fresh("c1")).settled());
    }

    @Test
    void theGateNamesTheFirstUnsettledCheck() {
        TourStep step = TourFixtures.step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")),
                predict("c1"), risk("c2"));
        StepProgress progress = StepProgress.fresh(step, AnchorIndex.of(TourFixtures.twoFileDiff()));
        StepGate.Unmet unmet = StepGate.unmet(step, progress).orElseThrow();
        assertEquals(StepGate.Kind.CHECK, unmet.kind());
        assertEquals("c1", unmet.id());
        StepProgress oneDone = progress.withCheck(StepGrading.answerChoice(predict("c1"), progress.check("c1"), 1));
        assertEquals("c2", StepGate.unmet(step, oneDone).orElseThrow().id());
    }

    @Test
    void aStaleStepCannotPass() {
        TourStep step = TourFixtures.step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")),
                predict("c1"));
        StepProgress stale = StepProgress.fresh(step, AnchorIndex.of(TourFixtures.twoFileDiff())).withStale(true);
        assertEquals(StepGate.Kind.STALE, StepGate.unmet(step, stale).orElseThrow().kind());
    }
}
```

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.step;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StepVerdictsTest {

    private final UnifiedDiff diff = twoFileDiff();
    private final AnchorIndex index = AnchorIndex.of(diff);
    private final String hunkA0 = index.hunks().get(0).digest();
    private final String hunkA1 = index.hunks().get(1).digest();

    /** s1 covers A#0 and part of A#1 (o20 only); s2 covers the rest of A#1 (n21) and B#0. */
    private TourRecord overlapping() {
        ReviewTour tour = new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "o20")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/A.java", "n21", "n21"),
                        new TourAnchor("src/B.java", "o5", "o6")), predict("c2"))));
        return TourRecord.fresh(tour, diff);
    }

    private static TourRecord decide(TourRecord record, String stepId, StepProgress.Decision decision) {
        return record.withProgress(record.progress(stepId).withDecision(decision, Optional.empty()));
    }

    @Test
    void nothingIsSettledBeforeAnyStepPasses() {
        Map<String, Optional<ReviewVerdict.Decision>> derived = StepVerdicts.derive(overlapping(), index);
        assertEquals(3, derived.size());
        derived.values().forEach(value -> assertEquals(Optional.empty(), value));
    }

    @Test
    void aHunkSplitAcrossTwoStepsNeedsBothToPass() {
        TourRecord oneDone = decide(overlapping(), "s1", StepProgress.Decision.PASSED);
        Map<String, Optional<ReviewVerdict.Decision>> derived = StepVerdicts.derive(oneDone, index);
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), derived.get(hunkA0));
        assertEquals(Optional.empty(), derived.get(hunkA1), "s2 also covers A#1 and has not passed");
        TourRecord both = decide(oneDone, "s2", StepProgress.Decision.OVERRIDDEN);
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), StepVerdicts.derive(both, index).get(hunkA1));
    }

    @Test
    void anyCoveringStepRequestingChangesWins() {
        TourRecord record = decide(decide(overlapping(), "s1", StepProgress.Decision.PASSED),
                "s2", StepProgress.Decision.CHANGES);
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES), StepVerdicts.derive(record, index).get(hunkA1));
    }

    @Test
    void aHunkOverrideSettlesOnlyThatHunk() {
        TourRecord record = overlapping().withHunkOverride(hunkA1,
                Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "approved in the hunk diff")));
        Map<String, Optional<ReviewVerdict.Decision>> derived = StepVerdicts.derive(record, index);
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), derived.get(hunkA1));
        assertEquals(Optional.empty(), derived.get(hunkA0));
    }

    @Test
    void aStepRequestingChangesBeatsAHunkOverride() {
        TourRecord record = decide(overlapping(), "s1", StepProgress.Decision.CHANGES).withHunkOverride(hunkA1,
                Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "approved in the hunk diff")));
        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES), StepVerdicts.derive(record, index).get(hunkA1));
    }

    @Test
    void aStaleStepDoesNotCountAsPassed() {
        TourRecord record = decide(overlapping(), "s1", StepProgress.Decision.PASSED);
        record = record.withProgress(record.progress("s1").withStale(true));
        assertEquals(Optional.empty(), StepVerdicts.derive(record, index).get(hunkA0));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.StepGradingTest" --tests "app.drydock.review.tour.StepVerdictsTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement the progress records**

`CheckProgress.java`:

```java
package app.drydock.review.tour;

import java.util.Objects;
import java.util.Optional;

/** Where the reviewer stands on one top-level check (its alternates share this record). */
public record CheckProgress(String checkId, int attempt, Status status, Optional<String> riskAnswer,
                            Optional<String> agentReason, Optional<String> lastExplanation) {

    public enum Status { OPEN, PASSED, EXHAUSTED, AWAITING_AGENT, AGENT_UNAVAILABLE, VOIDED }

    public CheckProgress {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(riskAnswer, "riskAnswer");
        Objects.requireNonNull(agentReason, "agentReason");
        Objects.requireNonNull(lastExplanation, "lastExplanation");
        attempt = Math.max(0, attempt);
    }

    public static CheckProgress fresh(String checkId) {
        return new CheckProgress(checkId, 0, Status.OPEN, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Passed, or voided because the finding it was built on was dismissed. */
    public boolean settled() {
        return status == Status.PASSED || status == Status.VOIDED;
    }

    CheckProgress with(int newAttempt, Status newStatus, Optional<String> newRiskAnswer,
                       Optional<String> newAgentReason, Optional<String> newExplanation) {
        return new CheckProgress(checkId, newAttempt, newStatus, newRiskAnswer, newAgentReason, newExplanation);
    }
}
```

`StepProgress.java`:

```java
package app.drydock.review.tour;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The reviewer's progress on one step, and the digests of the review-diff
 * hunks the step covers -- the key both staleness and verdict derivation
 * work from.
 */
public record StepProgress(String stepId, List<String> hunkDigests, Map<String, CheckProgress> checks,
                           Decision decision, Optional<String> overrideReason, boolean stale) {

    public enum Decision { NONE, PASSED, CHANGES, OVERRIDDEN }

    public StepProgress {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(overrideReason, "overrideReason");
        hunkDigests = List.copyOf(hunkDigests);
        checks = Map.copyOf(checks);
    }

    public static StepProgress fresh(TourStep step, AnchorIndex index) {
        Map<String, CheckProgress> checks = new LinkedHashMap<>();
        for (TourCheck check : step.checks()) {
            checks.put(check.id(), CheckProgress.fresh(check.id()));
        }
        List<String> digests = step.anchors().stream()
                .flatMap(anchor -> index.hunksTouched(anchor).stream())
                .map(AnchorIndex.HunkRef::digest)
                .distinct()
                .toList();
        return new StepProgress(step.id(), digests, checks, Decision.NONE, Optional.empty(), false);
    }

    /** Progress on top-level check {@code checkId}; fresh if none was recorded. */
    public CheckProgress check(String checkId) {
        return checks.getOrDefault(checkId, CheckProgress.fresh(checkId));
    }

    public StepProgress withCheck(CheckProgress progress) {
        Map<String, CheckProgress> next = new LinkedHashMap<>(checks);
        next.put(progress.checkId(), progress);
        return new StepProgress(stepId, hunkDigests, next, decision, overrideReason, stale);
    }

    public StepProgress withDecision(Decision newDecision, Optional<String> reason) {
        return new StepProgress(stepId, hunkDigests, checks, newDecision, reason, stale);
    }

    public StepProgress withStale(boolean newStale) {
        return new StepProgress(stepId, hunkDigests, checks, decision, overrideReason, newStale);
    }

    public StepProgress withHunkDigests(List<String> digests) {
        return new StepProgress(stepId, digests, checks, decision, overrideReason, stale);
    }

    /** Passed or overridden, and not stale. */
    public boolean settledForApproval() {
        return !stale && (decision == Decision.PASSED || decision == Decision.OVERRIDDEN);
    }
}
```

`HunkOverride.java`:

```java
package app.drydock.review.tour;

import app.drydock.review.ReviewVerdict;

import java.util.Objects;

/** A verdict the reviewer set on one hunk in the hunk diff while a tour existed. */
public record HunkOverride(ReviewVerdict.Decision decision, String reason) {

    public HunkOverride {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
    }
}
```

`TourRecord.java`:

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything persisted about one scope's tour: the tour, per-step progress,
 * hunk overrides from the hunk diff, and -- for migrating anchors when the
 * diff moves under it -- the row keys of every hunk the tour was written
 * against.
 */
public record TourRecord(ReviewTour tour, Map<String, StepProgress> progress,
                         Map<String, HunkOverride> hunkOverrides, Map<String, List<String>> hunkRows,
                         boolean reviewAnyway, boolean shelved) {

    public TourRecord {
        Objects.requireNonNull(tour, "tour");
        progress = Map.copyOf(progress);
        hunkOverrides = Map.copyOf(hunkOverrides);
        hunkRows = Map.copyOf(hunkRows);
    }

    public static TourRecord fresh(ReviewTour tour, UnifiedDiff reviewDiff) {
        AnchorIndex index = AnchorIndex.of(reviewDiff);
        Map<String, StepProgress> progress = new LinkedHashMap<>();
        for (TourStep step : tour.steps()) {
            progress.put(step.id(), StepProgress.fresh(step, index));
        }
        return new TourRecord(tour, progress, Map.of(), rowsOf(reviewDiff), false, false);
    }

    /** Row keys per hunk digest, in diff order. */
    public static Map<String, List<String>> rowsOf(UnifiedDiff diff) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                rows.put(HunkDigest.of(file.path(), hunk),
                        hunk.lines().stream().map(UnifiedDiff.Line::lineKey).toList());
            }
        }
        return rows;
    }

    public StepProgress progress(String stepId) {
        StepProgress existing = progress.get(stepId);
        if (existing != null) {
            return existing;
        }
        TourStep step = tour.step(stepId).orElseThrow(() -> new IllegalArgumentException("no step " + stepId));
        return new StepProgress(step.id(), List.of(), Map.of(), StepProgress.Decision.NONE, Optional.empty(), false);
    }

    public TourRecord withProgress(StepProgress stepProgress) {
        Map<String, StepProgress> next = new LinkedHashMap<>(progress);
        next.put(stepProgress.stepId(), stepProgress);
        return new TourRecord(tour, next, hunkOverrides, hunkRows, reviewAnyway, shelved);
    }

    public TourRecord withHunkOverride(String digest, Optional<HunkOverride> override) {
        Map<String, HunkOverride> next = new LinkedHashMap<>(hunkOverrides);
        override.ifPresentOrElse(value -> next.put(digest, value), () -> next.remove(digest));
        return new TourRecord(tour, progress, next, hunkRows, reviewAnyway, shelved);
    }

    public TourRecord withReviewAnyway(boolean value) {
        return new TourRecord(tour, progress, hunkOverrides, hunkRows, value, shelved);
    }

    public TourRecord withShelved(boolean value) {
        return new TourRecord(tour, progress, hunkOverrides, hunkRows, reviewAnyway, value);
    }

    public TourRecord withTour(ReviewTour newTour) {
        return new TourRecord(newTour, progress, hunkOverrides, hunkRows, reviewAnyway, shelved);
    }

    public TourRecord withHunkRows(Map<String, List<String>> rows) {
        return new TourRecord(tour, progress, hunkOverrides, rows, reviewAnyway, shelved);
    }
}
```

- [ ] **Step 4: Implement `StepGrading`, `StepGate`, `StepVerdicts`**

`StepGrading.java`:

```java
package app.drydock.review.tour;

import java.util.Locale;
import java.util.Optional;

/** The grading rules for tour checks, as pure transitions of {@link CheckProgress}. */
public final class StepGrading {

    public enum RiskVerdict {
        HOLDS("holds"),
        PARTLY("partly"),
        DOES_NOT_HOLD("doesNotHold");

        private final String wireName;

        RiskVerdict(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<RiskVerdict> fromWire(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (RiskVerdict verdict : values()) {
                if (verdict.wireName.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return Optional.of(verdict);
                }
            }
            return Optional.empty();
        }
    }

    private StepGrading() {
    }

    /** Grades a choice against the answer key of the version currently offered. */
    public static CheckProgress answerChoice(TourCheck check, CheckProgress progress, int choiceIndex) {
        if (progress.status() != CheckProgress.Status.OPEN || !check.gradedLocally()) {
            return progress;
        }
        TourCheck offered = check.version(progress.attempt());
        if (offered.answer().isPresent() && offered.answer().getAsInt() == choiceIndex) {
            return progress.with(progress.attempt(), CheckProgress.Status.PASSED, Optional.empty(),
                    Optional.empty(), progress.lastExplanation());
        }
        return wrong(check, progress, offered);
    }

    public static CheckProgress submitRisk(CheckProgress progress, String answer) {
        if (progress.status() != CheckProgress.Status.OPEN) {
            return progress;
        }
        return progress.with(progress.attempt(), CheckProgress.Status.AWAITING_AGENT, Optional.of(answer),
                Optional.empty(), progress.lastExplanation());
    }

    public static CheckProgress applyRiskVerdict(TourCheck check, CheckProgress progress, RiskVerdict verdict,
                                                 String reason) {
        if (progress.status() != CheckProgress.Status.AWAITING_AGENT) {
            return progress;
        }
        if (verdict == RiskVerdict.DOES_NOT_HOLD) {
            CheckProgress next = wrong(check, progress, check.version(progress.attempt()));
            return next.with(next.attempt(), next.status(), Optional.empty(), Optional.of(reason),
                    next.lastExplanation());
        }
        return progress.with(progress.attempt(), CheckProgress.Status.PASSED, progress.riskAnswer(),
                Optional.of(reason), progress.lastExplanation());
    }

    public static CheckProgress markAgentUnavailable(CheckProgress progress) {
        if (progress.status() != CheckProgress.Status.AWAITING_AGENT) {
            return progress;
        }
        return progress.with(progress.attempt(), CheckProgress.Status.AGENT_UNAVAILABLE, progress.riskAnswer(),
                Optional.empty(), progress.lastExplanation());
    }

    public static CheckProgress retryRisk(CheckProgress progress) {
        if (progress.status() != CheckProgress.Status.AGENT_UNAVAILABLE) {
            return progress;
        }
        return progress.with(progress.attempt(), CheckProgress.Status.AWAITING_AGENT, progress.riskAnswer(),
                Optional.empty(), progress.lastExplanation());
    }

    public static CheckProgress voided(CheckProgress progress) {
        return progress.with(progress.attempt(), CheckProgress.Status.VOIDED, progress.riskAnswer(),
                progress.agentReason(), progress.lastExplanation());
    }

    private static CheckProgress wrong(TourCheck check, CheckProgress progress, TourCheck offered) {
        int next = progress.attempt() + 1;
        Optional<String> explanation = Optional.of(offered.explanation());
        if (next < check.versions()) {
            return progress.with(next, CheckProgress.Status.OPEN, Optional.empty(), Optional.empty(), explanation);
        }
        return progress.with(progress.attempt(), CheckProgress.Status.EXHAUSTED, Optional.empty(),
                Optional.empty(), explanation);
    }
}
```

`StepGate.java`:

```java
package app.drydock.review.tour;

import java.util.Objects;
import java.util.Optional;

/** What still stands between a step and passing it, first requirement first. */
public final class StepGate {

    public enum Kind { STALE, CHECK, TRIAGE, BLOCKER }

    public record Unmet(Kind kind, String id, String message) {
        public Unmet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(message, "message");
        }
    }

    private StepGate() {
    }

    public static Optional<Unmet> unmet(TourStep step, StepProgress progress) {
        if (progress.stale()) {
            return Optional.of(new Unmet(Kind.STALE, step.id(),
                    "This step's code changed since the tour was written; it is waiting for the agent."));
        }
        for (TourCheck check : step.checks()) {
            if (!progress.check(check.id()).settled()) {
                return Optional.of(new Unmet(Kind.CHECK, check.id(), "Answer this check first."));
            }
        }
        return Optional.empty();
    }
}
```

`StepVerdicts.java`:

```java
package app.drydock.review.tour;

import app.drydock.review.ReviewVerdict;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the one stored verdict per hunk from the steps covering it.
 *
 * <p>CHANGES if any covering step requested changes; otherwise a hunk
 * override from the hunk diff decides; otherwise APPROVED only when every
 * covering step has passed or been overridden; otherwise unsettled.
 * Steps never write verdicts themselves, so two steps cannot race for one
 * hunk's slot and a step covering part of a hunk cannot approve all of it.</p>
 */
public final class StepVerdicts {

    private StepVerdicts() {
    }

    public static Map<String, Optional<ReviewVerdict.Decision>> derive(TourRecord record, AnchorIndex reviewIndex) {
        Map<String, Optional<ReviewVerdict.Decision>> derived = new LinkedHashMap<>();
        for (AnchorIndex.HunkRef hunk : reviewIndex.hunks()) {
            List<StepProgress> covering = record.progress().values().stream()
                    .filter(progress -> progress.hunkDigests().contains(hunk.digest()))
                    .toList();
            derived.put(hunk.digest(), decide(covering, Optional.ofNullable(record.hunkOverrides().get(hunk.digest()))));
        }
        return derived;
    }

    private static Optional<ReviewVerdict.Decision> decide(List<StepProgress> covering,
                                                           Optional<HunkOverride> override) {
        boolean changes = covering.stream()
                .anyMatch(progress -> !progress.stale() && progress.decision() == StepProgress.Decision.CHANGES);
        if (changes) {
            return Optional.of(ReviewVerdict.Decision.CHANGES);
        }
        if (override.isPresent()) {
            return Optional.of(override.get().decision());
        }
        if (!covering.isEmpty() && covering.stream().allMatch(StepProgress::settledForApproval)) {
            return Optional.of(ReviewVerdict.Decision.APPROVED);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.*" --offline`
Expected: all tour tests pass (10 + 12 + 9 + 6 = 37).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/review/tour app/src/test/java/app/drydock/review/tour
git commit -F - <<'EOF'
Review tour: checks are graded and hunk verdicts are derived from steps

A tour needs rules for what a reviewer's answer does and for how step
progress becomes the per-hunk verdict the rest of Review reads. Writing
verdicts from steps directly would let two overlapping steps race for one
slot, and let a step covering three lines of a hunk approve all of it.

StepGrading grades choice checks locally (wrong answer -> explanation and
the next alternate, then exhausted) and moves RISK checks through
awaiting-agent; "partly" passes, "doesNotHold" fails. StepVerdicts derives
each hunk's verdict: CHANGES if any covering step asked for changes, else a
hunk override, else APPROVED only when every covering step passed or was
overridden. A hunk-diff approval is a hunk override rather than an
override of every covering step, which would have approved their other
hunks too -- a refinement of spec section 5.

Verified: ./gradlew :app:test --tests "app.drydock.review.tour.*" --offline
-> 37 passed.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: Tour JSON codec

**Read first:** spec §7 (persistence: lenient, per entry), §8 (agent shape, caps).

**Constraints:** agent decode is strict (`InvalidTour` naming the path, e.g. `steps[2].checks[0].answer`); persisted decode is lenient per step; `JsonParser`/`JsonWriter` from `app.drydock.state.json`; no FQCNs; length caps enforced **before** allocating records from oversized input (reject strings longer than the caps in `TourValidator`, and arrays longer than `MAX_STEPS` / `MAX_CHECKS_PER_STEP` / `MAX_CHOICES` / 20 anchors / 20 impact notes / 6 alternates).

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/TourCodec.java`
- Test: `app/src/test/java/app/drydock/review/tour/TourCodecTest.java`

**Interfaces:**
- Consumes: Tasks 1 and 3 types; `JsonValue` (`JsonObject.get/put/has/empty`, `JsonArray.elements()/of`, `JsonString.value()`, `JsonNumber.of/asInt`, `JsonBoolean.value()`), `JsonParser.parse`, `JsonWriter.write`.
- Produces:
  - `static final class InvalidTour extends Exception` (`InvalidTour(String message)`)
  - `static List<TourStep> stepsFromAgent(JsonValue value) throws InvalidTour`
  - `static TourStep stepFromJson(JsonValue value, String path) throws InvalidTour`
  - `static JsonValue stepToJson(TourStep step)` (the same shape the agent sends)
  - `static JsonValue recordToJson(TourRecord record)`
  - `static Optional<TourRecord> recordFromJson(JsonValue value)` (lenient)

Agent shape (also the persisted step shape):

```json
{"id":"s1","title":"…","narrative":"…",
 "anchors":[{"file":"src/A.java","startKey":"n3","endKey":"n21"}],
 "impactNotes":[{"file":"src/C.java","line":88,"text":"…"}],
 "checks":[{"id":"c1","kind":"predict","prompt":"…",
            "choices":[{"text":"…","at":{"file":"src/C.java","line":88}}],
            "answer":1,"explanation":"…",
            "alternates":[{ …same fields, no alternates… }]}]}
```

`endKey` defaults to `startKey`; `impactNotes`, `choices`, `at`, `alternates` are optional; `answer` is a 0-based integer.

Persisted record shape:

```json
{"tour":{"scopeId":"…","fingerprint":"…","steps":[…]},
 "progress":{"s1":{"hunkDigests":["…"],"decision":"PASSED","override":"…","stale":false,
                   "checks":{"c1":{"attempt":0,"status":"PASSED","riskAnswer":"…","agentReason":"…","lastExplanation":"…"}}}},
 "hunkOverrides":{"<digest>":{"decision":"approved","reason":"…"}},
 "hunkRows":{"<digest>":["n1","n2"]},
 "reviewAnyway":false,"shelved":false}
```

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewVerdict;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourCodecTest {

    private static final String ONE_STEP = """
            [{"id":"s1","title":"Guard","narrative":"Why.",
              "anchors":[{"file":"src/A.java","startKey":"n1","endKey":"n22"}],
              "checks":[{"id":"c1","kind":"predict","prompt":"What happens?",
                         "choices":[{"text":"throws"},{"text":"returns","at":{"file":"src/C.java","line":88}}],
                         "answer":1,"explanation":"It returns.",
                         "alternates":[{"id":"c1b","kind":"risk","prompt":"Name a risk.","explanation":"Empty."}]}]}]
            """;

    @Test
    void anAgentStepDecodesWithChoicesLocationsAndAlternates() throws Exception {
        List<TourStep> steps = TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP));
        TourCheck check = steps.getFirst().checks().getFirst();
        assertEquals(TourCheck.Kind.PREDICT, check.kind());
        assertEquals(1, check.answer().getAsInt());
        assertEquals(new TourCheck.Location("src/C.java", 88), check.choices().get(1).at().orElseThrow());
        assertEquals(TourCheck.Kind.RISK, check.alternates().getFirst().kind());
    }

    @Test
    void endKeyDefaultsToStartKey() throws Exception {
        List<TourStep> steps = TourCodec.stepsFromAgent(JsonParser.parse(
                ONE_STEP.replace("\"startKey\":\"n1\",\"endKey\":\"n22\"", "\"startKey\":\"n3\"")));
        assertEquals("n3", steps.getFirst().anchors().getFirst().endKey());
    }

    @Test
    void aNonIntegerAnswerIsRejectedNamingItsPath() {
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP.replace("\"answer\":1", "\"answer\":\"1\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].answer"), error.getMessage());
    }

    @Test
    void anUnknownKindIsRejected() {
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(ONE_STEP.replace("\"predict\"", "\"quiz\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].kind"), error.getMessage());
    }

    @Test
    void anOverlongStringIsRejectedBeforeDecoding() {
        String huge = ONE_STEP.replace("\"Why.\"", "\"" + "x".repeat(TourValidator.MAX_NARRATIVE + 1) + "\"");
        TourCodec.InvalidTour error = assertThrows(TourCodec.InvalidTour.class,
                () -> TourCodec.stepsFromAgent(JsonParser.parse(huge)));
        assertTrue(error.getMessage().contains("steps[0].narrative"), error.getMessage());
    }

    @Test
    void stepsMustBeAnArray() {
        assertThrows(TourCodec.InvalidTour.class, () -> TourCodec.stepsFromAgent(JsonParser.parse("{}")));
    }

    @Test
    void aRecordRoundTripsWithProgressAndOverrides() {
        UnifiedDiff diff = twoFileDiff();
        TourRecord record = TourRecord.fresh(coveringTour(diff), diff);
        String digest = AnchorIndex.of(diff).hunks().getFirst().digest();
        record = record.withProgress(record.progress("s1")
                        .withCheck(StepGrading.answerChoice(TourFixtures.predict("c1"),
                                record.progress("s1").check("c1"), 0))
                        .withDecision(StepProgress.Decision.OVERRIDDEN, Optional.of("trust me")))
                .withHunkOverride(digest, Optional.of(new HunkOverride(ReviewVerdict.Decision.APPROVED, "diff view")))
                .withReviewAnyway(true);

        JsonValue json = TourCodec.recordToJson(record);
        TourRecord back = TourCodec.recordFromJson(JsonParser.parse(JsonWriter.write(json)))
                .orElseThrow();
        assertEquals(record, back);
    }

    @Test
    void aMalformedPersistedStepIsDroppedAndTheRestSurvive() {
        UnifiedDiff diff = twoFileDiff();
        String json = JsonWriter.write(TourCodec.recordToJson(TourRecord.fresh(coveringTour(diff), diff)))
                .replaceFirst("\"kind\":\"predict\"", "\"kind\":\"bogus\"");
        TourRecord back = TourCodec.recordFromJson(JsonParser.parse(json)).orElseThrow();
        assertEquals(List.of("s2"), back.tour().steps().stream().map(TourStep::id).toList());
        assertTrue(back.progress().containsKey("s2"));
        assertTrue(!back.progress().containsKey("s1"), "progress of a dropped step is dropped too");
    }

    @Test
    void aRecordWithoutATourDecodesToNothing() {
        assertTrue(TourCodec.recordFromJson(JsonParser.parse("{\"progress\":{}}")).isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.TourCodecTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement `TourCodec`**

```java
package app.drydock.review.tour;

import app.drydock.review.ReviewVerdict;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * JSON for tours: strict decoding of what an agent sends, and lenient
 * round-tripping of what {@link TourStore} persists.
 *
 * <p>Strict: every error names the path ({@code steps[2].checks[0].answer})
 * and nothing is decoded past a length or count cap. Lenient: a persisted
 * step that no longer decodes is dropped with its progress; the rest of
 * the tour survives.</p>
 */
public final class TourCodec {

    static final int MAX_ANCHORS = 20;
    static final int MAX_NOTES = 20;
    static final int MAX_ALTERNATES = 6;
    static final int MAX_ID = 80;
    static final int MAX_KEY = 24;
    static final int MAX_PATH = 1024;

    public static final class InvalidTour extends Exception {
        public InvalidTour(String message) {
            super(message);
        }
    }

    private TourCodec() {
    }

    // ---- agent → model (strict) ----

    public static List<TourStep> stepsFromAgent(JsonValue value) throws InvalidTour {
        if (!(value instanceof JsonArray array)) {
            throw new InvalidTour("steps must be an array of step objects");
        }
        if (array.elements().size() > TourValidator.MAX_STEPS) {
            throw new InvalidTour("steps: a tour has at most " + TourValidator.MAX_STEPS + " steps");
        }
        List<TourStep> steps = new ArrayList<>();
        for (int i = 0; i < array.elements().size(); i++) {
            steps.add(stepFromJson(array.elements().get(i), "steps[" + i + "]"));
        }
        return steps;
    }

    public static TourStep stepFromJson(JsonValue value, String path) throws InvalidTour {
        JsonObject obj = object(value, path);
        String id = string(obj, "id", path, MAX_ID);
        String title = string(obj, "title", path, TourValidator.MAX_TITLE);
        String narrative = string(obj, "narrative", path, TourValidator.MAX_NARRATIVE);
        List<TourAnchor> anchors = new ArrayList<>();
        JsonArray anchorArray = array(obj, "anchors", path, MAX_ANCHORS, true);
        for (int i = 0; i < anchorArray.elements().size(); i++) {
            String at = path + ".anchors[" + i + "]";
            JsonObject anchor = object(anchorArray.elements().get(i), at);
            String start = string(anchor, "startKey", at, MAX_KEY);
            String end = optionalString(anchor, "endKey", at, MAX_KEY).orElse(start);
            anchors.add(new TourAnchor(string(anchor, "file", at, MAX_PATH), start, end));
        }
        List<ImpactNote> notes = new ArrayList<>();
        JsonArray noteArray = array(obj, "impactNotes", path, MAX_NOTES, false);
        for (int i = 0; i < noteArray.elements().size(); i++) {
            String at = path + ".impactNotes[" + i + "]";
            JsonObject note = object(noteArray.elements().get(i), at);
            notes.add(new ImpactNote(string(note, "file", at, MAX_PATH), integer(note, "line", at),
                    string(note, "text", at, TourValidator.MAX_PROMPT)));
        }
        List<TourCheck> checks = new ArrayList<>();
        JsonArray checkArray = array(obj, "checks", path, TourValidator.MAX_CHECKS_PER_STEP, true);
        for (int i = 0; i < checkArray.elements().size(); i++) {
            checks.add(checkFromJson(checkArray.elements().get(i), path + ".checks[" + i + "]", true));
        }
        return new TourStep(id, title, narrative, anchors, notes, checks);
    }

    private static TourCheck checkFromJson(JsonValue value, String path, boolean topLevel) throws InvalidTour {
        JsonObject obj = object(value, path);
        String id = string(obj, "id", path, MAX_ID);
        String rawKind = string(obj, "kind", path, 16);
        TourCheck.Kind kind = TourCheck.Kind.fromWire(rawKind)
                .orElseThrow(() -> new InvalidTour(path + ".kind: expected predict, trace or risk, got " + rawKind));
        String prompt = string(obj, "prompt", path, TourValidator.MAX_PROMPT);
        String explanation = string(obj, "explanation", path, TourValidator.MAX_PROMPT);
        List<TourCheck.Choice> choices = new ArrayList<>();
        JsonArray choiceArray = array(obj, "choices", path, TourValidator.MAX_CHOICES, false);
        for (int i = 0; i < choiceArray.elements().size(); i++) {
            String at = path + ".choices[" + i + "]";
            JsonObject choice = object(choiceArray.elements().get(i), at);
            Optional<TourCheck.Location> location = Optional.empty();
            if (choice.get("at") instanceof JsonObject where) {
                location = Optional.of(new TourCheck.Location(string(where, "file", at + ".at", MAX_PATH),
                        integer(where, "line", at + ".at")));
            }
            choices.add(new TourCheck.Choice(string(choice, "text", at, TourValidator.MAX_CHOICE), location));
        }
        OptionalInt answer = OptionalInt.empty();
        if (obj.has("answer")) {
            answer = OptionalInt.of(integer(obj, "answer", path));
        }
        List<TourCheck> alternates = new ArrayList<>();
        JsonArray alternateArray = array(obj, "alternates", path, MAX_ALTERNATES, false);
        if (!topLevel && !alternateArray.elements().isEmpty()) {
            throw new InvalidTour(path + ".alternates: an alternate cannot have alternates");
        }
        for (int i = 0; i < alternateArray.elements().size(); i++) {
            alternates.add(checkFromJson(alternateArray.elements().get(i), path + ".alternates[" + i + "]", false));
        }
        return new TourCheck(id, kind, prompt, choices, answer, explanation, alternates);
    }

    // ---- model → JSON ----

    public static JsonValue stepToJson(TourStep step) {
        List<JsonValue> anchors = new ArrayList<>();
        for (TourAnchor anchor : step.anchors()) {
            anchors.add(JsonObject.empty().put("file", new JsonString(anchor.file()))
                    .put("startKey", new JsonString(anchor.startKey()))
                    .put("endKey", new JsonString(anchor.endKey())));
        }
        List<JsonValue> notes = new ArrayList<>();
        for (ImpactNote note : step.impactNotes()) {
            notes.add(JsonObject.empty().put("file", new JsonString(note.file()))
                    .put("line", JsonNumber.of(note.line())).put("text", new JsonString(note.text())));
        }
        List<JsonValue> checks = new ArrayList<>();
        for (TourCheck check : step.checks()) {
            checks.add(checkToJson(check));
        }
        return JsonObject.empty().put("id", new JsonString(step.id()))
                .put("title", new JsonString(step.title()))
                .put("narrative", new JsonString(step.narrative()))
                .put("anchors", JsonArray.of(anchors))
                .put("impactNotes", JsonArray.of(notes))
                .put("checks", JsonArray.of(checks));
    }

    private static JsonValue checkToJson(TourCheck check) {
        List<JsonValue> choices = new ArrayList<>();
        for (TourCheck.Choice choice : check.choices()) {
            JsonObject obj = JsonObject.empty().put("text", new JsonString(choice.text()));
            choice.at().ifPresent(at -> obj.put("at", JsonObject.empty()
                    .put("file", new JsonString(at.file())).put("line", JsonNumber.of(at.line()))));
            choices.add(obj);
        }
        List<JsonValue> alternates = new ArrayList<>();
        for (TourCheck alternate : check.alternates()) {
            alternates.add(checkToJson(alternate));
        }
        JsonObject obj = JsonObject.empty().put("id", new JsonString(check.id()))
                .put("kind", new JsonString(check.kind().wireName()))
                .put("prompt", new JsonString(check.prompt()))
                .put("choices", JsonArray.of(choices))
                .put("explanation", new JsonString(check.explanation()))
                .put("alternates", JsonArray.of(alternates));
        check.answer().ifPresent(answer -> obj.put("answer", JsonNumber.of(answer)));
        return obj;
    }

    public static JsonValue recordToJson(TourRecord record) {
        List<JsonValue> steps = new ArrayList<>();
        for (TourStep step : record.tour().steps()) {
            steps.add(stepToJson(step));
        }
        JsonObject progress = JsonObject.empty();
        for (StepProgress step : record.progress().values()) {
            JsonObject checks = JsonObject.empty();
            for (CheckProgress check : step.checks().values()) {
                JsonObject obj = JsonObject.empty().put("attempt", JsonNumber.of(check.attempt()))
                        .put("status", new JsonString(check.status().name()));
                check.riskAnswer().ifPresent(text -> obj.put("riskAnswer", new JsonString(text)));
                check.agentReason().ifPresent(text -> obj.put("agentReason", new JsonString(text)));
                check.lastExplanation().ifPresent(text -> obj.put("lastExplanation", new JsonString(text)));
                checks.put(check.checkId(), obj);
            }
            JsonObject obj = JsonObject.empty()
                    .put("hunkDigests", JsonArray.of(step.hunkDigests().stream().<JsonValue>map(JsonString::new).toList()))
                    .put("decision", new JsonString(step.decision().name()))
                    .put("stale", new JsonBoolean(step.stale()))
                    .put("checks", checks);
            step.overrideReason().ifPresent(reason -> obj.put("override", new JsonString(reason)));
            progress.put(step.stepId(), obj);
        }
        JsonObject overrides = JsonObject.empty();
        record.hunkOverrides().forEach((digest, override) -> overrides.put(digest, JsonObject.empty()
                .put("decision", new JsonString(override.decision().wireName()))
                .put("reason", new JsonString(override.reason()))));
        JsonObject rows = JsonObject.empty();
        record.hunkRows().forEach((digest, keys) ->
                rows.put(digest, JsonArray.of(keys.stream().<JsonValue>map(JsonString::new).toList())));
        return JsonObject.empty()
                .put("tour", JsonObject.empty()
                        .put("scopeId", new JsonString(record.tour().scopeId()))
                        .put("fingerprint", new JsonString(record.tour().diffFingerprint()))
                        .put("steps", JsonArray.of(steps)))
                .put("progress", progress)
                .put("hunkOverrides", overrides)
                .put("hunkRows", rows)
                .put("reviewAnyway", new JsonBoolean(record.reviewAnyway()))
                .put("shelved", new JsonBoolean(record.shelved()));
    }

    // ---- JSON → record (lenient) ----

    public static Optional<TourRecord> recordFromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("tour") instanceof JsonObject tourObj)
                || !(tourObj.get("scopeId") instanceof JsonString scopeId)
                || !(tourObj.get("fingerprint") instanceof JsonString fingerprint)) {
            return Optional.empty();
        }
        List<TourStep> steps = new ArrayList<>();
        if (tourObj.get("steps") instanceof JsonArray array) {
            for (int i = 0; i < array.elements().size(); i++) {
                try {
                    steps.add(stepFromJson(array.elements().get(i), "steps[" + i + "]"));
                } catch (InvalidTour | RuntimeException e) {
                    // Dropped: TourStore re-requests a dropped step (spec §7).
                }
            }
        }
        ReviewTour tour = new ReviewTour(scopeId.value(), fingerprint.value(), steps);
        Map<String, StepProgress> progress = new LinkedHashMap<>();
        if (root.get("progress") instanceof JsonObject progressObj) {
            for (TourStep step : steps) {
                if (progressObj.get(step.id()) instanceof JsonObject stepObj) {
                    decodeProgress(step.id(), stepObj).ifPresent(p -> progress.put(step.id(), p));
                }
            }
        }
        Map<String, HunkOverride> overrides = new LinkedHashMap<>();
        if (root.get("hunkOverrides") instanceof JsonObject overrideObj) {
            overrideObj.members().forEach((digest, entry) -> {
                if (entry instanceof JsonObject obj && obj.get("decision") instanceof JsonString decision
                        && obj.get("reason") instanceof JsonString reason) {
                    ReviewVerdict.Decision.fromWire(decision.value())
                            .ifPresent(d -> overrides.put(digest, new HunkOverride(d, reason.value())));
                }
            });
        }
        Map<String, List<String>> rows = new LinkedHashMap<>();
        if (root.get("hunkRows") instanceof JsonObject rowsObj) {
            rowsObj.members().forEach((digest, entry) -> rows.put(digest, strings(entry)));
        }
        boolean reviewAnyway = root.get("reviewAnyway") instanceof JsonBoolean flag && flag.value();
        boolean shelved = root.get("shelved") instanceof JsonBoolean flag && flag.value();
        return Optional.of(new TourRecord(tour, progress, overrides, rows, reviewAnyway, shelved));
    }

    private static Optional<StepProgress> decodeProgress(String stepId, JsonObject obj) {
        StepProgress.Decision decision;
        try {
            decision = obj.get("decision") instanceof JsonString raw
                    ? StepProgress.Decision.valueOf(raw.value()) : StepProgress.Decision.NONE;
        } catch (IllegalArgumentException e) {
            decision = StepProgress.Decision.NONE;
        }
        Map<String, CheckProgress> checks = new LinkedHashMap<>();
        if (obj.get("checks") instanceof JsonObject checksObj) {
            checksObj.members().forEach((checkId, entry) -> {
                if (entry instanceof JsonObject check) {
                    decodeCheck(checkId, check).ifPresent(c -> checks.put(checkId, c));
                }
            });
        }
        Optional<String> override = obj.get("override") instanceof JsonString reason
                ? Optional.of(reason.value()) : Optional.empty();
        boolean stale = obj.get("stale") instanceof JsonBoolean flag && flag.value();
        return Optional.of(new StepProgress(stepId, strings(obj.get("hunkDigests")), checks, decision, override, stale));
    }

    private static Optional<CheckProgress> decodeCheck(String checkId, JsonObject obj) {
        try {
            CheckProgress.Status status = obj.get("status") instanceof JsonString raw
                    ? CheckProgress.Status.valueOf(raw.value()) : CheckProgress.Status.OPEN;
            int attempt = obj.get("attempt") instanceof JsonNumber number ? number.asInt() : 0;
            return Optional.of(new CheckProgress(checkId, attempt, status, text(obj, "riskAnswer"),
                    text(obj, "agentReason"), text(obj, "lastExplanation")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---- helpers ----

    private static JsonObject object(JsonValue value, String path) throws InvalidTour {
        if (value instanceof JsonObject obj) {
            return obj;
        }
        throw new InvalidTour(path + ": expected an object");
    }

    private static String string(JsonObject obj, String key, String path, int max) throws InvalidTour {
        return optionalString(obj, key, path, max)
                .orElseThrow(() -> new InvalidTour(path + "." + key + ": missing or blank"));
    }

    private static Optional<String> optionalString(JsonObject obj, String key, String path, int max)
            throws InvalidTour {
        JsonValue value = obj.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof JsonString string)) {
            throw new InvalidTour(path + "." + key + ": expected a string");
        }
        if (string.value().length() > max) {
            throw new InvalidTour(path + "." + key + ": longer than " + max + " characters");
        }
        return string.value().isBlank() ? Optional.empty() : Optional.of(string.value());
    }

    private static int integer(JsonObject obj, String key, String path) throws InvalidTour {
        if (obj.get(key) instanceof JsonNumber number) {
            try {
                return number.asInt();
            } catch (RuntimeException e) {
                throw new InvalidTour(path + "." + key + ": expected an integer");
            }
        }
        throw new InvalidTour(path + "." + key + ": expected an integer");
    }

    private static JsonArray array(JsonObject obj, String key, String path, int max, boolean required)
            throws InvalidTour {
        JsonValue value = obj.get(key);
        if (value == null) {
            if (required) {
                throw new InvalidTour(path + "." + key + ": missing");
            }
            return JsonArray.of(List.of());
        }
        if (!(value instanceof JsonArray array)) {
            throw new InvalidTour(path + "." + key + ": expected an array");
        }
        if (array.elements().size() > max) {
            throw new InvalidTour(path + "." + key + ": at most " + max + " entries");
        }
        return array;
    }

    private static List<String> strings(JsonValue value) {
        if (!(value instanceof JsonArray array)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonString string) {
                out.add(string.value());
            }
        }
        return out;
    }

    private static Optional<String> text(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString string ? Optional.of(string.value()) : Optional.empty();
    }
}
```

Note: `JsonNumber.asInt()` on a non-integral literal — check its behaviour in `JsonValue.java`; if it truncates silently, reject literals containing `.`/`e`/`E` in `integer(...)` before calling it.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.TourCodecTest" --offline`
Expected: 9 passed.

- [ ] **Step 5: Commit**

Subject: `Review tour: tours round-trip through JSON, strictly from the agent and leniently from disk`. Body: the absence (no wire or file format), the two decoding modes and why (agent errors name a path so it can fix them; one bad persisted step must not cost the rest of the tour), the caps, verification line, `Co-Authored-By` trailer.

---

### Task 5: The tour store and its wiring

**Read first:** spec §7 (persistence), AGENTS.md "One writer for persistent state" and "Lifecycle symmetry".

**Constraints:** one writer for `review-tours.json`; debounced background writes on a single-thread virtual executor; `flushPendingSaves()`; lenient load per scope entry (a corrupt file starts empty, logs WARNING, and **never touches `annotations.json`**); close is exception-isolated in `DrydockApplication.stop()` after `McpServer`; listener callbacks fire outside the monitor and each is exception-isolated.

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/TourStore.java`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (field near :160, construct near :235, close in `stop()` near :1175, pass into `MainWorkspace` and the MCP context)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (constructor parameter; listener next to :600)
- Modify: `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java` (constructor parameter; field)
- Test: `app/src/test/java/app/drydock/review/tour/TourStoreTest.java`

**Interfaces:**
- Consumes: `TourCodec.recordToJson/recordFromJson`, `JsonParser`, `JsonWriter`.
- Produces: `public final class TourStore implements AutoCloseable` with `TourStore(Path file)`, `static Path siblingOf(Path stateFile)` (→ `review-tours.json`), `synchronized Optional<TourRecord> forScope(String scopeId)`, `void put(TourRecord record)`, `Optional<TourRecord> mutate(String scopeId, UnaryOperator<TourRecord> transform)`, `void remove(String scopeId)`, `Runnable addChangeListener(Consumer<String> scopeIdListener)`, `void flushPendingSaves()`, `void close()`.

Persisted file: `{"version":1,"scopes":{"<scopeId>":<recordToJson>}}`. One record per scope; `put` replaces.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourStoreTest {

    private final UnifiedDiff diff = twoFileDiff();

    @Test
    void aPutRecordSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("review-tours.json");
        TourRecord record = TourRecord.fresh(coveringTour(diff), diff).withReviewAnyway(true);
        try (TourStore store = new TourStore(file)) {
            store.put(record);
            store.flushPendingSaves();
        }
        try (TourStore reopened = new TourStore(file)) {
            assertEquals(Optional.of(record), reopened.forScope(SCOPE));
        }
    }

    @Test
    void putReplacesTheScopesPreviousTour(@TempDir Path dir) {
        try (TourStore store = new TourStore(dir.resolve("review-tours.json"))) {
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            TourRecord second = TourRecord.fresh(coveringTour(diff), diff).withShelved(true);
            store.put(second);
            assertEquals(Optional.of(second), store.forScope(SCOPE));
        }
    }

    @Test
    void mutateAppliesAndNotifies(@TempDir Path dir) {
        try (TourStore store = new TourStore(dir.resolve("review-tours.json"))) {
            List<String> heard = new ArrayList<>();
            store.addChangeListener(heard::add);
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            store.mutate(SCOPE, record -> record.withReviewAnyway(true));
            assertTrue(store.forScope(SCOPE).orElseThrow().reviewAnyway());
            assertEquals(List.of(SCOPE, SCOPE), heard);
            assertTrue(store.mutate("nope", record -> record).isEmpty());
        }
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers(@TempDir Path dir) {
        try (TourStore store = new TourStore(dir.resolve("review-tours.json"))) {
            List<String> heard = new ArrayList<>();
            store.addChangeListener(scope -> {
                throw new IllegalStateException("boom");
            });
            store.addChangeListener(heard::add);
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            assertEquals(List.of(SCOPE), heard);
        }
    }

    @Test
    void aMalformedStepIsDroppedAndTheRestOfTheTourSurvives(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("review-tours.json");
        try (TourStore store = new TourStore(file)) {
            store.put(TourRecord.fresh(coveringTour(diff), diff));
            store.flushPendingSaves();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8).replaceFirst("\"kind\":\"predict\"", "\"kind\":7");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        try (TourStore reopened = new TourStore(file)) {
            assertEquals(List.of("s2"), reopened.forScope(SCOPE).orElseThrow().tour().steps().stream()
                    .map(TourStep::id).toList());
        }
    }

    @Test
    void aCorruptTourFileStartsEmptyWithoutTouchingOtherFiles(@TempDir Path dir) throws Exception {
        Path annotations = dir.resolve("annotations.json");
        Files.writeString(annotations, "{\"version\":5}", StandardCharsets.UTF_8);
        Path file = dir.resolve("review-tours.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        try (TourStore store = new TourStore(file)) {
            assertTrue(store.forScope(SCOPE).isEmpty());
        }
        assertEquals("{\"version\":5}", Files.readString(annotations, StandardCharsets.UTF_8));
    }

    @Test
    void siblingOfSitsNextToTheStateFile() {
        assertEquals(Path.of("/x/review-tours.json"), TourStore.siblingOf(Path.of("/x/state.json")));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.TourStoreTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement `TourStore`**

Mirror `AnnotationStore`'s persistence (`AnnotationStore.java:98-123`, `:464-548`): a `Map<String, TourRecord> tours` guarded by `this`; public mutators call a `synchronized` internal method that updates the map and calls `persistAsync()`, then call `fireChanged(scopeId)` outside the monitor.

```java
package app.drydock.review.tour;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The single writer of {@code review-tours.json}: one tour record per review
 * scope, written in the background and debounced.
 *
 * <p>A sibling of the annotation store rather than part of it, because
 * {@code AnnotationStore} discards its whole file on any decode error; a
 * tour must never be able to cost a reviewer their findings. Loading is
 * lenient per scope, and per step inside {@link TourCodec}.</p>
 */
public final class TourStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger(TourStore.class.getName());
    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final Map<String, TourRecord> tours = new LinkedHashMap<>();
    private final ExecutorService saveExecutor =
            Executors.newSingleThreadExecutor(runnable -> Thread.ofVirtual().unstarted(runnable));
    private final AtomicReference<Map<String, TourRecord>> pendingSnapshot = new AtomicReference<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public TourStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        loadFromDisk();
    }

    public static Path siblingOf(Path stateFile) {
        return stateFile.toAbsolutePath().normalize().resolveSibling("review-tours.json");
    }

    public synchronized Optional<TourRecord> forScope(String scopeId) {
        return Optional.ofNullable(tours.get(scopeId));
    }

    public void put(TourRecord record) {
        putInternal(record);
        fireChanged(record.tour().scopeId());
    }

    public Optional<TourRecord> mutate(String scopeId, UnaryOperator<TourRecord> transform) {
        Optional<TourRecord> result = mutateInternal(scopeId, transform);
        result.ifPresent(record -> fireChanged(scopeId));
        return result;
    }

    public void remove(String scopeId) {
        boolean removed;
        synchronized (this) {
            removed = tours.remove(scopeId) != null;
            if (removed) {
                persistAsync();
            }
        }
        if (removed) {
            fireChanged(scopeId);
        }
    }

    public Runnable addChangeListener(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void flushPendingSaves() {
        try {
            saveExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // saveSnapshot logs its own failures; a wedged disk must not hang shutdown.
        }
    }

    @Override
    public void close() {
        flushPendingSaves();
        saveExecutor.shutdown();
    }

    private synchronized void putInternal(TourRecord record) {
        tours.put(record.tour().scopeId(), record);
        persistAsync();
    }

    private synchronized Optional<TourRecord> mutateInternal(String scopeId, UnaryOperator<TourRecord> transform) {
        TourRecord current = tours.get(scopeId);
        if (current == null) {
            return Optional.empty();
        }
        TourRecord next = transform.apply(current);
        tours.put(scopeId, next);
        persistAsync();
        return Optional.of(next);
    }

    private void fireChanged(String scopeId) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(scopeId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Tour store listener failed for scope " + scopeId, e);
            }
        }
    }

    private void persistAsync() {
        if (pendingSnapshot.getAndSet(Map.copyOf(tours)) == null) {
            saveExecutor.execute(() -> {
                Map<String, TourRecord> latest = pendingSnapshot.getAndSet(null);
                if (latest != null) {
                    saveSnapshot(latest);
                }
            });
        }
    }

    private void saveSnapshot(Map<String, TourRecord> snapshot) {
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            JsonObject scopes = JsonObject.empty();
            snapshot.forEach((scopeId, record) -> scopes.put(scopeId, TourCodec.recordToJson(record)));
            String text = JsonWriter.write(JsonObject.empty()
                    .put("version", JsonNumber.of(SCHEMA_VERSION)).put("scopes", scopes));
            Path tempFile = Files.createTempFile(directory, file.getFileName().toString() + ".", ".tmp");
            try {
                Files.writeString(tempFile, text, StandardCharsets.UTF_8);
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save review tours to " + file, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonValue parsed = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof JsonObject root) || !(root.get("scopes") instanceof JsonObject scopes)) {
                LOG.log(Level.WARNING, "Review tours file " + file + " has no scopes; starting empty");
                return;
            }
            scopes.members().forEach((scopeId, value) -> {
                try {
                    TourCodec.recordFromJson(value).ifPresent(record -> tours.put(scopeId, record));
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Dropping malformed tour for scope " + scopeId, e);
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Review tours file " + file + " is malformed; starting empty", e);
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.TourStoreTest" --offline`
Expected: 7 passed.

- [ ] **Step 5: Wire the store into the application**

In `DrydockApplication`:
- Add field `private TourStore tourStore;` next to `annotationStore` (:160).
- After `annotationStore = new AnnotationStore(...)` (:235): `tourStore = new TourStore(TourStore.siblingOf(stateRepository.stateFile()));`
- Pass `tourStore` to `new MainWorkspace(...)` (:260) as a new parameter right after `annotationStore`, and to the MCP context construction inside `startMcpServer(stateDir)` (:1353), next to where `annotationStore` is passed.
- In `stop()`, directly after the `annotationStore` close block:

```java
if (tourStore != null) {
    closeQuietly("TourStore", tourStore::close);
}
```

In `MainWorkspace`: add a `TourStore tourStore` constructor parameter after `AnnotationStore annotationStore`, keep it in a `private final TourStore tourStore;` field, and next to `annotationStore.addChangeListener(...)` (:600) add:

```java
tourStore.addChangeListener(scopeId -> Platform.runLater(this::refreshReviewCounts));
```

In `WorkspaceMcpSessionContext`: add a `TourStore tourStore` constructor parameter and field next to `annotationStore` (:149). Grep for every `new MainWorkspace(` and `new WorkspaceMcpSessionContext(` (main and test) and pass the store; tests that construct them get `new TourStore(tempDir.resolve("review-tours.json"))`.

- [ ] **Step 6: Compile and run the touched tests**

Run: `./gradlew :app:test --tests "app.drydock.review.tour.*" --tests "app.drydock.mcp.WorkspaceMcpSessionContextTest" --offline`
Expected: BUILD SUCCESSFUL; all pass.

- [ ] **Step 7: Commit**

Subject: `Review tour: tours persist in their own file, one per scope, written in the background`. Body: why a sibling file (AnnotationStore discards its file on any decode error, so a tour must not live inside it); one writer, debounced, flushed and closed in `stop()` after `McpServer`; verification; not covered: no UI consumes it yet. `Co-Authored-By` trailer.

---

### Task 6: `review_tour` over MCP, and Run review asks for a tour

**Read first:** spec §8 (MCP table, validation list), §3 (coverage).

**Constraints:** all-or-nothing (decode and validate everything before storing); rejection message lists every error; every text field through `PromptSafety.checkInboundText`; new context methods implemented in `WorkspaceMcpSessionContext` **and** `FakeMcpSessionContext`; tool list tests and `McpServer.AGENT_WRITE_TOOLS` updated; no FQCNs (the existing `new java.util.ArrayList<>()` in `reviewFinding` is not to be copied).

**Files:**
- Modify: `app/src/main/java/app/drydock/mcp/McpSessionContext.java` (two methods)
- Modify: `app/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (descriptor, dispatch case, `reviewTour`, `review_state` tour block)
- Create: `app/src/main/java/app/drydock/mcp/TourStateJson.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpServer.java:508-510` (`AGENT_WRITE_TOOLS`)
- Modify: `app/src/main/java/app/drydock/review/ReviewInstructions.java`
- Modify: `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`
- Modify: `app/src/test/java/app/drydock/mcp/McpToolRouterReadTest.java` (:62-71 ordered names, :90-121 required args)
- Modify: `app/src/test/java/app/drydock/review/ReviewInstructionsTest.java` (`bothFormsAskForIntentsAndFindings` → `bothFormsAskForATourAndFindings`)
- Modify: `docs/ui-redesign.md:42` (tool list row)
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterTourTest.java`

**Interfaces:**
- Consumes: `TourCodec.stepsFromAgent`, `TourValidator.validate`, `TourFingerprint.of`, `TourRecord.fresh`, `TourStore.forScope/put` (via context), `context.reviewDiff(scope)`, `context.excerpt(caller, file, line, 0)`, `PromptSafety.checkInboundText(String, String)`.
- Produces:
  - `McpSessionContext`: `Optional<TourRecord> tourOf(String scopeId);` and `void putTour(TourRecord record);`
  - Router tool `review_tour` (args `scopeId`, `steps`) → `{scopeId, steps, checks}`
  - `review_state` gains a `tour` member when a tour exists: `{fingerprint, current:boolean, steps:[{id, number, title, decision, stale, checks:[{id, status, attempt}]}]}` built by `TourStateJson.of(TourRecord, String currentFingerprint)`
  - `ReviewInstructions.forScope(scopeId, supportsSubagents)` now asks for `review_finding` and `review_tour`

- [ ] **Step 1: Write the failing router tests**

```java
package app.drydock.mcp;

import app.drydock.review.tour.TourRecord;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.num;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixture's diff: src/Widget.java and src/WidgetUser.java, both added,
 * one hunk each, rows n1..n5 and n1..n6 (see McpRouterFixture.parseableDiff).
 */
class McpToolRouterTourTest extends McpRouterFixture {

    private static final String CHECK = """
            {"id":"%s","kind":"predict","prompt":"What happens?",
             "choices":[{"text":"a"},{"text":"b"}],"answer":0,"explanation":"Because.",
             "alternates":[{"id":"%s_alt","kind":"risk","prompt":"Risk?","explanation":"E."}]}""";

    private static String step(String id, String file, String start, String end, String checkId) {
        return """
                {"id":"%s","title":"T %s","narrative":"Why.",
                 "anchors":[{"file":"%s","startKey":"%s","endKey":"%s"}],
                 "checks":[%s]}""".formatted(id, id, file, start, end, CHECK.formatted(checkId, checkId));
    }

    private JsonObject tourArgs(String... steps) {
        JsonObject args = JsonObject.empty().put("scopeId", new JsonValue.JsonString(scopeId()));
        args.put("steps", JsonParser.parse("[" + String.join(",", steps) + "]"));
        return args;
    }

    private JsonObject coveringArgs() {
        return tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1"),
                step("s2", "src/WidgetUser.java", "n1", "n6", "c2"));
    }

    @Test
    void aCoveringTourIsStoredWithFreshProgress() throws Exception {
        JsonValue result = router.call(callerId(), "review_tour", coveringArgs());

        assertEquals(2, num(result, "steps"));
        assertEquals(2, num(result, "checks"));
        TourRecord stored = context.tourOf(scopeId()).orElseThrow();
        assertEquals(2, stored.tour().steps().size());
        assertEquals(1, stored.progress().get("s1").hunkDigests().size());
    }

    @Test
    void anUncoveredHunkRejectsTheWholeTourAndStoresNothing() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1"))));
        assertTrue(error.getMessage().contains("hunk h_src/WidgetUser.java_0"), error.getMessage());
        assertTrue(error.getMessage().contains("nothing stored"), error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty());
    }

    @Test
    void aMalformedStepIsRejectedNamingItsPath() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1").replace("\"answer\":0", "\"answer\":\"0\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].answer"), error.getMessage());
    }

    @Test
    void anImpactNoteOnALineThatDoesNotExistIsRejected() {
        context.excerptAnswer = Optional.empty();
        String noted = step("s1", "src/Widget.java", "n1", "n5", "c1")
                .replace("\"checks\":", "\"impactNotes\":[{\"file\":\"src/Other.java\",\"line\":4000,\"text\":\"x\"}],\"checks\":");
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(noted, step("s2", "src/WidgetUser.java", "n1", "n6", "c2"))));
        assertTrue(error.getMessage().contains("src/Other.java:4000"), error.getMessage());
    }

    @Test
    void reviewStateReportsTheTourAndWhetherItIsCurrent() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());

        JsonValue state = router.call(callerId(), "review_state", JsonPeek.args("scopeId", scopeId()));

        JsonValue tour = field(state, "tour");
        assertTrue(JsonPeek.bool(tour, "current"));
        assertEquals(2, JsonPeek.array(tour, "steps").size());
    }
}
```

(`excerptAnswer` is a new public field on `FakeMcpSessionContext` added in Step 3; when it is `null` the fake keeps its current `excerpt` behaviour.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.mcp.McpToolRouterTourTest" --offline`
Expected: compilation FAILURE (`tourOf`, `excerptAnswer` missing) or "Unknown tool: review_tour".

- [ ] **Step 3: Add the context methods**

`McpSessionContext` (next to `putIntents`, :96):

```java
/** The scope's tour record, if the agent has posted one. */
Optional<TourRecord> tourOf(String scopeId);

/** Stores (replaces) the scope's tour record and flushes it to disk. */
void putTour(TourRecord record);
```

`WorkspaceMcpSessionContext`:

```java
@Override
public Optional<TourRecord> tourOf(String scopeId) {
    return tourStore.forScope(scopeId);
}

@Override
public void putTour(TourRecord record) {
    tourStore.put(record);
    tourStore.flushPendingSaves();
}
```

`FakeMcpSessionContext`: add `public final Map<String, TourRecord> tours = new HashMap<>();`, implement `tourOf` → `Optional.ofNullable(tours.get(scopeId))`, `putTour` → `tours.put(record.tour().scopeId(), record)`; add `public Optional<String> excerptAnswer;` (default `null`) and make `excerpt(...)` return `excerptAnswer` when it is non-null.

- [ ] **Step 4: Add the tool to the router**

Descriptor, inserted after `review_intents` in `toolDescriptors()`:

```java
descriptor("review_tour",
        "Posts the guided tour of a scope: ordered steps a human walks in full files. Validated "
                + "against the review diff and stored only if valid; on rejection nothing is stored "
                + "and every problem is listed. Every changed row must lie in some step's anchor; "
                + "each step needs a narrative and at least one check, each check at least one "
                + "alternate. Anchor keys are line keys from review_scope (n<newLine> or "
                + "o<oldLine>); answer is a 0-based index into choices.",
        JsonObject.empty()
                .put("scopeId", schemaString("Review scope handle."))
                .put("steps", schemaString("Array of {id, title, narrative (<=1000 chars), "
                        + "anchors[{file, startKey, endKey?}], impactNotes?[{file, line, text}], "
                        + "checks[{id, kind: predict|trace|risk, prompt, choices?[{text, at?{file, "
                        + "line}}] (2-4, not for risk), answer? (0-based, not for risk), explanation, "
                        + "alternates[{...same, no alternates}]}]}; at most 40 steps, 6 checks each."))),
        "scopeId", "steps"),
```

Dispatch: `case "review_tour" -> reviewTour(caller, arguments);`

```java
private JsonValue reviewTour(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
    requireLiveSession(caller);
    JsonObject args = asObject(arguments);
    ReviewScope scope = requireScope(caller, args);
    UnifiedDiff diff = context.reviewDiff(scope);
    List<TourStep> steps;
    try {
        steps = TourCodec.stepsFromAgent(args.get("steps"));
    } catch (TourCodec.InvalidTour e) {
        throw new McpToolException("review_tour rejected, nothing stored: " + e.getMessage());
    }
    checkTourText(steps);
    ReviewTour tour = new ReviewTour(scope.id(), TourFingerprint.of(diff), steps);
    List<String> errors = new ArrayList<>(TourValidator.validate(tour, diff));
    errors.addAll(impactNoteErrors(caller, steps));
    if (!errors.isEmpty()) {
        throw new McpToolException("review_tour rejected, nothing stored:\n- " + String.join("\n- ", errors));
    }
    context.putTour(TourRecord.fresh(tour, diff));
    int checks = steps.stream().mapToInt(step -> step.checks().size()).sum();
    return JsonObject.empty()
            .put("scopeId", new JsonString(scope.id()))
            .put("steps", JsonNumber.of(steps.size()))
            .put("checks", JsonNumber.of(checks));
}

private static void checkTourText(List<TourStep> steps) throws McpToolException {
    for (TourStep step : steps) {
        PromptSafety.checkInboundText(step.title(), "tour.step.title");
        PromptSafety.checkInboundText(step.narrative(), "tour.step.narrative");
        for (ImpactNote note : step.impactNotes()) {
            PromptSafety.checkInboundText(note.text(), "tour.impactNote.text");
        }
        for (TourCheck check : step.checks()) {
            for (int attempt = 0; attempt < check.versions(); attempt++) {
                TourCheck version = check.version(attempt);
                PromptSafety.checkInboundText(version.prompt(), "tour.check.prompt");
                PromptSafety.checkInboundText(version.explanation(), "tour.check.explanation");
                for (TourCheck.Choice choice : version.choices()) {
                    PromptSafety.checkInboundText(choice.text(), "tour.check.choice");
                }
            }
        }
    }
}

/** A claimed impact note must point at a line that exists in the checkout. */
private List<String> impactNoteErrors(ManagedSessionId caller, List<TourStep> steps) {
    List<String> errors = new ArrayList<>();
    for (TourStep step : steps) {
        for (ImpactNote note : step.impactNotes()) {
            if (note.line() >= 1 && context.excerpt(caller, note.file(), note.line(), 0).isEmpty()) {
                errors.add("step " + step.id() + ": impact note points at " + note.file() + ":" + note.line()
                        + ", which does not exist in the checkout");
            }
        }
    }
    return errors;
}
```

Before relying on `excerpt` returning empty for an out-of-range line, read `WorkspaceMcpSessionContext.excerpt` and confirm; if it clamps instead, add a strict `boolean lineExists(ManagedSessionId, String file, int line)` to the context (implemented with a bounded read off the caller's worktree) and use that.

`review_state`: after the findings are added, append the tour when present (omit the key when there is none — "absent means unknown"):

```java
Optional<TourRecord> tour = context.tourOf(scope.id());
if (tour.isPresent()) {
    try {
        String current = TourFingerprint.of(context.reviewDiff(scope));
        result.put("tour", TourStateJson.of(tour.get(), current));
    } catch (McpToolException e) {
        LOG.log(Level.WARNING, "review_state: could not diff scope " + scope.id() + " for its tour", e);
    }
}
```

`TourStateJson.java` (package-private, `app.drydock.mcp`):

```java
package app.drydock.mcp;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.List;

/** The agent's view of a tour's progress, for review_state. */
final class TourStateJson {

    private TourStateJson() {
    }

    static JsonValue of(TourRecord record, String currentFingerprint) {
        List<JsonValue> steps = new ArrayList<>();
        int number = 1;
        for (TourStep step : record.tour().steps()) {
            StepProgress progress = record.progress(step.id());
            List<JsonValue> checks = new ArrayList<>();
            for (TourCheck check : step.checks()) {
                CheckProgress p = progress.check(check.id());
                checks.add(JsonObject.empty()
                        .put("id", new JsonString(check.id()))
                        .put("status", new JsonString(p.status().name()))
                        .put("attempt", JsonNumber.of(p.attempt())));
            }
            steps.add(JsonObject.empty()
                    .put("id", new JsonString(step.id()))
                    .put("number", JsonNumber.of(number++))
                    .put("title", new JsonString(step.title()))
                    .put("decision", new JsonString(progress.decision().name()))
                    .put("stale", new JsonBoolean(progress.stale()))
                    .put("checks", JsonArray.of(checks)));
        }
        return JsonObject.empty()
                .put("fingerprint", new JsonString(record.tour().diffFingerprint()))
                .put("current", new JsonBoolean(record.tour().diffFingerprint().equals(currentFingerprint)))
                .put("steps", JsonArray.of(steps));
    }
}
```

- [ ] **Step 5: Update the pinned tool lists and the activity direction**

- `McpToolRouterReadTest.toolDescriptorsCoverEverySupportedTool`: insert `"review_tour"` after `"review_intents"` in the expected ordered list.
- `McpToolRouterReadTest.toolDescriptorsDeclareTheirRequiredArguments`: add `Map.entry("review_tour", Set.of("scopeId", "steps"))` (match the map's existing value type).
- `McpServer.AGENT_WRITE_TOOLS`: add `"review_tour"`.

- [ ] **Step 6: Ask for a tour in Run review**

Change `ReviewInstructions.forScope` so `work` reads:

```java
String work = "read review_scope for handle " + scopeId + " with include=sections"
        + ", call review_state first so already-settled findings are not re-flagged, "
        + "then post review_finding and review_tour against that handle; review_tour is validated "
        + "(every changed row in a step, each step at least one check with an alternate) and lists "
        + "every problem if it is rejected, so fix them and post it again";
```

Rename `ReviewInstructionsTest.bothFormsAskForIntentsAndFindings` to `bothFormsAskForATourAndFindings` and assert `contains("review_tour")` and `contains("review_finding")` for both forms. This changes an existing test's assertion to match the behaviour the approved spec asks for (§8); it does not delete or disable a test. Update `docs/ui-redesign.md:42`'s tool row to include `review_tour`.

- [ ] **Step 7: Run the MCP and instructions tests**

Run: `./gradlew :app:test --tests "app.drydock.mcp.*" --tests "app.drydock.review.ReviewInstructions*" --offline`
Expected: all pass, including the 5 new `McpToolRouterTourTest` tests.

- [ ] **Step 8: Commit**

Subject: `Review tour: the agent posts a tour over MCP, validated against the diff and stored only whole`. Body: the absence; the tool, all-or-nothing validation listing every error, the impact-note existence check, the `review_state` tour block, Run review now asking for a tour instead of intents, the renamed instructions test and why; verification; not covered: no UI yet. `Co-Authored-By` trailer.

---

## Phase B — Whole files and tour mode (ends in a usable vertical slice)

After Task 10 the reviewer can press Run review, receive a tour, walk its steps in whole files, answer PREDICT/TRACE checks, and have hunk verdicts derived from passed steps. RISK grading, triage, navigation and impact follow.

### Task 7: The diff column can show whole files

**Read first:** spec §5 "Whole files in the diff column"; recon note: `submitReview` builds `SubmitPlan.DiffIndex` from `diffColumn.displayedDiff()` (SessionReviewView:2583/2635) — that must keep returning the **review** diff.

**Constraints:** both fetches off the FX thread (`DiffService.diff(...)` already returns a future); results applied in `Platform.runLater` behind a request token; failure of the whole-file fetch falls back to the review diff and says "whole file unavailable"; `displayedDiff()` keeps its meaning (the filtered review diff) so submit, symbol index and links are untouched; never cell-width feedback loops (keep the `viewportWidth` binding).

**Files:**
- Modify: `app/src/main/java/app/drydock/git/DiffService.java` (constant)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffRows.java` (`Options` gains `expandRunsByDefault`; budget folding)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (whole-file state, fetch, `renderedDiff()`, `setWholeFiles`, `toggleContext`, diag hook)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewDiffRowsWholeFileTest.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewDiffColumnWholeFileTest.java`

**Interfaces:**
- Produces:
  - `DiffService.WHOLE_FILE_CONTEXT_LINES = 100_000`
  - `ReviewDiffRows.Options(boolean showContext, Set<RunKey> expandedRuns, int maxRows, HunkFilter filter, Map<String, List<ReadingPath.Link>> linksByHunk, boolean expandRunsByDefault)` — the existing constructors delegate with `false`
  - `static Set<ReviewDiffRow.RunKey> ReviewDiffRows.budgetFolds(UnifiedDiff diff, Options options)` (package-private)
  - `ReviewDiffColumn`: `void setWholeFiles(boolean on)`, `boolean wholeFiles()`, `UnifiedDiff renderedDiff()`, `boolean wholeFileUnavailable()`, `void diagShowWholeFileDiff(UnifiedDiff diff)`

Folding rule in whole-file mode: every run of unchanged lines renders expanded unless (a) the user collapsed all with `c`, or (b) the file set would exceed `maxRows`, in which case the longest runs fold first (longest runs are the ones farthest from any change) until the total fits; a run the user expanded explicitly stays expanded.

- [ ] **Step 1: Write the failing row-builder tests**

```java
package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewDiffRowsWholeFileTest {

    /** One file, one hunk: 30 context rows, one added row, 10 context rows. */
    private static UnifiedDiff wholeFile() {
        List<Line> lines = new ArrayList<>();
        int n = 1;
        for (int i = 0; i < 30; i++, n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), "c" + n));
        }
        lines.add(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n++), "added"));
        for (int i = 0; i < 10; i++, n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n - 1), OptionalInt.of(n), "c" + n));
        }
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,40 +1,41 @@", lines)))));
    }

    private static long collapsedRuns(List<ReviewDiffRow> rows) {
        return rows.stream().filter(row -> row instanceof ReviewDiffRow.CollapsedRun).count();
    }

    @Test
    void wholeFileModeExpandsEveryRunByDefault() {
        ReviewDiffRows.Options options = new ReviewDiffRows.Options(true, Set.of(), 4000,
                ReviewDiffRows.HunkFilter.ALL, Map.of(), true);
        assertEquals(0, collapsedRuns(ReviewDiffRows.build(wholeFile(), options)));
    }

    @Test
    void reviewModeStillFoldsLongRuns() {
        assertTrue(collapsedRuns(ReviewDiffRows.build(wholeFile(), ReviewDiffRows.Options.defaults(4000))) > 0);
    }

    @Test
    void overTheRowBudgetTheLongestRunFoldsFirst() {
        ReviewDiffRows.Options tight = new ReviewDiffRows.Options(true, Set.of(), 25,
                ReviewDiffRows.HunkFilter.ALL, Map.of(), true);
        Set<ReviewDiffRow.RunKey> folds = ReviewDiffRows.budgetFolds(wholeFile(), tight);
        assertEquals(Set.of(new ReviewDiffRow.RunKey("src/A.java", 0, 0)), folds, "the 30-row run, not the 10-row one");
        List<ReviewDiffRow> rows = ReviewDiffRows.build(wholeFile(), tight);
        assertTrue(rows.stream().noneMatch(row -> row instanceof ReviewDiffRow.Truncation));
    }

    @Test
    void aRunTheUserExpandedIsNeverBudgetFolded() {
        ReviewDiffRow.RunKey big = new ReviewDiffRow.RunKey("src/A.java", 0, 0);
        ReviewDiffRows.Options tight = new ReviewDiffRows.Options(true, Set.of(big), 25,
                ReviewDiffRows.HunkFilter.ALL, Map.of(), true);
        assertTrue(!ReviewDiffRows.budgetFolds(wholeFile(), tight).contains(big));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewDiffRowsWholeFileTest" --offline`
Expected: compilation FAILURE (6-arg `Options`, `budgetFolds`).

- [ ] **Step 3: Implement the row-builder change**

In `ReviewDiffRows`:
- Add `boolean expandRunsByDefault` as the last `Options` component; keep the compact constructor's normalisation; make the three existing constructors and `defaults(int)` delegate with `false`.
- Add:

```java
/**
 * In whole-file mode, the runs to fold so the rendered rows fit
 * {@code options.maxRows()}: longest first, because the longest unchanged
 * runs are the ones farthest from any change. Runs the user expanded are
 * never folded. Empty outside whole-file mode.
 */
static Set<ReviewDiffRow.RunKey> budgetFolds(UnifiedDiff diff, Options options) {
    if (!options.expandRunsByDefault()) {
        return Set.of();
    }
    record Run(ReviewDiffRow.RunKey key, int length) { }
    List<Run> runs = new ArrayList<>();
    int total = 0;
    for (UnifiedDiff.FileDiff file : diff.files()) {
        int hunkIndex = 0;
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            if (options.filter().includes(file.path(), hunkIndex)) {
                total += 1 + hunk.lines().size();
                int runIndex = 0;
                int i = 0;
                List<UnifiedDiff.Line> lines = hunk.lines();
                while (i < lines.size()) {
                    if (lines.get(i).kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                        i++;
                        continue;
                    }
                    int end = i;
                    while (end < lines.size() && lines.get(end).kind() == UnifiedDiff.Line.Kind.CONTEXT) {
                        end++;
                    }
                    ReviewDiffRow.RunKey key = new ReviewDiffRow.RunKey(file.path(), hunkIndex, runIndex++);
                    if (end - i > COLLAPSE_THRESHOLD && !options.expandedRuns().contains(key)) {
                        runs.add(new Run(key, end - i));
                    }
                    i = end;
                }
            }
            hunkIndex++;
        }
    }
    runs.sort(Comparator.comparingInt(Run::length).reversed());
    Set<ReviewDiffRow.RunKey> folds = new HashSet<>();
    for (Run run : runs) {
        if (total <= options.maxRows()) {
            break;
        }
        folds.add(run.key());
        total -= run.length() - 1;
    }
    return folds;
}
```

Run indices must match `buildBody`'s numbering exactly (it numbers context runs per hunk from 0, see `ReviewDiffRows.java:135-170`); if `buildBody` numbers differently, change this loop, not `buildBody`.

- In `build(...)`, compute `Set<RunKey> folds = budgetFolds(diff, options)` once, and in `buildBody` replace the fold condition with:

```java
boolean fold = runLength > COLLAPSE_THRESHOLD && (options.expandRunsByDefault()
        ? folds.contains(key)
        : !options.expandedRuns().contains(key));
```

- [ ] **Step 4: Run the row tests and the existing row tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewDiffRows*" --offline`
Expected: all pass (existing `ReviewDiffRowsTest` unchanged).

- [ ] **Step 5: Write the failing column test**

Mirror how `ReviewDiffColumnTest` constructs and shows a column (read it first; it is in the same package). The test:

```java
package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReviewDiffColumnWholeFileTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private ReviewDiffColumn column;

    private static UnifiedDiff diff(int contextRows) {
        List<Line> lines = new ArrayList<>();
        for (int n = 1; n <= contextRows; n++) {
            lines.add(new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), "c" + n));
        }
        lines.add(new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(contextRows + 1), "added"));
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)))));
    }

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        stage.setScene(new Scene(column, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void wholeFileModeRendersTheDisplayDiffButKeepsTheReviewDiffForEverythingElse() {
        UnifiedDiff review = diff(3);
        UnifiedDiff whole = diff(40);
        interact(() -> {
            column.showDiff(null, review);
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(whole);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertSame(whole, ReviewDiagFxThread.call(column::renderedDiff));
        assertEquals(review.files(), ReviewDiagFxThread.call(() -> column.displayedDiff().files()));
        assertEquals(42, ReviewDiagFxThread.call(() -> column.diagRows().size()), "header + 41 rows, nothing folded");
    }

    @Test
    void cFoldsLongRunsAgainInWholeFileMode() {
        interact(() -> {
            column.showDiff(null, diff(3));
            column.setWholeFiles(true);
            column.diagShowWholeFileDiff(diff(40));
            column.toggleContext();
        });
        WaitForAsyncUtils.waitForFxEvents();
        long folded = ReviewDiagFxThread.call(() -> column.diagRows().stream()
                .filter(row -> row instanceof ReviewDiffRow.CollapsedRun).count());
        assertEquals(1, folded);
    }
}
```

(If `ReviewDiffColumnTest` uses a helper such as `TestStages.show`, use it too. Adjust the row count assertion if the column adds a card-edge or message row; the point is "no `CollapsedRun`".)

- [ ] **Step 6: Implement whole-file mode in the column**

In `ReviewDiffColumn`:
- Fields: `private boolean wholeFiles;`, `private UnifiedDiff wholeFileDiff;` (filtered like `displayedDiff`), `private boolean wholeFileUnavailable;`, `private long wholeRequestToken;`, `private boolean foldAll;`.
- `UnifiedDiff renderedDiff()` returns `wholeFiles && wholeFileDiff != null ? wholeFileDiff : displayedDiff`.
- `rebuild()` and `expandRun(...)` build rows from `renderedDiff()` instead of `displayedDiff`.
- `buildOptions()` passes `expandRunsByDefault = wholeFiles && wholeFileDiff != null && !foldAll`.
- `toggleContext()`: `if (wholeFiles) { foldAll = !foldAll; } else { showContext = !showContext; } rebuild();`
- `setScope(...)` and `showDiff(...)` reset `wholeFileDiff = null; wholeFileUnavailable = false; wholeRequestToken++`.
- At the end of `publishDisplayed(scopeId)`: `if (wholeFiles) { fetchWholeFiles(); }`.
- `setWholeFiles(boolean on)`: store; if on and `wholeFileDiff == null` → `fetchWholeFiles()`; `rebuild()`; `updateSummary()`.

```java
private void fetchWholeFiles() {
    ReviewScope requested = scope;
    if (requested == null || !requested.diffable()) {
        return; // diag/test path: diagShowWholeFileDiff supplies it
    }
    long token = ++wholeRequestToken;
    diffService.diff(requested.diffRoot(), requested.diffScope(), requested.base(),
                    DiffService.WHOLE_FILE_CONTEXT_LINES)
            .whenComplete((result, failure) -> Platform.runLater(() -> {
                if (token != wholeRequestToken || !wholeFiles) {
                    return;
                }
                if (failure != null) {
                    LOG.log(Level.WARNING, "Whole-file diff failed for scope " + requested.id() + ": "
                            + UiErrors.unwrap(failure).getMessage());
                    wholeFileUnavailable = true;
                    wholeFileDiff = null;
                } else {
                    wholeFileUnavailable = false;
                    wholeFileDiff = withoutHiddenUntracked(result);
                }
                rebuild();
                updateSummary();
            }));
}

void diagShowWholeFileDiff(UnifiedDiff diff) {
    wholeFileDiff = diff;
    wholeFileUnavailable = false;
    rebuild();
}
```

`withoutHiddenUntracked` is whatever `publishDisplayed` already does to drop untracked files when the untracked toggle is off — extract that filter into a private method and use it for both diffs. `updateSummary()` appends `" · whole file unavailable"` when `wholeFileUnavailable`, and `" · whole files"` when whole files are shown. Use the logger the class already declares (add `System.getLogger` if it has none).

Add `DiffService.WHOLE_FILE_CONTEXT_LINES`:

```java
/**
 * Context for Review's whole-file display diff: large enough that every
 * line of any file a reviewer reads is in the diff. Display only -- hunk
 * digests, anchors and verdicts always come from {@link #REVIEW_CONTEXT_LINES}.
 */
public static final int WHOLE_FILE_CONTEXT_LINES = 100_000;
```

- [ ] **Step 7: Run the column tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewDiffColumn*" --tests "app.drydock.ui.review.ReviewDiffRows*" --offline`
Expected: all pass.

- [ ] **Step 8: Commit**

Subject: `Review: the diff column can show whole files while approvals stay on the review diff`. Body: the absence (hunks with 12 lines of context only); the display-only unlimited-context diff, default-expanded runs with longest-first folding over the 4000-row budget, `c` folding again, fallback message; why `displayedDiff()` keeps meaning the review diff (submit positions and hunk digests depend on the 12-line window); verification; not covered: no toggle is wired to a key yet (Task 10). `Co-Authored-By` trailer.

---

### Task 8: Step marks and the PREDICT band in the diff column

**Read first:** spec §5 (current step strong highlight, other steps faint with "step N", PREDICT hides added rows).

**Constraints:** re-render via the existing `renderGeneration++` path (`refreshPins()` pattern), never `rows.setAll` (that scrolls); colours go into `app.css` next to the existing `row-add`/`row-del` rules, with a dark-theme override in `theme-dark.css` if those rules have one.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/review/StepMark.java`
- Create: `app/src/main/java/app/drydock/ui/review/TourMarks.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (`setStepMarkSource`, `refreshMarks`, `buildLine`)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (and `theme-dark.css` if needed)
- Test: `app/src/test/java/app/drydock/ui/review/TourMarksTest.java`

**Interfaces:**
- Consumes: `TourRecord`, `AnchorIndex`, `CheckProgress`, `TourCheck`.
- Produces:
  - `record StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden)`, `enum Strength { CURRENT, OTHER }`
  - `@FunctionalInterface interface StepMarkSource { Optional<StepMark> markAt(String file, String lineKey); }` (nested in `ReviewDiffColumn`)
  - `ReviewDiffColumn.setStepMarkSource(StepMarkSource)`, `ReviewDiffColumn.refreshMarks()`
  - `final class TourMarks implements ReviewDiffColumn.StepMarkSource` with `static TourMarks of(TourRecord record, UnifiedDiff renderedDiff, String currentStepId)` and `static TourMarks none()`

Marking rules: a row inside an anchor of the current step is CURRENT; otherwise a **changed** row inside another step's anchor is OTHER with that step's number (unchanged rows are not marked as other steps', or every file would be wall-to-wall tinted). `tagged` is true on the first marked row of each contiguous run for a step, so "step N" appears once per run. `hidden` is true for ADD rows inside the current step when the step has a PREDICT check whose progress is `OPEN` (any attempt) and no earlier check on the step is unsettled.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourMarksTest {

    private static Line ctx(int n, String text) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), text);
    }

    private static Line add(int n, String text) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n), text);
    }

    /** Rows: n1 ctx, n2 add (step s1), n3 ctx, n4 add (step s2), n5 ctx. */
    private static final UnifiedDiff DIFF = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M",
            2, 0, false, false, List.of(new UnifiedDiff.Hunk("@@",
            List.of(ctx(1, "a"), add(2, "b"), ctx(3, "c"), add(4, "d"), ctx(5, "e")))))));

    private static TourCheck predict(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.PREDICT, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of());
        return new TourCheck(id, TourCheck.Kind.PREDICT, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of(alternate));
    }

    private static TourRecord record() {
        ReviewTour tour = new ReviewTour("rs", TourFingerprint.of(DIFF), List.of(
                new TourStep("s1", "One", "n", List.of(new TourAnchor("src/A.java", "n1", "n3")), List.of(),
                        List.of(predict("c1"))),
                new TourStep("s2", "Two", "n", List.of(new TourAnchor("src/A.java", "n4", "n4")), List.of(),
                        List.of(predict("c2")))));
        return TourRecord.fresh(tour, DIFF);
    }

    @Test
    void currentStepRowsAreCurrentAndOtherStepsChangedRowsCarryTheirNumber() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertEquals(StepMark.Strength.CURRENT, marks.markAt("src/A.java", "n1").orElseThrow().strength());
        StepMark other = marks.markAt("src/A.java", "n4").orElseThrow();
        assertEquals(StepMark.Strength.OTHER, other.strength());
        assertEquals(2, other.stepNumber());
        assertTrue(other.tagged());
        assertTrue(marks.markAt("src/A.java", "n5").isEmpty(), "unchanged and in no current anchor");
    }

    @Test
    void anUnansweredPredictHidesTheCurrentStepsAddedRowsOnly() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertTrue(marks.markAt("src/A.java", "n2").orElseThrow().hidden());
        assertFalse(marks.markAt("src/A.java", "n1").orElseThrow().hidden(), "context stays readable");
    }

    @Test
    void answeringThePredictRevealsTheRows() {
        TourRecord record = record();
        record = record.withProgress(record.progress("s1").withCheck(
                StepGrading.answerChoice(predict("c1"), record.progress("s1").check("c1"), 0)));
        assertFalse(TourMarks.of(record, DIFF, "s1").markAt("src/A.java", "n2").orElseThrow().hidden());
    }

    @Test
    void onlyTheFirstRowOfARunIsTagged() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertTrue(marks.markAt("src/A.java", "n1").orElseThrow().tagged());
        assertFalse(marks.markAt("src/A.java", "n2").orElseThrow().tagged());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.TourMarksTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement `StepMark` and `TourMarks`**

```java
package app.drydock.ui.review;

/** How the diff column paints one row while a tour is shown. */
record StepMark(Strength strength, int stepNumber, boolean tagged, boolean hidden) {

    enum Strength { CURRENT, OTHER }
}
```

```java
package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The step marks for one rendered diff: which rows belong to the current
 * step, which changed rows belong to which other step, and which added rows
 * an unanswered PREDICT check hides. Computed once per render on the FX
 * thread; a tour is at most 40 steps over one diff, so this is cheap.
 */
final class TourMarks implements ReviewDiffColumn.StepMarkSource {

    private static final TourMarks NONE = new TourMarks(Map.of());

    private final Map<String, StepMark> byRow;

    private TourMarks(Map<String, StepMark> byRow) {
        this.byRow = byRow;
    }

    static TourMarks none() {
        return NONE;
    }

    static TourMarks of(TourRecord record, UnifiedDiff renderedDiff, String currentStepId) {
        AnchorIndex index = AnchorIndex.of(renderedDiff);
        Map<String, StepMark> marks = new HashMap<>();
        Optional<TourStep> current = record.tour().step(currentStepId);
        boolean hideAdded = current.map(step -> predictPending(step, record.progress(step.id()))).orElse(false);
        for (UnifiedDiff.FileDiff file : renderedDiff.files()) {
            String previousOwner = null;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    String key = line.lineKey();
                    boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                    StepMark mark = null;
                    String owner = null;
                    if (current.isPresent() && inStep(index, current.get(), file.path(), key)) {
                        owner = current.get().id();
                        boolean hidden = hideAdded && line.kind() == UnifiedDiff.Line.Kind.ADD;
                        mark = new StepMark(StepMark.Strength.CURRENT, record.tour().number(owner),
                                !owner.equals(previousOwner), hidden);
                    } else if (changed) {
                        for (TourStep step : record.tour().steps()) {
                            if (!step.id().equals(currentStepId) && inStep(index, step, file.path(), key)) {
                                owner = step.id();
                                mark = new StepMark(StepMark.Strength.OTHER, record.tour().number(owner),
                                        !owner.equals(previousOwner), false);
                                break;
                            }
                        }
                    }
                    if (mark != null) {
                        marks.put(file.path() + " " + key, mark);
                    }
                    previousOwner = owner;
                }
            }
        }
        return new TourMarks(marks);
    }

    @Override
    public Optional<StepMark> markAt(String file, String lineKey) {
        return Optional.ofNullable(byRow.get(file + " " + lineKey));
    }

    private static boolean inStep(AnchorIndex index, TourStep step, String file, String key) {
        for (TourAnchor anchor : step.anchors()) {
            if (index.contains(anchor, file, key)) {
                return true;
            }
        }
        return false;
    }

    /** The first unsettled check of the step is a PREDICT still open. */
    private static boolean predictPending(TourStep step, StepProgress progress) {
        for (TourCheck check : step.checks()) {
            CheckProgress p = progress.check(check.id());
            if (p.settled()) {
                continue;
            }
            return check.version(p.attempt()).kind() == TourCheck.Kind.PREDICT
                    && p.status() == CheckProgress.Status.OPEN;
        }
        return false;
    }
}
```

- [ ] **Step 4: Paint marks in the column**

In `ReviewDiffColumn`:

```java
@FunctionalInterface
interface StepMarkSource {
    Optional<StepMark> markAt(String file, String lineKey);
}

private StepMarkSource stepMarks = (file, key) -> Optional.empty();

void setStepMarkSource(StepMarkSource source) {
    stepMarks = source == null ? (file, key) -> Optional.empty() : source;
    refreshMarks();
}

void refreshMarks() {
    renderGeneration++;
    refreshRender();
}
```

In `buildLine(ReviewDiffRow.Line row)`, after the row HBox and its style classes are created:

```java
Optional<StepMark> mark = stepMarks.markAt(row.file(), row.lineKey());
if (mark.isPresent()) {
    StepMark m = mark.get();
    rowBox.getStyleClass().add(m.strength() == StepMark.Strength.CURRENT ? "tour-step-current" : "tour-step-other");
    if (m.strength() == StepMark.Strength.OTHER && m.tagged()) {
        Label tag = new Label("step " + m.stepNumber());
        tag.getStyleClass().add("tour-step-tag");
        rowBox.getChildren().add(tag);
    }
    if (m.hidden()) {
        Label band = new Label(m.tagged() ? "hidden until you answer" : "");
        band.getStyleClass().add("tour-predict-band");
        band.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(band, Priority.ALWAYS);
        rowBox.getChildren().set(rowBox.getChildren().indexOf(source), band);
    }
}
```

(`rowBox` and `source` are the local names for the row container and the highlighted `TextFlow` in `buildLine`; use whatever names it already has. The band replaces only the source text, so the gutters keep their line numbers.) For `hidden`, the `tagged` flag means "first hidden row" because hidden rows are always CURRENT and contiguous.

CSS in `app.css`, next to `.row-add`:

```css
.review-code-row.tour-step-current { -fx-background-color: rgba(120, 160, 255, 0.16); -fx-border-color: transparent transparent transparent rgba(120, 160, 255, 0.9); -fx-border-width: 0 0 0 3; }
.review-code-row.tour-step-other { -fx-opacity: 0.72; }
.tour-step-tag { -fx-font-size: 0.85em; -fx-text-fill: derive(-fx-text-base-color, 35%); -fx-padding: 0 6 0 6; }
.tour-predict-band { -fx-background-color: repeating-linear-gradient(from 0px 0px to 8px 8px, rgba(127,127,127,0.18) 0%, rgba(127,127,127,0.18) 50%, transparent 50%, transparent 100%); -fx-text-fill: derive(-fx-text-base-color, 30%); -fx-font-style: italic; -fx-padding: 0 8 0 8; }
```

If `.row-add` uses theme variables (e.g. `-drydock-…`), use the same variables for the tint instead of the literal rgba values.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.TourMarksTest" --tests "app.drydock.ui.review.ReviewDiffColumn*" --offline`
Expected: all pass.

- [ ] **Step 6: Commit**

Subject: `Review tour: the diff column paints the current step and hides what a prediction must not see`. Body as per the commit rules; verification; not covered: visual check happens in Task 22. `Co-Authored-By` trailer.

---

### Task 9: The tour outline and the step panel

**Read first:** spec §5 (layout, checks in the panel).

**Constraints:** primary actions are real `Button`s; outline widths equal `ReviewIntentRail.EXPANDED_WIDTH/NARROW_WIDTH/COLLAPSED_WIDTH` and panel widths equal `ReviewFindingsMargin.EXPANDED_WIDTH/NARROW_WIDTH/COLLAPSED_WIDTH`, so `RailLayout` thresholds do not move; no indefinite animations; components are view-only (they call a host, they never touch stores).

**Files:**
- Create: `app/src/main/java/app/drydock/ui/review/TourOutline.java`
- Create: `app/src/main/java/app/drydock/ui/review/StepPanel.java`
- Create: `app/src/main/java/app/drydock/ui/review/StepView.java`
- Test: `app/src/test/java/app/drydock/ui/review/TourOutlineTest.java`
- Test: `app/src/test/java/app/drydock/ui/review/StepPanelTest.java`

**Interfaces:**
- Consumes: Task 1/3 types.
- Produces:
  - `enum TourOutline.RowState { NOT_STARTED, IN_PROGRESS, CHECKING, PASSED, CHANGES, OVERRIDDEN, STALE }`, `record TourOutline.Row(String stepId, int number, String title, RowState state)`
  - `TourOutline`: `void setRows(List<Row> rows, String currentStepId)`, `void setFooter(int filesWithoutLineChanges, boolean acknowledged)`, `void showMessage(String message, Optional<String> actionLabel, Runnable action)`, `void showFailure(String message, Runnable retry, Runnable openDiffReview)` (two buttons: "Retry", "Open diff review"), `void setOnSelected(Consumer<String> stepId)`, `void setOnAcknowledge(Runnable)`, `void setSearchContent(Node)`, `void showSearchTab(boolean)`, `boolean collapsed()`, `void setCollapsed(boolean)`, `void setNarrow(boolean)`
  - `static TourOutline.RowState TourOutline.stateOf(StepProgress progress)`
  - `record StepView(TourStep step, int number, int total, StepProgress progress)`
  - `StepPanel.Host` with `void answerChoice(String checkId, int choiceIndex)`, `void submitRisk(String checkId, String answer)`, `void override(String reason)`, `void askAgent(String checkId)`, `void goToAnchor(int anchorIndex)`, `void retryRisk(String checkId)`
  - `StepPanel`: `StepPanel(Host host)`, `void show(StepView view)`, `void showMessage(String message)`, `void showTransient(String message)` (a one-line notice above the content, cleared by the next `show`), `boolean answerByKey(int oneBased)` (returns false if no choice check is active), `void focusUnmet(StepGate.Unmet unmet)`, `boolean collapsed()`, `void setCollapsed(boolean)`, `void setNarrow(boolean)`, `VBox extraSections()` (a container Tasks 12/19 fill)

Panel content, top to bottom: header ("Step 3 of 9 · <title>"); narrative (wrapping `Label`); anchor chips (one `Button` per anchor, label `file:startLine`, action `host.goToAnchor(i)`); the active check: the first unsettled check, rendered from `check.version(progress.attempt())`:
- choice check: the prompt, then one `Button` per choice labelled `"1  " + text` … `"4  " + text`, action `host.answerChoice(check.id(), i)`;
- RISK: prompt, a `TextArea` (`⌘⏎` submits via its own `KEY_PRESSED` handler when `event.isShortcutDown() && code == ENTER`) and a "Send answer" `Button`;
- if `lastExplanation` is present: a "Not quite: <explanation>" label above the prompt;
- AWAITING_AGENT: "Checking with the agent…" label (no buttons);
- AGENT_UNAVAILABLE: "The agent did not answer." plus "Retry" `Button`;
- EXHAUSTED: "Out of alternates." plus an override `TextField` (reason) with an "Approve without passing" `Button` (disabled while the reason is blank) and an "Ask the agent" `Button`.
When every check is settled: "All checks passed — press a to approve this step." When the step is PASSED/OVERRIDDEN/CHANGES: a one-line state summary.

- [ ] **Step 1: Write the failing TestFX tests**

```java
package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourStep;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepPanelTest extends ApplicationTest {

    private final List<String> calls = new ArrayList<>();
    private StepPanel panel;

    private static TourCheck predict() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.PREDICT, "Alt?",
                List.of(new TourCheck.Choice("p", Optional.empty()), new TourCheck.Choice("q", Optional.empty())),
                OptionalInt.of(0), "Alt because.", List.of());
        return new TourCheck("c1", TourCheck.Kind.PREDICT, "What happens?",
                List.of(new TourCheck.Choice("throws", Optional.empty()), new TourCheck.Choice("returns", Optional.empty())),
                OptionalInt.of(1), "It returns.", List.of(alternate));
    }

    private static final TourStep STEP = new TourStep("s1", "Guard", "Why the guard exists.",
            List.of(new TourAnchor("src/A.java", "n3", "n9")), List.of(), List.of(predict()));

    private static StepView view(CheckProgress check) {
        return new StepView(STEP, 1, 3, new StepProgress("s1", List.of(), Map.of("c1", check),
                StepProgress.Decision.NONE, Optional.empty(), false));
    }

    @Override
    public void start(Stage stage) {
        panel = new StepPanel(new StepPanel.Host() {
            @Override public void answerChoice(String checkId, int choiceIndex) { calls.add("answer " + checkId + " " + choiceIndex); }
            @Override public void submitRisk(String checkId, String answer) { calls.add("risk " + checkId + " " + answer); }
            @Override public void override(String reason) { calls.add("override " + reason); }
            @Override public void askAgent(String checkId) { calls.add("ask " + checkId); }
            @Override public void goToAnchor(int anchorIndex) { calls.add("anchor " + anchorIndex); }
            @Override public void retryRisk(String checkId) { calls.add("retry " + checkId); }
        });
        stage.setScene(new Scene(panel, 336, 700));
        stage.show();
    }

    @Test
    void choicesAreNumberedButtonsThatAnswerTheCheck() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        clickOn("2  returns");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("answer c1 1"), calls);
    }

    @Test
    void aDigitKeyAnswersTheActiveChoiceCheck() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        boolean[] handled = new boolean[1];
        interact(() -> handled[0] = panel.answerByKey(1));
        assertTrue(handled[0]);
        assertEquals(List.of("answer c1 0"), calls);
    }

    @Test
    void afterAWrongAnswerTheExplanationAndTheAlternateShow() {
        CheckProgress wrong = new CheckProgress("c1", 1, CheckProgress.Status.OPEN, Optional.empty(),
                Optional.empty(), Optional.of("It returns."));
        interact(() -> panel.show(view(wrong)));
        assertTrue(lookup("Not quite: It returns.").tryQuery().isPresent());
        assertTrue(lookup("Alt?").tryQuery().isPresent());
    }

    @Test
    void anExhaustedCheckOffersAnOverrideThatNeedsAReason() {
        CheckProgress exhausted = new CheckProgress("c1", 1, CheckProgress.Status.EXHAUSTED, Optional.empty(),
                Optional.empty(), Optional.of("Alt because."));
        interact(() -> panel.show(view(exhausted)));
        Button override = lookup("Approve without passing").queryAs(Button.class);
        assertTrue(override.isDisabled());
        clickOn(".step-override-reason").write("trivial rename");
        WaitForAsyncUtils.waitForFxEvents();
        clickOn("Approve without passing");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("override trivial rename"), calls);
    }

    @Test
    void anchorChipsGoToTheirAnchor() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        clickOn("src/A.java:3");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("anchor 0"), calls);
    }
}
```

`TourOutlineTest` (same harness): `setRows` with three rows renders three `Button`s with style class `tour-outline-row`, the current one has `tour-outline-row-current`; clicking a row calls `onSelected` with its step id; `showMessage("Building tour…", Optional.empty(), () -> {})` shows the message and no rows; `showMessage("No tour arrived.", Optional.of("Retry"), action)` shows a Retry button that runs `action`; `setFooter(2, false)` shows "2 files without line changes" with an "Acknowledge" button that calls `onAcknowledge`; `stateOf` maps `Decision.PASSED` → `PASSED`, an `AWAITING_AGENT` check → `CHECKING`, stale → `STALE`, a check with attempt > 0 or any settled check → `IN_PROGRESS`, else `NOT_STARTED`. Write these five tests in full in the same style as `StepPanelTest`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.StepPanelTest" --tests "app.drydock.ui.review.TourOutlineTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement `StepView`, `StepPanel`, `TourOutline`**

`StepView`:

```java
package app.drydock.ui.review;

import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourStep;

/** What the step panel shows for the current step. */
record StepView(TourStep step, int number, int total, StepProgress progress) {
}
```

`StepPanel` skeleton (complete the rendering exactly as listed under **Interfaces**):

```java
package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The right-hand panel in tour mode: the current step's narrative, anchors
 * and active check, and the containers later tasks fill with triage and
 * impact. View-only: every action goes to {@link Host}.
 */
final class StepPanel extends VBox {

    static final double EXPANDED_WIDTH = ReviewFindingsMargin.EXPANDED_WIDTH;
    static final double NARROW_WIDTH = ReviewFindingsMargin.NARROW_WIDTH;
    static final double COLLAPSED_WIDTH = ReviewFindingsMargin.COLLAPSED_WIDTH;

    interface Host {
        void answerChoice(String checkId, int choiceIndex);
        void submitRisk(String checkId, String answer);
        void override(String reason);
        void askAgent(String checkId);
        void goToAnchor(int anchorIndex);
        void retryRisk(String checkId);
    }

    private final Host host;
    private final VBox content = new VBox(10);
    private final VBox extraSections = new VBox(10);
    private final List<Button> choiceButtons = new ArrayList<>();
    private Optional<TextArea> riskBox = Optional.empty();
    private Optional<TextField> overrideReason = Optional.empty();
    private boolean collapsed;
    private boolean narrow;

    StepPanel(Host host) {
        this.host = host;
        getStyleClass().add("step-panel");
        ScrollPane scroll = new ScrollPane(new VBox(14, content, extraSections));
        scroll.setFitToWidth(true);
        getChildren().add(scroll);
        applyWidth();
    }

    void show(StepView view) {
        choiceButtons.clear();
        riskBox = Optional.empty();
        overrideReason = Optional.empty();
        content.getChildren().clear();
        Label header = new Label("Step " + view.number() + " of " + view.total() + " · " + view.step().title());
        header.getStyleClass().add("step-panel-header");
        Label narrative = new Label(view.step().narrative());
        narrative.setWrapText(true);
        narrative.getStyleClass().add("step-panel-narrative");
        content.getChildren().addAll(header, narrative, anchorChips(view));
        content.getChildren().add(checkSection(view));
    }

    void showMessage(String message) {
        choiceButtons.clear();
        content.getChildren().setAll(new Label(message));
        extraSections.getChildren().clear();
    }

    /** Presses choice {@code oneBased} of the active choice check; false when there is none. */
    boolean answerByKey(int oneBased) {
        int index = oneBased - 1;
        if (index < 0 || index >= choiceButtons.size()) {
            return false;
        }
        choiceButtons.get(index).fire();
        return true;
    }

    void focusUnmet(StepGate.Unmet unmet) {
        if (!choiceButtons.isEmpty()) {
            choiceButtons.getFirst().requestFocus();
        } else {
            riskBox.ifPresentOrElse(TextArea::requestFocus, () -> overrideReason.ifPresent(TextField::requestFocus));
        }
    }

    VBox extraSections() {
        return extraSections;
    }

    boolean collapsed() {
        return collapsed;
    }

    void setCollapsed(boolean value) {
        collapsed = value;
        content.setVisible(!value);
        extraSections.setVisible(!value);
        applyWidth();
    }

    void setNarrow(boolean value) {
        narrow = value;
        applyWidth();
    }

    private void applyWidth() {
        double width = collapsed ? COLLAPSED_WIDTH : narrow ? NARROW_WIDTH : EXPANDED_WIDTH;
        setMinWidth(width);
        setPrefWidth(width);
        setMaxWidth(width);
    }

    private FlowPane anchorChips(StepView view) {
        FlowPane chips = new FlowPane(6, 6);
        List<TourAnchor> anchors = view.step().anchors();
        for (int i = 0; i < anchors.size(); i++) {
            int index = i;
            TourAnchor anchor = anchors.get(i);
            Button chip = new Button(anchor.file() + ":" + startLineOf(anchor));
            chip.getStyleClass().add("step-anchor-chip");
            chip.setOnAction(event -> host.goToAnchor(index));
            chips.getChildren().add(chip);
        }
        return chips;
    }

    private static String startLineOf(TourAnchor anchor) {
        return anchor.startKey().substring(1);
    }

    private VBox checkSection(StepView view) {
        VBox box = new VBox(8);
        box.getStyleClass().add("step-check");
        StepProgress progress = view.progress();
        if (progress.decision() != StepProgress.Decision.NONE) {
            box.getChildren().add(new Label(switch (progress.decision()) {
                case PASSED -> "Approved.";
                case CHANGES -> "Changes requested.";
                case OVERRIDDEN -> "Approved without passing: " + progress.overrideReason().orElse("");
                case NONE -> "";
            }));
            return box;
        }
        for (TourCheck check : view.step().checks()) {
            CheckProgress p = progress.check(check.id());
            if (p.settled()) {
                continue;
            }
            renderActiveCheck(box, check, p);
            return box;
        }
        box.getChildren().add(new Label("All checks passed — press a to approve this step."));
        return box;
    }

    private void renderActiveCheck(VBox box, TourCheck check, CheckProgress p) {
        TourCheck offered = check.version(p.attempt());
        p.lastExplanation().ifPresent(text -> {
            Label explanation = new Label("Not quite: " + text);
            explanation.setWrapText(true);
            explanation.getStyleClass().add("step-check-explanation");
            box.getChildren().add(explanation);
        });
        switch (p.status()) {
            case AWAITING_AGENT -> box.getChildren().add(new Label("Checking with the agent…"));
            case AGENT_UNAVAILABLE -> {
                Button retry = new Button("Retry");
                retry.setOnAction(event -> host.retryRisk(check.id()));
                box.getChildren().addAll(new Label("The agent did not answer."), retry);
            }
            case EXHAUSTED -> {
                TextField reason = new TextField();
                reason.setPromptText("Why approve without passing?");
                reason.getStyleClass().add("step-override-reason");
                Button override = new Button("Approve without passing");
                override.disableProperty().bind(reason.textProperty().isEmpty());
                override.setOnAction(event -> host.override(reason.getText().strip()));
                Button ask = new Button("Ask the agent");
                ask.setOnAction(event -> host.askAgent(check.id()));
                overrideReason = Optional.of(reason);
                box.getChildren().addAll(new Label("Out of alternates."), reason, override, ask);
            }
            default -> renderPrompt(box, check, offered);
        }
    }

    private void renderPrompt(VBox box, TourCheck check, TourCheck offered) {
        Label prompt = new Label(offered.prompt());
        prompt.setWrapText(true);
        prompt.getStyleClass().add("step-check-prompt");
        box.getChildren().add(prompt);
        if (offered.kind() == TourCheck.Kind.RISK) {
            TextArea answer = new TextArea();
            answer.setWrapText(true);
            answer.setPrefRowCount(3);
            Button send = new Button("Send answer");
            send.disableProperty().bind(answer.textProperty().isEmpty());
            send.setOnAction(event -> host.submitRisk(check.id(), answer.getText().strip()));
            answer.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
                if (event.isShortcutDown() && event.getCode() == KeyCode.ENTER && !answer.getText().isBlank()) {
                    send.fire();
                    event.consume();
                }
            });
            riskBox = Optional.of(answer);
            box.getChildren().addAll(answer, send);
            return;
        }
        for (int i = 0; i < offered.choices().size(); i++) {
            int index = i;
            Button choice = new Button((i + 1) + "  " + offered.choices().get(i).text());
            choice.getStyleClass().add("step-choice");
            choice.setWrapText(true);
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setOnAction(event -> host.answerChoice(check.id(), index));
            choiceButtons.add(choice);
            box.getChildren().add(choice);
        }
    }
}
```

`TourOutline`: a `VBox` with a two-tab header (`ToggleButton`s "Tour" / "Search" in a `ToggleGroup`; remember the ToggleButton trap from project memory — guard against deselecting both by re-selecting in the group's `selectedToggleProperty` listener), a rows `VBox` in a `ScrollPane`, a message area, and the footer. Widths from `ReviewIntentRail.EXPANDED_WIDTH/NARROW_WIDTH/COLLAPSED_WIDTH`. Each row is a `Button` with text `number + ". " + title` and a state glyph (`✓` passed, `✎` in progress, `…` checking, `✗` changes, `⚑` overridden, `⟳` stale), style class `tour-outline-row` plus `tour-outline-row-current` for the current step. `stateOf` as specified.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.StepPanelTest" --tests "app.drydock.ui.review.TourOutlineTest" --offline`
Expected: 10 passed.

- [ ] **Step 5: Commit**

Subject: `Review tour: an outline of steps and a panel that teaches and checks one step at a time`. Body per rules; verification; not covered: not mounted in Review yet. `Co-Authored-By` trailer.

---

### Task 10: Tour mode in the Review view

**Read first:** spec §5 (layout, approval, `v`, keys retired/moved), §8 (Run review → "Building tour…", Retry / Open diff review). Recon facts: `SessionReviewView` constructor at :723, `Host` at :80, `buildCenter()` at :886, `applyResponsiveLayout` at :2691, `handleShortcut` at :3078, `refreshReviewState()` at :1162, `MainWorkspace` pushes `refreshReviewState()` on store changes (:600, :628).

**Constraints:** FX thread only for UI; every async path clears its progress state; the "Building tour…" wait uses a `PauseTransition` stopped in `close()`; every key the overlay advertises is bound and vice versa (`ShortcutsOverlayParityTest`), new keys added to `REPLAYABLE_OFF_REVIEW_SUBTREE`; adding `Host` methods requires updating `FakeReviewHost` and `MainWorkspace.ReviewHost`.

**Scope clarification (spec §5/§8):** hunk-diff mode (`v`) is today's Review surface unchanged — intent rail, path mode `p`, `i`, findings margin — so nothing is deleted in this plan. In tour mode `p` and `i` are inert. Retiring the intent rail, path mode and `review_intents` entirely deletes existing tests and is left for a follow-up that asks first (CLAUDE.md: "ask before deleting or disabling tests").

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`ReviewHost`: new methods; `REPLAYABLE_OFF_REVIEW_SUBTREE`)
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` ("IN REVIEW" rows)
- Modify: `app/src/test/java/app/drydock/ui/ShortcutsOverlayParityTest.java` (bound set)
- Modify: `app/src/test/java/app/drydock/ui/review/FakeReviewHost.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewTourModeTest.java`
- Test fixture: `app/src/test/java/app/drydock/ui/review/ReviewTourFixture.java`

**Interfaces:**
- Consumes: Tasks 1–9.
- Produces (`SessionReviewView.Host` additions, all FX-thread):
  - `Optional<TourRecord> tour(ReviewScope scope);`
  - `void updateTour(ReviewScope scope, UnaryOperator<TourRecord> transform);`
  - `void applyTourVerdicts(ReviewScope scope, Map<String, Optional<ReviewVerdict.Decision>> byDigest);`
- Produces (view): `enum ReviewMode { TOUR, DIFF }`, `ReviewMode diagMode()`, `String diagCurrentStepId()`, `TourOutline diagOutline()`, `StepPanel diagStepPanel()`.

Behaviour:
- Mode: TOUR when the scope has a tour, otherwise DIFF; `v` toggles; the choice is per view and survives refreshes. While no tour exists and Run review was pressed (`host.runReview` returned true), TOUR mode shows the outline with "Building tour…" and the MCP activity panel; after 15 minutes without a tour the outline shows "No tour arrived." with **Retry** (calls `runReviewOnSelection()`) and **Open diff review** (switches to DIFF). If `runReview` returns false, show the failure immediately with the same two actions.
- Layout in TOUR mode: `setLeft(outline)` instead of `intentRail`; replace the margin in the centre `HBox` with `stepPanel` (keep `columns` as a field); `diffColumn.setWholeFiles(true)`; `diffColumn.setStepMarkSource(TourMarks.of(record, diffColumn.renderedDiff(), currentStepId))`. DIFF mode restores today's nodes and `setWholeFiles(false)`, `setStepMarkSource(null)`. `applyResponsiveLayout` applies `setNarrow/setCollapsed` to whichever left/right nodes are showing.
- Current step: the first step whose decision is NONE or which is stale; selected via the outline, `[`/`]`, `n`. Changing step reveals its first anchor (`diffColumn.revealLine(anchor.file(), anchor.startKey())`) and re-renders marks.
- Tour keys (TOUR mode only, before the existing switch): `a` pass (if `StepGate.unmet` is present → `stepPanel.focusUnmet(unmet)`; else decision PASSED, sync verdicts, advance to next unsettled step), `r` CHANGES, `u` back to NONE, `n` next unsettled, `[`/`]` previous/next step, `1`–`4` `stepPanel.answerByKey(n)`, `v` toggle. Shift+`a`/`r` are inert in TOUR mode. `p`, `i`, `⇧F` inert in TOUR mode.
- StepPanel host: `answerChoice` → `host.updateTour(scope, r -> r.withProgress(p.withCheck(StepGrading.answerChoice(check, p.check(id), i))))`; `override(reason)` → decision OVERRIDDEN with reason; `submitRisk` → `StepGrading.submitRisk` (the dispatch to the agent arrives in Task 13); `goToAnchor(i)` → reveal; `askAgent`/`retryRisk` → no-op until Tasks 13/16 (log at DEBUG).
- Verdict sync: after every tour change and every `refreshReviewState()` while a tour exists, compute `StepVerdicts.derive(record, AnchorIndex.of(reviewDiff))` and call `host.applyTourVerdicts(scope, derived)`; the host writes only hunks whose stored decision differs (`put` or `clear`).
- Hunk-diff approvals while a tour exists: in `verdictAction` (DIFF mode, tour present), after `host.setVerdict(...)` also call `host.updateTour(scope, r -> r.withHunkOverride(digest, Optional.of(new HunkOverride(decision, "set in the hunk diff"))))` for each digest; `undoVerdict` removes the overrides. Derivation then keeps them.

- [ ] **Step 1: Write the fixture and failing tests**

`ReviewTourFixture` extends the existing `ReviewViewFixture` pattern (read it: scene 1400×900, `FakeReviewHost`, `showBoard()`), but its diff and tour are:

```java
// two files, one hunk each, ADD rows only:
// src/guards.h   n1 "void foo();"   n11 "void bar();"   (two hunks: indices 0 and 1)
// src/guards.cpp n1 "void baz();"
// tour: s1 covers src/guards.h n1..n11 with a PREDICT c1 (answer 1, one alternate);
//       s2 covers src/guards.cpp n1..n1 with a PREDICT c2.
// host.tours.put(TourRecord.fresh(tour, host.diff)) before showing the board.
```

Write it in full: copy `ReviewViewFixture`'s `start`, `showBoard` and `tearDown`, build the diff with the same `file(...)` helper, build the two `TourCheck`s like `TourFixtures.predict`, and store the tour in `FakeReviewHost` (Step 3 adds `tours`).

```java
package app.drydock.ui.review;

import app.drydock.review.ReviewVerdict;
import app.drydock.review.tour.StepProgress;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewTourModeTest extends ReviewTourFixture {

    @Test
    void aScopeWithATourOpensInTourModeOnTheFirstStep() {
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertEquals("s1", ReviewDiagFxThread.call(view::diagCurrentStepId));
    }

    @Test
    void approvingBeforeTheCheckIsAnsweredDoesNotPassTheStep() {
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision());
    }

    @Test
    void answeringRightThenApprovingPassesTheStepAndApprovesItsHunks() {
        press(KeyCode.DIGIT2).release(KeyCode.DIGIT2);
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 0));
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 1));
        assertEquals(Optional.empty(), verdictOfHunk(FILE_B, 0));
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId), "advanced to the next unsettled step");
    }

    @Test
    void aWrongAnswerKeepsTheStepOpenOnTheAlternate() {
        press(KeyCode.DIGIT1).release(KeyCode.DIGIT1);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, progress("s1").check("c1").attempt());
    }

    @Test
    void vSwitchesToTheHunkDiffAndBack() {
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
        assertTrue(lookup(".review-intent-card").tryQuery().isPresent(), "today's intent rail is back");
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
    }

    @Test
    void anApprovalInTheHunkDiffIsRecordedAsAnOverride() throws Exception {
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        focusDiffColumn();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(!host.tours.forScope(scope.id()).orElseThrow().hunkOverrides().isEmpty());
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision(), "the step itself is not passed");
    }
}
```

`progress(stepId)` and `verdictOfHunk(file, index)` are fixture helpers reading `host.tours.forScope(scope.id())` and `host.store.verdict(scope.id(), HunkDigest.of(file, hunk))` through `ReviewDiagFxThread.call`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.ReviewTourModeTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 3: Extend the host interface and both hosts**

Add the three `Host` methods. `FakeReviewHost`: field `final TourStore tours;` created in the constructor next to the annotation store (`storeFile.resolveSibling("review-tours.json")`) and closed in the fixture teardown; `tour` → `tours.forScope(scope.id())`; `updateTour` → `tours.mutate(scope.id(), transform)`; `applyTourVerdicts` → for each entry, compare with `store.verdict(...)` and `store.putVerdict(new ReviewVerdict(scope.id(), digest, decision, Optional.empty(), Instant.now(), baseCommit, headCommit))` or `store.clearVerdict(scope.id(), digest)`.

`MainWorkspace.ReviewHost`: same three methods over `tourStore` and `annotationStore`; for `applyTourVerdicts` build the verdict with the base/head commits the existing `setVerdict` implementation uses (reuse its helper — read `ReviewHost.setVerdict` first).

- [ ] **Step 4: Implement tour mode in `SessionReviewView`**

Add fields `private final TourOutline outline = new TourOutline();`, `private final StepPanel stepPanel = new StepPanel(new StepHost());`, `private ReviewMode mode = ReviewMode.DIFF;`, `private boolean userChoseMode;`, `private String currentStepId;`, `private boolean tourPending;`, `private final PauseTransition tourWait = new PauseTransition(Duration.minutes(15));`, and make the centre `HBox columns` a field.

Core methods (write them in full, following these bodies):

```java
private Optional<TourRecord> currentTour() {
    return selectedScope().flatMap(host::tour);
}

private void applyMode() {
    Optional<TourRecord> tour = currentTour();
    if (!userChoseMode) {
        mode = tour.isPresent() || tourPending ? ReviewMode.TOUR : ReviewMode.DIFF;
    }
    boolean touring = mode == ReviewMode.TOUR;
    setLeft(touring ? outline : intentRail);
    columns.getChildren().set(1, touring ? stepPanel : margin);
    diffColumn.setWholeFiles(touring);
    if (touring) {
        renderTour(tour);
    } else {
        diffColumn.setStepMarkSource(null);
    }
    applyResponsiveLayout(getWidth());
}

private void renderTour(Optional<TourRecord> tour) {
    if (tour.isEmpty()) {
        if (tourPending) {
            outline.showMessage("Building tour…", Optional.empty(), () -> { });
            stepPanel.showMessage("The agent is writing the tour. Its MCP calls show below.");
        } else {
            outline.showMessage("No tour yet.", Optional.of("Run review"), this::runReviewOnSelection);
            stepPanel.showMessage("Run review asks this session's agent for a guided tour.");
        }
        diffColumn.setStepMarkSource(null);
        return;
    }
    TourRecord record = tour.get();
    if (currentStepId == null || record.tour().step(currentStepId).isEmpty()) {
        currentStepId = firstUnsettled(record).orElse(record.tour().steps().getFirst().id());
    }
    outline.setRows(record.tour().steps().stream()
            .map(step -> new TourOutline.Row(step.id(), record.tour().number(step.id()), step.title(),
                    TourOutline.stateOf(record.progress(step.id()))))
            .toList(), currentStepId);
    TourStep step = record.tour().step(currentStepId).orElseThrow();
    stepPanel.show(new StepView(step, record.tour().number(step.id()), record.tour().steps().size(),
            record.progress(step.id())));
    diffColumn.setStepMarkSource(TourMarks.of(record, diffColumn.renderedDiff(), currentStepId));
    syncTourVerdicts(record);
}

private Optional<String> firstUnsettled(TourRecord record) {
    return record.tour().steps().stream()
            .filter(step -> {
                StepProgress p = record.progress(step.id());
                return p.stale() || p.decision() == StepProgress.Decision.NONE;
            })
            .map(TourStep::id)
            .findFirst();
}

private void syncTourVerdicts(TourRecord record) {
    reviewDiffOfSelection().ifPresent(diff ->
            selectedScope().ifPresent(scope ->
                    host.applyTourVerdicts(scope, StepVerdicts.derive(record, AnchorIndex.of(diff)))));
}

private boolean handleTourShortcut(KeyEvent event) {
    if (mode != ReviewMode.TOUR) {
        return false;
    }
    Optional<TourRecord> tour = currentTour();
    switch (event.getCode()) {
        case V -> { toggleMode(); return true; }
        case P, I -> { return true; }
        case F -> { if (event.isShiftDown()) { return true; } return false; }
        default -> { }
    }
    if (tour.isEmpty()) {
        return false;
    }
    TourRecord record = tour.get();
    return switch (event.getCode()) {
        case A -> { if (!event.isShiftDown()) { passCurrentStep(record); } yield true; }
        case R -> { if (!event.isShiftDown()) { decideCurrentStep(StepProgress.Decision.CHANGES, Optional.empty()); } yield true; }
        case U -> { decideCurrentStep(StepProgress.Decision.NONE, Optional.empty()); yield true; }
        case N -> { firstUnsettled(record).ifPresent(this::selectStep); yield true; }
        case OPEN_BRACKET -> { moveStep(record, -1); yield true; }
        case CLOSE_BRACKET -> { moveStep(record, 1); yield true; }
        case DIGIT1, DIGIT2, DIGIT3, DIGIT4 -> {
            stepPanel.answerByKey(event.getCode().ordinal() - KeyCode.DIGIT1.ordinal() + 1);
            yield true;
        }
        default -> false;
    };
}

private void passCurrentStep(TourRecord record) {
    TourStep step = record.tour().step(currentStepId).orElseThrow();
    Optional<StepGate.Unmet> unmet = StepGate.unmet(step, record.progress(step.id()));
    if (unmet.isPresent()) {
        stepPanel.focusUnmet(unmet.get());
        return;
    }
    decideCurrentStep(StepProgress.Decision.PASSED, Optional.empty());
    currentTour().flatMap(this::firstUnsettled).ifPresent(this::selectStep);
}

private void decideCurrentStep(StepProgress.Decision decision, Optional<String> reason) {
    selectedScope().ifPresent(scope -> host.updateTour(scope,
            record -> record.withProgress(record.progress(currentStepId).withDecision(decision, reason))));
    renderTour(currentTour());
}
```

Wire it up:
- In `handleShortcut`, after the existing modifier/`TextInputControl` guard: `if (handleTourShortcut(event)) { return true; }`.
- `refreshReviewState()` ends with `applyMode()` (the existing DIFF rendering stays as it is; `applyMode` only swaps nodes and renders the tour).
- `renderSelectedScope()` resets `currentStepId = null; userChoseMode = false;`.
- `toggleMode()`: `userChoseMode = true; mode = mode == ReviewMode.TOUR ? ReviewMode.DIFF : ReviewMode.TOUR; applyMode();`.
- `selectStep(id)`: set `currentStepId`, render, reveal the step's first anchor.
- `moveStep(record, delta)`: clamp within the steps list.
- `reviewDiffOfSelection()`: the `DiffOutcome.Loaded` diff in `outcomeByScope` for the selected scope (that is the review diff; it is what `onDiffResolved` publishes).
- `runReviewOnSelection()` (existing, ~:2996): when `host.runReview(scope)` returns true set `tourPending = true`, `tourWait.playFromStart()`, `applyMode()`; when it returns false show the failure in the outline with Retry/Open diff review.
- `tourWait.setOnFinished(e -> { tourPending = false; if (currentTour().isEmpty()) outline.showFailure("No tour arrived.", this::runReviewOnSelection, this::openDiffReview); })`, where `openDiffReview()` sets `userChoseMode = true; mode = ReviewMode.DIFF; applyMode();`. A `false` from `host.runReview` shows `outline.showFailure("Could not reach this session's agent.", ...)` the same way.
- While `tourPending`, show the MCP activity panel (`mcpPanel`) so the agent's calls are visible; hide it again when the tour arrives or the wait fails, unless the user had opened it with `\\`.
- Tour mode drives the verdict bar: `verdictBar.showProgress(passedOrOverriddenSteps, totalSteps)`, and `VerdictHost.approve/requestChanges/undo/nextUnsettled/previousIntent/nextIntent` route to `passCurrentStep`, `decideCurrentStep(CHANGES)`, `decideCurrentStep(NONE)`, `n`, `[` and `]` when `mode == ReviewMode.TOUR`.
- Files without line changes: count the review diff's files with no hunks; `outline.setFooter(count, acknowledged)` where `acknowledged` is per-view state set by `onAcknowledge` (not persisted; spec §3 asks only that the reviewer acknowledges them, and they carry no verdict).
- `selectedScope()` here means the view's existing accessor for the scope currently shown (read how `renderSelectedScope()` gets it).
- When a tour arrives (`refreshReviewState` finds one while `tourPending`), set `tourPending = false` and stop `tourWait`.
- `close()`: `tourWait.stop();`.
- In `verdictAction`/`undoVerdict` (DIFF mode): when `currentTour()` is present, record/remove `HunkOverride`s for the acted digests as specified above.
- Inner `StepHost implements StepPanel.Host` implementing the actions listed under Behaviour.

- [ ] **Step 5: Shortcuts overlay, parity set and replayable keys**

`ShortcutsOverlay` "IN REVIEW": change the rows to

```java
{"Focus mode — collapse every rail", "f"},
{"Cycle density: cozy · compact · dense", "d"},
{"Show or hide unchanged lines (whole files: fold long runs)", "c"},
{"Tour / hunk diff", "v"},
{"Reading path / intents (hunk diff)", "p"},
{"Previous / next step (hunk diff: intent or path row)", "[ / ]"},
{"Next unsettled step (hunk diff: intent or hunk)", "n"},
{"Answer the current check", "1 / 2 / 3 / 4"},
{"Approve the step once its checks pass (hunk diff: section or next unread hunk)", "a"},
{"Request changes (step, or section / next unread hunk)", "r"},
{"Undo (step, or section / next unread hunk)", "u"},
{"Approve every hunk in this file (hunk diff)", "⇧A"},
{"Request changes on this file (hunk diff)", "⇧R"},
{"Send a free-text answer", "⌘⏎"},
{"Submit the review", "⏎"},
{"Collapse the intents (hunk diff)", "i"},
{"Collapse the findings margin / step panel", "m"},
{"Findings from the whole review (hunk diff)", "⇧F"},
{"MCP activity log", "\\"},
```

`ShortcutsOverlayParityTest.theReviewBoardAdvertisesExactlyTheKeysItBinds`: add `"v", "1", "2", "3", "4"` to the bound set, and exclude `"⌘⏎"` the way the Explorer test excludes rows that are not the view's own binding (`⌘⏎` is bound by the step panel's text area, not the board filter). `m` collapses whichever right-hand node is showing: change the `M` case to `if (mode == ReviewMode.TOUR) stepPanel.setCollapsed(!stepPanel.collapsed()); else setMarginCollapsed(!margin.collapsed());`.

`MainWorkspace.REPLAYABLE_OFF_REVIEW_SUBTREE`: add `KeyCode.V, KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4` (and replace the inline `java.util.Set` with an imported `Set` while touching that line — a housekeeping fix the AGENTS.md rule asks for).

- [ ] **Step 6: Run the Review UI tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*" --tests "app.drydock.ui.ShortcutsOverlayParityTest" --offline`
Expected: all pass. Existing review tests run in DIFF mode because their scopes have no tour.

- [ ] **Step 7: Commit**

Subject: `Review tour: a scope with a tour opens on its first step, and a step passes only when its checks do`. Body: the absence; tour mode layout; `v`; gating; derived verdicts; hunk-diff approvals recorded as hunk overrides; Building tour / Retry / Open diff review; why hunk-diff mode keeps today's surface (nothing deleted; retiring it deletes tests and is asked for separately); verification; not covered: RISK grading, triage, navigation, impact. `Co-Authored-By` trailer.

---

## Phase C — The agent proposes, the human decides

### Task 11: Findings carry a triage state, and only confirmed ones count

**Read first:** spec §4 ("Triage"), recon: `ReviewAnnotation` is a 20-component record; `postToPr` is persisted only when true and decoded absent → false (`AnnotationStore.java:646/756`); `ReviewToolCodec.findingFromJson` keeps human-owned fields on a re-run (:556-567).

**Constraints:** existing findings decode as CONFIRMED (absent field), so nothing posted or sent changes; an agent re-stating a finding keeps the human's triage; a convenience constructor keeps the 20-argument call sites compiling; `AnnotationStore.SCHEMA_VERSION` 5 → 6 with a doc line; wire change approved by the user (spec §8).

**Files:**
- Create: `app/src/main/java/app/drydock/review/Triage.java`
- Modify: `app/src/main/java/app/drydock/review/ReviewAnnotation.java` (two components, convenience constructor, withers, `blocksApproval`)
- Modify: `app/src/main/java/app/drydock/review/AnnotationStore.java` (`findingToJson`, `findingFromJson`, schema version)
- Modify: `app/src/main/java/app/drydock/mcp/ReviewToolCodec.java` (`findingFromJson`, `findingStateToJson`)
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (`review_finding` descriptor text)
- Modify: `app/src/main/java/app/drydock/review/SubmitPlan.java` (skip non-confirmed; `untriagedCount`)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewFindingsMargin.java` (Proposed chip; Confirm / Dismiss / Not sure; post toggle disabled until confirmed)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (`Host.setTriage`; filter `askAgentToFix` to confirmed)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` and `FakeReviewHost.java` (`setTriage`)
- Test: `app/src/test/java/app/drydock/review/AnnotationTriageTest.java`
- Test: `app/src/test/java/app/drydock/mcp/McpToolRouterTriageTest.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewTriageMarginTest.java`

**Interfaces:**
- Produces:
  - `enum Triage { PROPOSED("proposed"), CONFIRMED("confirmed"), DISMISSED("dismissed") }` with `wireName()`, `fromWire(String)`
  - `ReviewAnnotation` components appended: `Triage triage, Optional<String> withheldBy`; the old 20-argument constructor remains and delegates with `Triage.CONFIRMED, Optional.empty()`; withers `withTriage(Triage)`, `withWithheldBy(Optional<String>)`; every existing wither carries both new fields
  - `ReviewAnnotation.blocksApproval()` = `triage == Triage.CONFIRMED && !resolved() && effectiveSeverity().blocksApproval()`
  - `ReviewAnnotation.counts()` = `triage == Triage.CONFIRMED`
  - `SubmitPlan.of(...)` skips findings with `!finding.counts()`; `static long SubmitPlan.untriagedCount(List<ReviewAnnotation>)` counts PROPOSED, unresolved
  - `review_finding`: new findings land PROPOSED, re-runs keep the stored triage; optional `withheldBy` string; a BLOCKING finding with `withheldBy` is rejected ("a blocking finding is never withheld: <id>")
  - `findingStateToJson` emits `triage` and, when present, `withheldBy`
  - `SessionReviewView.Host`: `void setTriage(ReviewScope scope, ReviewAnnotation finding, Triage triage, Optional<String> reason);` — reason is appended to the thread as `"Dismissed: <reason>"` by the host
  - `ReviewFindingsMargin.Host`: `void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason);`

- [ ] **Step 1: Write the failing persistence and model tests** (`AnnotationTriageTest`): (a) `AnnotationStore.fromJson` on a schema-5 JSON finding without `triage` yields `Triage.CONFIRMED`; (b) a PROPOSED finding with `withheldBy` round-trips through `AnnotationStore.toJson`/`fromJson`; (c) a PROPOSED BLOCKING finding does not `blocksApproval()`, the same finding confirmed does; (d) `withStatus`, `withReply`, `withPostToPr` keep `triage` and `withheldBy`; (e) `SubmitPlan.of` with one CONFIRMED and one PROPOSED `postToPr` finding posts only the confirmed one and `untriagedCount` is 1. Build findings with the 22-argument constructor; use the JSON style of the existing legacy-file tests in `AnnotationStoreTest` for (a).

- [ ] **Step 2: Write the failing router tests** (`McpToolRouterTriageTest`, extending the `McpToolRouterReviewTest` setup style): a new agent finding is PROPOSED; a re-run after `context.mutateAnnotation(key, f -> f.withTriage(Triage.DISMISSED))` stays DISMISSED; `withheldBy` is stored; a BLOCKING finding with `withheldBy` throws `McpToolException` naming the id; `review_state` findings carry `"triage":"proposed"`.

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:test --tests "app.drydock.review.AnnotationTriageTest" --tests "app.drydock.mcp.McpToolRouterTriageTest" --offline`
Expected: compilation FAILURE.

- [ ] **Step 4: Implement the model and persistence**

`Triage.java` follows the wire-name enum pattern of `Severity` (constructor with wire name, `wireName()`, `fromWire` with `strip().toLowerCase(Locale.ROOT)`). In `ReviewAnnotation`, append the components, add to the compact constructor `Objects.requireNonNull(triage, "triage"); Objects.requireNonNull(withheldBy, "withheldBy");`, add the delegating 20-argument constructor, update every wither and `human(...)` (humans → CONFIRMED). In `AnnotationStore.findingToJson` add `obj.put("triage", new JsonString(finding.triage().wireName()));` and `finding.withheldBy().ifPresent(id -> obj.put("withheldBy", new JsonString(id)));`; in `findingFromJson` decode `optionalString(obj, "triage").flatMap(Triage::fromWire).orElse(Triage.CONFIRMED)` and `optionalString(obj, "withheldBy")`; bump `SCHEMA_VERSION` to 6 with the doc-comment line "6: findings carry triage (absent = confirmed) and withheldBy".

In `ReviewToolCodec.findingFromJson`: `Triage triage = existing.map(ReviewAnnotation::triage).orElse(Triage.PROPOSED);` and `Optional<String> withheldBy = optionalString(obj, "withheldBy");` after decoding severity: `if (withheldBy.isPresent() && severity == Severity.BLOCKING) throw new McpToolException("a blocking finding is never withheld: " + id);`; pass both to the 22-argument constructor. `findingStateToJson` emits them. Extend the `review_finding` descriptor's findings description with `withheldBy?` and the sentence "Findings land as proposals; the human confirms or dismisses each."

- [ ] **Step 5: Gate posting, sending and blocking on confirmation**

`SubmitPlan.of`: change the skip condition to `!finding.postToPr() || finding.resolved() || !finding.counts()`. Add `untriagedCount`. In `SessionReviewView`, wherever findings are handed to `host.askAgentToFix(...)`, filter `ReviewAnnotation::counts`. `blocksApproval` already includes triage.

`ReviewFindingsMargin`: for a PROPOSED card add a `Label("Proposed")` with style `review-card-proposed` and three `Button`s with style `review-card-action`: "Confirm" → `host.triage(f, CONFIRMED, empty)`; "Dismiss…" → reveals an inline `TextField` (prompt "Why is it wrong?") plus "Dismiss" `Button` disabled while blank → `host.triage(f, DISMISSED, Optional.of(reason))`; "Not sure" → focuses the card's existing reply field. Disable the existing post-to-PR toggle when `!f.counts()` with tooltip "Confirm the finding before posting it". DISMISSED cards are hidden under the OPEN filter and shown struck-through under ALL.

`MainWorkspace.ReviewHost.setTriage` and `FakeReviewHost.setTriage`: `store.mutate(finding.key(), f -> { ReviewAnnotation next = f.withTriage(triage); return reason.map(r -> next.withReply(new ReviewAnnotation.Message("You", Instant.now(), "Dismissed: " + r))).orElse(next); })`.

- [ ] **Step 6: Write and run the margin TestFX test** (`ReviewTriageMarginTest`, extends `ReviewViewFixture`): add a PROPOSED finding via `host.addFinding`, refresh, assert the "Proposed" chip is shown and the post toggle is disabled; click "Confirm" (real click), assert the stored triage is CONFIRMED; for a second finding click "Dismiss…", type a reason, click "Dismiss", assert DISMISSED and that the thread's last message is `"Dismissed: <reason>"`.

Run: `./gradlew :app:test --tests "app.drydock.review.*" --tests "app.drydock.mcp.*" --tests "app.drydock.ui.review.*" --offline`
Expected: all pass. (Existing tests use the 20-argument constructor → CONFIRMED, so their blocking/posting behaviour is unchanged.)

- [ ] **Step 7: Commit**

Subject: `Agent findings are proposals until the reviewer confirms them`. Body: the defect (an agent's finding could block approval and be sent to the author without a human looking at it; agents are wrong sometimes); triage field, migration as CONFIRMED (absent field), PROPOSED for new agent findings, kept across re-runs, `withheldBy` and the blocking rule, gating of post/send/block, margin actions, schema 6; verification; not covered: the tour's withheld-finding flow (Task 12). `Co-Authored-By` trailer.

---

### Task 12: The tour withholds non-blocking findings as checks and stops on blockers

**Read first:** spec §4 (non-blocking bugs become checks, blocking findings stop the tour, passing needs triage).

**Constraints:** a withheld finding is hidden **everywhere** in Review (step panel, margin, pins) until its check is answered or its step overridden; dismissing it voids the check; the "send back" action sends CONFIRMED blockers only, through the existing send path.

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/TourFindings.java`
- Modify: `app/src/main/java/app/drydock/review/tour/StepGate.java` (findings-aware overload)
- Modify: `app/src/main/java/app/drydock/review/tour/TourValidator.java` (withheld-finding rules; overload taking findings)
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (`reviewTour` passes findings to the validator)
- Modify: `app/src/main/java/app/drydock/ui/review/StepPanel.java` (triage section, blocker banner)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (visibility filter for margin and pins; banner; `Host.sendFindingsToAuthor`)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java`, `FakeReviewHost.java`
- Test: `app/src/test/java/app/drydock/review/tour/TourFindingsTest.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewTourTriageTest.java`

**Interfaces:**
- Produces:
  - `TourFindings.onStep(TourStep step, List<ReviewAnnotation> findings, AnchorIndex reviewIndex) -> List<ReviewAnnotation>` (findings whose `file`/`startKey` lies in one of the step's anchors)
  - `TourFindings.hidden(ReviewAnnotation finding, TourRecord record) -> boolean`: true when `withheldBy` is present, triage is not DISMISSED, the owning step is not OVERRIDDEN, and the check's progress is OPEN at attempt 0 with no `lastExplanation` (i.e. not yet answered)
  - `TourFindings.blockers(List<ReviewAnnotation> findings) -> List<ReviewAnnotation>`: unresolved BLOCKING findings whose triage is not DISMISSED
  - `TourFindings.needsBanner(TourRecord record, List<ReviewAnnotation> findings) -> boolean`: `!record.reviewAnyway() && !blockers(findings).isEmpty()`
  - `StepGate.unmet(TourStep step, StepProgress progress, List<ReviewAnnotation> stepFindings, TourRecord record) -> Optional<Unmet>`: STALE, then CHECK, then TRIAGE (a visible PROPOSED finding on the step), then BLOCKER (a CONFIRMED unresolved BLOCKING finding on the step: message "A confirmed blocking finding is open here: request changes (r) or approve without passing.")
  - `TourValidator.validate(ReviewTour tour, UnifiedDiff diff, List<ReviewAnnotation> findings)`: adds, for every finding with `withheldBy` and triage ≠ DISMISSED: the check exists in the tour; its step's anchors contain the finding's `file`/`startKey`; message `"finding <id> is withheld by check <checkId>, which is not on a step covering src/A.java n42"`
  - `SessionReviewView.Host`: `boolean sendFindingsToAuthor(ReviewScope scope, List<ReviewAnnotation> findings);`

UI rules:
- Step panel triage section (in `extraSections()`): for each visible finding on the current step whose triage is PROPOSED: title, severity, "Confirm" / "Dismiss…" (reason) / "Not sure" buttons → `host.setTriage`. When dismissing a finding that has `withheldBy`, also `host.updateTour(scope, r -> r.withProgress(p.withCheck(StepGrading.voided(p.check(checkId)))))`.
- After a check that withholds a finding is answered (right or wrong), the finding becomes visible (by `hidden(...)`) and appears in the triage section with the line "The agent found this here:" and a button that reveals its line in the column.
- An override (`StepHost.override`) reveals withheld findings by construction (`hidden` checks OVERRIDDEN).
- Banner: when `needsBanner(...)`, the step panel shows "The agent proposes N blocking problems." with one row per blocker (reveal-line button + Confirm/Dismiss), then **Send back to the author** (enabled when at least one blocker is CONFIRMED → `host.sendFindingsToAuthor(scope, confirmedBlockers)`; on true, `host.updateTour(scope, r -> r.withShelved(true))` and the outline shows "Shelved — waiting for the author's changes") and **Review anyway** (`r.withReviewAnyway(true)`). Dismissing every blocker removes the banner.
- Margin and pins filter out `TourFindings.hidden(f, record)` whenever a tour exists, in both modes.
- Switch `SessionReviewView.passCurrentStep` (Task 10) to the 4-argument `StepGate.unmet(step, progress, TourFindings.onStep(step, host.findings(scope), AnchorIndex.of(reviewDiff)), record)`, and have `focusUnmet` scroll the step panel to the triage section for TRIAGE and show the BLOCKER message for BLOCKER.

`MainWorkspace.ReviewHost.sendFindingsToAuthor`: read `ReviewHost.askAgentToFix`; extract its prompt-building and sending body into a private helper taking `(ReviewScope scope, String heading, List<ReviewAnnotation> findings)` and call it from both; mark the sent findings SENT the same way `askAgentToFix` does.

- [ ] **Step 1: Write the failing pure tests** (`TourFindingsTest`): `onStep` keeps a finding anchored inside the step and drops one outside; `hidden` is true for a withheld finding before its check is answered, false after a wrong answer (attempt 1), false after the step is OVERRIDDEN, false once DISMISSED; `blockers` ignores DISMISSED and resolved; `StepGate.unmet` returns TRIAGE for a visible PROPOSED finding after checks pass, BLOCKER for a CONFIRMED blocker, empty when both are triaged; `TourValidator.validate(tour, diff, findings)` rejects a withheld finding whose check is on a step that does not cover its line, and accepts it when it is. Use `TourFixtures` and the 22-argument `ReviewAnnotation` constructor.

- [ ] **Step 2: Run to verify failure**, then implement `TourFindings`, the `StepGate` overload and the validator overload (the 2-argument `validate` delegates with `List.of()`), and switch `McpToolRouter.reviewTour` to `TourValidator.validate(tour, diff, context.findingsOf(scope.id()))`. Also: in `review_finding`, when a tour exists and a finding names `withheldBy`, run the same per-finding rule against the stored tour and reject on failure.

Run: `./gradlew :app:test --tests "app.drydock.review.tour.*" --tests "app.drydock.mcp.*" --offline`
Expected: all pass.

- [ ] **Step 3: Write the failing TestFX test** (`ReviewTourTriageTest`, extends `ReviewTourFixture`): (a) a QUESTION finding on `src/guards.h n1` withheld by `c1` is not shown in the step panel or as a pin before answering; pressing `2` shows it with "The agent found this here:"; (b) answering `c1` wrong (press `1`) reveals the withheld finding; dismissing it with a reason voids `c1` (status VOIDED), and `a` then passes the step once the alternate is no longer required; (c) a PROPOSED BLOCKING finding shows the banner instead of the step; "Review anyway" removes it; (d) a CONFIRMED BLOCKING finding on `s1` makes `a` leave the step unpassed after the check is answered.

- [ ] **Step 4: Implement the UI rules above**, then run:

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*" --offline`
Expected: all pass.

- [ ] **Step 5: Commit**

Subject: `Review tour: a bug the agent found becomes the reviewer's check, and blocking ones stop the tour`. Body: the absence/risk (announcing bugs removes the moment a reviewer proves they read the code; hiding them would make the tool rubber-stamp); withheld findings, reveal-as-triage, dismissal voids the check, override reveals, banner with send-back/review-anyway, gate on triage and confirmed blockers, validator rules; verification; not covered: send-back is exercised against `FakeReviewHost`, not a live agent (Task 22). `Co-Authored-By` trailer.

---

## Phase D — Free-text answers judged by the agent

### Task 13: `review_check`, and a queue that asks the agent one answer at a time

**Read first:** spec §8 "RISK grading is pulled, not pushed". Recon: prompts go through `ReviewHost.sendToBoundSession(scope, prompt)` (MainWorkspace:2601); `dispatchRecheck` refuses exited tabs (`isProcessExited()`); `WorkspaceViewModel.activityOf(ManagedSessionId)` returns `SessionActivity` {UNKNOWN, IDLE, BUSY, NEEDS_ATTENTION}; Codex and Pi always report UNKNOWN.

**Constraints:** the prompt carries only the check id (the answer text is read by the agent through `review_state`); one request in flight per view; BUSY → wait and retry every 5 s; UNKNOWN or IDLE → send; 3-minute timeout → AGENT_UNAVAILABLE; a verdict whose record fingerprint differs from the current diff's is dropped; timers are `PauseTransition`s stopped in `close()`; the queue is FX-confined.

**Files:**
- Modify: `app/src/main/java/app/drydock/review/ReviewInstructions.java` (`forRiskCheck`)
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (`review_check` descriptor, dispatch, implementation; `review_state` adds `awaitingAgent`)
- Modify: `app/src/main/java/app/drydock/mcp/TourStateJson.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpServer.java` (`AGENT_WRITE_TOOLS` += `review_check`)
- Create: `app/src/main/java/app/drydock/ui/review/RiskCheckQueue.java`
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (host methods; enqueue on submit/retry; feed tour changes to the queue)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java`, `FakeReviewHost.java`
- Modify: `app/src/test/java/app/drydock/mcp/McpToolRouterReadTest.java` (tool list, required args)
- Test: extend `app/src/test/java/app/drydock/mcp/McpToolRouterTourTest.java`
- Test: `app/src/test/java/app/drydock/ui/review/RiskCheckQueueTest.java`
- Test: extend `app/src/test/java/app/drydock/review/ReviewInstructionsTest.java`

**Interfaces:**
- Produces:
  - `ReviewInstructions.forRiskCheck(String scopeId, String checkId)`: one line — `"For review handle <scopeId>, call review_state and read the reviewer's answer to check <checkId> under tour.awaitingAgent; judge it against the code and call review_check with verdict holds, partly or doesNotHold and a one-line reason."`
  - Tool `review_check` (args `scopeId`, `checkId`, `verdict`, `reason`) → `{scopeId, checkId, status}` or `{scopeId, checkId, dropped:true, reason}`; errors: unknown check, check not awaiting a verdict, bad verdict word
  - `review_state.tour.awaitingAgent`: `[{stepId, checkId, prompt, answer}]` for checks in AWAITING_AGENT
  - `SessionReviewView.Host`: `boolean dispatchRiskCheck(ReviewScope scope, String checkId);`, `SessionActivity agentActivity(ReviewScope scope);`
  - `final class RiskCheckQueue` with `RiskCheckQueue(Dispatcher dispatcher)`, `void enqueue(String scopeId, String checkId)`, `void onTourChanged(String scopeId, Function<String, Optional<CheckProgress.Status>> statusOfCheck)`, `void close()`, `boolean diagInFlight()`; `interface Dispatcher { SessionActivity activity(String scopeId); boolean dispatch(String scopeId, String checkId); void timedOut(String scopeId, String checkId); }`; package-private `Duration busyRetry` and `Duration timeout` fields settable by tests

- [ ] **Step 1: Write the failing router tests** (append to `McpToolRouterTourTest`): post a covering tour whose `c1` alternate is a RISK check; put the record through `context.putTour(record.withProgress(... StepGrading.submitRisk(...)))` to make `c2` AWAITING_AGENT with answer "an empty list"; then:
  - `review_state` includes `tour.awaitingAgent[0].answer == "an empty list"`;
  - `review_check` with `holds` stores PASSED and returns `status: "PASSED"`;
  - `review_check` with `nonsense` throws naming the allowed words;
  - `review_check` on a check that is OPEN throws "is not awaiting a verdict";
  - `aRiskVerdictForAnOldFingerprintIsDropped`: change `context.reviewDiff` to a different diff after submitting the answer; `review_check` returns `dropped: true` and the stored progress is still AWAITING_AGENT.

- [ ] **Step 2: Run to verify failure**, then implement:

```java
private JsonValue reviewCheck(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
    requireLiveSession(caller);
    JsonObject args = asObject(arguments);
    ReviewScope scope = requireScope(caller, args);
    String checkId = requiredStringArg(args, "checkId");
    String rawVerdict = requiredStringArg(args, "verdict");
    StepGrading.RiskVerdict verdict = StepGrading.RiskVerdict.fromWire(rawVerdict).orElseThrow(() ->
            new McpToolException("verdict must be holds, partly or doesNotHold; got " + rawVerdict));
    String reason = PromptSafety.checkInboundText(requiredStringArg(args, "reason"), "review_check.reason");
    TourRecord record = context.tourOf(scope.id())
            .orElseThrow(() -> new McpToolException("scope " + scope.id() + " has no tour"));
    TourStep step = record.tour().stepOfCheck(checkId)
            .orElseThrow(() -> new McpToolException("no check " + checkId + " in the tour"));
    TourCheck check = step.check(checkId).orElseThrow();
    CheckProgress progress = record.progress(step.id()).check(check.id());
    if (progress.status() != CheckProgress.Status.AWAITING_AGENT) {
        throw new McpToolException("check " + checkId + " is not awaiting a verdict (it is " + progress.status() + ")");
    }
    String current = TourFingerprint.of(context.reviewDiff(scope));
    if (!current.equals(record.tour().diffFingerprint())) {
        return JsonObject.empty().put("scopeId", new JsonString(scope.id()))
                .put("checkId", new JsonString(checkId))
                .put("dropped", new JsonBoolean(true))
                .put("reason", new JsonString("the diff changed since this answer was given; verdict dropped"));
    }
    CheckProgress next = StepGrading.applyRiskVerdict(check, progress, verdict, reason);
    context.putTour(record.withProgress(record.progress(step.id()).withCheck(next)));
    return JsonObject.empty().put("scopeId", new JsonString(scope.id()))
            .put("checkId", new JsonString(checkId))
            .put("status", new JsonString(next.status().name()));
}
```

(If `PromptSafety.checkInboundText` returns `void`, call it and then use the argument.) Descriptor: `"review_check"`, description "Judges the reviewer's free-text answer to a risk check, read from review_state tour.awaitingAgent. Verdict holds, partly or doesNotHold, with a one-line reason the reviewer will see.", required `scopeId, checkId, verdict, reason`. Add to `AGENT_WRITE_TOOLS` and both pinned lists in `McpToolRouterReadTest`. `TourStateJson` adds `awaitingAgent` (the offered version's prompt and the `riskAnswer`). Add `ReviewInstructions.forRiskCheck` and a test asserting it names the check id and `review_check`, contains no newline, and does not contain the answer text.

Run: `./gradlew :app:test --tests "app.drydock.mcp.*" --tests "app.drydock.review.ReviewInstructions*" --offline`
Expected: all pass.

- [ ] **Step 3: Write the failing queue test** (`RiskCheckQueueTest`, a TestFX `ApplicationTest` because the queue uses `PauseTransition`): a fake `Dispatcher` recording calls with a settable activity.
  - IDLE: `enqueue("rs","c1")`, `enqueue("rs","c2")` → only `c1` dispatched; `onTourChanged` reporting `c1` PASSED → `c2` dispatched.
  - BUSY with `busyRetry = 50ms`: nothing dispatched; switch to IDLE; within 1 s `c1` is dispatched.
  - `timeout = 100ms`: after dispatch and no change, `timedOut("rs","c1")` is called and the next request is dispatched.
  - enqueueing the same check twice dispatches once.
  - `dispatch` returning false → `timedOut` is called immediately (the host marks it unavailable).
  - `close()` stops timers: no dispatch happens after close.

- [ ] **Step 4: Implement `RiskCheckQueue`**

```java
package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.review.tour.CheckProgress;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Asks the agent to judge free-text answers one at a time (spec §8).
 *
 * <p>FX-confined. A request is sent only when the agent is not known to be
 * busy (Codex and Pi report UNKNOWN and are sent to at once); one is in
 * flight at a time; a request with no verdict after {@link #timeout} is
 * handed back as unavailable so the reviewer can retry or override.</p>
 */
final class RiskCheckQueue {

    interface Dispatcher {
        SessionActivity activity(String scopeId);
        boolean dispatch(String scopeId, String checkId);
        void timedOut(String scopeId, String checkId);
    }

    private record Request(String scopeId, String checkId) { }

    private final Dispatcher dispatcher;
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Set<Request> known = new HashSet<>();
    private Request inFlight;
    private boolean closed;
    Duration busyRetry = Duration.seconds(5);
    Duration timeout = Duration.minutes(3);
    private final PauseTransition retryTimer = new PauseTransition();
    private final PauseTransition timeoutTimer = new PauseTransition();

    RiskCheckQueue(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
        retryTimer.setOnFinished(event -> pump());
        timeoutTimer.setOnFinished(event -> {
            Request expired = inFlight;
            inFlight = null;
            if (expired != null) {
                known.remove(expired);
                dispatcher.timedOut(expired.scopeId(), expired.checkId());
            }
            pump();
        });
    }

    void enqueue(String scopeId, String checkId) {
        Request request = new Request(scopeId, checkId);
        if (closed || !known.add(request)) {
            return;
        }
        queue.addLast(request);
        pump();
    }

    /** Clears the in-flight request once its check is no longer awaiting the agent. */
    void onTourChanged(String scopeId, Function<String, Optional<CheckProgress.Status>> statusOfCheck) {
        if (inFlight == null || !inFlight.scopeId().equals(scopeId)) {
            return;
        }
        Optional<CheckProgress.Status> status = statusOfCheck.apply(inFlight.checkId());
        if (status.isEmpty() || status.get() != CheckProgress.Status.AWAITING_AGENT) {
            timeoutTimer.stop();
            known.remove(inFlight);
            inFlight = null;
            pump();
        }
    }

    void close() {
        closed = true;
        retryTimer.stop();
        timeoutTimer.stop();
        queue.clear();
    }

    boolean diagInFlight() {
        return inFlight != null;
    }

    private void pump() {
        if (closed || inFlight != null || queue.isEmpty()) {
            return;
        }
        Request next = queue.peekFirst();
        if (dispatcher.activity(next.scopeId()) == SessionActivity.BUSY) {
            retryTimer.setDuration(busyRetry);
            retryTimer.playFromStart();
            return;
        }
        queue.removeFirst();
        if (!dispatcher.dispatch(next.scopeId(), next.checkId())) {
            known.remove(next);
            dispatcher.timedOut(next.scopeId(), next.checkId());
            pump();
            return;
        }
        inFlight = next;
        timeoutTimer.setDuration(timeout);
        timeoutTimer.playFromStart();
    }
}
```

(Check the actual package of `SessionActivity` — recon says `domain.SessionActivity`, i.e. `app.drydock.domain.SessionActivity`.)

- [ ] **Step 5: Wire the queue into the view and hosts**

`SessionReviewView`: field `private final RiskCheckQueue riskQueue = new RiskCheckQueue(new RiskDispatcher());` where `RiskDispatcher` maps scope id → selected scope and calls `host.agentActivity`, `host.dispatchRiskCheck`, and on `timedOut` `host.updateTour(scope, r -> r.withProgress(p.withCheck(StepGrading.markAgentUnavailable(p.check(id)))))`. `StepHost.submitRisk` → update progress then `riskQueue.enqueue(scope.id(), checkId)`; `StepHost.retryRisk` → `StepGrading.retryRisk` then enqueue. `refreshReviewState()` (tour present) → `riskQueue.onTourChanged(scope.id(), checkId -> record.tour().stepOfCheck(checkId).map(s -> record.progress(s.id()).check(checkId).status()))`. `close()` → `riskQueue.close()`.

`MainWorkspace.ReviewHost.dispatchRiskCheck`: like `dispatchRecheck` — refuse when no bound session or `isProcessExited()`; `sendToBoundSession(scope, ReviewInstructions.forRiskCheck(scope.id(), checkId))`; log INFO. `agentActivity`: `scope.sessionId().map(viewModel::activityOf).orElse(SessionActivity.UNKNOWN)` (use the view model field `MainWorkspace` already holds). `FakeReviewHost`: record dispatches in a list; `agentActivity` returns a settable field (default IDLE).

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*" --tests "app.drydock.mcp.*" --offline`
Expected: all pass.

- [ ] **Step 7: Commit**

Subject: `Review tour: a free-text answer is judged by the agent, one at a time, and never against a changed diff`. Body: the absence; pulled grading (only the check id is typed into the agent's session, the answer is read through `review_state`), one in flight, busy retry, 3-minute timeout to "agent unavailable", stale-fingerprint drop; verification; not covered: a live agent judging answers (Task 22). `Co-Authored-By` trailer.

---

## Phase E — Navigation shared with the Explorer

### Task 14: Peek, trail and search move to a shared package (no behaviour change)

**Read first:** spec §5 "Shared components". Recon: `PeekLayer`, `TrailBar`, `SearchRail`, `FileRailModel` are package-private in `ui.explorer`; `NavigationTrail`, `SymbolPeekService`, `SymbolPeek`, `ExplorerTrailStore` are public; `SearchRail` depends on `FileRailModel` (move it too) and `ExplorerFinding` (public, stays); `FileViewer` and `SessionExplorerView` stay in `ui.explorer`.

**Constraints:** a pure move. No behaviour change, no new features, no renames beyond the package. This is the refactor commit of a refactor-then-change pair (AGENTS.md); the commit message asserts no behaviour change. No FQCNs: fix imports.

**Files:**
- Move (`git mv`) to `app/src/main/java/app/drydock/ui/nav/`: `PeekLayer.java`, `NavigationTrail.java`, `TrailBar.java`, `SearchRail.java`, `FileRailModel.java`, `SymbolPeekService.java`, `SymbolPeek.java`, `ExplorerTrailStore.java`
- Move matching tests to `app/src/test/java/app/drydock/ui/nav/`: `NavigationTrailTest`, `ExplorerTrailStoreTest`, `FileRailModelTest`, `SearchRailViewTest`, `SymbolPeekResolutionTest`
- Modify: every importer (`FileViewer`, `SessionExplorerView`, `MinimapTicks`, `MainWorkspace`, `DrydockApplication`, tests such as `SessionExplorerViewTest`, `MinimapTicksTest`)

**Interfaces:** after the move these become `public`: `PeekLayer` (class, constructor, `MAX_DEPTH`, `setOnPromote`, `setOnAsk`, `setOnStackFull`, `setOnChanged`, `setAgentAvailable`, `depth`, `isOpen`, `top`, `push`, `popOne`, `clear`, `toggleUsages`, `promoteTop`, `askTop`); `TrailBar` (class, constructor, `setOnGoTo`, `setOnStep`, `setOnTogglePin`, `render`); `SearchRail` (class, constructor, `FileOpener`, and every method `SessionExplorerView` calls: `setOnQueryChanged`, `setChangedLines`, `setDiffFileTest`, `setOnCollapseRequested`, `setOnExpandRequested`, `setFindings`, `setOpenFile`, `refresh`, `setSearch`, `focusSearch`, `focusSearchWhenExpanded`, `toggleScope`, `cycleSort`, `showCollapsed`, `showExpanded`); `SymbolPeekService.identifierAt`, `readExcerpt`, `isWholeWord`, `scoreDeclaration` (tests and `FileViewer` use them). `NavigationTrail.Waypoint.withLine/withPinned` stay package-private (only `NavigationTrail` uses them).

- [ ] **Step 1: Move the files with `git mv`, change their `package` lines to `app.drydock.ui.nav`, and fix imports everywhere** (`grep -rln "ui.explorer.\(PeekLayer\|NavigationTrail\|TrailBar\|SearchRail\|FileRailModel\|SymbolPeekService\|SymbolPeek\|ExplorerTrailStore\)" app/src` plus same-package users in `ui/explorer` that had no import). Widen visibility exactly as listed.
- [ ] **Step 2: Compile and run the moved and dependent tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.nav.*" --tests "app.drydock.ui.explorer.*" --offline`
Expected: all pass with the same test count as before the move (record the count from a run on the parent commit first).

- [ ] **Step 3: Commit**

Subject: `Peek, trail and search live in a shared package, unchanged`. Body: why (Review is about to reuse them; copying them would let the two drift); a pure move plus visibility widening; "No behaviour change." stated explicitly; verification with the before/after test counts. `Co-Authored-By` trailer.

---

### Task 15: Waypoints remember a line key, and Review keeps its own trail

**Read first:** spec §5 ("A waypoint records a line key as well as a file line"); recon trap: `DrydockApplication:243` calls `explorerTrailStore.retain(sessionIds)` at startup and would delete a Review trail stored under any other key.

**Constraints:** lenient decode (absent `lineKey` → empty); the 4-argument `Waypoint` constructor stays for existing callers; `retain` keeps `review:<id>` keys whose session is live.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/nav/NavigationTrail.java`
- Modify: `app/src/main/java/app/drydock/ui/nav/ExplorerTrailStore.java`
- Test: extend `app/src/test/java/app/drydock/ui/nav/NavigationTrailTest.java` and `ExplorerTrailStoreTest.java`

**Interfaces:**
- Produces: `record Waypoint(Path file, String label, int line, boolean pinned, Optional<String> lineKey)` plus `Waypoint(Path file, String label, int line, boolean pinned)` delegating with `Optional.empty()`; `boolean NavigationTrail.push(Path file, String label, int line, Optional<String> lineKey)` (the 3-argument `push` delegates); `static String ExplorerTrailStore.reviewKey(String sessionKey)` → `"review:" + sessionKey`; `retain` keeps a key `k` when `live.contains(k)` or `k.startsWith("review:") && live.contains(k.substring(7))`. JSON: optional `"lineKey"` string per waypoint.

- [ ] **Step 1: Write the failing tests**: a waypoint with `lineKey` round-trips through the store; a schema-1 file without `lineKey` decodes with `Optional.empty()`; `retain(List.of("abc"))` keeps `"abc"` and `"review:abc"` and drops `"review:zzz"`; `push(file, "Step 3", 12, Optional.of("o12"))` stores the key and re-pushing the same file at the cursor only updates the line (existing browser semantics unchanged).
- [ ] **Step 2: Run to verify failure**, implement, run:

Run: `./gradlew :app:test --tests "app.drydock.ui.nav.*" --offline`
Expected: all pass.

- [ ] **Step 3: Commit**

Subject: `A trail waypoint remembers its line key, and a session's Review trail survives restarts`. Body: removed rows have no post-image line; `retain` would have deleted `review:` keys on every launch; verification. `Co-Authored-By` trailer.

---

### Task 16: Review navigates like the Explorer: peek, trail, search, back to the step

**Read first:** spec §5 "Navigation". Recon: `handleShortcut` returns false for shortcut-modified keys and for `TextInputControl` targets; Esc is owned by the scene filter and reaches Review through `unwindOne()`; `⌘[`/`⌘]` are handled in `DrydockApplication:1009-1022` via `mainWorkspace.navigateExplorerTrail` with a fall-through to session-tab switching at the trail's ends; the Explorer's peek keys live in `SessionExplorerView.installShortcuts` (:334-398).

**Constraints:** an open peek owns `⏎`, `u`, `a` (checked before every other Review binding) and Esc closes one peek via `unwindOne()`; file reads for location peeks and symbol peeks run off the FX thread (`SymbolPeekService` already does); waypoints are added by step changes, promoted peeks and search results, never by plain peeks; the trail is saved under `ExplorerTrailStore.reviewKey(sessionKey)`; all new keys in the overlay, the parity set and the replayable set.

**Design note ("↩ back to step" pill):** the spec shows the pill "whenever the viewport is off the step's rows". Measuring a virtualized `ListView`'s visible rows is fragile, so the pill shows after any navigation that leaves the step (promoted peek, trail move, search result, anchor of a different step) and hides on `b`, a step change or `goToAnchor`. Say so in the commit.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/nav/SymbolPeekService.java` (`peekAt(Path relativePath, int line)`)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (`setSymbolClickHandler`)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java`
- Modify: `app/src/main/java/app/drydock/ui/review/TourOutline.java` (search tab content)
- Modify: `app/src/main/java/app/drydock/ui/review/StepPanel.java` (back-to-step pill)
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java` (`navigateReviewTrail`)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`navigateReviewTrail`, `ReviewHost.navigation`, replayable keys)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java:1009-1022`
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java`, `app/src/test/java/app/drydock/ui/ShortcutsOverlayParityTest.java`, `FakeReviewHost.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewTourNavigationTest.java`

**Interfaces:**
- Produces:
  - `record ReviewNavigation(Path root, SessionSearchService search, ExplorerTrailStore trails, String sessionKey)` (in `ui.review`)
  - `SessionReviewView.Host`: `Optional<ReviewNavigation> navigation(ReviewScope scope);`, `boolean askAgentAboutPeek(ReviewScope scope, SymbolPeek peek);`
  - `SymbolPeekService.peekAt(Path relativePath, int line) -> CompletableFuture<Optional<SymbolPeek>>` (excerpt around `line`, `resolvedDeclaration=false`, title `relativePath + ":" + line`)
  - `ReviewDiffColumn.setSymbolClickHandler(Predicate<String> handler)` — when it returns true the diff-local lens is not opened
  - `SessionReviewView`: `boolean navigateTrail(int direction)`, `void diagPushPeek(SymbolPeek peek)`, `boolean diagPeekOpen()`, `List<NavigationTrail.Waypoint> diagTrail()`
  - `MainWorkspace.navigateReviewTrail(int)`, `OpenSessionTab.navigateReviewTrail(int)` (gated on the Review sub-tab)

Behaviour:
- Layout: the diff column sits in a `StackPane(diffColumn, peekLayer)` that `bodyFor` returns where it returned `diffColumn`; the `TrailBar` is the last child of the centre `VBox` (below the verdict bar), shown in both modes.
- Symbol click (TOUR mode): `peekAt(symbol)` via `new SymbolPeekService(nav.root(), nav.search()).peek(symbol, changedLinesOfReviewDiff())` → `Platform.runLater(() -> peek.ifPresentOrElse(peekLayer::push, () -> stepPanel.showTransient("Nothing found for " + symbol)))`. `changedLinesOfReviewDiff()` maps each file's relative `Path` to the new line numbers of its ADD rows. DIFF mode keeps today's lens.
- Peek keys (both modes, first thing in `handleShortcut` after the modifier guard): if `peekLayer.isOpen()`: `ENTER` → `peekLayer.promoteTop()`, `U` → `toggleUsages()`, `A` → `askTop()`; consume. `unwindOne()` first does `if (peekLayer.isOpen()) { peekLayer.popOne(); return true; }`.
- Promote: if the peek's file is in the rendered diff → `diffColumn.revealLine(file, "n" + line)` and push a waypoint labelled with the file name; otherwise `host.openInExplorer(scope, peek.file(), peek.startLine())`. Ask: `host.askAgentAboutPeek(scope, peek)` (MainWorkspace: reuse whatever the Explorer's `FileViewer` `setOnAsk` handler sends — find it from `SessionExplorerView`/`FileViewer` wiring and extract a shared helper).
- Waypoints: `selectStep(id)` pushes `push(Path.of(anchor.file()), "Step " + number, lineOf(anchor.startKey()), Optional.of(anchor.startKey()))`; promote and search results push too; after every push, save `new ExplorerTrailStore.Trail(trail.waypoints(), trail.cursor())` under `reviewKey(nav.sessionKey())`; on first render for a scope, `restore` from the store.
- `navigateTrail(direction)`: move the trail; reveal the waypoint (`lineKey` if present, else `"n" + line`) when its file is in the rendered diff, otherwise open a location peek; if the waypoint label starts with `"Step "`, select that step without pushing. Returns false at the ends so `DrydockApplication` falls through to tab switching, as the Explorer does.
- `DrydockApplication`: in both bracket branches, `if (!mainWorkspace.navigateExplorerTrail(d) && !mainWorkspace.navigateReviewTrail(d)) { select…SessionTab(); }`.
- `b` → reveal the current step's first anchor and hide the pill; `.`/`,` → next/previous anchor of the current step (wrapping), revealing it.
- Search tab: `outline.setSearchContent(new SearchRail(nav.root(), nav.search(), opener))` where `opener` reveals and pushes when the file is in the rendered diff, else opens `peekAt(relativePath, line)` in the peek layer; `⇧D` (TOUR mode) → `searchRail.toggleScope()`; `/` is not bound in Review (it is the Explorer's).

- [ ] **Step 1: Write the failing TestFX tests** (`ReviewTourNavigationTest`, extends `ReviewTourFixture`): with `host.navigation` empty, use `view.diagPushPeek(new SymbolPeek(...))` to open a peek; then
  - pressing `U` toggles usages on the peek, not "undo the step" (assert the step decision is unchanged and the peek is still open);
  - pressing `Enter` with a peek open does not submit (assert `host.submittedScopes` is empty);
  - Esc via `view.unwindOne()` closes the peek;
  - pressing `]` pushes a "Step 2" waypoint; `view.navigateTrail(-1)` returns to step 1 without pushing a new waypoint;
  - pressing `.` moves to the next anchor of a two-anchor step (use a fixture variant whose `s1` has two anchors) and `b` returns to the first;
  - `diagTrail()` is unchanged after opening and closing a plain peek.
- [ ] **Step 2: Run to verify failure**, implement the behaviour above, update the overlay with

```java
{"Back to the current step", "b"},
{"Next / previous range in the step", ". / ,"},
{"Search scope: change / worktree (tour)", "⇧D"},
{"Back / forward along the trail", "⌘[ / ⌘]"},
{"In a peek: open / usages / ask the agent", "⏎ / u / a"},
```

add `"b", ".", ",", "⇧D"` to the parity test's bound set and exclude `"⌘[ / ⌘]"` and `"⏎ / u / a"` as not-the-board's-own bindings (mirroring the Explorer test), and add `KeyCode.B, KeyCode.PERIOD, KeyCode.COMMA` to `REPLAYABLE_OFF_REVIEW_SUBTREE`.
- [ ] **Step 3: Run the tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*" --tests "app.drydock.ui.nav.*" --tests "app.drydock.ui.ShortcutsOverlayParityTest" --offline`
Expected: all pass.

- [ ] **Step 4: Commit**

Subject: `Review tour: peek, a trail and search let the reviewer leave a step and come back`. Body: the absence (checking a caller meant leaving Review for the Explorer); peek over the column that owns `⏎`/`u`/`a`, waypoints only for real jumps, `⌘[`/`⌘]` with the Explorer's fall-through, `b`/`.`/`,`, search tab; the pill simplification and why; verification; not covered: robot input is blocked in diag runs, so hover/click behaviour beyond TestFX needs a human (Task 22). `Co-Authored-By` trailer.

---

## Phase F — Impact on callers and callees

### Task 17: Callers on unchanged lines of changed files are listed, and an unavailable scan says why

**Read first:** spec §6 "Measured". Recon: `OutOfDiffFanIn.parse` drops every match in a changed file at :252-254; `Result(Map<String, List<Occurrence>> bySymbol, boolean unavailable)` has 5 constructor callers outside the class; `ReadingPath.fanInByFile` ranks reading order by this count, so its semantics must not change.

**Constraints:** `ReadingPath` keeps counting only matches outside changed files (filter on the new flag) so the reading order is unchanged; the compatibility constructors keep existing callers compiling; `OutOfDiffFanInTest.matchesInChangedFilesAreExcluded` is **modified**, not deleted: it becomes `matchesOnChangedLinesAreExcluded` plus a new `unchangedLinesOfChangedFilesAreKeptAndFlagged` — this is the behaviour change the approved spec asks for; mention it in the commit body.

**Files:**
- Modify: `app/src/main/java/app/drydock/review/OutOfDiffFanIn.java`
- Modify: `app/src/main/java/app/drydock/review/ReadingPath.java` (`fanInByFile` filters `!inChangedFile()`)
- Modify: `app/src/test/java/app/drydock/review/OutOfDiffFanInTest.java`
- Test: `app/src/test/java/app/drydock/review/ReadingPathTest.java` (existing; must still pass unchanged)

**Interfaces:**
- Produces:
  - `record Occurrence(String file, int line, String text, boolean inChangedFile)` + `Occurrence(String file, int line, String text)` → `false`
  - `record Result(Map<String, List<Occurrence>> bySymbol, Optional<String> unavailableReason)` with `boolean unavailable()` (= reason present) + `Result(Map, boolean unavailable)` → reason `"unavailable"` when true
  - `static Result scan(Path worktree, ChangeGraph graph, Map<String, Set<Integer>> changedNewLinesByFile)` (the `Set<String>` overload delegates with every file mapped to all lines — i.e. drop whole files — so any other caller keeps its behaviour)
  - `static List<Occurrence> parse(String stdout, Map<String, Set<Integer>> changedNewLinesByFile)`: a match on a changed new line is dropped; a match elsewhere in a changed file is kept with `inChangedFile = true`
  - `forScope` builds the map from the diff's ADD rows and reports reasons: `"no checkout to search"`, `"git grep timed out after 30 s"`, `"git grep failed: <stderr excerpt>"`, `"git grep could not run: <message>"`

- [ ] **Step 1: Update/add the tests** (`parse` cases use the existing NUL-joined fixture strings; the timeout reason can be tested through `Result` construction, the `scan` happy path through the existing temp-repo helper).
- [ ] **Step 2: Run to verify failure**, implement, run:

Run: `./gradlew :app:test --tests "app.drydock.review.OutOfDiffFanInTest" --tests "app.drydock.review.ReadingPathTest" --tests "app.drydock.review.*" --offline`
Expected: all pass; `ReadingPathTest` unchanged.

- [ ] **Step 3: Commit**

Subject: `Callers on unchanged lines of a changed file are found, and a failed caller search says why`. Body: the defect (an unedited call site in an edited file — where a signature change usually breaks — was never listed; timeout and failure looked identical); line-level exclusion with an `inChangedFile` flag so the reading order (which ranks by out-of-change fan-in) is unchanged; the modified test and why; verification. `Co-Authored-By` trailer.

---

### Task 18: Step impact, the signature-changed signal, the `UsageProvider` seam and `review_scope` impact

**Read first:** spec §6 (all of it), §8 (`review_scope` `impact` include). Recon: `ChangeGraph` and `SymbolScan.Symbol(name, path, hunk, declaration, onChangedLine)` carry no line numbers; `ChangeGraph.declarationsIn(Hunk)`, `referencesIn(Hunk)`, `hunksDeclaring(symbol)`, `hunksReferencingSymbol(symbol)` exist; `computeSections` in the router caches sections, not the graph.

**Constraints:** computation off the FX thread; `Provenance.RESOLVED` added with `label()`/`styleClass()` like the others; callees capped at 8 per step; the `review_scope` include parser generalised (comma-separated tokens) without changing the `sections` behaviour; graph built once per diff and shared by `sections` and `impact` in the router cache.

**Files:**
- Modify: `app/src/main/java/app/drydock/review/SymbolScan.java` (`Symbol` gains `String lineKey`; compatibility constructor with `""`)
- Modify: `app/src/main/java/app/drydock/review/ChangeGraph.java` (`record DeclarationSite(String name, String file, String lineKey)`, `SortedSet<DeclarationSite> declarationSites()`)
- Modify: `app/src/main/java/app/drydock/review/Provenance.java` (`RESOLVED("resolved")`)
- Create: `app/src/main/java/app/drydock/review/UsageProvider.java`
- Create: `app/src/main/java/app/drydock/review/tour/StepImpact.java`
- Create: `app/src/main/java/app/drydock/ui/nav/LexicalUsageProvider.java`
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (include parsing, graph cache, `impact`)
- Create: `app/src/main/java/app/drydock/mcp/ImpactJson.java`
- Modify: `app/src/main/java/app/drydock/review/ReviewInstructions.java` (`include=sections,impact`)
- Test: `app/src/test/java/app/drydock/review/tour/StepImpactTest.java`, `app/src/test/java/app/drydock/review/ChangeGraphDeclarationSitesTest.java`, `app/src/test/java/app/drydock/mcp/McpToolRouterImpactTest.java`

**Interfaces:**
- Produces:
  - `interface UsageProvider { CompletableFuture<Optional<Usage>> declaration(String symbol); CompletableFuture<List<Usage>> usages(String symbol); record Usage(String file, int line, String text, Provenance provenance) { } }`
  - `LexicalUsageProvider(SymbolPeekService peeks, Map<Path, Set<Integer>> changedLines) implements UsageProvider` — `declaration` from `peek(...)`'s `relativePath/startLine` (provenance MEASURED), `usages` from `peek(...).occurrences()` (MEASURED)
  - `record StepImpact(List<Caller> calledFromOutside, List<InChange> inChange, List<String> calleesToResolve, List<SignatureFlag> signatureFlags, Optional<String> unavailableReason)` with `record Caller(String symbol, String file, int line, String text, boolean inChangedFile)`, `record InChange(String symbol, Direction direction, String otherStepId, int otherStepNumber)` (`enum Direction { CALLS, CALLED_BY }`), `record SignatureFlag(String symbol, String file, String lineKey, int uneditedCallSites)`
  - `static StepImpact StepImpact.of(TourStep step, ReviewTour tour, UnifiedDiff reviewDiff, ChangeGraph graph, OutOfDiffFanIn.Result fanIn)`:
    - step symbols = names in `graph.declarationSites()` whose `file`/`lineKey` lies in one of the step's anchors (via `AnchorIndex.of(reviewDiff)`)
    - `calledFromOutside` = `fanIn.bySymbol().get(symbol)` for each step symbol, sorted by file then line
    - `inChange`: for each hunk touched by the step, `graph.referencesIn(hunk)` → CALLS edges to the step owning `graph.hunksDeclaring(name)`; for each step symbol, `graph.hunksReferencingSymbol(name)` → CALLED_BY edges to the owning step (skip self-step); owner = first step whose progress `hunkDigests` contains that hunk's digest (compute from anchors)
    - `calleesToResolve` = identifiers referenced on the step's changed rows that are not in `graph.changedDeclarations()`, at most 8, most frequent first (use `SymbolWords` rules for identifier extraction, as `SymbolScan` does)
    - `signatureFlags`: for each step symbol whose declaration line is a changed row, the count of `calledFromOutside` occurrences (any file, changed or not, since occurrences on changed lines are already excluded) → a flag when the count is > 0
    - `unavailableReason` = `fanIn.unavailableReason()`
  - `review_scope` with `include` containing `impact` returns `impact: [{symbol, declaredIn:{file, lineKey}, calledFrom:[{file, line, inChangedFile}], signatureChanged:boolean, uneditedCallSites:int}]` (per changed declaration site, not per step: the agent builds steps), `impactUnavailable: "<reason>"` when the scan failed

- [ ] **Step 1: Write failing tests**: `ChangeGraphDeclarationSitesTest` (a hand-built diff declaring `void foo()` on an ADD row `n3` yields a site `foo src/A.java n3`; a declaration on a context row is not a site); `StepImpactTest` (a step anchored over `foo`'s declaration lists its out-of-diff callers from a hand-built `Result`, a CALLED_BY edge to the step owning the referencing hunk, a signature flag with the caller count, the reason when the result is unavailable, callees capped at 8); `McpToolRouterImpactTest` (extends `McpRouterFixture`: `include=impact` returns an `impact` array and builds the graph once — `graphBuilds() == 1` — when called together with `sections`).
- [ ] **Step 2: Run to verify failure**, then implement in this order: `SymbolScan.Symbol.lineKey` (find where `SymbolScan.of(FileDiff)` creates symbols; it iterates lines, so pass `line.lineKey()`), `ChangeGraph.declarationSites()`, `Provenance.RESOLVED` (`styleClass()` → `"provenance-resolved"`), `UsageProvider`, `LexicalUsageProvider`, `StepImpact`, router include parsing (`Set<String> includes(JsonObject args)` replacing `includesSections`), a `GraphCacheEntry(UnifiedDiff diff, ChangeGraph graph, OutOfDiffFanIn.Result fanIn)` cache used by both includes, `ImpactJson`, and the instructions' `include=sections,impact`.

Run: `./gradlew :app:test --tests "app.drydock.review.*" --tests "app.drydock.mcp.*" --offline`
Expected: all pass (existing `McpToolRouterSectionsTest` unchanged, including its `graphBuilds()` counts).

- [ ] **Step 3: Commit**

Subject: `Review tour: each step knows its callers, callees and whether it changed a signature callers still use`. Body: the absence; measured sources, the signature-changed flag and its limits (name matches, not resolved references), the `UsageProvider` seam with the `RESOLVED` provenance reserved for the LSP follow-up, `review_scope impact` sharing one graph build with `sections`; verification. `Co-Authored-By` trailer.

---

### Task 19: The step panel shows the impact, and every entry opens in place

**Read first:** spec §6, §5 (impact in the step panel; peek on click).

**Constraints:** impact computed off the FX thread once per scope/diff alongside the existing graph build (`SessionReviewView` already builds `ChangeGraph` on `SECTION_GRAPH_EXECUTOR` and runs `OutOfDiffFanIn.forScope` at :1603 — reuse those results, do not start a second scan); "Finding callers…" until available; "callers unavailable: <reason>" on failure, never an empty list; callee resolution async via `LexicalUsageProvider`, at most 8, "resolving…" per row until done; every entry is a `Button`.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/StepPanel.java` (impact section in `extraSections()`; TRACE choices with `at` get a peek button)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (compute `StepImpact`, resolve callees, wire clicks to peek/step selection)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewTourImpactTest.java`

**Interfaces:**
- Produces: `StepPanel.showImpact(ImpactView view)` with `record ImpactView(List<ImpactNote> claimed, StepImpact measured, Map<String, Optional<UsageProvider.Usage>> callees, boolean pending)`; `StepPanel.Host` gains `void openLocation(String file, int line)`, `void selectStep(String stepId)`.

Rendering, top to bottom: **Agent notes** (claimed, each `file:line — text`, tag "claimed"); **Signature changed** flags ("declaration changed · N call sites were not edited"); **Called from outside the change** grouped by file (tag "occurrences, not resolved references"; rows flagged `inChangedFile` say "in a changed file"); **In this change** ("→ name · step N" / "← name · step N", click selects that step); **Calls outside the change** (name → `file:line` when resolved, "no declaration found" when empty, "resolving…" while pending, tag "measured"). Clicking a location row calls `host.openLocation(file, line)` → the view opens `SymbolPeekService.peekAt(relativePath, line)` in the peek layer. TRACE choices whose `at` is present get a small "peek" `Button` next to the choice that opens that location the same way.

- [ ] **Step 1: Write the failing TestFX test** (`ReviewTourImpactTest`, extends `ReviewTourFixture` with a fixture diff in which `src/guards.h` declares `foo` on `n1` and `src/guards.cpp` references it): set a fake fan-in result through a diag hook `view.diagSetFanIn(scopeId, OutOfDiffFanIn.Result)` (add it; it replaces the scan result for the scope); assert the panel lists "Called from outside the change" with the fake caller, a "← foo · step 2" in-change row, and a signature flag; set an unavailable result and assert "callers unavailable: git grep timed out after 30 s" and no caller rows; click the in-change row and assert the current step is `s2`.
- [ ] **Step 2: Run to verify failure**, implement, run:

Run: `./gradlew :app:test --tests "app.drydock.ui.review.*" --offline`
Expected: all pass.

- [ ] **Step 3: Commit**

Subject: `Review tour: the step panel shows who calls the step's code and what it calls, each one a click away`. Body per rules; verification; not covered: resolution is lexical (LSP is the follow-up spec). `Co-Authored-By` trailer.

---

## Phase G — Submit, staleness, verification

### Task 20: Comments outside the PR diff go into the review body, and the sheet says what was not verified

**Read first:** spec §7 "Line comments", "Overrides". Recon: `SubmitPlan.of` refuses "line %s is not in this diff" (:~111); `MainWorkspace.openSubmitSheet` (~:2263) → `postReview(...)` → `gitHubReviewService.submit(root, pr, event, summary, plan.comments())`; the body is the summary text only.

**Constraints:** `SubmitPlan` stays pure (the excerpt comes from a supplied function); the existing `of(findings, decisions, index)` overload keeps today's behaviour for its callers; only `postToPr`, unresolved, CONFIRMED findings are considered (Task 11).

**Files:**
- Modify: `app/src/main/java/app/drydock/review/SubmitPlan.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewSubmitSheet.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`openSubmitSheet`, `postReview`)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (pass the line-text lookup and the tour's override summary through `Host.submit`)
- Test: extend `app/src/test/java/app/drydock/review/SubmitPlanTest.java` (or create it if absent), `app/src/test/java/app/drydock/ui/review/ReviewSubmitSheetTest.java`

**Interfaces:**
- Produces:
  - `record SubmitPlan.BodyNote(ReviewAnnotation.Key key, String file, String lineLabel, String excerpt, String body)`
  - `SubmitPlan` gains component `List<BodyNote> bodyNotes` (the existing canonical constructor's callers: grep and pass `List.of()`), `static SubmitPlan of(List<ReviewAnnotation> findings, List<ReviewVerdict.Decision> decisions, DiffIndex index, BiFunction<String, String, Optional<String>> lineText)` — a finding whose start or end key is missing from `index` becomes a `BodyNote` instead of a `Refusal`; other refusals ("span two hunks", "range runs backwards", cross-side) stay refusals
  - `String SubmitPlan.composeBody(String summary)` → summary, then (if notes) a blank line, `"Comments on lines outside this diff:"`, and per note `"- `file:lineLabel` — body"` followed by the excerpt as an indented code line when present
  - `record ReviewSubmitSheet.Unverified(int stepOverrides, int hunkOverrides, long untriagedFindings)` and a constructor overload taking it; the sheet shows "Inline comments (N)", "In the review body (N)" listing each note's `file:line`, and when non-zero "Not verified: 2 steps approved without passing checks · 1 hunk approved in the hunk diff · 3 agent findings not reviewed"
  - `SessionReviewView.Host.submit(ReviewScope, SubmitPlan.DiffIndex, List<ReviewVerdict.Decision>)` stays; the host computes the line-text lookup from the scope's whole-file diff when available (a new `Host` method `Optional<UnifiedDiff> wholeFileDiff(ReviewScope)` is **not** needed: pass a `BiFunction` built by the view from `diffColumn.renderedDiff()` via a new `Host.submit` overload `submit(scope, index, decisions, lineText, unverified)`; keep the old method delegating with `(f, k) -> Optional.empty()` and a zero `Unverified`)

- [ ] **Step 1: Write the failing tests**: `SubmitPlanTest` — a CONFIRMED `postToPr` finding at `n500` (not in the index) becomes a body note with the excerpt from the lookup; `composeBody("LGTM")` contains the note and the excerpt; a finding spanning two hunks is still a refusal; the 3-argument `of` still refuses `n500` (old behaviour). `ReviewSubmitSheetTest` — with one note and an `Unverified(2, 1, 3)` the sheet shows "In the review body (1)" and the "Not verified" line.
- [ ] **Step 2: Run to verify failure**, implement, and change `postReview` to send `plan.composeBody(summary)` as the review body. Run:

Run: `./gradlew :app:test --tests "app.drydock.review.SubmitPlan*" --tests "app.drydock.ui.review.ReviewSubmitSheet*" --tests "app.drydock.ui.review.*" --offline`
Expected: all pass.

- [ ] **Step 3: Commit**

Subject: `Comments on lines outside the PR diff are posted in the review body instead of refused`. Body: the defect (a comment on an unchanged caller — the point of whole-file review — was refused at submit); body notes with excerpts; the sheet shows each comment's route and what was approved without verification; verification; not covered: no live GitHub post in automated tests (Task 22 posts to a real PR only if the user agrees). `Co-Authored-By` trailer.

---

### Task 21: When the diff moves, passed steps carry over and only changed steps are re-issued

**Read first:** spec §3 "Staleness", §8 (`review_tour` with `onlySteps`).

**Constraints:** migration is pure and runs through `TourStore.mutate` (one writer); anchors of kept steps are remapped through `hunkRows` (same digest ⇒ same rows in the same order, so a key maps by position); a step whose anchor endpoint does not map is stale; new hunks are uncovered; the refresh prompt is deduplicated per fingerprint (a `TourRefreshDispatch` claim set, FX-confined, like `RecheckDispatch`); `onlySteps` replaces the named steps, adds new ones, and the merged tour must pass full validation.

**Files:**
- Create: `app/src/main/java/app/drydock/review/tour/TourMigration.java`
- Create: `app/src/main/java/app/drydock/review/tour/TourMerge.java`
- Create: `app/src/main/java/app/drydock/ui/review/TourRefreshDispatch.java`
- Modify: `app/src/main/java/app/drydock/review/ReviewInstructions.java` (`forTourRefresh`)
- Modify: `app/src/main/java/app/drydock/mcp/McpToolRouter.java` (`review_tour` accepts `onlySteps: true`)
- Modify: `app/src/main/java/app/drydock/ui/review/SessionReviewView.java` (migrate on a new review diff; dispatch refresh; clear `shelved`)
- Modify: `SessionReviewView.Host` (`boolean dispatchTourRefresh(ReviewScope scope, List<String> staleStepIds, int uncoveredHunks)`), `MainWorkspace`, `FakeReviewHost`
- Test: `app/src/test/java/app/drydock/review/tour/TourMigrationTest.java`, `TourMergeTest.java`; extend `McpToolRouterTourTest`

**Interfaces:**
- Produces:
  - `record TourMigration.Result(TourRecord record, List<String> staleStepIds, List<String> uncoveredHunkIds)`
  - `static TourMigration.Result TourMigration.migrate(TourRecord record, UnifiedDiff newReviewDiff)`: new fingerprint; for each step: if every digest in its progress `hunkDigests` exists in the new diff, remap each anchor endpoint (find the old hunk whose `hunkRows` contains the key, take its index in that list, take the same index in the new hunk with the same digest) and keep the progress; otherwise mark the step stale (progress kept, `stale = true`); recompute `hunkRows` for the new diff; uncovered = changed rows of the new diff in no anchor of a non-stale step, grouped by hunk id; `shelved` cleared
  - `static TourRecord TourMerge.replaceSteps(TourRecord record, List<TourStep> steps, UnifiedDiff reviewDiff)`: steps with an existing id replace it (fresh progress, not stale); new ids are appended; fingerprint set to the current diff; then the router validates the merged tour with `TourValidator.validate(merged.tour(), diff, findings)`
  - `ReviewInstructions.forTourRefresh(String scopeId, List<String> staleStepIds, int uncoveredHunks)`: one line asking the agent to read `review_scope` and `review_state`, then call `review_tour` with `onlySteps` true to replace the named steps and add steps for the uncovered hunks
  - `review_tour` argument `onlySteps` (boolean, default false); with no stored tour it is an error

- [ ] **Step 1: Write the failing tests**: `TourMigrationTest` — (a) a diff where only `src/B.java` changed keeps `s1` (anchors on `src/A.java`) passed and marks `s2` stale; (b) a diff where `src/A.java`'s second hunk shifted by 5 lines (same content, new line numbers) keeps `s1` and remaps `n21` to `n26`; (c) a new hunk is reported uncovered; (d) `shelved` is cleared. `TourMergeTest` — replacing `s2` resets its progress and keeps `s1`'s; adding `s3` appends it. Router — `onlySteps` with a valid replacement is stored; an `onlySteps` merge that leaves a hunk uncovered is rejected and stores nothing.
- [ ] **Step 2: Run to verify failure**, implement. In the view: when `onDiffResolved` delivers a review diff whose fingerprint differs from the stored tour's, call `host.updateTour(scope, r -> TourMigration.migrate(r, diff).record())`, and if the result has stale steps or uncovered hunks and `TourRefreshDispatch.claim(scope.id(), fingerprint)` succeeds, call `host.dispatchTourRefresh(...)` (release the claim if it returns false). The outline shows stale steps with `⟳` and the step panel says "This step's code changed; the agent is re-writing it." Run:

Run: `./gradlew :app:test --tests "app.drydock.review.tour.*" --tests "app.drydock.mcp.*" --tests "app.drydock.ui.review.*" --offline`
Expected: all pass.

- [ ] **Step 3: Commit**

Subject: `Review tour: when the diff moves, untouched steps keep their progress and only changed ones are re-issued`. Body: the absence (any new commit would have invalidated every anchor and every answer); migration by hunk digest with anchor remapping through stored row keys, stale steps, uncovered hunks, a deduplicated refresh request and `onlySteps` merging validated as a whole; verification. `Co-Authored-By` trailer.

---

### Task 22: Visual check, live run, docs, and the full gate

**Read first:** memory notes "Visual verification harness", "Run the visual check, don't argue it away", "Robot input is blocked in diag runs", "Which Drydock build is under test", "Subagents can't run the full gradle suite". This task is run by the **controller**, not a subagent.

**Constraints:** the full suite runs from the controller (14–20 min, `run_in_background`); every diag script ends with `quit` and leftover PIDs are checked; nothing is posted to GitHub without the user's explicit go-ahead; results are reported as `command → key result line`; anything not verified is stated as `NOT VERIFIED: <reason> | what would verify it`.

**Files:**
- Modify: `docs/architecture.md` (a short "Guided review tour" section: model, store, MCP tools, where the derivation lives)
- Modify: `docs/ui-redesign.md:39-42` (MCP tool row now lists `review_tour`, `review_check`)
- Possibly modify: `app/src/main/java/app/drydock/DrydockApplication.java` diag verbs, only if a new verb is needed (then `DiagVerbsAreWiredTest` must cover it)

- [ ] **Step 1: Full gate**

Run (background, controller): `./gradlew :app:test --offline > <scratchpad>/full-test.log 2>&1; tail -40 <scratchpad>/full-test.log`
Expected: `BUILD SUCCESSFUL`, 0 failures. Record the test count.

- [ ] **Step 2: Visual check at two widths**

Initialise the submodule in the worktree if needed (`git submodule update --init third_party/ghostty`), wake the display (`caffeinate -u -t 900 &`), and run against a repository with a branch diff and a stored tour (seed `review-tours.json` in the diag state directory with a tour for the diag scope, or let a real agent post one in Step 3 first):

```
ZIG_BIN=/usr/local/opt/zig@0.15/bin/zig ./gradlew run \
  -Papp.drydock.diag.stateFile=<tmp>/diag-state.json \
  -Papp.drydock.diag.autoCreateSession=true \
  -Papp.drydock.diag.repo=<repo with a branch diff> \
  -Papp.drydock.diag.tabScript="25:subtab:review" \
  -Papp.drydock.diag.explorerScript="35:resize:1280x900,40:shot:<tmp>/tour-1280.png,45:resize:1920x1200,50:shot:<tmp>/tour-1920.png,55:reviewkey:V,58:shot:<tmp>/diff-1920.png,60:quit"
```

Look at each PNG (Read tool): three columns visible; outline rows and step panel buttons not truncated (the memory's "R.." failure); PREDICT band hatched with its label; removed rows rendered; "step N" tags readable; hunk-diff mode identical to before. Check no diag process is left: `ps -eo pid,command | grep diag.autoCreateSession`.

- [ ] **Step 3: Live run against a real PR**

In a Drydock session on a real PR or branch of this repository, press Run review with a Claude agent. Report: whether `review_tour` validated on the first or a later attempt (count the rejections in the MCP activity log), number of steps and checks, time from Run review to the tour appearing, whether a withheld finding round-tripped (answer → reveal → confirm/dismiss), whether a RISK answer was judged (`review_check` in the log), and the submit sheet's routes. Ask the user before submitting the review to GitHub. Note any pedagogical-quality observations about the checks for prompt tuning.

- [ ] **Step 4: Docs**

Add the `docs/architecture.md` section (8–15 lines) and update `docs/ui-redesign.md`'s tool row.

- [ ] **Step 5: Commit the docs**

Subject: `Docs describe the guided review tour`. Body: what was added where; the verification results from Steps 1–3 with numbers; what was not covered. `Co-Authored-By` trailer.

---

## Follow-ups (not in this plan)

- Retire the intent rail, path mode and `review_intents` (deletes existing tests — ask first).
- LSP-backed `UsageProvider` (its own spec).
- `review_recheck` is missing from `McpServer.AGENT_WRITE_TOOLS` (pre-existing gap found during recon).
