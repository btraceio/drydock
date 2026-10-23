package app.drydock.intellij.review;

import app.drydock.git.DiffService;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC9, AC11 and AC12 for {@link ReviewVerdictCommands}: a real git fixture,
 * the real diff and git services, and a real annotation store -- the commands
 * write through exactly the classes production wires, so the digest contract
 * and the commit dating are tested against git itself, not against fakes.
 *
 * <p>The fixture is a 100-line file whose content is periodic ("a"…"e"
 * repeating) with changes placed congruently: two changes at lines 30 and 70
 * sit on the same phase of the cycle, are more than 24 lines apart, so a
 * {@code -U12} diff keeps them as two hunks -- and the periodic context
 * around congruent changes makes the two hunks byte-identical line
 * sequences, which is what makes them one digest and exercises the "per
 * DISTINCT digest" rule for real. (Uniform "x" content does NOT work: every
 * line matching every other lets git pick an asymmetric edit script -- one
 * hunk a pure insertion, the other a 2-delete/1-insert -- so the two hunks'
 * line sequences differ and the identical-digest premise fails.)</p>
 */
class ReviewVerdictCommandsTest {

    private static final long SECONDS = 30;

    @TempDir
    Path base;

    private DiffService diffService;
    private GitStatusService git;
    private AnnotationStore store;
    private ReviewVerdictCommands commands;
    private ExecutorService executor;
    /** Counts the command steps the injected executor ran. */
    private final AtomicInteger executorTasks = new AtomicInteger();
    private Path repo;
    private Path wt;
    private ReviewScope scope;

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(base.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.createDirectories(repo.resolve("src"));
        writeUniform(repo.resolve("src/Distinct.java"), Map.of());
        writeUniform(repo.resolve("src/Identical.java"), Map.of());
        writeUniform(repo.resolve("src/Changes.java"), Map.of());
        runGit(repo, "add", "src");
        runGit(repo, "commit", "-m", "uniform base");
        wt = base.resolve("wt");
        runGit(repo, "worktree", "add", "-b", "feat", wt.toString());
        writeUniform(wt.resolve("src/Distinct.java"), Map.of(30, "y", 70, "z"));
        writeUniform(wt.resolve("src/Identical.java"), Map.of(30, "y", 70, "y"));
        writeUniform(wt.resolve("src/Changes.java"), Map.of(30, "q"));
        runGit(wt, "add", "src");
        runGit(wt, "commit", "-m", "review changes");

        diffService = new DiffService();
        git = new GitStatusService();
        store = new AnnotationStore(base.resolve("annotations.json"));
        scope = new ReviewScope("scope-1", ReviewScope.Kind.WORKTREE, repo, Optional.of(wt),
                "main", "feat", Optional.empty(), Optional.empty(), Optional.empty());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        commands = new ReviewVerdictCommands(diffService, git, store, task -> {
            executorTasks.incrementAndGet();
            executor.execute(task);
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        diffService.close();
        git.close();
        store.close();
    }

    // ---- AC9 ---------------------------------------------------------------

    @Test
    void markReviewedWritesOneApprovedVerdictPerDistinctDigestDatedByCommits() throws Exception {
        UnifiedDiff.FileDiff distinct = fileOf(scope, "src/Distinct.java");
        UnifiedDiff.FileDiff identical = fileOf(scope, "src/Identical.java");
        assertEquals(2, distinct.hunks().size());
        assertEquals(2, identical.hunks().size());
        Set<String> distinctDigests = digestsOf(scope, "src/Distinct.java");
        Set<String> identicalDigests = digestsOf(scope, "src/Identical.java");
        // Fixture premise, checked before it is used: the two changes of
        // Identical.java sit on the same phase of the periodic fixture, so real
        // git renders them as two hunks with byte-identical row sequences. If
        // this fails, the fixture's premise broke (a git heuristic change,
        // say) -- it is a fixture problem, not a digest-contract failure.
        assertEquals(hunkSequence(identical.hunks().get(0)), hunkSequence(identical.hunks().get(1)),
                "fixture premise: real git must render the two congruent changes"
                        + " of Identical.java as byte-identical hunks");
        // Two byte-identical hunks of one file are one digest: one verdict.
        assertEquals(2, distinctDigests.size());
        assertEquals(1, identicalDigests.size());

        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, distinct).get(SECONDS, TimeUnit.SECONDS));
        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, identical).get(SECONDS, TimeUnit.SECONDS));

        String expectedBase = revParse(wt, "main");
        String expectedHead = revParse(wt, "feat");
        assertTrue(expectedBase.matches("[0-9a-f]{40}"), expectedBase);
        assertTrue(expectedHead.matches("[0-9a-f]{40}"), expectedHead);
        assertEquals(Set.of(expectedBase, expectedHead).size(), 2, "base and head differ");

        Set<String> all = new LinkedHashSet<>();
        all.addAll(distinctDigests);
        all.addAll(identicalDigests);
        for (String digest : all) {
            ReviewVerdict verdict = store.verdict(scope.id(), digest).orElseThrow();
            assertEquals(ReviewVerdict.Decision.APPROVED, verdict.decision());
            assertEquals(scope.id(), verdict.scopeId());
            assertEquals(expectedBase, verdict.baseCommit());
            assertEquals(expectedHead, verdict.headCommit());
        }
        assertEquals(3, store.verdictsFor(scope.id()).size(),
                "2 distinct hunks + 1 digest for the identical pair");

        // After flushPendingSaves() the verdicts are in annotations.json:
        // reopened from the file, not asserted from memory.
        store.flushPendingSaves();
        try (AnnotationStore reopened = new AnnotationStore(base.resolve("annotations.json"))) {
            assertEquals(3, reopened.verdictsFor(scope.id()).size());
            for (String digest : all) {
                ReviewVerdict verdict = reopened.verdict(scope.id(), digest).orElseThrow();
                assertEquals(ReviewVerdict.Decision.APPROVED, verdict.decision());
                assertEquals(expectedBase, verdict.baseCommit());
                assertEquals(expectedHead, verdict.headCommit());
            }
        }
    }

    /** The blocking steps after the diff run on the injected executor, never ForkJoinPool.commonPool(). */
    @Test
    void bothCommandsRunTheirBlockingStepsOnTheInjectedExecutor() throws Exception {
        UnifiedDiff.FileDiff distinct = fileOf(scope, "src/Distinct.java");
        int before = executorTasks.get();
        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, distinct).get(30, TimeUnit.SECONDS));
        assertEquals(before + 1, executorTasks.get(), "mark's steps ran on the injected executor");
        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.unmark(scope, distinct).get(30, TimeUnit.SECONDS));
        assertEquals(before + 2, executorTasks.get(), "unmark's steps ran on the injected executor");
    }

    @Test
    void theWrittenDigestsComeFromAFreshDiffNotThePassedFile() throws Exception {
        UnifiedDiff.FileDiff snapshot = fileOf(scope, "src/Distinct.java");
        Set<String> snapshotDigests = digestsOf(scope, "src/Distinct.java");

        // The file changes after the snapshot the human clicked on; the
        // uncommitted edit reaches the working tree, which the scope's
        // BRANCH_WORKING_TREE diff includes.
        writeUniform(wt.resolve("src/Distinct.java"), Map.of(30, "w", 70, "z"));

        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, snapshot).get(SECONDS, TimeUnit.SECONDS));

        Set<String> freshDigests = digestsOf(scope, "src/Distinct.java");
        assertTrue(freshDigests.stream().anyMatch(digest -> !snapshotDigests.contains(digest)),
                "the edit must have changed a hunk: " + snapshotDigests + " vs " + freshDigests);
        assertEquals(freshDigests, storeDigests(),
                "the written digests are the fresh diff's, not the passed file's");
    }

    // ---- AC11 --------------------------------------------------------------

    @Test
    void markReviewedIsRefusedWhileABlockingFindingIsOpenAndWritesNothing() throws Exception {
        store.upsert(blockingFinding("f_block_1", "src/Distinct.java"));

        ReviewVerdictCommands.Result.Refused refused = assertInstanceOf(
                ReviewVerdictCommands.Result.Refused.class,
                commands.markReviewed(scope, fileOf(scope, "src/Distinct.java"))
                        .get(SECONDS, TimeUnit.SECONDS));
        assertTrue(refused.reason().contains("blocking"), refused.reason());
        assertTrue(refused.reason().contains("f_block_1"), refused.reason());
        assertTrue(store.verdictsFor(scope.id()).isEmpty(), "a refusal writes nothing");
    }

    @Test
    void markReviewedIsRefusedWhenTheBaseCannotBeResolvedAndWritesNothing() throws Exception {
        // The main checkout carries an uncommitted change, so its working-tree
        // diff shows the file; its scope's base names a ref git cannot resolve.
        writeUniform(repo.resolve("src/Distinct.java"), Map.of(30, "y"));
        ReviewScope brokenBase = new ReviewScope("scope-base", ReviewScope.Kind.WORKING_TREE, repo,
                Optional.of(repo), "no-such-branch", "main", Optional.empty(), Optional.empty(),
                Optional.empty());

        ReviewVerdictCommands.Result.Refused refused = assertInstanceOf(
                ReviewVerdictCommands.Result.Refused.class,
                commands.markReviewed(brokenBase, fileOf(brokenBase, "src/Distinct.java"))
                        .get(SECONDS, TimeUnit.SECONDS));
        assertTrue(refused.reason().contains("base"), refused.reason());
        assertTrue(refused.reason().contains("no-such-branch"), refused.reason());
        assertTrue(store.verdictsFor("scope-base").isEmpty(), "a refusal writes nothing");
    }

    @Test
    void markReviewedIsRefusedWhenTheHeadCannotBeResolvedAndWritesNothing() throws Exception {
        // The worktree's diff needs only the base; the head is used for
        // dating, and here it names a ref git cannot resolve.
        ReviewScope brokenHead = new ReviewScope("scope-head", ReviewScope.Kind.WORKTREE, repo,
                Optional.of(wt), "main", "no-such-branch", Optional.empty(), Optional.empty(),
                Optional.empty());

        ReviewVerdictCommands.Result.Refused refused = assertInstanceOf(
                ReviewVerdictCommands.Result.Refused.class,
                commands.markReviewed(brokenHead, fileOf(brokenHead, "src/Distinct.java"))
                        .get(SECONDS, TimeUnit.SECONDS));
        assertTrue(refused.reason().contains("head"), refused.reason());
        assertTrue(refused.reason().contains("no-such-branch"), refused.reason());
        assertTrue(store.verdictsFor("scope-head").isEmpty(), "a refusal writes nothing");
    }

    @Test
    void markReviewedIsRefusedForAFileWithNoTextChanges() throws Exception {
        UnifiedDiff.FileDiff absent = new UnifiedDiff.FileDiff(
                "src/Absent.java", "M", 0, 0, false, false, List.of());

        ReviewVerdictCommands.Result.Refused refused = assertInstanceOf(
                ReviewVerdictCommands.Result.Refused.class,
                commands.markReviewed(scope, absent).get(SECONDS, TimeUnit.SECONDS));
        assertEquals("no text changes to review", refused.reason());
        assertTrue(store.verdictsFor(scope.id()).isEmpty(), "a refusal writes nothing");
    }

    // ---- AC12 --------------------------------------------------------------

    @Test
    void unmarkClearsOnlyTheFilesHumanApprovals() throws Exception {
        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, fileOf(scope, "src/Distinct.java"))
                        .get(SECONDS, TimeUnit.SECONDS));
        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, fileOf(scope, "src/Identical.java"))
                        .get(SECONDS, TimeUnit.SECONDS));
        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.markReviewed(scope, fileOf(scope, "src/Changes.java"))
                        .get(SECONDS, TimeUnit.SECONDS));
        List<String> distinct = List.copyOf(digestsOf(scope, "src/Distinct.java"));
        String identical = digestsOf(scope, "src/Identical.java").iterator().next();
        String changes = digestsOf(scope, "src/Changes.java").iterator().next();
        // Not the human's call: an agent's own approval, and a CHANGES verdict.
        store.putVerdict(new ReviewVerdict(scope.id(), distinct.get(1),
                ReviewVerdict.Decision.AUTO_APPROVED, Optional.empty(), Instant.now(), "b", "h"));
        store.putVerdict(new ReviewVerdict(scope.id(), changes,
                ReviewVerdict.Decision.CHANGES, Optional.empty(), Instant.now(), "b", "h"));

        assertEquals(new ReviewVerdictCommands.Result.Ok(),
                commands.unmark(scope, fileOf(scope, "src/Distinct.java")).get(SECONDS, TimeUnit.SECONDS));

        // The file's human approval is gone.
        assertTrue(store.verdict(scope.id(), distinct.get(0)).isEmpty());
        // The agent's own approval stays (Q14).
        assertEquals(ReviewVerdict.Decision.AUTO_APPROVED,
                store.verdict(scope.id(), distinct.get(1)).orElseThrow().decision());
        // Other files' verdicts and other decisions stay.
        assertEquals(ReviewVerdict.Decision.APPROVED,
                store.verdict(scope.id(), identical).orElseThrow().decision());
        assertEquals(ReviewVerdict.Decision.CHANGES,
                store.verdict(scope.id(), changes).orElseThrow().decision());
    }

    // ---- fixture helpers ----------------------------------------------------

    /**
     * A file of 100 lines cycling "a"…"e" (period 5), with the given 1-based
     * lines replaced. Periodic content is what makes the fixture work: every
     * change sits between distinct neighbours, so git cannot slide its edit
     * script sideways, while two changes on the same phase of the cycle
     * (30 and 70: both replace "e") are rendered with byte-identical context
     * windows. Uniform content would let git pick an asymmetric script.
     */
    private static void writeUniform(Path file, Map<Integer, String> changedLines) throws IOException {
        String[] cycle = {"a", "b", "c", "d", "e"};
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 100; line++) {
            content.append(changedLines.getOrDefault(line, cycle[(line - 1) % cycle.length])).append('\n');
        }
        Files.writeString(file, content.toString());
    }

    /** The hunk's row sequence -- every (kind, text) pair, headers excluded. */
    private static List<String> hunkSequence(UnifiedDiff.Hunk hunk) {
        List<String> rows = new ArrayList<>();
        for (UnifiedDiff.Line line : hunk.lines()) {
            rows.add(line.kind() + " " + line.text());
        }
        return rows;
    }

    /** The file's {@code FileDiff} in the scope's own review diff. */
    private UnifiedDiff.FileDiff fileOf(ReviewScope s, String path) throws Exception {
        return diff(s).files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(path + " is not in the diff"));
    }

    /** The file's distinct digests in the scope's own review diff. */
    private Set<String> digestsOf(ReviewScope s, String path) throws Exception {
        Set<String> digests = new LinkedHashSet<>();
        for (UnifiedDiff.Hunk hunk : fileOf(s, path).hunks()) {
            digests.add(HunkDigest.of(path, hunk));
        }
        return digests;
    }

    /** The digests of every verdict the store holds for the test scope. */
    private Set<String> storeDigests() {
        Set<String> digests = new LinkedHashSet<>();
        for (ReviewVerdict verdict : store.verdictsFor(scope.id())) {
            digests.add(verdict.hunkDigest());
        }
        return digests;
    }

    private UnifiedDiff diff(ReviewScope s) throws Exception {
        return diffService.diff(s.diffRoot(), s.diffScope(), s.base(), DiffService.REVIEW_CONTEXT_LINES)
                .get(SECONDS, TimeUnit.SECONDS);
    }

    private static ReviewAnnotation blockingFinding(String id, String file) {
        return new ReviewAnnotation("scope-1", id, file, "n1", "n1", Severity.BLOCKING, Confidence.HIGH,
                Optional.of("A blocking problem"), "Reviewer", Instant.now(), List.of(),
                Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty(),
                AnnotationStatus.OPEN, Optional.empty(), false);
    }

    private static String revParse(Path dir, String ref) throws Exception {
        ProcessResult result = ProcessRunner.run(
                List.of("git", "-C", dir.toString(), "rev-parse", ref), dir, Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git rev-parse " + ref + ": " + result.stderr());
        }
        return result.stdout().strip();
    }

    private static void runGit(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        ProcessResult result = ProcessRunner.run(command, dir, Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
        }
    }
}
