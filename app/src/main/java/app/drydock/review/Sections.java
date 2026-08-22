package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The change's sections: units of the file-level reference graph in
 * dependency order, each carrying the foundation it is read against
 * (spec §5.2).
 *
 * <p>The failure this replaces, measured on a real C++ change: cards reading
 * {@code main/cpp · 12 files}, {@code test/cpp · 4 files},
 * {@code cpp/hotspot · 6 files}. Each individually correct, the rail as a
 * whole saying nothing, because (kind, directory) has no structural input at
 * all.</p>
 *
 * <p><strong>How a section is formed.</strong> Three edge kinds go in. A
 * <em>reference</em> edge (from {@link ChangeGraph}) and an <em>include</em>
 * edge are directed: the file that names another depends on it. Two
 * <em>conventions</em> are symmetric, and being symmetric is what makes them
 * merge rather than merely relate -- a file and its same-basename
 * counterpart, and a file that declares no changed symbol of its own but is
 * pulled in by exactly one changed file (the {@code counters.h} case).
 * {@link Graphs#topologicalOrder} then condenses that graph: a symmetric
 * pair is one unit because each is the other's prerequisite, and genuine
 * mutual references collapse the same way, which is why {@link
 * Section#cycleWith()} is recomputed from the directed edges alone rather
 * than read off the unit -- a convention-joined pair is one thing, not a
 * cycle, and telling a reviewer otherwise is a lie they would act on.</p>
 *
 * <p><strong>Sections overlap.</strong> A section carries the files its own
 * members depend on, so a shared header appears in every section that needs
 * it to be understood; with disjoint membership one of those would have to
 * lose. Dependents are deliberately NOT pulled in: a change cannot be read
 * without its foundation, but it can be read without knowing who calls it,
 * and every caller gets its own section further down the rail. The reviewed
 * flag is keyed to hunk content, so a file shown three times is still read
 * once (spec §5.6, §9).</p>
 *
 * <p>Tests are NOT split out. A test references the symbol under test, so
 * the graph already places it; splitting on {@code /test/} would be a path
 * heuristic drawing a boundary through a structurally sound group, which is
 * the very failure this class replaces.</p>
 *
 * <p>{@link #of} itself is string work over an already-built graph, but
 * {@link ChangeGraph#of} is blocking (it parses every changed file and can
 * trigger a first-time native grammar load), so the pair belongs off the FX
 * thread.</p>
 */
public final class Sections {

    /** One section. {@code cycleWith} is non-empty when it is part of a dependency cycle. */
    public record Section(String title, List<String> files, List<String> hunkIds,
                          Optional<String> hubSymbol, List<String> cycleWith) {
        public Section {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(hubSymbol, "hubSymbol");
            files = List.copyOf(files);
            hunkIds = List.copyOf(hunkIds);
            cycleWith = List.copyOf(cycleWith);
        }
    }

    /**
     * A C/C++ include. Anchored at the start of the line so a {@code //}
     * comment, a doc block's {@code *} margin and a string literal cannot
     * match; the captured token is a path, extension and all.
     */
    private static final Pattern INCLUDE =
            Pattern.compile("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]");

    /**
     * A quoted module: JavaScript and TypeScript's {@code from './x'},
     * {@code require('./x')} and bare {@code import './x'}. The keyword has
     * to sit immediately before the quote, so prose naming a file does not
     * match.
     */
    private static final Pattern QUOTED_MODULE =
            Pattern.compile("(?:\\bfrom|\\brequire\\s*\\(|^\\s*import)\\s*[\"']([^\"']+)[\"']");

    /**
     * A dotted or {@code ::}-separated module: Java/Kotlin {@code import},
     * Python {@code import}/{@code from}, Rust {@code use}/{@code mod}.
     * Anchored, and the token must start like an identifier so the quoted
     * forms above fall to {@link #QUOTED_MODULE} instead.
     */
    private static final Pattern SYMBOLIC_MODULE =
            Pattern.compile("^\\s*(?:import|from|use|mod)\\s+([A-Za-z_$][\\w.:$]*)");

    private Sections() {
    }

    /** {@code diff}'s sections, in reading order. */
    public static List<Section> of(UnifiedDiff diff, ChangeGraph graph) {
        SortedSet<String> nodes = new TreeSet<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            nodes.add(file.path());
        }

        Map<String, SortedSet<String>> includes = includeEdges(diff, nodes);
        Map<String, SortedSet<String>> depends = dependencyEdges(graph, nodes, includes);
        Map<String, SortedSet<String>> merges = conventionEdges(graph, nodes, includes);

        if (isEmpty(depends) && isEmpty(merges)) {
            // Nothing structural to consult: today's (kind, directory)
            // clustering is still the best available guess, and saying so is
            // better than inventing structure that is not there.
            return fromFallback(diff);
        }

        Function<String, SortedSet<String>> dependsOn = file -> {
            SortedSet<String> all = new TreeSet<>(depends.get(file));
            all.addAll(merges.get(file));
            return all;
        };
        List<List<String>> units =
                Graphs.topologicalOrder(nodes, dependsOn, Comparator.naturalOrder());

        // Reading position: where each file's own unit sits in the rail. A
        // section lists its files by this, not alphabetically, so the file
        // being depended on is read before the file using it.
        Map<String, Integer> position = new TreeMap<>();
        for (int index = 0; index < units.size(); index++) {
            for (String file : units.get(index)) {
                position.put(file, index);
            }
        }
        Comparator<String> readingOrder = (left, right) -> {
            int byUnit = Integer.compare(position.get(left), position.get(right));
            return byUnit != 0 ? byUnit : left.compareTo(right);
        };

        Map<String, UnifiedDiff.FileDiff> byPath = new TreeMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            byPath.put(file.path(), file);
        }

        List<Section> sections = new ArrayList<>();
        for (List<String> unit : units) {
            SortedSet<String> members = new TreeSet<>(unit);
            for (String file : unit) {
                members.addAll(depends.get(file));
            }
            List<String> ordered = new ArrayList<>(members);
            ordered.sort(readingOrder);
            Optional<String> hub = hubOf(unit, graph);
            sections.add(new Section(
                    title(ordered, hub),
                    ordered,
                    hunkIdsOf(byPath, ordered),
                    hub,
                    cyclesIn(unit, depends)));
        }
        return readable(sections);
    }

    /**
     * The rail, minus the cards that say nothing. A unit with no symbol to
     * name it -- a header declaring nothing of its own, pulled in by two
     * changed files, so neither may claim it -- would otherwise get a card
     * titled after its directory, which is the exact failure this class
     * replaces, sitting next to the sections that already carry the file.
     * It is dropped only when some other section carries all of it, so no
     * hunk can fall out of the rail; of two sections carrying the same
     * files, the one that reads first is the one kept.
     */
    private static List<Section> readable(List<Section> sections) {
        List<Section> visible = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            if (section.hubSymbol().isEmpty() && coveredByAnother(sections, index)) {
                continue;
            }
            visible.add(section);
        }
        return List.copyOf(visible);
    }

    private static boolean coveredByAnother(List<Section> sections, int index) {
        List<String> files = sections.get(index).files();
        for (int other = 0; other < sections.size(); other++) {
            if (other == index || !sections.get(other).files().containsAll(files)) {
                continue;
            }
            if (sections.get(other).files().size() > files.size() || other < index) {
                return true;
            }
        }
        return false;
    }

    // ---- edges --------------------------------------------------------------

    /**
     * Which changed files each file pulls in by name. This is what puts a
     * header with no changed symbol of its own in the right section, so it
     * has to recognise a dependency rather than a mention: only a line
     * SHAPED like an include or an import counts, and the name it carries
     * has to match the whole of the other file's name, never a substring of
     * it.
     */
    private static Map<String, SortedSet<String>> includeEdges(UnifiedDiff diff,
                                                               SortedSet<String> nodes) {
        Map<String, SortedSet<String>> result = emptyEdges(nodes);
        for (UnifiedDiff.FileDiff file : diff.files()) {
            SortedSet<String> named = result.get(file.path());
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    // Deleted include lines count too: a dependency being
                    // removed is part of the same piece of work as what
                    // replaced it, and dropping it would strand the file.
                    for (Reference reference : referencesOn(line.text())) {
                        for (String other : nodes) {
                            if (!other.equals(file.path()) && reference.names(other)) {
                                named.add(other);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Directed edges: what a file has to be read against. */
    private static Map<String, SortedSet<String>> dependencyEdges(
            ChangeGraph graph, SortedSet<String> nodes, Map<String, SortedSet<String>> includes) {
        Map<String, SortedSet<String>> result = emptyEdges(nodes);
        for (String file : nodes) {
            SortedSet<String> targets = result.get(file);
            targets.addAll(graph.filesReferencedBy(file));
            targets.addAll(includes.get(file));
            targets.remove(file);
            // Graphs.topologicalOrder rejects an edge pointing outside its
            // node set rather than dropping it, so the graph being built
            // from a different diff than the one passed in must be filtered
            // here, not discovered as an exception three frames down.
            targets.retainAll(nodes);
        }
        return result;
    }

    /**
     * Symmetric edges: the two claims that two files are ONE thing rather
     * than two related things, which is what makes them share a unit.
     *
     * <p>Same basename, different extension, and either in the same
     * directory or already joined by an include -- {@code guards.h} and
     * {@code guards.cpp}, not {@code a/util.py} and {@code b/util.rb}. And a
     * file that declares no changed symbol of its own, pulled in by exactly
     * one changed file: it has nothing of its own to be a section about, and
     * exactly one place it belongs. Pulled in by two, it is a shared
     * foundation instead, and appears in both their sections.</p>
     */
    private static Map<String, SortedSet<String>> conventionEdges(
            ChangeGraph graph, SortedSet<String> nodes, Map<String, SortedSet<String>> includes) {
        Map<String, SortedSet<String>> result = emptyEdges(nodes);
        for (String left : nodes) {
            for (String right : nodes) {
                if (left.compareTo(right) >= 0 || !sameComponentByName(left, right, includes)) {
                    continue;
                }
                result.get(left).add(right);
                result.get(right).add(left);
            }
        }
        for (String file : nodes) {
            if (!graph.declarationsIn(file).isEmpty()) {
                continue;
            }
            List<String> pullers = new ArrayList<>();
            for (String other : nodes) {
                if (!other.equals(file) && includes.get(other).contains(file)) {
                    pullers.add(other);
                }
            }
            if (pullers.size() == 1) {
                result.get(file).add(pullers.get(0));
                result.get(pullers.get(0)).add(file);
            }
        }
        return result;
    }

    private static boolean sameComponentByName(String left, String right,
                                               Map<String, SortedSet<String>> includes) {
        return stem(FallbackIntents.fileName(left)).equals(stem(FallbackIntents.fileName(right)))
                && !extension(left).equals(extension(right))
                && (FallbackIntents.directoryOf(left).equals(FallbackIntents.directoryOf(right))
                        || includes.get(left).contains(right)
                        || includes.get(right).contains(left));
    }

    // ---- naming -------------------------------------------------------------

    /**
     * The section's most-referenced changed symbol: what the section is
     * about. Counted per symbol, not per declaring file -- a file's fan-in
     * is the same number for every name it declares, so ranking by it would
     * title the section with whichever name sorted first.
     *
     * <p>Only the unit's own files are candidates. The foundation a section
     * carries for context is what some other section is about, and naming
     * this one after it would give two cards the same title.</p>
     *
     * <p>A symbol nothing references wins only when it is the unit's single
     * declaration -- then there is nothing to be wrong about. Several, all
     * unreferenced, and there is no hub: the honest answer is no symbol at
     * all, and {@link #title} falls back to the directory.</p>
     */
    private static Optional<String> hubOf(List<String> unit, ChangeGraph graph) {
        SortedSet<String> declarations = new TreeSet<>();
        for (String file : unit) {
            declarations.addAll(graph.declarationsIn(file));
        }
        String best = null;
        int bestFanIn = -1;
        // Ascending order plus a strict >: ties keep the alphabetically
        // first name, so the title cannot depend on iteration order.
        for (String symbol : declarations) {
            int fanIn = graph.filesReferencingSymbol(symbol).size();
            if (fanIn > bestFanIn) {
                best = symbol;
                bestFanIn = fanIn;
            }
        }
        return bestFanIn > 0 || declarations.size() == 1
                ? Optional.ofNullable(best)
                : Optional.empty();
    }

    private static String title(List<String> files, Optional<String> hub) {
        String count = files.size() + (files.size() == 1 ? " file" : " files");
        return hub.map(symbol -> symbol + " · " + count)
                // No symbol dominates: the directory is still the most
                // specific true thing that can be said.
                .orElseGet(() -> {
                    String directory = FallbackIntents.directoryOf(files.get(0));
                    return (directory.isEmpty() ? "repository root" : directory) + " · " + count;
                });
    }

    // ---- cycles -------------------------------------------------------------

    /**
     * The unit's members that genuinely depend on each other, using the
     * directed edges alone. A unit is not evidence of a cycle: the
     * conventions in {@link #conventionEdges} put files in one unit
     * precisely so they are read together, and reporting {@code guards.h}
     * and {@code guards.cpp} as a dependency cycle would send a reviewer
     * looking for a knot that is not there.
     */
    private static List<String> cyclesIn(List<String> unit,
                                         Map<String, SortedSet<String>> depends) {
        if (unit.size() < 2) {
            return List.of();
        }
        SortedSet<String> members = new TreeSet<>(unit);
        List<List<String>> parts = Graphs.topologicalOrder(members, file -> {
            SortedSet<String> inside = new TreeSet<>(depends.get(file));
            inside.retainAll(members);
            return inside;
        }, Comparator.naturalOrder());
        SortedSet<String> cyclic = new TreeSet<>();
        for (List<String> part : parts) {
            if (part.size() > 1) {
                cyclic.addAll(part);
            }
        }
        return List.copyOf(cyclic);
    }

    // ---- plumbing -----------------------------------------------------------

    private static List<String> hunkIdsOf(Map<String, UnifiedDiff.FileDiff> byPath,
                                          List<String> files) {
        List<String> ids = new ArrayList<>();
        for (String path : files) {
            UnifiedDiff.FileDiff file = byPath.get(path);
            if (file == null) {
                continue;
            }
            for (int hunk = 0; hunk < file.hunks().size(); hunk++) {
                ids.add(ReviewIntent.hunkId(path, hunk));
            }
        }
        return ids;
    }

    private static List<Section> fromFallback(UnifiedDiff diff) {
        List<Section> sections = new ArrayList<>();
        for (ReviewIntent intent : FallbackIntents.group(diff)) {
            sections.add(new Section(intent.title(), intent.files(), intent.hunkIds(),
                    Optional.empty(), List.of()));
        }
        return List.copyOf(sections);
    }

    private static Map<String, SortedSet<String>> emptyEdges(SortedSet<String> nodes) {
        Map<String, SortedSet<String>> result = new TreeMap<>();
        for (String node : nodes) {
            result.put(node, new TreeSet<>());
        }
        return result;
    }

    private static boolean isEmpty(Map<String, SortedSet<String>> edges) {
        return edges.values().stream().allMatch(SortedSet::isEmpty);
    }

    /** The file name without its extension: {@code src/guards.h} to {@code guards}. */
    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String extension(String path) {
        String name = FallbackIntents.fileName(path);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    // ---- what one line names ------------------------------------------------

    /**
     * One file or module named by an include or import line.
     *
     * <p>{@code pathLike} tokens ({@code "counters.h"}, {@code "./widget"})
     * carry their own separators; symbolic ones ({@code app.Constants},
     * {@code crate::guards::Scope}) spell a package, and only their last two
     * segments can plausibly be a file.</p>
     */
    private record Reference(String token, boolean pathLike) {

        boolean names(String other) {
            String otherName = FallbackIntents.fileName(other);
            if (pathLike) {
                if (other.equals(token) || other.endsWith("/" + token)) {
                    return true;
                }
                String named = FallbackIntents.fileName(token);
                return named.equals(otherName)
                        || (!stem(named).isEmpty() && stem(named).equals(stem(otherName)));
            }
            // The tail of a package path is the type; the one before it is
            // usually the module. Anything further up is a directory, and
            // matching on it would join every file under a common package.
            List<String> segments = List.of(token.split("[.:/]+"));
            String otherStem = stem(otherName);
            for (int index = segments.size() - 1;
                    index >= 0 && index >= segments.size() - 2; index--) {
                if (!segments.get(index).isEmpty() && segments.get(index).equals(otherStem)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<Reference> referencesOn(String text) {
        List<Reference> references = new ArrayList<>();
        add(references, INCLUDE.matcher(text), true);
        add(references, QUOTED_MODULE.matcher(text), true);
        add(references, SYMBOLIC_MODULE.matcher(text), false);
        return references;
    }

    private static void add(List<Reference> references, Matcher matcher, boolean pathLike) {
        while (matcher.find()) {
            references.add(new Reference(matcher.group(1), pathLike));
        }
    }
}
