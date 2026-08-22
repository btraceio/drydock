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
 * within the changed set, then not-a-test, then not-a-leaf, then {@link
 * FallbackIntents}' kind order, then the path. The path is what makes it
 * TOTAL, and total is not a nicety here: {@code Graphs} keeps its ready set
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
 * <p>Not-a-leaf is, at file granularity, exactly "in-degree is zero", so the
 * signal ahead of it has already decided every case it could decide. It is
 * written out anyway because it is one of the four signals §6.2 names, and a
 * chain that reads like the spec is worth more than one comparator step
 * saved; see the task report for the finding.</p>
 *
 * <p><strong>Links are file-level.</strong> {@link SymbolScan.Symbol} does
 * not carry a line, so nothing here can tell which hunk of a file a symbol
 * sits in. Every hunk of a file therefore carries that file's links, and a
 * link points at the first hunk of its target file. Narrowing this needs a
 * line on {@code Symbol}, not a guess here.</p>
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
    public record Link(String kind, String targetHunkId, String label) {
        public Link {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetHunkId, "targetHunkId");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * One hunk, in reading order. {@code sectionNumber} is the 1-based place
     * in the rail of the first section carrying this hunk -- sections overlap
     * by design (§5.6), and a step names the one a reviewer meets first.
     * {@code entryPoint} is true for the first step and no other.
     */
    public record Step(String hunkId, String file, int sectionNumber, String reason,
                       List<Link> links, boolean entryPoint) {
        public Step {
            Objects.requireNonNull(hunkId, "hunkId");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(reason, "reason");
            links = List.copyOf(links);
        }
    }

    /**
     * {@code diff}'s hunks in reading order. Blocking only in the sense its
     * inputs are; never call the {@link ChangeGraph#of} that feeds it on the
     * FX thread.
     *
     * <p>{@code fanIn.unavailable()} is honoured rather than read as zero: a
     * scan that could not run contributes no rank, and the reason it writes
     * says the outside callers are unknown instead of implying there are
     * none.</p>
     */
    public static List<Step> of(UnifiedDiff diff, ChangeGraph graph,
                                List<Sections.Section> sections, OutOfDiffFanIn.Result fanIn) {
        Map<String, UnifiedDiff.FileDiff> byPath = new TreeMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            byPath.put(file.path(), file);
        }
        SortedSet<String> nodes = new TreeSet<>(byPath.keySet());
        if (nodes.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> sectionByHunk = sectionNumbers(sections);
        Map<String, Integer> fanInByFile = fanInByFile(graph, fanIn);
        Map<String, Map<String, SortedSet<String>>> concepts = concepts(graph);
        Comparator<String> rank = rank(graph, fanInByFile);

        List<List<String>> units =
                Graphs.topologicalOrder(nodes, file -> dependencies(graph, nodes, file), rank);

        List<Step> steps = new ArrayList<>();
        for (List<String> unit : units) {
            for (String file : unit) {
                UnifiedDiff.FileDiff fileDiff = byPath.get(file);
                if (fileDiff == null) {
                    continue;
                }
                List<Link> links = linksFrom(file, graph, concepts, byPath, sectionByHunk);
                String reason = reasonFor(file, graph, byPath, sectionByHunk,
                        fanInByFile.getOrDefault(file, 0), fanIn.unavailable());
                for (int hunk = 0; hunk < fileDiff.hunks().size(); hunk++) {
                    String hunkId = ReviewIntent.hunkId(file, hunk);
                    steps.add(new Step(hunkId, file, sectionByHunk.getOrDefault(hunkId, 0),
                            reason, links, steps.isEmpty()));
                }
            }
        }
        return List.copyOf(steps);
    }

    // ---- order --------------------------------------------------------------

    /**
     * The entry-point rank (§6.2), as a TOTAL comparator over changed files:
     * fan-in from outside the change, then in-degree within it, then
     * not-a-test, then not-a-leaf, then the kind order, then the path.
     *
     * <p>Counts are negated rather than reversed so the whole chain reads in
     * one direction: smaller is earlier.</p>
     */
    private static Comparator<String> rank(ChangeGraph graph, Map<String, Integer> fanInByFile) {
        return Comparator
                .comparingInt((String file) -> -fanInByFile.getOrDefault(file, 0))
                .thenComparingInt(file -> -graph.filesReferencing(file).size())
                .thenComparingInt(file -> FallbackIntents.isTestPath(file) ? 1 : 0)
                .thenComparingInt(file -> graph.filesReferencing(file).isEmpty() ? 1 : 0)
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
     * {@code file}'s links, cross-file and deduplicated by target hunk.
     *
     * <p>Kinds are emitted in a fixed order -- calls, called by, same concept
     * -- and the first one to claim a target hunk keeps it. That is what
     * "deduplicated by target hunk" has to mean for a pair that is both: a
     * file that calls another and shares its symbol is one relationship, and
     * the call is the more specific thing to say about it. It is also why
     * same-concept ends up meaning what §2.2 wants -- two files that use the
     * same thing without either defining it -- rather than restating every
     * edge.</p>
     */
    private static List<Link> linksFrom(String file, ChangeGraph graph,
                                        Map<String, Map<String, SortedSet<String>>> concepts,
                                        Map<String, UnifiedDiff.FileDiff> byPath,
                                        Map<String, Integer> sectionByHunk) {
        List<Link> links = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();

        for (String target : graph.filesReferencedBy(file)) {
            // The symbols target declares that file uses: a real location in
            // the target, so the label may point at it.
            String symbol = best(sharedBetween(graph, target, file), graph);
            addLink(links, claimed, byPath, sectionByHunk, CALLS, target,
                    symbol == null ? "" : ":" + symbol);
        }
        for (String source : graph.filesReferencing(file)) {
            // The symbols file declares that source uses. They live HERE, not
            // in the target, so the label says what the target does with them
            // rather than pointing into it.
            String symbol = best(sharedBetween(graph, file, source), graph);
            addLink(links, claimed, byPath, sectionByHunk, CALLED_BY, source,
                    symbol == null ? "" : " · uses " + symbol);
        }
        Map<String, SortedSet<String>> sharedWith =
                concepts.getOrDefault(file, Map.of());
        for (Map.Entry<String, SortedSet<String>> shared : sharedWith.entrySet()) {
            String symbol = best(shared.getValue(), graph);
            addLink(links, claimed, byPath, sectionByHunk, SAME_CONCEPT, shared.getKey(),
                    " · both touch " + symbol);
        }
        return List.copyOf(links);
    }

    private static void addLink(List<Link> links, Set<String> claimed,
                                Map<String, UnifiedDiff.FileDiff> byPath,
                                Map<String, Integer> sectionByHunk,
                                String kind, String target, String suffix) {
        UnifiedDiff.FileDiff targetDiff = byPath.get(target);
        if (targetDiff == null || targetDiff.hunks().isEmpty()) {
            // Nothing to click through to; a link to no hunk is a dead row.
            return;
        }
        String hunkId = ReviewIntent.hunkId(target, 0);
        if (!claimed.add(hunkId)) {
            return;
        }
        String marker = marker(sectionByHunk.getOrDefault(hunkId, 0));
        String label = (marker.isEmpty() ? "" : marker + " ")
                + FallbackIntents.fileName(target) + suffix;
        links.add(new Link(kind, hunkId, label));
    }

    /** The changed names {@code declarer} declares and {@code user} references. */
    private static SortedSet<String> sharedBetween(ChangeGraph graph, String declarer,
                                                  String user) {
        SortedSet<String> shared = new TreeSet<>();
        for (String symbol : graph.declarationsIn(declarer)) {
            if (graph.filesReferencingSymbol(symbol).contains(user)) {
                shared.add(symbol);
            }
        }
        return shared;
    }

    /**
     * For each changed file, every other changed file it shares a changed
     * symbol with and the names they share. A file touches a symbol by
     * declaring it or by referencing it; the name has to be uniquely declared
     * in the scope, which {@link ChangeGraph#changedDeclarations()} already
     * guarantees -- the same test an edge passes (§4.2), so an ambiguous name
     * links nothing.
     *
     * <p>Built once for the whole change rather than per file: the question
     * is symmetric, and asking it file by file re-walks every changed
     * declaration once per changed file.</p>
     */
    private static Map<String, Map<String, SortedSet<String>>> concepts(ChangeGraph graph) {
        Map<String, Map<String, SortedSet<String>>> byFile = new TreeMap<>();
        for (String symbol : graph.changedDeclarations()) {
            SortedSet<String> touching = new TreeSet<>(graph.filesReferencingSymbol(symbol));
            graph.fileDeclaring(symbol).ifPresent(touching::add);
            for (String file : touching) {
                for (String other : touching) {
                    if (!other.equals(file)) {
                        byFile.computeIfAbsent(file, key -> new TreeMap<>())
                                .computeIfAbsent(other, key -> new TreeSet<>())
                                .add(symbol);
                    }
                }
            }
        }
        return byFile;
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
     * Each hunk's section number, 1-based. Sections overlap (§5.6), so a hunk
     * can be in several; the first one wins, which is the one the reviewer
     * meets first in the rail.
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
