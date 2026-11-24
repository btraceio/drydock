package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.app.ApplicationStateStore.DuplicateMarkedSessionException;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The catalog half of the editor Q&amp;A coordinator: the marked READ_ONLY row
 * of a checkout and the provider conversation ID saved on it. Every change
 * goes through {@link ApplicationStateStore#update} (the single writer); no
 * load-then-save. The row, the proven conversation and the launch are
 * separate things here: this class only knows the first two.
 *
 * <p>Reservation order is the contract's: insert/return the marked row by
 * one compare-and-insert, then require a <em>durable</em> catalog flush
 * before the caller may commit the first history record. A row that is
 * already durable in this process is not flushed again.</p>
 */
public final class EditorAskRowBinder {

    /** A visible recovery problem (duplicate row, inconsistent owner, unsaved catalog). */
    public static final class RowException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RowException(String message) {
            super(message);
        }
    }

    private final ApplicationStateStore catalog;
    private final Set<ManagedSessionId> durable = ConcurrentHashMap.newKeySet();

    public EditorAskRowBinder(ApplicationStateStore catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Returns the checkout's marked row, inserting {@code candidate} when
     * there is none, and proves it is on disk. Blocking: background thread
     * only. A crash after a row-only reservation leaves a row that the next
     * call reuses.
     */
    public ManagedAgentSession reserve(ManagedAgentSession candidate) {
        if (candidate.purpose() != SessionPurpose.EDITOR_QA || candidate.binding().mode() != SessionMode.READ_ONLY) {
            throw new RowException("The Q&A session row must be marked and read-only.");
        }
        ManagedAgentSession row;
        try {
            row = catalog.ensureMarkedSession(candidate);
        } catch (DuplicateMarkedSessionException e) {
            throw new RowException("This checkout has more than one Q&A session in the catalog; "
                    + "remove the extra rows to continue.");
        }
        if (!durable.contains(row.id())) {
            if (!catalog.flushDurably()) {
                throw new RowException("The session catalog could not be saved; the question was not sent.");
            }
            durable.add(row.id());
        }
        return row;
    }

    /** A partition whose records name another owner row is a recovery error, never a guessed binding. */
    public void checkOwner(ManagedSessionId row, List<EditorAskRecord> records) {
        for (EditorAskRecord record : records) {
            if (!record.owner().equals(row)) {
                throw new RowException("This checkout's Q&A history belongs to a different session row; "
                        + "it is not reused automatically.");
            }
        }
    }

    /** The live catalog entry of {@code row}, if it still exists. */
    public Optional<ManagedAgentSession> current(ManagedSessionId row) {
        return catalog.state().sessions().stream().filter(s -> s.id().equals(row)).findFirst();
    }

    /** The marked row for a canonical checkout directory, if any (restart recovery). */
    public Optional<ManagedAgentSession> markedFor(Path canonicalRoot) {
        return catalog.markedSession(canonicalRoot);
    }

    /**
     * Saves {@code conversationId} as the row's proven conversation of
     * {@code kind}. The caller has already validated provider, root and
     * launch generation; this only rejects an unbounded or control-laden ID.
     */
    public boolean saveConversation(ManagedSessionId row, AgentKind kind, String conversationId) {
        if (conversationId == null || conversationId.isBlank() || conversationId.length() > 256
                || conversationId.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        return edit(row, s -> {
            ManagedAgentSession withKind = s.binding().kind() == kind ? s : s.withAgentKind(kind);
            return withKind.withAgentSessionId(Optional.of(conversationId));
        });
    }

    /** Clears the stale conversation ID (context lost); the row and its history stay. */
    public boolean clearConversation(ManagedSessionId row) {
        return edit(row, s -> s.withAgentSessionId(Optional.empty()));
    }

    /** A fresh conversation under {@code kind}: the row keeps its identity, only the harness changes. */
    public boolean useHarness(ManagedSessionId row, AgentKind kind) {
        return edit(row, s -> s.binding().kind() == kind ? s : s.withAgentKind(kind).withAgentSessionId(Optional.empty()));
    }

    /**
     * Why an ordinary Forget must not remove this row, or empty when it may
     * go. A marked row that retains history (or has unfinished work) stays:
     * its history is associated with this session ID.
     */
    public static Optional<String> forgetProtection(ManagedAgentSession session, boolean retainsHistory) {
        if (session.purpose() == SessionPurpose.EDITOR_QA && retainsHistory) {
            return Optional.of("This is the checkout's Q&A session and it still holds answered questions. "
                    + "Forgetting it would strand their links.");
        }
        return Optional.empty();
    }

    private boolean edit(ManagedSessionId row, UnaryOperator<ManagedAgentSession> change) {
        boolean[] found = {false};
        catalog.update(state -> {
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            for (int i = 0; i < sessions.size(); i++) {
                ManagedAgentSession s = sessions.get(i);
                if (s.id().equals(row) && s.purpose() == SessionPurpose.EDITOR_QA) {
                    sessions.set(i, change.apply(s));
                    found[0] = true;
                }
            }
            return found[0] ? state.withSessions(List.copyOf(sessions)) : state;
        });
        return found[0];
    }
}
