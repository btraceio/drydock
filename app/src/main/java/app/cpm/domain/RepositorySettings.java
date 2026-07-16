package app.cpm.domain;

/**
 * Per-repository settings placeholder (plan section 10.1).
 *
 * <p>No fields yet -- Milestone 4 has nothing repository-specific to
 * configure. Deliberately kept as an empty record rather than skipped
 * entirely so the persisted schema already has a stable {@code settings}
 * slot for later milestones to extend without a migration (plan rule
 * 27.2: do not scaffold later milestones, but this is the *current*
 * milestone's own field, per plan section 10.1's record shape).</p>
 */
public record RepositorySettings() {

    public static final RepositorySettings DEFAULT = new RepositorySettings();
}
