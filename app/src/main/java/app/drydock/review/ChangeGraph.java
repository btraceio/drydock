package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The changed symbols of one scope and the references between them
 * (spec §4).
 *
 * <p>In memory, scope lifetime, rebuilt when the diff is re-read. Nothing is
 * persisted -- the reference implementation keeps a SQLite graph only because
 * it is a multi-process pipeline, and one process needs no file, no
 * invalidation story and no collection.</p>
 *
 * <p><strong>Two granularities, one rule.</strong> The file-level view
 * ({@link #filesReferencedBy}, {@link #filesReferencing}) is what grouping
 * asks for: are these two files related. The hunk-level view ({@link
 * #referencesIn}, {@link #hunksDeclaring}) is what a marker rendered
 * BENEATH one hunk asks for, and the two are not interchangeable -- a
 * footer saying "calls guards.cpp" under a hunk that calls nothing is a
 * false statement about that hunk, not a loose one about the file. Both are
 * built from the same symbols in the same pass and answer cross-file by the
 * same test, so they cannot drift.</p>
 *
 * <p>Every exposed collection is sorted. Determinism is a requirement here,
 * not a property (spec §9.5), and hash iteration order is the cheapest way
 * to lose it.</p>
 *
 * <p>Building the graph parses every changed file through {@link
 * SymbolScan}, which can trigger a first-time native grammar load. Blocking;
 * never call {@link #of} on the FX thread.</p>
 */
public final class ChangeGraph {

    private final SortedSet<String> files;
    private final Map<String, SortedSet<String>> declarationsByFile;
    private final Map<String, String> fileByUniqueDeclaration;
    private final Map<String, SortedSet<String>> referencesOut;
    private final Map<String, SortedSet<String>> referencesIn;
    private final Map<String, SortedSet<String>> referencesInBySymbol;
    private final Map<Hunk, SortedSet<String>> declarationsByHunk;
    private final Map<Hunk, SortedSet<String>> referencesByHunk;
    private final Map<String, SortedSet<Hunk>> hunksDeclaringSymbol;
    private final Map<String, SortedSet<Hunk>> hunksReferencingSymbol;

    /**
     * One hunk of one changed file, by the same index {@link
     * ReviewIntent#hunkId} counts.
     *
     * <p>The file-level view answers "are these two files related". A
     * reviewer is shown a marker under ONE hunk, and a marker under a hunk
     * that does not reference the target is a false statement about that
     * hunk, not a soft overstatement about the file -- so the graph carries
     * both granularities rather than leaving a caller to spread a file's
     * answer over its hunks.</p>
     */
    public record Hunk(String file, int index) implements Comparable<Hunk> {
        public Hunk {
            Objects.requireNonNull(file, "file");
        }

        @Override
        public int compareTo(Hunk other) {
            int byFile = file.compareTo(other.file);
            return byFile != 0 ? byFile : Integer.compare(index, other.index);
        }
    }

    private ChangeGraph(SortedSet<String> files,
                        Map<String, SortedSet<String>> declarationsByFile,
                        Map<String, String> fileByUniqueDeclaration,
                        Map<String, SortedSet<String>> referencesOut,
                        Map<String, SortedSet<String>> referencesIn,
                        Map<String, SortedSet<String>> referencesInBySymbol,
                        Map<Hunk, SortedSet<String>> declarationsByHunk,
                        Map<Hunk, SortedSet<String>> referencesByHunk,
                        Map<String, SortedSet<Hunk>> hunksDeclaringSymbol,
                        Map<String, SortedSet<Hunk>> hunksReferencingSymbol) {
        this.declarationsByHunk = declarationsByHunk;
        this.referencesByHunk = referencesByHunk;
        this.hunksDeclaringSymbol = hunksDeclaringSymbol;
        this.hunksReferencingSymbol = hunksReferencingSymbol;
        this.files = files;
        this.declarationsByFile = declarationsByFile;
        this.fileByUniqueDeclaration = fileByUniqueDeclaration;
        this.referencesOut = referencesOut;
        this.referencesIn = referencesIn;
        this.referencesInBySymbol = referencesInBySymbol;
    }

    /**
     * Builds the graph for {@code diff}. Blocking -- scans every file with
     * {@link SymbolScan}, which can load a native grammar library the first
     * time a language is seen -- never call on the FX thread.
     */
    public static ChangeGraph of(UnifiedDiff diff) {
        Map<String, List<SymbolScan.Symbol>> scans = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            scans.put(file.path(), SymbolScan.of(file));
        }

        // A name declared in more than one changed file cannot be resolved,
        // so it is dropped rather than guessed at.
        Map<String, List<String>> declaringFiles = new TreeMap<>();
        Map<String, SortedSet<String>> declarationsByFile = new TreeMap<>();
        Map<Hunk, SortedSet<String>> declarationsByHunk = new TreeMap<>();
        for (Map.Entry<String, List<SymbolScan.Symbol>> entry : scans.entrySet()) {
            for (SymbolScan.Symbol symbol : entry.getValue()) {
                if (symbol.declaration() && symbol.onChangedLine()) {
                    declaringFiles.computeIfAbsent(symbol.name(), key -> new ArrayList<>())
                            .add(entry.getKey());
                    declarationsByFile.computeIfAbsent(entry.getKey(), key -> new TreeSet<>())
                            .add(symbol.name());
                    declarationsByHunk
                            .computeIfAbsent(new Hunk(entry.getKey(), symbol.hunk()),
                                    key -> new TreeSet<>())
                            .add(symbol.name());
                }
            }
        }
        Map<String, String> unique = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : declaringFiles.entrySet()) {
            List<String> distinct = entry.getValue().stream().distinct().toList();
            if (distinct.size() == 1) {
                unique.put(entry.getKey(), distinct.get(0));
            }
        }

        Map<String, SortedSet<String>> out = new TreeMap<>();
        Map<String, SortedSet<String>> in = new TreeMap<>();
        Map<String, SortedSet<String>> inBySymbol = new TreeMap<>();
        Map<Hunk, SortedSet<String>> referencesByHunk = new TreeMap<>();
        Map<String, SortedSet<Hunk>> hunksDeclaringSymbol = new TreeMap<>();
        Map<String, SortedSet<Hunk>> hunksReferencingSymbol = new TreeMap<>();
        for (Map.Entry<String, List<SymbolScan.Symbol>> entry : scans.entrySet()) {
            for (SymbolScan.Symbol symbol : entry.getValue()) {
                // A use counts wherever it sits in the diff window, changed
                // line or context line. The node set is already restricted
                // to changed files, so this cannot drag in unrelated code --
                // it only connects files already under review together. A
                // declaration changing behaviour without most of its call
                // sites being touched is the single most common shape of
                // the coupling this graph exists to surface; requiring the
                // use itself to be edited would split that section in half
                // to buy edge purity the node-set restriction already gives
                // for free.
                String target = unique.get(symbol.name());
                // Cross-file only: an intra-file match is noise from
                // short-name matching, not a relationship worth showing.
                if (target == null || target.equals(entry.getKey())) {
                    continue;
                }
                out.computeIfAbsent(entry.getKey(), key -> new TreeSet<>()).add(target);
                in.computeIfAbsent(target, key -> new TreeSet<>()).add(entry.getKey());
                inBySymbol.computeIfAbsent(symbol.name(), key -> new TreeSet<>())
                        .add(entry.getKey());
                // Same edge, one granularity finer. Cross-file only, by the
                // same test: the file-level and hunk-level views must agree
                // about what an edge IS, or a link footer and the section it
                // sits in would disagree.
                referencesByHunk
                        .computeIfAbsent(new Hunk(entry.getKey(), symbol.hunk()),
                                key -> new TreeSet<>())
                        .add(symbol.name());
                hunksReferencingSymbol.computeIfAbsent(symbol.name(), key -> new TreeSet<>())
                        .add(new Hunk(entry.getKey(), symbol.hunk()));
            }
        }

        // Which hunks of the declaring file actually declare each resolvable
        // name. A name uniquely declared in one FILE may still be declared in
        // more than one of its hunks, and a "calls" link has to point at all
        // of them rather than guess one.
        for (Map.Entry<Hunk, SortedSet<String>> entry : declarationsByHunk.entrySet()) {
            for (String name : entry.getValue()) {
                if (entry.getKey().file().equals(unique.get(name))) {
                    hunksDeclaringSymbol.computeIfAbsent(name, key -> new TreeSet<>())
                            .add(entry.getKey());
                }
            }
        }

        SortedSet<String> files = new TreeSet<>(scans.keySet());
        return new ChangeGraph(files, declarationsByFile, unique, out, in, inBySymbol,
                declarationsByHunk, referencesByHunk, hunksDeclaringSymbol,
                hunksReferencingSymbol);
    }

    /** Every changed file, in this scope. */
    public SortedSet<String> files() {
        return Collections.unmodifiableSortedSet(files);
    }

    /** Names {@code file} declares on a changed line. */
    public SortedSet<String> declarationsIn(String file) {
        return unmodifiable(declarationsByFile.get(file));
    }

    /** Files {@code file} references. */
    public SortedSet<String> filesReferencedBy(String file) {
        return unmodifiable(referencesOut.get(file));
    }

    /** Files that reference {@code file}. */
    public SortedSet<String> filesReferencing(String file) {
        return unmodifiable(referencesIn.get(file));
    }

    /**
     * Files that reference {@code symbol} itself, which is not the same
     * question as {@link #filesReferencing(String)} on its declaring file: a
     * file declaring ten changed symbols has one fan-in, and its ten symbols
     * do not. Anything asking which symbol a group of files is ABOUT needs
     * the per-symbol count, and reading it off the file would answer with
     * whichever name happened to sort first.
     */
    public SortedSet<String> filesReferencingSymbol(String symbol) {
        return unmodifiable(referencesInBySymbol.get(symbol));
    }

    /** The one changed file declaring {@code symbol}, when exactly one does. */
    public Optional<String> fileDeclaring(String symbol) {
        return Optional.ofNullable(fileByUniqueDeclaration.get(symbol));
    }

    /** Every uniquely-declared changed symbol name. */
    public SortedSet<String> changedDeclarations() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(fileByUniqueDeclaration.keySet()));
    }

    /** Names {@code hunk} declares on a changed line. */
    public SortedSet<String> declarationsIn(Hunk hunk) {
        return unmodifiable(declarationsByHunk.get(hunk));
    }

    /**
     * Names {@code hunk} uses that another changed file uniquely declares --
     * the hunk-level counterpart of {@link #filesReferencedBy(String)}, and
     * cross-file by the same rule.
     */
    public SortedSet<String> referencesIn(Hunk hunk) {
        return unmodifiable(referencesByHunk.get(hunk));
    }

    /** The hunks of {@code symbol}'s one declaring file that declare it. */
    public SortedSet<Hunk> hunksDeclaring(String symbol) {
        return unmodifiableHunks(hunksDeclaringSymbol.get(symbol));
    }

    /** The hunks in other files that reference {@code symbol}. */
    public SortedSet<Hunk> hunksReferencingSymbol(String symbol) {
        return unmodifiableHunks(hunksReferencingSymbol.get(symbol));
    }

    private static SortedSet<String> unmodifiable(SortedSet<String> set) {
        return Collections.unmodifiableSortedSet(set == null ? new TreeSet<>() : set);
    }

    private static SortedSet<Hunk> unmodifiableHunks(SortedSet<Hunk> set) {
        return Collections.unmodifiableSortedSet(set == null ? new TreeSet<>() : set);
    }
}
