package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sections follow the code's structure, not its folders (spec §5).
 *
 * <p>The failure this replaces, measured on a real C++ change: cards reading
 * "main/cpp · 12 files", "test/cpp · 4 files", "cpp/hotspot · 6 files" --
 * each individually correct and collectively saying nothing, because the
 * grouping had no structural input at all.</p>
 */
class SectionsTest {

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

    private static List<Sections.Section> sectionsOf(UnifiedDiff diff) {
        return Sections.of(diff, ChangeGraph.of(diff));
    }

    private static Sections.Section sectionContaining(List<Sections.Section> sections, String file) {
        return sections.stream().filter(s -> s.files().contains(file)).findFirst().orElseThrow();
    }

    /** The convention a C or C++ change is unreadable without. */
    @Test
    void aHeaderGroupsWithItsSameBasenameImplementation() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/guards.cpp", "void install() { }"))));

        assertTrue(sectionContaining(sections, "src/guards.h").files().contains("src/guards.cpp"));
    }

    /**
     * The counters.h case from the reference output: a header with no changed
     * symbol of its own still belongs with the file that pulls it in.
     */
    @Test
    void aHeaderGroupsWithAChangedImplementationThatReferencesIt() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/counters.h", "#define FAULTS 1"),
                file("src/profiler.cpp", "#include \"counters.h\"", "void loop() { }"))));

        assertTrue(sectionContaining(sections, "src/profiler.cpp").files().contains("src/counters.h"));
    }

    /**
     * The same rule through an {@code import}: the languages that spell the
     * dependency with a dotted name get it too, not just {@code #include}.
     */
    @Test
    void anImportedFileWithNoChangedSymbolGroupsWithItsImporter() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/Constants.java", "// the shared table"),
                file("src/Main.java", "import app.Constants;", "void run() { }"))));

        assertTrue(sectionContaining(sections, "src/Main.java").files().contains("src/Constants.java"));
    }

    /**
     * Naming a file in prose is not depending on it. A substring test over
     * hunk text -- which is what the first sketch of this class did -- fires
     * on comments, string literals and unrelated words, and would drag every
     * file that mentions a header into that header's section.
     */
    @Test
    void aFileNameMentionedInACommentIsNotADependency() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/counters.h", "#define FAULTS 1"),
                file("src/profiler.cpp", "#include \"counters.h\"", "void loop() { }"),
                file("src/notes.cpp", "// counters.h explains the flag", "void notes() { }"))));

        assertTrue(sectionContaining(sections, "src/profiler.cpp").files().contains("src/counters.h"));
        assertFalse(sectionContaining(sections, "src/notes.cpp").files().contains("src/counters.h"),
                "a comment naming a header is not an include of it");
    }

    /** Overlap is the point (spec §5.6): a shared header appears in both. */
    @Test
    void aFileNeededByTwoSectionsAppearsInBoth() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/a.cpp", "#include \"guards.h\"", "void alpha() { new JmpCtxScope(); }"),
                file("src/b.cpp", "#include \"guards.h\"", "void beta() { new JmpCtxScope(); }"))));

        List<Sections.Section> withHeader = sections.stream()
                .filter(s -> s.files().contains("src/guards.h")).toList();

        assertTrue(withHeader.size() >= 2, "a shared header must appear wherever it is needed");
        // Discriminating: the appearances must be in genuinely different
        // sections, not one section counted twice.
        assertTrue(withHeader.stream().anyMatch(s -> s.files().contains("src/a.cpp")));
        assertTrue(withHeader.stream().anyMatch(s -> s.files().contains("src/b.cpp")));
        assertFalse(withHeader.stream()
                        .anyMatch(s -> s.files().containsAll(List.of("src/a.cpp", "src/b.cpp"))),
                "the two consumers are separate changes; sharing a header does not merge them");
    }

    /** Foundation first: the guard is read before what uses it. */
    @Test
    void sectionsAreOrderedByDependencyDirection() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/profiler.cpp", "void loop() { new JmpCtxScope(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))));

        assertEquals("src/guards.cpp", sections.get(0).files().get(0));
    }

    /** Within a section too: the file being depended on is read first. */
    @Test
    void aSectionListsItsFoundationBeforeWhatUsesIt() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/aaa.cpp", "void loop() { new JmpCtxScope(); }"),
                file("src/zzz.cpp", "class JmpCtxScope { };"))));

        Sections.Section user = sectionContaining(sections, "src/aaa.cpp");
        assertEquals(List.of("src/zzz.cpp", "src/aaa.cpp"), user.files(),
                "alphabetical order would put aaa.cpp first; reading order must not");
    }

    /** A test referencing a changed symbol lands with it -- no path-based split. */
    @Test
    void aTestReferencingAChangedSymbolIsInThatSymbolsSection() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("test/guards_ut.cpp", "void probe() { new JmpCtxScope(); }"))));

        assertTrue(sectionContaining(sections, "test/guards_ut.cpp")
                .files().contains("src/guards.cpp"));
        assertFalse(sections.stream().anyMatch(s -> s.files().equals(List.of("test/guards_ut.cpp"))),
                "a tests-only section is the path heuristic this class replaces");
    }

    /** A test referencing nothing changed is its own section, honestly. */
    @Test
    void aTestReferencingNothingChangedFormsItsOwnSection() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("test/unrelated_ut.cpp", "void probe() { checkSomethingElse(); }"))));

        assertEquals(List.of("test/unrelated_ut.cpp"),
                sectionContaining(sections, "test/unrelated_ut.cpp").files());
    }

    /** The name is the thing, not the folder. */
    @Test
    void aSectionIsTitledByItsHighestFanInChangedSymbol() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/a.cpp", "void alpha() { new JmpCtxScope(); }"),
                file("src/b.cpp", "void beta() { new JmpCtxScope(); }"))));

        assertTrue(sections.get(0).title().startsWith("JmpCtxScope"),
                "expected a hub-symbol title, got: " + sections.get(0).title());
    }

    /**
     * Fan-in is counted per SYMBOL, not per file. Scoring every declaration
     * with its file's fan-in -- which the first sketch of this class did --
     * makes the "hub" whatever sorts first alphabetically in the
     * most-referenced file, which is not a claim about the code at all.
     */
    @Test
    void theHubIsTheMostReferencedSymbolNotTheFirstOneInTheFile() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/core.cpp", "class AaaHelper { };", "class ZzzEngine { };"),
                file("src/one.cpp", "void one() { new ZzzEngine(); }"),
                file("src/two.cpp", "void two() { new ZzzEngine(); }"))));

        Sections.Section core = sections.get(0);
        assertEquals(Optional.of("ZzzEngine"), core.hubSymbol(),
                "AaaHelper is referenced by nothing; it cannot be what the section is about");
        assertTrue(core.title().startsWith("ZzzEngine"), "got: " + core.title());
    }

    /**
     * A card nobody can name, whose files another card already carries, is
     * the folder failure coming back in through the side door: it would read
     * "src · 1 file" and sit next to the sections that already show it.
     */
    @Test
    void aHublessSectionAlreadyCarriedElsewhereGetsNoCardOfItsOwn() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/counters.h", "#define FAULTS 1"),
                file("src/one.cpp", "#include \"counters.h\"", "void one() { }"),
                file("src/two.cpp", "#include \"counters.h\"", "void two() { }"))));

        assertFalse(sections.stream().anyMatch(s -> s.files().equals(List.of("src/counters.h"))),
                "an unnameable card the rail already covers is noise");
        assertEquals(2, sections.stream().filter(s -> s.files().contains("src/counters.h")).count(),
                "dropping the card must not drop the file from the sections that need it");
    }

    /** With nothing to consult, today's behaviour survives unchanged. */
    @Test
    void anEdgelessDiffFallsBackToDirectoryClustering() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("web/a.zzz", "nothing"), file("web/b.zzz", "nothing")));

        assertEquals(FallbackIntents.group(diff).size(), sectionsOf(diff).size());
    }

    /** A genuine mutual reference is reported as a cycle. */
    @Test
    void mutuallyReferencingFilesAreOneSectionMarkedAsACycle() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/alpha.cpp", "class Alpha { };", "void useBeta() { new Beta(); }"),
                file("src/beta.cpp", "class Beta { };", "void useAlpha() { new Alpha(); }"))));

        assertEquals(1, sections.size());
        assertEquals(List.of("src/alpha.cpp", "src/beta.cpp"), sections.get(0).cycleWith());
    }

    /**
     * A header joined to its implementation by convention is not a cycle.
     * Both are one unit, but nothing about the code depends on itself, and
     * saying so would be a lie a reviewer acts on.
     */
    @Test
    void aConventionJoinedPairIsNotReportedAsACycle() {
        List<Sections.Section> sections = sectionsOf(new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/guards.cpp", "void install() { }"))));

        assertEquals(List.of(), sectionContaining(sections, "src/guards.h").cycleWith());
    }

    /** Nothing may fall out of the rail: every hunk lands in some section. */
    @Test
    void everyHunkAppearsInSomeSection() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/guards.cpp", "void install() { }"),
                file("src/profiler.cpp", "void loop() { new JmpCtxScope(); }"),
                file("src/orphan.cpp", "void lonely() { }")));
        List<Sections.Section> sections = sectionsOf(diff);

        for (UnifiedDiff.FileDiff file : diff.files()) {
            String hunkId = ReviewIntent.hunkId(file.path(), 0);
            assertTrue(sections.stream().anyMatch(s -> s.hunkIds().contains(hunkId)),
                    "no section carries " + hunkId);
        }
    }

    /**
     * Determinism (spec §9.5) is a requirement, not a property. The input's
     * own order must not reach the output -- that is the cheapest way for
     * hash iteration to leak in unnoticed.
     */
    @Test
    void theSameChangeInADifferentFileOrderGivesTheSameSections() {
        List<UnifiedDiff.FileDiff> files = List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/guards.cpp", "void install() { }"),
                file("src/a.cpp", "#include \"guards.h\"", "void alpha() { new JmpCtxScope(); }"),
                file("src/b.cpp", "#include \"guards.h\"", "void beta() { new JmpCtxScope(); }"));
        List<UnifiedDiff.FileDiff> reversed = new ArrayList<>(files);
        Collections.reverse(reversed);

        assertEquals(sectionsOf(new UnifiedDiff(files)),
                sectionsOf(new UnifiedDiff(List.copyOf(reversed))));
    }
}
