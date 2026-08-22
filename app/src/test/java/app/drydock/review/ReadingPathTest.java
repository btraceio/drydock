package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where to start, what follows, and why (spec §6). Entry-point rank is
 * applied INSIDE the sort rather than as a marking pass afterwards: ordering
 * first and marking second lets "card 1" and "START HERE" disagree, and a
 * START HERE badge on card 4 reads as a bug rather than a design.
 *
 * <p>Every ordering test here is built so that path order alone would give
 * the WRONG answer -- the file that must come first is deliberately named so
 * it sorts second. A fixture whose expected order happens to be alphabetical
 * cannot fail when the signal it names is deleted.</p>
 */
class ReadingPathTest {

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        return new UnifiedDiff.FileDiff(path, "M", added.length, 0, false, false,
                List.of(hunk(1, added)));
    }

    private static UnifiedDiff.Hunk hunk(int firstLine, String... added) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int n = firstLine;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.Hunk("@@", lines);
    }

    private static List<ReadingPath.Step> pathOf(UnifiedDiff diff, OutOfDiffFanIn.Result fanIn) {
        ChangeGraph graph = ChangeGraph.of(diff);
        return ReadingPath.of(diff, graph, Sections.of(diff, graph), fanIn);
    }

    private static List<String> filesOf(List<ReadingPath.Step> path) {
        return path.stream().map(ReadingPath.Step::file).distinct().toList();
    }

    private static OutOfDiffFanIn.Result fanIn(String symbol, int occurrences) {
        List<OutOfDiffFanIn.Occurrence> where = new ArrayList<>();
        for (int i = 0; i < occurrences; i++) {
            where.add(new OutOfDiffFanIn.Occurrence("other/caller.cpp", i + 1, "  use();"));
        }
        return new OutOfDiffFanIn.Result(Map.of(symbol, where), false);
    }

    private static final OutOfDiffFanIn.Result NO_FAN_IN =
            new OutOfDiffFanIn.Result(Map.of(), false);

    /**
     * The dependent is named so it sorts first AND is the top-ranked entry
     * point (it is the one with out-of-diff callers). Only the edge can put
     * the foundation first, so deleting the edge -- or handing {@code Graphs}
     * the nodes without them -- fails this.
     */
    @Test
    void theFoundationIsReadBeforeWhatUsesIt() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/aprofiler.cpp", "void hotEntry() { new JmpCtxScope(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))), fanIn("hotEntry", 4));

        assertEquals(List.of("src/guards.cpp", "src/aprofiler.cpp"), filesOf(path));
    }

    /** The first step and the entry point are the same step, by construction. */
    @Test
    void theFirstStepIsTheEntryPoint() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/aprofiler.cpp", "void hotEntry() { new JmpCtxScope(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))), fanIn("hotEntry", 4));

        assertTrue(path.get(0).entryPoint());
        assertTrue(path.stream().skip(1).noneMatch(ReadingPath.Step::entryPoint));
    }

    /**
     * Called from outside the change outranks everything else. {@code
     * src/zeta.cpp} sorts last and has in-degree 0; {@code src/internal.cpp}
     * sorts first and is referenced by a changed file. Without the fan-in
     * term, internal wins on both of the remaining signals.
     */
    @Test
    void outOfDiffFanInOutranksInDegree() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("src/internal.cpp", "class Internal { };"),
                file("src/user.cpp", "void u() { new Internal(); }"),
                file("src/zeta.cpp", "class PublicThing { };")));

        assertEquals("src/internal.cpp", pathOf(diff, NO_FAN_IN).get(0).file());
        assertEquals("src/zeta.cpp", pathOf(diff, fanIn("PublicThing", 1)).get(0).file());
    }

    /**
     * In-degree is a count, not a flag: two files that both have dependents
     * are still ordered by how many. {@code zbase.cpp} sorts last and carries
     * two, {@code mid.cpp} sorts first and carries one, and neither is a leaf
     * -- so the not-a-leaf signal cannot decide this one.
     */
    @Test
    void theWiderFoundationIsReadFirst() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/mid.cpp", "class Mid { };"),
                file("src/u1.cpp", "void u1() { new Base(); new Mid(); }"),
                file("src/u2.cpp", "void u2() { new Base(); }"),
                file("src/zbase.cpp", "class Base { };"))), NO_FAN_IN);

        assertEquals("src/zbase.cpp", path.get(0).file());
    }

    /**
     * With every §6.2 signal silent the rank degrades to today's fallback
     * order rather than to alphabetical chaos: production code before
     * configuration, even where the path says otherwise.
     */
    @Test
    void theKindOrderBreaksATieTheSignalsCannot() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/a.json", "{\"key\": 1}"),
                file("src/z.cpp", "class Zed { };"))), NO_FAN_IN);

        assertEquals("src/z.cpp", path.get(0).file());
    }

    /**
     * A tie-break for when the graph is silent, not an override of it: where
     * a test references changed code the edge already orders it. The test
     * path sorts FIRST here ({@code _} before {@code g}), so only the signal
     * can demote it.
     */
    @Test
    void aTestOnlySectionDoesNotBecomeTheEntryPoint() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/__tests__/unrelated_ut.cpp", "void t() { somethingElse(); }"),
                file("src/guards.cpp", "class JmpCtxScope { };"))), NO_FAN_IN);

        assertEquals("src/guards.cpp", path.get(0).file());
    }

    /**
     * The four §6.2 signals rank ahead of {@code FallbackIntents}' kind
     * order, which is only what the rank falls back to. Isolates the
     * not-a-test signal from the kind order, which would otherwise demote
     * every test on its own and leave the signal untestable: a vendored file
     * is GENERATED, which the kind order ranks BELOW tests, and it sorts
     * last as well.
     */
    @Test
    void theTestSignalOutranksTheFallbackKindOrder() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/__tests__/probe_ut.cpp", "void probe() { }"),
                file("src/vendor/lib.cpp", "class VendorThing { };"))), NO_FAN_IN);

        assertEquals("src/vendor/lib.cpp", path.get(0).file());
    }

    /** With every signal equal the tie-break is the path, and it is TOTAL. */
    @Test
    void withNothingToTellThemApartStepsFollowPathOrder() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/c.cpp", "class Cee { };"),
                file("src/a.cpp", "class Aye { };"),
                file("src/b.cpp", "class Bee { };"))), NO_FAN_IN);

        assertEquals(List.of("src/a.cpp", "src/b.cpp", "src/c.cpp"), filesOf(path));
    }

    @Test
    void aStepLinksToWhatCallsIt() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"))), NO_FAN_IN);

        ReadingPath.Step guards = stepFor(path, "src/guards.cpp");
        ReadingPath.Link link = guards.links().stream()
                .filter(candidate -> candidate.kind().equals("called by"))
                .findFirst().orElseThrow();
        assertEquals(ReviewIntent.hunkId("src/profiler.cpp", 0), link.targetHunkId());
        assertTrue(link.label().contains("profiler.cpp"), link.label());
        assertTrue(link.label().contains("JmpCtxScope"), link.label());
    }

    @Test
    void aStepLinksToWhatItCalls() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"))), NO_FAN_IN);

        ReadingPath.Step profiler = stepFor(path, "src/profiler.cpp");
        ReadingPath.Link link = profiler.links().stream()
                .filter(candidate -> candidate.kind().equals("calls"))
                .findFirst().orElseThrow();
        assertEquals(ReviewIntent.hunkId("src/guards.cpp", 0), link.targetHunkId());
        assertTrue(link.label().contains("guards.cpp:JmpCtxScope"), link.label());
    }

    /** Same-concept links name the symbol they share; a bare affinity says nothing. */
    @Test
    void sameConceptLinksNameTheSharedSymbol() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/a.cpp", "void a() { new JmpCtxScope(); }"),
                file("src/b.cpp", "void b() { new JmpCtxScope(); }"))), NO_FAN_IN);

        ReadingPath.Link shared = stepFor(path, "src/a.cpp").links().stream()
                .filter(link -> link.kind().equals("same concept"))
                .findFirst().orElseThrow();
        assertEquals(ReviewIntent.hunkId("src/b.cpp", 0), shared.targetHunkId());
        assertTrue(shared.label().contains("JmpCtxScope"), shared.label());
        assertTrue(shared.label().contains("b.cpp"), shared.label());
    }

    /**
     * Deduplicated by target hunk: {@code a.cpp} both calls {@code guards.cpp}
     * and shares {@code JmpCtxScope} with it, and that is one link, not two.
     */
    @Test
    void aTargetHunkIsLinkedOnce() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/a.cpp", "void a() { new JmpCtxScope(); }"),
                file("src/b.cpp", "void b() { new JmpCtxScope(); }"))), NO_FAN_IN);

        for (ReadingPath.Step step : path) {
            List<String> targets =
                    step.links().stream().map(ReadingPath.Link::targetHunkId).toList();
            assertEquals(targets.stream().distinct().toList(), targets, step.hunkId());
        }
    }

    /** Cross-file only: two hunks of one file are not a relationship. */
    @Test
    void aFileDoesNotLinkToItself() {
        UnifiedDiff.FileDiff both = new UnifiedDiff.FileDiff(
                "src/solo.cpp", "M", 2, 0, false, false,
                List.of(hunk(1, "class Solo { };"), hunk(40, "void use() { new Solo(); }")));

        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(both)), NO_FAN_IN);

        assertEquals(2, path.size());
        assertTrue(path.stream().allMatch(step -> step.links().isEmpty()));
    }

    @Test
    void everyHunkIsOnThePathExactlyOnce() {
        UnifiedDiff.FileDiff two = new UnifiedDiff.FileDiff(
                "src/profiler.cpp", "M", 2, 0, false, false,
                List.of(hunk(1, "void go() { new JmpCtxScope(); }"), hunk(40, "void stop() { }")));

        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"), two)), NO_FAN_IN);

        assertEquals(List.of(
                ReviewIntent.hunkId("src/guards.cpp", 0),
                ReviewIntent.hunkId("src/profiler.cpp", 0),
                ReviewIntent.hunkId("src/profiler.cpp", 1)),
                path.stream().map(ReadingPath.Step::hunkId).toList());
    }

    @Test
    void everyStepCarriesTheSectionItsHunkIsIn() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }")));
        ChangeGraph graph = ChangeGraph.of(diff);
        List<Sections.Section> sections = Sections.of(diff, graph);

        for (ReadingPath.Step step : ReadingPath.of(diff, graph, sections, NO_FAN_IN)) {
            int expected = 0;
            for (int index = 0; index < sections.size(); index++) {
                if (sections.get(index).hunkIds().contains(step.hunkId())) {
                    expected = index + 1;
                    break;
                }
            }
            assertNotEquals(0, expected, step.hunkId());
            assertEquals(expected, step.sectionNumber(), step.hunkId());
        }
    }

    @Test
    void everyStepStatesWhyItSitsWhereItDoes() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"))), NO_FAN_IN);

        assertTrue(path.stream().noneMatch(step -> step.reason().isBlank()));
    }

    /**
     * The reason points at the rail, in the rail's own notation (§7.1): the
     * foundation says which sections reference it, and what builds on it says
     * so the other way round.
     */
    @Test
    void theReasonNamesTheSectionsOnTheOtherEndOfTheEdge() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.cpp", "class JmpCtxScope { };"),
                file("src/profiler.cpp", "void go() { new JmpCtxScope(); }"))), NO_FAN_IN);

        String foundation = stepFor(path, "src/guards.cpp").reason();
        String dependent = stepFor(path, "src/profiler.cpp").reason();
        assertTrue(foundation.matches("referenced by [①-⑳](, [①-⑳])*"), foundation);
        assertTrue(dependent.matches("builds on [①-⑳](, [①-⑳])*"), dependent);
    }

    /**
     * "referenced by ①" on a row that is itself in ① tells a reviewer
     * nothing, and a section carries the files its unit depends on, so an
     * edge inside one section is the common case. The header and its
     * same-basename implementation are one section, and the reason names the
     * file instead.
     */
    @Test
    void aReasonNamesTheFileWhenTheEdgeStaysInsideOneSection() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/guards.h", "class JmpCtxScope { };"),
                file("src/guards.cpp", "void install() { new JmpCtxScope(); }"))), NO_FAN_IN);

        ReadingPath.Step header = stepFor(path, "src/guards.h");
        ReadingPath.Step implementation = stepFor(path, "src/guards.cpp");
        assertEquals(header.sectionNumber(), implementation.sectionNumber());
        assertEquals("referenced by guards.cpp", header.reason());
    }

    /** The reason names the count and the callers, not a bare "entry point". */
    @Test
    void theReasonNamesWhatCallsItFromOutside() {
        List<ReadingPath.Step> path = pathOf(new UnifiedDiff(List.of(
                file("src/api.cpp", "class PublicThing { };"))), fanIn("PublicThing", 7));

        assertTrue(path.get(0).reason().contains("7"), path.get(0).reason());
        assertTrue(path.get(0).reason().contains("outside the change"), path.get(0).reason());
    }

    /**
     * A scan that could not run is not a scan that found nothing. The reason
     * for a file with no in-diff references says so, rather than implying
     * that nothing outside the change uses it.
     */
    @Test
    void anUnavailableScanIsNotReadAsZero() {
        UnifiedDiff diff = new UnifiedDiff(List.of(file("src/lonely.cpp", "class Lonely { };")));

        String measured = pathOf(diff, NO_FAN_IN).get(0).reason();
        String unknown = pathOf(diff, new OutOfDiffFanIn.Result(Map.of(), true)).get(0).reason();

        assertFalse(measured.contains("unknown"), measured);
        assertTrue(unknown.contains("unknown"), unknown);
    }

    @Test
    void anEmptyDiffHasNoPath() {
        assertEquals(List.of(), pathOf(new UnifiedDiff(List.of()), NO_FAN_IN));
    }

    private static ReadingPath.Step stepFor(List<ReadingPath.Step> path, String file) {
        Optional<ReadingPath.Step> found =
                path.stream().filter(step -> step.file().equals(file)).findFirst();
        return found.orElseThrow();
    }
}
