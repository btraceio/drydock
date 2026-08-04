# Review UI: correctness and orientation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Review destination's intent rail describe the selected scope and nothing else, make a narrow window always offer an obvious next action, and make an empty Review say which kind of empty it is.

**Architecture:** Three independent parts against the existing `ReviewDestinationView` / `MainWorkspace` seam. Part 1 replaces the diff column's single "a diff landed" `Runnable` with a channel carrying `(scopeId, outcome)`, and routes intents through a per-scope map so a scope's intents can only come from that scope's diff. Part 3 surfaces `ReviewQueueService`'s existing completeness flags so the view can distinguish four kinds of empty. Part 2 replaces four width thresholds with one invariant — the code column never goes below a floor — and makes the verdict bar a self-sufficient review loop.

**Tech Stack:** Java 21+ records/sealed types, JavaFX, JUnit 5, TestFX 4.0.18 with openjfx-monocle (headless), real temporary git repositories driven by `ProcessBuilder`.

## Global Constraints

- Never block the JavaFX Application Thread: process spawns, filesystem I/O and network go on a background executor, hopping back with `Platform.runLater` (`AGENTS.md`, "Blocking work is async").
- Every user-triggered async operation shows progress immediately and clears it on **every** completion path, including early returns.
- All external process spawns go through `app.drydock.process.ProcessRunner` with a timeout. A failed command is never silently equal to an empty result: throw the service's exception type, or log a WARNING with an stderr excerpt.
- Tests run headless: `./gradlew :app:test`. Monocle's virtual screen is `1920x1200-32` (`app/build.gradle.kts:130`) — a layout test must not assume a larger window.
- The FX layer has no headless harness inside the *running* app. Visual confirmation goes through the `shot:` scene-snapshot path or a screen recording of the running window; `screencapture` is the one route that does not work.
- Existing keyboard table (spec §5) is preserved exactly. New controls are accelerators' visible counterparts, never replacements.
- The code column's minimum width is **560px**, verbatim from the spec.
- Rail collapse priority, verbatim from the spec: findings margin first, then intents, then queue; re-expansion in reverse.

## Deviation from the spec, decided here

The spec says "the workspace holds the per-scope diffs". This plan puts the
map in `ReviewDestinationView` instead, and widens `Host.intents` to take the
diff. Reason: the view is the only component that knows *both* the selected
scope and the diff column that produced a diff, and it is the component the
test suite can reach through `FakeReviewHost`. `MainWorkspace` keeps its role
— it answers `intents(scope, diff)` by consulting `IntentGrouping` — and
loses only the global read at `MainWorkspace.java:977`. The spec's guarantee
is unchanged: a scope's intents derive from that scope's diff or nothing.

## File Structure

**Part 1 — intents that describe the selected scope**

| File | Responsibility |
|---|---|
| `app/src/main/java/app/drydock/ui/review/DiffOutcome.java` (create) | What a diff attempt produced for one scope: loaded / failed / none. |
| `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (modify) | Publishes `(scopeId, DiffOutcome)` on success **and** failure. |
| `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (modify) | Owns the per-scope outcome map; feeds the rail its intents and its empty reason. |
| `app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java` (modify) | Renders four distinct empty states instead of a blank rail. |
| `app/src/main/java/app/drydock/ui/MainWorkspace.java` (modify) | `intents(scope, diff)` — no global diff read. |
| `app/src/main/java/app/drydock/git/ReviewBase.java` (create) | A resolved base plus how it was chosen. |
| `app/src/main/java/app/drydock/git/GitStatusService.java` (modify) | Returns `ReviewBase`; logs the unmeasurable-candidates fallback. |
| `app/src/main/java/app/drydock/review/ReviewScope.java` (modify) | `diffable()` — false for a PR with no worktree. |

**Part 3 — an empty Review that explains itself**

| File | Responsibility |
|---|---|
| `app/src/main/java/app/drydock/review/QueueAssembly.java` (create) | Items plus per-source completeness. |
| `app/src/main/java/app/drydock/review/ReviewQueueService.java` (modify) | Returns `QueueAssembly`. |
| `app/src/main/java/app/drydock/ui/review/ReviewEmptyState.java` (create) | The four empty states and their copy. |
| `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (modify) | Renders the state; wires Retry; drops the session row with no item. |

**Part 2 — a narrow window with an obvious next move**

| File | Responsibility |
|---|---|
| `app/src/main/java/app/drydock/ui/review/RailLayout.java` (create) | Pure arithmetic: which rails collapse at a given width. |
| `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (modify) | Applies `RailLayout`; lands on intent 1. |
| `app/src/main/java/app/drydock/ui/review/ReviewVerdictBar.java` (modify) | Names the current intent; carries prev / next / next-unsettled. |

---

# Part 1 — intents that describe the thing you selected

### Task 1: A diff outcome that names its scope

**Files:**
- Create: `app/src/main/java/app/drydock/ui/review/DiffOutcome.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (fields ~111-114, `setScope` 165-177, `reload` 180-207, `showDiff` ~292-300, `setOnDiffLoaded` 211-214)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewDiffColumnPublishTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `sealed interface DiffOutcome` with records `Loaded(UnifiedDiff diff)`, `Failed(String message)`, and singleton `Diffing`; `ReviewDiffColumn.setOnDiffResolved(BiConsumer<String, DiffOutcome>)`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish channel carries the scope a diff belongs to, and carries a
 * failure as well as a success. Both halves are load-bearing: without the
 * scope id an intent rail cannot tell whose diff it received, and without
 * the failure a failed scope is indistinguishable from one still loading.
 */
class ReviewDiffColumnPublishTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final Map<String, DiffOutcome> published = new ConcurrentHashMap<>();
    private final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
    private ReviewDiffColumn column;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        column.setOnDiffResolved((scopeId, outcome) -> {
            published.put(scopeId, outcome);
            order.add(scopeId);
        });
        stage.setScene(new Scene(column, 1400, 900));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void aLoadedDiffIsPublishedUnderItsOwnScopeId() throws Exception {
        Path repo = dirtyRepo();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> column.setScope(scope));

        DiffOutcome outcome = await(scope.id());
        assertInstanceOf(DiffOutcome.Loaded.class, outcome);
        assertEquals(1, ((DiffOutcome.Loaded) outcome).diff().files().size());
    }

    @Test
    void aFailedDiffIsPublishedAsFailedRatherThanNotAtAll() throws Exception {
        // A directory that is not a git repository: git exits non-zero.
        Path notARepo = Files.createTempDirectory("drydock-not-a-repo");
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, notARepo, Optional.of(notARepo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> column.setScope(scope));

        assertInstanceOf(DiffOutcome.Failed.class, await(scope.id()),
                "a failure must reach the channel; silence is indistinguishable from loading");
    }

    private DiffOutcome await(String scopeId) {
        for (int i = 0; i < 200; i++) {
            DiffOutcome outcome = published.get(scopeId);
            if (outcome != null && !(outcome instanceof DiffOutcome.Diffing)) {
                return outcome;
            }
            sleep(25);
        }
        throw new AssertionError("no terminal outcome published for " + scopeId
                + "; saw " + order);
    }

    private static Path dirtyRepo() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-publish").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 2; }\n");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDiffColumnPublishTest'`
Expected: FAIL to compile — `DiffOutcome` and `setOnDiffResolved` do not exist.

- [ ] **Step 3: Create `DiffOutcome`**

```java
package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;

import java.util.Objects;

/**
 * What a diff attempt produced for one scope.
 *
 * <p>Three states, not two. "Failed" and "still loading" look identical to
 * anything downstream that only learns about successes, and the intent rail
 * has to tell them apart -- a rail reading "Diffing…" beside a column
 * reading "Could not diff" is the kind of contradiction that taught readers
 * to distrust the rail in the first place.</p>
 */
public sealed interface DiffOutcome {

    /** A diff request is in flight. */
    record Diffing() implements DiffOutcome { }

    /** The diff resolved; {@code diff} may legitimately contain zero files. */
    record Loaded(UnifiedDiff diff) implements DiffOutcome {
        public Loaded {
            Objects.requireNonNull(diff, "diff");
        }
    }

    /** The diff could not be produced; {@code message} is what the column shows. */
    record Failed(String message) implements DiffOutcome {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }
}
```

- [ ] **Step 4: Publish from `ReviewDiffColumn`**

Replace the `onDiffLoaded` field and its setter:

```java
    private java.util.function.BiConsumer<String, DiffOutcome> onDiffResolved = (scopeId, outcome) -> { };

    /**
     * Notified when a diff resolves, with the scope it resolved for.
     *
     * <p>The scope id is the point: a bare "a diff landed" signal left every
     * consumer reading whatever diff happened to be current, which is how an
     * intent rail came to show one scope's files beside another's header.</p>
     */
    void setOnDiffResolved(java.util.function.BiConsumer<String, DiffOutcome> handler) {
        this.onDiffResolved = handler == null ? (scopeId, outcome) -> { } : handler;
    }
```

In `setScope`, publish `Diffing` for the newly selected scope before the reload, and publish nothing for a null scope:

```java
    void setScope(ReviewScope newScope) {
        if (scope != null && newScope != null && scope.id().equals(newScope.id())) {
            return;
        }
        scope = newScope;
        expandedRuns.clear();
        if (newScope == null) {
            diff = new UnifiedDiff(List.of());
            showMessage("Nothing selected.");
            return;
        }
        onDiffResolved.accept(newScope.id(), new DiffOutcome.Diffing());
        reload();
    }
```

In `reload`, publish both terminal outcomes. Note `requested.id()` is captured before the async hop, so a superseded request cannot publish under the wrong scope:

```java
        diffService.diff(requested.diffRoot(), diffScope, requested.base(),
                        DiffService.REVIEW_CONTEXT_LINES)
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (token != requestToken) {
                        return; // superseded by a newer scope selection
                    }
                    if (failure != null) {
                        LOG.log(Level.DEBUG, "Diff failed for " + requested.diffRoot(), failure);
                        diff = new UnifiedDiff(List.of());
                        String message = UiErrors.unwrap(failure).getMessage();
                        showMessage("Could not diff: " + message);
                        onDiffResolved.accept(requested.id(), new DiffOutcome.Failed(message));
                        return;
                    }
                    diff = result;
                    symbolIndex = SymbolIndex.of(diff);
                    rebuild();
                    onDiffResolved.accept(requested.id(), new DiffOutcome.Loaded(result));
                }));
```

In `showDiff` (the patch-only path), take the scope it was read for and publish under it:

```java
    /**
     * Renders a diff that did not come from this column's own git call --
     * {@code gh pr diff} for the "Read the patch only" path, which has no
     * checkout to run git in. Clears the scope so a later reload cannot
     * overwrite it with a local diff of the wrong tree, and publishes under
     * the scope it was read FOR, which is not the same thing as adopting
     * that scope as the column's live one.
     */
    void showDiff(ReviewScope forScope, UnifiedDiff supplied) {
        scope = null;
        requestToken++;
        diff = supplied;
        symbolIndex = SymbolIndex.of(diff);
        expandedRuns.clear();
        rebuild();
        onDiffResolved.accept(forScope.id(), new DiffOutcome.Loaded(supplied));
    }
```

**Do not drop `scope = null` or `requestToken++`.** They are why a
patch-only diff survives: the scope belongs to a PR with no checkout, so
adopting it would let a subsequent `reload()` run git in the *main checkout*
and overwrite the PR's patch with a local diff of the wrong tree — and after
Task 5 it would additionally throw. The only change here is the added
parameter and the publish.

Delete `setOnDiffLoaded` and the `onDiffLoaded` field. Update the two existing call sites in `ReviewDestinationView` (`setOnDiffLoaded` at :252, `showDiff` at :750) to compile — the real wiring lands in Task 2; for now:

```java
        diffColumn.setOnDiffResolved((scopeId, outcome) -> refreshReviewState());
```

and in `patchOnlyBody`:

```java
        diffColumn.showDiff(scope, patchOnlyDiffs.get(scope.id()));
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDiffColumnPublishTest'`
Expected: PASS, both tests.

- [ ] **Step 6: Run the whole Review suite for regressions**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*' --tests 'app.drydock.review.*'`
Expected: PASS. `ReviewIntentFallbackTest` and `ReviewDiffColumnTest` exercise the channel that just changed shape.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/DiffOutcome.java \
        app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/test/java/app/drydock/ui/review/ReviewDiffColumnPublishTest.java
git commit -m "A diff that resolves says which scope it resolved for"
```

---

### Task 2: Intents come from the selected scope's diff, or from nothing

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`Host.intents` 88-89, `intents()` 447-449, `showItem` 655-674, `refreshReviewState` 410-424)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:976-978`
- Modify: `app/src/test/java/app/drydock/ui/review/FakeReviewHost.java` (106-108)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewIntentScopeIsolationTest.java` (create)

**Interfaces:**
- Consumes: `DiffOutcome` and `setOnDiffResolved` from Task 1.
- Produces: `Host.intents(ReviewScope scope, UnifiedDiff diff)`; `ReviewDestinationView.outcomeFor(String scopeId)` returning `DiffOutcome` (never null — `Diffing` is not the default, absence is `new DiffOutcome.Diffing()` only for a scope whose diff is genuinely in flight; a gate scope's absence stays absent).

- [ ] **Step 1: Write the failing test**

This is the reported bug, reduced: select an item that loads a diff, then select a gate item, and assert the rail does not inherit the first item's files.

```java
package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported defect: the intent rail described whichever scope last
 * produced a diff, not the scope the header named. A not-checked-out PR
 * never runs a diff at all, so it inherited the previous item's files and
 * kept them -- which is how a repository with no diffable item at all came
 * to show another repository's files.
 */
class ReviewIntentScopeIsolationTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-isolation")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void aGateItemDoesNotInheritThePreviousItemsFiles() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope gate = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, repo, Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty()));

        interact(() -> view.setItems(List.of(
                new ReviewItem(worktree, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted"),
                new ReviewItem(gate, ReviewItem.Group.REQUESTED, "PR #7 feature", "repo · not checked out")), 1));

        awaitCardCount(2);

        interact(() -> view.selectScope(gate.id()));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, cardCount(),
                "a scope with no diff of its own must show no intents, not the previous scope's");
        assertEquals("Not checked out — check out to group changes", railMessage());
    }

    @Test
    void comingBackToTheWorktreeRestoresItsOwnIntents() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope gate = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, repo, Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty()));

        interact(() -> view.setItems(List.of(
                new ReviewItem(worktree, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted"),
                new ReviewItem(gate, ReviewItem.Group.REQUESTED, "PR #7 feature", "repo · not checked out")), 1));
        awaitCardCount(2);

        interact(() -> view.selectScope(gate.id()));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        interact(() -> view.selectScope(worktree.id()));

        awaitCardCount(2);
        assertTrue(cardCount() == 2, "the worktree's own intents come back");
    }

    private int cardCount() {
        int[] count = new int[1];
        interact(() -> count[0] = lookup(".review-intent-card").queryAll().size());
        return count[0];
    }

    private String railMessage() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".review-intent-empty").tryQuery()
                .map(node -> ((Label) node).getText()).orElse(""));
        return text[0];
    }

    private void awaitCardCount(int expected) {
        for (int i = 0; i < 200; i++) {
            if (cardCount() == expected) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("expected " + expected + " intent cards, saw " + cardCount());
    }

    private static Path repoWithTwoChangedFiles() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-isolation-repo").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 1; }\n");
        Files.writeString(repo.resolve("B.java"), "class B { int y = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 2; }\n");
        Files.writeString(repo.resolve("B.java"), "class B { int y = 2; }\n");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
```

Add one line to the test's `start(Stage)`, directly after constructing the
view, so the red run reproduces what the app does today:

```java
        // Today's wiring, verbatim from MainWorkspace: the fallback groups
        // whatever diff the column last rendered. Deleted in Step 3 along
        // with the field itself -- it exists here only so the red run shows
        // the real defect instead of an empty fixture.
        host.diffSource = view::currentDiff;
```

Without it `FakeReviewHost.diff` defaults to `new UnifiedDiff(List.of())`
(`FakeReviewHost.java:42`), every scope yields zero intents, and the red run
would fail for a reason that has nothing to do with the bug.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewIntentScopeIsolationTest'`
Expected: FAIL — `aGateItemDoesNotInheritThePreviousItemsFiles` reaches 2 cards for the worktree, and **still shows 2 after selecting the gate item**, where it expects 0. That persistence *is* the reported bug. If instead you see `expected 2 intent cards, saw 0`, the `diffSource` line above is missing — fix that before going on, because the rest of this task is unverifiable without a genuine red.

- [ ] **Step 3: Widen the Host interface**

In `ReviewDestinationView.Host`, replace `intents`:

```java
        /**
         * The intents of {@code scope}, grouping {@code diff}: the reviewer's
         * grouping when one was supplied, otherwise one intent per changed
         * file of the diff handed in.
         *
         * <p>The diff is a parameter rather than something the host fetches,
         * because the only correct diff here is the one the caller has
         * already established belongs to {@code scope}. A host that looked it
         * up would be free to look up the wrong one, which is exactly the
         * defect this shape removes.</p>
         */
        List<ReviewIntent> intents(ReviewScope scope, app.drydock.git.UnifiedDiff diff);
```

In `MainWorkspace`:

```java
        @Override
        public List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff) {
            return intentGrouping.intentsFor(scope.id(), diff);
        }
```

In `FakeReviewHost`, drop the `diffSource` field entirely and answer from the diff handed in:

```java
    @Override
    public List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff) {
        return intents.intentsFor(scope.id(), diff);
    }
```

`ReviewIntentFallbackTest` sets `host.diffSource = view::currentDiff` (line ~55); delete that line — the view now routes the diff itself.

- [ ] **Step 4: Own the per-scope outcomes in the view**

Add to `ReviewDestinationView`:

```java
    /**
     * What each scope's diff attempt produced, keyed by scope id. A scope
     * absent from this map has no diff -- which is a state, not a reason to
     * reach for someone else's.
     */
    private final java.util.Map<String, DiffOutcome> outcomeByScope = new java.util.HashMap<>();
```

Wire the channel (replacing the placeholder from Task 1, Step 4):

```java
        diffColumn.setOnDiffResolved((scopeId, outcome) -> {
            outcomeByScope.put(scopeId, outcome);
            // Only the selected scope's arrival changes what is on screen;
            // a superseded one still records its outcome, so coming back to
            // it does not re-run git.
            if (selectedScope().map(scope -> scope.id().equals(scopeId)).orElse(false)) {
                refreshReviewState();
                revealCurrentIntent();
            }
        });
```

Replace `intents()`:

```java
    /**
     * The selected scope's intents. A scope whose diff has not loaded -- or
     * never will, because it has no checkout -- has none, and says so
     * through {@link #emptyReason()} rather than borrowing another's.
     */
    private List<ReviewIntent> intents() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return List.of();
        }
        DiffOutcome outcome = outcomeByScope.get(scope.get().id());
        if (outcome instanceof DiffOutcome.Loaded loaded) {
            return host.intents(scope.get(), loaded.diff());
        }
        return List.of();
    }
```

**A test seam, and the one existing test that needs it.** Gating on
`DiffOutcome.Loaded` means a fake host can no longer supply a diff at all —
`FakeReviewHost.diffSource` was the only route and it is gone. Add a
package-private seam beside the existing `diag*` methods:

```java
    /**
     * Test-only: records an outcome for a scope without running git. The
     * view derives everything from these outcomes now, so a test with a
     * synthetic diff and no real checkout has no other way in.
     */
    void diagPublishOutcome(String scopeId, DiffOutcome outcome) {
        outcomeByScope.put(scopeId, outcome);
        refreshReviewState();
    }
```

`ReviewFindingsAndVerdictsTest` needs it. Its scope's worktree is
`Path.of("/wt/feat")` (`ReviewFindingsAndVerdictsTest.java:367-368`), which
does not exist, so git fails there and Task 1's channel publishes `Failed` —
leaving every intent-dependent case in that class with zero intents. In its
`seed(...)` helper (`:375-383`), after the `view.setItems(...)` call:

```java
        interact(() -> view.diagPublishOutcome(minted.scope().id(),
                new DiffOutcome.Loaded(host.diff)));
```

Ten cases in that class depend on the two fallback intents — the verdict
round-trips, the `[` / `]` / `n` handlers, both submit cases and
`theVerdictBarSurvivesFocusMode` — plus `shiftFWidensTheMarginToTheWholeReview`,
which counts margin cards and changes answer when `currentIntent()` is empty.
Run that class specifically after this step.

Add the empty-reason derivation, and drop the outcome when an item leaves the queue so a re-minted scope does not resurrect a stale diff. In `setItems`, after `queue.setItems(items)`:

```java
        java.util.Set<String> present = items.stream().map(item -> item.scope().id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        outcomeByScope.keySet().retainAll(present);
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*'`
Expected: `comingBackToTheWorktreeRestoresItsOwnIntents` PASSES, and
`ReviewFindingsAndVerdictsTest` passes in full — if its verdict and
`[`/`]`/`n` cases fail with "no intent", the `diagPublishOutcome` call is
missing from `seed(...)`.

`aGateItemDoesNotInheritThePreviousItemsFiles` passes its card-count
assertion but still FAILS on `railMessage()` — the `.review-intent-empty`
label lands in Task 3. That one failure is the expected intermediate state;
nothing else may be red at this commit.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/ui/review/FakeReviewHost.java \
        app/src/test/java/app/drydock/ui/review/ReviewIntentFallbackTest.java \
        app/src/test/java/app/drydock/ui/review/ReviewFindingsAndVerdictsTest.java \
        app/src/test/java/app/drydock/ui/review/ReviewIntentScopeIsolationTest.java
git commit -m "A scope's intents come from that scope's diff, or from nothing"
```

---

### Task 3: A rail that says which kind of empty it is

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java` (`setIntents` 91-95, `rebuild` 134-155)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`refreshReviewState` ~420)
- Modify: `app/src/main/resources/app/drydock/ui/app.css`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewIntentRailEmptyStateTest.java` (create)

**Interfaces:**
- Consumes: `DiffOutcome` (Task 1), `outcomeByScope` (Task 2).
- Produces: `ReviewIntentRail.Empty` enum — `NONE`, `DIFFING`, `NOT_CHECKED_OUT`, `DIFF_FAILED`, `NO_CHANGES`; `setIntents(List<ReviewIntent>, String selectedIntentId, Empty reason)`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Four situations produce an empty rail, and rendering one as another is
 * how a reader learns to distrust it: a failed diff is not "still loading",
 * and a worktree with nothing changed is not "not checked out".
 */
class ReviewIntentRailEmptyStateTest {

    @Test
    void everyEmptyReasonHasItsOwnSentence() {
        assertEquals("Diffing…", ReviewIntentRail.Empty.DIFFING.message());
        assertEquals("Not checked out — check out to group changes",
                ReviewIntentRail.Empty.NOT_CHECKED_OUT.message());
        assertEquals("Could not diff — see the message beside this",
                ReviewIntentRail.Empty.DIFF_FAILED.message());
        assertEquals("No changes", ReviewIntentRail.Empty.NO_CHANGES.message());
    }

    @Test
    void theNonEmptyCaseHasNoMessageAtAll() {
        assertEquals("", ReviewIntentRail.Empty.NONE.message());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewIntentRailEmptyStateTest'`
Expected: FAIL to compile — `ReviewIntentRail.Empty` does not exist.

- [ ] **Step 3: Add the enum and render it**

In `ReviewIntentRail`:

```java
    /**
     * Why a rail is showing no intents. Four situations, four sentences: the
     * rail is told which one it is in rather than inferring it from an
     * absence, because a failed diff and an in-flight one are both "no
     * entry" and mean opposite things to a reader.
     */
    enum Empty {
        NONE(""),
        DIFFING("Diffing…"),
        NOT_CHECKED_OUT("Not checked out — check out to group changes"),
        DIFF_FAILED("Could not diff — see the message beside this"),
        NO_CHANGES("No changes");

        private final String message;

        Empty(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private Empty emptyReason = Empty.NONE;

    void setIntents(List<ReviewIntent> newIntents, String selectedIntentId, Empty reason) {
        this.intents = List.copyOf(newIntents);
        this.selectedId = selectedIntentId;
        this.emptyReason = reason == null ? Empty.NONE : reason;
        rebuild();
    }
```

In `rebuild`, after `cards.getChildren().setAll(nodes)`, render the message when there is nothing else. Collapsed rails show no prose — there is no width for it:

```java
        if (intents.isEmpty() && emptyReason != Empty.NONE && !collapsed) {
            Label message = new Label(emptyReason.message());
            message.getStyleClass().add("review-intent-empty");
            message.setWrapText(true);
            cards.getChildren().setAll(message);
        }
```

In `app.css`, beside the existing `.review-intent-rationale` rule:

```css
.review-intent-empty {
    -fx-text-fill: -drydock-text-faint;
    -fx-font-size: 11px;
    -fx-padding: 8 10 8 10;
}
```

`-drydock-text-faint` is the project's muted-text variable, used by the
adjacent `.review-intent-rationale` rule (`app.css:3027-3030`). Wrapping is
set in Java (`message.setWrapText(true)`), not in CSS.

- [ ] **Step 4: Derive the reason in the view**

In `ReviewDestinationView`, add:

```java
    /**
     * Which empty the rail is showing. A scope with a checkout whose diff has
     * not arrived is loading; one without a checkout never will; a loaded
     * diff with no files is a genuine "nothing changed here".
     */
    private ReviewIntentRail.Empty emptyReason() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return ReviewIntentRail.Empty.NONE;
        }
        DiffOutcome outcome = outcomeByScope.get(scope.get().id());
        if (outcome instanceof DiffOutcome.Failed) {
            return ReviewIntentRail.Empty.DIFF_FAILED;
        }
        if (outcome instanceof DiffOutcome.Loaded loaded) {
            return loaded.diff().files().isEmpty()
                    ? ReviewIntentRail.Empty.NO_CHANGES
                    : ReviewIntentRail.Empty.NONE;
        }
        return scope.get().worktree().isEmpty()
                ? ReviewIntentRail.Empty.NOT_CHECKED_OUT
                : ReviewIntentRail.Empty.DIFFING;
    }
```

and in `refreshReviewState`, replace the `setIntents` call:

```java
        intentRail.setIntents(intents(), currentIntent().map(ReviewIntent::id).orElse(null),
                emptyReason());
```

- [ ] **Step 5: Run both tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewIntentRailEmptyStateTest' --tests 'app.drydock.ui.review.ReviewIntentScopeIsolationTest'`
Expected: PASS — including the `railMessage()` assertion left failing at the end of Task 2.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewIntentRail.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui/review/ReviewIntentRailEmptyStateTest.java
git commit -m "An empty intent rail says which kind of empty it is"
```

---

### Task 4: A base that says how it was chosen

**Files:**
- Create: `app/src/main/java/app/drydock/git/ReviewBase.java`
- Modify: `app/src/main/java/app/drydock/git/GitStatusService.java` (`reviewBase` 446-450, `reviewBaseBlocking` 452-483)
- Modify: `app/src/main/java/app/drydock/review/ReviewQueueService.java` (`resolveBases` 230-259, `build` 287-353)
- Test: `app/src/test/java/app/drydock/git/GitStatusServiceReviewBaseTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `record ReviewBase(String ref, Origin origin)` with `enum Origin { PULL_REQUEST, FORKED_FROM, DEFAULT_UNMEASURED }` and `String describe()`; `GitStatusService.reviewBase(Path, Optional<String>, String)` now returns `CompletableFuture<ReviewBase>`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The btrace shape: origin/HEAD says master, every branch is cut from
 * develop. Diffing against master rendered a five-file branch as fourteen
 * hundred files, and nothing on screen said why -- so the base now carries
 * how it was chosen.
 */
class GitStatusServiceReviewBaseTest {

    private final GitStatusService service = new GitStatusService();

    @Test
    void aBranchCutFromDevelopResolvesToDevelopAndSaysSo() throws Exception {
        Path repo = repoWithDevelopCutBranch();

        ReviewBase base = service.reviewBase(repo, Optional.empty(), "master").get();

        assertEquals("develop", base.ref());
        assertEquals(ReviewBase.Origin.FORKED_FROM, base.origin());
    }

    @Test
    void aDeclaredPullRequestBaseWins() throws Exception {
        Path repo = repoWithDevelopCutBranch();

        ReviewBase base = service.reviewBase(repo, Optional.of("master"), "master").get();

        assertEquals("master", base.ref());
        assertEquals(ReviewBase.Origin.PULL_REQUEST, base.origin());
    }

    @Test
    void anUnmeasurableRepositoryFallsBackAndAdmitsIt() throws Exception {
        // An empty repository: HEAD is unborn, so no candidate can be counted.
        Path repo = Files.createTempDirectory("drydock-unborn");
        runGit(repo, "init", "-b", "main");

        ReviewBase base = service.reviewBase(repo, Optional.empty(), "main").get();

        assertEquals("main", base.ref());
        assertEquals(ReviewBase.Origin.DEFAULT_UNMEASURED, base.origin());
    }

    /** master, then a develop cut from it that moved on, then a branch cut from develop. */
    private static Path repoWithDevelopCutBranch() throws Exception {
        Path repo = Files.createTempDirectory("drydock-base");
        runGit(repo, "init", "-b", "master");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("base.txt"), "base\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "master commit");

        runGit(repo, "checkout", "-b", "develop");
        for (int i = 0; i < 5; i++) {
            Files.writeString(repo.resolve("infra" + i + ".txt"), "infra\n");
            runGit(repo, "add", ".");
            runGit(repo, "commit", "-m", "infra " + i);
        }

        runGit(repo, "checkout", "-b", "feature/thing");
        Files.writeString(repo.resolve("feature.txt"), "feature\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "the feature");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.git.GitStatusServiceReviewBaseTest'`
Expected: FAIL to compile — `ReviewBase` does not exist.

- [ ] **Step 3: Create `ReviewBase`**

```java
package app.drydock.git;

import java.util.Objects;

/**
 * The revision a review diffs against, and how that was decided.
 *
 * <p>The provenance is not decoration. A base of {@code master} in a
 * repository where every branch is cut from {@code develop} turns a
 * five-file review into fourteen hundred files, and the only symptom the
 * reader gets is a diff that looks like someone else's work. Carrying how
 * the base was chosen lets the item header say "default, could not measure"
 * where it used to say nothing at all.</p>
 */
public record ReviewBase(String ref, Origin origin) {

    /** How {@link #ref} was arrived at, in descending order of authority. */
    public enum Origin {
        /** GitHub declared it: the PR's own {@code baseRefName}. */
        PULL_REQUEST("declared by the pull request"),
        /** The integration branch containing the most of this checkout's history. */
        FORKED_FROM("forked from"),
        /** Nothing could be measured; the repository default is a guess. */
        DEFAULT_UNMEASURED("repository default — could not measure");

        private final String description;

        Origin(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public ReviewBase {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(origin, "origin");
    }

    /** "develop (forked from)" -- what the item header shows. */
    public String describe() {
        return ref + " (" + origin.description() + ")";
    }
}
```

- [ ] **Step 4: Return it, and stop falling back silently**

In `GitStatusService`, change the signature and the two return paths:

```java
    public CompletableFuture<ReviewBase> reviewBase(Path checkoutRoot, Optional<String> pullRequestBase,
                                                    String defaultBranch) {
        return CompletableFuture.supplyAsync(
                () -> reviewBaseBlocking(checkoutRoot, pullRequestBase, defaultBranch), executor);
    }

    /** Synchronous form of {@link #reviewBase}, package-private for tests. */
    ReviewBase reviewBaseBlocking(Path checkoutRoot, Optional<String> pullRequestBase, String defaultBranch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        Optional<String> declared = pullRequestBase.flatMap(name -> resolveBranch(git, checkoutRoot, name));
        if (declared.isPresent()) {
            return new ReviewBase(declared.get(), ReviewBase.Origin.PULL_REQUEST);
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(defaultBranch);
        for (String name : INTEGRATION_BRANCHES) {
            resolveBranch(git, checkoutRoot, name)
                    .filter(resolved -> !candidates.contains(resolved))
                    .ifPresent(candidates::add);
        }

        String best = defaultBranch;
        long fewest = Long.MAX_VALUE;
        for (String candidate : candidates) {
            long ahead = commitsAhead(git, checkoutRoot, candidate);
            if (ahead >= 0 && ahead < fewest) {
                fewest = ahead;
                best = candidate;
            }
        }
        if (fewest == Long.MAX_VALUE) {
            // Not a detail to bury: this is the path that renders a whole
            // integration branch as though it were the review.
            LOG.log(Level.WARNING, "No review base candidate could be measured in " + checkoutRoot
                    + " (tried " + candidates + "); falling back to " + defaultBranch);
            return new ReviewBase(defaultBranch, ReviewBase.Origin.DEFAULT_UNMEASURED);
        }
        return new ReviewBase(best, ReviewBase.Origin.FORKED_FROM);
    }
```

- [ ] **Step 5: Carry it through the queue service**

In `ReviewQueueService`, change `resolveBases` to `Map<Path, ReviewBase>`, and in its `handle` fallback return `new ReviewBase(fallback, ReviewBase.Origin.DEFAULT_UNMEASURED)`. In `build`, replace line 318:

```java
            ReviewBase worktreeBase = worktreeBases.getOrDefault(worktree.path(),
                    new ReviewBase(base, ReviewBase.Origin.DEFAULT_UNMEASURED));
```

and use `worktreeBase.ref()` where a revision is needed (the `ReviewScope` base, and the `" · vs "` subtitles). Add the provenance to `ReviewScope` so the header can show it:

```java
        ReviewScope scope = scopeRegistry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, repository.root(), Optional.of(worktree.path()),
                worktreeBase.ref(), head, ...));
```

`ReviewScope` gains one component, `Optional<ReviewBase.Origin> baseOrigin`.
It is a record, so **every** construction site must be updated in this same
step or nothing compiles. There are exactly five, and they are all of them:

1. `ReviewScopeRegistry.spec(...)` (`ReviewScopeRegistry.java:135-138`) —
   passes `Optional.empty()`. Add an overload taking the origin rather than
   widening the existing seven-argument signature, so the callers that do not
   know a base origin stay unchanged:

```java
    public static ReviewScope spec(ReviewScope.Kind kind, Path repoRoot, Optional<Path> worktree,
                                   String base, String head, Optional<ReviewScope.PullRequestRef> pr,
                                   Optional<ManagedSessionId> sessionId) {
        return spec(kind, repoRoot, worktree, base, head, pr, sessionId, Optional.empty());
    }

    /** As above, recording how {@code base} was chosen (see {@link ReviewBase}). */
    public static ReviewScope spec(ReviewScope.Kind kind, Path repoRoot, Optional<Path> worktree,
                                   String base, String head, Optional<ReviewScope.PullRequestRef> pr,
                                   Optional<ManagedSessionId> sessionId,
                                   Optional<ReviewBase.Origin> baseOrigin) {
        return new ReviewScope("rs_pending", kind, repoRoot, worktree, base, head, pr, sessionId,
                baseOrigin);
    }
```

2. `ReviewScopeRegistry.mint(...)` (`ReviewScopeRegistry.java:122-123`) —
   carry `spec.baseOrigin()` through.
3. `ReviewScope.withSession(...)` (`ReviewScope.java:86-89`).
4. `ReviewScope.withWorktree(...)` (`ReviewScope.java:92-95`).
5. `app/src/test/java/app/drydock/mcp/McpToolRouterReviewTest.java` — the one
   test that calls the canonical constructor directly.

Verify none was missed before moving on:

```bash
grep -rn "new ReviewScope(" app/src
```

**Do not** add `baseOrigin` to `ReviewScopeRegistry.Identity` — the identity
must stay stable across rescans, or every finding is orphaned when a base is
re-measured (`ReviewScopeRegistry.java:33-36, 61-65`).

In `ReviewDestinationView.contextLine`, append the provenance when it is not the confident case:

```java
        String comparison = "vs " + against;
        String provenance = item.scope().baseOrigin()
                .filter(origin -> origin == ReviewBase.Origin.DEFAULT_UNMEASURED)
                .map(origin -> "  ·  " + origin.description())
                .orElse("");
        return (item.subtitle().endsWith(comparison)
                ? item.subtitle()
                : item.subtitle() + "  ·  " + comparison) + provenance;
```

- [ ] **Step 6: Update the five existing `reviewBase` assertions**

`GitStatusServiceTest` asserts on the old `String` return at five sites —
lines **268, 284, 300, 320 and 331**. Each is
`assertEquals("<branch>", service.reviewBase(...).get())`; each becomes
`.get().ref()`:

```java
        assertEquals("develop", service.reviewBase(repo, Optional.empty(), "master").get().ref());
```

Leave the expected branch names exactly as they are — they encode
behaviour this task must not change. Confirm none was missed:

```bash
grep -rn "reviewBase" app/src/test/java
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.git.*' --tests 'app.drydock.review.*'`
Expected: PASS. `ReviewScopeRegistryTest` guards the identity rule; if it fails, `baseOrigin` leaked into `Identity`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/drydock/git/ReviewBase.java \
        app/src/test/java/app/drydock/git/GitStatusServiceTest.java \
        app/src/main/java/app/drydock/git/GitStatusService.java \
        app/src/main/java/app/drydock/review/ReviewQueueService.java \
        app/src/main/java/app/drydock/review/ReviewScope.java \
        app/src/main/java/app/drydock/review/ReviewScopeRegistry.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/test/java/app/drydock/mcp/McpToolRouterReviewTest.java \
        app/src/test/java/app/drydock/git/GitStatusServiceReviewBaseTest.java
git commit -m "A review base says how it was chosen, and stops guessing quietly"
```

---

### Task 5: A PR with no checkout cannot be diffed

**Files:**
- Modify: `app/src/main/java/app/drydock/review/ReviewScope.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java` (`setScope`)
- Test: `app/src/test/java/app/drydock/review/ReviewScopeDiffabilityTest.java` (create)

**Interfaces:**
- Consumes: `ReviewScope` from Task 4.
- Produces: `ReviewScope.diffable()`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PR with no worktree pairs the PR's base with the *main checkout* as its
 * diff root (see {@link ReviewScope#diffRoot()}), so any diff it produced
 * would be "the main checkout's HEAD vs the PR's base" -- wrong by
 * construction, and never the PR's changes. Nothing may run one.
 */
class ReviewScopeDiffabilityTest {

    @Test
    void aPullRequestWithNoWorktreeIsNotDiffable() {
        ReviewScope scope = new ReviewScope("s1", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty(), Optional.empty());

        assertFalse(scope.diffable());
    }

    @Test
    void theSamePullRequestCheckedOutIsDiffable() {
        ReviewScope scope = new ReviewScope("s1", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.of(Path.of("/repo/.worktrees/pr-7")), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty(), Optional.empty());

        assertTrue(scope.diffable());
    }

    @Test
    void aWorkingTreeIsAlwaysDiffable() {
        ReviewScope scope = new ReviewScope("s1", ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.of(Path.of("/repo")), "main", "main",
                Optional.empty(), Optional.empty(), Optional.empty());

        assertTrue(scope.diffable());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.review.ReviewScopeDiffabilityTest'`
Expected: FAIL to compile — `diffable()` does not exist.

- [ ] **Step 3: Add `diffable()` and enforce it**

In `ReviewScope`:

```java
    /**
     * Whether a diff may be run for this scope at all.
     *
     * <p>False for a pull request with no checkout. {@link #diffRoot()} falls
     * back to the repository's main checkout there, so the only diff
     * obtainable would be that checkout's HEAD against the PR's base -- a
     * real diff of the wrong thing, which is the most misleading kind. Such a
     * scope is read through the checkout gate or the patch-only path, or not
     * at all.</p>
     */
    public boolean diffable() {
        return worktree.isPresent();
    }
```

In `ReviewDiffColumn.setScope`, refuse rather than trust the caller:

```java
        if (newScope != null && !newScope.diffable()) {
            throw new IllegalArgumentException(
                    "not diffable (no checkout): " + newScope.id());
        }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.review.ReviewScopeDiffabilityTest' --tests 'app.drydock.ui.review.*'`
Expected: PASS. `ReviewDestinationView.bodyFor` already routes a worktree-less scope to the gate (:707), so nothing reaches the new guard.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReviewScope.java \
        app/src/main/java/app/drydock/ui/review/ReviewDiffColumn.java \
        app/src/test/java/app/drydock/review/ReviewScopeDiffabilityTest.java
git commit -m "A pull request with no checkout cannot be diffed at all"
```

---

### Task 6: End-to-end, over real repositories

**Files:**
- Test: `app/src/test/java/app/drydock/review/ReviewQueueEndToEndTest.java` (create)

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: nothing.

This is the task the reported symptoms are graded against. It drives
`ReviewQueueService` over real temporary repositories and asserts the intents
each scope produces.

- [ ] **Step 1: Write the failing tests**

```java
package app.drydock.review;

import app.drydock.git.DiffService;
import app.drydock.git.DiffScope;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three reported cases, end to end over real git: local changes, a
 * develop-cut branch in a master-default repository, and a pull request
 * with and without a checkout.
 */
class ReviewQueueEndToEndTest {

    private final DiffService diffService = new DiffService();
    private final GitStatusService gitStatusService = new GitStatusService();
    private final IntentGrouping grouping = new IntentGrouping();

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void localChangesGroupIntoExactlyTheDirtyFiles() throws Exception {
        Path repo = Files.createTempDirectory("drydock-e2e-local");
        initRepo(repo, "main");
        write(repo, "A.java", "class A { int x = 1; }\n");
        write(repo, "B.java", "class B { int y = 1; }\n");
        write(repo, "C.java", "class C { int z = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        write(repo, "A.java", "class A { int x = 2; }\n");
        write(repo, "B.java", "class B { int y = 2; }\n");

        UnifiedDiff diff = diffService.diff(repo, DiffScope.WORKING_TREE, "main").get();
        List<ReviewIntent> intents = grouping.intentsFor("scope-local", diff);

        assertEquals(List.of("A.java", "B.java"),
                intents.stream().map(ReviewIntent::title).sorted().toList(),
                "the clean file must not become an intent");
    }

    @Test
    void aDevelopCutBranchDiffsAgainstDevelopNotMaster() throws Exception {
        Path repo = Files.createTempDirectory("drydock-e2e-base");
        initRepo(repo, "master");
        write(repo, "base.txt", "base\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "master");

        runGit(repo, "checkout", "-b", "develop");
        for (int i = 0; i < 8; i++) {
            write(repo, "infra" + i + ".txt", "infra\n");
            runGit(repo, "add", ".");
            runGit(repo, "commit", "-m", "infra " + i);
        }

        runGit(repo, "checkout", "-b", "feature/thing");
        write(repo, "feature.txt", "the feature\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "feature");

        var base = gitStatusService.reviewBase(repo, Optional.empty(), "master").get();
        assertEquals("develop", base.ref());

        UnifiedDiff diff = diffService.diff(repo, DiffScope.BASE, base.ref()).get();
        List<ReviewIntent> intents = grouping.intentsFor("scope-branch", diff);

        assertEquals(List.of("feature.txt"), intents.stream().map(ReviewIntent::title).toList(),
                "diffing against master would make this the whole of develop");
    }

    @Test
    void aCheckedOutPullRequestGroupsThePullRequestsFiles() throws Exception {
        Path repo = Files.createTempDirectory("drydock-e2e-pr");
        initRepo(repo, "main");
        write(repo, "base.txt", "base\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        runGit(repo, "checkout", "-b", "pr-7");
        write(repo, "changed-by-pr.txt", "pr\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "the pr");

        UnifiedDiff diff = diffService.diff(repo, DiffScope.BASE, "main").get();
        List<ReviewIntent> intents = grouping.intentsFor("scope-pr", diff);

        assertEquals(List.of("changed-by-pr.txt"), intents.stream().map(ReviewIntent::title).toList());
    }

    @Test
    void aPullRequestWithNoCheckoutHasNoIntentsAtAll() {
        ReviewScope gate = new ReviewScope("scope-gate", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty(), Optional.empty());

        assertTrue(intentsWithNoDiff(gate).isEmpty(),
                "there is no diff to group, and borrowing another scope's is the bug");
    }

    /** What the view does for a scope with no loaded diff: group nothing. */
    private List<ReviewIntent> intentsWithNoDiff(ReviewScope scope) {
        return grouping.intentsFor(scope.id(), new UnifiedDiff(List.of()));
    }

    private static void initRepo(Path repo, String branch) throws Exception {
        runGit(repo, "init", "-b", branch);
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
    }

    private static void write(Path repo, String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
```

- [ ] **Step 2: Run them**

Run: `./gradlew :app:test --tests 'app.drydock.review.ReviewQueueEndToEndTest'`
Expected: PASS — Tasks 1-5 are what make them pass. If `aDevelopCutBranchDiffsAgainstDevelopNotMaster` fails with `master`, Task 4's candidate loop is wrong; do not weaken the assertion.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/app/drydock/review/ReviewQueueEndToEndTest.java
git commit -m "End-to-end: the intents of a real repository are its real files"
```

---

# Part 3 — a Review destination that explains itself

### Task 7: The queue reports how complete its scan was

**Files:**
- Create: `app/src/main/java/app/drydock/review/QueueAssembly.java`
- Modify: `app/src/main/java/app/drydock/review/ReviewQueueService.java` (`assemble` 128-144)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`refreshReviewQueue` 786-806)
- Test: `app/src/test/java/app/drydock/review/ReviewQueueCompletenessTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `record QueueAssembly(List<ReviewItem> items, boolean localComplete, boolean requestsComplete)` with `boolean complete()`; `ReviewQueueService.assemble(...)` returns `CompletableFuture<QueueAssembly>`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A missing or unauthenticated gh does not fail the assembly -- every fetch
 * failure is absorbed into Fetch(value, complete=false), so the future
 * completes successfully with fewer items. That completeness is the only
 * signal an empty queue has, and it used to stay inside the service.
 */
class ReviewQueueCompletenessTest {

    @Test
    void anAssemblyIsCompleteOnlyWhenEverySourceAnswered() {
        assertTrue(new QueueAssembly(List.of(), true, true).complete());
        assertFalse(new QueueAssembly(List.of(), true, false).complete());
        assertFalse(new QueueAssembly(List.of(), false, true).complete());
    }

    @Test
    void anIncompleteAssemblyStillCarriesWhatItDidFind() {
        QueueAssembly assembly = new QueueAssembly(List.of(), true, false);

        assertTrue(assembly.items().isEmpty());
        assertTrue(assembly.localComplete(), "git answered even though gh did not");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.review.ReviewQueueCompletenessTest'`
Expected: FAIL to compile — `QueueAssembly` does not exist.

- [ ] **Step 3: Create `QueueAssembly` and return it**

```java
package app.drydock.review;

import java.util.List;
import java.util.Objects;

/**
 * A queue assembly: the items, and whether every source that feeds them
 * actually answered.
 *
 * <p>The completeness is the load-bearing part. {@code ReviewQueueService}
 * absorbs each fetch failure into a partial result rather than failing the
 * whole scan, so "no items" and "gh never answered" arrive down the same
 * successful path and are indistinguishable at the view -- which is how an
 * empty Review came to sit there claiming there was nothing to review.</p>
 */
public record QueueAssembly(List<ReviewItem> items, boolean localComplete, boolean requestsComplete) {

    public QueueAssembly {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
    }

    /** Whether every source answered; false means the queue may be missing rows. */
    public boolean complete() {
        return localComplete && requestsComplete;
    }
}
```

In `ReviewQueueService.assemble`, return it — the scans already carry both flags:

```java
    public CompletableFuture<QueueAssembly> assemble(List<RepositoryTarget> repositories,
                                                     SessionLookup sessions) {
        Objects.requireNonNull(sessions, "sessions");
        List<CompletableFuture<RepositoryScan>> perRepository = repositories.stream()
                .map(repository -> assembleRepository(repository, sessions))
                .toList();
        return CompletableFuture.allOf(perRepository.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<RepositoryScan> scans = perRepository.stream().map(CompletableFuture::join).toList();
                    List<ReviewItem> items = scans.stream()
                            .flatMap(scan -> scan.items().stream())
                            .sorted(Comparator.comparingInt(item -> item.group().ordinal()))
                            .toList();
                    revokeDepartedScopes(scans);
                    // One repository's failed gh makes the whole queue partial:
                    // the reader cannot be told "complete" about a list that is
                    // missing another repository's pull requests.
                    return new QueueAssembly(items,
                            scans.stream().allMatch(RepositoryScan::localComplete),
                            scans.stream().allMatch(RepositoryScan::requestsComplete));
                });
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.review.*'`
Expected: PASS. `ReviewQueueServiceTest` call sites need `.items()` added; make that change, do not weaken assertions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/QueueAssembly.java \
        app/src/main/java/app/drydock/review/ReviewQueueService.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/review/ReviewQueueServiceTest.java \
        app/src/test/java/app/drydock/review/ReviewQueueCompletenessTest.java
git commit -m "A queue assembly says which of its sources answered"
```

---

### Task 8: Four kinds of empty

**Files:**
- Create: `app/src/main/java/app/drydock/ui/review/ReviewEmptyState.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`setItems` 352-373, `showItem` 655-674, `Host`)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (`refreshReviewQueue`)
- Modify: `app/src/test/java/app/drydock/ui/review/FakeReviewHost.java`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewEmptyStateTest.java` (create)

**Interfaces:**
- Consumes: `QueueAssembly` (Task 7).
- Produces: `ReviewEmptyState` enum — `SCANNING`, `NOTHING_REVIEWABLE`, `SCAN_INCOMPLETE`, `NO_REPOSITORIES` — each with `title()` and `detail()`; `ReviewDestinationView.setItems(QueueAssembly assembly, int repositoryCount)`; `Host.retryQueueScan()`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.review.QueueAssembly;
import app.drydock.git.DiffService;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An empty Review has to say which empty it is. "gh did not answer, pull
 * requests may be missing" and "you have nothing to review" are opposite
 * messages, and rendering the first as the second is what made the surface
 * read as broken.
 */
class ReviewEmptyStateTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private FakeReviewHost host;
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-empty")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void anIncompleteScanSaysSoRatherThanClaimingNothingToReview() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, false), 2));

        assertEquals(ReviewEmptyState.SCAN_INCOMPLETE.title(), placeholderTitle());
        assertTrue(retryVisible(), "an incomplete scan offers a retry");
    }

    @Test
    void aCompleteScanWithNoItemsSaysThereIsNothingToReview() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), 2));

        assertEquals(ReviewEmptyState.NOTHING_REVIEWABLE.title(), placeholderTitle());
    }

    @Test
    void noRepositoriesIsItsOwnState() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), 0));

        assertEquals(ReviewEmptyState.NO_REPOSITORIES.title(), placeholderTitle());
    }

    @Test
    void retryAsksTheHostToScanAgain() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), false, false), 2));

        interact(() -> ((Button) lookup(".review-empty-retry").query()).fire());

        assertEquals(1, host.queueRetries);
    }

    @Test
    void anEmptyQueueRendersNoSessionRow() {
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, true), 2));

        assertTrue(lookup(".review-session-line").queryAll().stream()
                        .noneMatch(javafx.scene.Node::isManaged),
                "with no item there is no session to describe");
    }

    private String placeholderTitle() {
        String[] text = new String[1];
        interact(() -> text[0] = ((Label) lookup(".review-placeholder-title").query()).getText());
        return text[0];
    }

    private boolean retryVisible() {
        boolean[] visible = new boolean[1];
        interact(() -> visible[0] = lookup(".review-empty-retry").tryQuery().isPresent());
        return visible[0];
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewEmptyStateTest'`
Expected: FAIL to compile — `ReviewEmptyState`, the `QueueAssembly` overload and `host.queueRetries` do not exist.

- [ ] **Step 3: Create `ReviewEmptyState`**

```java
package app.drydock.ui.review;

/**
 * Why the Review queue is showing nothing. Four states, because "empty"
 * covers four situations a reader must act on differently: wait, add a
 * repository, retry, or make a change worth reviewing.
 */
public enum ReviewEmptyState {

    SCANNING("Scanning…",
            "Looking through your repositories for worktrees, uncommitted changes and pull requests."),

    NOTHING_REVIEWABLE("Nothing to review",
            "Worktrees, uncommitted changes and PRs that ask you for a review all land here. "
                    + "Check out a pull request, start an agent worktree, or make a local change."),

    SCAN_INCOMPLETE("Some sources did not answer",
            "The scan finished, but not every source answered — pull requests may be missing. "
                    + "This is usually the GitHub CLI being unavailable or unauthenticated."),

    NO_REPOSITORIES("No repositories",
            "Review works across the repositories in your sidebar. Add one to get started.");

    private final String title;
    private final String detail;

    ReviewEmptyState(String title, String detail) {
        this.title = title;
        this.detail = detail;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }
}
```

- [ ] **Step 4: Render it**

In `ReviewDestinationView`, add `void retryQueueScan();` to `Host`, and a field for the current state. Replace `setItems`:

```java
    /**
     * Replaces the queue's contents. The previous selection survives when its
     * scope is still present; otherwise the first item the rail is actually
     * showing is selected. An assembly with no items shows whichever empty
     * state the assembly's own completeness implies.
     */
    public void setItems(QueueAssembly assembly, int repositoryCount) {
        List<ReviewItem> items = assembly.items();
        String previous = queue.selected().map(item -> item.scope().id()).orElse(null);
        queue.setItems(items);
        outcomeByScope.keySet().retainAll(items.stream().map(item -> item.scope().id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        countsLabel.setText(items.size() + (items.size() == 1 ? " item · " : " items · ")
                + repositoryCount + (repositoryCount == 1 ? " repo" : " repos"));
        if (items.isEmpty()) {
            showEmpty(repositoryCount == 0
                    ? ReviewEmptyState.NO_REPOSITORIES
                    : assembly.complete()
                            ? ReviewEmptyState.NOTHING_REVIEWABLE
                            : ReviewEmptyState.SCAN_INCOMPLETE);
            return;
        }
        boolean stillThere = previous != null
                && items.stream().anyMatch(item -> item.scope().id().equals(previous));
        if (stillThere) {
            queue.select(previous);
            return;
        }
        queue.select(queue.firstVisible().map(item -> item.scope().id())
                .orElse(items.get(0).scope().id()));
    }

    /** Called when Review is shown and a scan is in flight. */
    public void showScanning() {
        showEmpty(ReviewEmptyState.SCANNING);
    }

    /**
     * The empty surface. The session row is not rendered at all here: with no
     * item there is no session to describe, and a row reading "no items in
     * the queue" beside a session dot read as a claim about the session
     * Review was opened from.
     */
    private void showEmpty(ReviewEmptyState state) {
        headerIcon.setText("◨");
        headerTitle.setText(state.title());
        headerContext.setText("");
        setSessionRowVisible(false);
        Region placeholder = placeholder(state.title(), state.detail(), "");
        if (state == ReviewEmptyState.SCAN_INCOMPLETE) {
            Button retry = new Button("Retry the scan");
            retry.getStyleClass().addAll("review-chip-button", "review-empty-retry");
            retry.setOnAction(e -> {
                retry.setDisable(true);
                retry.setText("Scanning…");
                host.retryQueueScan();
            });
            ((VBox) placeholder).getChildren().add(retry);
        }
        body.getChildren().setAll(placeholder);
        refreshReviewState();
    }
```

Add `setSessionRowVisible`, and call it from `setSessionRow`:

```java
    /** Hides the whole session row -- dot, line, button and hint. */
    private void setSessionRowVisible(boolean visible) {
        sessionDot.setVisible(visible);
        sessionDot.setManaged(visible);
        sessionLine.setVisible(visible);
        sessionLine.setManaged(visible);
        if (!visible) {
            openSessionButton.setVisible(false);
            openSessionButton.setManaged(false);
            returnHint.setVisible(false);
            returnHint.setManaged(false);
        }
    }
```

In `MainWorkspace`: implement `retryQueueScan()` as `refreshReviewQueue()`; call `reviewDestination.showScanning()` before `assemble` when the queue is currently empty; pass the assembly through; and make the `failure != null` branch reach the view rather than only the log:

```java
        reviewQueueService.assemble(targets, this::sessionAtCheckout)
                .whenComplete((assembly, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not assemble the Review queue", failure);
                        // A backstop, not the mechanism: assemble absorbs its
                        // own fetch failures, so this fires only for something
                        // unforeseen -- which is all the more reason to show it.
                        reviewDestination.setItems(new QueueAssembly(List.of(), false, false),
                                local.size());
                        return;
                    }
                    adoptLegacyAnnotations(assembly.items());
                    reviewDestination.setItems(assembly, local.size());
                    ...
```

In `FakeReviewHost`, add:

```java
    /** How many times the empty state's Retry asked for a rescan. */
    int queueRetries;

    @Override
    public void retryQueueScan() {
        queueRetries++;
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*'`
Expected: PASS. Existing tests calling `setItems(List, int)` need updating to `setItems(new QueueAssembly(items, true, true), n)`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewEmptyState.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/ui/review/FakeReviewHost.java \
        app/src/test/java/app/drydock/ui/review/ReviewEmptyStateTest.java
git commit -m "An empty Review says which kind of empty it is, and offers a way on"
```

---

### Task 9: A destination you can tell you entered

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`buildTitleBar` 262-292)
- Modify: `app/src/main/resources/app/drydock/ui/app.css`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewTitleBarTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `Host.leaveReview()`.

Note the constraint settled in the spec: the way out is `Esc`, and only
`Esc`. `⌘4` is the way *in* (`DrydockApplication.java:826` routes it
unconditionally to `showReviewForCurrentSession()`), so the chrome must not
name it as an exit.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.git.DiffService;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review is a place you entered, and the chrome has to say how to leave. */
class ReviewTitleBarTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private FakeReviewHost host;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-titlebar")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ReviewDestinationView view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void theExitIsLabelledEscAndOnlyEsc() {
        Button leave = lookup(".review-leave").query();

        assertEquals("Leave Review ⎋", leave.getText());
        assertTrue(leave.getTooltip().getText().contains("Esc"));
        assertTrue(!leave.getTooltip().getText().contains("⌘4"),
                "⌘4 enters Review; naming it here would advertise a dead key");
    }

    @Test
    void leavingAsksTheHost() {
        interact(() -> ((Button) lookup(".review-leave").query()).fire());

        assertEquals(1, host.leaveRequests);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewTitleBarTest'`
Expected: FAIL — no node matches `.review-leave`.

- [ ] **Step 3: Add the control**

In `buildTitleBar`, before the shortcuts button:

```java
        Button leave = new Button("Leave Review ⎋");
        leave.getStyleClass().addAll("review-chip-button", "review-leave");
        leave.setTooltip(new Tooltip("Leave Review and return to your session (Esc)"));
        leave.setOnAction(e -> host.leaveReview());
```

and include it in the `HBox`. Add `void leaveReview();` to `Host`;
`MainWorkspace` implements it as `hideReview()`; `FakeReviewHost` counts it:

```java
    /** How many times the title bar's exit was used. */
    int leaveRequests;

    @Override
    public void leaveReview() {
        leaveRequests++;
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/ui/review/FakeReviewHost.java \
        app/src/test/java/app/drydock/ui/review/ReviewTitleBarTest.java
git commit -m "Review says how to leave, and Esc is how"
```

---

# Part 2 — a narrow window with an obvious next move

### Task 10: The code column never disappears

**Files:**
- Create: `app/src/main/java/app/drydock/ui/review/RailLayout.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (constants 49-53, `applyResponsiveLayout` 815-822)
- Test: `app/src/test/java/app/drydock/ui/review/RailLayoutTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `RailLayout.solve(double width, boolean queueForced, boolean intentsForced, boolean marginForced)` returning `record Layout(boolean queueCollapsed, boolean intentsCollapsed, boolean marginCollapsed, boolean narrow)`; constants `CODE_MIN_WIDTH = 560`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One invariant in place of four thresholds: there is always readable code
 * on screen. The rails used to collapse on independent width triggers while
 * the code column had no claim on space at all, so at ~1200px the rails were
 * the only thing left and the intent card was the only thing to click.
 */
class RailLayoutTest {

    @Test
    void aWideWindowCollapsesNothingAndStaysFullWidth() {
        // Expanded rails are 236 + 232 + 336 = 804; 1800 leaves 996 for code.
        RailLayout.Layout layout = RailLayout.solve(1800, false, false, false);

        assertFalse(layout.queueCollapsed());
        assertFalse(layout.intentsCollapsed());
        assertFalse(layout.marginCollapsed());
        assertFalse(layout.narrow());
    }

    @Test
    void narrowingTheRailsIsTriedBeforeCollapsingAnything() {
        // Expanded would leave 1300 - 804 = 496, under the floor. Narrow rails
        // are 206 + 196 + 286 = 688, leaving 612 -- so nothing has to go.
        RailLayout.Layout layout = RailLayout.solve(1300, false, false, false);

        assertTrue(layout.narrow());
        assertFalse(layout.marginCollapsed(), "narrowing bought enough width on its own");
    }

    @Test
    void theMarginIsTheFirstToGo() {
        // Narrow rails 688 leave 1200 - 688 = 512, under the floor. Collapsing
        // the margin gives 206 + 196 + 30 = 432, leaving 768.
        RailLayout.Layout layout = RailLayout.solve(1200, false, false, false);

        assertTrue(layout.marginCollapsed(), "the margin collapses first");
        assertFalse(layout.intentsCollapsed());
        assertFalse(layout.queueCollapsed());
    }

    @Test
    void thenTheIntentsAndOnlyThenTheQueue() {
        // 950 - 432 = 518, under the floor; collapsing intents gives 276.
        RailLayout.Layout intents = RailLayout.solve(950, false, false, false);
        assertTrue(intents.marginCollapsed());
        assertTrue(intents.intentsCollapsed());
        assertFalse(intents.queueCollapsed(), "the queue still has room here");

        // 800 - 276 = 524, under the floor; the queue is the last to go.
        RailLayout.Layout all = RailLayout.solve(800, false, false, false);
        assertTrue(all.queueCollapsed(), "the queue is the last to give up its width");
    }

    @Test
    void theCodeColumnClearsItsFloorWheneverArithmeticAllows() {
        for (double width = 700; width <= 2000; width += 10) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false, false);
            double used = RailLayout.railsWidth(layout);
            assertTrue(width - used >= RailLayout.CODE_MIN_WIDTH || allCollapsed(layout),
                    "at " + width + "px the code column got " + (width - used));
        }
    }

    @Test
    void aManualCollapseIsHonouredEvenWhenThereIsRoom() {
        RailLayout.Layout layout = RailLayout.solve(1800, true, false, false);

        assertTrue(layout.queueCollapsed(), "the user's own collapse survives a wide window");
        assertFalse(layout.intentsCollapsed());
    }

    @Test
    void collapseIsMonotonicInWidth() {
        // A wider window may never be more collapsed than a narrower one.
        // The rule this pins down: narrowing is tried before collapsing, so
        // there is no width at which widening the window loses you a rail.
        RailLayout.Layout previous = RailLayout.solve(600, false, false, false);
        for (double width = 610; width <= 2000; width += 10) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false, false);
            assertTrue(collapsedCount(layout) <= collapsedCount(previous),
                    "widening to " + width + "px collapsed something that was open");
            previous = layout;
        }
    }

    private static int collapsedCount(RailLayout.Layout layout) {
        return (layout.queueCollapsed() ? 1 : 0) + (layout.intentsCollapsed() ? 1 : 0)
                + (layout.marginCollapsed() ? 1 : 0);
    }

    private static boolean allCollapsed(RailLayout.Layout layout) {
        return layout.queueCollapsed() && layout.intentsCollapsed() && layout.marginCollapsed();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.RailLayoutTest'`
Expected: FAIL to compile — `RailLayout` does not exist.

- [ ] **Step 3: Create `RailLayout`**

```java
package app.drydock.ui.review;

/**
 * Which rails are collapsed at a given window width.
 *
 * <p>Pure arithmetic, deliberately: this used to be four independent width
 * thresholds inside the view, and the code column -- the only thing anyone
 * opened Review to read -- had no claim on space at all. One rule replaces
 * them: rails give up their width, in a fixed order, until the code column
 * clears {@link #CODE_MIN_WIDTH}.</p>
 */
public final class RailLayout {

    /**
     * The narrowest the code column may be. Wide enough for a unified diff
     * line at the default density without wrapping, which is the point below
     * which the column stops doing its job.
     */
    public static final double CODE_MIN_WIDTH = 560;

    private RailLayout() {
    }

    /** Which rails are collapsed, and whether the rest are in narrow mode. */
    public record Layout(boolean queueCollapsed, boolean intentsCollapsed,
                         boolean marginCollapsed, boolean narrow) { }

    /**
     * Gives up rail width in escalating steps until the code column clears
     * its floor: narrow the rails first, then collapse the margin, then the
     * intents, then the queue. A rail the user collapsed by hand starts
     * collapsed and stays that way however wide the window is.
     *
     * <p>Narrowing comes before any collapse, and that ordering is what makes
     * the result monotonic in width. The previous design took narrow mode
     * from its own fixed threshold, which produced the absurd case of
     * widening the window from 1300px to 1320px and <em>losing</em> the
     * findings margin -- the rails jumped from their narrow widths to their
     * expanded ones and no longer fitted.</p>
     */
    public static Layout solve(double width, boolean queueForced, boolean intentsForced,
                               boolean marginForced) {
        boolean margin = marginForced;
        boolean intents = intentsForced;
        boolean queue = queueForced;

        if (fits(width, queue, intents, margin, false)) {
            return new Layout(queue, intents, margin, false);
        }
        if (fits(width, queue, intents, margin, true)) {
            return new Layout(queue, intents, margin, true);
        }
        margin = true;
        if (!fits(width, queue, intents, margin, true)) {
            intents = true;
        }
        if (!fits(width, queue, intents, margin, true)) {
            queue = true;
        }
        return new Layout(queue, intents, margin, true);
    }

    private static boolean fits(double width, boolean queue, boolean intents, boolean margin,
                                boolean narrow) {
        return width - railsWidth(new Layout(queue, intents, margin, narrow)) >= CODE_MIN_WIDTH;
    }

    /** The total width the rails occupy under {@code layout}. */
    public static double railsWidth(Layout layout) {
        return railWidth(layout.queueCollapsed(), layout.narrow(),
                        ReviewQueueRail.COLLAPSED_WIDTH, ReviewQueueRail.NARROW_WIDTH,
                        ReviewQueueRail.EXPANDED_WIDTH)
                + railWidth(layout.intentsCollapsed(), layout.narrow(),
                        ReviewIntentRail.COLLAPSED_WIDTH, ReviewIntentRail.NARROW_WIDTH,
                        ReviewIntentRail.EXPANDED_WIDTH)
                + railWidth(layout.marginCollapsed(), layout.narrow(),
                        ReviewFindingsMargin.COLLAPSED_WIDTH, ReviewFindingsMargin.NARROW_WIDTH,
                        ReviewFindingsMargin.EXPANDED_WIDTH);
    }

    private static double railWidth(boolean collapsed, boolean narrow, double collapsedWidth,
                                    double narrowWidth, double expandedWidth) {
        if (collapsed) {
            return collapsedWidth;
        }
        return narrow ? narrowWidth : expandedWidth;
    }
}
```

The three rails' width constants are already `static final` and package-private (`ReviewQueueRail.java:52-54`, `ReviewIntentRail.java:40-42`, `ReviewFindingsMargin.java:50-52`); no visibility change is needed since `RailLayout` is in the same package.

- [ ] **Step 4: Apply it in the view**

Delete `NARROW_WIDTH`, `QUEUE_COLLAPSE_WIDTH`, `INTENT_COLLAPSE_WIDTH` and `MARGIN_COLLAPSE_WIDTH` from `ReviewDestinationView` and replace `applyResponsiveLayout`:

```java
    /**
     * Rails give up their width, margin first and queue last, until the code
     * column clears its floor (spec §4.9). A manual collapse is remembered
     * separately: a user who collapsed the queue keeps it collapsed when the
     * window grows back, and one who did not gets it back.
     */
    private void applyResponsiveLayout(double width) {
        RailLayout.Layout layout = RailLayout.solve(width, queueCollapsedByUser,
                intentsCollapsedByUser, marginCollapsedByUser);
        queue.setNarrow(layout.narrow());
        queue.setCollapsed(layout.queueCollapsed());
        intentRail.setNarrow(layout.narrow());
        intentRail.setCollapsed(layout.intentsCollapsed());
        margin.setNarrow(layout.narrow());
        margin.setCollapsed(layout.marginCollapsed());
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/RailLayout.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/test/java/app/drydock/ui/review/RailLayoutTest.java
git commit -m "Rails give up their width before the code column does"
```

---

### Task 11: Selecting an item lands on its first intent

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`showItem` 655-674)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewLandsOnFirstIntentTest.java` (create)

**Interfaces:**
- Consumes: the `setOnDiffResolved` wiring from Task 2, which already calls `revealCurrentIntent()` when the selected scope's diff lands.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selecting a queue item used to leave the centre wherever the previous item
 * had scrolled it, so clicking an intent card was the only thing that ever
 * moved the code -- which is why the rail read as the only live surface.
 */
class ReviewLandsOnFirstIntentTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-land")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void comingBackToAnItemLandsOnItsFirstIntentNotWhereYouLeftIt() throws Exception {
        Path repo = repoWithTwoFilesFarApart();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        Path otherRepo = repoWithTwoFilesFarApart();
        ReviewScope other = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, otherRepo, Optional.of(otherRepo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(
                new ReviewItem(scope, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted"),
                new ReviewItem(other, ReviewItem.Group.MINE, "Other tree", "other · uncommitted")),
                true, true), 2));
        awaitCardCount(2);

        // Walk to the second intent, which scrolls the column to Zulu.java.
        List<javafx.scene.Node> cards = new ArrayList<>(lookup(".review-intent-card").queryAll());
        interact(((javafx.scene.control.Button) cards.get(1))::fire);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        assertTrue(renderedHunkFiles().contains("Zulu.java"), "precondition: moved off intent 1");

        // Leave for another item and come back. Selecting an item must land
        // on its first intent, not leave the column where the last read of
        // it happened to stop.
        interact(() -> view.selectScope(other.id()));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        interact(() -> view.selectScope(scope.id()));
        awaitCardCount(2);

        assertTrue(renderedHunkFiles().contains("Alpha.java"),
                "coming back lands on the first intent; rendered " + renderedHunkFiles());
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    private void awaitCardCount(int expected) {
        for (int i = 0; i < 200; i++) {
            int[] count = new int[1];
            interact(() -> count[0] = lookup(".review-intent-card").queryAll().size());
            if (count[0] == expected) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("never reached " + expected + " intent cards");
    }

    /**
     * Two changed files far enough apart that the second starts below the
     * viewport -- the same fixture shape ReviewIntentFallbackTest uses, and
     * the reason it can tell "scrolled to Alpha" from "scrolled to Zulu".
     */
    private static Path repoWithTwoFilesFarApart() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-land-repo").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        for (String name : List.of("Alpha.java", "Zulu.java")) {
            StringBuilder original = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                original.append("int field").append(i).append(" = ").append(i).append(";\n");
            }
            Files.writeString(repo.resolve(name), original.toString());
        }
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "two files");
        for (String name : List.of("Alpha.java", "Zulu.java")) {
            StringBuilder changed = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                changed.append("int field").append(i).append(" = ").append(i * 2).append(";\n");
            }
            Files.writeString(repo.resolve(name), changed.toString());
        }
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewLandsOnFirstIntentTest'`
Expected: FAIL — after coming back, the column is still showing `Zulu.java`, because `showItem` resets `intentIndex` to 0 without moving the code. A test that merely asserted "intent 1's file is on screen for a freshly opened item" would pass *before* the fix (a new column starts at the top), which is why this one deliberately scrolls away first.

- [ ] **Step 3: Reveal on selection**

At the end of `showItem`, after `refreshReviewState()`:

```java
        intentIndex = 0;
        refreshReviewState();
        // The diff usually has not arrived yet, in which case there is nothing
        // to reveal and the setOnDiffResolved handler does it when it lands.
        // Revealing here too covers the case where it already has -- coming
        // back to an item whose diff is still cached.
        revealCurrentIntent();
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/test/java/app/drydock/ui/review/ReviewLandsOnFirstIntentTest.java
git commit -m "Selecting an item lands on its first intent's code"
```

---

### Task 12: A verdict bar that is a whole review loop

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewVerdictBar.java`
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`VerdictHost`, `renderVerdictBar` 459-481)
- Modify: `app/src/main/resources/app/drydock/ui/app.css`
- Test: `app/src/test/java/app/drydock/ui/review/ReviewVerdictBarNavigationTest.java` (create)

**Interfaces:**
- Consumes: `moveIntent(int)` and `nextUnsettledIntent()`, already private in the view.
- Produces: `ReviewVerdictBar.Host.previousIntent()` and `nextIntent()`; the bar's `update(...)` renders `intent.number() + " · " + intent.title()`.

- [ ] **Step 1: Write the failing test**

```java
package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With every rail collapsed the verdict bar is the only surface left, so it
 * has to be a complete loop on its own: say which intent it is settling, and
 * move between them without the keyboard.
 */
class ReviewVerdictBarNavigationTest extends ApplicationTest {

    private final List<String> calls = new ArrayList<>();
    private ReviewVerdictBar bar;

    @Override
    public void start(Stage stage) {
        bar = new ReviewVerdictBar(new ReviewVerdictBar.Host() {
            @Override public void approve(ReviewIntent intent) { calls.add("approve"); }
            @Override public void requestChanges(ReviewIntent intent) { calls.add("changes"); }
            @Override public void askAgentToFix(ReviewIntent intent) { calls.add("ask"); }
            @Override public void undo(ReviewIntent intent) { calls.add("undo"); }
            @Override public void nextUnsettled() { calls.add("nextUnsettled"); }
            @Override public void submit() { calls.add("submit"); }
            @Override public void previousIntent() { calls.add("previous"); }
            @Override public void nextIntent() { calls.add("next"); }
        });
        Scene scene = new Scene(bar, 900, 200);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void theBarNamesTheIntentItIsSettling() {
        interact(() -> bar.update(intent(2, "Rename the parser"), Optional.empty(), false, 1, 4));

        assertEquals("2 · Rename the parser",
                ((Label) lookup(".review-verdict-intent").query()).getText());
    }

    @Test
    void theNavigationControlsReachTheSameActionsAsTheKeys() {
        interact(() -> bar.update(intent(2, "Rename the parser"), Optional.empty(), false, 1, 4));

        interact(() -> ((Button) lookup(".review-verdict-previous").query()).fire());
        interact(() -> ((Button) lookup(".review-verdict-next").query()).fire());

        assertEquals(List.of("previous", "next"), calls);
    }

    @Test
    void withNoIntentTheBarSaysSoAndDisablesNavigation() {
        interact(() -> bar.update(null, Optional.empty(), false, 0, 0));

        assertEquals("no intent", ((Label) lookup(".review-verdict-intent").query()).getText());
        assertTrue(((Button) lookup(".review-verdict-next").query()).isDisabled());
    }

    private static ReviewIntent intent(int number, String title) {
        return new ReviewIntent("intent-" + number, number, title, ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.LOW, "", List.of(), Optional.empty(), false);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewVerdictBarNavigationTest'`
Expected: FAIL to compile — `Host.previousIntent()` / `nextIntent()` do not exist.

- [ ] **Step 3: Add the controls**

In `ReviewVerdictBar.Host`, add:

```java
        /** {@code [} -- the intent before this one. */
        void previousIntent();

        /** {@code ]} -- the intent after this one. */
        void nextIntent();
```

Add the buttons beside `intentLabel`:

```java
    private final Button previousButton = new Button("‹");
    private final Button nextButton = new Button("›");
```

wired in the constructor:

```java
        previousButton.getStyleClass().addAll("review-verdict-nav", "review-verdict-previous");
        previousButton.setTooltip(new Tooltip("Previous intent ([)"));
        previousButton.setOnAction(e -> host.previousIntent());

        nextButton.getStyleClass().addAll("review-verdict-nav", "review-verdict-next");
        nextButton.setTooltip(new Tooltip("Next intent (])"));
        nextButton.setOnAction(e -> host.nextIntent());
```

**The change goes in `render()`, not `update()`, and the buttons must be
added to all three child lists.** `update(...)` ends by calling `render()`
(`ReviewVerdictBar.java:143`), and `render()` rebuilds `actionRow` from
scratch with three exhaustive `setAll(...)` calls — so anything set in
`update` is overwritten, and any node not named in a `setAll` list never
enters the scene graph at all.

In `render()`, the null-intent branch (`:147-153`) becomes:

```java
        if (intent == null) {
            intentLabel.setText("no intent");
            previousButton.setDisable(true);
            nextButton.setDisable(true);
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel);
            progressLabel.setText("");
            progressFill.setPrefWidth(0);
            submitButton.setDisable(true);
            return;
        }
        intentLabel.setText(intent.number() + " · " + intent.title());
        previousButton.setDisable(false);
        nextButton.setDisable(false);
```

and both remaining `setAll` calls (`:168` and `:175-176`) gain the two
buttons at the front:

```java
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    settledLabel, undoButton, spacer, right);
```

```java
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    approveButton, requestChangesButton, askAgentButton, refusalLabel,
                    spacer, right);
```

**The label format change breaks nine existing assertions.** The bar used to
render `"intent " + intent.number()`; it now renders `number · title`. Update
every site — `ReviewFindingsAndVerdictsTest` (seven), `ReviewIntentFallbackTest`
(two) — and Task 11's `ReviewLandsOnFirstIntentTest` if it still asserts on
the label. Find them all:

```bash
grep -rn '"intent ' app/src/test/java
```

The fallback intents are titled after their file, so
`ReviewIntentFallbackTest`'s `"intent 1"` becomes `"1 · A.java"`.

In `app.css`:

```css
.review-verdict-nav {
    -fx-min-width: 24px;
    -fx-padding: 2 6 2 6;
    -fx-font-size: 13px;
}
```

In `ReviewDestinationView.VerdictHost`:

```java
        @Override
        public void previousIntent() {
            moveIntent(-1);
        }

        @Override
        public void nextIntent() {
            moveIntent(1);
        }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.review.*'`
Expected: PASS. `ReviewFindingsAndVerdictsTest` implements `ReviewVerdictBar.Host`; add the two methods there too.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewVerdictBar.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui/review/ReviewVerdictBarNavigationTest.java \
        app/src/test/java/app/drydock/ui/review/ReviewFindingsAndVerdictsTest.java \
        app/src/test/java/app/drydock/ui/review/ReviewIntentFallbackTest.java \
        app/src/test/java/app/drydock/ui/review/ReviewLandsOnFirstIntentTest.java
git commit -m "The verdict bar is a review loop on its own"
```

---

### Task 13: Confirm it in the running app

**Files:** none — this is verification, not code.

- [ ] **Step 1: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS, no skips in `app.drydock.review.*` or `app.drydock.ui.review.*`.

- [ ] **Step 2: Capture the running app**

Two routes, and the choice is about what is being shown. Both are valid;
`screencapture` is the one that does not work.

- **Screen recording** for anything with motion or a sequence: narrowing the
  window and watching the rails give up their width in order is a recording,
  not four snapshots.
- **`shot:` scene snapshot** for a single settled state, where a still is
  easier to compare against the previous one.

Capture, against a repository with a `develop`-cut branch:

1. **Recording** — select a worktree item, then a not-checked-out PR, then
   the worktree again. The rail must follow the header every time: its files,
   then "Not checked out — check out to group changes", then its files again.
   This is the reported bug, and its absence is only convincing in motion.
2. **Recording** — drag the window from wide to ~700px. The margin collapses,
   then the intents, then the queue, and the code column stays readable
   throughout. Then drag it back and watch them return in reverse.
3. **Snapshot** — the window at ~1100px, verdict bar naming its intent and
   carrying its `‹ ›` controls.
4. **Snapshot** — Review with an empty queue, in each of the four states that
   can be produced without breaking the environment (`NOTHING_REVIEWABLE` and
   `NO_REPOSITORIES` are reachable directly; `SCAN_INCOMPLETE` by making `gh`
   unavailable on `PATH` for one launch).

- [ ] **Step 3: Report honestly**

State which of the four snapshots confirmed the change and which did not. A snapshot that could not be taken is reported as not taken, never as passing.

- [ ] **Step 4: Commit any snapshot artefacts the harness produces**

```bash
git add -A docs/
git commit -m "Visual confirmation of the Review correctness and orientation work"
```

---

## Self-Review

**Spec coverage:**

| Spec item | Task |
|---|---|
| P1.1 publish `(scopeId, outcome)`, failure included | 1 |
| P1.2 per-scope diffs; `intents` looks up that scope | 2 |
| P1.3 `intentsFor` returns empty, never another scope's | 2 |
| P1.4 four rail empty states | 3 |
| P1.5 base fallback logged | 4 |
| P1.6 base carries provenance, header shows it | 4 |
| P1.7 PR with no worktree is not diffable | 5 |
| P1 tests (local, develop-cut, PR ±checkout, cross-repo) | 6 |
| P3a destination chrome, `Esc` only | 9 |
| P3b session row absent with no item | 8 |
| P3c completeness surfaced; four states; Retry | 7, 8 |
| P3d "nothing reviewable" explains Review | 8 |
| P2a code floor + collapse priority | 10 |
| P2b land on intent 1 | 11 |
| P2c verdict bar names the intent, carries nav | 12 |

Base-resolution-failure provenance is asserted in Task 4's
`anUnmeasurableRepositoryFallsBackAndAdmitsIt`; the cross-repository case is
covered structurally by Task 2's isolation tests, which is stronger than a
two-repo fixture — a scope with no diff of its own cannot show any files,
whatever repository they would have come from.

**Placeholder scan:** none. Every code step carries the code.

**Type consistency:** `DiffOutcome` is `Diffing` / `Loaded(diff)` /
`Failed(message)` throughout. `setOnDiffResolved(BiConsumer<String,
DiffOutcome>)` is used with that exact name in Tasks 1, 2 and 11.
`Host.intents(ReviewScope, UnifiedDiff)` is consistent in Task 2 across the
interface, `MainWorkspace` and `FakeReviewHost`. `QueueAssembly(items,
localComplete, requestsComplete)` matches between Tasks 7 and 8.
`ReviewIntentRail.Empty` values match between Tasks 3 and 2's assertion
string. `RailLayout.solve(width, queueForced, intentsForced, marginForced)`
matches between Tasks 10's test and its use in the view.
