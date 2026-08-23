package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.RecheckAssessment;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.Severity;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Encodes and decodes the Review MCP payloads (schema §§1-4), keeping the
 * shape of the wire format out of {@link McpToolRouter}'s dispatch logic.
 *
 * <p>Every inbound text field goes through
 * {@link PromptSafety#checkInboundText}: the diff an agent read is untrusted
 * input, a finding may quote it verbatim, and that text can later be typed
 * into a live terminal.</p>
 */
final class ReviewToolCodec {

    private ReviewToolCodec() {
    }

    // ---- review_scope (drydock -> agent) ------------------------------------

    /** One page of {@code review_scope}: the rows that fit, and where to resume. */
    record ScopePage(List<JsonValue> hunks, Optional<String> cursor, boolean truncatedHunk) {
    }

    static JsonValue scopeToJson(ReviewScope scope) {
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(scope.id()));
        obj.put("kind", new JsonString(scope.kind().name().toLowerCase(java.util.Locale.ROOT)));
        obj.put("repoRoot", new JsonString(scope.repoRoot().toString()));
        obj.put("worktree", scope.worktree()
                .<JsonValue>map(path -> new JsonString(path.toString()))
                .orElse(JsonValue.JsonNull.INSTANCE));
        obj.put("base", new JsonString(scope.base()));
        obj.put("head", new JsonString(scope.head()));
        obj.put("pr", scope.pr().<JsonValue>map(pr -> {
            JsonObject prObj = JsonObject.empty();
            prObj.put("number", JsonNumber.of(pr.number()));
            pr.url().ifPresent(url -> prObj.put("url", new JsonString(url)));
            return prObj;
        }).orElse(JsonValue.JsonNull.INSTANCE));
        obj.put("sessionId", scope.sessionId()
                .<JsonValue>map(id -> new JsonString(id.toString()))
                .orElse(JsonValue.JsonNull.INSTANCE));
        return obj;
    }

    static JsonValue filesToJson(UnifiedDiff diff) {
        List<JsonValue> files = new ArrayList<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            JsonObject obj = JsonObject.empty();
            obj.put("path", new JsonString(file.path()));
            obj.put("status", new JsonString(file.kind()));
            obj.put("insertions", JsonNumber.of(file.insertions()));
            obj.put("deletions", JsonNumber.of(file.deletions()));
            files.add(obj);
        }
        return new JsonArray(files);
    }

    /**
     * Pages the diff's hunks under {@code maxBytes}, resuming after
     * {@code cursor}.
     *
     * <p>A single hunk larger than the whole budget is returned
     * <em>truncated</em> with {@code truncated: true}, never dropped and
     * never allowed to fail the call: one generated file must not make a
     * scope unreadable (schema, "Budget").</p>
     */
    static ScopePage pageHunks(UnifiedDiff diff, Optional<String> cursor, int maxBytes) {
        List<HunkRef> refs = flatten(diff);
        int start = cursor.map(value -> indexOf(refs, value)).orElse(0);
        List<JsonValue> page = new ArrayList<>();
        int budget = maxBytes;
        boolean truncatedHunk = false;

        int i = start;
        for (; i < refs.size(); i++) {
            HunkRef ref = refs.get(i);
            JsonObject encoded = hunkToJson(ref, budget);
            int size = approximateBytes(encoded);
            boolean truncated = encoded.get("truncated") instanceof JsonBoolean marker && marker.value();
            if (truncated && !page.isEmpty()) {
                break;
            }
            page.add(encoded);
            budget -= size;
            truncatedHunk |= truncated;
            if (truncated) {
                i++;
                break;
            }
        }
        Optional<String> next = i < refs.size() ? Optional.of(refs.get(i).id()) : Optional.empty();
        return new ScopePage(page, next, truncatedHunk);
    }

    /** A hunk plus the file it came from and the stable id the cursor uses. */
    private record HunkRef(String file, int index, UnifiedDiff.Hunk hunk) {
        String id() {
            return app.drydock.review.ReviewIntent.hunkId(file, index);
        }
    }

    private static List<HunkRef> flatten(UnifiedDiff diff) {
        List<HunkRef> refs = new ArrayList<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            int index = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                refs.add(new HunkRef(file.path(), index++, hunk));
            }
        }
        return refs;
    }

    private static int indexOf(List<HunkRef> refs, String cursor) {
        for (int i = 0; i < refs.size(); i++) {
            if (refs.get(i).id().equals(cursor)) {
                return i;
            }
        }
        return 0;
    }

    private static JsonObject hunkToJson(HunkRef ref, int maxBytes) {
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(ref.id()));
        obj.put("file", new JsonString(ref.file()));
        List<UnifiedDiff.Line> lines = ref.hunk().lines();
        obj.put("oldStart", JsonNumber.of(firstOld(lines)));
        obj.put("oldCount", JsonNumber.of((int) lines.stream()
                .filter(line -> line.oldLine().isPresent()).count()));
        obj.put("newStart", JsonNumber.of(firstNew(lines)));
        obj.put("newCount", JsonNumber.of((int) lines.stream()
                .filter(line -> line.newLine().isPresent()).count()));

        List<JsonValue> encoded = new ArrayList<>();
        int contentBudget = Math.max(0, maxBytes - 20); // reserve ,"truncated":true
        for (UnifiedDiff.Line line : lines) {
            JsonObject lineObj = lineToJson(line, truncateUtf8(line.text(), contentBudget));
            encoded.add(lineObj);
            obj.put("lines", new JsonArray(encoded));
            if (approximateBytes(obj) > contentBudget) {
                encoded.remove(encoded.size() - 1);
                obj.put("lines", new JsonArray(encoded));
                obj.put("truncated", new JsonBoolean(true));
                return obj;
            }
            if (!line.text().equals(((JsonString) lineObj.get("text")).value())) {
                obj.put("truncated", new JsonBoolean(true));
                return obj;
            }
        }
        obj.put("lines", new JsonArray(encoded));
        return obj;
    }

    private static JsonObject lineToJson(UnifiedDiff.Line line, String text) {
        JsonObject lineObj = JsonObject.empty();
        lineObj.put("key", new JsonString(line.lineKey()));
        lineObj.put("sign", new JsonString(switch (line.kind()) {
            case ADD -> "+";
            case DEL -> "-";
            case CONTEXT -> " ";
        }));
        lineObj.put("old", line.oldLine().isPresent()
                ? JsonNumber.of(line.oldLine().getAsInt()) : JsonValue.JsonNull.INSTANCE);
        lineObj.put("new", line.newLine().isPresent()
                ? JsonNumber.of(line.newLine().getAsInt()) : JsonValue.JsonNull.INSTANCE);
        lineObj.put("text", new JsonString(text));
        return lineObj;
    }

    /** Returns a UTF-8-bounded prefix without ever serializing a huge source line. */
    private static String truncateUtf8(String text, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        int used = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int bytes = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (used + bytes > maxBytes) {
                return text.substring(0, end) + "…";
            }
            used += bytes;
            end += Character.charCount(codePoint);
        }
        return text;
    }

    private static int firstOld(List<UnifiedDiff.Line> lines) {
        return lines.stream().filter(line -> line.oldLine().isPresent())
                .mapToInt(line -> line.oldLine().getAsInt()).min().orElse(0);
    }

    private static int firstNew(List<UnifiedDiff.Line> lines) {
        return lines.stream().filter(line -> line.newLine().isPresent())
                .mapToInt(line -> line.newLine().getAsInt()).min().orElse(0);
    }

    /**
     * Byte cost of an encoded value, measured on its own serialization rather
     * than estimated: the budget exists to keep a response under a hard limit,
     * and an estimate that drifts would either waste the budget or blow it.
     * Package-private so {@link McpToolRouter} can charge the {@code
     * sections} include against the same budget {@code hunks} pays from.
     */
    static int approximateBytes(JsonValue value) {
        return app.drydock.state.json.JsonWriter.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * drydock's computed grouping ({@code review_scope}'s {@code sections}
     * include), offered so an agent can accept-and-name it rather than
     * regroup from scratch and lose the header conventions and the
     * dependency order {@link Sections#of} already worked out.
     */
    static JsonValue sectionsToJson(List<Sections.Section> sections) {
        List<JsonValue> entries = new ArrayList<>();
        for (Sections.Section section : sections) {
            JsonObject obj = JsonObject.empty();
            obj.put("title", new JsonString(section.title()));
            obj.put("files", new JsonArray(section.files().stream()
                    .map(file -> (JsonValue) new JsonString(file)).toList()));
            obj.put("hunkIds", new JsonArray(section.hunkIds().stream()
                    .map(id -> (JsonValue) new JsonString(id)).toList()));
            section.hubSymbol().ifPresent(hub -> obj.put("hubSymbol", new JsonString(hub)));
            entries.add(obj);
        }
        return new JsonArray(entries);
    }

    // ---- review_intents (agent -> drydock) ----------------------------------

    static List<ReviewIntent> intentsFromJson(JsonValue value) throws McpToolException {
        if (!(value instanceof JsonArray array)) {
            throw new McpToolException("intents must be an array");
        }
        List<ReviewIntent> intents = new ArrayList<>();
        // Provisional only: IntentGrouping.set re-assigns a dense 1..N over
        // the order `reads` produces and discards whatever arrives here, so
        // this numbering never reaches a rail. It is kept because a
        // ReviewIntent has to carry SOME number to be constructed at all.
        int number = 1;
        for (JsonValue element : array.elements()) {
            if (!(element instanceof JsonObject obj)) {
                throw new McpToolException("each intent must be an object");
            }
            String id = requireString(obj, "id");
            intents.add(new ReviewIntent(id, number++,
                    PromptSafety.checkInboundText(requireString(obj, "title"), "intent.title"),
                    optionalString(obj, "kind").flatMap(ReviewIntent.Kind::fromWire)
                            .orElse(ReviewIntent.Kind.CHANGE),
                    optionalString(obj, "risk").flatMap(ReviewIntent.Risk::fromWire)
                            .orElse(ReviewIntent.Risk.NONE),
                    PromptSafety.checkInboundText(optionalString(obj, "rationale").orElse(""),
                            "intent.rationale"),
                    stringList(obj, "hunkIds"),
                    collapseFromJson(obj),
                    obj.get("autoApprove") instanceof JsonBoolean auto && auto.value(),
                    readsFromJson(obj, id)));
        }
        checkReadsResolve(intents);
        return List.copyOf(intents);
    }

    /**
     * One intent's {@code reads}, rejecting a malformed one rather than
     * quietly reading it as an empty list.
     *
     * <p>Decoded here and not through {@link #stringList}, which answers
     * {@code List.of()} for any non-array and drops any non-string element.
     * That lenience predates this task and is shared with {@code hunkIds},
     * where a dropped entry costs at worst one hunk's membership in a group
     * a human can see and fix. It costs far more here: {@code
     * "reads":"the-guard"} -- one dependency written without the brackets,
     * which is the likeliest way to get this wrong -- would decode as
     * "declared nothing", and the rail would then render the exact REVERSE of
     * the order the agent asserted. With no diagnostic on any surface, and
     * {@code reads} echoed on no outbound wire, the agent could not discover
     * it had happened. Absent and broken must not look the same -- the same
     * rule {@link app.drydock.review.Graphs#topologicalOrder} keeps for an
     * edge pointing outside its nodes, and the reason {@link
     * #checkReadsResolve} exists at all.</p>
     *
     * <p>An explicit {@code null} is absent, not broken: it is how several
     * clients spell an omitted optional field.</p>
     */
    private static List<String> readsFromJson(JsonObject obj, String id) throws McpToolException {
        JsonValue raw = obj.get("reads");
        if (raw == null || raw instanceof JsonValue.JsonNull) {
            return List.of();
        }
        if (!(raw instanceof JsonArray array)) {
            throw new McpToolException("intent '" + id + "' has a reads that is not an array; "
                    + "one dependency is [\"other-id\"], not \"other-id\"");
        }
        List<String> reads = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (!(element instanceof JsonString read)) {
                throw new McpToolException("intent '" + id + "' has a reads entry that is not a "
                        + "string; every entry names an intent id in this call");
            }
            reads.add(read.value());
        }
        return List.copyOf(reads);
    }

    /**
     * Rejects the whole batch when a {@code reads} names an id no intent in
     * the same call carries.
     *
     * <p>Checked HERE, at decode, and not where the order is actually built:
     * {@link app.drydock.review.Graphs#topologicalOrder} does refuse an edge
     * pointing outside its nodes -- deliberately, so absent and broken cannot
     * look the same -- but it refuses with an {@link IllegalArgumentException}
     * on whatever thread {@code IntentGrouping.set} was called from, where
     * the agent that sent the payload never hears about it. An MCP error
     * naming the id and the intent that declared it is the report the agent
     * can act on.</p>
     *
     * <p>All-or-nothing, like the rest of the batch: half a grouping, with
     * some intents' declared order silently dropped, is worse than none.</p>
     */
    private static void checkReadsResolve(List<ReviewIntent> intents) throws McpToolException {
        Set<String> ids = new LinkedHashSet<>();
        for (ReviewIntent intent : intents) {
            ids.add(intent.id());
        }
        for (ReviewIntent intent : intents) {
            for (String read : intent.reads()) {
                if (!ids.contains(read)) {
                    throw new McpToolException("intent '" + intent.id() + "' reads '" + read
                            + "', which is not an intent in this call");
                }
            }
        }
    }

    private static Optional<ReviewIntent.Collapse> collapseFromJson(JsonObject obj)
            throws McpToolException {
        if (!(obj.get("collapse") instanceof JsonObject collapse)) {
            return Optional.empty();
        }
        return Optional.of(new ReviewIntent.Collapse(
                optionalString(collapse, "reason").orElse("generated"),
                PromptSafety.checkInboundText(optionalString(collapse, "evidence").orElse(""),
                        "collapse.evidence"),
                collapse.get("hunkCount") instanceof JsonNumber count ? count.asInt() : 0,
                collapse.get("fileCount") instanceof JsonNumber count ? count.asInt() : 0));
    }

    // ---- review_recheck (agent -> drydock) ----------------------------------

    /**
     * Decodes {@code review_recheck}'s {@code assessments} array, translating
     * each wire {@code hunkId} into the content digest a verdict is actually
     * keyed by (spec §9.7).
     *
     * <p><strong>The two ids are different things.</strong> {@link
     * ReviewIntent#hunkId} is POSITIONAL -- a file and an index into that
     * file's hunks -- and is what an agent reads off {@code review_scope}.
     * {@link HunkDigest#of} is CONTENT-ADDRESSED and deliberately excludes
     * line numbers, so a hunk that merely moved keeps its digest. Storing the
     * positional id would strand every assessment the moment the diff
     * re-hunked; this walks the diff the same way {@code IntentHunks.digestsOf}
     * does and stores the digest.</p>
     *
     * <p>The base PAIR is derived, never taken from the wire. {@code fromBase}
     * is the base the hunk's own verdict was recorded against and {@code
     * toBase} is the scope's current base commit, so the key this writes is
     * by construction the key the board later reads with. An agent-supplied
     * pair could name commits no verdict was ever judged against, and the
     * recheck would then sit in the store answering a question nobody asks --
     * absent and broken looking the same again.</p>
     *
     * <p>Five things reject the whole batch, each naming the offending id: a
     * {@code hunkId} that resolves to nothing in the current diff; a hunk that
     * carries no verdict at all, which has no {@code fromBase} and therefore
     * nothing to recheck; a mark with no {@code why}, which is the reflexive
     * signal this tool is asymmetric to avoid; a {@code why} that fails {@link
     * PromptSafety}; and an {@code affected} or {@code why} of the wrong JSON
     * type, which must not decode as "said nothing" (see {@link
     * #affectedFromJson}). All-or-nothing like the rest of this surface: a
     * silently skipped entry is an agent's recheck that the human believes
     * happened and did not.</p>
     */
    static List<RecheckAssessment> assessmentsFromJson(String scopeId, JsonValue value, UnifiedDiff diff,
                                                       Map<String, ReviewVerdict> verdictsByDigest,
                                                       String toBase, Instant at)
            throws McpToolException {
        if (!(value instanceof JsonArray array)) {
            throw new McpToolException("assessments must be an array");
        }
        List<RecheckAssessment> assessments = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (!(element instanceof JsonObject obj)) {
                throw new McpToolException("each assessment must be an object");
            }
            String hunkId = requireString(obj, "hunkId");
            String digest = digestOfHunkId(diff, hunkId).orElseThrow(() -> new McpToolException(
                    "assessment names hunkId '" + hunkId + "', which is not a hunk of this scope's "
                            + "current diff; hunk ids are the ones review_scope reports and are "
                            + "positional, so a re-diff can strand them"));
            ReviewVerdict verdict = verdictsByDigest.get(digest);
            if (verdict == null) {
                throw new McpToolException("assessment names hunkId '" + hunkId + "', which carries "
                        + "no verdict; a recheck says whether a base move undermines a decision, "
                        + "and there is no decision on that hunk to undermine");
            }
            boolean affected = affectedFromJson(obj, hunkId);
            String why = PromptSafety.checkInboundText(whyFromJson(obj, hunkId), "assessment.why");
            if (affected && why.isBlank()) {
                throw new McpToolException("assessment marks hunkId '" + hunkId + "' affected with "
                        + "no why; a staleness signal asserted with no reason is the reflexive "
                        + "click this recheck is asymmetric to avoid, and a human will be shown "
                        + "the reason as the whole justification for re-reading the hunk");
            }
            assessments.add(new RecheckAssessment(scopeId, digest, verdict.baseCommit(), toBase,
                    affected, why, at));
        }
        return List.copyOf(assessments);
    }

    /**
     * One assessment's {@code affected}, refusing anything that is not a
     * boolean rather than quietly reading it as {@code false}.
     *
     * <p>The same rule {@link #readsFromJson} keeps, and for the same reason:
     * absent and broken must not look the same. {@code "affected":"true"} from
     * a stringifying client -- not hypothetical, {@code
     * McpToolRouter.optionalIntArg} exists to accommodate one -- would
     * otherwise decode as "the agent looked and found nothing", which is the
     * one answer this tool must never manufacture. The direction is inert, so
     * nothing unsafe follows; what follows is a recheck the human believes
     * happened and did not, which is the failure this whole surface is drawn
     * around.</p>
     *
     * <p>Absent, and an explicit {@code null}, stay ABSENT and decode as
     * {@code false}: an assessment that says nothing about a hunk is a legal
     * thing to send, and {@code null} is how several clients spell an omitted
     * optional field.</p>
     */
    private static boolean affectedFromJson(JsonObject obj, String hunkId) throws McpToolException {
        JsonValue raw = obj.get("affected");
        if (raw == null || raw instanceof JsonValue.JsonNull) {
            return false;
        }
        if (!(raw instanceof JsonBoolean flag)) {
            throw new McpToolException("assessment for hunkId '" + hunkId + "' has an affected that "
                    + "is not a boolean; it is true or false, not \"true\" or 1");
        }
        return flag.value();
    }

    /** One assessment's {@code why}, refusing a non-string for {@link #affectedFromJson}'s reason. */
    private static String whyFromJson(JsonObject obj, String hunkId) throws McpToolException {
        JsonValue raw = obj.get("why");
        if (raw == null || raw instanceof JsonValue.JsonNull) {
            return "";
        }
        if (!(raw instanceof JsonString why)) {
            throw new McpToolException("assessment for hunkId '" + hunkId + "' has a why that is "
                    + "not a string; it is the sentence a human reads as the reason");
        }
        return why.value();
    }

    /**
     * The content digest of the hunk {@code hunkId} names in {@code diff}, or
     * empty when it names no hunk there -- an unknown file, or an index past
     * that file's hunk count.
     */
    private static Optional<String> digestOfHunkId(UnifiedDiff diff, String hunkId) {
        return ReviewIntent.parseHunkId(hunkId).flatMap(anchor -> {
            for (UnifiedDiff.FileDiff file : diff.files()) {
                if (!file.path().equals(anchor.file())) {
                    continue;
                }
                List<UnifiedDiff.Hunk> hunks = file.hunks();
                return anchor.hunkIndex() < hunks.size()
                        ? Optional.of(HunkDigest.of(file.path(), hunks.get(anchor.hunkIndex())))
                        : Optional.empty();
            }
            return Optional.empty();
        });
    }

    // ---- review_finding (agent -> drydock) ----------------------------------

    /**
     * Decodes one finding. {@code existing} is the value already stored under
     * the same key, if any: a re-run upserts, and the human's thread, severity
     * override and resolution are theirs -- an agent re-stating its finding
     * must not quietly undo them.
     */
    static ReviewAnnotation findingFromJson(String scopeId, JsonObject obj, String author,
                                            Optional<ReviewAnnotation> existing)
            throws McpToolException {
        String id = requireString(obj, "id");
        if (!(obj.get("anchor") instanceof JsonObject anchor)) {
            throw new McpToolException("finding '" + id + "' has no anchor");
        }
        String file = requireString(anchor, "file");
        String startKey = requireString(anchor, "startKey");
        String endKey = optionalString(anchor, "endKey").orElse(startKey);

        List<ReviewAnnotation.Message> thread = existing.map(ReviewAnnotation::thread)
                .filter(messages -> !messages.isEmpty())
                .orElse(null);
        String body = PromptSafety.checkInboundText(requireString(obj, "body"), "finding.body");
        if (thread == null) {
            thread = List.of(new ReviewAnnotation.Message(author, Instant.now(), body));
        } else {
            // Keep the conversation, refresh the reviewer's opening statement.
            List<ReviewAnnotation.Message> updated = new ArrayList<>(thread);
            updated.set(0, new ReviewAnnotation.Message(author, updated.get(0).at(), body));
            thread = List.copyOf(updated);
        }

        return new ReviewAnnotation(scopeId, id,
                optionalString(obj, "intentId"),
                file, startKey, endKey,
                optionalString(obj, "severity").flatMap(Severity::fromWire).orElse(Severity.QUESTION),
                optionalString(obj, "confidence").flatMap(Confidence::fromWire).orElse(Confidence.MEDIUM),
                Optional.ofNullable(PromptSafety.checkInboundText(
                        optionalString(obj, "title").orElse(null), "finding.title")),
                author,
                existing.map(ReviewAnnotation::at).orElseGet(Instant::now),
                evidenceFromJson(obj),
                patchFromJson(obj),
                deviatesFromJson(obj),
                asksFromJson(obj),
                thread,
                // The human's override and status survive a re-run: they are
                // the human's, not the agent's, to restate.
                existing.flatMap(ReviewAnnotation::severityOverride),
                existing.map(ReviewAnnotation::status).orElse(AnnotationStatus.OPEN),
                // Likewise GitHub state and the human's posting intent: an
                // agent re-stating a finding must not un-post or un-link it.
                existing.flatMap(ReviewAnnotation::github),
                existing.map(ReviewAnnotation::postToPr).orElse(false));
    }

    private static List<ReviewAnnotation.Evidence> evidenceFromJson(JsonObject obj)
            throws McpToolException {
        if (!(obj.get("evidence") instanceof JsonArray array)) {
            return List.of();
        }
        List<ReviewAnnotation.Evidence> evidence = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonObject entry) {
                evidence.add(new ReviewAnnotation.Evidence(
                        PromptSafety.checkInboundText(optionalString(entry, "label").orElse(""),
                                "evidence.label"),
                        PromptSafety.checkInboundText(requireString(entry, "code"), "evidence.code"),
                        optionalString(entry, "language").orElse("text")));
            }
        }
        return List.copyOf(evidence);
    }

    static Optional<ReviewAnnotation.Patch> patchFromJson(JsonObject obj) throws McpToolException {
        if (!(obj.get("patch") instanceof JsonObject patch)) {
            return Optional.empty();
        }
        return Optional.of(new ReviewAnnotation.Patch(
                PromptSafety.checkInboundText(requireString(patch, "unified"), "patch.unified"),
                PromptSafety.checkInboundText(optionalString(patch, "summary").orElse(""),
                        "patch.summary")));
    }

    private static Optional<ReviewAnnotation.DeviatesFrom> deviatesFromJson(JsonObject obj)
            throws McpToolException {
        if (!(obj.get("deviatesFrom") instanceof JsonObject deviation)) {
            return Optional.empty();
        }
        return Optional.of(new ReviewAnnotation.DeviatesFrom(
                PromptSafety.checkInboundText(requireString(deviation, "prompt"), "deviatesFrom.prompt"),
                deviation.get("step") instanceof JsonNumber step
                        ? Optional.of(step.asInt()) : Optional.empty()));
    }

    private static List<ReviewAnnotation.Ask> asksFromJson(JsonObject obj) throws McpToolException {
        if (!(obj.get("asks") instanceof JsonArray array)) {
            return List.of();
        }
        List<ReviewAnnotation.Ask> asks = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonObject entry) {
                asks.add(new ReviewAnnotation.Ask(
                        PromptSafety.checkInboundText(optionalString(entry, "label").orElse("Ask"),
                                "ask.label"),
                        PromptSafety.checkInboundText(requireString(entry, "question"),
                                "ask.question")));
            }
        }
        return List.copyOf(asks);
    }

    // ---- review_state (drydock -> agent) ------------------------------------

    static JsonValue findingStateToJson(ReviewAnnotation finding) {
        JsonObject obj = JsonObject.empty();
        obj.put("id", new JsonString(finding.id()));
        obj.put("severity", new JsonString(finding.effectiveSeverity().wireName()));
        obj.put("resolved", new JsonBoolean(finding.resolved()));
        obj.put("status", new JsonString(finding.status().name()));
        List<JsonValue> messages = new ArrayList<>();
        for (ReviewAnnotation.Message message : finding.thread()) {
            JsonObject messageObj = JsonObject.empty();
            messageObj.put("actor", new JsonString(message.author()));
            messageObj.put("at", new JsonString(message.at().toString()));
            messageObj.put("body", new JsonString(message.text()));
            messages.add(messageObj);
        }
        obj.put("messages", new JsonArray(messages));
        return obj;
    }

    // ---- shared helpers -----------------------------------------------------

    static String requireString(JsonObject obj, String key) throws McpToolException {
        if (obj.get(key) instanceof JsonString value && !value.value().isBlank()) {
            return value.value();
        }
        throw new McpToolException("missing or blank string field '" + key + "'");
    }

    static Optional<String> optionalString(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString value ? Optional.of(value.value()) : Optional.empty();
    }

    private static List<String> stringList(JsonObject obj, String key) {
        if (!(obj.get(key) instanceof JsonArray array)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonString value) {
                values.add(value.value());
            }
        }
        return List.copyOf(values);
    }
}
