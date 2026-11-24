package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskHarnessPolicy.Choice;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRecord.TextRange;
import app.drydock.state.json.JsonValue;

import java.util.Objects;
import java.util.Optional;

/**
 * The seams between the headless {@link EditorAskCoordinator} and the world
 * it cannot import: the managed launch pipeline, the terminal write path,
 * the MCP credential registry, the settings and the UI. The platform
 * implements them; tests fake them. Nothing here touches the IntelliJ
 * classpath. Every method of {@link Launcher} may block and is only ever
 * called off the EDT, on the coordinator's executors.
 */
public final class EditorAskPorts {

    private EditorAskPorts() {
    }

    /**
     * One confirmed question, fully resolved and immutable: the canonical
     * checkout (symlinks resolved, the partition key), the candidate marked
     * READ_ONLY row to insert if the checkout has none, and the captured
     * selection. The coordinator never re-reads a document or the disk.
     */
    public record Submission(String canonicalRoot, ManagedAgentSession candidateRow, String relativeFile,
                             TextRange range, String selectedText, String question, JsonValue anchor) {
        public Submission {
            EditorAskLimits.checkRoot(Objects.requireNonNull(canonicalRoot, "canonicalRoot"));
            Objects.requireNonNull(candidateRow, "candidateRow");
            // Tab shape: file, range and selection are null together; any partial null is refused.
            if (relativeFile != null || range != null || selectedText != null) {
                Objects.requireNonNull(relativeFile, "relativeFile");
                Objects.requireNonNull(range, "range");
                Objects.requireNonNull(selectedText, "selectedText");
            } else if (anchor != null) {
                throw new IllegalArgumentException("a tab question has no editor anchor");
            }
            Objects.requireNonNull(question, "question");
        }
    }

    /** The synchronous answer to a submission: durably admitted (with its identity) or refused visibly. */
    public record Result(boolean accepted, String id, long sequence, ManagedSessionId row, String refusal) {
        static Result accepted(String id, long sequence, ManagedSessionId row) {
            return new Result(true, id, sequence, row, null);
        }

        static Result refused(String reason) {
            return new Result(false, null, -1, null, reason);
        }
    }

    /** How a launch call ended: STARTED, REFUSED (nothing was launched), FAILED (may have reached an agent). */
    public enum LaunchOutcome { STARTED, REFUSED, FAILED }

    /**
     * What to start. {@code executionId} and {@code generation} are minted by
     * the coordinator so a late discovery or exit can be matched to exactly
     * this launch. A present {@code resumeConversationId} is an exact resume
     * (no model flag); the prompt is the one first turn, carried once.
     */
    public record LaunchRequest(ManagedSessionId row, String root, String executionId, long generation,
                                AgentKind harness, Optional<String> model, Optional<String> resumeConversationId,
                                String prompt) {
    }

    /** {@code credentialGeneration} is the MCP credential generation minted for this launch, or -1 if unknown. */
    public record LaunchResult(LaunchOutcome outcome, long credentialGeneration, String reason) {
    }

    /** Whether a bound provider conversation can be addressed exactly. */
    public enum Resumability { EXACT, LOST }

    /** Launch, write and verification; all blocking, never on the EDT. */
    public interface Launcher {
        LaunchResult launch(LaunchRequest request);

        /** One live follow-up, framed once, bound to this exact execution and generation. */
        WriteOutcome write(String executionId, long generation, String prompt);

        /** Off-thread proof that {@code conversationId} of {@code kind} is addressable exactly. */
        Resumability verifyConversation(ManagedSessionId row, AgentKind kind, String conversationId);

        /** Ends the execution (shutdown or explicit stop); idempotent. */
        void stop(String executionId);
    }

    /** The Q&A settings snapshot, read at launch time only. */
    public interface Settings {
        Choice choice();

        boolean available(AgentKind kind);

        Optional<String> model(AgentKind kind);
    }

    /** The MCP credential registry's view: is this the row's current generation. */
    @FunctionalInterface
    public interface Credentials {
        boolean isCurrent(ManagedSessionId row, long generation);
    }

    /** UI-facing events; every method must be cheap and non-throwing-by-contract (failures are isolated). */
    public interface Events {
        /** Immediate progress of a turn (queue position, Starting…, Waiting for answer…, terminal reason). */
        default void progress(String root, String id, Status status, String label, String reason) {
        }

        /** A record as persisted (or held in memory after a failed save). */
        default void recorded(String root, EditorAskRecord record) {
        }

        /** A notice for the row, e.g. provider conversation context lost. */
        default void notice(String root, ManagedSessionId row, String message) {
        }

        /** A visible recovery problem: inconsistent owner, duplicate row, unsaved state, bad partition. */
        default void recoveryError(String root, String message) {
        }
    }

    /** Everything the coordinator is wired to. */
    public record Wiring(Launcher launcher, Settings settings, Credentials credentials, Events events) {
        public Wiring {
            Objects.requireNonNull(launcher, "launcher");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(events, "events");
        }
    }
}
