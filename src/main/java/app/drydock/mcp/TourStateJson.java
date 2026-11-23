package app.drydock.mcp;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.List;

/** The agent's view of a tour's progress, for review_state. */
final class TourStateJson {

    private TourStateJson() {
    }

    static JsonValue of(TourRecord record, String currentFingerprint) {
        List<JsonValue> steps = new ArrayList<>();
        List<JsonValue> awaiting = new ArrayList<>();
        int number = 1;
        for (TourStep step : record.tour().steps()) {
            StepProgress progress = record.progress(step.id());
            List<JsonValue> checks = new ArrayList<>();
            for (TourCheck check : step.checks()) {
                CheckProgress p = progress.check(check.id());
                checks.add(JsonObject.empty()
                        .put("id", new JsonString(check.id()))
                        .put("status", new JsonString(p.status().name()))
                        .put("attempt", JsonNumber.of(p.attempt())));
                if (p.status() == CheckProgress.Status.AWAITING_AGENT) {
                    awaiting.add(JsonObject.empty()
                            .put("stepId", new JsonString(step.id()))
                            .put("checkId", new JsonString(check.id()))
                            .put("prompt", new JsonString(check.version(p.attempt()).prompt()))
                            .put("answer", new JsonString(p.riskAnswer().orElse(""))));
                }
            }
            steps.add(JsonObject.empty()
                    .put("id", new JsonString(step.id()))
                    .put("number", JsonNumber.of(number++))
                    .put("title", new JsonString(step.title()))
                    .put("decision", new JsonString(progress.decision().name()))
                    .put("stale", new JsonBoolean(progress.stale()))
                    .put("checks", JsonArray.of(checks)));
        }
        return JsonObject.empty()
                .put("fingerprint", new JsonString(record.tour().diffFingerprint()))
                .put("current", new JsonBoolean(record.tour().diffFingerprint().equals(currentFingerprint)))
                .put("steps", JsonArray.of(steps))
                .put("awaitingAgent", JsonArray.of(awaiting));
    }
}
