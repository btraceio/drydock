package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One gutter annotation of the Diff Review tab (design handoff section C):
 * a line range of one file in one diff scope, with a message thread and a
 * status. The range is stored as <em>stable line keys</em> --
 * {@code n<newLine>} for lines that exist in the post-image,
 * {@code o<oldLine>} for deleted lines (see
 * {@link app.drydock.git.UnifiedDiff.Line#lineKey()}) -- so annotations
 * survive re-diffs of the same scope.
 */
public record ReviewAnnotation(
        String id,
        ManagedSessionId sessionId,
        DiffScope scope,
        String file,
        String startKey,
        String endKey,
        AnnotationStatus status,
        List<Message> thread
) {

    /** One message of the thread; {@code author} is "You" or "Claude" (design: You/Claude avatars). */
    public record Message(String author, Instant at, String text) {
        public Message {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(text, "text");
        }
    }

    public ReviewAnnotation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(startKey, "startKey");
        Objects.requireNonNull(endKey, "endKey");
        Objects.requireNonNull(status, "status");
        thread = List.copyOf(Objects.requireNonNull(thread, "thread"));
    }

    public static ReviewAnnotation create(ManagedSessionId sessionId, DiffScope scope, String file,
                                          String startKey, String endKey, Message firstMessage) {
        return new ReviewAnnotation(UUID.randomUUID().toString(), sessionId, scope, file,
                startKey, endKey, AnnotationStatus.OPEN, List.of(firstMessage));
    }

    public ReviewAnnotation withStatus(AnnotationStatus newStatus) {
        return new ReviewAnnotation(id, sessionId, scope, file, startKey, endKey, newStatus, thread);
    }

    public ReviewAnnotation withReply(Message reply) {
        List<Message> extended = new ArrayList<>(thread);
        extended.add(reply);
        return new ReviewAnnotation(id, sessionId, scope, file, startKey, endKey, status, extended);
    }
}
