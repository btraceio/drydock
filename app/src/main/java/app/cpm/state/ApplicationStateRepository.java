package app.cpm.state;

import app.cpm.domain.ApplicationState;

/**
 * Persistence boundary for {@link ApplicationState} (plan section 17).
 *
 * <p>Implementations must never throw for a missing, truncated, or
 * malformed persisted state -- {@link #load()} recovers to {@link
 * ApplicationState#empty()} in that case (plan section 17: "recovery from
 * truncated or malformed state").</p>
 */
public interface ApplicationStateRepository {

    ApplicationState load();

    void save(ApplicationState state);
}
