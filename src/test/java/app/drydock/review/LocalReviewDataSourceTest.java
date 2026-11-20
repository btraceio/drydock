package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewContents;
import app.drydock.review.ReviewDataSnapshot.Content;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewDataSource.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Local data parity (AC-16/AC-45): the adapter's output equals the direct local calls. */
class LocalReviewDataSourceTest {

    private final GitStatusService git = new GitStatusService(new GitExecutableLocator());
    private final DiffService diffs = new DiffService(new GitExecutableLocator());
    private final ReviewContents reader = new ReviewContents(new GitExecutableLocator(), git);
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final LocalReviewDataSource source = new LocalReviewDataSource(registry, diffs,
            new LocalReviewDataSource.Contents() {
                public String leftText(ReviewScope s, UnifiedDiff.FileDiff f) { return reader.leftText(s, f); }
                public String rightText(ReviewScope s, UnifiedDiff.FileDiff f) { return reader.rightText(s, f); }
                public Optional<String> baseCommit(ReviewScope s) { return reader.baseCommit(s); }
                public Optional<String> headCommit(ReviewScope s) { return reader.headCommit(s); }
            });

    @AfterEach
    void close() {
        diffs.close();
        git.close();
    }

    @Test
    void outputEqualsTheDirectLocalCalls(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "l\n".repeat(20) + "two\n" + "l\n".repeat(20));
        git(repo, "add", "a.txt");
        commit(repo);
        git(repo, "checkout", "-b", "feat");
        Files.writeString(repo.resolve("a.txt"), "l\n".repeat(20) + "TWO\n" + "l\n".repeat(20));
        Files.writeString(repo.resolve("b.txt"), "new\n");
        git(repo, "add", "-A");
        commit(repo);
        ManagedSessionId session = ManagedSessionId.newId();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE, repo,
                Optional.of(repo), "main", "feat", Optional.empty(), Optional.of(session)));

        ReviewScope resolved = ok(source.resolve(session, scope.id()));
        assertEquals(registry.byId(scope.id()).orElseThrow(), resolved, "scope identity is the registry's");
        assertInstanceOf(Result.Refused.class, source.resolve(ManagedSessionId.newId(), scope.id()).get(),
                "another session is refused like the registry does");

        UnifiedDiff direct = diffs.diff(scope.diffRoot(), scope.diffScope(), scope.base(),
                DiffService.REVIEW_CONTEXT_LINES).get();
        ReviewDataSnapshot snap = ok(source.refresh(scope, () -> false));
        assertEquals(direct, snap.diff(), "diff content is the direct DiffService result");
        assertEquals(reader.baseCommit(scope).orElseThrow(), snap.baseCommit());
        assertEquals(reader.headCommit(scope).orElseThrow(), snap.headCommit());
        assertEquals(hunkIds(direct), hunkIds(snap.diff()), "hunk ids");
        assertEquals(lineKeys(direct), lineKeys(snap.diff()), "line keys");
        assertFalse(lineKeys(snap.diff()).isEmpty(), "fixture has keyed lines");

        UnifiedDiff.FileDiff a = direct.files().stream().filter(f -> f.path().equals("a.txt")).findFirst().orElseThrow();
        assertArrayEquals(reader.leftText(scope, a).getBytes(StandardCharsets.UTF_8),
                bytes(ok(source.contents(snap, "a.txt", Side.LEFT))), "left bytes");
        assertArrayEquals(reader.rightText(scope, a).getBytes(StandardCharsets.UTF_8),
                bytes(ok(source.contents(snap, "a.txt", Side.RIGHT))), "right bytes");
        assertInstanceOf(Content.Absent.class, ok(source.contents(snap, "b.txt", Side.LEFT)), "added file has no left");
        assertInstanceOf(Result.Refused.class, source.contents(snap, "nope.txt", Side.LEFT).get());

        assertInstanceOf(Result.Ok.class, source.validateCurrent(snap).get(), "unchanged checkout is current");
        Files.writeString(repo.resolve("c.txt"), "x\n");
        git(repo, "add", "-A");
        commit(repo);
        assertInstanceOf(Result.Stale.class, source.validateCurrent(snap).get(), "a new commit makes it stale");
    }

    private static List<String> hunkIds(UnifiedDiff d) {
        List<String> ids = new ArrayList<>();
        d.files().forEach(f -> { for (int i = 0; i < f.hunks().size(); i++) ids.add(HunkIds.hunkId(f.path(), i)); });
        return ids;
    }

    private static List<String> lineKeys(UnifiedDiff d) {
        List<String> keys = new ArrayList<>();
        d.files().forEach(f -> f.hunks().forEach(h -> h.lines().forEach(l -> keys.add(f.path() + ":" + l.lineKey()))));
        return keys;
    }

    private static byte[] bytes(Content c) {
        return ((Content.Text) c).bytes();
    }

    private static <T> T ok(java.util.concurrent.CompletableFuture<Result<T>> f) throws Exception {
        return ((Result.Ok<T>) assertInstanceOf(Result.Ok.class, f.get())).value();
    }

    private static void commit(Path repo) throws Exception {
        git(repo, "-c", "user.name=T", "-c", "user.email=t@example.com", "commit", "-m", "c");
    }

    private static void git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", args) + ": " + out);
        }
    }
}
