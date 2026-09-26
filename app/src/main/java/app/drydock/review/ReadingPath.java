package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The order the change is read in, where to start, and what each hunk has to
 * do with the one before it (spec §6).
 *
 * <p><strong>Rank inside the sort, not after it.</strong> The entry-point
 * rank (§6.2) is handed to {@link Graphs#topologicalOrder} as its tie-break,
 * so it decides which of the units Kahn could emit next actually goes next.
 * Ordering first and marking second would let "the first card" and the
 * {@code START HERE} card disagree, and a {@code START HERE} badge sitting
 * on card 4 reads as a bug rather than as a design. Marking is therefore not
 * a pass at all: the first step emitted is the entry point, by
 * construction.</p>
 *
 * <p><strong>The rank, in full.</strong> Out-of-diff fan-in, then in-degree
 * within the changed set, then not-a-test, then {@link FallbackIntents}'
 * kind order, then the path. That is the whole chain: §6.2's not-a-leaf is
 * absent from it, for the reason two paragraphs down. The path is what
 * makes it TOTAL, and total is not a nicety here: {@code Graphs} keeps its ready set
 * in a {@code TreeSet} ordered by this comparator, so two distinct units
 * comparing equal would collapse into one and a unit would silently fall out
 * of the path (spec §9.5).</p>
 *
 * <p>Not-a-test is a tie-break for when the graph is silent rather than an
 * override of it: where a test references changed code the edge has already
 * placed it and this signal never runs, which leaves it deciding exactly the
 * case it should -- a test-only file with nothing pointing into it. It is
 * ranked ahead of the kind order deliberately (§6.1): the kind order is what
 * the rank degrades to, not one of its signals.</p>
 *
 * <p>§6.2's fourth signal, not-a-leaf, is NOT in the chain. At file
 * granularity a leaf is exactly in-degree zero, so the term ahead of it has
 * already decided every case it could decide -- proved analytically and then
 * empirically, by 300 generated diffs coming out byte-identical with it
 * removed. A comparator step that cannot discriminate asserts a distinction
 * that does not exist. It would become real only if "leaf" were redefined at
 * unit level, where a cycle's members each have in-degree from inside the
 * cycle while the unit as a whole is an endpoint.</p>
 *
 * <p><strong>One reading order, not two.</strong> {@link Sections} orders
 * its units by path, having no entry-point rank to consult. This class
 * orders by rank. A rail listing sections in the first order while badging
 * the entry point computed by the second puts START HERE on card 2 -- the
 * same failure the rank-inside-the-sort rule exists to prevent, one level
 * up. So {@link #of} returns the section order its own path implies
 * together with the numbering that indexes into it, and there is nothing
 * left for a consumer to reconcile.</p>
 *
 * <p><strong>Links are per hunk, on both ends.</strong> A link renders as a
 * footer row beneath one hunk (§7.2), so a file-level answer spread over a
 * file's hunks would put "calls guards.cpp" under hunks that call nothing --
 * a false statement about a specific hunk, which is the one thing a surface
 * built on true markers may not ship. {@link ChangeGraph.Hunk} carries the
 * finer view and {@link SymbolScan.Symbol} the hunk index it is built
 * from.</p>
 *
 * <p>{@link #of} is string work over an already-built graph, but {@link
 * ChangeGraph#of} is blocking -- it parses every changed file and can
 * trigger a first-time native grammar load -- so the pair belongs off the FX
 * thread.</p>
 */
public final class ReadingPath {

    /** A changed symbol this hunk's symbols reference. */
    public static final String CALLS = "calls";

    /** A changed symbol that references this hunk's symbols. */
    public static final String CALLED_BY = "called by";

    /** A hunk sharing a changed symbol with this one, neither calling the other. */
    public static final String SAME_CONCEPT = "same concept";

    /** Above this the circled glyphs run out and the number is spelled. */
    private static final int LAST_CIRCLED = 20;

    private static final char FIRST_CIRCLED = '①';

    private ReadingPath() {
    }

    /**
     * One relationship between two hunks in different files. {@code label}
     * names files and symbols ({@code ③ SessionReviewScopes.java}) and never
     * a raw hunk id -- the id is what the surface acts on, not what it shows.
     */
    public record Link(String kind, String targetHunkId, String label, Provenance provenance) {
        public Link {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetHunkId, "targetHunkId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(provenance, "provenance");
        }

        /**
         * A link with no warrant named is MEASURED: everything this class
         * builds is (spec §6.4 -- links are facts about the diff, computed
         * whoever grouped it). Callers naming it explicitly are the ones that
         * could ever differ.
         */
        public Link(String kind, String targetHunkId, String label) {
            this(kind, targetHunkId, label, Provenance.MEASURED);
        }
    }

    /**
     * One hunk, in reading order. {@code sectionNumber} is the 1-based place
     * in {@link Path#sections()} of the first section carrying this hunk --
     * sections overlap by design (§5.6), and a step names the one a reviewer
     * meets first. {@code entryPoint} is true for the first step and no
     * other.
     */
    public record Step(String hunkId, String file, int sectionNumber, String reason,
                       List<Link> links, boolean entryPoint, Provenance provenance) {
        public Step {
            Objects.requireNonNull(hunkId, "hunkId");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(provenance, "provenance");
            links = List.copyOf(links);
        }

        /** See {@link Link#Link(String, String, String)} -- a step is measured too. */
        public Step(String hunkId, String file, int sectionNumber, String reason,
                    List<Link> links, boolean entryPoint) {
            this(hunkId, file, sectionNumber, reason, links, entryPoint, Provenance.MEASURED);
        }
    }

    /**
     * The path: its hunks in reading order, and the sections in the order the
     * path reaches them.
     *
     * <p>Both, from one call, because there is no such thing as two reading
     * orders. {@link Sections} orders its units by path -- it has no
     * entry-point rank to consult -- and this class orders by rank, so a rail
     * listing sections in {@code Sections} order while badging the entry
     * point would put START HERE on card 2. That is the exact failure the
     * rank-inside-the-sort rule exists to prevent, one level up. Returning
     * the ordering together with the numbering that indexes into it leaves a
     * consumer nothing to reconcile: render {@link #sections()} down the
     * rail, and {@code step.sectionNumber()} is its 1-based place there,
     * which is also the number every reason and label mints.</p>
     */
    public record Path(List<Step> steps, List<Sections.Section> sections) {
        public Path {
            steps = List.copyOf(steps);
            sections = List.copyOf(sections);
        }
    }

    /**
     * {@code diff}'s hunks in reading order, and {@code sections} in the
     * order that path reaches them. Blocking only in the sense its inputs
     * are; never call the {@link ChangeGraph#of} that feeds it on the FX
     * thread.
     *
     * <p>{@code fanIn.unavailable()} is honoured rather than read as zero: a
     * scan that could not run contributes no rank, and the reason it writes
     * says the outside callers are unknown instead of implying there are
     * none.</p>
     */
    public static Path of(UnifiedDiff diff, ChangeGraph graph,
                          List<Sections.Section> sections, OutOfDiffFanIn.Result fanIn) {
        Map<String, UnifiedDiff.FileDiff> byPath = new TreeMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            byPath.put(file.path(), file);
        }
        SortedSet<String> nodes = new TreeSet<>(byPath.keySet());
        if (nodes.isEmpty()) {
            return new Path(List.of(), List.copyOf(sections));
        }

        Map<String, Integer> fanInByFile = fanInByFile(graph, fanIn);
        Comparator<String> rank = rank(graph, fanInByFile);
        List<List<String>> units =
                Graphs.topologicalOrder(nodes, file -> dependencies(graph, nodes, file), rank);

        // Hunk order first, because the section order is read off it, and the
        // numbering off that.
        List<String> hunkIds = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (List<String> unit : units) {
            for (String file : unit) {
                UnifiedDiff.FileDiff fileDiff = byPath.get(file);
                if (fileDiff == null) {
                    continue;
                }
                files.add(file);
                for (int index = 0; index < fileDiff.hunks().size(); index++) {
                    hunkIds.add(ReviewIntent.hunkId(file, index));
                }
            }
        }
        List<Sections.Section> ordered = sectionOrder(sections, hunkIds);
        Map<String, Integer> sectionByHunk = sectionNumbers(ordered);

        List<Step> steps = new ArrayList<>();
        for (String file : files) {
            UnifiedDiff.FileDiff fileDiff = byPath.get(file);
            String reason = reasonFor(file, graph, byPath, sectionByHunk,
                    fanInByFile.getOrDefault(file, 0), fanIn.unavailable());
            for (int index = 0; index < fileDiff.hunks().size(); index++) {
                String hunkId = ReviewIntent.hunkId(file, index);
                List<Link> links = linksFrom(new ChangeGraph.Hunk(file, index), graph,
                        byPath, sectionByHunk);
                steps.add(new Step(hunkId, file, sectionByHunk.getOrDefault(hunkId, 0),
                        reason, links, steps.isEmpty(), Provenance.MEASURED));
            }
        }
        return new Path(steps, ordered);
    }

    // ---- order --------------------------------------------------------------

    /**
     * The entry-point rank (§6.2), as a TOTAL comparator over changed files:
     * fan-in from outside the change, then in-degree within it, then
     * not-a-test, then the kind order, then the path. §6.2's not-a-leaf is
     * absent on purpose: see the class javadoc.
     *
     * <p>Counts are negated rather than reversed so the whole chain reads in
     * one direction: smaller is earlier.</p>
     */
    private static Comparator<String> rank(ChangeGraph graph, Map<String, Integer> fanInByFile) {
        return Comparator
                .comparingInt((String file) -> -fanInByFile.getOrDefault(file, 0))
                .thenComparingInt(file -> -graph.filesReferencing(file).size())
                .thenComparingInt(file -> FallbackIntents.isTestPath(file) ? 1 : 0)
                .thenComparingInt(
                        file -> FallbackIntents.readingOrder(FallbackIntents.kindOf(file)))
                .thenComparing(Comparator.naturalOrder());
    }

    /**
     * What {@code file} has to be read after. Intersected with {@code nodes}
     * on the way out: {@link Graphs#topologicalOrder} rejects an edge that
     * leaves the node set, and a graph built from a different diff than the
     * one being walked would otherwise take the whole path down.
     */
    private static SortedSet<String> dependencies(ChangeGraph graph, SortedSet<String> nodes,
                                                  String file) {
        SortedSet<String> targets = new TreeSet<>(graph.filesReferencedBy(file));
        targets.retainAll(nodes);
        return targets;
    }

    /**
     * How many places outside the change use each file's changed
     * declarations. Iterated over the graph's sorted declarations rather than
     * over {@code fanIn.bySymbol()}, whose iteration order is the caller's to
     * choose and therefore not something determinism may rest on.
     */
    private static Map<String, Integer> fanInByFile(ChangeGraph graph,
                                                    OutOfDiffFanIn.Result fanIn) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String symbol : graph.changedDeclarations()) {
            List<OutOfDiffFanIn.Occurrence> occurrences = fanIn.bySymbol().get(symbol);
            if (occurrences == null || occurrences.isEmpty()) {
                continue;
            }
            graph.fileDeclaring(symbol)
                    .ifPresent(file -> counts.merge(file, occurrences.size(), Integer::sum));
        }
        return counts;
    }

    // ---- reasons ------------------------------------------------------------

    /**
     * Why this file sits where it does, in the words §7.1 puts on the row.
     * Ordered as the rank is, so the reason names the signal that actually
     * placed it.
     */
    private static String reasonFor(String file, ChangeGraph graph,
                                    Map<String, UnifiedDiff.FileDiff> byPath,
                                    Map<String, Integer> sectionByHunk,
                                    int fanIn, boolean fanInUnavailable) {
        if (fanIn > 0) {
            return "called from " + fanIn + (fanIn == 1 ? " place" : " places")
                    + " outside the change";
        }
        int own = sectionOfFile(file, byPath, sectionByHunk);
        SortedSet<String> dependents = graph.filesReferencing(file);
        if (!dependents.isEmpty()) {
            return "referenced by " + markers(dependents, own, byPath, sectionByHunk);
        }
        SortedSet<String> dependencies = graph.filesReferencedBy(file);
        if (!dependencies.isEmpty()) {
            return "builds on " + markers(dependencies, own, byPath, sectionByHunk);
        }
        String silence = FallbackIntents.isTestPath(file)
                ? "test, referenced by nothing in the change"
                : "nothing in the change references it";
        // An unavailable scan is not a scan that found nothing (§4.3): this
        // is the one reason that would otherwise read as "and nothing outside
        // it either", which was never measured.
        return fanInUnavailable ? silence + ", outside callers unknown" : silence;
    }

    /**
     * {@code ③, ⑤} for a set of files: where they sit in the rail.
     *
     * <p>A file in {@code own} -- the section the reason is being written for
     * -- is named instead of numbered. "referenced by ①" on a row that is
     * itself in ① tells a reviewer nothing, and sections carry the files
     * their unit depends on (§5.2), so an edge inside one section is the
     * common case rather than the corner. Naming is also the fallback when a
     * section number cannot be had at all, so a reason is never a bare
     * count.</p>
     */
    private static String markers(SortedSet<String> files, int own,
                                  Map<String, UnifiedDiff.FileDiff> byPath,
                                  Map<String, Integer> sectionByHunk) {
        Set<String> rendered = new LinkedHashSet<>();
        for (String file : files) {
            int number = sectionOfFile(file, byPath, sectionByHunk);
            rendered.add(number > 0 && number != own
                    ? marker(number)
                    : FallbackIntents.fileName(file));
        }
        return String.join(", ", rendered);
    }

    // ---- links --------------------------------------------------------------

    /**
     * {@code hunk}'s links, cross-file and deduplicated by target hunk.
     *
     * <p>Per hunk, not per file. A link renders as a footer row beneath one
     * hunk (§7.2), so "calls guards.cpp" under a hunk that references
     * nothing is a false statement about that hunk -- not a loose one about
     * the file -- and this surface is worth having only while its markers
     * state true things.</p>
     *
     * <p>Kinds are emitted in a fixed order -- calls, called by, same
     * concept -- and the first one to claim a target hunk keeps it. That is
     * what "deduplicated by target hunk" has to mean for a pair that is
     * both: the call is the more specific thing to say. It is also why
     * same-concept ends up meaning what §2.2 wants -- two hunks that use the
     * same thing, neither declaring it -- rather than restating every edge.
     * Two hunks that genuinely reference each other therefore show one link
     * rather than two; the cycle that makes is named by its section (§6.1),
     * which is where a mutual dependency belongs on this surface.</p>
     */
    private static List<Link> linksFrom(ChangeGraph.Hunk hunk, ChangeGraph graph,
                                        Map<String, UnifiedDiff.FileDiff> byPath,
                                        Map<String, Integer> sectionByHunk) {
        SortedSet<String> declared = graph.declarationsIn(hunk);
        SortedSet<String> referenced = graph.referencesIn(hunk);

        Map<ChangeGraph.Hunk, SortedSet<String>> calls = new TreeMap<>();
        for (String symbol : referenced) {
            for (ChangeGraph.Hunk target : graph.hunksDeclaring(symbol)) {
                calls.computeIfAbsent(target, key -> new TreeSet<>()).add(symbol);
            }
        }
        Map<ChangeGraph.Hunk, SortedSet<String>> calledBy = new TreeMap<>();
        for (String symbol : declared) {
            for (ChangeGraph.Hunk source : graph.hunksReferencingSymbol(symbol)) {
                calledBy.computeIfAbsent(source, key -> new TreeSet<>()).add(symbol);
            }
        }
        // A hunk touches a symbol by declaring it or by referencing it; an
        // unresolvable name touches nothing, because neither lookup below
        // knows it -- the same test an edge passes (§4.2).
        Map<ChangeGraph.Hunk, SortedSet<String>> shared = new TreeMap<>();
        SortedSet<String> touched = new TreeSet<>(declared);
        touched.addAll(referenced);
        for (String symbol : touched) {
            SortedSet<ChangeGraph.Hunk> touching = new TreeSet<>(graph.hunksDeclaring(symbol));
            touching.addAll(graph.hunksReferencingSymbol(symbol));
            for (ChangeGraph.Hunk other : touching) {
                if (!other.file().equals(hunk.file())) {
                    shared.computeIfAbsent(other, key -> new TreeSet<>()).add(symbol);
                }
            }
        }

        List<Link> links = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        // The symbol is declared in the target, so the label may point at it.
        emit(links, claimed, byPath, sectionByHunk, CALLS, calls, graph, ":");
        // The symbols live HERE, not in the target, so the label says what
        // the target does with them rather than pointing into it.
        emit(links, claimed, byPath, sectionByHunk, CALLED_BY, calledBy, graph, " · uses ");
        emit(links, claimed, byPath, sectionByHunk, SAME_CONCEPT, shared, graph,
                " · both touch ");
        return List.copyOf(links);
    }

    private static void emit(List<Link> links, Set<String> claimed,
                             Map<String, UnifiedDiff.FileDiff> byPath,
                             Map<String, Integer> sectionByHunk, String kind,
                             Map<ChangeGraph.Hunk, SortedSet<String>> targets,
                             ChangeGraph graph, String relation) {
        for (Map.Entry<ChangeGraph.Hunk, SortedSet<String>> target : targets.entrySet()) {
            ChangeGraph.Hunk to = target.getKey();
            UnifiedDiff.FileDiff targetDiff = byPath.get(to.file());
            if (targetDiff == null || to.index() >= targetDiff.hunks().size()) {
                // Nothing to click through to; a link to no hunk is a dead row.
                continue;
            }
            String hunkId = ReviewIntent.hunkId(to.file(), to.index());
            if (!claimed.add(hunkId)) {
                continue;
            }
            String marker = marker(sectionByHunk.getOrDefault(hunkId, 0));
            String label = (marker.isEmpty() ? "" : marker + " ")
                    + FallbackIntents.fileName(to.file())
                    + relation + best(target.getValue(), graph);
            links.add(new Link(kind, hunkId, label, Provenance.MEASURED));
        }
    }

    /**
     * Which of several shared names to put on one label: the one the most
     * changed files reference, then the name itself. A label has room for one
     * symbol, and the one the relationship is most about is the useful one --
     * picking alphabetically would name whichever happened to sort first.
     */
    private static String best(SortedSet<String> symbols, ChangeGraph graph) {
        String chosen = null;
        int fanIn = -1;
        for (String symbol : symbols) {
            int uses = graph.filesReferencingSymbol(symbol).size();
            if (uses > fanIn) {
                chosen = symbol;
                fanIn = uses;
            }
        }
        return chosen;
    }

    // ---- sections -----------------------------------------------------------

    /**
     * {@code sections} in the order the path first reaches them.
     *
     * <p>A section's place is decided by its earliest hunk in the path, so
     * the entry point's section is card 1 and START HERE sits on it in both
     * dimensions -- the same construction that makes the first STEP the entry
     * point. Ties go to the order {@link Sections} produced, so two sections
     * first reached by the same hunk keep their relative order. A section the
     * path never reaches -- one carrying no hunk of this diff -- is appended
     * rather than dropped: a card falling out of the rail is worse than one
     * sitting at the end of it.</p>
     */
    private static List<Sections.Section> sectionOrder(List<Sections.Section> sections,
                                                       List<String> hunkIds) {
        Map<String, SortedSet<Integer>> carrying = new TreeMap<>();
        for (int index = 0; index < sections.size(); index++) {
            for (String hunkId : sections.get(index).hunkIds()) {
                carrying.computeIfAbsent(hunkId, key -> new TreeSet<>()).add(index);
            }
        }
        Set<Integer> placed = new LinkedHashSet<>();
        for (String hunkId : hunkIds) {
            SortedSet<Integer> here = carrying.get(hunkId);
            if (here != null) {
                placed.addAll(here);
            }
        }
        for (int index = 0; index < sections.size(); index++) {
            placed.add(index);
        }
        List<Sections.Section> ordered = new ArrayList<>();
        for (Integer index : placed) {
            ordered.add(sections.get(index));
        }
        return ordered;
    }

    /**
     * Each hunk's section number, 1-based over the READING order. Sections
     * overlap (§5.6), so a hunk can be in several; the one the reviewer meets
     * first wins.
     */
    private static Map<String, Integer> sectionNumbers(List<Sections.Section> sections) {
        Map<String, Integer> numbers = new TreeMap<>();
        for (int index = 0; index < sections.size(); index++) {
            for (String hunkId : sections.get(index).hunkIds()) {
                numbers.putIfAbsent(hunkId, index + 1);
            }
        }
        return numbers;
    }

    private static int sectionOfFile(String file, Map<String, UnifiedDiff.FileDiff> byPath,
                                     Map<String, Integer> sectionByHunk) {
        UnifiedDiff.FileDiff fileDiff = byPath.get(file);
        if (fileDiff == null || fileDiff.hunks().isEmpty()) {
            return 0;
        }
        return sectionByHunk.getOrDefault(ReviewIntent.hunkId(file, 0), 0);
    }

    /**
     * {@code ③} for 3. Past the twenty glyphs Unicode circles, {@code #21} --
     * a rail that long is not the case this notation is for, and inventing a
     * fallback glyph would be worse than saying the number.
     */
    private static String marker(int number) {
        if (number <= 0) {
            return "";
        }
        return number <= LAST_CIRCLED
                ? String.valueOf((char) (FIRST_CIRCLED + number - 1))
                : "#" + number;
    }
}
