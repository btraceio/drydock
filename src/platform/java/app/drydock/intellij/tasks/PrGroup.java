package app.drydock.intellij.tasks;

import app.drydock.git.PullRequestLabelFilter;

import java.util.Objects;

/**
 * One user-defined pull-request subgroup: a named folder under a project's
 * Pull Requests section that queries GitHub with its own boolean label
 * filter. The {@code id} is the stable identity the settings store, the
 * expansion tracker and the edit/delete actions key on; the name is what
 * the tree shows.
 *
 * @param id     stable unique id (a UUID as assigned by {@link PrGroups#withAdded})
 * @param name   the subgroup's display name; never blank
 * @param filter the boolean label expression ({@link PullRequestLabelFilter}) the
 *               subgroup queries GitHub with; validated here as the last line of
 *               defense behind the dialog's validation
 */
record PrGroup(String id, String name, String filter) {

    PrGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(filter, "filter");
        id = id.strip();
        name = name.strip();
        filter = PullRequestLabelFilter.parse(filter).raw();
        if (id.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("a PR group needs an id, a name, and a label filter");
        }
    }

    /** The parsed boolean label expression behind {@link #filter}. */
    PullRequestLabelFilter parsedFilter() {
        return PullRequestLabelFilter.parse(filter);
    }
}
