package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The store of Review findings and verdicts: in-memory, mutated on the FX
 * thread by the Review destination and on its own executor by the MCP tool
 * router, persisted as a JSON file <em>alongside</em> the application state
 * file ({@code annotations.json} beside {@code state.json}).
 *
 * <p><strong>Everything is keyed by {@code (scopeId, id)}.</strong> Finding
 * and intent ids are agent-chosen and repeat across scopes -- a reviewer
 * re-run against two worktrees produces {@code f_leak_1} in both -- so
 * keying on the id alone made resolving a finding in one worktree resolve it
 * in another. That was a real bug; {@link ReviewAnnotation.Key} is the fix,
 * and {@code AnnotationStoreTest} keeps it fixed.</p>
 *
 * <p>Persisting to a sibling file -- rather than adding a member to
 * {@code state.json} -- keeps this store the single writer of its file:
 * {@code SessionManager} snapshots and rewrites {@code state.json}
 * wholesale, so a second writer there would race it. Saves run on a
 * single background thread (writes are serialized; the newest snapshot
 * wins) with the same temp-file-and-atomic-rename pattern as the state
 * repository. Rapid mutations coalesce. Loading is lenient: a missing or
 * malformed file yields an empty store, and one malformed entry never
 * discards the rest.</p>
 */
public final class AnnotationStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger(AnnotationStore.class.getName());

    /**
     * 1 keyed findings by {@code (sessionId, DiffScope)}; 2 keys them by
     * scope handle; 3 adds the secret used to derive restart-stable scope
     * handles; 4 re-keys verdicts from {@code intentId} onto a hunk content
     * digest, carrying {@code base}/{@code head}; 5 adds the agent recheck
     * assessments of spec §9.7. A v1 file is migrated on read rather than
     * dropped -- see {@link #legacyScopeId}. A v3 verdict entry has no digest
     * to migrate to (none were recorded in the wild) and is skipped by the
     * existing lenient decode. A v4 file needs no migration at all: {@link
     * #loadFromDisk} reads each named array independently, so one simply has
     * no {@code assessments} key and loads with none -- which {@code
     * AnnotationStoreTest} pins rather than assumes.
     */
    private static final int SCHEMA_VERSION = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The scope id a pre-scope-handle finding is parked under. Scope handles
     * are minted per process, so a v1 entry cannot name the handle it now
     * belongs to; it keeps a deterministic placeholder instead, and {@link
     * #adoptLegacy} moves it across the first time the matching scope is
     * minted. The alternative -- dropping v1 entries -- would silently throw
     * away real review comments on upgrade.
     */
    static String legacyScopeId(ManagedSessionId sessionId, DiffScope scope) {
        return "legacy:" + sessionId.value() + ":" + scope.name();
    }

    private final Path file;
    private final ExecutorService saveExecutor =
            Executors.newSingleThreadExecutor(runnable -> Thread.ofVirtual().unstarted(runnable));

    /** Findings by their composite key, in insertion order (the margin renders in this order). */
    private final Map<ReviewAnnotation.Key, ReviewAnnotation> findings = new LinkedHashMap<>();

    /** Verdicts by {@code (scopeId, hunkDigest)}. */
    private final Map<ReviewVerdict.Key, ReviewVerdict> verdicts = new LinkedHashMap<>();

    /**
     * Agent rechecks by {@code (scopeId, hunkDigest, fromBase, toBase)}
     * (spec §9.7), in insertion order.
     *
     * <p>Keyed by the base PAIR, not by the hunk: a later base move is a new
     * question, and an old answer carried forward would be the agent
     * answering something it was never asked.</p>
     */
    private final Map<RecheckAssessment.Key, RecheckAssessment> assessments = new LinkedHashMap<>();

    /** Scopes whose review has been submitted. */
    private final List<String> submitted = new ArrayList<>();

    /** Persisted per-profile secret used to derive opaque, restart-stable scope ids. */
    private String scopeIdSecret = newScopeIdSecret();

    private final AtomicReference<Snapshot> pendingSnapshot = new AtomicReference<>();

    /**
     * Change listeners, notified after every mutation with the affected key
     * (or {@code null} for a bulk change).
     *
     * <p>Exists because this store has more than one writer: the Review
     * destination on the FX thread, and the MCP tool router on its own
     * executor. A view that caches a finding must be told to re-read it, or a
     * later read-modify-write from the stale value silently discards the
     * other writer's change (AGENTS.md, "One writer for persistent state").</p>
     */
    private final List<Consumer<ReviewAnnotation.Key>> changeListeners = new CopyOnWriteArrayList<>();

    private record Snapshot(List<ReviewAnnotation> findings, List<ReviewVerdict> verdicts,
                            List<RecheckAssessment> assessments, List<String> submitted,
                            String scopeIdSecret) {
    }

    public AnnotationStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        loadFromDisk();
    }

    /** The annotations file next to {@code stateFile} (same directory, {@code annotations.json}). */
    public static Path siblingOf(Path stateFile) {
        return stateFile.toAbsolutePath().normalize().resolveSibling("annotations.json");
    }

    /** A defensive copy of the profile secret used by {@link ReviewScopeRegistry}. */
    public synchronized byte[] scopeIdSecret() {
        return Base64.getUrlDecoder().decode(scopeIdSecret);
    }

    // ---- queries ------------------------------------------------------------

    /** Every finding of one scope, in the order they were added. */
    public synchronized List<ReviewAnnotation> forScope(String scopeId) {
        return findings.values().stream().filter(f -> f.scopeId().equals(scopeId)).toList();
    }

    /** Every finding of one intent within one scope. */
    public synchronized List<ReviewAnnotation> forIntent(String scopeId, String intentId) {
        return findings.values().stream()
                .filter(f -> f.scopeId().equals(scopeId))
                .filter(f -> f.intentId().filter(intentId::equals).isPresent())
                .toList();
    }

    public synchronized Optional<ReviewAnnotation> byKey(ReviewAnnotation.Key key) {
        return Optional.ofNullable(findings.get(key));
    }

    public Optional<ReviewAnnotation> byId(String scopeId, String id) {
        return byKey(new ReviewAnnotation.Key(scopeId, id));
    }

    /** Open (unresolved) findings of a scope -- what the queue badge and the margin count. */
    public synchronized long openCount(String scopeId) {
        return findings.values().stream()
                .filter(f -> f.scopeId().equals(scopeId))
                .filter(f -> !f.resolved())
                .count();
    }

    /** Whether any unresolved finding of {@code intentId} refuses approval. */
    public synchronized boolean hasOpenBlockingFinding(String scopeId, String intentId) {
        return findings.values().stream()
                .filter(f -> f.scopeId().equals(scopeId))
                .filter(f -> intentId == null || f.intentId().filter(intentId::equals).isPresent())
                .anyMatch(ReviewAnnotation::blocksApproval);
    }

    public synchronized Optional<ReviewVerdict> verdict(String scopeId, String hunkDigest) {
        return Optional.ofNullable(verdicts.get(new ReviewVerdict.Key(scopeId, hunkDigest)));
    }

    public synchronized List<ReviewVerdict> verdictsFor(String scopeId) {
        return verdicts.values().stream().filter(v -> v.scopeId().equals(scopeId)).toList();
    }

    /**
     * Whether the agent said this base move affects this hunk (spec §9.7).
     *
     * <p>False for an assessment that said "unaffected" AND for no
     * assessment at all, deliberately: the two are the same to every reader,
     * because neither may clear anything. Only {@code true} is actionable,
     * and it can only ever ADD staleness.</p>
     */
    public synchronized boolean assessedAffected(String scopeId, String hunkDigest,
                                                 String fromBase, String toBase) {
        RecheckAssessment found = assessments.get(
                new RecheckAssessment.Key(scopeId, hunkDigest, fromBase, toBase));
        return found != null && found.affected();
    }

    /** Every recheck recorded against one scope, in the order they arrived. */
    public synchronized List<RecheckAssessment> assessmentsFor(String scopeId) {
        return assessments.values().stream().filter(a -> a.scopeId().equals(scopeId)).toList();
    }

    public synchronized boolean isSubmitted(String scopeId) {
        return submitted.contains(scopeId);
    }

    // ---- change notification ------------------------------------------------

    /** Registers a listener; returns a handle that unregisters it. */
    public Runnable addChangeListener(Consumer<ReviewAnnotation.Key> listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /**
     * Fires listeners outside this object's monitor. A listener that throws is
     * logged and skipped: notification is cosmetic next to the write that just
     * succeeded, and one bad subscriber must not fail the others.
     */
    private void fireChanged(ReviewAnnotation.Key key) {
        for (Consumer<ReviewAnnotation.Key> listener : changeListeners) {
            try {
                listener.accept(key);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Annotation change listener failed", e);
            }
        }
    }

    // ---- mutations (each persists asynchronously) ---------------------------

    /** Adds a finding, or replaces the one already stored under its key (idempotent upsert). */
    public void upsert(ReviewAnnotation finding) {
        upsertInternal(finding);
        fireChanged(finding.key());
    }

    private synchronized void upsertInternal(ReviewAnnotation finding) {
        findings.put(finding.key(), finding);
        persistAsync();
    }

    /**
     * The atomic read-modify-write: re-reads the finding under this store's
     * monitor, applies {@code transform}, stores the result and returns it.
     * Empty when no finding has that key.
     *
     * <p>The only way to change a stored finding, deliberately: a
     * replace-by-key mutator alongside it would let a caller read, decide, and
     * then write, giving the other writer -- the FX Review destination or the
     * MCP tool router -- a window in which to land a change that the write then
     * overwrites (AGENTS.md names this read-modify-write pattern as a past
     * data-loss bug).</p>
     *
     * <p>{@code transform} runs while the monitor is held, so it must be short
     * and must not call back into this store. It may throw: an exception
     * propagates with nothing written and no listener fired, which is how a
     * caller refuses a mutation based on the value it was handed. Listeners
     * fire after the monitor is released, never while it is held.</p>
     */
    public Optional<ReviewAnnotation> mutate(ReviewAnnotation.Key key,
                                             UnaryOperator<ReviewAnnotation> transform) {
        Optional<ReviewAnnotation> updated = mutateInternal(key, transform);
        updated.ifPresent(finding -> fireChanged(finding.key()));
        return updated;
    }

    private synchronized Optional<ReviewAnnotation> mutateInternal(
            ReviewAnnotation.Key key, UnaryOperator<ReviewAnnotation> transform) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(transform, "transform");
        ReviewAnnotation current = findings.get(key);
        if (current == null) {
            return Optional.empty();
        }
        ReviewAnnotation updated = Objects.requireNonNull(transform.apply(current),
                "transform must not return null");
        if (!updated.key().equals(key)) {
            throw new IllegalArgumentException("transform must not change the (scopeId, id) key");
        }
        findings.put(key, updated);
        persistAsync();
        return Optional.of(updated);
    }

    public void remove(ReviewAnnotation.Key key) {
        if (removeInternal(key)) {
            fireChanged(key);
        }
    }

    private synchronized boolean removeInternal(ReviewAnnotation.Key key) {
        if (findings.remove(key) != null) {
            persistAsync();
            return true;
        }
        return false;
    }

    /** Drops every finding and verdict of a scope that has left the queue for good. */
    public void removeScope(String scopeId) {
        if (removeScopeInternal(scopeId)) {
            fireChanged(null);
        }
    }

    private synchronized boolean removeScopeInternal(String scopeId) {
        boolean changed = findings.keySet().removeIf(key -> key.scopeId().equals(scopeId));
        changed |= verdicts.keySet().removeIf(key -> key.scopeId().equals(scopeId));
        changed |= assessments.keySet().removeIf(key -> key.scopeId().equals(scopeId));
        changed |= submitted.remove(scopeId);
        if (changed) {
            persistAsync();
        }
        return changed;
    }

    /** Records a per-hunk verdict, replacing any previous one. */
    public void putVerdict(ReviewVerdict verdict) {
        putVerdictInternal(verdict);
        fireChanged(null);
    }

    private synchronized void putVerdictInternal(ReviewVerdict verdict) {
        verdicts.put(verdict.key(), verdict);
        persistAsync();
    }

    /** {@code u}: undoes the verdict on one hunk. */
    public void clearVerdict(String scopeId, String hunkDigest) {
        if (clearVerdictInternal(scopeId, hunkDigest)) {
            fireChanged(null);
        }
    }

    private synchronized boolean clearVerdictInternal(String scopeId, String hunkDigest) {
        if (verdicts.remove(new ReviewVerdict.Key(scopeId, hunkDigest)) != null) {
            persistAsync();
            return true;
        }
        return false;
    }

    /**
     * Records an agent's recheck, replacing any it already made about the
     * same hunk and the same base pair.
     *
     * <p>Only an affected one has any effect (spec §9.7). Nothing here
     * touches {@link #verdicts}: an assessment is a second, weaker fact
     * stored alongside a verdict, never an edit to it, which is what makes
     * "an agent may never clear a human's approval" true by construction
     * rather than by every reader remembering to.</p>
     */
    public void putAssessment(RecheckAssessment assessment) {
        putAssessmentInternal(assessment);
        fireChanged(null);
    }

    private synchronized void putAssessmentInternal(RecheckAssessment assessment) {
        assessments.put(assessment.key(), assessment);
        persistAsync();
    }

    /** Marks a scope's review as submitted. */
    public void markSubmitted(String scopeId) {
        if (markSubmittedInternal(scopeId)) {
            fireChanged(null);
        }
    }

    private synchronized boolean markSubmittedInternal(String scopeId) {
        if (submitted.contains(scopeId)) {
            return false;
        }
        submitted.add(scopeId);
        persistAsync();
        return true;
    }

    /**
     * Moves findings parked under a pre-scope-handle key onto {@code scopeId}
     * (see {@link #legacyScopeId}). Called when a scope is minted for a
     * checkout whose session used to own those annotations, so a user's
     * comments from before scope handles existed reappear against the right
     * review rather than being lost.
     *
     * @return how many findings were adopted
     */
    public int adoptLegacy(ManagedSessionId sessionId, DiffScope diffScope, String scopeId) {
        int adopted = adoptLegacyInternal(legacyScopeId(sessionId, diffScope), scopeId);
        if (adopted > 0) {
            fireChanged(null);
        }
        return adopted;
    }

    private synchronized int adoptLegacyInternal(String from, String to) {
        List<ReviewAnnotation> moving = findings.values().stream()
                .filter(f -> f.scopeId().equals(from))
                .toList();
        if (moving.isEmpty()) {
            return 0;
        }
        for (ReviewAnnotation finding : moving) {
            findings.remove(finding.key());
        }
        for (ReviewAnnotation finding : moving) {
            ReviewAnnotation moved = finding.withScopeId(to);
            // An id that already exists on the target scope keeps the target's
            // value: the live review is the truth, and a stale legacy copy
            // must never overwrite it.
            findings.putIfAbsent(moved.key(), moved);
        }
        persistAsync();
        return moving.size();
    }

    // ---- persistence --------------------------------------------------------

    /**
     * Blocks until every save queued so far has finished writing. For
     * tests and shutdown paths that must not race the background writer
     * (e.g. JUnit's {@code @TempDir} cleanup deleting the directory while
     * a queued save re-creates it).
     */
    public void flushPendingSaves() {
        try {
            saveExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // saveSnapshot never throws; it logs its own failures.
        } catch (TimeoutException e) {
            // A wedged disk must not hang shutdown forever; the atomic
            // temp-file write means the worst case is a stale file, not a
            // corrupt one.
        }
    }

    /**
     * Flushes the newest snapshot and stops the background writer. Safe to
     * call once at shutdown; mutations after close are not persisted.
     */
    @Override
    public void close() {
        flushPendingSaves();
        saveExecutor.shutdown();
    }

    private void persistAsync() {
        Snapshot snapshot = new Snapshot(List.copyOf(findings.values()),
                List.copyOf(verdicts.values()), List.copyOf(assessments.values()),
                List.copyOf(submitted), scopeIdSecret);
        // Queue a writer task only when there is no snapshot already
        // pending; otherwise the queued task picks up this newer one.
        if (pendingSnapshot.getAndSet(snapshot) == null) {
            saveExecutor.execute(() -> {
                Snapshot latest = pendingSnapshot.getAndSet(null);
                if (latest != null) {
                    saveSnapshot(latest);
                }
            });
        }
    }

    private void saveSnapshot(Snapshot snapshot) {
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            String text = JsonWriter.write(toJson(snapshot.findings(), snapshot.verdicts(),
                    snapshot.assessments(), snapshot.submitted(), snapshot.scopeIdSecret()));
            Path tempFile = Files.createTempFile(directory, file.getFileName().toString() + ".", ".tmp");
            try {
                Files.writeString(tempFile, text, StandardCharsets.UTF_8);
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save annotations to " + file, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonValue parsed = JsonParser.parse(text);
            scopeIdSecretFromJson(parsed).ifPresent(secret -> scopeIdSecret = secret);
            for (ReviewAnnotation finding : fromJson(parsed)) {
                findings.put(finding.key(), finding);
            }
            for (ReviewVerdict verdict : verdictsFromJson(parsed)) {
                verdicts.put(verdict.key(), verdict);
            }
            // Read on its own terms, like every other named array: a file
            // written before spec §9.7 simply has no "assessments" key and
            // loads with none, which is why this schema bump needs no
            // migration path.
            for (RecheckAssessment assessment : assessmentsFromJson(parsed)) {
                assessments.put(assessment.key(), assessment);
            }
            submitted.addAll(submittedFromJson(parsed));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Annotations file " + file + " is malformed; starting empty", e);
        }
    }

    // ---- codec (package-private for tests) ----------------------------------

    static JsonValue toJson(List<ReviewAnnotation> findings, List<ReviewVerdict> verdicts,
                            List<String> submitted) {
        return toJson(findings, verdicts, List.of(), submitted, newScopeIdSecret());
    }

    private static JsonValue toJson(List<ReviewAnnotation> findings, List<ReviewVerdict> verdicts,
                                    List<RecheckAssessment> assessments, List<String> submitted,
                                    String scopeIdSecret) {
        JsonObject root = JsonObject.empty();
        root.put("schemaVersion", JsonNumber.of(SCHEMA_VERSION));
        root.put("scopeIdSecret", new JsonString(scopeIdSecret));

        List<JsonValue> entries = new ArrayList<>();
        for (ReviewAnnotation finding : findings) {
            entries.add(findingToJson(finding));
        }
        root.put("annotations", new JsonArray(entries));

        List<JsonValue> verdictEntries = new ArrayList<>();
        for (ReviewVerdict verdict : verdicts) {
            JsonObject obj = JsonObject.empty();
            obj.put("scopeId", new JsonString(verdict.scopeId()));
            obj.put("hunkDigest", new JsonString(verdict.hunkDigest()));
            obj.put("verdict", new JsonString(verdict.decision().wireName()));
            verdict.note().ifPresent(note -> obj.put("note", new JsonString(note)));
            obj.put("at", new JsonString(verdict.at().toString()));
            obj.put("base", new JsonString(verdict.baseCommit()));
            obj.put("head", new JsonString(verdict.headCommit()));
            verdictEntries.add(obj);
        }
        root.put("verdicts", new JsonArray(verdictEntries));

        List<JsonValue> assessmentEntries = new ArrayList<>();
        for (RecheckAssessment assessment : assessments) {
            JsonObject obj = JsonObject.empty();
            obj.put("scopeId", new JsonString(assessment.scopeId()));
            obj.put("hunkDigest", new JsonString(assessment.hunkDigest()));
            obj.put("fromBase", new JsonString(assessment.fromBase()));
            obj.put("toBase", new JsonString(assessment.toBase()));
            obj.put("affected", new JsonBoolean(assessment.affected()));
            obj.put("why", new JsonString(assessment.why()));
            obj.put("at", new JsonString(assessment.at().toString()));
            assessmentEntries.add(obj);
        }
        root.put("assessments", new JsonArray(assessmentEntries));

        List<JsonValue> submittedEntries = new ArrayList<>();
        for (String scopeId : submitted) {
            submittedEntries.add(new JsonString(scopeId));
        }
        root.put("submitted", new JsonArray(submittedEntries));
        return root;
    }

    private static Optional<String> scopeIdSecretFromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("scopeIdSecret") instanceof JsonString secret)) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(secret.value());
            return decoded.length >= 16 ? Optional.of(secret.value()) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String newScopeIdSecret() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static JsonObject findingToJson(ReviewAnnotation finding) {
        JsonObject obj = JsonObject.empty();
        obj.put("scopeId", new JsonString(finding.scopeId()));
        obj.put("id", new JsonString(finding.id()));
        finding.intentId().ifPresent(value -> obj.put("intentId", new JsonString(value)));
        obj.put("file", new JsonString(finding.file()));
        obj.put("startKey", new JsonString(finding.startKey()));
        obj.put("endKey", new JsonString(finding.endKey()));
        obj.put("severity", new JsonString(finding.severity().wireName()));
        obj.put("confidence", new JsonString(finding.confidence().wireName()));
        finding.title().ifPresent(value -> obj.put("title", new JsonString(value)));
        obj.put("author", new JsonString(finding.author()));
        obj.put("at", new JsonString(finding.at().toString()));
        obj.put("status", new JsonString(finding.status().name()));
        finding.severityOverride().ifPresent(value ->
                obj.put("severityOverride", new JsonString(value.wireName())));
        finding.github().ifPresent(comment -> {
            JsonObject githubObj = JsonObject.empty();
            githubObj.put("id", JsonNumber.of(comment.id()));
            githubObj.put("url", new JsonString(comment.url()));
            githubObj.put("resolved", new JsonBoolean(comment.resolved()));
            obj.put("github", githubObj);
        });
        if (finding.postToPr()) {
            obj.put("postToPr", new JsonBoolean(true));
        }

        if (!finding.evidence().isEmpty()) {
            obj.put("evidence", new JsonArray(finding.evidence().stream()
                    .map(evidence -> (JsonValue) evidenceToJson(evidence)).toList()));
        }
        finding.patch().ifPresent(patch -> {
            JsonObject patchObj = JsonObject.empty();
            patchObj.put("unified", new JsonString(patch.unified()));
            patchObj.put("summary", new JsonString(patch.summary()));
            obj.put("patch", patchObj);
        });
        finding.deviatesFrom().ifPresent(deviation -> {
            JsonObject deviationObj = JsonObject.empty();
            deviationObj.put("prompt", new JsonString(deviation.prompt()));
            deviation.step().ifPresent(step -> deviationObj.put("step", JsonNumber.of(step)));
            obj.put("deviatesFrom", deviationObj);
        });
        if (!finding.asks().isEmpty()) {
            List<JsonValue> asks = new ArrayList<>();
            for (ReviewAnnotation.Ask ask : finding.asks()) {
                JsonObject askObj = JsonObject.empty();
                askObj.put("label", new JsonString(ask.label()));
                askObj.put("question", new JsonString(ask.question()));
                asks.add(askObj);
            }
            obj.put("asks", new JsonArray(asks));
        }
        List<JsonValue> thread = new ArrayList<>();
        for (ReviewAnnotation.Message message : finding.thread()) {
            JsonObject messageObj = JsonObject.empty();
            messageObj.put("author", new JsonString(message.author()));
            messageObj.put("at", new JsonString(message.at().toString()));
            messageObj.put("text", new JsonString(message.text()));
            message.code().ifPresent(code -> messageObj.put("code", evidenceToJson(code)));
            thread.add(messageObj);
        }
        obj.put("thread", new JsonArray(thread));
        return obj;
    }

    private static JsonObject evidenceToJson(ReviewAnnotation.Evidence evidence) {
        JsonObject obj = JsonObject.empty();
        obj.put("label", new JsonString(evidence.label()));
        obj.put("code", new JsonString(evidence.code()));
        obj.put("language", new JsonString(evidence.language()));
        return obj;
    }

    static List<ReviewAnnotation> fromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("annotations") instanceof JsonArray entries)) {
            return List.of();
        }
        List<ReviewAnnotation> result = new ArrayList<>();
        for (JsonValue entryValue : entries.elements()) {
            if (!(entryValue instanceof JsonObject obj)) {
                continue;
            }
            try {
                result.add(findingFromJson(obj));
            } catch (IllegalArgumentException | DateTimeException e) {
                // One malformed entry never discards the rest.
                LOG.log(Level.WARNING, "Skipping malformed annotation entry: " + e.getMessage());
            }
        }
        return result;
    }

    private static ReviewAnnotation findingFromJson(JsonObject obj) {
        List<ReviewAnnotation.Message> thread = new ArrayList<>();
        if (obj.get("thread") instanceof JsonArray messages) {
            for (JsonValue messageValue : messages.elements()) {
                if (messageValue instanceof JsonObject messageObj) {
                    thread.add(new ReviewAnnotation.Message(
                            requireString(messageObj, "author"),
                            Instant.parse(requireString(messageObj, "at")),
                            requireString(messageObj, "text"),
                            messageObj.get("code") instanceof JsonObject code
                                    ? Optional.of(evidenceFromJson(code))
                                    : Optional.empty()));
                }
            }
        }
        Instant at = obj.get("at") instanceof JsonString value
                ? Instant.parse(value.value())
                : thread.stream().map(ReviewAnnotation.Message::at).findFirst().orElse(Instant.EPOCH);

        return new ReviewAnnotation(
                scopeIdFromJson(obj),
                requireString(obj, "id"),
                optionalString(obj, "intentId"),
                requireString(obj, "file"),
                requireString(obj, "startKey"),
                requireString(obj, "endKey"),
                optionalString(obj, "severity").flatMap(Severity::fromWire).orElse(Severity.QUESTION),
                optionalString(obj, "confidence").flatMap(Confidence::fromWire).orElse(Confidence.HIGH),
                optionalString(obj, "title"),
                optionalString(obj, "author").orElseGet(() ->
                        thread.isEmpty() ? "You" : thread.get(0).author()),
                at,
                evidenceListFromJson(obj),
                patchFromJson(obj),
                deviatesFromJson(obj),
                asksFromJson(obj),
                thread,
                optionalString(obj, "severityOverride").flatMap(Severity::fromWire),
                AnnotationStatus.fromPersisted(requireString(obj, "status")),
                githubFromJson(obj),
                obj.get("postToPr") instanceof JsonBoolean flag && flag.value());
    }

    private static Optional<ReviewAnnotation.GitHubComment> githubFromJson(JsonObject obj) {
        if (!(obj.get("github") instanceof JsonObject github)
                || !(github.get("id") instanceof JsonNumber id)
                || !(github.get("url") instanceof JsonString url)) {
            return Optional.empty();
        }
        return Optional.of(new ReviewAnnotation.GitHubComment(id.asLong(), url.value(),
                github.get("resolved") instanceof JsonBoolean resolved && resolved.value()));
    }

    /**
     * A v2 entry names its scope handle. A v1 entry predates handles and
     * names a session and a diff scope instead; it is parked under a
     * deterministic placeholder rather than discarded (see {@link
     * #legacyScopeId}).
     */
    private static String scopeIdFromJson(JsonObject obj) {
        Optional<String> scopeId = optionalString(obj, "scopeId");
        if (scopeId.isPresent()) {
            return scopeId.get();
        }
        String sessionId = requireString(obj, "sessionId");
        DiffScope diffScope = DiffScope.valueOf(requireString(obj, "scope").toUpperCase(Locale.ROOT));
        return legacyScopeId(ManagedSessionId.of(sessionId), diffScope);
    }

    private static List<ReviewAnnotation.Evidence> evidenceListFromJson(JsonObject obj) {
        if (!(obj.get("evidence") instanceof JsonArray array)) {
            return List.of();
        }
        List<ReviewAnnotation.Evidence> evidence = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonObject entry) {
                evidence.add(evidenceFromJson(entry));
            }
        }
        return evidence;
    }

    private static ReviewAnnotation.Evidence evidenceFromJson(JsonObject obj) {
        return new ReviewAnnotation.Evidence(
                optionalString(obj, "label").orElse(""),
                requireString(obj, "code"),
                optionalString(obj, "language").orElse("text"));
    }

    private static Optional<ReviewAnnotation.Patch> patchFromJson(JsonObject obj) {
        if (!(obj.get("patch") instanceof JsonObject patch)) {
            return Optional.empty();
        }
        return Optional.of(new ReviewAnnotation.Patch(
                requireString(patch, "unified"),
                optionalString(patch, "summary").orElse("")));
    }

    private static Optional<ReviewAnnotation.DeviatesFrom> deviatesFromJson(JsonObject obj) {
        if (!(obj.get("deviatesFrom") instanceof JsonObject deviation)) {
            return Optional.empty();
        }
        Optional<Integer> step = deviation.get("step") instanceof JsonNumber number
                ? Optional.of(number.asInt())
                : Optional.empty();
        return Optional.of(new ReviewAnnotation.DeviatesFrom(requireString(deviation, "prompt"), step));
    }

    private static List<ReviewAnnotation.Ask> asksFromJson(JsonObject obj) {
        if (!(obj.get("asks") instanceof JsonArray array)) {
            return List.of();
        }
        List<ReviewAnnotation.Ask> asks = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (element instanceof JsonObject entry) {
                asks.add(new ReviewAnnotation.Ask(
                        optionalString(entry, "label").orElse("Ask"),
                        requireString(entry, "question")));
            }
        }
        return asks;
    }

    static List<ReviewVerdict> verdictsFromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("verdicts") instanceof JsonArray entries)) {
            return List.of();
        }
        List<ReviewVerdict> result = new ArrayList<>();
        for (JsonValue entryValue : entries.elements()) {
            if (!(entryValue instanceof JsonObject obj)) {
                continue;
            }
            try {
                result.add(new ReviewVerdict(
                        requireString(obj, "scopeId"),
                        requireString(obj, "hunkDigest"),
                        ReviewVerdict.Decision.fromWire(requireString(obj, "verdict"))
                                .orElseThrow(() -> new IllegalArgumentException("unknown verdict")),
                        optionalString(obj, "note"),
                        Instant.parse(requireString(obj, "at")),
                        requireString(obj, "base"),
                        requireString(obj, "head")));
            } catch (IllegalArgumentException | DateTimeException e) {
                LOG.log(Level.WARNING, "Skipping malformed verdict entry: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * The recorded rechecks, decoded as leniently as the verdicts above: one
     * malformed entry is skipped, never the rest.
     *
     * <p>A missing {@code affected} decodes as {@code false}, which is the
     * inert direction. Reading an unreadable entry as "affected" would let a
     * corrupt file invent staleness nobody asserted; reading it as
     * "unaffected" costs nothing, because unaffected clears nothing.</p>
     */
    static List<RecheckAssessment> assessmentsFromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("assessments") instanceof JsonArray entries)) {
            return List.of();
        }
        List<RecheckAssessment> result = new ArrayList<>();
        for (JsonValue entryValue : entries.elements()) {
            if (!(entryValue instanceof JsonObject obj)) {
                continue;
            }
            try {
                result.add(new RecheckAssessment(
                        requireString(obj, "scopeId"),
                        requireString(obj, "hunkDigest"),
                        requireString(obj, "fromBase"),
                        requireString(obj, "toBase"),
                        obj.get("affected") instanceof JsonBoolean affected && affected.value(),
                        optionalString(obj, "why").orElse(""),
                        Instant.parse(requireString(obj, "at"))));
            } catch (IllegalArgumentException | DateTimeException e) {
                LOG.log(Level.WARNING, "Skipping malformed assessment entry: " + e.getMessage());
            }
        }
        return result;
    }

    static List<String> submittedFromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("submitted") instanceof JsonArray entries)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonValue element : entries.elements()) {
            if (element instanceof JsonString scopeId) {
                result.add(scopeId.value());
            }
        }
        return result;
    }

    private static String requireString(JsonObject obj, String key) {
        if (obj.get(key) instanceof JsonString s) {
            return s.value();
        }
        throw new IllegalArgumentException("Missing or non-string field: " + key);
    }

    private static Optional<String> optionalString(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString s ? Optional.of(s.value()) : Optional.empty();
    }
}
