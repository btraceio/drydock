package app.drydock.git;

import java.util.Objects;

/**
 * The revision a review diffs against, and how that was decided.
 *
 * <p>The provenance is not decoration. A base of {@code master} in a
 * repository where every branch is cut from {@code develop} turns a
 * five-file review into fourteen hundred files, and the only symptom the
 * reader gets is a diff that looks like someone else's work. Carrying how
 * the base was chosen lets the item header say "default, could not measure"
 * where it used to say nothing at all.</p>
 */
public record ReviewBase(String ref, Origin origin) {

    /** How {@link #ref} was arrived at, in descending order of authority. */
    public enum Origin {
        /** GitHub declared it: the PR's own {@code baseRefName}. */
        PULL_REQUEST("declared by the pull request"),
        /** The integration branch containing the most of this checkout's history. */
        FORKED_FROM("forked from"),
        /** Nothing could be measured; the repository default is a guess. */
        DEFAULT_UNMEASURED("repository default — could not measure");

        private final String description;

        Origin(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public ReviewBase {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(origin, "origin");
    }

    /** "develop (forked from)" -- what the item header shows. */
    public String describe() {
        return ref + " (" + origin.description() + ")";
    }
}
