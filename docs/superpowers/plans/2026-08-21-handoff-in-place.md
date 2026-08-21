# Handoff In Place Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the harness handoff so the successor session starts in the
outgoing session's own worktree and branch, and the outgoing session is
deleted rather than left behind.

**Architecture:** `SessionForkService` becomes `SessionHandoffService` and
loses everything that made a sibling checkout — branch creation, worktree
creation, submodule init, the dirty-tree transplant and the rollback that
existed only for a half-populated destination. What remains is: compose the
seed from the brief plus facts derived from the session's own worktree, write
the seed file, delete the outgoing session, launch the chosen `AgentKind` on
the same paths carrying the fields the successor inherits, and rebind the
outgoing session's review scopes to it.

**Tech Stack:** Java 21+ records and `Optional`, JavaFX for UI, the project's
hand-rolled `app.drydock.state.json` (no Jackson/Gson), JUnit 5, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-12-harness-handoff-design.md`
(amended for this rework in commit `e1d8565`)

## Global Constraints

- **No new dependencies.** JSON goes through `app.drydock.state.json`; there
  is no mocking library, so seams are hand-written fakes (see
  `app/src/test/java/app/drydock/mcp/FakeMcpSessionContext.java`).
- **Persisted names are wire contracts.** Never rename an existing persisted
  JSON member or an `AgentKind.persistedName()`. In particular `forkedFrom`
  keeps its name and its codec: this rework stops *writing* it, and old state
  files must still decode.
- **All new persisted members decode leniently.** Absent or malformed decodes
  to a stated default; never throw, never bump `schemaVersion`. (This plan
  adds no persisted member, but the rule stands.)
- **Blocking work never touches the FX thread.** Git and filesystem calls go
  on a background executor; `GitStatusService` exposes `CompletableFuture`
  publics with package-private `*Blocking` forms for tests. Follow that
  pattern exactly.
- **Refuse, never truncate.** Oversize agent input comes back with a message
  naming the slot and the limit.
- **Caps:** each brief slot 2,000 characters; the whole record 8,000;
  `MAX_COMMIT_SUBJECTS = 20`; `MAX_CHANGED_FILES = 50`.
- **Test command:** `./gradlew :app:test --tests "<pattern>"` for a subset,
  `./gradlew :app:test` for the suite. The full suite takes 14–20 minutes —
  run subsets while working and the full suite once at the end.
- **Commit style:** sentence-case subject describing the change, as in
  `git log`. No `Co-Authored-By` unless the repo's recent commits carry one
  (they do not).
- **Deleting is deleting.** Where this plan says a file or a method is
  deleted, delete it — do not leave a deprecated shim. The old mechanism has
  exactly one caller and no external consumers.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/app/drydock/review/ReviewScopeRegistry.java` | **Modify.** Add `rebind(from, to)`. |
| `app/src/main/java/app/drydock/handoff/ForkFacts.java` | **Delete**, replaced by `HandoffFacts`. |
| `app/src/main/java/app/drydock/handoff/HandoffFacts.java` | **Create.** Facts derived at handoff time: branch, head commit, commit subjects, changed files, open intents. |
| `app/src/main/java/app/drydock/handoff/HandoffSeed.java` | **Modify.** Take `HandoffFacts`; print the branch line without "forked from"; say when the branch is unborn. |
| `app/src/main/java/app/drydock/app/SessionManager.java` | **Modify.** Add `prepareSuccessorSession`. |
| `app/src/main/java/app/drydock/app/SessionForkService.java` | **Delete**, replaced by `SessionHandoffService`. |
| `app/src/main/java/app/drydock/app/SessionHandoffService.java` | **Create.** The four-step handoff, its seams, staleness, seed file and sweep. |
| `app/src/main/java/app/drydock/git/WorktreeTransplant.java` | **Delete.** Nothing crosses trees any more. |
| `app/src/main/java/app/drydock/ui/MainWorkspace.java` | **Modify.** Confirmation, service wiring, successor launch, scope rebind, diag verb. |
| `app/src/main/java/app/drydock/ui/OpenSessionTab.java` | **Modify.** "Hand off to…" control, tooltip, style class. |
| `app/src/main/java/app/drydock/ui/HandoffBanner.java` | **Modify.** Copy that says "hand off", not "fork". |
| `app/src/main/resources/app/drydock/ui/app.css` | **Modify.** Rename the button's style class. |
| `app/src/main/java/app/drydock/DrydockApplication.java` | **Modify.** Rename the `fork:` diag verb to `handoff:`. |

Test files mirror these one-for-one; each task names its own.

---

### Task 1: Review scopes follow the work to the successor

A `ReviewScope` is bound to a `ManagedSessionId`, and
`ReviewScopeRegistry.isAddressableBy` gates every review MCP tool on that
binding. The handoff deletes the outgoing session, so without this task its
scopes become addressable by nobody: the findings the human recorded still
exist in `AnnotationStore` and no agent can answer them.

Rebinding rather than revoking is the point. A review is about the worktree's
diff, and the handoff does not change the diff by a line, so the scope is
exactly as valid a second after the handoff as a second before.

**Files:**
- Modify: `app/src/main/java/app/drydock/review/ReviewScopeRegistry.java`
- Test: `app/src/test/java/app/drydock/review/ReviewScopeRegistryTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `public void rebind(ManagedSessionId from, ManagedSessionId to)`
  on `ReviewScopeRegistry`. Task 4 consumes it through a
  `SessionHandoffService.ScopeRebinder` seam; Task 5 wires the real one.

Useful context for the implementer: scope *identity* is
`(kind, repoRoot, worktree, pr)` and deliberately excludes the session, so a
rebind never changes a scope's id and never orphans a finding. `byId` is a
`ConcurrentHashMap<String, ReviewScope>`, `ReviewScope.withSession(id)`
returns a copy with a new binding, and `notifyChanged(scopeId)` is the private
method that tells listeners a scope moved.

- [ ] **Step 1: Write the failing test**

Append to the existing
`app/src/test/java/app/drydock/review/ReviewScopeRegistryTest.java`, reusing
its fields (`registry`, `owner`, `stranger`) and its
`worktreeSpec(String head, Optional<ManagedSessionId> session)` helper. Every
import these tests need is already in that file.

```java
    @Test
    void rebindMovesAScopeToTheSuccessorSession() {
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.of(owner)));
        ManagedSessionId successor = ManagedSessionId.newId();

        registry.rebind(owner, successor);

        assertTrue(registry.isAddressableBy(scope.id(), successor));
        assertFalse(registry.isAddressableBy(scope.id(), owner));
    }

    @Test
    void rebindKeepsTheScopeIdSoFindingsAreNotOrphaned() {
        // Findings are keyed by (scopeId, ...). A new id would hide every one
        // of them while leaving it sitting in the store under the old handle.
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.of(owner)));

        registry.rebind(owner, ManagedSessionId.newId());

        assertEquals(Optional.of(scope.id()), registry.byId(scope.id()).map(ReviewScope::id));
        assertEquals(1, registry.scopes().size());
    }

    @Test
    void rebindLeavesScopesBoundToOtherSessionsAlone() {
        ReviewScope other = registry.mint(worktreeSpec("feat/b", Optional.of(stranger)));

        registry.rebind(owner, ManagedSessionId.newId());

        assertTrue(registry.isAddressableBy(other.id(), stranger));
    }

    @Test
    void rebindNotifiesListenersSoTheBoardRedraws() {
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.of(owner)));
        List<String> changed = new ArrayList<>();
        registry.addChangeListener(changed::add);

        registry.rebind(owner, ManagedSessionId.newId());

        assertEquals(List.of(scope.id()), changed);
    }

    @Test
    void rebindIgnoresNullsRatherThanThrowingIntoAHandoff() {
        registry.rebind(null, ManagedSessionId.newId());
        registry.rebind(owner, null);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewScopeRegistryTest"`
Expected: FAIL to compile — `cannot find symbol: method rebind`.

- [ ] **Step 3: Write minimal implementation**

Add to `ReviewScopeRegistry`, directly after `revoke`:

```java
    /**
     * Moves every scope bound to {@code from} onto {@code to}, keeping each
     * scope's id.
     *
     * <p>A handoff deletes the outgoing session and starts a successor on the
     * same worktree. The diff those scopes describe is untouched by that, so
     * the review is exactly as valid as it was a moment earlier -- but the
     * session that could address it no longer exists. Revoking instead would
     * strand the findings already recorded against these ids and make the
     * human review the same diff twice.</p>
     *
     * <p>Ids are preserved because scope identity is {@code (kind, repoRoot,
     * worktree, pr)} and excludes the session: nothing about a rebind changes
     * what a scope IS, so nothing may change what findings are keyed by.</p>
     */
    public void rebind(ManagedSessionId from, ManagedSessionId to) {
        if (from == null || to == null) {
            return;
        }
        for (Map.Entry<String, ReviewScope> entry : byId.entrySet()) {
            if (entry.getValue().sessionId().filter(from::equals).isEmpty()) {
                continue;
            }
            byId.put(entry.getKey(), entry.getValue().withSession(to));
            notifyChanged(entry.getKey());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.review.ReviewScopeRegistryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/review/ReviewScopeRegistry.java \
        app/src/test/java/app/drydock/review/ReviewScopeRegistryTest.java
git commit -m "Review scopes can move to a session's successor"
```

---

### Task 2: The facts are about one branch, not two

`ForkFacts` carries `branch` and `baseBranch` because the fork cut a new
branch from the outgoing one. There is one branch now, so `baseBranch` is
meaningless and the seed's `**Branch:** X (forked from Y)` line is wrong.

The same edit makes an existing spec claim true. The spec's failure table says
an outgoing session with no commits yet "proceeds; the seed says the branch is
unborn" — today an unborn branch merely produces an empty commit list and the
seed silently omits the section, which reads to a successor as "no commits
worth mentioning" rather than "there are none". Carrying the head commit as a
fact says it outright, and gives the successor something checkable.

**Files:**
- Delete: `app/src/main/java/app/drydock/handoff/ForkFacts.java`
- Create: `app/src/main/java/app/drydock/handoff/HandoffFacts.java`
- Modify: `app/src/main/java/app/drydock/handoff/HandoffSeed.java`
- Test: `app/src/test/java/app/drydock/handoff/HandoffSeedTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `public record HandoffFacts(String branch, Optional<String> headCommit, List<String> commitSubjects, List<String> changedFiles, List<String> openIntents)`
  - `public static String HandoffSeed.compose(Optional<HandoffBrief> brief, HandoffFacts facts)`
  Task 4 constructs `HandoffFacts` and calls `compose`.

- [ ] **Step 1: Write the failing test**

Replace the `ForkFacts` construction throughout
`app/src/test/java/app/drydock/handoff/HandoffSeedTest.java` with
`HandoffFacts`, and add these tests:

```java
    @Test
    void theBranchLineNamesOneBranchBecauseThereIsOnlyOne() {
        String seed = HandoffSeed.compose(Optional.empty(), new HandoffFacts(
                "feat/work", Optional.of("a1b2c3d"), List.of("add a.txt"), List.of(), List.of()));

        assertTrue(seed.contains("**Branch:** feat/work"), seed);
        assertFalse(seed.contains("forked from"), seed);
    }

    @Test
    void theSeedCarriesTheHeadCommitSoTheSuccessorCanCheckIt() {
        String seed = HandoffSeed.compose(Optional.empty(), new HandoffFacts(
                "feat/work", Optional.of("a1b2c3d"), List.of("add a.txt"), List.of(), List.of()));

        assertTrue(seed.contains("a1b2c3d"), seed);
    }

    @Test
    void anUnbornBranchSaysSoRatherThanOmittingTheCommitSection() {
        // Silence would read as "no commits worth mentioning", not "none".
        String seed = HandoffSeed.compose(Optional.empty(), new HandoffFacts(
                "feat/work", Optional.empty(), List.of(), List.of("?? wip.txt"), List.of()));

        assertTrue(seed.contains("no commits yet"), seed);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.handoff.HandoffSeedTest"`
Expected: FAIL to compile — `cannot find symbol: class HandoffFacts`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/app/drydock/handoff/HandoffFacts.java`:

```java
package app.drydock.handoff;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What drydock knows about the handed-off work without asking anyone.
 *
 * <p>Derived fresh at handoff time, so it is available and correct even when
 * the outgoing session is wedged, rate-limited or dead -- which is the case
 * the handoff exists for. This is the floor under every seed: a handoff whose
 * brief is stale or missing entirely is still bounded by current mechanical
 * truth.</p>
 *
 * <p>{@code headCommit} is absent on an unborn branch. The seed says so out
 * loud rather than printing an empty commit list, because a missing section
 * reads as "nothing worth mentioning" and this one means "there is nothing".</p>
 */
public record HandoffFacts(String branch, Optional<String> headCommit, List<String> commitSubjects,
                           List<String> changedFiles, List<String> openIntents) {

    public HandoffFacts {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(headCommit, "headCommit");
        commitSubjects = List.copyOf(Objects.requireNonNull(commitSubjects, "commitSubjects"));
        changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
        openIntents = List.copyOf(Objects.requireNonNull(openIntents, "openIntents"));
    }
}
```

Delete `app/src/main/java/app/drydock/handoff/ForkFacts.java`.

`SessionForkService` is `ForkFacts`'s other consumer, and deleting the record
without touching it breaks the **main** source set, which would stop gradle
running any test at all. Keep it compiling with the smallest possible edit —
Task 4 rewrites this method anyway. In `SessionForkService`, change the import
to `HandoffFacts`, drop the now-unused `baseBranch` parameter from `factsFor`
and from its single call site in `forkBlocking`, and construct the new record:

```java
    private HandoffFacts factsFor(ManagedAgentSession outgoing, Path sourceWorktree, String branch,
                                  Optional<String> head) {
        ...unchanged body...
        return new HandoffFacts(branch, head, subjects, changed,
                capped(openIntentLookup.apply(outgoing.id()), MAX_CHANGED_FILES));
    }
```

In `HandoffSeed`, change the parameter type of `compose` and `appendFacts`
from `ForkFacts` to `HandoffFacts`, change the class javadoc's "forked
session" to "successor session", and replace `appendFacts`'s branch line:

```java
    private static void appendFacts(StringBuilder seed, HandoffFacts facts) {
        seed.append("## State (derived by drydock, checkable)\n\n");
        seed.append("**Branch:** ").append(facts.branch())
                .append(facts.headCommit()
                        .map(commit -> " at " + commit)
                        .orElse(" -- no commits yet, so all of the work is uncommitted"))
                .append("\n\n");
        // "Recent commits", not "Commits": the caller lists the last N commits
        // reachable from HEAD, which on a branch cut from a long-lived base is
        // mostly that base's history. Calling it "Commits" directly under
        // "Branch: X" reads as "this is the work", and a successor would
        // attribute a dozen unrelated mainline subjects to the session it is
        // taking over.
        appendList(seed, "Recent commits (newest first, including base history)", facts.commitSubjects());
        appendList(seed, "Uncommitted changes", facts.changedFiles());
        appendList(seed, "Open review intents", facts.openIntents());
    }
```

Also update the opening sentence of `compose` — replace `"No handoff brief was
recorded before this fork, so nothing "` with `"No handoff brief was recorded
before this handoff, so nothing "`. Leave the assertion string
`"No handoff brief was recorded"` matchable: existing tests grep for exactly
that prefix.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.handoff.HandoffSeedTest"`
Expected: PASS.

Then confirm the module as a whole still builds, since `SessionForkService`
was edited too:

Run: `./gradlew :app:test --tests "app.drydock.app.SessionForkServiceTest"`
Expected: PASS. That test asserts on seed content but never on the
`forked from` wording, so the branch-line change does not reach it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/handoff/ \
        app/src/test/java/app/drydock/handoff/HandoffSeedTest.java
git commit -m "The seed describes one branch, and says when it has no commits"
```

---

### Task 3: A successor session inherits what the outgoing one was

The successor takes the outgoing session's place, so it must arrive already
looking like it: same title, same pinned-name state, same checkout, same
branch ownership, same eval mode. Deriving those at the launch site would put
five inheritance rules inside a JavaFX method that no test can reach. This
task puts them in `SessionManager`, which is testable headless.

Two of the five are not cosmetic. `branchCreatedHere` decides whether drydock
may later delete the branch: the successor did **not** create it, so copying
the outgoing session's flag is the only correct value — hardcoding `true`, as
the old fork path did, would offer to delete a branch drydock does not own.
`namePinned` records that a human confirmed this session's name; carrying it
keeps `session_rename` refusing, because a name the human typed is theirs
whichever agent is now sitting under it.

`forkedFrom` is deliberately **not** set. The parent is deleted moments later,
so the id would resolve to nothing; the persisted member and
`withForkedFrom` stay for old state files, and nothing new writes them.

**Files:**
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java`
- Test: `app/src/test/java/app/drydock/app/SessionManagerTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `public ManagedAgentSession prepareSuccessorSession(Repository repository, ManagedAgentSession outgoing, AgentKind agentKind)`.
  Task 5 calls it from `MainWorkspace`'s launcher.

Useful context: `newSessionMetadata(repository, displayName, agentKind,
Optional<Path> worktreeRoot, boolean branchCreatedHere)` is the private
builder; `ManagedAgentSession` exposes `displayName()`, `namePinned()`,
`worktreeRoot()`, `branchCreatedHere()`, `evalMode()`, and the copy methods
`withNamePinned(boolean)` and `withEvalMode(boolean)`. `prepareWorktreeSession`
sits immediately above the right insertion point.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/app/drydock/app/SessionManagerTest.java`:

```java
    @Test
    void aSuccessorInheritsTheOutgoingSessionsIdentityAndCheckout() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager
                .prepareWorktreeSession(repository, "Rework the rail", Path.of("/repo/wt"), true,
                        AgentKind.CLAUDE)
                .withNamePinned(true);

        ManagedAgentSession successor =
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX);

        assertEquals("Rework the rail", successor.displayName());
        assertTrue(successor.namePinned());
        assertEquals(Optional.of(Path.of("/repo/wt")), successor.worktreeRoot());
        assertEquals(Path.of("/repo/wt"), successor.workingDirectory());
        assertEquals(AgentKind.CODEX, successor.agentKind());
    }

    @Test
    void aSuccessorNeverClaimsToHaveCreatedTheBranch() {
        // It did not: the branch is the outgoing session's, and a successor
        // that claims otherwise would offer to delete a branch drydock does
        // not own.
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareWorktreeSession(
                repository, "Rework the rail", Path.of("/repo/wt"), false, AgentKind.CLAUDE);

        ManagedAgentSession successor =
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX);

        assertFalse(successor.branchCreatedHere());
    }

    @Test
    void aSuccessorOfAMainCheckoutSessionStaysOnTheMainCheckout() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareSession(repository, AgentKind.CLAUDE);

        ManagedAgentSession successor =
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX);

        assertEquals(Optional.empty(), successor.worktreeRoot());
        assertEquals(repository.root(), successor.workingDirectory());
    }

    @Test
    void aSuccessorInheritsEvalModeSoGatedWorkStaysOnTheEvalAccount() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager
                .prepareSession(repository, AgentKind.CLAUDE).withEvalMode(true);

        assertTrue(manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX).evalMode());
    }

    @Test
    void aSuccessorRecordsNoLineageBecauseItsParentIsAboutToBeDeleted() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareSession(repository, AgentKind.CLAUDE);

        assertEquals(Optional.empty(),
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX).forkedFrom());
    }

    @Test
    void aSuccessorGetsItsOwnId() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareSession(repository, AgentKind.CLAUDE);

        assertNotEquals(outgoing.id(),
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX).id());
    }
```

`newManager(InMemoryStateRepository)` and `someRepository()` are the test
class's existing helpers -- use them rather than adding duplicates. Add
`assertNotEquals` to the static imports if it is not already there.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionManagerTest"`
Expected: FAIL to compile — `cannot find symbol: method prepareSuccessorSession`.

- [ ] **Step 3: Write minimal implementation**

Add to `SessionManager`, directly after the two-arg `prepareWorktreeSession`
overloads:

```java
    /**
     * Metadata for the session that takes {@code outgoing}'s place under a
     * different agent.
     *
     * <p>The successor runs in the same checkout on the same branch over the
     * same working tree, so it inherits what made that session what it was:
     * its title, whether a human pinned that title, which checkout it lives
     * in, whether drydock created the branch there, and its eval mode. Only
     * the agent changes.</p>
     *
     * <p>{@code branchCreatedHere} is COPIED rather than asserted. The
     * successor did not create the branch; claiming it did would let a later
     * delete offer to remove a branch drydock does not own.</p>
     *
     * <p>No lineage is recorded. {@code outgoing} is deleted as part of the
     * same handoff, so a {@code forkedFrom} pointing at it would resolve to
     * nothing.</p>
     */
    public ManagedAgentSession prepareSuccessorSession(Repository repository, ManagedAgentSession outgoing,
                                                       AgentKind agentKind) {
        Objects.requireNonNull(outgoing, "outgoing");
        return newSessionMetadata(repository, outgoing.displayName(), agentKind,
                        outgoing.worktreeRoot(), outgoing.branchCreatedHere())
                .withNamePinned(outgoing.namePinned())
                .withEvalMode(outgoing.evalMode());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionManagerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/app/SessionManager.java \
        app/src/test/java/app/drydock/app/SessionManagerTest.java
git commit -m "A successor session inherits the identity and checkout it takes over"
```

---

### Task 4: The handoff happens in place

This is the rework itself. `SessionForkService` becomes
`SessionHandoffService` and stops building a destination: no branch, no
worktree, no submodule init, no transplant, and therefore no rollback — that
existed only to clean up a half-populated sibling checkout, and there is no
longer anything to half-populate.

The operation is four steps, and their order is load-bearing:

1. Compose the seed and write it to the seed file. This must happen **before**
   the delete, because `SessionManager.deleteSession` removes the
   `HandoffBrief` along with the session it describes.
2. Delete the outgoing session.
3. Launch the successor on the outgoing session's own worktree.
4. Rebind the outgoing session's review scopes to the successor.

The inherited fields need no separate "read before delete" step: the
`ManagedAgentSession` handed to `handOffBlocking` is an immutable record, so
the value in hand stays valid after the entry is gone from state. Pass that
record to the launcher rather than a fistful of extracted fields.

Step 4 comes last because it needs the successor's id.

There is no "the outgoing session would not stop" failure. `closeGracefully`
waits out its grace period, calls `destroyForcibly`, and always invokes its
completion callback. What can fail is the state write inside `deleteSession`;
that failure must abort the handoff before step 3, so one worktree never ends
up with two sessions on it.

**Files:**
- Create: `app/src/main/java/app/drydock/app/SessionHandoffService.java`
- Delete: `app/src/main/java/app/drydock/app/SessionForkService.java`
- Delete: `app/src/main/java/app/drydock/git/WorktreeTransplant.java`
- Delete: `app/src/test/java/app/drydock/git/WorktreeTransplantTest.java`
- Create: `app/src/test/java/app/drydock/app/SessionHandoffServiceTest.java`
- Delete: `app/src/test/java/app/drydock/app/SessionForkServiceTest.java`

**Interfaces:**
- Consumes: `HandoffFacts` and `HandoffSeed.compose` (Task 2);
  `ReviewScopeRegistry.rebind` (Task 1), through the `ScopeRebinder` seam.
- Produces:
  - `public ManagedSessionId handOffBlocking(ManagedAgentSession outgoing, AgentKind target)`
  - `public CompletableFuture<ManagedSessionId> handOff(ManagedAgentSession outgoing, AgentKind target)`
  - `public HandoffStaleness stalenessBlocking(ManagedAgentSession session)` (unchanged behaviour)
  - `public void sweepStaleSeeds(Instant now)` (unchanged behaviour)
  - seams `Launcher`, `SessionDeleter`, `ScopeRebinder` (signatures below)
  Task 5 wires all of these in `MainWorkspace`.

Start from `SessionForkService.java` and edit it down — most of the file
(`stalenessBlocking`, `seedPointer`, `undeliverableSeed`, `sweepStaleSeeds`,
`capped`, `gitOut`, `lines`, `countLines`, `parseCountOrZero`, `run`, `git`,
`branchOf`, `join`) survives verbatim and its comments are load-bearing. What
goes: `Transplanter`, `initSubmodules`, `rollback`, `availableBranchName`,
`branchExists`, `MAX_BRANCH_SUFFIX`, and the `locator`-driven worktree
plumbing in the old `forkBlocking`. What goes from the constructor:
`transplant`, `repositoryRootLookup`, `worktreeDirectory`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/app/SessionHandoffServiceTest.java` by
copying `SessionForkServiceTest.java` and reworking it. Keep verbatim: the
`@BeforeEach` real-repository setup, the `session(...)`, `brief(...)`,
`commit`, `git`, `gitCapture`, `seedFile`, `seedFileContents` fixtures, and
every staleness, seed-content, seed-permission and sweep test (they assert
behaviour this task does not change). Delete the tests named
`forksOntoASiblingWorktreeRunningTheChosenAgent`,
`leavesTheOutgoingWorktreeExactlyAsItWas`,
`carriesTheOutgoingDirtyTreeIntoTheFork`,
`theSeedFileLivesOutsideTheWorktreeSoItNeverEntersTheForksDiff`,
`suffixesTheBranchNameWhenTheNaturalOneIsTaken` and
`aFailedTransplantLeavesNoWorktreeNoBranchAndNoSession`, and drop the
`FailableTransplant` fake and the `WorktreeTransplant` import.

Replace the launcher fake and add the two new fakes:

```java
    /** Records what it was asked to start, and never starts anything real. */
    private static final class RecordingLauncher implements SessionHandoffService.Launcher {
        int startCount;
        String lastPrompt;
        AgentKind lastKind;
        ManagedAgentSession lastOutgoing;
        final ManagedSessionId successorId = ManagedSessionId.newId();

        @Override
        public CompletableFuture<ManagedSessionId> start(ManagedAgentSession outgoing, AgentKind kind,
                                                         String seedPrompt) {
            startCount++;
            lastOutgoing = outgoing;
            lastKind = kind;
            lastPrompt = seedPrompt;
            return CompletableFuture.completedFuture(successorId);
        }
    }

    /** The delete, unless told to fail -- which is how the abort is exercised. */
    private static final class FailableDeleter implements SessionHandoffService.SessionDeleter {
        final List<ManagedSessionId> deleted = new ArrayList<>();
        boolean failNext;

        @Override
        public CompletableFuture<Void> delete(ManagedSessionId sessionId) {
            if (failNext) {
                return CompletableFuture.failedFuture(new IllegalStateException("state write refused"));
            }
            deleted.add(sessionId);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Records the rebind so ordering against the launch can be asserted. */
    private static final class RecordingRebinder implements SessionHandoffService.ScopeRebinder {
        ManagedSessionId from;
        ManagedSessionId to;

        @Override
        public void rebind(ManagedSessionId outgoing, ManagedSessionId successor) {
            from = outgoing;
            to = successor;
        }
    }
```

Build the service in `setUp` as:

```java
        launcher = new RecordingLauncher();
        deleter = new FailableDeleter();
        rebinder = new RecordingRebinder();
        service = new SessionHandoffService(
                new GitStatusService(),
                new GitExecutableLocator(),
                launcher,
                deleter,
                rebinder,
                id -> Optional.ofNullable(briefs.get(id)),
                id -> List.of("Rework the rail"),
                seedDirectory,
                ForkJoinPool.commonPool());
```

and add these tests:

```java
    @Test
    void theSuccessorRunsInTheOutgoingSessionsOwnWorktree() {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(AgentKind.CODEX, launcher.lastKind);
        assertEquals(outgoing.id(), launcher.lastOutgoing.id());
        assertEquals(outgoing.workingDirectory(), launcher.lastOutgoing.workingDirectory());
    }

    @Test
    void nothingInTheWorkingTreeMovesOrChanges() throws Exception {
        // The rescue case: the uncommitted work is exactly what must survive,
        // and the surest way to keep it is never to copy it.
        Files.writeString(repositoryRoot.resolve("wip.txt"), "half done");
        String before = gitCapture(repositoryRoot, "status", "--porcelain");

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "status", "--porcelain"));
        assertEquals("feat/work", gitCapture(repositoryRoot, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertEquals("half done", Files.readString(repositoryRoot.resolve("wip.txt")));
    }

    @Test
    void noBranchIsCreated() throws Exception {
        String before = gitCapture(repositoryRoot, "branch", "--list");

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "branch", "--list"));
    }

    @Test
    void noWorktreeIsCreated() throws Exception {
        String before = gitCapture(repositoryRoot, "worktree", "list");

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(before, gitCapture(repositoryRoot, "worktree", "list"));
    }

    @Test
    void theOutgoingSessionIsDeletedBeforeTheSuccessorStarts() {
        // `gitCapture` and `Files.*` throw; any test here that calls them
        // needs `throws Exception`, as the copied ones already do.
        // Two agent processes on one worktree is the one hazard this design
        // has no defence against beyond never creating it.
        deleter.observeStartCount = () -> launcher.startCount;

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(List.of(outgoing.id()), deleter.deleted);
        assertEquals(0, deleter.startCountAtDelete);
        assertEquals(1, launcher.startCount);
    }

    @Test
    void aFailedDeleteStartsNoSuccessor() {
        // The surface is closed but the metadata write lost: degraded, not
        // damaged. Starting anyway would put two sessions on one worktree.
        deleter.failNext = true;

        assertThrows(IllegalStateException.class, () -> service.handOffBlocking(outgoing, AgentKind.CODEX));

        assertEquals(0, launcher.startCount, "no session may be started for a failed handoff");
    }

    @Test
    void theOutgoingSessionsReviewScopesFollowItToTheSuccessor() {
        ManagedSessionId successor = service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertEquals(outgoing.id(), rebinder.from);
        assertEquals(successor, rebinder.to);
    }

    @Test
    void anAlreadyDeadSessionIsHandedOffWithoutSpecialCasing() {
        // The common rescue case. deleteSession closes nothing when there is
        // no active surface and removes the metadata just the same, so this
        // path needs no branch of its own -- assert that it has none.
        ManagedAgentSession dead = session(repositoryRoot).withStatus(SessionStatus.EXITED);

        service.handOffBlocking(dead, AgentKind.CODEX);

        assertEquals(List.of(dead.id()), deleter.deleted);
        assertEquals(1, launcher.startCount);
    }

    @Test
    void handingOffToTheSameAgentIsAllowed() {
        // A wedged process is a reason to hand off even when the harness is fine.
        service.handOffBlocking(outgoing, AgentKind.CLAUDE);

        assertEquals(AgentKind.CLAUDE, launcher.lastKind);
    }

    @Test
    void theSeedIsWrittenBeforeTheDeleteTakesTheBriefWithIt() throws Exception {
        briefs.put(outgoing.id(), brief(outgoing.id(), "Ship the handoff gesture"));

        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertTrue(seedFileContents().contains("Ship the handoff gesture"), seedFileContents());
    }

    @Test
    void theSeedFileLivesOutsideTheWorktreeSoItNeverEntersTheDiff() throws Exception {
        service.handOffBlocking(outgoing, AgentKind.CODEX);

        assertFalse(seedFile().startsWith(outgoing.workingDirectory()), seedFile().toString());
        assertEquals("", gitCapture(repositoryRoot, "status", "--porcelain").strip());
    }
```

`theOutgoingSessionIsDeletedBeforeTheSuccessorStarts` needs two extra members
on `FailableDeleter` — add them to the fake:

```java
        java.util.function.IntSupplier observeStartCount = () -> -1;
        int startCountAtDelete = -1;
```

and record inside `delete`, before returning the completed future:

```java
            startCountAtDelete = observeStartCount.getAsInt();
```

Rename every remaining `service.forkBlocking(` call in the copied tests to
`service.handOffBlocking(`, and rename the two tests whose names contain
"fork" (`seedsTheForkWithTheBriefWhenOneExists` →
`seedsTheSuccessorWithTheBriefWhenOneExists`, and the brief goal strings that
read "Ship the fork gesture" → "Ship the handoff gesture"). Update the class
javadoc to say the invariants are now about what git is left *not* doing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.app.SessionHandoffServiceTest"`
Expected: FAIL to compile — `cannot find symbol: class SessionHandoffService`.

- [ ] **Step 3: Write minimal implementation**

Create `SessionHandoffService.java` from `SessionForkService.java` with these
changes. The class javadoc:

```java
/**
 * Hands a session's work to another agent, in place.
 *
 * <p>The successor takes the outgoing session's place: same repository, same
 * worktree, same branch, same working tree exactly as it stands. Nothing is
 * copied, so nothing can be copied wrongly -- the uncommitted work the rescue
 * case exists to save never moves at all.</p>
 *
 * <p>The outgoing session is deleted rather than closed. A superseded session
 * left resumable is a trap: its transcript describes a tree that has since
 * moved on under a different agent, and reopening it would put a confident
 * model with stale context onto live files. Deleting it also keeps one
 * worktree to one session, which is what lets the sidebar go on matching them
 * one-to-one.</p>
 */
```

The seams, replacing `Launcher` and `Transplanter`:

```java
    /**
     * Starts the successor session and completes with its id.
     *
     * <p>Takes the whole outgoing session rather than extracted fields: it is
     * an immutable record, so the value stays valid after {@link
     * SessionDeleter} removes the entry from state, and every inheritance rule
     * then lives in one place ({@code SessionManager.prepareSuccessorSession})
     * instead of being spread across this signature.</p>
     *
     * <p>A seam rather than a direct call into the workspace, for the same
     * reason {@code McpSessionContext} is one: the build has no mocking
     * library, and a service that reached into JavaFX could only be exercised
     * on the FX thread. Asynchronous because opening a session is FX-thread
     * work while {@link #handOffBlocking} runs on a background thread.</p>
     */
    public interface Launcher {
        CompletableFuture<ManagedSessionId> start(ManagedAgentSession outgoing, AgentKind kind,
                                                  String seedPrompt);
    }

    /**
     * Removes the outgoing session. Matches {@code
     * SessionManager::deleteSession}, which closes the surface, releases the
     * MCP config, and drops the session and its brief from state.
     */
    public interface SessionDeleter {
        CompletableFuture<Void> delete(ManagedSessionId sessionId);
    }

    /**
     * Moves the outgoing session's review scopes onto the successor. Matches
     * {@code ReviewScopeRegistry::rebind}.
     */
    public interface ScopeRebinder {
        void rebind(ManagedSessionId outgoing, ManagedSessionId successor);
    }
```

The fields and constructor:

```java
    private final GitStatusService gitStatusService;
    private final GitExecutableLocator locator;
    private final Launcher launcher;
    private final SessionDeleter deleter;
    private final ScopeRebinder rebinder;
    private final Function<ManagedSessionId, Optional<HandoffBrief>> briefLookup;
    private final Function<ManagedSessionId, List<String>> openIntentLookup;
    private final Path seedDirectory;
    private final Executor backgroundExecutor;

    public SessionHandoffService(GitStatusService gitStatusService,
                                 GitExecutableLocator locator,
                                 Launcher launcher,
                                 SessionDeleter deleter,
                                 ScopeRebinder rebinder,
                                 Function<ManagedSessionId, Optional<HandoffBrief>> briefLookup,
                                 Function<ManagedSessionId, List<String>> openIntentLookup,
                                 Path seedDirectory,
                                 Executor backgroundExecutor) {
        this.gitStatusService = gitStatusService;
        this.locator = locator;
        this.launcher = launcher;
        this.deleter = deleter;
        this.rebinder = rebinder;
        this.briefLookup = briefLookup;
        this.openIntentLookup = openIntentLookup;
        this.seedDirectory = seedDirectory;
        this.backgroundExecutor = backgroundExecutor;
    }
```

Keep whatever `fork(...)` wrapper the old file had that hops onto
`backgroundExecutor`, renamed:

```java
    /** Runs {@link #handOffBlocking} on the background executor. */
    public CompletableFuture<ManagedSessionId> handOff(ManagedAgentSession outgoing, AgentKind target) {
        return CompletableFuture.supplyAsync(() -> handOffBlocking(outgoing, target), backgroundExecutor);
    }
```

The operation, replacing `forkBlocking`:

```java
    /**
     * Blocking; never call on the FX thread.
     *
     * <p>{@code target} may be the agent {@code outgoing} is already running:
     * a wedged process is a legitimate reason to hand off even when the
     * harness itself is fine, and so is wanting a second opinion from the
     * same model.</p>
     *
     * <p>The order is load-bearing. The seed is composed and written FIRST,
     * because {@code deleteSession} takes the brief with the session it
     * describes. The delete comes before the launch, because two agent
     * processes editing one worktree is the one hazard this design has no
     * defence against beyond never creating it -- so a delete that fails
     * aborts here, with the outgoing session's surface closed but its
     * metadata intact, rather than leaving two sessions on one tree. The
     * rebind comes last, because it needs the successor's id.</p>
     */
    public ManagedSessionId handOffBlocking(ManagedAgentSession outgoing, AgentKind target) {
        Path worktree = outgoing.workingDirectory();
        String branch = branchOf(worktree).orElse("HEAD");
        Optional<String> head = gitStatusService.headCommitBlocking(worktree);
        String prompt = seedPointer(
                HandoffSeed.compose(briefLookup.apply(outgoing.id()), factsFor(outgoing, worktree, branch, head)),
                branch);

        join(deleter.delete(outgoing.id()));
        ManagedSessionId successor = join(launcher.start(outgoing, target, prompt));
        rebinder.rebind(outgoing.id(), successor);
        return successor;
    }
```

`factsFor` loses `baseBranch` and gains the head commit:

```java
    private HandoffFacts factsFor(ManagedAgentSession outgoing, Path worktree, String branch,
                                  Optional<String> head) {
        List<String> subjects = head.isEmpty()
                ? List.of()
                : lines(gitOut(worktree, "log", "--format=%s",
                        "-n", String.valueOf(MAX_COMMIT_SUBJECTS), "HEAD"));
        List<String> changed = capped(lines(gitOut(worktree, "status", "--porcelain")), MAX_CHANGED_FILES);
        // The intents the human has not settled are the most concrete
        // statement of what is still open; a successor that re-litigates a
        // resolved one is doing the work twice.
        return new HandoffFacts(branch, head, subjects, changed,
                capped(openIntentLookup.apply(outgoing.id()), MAX_CHANGED_FILES));
    }
```

Then delete outright: the `Transplanter` interface, `initSubmodules`,
`rollback`, `availableBranchName`, `branchExists`, the `MAX_BRANCH_SUFFIX`
constant, and the now-unused imports (`WorktreeTransplant`, `WorktreeNaming`
stays — `seedPointer` uses `WorktreeNaming.slug`, `GitCommandFailedException`
stays — `run` throws it, `ProcessResult`/`ProcessRunner` stay). Delete
`SessionForkService.java`, `WorktreeTransplant.java`,
`WorktreeTransplantTest.java` and `SessionForkServiceTest.java`.

`MainWorkspace` will not compile until Task 5. Compile the module's tests for
this package only:

- [ ] **Step 4: Make the module compile, then run the test**

Deleting `SessionForkService` breaks `MainWorkspace`, and a module that does
not compile runs no tests — so this task's boundary includes the *mechanical*
rewiring in `MainWorkspace` and nothing more:

- `forkService` / `forkService()` → `handoffService` / `handoffService()`,
  built with the new constructor arguments (Task 5, Step 1 gives the exact
  code);
- `launchForkedSession` → `launchSuccessorSession` with the new `Launcher`
  signature (Task 5, Step 2 gives the exact code);
- `forkSessionTo` calls `handoffService().handOff(...)` instead of
  `forkService().fork(...)`;
- delete `forkedWorktreeOwner` and the `worktreeDirectory` lambda.

Leave the gesture, its copy, the confirmation and the diag verb exactly as
they are — Task 5 owns those, and a reviewer should be able to reject this
task's service rewrite without also rejecting the new dialog.

Run: `./gradlew :app:test --tests "app.drydock.app.SessionHandoffServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/app/drydock/app app/src/main/java/app/drydock/git \
           app/src/test/java/app/drydock/app app/src/test/java/app/drydock/git \
           app/src/main/java/app/drydock/ui/MainWorkspace.java
git commit -m "The successor starts in the session's own worktree, and the session goes"
```

---

### Task 5: The gesture, its confirmation, and the diag verb

The handoff now removes a session rather than adding one, which puts it among
drydock's destructive sequences — and those are confirmed. The confirmation
names what goes: this session's tab and its conversation. It does **not**
claim the worktree or the branch are at risk, because they are not.

`diagFork` becomes `diagHandoff` and keeps its reason for existing: it fires
the control's real `onShowing` handler and then the chosen item's real action,
so the verb cannot pass with the menu unwired. Robot input cannot reach a diag
run, so this is the only way to press this button without a human.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java:619`

**Interfaces:**
- Consumes: `SessionHandoffService` and its three seams (Task 4);
  `SessionManager.prepareSuccessorSession` (Task 3);
  `ReviewScopeRegistry.rebind` (Task 1).
- Produces: `public void handOffSessionTo(ManagedSessionId, AgentKind)`,
  `public void populateHandoffMenu(MenuButton, ManagedSessionId)`,
  `public String diagHandoff(String agentName)` on `MainWorkspace`.

- [ ] **Step 1: Rewire the service**

If Task 4's step 4 has not already done it, rename `forkService`/`forkService()`
to `handoffService`/`handoffService()` and build it with the new arguments:

```java
    private SessionHandoffService handoffService() {
        if (handoffService == null) {
            handoffService = new SessionHandoffService(
                    gitStatusService,
                    new GitExecutableLocator(),
                    this::launchSuccessorSession,
                    sessionManager::deleteSession,
                    reviewScopeRegistry::rebind,
                    id -> sessionManager.handoffBriefs().stream()
                            .filter(brief -> brief.sessionId().equals(id))
                            .findFirst(),
                    this::openIntentTitles,
                    stateDirectory.resolve("handoff-seeds"),
                    HANDOFF_EXECUTOR);
            handoffService.sweepStaleSeeds(Instant.now());
        }
        return handoffService;
    }
```

Delete `forkedWorktreeOwner` entirely — nothing names a worktree directory any
more — along with the `WorktreeNaming` and `UserConfig` imports if this was
their only use in the file.

- [ ] **Step 2: Replace the launcher**

Replace `launchForkedSession` with:

```java
    /**
     * Opens the successor in the outgoing session's own checkout. Everything
     * the successor inherits is decided by {@code
     * SessionManager.prepareSuccessorSession}; this method only finds the
     * owning repository, shows the pending tab, and launches.
     */
    private CompletableFuture<ManagedSessionId> launchSuccessorSession(ManagedAgentSession outgoing,
                                                                       AgentKind kind, String seedPrompt) {
        CompletableFuture<ManagedSessionId> opened = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Optional<Repository> owner = repositoryManager.repositories().stream()
                        .filter(repository -> repository.id().equals(outgoing.repositoryId()))
                        .findFirst();
                if (owner.isEmpty()) {
                    opened.completeExceptionally(new IllegalStateException(
                            "No registered repository owns " + outgoing.workingDirectory()));
                    return;
                }
                ManagedAgentSession prepared =
                        sessionManager.prepareSuccessorSession(owner.get(), outgoing, kind);
                opened.complete(openPreparedSession(prepared, prepared.displayName(),
                        Optional.of(seedPrompt), Spawn.ALLOWED, owner.get()));
            } catch (RuntimeException e) {
                opened.completeExceptionally(e);
            }
        });
        return opened;
    }
```

Extract `openPreparedSession` from the tail of the existing
`openWorktreeSession(…, Spawn, Optional<ManagedSessionId>, boolean)` so both
share one launch path — the pending tab, `launchSession`, `handleOpenResult`
and `sendTaskWhenReady` are identical for both callers:

```java
    /** The shared launch tail: pending tab, launch, then the seeded prompt when it is ready. */
    private ManagedSessionId openPreparedSession(ManagedAgentSession prepared, String tabLabel,
                                                 Optional<String> task, Spawn spawn, Repository repository) {
        OpenSessionTab placeholderTab = showPendingTab(prepared.id(), tabLabel,
                AgentLabels.displayName(agentRegistry, prepared), prepared.agentKind(),
                prepared.status() == SessionStatus.UNSUPPORTED_AGENT,
                Optional.of(repository), prepared.workingDirectory());

        double scale = stage.getOutputScaleX();
        sessionManager.launchSession(prepared, placeholderTab.app(), placeholderTab.host(), scale, spawn)
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    handleOpenResult(placeholderTab, result, ex);
                    if (ex == null && result instanceof SessionOpenResult.Opened && task.isPresent()) {
                        sendTaskWhenReady(placeholderTab, task.get());
                    }
                }));
        return prepared.id();
    }
```

and have `openWorktreeSession` call it with `branch` as the tab label, keeping
its existing "Keyed under the real session id for the same launch-race reason
as openNewSession" comment on the `prepareWorktreeSession` call it retains.

- [ ] **Step 3: Replace the gesture with a confirmed one**

Replace `forkSessionTo` and `populateForkMenu`:

```java
    /**
     * <em>Hand off</em>: replace this session with one running {@code target}
     * in the same worktree, seeded from the brief.
     *
     * <p>Confirmed first, because it removes a session. The worktree, the
     * branch and every uncommitted change are untouched -- the confirmation
     * says so, so the human is deciding about the conversation and nothing
     * else.</p>
     */
    public void handOffSessionTo(ManagedSessionId sessionId, AgentKind target) {
        Optional<ManagedAgentSession> session = sessionManager.sessions().stream()
                .filter(candidate -> candidate.id().equals(sessionId))
                .findFirst();
        if (session.isEmpty()) {
            return;
        }
        // Pre-flight, and the reason it is here rather than inside the
        // service: the delete is committed before the launch runs, so
        // anything the launch needs that can be checked in advance MUST be
        // checked before the session is destroyed. An unregistered repository
        // is the one predictable way that launch fails, and finding out
        // afterwards would cost the session with no successor to show for it.
        if (repositoryManager.repositories().stream()
                .noneMatch(repository -> repository.id().equals(session.get().repositoryId()))) {
            Alert missing = new Alert(Alert.AlertType.WARNING);
            missing.setTitle("Could not hand off this session");
            missing.setHeaderText("No registered repository owns this session");
            missing.setContentText("Drydock no longer has a repository registered for "
                    + session.get().workingDirectory()
                    + ", so it cannot start a successor there. Nothing has been changed.");
            missing.showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Hand off this session");
        confirm.setHeaderText("Hand \"" + session.get().displayName() + "\" to "
                + AgentLabels.displayName(agentRegistry, target) + "?");
        confirm.setContentText("This session's tab and its conversation are removed, and "
                + AgentLabels.displayName(agentRegistry, target)
                + " takes over the same worktree with a brief of what happened here. "
                + "The branch, the working tree and every uncommitted change stay exactly as they are.");
        if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }
        handoffService().handOff(session.get(), target)
                .whenComplete((successor, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        // Deliberately vague about what survives, because it
                        // depends on how far the handoff got: a failure before
                        // the delete leaves the session intact, and one after
                        // it leaves no session at all. What is true either way
                        // -- and the thing the human is about to worry about
                        // -- is that the tree, the branch and every
                        // uncommitted change are untouched.
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Could not hand off this session");
                        alert.setHeaderText("Could not hand off this session");
                        alert.setContentText(UiErrors.unwrap(failure).getMessage()
                                + "\n\nThe worktree, the branch and every uncommitted change are "
                                + "untouched. If this session's tab is gone, its work is still on disk "
                                + "and you can open a new session on the same worktree.");
                        alert.showAndWait();
                        return;
                    }
                    publishSessions();
                }));
    }

    /**
     * Fills a handoff control with the installed agents. An unavailable one is
     * shown DISABLED with where drydock looked, rather than hidden: "Codex is
     * not installed" is a fact the human can act on; an absent row is not.
     */
    public void populateHandoffMenu(MenuButton control, ManagedSessionId sessionId) {
        control.getItems().clear();
        for (Agent agent : agentRegistry.agents()) {
            MenuItem item = new MenuItem(agent.displayName());
            if (agent.isAvailable()) {
                item.setOnAction(event -> handOffSessionTo(sessionId, agent.kind()));
            } else {
                item.setText(agent.displayName() + " (not installed)");
                item.setDisable(true);
                Tooltip.install(control, new Tooltip(agent.describeSearched()));
            }
            control.getItems().add(item);
        }
    }
```

Update `wireHandoffBanner` to call `populateHandoffMenu`, and its javadoc
sentence "the session header's persistent Fork control" to "the session
header's persistent Hand off control".

`OpenSessionTab`'s accessor is still called `forkButton()` at this point —
Task 6 renames it together with the label, and sweeps this file. Use the
current name here rather than reaching forward to one that does not exist
yet.

- [ ] **Step 4: Rename the diag verb**

Rename `diagFork` to `diagHandoff`, changing only the identifiers and copy —
its body and its javadoc's reasoning stay:

```java
        MenuButton handoff = active.getValue().forkButton();   // renamed in Task 6
        EventHandler<javafx.event.Event> onShowing = handoff.getOnShowing();
        if (onShowing == null) {
            return "the handoff button has no onShowing handler, so its menu never populates";
        }
```

and its failure strings: `"no agent matching " + quoted(agentName) + " among "`
stays, `quoted(chosen.get().getText()) + " cannot be forked to"` becomes
`quoted(chosen.get().getText()) + " cannot be handed off to"`.

In `DrydockApplication.java` around line 612, rename the verb and its comment:

```java
                            // history instead of forcing a number, and handoff:<agent>
                            // performs the handoff gesture through the button's own
                            // handlers. Together they drive a live end-to-end handoff,
                            ...
                            case "handoff" -> System.out.println("[diag] handoff -> "
                                    + mainWorkspace.diagHandoff(arg));
```

Note that the confirmation dialog now sits between the verb and the service,
so a diag run of `handoff:` will block on a modal. Say so in the verb's
javadoc: a screenshot driver must dismiss it, and that is the point — the
confirmation is part of the gesture being exercised.

- [ ] **Step 5: Check the pre-flight by reading, not by testing**

`handOffSessionTo` is FX-thread code with no headless harness, so the
pre-flight has no unit test. Read it once against this question: is there any
path where `deleteSession` runs before the repository lookup? If there is, the
pre-flight is decoration. Say in your report which lines you checked.

- [ ] **Step 6: Build and run the affected tests**

Run: `./gradlew :app:test --tests "app.drydock.app.*" --tests "app.drydock.ui.*" --tests "app.drydock.handoff.*" --tests "app.drydock.review.*"`
Expected: PASS. Fix any remaining references the compiler finds.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/main/java/app/drydock/DrydockApplication.java
git commit -m "Handing off is a confirmed gesture on the session it replaces"
```

Run `git status --short` first and stage anything else you changed — a commit
that does not build is worse than a noisy one.

---

### Task 6: Say "hand off" everywhere the human can read

The control still says "Fork to…" and its tooltip still promises a sibling
worktree, which is now simply untrue. This task makes the visible copy match
what the gesture does.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java:191,856-858,879,902-906,1381-1387`
- Modify: `app/src/main/java/app/drydock/ui/HandoffBanner.java:28-32,83`
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (`.header-fork-button`)
- Test: `app/src/test/java/app/drydock/ui/SessionHeaderLayoutTest.java`
- Test: `app/src/test/java/app/drydock/ui/HandoffBannerTest.java`

**Interfaces:**
- Consumes: `MainWorkspace.populateHandoffMenu` and `diagHandoff` (Task 5).
- Produces: `MenuButton OpenSessionTab.handoffButton()` (package-private, as
  `forkButton()` was).

- [ ] **Step 1: Update the header-layout test first**

`SessionHeaderLayoutTest` is a regression test found by screenshotting a live
run: at a realistic tab width the header's controls truncated to "R.." and
"...". "Hand off to…" is **longer** than "Fork to…", so this test is the thing
standing between the rename and a repeat of that bug. Change its fixture
first and watch it fail if the layout cannot take the longer label:

```java
        handoff = new MenuButton("Hand off to…");
```

renaming the field from `fork` to `handoff` throughout the file, including the
`layOutSessionHeader` call and the `List.of(back, chips, handoff, finishBox,
statusPill, rename)` pinned-region loop.

- [ ] **Step 2: Run it to see where the layout stands**

Run: `./gradlew :app:test --tests "app.drydock.ui.SessionHeaderLayoutTest"`
Expected: FAIL to compile first (the field rename), then either PASS — the
header takes the longer label — or FAIL on a truncation assertion. If it
fails on truncation, shorten the label to "Hand off…" and re-run rather than
relaxing the assertion: the assertion is the finding.

- [ ] **Step 3: Rename the control**

In `OpenSessionTab`:

```java
    private final MenuButton handoffButton = new MenuButton("Hand off to…");
```

```java
        handoffButton.getStyleClass().add("header-handoff-button");
        handoffButton.setFocusTraversable(false);
        handoffButton.setTooltip(new Tooltip("Hand this session's work to another agent. "
                + "The successor continues in this same worktree, on this branch, over these "
                + "same uncommitted changes; this session and its conversation are removed."));
```

and rename the field, the `layOutSessionHeader` argument, the pinned-region
list entry and the accessor. Task 5 left two call sites using the old
accessor name — `wireHandoffBanner` and `diagHandoff`, both in
`MainWorkspace` — so rename those with it:

```java
    /**
     * The persistent <code>Hand off to…</code> control in the session header.
     * Always visible, unlike the banner's verbs: handing off is the primary
     * handoff action and must stay reachable once the staleness warning
     * clears.
     */
    MenuButton handoffButton() {
        return handoffButton;
    }
```

In `app.css`, rename the `.header-fork-button` selector to
`.header-handoff-button`. Check for the old name with
`grep -rn "header-fork-button" app/src` and leave nothing behind.

- [ ] **Step 4: Fix the banner copy**

In `HandoffBanner`, the class javadoc paragraph becomes:

```java
 * <p>Handing off is not a corrective verb -- it proceeds whether the brief is
 * stale or current -- so it lives in the session header as a persistent
 * control, not here. This banner warns; it does not host the primary action,
 * because hosting it meant the action vanished the moment the warning cleared
 * (see the {@code Hand off to…} control on {@code OpenSessionTab}).</p>
```

and the disabled-Refresh tooltip's last sentence:

```java
                        + "Edit the brief yourself, or hand off anyway."));
```

If `HandoffBannerTest` asserts on that tooltip string, update the expectation
to match.

- [ ] **Step 5: Sweep the remaining copy**

Run `grep -rn -i "fork" app/src/main/java/app/drydock/handoff app/src/main/java/app/drydock/app/SessionHandoffService.java app/src/main/java/app/drydock/domain/HandoffBrief.java app/src/main/java/app/drydock/ui/HandoffEditDialog.java`
and fix the known stragglers, which are comments rather than behaviour:

- `HandoffStaleness.java:18` — "(see {@code SessionForkService})" becomes
  "(see {@code SessionHandoffService})".
- `SessionHandoffService.java`, the `seedPointer` javadoc — "would show up in
  the fork's very first diff" becomes "the successor's very first diff".
- `SessionHandoffService.java`, `undeliverableSeed`'s comment — "The fork
  itself is still worth having" becomes "The handoff itself is still worth
  having".
- `HandoffBrief.java:29` — "the seed a fork is launched with labels" becomes
  "the seed a successor is launched with labels".
- `HandoffEditDialog.java:109` — "is later flattened into the fork seed"
  becomes "is later flattened into the handoff seed".

Leave every other `fork` in the tree alone: `ForkJoinPool`, git's
`fork-point`, and the worktree modal's "Fork from" branch picker are all
unrelated to this feature.

Verify `McpServer.INSTRUCTIONS` (around line 398) still reads correctly — it
says "This session may be handed to a different agent at any moment", which
was already harness-neutral and needs no change. Confirm rather than assume.

- [ ] **Step 6: Run the UI tests**

Run: `./gradlew :app:test --tests "app.drydock.ui.SessionHeaderLayoutTest" --tests "app.drydock.ui.HandoffBannerTest" --tests "app.drydock.ui.HandoffEditDialogTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui app/src/main/java/app/drydock/handoff \
        app/src/main/java/app/drydock/domain/HandoffBrief.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui
git commit -m "The control says what it does: hand off, not fork"
```

---

### Task 7: The whole suite, and a look at the real thing

Two of this feature's properties have no JUnit assertion behind them: whether
the longer control label and the new confirmation actually fit and read well
at real tab width, and whether the gesture works end to end against live
agents. `docs/architecture.md` covers the first with a diag script and its
`shot:` scene snapshot. The second needs a human, because robot input never
reaches a diag run — synthetic events are reported as delivered and are not.

**Files:**
- Modify: `docs/superpowers/plans/2026-08-21-handoff-in-place.md` (tick the boxes)

- [ ] **Step 1: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS. This takes 14–20 minutes; run it in the foreground and wait.
If a test outside the files this plan names fails, read it before touching it
— it is telling you about a coupling this plan missed, and the plan is what is
wrong.

- [ ] **Step 2: Confirm nothing still references the old mechanism**

```bash
grep -rn "SessionForkService\|WorktreeTransplant\|forkSessionTo\|populateForkMenu\|diagFork\|header-fork-button" app/src docs scripts
```
Expected: no matches. `docs/superpowers/specs/2026-08-12-harness-handoff-design.md`
may still describe the old mechanism inside its "What was cut" section — that
is deliberate and stays.

- [ ] **Step 3: Screenshot the header and the confirmation**

Launch the worktree's build (`build/image/Drydock.app`, not `/Applications`)
with the diag properties from `docs/architecture.md`, and take a `shot:` scene
snapshot of a session header at a realistic tab width, then of the
confirmation dialog. Look at both. The specific failure this catches has
happened before on this exact control: buttons truncating to "R.." and "..."
at a width the layout test was happy with.

- [ ] **Step 4: Drive one real handoff**

With a live session in a worktree with uncommitted changes and a brief
recorded, use "Hand off to…" to hand it to a different installed agent.
Confirm by looking, not by inference: the outgoing tab is gone, the successor
occupies its place under the same title with a different agent badge, the
worktree still holds the uncommitted changes, `git status` in that worktree is
unchanged, the successor's first prompt points at a seed file that exists and
is owner-only, and the sidebar shows one row for that worktree rather than
two.

- [ ] **Step 5: Write the implementation report**

State plainly which properties were tested and which were looked at. Per the
spec's Verification section, visual state is "checked with a diag script and
its `shot:` scene snapshot" and the report says so rather than claiming
"tested".

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans/2026-08-21-handoff-in-place.md
git commit -m "Handoff in place: full suite green and the gesture driven live"
```
