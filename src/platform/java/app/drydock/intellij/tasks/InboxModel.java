package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.DelayedTaskRows;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The tool window's Attention Inbox, decided in one pure place: every
 * session and task of every project, in flat groups by state rather than
 * by repository. Built from the rows {@link TaskTreeModel} already derives
 * (the ALL view, no search) and the task layer's rows; no I/O, no clock.
 *
 * <p>A session goes to the first group that takes it: Babysitting (a
 * babysitter whose engagement is active), Needs you
 * ({@link DelayedTaskRows#needsYou(TaskRow)}: a running session waiting
 * for the user, or a watched session its babysitter escalated), Flagged
 * (a flagged session that is not running -- a flagged one that runs goes
 * to Running, its row wearing a flagged chip), Running, else Idle. An assigned task in review
 * does not pin its session here -- the task shows as its own row under
 * In review, and the session's group follows its dot alone. A task goes
 * by its presentation: Blocked, In review, Ready to start
 * (ready, and started tasks whose sessions are gone), and Done today (done,
 * with a {@code done_at} on the current day). A started task attached to a
 * session row in the inbox is represented by that row, so it is not a row of
 * its own; its title rides on the session item. A started task with no such
 * row goes to Running as a task row.</p>
 *
 * <p>Sessions are ordered by when they were last opened, newest first;
 * tasks keep {@link DelayedTaskRows#sorted} order.</p>
 */
final class InboxModel {

    /** Group rows beyond this many are behind "+N more", unless the group never truncates. */
    static final int TRUNCATE_AFTER = 4;

    /** The inbox groups, in display order. */
    enum GroupKind {
        NEEDS_YOU("Needs you", "needs", false, false),
        FLAGGED("Flagged", "flagged", false, true),
        RUNNING("Running", "running", false, true),
        BLOCKED("Blocked", "blocked", false, true),
        IN_REVIEW("In review", "review", false, true),
        READY("Ready to start", "ready", false, true),
        /** Babysitter sessions with an active engagement: their own section, never mixed into the others. */
        BABYSITTING("Babysitting", "babysit", false, true),
        IDLE("Idle", "idle", true, true),
        DONE("Done today", "done", true, true);

        private final String label;
        private final String token;
        private final boolean collapsedByDefault;
        private final boolean truncates;

        GroupKind(String label, String token, boolean collapsedByDefault, boolean truncates) {
            this.label = label;
            this.token = token;
            this.collapsedByDefault = collapsedByDefault;
            this.truncates = truncates;
        }

        String label() {
            return label;
        }

        /** The word after {@code is:} that narrows the inbox to this group. */
        String token() {
            return token;
        }

        boolean collapsedByDefault() {
            return collapsedByDefault;
        }

        /** Whether rows beyond {@link #TRUNCATE_AFTER} are hidden behind "+N more". */
        boolean truncates() {
            return truncates;
        }

        /**
         * Whether the group's rows count as the project's activity: Idle and
         * Done do not, and neither do babysitters -- background helpers whose
         * news reaches the human through the watched session's row.
         */
        boolean active() {
            return this != IDLE && this != DONE && this != BABYSITTING;
        }
    }

    /** The project an item belongs to: its main checkout, and the name its tag shows. */
    record ProjectRef(Path root, String name) {
        ProjectRef {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(name, "name");
        }
    }

    /** One inbox row. */
    sealed interface Item {
        String title();

        /** The item's project, when it resolves to one. */
        Optional<ProjectRef> project();
    }

    /** A session's row, with the titles of the live tasks it carries (for its tooltip). */
    record SessionItem(TaskRow row, Optional<ProjectRef> project, List<String> taskTitles) implements Item {
        SessionItem {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(project, "project");
            taskTitles = List.copyOf(taskTitles);
        }

        @Override
        public String title() {
            return row.session().displayName();
        }
    }

    /** A task's row. */
    record TaskItem(DelayedTaskRow row, Optional<ProjectRef> project) implements Item {
        TaskItem {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(project, "project");
        }

        @Override
        public String title() {
            return row.title();
        }
    }

    /** One non-empty group and its matching items, all of them; {@link #shown} truncates. */
    record Group(GroupKind kind, String label, List<Item> items) {
        Group {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            items = List.copyOf(items);
        }

        /** The items the group shows: all of them when it does not truncate or the user asked for more. */
        List<Item> shown(boolean showAll) {
            return showAll || !kind.truncates() || items.size() <= TRUNCATE_AFTER
                    ? items : items.subList(0, TRUNCATE_AFTER);
        }

        /** How many items {@link #shown} hides behind "+N more". */
        int hidden(boolean showAll) {
            return items.size() - shown(showAll).size();
        }
    }

    /** A registered project in the {@code project:} suggestions, with how many active items it has. */
    record Suggestion(ProjectRef project, int active) {
        Suggestion {
            Objects.requireNonNull(project, "project");
        }
    }

    /**
     * What the user narrowed the inbox to: the {@code project:} chip, if
     * any, and the search text. The text's {@code is:<group>} words keep
     * only those groups; the remaining words, joined by single spaces,
     * must occur in an item's title (case-insensitively).
     */
    record Query(Optional<Path> project, String text) {
        static final Query NONE = new Query(Optional.empty(), "");

        Query {
            Objects.requireNonNull(project, "project");
            text = text == null ? "" : text.strip();
        }
    }

    /**
     * Everything the inbox is built from.
     *
     * @param nodes      the tree model's rows for the ALL view with no search
     * @param projects   the registered projects; remote ones are ignored
     * @param tasks      every task row, terminal ones included
     * @param documents  the task documents by id, for status and {@code done_at}
     * @param taskRoots  each task repository's project main checkout, where it resolves to one
     * @param now        the clock reading "today" is measured against
     * @param zone       the zone "today" is a day in
     * @param prerequisites each task's resolved prerequisite ids ({@code TaskGraph#prerequisites}); the
     *                   edges the dependency graphs are drawn from
     */
    record Inputs(List<TaskNode> nodes, List<SharedProject> projects, List<DelayedTaskRow> tasks,
                  Map<TaskId, TaskDocument> documents, Map<RepositoryId, Path> taskRoots, Instant now,
                  ZoneId zone, Map<TaskId, List<TaskId>> prerequisites) {
        Inputs {
            nodes = List.copyOf(nodes);
            projects = List.copyOf(projects);
            tasks = List.copyOf(tasks);
            documents = Map.copyOf(documents);
            taskRoots = Map.copyOf(taskRoots);
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(zone, "zone");
            prerequisites = Map.copyOf(prerequisites);
        }

        /** The shape without dependency edges: no task graphs. */
        Inputs(List<TaskNode> nodes, List<SharedProject> projects, List<DelayedTaskRow> tasks,
               Map<TaskId, TaskDocument> documents, Map<RepositoryId, Path> taskRoots, Instant now,
               ZoneId zone) {
            this(nodes, projects, tasks, documents, taskRoots, now, zone, Map.of());
        }
    }

    /**
     * One task of a dependency {@link Graph}: its row, its depth below the graph's roots, the titles of
     * the unfinished tasks it waits on, how many unfinished tasks wait on it, and whether its graph spans
     * several projects (then each row names its own project; otherwise the header does).
     */
    record GraphNode(TaskItem item, int depth, List<String> waitsOn, int unblocks, boolean mixedProjects) {
        GraphNode {
            Objects.requireNonNull(item, "item");
            waitsOn = List.copyOf(waitsOn);
        }
    }

    /**
     * A connected set of at least two tasks joined by {@code after} references; tasks a live session is
     * running are no members (their session row shows them) but stay in a member's {@code waitsOn}.
     * {@code nodes} are in display order (a task before the tasks that wait on it, each task once) except
     * the tasks in or behind a dependency cycle, which {@code stuck} marks: they follow the rest in
     * {@link DelayedTaskRows#sorted} order at depth 0. {@code sinks} are the members nothing waits on, the
     * targets whose start plan covers the whole graph; {@code done} counts the prerequisites the members
     * name whose status is done.
     */
    record Graph(String key, List<GraphNode> nodes, List<TaskItem> sinks, int done, boolean stuck) {
        Graph {
            Objects.requireNonNull(key, "key");
            nodes = List.copyOf(nodes);
            sinks = List.copyOf(sinks);
        }

        /** Finished plus unfinished tasks. */
        int total() {
            return done + nodes.size();
        }

        /**
         * The sinks that can still be started: open tasks, ready or blocked, as the Start action allows.
         * Empty for a stuck graph: the start planner refuses work that contains a cycle.
         */
        List<TaskItem> startTargets() {
            return stuck ? List.of() : sinks.stream().filter(sink -> sink.row().presentation() == TaskState.Presentation.READY
                    || sink.row().presentation() == TaskState.Presentation.BLOCKED).toList();
        }

        /** The distinct projects of the members, in display order. */
        List<ProjectRef> projects() {
            return nodes.stream().flatMap(node -> node.item().project().stream()).distinct().toList();
        }

        boolean contains(TaskId id) {
            return nodes.stream().anyMatch(node -> node.item().row().id().equals(id));
        }
    }

    /**
     * The built inbox: the non-empty groups for the query, the registered
     * projects with their active-item counts (ignoring the query), and how
     * many of them have no active item at all.
     */
    record Inbox(List<Group> groups, List<Suggestion> projects, int quietProjects, List<Graph> graphs) {
        Inbox {
            groups = List.copyOf(groups);
            projects = List.copyOf(projects);
            graphs = List.copyOf(graphs);
        }

        Inbox(List<Group> groups, List<Suggestion> projects, int quietProjects) {
            this(groups, projects, quietProjects, List.of());
        }

        /** This inbox with the tasks of its graphs taken out of the state groups, which then show only what no graph shows. */
        Inbox graphed() {
            List<Group> kept = new ArrayList<>();
            for (Group group : groups) {
                List<Item> rest = group.items().stream()
                        .filter(item -> !(item instanceof TaskItem task)
                                || graphs.stream().noneMatch(graph -> graph.contains(task.row().id())))
                        .toList();
                if (!rest.isEmpty()) {
                    kept.add(new Group(group.kind(), group.label(), rest));
                }
            }
            return new Inbox(kept, projects, quietProjects, graphs);
        }

        boolean isEmpty() {
            return groups.isEmpty();
        }

        /** The registered projects whose name contains {@code text} (case-insensitively), for {@code project:}. */
        List<Suggestion> suggestions(String text) {
            String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            return projects.stream()
                    .filter(suggestion -> suggestion.project().name().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
    }

    /**
     * A placed item, before the query narrows it, with the pull requests
     * and issues it can be matched against: a session's own and its
     * attached tasks' links, a task's own ones. Empty lists are normal —
     * a row with no PR link matches no {@code pr:} term.
     */
    private record Placed(GroupKind kind, Item item, List<SearchTerms.PrRef> pullRequests,
                          List<SearchTerms.IssueRef> issues) {
    }

    private InboxModel() {
    }

    /**
     * The text after a leading {@code project:} while the user is still
     * typing it (the suggestion popup's input), or empty when the search
     * text does not start with it.
     */
    static Optional<String> projectPrefix(String text) {
        String stripped = text == null ? "" : text.stripLeading();
        return stripped.regionMatches(true, 0, "project:", 0, "project:".length())
                ? Optional.of(stripped.substring("project:".length()).strip())
                : Optional.empty();
    }

    /** The search text without its {@code is:<group>} words and {@code pr:}/{@code issue:} terms: what
     *  titles elsewhere are matched by. Pure. */
    static String titleText(String text) {
        return SearchTerms.parse(text, InboxModel::kindOf).plainText();
    }

    static Inbox build(Inputs in, Query query) {
        SearchTerms<GroupKind> terms = SearchTerms.parse(query.text(), InboxModel::kindOf);
        boolean matchReferences = !terms.pullRequests().isEmpty() || !terms.issues().isEmpty();
        List<Placed> placed = place(in, matchReferences);

        Map<Path, Integer> activeByRoot = new LinkedHashMap<>();
        for (Placed one : placed) {
            if (one.kind().active()) {
                one.item().project().ifPresent(project -> activeByRoot.merge(project.root(), 1, Integer::sum));
            }
        }
        List<Suggestion> projects = in.projects().stream()
                .filter(project -> !project.isRemote())
                .sorted(Comparator.comparing(project -> project.displayName().toLowerCase(Locale.ROOT)))
                .map(project -> new Suggestion(new ProjectRef(project.root(), project.displayName()),
                        activeByRoot.getOrDefault(project.root(), 0)))
                .toList();
        int quiet = (int) projects.stream().filter(suggestion -> suggestion.active() == 0).count();

        Set<GroupKind> only = terms.groupKinds();
        String needle = terms.plainText().toLowerCase(Locale.ROOT);

        Map<GroupKind, List<Item>> grouped = new EnumMap<>(GroupKind.class);
        for (Placed one : placed) {
            if (!only.isEmpty() && !only.contains(one.kind()) && !matchesFlagTerm(only, one)) {
                continue;
            }
            if (query.project().isPresent() && !one.item().project().map(ProjectRef::root)
                    .equals(query.project())) {
                continue;
            }
            if (!needle.isEmpty() && !one.item().title().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (!terms.matchPullRequests(one.pullRequests()) || !terms.matchIssues(one.issues())) {
                continue;
            }
            grouped.computeIfAbsent(one.kind(), any -> new ArrayList<>()).add(one.item());
        }
        // is:done also shows done tasks with no done_at, or done before today.
        if (only.contains(GroupKind.DONE)) {
            grouped.remove(GroupKind.DONE);
            for (DelayedTaskRow row : DelayedTaskRows.sorted(in.tasks())) {
                TaskDocument document = in.documents().get(row.id());
                if (document == null || document.status() != TaskStatus.DONE) {
                    continue;
                }
                TaskItem item = new TaskItem(row, projectOf(in, row));
                SearchRefs.ItemRefs refs = matchReferences ? SearchRefs.looseTaskRefs(row) : null;
                List<SearchTerms.PrRef> pullRequests = refs == null ? List.of() : refs.pullRequests();
                List<SearchTerms.IssueRef> issues = refs == null ? List.of() : refs.issues();
                if (query.project().isPresent() && !item.project().map(ProjectRef::root).equals(query.project())
                        || !needle.isEmpty() && !item.title().toLowerCase(Locale.ROOT).contains(needle)
                        || !terms.matchPullRequests(pullRequests) || !terms.matchIssues(issues)) {
                    continue;
                }
                grouped.computeIfAbsent(GroupKind.DONE, any -> new ArrayList<>()).add(item);
            }
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((kind, items) -> groups.add(new Group(kind,
                kind == GroupKind.DONE && only.contains(GroupKind.DONE) ? "Done" : kind.label(), items)));
        boolean plain = only.isEmpty() && !matchReferences;
        return new Inbox(groups, projects, quiet,
                plain ? graphs(in).stream().filter(graph -> graph.nodes().stream().anyMatch(node ->
                        (query.project().isEmpty() || node.item().project().map(ProjectRef::root)
                                .equals(query.project()))
                                && (needle.isEmpty() || node.item().title().toLowerCase(Locale.ROOT)
                                .contains(needle)))).toList() : List.of());
    }

    /**
     * The dependency graphs of the unfinished tasks: connected components of two or more tasks over the
     * resolved {@code after} edges, ordered by their first task in {@link DelayedTaskRows#sorted} order.
     * Finished tasks and tasks a live session runs are no members and join nothing (a running prerequisite
     * still shows in its dependents' {@code waitsOn}); a dependency cycle never loops the ordering.
     */
    static List<Graph> graphs(Inputs in) {
        Map<TaskId, DelayedTaskRow> unfinished = new LinkedHashMap<>();
        Map<TaskId, DelayedTaskRow> running = new LinkedHashMap<>();
        for (DelayedTaskRow row : DelayedTaskRows.sorted(in.tasks())) {
            if (row.presentation() == TaskState.Presentation.STARTED) {
                running.put(row.id(), row);
            } else if (!row.terminal()) {
                unfinished.put(row.id(), row);
            }
        }
        Map<TaskId, List<TaskId>> waitsOn = new LinkedHashMap<>();
        Map<TaskId, List<TaskId>> dependents = new LinkedHashMap<>();
        for (TaskId id : unfinished.keySet()) {
            waitsOn.put(id, new ArrayList<>());
            dependents.put(id, new ArrayList<>());
        }
        for (TaskId id : unfinished.keySet()) {
            for (TaskId prerequisite : in.prerequisites().getOrDefault(id, List.of())) {
                if (unfinished.containsKey(prerequisite) && !prerequisite.equals(id)
                        && !waitsOn.get(id).contains(prerequisite)) {
                    waitsOn.get(id).add(prerequisite);
                    dependents.get(prerequisite).add(id);
                }
            }
        }
        List<Graph> graphs = new ArrayList<>();
        Set<TaskId> seen = new HashSet<>();
        for (TaskId start : unfinished.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            List<TaskId> component = new ArrayList<>();
            ArrayDeque<TaskId> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                TaskId at = queue.poll();
                component.add(at);
                for (TaskId next : concat(waitsOn.get(at), dependents.get(at))) {
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            if (component.size() >= 2) {
                graphs.add(graphOf(in, unfinished, running, waitsOn, dependents, component));
            }
        }
        return graphs;
    }

    private static List<TaskId> concat(List<TaskId> first, List<TaskId> second) {
        List<TaskId> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    private static Graph graphOf(Inputs in, Map<TaskId, DelayedTaskRow> unfinished,
                                 Map<TaskId, DelayedTaskRow> running, Map<TaskId, List<TaskId>> waitsOn, Map<TaskId, List<TaskId>> dependents,
                                 List<TaskId> component) {
        // Component order is by discovery; display order is by the global sorted order.
        List<TaskId> members = unfinished.keySet().stream().filter(component::contains).toList();
        Map<TaskId, Integer> depth = new LinkedHashMap<>();
        Map<TaskId, Integer> pending = new LinkedHashMap<>();
        ArrayDeque<TaskId> ready = new ArrayDeque<>();
        for (TaskId id : members) {
            pending.put(id, waitsOn.get(id).size());
            if (waitsOn.get(id).isEmpty()) {
                depth.put(id, 0);
                ready.add(id);
            }
        }
        while (!ready.isEmpty()) {
            TaskId at = ready.poll();
            for (TaskId next : dependents.get(at)) {
                depth.merge(next, depth.get(at) + 1, Math::max);
                if (pending.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        List<TaskId> order = new ArrayList<>();
        Set<TaskId> placed = new HashSet<>();
        ArrayDeque<TaskId> stack = new ArrayDeque<>();
        for (TaskId root : members) {
            if (!waitsOn.get(root).isEmpty()) {
                continue;
            }
            stack.push(root);
            while (!stack.isEmpty()) {
                TaskId at = stack.pop();
                // Placed only once everything it waits on is: it is pushed again by its last prerequisite.
                if (placed.contains(at) || !placed.containsAll(waitsOn.get(at))) {
                    continue;
                }
                placed.add(at);
                order.add(at);
                List<TaskId> below = dependents.get(at);
                for (int i = below.size() - 1; i >= 0; i--) {
                    stack.push(below.get(i));
                }
            }
        }
        boolean stuck = false;
        for (TaskId id : members) {
            if (placed.add(id)) {
                order.add(id);
                depth.put(id, 0);
                stuck = true;
            }
        }
        List<GraphNode> nodes = new ArrayList<>();
        List<TaskItem> sinks = new ArrayList<>();
        Set<TaskId> finished = new HashSet<>();
        Map<TaskId, TaskItem> items = new LinkedHashMap<>();
        for (TaskId id : order) {
            DelayedTaskRow row = unfinished.get(id);
            items.put(id, new TaskItem(row, projectOf(in, row)));
        }
        boolean mixed = items.values().stream().flatMap(item -> item.project().stream()).distinct().count() > 1;
        for (TaskId id : order) {
            TaskItem item = items.get(id);
            List<String> waiting = new ArrayList<>(waitsOn.get(id).stream()
                    .map(prerequisite -> unfinished.get(prerequisite).title()).toList());
            for (TaskId prerequisite : in.prerequisites().getOrDefault(id, List.of())) {
                if (running.containsKey(prerequisite)) {
                    waiting.add(running.get(prerequisite).title());
                }
            }
            nodes.add(new GraphNode(item, depth.get(id), waiting, dependents.get(id).size(), mixed));
            if (dependents.get(id).isEmpty()) {
                sinks.add(item);
            }
            for (TaskId prerequisite : in.prerequisites().getOrDefault(id, List.of())) {
                TaskDocument document = in.documents().get(prerequisite);
                if (document != null && document.status() == TaskStatus.DONE) {
                    finished.add(prerequisite);
                }
            }
        }
        return new Graph(order.get(0).text(), nodes, sinks, finished.size(), stuck);
    }

    static Optional<GroupKind> kindOf(String token) {
        for (GroupKind kind : GroupKind.values()) {
            if (kind.token().equalsIgnoreCase(token)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether an item outside the group an {@code is:} term names still
     * matches it: {@code is:flagged} admits a flagged session wherever it
     * sits, since the flag follows the session and not the section.
     */
    private static boolean matchesFlagTerm(Set<GroupKind> only, Placed one) {
        return only.contains(GroupKind.FLAGGED)
                && one.item() instanceof SessionItem session && session.row().session().flagged();
    }

    /** Every item in its group, in group order and then each group's own order. */
    private static List<Placed> place(Inputs in, boolean matchReferences) {
        Map<String, SessionAt> sessions = new LinkedHashMap<>();
        collectSessions(in.nodes(), Optional.empty(), sessions);

        Map<String, List<DelayedTaskRow>> attached = new LinkedHashMap<>();
        List<DelayedTaskRow> loose = new ArrayList<>();
        for (DelayedTaskRow row : DelayedTaskRows.sorted(in.tasks())) {
            Optional<String> session = DelayedTaskRows.attachedSession(row, sessions.keySet());
            session.ifPresent(id -> attached.computeIfAbsent(id, any -> new ArrayList<>()).add(row));
            if (session.isEmpty() || row.presentation() != TaskState.Presentation.STARTED) {
                loose.add(row);
            }
        }

        List<Placed> placed = new ArrayList<>();
        List<SessionAt> ordered = new ArrayList<>(sessions.values());
        ordered.sort(Comparator.comparing((SessionAt at) -> at.row().session().lastOpenedAt()).reversed());
        for (SessionAt at : ordered) {
            String id = at.row().session().id().toString();
            List<DelayedTaskRow> children = attached.getOrDefault(id, List.of());
            List<String> titles = children.stream()
                    .filter(child -> child.presentation() == TaskState.Presentation.STARTED)
                    .map(DelayedTaskRow::title).toList();
            SearchRefs.ItemRefs refs = matchReferences ? SearchRefs.sessionRefs(at.row(), children) : null;
            List<SearchTerms.PrRef> pullRequests = refs == null ? List.of() : refs.pullRequests();
            List<SearchTerms.IssueRef> issues = refs == null ? List.of() : refs.issues();
            placed.add(new Placed(sessionKind(at.row()), new SessionItem(at.row(), at.project(), titles),
                    pullRequests, issues));
        }
        LocalDate today = LocalDate.ofInstant(in.now(), in.zone());
        for (DelayedTaskRow row : loose) {
            taskKind(row, in.documents().get(row.id()), today, in.zone())
                    .ifPresent(kind -> {
                        SearchRefs.ItemRefs refs = matchReferences ? SearchRefs.looseTaskRefs(row) : null;
                        List<SearchTerms.PrRef> pullRequests = refs == null ? List.of() : refs.pullRequests();
                        List<SearchTerms.IssueRef> issues = refs == null ? List.of() : refs.issues();
                        placed.add(new Placed(kind, new TaskItem(row, projectOf(in, row)), pullRequests, issues));
                    });
        }
        placed.sort(Comparator.comparing(Placed::kind));
        return placed;
    }

    /**
     * A session's group. A babysitter with an active engagement goes to
     * Babysitting before any other check, so it never mixes into the regular
     * groups whatever its dot says; a watched session whose engagement needs
     * the human (escalated) goes to Needs you -- an escalation is the
     * babysitter asking for the human. Flagged takes a session only while it
     * is not running: a running flagged one shows in Running (its row wears
     * the flagged chip, the group header no longer says it).
     */
    static GroupKind sessionKind(TaskRow row) {
        if (row.babysit().filter(part -> part.role() == SessionBabysit.Role.BABYSITTER
                && part.active()).isPresent()) {
            return GroupKind.BABYSITTING;
        }
        if (DelayedTaskRows.needsYou(row) || row.babysit().filter(SessionBabysit::needsHuman).isPresent()) {
            return GroupKind.NEEDS_YOU;
        }
        if (row.session().flagged() && !row.running()) {
            return GroupKind.FLAGGED;
        }
        return row.running() ? GroupKind.RUNNING : GroupKind.IDLE;
    }

    /** Whether a loose task can appear for this query's group semantics, shared with tree item matching. */
    static boolean searchableTask(Inputs in, DelayedTaskRow row, boolean includeAllDone, Instant now, ZoneId zone) {
        TaskDocument document = in.documents().get(row.id());
        return taskKind(row, document, LocalDate.ofInstant(now, zone), zone).isPresent()
                || includeAllDone && document != null && document.status() == TaskStatus.DONE;
    }

    private static Optional<GroupKind> taskKind(DelayedTaskRow row, TaskDocument document, LocalDate today,
                                                ZoneId zone) {
        return switch (row.presentation()) {
            case BLOCKED -> Optional.of(GroupKind.BLOCKED);
            case REVIEW -> Optional.of(GroupKind.IN_REVIEW);
            case READY, INTERRUPTED -> Optional.of(GroupKind.READY);
            // A started task with no session row in this inbox has nowhere better to go.
            case STARTED -> Optional.of(GroupKind.RUNNING);
            case TERMINAL -> document != null && document.status() == TaskStatus.DONE
                    && document.doneAt().map(at -> LocalDate.ofInstant(at, zone).equals(today)).orElse(false)
                    ? Optional.of(GroupKind.DONE) : Optional.empty();
        };
    }

    private static Optional<ProjectRef> projectOf(Inputs in, DelayedTaskRow row) {
        Path root = registeredTaskRoot(in.taskRoots(), in.projects(), row).orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        return in.projects().stream().filter(project -> project.root().equals(root)).findFirst()
                .map(project -> new ProjectRef(root, project.displayName()));
    }

    /** The registered main root a delayed task resolves to, shared with the tree's search candidates. */
    static Optional<Path> registeredTaskRoot(Map<RepositoryId, Path> taskRoots, List<SharedProject> projects,
                                             DelayedTaskRow row) {
        Path root = taskRoots.get(row.repository());
        return root != null && projects.stream().anyMatch(project -> project.root().equals(root))
                ? Optional.of(root) : Optional.empty();
    }

    /** A session row and the project whose rows it came from. */
    private record SessionAt(TaskRow row, Optional<ProjectRef> project) {
    }

    private static void collectSessions(List<TaskNode> nodes, Optional<ProjectRef> project,
                                        Map<String, SessionAt> out) {
        Optional<ProjectRef> loose = project;
        for (TaskNode node : nodes) {
            switch (node) {
                case ProjectRow row -> collectSessions(row.children(),
                        Optional.of(new ProjectRef(row.project().root(), row.project().displayName())), out);
                // The unregistered current project's rows follow its "add" row at the top level.
                case AddCurrentProject add -> loose = Optional.of(new ProjectRef(add.mainRoot(), add.repoName()));
                case Section section -> collectSessions(section.children(), loose, out);
                case WorktreeGroup group -> {
                    for (TaskRow task : group.tasks()) {
                        out.putIfAbsent(task.session().id().toString(), new SessionAt(task, loose));
                    }
                }
                case TaskRow task -> out.putIfAbsent(task.session().id().toString(), new SessionAt(task, loose));
                default -> {
                }
            }
        }
    }
}
