package app.drydock.editorqa;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonValue;

/**
 * One editor ask: the immutable record of what the user selected and asked,
 * the state of its turn, and (once settled) the full answer. This is also
 * the persisted shape of a history record -- every field here survives a
 * partition round trip.
 *
 * <p>All bounded fields are validated against {@link EditorAskLimits} at
 * construction, so no code path -- capture, decode, restore -- can hold a
 * record whose text was clipped or carries unsafe characters. Validation
 * rejects; it never clips.</p>
 *
 * <p>Identity is layered exactly as the contract separates it: {@link #id()}
 * is the minted exchange ID (also the attempt ID, the navigation ID and the
 * turn-prompt marker), {@link #acceptanceSequence()} is the checkout-local
 * FIFO position, and {@link #owner()} is the marked {@link ManagedSessionId}
 * row the exchange belongs to. The canonical checkout root is deliberately
 * <em>not</em> a field: it is the partition key that addresses the record,
 * verified by the store, not repeated per record.</p>
 */
public record EditorAskRecord(
        String id,
        long acceptanceSequence,
        ManagedSessionId owner,
        String relativeFile,
        TextRange range,
        String selectedText,
        String question,
        Instant askedAt,
        Status status,
        String failureReason,
        String answer,
        Instant answeredAt,
        JsonValue anchor) {

    /**
     * The persisted name of each state is its constant name; it is a wire
     * contract with the history store and the UI and must never be renamed.
     * {@link #isSettled()} defines which constants close a turn.
     */
    public enum Status {
        ACCEPTED,
        QUEUED,
        SUBMITTING,
        AWAITING,
        ANSWERED,
        NO_ANSWER,
        FAILED_NOT_SENT,
        INTERRUPTED_POSSIBLY_SENT;

        /** A terminal state: the turn will not change again. */
        public boolean isSettled() {
            return this == ANSWERED || this == NO_ANSWER || this == FAILED_NOT_SENT
                    || this == INTERRUPTED_POSSIBLY_SENT;
        }
    }

    /**
     * The half-open UTF-16 offset range {@code [startOffset, endOffset)} of
     * the selection, with the line/column coordinates of both ends. Lines
     * and columns are 0-based; columns count UTF-16 code units from the
     * start of the line, and the end coordinates are those of
     * {@code endOffset} (the exclusive end), not of the last selected unit.
     */
    public record TextRange(
            int startOffset,
            int endOffset,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn) {

        public TextRange {
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("range must satisfy 0 <= start <= end, got ["
                        + startOffset + ", " + endOffset + ")");
            }
            if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
                throw new IllegalArgumentException("line and column must be 0-based and non-negative, got ["
                        + startLine + ":" + startColumn + ", " + endLine + ":" + endColumn + ")");
            }
        }
    }

    private static final Pattern CANONICAL_ID = Pattern.compile(
            "editor-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * Mints a new exchange ID: {@code editor-} plus a random UUID in the
     * canonical lowercase textual form. The same string serves as attempt,
     * exchange, navigation and turn-prompt marker ID.
     */
    public static String newId() {
        return "editor-" + UUID.randomUUID();
    }

    /**
     * The canonical parser: accepts only {@code editor-} followed by a
     * lowercase canonical UUID and returns the argument unchanged, so a
     * caller can compare IDs as plain strings. Spellings {@link UUID}
     * itself would tolerate -- uppercase hex, braces, the {@code urn:}
     * prefix -- are refused, because an ID that round-trips through
     * persistence, MCP calls and prompt markers must be byte-identical
     * everywhere it appears.
     *
     * @throws IllegalArgumentException if {@code id} is not canonical
     */
    public static String parseId(String id) {
        Objects.requireNonNull(id, "id");
        if (!CANONICAL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("not a canonical editor-ask ID: " + id);
        }
        return id;
    }

    public EditorAskRecord {
        parseId(Objects.requireNonNull(id, "id"));
        if (acceptanceSequence < 0) {
            throw new IllegalArgumentException("acceptance sequence must be >= 0, got " + acceptanceSequence);
        }
        Objects.requireNonNull(owner, "owner");
        // Editor shape: file, range and selection all present and fully validated.
        // Tab shape (a session-tab question): all three null together, no editor anchor.
        boolean tabShape = relativeFile == null && range == null && selectedText == null;
        if (!tabShape) {
            EditorAskLimits.checkRelativePath(Objects.requireNonNull(relativeFile, "relativeFile"));
            Objects.requireNonNull(range, "range");
            if (range.endOffset() == range.startOffset()) {
                throw new IllegalArgumentException("selection range must be nonempty");
            }
            EditorAskLimits.checkSelection(Objects.requireNonNull(selectedText, "selectedText"));
        } else if (anchor != null) {
            throw new IllegalArgumentException("a tab question has no editor anchor");
        }
        EditorAskLimits.checkQuestion(Objects.requireNonNull(question, "question"));
        Objects.requireNonNull(askedAt, "askedAt");
        Objects.requireNonNull(status, "status");
        failureReason = checkFailureReason(failureReason, status);
        if (status == Status.ANSWERED) {
            EditorAskLimits.checkAnswer(Objects.requireNonNull(answer, "answer"));
            Objects.requireNonNull(answeredAt, "answeredAt");
        } else {
            if (answer != null) {
                throw new IllegalArgumentException("answer is only set on " + Status.ANSWERED + ", got " + status);
            }
            if (answeredAt != null) {
                throw new IllegalArgumentException("answeredAt is only set on " + Status.ANSWERED + ", got " + status);
            }
        }
        // The anchor is opaque validated metadata (EditorAskAnchor's persisted
        // shape); a question asked from the composer has none.
    }

    private static String checkFailureReason(String reason, Status status) {
        if (status.isSettled() && status != Status.ANSWERED) {
            return reason == null ? null : EditorAskLimits.checkReason(reason);
        }
        if (reason != null) {
            throw new IllegalArgumentException("failureReason is only set on a settled failure, got " + status);
        }
        return null;
    }

    /** The record settled in {@code newStatus}, carrying {@code reason} (null where the status forbids it). */
    public EditorAskRecord settled(Status newStatus, String reason) {
        return new EditorAskRecord(id, acceptanceSequence, owner, relativeFile, range, selectedText,
                question, askedAt, newStatus, reason, null, null, anchor);
    }

    /** The record answered: immutable full answer and the server-clock instant it was accepted. */
    public EditorAskRecord answered(String fullAnswer, Instant acceptedAt) {
        return new EditorAskRecord(id, acceptanceSequence, owner, relativeFile, range, selectedText,
                question, askedAt, Status.ANSWERED, null, fullAnswer, acceptedAt, anchor);
    }

    /** The record with a new best-known restorable anchor (null clears it). */
    public EditorAskRecord withAnchor(JsonValue newAnchor) {
        return new EditorAskRecord(id, acceptanceSequence, owner, relativeFile, range, selectedText,
                question, askedAt, status, failureReason, answer, answeredAt, newAnchor);
    }

    /** A session-tab question: no file, range, selection or anchor. */
    public boolean isTab() {
        return relativeFile == null;
    }

    public boolean isSettled() {
        return status.isSettled();
    }
}
