# Worktree From An Existing Branch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the create-worktree modal open a worktree on an existing branch — local or remote — instead of only creating a new one.

**Architecture:** A branch catalog (refs from `GitStatusService` composed with worktree occupancy from the existing `WorktreeService`) drives an editable `ComboBox`; the modal's mode (create vs. checkout) is *derived* from whether the typed text names a known branch, never toggled. Because checking out a pre-existing branch breaks the invariant that every app-made worktree owns a branch the app just created, the delete paths become provenance-aware.

**Tech Stack:** Java 24 (records, FFM elsewhere), JavaFX, JUnit 5, Gradle (`./gradlew :app:test`). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-22-worktree-from-existing-branch-design.md`

## Global Constraints

Copied from `AGENTS.md`; every task's requirements implicitly include these.

- **Never block the JavaFX Application Thread.** Process spawns, filesystem I/O, and network calls run on a background executor (`CompletableFuture` + the owning service's executor); hop back with `Platform.runLater` only to touch UI.
- **Every user-triggered async operation shows progress immediately**, and **every** completion path — success, error, AND early return — clears it. Never leave a spinner or busy state stranded.
- **All child processes go through `app.drydock.process.ProcessRunner`** — never a hand-rolled `ProcessBuilder` in a service. Every spawn has a timeout. Arguments are a list, never a shell string.
- **Positional revision/branch/path arguments that can start with `-` are preceded by `--end-of-options`.**
- **A failed command is never silently an empty result:** throw the service's exception type, or log a WARNING with an stderr excerpt.
- **Package-private `…Blocking` forms** accompany each async service method so tests assert on the thrown exception type directly instead of unwrapping `CompletionException`.
- **No fully-qualified class names inline** — use imports (except same-name-different-package collisions).
- Tests run with `./gradlew :app:test`. A single test: `./gradlew :app:test --tests "app.drydock.git.BranchCatalogTest"`.
- **There is no TestFX and no FX toolkit in unit tests.** Anything that must be tested has to be a toolkit-free unit (records, pure statics, `javafx.util.StringConverter` — which instantiates fine without the toolkit). `ComboBox`/`Node` construction in a test will fail.

## File Structure

**Created**
- `app/src/main/java/app/drydock/git/BranchRef.java` — one branch (name, remote flag, occupancy).
- `app/src/main/java/app/drydock/git/BranchListing.java` — raw `listBranches` result: refs + remote names.
- `app/src/main/java/app/drydock/git/BranchCatalog.java` — pure merge of refs + worktrees, plus `lookup`/`localName`. The mode oracle. **This is where the listing rules get tested.**
- `app/src/main/java/app/drydock/ui/BranchRefConverter.java` — `StringConverter<BranchRef>`; identity on `name`.
- `app/src/main/java/app/drydock/domain/BranchOwnership.java` — pure predicate: may this worktree's branch be deleted?
- Tests: `BranchRefConverterTest`, `BranchCatalogTest`, `BranchOwnershipTest`, `ProcessRunnerTest` (if absent).

**Modified**
- `app/src/main/java/app/drydock/process/ProcessRunner.java` — `Options` record (stdin discard + environment).
- `app/src/main/java/app/drydock/git/WorktreeService.java` — `Worktree` gains `prunable`/`locked`; `parse` reads them.
- `app/src/main/java/app/drydock/git/GitStatusService.java` — `listBranches` replaces `listLocalBranches`; `addWorktreeForBranch`; `fetchAll`; `run(command, timeout)` overload.
- `app/src/main/java/app/drydock/domain/ManagedClaudeSession.java` — `branchCreatedHere` component.
- `app/src/main/java/app/drydock/state/ApplicationStateCodec.java` — encode/decode it leniently.
- `app/src/main/java/app/drydock/app/SessionManager.java` — thread provenance through session creation; expose the predicate.
- `app/src/main/java/app/drydock/ui/NewWorktreeModal.java` — the combo, derived mode, `refreshState()`, two message slots.
- `app/src/main/java/app/drydock/ui/MainWorkspace.java` — handler signature, checkout branch.
- `app/src/main/java/app/drydock/ui/WorktreeLifecycleController.java` + `RepositorySidebar.java` — provenance-aware delete.

---

### Task 1: `ProcessRunner` gains stdin-discard and environment options

`git fetch` against an HTTPS remote needing credentials blocks on a terminal prompt, because `ProcessRunner` leaves stdin an open pipe nobody closes. The user would watch a spinner for the full timeout and get a bare "timed out".

**Files:**
- Modify: `app/src/main/java/app/drydock/process/ProcessRunner.java:48-56`
- Test: `app/src/test/java/app/drydock/process/ProcessRunnerTest.java`

**Interfaces:**
- Produces: `ProcessRunner.Options(Path workingDirectory, Duration timeout, boolean discardInput, Map<String,String> environment)` and `ProcessRunner.run(List<String> command, Options options)`. The existing 3-arg `run(command, workingDirectory, timeout)` keeps working unchanged.

- [ ] **Step 1: Write the failing test**

`/dev/null` is fine — this application is macOS-only (AppKit/FFM native host).

Create `app/src/test/java/app/drydock/process/ProcessRunnerTest.java` (if the file exists, add only the two methods and any missing imports):

```java
package app.drydock.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    @Test
    void discardInputClosesStdinSoAReaderExitsImmediately() throws Exception {
        // Without discardInput, `cat` blocks on the inherited pipe until the
        // timeout kills it. With it, cat sees EOF at once and exits 0.
        ProcessResult result = ProcessRunner.run(
                List.of("/bin/cat"),
                new ProcessRunner.Options(null, Duration.ofSeconds(5), true, Map.of()));

        assertEquals(0, result.exitCode());
        assertEquals("", result.stdout());
    }

    @Test
    void environmentEntriesReachTheChild() throws Exception {
        ProcessResult result = ProcessRunner.run(
                List.of("/bin/sh", "-c", "printf %s \"$DRYDOCK_TEST_VAR\""),
                new ProcessRunner.Options(null, Duration.ofSeconds(5), true,
                        Map.of("DRYDOCK_TEST_VAR", "set-by-test")));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("set-by-test"), result.stdout());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.process.ProcessRunnerTest"`
Expected: FAIL to **compile** — `cannot find symbol: class Options`.

- [ ] **Step 3: Write minimal implementation**

In `ProcessRunner.java`, add imports `java.io.File`, `java.util.Map`, then add the record and the overload, and make the existing `run` delegate:

```java
    /**
     * How to spawn a child: {@code workingDirectory} may be {@code null} to
     * inherit this process's cwd; {@code discardInput} redirects stdin from
     * {@code /dev/null} so a child that would prompt (e.g. {@code git fetch}
     * asking for credentials) sees EOF instead of hanging until
     * {@code timeout}; {@code environment} entries are added to the child's
     * inherited environment.
     */
    public record Options(Path workingDirectory, Duration timeout, boolean discardInput,
                          Map<String, String> environment) {
        public Options {
            Objects.requireNonNull(timeout, "timeout");
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        }
    }

    public static ProcessResult run(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException, InterruptedException {
        return run(command, new Options(workingDirectory, timeout, false, Map.of()));
    }

    public static ProcessResult run(List<String> command, Options options)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        if (options.workingDirectory() != null) {
            builder.directory(options.workingDirectory().toFile());
        }
        if (options.discardInput()) {
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        }
        builder.environment().putAll(options.environment());
        return runBuilder(builder, command, options.timeout());
    }
```

Add `import java.util.Objects;` if absent. Rename the **existing** method body: the old `run(List, Path, Duration)` body (from `ProcessBuilder builder = …` through the return) becomes a private helper:

```java
    private static ProcessResult runBuilder(ProcessBuilder builder, List<String> command, Duration timeout)
            throws IOException, InterruptedException {
```

with its first three lines (the `ProcessBuilder` construction and `workingDirectory` block) deleted, since the callers now build it. Everything from `Process process = builder.start();` onward is unchanged — including the concurrent drain and the `destroyForcibly()` timeout path.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.process.ProcessRunnerTest"`
Expected: PASS, 2 tests.

Then the full suite, to prove the refactor broke no existing caller:
Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/process/ProcessRunner.java app/src/test/java/app/drydock/process/ProcessRunnerTest.java
git commit -m "Let ProcessRunner discard stdin and extend the child environment"
```

---

### Task 2: `Worktree` learns `prunable` and `locked`

A branch owned by a worktree whose directory was deleted still blocks `git worktree add` (`fatal: 'x' is already used by worktree at '<gone>'`). Presenting that as a live checkout is a dead end, so the parser must surface it.

**Files:**
- Modify: `app/src/main/java/app/drydock/git/WorktreeService.java:53` (record), `:313-345` (`parse`)
- Test: `app/src/test/java/app/drydock/git/WorktreeServiceTest.java`

**Interfaces:**
- Produces: `WorktreeService.Worktree(Path path, Optional<String> branch, boolean mainCheckout, boolean detached, boolean prunable, boolean locked)` — two components appended, so every existing construction site needs two more arguments.

- [ ] **Step 1: Write the failing test**

Add to `WorktreeServiceTest`:

```java
    @Test
    void parseReadsPrunableAndLockedAttributes() {
        String porcelain = """
                worktree /repo
                HEAD 1111111111111111111111111111111111111111
                branch refs/heads/main

                worktree /gone
                HEAD 2222222222222222222222222222222222222222
                branch refs/heads/ghost
                prunable gitdir file points to non-existent location

                worktree /held
                HEAD 3333333333333333333333333333333333333333
                branch refs/heads/held-branch
                locked

                """;

        List<WorktreeService.Worktree> worktrees = WorktreeService.parse(porcelain);

        assertEquals(3, worktrees.size());
        assertFalse(worktrees.get(0).prunable());
        assertFalse(worktrees.get(0).locked());
        assertTrue(worktrees.get(1).prunable());
        assertEquals(Optional.of("ghost"), worktrees.get(1).branch());
        assertTrue(worktrees.get(2).locked());
        assertFalse(worktrees.get(2).prunable());
    }
```

Add any missing imports (`java.util.Optional`, `java.util.List`, `assertFalse`, `assertTrue`, `assertEquals`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.git.WorktreeServiceTest"`
Expected: FAIL to compile — `cannot find symbol: method prunable()`.

- [ ] **Step 3: Write minimal implementation**

Replace the record at `WorktreeService.java:53`:

```java
    /**
     * One worktree of a repository as reported by
     * {@code git worktree list --porcelain}. The first entry git prints is
     * always the main checkout ({@link #mainCheckout()}); a detached or
     * bare entry has no {@link #branch()}. A {@link #prunable()} entry's
     * directory is gone from disk but still owns its branch, and a
     * {@link #locked()} one refuses removal -- both still block
     * {@code git worktree add} on that branch.
     */
    public record Worktree(Path path, Optional<String> branch, boolean mainCheckout, boolean detached,
                           boolean prunable, boolean locked) {
    }
```

In `parse`, add two locals beside `detached`/`bare`, reset them in the blank-line block, set them in the attribute chain, and pass them to both `new Worktree(...)` sites (the in-loop one and the trailing one):

```java
        boolean prunable = false;
        boolean locked = false;
```

Reset block (alongside `detached = false; bare = false;`):

```java
                prunable = false;
                locked = false;
```

Attribute parsing — note `prunable` and `locked` both may carry a trailing reason, so match the prefix, not equality:

```java
            } else if (line.equals("prunable") || line.startsWith("prunable ")) {
                prunable = true;
            } else if (line.equals("locked") || line.startsWith("locked ")) {
                locked = true;
            }
```

Both construction sites become:

```java
                    worktrees.add(new Worktree(path, branch, worktrees.isEmpty(), detached, prunable, locked));
```

- [ ] **Step 4: Fix the other construction sites, then run tests**

Compile will point at every remaining `new Worktree(` — pass `false, false` for the two new components. Search first: `grep -rn "new Worktree(\|new WorktreeService.Worktree(" app/src`.

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/WorktreeService.java app/src/test/java/app/drydock/git/WorktreeServiceTest.java
git commit -m "Parse prunable and locked worktree attributes"
```

---

### Task 3: `GitStatusService.listBranches` — refs and remote names

`%(refname:short)` renders `refs/remotes/origin/HEAD` as **`origin`**, not `origin/HEAD`. Any filter shaped like the name both misses it and leaves a phantom branch called `origin` in the list, which would go on to produce `git worktree add <dir> -b "" --track origin`. The symref must be asked for explicitly.

**Files:**
- Create: `app/src/main/java/app/drydock/git/BranchRef.java`, `app/src/main/java/app/drydock/git/BranchListing.java`
- Modify: `app/src/main/java/app/drydock/git/GitStatusService.java:268-294` (replaces `listLocalBranches`)
- Test: `app/src/test/java/app/drydock/git/GitStatusServiceTest.java:243` (replaces `listLocalBranchesReportsEveryLocalBranch`)

**Interfaces:**
- Produces:
  - `BranchRef(String name, boolean remote, Optional<Path> checkedOutAt, boolean stale)`, factories `BranchRef.local(String)` / `BranchRef.remote(String)`, and `boolean available()`.
  - `BranchListing(List<BranchRef> branches, List<String> remotes)`.
  - `GitStatusService.listBranches(Path) -> CompletableFuture<BranchListing>` and package-private `listBranchesBlocking(Path)`. `checkedOutAt` is always empty and `stale` always false here — Task 4 fills them.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test**

Replace `listLocalBranchesReportsEveryLocalBranch` in `GitStatusServiceTest` with these two. The second **clones** — a test against a remote-less repo would pass vacuously, since `refs/remotes/…` would not exist at all.

```java
    @Test
    void listBranchesReportsEveryLocalBranch(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "branch", "develop");
        runGit(repo, "branch", "feat/x");

        BranchListing listing = service.listBranches(repo).get();

        List<String> names = listing.branches().stream().map(BranchRef::name).toList();
        assertEquals(List.of("develop", "feat/x", "main"), names);
        assertTrue(listing.branches().stream().noneMatch(BranchRef::remote));
        assertTrue(listing.remotes().isEmpty());
    }

    @Test
    void listBranchesIncludesRemotesAndSkipsTheOriginHeadSymref(@TempDir Path tmp) throws Exception {
        Path upstream = tmp.resolve("upstream");
        Files.createDirectory(upstream);
        initRepo(upstream, "main");
        writeFile(upstream, "README.md", "hello\n");
        runGit(upstream, "add", "README.md");
        commit(upstream, "initial commit");
        runGit(upstream, "branch", "feature/x");

        Path clone = tmp.resolve("clone");
        runGitIn(tmp, "clone", upstream.toString(), clone.toString());

        BranchListing listing = service.listBranches(clone).get();

        List<String> names = listing.branches().stream().map(BranchRef::name).toList();
        assertTrue(names.contains("origin/feature/x"), names.toString());
        assertTrue(names.contains("origin/main"), names.toString());
        // refs/remotes/origin/HEAD short-names to "origin" -- it must be
        // dropped as a symref, not survive as a phantom branch.
        assertFalse(names.contains("origin"), names.toString());
        assertEquals(List.of("origin"), listing.remotes());
    }
```

Add a helper beside the existing ones (the existing `runGit` always passes `-C <repo>`, which cannot host a `clone` that creates its target):

```java
    private static void runGitIn(Path cwd, String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.git.GitStatusServiceTest"`
Expected: FAIL to compile — `cannot find symbol: class BranchListing`.

- [ ] **Step 3: Write minimal implementation**

Create `BranchRef.java`:

```java
package app.drydock.git;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One branch offered by the create-worktree modal's picker: either a local
 * branch ({@code feat/minors}) or a remote-tracking one
 * ({@code origin/feature/x}). {@link #checkedOutAt()} is the worktree that
 * already holds it -- git refuses to check the same branch out twice, so a
 * present value blocks selection -- and {@link #stale()} marks that the
 * blocking worktree is prunable or locked, i.e. the block outlives a
 * directory that may no longer exist.
 */
public record BranchRef(String name, boolean remote, Optional<Path> checkedOutAt, boolean stale) {

    public BranchRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(checkedOutAt, "checkedOutAt");
        if (name.isBlank()) {
            throw new IllegalArgumentException("BranchRef name must not be blank");
        }
    }

    public static BranchRef local(String name) {
        return new BranchRef(name, false, Optional.empty(), false);
    }

    public static BranchRef remote(String name) {
        return new BranchRef(name, true, Optional.empty(), false);
    }

    /** Whether a worktree can be created on this branch right now. */
    public boolean available() {
        return checkedOutAt.isEmpty();
    }
}
```

Create `BranchListing.java`:

```java
package app.drydock.git;

import java.util.List;
import java.util.Objects;

/**
 * The raw result of {@link GitStatusService#listBranches}: every branch ref
 * plus the repository's remote names. The remote names are not decoration --
 * a remote may itself contain a slash ({@code git remote add team/fork}), so
 * they are the only way to split {@code team/fork/feature/x} into its remote
 * and its local name (see {@link BranchCatalog#localName}).
 */
public record BranchListing(List<BranchRef> branches, List<String> remotes) {

    public BranchListing {
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        remotes = List.copyOf(Objects.requireNonNull(remotes, "remotes"));
    }
}
```

In `GitStatusService`, replace `listLocalBranches`/`listLocalBranchesBlocking` with:

```java
    /**
     * Lists every branch -- local and remote-tracking -- plus the
     * repository's remote names, for the create-worktree modal's branch
     * picker, on this service's background executor. Occupancy
     * ({@link BranchRef#checkedOutAt()}) is filled in separately by
     * {@link BranchCatalog}, which composes this with
     * {@link WorktreeService#list}.
     */
    public CompletableFuture<BranchListing> listBranches(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> listBranchesBlocking(repositoryRoot), executor);
    }

    /** Synchronous form of {@link #listBranches}, package-private for tests. */
    BranchListing listBranchesBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> remotes = runLines(git, repositoryRoot, List.of("remote"));

        // %(symref) is the only reliable way to spot refs/remotes/origin/HEAD:
        // %(refname:short) renders it as "origin", so a name-shaped filter
        // would miss it and leave a phantom branch named after the remote.
        List<String> refLines = runLines(git, repositoryRoot, List.of(
                "for-each-ref", "--format=%(refname)%09%(symref)", "refs/heads/", "refs/remotes/"));

        List<BranchRef> branches = new ArrayList<>();
        for (String line : refLines) {
            String[] parts = line.split("\t", -1);
            if (parts.length > 1 && !parts[1].isBlank()) {
                continue; // symbolic ref (origin/HEAD), not a branch
            }
            String refName = parts[0];
            if (refName.startsWith("refs/heads/")) {
                branches.add(BranchRef.local(refName.substring("refs/heads/".length())));
            } else if (refName.startsWith("refs/remotes/")) {
                branches.add(BranchRef.remote(refName.substring("refs/remotes/".length())));
            }
        }
        return new BranchListing(List.copyOf(branches), remotes);
    }

    /** Runs a read-only git subcommand in {@code repositoryRoot}, returning its non-blank stdout lines. */
    private List<String> runLines(Path git, Path repositoryRoot, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(git.toString(), "-C", repositoryRoot.toString()));
        command.addAll(arguments);

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return result.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
```

`for-each-ref` sorts by refname by default, so `refs/heads/*` precedes `refs/remotes/*` and each group is alphabetical — which is the order the first test asserts.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.git.GitStatusServiceTest"`
Expected: PASS. `NewWorktreeModal.java:83` still calls the deleted `listLocalBranches`, so compilation of the main source set fails — apply the temporary shim below, since Task 9 rewrites that call properly.

In `NewWorktreeModal`, change the `listLocalBranches` block to:

```java
        gitStatusService.listBranches(repository.root()).whenComplete((listing, failure) ->
                Platform.runLater(() -> {
                    if (failure == null) {
                        baseField.getItems().setAll(listing.branches().stream()
                                .filter(branch -> !branch.remote())
                                .map(BranchRef::name)
                                .toList());
                    }
                }));
```

with `import app.drydock.git.BranchRef;`. This keeps the fork-from picker behaving exactly as before (local branches only).

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/BranchRef.java app/src/main/java/app/drydock/git/BranchListing.java app/src/main/java/app/drydock/git/GitStatusService.java app/src/main/java/app/drydock/ui/NewWorktreeModal.java app/src/test/java/app/drydock/git/GitStatusServiceTest.java
git commit -m "List local and remote branches, dropping the origin/HEAD symref"
```

---

### Task 4: `BranchCatalog` — occupancy, shadowing, local names, lookup

The pure heart of the feature. Everything the modal decides comes from here, and everything here is testable without a toolkit.

**Files:**
- Create: `app/src/main/java/app/drydock/git/BranchCatalog.java`
- Test: `app/src/test/java/app/drydock/git/BranchCatalogTest.java`

**Interfaces:**
- Consumes: `BranchRef`, `BranchListing` (Task 3); `WorktreeService.Worktree` with `prunable`/`locked` (Task 2).
- Produces:
  - `BranchCatalog(List<BranchRef> branches, List<String> remotes)`
  - `static BranchCatalog merge(BranchListing listing, List<Worktree> worktrees)`
  - `static CompletableFuture<BranchCatalog> load(GitStatusService, WorktreeService, Path root)`
  - `Optional<BranchRef> lookup(String text)` — the mode oracle
  - `String localName(BranchRef ref)` — used by Task 5 (`--track -b`), Task 9 (directory derivation), Task 10 (session branch name)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/git/BranchCatalogTest.java`:

```java
package app.drydock.git;

import app.drydock.git.WorktreeService.Worktree;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing rules the create-worktree picker depends on, as pure data:
 * occupancy, shadowing, remote-name splitting, and the text lookup that
 * decides whether the modal creates a branch or checks one out.
 */
class BranchCatalogTest {

    private static Worktree worktree(String path, String branch) {
        return new Worktree(Path.of(path), Optional.of(branch), false, false, false, false);
    }

    @Test
    void occupiedBranchCarriesItsWorktreePath() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.local("main"), BranchRef.local("idle")), List.of()),
                List.of(worktree("/src/olifer", "main")));

        BranchRef main = catalog.lookup("main").orElseThrow();
        assertEquals(Optional.of(Path.of("/src/olifer")), main.checkedOutAt());
        assertFalse(main.available());
        assertTrue(catalog.lookup("idle").orElseThrow().available());
    }

    @Test
    void aPrunableOrLockedWorktreeMarksItsBranchStale() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.local("ghost"), BranchRef.local("held")), List.of()),
                List.of(new Worktree(Path.of("/gone"), Optional.of("ghost"), false, false, true, false),
                        new Worktree(Path.of("/held"), Optional.of("held"), false, false, false, true)));

        assertTrue(catalog.lookup("ghost").orElseThrow().stale());
        assertTrue(catalog.lookup("held").orElseThrow().stale());
    }

    @Test
    void remoteBranchShadowedByASameNamedLocalOneIsDropped() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(
                        BranchRef.local("feature/x"),
                        BranchRef.remote("origin/feature/x"),
                        BranchRef.remote("origin/only-remote")), List.of("origin")),
                List.of());

        List<String> names = catalog.branches().stream().map(BranchRef::name).toList();
        assertEquals(List.of("feature/x", "origin/only-remote"), names);
    }

    @Test
    void localNameStripsTheLongestMatchingRemotePrefix() {
        // A remote may itself contain a slash: stripping the FIRST path
        // segment would yield "fork/feature/x" and create the wrong branch.
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.remote("team/fork/feature/x")),
                        List.of("origin", "team/fork")),
                List.of());

        assertEquals("feature/x", catalog.localName(BranchRef.remote("team/fork/feature/x")));
        assertEquals("feat/y", catalog.localName(BranchRef.local("feat/y")));
    }

    @Test
    void lookupPrefersTheLocalBranchOverASameNamedRemoteTrackingRef() {
        // A local branch may literally be named "origin/foo", which is
        // string-identical to origin's remote-tracking name for "foo".
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(
                        BranchRef.local("origin/foo"),
                        BranchRef.remote("origin/foo")), List.of("origin")),
                List.of());

        assertFalse(catalog.lookup("origin/foo").orElseThrow().remote());
    }

    @Test
    void lookupResolvesABareNameAgainstEachRemote() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.remote("origin/feature/x")), List.of("origin")),
                List.of());

        assertEquals("origin/feature/x", catalog.lookup("feature/x").orElseThrow().name());
        assertEquals("origin/feature/x", catalog.lookup("  origin/feature/x  ").orElseThrow().name());
        assertTrue(catalog.lookup("brand-new-branch").isEmpty());
        assertTrue(catalog.lookup("").isEmpty());
        assertTrue(catalog.lookup(null).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.git.BranchCatalogTest"`
Expected: FAIL to compile — `cannot find symbol: class BranchCatalog`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/app/drydock/git/BranchCatalog.java`:

```java
package app.drydock.git;

import app.drydock.git.WorktreeService.Worktree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Every branch the create-worktree modal may offer, with the occupancy that
 * decides whether it can be checked out -- assembled from
 * {@link GitStatusService#listBranches} and the existing
 * {@link WorktreeService#list}, never from a second porcelain parser.
 *
 * <p>{@link #lookup(String)} is the modal's mode oracle: text that names a
 * branch means "check this out", text that does not means "create it".</p>
 */
public record BranchCatalog(List<BranchRef> branches, List<String> remotes) {

    public BranchCatalog {
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        remotes = List.copyOf(Objects.requireNonNull(remotes, "remotes"));
    }

    /** Loads the refs and the worktree list concurrently and merges them. */
    public static CompletableFuture<BranchCatalog> load(GitStatusService gitStatusService,
                                                        WorktreeService worktreeService, Path repositoryRoot) {
        return gitStatusService.listBranches(repositoryRoot)
                .thenCombine(worktreeService.list(repositoryRoot), BranchCatalog::merge);
    }

    /**
     * Pure merge: fills each local branch's occupancy from {@code worktrees}
     * and drops any remote branch whose local name already exists locally
     * (picking {@code origin/x} when local {@code x} exists is just {@code x}).
     * Local branches sort first, then remotes, each alphabetically.
     */
    public static BranchCatalog merge(BranchListing listing, List<Worktree> worktrees) {
        Map<String, Worktree> byBranch = new HashMap<>();
        for (Worktree worktree : worktrees) {
            worktree.branch().ifPresent(branch -> byBranch.putIfAbsent(branch, worktree));
        }

        List<BranchRef> locals = new ArrayList<>();
        Set<String> localNames = new HashSet<>();
        for (BranchRef ref : listing.branches()) {
            if (ref.remote()) {
                continue;
            }
            localNames.add(ref.name());
            Worktree holder = byBranch.get(ref.name());
            locals.add(new BranchRef(ref.name(), false,
                    holder == null ? Optional.empty() : Optional.of(holder.path()),
                    holder != null && (holder.prunable() || holder.locked())));
        }
        locals.sort(Comparator.comparing(BranchRef::name));

        List<BranchRef> remoteRefs = new ArrayList<>();
        for (BranchRef ref : listing.branches()) {
            // A remote ref is never itself checked out; its local
            // counterpart would be, and those are dropped here.
            if (ref.remote() && !localNames.contains(localName(ref, listing.remotes()))) {
                remoteRefs.add(ref);
            }
        }
        remoteRefs.sort(Comparator.comparing(BranchRef::name));

        List<BranchRef> merged = new ArrayList<>(locals);
        merged.addAll(remoteRefs);
        return new BranchCatalog(merged, listing.remotes());
    }

    /**
     * The local branch name {@code ref} would check out as: a remote ref
     * loses its remote prefix, a local one is unchanged. The <em>longest</em>
     * matching remote wins, because a remote name may itself contain a slash
     * ({@code git remote add team/fork} is legal) -- stripping the first path
     * segment would turn {@code team/fork/feature/x} into {@code fork/feature/x}.
     */
    public String localName(BranchRef ref) {
        return localName(ref, remotes);
    }

    private static String localName(BranchRef ref, List<String> remotes) {
        if (!ref.remote()) {
            return ref.name();
        }
        String longest = null;
        for (String remote : remotes) {
            String prefix = remote + "/";
            if (ref.name().startsWith(prefix) && (longest == null || prefix.length() > longest.length())) {
                longest = prefix;
            }
        }
        return longest == null ? ref.name() : ref.name().substring(longest.length());
    }

    /**
     * Resolves picker text to a branch: an exact local match first (a local
     * branch may literally be named {@code origin/foo}), then an exact
     * remote-tracking match, then the bare name qualified by each remote.
     * Empty means "no such branch" -- i.e. create mode.
     */
    public Optional<BranchRef> lookup(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String needle = text.strip();
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        for (BranchRef ref : branches) {
            if (!ref.remote() && ref.name().equals(needle)) {
                return Optional.of(ref);
            }
        }
        for (BranchRef ref : branches) {
            if (ref.remote() && ref.name().equals(needle)) {
                return Optional.of(ref);
            }
        }
        for (String remote : remotes) {
            String qualified = remote + "/" + needle;
            for (BranchRef ref : branches) {
                if (ref.remote() && ref.name().equals(qualified)) {
                    return Optional.of(ref);
                }
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.git.BranchCatalogTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/BranchCatalog.java app/src/test/java/app/drydock/git/BranchCatalogTest.java
git commit -m "Add BranchCatalog: branch occupancy, shadowing, and mode lookup"
```

---

### Task 5: `addWorktreeForBranch` and a guarded `fetchAll`

**Files:**
- Modify: `app/src/main/java/app/drydock/git/GitStatusService.java` (add after `createWorktreeBlocking`; add the `run` overload near `:405`)
- Test: `app/src/test/java/app/drydock/git/GitStatusServiceTest.java`

**Interfaces:**
- Consumes: `BranchRef` (Task 3); `ProcessRunner.Options` (Task 1).
- Produces:
  - `addWorktreeForBranch(Path repositoryRoot, Path worktreeDirectory, BranchRef branch, String localName) -> CompletableFuture<Path>` (+ blocking form). `localName` comes from `BranchCatalog.localName`, keeping remote-name logic out of the service.
  - `fetchAll(Path repositoryRoot) -> CompletableFuture<Void>` (+ blocking form).

- [ ] **Step 1: Write the failing test**

Add to `GitStatusServiceTest`:

```java
    @Test
    void addWorktreeForLocalBranchChecksItOutWithoutCreatingARef(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectory(repo);
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "branch", "existing");
        String before = runGitCapture(repo, "rev-parse", "existing").strip();

        Path created = service.addWorktreeForBranch(
                repo, tmp.resolve("wt"), BranchRef.local("existing"), "existing").get();

        assertTrue(Files.exists(created.resolve("README.md")));
        assertEquals("existing", runGitCapture(created, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertEquals(before, runGitCapture(repo, "rev-parse", "existing").strip());
    }

    @Test
    void addWorktreeForRemoteBranchCreatesATrackingLocalBranch(@TempDir Path tmp) throws Exception {
        Path upstream = tmp.resolve("upstream");
        Files.createDirectory(upstream);
        initRepo(upstream, "main");
        writeFile(upstream, "README.md", "hello\n");
        runGit(upstream, "add", "README.md");
        commit(upstream, "initial commit");
        runGit(upstream, "branch", "feature/x");

        Path clone = tmp.resolve("clone");
        runGitIn(tmp, "clone", upstream.toString(), clone.toString());

        Path created = service.addWorktreeForBranch(
                clone, tmp.resolve("wt"), BranchRef.remote("origin/feature/x"), "feature/x").get();

        assertEquals("feature/x", runGitCapture(created, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertEquals("origin", runGitCapture(clone, "config", "branch.feature/x.remote").strip());
    }

    @Test
    void addWorktreeForABranchAlreadyCheckedOutFails(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectory(repo);
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        // "main" is checked out in the main checkout itself.
        assertThrows(GitCommandFailedException.class, () ->
                service.addWorktreeForBranchBlocking(repo, tmp.resolve("wt"), BranchRef.local("main"), "main"));
    }

    @Test
    void fetchAllSucceedsAgainstALocalRemote(@TempDir Path tmp) throws Exception {
        Path upstream = tmp.resolve("upstream");
        Files.createDirectory(upstream);
        initRepo(upstream, "main");
        writeFile(upstream, "README.md", "hello\n");
        runGit(upstream, "add", "README.md");
        commit(upstream, "initial commit");

        Path clone = tmp.resolve("clone");
        runGitIn(tmp, "clone", upstream.toString(), clone.toString());
        runGit(upstream, "branch", "added-later");

        service.fetchAll(clone).get();

        assertTrue(service.listBranches(clone).get().branches().stream()
                .anyMatch(branch -> branch.name().equals("origin/added-later")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.git.GitStatusServiceTest"`
Expected: FAIL to compile — `cannot find symbol: method addWorktreeForBranch`.

- [ ] **Step 3: Write minimal implementation**

Add to `GitStatusService` (imports: `app.drydock.process.ProcessRunner`, `java.util.Map` — `ProcessRunner` is already imported):

```java
    /** git fetch reaches the network; it needs far longer than a local status query. */
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Creates a worktree at {@code worktreeDirectory} on an <em>existing</em>
     * branch, on this service's background executor. A local branch is
     * checked out as-is; a remote-tracking one gets a local branch named
     * {@code localName} that tracks it ({@code -b <localName> --track}) --
     * never a detached checkout. {@code localName} comes from
     * {@link BranchCatalog#localName}, so remote-name splitting stays in one
     * place.
     */
    public CompletableFuture<Path> addWorktreeForBranch(Path repositoryRoot, Path worktreeDirectory,
                                                        BranchRef branch, String localName) {
        return CompletableFuture.supplyAsync(
                () -> addWorktreeForBranchBlocking(repositoryRoot, worktreeDirectory, branch, localName), executor);
    }

    /** Synchronous form of {@link #addWorktreeForBranch}, package-private for tests. */
    Path addWorktreeForBranchBlocking(Path repositoryRoot, Path worktreeDirectory, BranchRef branch,
                                      String localName) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        Path normalizedDir = prepareWorktreeParent(worktreeDirectory);

        List<String> command = new ArrayList<>(List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "add", normalizedDir.toString()));
        if (branch.remote()) {
            command.addAll(List.of("-b", localName, "--track"));
        }
        // --end-of-options: a ref that looks like an option must reach git as
        // a ref, never be parsed as a flag.
        command.addAll(List.of("--end-of-options", branch.name()));

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return normalizedDir;
    }

    /**
     * Updates every remote and drops stale remote-tracking refs
     * ({@code git fetch --all --prune}) so the branch picker can show
     * newly pushed branches, on this service's background executor.
     */
    public CompletableFuture<Void> fetchAll(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> {
            fetchAllBlocking(repositoryRoot);
            return null;
        }, executor);
    }

    /** Synchronous form of {@link #fetchAll}, package-private for tests. */
    void fetchAllBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(), "fetch", "--all", "--prune");

        // Credentials must never be prompted for: stdin is discarded and
        // GIT_TERMINAL_PROMPT=0 makes an auth-needing remote fail fast with a
        // real message instead of parking on a prompt until FETCH_TIMEOUT.
        ProcessResult result = run(command, new ProcessRunner.Options(
                null, FETCH_TIMEOUT, true, Map.of("GIT_TERMINAL_PROMPT", "0")));
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
    }
```

Extract the parent-directory creation that `createWorktreeBlocking` already does (`GitStatusService.java:239-249`) into the shared helper both now use, replacing those lines in `createWorktreeBlocking` with `Path normalizedDir = prepareWorktreeParent(worktreeDirectory);`:

```java
    /** Normalizes the target and creates its parent, as {@code git worktree add} will not. */
    private static Path prepareWorktreeParent(Path worktreeDirectory) {
        Path normalizedDir = worktreeDirectory.toAbsolutePath().normalize();
        Path parent = normalizedDir.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new GitCommandFailedException(
                        List.of("mkdir", parent.toString()), -1,
                        e.getMessage() == null ? "could not create parent directory" : e.getMessage());
            }
        }
        return normalizedDir;
    }
```

Add the `run` overload beside the existing private `run` (`:405`), and make the existing one delegate:

```java
    private static ProcessResult run(List<String> command) {
        return run(command, new ProcessRunner.Options(null, PROCESS_TIMEOUT, false, Map.of()));
    }

    private static ProcessResult run(List<String> command, ProcessRunner.Options options) {
        try {
            return ProcessRunner.run(command, options);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + options.timeout().toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git");
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.git.GitStatusServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/git/GitStatusService.java app/src/test/java/app/drydock/git/GitStatusServiceTest.java
git commit -m "Add worktrees on existing branches and a prompt-free fetch"
```

---

### Task 6: `BranchRefConverter` — the identity converter

The single most likely thing to ship broken, and the reason it gets its own task. On an editable `ComboBox`, selecting an item writes `converter.toString(item)` **into the editor**. Since mode is derived from editor text, a converter that returned decoration ("main — in use (…)") would flip the modal to create mode with a space-containing name the moment the user picked any branch from the dropdown — breaking the feature's primary interaction. Decoration belongs to the cell factory alone.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/BranchRefConverter.java`
- Test: `app/src/test/java/app/drydock/ui/BranchRefConverterTest.java`

**Interfaces:**
- Consumes: `BranchRef` (Task 3).
- Produces: `BranchRefConverter` (a `StringConverter<BranchRef>`) and `static String describe(BranchRef)` — the decorated form, for the cell factory only.

- [ ] **Step 1: Write the failing test**

`javafx.util.StringConverter` is a plain abstract class, not a `Node`, so this needs no toolkit.

```java
package app.drydock.ui;

import app.drydock.git.BranchRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editable ComboBox writes {@code toString} into its editor, and the
 * modal derives create-vs-checkout mode from that editor text -- so the
 * converter must be the identity on the branch name. Decoration lives only
 * in {@link BranchRefConverter#describe}, used by the cell factory.
 */
class BranchRefConverterTest {

    private final BranchRefConverter converter = new BranchRefConverter();

    @Test
    void toStringIsTheBareBranchNameEvenWhenTheBranchIsOccupied() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false);

        assertEquals("main", converter.toString(occupied));
        assertEquals("origin/feature/x", converter.toString(BranchRef.remote("origin/feature/x")));
        assertEquals("", converter.toString(null));
    }

    @Test
    void fromStringRoundTripsToStringExactly() {
        BranchRef branch = BranchRef.local("feat/minors");

        assertEquals(branch.name(), converter.fromString(converter.toString(branch)).name());
        assertEquals("typed-by-hand", converter.fromString("typed-by-hand").name());
    }

    @Test
    void describeAnnotatesOccupiedAndStaleBranchesForTheDropdownOnly() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false);
        BranchRef stale = new BranchRef("ghost", false, Optional.of(Path.of("/gone")), true);

        assertTrue(BranchRefConverter.describe(occupied).contains("in use"));
        assertTrue(BranchRefConverter.describe(occupied).contains("/src/olifer"));
        assertTrue(BranchRefConverter.describe(stale).contains("stale"));
        assertEquals("idle", BranchRefConverter.describe(BranchRef.local("idle")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "app.drydock.ui.BranchRefConverterTest"`
Expected: FAIL to compile — `cannot find symbol: class BranchRefConverter`.

- [ ] **Step 3: Write minimal implementation**

```java
package app.drydock.ui;

import app.drydock.git.BranchRef;
import javafx.util.StringConverter;

/**
 * Converts between {@link BranchRef} and the create-worktree modal's
 * editable branch combo box.
 *
 * <p><strong>The conversion is the identity on the branch name, and must
 * stay that way.</strong> Selecting an item in an editable {@code ComboBox}
 * writes {@code toString(item)} into the editor, and the modal derives
 * create-vs-checkout mode from that editor text -- so decoration here would
 * make picking a branch from the dropdown resolve to no branch at all.
 * Decoration belongs to {@link #describe}, which only the cell factory
 * calls.</p>
 */
final class BranchRefConverter extends StringConverter<BranchRef> {

    @Override
    public String toString(BranchRef branch) {
        return branch == null ? "" : branch.name();
    }

    @Override
    public BranchRef fromString(String text) {
        String name = text == null ? "" : text.strip();
        // Hand-typed text may name a branch that does not exist yet; the
        // catalog lookup -- not this converter -- decides what it means.
        return name.isEmpty() ? null : BranchRef.local(name);
    }

    /** The dropdown row's label: the name, plus why it cannot be selected. */
    static String describe(BranchRef branch) {
        if (branch.available()) {
            return branch.name();
        }
        Path holder = branch.checkedOutAt().orElseThrow();
        return branch.name() + (branch.stale()
                ? "  —  stale worktree (" + holder + ")"
                : "  —  in use (" + holder + ")");
    }
}
```

Add `import java.nio.file.Path;`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "app.drydock.ui.BranchRefConverterTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/BranchRefConverter.java app/src/test/java/app/drydock/ui/BranchRefConverterTest.java
git commit -m "Add an identity BranchRef converter for the branch combo"
```

---

### Task 7: Branch provenance on the session

`WorktreeService.removeBlocking` force-deletes with `git branch -D` (`WorktreeService.java:182-188`). That is safe today only because every worktree the app creates owns a branch it just created. Checking out a pre-existing branch breaks the invariant: resume `origin/colleagues-branch`, hit 🗑, and a branch the user did not create is force-deleted.

**Files:**
- Modify: `app/src/main/java/app/drydock/domain/ManagedClaudeSession.java` (record + 9 withers)
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java:135-160` (encode), `:243-268` (decode), and the class javadoc at `:78-88`
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java:190-230`, `:756-782`
- Create: `app/src/main/java/app/drydock/domain/BranchOwnership.java`
- Test: `app/src/test/java/app/drydock/domain/BranchOwnershipTest.java`, `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java`

**Interfaces:**
- Produces:
  - `ManagedClaudeSession` gains a final component `boolean branchCreatedHere` (appended **last**, after `prNumber`).
  - `BranchOwnership.mayDeleteBranchOf(List<ManagedClaudeSession> sessions, Path worktreeRoot) -> boolean`
  - `SessionManager.prepareWorktreeSession(Repository, String displayName, Path worktreeRoot, boolean branchCreatedHere)`
  - `SessionManager.mayDeleteBranchOf(Path worktreeRoot) -> boolean`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/app/drydock/domain/BranchOwnershipTest.java`:

```java
package app.drydock.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * git branch -D is irreversible for an unpushed branch, so the delete paths
 * only pass a branch name when drydock is the one that created it.
 */
class BranchOwnershipTest {

    private static ManagedClaudeSession session(Path worktreeRoot, boolean branchCreatedHere) {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new ManagedClaudeSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "s",
                Optional.empty(), Optional.empty(),
                worktreeRoot, Optional.of(worktreeRoot),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrState.NONE, Optional.empty(), branchCreatedHere);
    }

    @Test
    void aBranchThisAppCreatedMayBeDeleted() {
        Path worktree = Path.of("/wt/feature");
        assertTrue(BranchOwnership.mayDeleteBranchOf(List.of(session(worktree, true)), worktree));
    }

    @Test
    void aPreExistingBranchCheckedOutHereMayNotBeDeleted() {
        Path worktree = Path.of("/wt/feature");
        assertFalse(BranchOwnership.mayDeleteBranchOf(List.of(session(worktree, false)), worktree));
    }

    @Test
    void aWorktreeWithNoSessionAtAllMayNotHaveItsBranchDeleted() {
        // Discovered on disk by the sidebar rescan: drydock never created it,
        // so it has no business force-deleting the branch.
        assertFalse(BranchOwnership.mayDeleteBranchOf(List.of(), Path.of("/wt/discovered")));
        assertFalse(BranchOwnership.mayDeleteBranchOf(
                List.of(session(Path.of("/wt/other"), true)), Path.of("/wt/discovered")));
    }
}
```

If `RepositoryId.newId()` does not exist, use `RepositoryId.of(java.util.UUID.randomUUID().toString())` — check the type first.

Add to `ApplicationStateCodecTest`:

```java
    @Test
    void sessionWithoutBranchCreatedHereDecodesToTrue() {
        // Every session persisted before this field existed did create its
        // own branch; defaulting to false would silently stop deleting
        // branches the app is responsible for.
        ApplicationState state = ApplicationStateCodec.fromJson(document(SESSION_WITHOUT_PROVENANCE));

        assertTrue(state.sessions().get(0).branchCreatedHere());
    }
```

Build `SESSION_WITHOUT_PROVENANCE` following the existing `document(...)` helper's shape in that file — a `sessions` array with one entry carrying `id`, `repositoryId`, `displayName`, `workingDirectory`, `status`, `createdAt`, `lastOpenedAt`, and **no** `branchCreatedHere` member. Match the existing test's literal style rather than inventing a new one.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "app.drydock.domain.BranchOwnershipTest" --tests "app.drydock.state.ApplicationStateCodecTest"`
Expected: FAIL to compile — `constructor ManagedClaudeSession cannot be applied to given types`.

- [ ] **Step 3: Write minimal implementation**

**3a.** In `ManagedClaudeSession`, append the component after `prNumber`:

```java
        PrState prState,
        Optional<Integer> prNumber,
        boolean branchCreatedHere
) {
```

Document it in the record's javadoc:

```java
 * <p>{@link #branchCreatedHere()} records whether this application created
 * the session's branch (as opposed to checking out a branch that already
 * existed). The delete paths consult it before {@code git branch -D}: a
 * branch drydock did not create is never force-deleted.</p>
```

Update **all 9 withers** (`:88-133`) to pass `branchCreatedHere` as the final argument. Each is a full canonical-constructor call, so each needs the extra argument — the compiler will list every one.

**3b.** In `SessionManager`:

```java
    /** As {@link #prepareSession}, for a session living inside an already-created worktree checkout. */
    public ManagedClaudeSession prepareWorktreeSession(Repository repository, String displayName, Path worktreeRoot,
                                                        boolean branchCreatedHere) {
        return newSessionMetadata(repository, displayName, Optional.of(worktreeRoot), branchCreatedHere);
    }
```

`newSessionMetadata` gains the same parameter; the 2-arg and 3-arg overloads delegate with `true` (a plain session has no worktree branch to protect, and every pre-existing worktree call site created its branch):

```java
    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName) {
        return newSessionMetadata(repository, displayName, Optional.empty(), true);
    }

    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName,
                                                    Optional<Path> worktreeRoot) {
        return newSessionMetadata(repository, displayName, worktreeRoot, true);
    }

    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName,
                                                    Optional<Path> worktreeRoot, boolean branchCreatedHere) {
        Instant now = Instant.now();
        return new ManagedClaudeSession(
                ManagedSessionId.newId(),
                repository.id(),
                displayName,
                Optional.empty(),
                Optional.empty(),
                worktreeRoot.orElse(repository.root()),
                worktreeRoot,
                SessionStatus.INACTIVE,
                now,
                now,
                Optional.empty(),
                PrState.NONE,
                Optional.empty(),
                branchCreatedHere);
    }
```

Expose the predicate over the live session list:

```java
    /**
     * Whether the branch of the worktree at {@code worktreeRoot} may be
     * force-deleted along with it -- true only when a session records that
     * this application created that branch. See {@link BranchOwnership}.
     */
    public boolean mayDeleteBranchOf(Path worktreeRoot) {
        return BranchOwnership.mayDeleteBranchOf(sessions(), worktreeRoot);
    }
```

**3c.** Create `BranchOwnership.java`:

```java
package app.drydock.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Whether drydock may force-delete the branch of a worktree it is removing.
 *
 * <p>{@code git worktree remove} is recoverable; the {@code git branch -D}
 * that follows it is not, for a branch with unpushed commits. Deleting is
 * therefore allowed only where drydock knows it created the branch itself:
 * a branch checked out from one that already existed -- and any worktree
 * merely discovered on disk -- keeps its branch when the worktree goes.</p>
 */
public final class BranchOwnership {

    private BranchOwnership() {
    }

    public static boolean mayDeleteBranchOf(List<ManagedClaudeSession> sessions, Path worktreeRoot) {
        Path normalized = worktreeRoot.toAbsolutePath().normalize();
        return sessions.stream()
                .filter(session -> session.worktreeRoot()
                        .map(root -> root.toAbsolutePath().normalize().equals(normalized))
                        .orElse(false))
                .anyMatch(ManagedClaudeSession::branchCreatedHere);
    }
}
```

**3d.** In `ApplicationStateCodec`, encode in `sessionToJson`:

```java
        obj.put("branchCreatedHere", JsonValue.JsonBoolean.of(session.branchCreatedHere()));
```

Use whatever boolean `JsonValue` variant this codebase's hand-rolled JSON provides — check `app/src/main/java/app/drydock/state/json/JsonValue.java` and follow the existing style exactly (it may be `new JsonBoolean(true)` or a shared constant).

Decode in `sessionFromJson`, beside the other lenient fields:

```java
            // Lenient, like prState/remote: every session persisted before
            // this member existed did create its own branch, so an absent or
            // malformed value decodes to true. No schema bump.
            boolean branchCreatedHere = !(obj.get("branchCreatedHere") instanceof JsonValue.JsonBoolean b)
                    || b.value();
```

and pass it as the final constructor argument. Extend the class javadoc's version-2 note (`:78-88`) with one sentence recording the same, matching how `prState` and `remote` are documented there.

- [ ] **Step 4: Fix remaining construction sites, then run tests**

`grep -rn "new ManagedClaudeSession(" app/src` lists 18 sites across 7 files; the 4 test files each need `true` (or a value the test names explicitly) as the final argument.

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/domain/ app/src/main/java/app/drydock/state/ApplicationStateCodec.java app/src/main/java/app/drydock/app/SessionManager.java app/src/test/java/app/drydock/
git commit -m "Record whether drydock created a session's branch"
```

---

### Task 8: Provenance-aware delete at both call sites

Two places run `git branch -D`, and the spec originally caught only one.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/WorktreeLifecycleController.java:335`
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java:796`, `:825`

**Interfaces:**
- Consumes: `SessionManager.mayDeleteBranchOf(Path)` (Task 7).

- [ ] **Step 1: Change `WorktreeLifecycleController.handoffDelete`**

Replace line 335:

```java
        // A branch drydock did not create (an existing branch checked out
        // into this worktree) outlives the worktree: only the worktree goes.
        Optional<String> branchToDelete = sessionManager.mayDeleteBranchOf(worktreeRoot)
                ? Optional.of(branch)
                : Optional.empty();
        worktreeService.remove(repository.root(), worktreeRoot, branchToDelete)
```

If `WorktreeLifecycleController` has no `sessionManager` field, it already receives one (it calls `sessionManager.deleteSession` at `:348`) — use that field.

- [ ] **Step 2: Change both `RepositorySidebar` call sites**

At `:796` (`onDeleteUnopenedWorktree`) and `:825` (the forced retry), replace `worktree.branch()` with a provenance-filtered value. Add a helper beside them:

```java
    /**
     * The branch to delete along with {@code worktree}, if any. A worktree
     * discovered on disk, or one opened on a branch that already existed,
     * keeps its branch: {@code git branch -D} is unrecoverable for unpushed
     * commits, and drydock only destroys what it created.
     */
    private Optional<String> deletableBranchOf(WorktreeService.Worktree worktree) {
        return sessionManager.mayDeleteBranchOf(worktree.path()) ? worktree.branch() : Optional.empty();
    }
```

and use `deletableBranchOf(worktree)` at both sites.

- [ ] **Step 3: Run the suite**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/drydock/ui/WorktreeLifecycleController.java app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "Only force-delete branches drydock created"
```

---

### Task 9: The modal — derived mode, one state writer, two message slots

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/NewWorktreeModal.java` (substantial rewrite)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (style the new hint label + refresh button)

**Interfaces:**
- Consumes: `BranchCatalog` (Task 4), `BranchRefConverter` (Task 6), `WorktreeService` (new constructor dependency).
- Produces: `CreateHandler.create(Optional<BranchRef> existing, String branch, String base, Path directory, Optional<String> task)` — `existing` empty means create mode. Task 10 implements it.

- [ ] **Step 1: Widen the constructor and the handler**

`NewWorktreeModal` needs a `WorktreeService` to build the catalog. Update the signature and the `CreateHandler`:

```java
    /**
     * Invoked on Create. {@code existing} is present when the branch field
     * names a branch that already exists -- check it out rather than create
     * it, and ignore {@code base}.
     */
    interface CreateHandler {
        void create(Optional<BranchRef> existing, String branch, String base, Path directory,
                    Optional<String> task);
    }

    NewWorktreeModal(Repository repository, GitStatusService gitStatusService, WorktreeService worktreeService,
                     Runnable onClose, CreateHandler onCreate) {
```

- [ ] **Step 2: Replace the branch field with the combo, and add the refresh button**

Replace the `private final TextField branchField = new TextField("feat/");` declaration and add state:

```java
    private final ComboBox<BranchRef> branchField = new ComboBox<>();
    private final Button refreshButton = new Button("⟳");
    private final Label branchLabel = new Label("New branch");
    private final Label hintLine = new Label();
    private final VBox baseGroup;

    /** Null until the catalog loads; every mode decision waits for it. */
    private BranchCatalog catalog;
    private boolean catalogFailed;
```

In the constructor, build the field:

```java
        branchField.setEditable(true);
        branchField.setMaxWidth(Double.MAX_VALUE);
        branchField.getStyleClass().add("worktree-branch-combo");
        branchField.setConverter(new BranchRefConverter());
        branchField.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(BranchRef branch, boolean empty) {
                super.updateItem(branch, empty);
                setText(empty || branch == null ? null : BranchRefConverter.describe(branch));
                setDisable(branch != null && !branch.available());
            }
        });
        branchField.getEditor().setText("feat/");
        branchField.getEditor().setPromptText("Loading branches…");
        branchField.getEditor().textProperty().addListener((obs, oldText, newText) -> {
            if (!directoryManuallyEdited) {
                deriveDirectory.run();
            }
            refreshState();
        });

        refreshButton.getStyleClass().add("worktree-refresh-button");
        refreshButton.setTooltip(new Tooltip("Fetch all remotes and refresh the branch list"));
        refreshButton.setOnAction(e -> onRefresh(repository, gitStatusService, worktreeService));

        HBox branchRow = new HBox(6, branchField, refreshButton);
        HBox.setHgrow(branchField, Priority.ALWAYS);
```

`deriveDirectory` must be declared before this listener; keep it as the existing `Runnable` local but move its declaration above. It now derives from the **local** name, so the directory is identical whether the user picked the local or the remote spelling:

```java
        Runnable deriveDirectory = () -> {
            derivingDirectory = true;
            directoryField.setText(
                    WorktreeNaming.defaultDirectory(home, worktreesDirectory.get(), repository.displayName(),
                            localBranchName()).toString());
            derivingDirectory = false;
        };
```

with:

```java
    /** The local branch name the current text would check out as. */
    private String localBranchName() {
        String text = branchText();
        if (catalog == null) {
            return text;
        }
        return catalog.lookup(text).map(catalog::localName).orElse(text);
    }

    /** The branch field's current text, whether typed or picked from the dropdown. */
    private String branchText() {
        String editorText = branchField.getEditor().getText();
        return (editorText == null ? "" : editorText).strip();
    }
```

Imports to add: `app.drydock.git.BranchCatalog`, `app.drydock.git.BranchRef`, `app.drydock.git.WorktreeService`, `javafx.scene.control.ListCell`, `javafx.scene.control.Tooltip`.

- [ ] **Step 3: Load the catalog with a gate**

Replace the two `getStatus`/`listLocalBranches` blocks. The status call still seeds "Fork from"; the catalog load gates Create.

```java
        gitStatusService.getStatus(repository.root()).whenComplete((status, failure) ->
                Platform.runLater(() -> {
                    if (failure == null && status.branch() instanceof GitBranchState.OnBranch onBranch) {
                        baseField.setValue(onBranch.name());
                    }
                }));
        loadCatalog(repository, gitStatusService, worktreeService);
```

```java
    /**
     * Loads the branch catalog. Until it arrives, Create stays disabled and
     * the field prompts "Loading branches…": the catalog decides create vs.
     * checkout, so acting on a half-known list would run {@code -b} against
     * a branch that already exists. A failure is surfaced, never silently
     * degraded to an empty list -- that would make every branch read as new.
     */
    private void loadCatalog(Repository repository, GitStatusService gitStatusService,
                             WorktreeService worktreeService) {
        BranchCatalog.load(gitStatusService, worktreeService, repository.root())
                .whenComplete((loaded, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        catalogFailed = true;
                        showError("Could not list branches: " + UiErrors.unwrap(failure).getMessage());
                        refreshState();
                        return;
                    }
                    catalog = loaded;
                    catalogFailed = false;
                    branchField.getItems().setAll(loaded.branches());
                    branchField.getEditor().setPromptText("");
                    refreshState();
                }));
    }
```

Add `import app.drydock.domain.Repository;` if not present, and `UiErrors` is already used by `MainWorkspace` — import `app.drydock.ui.UiErrors` only if `NewWorktreeModal` is in another package (it is not; no import needed).

- [ ] **Step 4: Refresh, reporting a selection that the prune removed**

```java
    private void onRefresh(Repository repository, GitStatusService gitStatusService,
                           WorktreeService worktreeService) {
        boolean matchedBefore = catalog != null && catalog.lookup(branchText()).isPresent();
        refreshButton.setDisable(true);
        refreshButton.setText("…");
        hideError();
        gitStatusService.fetchAll(repository.root()).whenComplete((v, fetchFailure) ->
                Platform.runLater(() -> {
                    if (fetchFailure != null) {
                        refreshButton.setDisable(false);
                        refreshButton.setText("⟳");
                        showError("Fetch failed: " + UiErrors.unwrap(fetchFailure).getMessage());
                        refreshState();
                        return;
                    }
                    BranchCatalog.load(gitStatusService, worktreeService, repository.root())
                            .whenComplete((loaded, loadFailure) -> Platform.runLater(() -> {
                                refreshButton.setDisable(false);
                                refreshButton.setText("⟳");
                                if (loadFailure != null) {
                                    catalogFailed = true;
                                    showError("Could not list branches: "
                                            + UiErrors.unwrap(loadFailure).getMessage());
                                    refreshState();
                                    return;
                                }
                                catalog = loaded;
                                catalogFailed = false;
                                branchField.getItems().setAll(loaded.branches());
                                // --prune can delete the very remote-tracking
                                // ref that was selected; say so rather than
                                // silently flipping to "New branch".
                                if (matchedBefore && catalog.lookup(branchText()).isEmpty()) {
                                    showError("That branch no longer exists on the remote — "
                                            + "Create would now make a new one.");
                                }
                                refreshState();
                            }));
                }));
    }
```

Every path re-enables the button and restores its label — success, fetch failure, and load failure alike.

- [ ] **Step 5: Make `refreshState` the only writer of the disabled state**

Delete `updateFooter()` and replace with:

```java
    /**
     * Recomputes everything derived from the branch text: mode, the command
     * preview, the blocking hint, and Create's disabled state. This is the
     * ONLY place {@code createButton.setDisable} is called -- a second writer
     * (as {@link #showError} used to be) can re-enable a button the derived
     * state has just declared impossible.
     */
    private void refreshState() {
        String text = branchText();
        String directory = directoryField.getText() == null ? "" : directoryField.getText().strip();
        Optional<BranchRef> existing = catalog == null ? Optional.empty() : catalog.lookup(text);

        branchLabel.setText(existing.isPresent() ? "Existing branch" : "New branch");
        boolean creating = existing.isEmpty();
        baseGroup.setVisible(creating);
        baseGroup.setManaged(creating);

        String base = baseText();
        if (existing.isPresent()) {
            BranchRef branch = existing.get();
            commandPreview.setText(branch.remote()
                    ? "$ git worktree add " + directory + " -b " + catalog.localName(branch)
                            + " --track " + branch.name()
                    : "$ git worktree add " + directory + " " + branch.name());
        } else {
            commandPreview.setText("$ git worktree add " + directory + " -b " + text
                    + (base.isEmpty() ? "" : " " + base));
        }

        String hint = existing.filter(branch -> !branch.available())
                .map(branch -> branch.stale()
                        ? "Blocked by a stale worktree at " + branch.checkedOutAt().orElseThrow()
                                + " — run `git worktree prune` to release it."
                        : "Already checked out in " + branch.checkedOutAt().orElseThrow())
                .orElse("");
        hintLine.setText(hint);
        hintLine.setVisible(!hint.isEmpty());
        hintLine.setManaged(!hint.isEmpty());

        boolean blocked = catalog == null || catalogFailed || !hint.isEmpty() || directory.isEmpty();
        boolean branchValid = existing.isPresent()
                || (!text.isEmpty() && !text.endsWith("/") && !text.contains(" ") && !base.isEmpty());
        createButton.setDisable(blocked || !branchValid || creatingInFlight);
    }
```

Add a `private boolean creatingInFlight;` field. Rewrite the three state methods so none of them touches `setDisable` directly:

```java
    /** Shows a creation failure inline; the modal stays open so the input can be corrected. */
    void showError(String message) {
        errorLine.setText(message);
        errorLine.setVisible(true);
        errorLine.setManaged(true);
        creatingInFlight = false;
        createButton.setText("Create worktree");
        refreshState();
    }

    private void hideError() {
        errorLine.setVisible(false);
        errorLine.setManaged(false);
    }

    /** Marks the create action as in flight. */
    void showCreating() {
        hideError();
        creatingInFlight = true;
        createButton.setText("Creating…");
        refreshState();
    }
```

`hintLine` is a *separate* label from `errorLine`: the hint is a derived property of the selection, the error is a transient result of a submitted action. Sharing one label leaves a stale creation error looking like a blocking hint.

- [ ] **Step 6: Wire the layout and the Create action**

Assign `baseGroup` (it must be a field so `refreshState` can hide it) and use the new rows:

```java
        baseGroup = fieldGroup("Fork from", baseField);

        createButton.setOnAction(e -> {
            String task = taskField.getText() == null ? "" : taskField.getText().strip();
            Optional<BranchRef> existing = catalog == null ? Optional.empty() : catalog.lookup(branchText());
            onCreate.create(existing, localBranchName(), baseText(),
                    Path.of(directoryField.getText().strip()).toAbsolutePath().normalize(),
                    task.isEmpty() ? Optional.empty() : Optional.of(task));
        });

        getChildren().addAll(header,
                labelledRow(branchLabel, branchRow),
                baseGroup,
                fieldGroup("Worktree directory", directoryField),
                fieldGroup("Start Claude with a task", taskField),
                commandPreview, hintLine, errorLine, buttons);

        refreshState();
        Platform.runLater(() -> {
            branchField.getEditor().requestFocus();
            branchField.getEditor().positionCaret(branchField.getEditor().getText().length());
        });
```

with a variant of the existing helper that takes a pre-built label:

```java
    private static VBox labelledRow(Label label, Region field) {
        label.getStyleClass().add("worktree-field-label");
        return new VBox(4, label, field);
    }
```

`hintLine.getStyleClass().add("worktree-hint");` beside the existing `errorLine` setup, and initialise it hidden the same way.

- [ ] **Step 7: Style the new controls**

In `app/src/main/resources/app/drydock/ui/app.css`, beside the existing `.worktree-base-combo` / `.worktree-error` rules, add `.worktree-branch-combo` (match `.worktree-base-combo`), `.worktree-refresh-button` (match `.icon-button`'s size/colour), and `.worktree-hint` (like `.worktree-error` but in the muted/secondary colour, not the error colour). Reuse the existing colour variables in that file; do not introduce new literals.

- [ ] **Step 8: Compile**

Run: `./gradlew :app:compileJava`
Expected: FAILS at `MainWorkspace.java:557` — the `CreateHandler` lambda now takes five parameters. Task 10 fixes it; do not commit yet.

---

### Task 10: Wire the modal into `MainWorkspace`

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:549-571` (`promptNewWorktree`), `:580-586` (`openNewWorktreeSession`)

**Interfaces:**
- Consumes: the 5-arg `CreateHandler` (Task 9), `addWorktreeForBranch` (Task 5), `prepareWorktreeSession(..., boolean)` (Task 7).

- [ ] **Step 1: Rewrite `promptNewWorktree`**

`MainWorkspace` must have a `WorktreeService` to pass down; it already holds one (it constructs `RepositorySidebar` with one) — use that field.

```java
    /**
     * Shows the create-worktree modal for {@code repository} (worktree
     * handoff "Creating"): on Create, either creates a new branch or checks
     * out an existing one (local, or remote as a new tracking branch), then
     * opens a session in the fresh worktree; failures show inline and keep
     * the modal open.
     */
    public void promptNewWorktree(Repository repository, ModalLayer modalLayer) {
        NewWorktreeModal[] holder = new NewWorktreeModal[1];
        holder[0] = new NewWorktreeModal(repository, gitStatusService, worktreeService, modalLayer::close,
                (existing, branch, base, directory, task) -> {
                    holder[0].showCreating();
                    CompletableFuture<Path> creation = existing
                            .map(ref -> gitStatusService.addWorktreeForBranch(
                                    repository.root(), directory, ref, branch))
                            .orElseGet(() -> gitStatusService.createWorktree(
                                    repository.root(), directory, branch, Optional.of(base)));
                    creation.whenComplete((created, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            holder[0].showError(String.valueOf(UiErrors.unwrap(ex).getMessage()));
                            return;
                        }
                        modalLayer.close();
                        openNewWorktreeSession(repository, branch, created, task, existing.isEmpty());
                    }));
                });
        modalLayer.show(holder[0]);
    }
```

`branch` is already the **local** branch name in both modes (the modal passes `localBranchName()`), so the session is tagged consistently.

- [ ] **Step 2: Thread provenance into the session**

```java
    public void openNewWorktreeSession(Repository repository, String branch, Path worktreeRoot,
                                       Optional<String> task, boolean branchCreatedHere) {
        // Keyed under the real session id for the same launch-race reason
        // as openNewSession.
        ManagedClaudeSession prepared =
                sessionManager.prepareWorktreeSession(repository, branch, worktreeRoot, branchCreatedHere);
```

The rest of the method is unchanged. Fix any other caller the compiler flags — `grep -rn "openNewWorktreeSession(" app/src` — passing `true` where the branch was freshly created.

- [ ] **Step 3: Compile and run the full suite**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 4: Commit Tasks 9 and 10 together**

They are one compilable unit.

```bash
git add app/src/main/java/app/drydock/ui/NewWorktreeModal.java app/src/main/java/app/drydock/ui/MainWorkspace.java app/src/main/resources/app/drydock/ui/app.css
git commit -m "Open a worktree from an existing local or remote branch"
```

---

### Task 11: Visual verification

The pure units cover the listing rules and the converter; nothing so far proves the modal *looks* right or that the mode actually flips on screen. Launch the app and screenshot it.

**REQUIRED SUB-SKILL:** use the `run` skill to launch and drive the app.

- [ ] **Step 1: Prepare a scratch repository with branches worth picking**

```bash
SCRATCH=/private/tmp/claude-501/wt-demo
rm -rf "$SCRATCH" && mkdir -p "$SCRATCH"
git init -b main "$SCRATCH/upstream"
cd "$SCRATCH/upstream" && echo hi > README.md && git add README.md \
  && git -c user.name=T -c user.email=t@e.com commit -m init \
  && git branch feature/from-remote
git clone "$SCRATCH/upstream" "$SCRATCH/repo"
cd "$SCRATCH/repo" && git branch local-existing
```

- [ ] **Step 2: Launch the app against an isolated state file**

```bash
./gradlew run \
  -Papp.drydock.diag.stateFile=/private/tmp/claude-501/wt-demo/state.json \
  -Papp.drydock.diag.repo=/private/tmp/claude-501/wt-demo/repo \
  -Papp.drydock.diag.autoCreateSession=true
```

- [ ] **Step 3: Open the modal and screenshot each state**

Take a **series** of screenshots — shoot before and after each interaction, not
just the end state. Most of what this feature does is a *transition* (the mode
flip, the "Fork from" row appearing and disappearing, the refresh button's
in-flight label), and a transition is only visible as a difference between two
frames. Compare consecutive shots and validate the change, not the snapshot:
a modal that looks right at rest can still have flipped mode at the wrong
moment, or left a gap where the hidden row used to be.

Confirm, in order:

1. **Typing a new name** (`feat/whatever`) — label reads "New branch", "Fork from" row **visible**, preview ends `-b feat/whatever <base>`.
2. **Dropdown open** — local branches first, then `origin/…`; `main` shows `— in use (…)` and its row is greyed.
3. **Picking `local-existing` from the dropdown** — this is the converter regression: the editor must read exactly `local-existing`, **not** a decorated string. Label reads "Existing branch", "Fork from" row **hidden with no gap left behind**, preview is `git worktree add <dir> local-existing`.
4. **Picking `origin/feature/from-remote`** — preview reads `-b feature/from-remote --track origin/feature/from-remote`, and the directory field ends in `-from-remote` (derived from the *local* name).
5. **Picking `main`** — Create is disabled and the hint (not the error line) says it is already checked out.
6. **⟳** — button shows its in-flight label and returns to `⟳` afterwards.

- [ ] **Step 4: Create one worktree of each kind and confirm on disk**

Create from `local-existing`, then verify no new ref was invented and the session opened in the right directory:

```bash
git -C /private/tmp/claude-501/wt-demo/repo worktree list
git -C /private/tmp/claude-501/wt-demo/repo branch -a
git -C /private/tmp/claude-501/wt-demo/repo config branch.feature/from-remote.remote  # expect: origin
```

- [ ] **Step 5: Confirm the delete guard**

Delete the `local-existing` worktree from the sidebar 🗑, then:

```bash
git -C /private/tmp/claude-501/wt-demo/repo branch --list local-existing
```

Expected: **the branch still exists** — the worktree is gone, the pre-existing branch survives. Repeat for a worktree created on a *new* branch; that branch should be gone.

- [ ] **Step 6: Clean up and commit any fixes**

```bash
rm -rf /private/tmp/claude-501/wt-demo
```

Commit anything the visual pass turned up; if it turned up nothing, there is nothing to commit.

---

## Self-Review

**Spec coverage:** listing/symref → Task 3; occupancy + prunable/locked → Tasks 2, 4; local-name/longest-remote-prefix → Task 4; shadowing → Task 4; `(name, remote)` collision → Task 4; `addWorktreeForBranch` → Task 5; fetch timeout + prompt guard → Tasks 1, 5; converter identity → Task 6; derived mode + `refreshState` sole writer + two message slots + load gate + refresh reporting → Task 9; directory derivation from local name → Task 9; provenance model + lenient codec → Task 7; both delete sites → Task 8 (the sidebar site was **not** in the spec; added per the decision to use one predicate across both); wiring → Task 10.

**Type consistency:** `BranchRef` is `(name, remote, checkedOutAt, stale)` throughout; `BranchCatalog.localName(BranchRef)` is the single name-splitting entry point used by Tasks 5, 9, 10; `mayDeleteBranchOf` has the same name on `BranchOwnership` (static, list-taking) and `SessionManager` (instance, live sessions). `Worktree` gains exactly `prunable, locked` in that order, appended last, matching every construction site in Tasks 2 and 4.

**Known deviation from the spec:** the spec proposed a `NewWorktreeModalTest` that selects a dropdown row. There is no TestFX and no toolkit in unit tests, so that test cannot exist. The coverage moved to `BranchRefConverterTest` (Task 6, catches the exact regression) plus the on-screen check in Task 11 step 3.3.
