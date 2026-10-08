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
import java.util.Optional;
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

    /** A match on a line the change itself wrote is not a caller of the change. */
    @Test
    void matchesOnChangedLinesAreExcluded() {
        assertEquals(List.of(), OutOfDiffFanIn.parse(
                "src/guards.cpp" + NUL + "9" + NUL + "  JmpCtxScope guard;\n",
                Map.of("src/guards.cpp", Set.of(9))));
    }

    /**
     * An unedited call site in an edited file is where a signature change
     * usually breaks: it is kept, and flagged so the reading order (which
     * ranks by fan-in from OUTSIDE the change files) can ignore it.
     */
    @Test
    void unchangedLinesOfChangedFilesAreKeptAndFlagged() {
        List<OutOfDiffFanIn.Occurrence> parsed = OutOfDiffFanIn.parse(
                "src/guards.cpp" + NUL + "9" + NUL + "  JmpCtxScope guard;\n"
                        + "src/guards.cpp" + NUL + "30" + NUL + "  use(JmpCtxScope);\n"
                        + "src/other.cpp" + NUL + "4" + NUL + "  JmpCtxScope x;\n",
                Map.of("src/guards.cpp", Set.of(9)));

        assertEquals(List.of(
                new OutOfDiffFanIn.Occurrence("src/guards.cpp", 30, "  use(JmpCtxScope);", true),
                new OutOfDiffFanIn.Occurrence("src/other.cpp", 4, "  JmpCtxScope x;", false)),
                parsed);
    }

    @Test
    void anUnavailableResultCarriesItsReason() {
        OutOfDiffFanIn.Result timedOut = new OutOfDiffFanIn.Result(
                Map.of(), Optional.of("git grep timed out after 30 s"));
        assertTrue(timedOut.unavailable());
        assertEquals("git grep timed out after 30 s", timedOut.unavailableReason().orElseThrow());
        assertFalse(new OutOfDiffFanIn.Result(Map.of(), false).unavailable());
        assertEquals("unavailable",
                new OutOfDiffFanIn.Result(Map.of(), true).unavailableReason().orElseThrow());
    }

    @Test
    void aScopeWithNoCheckoutSaysSoInsteadOfLookingEmpty() {
        ReviewScope scope = new ReviewScope("rs_1", ReviewScope.Kind.BRANCH, Path.of("/repo"),
                Optional.empty(), "main", "feature", Optional.empty(), Optional.empty(),
                Optional.empty());
        UnifiedDiff diff = new UnifiedDiff(List.of(file("src/Guards.java", "class JmpCtxScope { }")));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.forScope(scope, ChangeGraph.of(diff), diff);

        assertTrue(result.unavailable());
        assertEquals("no checkout to search", result.unavailableReason().orElseThrow());
    }

    /**
     * The whole pipeline: the diff's ADD rows say which lines of Guards.java
     * the change wrote (line 1); a mention on an unedited line 2 of the same
     * file is kept and flagged, the line-1 mention is not listed.
     */
    @Test
    void forScopeKeepsUneditedLinesOfAChangedFileAndFlagsThem(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path repo = initCommittedRepoWithFanIn(dir);
        Files.writeString(repo.resolve("src/Guards.java"),
                "class JmpCtxScope { }\nvoid again() { new JmpCtxScope(); }\n", StandardCharsets.UTF_8);
        ReviewScope scope = new ReviewScope("rs_1", ReviewScope.Kind.BRANCH, repo,
                Optional.of(repo), "main", "feature", Optional.empty(), Optional.empty(),
                Optional.empty());
        UnifiedDiff diff = new UnifiedDiff(List.of(file("src/Guards.java", "class JmpCtxScope { }")));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.forScope(scope, ChangeGraph.of(diff), diff);

        assertFalse(result.unavailable());
        List<OutOfDiffFanIn.Occurrence> hits = result.bySymbol().get("JmpCtxScope");
        List<OutOfDiffFanIn.Occurrence> inChanged =
                hits.stream().filter(OutOfDiffFanIn.Occurrence::inChangedFile).toList();
        assertEquals(1, inChanged.size(), "hits: " + hits);
        assertEquals("src/Guards.java", inChanged.get(0).file());
        assertEquals(2, inChanged.get(0).line());
        assertTrue(hits.stream().anyMatch(o -> o.file().equals("src/Other.java") && !o.inChangedFile()));
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

    /**
     * {@code git grep} exits 1 for "no matches", and that must reach the
     * caller as an empty-but-available answer. This is the property a
     * regression narrowing {@code exitCode() > 1} to {@code >= 1} would
     * silently break, so it is pinned through a REAL spawn (a repo git
     * actually greps and finds nothing in) rather than through the
     * could-not-launch path {@code scanReturnsUnavailableWhenGitCannotRun}
     * already covers.
     */
    @Test
    void aSymbolMatchingNowhereIsAnEmptyAnswerNotUnavailable(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path repo = initCommittedRepoWithFanIn(dir);
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(
                List.of(file("src/Guards.java", "class TotallyAbsentSymbolXyz { }"))));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.scan(repo, graph, Set.of("src/Guards.java"));

        assertFalse(result.unavailable(),
                "git grep exit 1 (no matches anywhere) is a valid empty answer, not unavailable");
        assertEquals(Map.of(), result.bySymbol());
    }

    // ---- word boundaries: an inflated count reorders what a human reads ----

    /**
     * {@code ZetaSymHelper} is not two uses of {@code ZetaSym}; it is a
     * different class that happens to start with those letters. Before this
     * fix, both halves of the match were plain substring tests -- {@code git
     * grep -F} finding the lines and {@code text.contains(symbol)}
     * attributing them -- so the rail rendered "called from 2 places outside
     * the change" and the popover listed two lines that are not usages at
     * all.
     *
     * <p>Worth a real spawn rather than a unit test of the filter: it is
     * {@code -w} on the git side that has to be right too, and a filter
     * fixed alone would still be handed lines the symbol never appears in.
     * This number is the reading path's FIRST rank term, so an inflated one
     * does not merely read wrong -- it reorders what a reviewer reads
     * next.</p>
     */
    @Test
    void aLongerIdentifierThatMerelyStartsWithTheSymbolIsNotAUse(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Zeta.java"), "class ZetaSym { }\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/Caller.java"),
                "void a() { new ZetaSymHelper(); }\nvoid b() { ZetaSymHelper.of(); }\n",
                StandardCharsets.UTF_8);
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-m", "initial commit");
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(
                List.of(file("src/Zeta.java", "class ZetaSym { }"))));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.scan(repo, graph, Set.of("src/Zeta.java"));

        assertFalse(result.unavailable());
        assertEquals(Map.of(), result.bySymbol(),
                "ZetaSymHelper is a different identifier; counting it inflates rank term 1");
    }

    /**
     * The other half: when one changed declaration's name is a prefix of
     * another's, a line using the LONGER one comes back from {@code git grep
     * -w} legitimately -- and must then be attributed to that one only.
     * {@code git grep -w} cannot make this distinction for us, which is why
     * {@link OutOfDiffFanIn#mentions} exists rather than a plain {@code
     * contains}.
     */
    @Test
    void aLineIsAttributedOnlyToTheSymbolItActuallyNames(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Pair.java"), "class Foo { }\nclass FooBar { }\n",
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("src/Caller.java"), "void a() { new FooBar(); }\n",
                StandardCharsets.UTF_8);
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-m", "initial commit");
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(
                List.of(file("src/Pair.java", "class Foo { }", "class FooBar { }"))));

        OutOfDiffFanIn.Result result = OutOfDiffFanIn.scan(repo, graph, Set.of("src/Pair.java"));

        assertFalse(result.unavailable());
        assertEquals(Set.of("FooBar"), result.bySymbol().keySet(),
                "the caller names FooBar, not Foo: " + result.bySymbol());
    }

    // ---- classified(): self-declaring files, and too-common symbols --------

    /**
     * The defect this filter exists for: a changed declaration named like a
     * common member. A file that declares its own {@code text} uses its own
     * -- every occurrence of the symbol in that file is a false link to the
     * change, so the file is dropped whole, while a file that only calls
     * {@code text()} keeps its rows.
     */
    @Test
    void aFileOutsideTheChangeThatDeclaresItsOwnSymbolIsDroppedWhole() {
        OutOfDiffFanIn.Occurrence genuineUse = new OutOfDiffFanIn.Occurrence("src/Reader.java", 3,
                "    System.out.println(holder.text());", false);
        OutOfDiffFanIn.Result result = OutOfDiffFanIn.classified(Map.of("text", List.of(
                genuineUse,
                new OutOfDiffFanIn.Occurrence("src/Label.java", 7, "    private String text;", false),
                new OutOfDiffFanIn.Occurrence("src/Label.java", 12, "    return text.trim();", false),
                new OutOfDiffFanIn.Occurrence("src/Kotlin.kt", 2, "val text = compute()", false))));

        assertEquals(Map.of("text", List.of(genuineUse)), result.bySymbol(),
                "Label.java and Kotlin.kt declare their own text; Reader.java does not");
        assertTrue(result.suppressedCounts().isEmpty());
    }

    /** A call, an instantiation and a qualified write are uses, never declarations. */
    @Test
    void plainUsesNeverReadAsDeclarations() {
        assertFalse(OutOfDiffFanIn.looksLikeDeclaration("    holder.text();", "text"));
        assertFalse(OutOfDiffFanIn.looksLikeDeclaration("    new JmpCtxScope();", "JmpCtxScope"));
        // An anonymous class body opens a brace after the constructor -- the
        // method pattern would match without the new-guard.
        assertFalse(OutOfDiffFanIn.looksLikeDeclaration("    new JmpCtxScope() {", "JmpCtxScope"));
        assertFalse(OutOfDiffFanIn.looksLikeDeclaration("    foo.text = x;", "text"));
        assertFalse(OutOfDiffFanIn.looksLikeDeclaration("    return text();", "text"));
        assertFalse(OutOfDiffFanIn.looksLikeDeclaration("    // text is declared elsewhere", "text"));
    }

    /** The declaration shapes that DO mark a file as self-declaring. */
    @Test
    void ownDeclarationsAreRecognizedAcrossTheirShapes() {
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    private String text;", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    private final String text = \"\";", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    var text = 5;", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    String text() {", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    @Override public int text() {", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    void run() { go(); }", "run"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    fun text(): String {", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    def text(self):", "text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    class Text {", "Text"));
        assertTrue(OutOfDiffFanIn.looksLikeDeclaration("    record Pair(int left) {", "Pair"));
    }

    /**
     * An unedited call site in an EDITED file is where a signature change
     * breaks: never dropped for self-declaring, even when that file also
     * declares a symbol of the same name elsewhere.
     */
    @Test
    void occurrencesInsideChangedFilesAreNeverDroppedAsSelfDeclaring() {
        OutOfDiffFanIn.Occurrence use = new OutOfDiffFanIn.Occurrence("src/Guards.java", 30,
                "    new JmpCtxScope();", true);
        OutOfDiffFanIn.Occurrence ownDeclaration = new OutOfDiffFanIn.Occurrence("src/Guards.java", 9,
                "    private JmpCtxScope own;", true);
        OutOfDiffFanIn.Result result = OutOfDiffFanIn.classified(Map.of("JmpCtxScope", List.of(
                use, ownDeclaration)));

        assertEquals(Map.of("JmpCtxScope", List.of(use, ownDeclaration)), result.bySymbol(),
                "inside a changed file the graph says what is declared, not this heuristic");
    }

    /**
     * Past the attribution cap the rows are noise, not signal: the symbol
     * leaves bySymbol and its count moves to suppressedCounts, where
     * occurrences() still reports it so the signature flag survives.
     */
    @Test
    void aSymbolTooCommonToListIsCountedNotListed() {
        List<OutOfDiffFanIn.Occurrence> many = new ArrayList<>();
        for (int i = 1; i <= OutOfDiffFanIn.MAX_ATTRIBUTABLE + 1; i++) {
            many.add(new OutOfDiffFanIn.Occurrence("src/F" + i + ".java", 1, "    use(spindle);", false));
        }
        OutOfDiffFanIn.Result result = OutOfDiffFanIn.classified(Map.of("spindle", many));

        assertEquals(Map.of(), result.bySymbol(), "a wall of rows is a count, not a list");
        assertEquals(Map.of("spindle", OutOfDiffFanIn.MAX_ATTRIBUTABLE + 1), result.suppressedCounts());
        assertEquals(OutOfDiffFanIn.MAX_ATTRIBUTABLE + 1, result.occurrences("spindle"));
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
