# Browser Tree Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Declutter the repository sidebar — collapse stale/detached worktrees into one bucket, band each repo's children live-first, give sessions/worktrees/stale distinct row shapes on one grid, fix the repo-header count bug, and add `⌘↑/⌘↓` live-session cycling.

**Architecture:** Extract the row-ordering + stale-classification + count logic out of `RepositorySidebar.childNodesFor` into a pure, toolkit-free helper `SidebarChildren` (a record + a static `classify(...)`), unit-tested with plain JUnit. `RepositorySidebar` becomes a thin adapter that wraps the classified bands into `TreeItem`s and renders them. FX row construction stays in `RepositorySidebar` and is verified on screen (the spec and AGENTS.md keep visual rows out of unit tests).

**Tech Stack:** Java 21 (records, sealed interfaces, pattern-switch), JavaFX (TreeView/TreeCell), JUnit 5 (`junit-jupiter`, already on the test classpath), Gradle.

## Global Constraints

- **No FQNs:** never inline fully-qualified Java class names; use `import`s (sole exception: same-name-different-package collisions). (Matches repo memory + AGENTS.md.)
- **No blocking on the FX thread:** every `git`/`gh` spawn or filesystem probe runs on a background executor and hops back via `Platform.runLater`; every user-triggered async action shows immediate progress and clears it on *every* completion path (success, error, early return). (AGENTS.md.)
- **Advertised ⟺ bound:** anything in `ShortcutsOverlay` must be really bound, and vice versa. (AGENTS.md.)
- **No `Animation.INDEFINITE` without a stop path** tied to the node's scene lifecycle. (AGENTS.md — relevant to the status dot.)
- **Shared presentation logic stays in one place** (`UiFormats`, `SessionStatusStyles`); no per-view copies. (AGENTS.md.)
- **Stale rule (verbatim from spec §2), exemption-first:** `!locked && !mainCheckout && !hasSession && (prunable || detached)`. A worktree carrying a session (running or idle), a locked worktree, and the main checkout are **never** stale.
- **Spec:** `docs/superpowers/specs/2026-07-23-browser-tree-redesign-design.md`.

---

## File Structure

- **Create** `app/src/main/java/app/drydock/ui/SidebarChildren.java` — pure value object + `classify(...)`. No JavaFX, no `WorkspaceViewModel` imports. One responsibility: turn (worktrees, sessions, activity-lookup) into ordered bands + stale bucket + counts.
- **Create** `app/src/test/java/app/drydock/ui/SidebarChildrenTest.java` — JUnit coverage of banding, stale rule, sort keys, counts.
- **Modify** `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — new `StaleWorktreesNode`; `childNodesFor` delegates to `classify`; delete `additionalWorktreeCount`; header counts + label split from `classify`; stale bucket row + `staleBucketExpanded` set + `Clean ↺` batch handler; `⌘↑/⌘↓` handling; hollow/filled dot call.
- **Modify** `app/src/main/java/app/drydock/ui/SessionStatusStyles.java` — `createDot` gains a `filled` variant.
- **Modify** `app/src/main/java/app/drydock/DrydockApplication.java` — `⌘↑/⌘↓` bindings in the existing `cmd`-key filter (~line 528).
- **Modify** `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` — new shortcut row + `⌘⇧[ / ⌘⇧]` → `⌘[ / ⌘]` correction.
- **Modify** `app/src/main/resources/app/drydock/ui/app.css` (+ `theme-dark.css`, `theme-light.css` only if a token is missing) — fixed gutter/grid, hollow dot, stale-bucket + open-worktree row styles.

---

### Task 1: `SidebarChildren` — pure classification, banding, counts

This is the whole logic core, and the only fully unit-tested task. Everything downstream is a thin adapter over it.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/SidebarChildren.java`
- Test: `app/src/test/java/app/drydock/ui/SidebarChildrenTest.java`

**Interfaces:**
- Consumes: `app.drydock.git.WorktreeService.Worktree` (`record Worktree(Path path, Optional<String> branch, boolean mainCheckout, boolean detached, boolean prunable, boolean locked)`); `app.drydock.domain.ManagedClaudeSession` (fields used: `ManagedSessionId id()`, `Optional<Path> worktreeRoot()`, `SessionStatus status()`, `Instant lastOpenedAt()`); `app.drydock.domain.SessionActivity` (`NEEDS_ATTENTION`, …); `app.drydock.domain.SessionStatus`; `app.drydock.domain.ManagedSessionId`.
- Produces (relied on by Task 2):
  - `static SidebarChildren classify(List<Worktree> worktrees, List<ManagedClaudeSession> sessions, Function<ManagedSessionId, SessionActivity> activityOf)`
  - record `SidebarChildren(List<ManagedClaudeSession> liveSessions, List<ManagedClaudeSession> idleSessions, List<Worktree> openWorktrees, List<Worktree> staleWorktrees, int worktreeCount, int staleCount)`
  - convenience `List<ManagedClaudeSession> orderedSessions()` = `liveSessions` followed by `idleSessions`.

Matching semantics are **preserved exactly** from today's `childNodesFor` (`RepositorySidebar.java:640-674`): main checkout yields its `worktreeRoot().isEmpty()` sessions, or one main open-worktree row if none; each non-main worktree matches the first session whose `worktreeRoot()` equals its path, else an unopened row; sessions whose worktree dir is gone (`worktreeRoot()` present but not in the list) are appended. Only ordering, the stale split, and the counts are new.

- [ ] **Step 1: Write the failing test file**

```java
package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService.Worktree;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SidebarChildrenTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final Path ROOT = Path.of("/repo");

    private static Worktree main() {
        return new Worktree(ROOT, Optional.of("main"), true, false, false, false);
    }

    private static Worktree wt(String branch, boolean detached, boolean prunable, boolean locked) {
        return new Worktree(Path.of("/wt/" + (branch == null ? "x" : branch)),
                Optional.ofNullable(branch), false, detached, prunable, locked);
    }

    private static ManagedClaudeSession session(String name, Path worktreeRoot,
            SessionStatus status, Instant lastOpened) {
        return new ManagedClaudeSession(ManagedSessionId.newId(), REPO, name,
                Optional.empty(), Optional.empty(),
                worktreeRoot == null ? ROOT : worktreeRoot,
                Optional.ofNullable(worktreeRoot),
                status, Instant.EPOCH, lastOpened, Optional.empty(),
                PrState.NONE, Optional.empty(), false);
    }

    private static Function<ManagedSessionId, SessionActivity> noActivity() {
        return id -> SessionActivity.UNKNOWN;
    }

    @Test
    void banding_putsLiveSessionsBeforeIdle() {
        ManagedClaudeSession live = session("live", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedClaudeSession idle = session("idle", null,
                SessionStatus.INACTIVE, Instant.ofEpochSecond(20));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false)),
                List.of(live, idle), noActivity());
        assertEquals(List.of("live"), result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
        assertEquals(List.of("idle"), result.idleSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void liveBand_sortsMostRecentFirst() {
        ManagedClaudeSession older = session("older", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedClaudeSession newer = session("newer", Path.of("/wt/b"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(30));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(older, newer), noActivity());
        assertEquals(List.of("newer", "older"),
                result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void liveBand_pinsNeedsAttentionFirst() {
        ManagedClaudeSession fresh = session("fresh", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(50));
        ManagedClaudeSession waiting = session("waiting", Path.of("/wt/b"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        Map<ManagedSessionId, SessionActivity> activity =
                Map.of(waiting.id(), SessionActivity.NEEDS_ATTENTION);
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(fresh, waiting),
                id -> activity.getOrDefault(id, SessionActivity.UNKNOWN));
        assertEquals(List.of("waiting", "fresh"),
                result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void staleRule_prunableNoSession_isStale() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("gone", false, true, false)),
                List.of(), noActivity());
        assertEquals(1, result.staleWorktrees().size());
        assertTrue(result.openWorktrees().stream().noneMatch(w -> !w.mainCheckout()));
    }

    @Test
    void staleRule_detachedNoSession_isStale() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt(null, true, false, false)),
                List.of(), noActivity());
        assertEquals(1, result.staleWorktrees().size());
    }

    @Test
    void staleRule_prunableWithSession_neverStale_staysASession() {
        ManagedClaudeSession onStale = session("onstale", Path.of("/wt/gone"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("gone", false, true, false)),
                List.of(onStale), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(List.of("onstale"),
                result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void staleRule_locked_neverStale() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt(null, true, true, true)),
                List.of(), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(1, result.openWorktrees().stream().filter(w -> !w.mainCheckout()).count());
    }

    @Test
    void staleRule_mainCheckout_neverStale() {
        Worktree detachedMain = new Worktree(ROOT, Optional.empty(), true, true, false, false);
        SidebarChildren result = SidebarChildren.classify(
                List.of(detachedMain), List.of(), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
    }

    @Test
    void openWorktrees_sortByBranchThenBranchlessByPath() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("zebra", false, false, false),
                        wt("alpha", false, false, false), wt(null, false, false, true)),
                List.of(), noActivity());
        List<String> order = result.openWorktrees().stream()
                .filter(w -> !w.mainCheckout())
                .map(w -> w.branch().orElse("(" + w.path().getFileName() + ")")).toList();
        assertEquals(List.of("alpha", "zebra", "(x)"), order);
    }

    @Test
    void counts_wtIncludesSessionBackedWorktrees_staleDisjoint() {
        ManagedClaudeSession onWt = session("onwt", Path.of("/wt/a"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false),
                        wt("open", false, false, false), wt("gone", false, true, false)),
                List.of(onWt), noActivity());
        // a (session-backed) + open = 2 worktrees; gone = 1 stale; no overlap.
        assertEquals(2, result.worktreeCount());
        assertEquals(1, result.staleCount());
    }

    @Test
    void counts_mainOpenRowIsNotAWorktree() {
        // Main checkout with no session becomes an open row but must NOT count as "N wt".
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(), noActivity());
        assertEquals(0, result.worktreeCount());
        assertFalse(result.openWorktrees().isEmpty()); // the main row is still emitted
    }

    @Test
    void orphanSession_whoseWorktreeDirIsGone_stillAppears() {
        ManagedClaudeSession orphan = session("orphan", Path.of("/wt/vanished"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(orphan), noActivity());
        assertEquals(List.of("orphan"),
                result.idleSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarChildrenTest'`
Expected: FAIL — compilation error, `SidebarChildren` does not exist. (First confirm the `ManagedClaudeSession` constructor arity/order and `RepositoryId.newId()` / `ManagedSessionId.newId()` factory names against the domain sources; adjust the test helpers if the real signatures differ — do **not** invent fields.)

- [ ] **Step 3: Write `SidebarChildren`**

```java
package app.drydock.ui;

import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService.Worktree;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure classification of a repository's on-disk worktrees and managed
 * sessions into the sidebar's display bands, the collapsed stale bucket, and
 * the header counts. Toolkit-free and side-effect-free so it can be unit
 * tested without a JavaFX runtime; {@code RepositorySidebar} wraps the result
 * into tree rows.
 */
record SidebarChildren(
        List<ManagedClaudeSession> liveSessions,
        List<ManagedClaudeSession> idleSessions,
        List<Worktree> openWorktrees,
        List<Worktree> staleWorktrees,
        int worktreeCount,
        int staleCount) {

    /** {@code liveSessions} followed by {@code idleSessions}, in display order. */
    List<ManagedClaudeSession> orderedSessions() {
        List<ManagedClaudeSession> all = new ArrayList<>(liveSessions);
        all.addAll(idleSessions);
        return all;
    }

    static SidebarChildren classify(List<Worktree> worktrees,
            List<ManagedClaudeSession> sessions,
            Function<ManagedSessionId, SessionActivity> activityOf) {

        List<ManagedClaudeSession> mainSessions = sessions.stream()
                .filter(session -> session.worktreeRoot().isEmpty()).toList();
        Set<ManagedClaudeSession> placed = new LinkedHashSet<>();

        List<ManagedClaudeSession> sessionRows = new ArrayList<>();
        List<Worktree> openWorktrees = new ArrayList<>();
        List<Worktree> staleWorktrees = new ArrayList<>();

        // Match sessions to worktrees exactly as childNodesFor did.
        for (Worktree worktree : worktrees) {
            if (worktree.mainCheckout()) {
                if (mainSessions.isEmpty()) {
                    bucket(worktree, false, openWorktrees, staleWorktrees);
                } else {
                    sessionRows.addAll(mainSessions);
                    placed.addAll(mainSessions);
                }
            } else {
                Optional<ManagedClaudeSession> match = sessions.stream()
                        .filter(session -> session.worktreeRoot()
                                .map(root -> root.equals(worktree.path())).orElse(false))
                        .findFirst();
                if (match.isPresent()) {
                    sessionRows.add(match.get());
                    placed.add(match.get());
                } else {
                    bucket(worktree, false, openWorktrees, staleWorktrees);
                }
            }
        }
        // Orphan sessions whose worktree directory no longer exists.
        for (ManagedClaudeSession session : sessions) {
            if (!placed.contains(session) && session.worktreeRoot().isPresent()) {
                sessionRows.add(session);
            }
        }

        // Band the session rows: live first, then idle. NEEDS_ATTENTION pins to
        // the front of the live band; otherwise most-recently-opened first.
        Comparator<ManagedClaudeSession> byRecency =
                Comparator.comparing(ManagedClaudeSession::lastOpenedAt).reversed();
        List<ManagedClaudeSession> live = new ArrayList<>();
        List<ManagedClaudeSession> idle = new ArrayList<>();
        for (ManagedClaudeSession session : sessionRows) {
            (isRunning(session.status()) ? live : idle).add(session);
        }
        live.sort(Comparator
                .comparingInt((ManagedClaudeSession session) ->
                        activityOf.apply(session.id()) == SessionActivity.NEEDS_ATTENTION ? 0 : 1)
                .thenComparing(byRecency));
        idle.sort(byRecency);

        // Open worktrees: main checkout row first, then by branch name
        // (case-insensitive), branch-less last by path.
        openWorktrees.sort(Comparator
                .comparingInt((Worktree worktree) -> worktree.mainCheckout() ? 0 : 1)
                .thenComparingInt(worktree -> worktree.branch().isPresent() ? 0 : 1)
                .thenComparing(worktree -> worktree.branch().map(String::toLowerCase).orElse(""))
                .thenComparing(worktree -> worktree.path().toString()));

        int worktreeCount = (int) openWorktrees.stream().filter(w -> !w.mainCheckout()).count()
                + (int) sessionRows.stream().filter(s -> s.worktreeRoot().isPresent()).count();

        return new SidebarChildren(List.copyOf(live), List.copyOf(idle),
                List.copyOf(openWorktrees), List.copyOf(staleWorktrees),
                worktreeCount, staleWorktrees.size());
    }

    private static void bucket(Worktree worktree, boolean hasSession,
            List<Worktree> open, List<Worktree> stale) {
        boolean isStale = !worktree.locked() && !worktree.mainCheckout() && !hasSession
                && (worktree.prunable() || worktree.detached());
        (isStale ? stale : open).add(worktree);
    }

    private static boolean isRunning(SessionStatus status) {
        return status == SessionStatus.RUNNING || status == SessionStatus.STARTING;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarChildrenTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/SidebarChildren.java \
        app/src/test/java/app/drydock/ui/SidebarChildrenTest.java
git commit -m "Add SidebarChildren: pure banding, stale rule, and counts"
```

---

### Task 2: Wire ordering + counts into the tree (stale rendered inline for now)

Delegate `childNodesFor` and the header counts to `classify`. Stale worktrees are still rendered as ordinary unopened rows in this task (appended after open ones) — Task 3 gives them the collapsed bucket. This task's on-screen deliverable: children are banded live→idle→worktree, and the `olifer` header count is correct.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (`childNodesFor` ~628-675; `repoMetaText` ~1125-1143; delete `additionalWorktreeCount` ~677-684)

**Interfaces:**
- Consumes: `SidebarChildren.classify(...)`, `SidebarChildren` accessors from Task 1.
- Produces (relied on by Task 3): `childNodesFor` builds children from a `SidebarChildren`; a private `SidebarChildren childrenOf(Repository)` helper that calls `classify(worktrees, sessionsFor(repository), viewModel::activityOf)` and is reused by `repoMetaText`.

- [ ] **Step 1: Add the `childrenOf` helper and rewrite `childNodesFor`**

Replace the body of `childNodesFor` (`RepositorySidebar.java:628-675`) with a thin adapter, and add `childrenOf` above it:

```java
/** Classifies a repo's worktrees + sessions, or {@code null} if discovery hasn't run yet. */
private SidebarChildren childrenOf(Repository repository) {
    List<WorktreeService.Worktree> worktrees = viewModel.worktrees(repository.id()).orElse(null);
    if (worktrees == null) {
        return null;
    }
    return SidebarChildren.classify(worktrees, sessionsFor(repository), viewModel::activityOf);
}

private List<SidebarNode> childNodesFor(Repository repository) {
    SidebarChildren classified = childrenOf(repository);
    if (classified == null) {
        // Discovery hasn't run yet: kick it off and show session-derived rows meanwhile.
        refreshWorktrees(repository, false);
        return new ArrayList<>(sessionsFor(repository).stream()
                .map(session -> (SidebarNode) new SidebarNode.SessionNode(session, repository))
                .toList());
    }
    List<SidebarNode> children = new ArrayList<>();
    for (ManagedClaudeSession session : classified.orderedSessions()) {
        children.add(new SidebarNode.SessionNode(session, repository));
    }
    for (WorktreeService.Worktree worktree : classified.openWorktrees()) {
        children.add(new SidebarNode.UnopenedWorktreeNode(worktree, repository));
    }
    // Task 3 replaces this inline listing with a single StaleWorktreesNode.
    for (WorktreeService.Worktree worktree : classified.staleWorktrees()) {
        children.add(new SidebarNode.UnopenedWorktreeNode(worktree, repository));
    }
    return children;
}
```

- [ ] **Step 2: Rederive `repoMetaText` counts from `classify`**

Rewrite `repoMetaText` (`RepositorySidebar.java:1125-1143`) so the numbers come from the same `SidebarChildren`. Keep it a single string for now (the label split is Task 4):

```java
/** Repo meta line: {@code ⎇ <base> · <n> wt · <m> stale}, counts from the shared classification. */
private String repoMetaText(Repository repository) {
    String note = rescanNotes.get(repository.id());
    if (note != null) {
        return note;
    }
    StringBuilder meta = new StringBuilder("⎇ ").append(branchTextFor(repository));
    SidebarChildren classified = childrenOf(repository);
    if (classified != null) {
        meta.append(" · ").append(classified.worktreeCount()).append(" wt");
        if (classified.staleCount() > 0) {
            meta.append(" · ").append(classified.staleCount()).append(" stale");
        }
    }
    return meta.toString();
}
```

- [ ] **Step 3: Rederive the footer counts, then delete `additionalWorktreeCount`**

`additionalWorktreeCount` has a live caller in `updateFooter` (`RepositorySidebar.java:445`), and the footer also computes `unopenedTotal` by filtering `childNodesFor(...)` for `UnopenedWorktreeNode` (`:446-448`) — that filter will silently drop stale worktrees in Task 3 once they move into `StaleWorktreesNode`. Rederive both from `classify` now, preserving today's meaning (`unopened` = worktrees with no session, which continues to include the stale ones). Replace the per-repo loop body (`:441-448`) with:

```java
for (Repository repository : repositoryManager.repositories()) {
    SidebarChildren classified = childrenOf(repository);
    if (classified == null) {
        continue;
    }
    worktreeTotal += classified.worktreeCount();
    unopenedTotal += (int) classified.openWorktrees().stream().filter(w -> !w.mainCheckout()).count()
            + classified.staleCount();
}
```

Then delete the now-unused `additionalWorktreeCount` method (`RepositorySidebar.java:677-684`).

- [ ] **Step 4: Build and run the unit suite**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarChildrenTest' :app:classes`
Expected: PASS + compiles cleanly. If the compiler still reports a caller of `additionalWorktreeCount`, resolve it to `childrenOf(...).worktreeCount()`.

- [ ] **Step 5: Verify on screen**

Launch the app (`./gradlew run`), expand `olifer` and `btrace`. Confirm: children order is live sessions → idle sessions → worktrees; the `olifer` header shows the correct `N wt` (not `0 worktrees`) matching the visible worktree row; the footer's `running · N worktrees · M unopened` still reads sensibly; no crash on repos mid-discovery. Compare against `scratchpad/sidebar-before.png`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "Band sidebar children and derive header counts from one source"
```

---

### Task 3: Stale bucket — collapsed `StaleWorktreesNode` + `Clean ↺` batch

Give stale worktrees a single collapsible row with its own expansion state, and a batch clean that never silently discards dirty work.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (sealed `SidebarNode` ~165-170; `childNodesFor`; `updateItem` switch ~1026-1039; new `buildStaleRow`; new `staleBucketExpanded` field near `collapsed` ~118; new `cleanStaleWorktrees`)

**Interfaces:**
- Consumes: `SidebarChildren` (Task 1), `worktreeService.remove(...)` / `removeForced(...)` (`WorktreeService.java:125,138`, both `CompletableFuture<Void>`), `deletableBranchOf` (`RepositorySidebar.java:854`), `WorktreeNotCleanException`, `UiErrors`.
- Produces: `record StaleWorktreesNode(List<WorktreeService.Worktree> worktrees, Repository repository) implements SidebarNode`.

- [ ] **Step 1: Add the node type, expansion state, and switch case**

Add to the sealed interface (`RepositorySidebar.java:165-170`):

```java
record StaleWorktreesNode(java.util.List<WorktreeService.Worktree> worktrees, Repository repository)
        implements SidebarNode { }
```

(Use a top-of-file `import java.util.List;` if not present rather than the inline FQN — shown inline here only to locate the edit.)

Add a field next to `collapsed` (`RepositorySidebar.java:118`):

```java
/** Repos whose stale bucket is expanded. Distinct from {@code collapsed} (repo-level). */
private final Set<RepositoryId> staleBucketExpanded = new HashSet<>();
```

Add a case to the `updateItem` switch (`RepositorySidebar.java:1026-1039`):

```java
case SidebarNode.StaleWorktreesNode staleNode -> {
    setGraphic(buildStaleRow(staleNode.worktrees(), staleNode.repository()));
    setContextMenu(null);
}
```

**Every exhaustive switch over the sealed `SidebarNode` must gain a case or the build fails.** There are three besides `updateItem`. Add to `activateNode` (`RepositorySidebar.java:322-328`) — Enter/Space on the bucket toggles its expansion:

```java
case SidebarNode.StaleWorktreesNode staleNode -> {
    RepositoryId repoId = staleNode.repository().id();
    if (!staleBucketExpanded.add(repoId)) {
        staleBucketExpanded.remove(repoId);
    }
    requestRebuild();
}
```

Add to `matchesNode` (`RepositorySidebar.java:696-714`) — the bucket matches when any contained worktree's branch or path matches the filter query:

```java
case SidebarNode.StaleWorktreesNode staleNode -> staleNode.worktrees().stream().anyMatch(worktree -> {
    String text = worktree.branch().orElse("") + " " + worktree.path();
    return text.toLowerCase(Locale.ROOT).contains(query);
});
```

Check `updateWorktreeRow`'s switch (`~534`) — it has a `default`, so it compiles unchanged; stale-bucket rows update only via full rebuild, which is fine (the bucket has no per-row incremental update).

- [ ] **Step 2: Emit one `StaleWorktreesNode` from `childNodesFor`**

Replace the Task-2 inline stale loop with:

```java
if (!classified.staleWorktrees().isEmpty()) {
    children.add(new SidebarNode.StaleWorktreesNode(classified.staleWorktrees(), repository));
}
```

- [ ] **Step 3: Add `buildStaleRow` and `cleanStaleWorktrees`**

```java
/**
 * The collapsed stale bucket: {@code ▸ N stale worktrees} + a Clean action.
 * Expands in place (its own {@code staleBucketExpanded} state) to plain dim
 * path rows. Clean removes the cleanly-removable worktrees in one confirm and
 * reports (never force-deletes) those with uncommitted work.
 */
private VBox buildStaleRow(List<WorktreeService.Worktree> worktrees, Repository repository) {
    boolean expanded = staleBucketExpanded.contains(repository.id());

    Label caret = new Label(expanded ? "▾" : "▸");
    caret.getStyleClass().add("repo-caret");
    Label label = new Label(worktrees.size() + (worktrees.size() == 1
            ? " stale worktree" : " stale worktrees"));
    label.getStyleClass().add("stale-summary");
    HBox.setHgrow(label, Priority.ALWAYS);

    Button clean = new Button("Clean ↺");
    clean.getStyleClass().add("stale-clean-button");
    clean.setFocusTraversable(false);
    clean.setOnAction(e -> cleanStaleWorktrees(repository, worktrees));

    HBox summary = new HBox(7, caret, label, clean);
    summary.getStyleClass().add("stale-summary-row");
    summary.setAlignment(Pos.CENTER_LEFT);
    summary.setPadding(new Insets(5, 8, 5, 16));
    summary.setOnMouseClicked(event -> {
        if (event.getButton() == MouseButton.PRIMARY) {
            if (expanded) {
                staleBucketExpanded.remove(repository.id());
            } else {
                staleBucketExpanded.add(repository.id());
            }
            requestRebuild();
            event.consume();
        }
    });

    VBox box = new VBox(summary);
    if (expanded) {
        for (WorktreeService.Worktree worktree : worktrees) {
            Label path = new Label(shortPath(worktree.path()));
            path.getStyleClass().add("stale-path");
            path.setPadding(new Insets(2, 8, 2, 34));
            box.getChildren().add(path);
        }
    }
    return box;
}

/**
 * Batch-remove the bucket: one confirm, remove the cleanly-removable ones,
 * and report (never silently force) any that hold uncommitted work.
 */
private void cleanStaleWorktrees(Repository repository, List<WorktreeService.Worktree> worktrees) {
    Alert confirm = new Alert(AlertType.CONFIRMATION);
    confirm.setTitle("Clean stale worktrees");
    confirm.setHeaderText("Remove " + worktrees.size() + " stale worktree"
            + (worktrees.size() == 1 ? "" : "s") + "?");
    confirm.setContentText("Worktrees with uncommitted changes are skipped and left in place.");
    if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
        return;
    }
    List<CompletableFuture<Void>> removals = new ArrayList<>();
    List<Path> skippedPaths = Collections.synchronizedList(new ArrayList<>());
    for (WorktreeService.Worktree worktree : worktrees) {
        removals.add(worktreeService.remove(repository.root(), worktree.path(), deletableBranchOf(worktree))
                .handle((v, failure) -> {
                    if (failure != null) {
                        if (UiErrors.unwrap(failure) instanceof WorktreeNotCleanException) {
                            skippedPaths.add(worktree.path()); // dirty: report, do not force
                        } else {
                            Platform.runLater(() -> UiErrors.show("Could not remove worktree", failure));
                        }
                    } else {
                        viewModel.removeWorktreeStatus(worktree.path());
                    }
                    return null;
                }));
    }
    CompletableFuture
            .allOf(removals.toArray(CompletableFuture[]::new))
            .whenComplete((v, ignored) -> Platform.runLater(() -> {
                refreshWorktrees(repository, false);
                if (!skippedPaths.isEmpty()) {
                    // Transient status note, cleared after 2.4s — mirrors the
                    // "Already up to date" rescan note (RepositorySidebar.java:765-771).
                    rescanNotes.put(repository.id(), "kept " + skippedPaths.size()
                            + " with uncommitted changes");
                    updateRepoRow(repository.id());
                    PauseTransition clearNote = new PauseTransition(Duration.seconds(2.4));
                    clearNote.setOnFinished(e -> {
                        rescanNotes.remove(repository.id());
                        updateRepoRow(repository.id());
                    });
                    clearNote.play();
                }
            }));
}
```

(Add top-of-file imports for `java.util.Collections`, `java.util.concurrent.CompletableFuture`, and `java.util.List` if not already present. `rescanNotes`, `updateRepoRow`, `PauseTransition`, and `Duration` are already used in this file.)

- [ ] **Step 4: Build**

Run: `./gradlew :app:classes`
Expected: compiles. The sealed `switch` in `updateItem` now covers all four `SidebarNode` variants (compiler enforces exhaustiveness — good).

- [ ] **Step 5: Verify on screen**

Launch, expand `btrace` (14 stale worktrees). Confirm: one `▸ 14 stale worktrees  Clean ↺` row instead of 14 blocks; clicking the caret expands/collapses just the bucket while the repo stays expanded; `Clean ↺` shows a single confirm; a repo with a dirty stale worktree keeps it and shows the "kept N" note. Compare against `scratchpad/sidebar-before.png`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "Collapse stale worktrees into one bucket with a batch Clean"
```

---

### Task 4: Row anatomy — grid, typed rows, hollow idle dot, header label split

Make the three row shapes visually distinct on one indent grid, and split the repo header so counts survive a long branch.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/SessionStatusStyles.java` (`createDot` ~56)
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (`buildSessionRow` ~1145; `buildUnopenedRow` ~1254; `buildRepoRow` meta line ~1051-1069)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (row styles ~203-286, ~1470-1525)

**Interfaces:**
- Consumes: `SessionStatusStyles.isRunning(status)`.
- Produces: `SessionStatusStyles.createDot(int sizePx, SessionStatus status, boolean filled)` (new overload; existing 2-arg calls delegate with `filled=true`).

- [ ] **Step 1: Add a `filled` variant to `createDot`**

Add an overload and route the existing one through it (`SessionStatusStyles.java`):

```java
static Region createDot(int sizePx, SessionStatus initialStatus) {
    return createDot(sizePx, initialStatus, true);
}

static Region createDot(int sizePx, SessionStatus initialStatus, boolean filled) {
    Region dot = new Region();
    dot.getStyleClass().addAll("status-dot", "dot-" + sizePx);
    // Idle sessions render hollow (ring only); error color always fills so the
    // failure signal is never weakened by the idle treatment.
    if (!filled && !isError(initialStatus)) {
        dot.getStyleClass().add("dot-hollow");
    }
    // ... unchanged: fade/scale pulse setup, scene listener, updateDot(dot, initialStatus) ...
    updateDot(dot, initialStatus);
    return dot;
}
```

(Keep the existing pulse + scene-listener body — do not duplicate it; move it into the 3-arg form.)

- [ ] **Step 2: Session row uses hollow-when-idle dot**

In `buildSessionRow` (`RepositorySidebar.java:1146`) change the dot construction to pass the idle flag:

```java
boolean live = SessionStatusStyles.isRunning(session.status());
Region dot = SessionStatusStyles.createDot(8, session.status(), live);
```

- [ ] **Step 3: Put every child row on one fixed gutter**

Remove the per-row inline `setPadding(new Insets(5, 8, 5, 16))` from `buildSessionRow` (~1216) and `buildUnopenedRow` (~1289), and the `setPadding` on the stale summary row from Task 3's `buildStaleRow`. The gutter now comes from a shared style class — **add the class explicitly on each row** (CSS alone does nothing without this):

- in `buildSessionRow`, on the returned `row`: `row.getStyleClass().add("child-row");`
- in `buildUnopenedRow`, on the returned `row`: `row.getStyleClass().add("child-row");`
- in `buildStaleRow`, on the `summary` HBox: `summary.getStyleClass().add("child-row");`

Keep the status column fixed-width so dots and the `◫` glyph align vertically. Wrap the leading dot/glyph of each row in a fixed-width container:

```java
StackPane statusCol = new StackPane(dot); // or the ◫ Label for worktree rows
statusCol.getStyleClass().add("child-row-status");
```

and use `statusCol` as the row's first child in place of the bare `dot`/`icon`. For the stale summary row the caret occupies this column, so wrap `caret` the same way. (`.child-row-status { -fx-min-width: 16; -fx-alignment: center; }` in Step 5 gives it the fixed width.)

- [ ] **Step 4: Split the repo header meta into branch + counts labels**

In `buildRepoRow` (`RepositorySidebar.java:1051-1069`) replace the single `branch` label with two, so the branch ellipsizes but the counts never do. Add a `repoCountsText` helper (branch-less counts, derived from the same `classify`) next to `repoMetaText`:

```java
/** Just the counts fragment: {@code · 3 wt · 1 stale}, or "" before discovery / when a note is showing. */
private String repoCountsText(Repository repository) {
    if (rescanNotes.get(repository.id()) != null) {
        return "";
    }
    SidebarChildren classified = childrenOf(repository);
    if (classified == null) {
        return "";
    }
    StringBuilder counts = new StringBuilder(" · ").append(classified.worktreeCount()).append(" wt");
    if (classified.staleCount() > 0) {
        counts.append(" · ").append(classified.staleCount()).append(" stale");
    }
    return counts.toString();
}
```

Then split the label in `buildRepoRow`:

```java
// When a transient rescan note is present it owns the whole line (branch text = note, no counts).
String note = rescanNotes.get(repository.id());
Label branch = new Label(note != null ? note : "⎇ " + branchTextFor(repository));
branch.getStyleClass().add("repo-branch");
branch.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
HBox.setHgrow(branch, Priority.ALWAYS);
branch.setMaxWidth(Double.MAX_VALUE);

Label counts = new Label(repoCountsText(repository)); // "" when a note is showing or pre-discovery
counts.getStyleClass().add("repo-count-meta");
counts.setMinWidth(Region.USE_PREF_SIZE); // never shrinks

HBox branchRow = new HBox(6, branch, counts);
```

Keep the existing running-dot append onto `branchRow`, and keep the failure/remote `Tooltip` logic (`RepositorySidebar.java:1053-1059`) — install it on the `branch` label. `repoMetaText` (from Task 2) becomes unused after this split; delete it once the compiler confirms no remaining caller. (`OverrunStyle` and `Region` need imports if not already present.)

- [ ] **Step 5: CSS — gutter, status column, hollow dot, stale + count styles**

Add to `app.css` (light/dark tokens already exist as `-drydock-*`):

```css
.child-row { -fx-padding: 5 8 5 16; }
.child-row-status { -fx-min-width: 16; -fx-alignment: center; }
.status-dot.dot-hollow {
    -fx-background-color: transparent;
    -fx-border-color: -drydock-text-dim;
    -fx-border-width: 1.5;
    -fx-border-radius: 100;
}
.repo-count-meta { -fx-text-fill: -drydock-text-dim; -fx-font-family: "JetBrains Mono", "Menlo"; -fx-font-size: 10px; }
.stale-summary-row { -fx-padding: 5 8 5 16; }
.stale-summary { -fx-text-fill: -drydock-text-faint; -fx-font-size: 11px; }
.stale-clean-button { -fx-font-size: 10.5px; }
.stale-path { -fx-text-fill: -drydock-text-faint; -fx-font-family: "JetBrains Mono", "Menlo"; -fx-font-size: 10px; }
```

Remove the now-obsolete `-fx-padding`/left-rule that faked the child indent (`app.css` ~240-248) if it conflicts with `.child-row`.

- [ ] **Step 6: Build and verify on screen**

Run: `./gradlew :app:classes && ./gradlew run`
Confirm: live sessions have filled dots, idle sessions hollow rings, a `FAILED` idle session still shows a filled error dot; worktree rows have no dot (dim `◫` in the aligned status column); dots/glyphs/text line up on one vertical grid; a long branch (`btrace`) ellipsizes while `· N wt` stays visible. Compare against `scratchpad/sidebar-before.png`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/SessionStatusStyles.java \
        app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "Give sidebar rows a typed grid, hollow idle dot, and count-safe header"
```

---

### Task 5: `⌘↑/⌘↓` live-session cycling

Cycle selection through running sessions only, across repos, wrapping — and fix the stale overlay advertisement.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (new pure `nextLiveIndex` + `cycleLiveSession`; a public entry `focusAdjacentLiveSession(int direction)`)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (`cmd`-key filter ~528)
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` (~28)
- Test: `app/src/test/java/app/drydock/ui/SidebarChildrenTest.java` (add cycle-index cases) — or a small `CycleIndexTest`

**Interfaces:**
- Consumes: the ordered live sessions per repo (from `classify`).
- Produces: `static int nextLiveIndex(int count, int current, int direction)` (pure, wrapping; `current == -1` means "nothing selected" → first for +1, last for −1); `void focusAdjacentLiveSession(int direction)` on `RepositorySidebar`.

- [ ] **Step 1: Write the failing pure-helper test**

Add to `SidebarChildrenTest` (or a new `CycleIndexTest`). Put `nextLiveIndex` as a `static` on `RepositorySidebar` (package-private) so the test in the same package can call it:

```java
@Test
void nextLiveIndex_wrapsForward() {
    assertEquals(0, RepositorySidebar.nextLiveIndex(3, 2, +1));
    assertEquals(2, RepositorySidebar.nextLiveIndex(3, 0, -1));
    assertEquals(0, RepositorySidebar.nextLiveIndex(3, -1, +1)); // nothing selected → first
    assertEquals(2, RepositorySidebar.nextLiveIndex(3, -1, -1)); // nothing selected → last
}

@Test
void nextLiveIndex_noLiveSessions_returnsMinusOne() {
    assertEquals(-1, RepositorySidebar.nextLiveIndex(0, -1, +1));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarChildrenTest'`
Expected: FAIL — `nextLiveIndex` not defined.

- [ ] **Step 3: Implement `nextLiveIndex` + `focusAdjacentLiveSession`**

```java
/** Wrapping index of the next live session; {@code -1} when there are none. */
static int nextLiveIndex(int count, int current, int direction) {
    if (count == 0) {
        return -1;
    }
    if (current < 0) {
        return direction > 0 ? 0 : count - 1;
    }
    return ((current + direction) % count + count) % count;
}

/**
 * Moves selection to the next/previous running session (top-to-bottom across
 * repos, wrapping) and opens it. Skips idle sessions, worktrees, and buckets.
 */
void focusAdjacentLiveSession(int direction) {
    List<ManagedClaudeSession> live = new ArrayList<>();
    for (Repository repository : sorted(repositoryManager.repositories())) {
        SidebarChildren classified = childrenOf(repository);
        if (classified != null) {
            live.addAll(classified.liveSessions());
        }
    }
    if (live.isEmpty()) {
        return;
    }
    ManagedSessionId selectedId = selectedSessionId(); // helper: current selection's session id or null
    int current = -1;
    for (int i = 0; i < live.size(); i++) {
        if (live.get(i).id().equals(selectedId)) {
            current = i;
            break;
        }
    }
    ManagedClaudeSession target = live.get(nextLiveIndex(live.size(), current, direction));
    // Same entry point as a row click; opening the session drives selection,
    // and syncActiveSelection() (RepositorySidebar.java:568) then expands the
    // owning repo and scrolls the row into view.
    navigator.resumeSession(target);
}
```

Add a small `selectedSessionId()` helper reading `tree.getSelectionModel().getSelectedItem()` and returning the session id if it is a `SessionNode`, else `null`.

- [ ] **Step 4: Run to verify the helper tests pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarChildrenTest'`
Expected: PASS.

- [ ] **Step 5: Bind `⌘↑/⌘↓` and fix the overlay entry**

In `DrydockApplication` `cmd`-key filter (~528, beside the `OPEN_BRACKET`/`CLOSE_BRACKET` handlers) add:

```java
} else if (cmd && event.getCode() == KeyCode.DOWN) {
    sidebar.focusAdjacentLiveSession(+1);
    event.consume();
} else if (cmd && event.getCode() == KeyCode.UP) {
    sidebar.focusAdjacentLiveSession(-1);
    event.consume();
}
```

(Confirm the `RepositorySidebar` instance's field name in `DrydockApplication`; wire the call to it.)

In `ShortcutsOverlay` (`ShortcutsOverlay.java:28`), correct the stale entry and add the new one:

```java
{"Previous / next session tab", "⌘[ / ⌘]"},
{"Previous / next live session", "⌘↑ / ⌘↓"},
```

- [ ] **Step 6: Build and verify on screen**

Run: `./gradlew :app:classes && ./gradlew run`
With ≥2 running sessions, press `⌘↓`/`⌘↑`: selection jumps between running sessions only (idle/worktree/stale skipped), wraps at the ends, and opens each on landing. Open the shortcuts overlay (`?`) and confirm both entries read correctly and both are actually bound.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/java/app/drydock/DrydockApplication.java \
        app/src/main/java/app/drydock/ui/ShortcutsOverlay.java \
        app/src/test/java/app/drydock/ui/SidebarChildrenTest.java
git commit -m "Add ⌘↑/⌘↓ live-session cycling and fix the tab-cycle overlay label"
```

---

### Task 6: Full-suite regression + on-screen acceptance pass

**Files:** none (verification only).

- [ ] **Step 1: Run the whole test suite**

Run: `./gradlew :app:test`
Expected: PASS (existing suites + `SidebarChildrenTest`). The suite runs headless (`testfx.headless=true`).

- [ ] **Step 2: On-screen acceptance against the spec's goals**

Launch (`./gradlew run`) and confirm all four original complaints are resolved, comparing to `scratchpad/sidebar-before.png`:
- `(detached)` clutter → one collapsed `▸ N stale worktrees` bucket per repo.
- reach active sessions → live sessions band to the top; `⌘↑/⌘↓` cycles them.
- indentation/"what is what" → sessions (dot, 2-line), worktrees (no dot, dim `◫`), stale bucket (caret) are distinct and aligned on one gutter.
- header counts → correct and never eaten by a long branch.

Capture an after screenshot to `scratchpad/sidebar-after.png` for the record.

- [ ] **Step 3: Final commit (if any cleanup)**

```bash
git add -A
git commit -m "Browser tree redesign: regression pass and cleanup"
```

---

## Notes for the implementer

- **`ManagedClaudeSession` constructor:** Task 1's test builds sessions directly. Before writing the test, open `app/src/main/java/app/drydock/domain/ManagedClaudeSession.java` and copy the *exact* canonical-constructor parameter order and the `RepositoryId`/`ManagedSessionId` factory names; the arguments shown in Step 1 are indicative, not guaranteed. Do not add or reorder fields.
- **No-FQN rule:** several code blocks above show `java.util.*` / `java.util.concurrent.*` inline purely to name types unambiguously at the edit site. Replace them with top-of-file imports when you apply the edit.
- **`requestRebuild` vs `rebuildTree`:** stale-bucket toggling uses `requestRebuild()` (the coalesced path, `RepositorySidebar.java:356`) — never call `rebuildTree()` directly from an event handler.
- **FX-thread rule:** `cleanStaleWorktrees` does its removals on `worktreeService`'s background futures and only touches UI inside `Platform.runLater`; keep it that way.
