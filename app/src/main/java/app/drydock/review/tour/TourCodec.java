package app.drydock.review.tour;

import app.drydock.review.ReviewVerdict;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * JSON for tours: strict decoding of what an agent sends, and lenient
 * round-tripping of what {@link TourStore} persists.
 *
 * <p>Strict: every error names the path ({@code steps[2].checks[0].answer})
 * and nothing is decoded past a length or count cap. Lenient: a persisted
 * step that no longer decodes is dropped with its progress; the rest of
 * the tour survives.</p>
 */
public final class TourCodec {

    static final int MAX_ANCHORS = 20;
    static final int MAX_NOTES = 20;
    static final int MAX_ALTERNATES = 6;
    static final int MAX_ID = 80;
    static final int MAX_KEY = 24;
    static final int MAX_PATH = 1024;

    public static final class InvalidTour extends Exception {
        public InvalidTour(String message) {
            super(message);
        }
    }

    private TourCodec() {
    }

    // ---- agent → model (strict) ----

    public static List<TourStep> stepsFromAgent(JsonValue value) throws InvalidTour {
        if (!(value instanceof JsonArray array)) {
            throw new InvalidTour("steps must be an array of step objects");
        }
        if (array.elements().size() > TourValidator.MAX_STEPS) {
            throw new InvalidTour("steps: a tour has at most " + TourValidator.MAX_STEPS + " steps");
        }
        List<TourStep> steps = new ArrayList<>();
        for (int i = 0; i < array.elements().size(); i++) {
            steps.add(stepFromJson(array.elements().get(i), "steps[" + i + "]"));
        }
        return steps;
    }

    public static TourStep stepFromJson(JsonValue value, String path) throws InvalidTour {
        JsonObject obj = object(value, path);
        String id = string(obj, "id", path, MAX_ID);
        String title = string(obj, "title", path, TourValidator.MAX_TITLE);
        String narrative = string(obj, "narrative", path, TourValidator.MAX_NARRATIVE);
        List<TourAnchor> anchors = new ArrayList<>();
        JsonArray anchorArray = array(obj, "anchors", path, MAX_ANCHORS, true);
        for (int i = 0; i < anchorArray.elements().size(); i++) {
            String at = path + ".anchors[" + i + "]";
            JsonObject anchor = object(anchorArray.elements().get(i), at);
            String start = string(anchor, "startKey", at, MAX_KEY);
            String end = optionalString(anchor, "endKey", at, MAX_KEY).orElse(start);
            anchors.add(new TourAnchor(string(anchor, "file", at, MAX_PATH), start, end));
        }
        List<ImpactNote> notes = new ArrayList<>();
        JsonArray noteArray = array(obj, "impactNotes", path, MAX_NOTES, false);
        for (int i = 0; i < noteArray.elements().size(); i++) {
            String at = path + ".impactNotes[" + i + "]";
            JsonObject note = object(noteArray.elements().get(i), at);
            notes.add(new ImpactNote(string(note, "file", at, MAX_PATH), integer(note, "line", at),
                    string(note, "text", at, TourValidator.MAX_PROMPT)));
        }
        List<TourCheck> checks = new ArrayList<>();
        JsonArray checkArray = array(obj, "checks", path, TourValidator.MAX_CHECKS_PER_STEP, true);
        for (int i = 0; i < checkArray.elements().size(); i++) {
            checks.add(checkFromJson(checkArray.elements().get(i), path + ".checks[" + i + "]", true));
        }
        return new TourStep(id, title, narrative, anchors, notes, checks);
    }

    private static TourCheck checkFromJson(JsonValue value, String path, boolean topLevel) throws InvalidTour {
        JsonObject obj = object(value, path);
        String id = string(obj, "id", path, MAX_ID);
        String rawKind = string(obj, "kind", path, 16);
        TourCheck.Kind kind = TourCheck.Kind.fromWire(rawKind)
                .orElseThrow(() -> new InvalidTour(path + ".kind: expected predict, trace or risk, got " + rawKind));
        String prompt = string(obj, "prompt", path, TourValidator.MAX_PROMPT);
        String explanation = string(obj, "explanation", path, TourValidator.MAX_PROMPT);
        List<TourCheck.Choice> choices = new ArrayList<>();
        JsonArray choiceArray = array(obj, "choices", path, TourValidator.MAX_CHOICES, false);
        for (int i = 0; i < choiceArray.elements().size(); i++) {
            String at = path + ".choices[" + i + "]";
            JsonObject choice = object(choiceArray.elements().get(i), at);
            Optional<TourCheck.Location> location = Optional.empty();
            if (choice.get("at") instanceof JsonObject where) {
                location = Optional.of(new TourCheck.Location(string(where, "file", at + ".at", MAX_PATH),
                        integer(where, "line", at + ".at")));
            }
            choices.add(new TourCheck.Choice(string(choice, "text", at, TourValidator.MAX_CHOICE), location));
        }
        OptionalInt answer = OptionalInt.empty();
        if (obj.has("answer")) {
            answer = OptionalInt.of(integer(obj, "answer", path));
        }
        List<TourCheck> alternates = new ArrayList<>();
        JsonArray alternateArray = array(obj, "alternates", path, MAX_ALTERNATES, false);
        if (!topLevel && !alternateArray.elements().isEmpty()) {
            throw new InvalidTour(path + ".alternates: an alternate cannot have alternates");
        }
        for (int i = 0; i < alternateArray.elements().size(); i++) {
            alternates.add(checkFromJson(alternateArray.elements().get(i), path + ".alternates[" + i + "]", false));
        }
        return new TourCheck(id, kind, prompt, choices, answer, explanation, alternates);
    }

    // ---- model → JSON ----

    public static JsonValue stepToJson(TourStep step) {
        List<JsonValue> anchors = new ArrayList<>();
        for (TourAnchor anchor : step.anchors()) {
            anchors.add(JsonObject.empty().put("file", new JsonString(anchor.file()))
                    .put("startKey", new JsonString(anchor.startKey()))
                    .put("endKey", new JsonString(anchor.endKey())));
        }
        List<JsonValue> notes = new ArrayList<>();
        for (ImpactNote note : step.impactNotes()) {
            notes.add(JsonObject.empty().put("file", new JsonString(note.file()))
                    .put("line", JsonNumber.of(note.line())).put("text", new JsonString(note.text())));
        }
        List<JsonValue> checks = new ArrayList<>();
        for (TourCheck check : step.checks()) {
            checks.add(checkToJson(check));
        }
        return JsonObject.empty().put("id", new JsonString(step.id()))
                .put("title", new JsonString(step.title()))
                .put("narrative", new JsonString(step.narrative()))
                .put("anchors", JsonArray.of(anchors))
                .put("impactNotes", JsonArray.of(notes))
                .put("checks", JsonArray.of(checks));
    }

    private static JsonValue checkToJson(TourCheck check) {
        List<JsonValue> choices = new ArrayList<>();
        for (TourCheck.Choice choice : check.choices()) {
            JsonObject obj = JsonObject.empty().put("text", new JsonString(choice.text()));
            choice.at().ifPresent(at -> obj.put("at", JsonObject.empty()
                    .put("file", new JsonString(at.file())).put("line", JsonNumber.of(at.line()))));
            choices.add(obj);
        }
        List<JsonValue> alternates = new ArrayList<>();
        for (TourCheck alternate : check.alternates()) {
            alternates.add(checkToJson(alternate));
        }
        JsonObject obj = JsonObject.empty().put("id", new JsonString(check.id()))
                .put("kind", new JsonString(check.kind().wireName()))
                .put("prompt", new JsonString(check.prompt()))
                .put("choices", JsonArray.of(choices))
                .put("explanation", new JsonString(check.explanation()))
                .put("alternates", JsonArray.of(alternates));
        check.answer().ifPresent(answer -> obj.put("answer", JsonNumber.of(answer)));
        return obj;
    }

    public static JsonValue recordToJson(TourRecord record) {
        List<JsonValue> steps = new ArrayList<>();
        for (TourStep step : record.tour().steps()) {
            steps.add(stepToJson(step));
        }
        JsonObject progress = JsonObject.empty();
        for (StepProgress step : record.progress().values()) {
            JsonObject checks = JsonObject.empty();
            for (CheckProgress check : step.checks().values()) {
                JsonObject obj = JsonObject.empty().put("attempt", JsonNumber.of(check.attempt()))
                        .put("status", new JsonString(check.status().name()));
                check.riskAnswer().ifPresent(text -> obj.put("riskAnswer", new JsonString(text)));
                check.agentReason().ifPresent(text -> obj.put("agentReason", new JsonString(text)));
                check.lastExplanation().ifPresent(text -> obj.put("lastExplanation", new JsonString(text)));
                checks.put(check.checkId(), obj);
            }
            JsonObject obj = JsonObject.empty()
                    .put("hunkDigests", JsonArray.of(step.hunkDigests().stream().<JsonValue>map(JsonString::new).toList()))
                    .put("decision", new JsonString(step.decision().name()))
                    .put("stale", new JsonBoolean(step.stale()))
                    .put("checks", checks);
            step.overrideReason().ifPresent(reason -> obj.put("override", new JsonString(reason)));
            progress.put(step.stepId(), obj);
        }
        JsonObject overrides = JsonObject.empty();
        record.hunkOverrides().forEach((digest, override) -> overrides.put(digest, JsonObject.empty()
                .put("decision", new JsonString(override.decision().wireName()))
                .put("reason", new JsonString(override.reason()))));
        JsonObject rows = JsonObject.empty();
        record.hunkRows().forEach((digest, keys) ->
                rows.put(digest, JsonArray.of(keys.stream().<JsonValue>map(JsonString::new).toList())));
        return JsonObject.empty()
                .put("tour", JsonObject.empty()
                        .put("scopeId", new JsonString(record.tour().scopeId()))
                        .put("fingerprint", new JsonString(record.tour().diffFingerprint()))
                        .put("steps", JsonArray.of(steps)))
                .put("progress", progress)
                .put("hunkOverrides", overrides)
                .put("hunkRows", rows)
                .put("reviewAnyway", new JsonBoolean(record.reviewAnyway()))
                .put("shelved", new JsonBoolean(record.shelved()));
    }

    // ---- JSON → record (lenient) ----

    public static Optional<TourRecord> recordFromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("tour") instanceof JsonObject tourObj)
                || !(tourObj.get("scopeId") instanceof JsonString scopeId)
                || !(tourObj.get("fingerprint") instanceof JsonString fingerprint)) {
            return Optional.empty();
        }
        List<TourStep> steps = new ArrayList<>();
        if (tourObj.get("steps") instanceof JsonArray array) {
            for (int i = 0; i < array.elements().size(); i++) {
                try {
                    steps.add(stepFromJson(array.elements().get(i), "steps[" + i + "]"));
                } catch (InvalidTour | RuntimeException e) {
                    // Dropped: TourStore re-requests a dropped step (spec §7).
                }
            }
        }
        ReviewTour tour = new ReviewTour(scopeId.value(), fingerprint.value(), steps);
        Map<String, StepProgress> progress = new LinkedHashMap<>();
        if (root.get("progress") instanceof JsonObject progressObj) {
            for (TourStep step : steps) {
                if (progressObj.get(step.id()) instanceof JsonObject stepObj) {
                    decodeProgress(step.id(), stepObj).ifPresent(p -> progress.put(step.id(), p));
                }
            }
        }
        Map<String, HunkOverride> overrides = new LinkedHashMap<>();
        if (root.get("hunkOverrides") instanceof JsonObject overrideObj) {
            overrideObj.members().forEach((digest, entry) -> {
                if (entry instanceof JsonObject obj && obj.get("decision") instanceof JsonString decision
                        && obj.get("reason") instanceof JsonString reason) {
                    ReviewVerdict.Decision.fromWire(decision.value())
                            .ifPresent(d -> overrides.put(digest, new HunkOverride(d, reason.value())));
                }
            });
        }
        Map<String, List<String>> rows = new LinkedHashMap<>();
        if (root.get("hunkRows") instanceof JsonObject rowsObj) {
            rowsObj.members().forEach((digest, entry) -> rows.put(digest, strings(entry)));
        }
        boolean reviewAnyway = root.get("reviewAnyway") instanceof JsonBoolean flag && flag.value();
        boolean shelved = root.get("shelved") instanceof JsonBoolean flag && flag.value();
        return Optional.of(new TourRecord(tour, progress, overrides, rows, reviewAnyway, shelved));
    }

    private static Optional<StepProgress> decodeProgress(String stepId, JsonObject obj) {
        StepProgress.Decision decision;
        try {
            decision = obj.get("decision") instanceof JsonString raw
                    ? StepProgress.Decision.valueOf(raw.value()) : StepProgress.Decision.NONE;
        } catch (IllegalArgumentException e) {
            decision = StepProgress.Decision.NONE;
        }
        Map<String, CheckProgress> checks = new LinkedHashMap<>();
        if (obj.get("checks") instanceof JsonObject checksObj) {
            checksObj.members().forEach((checkId, entry) -> {
                if (entry instanceof JsonObject check) {
                    decodeCheck(checkId, check).ifPresent(c -> checks.put(checkId, c));
                }
            });
        }
        Optional<String> override = obj.get("override") instanceof JsonString reason
                ? Optional.of(reason.value()) : Optional.empty();
        boolean stale = obj.get("stale") instanceof JsonBoolean flag && flag.value();
        return Optional.of(new StepProgress(stepId, strings(obj.get("hunkDigests")), checks, decision, override, stale));
    }

    private static Optional<CheckProgress> decodeCheck(String checkId, JsonObject obj) {
        try {
            CheckProgress.Status status = obj.get("status") instanceof JsonString raw
                    ? CheckProgress.Status.valueOf(raw.value()) : CheckProgress.Status.OPEN;
            int attempt = obj.get("attempt") instanceof JsonNumber number ? number.asInt() : 0;
            return Optional.of(new CheckProgress(checkId, attempt, status, text(obj, "riskAnswer"),
                    text(obj, "agentReason"), text(obj, "lastExplanation")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---- helpers ----

    private static JsonObject object(JsonValue value, String path) throws InvalidTour {
        if (value instanceof JsonObject obj) {
            return obj;
        }
        throw new InvalidTour(path + ": expected an object");
    }

    private static String string(JsonObject obj, String key, String path, int max) throws InvalidTour {
        return optionalString(obj, key, path, max)
                .orElseThrow(() -> new InvalidTour(path + "." + key + ": missing or blank"));
    }

    private static Optional<String> optionalString(JsonObject obj, String key, String path, int max)
            throws InvalidTour {
        JsonValue value = obj.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof JsonString string)) {
            throw new InvalidTour(path + "." + key + ": expected a string");
        }
        if (string.value().length() > max) {
            throw new InvalidTour(path + "." + key + ": longer than " + max + " characters");
        }
        return string.value().isBlank() ? Optional.empty() : Optional.of(string.value());
    }

    private static int integer(JsonObject obj, String key, String path) throws InvalidTour {
        if (obj.get(key) instanceof JsonNumber number) {
            try {
                return number.asInt();
            } catch (RuntimeException e) {
                throw new InvalidTour(path + "." + key + ": expected an integer");
            }
        }
        throw new InvalidTour(path + "." + key + ": expected an integer");
    }

    private static JsonArray array(JsonObject obj, String key, String path, int max, boolean required)
            throws InvalidTour {
        JsonValue value = obj.get(key);
        if (value == null) {
            if (required) {
                throw new InvalidTour(path + "." + key + ": missing");
            }
            return JsonArray.of(List.of());
        }
        if (!(value instanceof JsonArray array)) {
            throw new InvalidTour(path + "." + key + ": expected an array");
        }
        if (array.elements().size() > max) {
            throw new InvalidTour(path + "." + key + ": at most " + max + " entries");
        }
        return array;
    }

    private static List<String> strings(JsonValue value) {
        if (!(value instanceof JsonArray array)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonString string) {
                out.add(string.value());
            }
        }
        return out;
    }

    private static Optional<String> text(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString string ? Optional.of(string.value()) : Optional.empty();
    }
}
