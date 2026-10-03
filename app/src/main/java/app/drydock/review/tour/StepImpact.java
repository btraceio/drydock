package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ChangeGraph;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.review.SymbolWords;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;

/**
 * What one tour step touches beyond its own rows (spec §6), all of it
 * measured from the review diff, the change graph and the out-of-diff
 * caller scan.
 *
 * <p><strong>Occurrences, not resolved references.</strong> Every name here
 * is matched by spelling. A caller listed under {@code foo} may call an
 * unrelated {@code foo}, and a signature flag counts such lines too: the
 * flag says "a declaration changed and these lines spell its name without
 * having been edited", which is what a reviewer needs to go and look, not
 * a compiler's verdict.</p>
 *
 * <p>Pure and blocking-free given its inputs, but its inputs are not: the
 * graph and the scan are built off the FX thread, and so is this.</p>
 *
 * @param calledFromOutside occurrences of the step's declarations outside
 *                          the change, by file then line
 * @param inChange          edges to other steps of the tour through the
 *                          change graph
 * @param calleesToResolve  names used on the step's changed rows that the
 *                          change does not declare, most used first, at most
 *                          {@link #MAX_CALLEES}
 * @param signatureFlags    declarations on changed rows whose name still
 *                          appears on unedited lines
 * @param unavailableReason why the caller scan could not run; present means
 *                          {@code calledFromOutside} is absent, not empty
 */
public record StepImpact(List<Caller> calledFromOutside, List<InChange> inChange,
                         List<String> calleesToResolve, List<SignatureFlag> signatureFlags,
                         Optional<String> unavailableReason) {

    /** Each callee is a search when it is resolved, so the list is capped. */
    public static final int MAX_CALLEES = 8;

    public enum Direction { CALLS, CALLED_BY }

    public record Caller(String symbol, String file, int line, String text, boolean inChangedFile) {
    }

    /** {@code symbol} links this step to step {@code otherStepNumber} ({@code otherStepId}). */
    public record InChange(String symbol, Direction direction, String otherStepId, int otherStepNumber) {
    }

    /** {@code symbol}, declared on changed row {@code lineKey}, still spelled on {@code uneditedCallSites} lines. */
    public record SignatureFlag(String symbol, String file, String lineKey, int uneditedCallSites) {
    }

    public StepImpact {
        calledFromOutside = List.copyOf(calledFromOutside);
        inChange = List.copyOf(inChange);
        calleesToResolve = List.copyOf(calleesToResolve);
        signatureFlags = List.copyOf(signatureFlags);
        Objects.requireNonNull(unavailableReason, "unavailableReason");
    }

    public static StepImpact of(TourStep step, ReviewTour tour, UnifiedDiff reviewDiff, ChangeGraph graph,
                                OutOfDiffFanIn.Result fanIn) {
        AnchorIndex index = AnchorIndex.of(reviewDiff);
        List<ChangeGraph.DeclarationSite> sites = graph.declarationSites().stream()
                .filter(site -> inStep(step, index, site.file(), site.lineKey()))
                .toList();
        SortedSet<String> symbols = new TreeSet<>();
        sites.forEach(site -> symbols.add(site.name()));

        List<Caller> callers = new ArrayList<>();
        for (String symbol : symbols) {
            for (OutOfDiffFanIn.Occurrence occurrence : fanIn.bySymbol().getOrDefault(symbol, List.of())) {
                callers.add(new Caller(symbol, occurrence.file(), occurrence.line(), occurrence.text(),
                        occurrence.inChangedFile()));
            }
        }
        callers.sort(Comparator.comparing(Caller::file).thenComparingInt(Caller::line)
                .thenComparing(Caller::symbol));

        List<SignatureFlag> flags = new ArrayList<>();
        for (ChangeGraph.DeclarationSite site : sites) {
            // Every site is a declaration on a changed row by construction,
            // and the scan already dropped occurrences on changed rows, so
            // whatever it found is an unedited line still spelling the name.
            int unedited = fanIn.bySymbol().getOrDefault(site.name(), List.of()).size();
            if (unedited > 0) {
                flags.add(new SignatureFlag(site.name(), site.file(), site.lineKey(), unedited));
            }
        }

        return new StepImpact(callers, inChange(step, tour, index, graph, symbols),
                callees(step, index, reviewDiff, graph), flags, fanIn.unavailableReason());
    }

    private static boolean inStep(TourStep step, AnchorIndex index, String file, String lineKey) {
        return step.anchors().stream().anyMatch(anchor -> index.contains(anchor, file, lineKey));
    }

    /**
     * CALLS: names this step's hunks reference, to the step owning the hunk
     * that declares each. CALLED_BY: hunks referencing this step's
     * declarations, to the step owning each. A hunk's owner is the first step
     * whose anchors touch it -- the same digests {@link StepProgress#fresh}
     * records -- and an edge back to this step is no edge.
     */
    private static List<InChange> inChange(TourStep step, ReviewTour tour, AnchorIndex index,
                                           ChangeGraph graph, SortedSet<String> symbols) {
        Map<ChangeGraph.Hunk, String> digests = new HashMap<>();
        for (AnchorIndex.HunkRef hunk : index.hunks()) {
            digests.put(new ChangeGraph.Hunk(hunk.file(), hunk.index()), hunk.digest());
        }
        Map<String, List<String>> stepDigests = new HashMap<>();
        for (TourStep each : tour.steps()) {
            stepDigests.put(each.id(), StepProgress.fresh(each, index).hunkDigests());
        }

        Set<InChange> edges = new LinkedHashSet<>();
        Set<ChangeGraph.Hunk> touched = new TreeSet<>();
        for (TourAnchor anchor : step.anchors()) {
            for (AnchorIndex.HunkRef hunk : index.hunksTouched(anchor)) {
                touched.add(new ChangeGraph.Hunk(hunk.file(), hunk.index()));
            }
        }
        for (ChangeGraph.Hunk hunk : touched) {
            for (String name : graph.referencesIn(hunk)) {
                for (ChangeGraph.Hunk declaring : graph.hunksDeclaring(name)) {
                    owner(tour, stepDigests, digests.get(declaring))
                            .filter(other -> !other.id().equals(step.id()))
                            .ifPresent(other -> edges.add(new InChange(name, Direction.CALLS, other.id(),
                                    tour.number(other.id()))));
                }
            }
        }
        for (String name : symbols) {
            for (ChangeGraph.Hunk referencing : graph.hunksReferencingSymbol(name)) {
                owner(tour, stepDigests, digests.get(referencing))
                        .filter(other -> !other.id().equals(step.id()))
                        .ifPresent(other -> edges.add(new InChange(name, Direction.CALLED_BY, other.id(),
                                tour.number(other.id()))));
            }
        }
        return List.copyOf(edges);
    }

    private static Optional<TourStep> owner(ReviewTour tour, Map<String, List<String>> stepDigests,
                                            String digest) {
        if (digest == null) {
            return Optional.empty();
        }
        return tour.steps().stream()
                .filter(each -> stepDigests.getOrDefault(each.id(), List.of()).contains(digest))
                .findFirst();
    }

    /**
     * Identifiers on the step's changed rows, by the lexical rule {@link
     * SymbolWords} gives every lens, that the change does not declare. Most
     * used first, then by name so the cut at {@link #MAX_CALLEES} is stable.
     */
    private static List<String> callees(TourStep step, AnchorIndex index, UnifiedDiff reviewDiff,
                                        ChangeGraph graph) {
        SortedSet<String> declared = graph.changedDeclarations();
        Map<String, Integer> counts = new TreeMap<>();
        for (UnifiedDiff.FileDiff file : reviewDiff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    if (line.kind() == UnifiedDiff.Line.Kind.CONTEXT
                            || !inStep(step, index, file.path(), line.lineKey())) {
                        continue;
                    }
                    Matcher matcher = SymbolWords.IDENTIFIER.matcher(line.text());
                    while (matcher.find()) {
                        String name = matcher.group();
                        if (SymbolWords.isSymbol(name) && !declared.contains(name)) {
                            counts.merge(name, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_CALLEES)
                .map(Map.Entry::getKey)
                .toList();
    }
}
