package app.drydock.mcp;

import app.drydock.review.ChangeGraph;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * {@code review_scope}'s {@code impact} include (spec §8): one entry per
 * changed declaration site, not per tour step -- the agent is the one
 * building the steps, so it gets the raw material.
 *
 * <p>{@code signatureChanged} is true when the declaration sits on a changed
 * row (every site does) and its name still appears on lines the change did
 * not edit; {@code uneditedCallSites} counts those lines. Both are name
 * matches. Each {@code calledFrom} entry also carries {@code bound}: true
 * when scoped analysis (the JavaScopeBinder tier, spec §6) tied the
 * occurrence to THIS declaration through a statically derivable receiver
 * -- a real reference -- and false for a plain name match, the honest
 * default for anything the classification did not or could not reach.</p>
 *
 * <p>A symbol whose attributable occurrences exceed {@link
 * OutOfDiffFanIn#MAX_ATTRIBUTABLE} is reported as a count, not a list:
 * {@code calledFrom} is empty and {@code tooCommonToAttribute} carries how
 * many there were. At that size the rows are links to unrelated same-named
 * symbols more often than not -- the count is the honest signal, and an
 * agent citing a caller should read the caller's file first.</p>
 *
 * <p>{@code callsInChange} names what the hunks declaring the symbol
 * reference in other changed files -- callees inside the change. Callees
 * outside it would each cost a search, and stay with the UI.</p>
 *
 * <p>A name declared in more than one changed file is {@code ambiguous}:
 * the caller scan cannot attribute an occurrence to either declaration, so
 * the entry carries no callers and is never flagged.</p>
 */
final class ImpactJson {

    private ImpactJson() {
    }

    static JsonValue toJson(ChangeGraph graph, OutOfDiffFanIn.Result fanIn) {
        List<JsonValue> entries = new ArrayList<>();
        SortedSet<String> unique = graph.changedDeclarations();
        for (ChangeGraph.DeclarationSite site : graph.declarationSites()) {
            boolean ambiguous = !unique.contains(site.name());
            List<OutOfDiffFanIn.Occurrence> callers = ambiguous
                    ? List.of()
                    : fanIn.bySymbol().getOrDefault(site.name(), List.of());
            Integer tooCommon = ambiguous ? null : fanIn.suppressedCounts().get(site.name());
            int occurrences = fanIn.occurrences(site.name());
            List<JsonValue> calledFrom = new ArrayList<>();
            for (OutOfDiffFanIn.Occurrence caller : callers) {
                calledFrom.add(JsonObject.empty()
                        .put("file", new JsonString(caller.file()))
                        .put("line", JsonNumber.of(caller.line()))
                        .put("inChangedFile", new JsonBoolean(caller.inChangedFile()))
                        .put("bound", new JsonBoolean(caller.bound())));
            }
            SortedSet<String> calls = new TreeSet<>();
            for (ChangeGraph.Hunk hunk : graph.hunksDeclaring(site.name())) {
                calls.addAll(graph.referencesIn(hunk));
            }
            JsonObject entry = JsonObject.empty()
                    .put("symbol", new JsonString(site.name()))
                    .put("declaredIn", JsonObject.empty()
                            .put("file", new JsonString(site.file()))
                            .put("lineKey", new JsonString(site.lineKey())))
                    .put("calledFrom", new JsonArray(calledFrom))
                    .put("callsInChange", new JsonArray(calls.stream()
                            .map(name -> (JsonValue) new JsonString(name)).toList()))
                    .put("signatureChanged", new JsonBoolean(!ambiguous && occurrences > 0))
                    .put("uneditedCallSites", JsonNumber.of(ambiguous ? 0 : occurrences));
            if (tooCommon != null) {
                entry.put("tooCommonToAttribute", JsonNumber.of(tooCommon));
            }
            if (ambiguous) {
                entry.put("ambiguous", new JsonBoolean(true));
            }
            entries.add(entry);
        }
        return new JsonArray(entries);
    }
}
