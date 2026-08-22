package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>{@code git grep -n -F} without {@code -z} C-quotes any path with a
 * non-ASCII byte or special character -- the same defect a base-move fix
 * elsewhere in this package already paid for once. So the scan is spawned
 * with {@code -z}, and the parsing below is against that framing: {@code
 * file<NUL>line<NUL>text\n} per match, not the colon-joined text {@code git
 * grep} prints without it. The scan test at the bottom spawns real git
 * against a repo with a non-ASCII filename to prove the whole pipeline, not
 * just the parser, carries it through intact.</p>
 */
class OutOfDiffFanInTest {

    private static final char NUL = '\0';

    @Test
    void parsingKeepsFileLineAndText() {
        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(
                "src/other.cpp" + NUL + "42" + NUL + "  JmpCtxScope guard;\n",
                Set.of("src/guards.cpp"));

        assertEquals(1, parsed.size());
        assertEquals("src/other.cpp", parsed.get(0).file());
        assertEquals(42, parsed.get(0).line());
        assertTrue(parsed.get(0).text().contains("JmpCtxScope"));
    }

    /** Occurrences inside the change are not "outside" it. */
    @Test
    void matchesInChangedFilesAreExcluded() {
        assertEquals(List.of(), OutOfDiffFanIn.parse(
                "src/guards.cpp" + NUL + "9" + NUL + "  JmpCtxScope guard;\n",
                Set.of("src/guards.cpp")));
    }

    @Test
    void aMalformedLineIsSkippedRatherThanFatal() {
        assertEquals(List.of(), OutOfDiffFanIn.parse("not a grep line\n", Set.of()));
    }

    /** A path containing a colon must not be truncated at it -- NUL, not ':', separates fields. */
    @Test
    void aPathContainingAColonParsesBackToItself() {
        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(
                "src/a:b.cpp" + NUL + "7" + NUL + "x();\n", Set.of());

        assertEquals("src/a:b.cpp", parsed.get(0).file());
        assertEquals(7, parsed.get(0).line());
    }

    /**
     * The defect this task exists to avoid repeating: a non-ASCII filename
     * must round-trip intact, not arrive C-quoted with octal escapes.
     */
    @Test
    void aNonAsciiPathParsesBackToItself() {
        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(
                "src/café.txt" + NUL + "1" + NUL + "JmpCtxScope guard;\n", Set.of());

        assertEquals("src/café.txt", parsed.get(0).file());
    }

    /** Multiple matches in one grep run, across records, all parse. */
    @Test
    void multipleRecordsInOneRunAllParse() {
        String stdout = "src/a.cpp" + NUL + "1" + NUL + "JmpCtxScope x;\n"
                + "src/b.cpp" + NUL + "2" + NUL + "JmpCtxScope y;\n";

        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(stdout, Set.of());

        assertEquals(2, parsed.size());
        assertEquals("src/a.cpp", parsed.get(0).file());
        assertEquals("src/b.cpp", parsed.get(1).file());
    }

    @Test
    void anEmptyStdoutParsesToNoOccurrences() {
        assertEquals(List.of(), OutOfDiffFanIn.parse("", Set.of()));
    }

    // ---- scan(): the real spawn, a real repo, a real non-ASCII filename ----

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }

    @Test
    void scanFindsOutOfDiffUsesAcrossFilesIncludingANonAsciiCaller(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path repo = initCommittedRepoWithFanIn(dir);
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(
                List.of(file("src/Guards.java", "class JmpCtxScope { }"))));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.scan(repo, graph, Set.of("src/Guards.java"));

        assertFalse(result.unavailable());
        List<OutOfDiffFanIn.Occurrence> hits = result.bySymbol().get("JmpCtxScope");
        assertTrue(hits != null && hits.size() >= 2,
                "expected hits in both the plain and non-ASCII caller, got: " + hits);
        List<String> files = hits.stream().map(OutOfDiffFanIn.Occurrence::file).toList();
        assertTrue(files.contains("src/Other.java"), "plain caller missing: " + files);
        assertTrue(files.contains("src/café.txt"), "non-ASCII caller missing: " + files);
        assertFalse(files.contains("src/Guards.java"), "the changed file itself must be excluded");
    }

    @Test
    void scanReturnsUnavailableWhenGitCannotRun(@TempDir Path dir) throws IOException {
        Path notARepo = dir.resolve("does-not-exist");
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(
                List.of(file("src/Guards.java", "class JmpCtxScope { }"))));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.scan(notARepo, graph, Set.of("src/Guards.java"));

        assertTrue(result.unavailable(), "a scan that could not run must report unavailable, not zero");
        assertEquals(Map.of(), result.bySymbol());
    }

    @Test
    void aScopeWithNoChangedDeclarationsScansNothing(@TempDir Path dir) {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of()));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.scan(dir, graph, Set.of());

        assertFalse(result.unavailable());
        assertEquals(Map.of(), result.bySymbol());
    }

    private static Path initCommittedRepoWithFanIn(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Guards.java"), "class JmpCtxScope { }\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/Other.java"),
                "void go() { new JmpCtxScope(); }\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/café.txt"),
                "JmpCtxScope guard;\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited " + exit + ": " + output);
        }
    }
}
