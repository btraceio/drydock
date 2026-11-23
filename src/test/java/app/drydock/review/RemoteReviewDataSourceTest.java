package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewContents;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.review.ReviewDataSnapshot.Content;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewDataSource.Side;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.workspace.CommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-40/AC-41: the remote adapter against real Git repositories, the real bundled helpers and the real
 * {@code RemoteReviewQueryBuilder}. A fake {@code CommandRunner} executes the ssh command string with
 * {@code sh -c} (the remote is this machine); everything the adapter reads goes through that seam.
 */
class RemoteReviewDataSourceTest {
    private static final Path RES = Path.of("src/main/resources/drydock/remote").toAbsolutePath();
    private final GitStatusService git = new GitStatusService(new GitExecutableLocator());
    private final DiffService diffs = new DiffService(new GitExecutableLocator());
    private final ReviewContents reader = new ReviewContents(new GitExecutableLocator(), git);
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final RemoteSnapshotCache cache = new RemoteSnapshotCache();
    private final ManagedSessionId session = ManagedSessionId.newId();
    private final AtomicInteger captures = new AtomicInteger();
    private volatile Consumer<Integer> afterCapture = n -> { };
    private Path repo;

    private final CommandRunner sh = (argv, options, cap) -> {
        String command = argv.get(argv.size() - 1);
        ProcessResult r = ProcessRunner.run(List.of("sh", "-c", command),
                new ProcessRunner.Options(null, options.timeout(), true, Map.of()), cap);
        if (command.contains("review_git.py")) {
            afterCapture.accept(captures.incrementAndGet());
        }
        return r;
    };

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        repo = Files.createDirectories(dir.resolve("repo"));
        git("init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "l\n".repeat(20) + "two\n" + "l\n".repeat(20));
        Files.writeString(repo.resolve("old.txt"), "keep\n".repeat(10));
        Files.writeString(repo.resolve("crlf.txt"), "x\r\ny\r\n");
        Files.writeString(repo.resolve("gone.txt"), "bye\n");
        Files.write(repo.resolve("bin.dat"), new byte[]{1, 2, 0, 3});
        git("add", ".");
        commit();
    }

    @AfterEach
    void close() {
        diffs.close();
        git.close();
    }

    @Test
    void workingTreeMatchesLocalAndLeavesIndexAndRefsUntouched() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "l\n".repeat(20) + "TWO\n" + "l\n".repeat(20));
        git("add", "a.txt");
        Files.writeString(repo.resolve("crlf.txt"), "x\r\nZ\r\n");
        Files.write(repo.resolve("bin.dat"), new byte[]{9, 0, 3});
        Files.writeString(repo.resolve("new.txt"), "fresh\n");
        String before = guard();
        ReviewDataSnapshot snap = paired(ReviewScope.Kind.WORKING_TREE, "main");
        assertEquals(before, guard(), "real index and refs are unchanged");
        assertInstanceOf(Content.Binary.class, snap.sources().get("bin.dat").right());
        assertInstanceOf(Content.Binary.class, snap.sources().get("bin.dat").left());
        assertTrue(new String(((Content.Text) snap.sources().get("crlf.txt").right()).bytes(),
                StandardCharsets.UTF_8).contains("\r\n"), "CRLF bytes are original");
    }

    @Test
    void worktreeScopeHandlesRenameAddDeleteAndStackedBase() throws Exception {
        git("checkout", "-q", "-b", "feat1");
        Files.writeString(repo.resolve("a.txt"), "l\n".repeat(20) + "ONE\n" + "l\n".repeat(20));
        git("add", "-A");
        commit();
        git("checkout", "-q", "-b", "feat2");
        git("mv", "old.txt", "renamed.txt");
        git("rm", "-q", "gone.txt");
        Files.writeString(repo.resolve("added.txt"), "added\n");
        git("add", "-A");
        commit();
        Files.writeString(repo.resolve("renamed.txt"), "keep\n".repeat(9) + "edited\n");
        paired(ReviewScope.Kind.WORKTREE, "main");
        ReviewDataSnapshot stacked = paired(ReviewScope.Kind.BRANCH, "feat1");
        assertEquals(List.of("added.txt", "gone.txt", "renamed.txt"),
                stacked.diff().files().stream().map(UnifiedDiff.FileDiff::path).sorted().toList());
    }

    @Test
    void detachedHeadIsPinnedAndMatchesLocal() throws Exception {
        git("checkout", "-q", "-b", "feat");
        Files.writeString(repo.resolve("b.txt"), "b\n");
        git("add", "-A");
        commit();
        git("checkout", "-q", "--detach");
        paired(ReviewScope.Kind.BRANCH, "main");
    }

    @Test
    void overLimitHostileAndStaleAreExplicit() throws Exception {
        git("checkout", "-q", "-b", "many");
        for (int i = 0; i <= RemoteReviewDataSource.MAX_DIFF_FILES; i++) {
            Files.writeString(repo.resolve("f" + i + ".txt"), "x\n");
        }
        git("add", "-A");
        commit();
        ReviewScope many = remoteScope(ReviewScope.Kind.BRANCH, "main");
        var tooMany = assertInstanceOf(Result.Unavailable.class,
                source(Duration.ofSeconds(30)).refresh(many, () -> false).get(), "2,049 files is unavailable");
        assertTrue(tooMany.reason().contains("more than 2048 files"), tooMany.reason());
        var late = assertInstanceOf(Result.Unavailable.class,
                source(Duration.ZERO).refresh(many, () -> false).get(), "the overall ceiling is unavailable");
        assertTrue(late.reason().contains("overall time ceiling"), late.reason());

        git("checkout", "-q", "main");
        Files.delete(repo.resolve("a.txt"));
        Files.createSymbolicLink(repo.resolve("a.txt"), Path.of("/etc/hosts"));
        ReviewScope wt = remoteScope(ReviewScope.Kind.WORKING_TREE, "main");
        assertInstanceOf(Result.Unavailable.class, source(Duration.ofSeconds(30)).refresh(wt, () -> false).get(),
                "a symlinked working file is never followed or read");
        git("checkout", "--", "a.txt");
        for (String hostile : List.of("../x", "-x", "/etc/hosts", ".git/config", "a.txt/../../x")) {
            assertInstanceOf(Result.Unavailable.class,
                    source(Duration.ofSeconds(30)).excerpt(session, hostile, 1, 1).get(), hostile);
        }
        assertEquals(List.of("l"), ok(source(Duration.ofSeconds(30)).excerpt(session, "a.txt", 1, 0)).lines());

        Files.writeString(repo.resolve("a.txt"), "edit0\n");
        afterCapture = n -> write("a.txt", "edit" + n + "\n");
        assertInstanceOf(Result.Stale.class, source(Duration.ofSeconds(30)).refresh(wt, () -> false).get(),
                "an edit during every capture is STALE after one retry");
        captures.set(0);
        afterCapture = n -> { if (n == 1) write("a.txt", "edit-once\n"); };
        ReviewDataSnapshot snap = ok(source(Duration.ofSeconds(30)).refresh(wt, () -> false));
        assertArrayEquals("edit-once\n".getBytes(StandardCharsets.UTF_8),
                ((Content.Text) snap.sources().get("a.txt").right()).bytes(), "retry captured the settled state");
        afterCapture = n -> { };
        write("a.txt", "later\n");
        assertInstanceOf(Result.Stale.class, source(Duration.ofSeconds(30)).validateCurrent(snap).get());
    }

    /** Remote snapshot equals the local DiffService/ReviewContents results for the same fixture. */
    private ReviewDataSnapshot paired(ReviewScope.Kind kind, String base) throws Exception {
        ReviewScope local = registry.mint(ReviewScopeRegistry.spec(kind, repo, Optional.of(repo), base, "h",
                Optional.empty(), Optional.of(session)));
        ReviewDataSnapshot snap = ok(source(Duration.ofSeconds(30)).refresh(remoteScope(kind, base), () -> false));
        UnifiedDiff direct = diffs.diff(repo, local.diffScope(), base, DiffService.REVIEW_CONTEXT_LINES).get();
        assertEquals(direct, snap.diff());
        assertEquals(TourFingerprint.of(direct), TourFingerprint.of(snap.diff()));
        assertTrue(!direct.files().isEmpty());
        for (UnifiedDiff.FileDiff f : direct.files()) {
            assertSide(reader.leftText(local, f), snap, f, Side.LEFT);
            assertSide(reader.rightText(local, f), snap, f, Side.RIGHT);
        }
        return snap;
    }

    private void assertSide(String expected, ReviewDataSnapshot snap, UnifiedDiff.FileDiff f, Side side)
            throws Exception {
        Content c = ok(source(Duration.ofSeconds(30)).contents(snap, f.path(), side));
        if (c instanceof Content.Text t) {
            assertEquals(expected, ReviewContents.normalizeLineSeparators(new String(t.bytes(), StandardCharsets.UTF_8)),
                    f.path() + " " + side);
        } else {
            assertTrue(expected.isEmpty() || c instanceof Content.Binary || c instanceof Content.Absent, f.path());
        }
    }

    private ReviewScope remoteScope(ReviewScope.Kind kind, String base) {
        return registry.mint(ReviewScopeRegistry.spec(kind, Path.of("/remote/placeholder"), Optional.empty(), base,
                "remote-" + kind + base, Optional.empty(), Optional.of(session)));
    }

    private RemoteReviewDataSource source(Duration overall) {
        return new RemoteReviewDataSource(new SshRemote("box", repo.toString()), sh, registry, cache,
                new RemoteReviewDataSource.Helpers(RES.resolve("review_git.py").toString(),
                        RES.resolve("review_data.py").toString()), "checkout-1", 1, Runnable::run, overall);
    }

    private String guard() throws Exception {
        return Arrays.toString(Files.readAllBytes(repo.resolve(".git/index"))).hashCode() + git("for-each-ref");
    }

    private void write(String name, String body) {
        try {
            Files.writeString(repo.resolve(name), body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T ok(CompletableFuture<Result<T>> f) throws Exception {
        return ((Result.Ok<T>) assertInstanceOf(Result.Ok.class, f.get())).value();
    }

    private void commit() throws Exception {
        git("-c", "commit.gpgsign=false", "-c", "user.name=T", "-c", "user.email=t@example.com", "commit", "-q", "-m", "c");
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", args) + ": " + out);
        }
        return out;
    }
}
