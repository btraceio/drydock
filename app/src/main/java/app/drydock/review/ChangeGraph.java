package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private ChangeGraph(SortedSet<String> files,
                        Map<String, SortedSet<String>> declarationsByFile,
                        Map<String, String> fileByUniqueDeclaration,
                        Map<String, SortedSet<String>> referencesOut,
                        Map<String, SortedSet<String>> referencesIn) {
        this.files = files;
        this.declarationsByFile = declarationsByFile;
        this.fileByUniqueDeclaration = fileByUniqueDeclaration;
        this.referencesOut = referencesOut;
        this.referencesIn = referencesIn;
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
        for (Map.Entry<String, List<SymbolScan.Symbol>> entry : scans.entrySet()) {
            for (SymbolScan.Symbol symbol : entry.getValue()) {
                if (symbol.declaration() && symbol.onChangedLine()) {
                    declaringFiles.computeIfAbsent(symbol.name(), key -> new ArrayList<>())
                            .add(entry.getKey());
                    declarationsByFile.computeIfAbsent(entry.getKey(), key -> new TreeSet<>())
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
            }
        }

        SortedSet<String> files = new TreeSet<>(scans.keySet());
        return new ChangeGraph(files, declarationsByFile, unique, out, in);
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

    /** The one changed file declaring {@code symbol}, when exactly one does. */
    public Optional<String> fileDeclaring(String symbol) {
        return Optional.ofNullable(fileByUniqueDeclaration.get(symbol));
    }

    /** Every uniquely-declared changed symbol name. */
    public SortedSet<String> changedDeclarations() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(fileByUniqueDeclaration.keySet()));
    }

    private static SortedSet<String> unmodifiable(SortedSet<String> set) {
        return Collections.unmodifiableSortedSet(set == null ? new TreeSet<>() : set);
    }
}
