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
 * <p>A session goes to the first group that takes it: Needs you
 * ({@link DelayedTaskRows#needsYou}: running and waiting for the user, or an
 * assigned task waiting for the user's confirmation), Flagged, Running, else
 * Idle. A task goes by its presentation: Blocked, In review, Ready to start
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

        /** Whether the group's rows count as the project's activity (Idle and Done do not). */
        boolean active() {
            return this != IDLE && this != DONE;
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
     */
    record Inputs(List<TaskNode> nodes, List<SharedProject> projects, List<DelayedTaskRow> tasks,
                  Map<TaskId, TaskDocument> documents, Map<RepositoryId, Path> taskRoots, Instant now,
                  ZoneId zone) {
        Inputs {
            nodes = List.copyOf(nodes);
            projects = List.copyOf(projects);
            tasks = List.copyOf(tasks);
            documents = Map.copyOf(documents);
            taskRoots = Map.copyOf(taskRoots);
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(zone, "zone");
        }
    }

    /**
     * The built inbox: the non-empty groups for the query, the registered
     * projects with their active-item counts (ignoring the query), and how
     * many of them have no active item at all.
     */
    record Inbox(List<Group> groups, List<Suggestion> projects, int quietProjects) {
        Inbox {
            groups = List.copyOf(groups);
            projects = List.copyOf(projects);
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

    /** A placed item, before the query narrows it. */
    private record Placed(GroupKind kind, Item item) {
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

    /** The search text without its {@code is:<group>} words: what titles elsewhere are matched by. Pure. */
    static String titleText(String text) {
        List<String> words = new ArrayList<>();
        for (String word : (text == null ? "" : text.strip()).split("\\s+")) {
            boolean groupWord = word.regionMatches(true, 0, "is:", 0, 3) && kindOf(word.substring(3)).isPresent();
            if (!groupWord && !word.isEmpty()) {
                words.add(word);
            }
        }
        return String.join(" ", words);
    }

    static Inbox build(Inputs in, Query query) {
        List<Placed> placed = place(in);

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

        Set<GroupKind> only = new HashSet<>();
        List<String> words = new ArrayList<>();
        for (String word : query.text().split("\\s+")) {
            Optional<GroupKind> kind = word.regionMatches(true, 0, "is:", 0, 3) ? kindOf(word.substring(3))
                    : Optional.empty();
            if (kind.isPresent()) {
                only.add(kind.get());
            } else if (!word.isEmpty()) {
                words.add(word);
            }
        }
        String needle = String.join(" ", words).toLowerCase(Locale.ROOT);

        Map<GroupKind, List<Item>> grouped = new EnumMap<>(GroupKind.class);
        for (Placed one : placed) {
            if (!only.isEmpty() && !only.contains(one.kind())) {
                continue;
            }
            if (query.project().isPresent() && !one.item().project().map(ProjectRef::root)
                    .equals(query.project())) {
                continue;
            }
            if (!needle.isEmpty() && !one.item().title().toLowerCase(Locale.ROOT).contains(needle)) {
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
                if (query.project().isPresent() && !item.project().map(ProjectRef::root).equals(query.project())
                        || !needle.isEmpty() && !item.title().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                grouped.computeIfAbsent(GroupKind.DONE, any -> new ArrayList<>()).add(item);
            }
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((kind, items) -> groups.add(new Group(kind,
                kind == GroupKind.DONE && only.contains(GroupKind.DONE) ? "Done" : kind.label(), items)));
        return new Inbox(groups, projects, quiet);
    }

    private static Optional<GroupKind> kindOf(String token) {
        for (GroupKind kind : GroupKind.values()) {
            if (kind.token().equalsIgnoreCase(token)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** Every item in its group, in group order and then each group's own order. */
    private static List<Placed> place(Inputs in) {
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
            placed.add(new Placed(sessionKind(at.row(), children), new SessionItem(at.row(), at.project(), titles)));
        }
        LocalDate today = LocalDate.ofInstant(in.now(), in.zone());
        for (DelayedTaskRow row : loose) {
            taskKind(row, in.documents().get(row.id()), today, in.zone())
                    .ifPresent(kind -> placed.add(new Placed(kind, new TaskItem(row, projectOf(in, row)))));
        }
        placed.sort(Comparator.comparing(Placed::kind));
        return placed;
    }

    private static GroupKind sessionKind(TaskRow row, List<DelayedTaskRow> children) {
        if (DelayedTaskRows.needsYou(row, children)) {
            return GroupKind.NEEDS_YOU;
        }
        if (row.session().flagged()) {
            return GroupKind.FLAGGED;
        }
        return row.running() ? GroupKind.RUNNING : GroupKind.IDLE;
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
        Path root = in.taskRoots().get(row.repository());
        if (root == null) {
            return Optional.empty();
        }
        return in.projects().stream()
                .filter(project -> project.root().equals(root))
                .findFirst()
                .map(project -> new ProjectRef(root, project.displayName()));
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
