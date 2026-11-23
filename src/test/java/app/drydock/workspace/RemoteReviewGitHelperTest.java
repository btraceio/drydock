package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real bundled Git capture helper against real temporary repositories. */
class RemoteReviewGitHelperTest {

    private static final Path HELPER = Path.of("src/main/resources/drydock/remote/review_git.py")
            .toAbsolutePath();

    @TempDir
    Path tmp;
    Path repo;
    Path scratchTmp;

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectory(tmp.resolve("repo"));
        scratchTmp = Files.createDirectory(tmp.resolve("scratch"));
        git("init", "-q", "-b", "main");
        git("config", "user.email", "t@example.invalid");
        git("config", "user.name", "T");
        git("config", "commit.gpgsign", "false");
        write("a.txt", "one\ntwo\n");
        write("old.txt", "rename me\nline2\nline3\nline4\n");
        write("bin.dat", new byte[]{1, 2, 0, 3});
        git("add", ".");
        git("commit", "-q", "-m", "base");
    }

    private ProcessResult exec(List<String> cmd, Map<String, String> env) throws Exception {
        return ProcessRunner.run(cmd, new ProcessRunner.Options(repo, Duration.ofSeconds(60), true, env));
    }

    private String git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessResult r = exec(cmd, Map.of());
        assertEquals(0, r.exitCode(), r.stderr());
        return r.stdout();
    }

    private ProcessResult capture(String range, String worktree) throws Exception {
        return exec(List.of("python3", HELPER.toString(), "capture", repo.toString(), range, "3", worktree),
                Map.of("TMPDIR", scratchTmp.toString()));
    }

    private void write(String rel, String body) throws Exception {
        write(rel, body.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String rel, byte[] body) throws Exception {
        Files.write(repo.resolve(rel), body);
    }

    private static String field(ProcessResult r, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"[^\"]*\"|[^,}]*)").matcher(r.stdout());
        assertTrue(m.find(), r.stdout() + r.stderr());
        return m.group(1).replace("\"", "");
    }

    private static String b64(ProcessResult r, String name) {
        return new String(Base64.getDecoder().decode(field(r, name)), StandardCharsets.UTF_8);
    }

    private String realState() throws Exception {
        return Arrays.hashCode(Files.readAllBytes(repo.resolve(".git/index"))) + git("for-each-ref") + git("rev-parse", "HEAD");
    }

    private void assertCode(ProcessResult r, String code) {
        assertEquals(1, r.exitCode(), r.stdout() + r.stderr());
        assertEquals("{\"ok\":false,\"code\":\"" + code + "\"}", r.stdout().trim());
    }

    @Test
    void capturesStagedUnstagedAndUntrackedWithoutTouchingRealIndexOrRefs() throws Exception {
        write("a.txt", "one\ntwo\nstaged\n");
        git("add", "a.txt");
        write("a.txt", "one\ntwo\nstaged\nunstaged\n");
        write("new.txt", "fresh\n");
        byte[] indexBefore = Files.readAllBytes(repo.resolve(".git/index"));
        String before = realState();

        ProcessResult r = capture("HEAD", "1");

        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        assertEquals(git("rev-parse", "HEAD").trim(), field(r, "head"));
        assertEquals("main", field(r, "branch"));
        assertEquals("false", field(r, "detached"));
        assertEquals("a.txt\0", b64(r, "staged_b64"));
        assertEquals("new.txt\0", b64(r, "untracked_b64"));
        String diff = b64(r, "diff_b64");
        assertTrue(diff.contains("+staged\n+unstaged\n"), diff);
        assertTrue(diff.contains("diff --git a/new.txt b/new.txt\nnew file mode"), diff);
        assertTrue(diff.contains("+fresh"), diff);
        assertTrue(b64(r, "status_b64").contains("?? new.txt"), b64(r, "status_b64"));
        assertEquals(before, realState());
        assertArrayEquals(indexBefore, Files.readAllBytes(repo.resolve(".git/index")));
        assertEquals("new.txt\n", git("ls-files", "--others", "--exclude-standard"));
        try (var left = Files.list(scratchTmp)) {
            assertEquals(0, left.count(), "private index copy must be removed");
        }
    }

    @Test
    void detachedHeadHasNoBranch() throws Exception {
        git("checkout", "-q", "--detach", "HEAD");
        ProcessResult r = capture("HEAD", "0");
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        assertEquals("true", field(r, "detached"));
        assertEquals("null", field(r, "branch"));
        assertEquals("", b64(r, "untracked_b64"));
    }

    @Test
    void reportsRenameBinaryAndCrlfFromGit() throws Exception {
        git("mv", "old.txt", "renamed.txt");
        write("bin.dat", new byte[]{1, 2, 0, 9});
        write("crlf.txt", "x\r\ny\r\n");
        ProcessResult r = capture("HEAD", "1");
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        String diff = b64(r, "diff_b64");
        assertTrue(diff.contains("rename from old.txt\nrename to renamed.txt"), diff);
        assertTrue(diff.contains("Binary files a/bin.dat and b/bin.dat differ"), diff);
        assertTrue(diff.contains("+x\r\n+y\r\n"), diff);
    }

    @Test
    void rejectsOptionLikeRangeAndBadArguments() throws Exception {
        assertCode(capture("--output=x", "1"), "bad-arguments");
        assertCode(capture("HEAD", "2"), "bad-arguments");
        assertCode(capture("no-such-ref...HEAD", "0"), "git-failed");
        assertFalse(Files.exists(repo.resolve("x")));
    }

    @Test
    void aboveUntrackedThresholdOmitsInclusionVisibly() throws Exception {
        for (int i = 0; i < 2001; i++) {
            Files.write(repo.resolve("u" + i), new byte[0]);
        }
        ProcessResult r = capture("HEAD", "1");
        assertEquals(0, r.exitCode(), r.stdout() + r.stderr());
        assertEquals("true", field(r, "untracked_omitted"));
        assertFalse(b64(r, "diff_b64").contains("u1"));
    }
}
