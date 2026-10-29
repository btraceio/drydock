package app.drydock.intellij.tasks;

import java.util.Objects;

/**
 * One user-defined Jira group: a named subnode of a project's Issues
 * section that queries Jira with its own JQL. The {@code id} is the stable
 * identity the settings store, the listing cache and the edit/delete
 * actions key on; the name is what the tree shows.
 *
 * @param id   stable unique id (a UUID as assigned by {@link JiraGroups#withAdded})
 * @param name the subnode's display name; never blank
 * @param jql  the JQL the group's listing runs; never blank — Jira groups
 *             exist to scope issues, so an empty query is refused here as
 *             the last line of defense behind the dialog's validation
 */
record JiraGroup(String id, String name, String jql) {

    JiraGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jql, "jql");
        id = id.strip();
        name = name.strip();
        jql = jql.strip();
        if (id.isEmpty() || name.isEmpty() || jql.isEmpty()) {
            throw new IllegalArgumentException("a Jira group needs an id, a name, and a JQL");
        }
    }
}
