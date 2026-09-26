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
import java.util.function.Predicate;
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
 * <p><strong>What a card says.</strong> A section is named after the changed
 * symbol its unit declares that most looks like the thing it is about --
 * type-shaped first, fan-in only breaking ties within a shape, because fan-in
 * alone titles cards after loop variables. A unit that declares nothing
 * nameable is named after its own most substantial file, never after a
 * directory: the grouping is not directory-derived, so a directory title
 * misdescribes it. No two cards may read the same, which {@link
 * FallbackIntents} guarantees and this has to guarantee too.</p>
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

        List<Draft> drafts = new ArrayList<>();
        for (List<String> unit : units) {
            SortedSet<String> members = new TreeSet<>(unit);
            for (String file : unit) {
                members.addAll(depends.get(file));
            }
            List<String> ordered = new ArrayList<>(members);
            ordered.sort(readingOrder);
            drafts.add(new Draft(ordered, hunkIdsOf(byPath, ordered),
                    hubOf(unit, ordered, graph), cyclesIn(unit, depends),
                    primaryOf(unit, byPath)));
        }
        return titled(readable(drafts));
    }

    /**
     * A section before it is named. Titling needs the whole rail in hand --
     * no two cards may read the same -- so it cannot happen while the
     * sections are still being built one at a time.
     *
     * <p>{@code primary} is the unit's own most substantial file, and it is
     * what names a section no symbol can name. Units are disjoint, so no two
     * drafts can carry the same primary, which is what makes the
     * disambiguation in {@link #titled} terminate rather than merely
     * usually work.</p>
     */
    private record Draft(List<String> files, List<String> hunkIds, Optional<String> hub,
                         List<String> cycleWith, String primary) {
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
    private static List<Draft> readable(List<Draft> drafts) {
        List<Draft> visible = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            Draft draft = drafts.get(index);
            if (draft.hub().isEmpty() && coveredByAnother(drafts, index)) {
                continue;
            }
            visible.add(draft);
        }
        return List.copyOf(visible);
    }

    private static boolean coveredByAnother(List<Draft> drafts, int index) {
        List<String> files = drafts.get(index).files();
        for (int other = 0; other < drafts.size(); other++) {
            if (other == index || !drafts.get(other).files().containsAll(files)) {
                continue;
            }
            if (drafts.get(other).files().size() > files.size() || other < index) {
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
     * What the section is about: the most promising changed symbol its own
     * unit declares.
     *
     * <p>Fan-in alone is not it. Measured on this branch's own 54-file diff,
     * ranking by fan-in titled cards {@code hunk}, {@code isEmpty},
     * {@code has} and {@code files} -- loop variables and one-line accessors
     * whose names simply recur in many files -- while the names a reviewer
     * would recognise ({@code BaseMove}, {@code HunkDigest},
     * {@code ChangeGraph}) sat one or two references below them. A
     * <em>type</em> is what a group of files is about; a member name is what
     * they happen to have in common. So a type-shaped name outranks any
     * member name, and fan-in only breaks ties within a shape.</p>
     *
     * <p>Type-shaped means an initial capital. That is a naming convention
     * rather than a fact from the parse tree -- it is right for Java,
     * Kotlin, C++, Go, Rust, TypeScript and Python types, and wrong for a C
     * codebase spelling structs in lower case, which lands on the member
     * name it would have picked anyway.</p>
     *
     * <p>Fan-in is counted twice: within the section (how central the name
     * is to what this card shows) and across the change. A foundation
     * section holds only itself -- its referencing files are, by
     * construction, in the sections further down the rail -- so requiring an
     * in-section reference would leave exactly the cards that name real hubs
     * unnamed. In-section count therefore ranks, and the change-wide count
     * is what a candidate has to have any of.</p>
     *
     * <p>Only the unit's own files are candidates. The foundation a section
     * carries for context is what some other section is about, and naming
     * this one after it would give two cards the same title.</p>
     */
    private static Optional<String> hubOf(List<String> unit, List<String> sectionFiles,
                                          ChangeGraph graph) {
        SortedSet<String> declarations = new TreeSet<>();
        for (String file : unit) {
            declarations.addAll(graph.declarationsIn(file));
        }
        if (declarations.isEmpty()) {
            return Optional.empty();
        }
        SortedSet<String> section = new TreeSet<>(sectionFiles);
        List<Candidate> referenced = new ArrayList<>();
        for (String symbol : declarations) {
            SortedSet<String> referencing = new TreeSet<>(graph.filesReferencingSymbol(symbol));
            int across = referencing.size();
            referencing.retainAll(section);
            if (across > 0) {
                referenced.add(new Candidate(symbol, typeShaped(symbol),
                        referencing.size(), across));
            }
        }
        if (!referenced.isEmpty()) {
            referenced.sort(Sections::byPromise);
            return Optional.of(referenced.get(0).name());
        }
        // Nothing here is referenced at all, so there is no hub to measure --
        // only a name to recognise. One declaration is unambiguous; a name
        // matching the unit's own file name is the file's subject by
        // convention; a lone type among functions is the thing the functions
        // are for. That last rung is what titles the guards.h/guards.cpp
        // pair "JmpCtxScope" instead of after its folder, which is the case
        // this class was commissioned to fix.
        if (declarations.size() == 1) {
            return Optional.of(declarations.first());
        }
        Optional<String> named = onlyOne(declarations, symbol -> matchesFileName(symbol, unit));
        return named.isPresent() ? named : onlyOne(declarations, Sections::typeShaped);
    }

    /** One possible hub, with the two counts and the shape that rank it. */
    private record Candidate(String name, boolean type, int inSection, int acrossChange) {
    }

    private static int byPromise(Candidate left, Candidate right) {
        if (left.type() != right.type()) {
            return left.type() ? -1 : 1;
        }
        if (left.inSection() != right.inSection()) {
            return Integer.compare(right.inSection(), left.inSection());
        }
        if (left.acrossChange() != right.acrossChange()) {
            return Integer.compare(right.acrossChange(), left.acrossChange());
        }
        return left.name().compareTo(right.name());
    }

    private static boolean typeShaped(String symbol) {
        return !symbol.isEmpty() && Character.isUpperCase(symbol.charAt(0));
    }

    private static boolean matchesFileName(String symbol, List<String> unit) {
        for (String file : unit) {
            if (stem(FallbackIntents.fileName(file)).equalsIgnoreCase(symbol)) {
                return true;
            }
        }
        return false;
    }

    /** {@code symbol} when exactly one matches, so a guess is never made from several. */
    private static Optional<String> onlyOne(SortedSet<String> symbols, Predicate<String> matches) {
        String found = null;
        for (String symbol : symbols) {
            if (!matches.test(symbol)) {
                continue;
            }
            if (found != null) {
                return Optional.empty();
            }
            found = symbol;
        }
        return Optional.ofNullable(found);
    }

    /**
     * The unit's own most substantial file: what names a card no symbol can
     * name. The most-changed file first, ties by path.
     *
     * <p>Deliberately NOT the directory. A section is not directory-derived,
     * so a directory title misdescribes the grouping -- and the first
     * attempt proved it, titling a card after a package containing none of
     * the files the card was about, because it read the directory off the
     * first file in reading order, which is a pulled-in foundation rather
     * than a member.</p>
     */
    private static String primaryOf(List<String> unit, Map<String, UnifiedDiff.FileDiff> byPath) {
        String best = null;
        int bestHunks = -1;
        for (String file : unit) {
            UnifiedDiff.FileDiff diff = byPath.get(file);
            int hunks = diff == null ? 0 : diff.hunks().size();
            if (hunks > bestHunks) {
                best = file;
                bestHunks = hunks;
            }
        }
        return best;
    }

    /**
     * The rail, named. {@link FallbackIntents} guarantees that two cards can
     * never read the same, and a grouping is only useful if its entries can
     * be told apart -- so this makes the same guarantee rather than hoping
     * for it. A hub symbol is declared in exactly one file and units are
     * disjoint, so hub titles are already unique; a file name is not, and
     * any that repeats is re-spelled as the full path of a file only that
     * card is about.
     */
    private static List<Section> titled(List<Draft> drafts) {
        List<String> provisional = new ArrayList<>();
        Map<String, Integer> seen = new TreeMap<>();
        for (Draft draft : drafts) {
            String title = name(draft, false);
            provisional.add(title);
            seen.merge(title, 1, Integer::sum);
        }
        List<Section> sections = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            Draft draft = drafts.get(index);
            boolean clashes = seen.get(provisional.get(index)) > 1;
            sections.add(new Section(clashes ? name(draft, true) : provisional.get(index),
                    draft.files(), draft.hunkIds(), draft.hub(), draft.cycleWith()));
        }
        return List.copyOf(sections);
    }

    private static String name(Draft draft, boolean qualified) {
        int size = draft.files().size();
        String count = size + (size == 1 ? " file" : " files");
        String subject = draft.hub()
                .map(hub -> qualified ? hub + " (" + draft.primary() + ")" : hub)
                .orElseGet(() -> qualified
                        ? draft.primary()
                        : FallbackIntents.fileName(draft.primary()));
        return subject + " · " + count;
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
