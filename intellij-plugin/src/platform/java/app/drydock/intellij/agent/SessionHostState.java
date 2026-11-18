package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The one thing a window's session host remembers across an IDE restart: the
 * id of the session it last intentionally showed, in the project's workspace
 * file (the {@code InboxViewState} pattern). Deliberately nothing else — a
 * held secondary session and the previous-session target are in-memory only,
 * so a restart never resurrects them. Nothing is added to the core
 * application state.
 *
 * <p>Decoding is lenient, like every cosmetic view state: an absent or
 * malformed id is no selection (the host shows its honest empty state), never
 * a failure. A remembered id is only a hint; whether that session still
 * exists is the host's question. EDT.</p>
 */
@State(name = "DrydockSessionHost", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SessionHostState implements PersistentStateComponent<SessionHostState.Stored> {

    /** The serialized shape: a plain public field, as the platform's XML binding reads it. */
    public static final class Stored {
        public String selectedSessionId;
    }

    private Stored stored = new Stored();

    public static SessionHostState getInstance(Project project) {
        return project.getService(SessionHostState.class);
    }

    @Override
    public @NotNull Stored getState() {
        return stored;
    }

    @Override
    public void loadState(@NotNull Stored state) {
        stored = state;
    }

    /** The remembered session id, empty when none is stored or the stored text is not an id. */
    public Optional<ManagedSessionId> selectedSession() {
        if (stored.selectedSessionId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ManagedSessionId.of(stored.selectedSessionId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Remembers {@code sessionId} as the last intentionally shown session; empty forgets it. */
    public void setSelectedSession(Optional<ManagedSessionId> sessionId) {
        stored.selectedSessionId = sessionId.map(ManagedSessionId::toString).orElse(null);
    }
}
