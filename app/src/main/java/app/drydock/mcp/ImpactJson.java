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

/**
 * {@code review_scope}'s {@code impact} include (spec §8): one entry per
 * changed declaration site, not per tour step -- the agent is the one
 * building the steps, so it gets the raw material.
 *
 * <p>{@code signatureChanged} is true when the declaration sits on a changed
 * row (every site does) and its name still appears on lines the change did
 * not edit; {@code uneditedCallSites} counts those lines. Both are name
 * matches, not resolved references.</p>
 */
final class ImpactJson {

    private ImpactJson() {
    }

    static JsonValue toJson(ChangeGraph graph, OutOfDiffFanIn.Result fanIn) {
        List<JsonValue> entries = new ArrayList<>();
        for (ChangeGraph.DeclarationSite site : graph.declarationSites()) {
            List<OutOfDiffFanIn.Occurrence> callers = fanIn.bySymbol().getOrDefault(site.name(), List.of());
            List<JsonValue> calledFrom = new ArrayList<>();
            for (OutOfDiffFanIn.Occurrence caller : callers) {
                calledFrom.add(JsonObject.empty()
                        .put("file", new JsonString(caller.file()))
                        .put("line", JsonNumber.of(caller.line()))
                        .put("inChangedFile", new JsonBoolean(caller.inChangedFile())));
            }
            entries.add(JsonObject.empty()
                    .put("symbol", new JsonString(site.name()))
                    .put("declaredIn", JsonObject.empty()
                            .put("file", new JsonString(site.file()))
                            .put("lineKey", new JsonString(site.lineKey())))
                    .put("calledFrom", new JsonArray(calledFrom))
                    .put("signatureChanged", new JsonBoolean(!callers.isEmpty()))
                    .put("uneditedCallSites", JsonNumber.of(callers.size())));
        }
        return new JsonArray(entries);
    }
}
