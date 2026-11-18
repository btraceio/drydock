package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.SharedProjectResolver;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService;
import app.drydock.git.JiraConnection;
import app.drydock.git.JiraIssueWriter;
import app.drydock.github.GitHubIssueMirrorService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.delayedtasks.DelayedTaskActions;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.mcp.TaskToolContext;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskMirrorPlan;
import app.drydock.tasks.TaskMirrorPlan.Disposition;
import app.drydock.tasks.TaskMirrorRun;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The tree's mirror flows: a task and its resolved prerequisite closure
 * copied into GitHub issues or Jira tickets, each confirmed address recorded
 * back on its own task, and the way from an issue row back to its task.
 *
 * <p>One run is one background progress task started immediately in a
 * "preparing" phase: off the EDT it takes a fresh task snapshot, builds the
 * production {@link TaskGraph} with the captured slug projection, projects
 * the {@link TaskMirrorPlan}, resolves every document's shared project and
 * prepares each task's disposition (reuse, create, or skip with a reason;
 * a destination that is not usable is never a create). The single
 * confirmation is shown on the EDT; after acceptance the platform-free
 * {@link TaskMirrorRun} revalidates, remembers the accepted Jira choices,
 * creates in prerequisite order, records each address, ensures Jira Blocks
 * links, and returns one aggregate that is rendered as one notification.</p>
 *
 * <p>The order per task is remote first, then local: the address only
 * exists once the issue does. The local task is never removed. Secrets live
 * only in operation-local {@link JiraEndpoint}s, never in plan text,
 * remembered choices or reports.</p>
 */
public final class TaskMirroring {

    private static final Logger LOG = System.getLogger(TaskMirroring.class.getName());

    /** Matches the plugin.xml notificationGroup id. */
    private static final String GROUP = "Drydock tasks";

    /** The bound on every wait for the task service or a writer future. */
    private static final long WAIT_SECONDS = 60;

    /** One snapshot's mirror index, rebuilt only when the watched snapshot changes. */
    private record Index(TaskSnapshot snapshot, Map<String, TaskDocument> byMirror) { }

    private static volatile Index index;

    private TaskMirroring() {
    }

    /** Pure: Mirror to GitHub Issue is offered until the task has a GitHub mirror. */
    public static boolean offersGitHub(DelayedTaskRow row) {
        return !TaskMirror.hasMirror(row.mirrors(), TaskMirror.Kind.GITHUB);
    }

    /** Pure: Mirror to Jira is offered until the task has a Jira mirror. */
    public static boolean offersJira(DelayedTaskRow row) {
        return !TaskMirror.hasMirror(row.mirrors(), TaskMirror.Kind.JIRA);
    }

    /** Opens every recorded mirror of the task in the browser. */
    public static void openMirrors(DelayedTaskRow row) {
        row.mirrors().forEach(BrowserUtil::browse);
    }

    /**
     * The mirror addresses of every task whose {@code sessions} list names
     * the session, in snapshot order, each address once: two tasks naming
     * the same session may record the same mirror ({@link TaskMirror#byMirror}
     * keeps the first for the same reason), and a repeat would paint a
     * duplicate link and open a duplicate tab. From the task service's cached
     * watched snapshot only: no scan, no I/O (the same paint discipline as the
     * plan annotation). Empty before the first snapshot and for a session no
     * task claims.
     */
    public static List<String> mirrorsOfSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        TaskStoreWatcher.WatchedSnapshot watched =
                DrydockPluginService.getInstance().taskService().watchedSnapshot();
        if (watched == null) {
            return List.of();
        }
        List<String> mirrors = new ArrayList<>();
        for (TaskDocument document : watched.snapshot().bySessionReference()
                .getOrDefault(sessionId, List.of())) {
            mirrors.addAll(document.mirrors());
        }
        return TaskMirror.uniqueByAddress(mirrors);
    }

    /**
     * The task an issue page is a mirror of, from the task service's cached
     * watched snapshot only: no scan, no I/O. Empty before the first
     * snapshot and for an issue no task mirrors.
     */
    static Optional<TaskDocument> mirroredTask(String url) {
        TaskStoreWatcher.WatchedSnapshot watched = DrydockPluginService.getInstance().taskService().watchedSnapshot();
        if (watched == null || url == null) {
            return Optional.empty();
        }
        Index current = index;
        if (current == null || current.snapshot() != watched.snapshot()) {
            current = new Index(watched.snapshot(), TaskMirror.byMirror(watched.snapshot()));
            index = current;
        }
        return Optional.ofNullable(current.byMirror().get(TaskMirror.normalize(url)));
    }

    /** Opens the task file an issue page mirrors, when a task mirrors it. */
    static void openMirroredTask(Project from, String url) {
        mirroredTask(url).ifPresentOrElse(
                document -> DelayedTaskActions.edit(from, document.repository(), document.id(), document.title()),
                () -> refuse(from, "No Drydock task mirrors " + url + "."));
    }

    /** Mirror to GitHub Issue: the task and its prerequisites, one confirmation, one report. */
    public static void mirrorToGitHub(Project from, DelayedTaskRow row) {
        start(from, row, TaskMirror.Kind.GITHUB);
    }

    /** Mirror to Jira: the task and its prerequisites, one confirmation, one report. */
    public static void mirrorToJira(Project from, DelayedTaskRow row) {
        start(from, row, TaskMirror.Kind.JIRA);
    }

    private static String systemName(TaskMirror.Kind kind) {
        return kind == TaskMirror.Kind.GITHUB ? "GitHub" : "Jira";
    }

    /** EDT: starts the progress task at once; everything else, preparation included, runs in it. */
    private static void start(Project from, DelayedTaskRow row, TaskMirror.Kind kind) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Mirroring '" + row.title() + "' to " + systemName(kind) + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                new Flow(from, row, kind, indicator).execute();
            }
        });
    }

    /** What the operation keeps per resolved shared project; never printed, never persisted. */
    private record Context(Map<String, SharedProject> projects, Map<String, String> routes,
                           Map<String, JiraEndpoint> jira) {
        static final Context EMPTY = new Context(Map.of(), Map.of(), Map.of());
    }

    /** A project's own Jira connection and its secret, read off the EDT; the secret never prints. */
    private record JiraEndpoint(JiraConnection connection, String secret) {
        @Override
        public String toString() {
            return "JiraEndpoint[" + connection.baseUrl() + "]";
        }
    }

    /** The prepared proposal, or the reason the run cannot even be proposed. */
    private record Prepared(TaskMirrorRun.Proposal proposal, Context context, String refusal) {
        static Prepared refused(String message) {
            return new Prepared(null, Context.EMPTY, message);
        }
    }

    /** One project's creation readiness: a usable destination, or the reason there is none. */
    private record Readiness(TaskMirrorRun.Destination destination, String problem) { }

    /** Pure: why a GitHub create from this project is impossible, or empty. Enable flags never count. */
    static Optional<String> githubCreationProblem(String projectName, boolean remote, boolean checkoutPresent,
                                                  Optional<String> override) {
        if (remote) {
            return Optional.of("Mirroring to GitHub needs a local checkout; " + projectName
                    + " is a remote project.");
        }
        if (!checkoutPresent) {
            return Optional.of("The local checkout of " + projectName + " does not exist.");
        }
        if (override.isPresent() && !GhCliService.validRepository(override.get())) {
            return Optional.of("The GitHub repository setting of " + projectName
                    + " is not OWNER/REPO or HOST/OWNER/REPO.");
        }
        return Optional.empty();
    }

    /** Pure: why a Jira create from this project is impossible, or empty. A remote project is fine. */
    static Optional<String> jiraCreationProblem(String projectName, JiraProjectSettings settings,
                                                boolean hasSecret) {
        if (settings.connection("").isEmpty()) {
            return Optional.of("Jira is not enabled for " + projectName + "; turn it on in its Jira Settings"
                    + " first.");
        }
        return JiraProjectSettings.refusal(settings.enabled(), settings.deployment(), settings.baseUrl(),
                settings.email().orElse(""), hasSecret).map(refusal -> "Jira for " + projectName + ": "
                + refusal.message());
    }

    /**
     * Pure: compares the accepted plan with a freshly prepared one. Anything but a newly found
     * mirror of a task that was to be created (which narrows it to reuse) is a material change.
     */
    static TaskMirrorRun.Revalidation revalidation(TaskMirrorRun.Accepted accepted,
                                                   TaskMirrorRun.Proposal original,
                                                   TaskMirrorRun.Proposal fresh) {
        TaskMirrorPlan was = original.plan();
        TaskMirrorPlan now = fresh.plan();
        if (now.refusal().isPresent()) {
            return new TaskMirrorRun.Revalidation.Stop(now.refusal().get().message());
        }
        if (!idsOf(was).equals(idsOf(now))) {
            return new TaskMirrorRun.Revalidation.Stop("the set or order of participating tasks changed");
        }
        Set<String> edges = new LinkedHashSet<>();
        was.edges().forEach(e -> edges.add(e.prerequisite().id().text() + ">" + e.dependent().id().text()));
        Set<String> freshEdges = new LinkedHashSet<>();
        now.edges().forEach(e -> freshEdges.add(e.prerequisite().id().text() + ">" + e.dependent().id().text()));
        if (!edges.equals(freshEdges)) {
            return new TaskMirrorRun.Revalidation.Stop("the prerequisite relationships changed");
        }
        boolean jira = original.kind() == TaskMirror.Kind.JIRA;
        Map<TaskId, String> found = new LinkedHashMap<>();
        for (int i = 0; i < was.order().size(); i++) {
            TaskDocument before = was.order().get(i);
            TaskDocument after = now.order().get(i);
            if (!before.title().equals(after.title()) || !before.repository().equals(after.repository())
                    || !before.rawBody().equals(after.rawBody())) {
                return new TaskMirrorRun.Revalidation.Stop("task '" + before.title() + "' ("
                        + before.id().text() + ") changed");
            }
            Disposition expected = accepted.dispositions().get(before.id());
            Disposition current = now.dispositionOf(before.id()).orElse(new Disposition.Skip("not prepared"));
            boolean sameDestination = Objects.equals(original.destinations().get(before.id()),
                    fresh.destinations().get(before.id()));
            if (expected instanceof Disposition.Reuse) {
                if (!expected.equals(current)) {
                    return new TaskMirrorRun.Revalidation.Stop("the recorded mirror of '" + before.title()
                            + "' (" + before.id().text() + ") changed");
                }
                if (jira && !sameDestination) {
                    return new TaskMirrorRun.Revalidation.Stop("the Jira connection of '" + before.title()
                            + "' (" + before.id().text() + ") changed");
                }
            } else if (expected instanceof Disposition.Create) {
                if (current instanceof Disposition.Reuse reuse) {
                    found.put(before.id(), reuse.address());
                } else if (current instanceof Disposition.Skip skip) {
                    return new TaskMirrorRun.Revalidation.Stop("'" + before.title() + "' (" + before.id().text()
                            + ") can no longer be created: " + skip.reason());
                } else if (!sameDestination) {
                    return new TaskMirrorRun.Revalidation.Stop("the destination of '" + before.title() + "' ("
                            + before.id().text() + ") changed");
                }
            }
        }
        return found.isEmpty() ? new TaskMirrorRun.Revalidation.Unchanged()
                : new TaskMirrorRun.Revalidation.Narrow(found);
    }

    private static List<TaskId> idsOf(TaskMirrorPlan plan) {
        return plan.order().stream().map(TaskDocument::id).toList();
    }

    /** Pure: the one report of a finished run, as plain text; confirmed addresses always appear. */
    static String report(TaskMirrorRun.Result result) {
        StringBuilder out = new StringBuilder(result.message());
        if (!result.created().isEmpty()) {
            out.append("\n\nCreated (").append(result.created().size()).append("):");
            for (TaskMirrorRun.Created created : result.created()) {
                out.append("\n- ").append(taskLabel(created.task())).append(" -> ").append(created.address());
                if (created.recordProblem().isPresent()) {
                    out.append(" (NOT recorded on the task)");
                }
            }
        }
        if (!result.reused().isEmpty()) {
            out.append("\n\nReused (").append(result.reused().size()).append("):");
            for (TaskMirrorRun.Reused reused : result.reused()) {
                out.append("\n- ").append(taskLabel(reused.task())).append(" -> ").append(reused.address());
            }
        }
        if (!result.links().isEmpty()) {
            long present = result.links().stream().filter(TaskMirrorRun.EnsuredLink::alreadyPresent).count();
            out.append("\n\nJira Blocks links in place: ").append(result.links().size()).append(" (")
                    .append(present).append(" already present)");
        }
        if (!result.omissions().isEmpty()) {
            out.append("\n\nLeft out (").append(result.omissions().size()).append("):");
            for (TaskMirrorRun.Omission omission : result.omissions()) {
                out.append("\n- ").append(omission.subject()).append(": ").append(omission.reason());
            }
        }
        if (!result.failures().isEmpty()) {
            out.append("\n\nProblems (").append(result.failures().size()).append("):");
            for (TaskMirrorRun.Failure failure : result.failures()) {
                out.append("\n- ").append(failure.operation().name().toLowerCase(Locale.ROOT)).append(" ")
                        .append(failure.subject()).append(": ").append(failure.message());
            }
        }
        return out.toString();
    }

    private static String taskLabel(TaskDocument task) {
        return "'" + task.title() + "' (" + task.id().text() + ")";
    }

    /** Pure: error when something needs manual recovery, warning for other problems, else information. */
    static NotificationType severity(TaskMirrorRun.Result result) {
        if (result.clean()) {
            return NotificationType.INFORMATION;
        }
        boolean manual = result.failures().stream().anyMatch(failure -> failure.uncertain()
                || failure.operation() == TaskMirrorRun.Operation.RECORD)
                || (!result.failures().isEmpty() && result.created().isEmpty());
        return manual ? NotificationType.ERROR : NotificationType.WARNING;
    }

    /** One mirror run: preparation, the callbacks {@link TaskMirrorRun} needs, and the report. */
    private static final class Flow {
        private final Project from;
        private final DelayedTaskRow row;
        private final TaskMirror.Kind kind;
        private final ProgressIndicator indicator;
        private final DelayedTaskService service = DrydockPluginService.getInstance().taskService();
        private final GitHubIssueMirrorService github;
        private final JiraIssueWriter jira;
        private final AtomicReference<Context> active = new AtomicReference<>(Context.EMPTY);
        private TaskMirrorRun.Proposal proposed;

        Flow(Project from, DelayedTaskRow row, TaskMirror.Kind kind, ProgressIndicator indicator) {
            this.from = from;
            this.row = row;
            this.kind = kind;
            this.indicator = indicator;
            this.github = kind == TaskMirror.Kind.GITHUB ? new GitHubIssueMirrorService() : null;
            this.jira = kind == TaskMirror.Kind.JIRA ? new JiraIssueWriter(JiraIssueSource::ideClientBuilder) : null;
        }

        /** Background: prepare, ask, run, report; every path ends here and clears the progress. */
        void execute() {
            indicator.setText("Preparing the mirror plan for '" + row.title() + "'");
            try {
                Prepared prepared = prepare();
                if (prepared.refusal() != null) {
                    refuse(from, prepared.refusal());
                    return;
                }
                if (indicator.isCanceled()) {
                    notifyReport(from, "Mirror cancelled", "Cancelled while preparing; nothing was written.",
                            NotificationType.INFORMATION, List.of());
                    return;
                }
                proposed = prepared.proposal();
                active.set(prepared.context());
                TaskMirrorRun.Result result = new TaskMirrorRun(new TaskMirrorRun.Effects(this::confirm,
                        this::revalidate, this::remember, this::create, this::record, this::link,
                        indicator::isCanceled, new TaskMirrorRun.Progress() {
                            @Override
                            public void phase(String text) {
                                indicator.setText(text);
                            }

                            @Override
                            public void finish() {
                                // The background task ends when execute() returns; nothing else to clear.
                            }
                        })).run(proposed);
                render(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                refuse(from, "Mirroring '" + row.title() + "' was interrupted. Check the tracker and the task's"
                        + " 'mirrors' list before retrying.");
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not mirror " + row.id().text() + " to " + systemName(kind), e);
                refuse(from, "Drydock could not mirror '" + row.title() + "' to " + systemName(kind) + ": "
                        + WorktreeErrors.visibleMessage(e));
            } finally {
                close();
            }
        }

        private void close() {
            if (github != null) {
                github.close();
            }
            if (jira != null) {
                jira.close();
            }
        }

        // ---- preparation ------------------------------------------------

        private Prepared prepare() throws InterruptedException, ExecutionException, TimeoutException {
            TaskSnapshot snapshot = service.snapshot().get(WAIT_SECONDS, TimeUnit.SECONDS);
            TaskDocument root = snapshot.find(row.id()).orElse(null);
            if (root == null) {
                boolean collided = snapshot.idCollisions().stream().anyMatch(c -> c.id().equals(row.id()));
                return Prepared.refused(collided
                        ? "The task id '" + row.id().text() + "' is claimed by more than one file."
                        : "The task '" + row.title() + "' is no longer in the task store.");
            }
            if (!root.repository().equals(row.repository())) {
                return Prepared.refused("The task '" + row.title() + "' now belongs to a different project than"
                        + " the row shows; refresh the tree and retry.");
            }
            Function<RepositoryId, String> display = service.displaySlugs();
            Map<RepositoryId, String> slugs = new HashMap<>();
            for (RepositoryId repository : snapshot.repositories()) {
                slugs.put(repository, display.apply(repository));
            }
            TaskGraph graph = TaskGraph.of(snapshot, slugs::get);
            TaskMirrorPlan plan = TaskMirrorPlan.of(snapshot, graph, row.id());
            if (plan.refusal().isPresent()) {
                return new Prepared(new TaskMirrorRun.Proposal(kind, plan, Map.of(), Map.of()), Context.EMPTY, null);
            }
            return new Resolution().resolve(plan);
        }

        /** The per-preparation caches: one resolution, one readiness per project. */
        private final class Resolution {
            private final DrydockPluginService plugin = DrydockPluginService.getInstance();
            private final List<SharedProject> shared = plugin.sharedProjects().projects();
            private final List<Repository> install = plugin.repositories();
            private final Map<RepositoryId, Optional<SharedProject>> resolved = new HashMap<>();
            private final Map<String, SharedProject> projects = new LinkedHashMap<>();
            private final Map<String, String> routes = new HashMap<>();
            private final Map<String, JiraEndpoint> endpoints = new HashMap<>();
            private final Map<String, Readiness> readiness = new HashMap<>();

            Prepared resolve(TaskMirrorPlan plan) throws InterruptedException {
                boolean jiraKind = kind == TaskMirror.Kind.JIRA;
                Map<TaskId, Disposition> dispositions = new LinkedHashMap<>();
                Map<TaskId, TaskMirrorRun.Destination> destinations = new LinkedHashMap<>();
                Map<String, TaskMirrorRun.JiraChoice> choices = new LinkedHashMap<>();
                for (TaskDocument doc : plan.order()) {
                    Disposition base = Disposition.forMirrors(doc.mirrors(), kind);
                    Optional<SharedProject> project = resolved.computeIfAbsent(doc.repository(),
                            id -> resolveShared(shared, install, id));
                    project.ifPresent(p -> projects.put(p.id().toString(), p));
                    if (base instanceof Disposition.Reuse) {
                        dispositions.put(doc.id(), base);
                        if (jiraKind && project.isPresent()) {
                            // A reused ticket still needs its own usable connection for the Blocks links.
                            TaskMirrorRun.Destination destination = readiness(project.get()).destination();
                            if (destination != null) {
                                destinations.put(doc.id(), destination);
                            }
                        }
                        continue;
                    }
                    if (project.isEmpty()) {
                        dispositions.put(doc.id(), new Disposition.Skip("its project is not registered in Drydock"
                                + " (repository " + doc.repository().value() + ")"));
                        continue;
                    }
                    Readiness ready = readiness(project.get());
                    if (ready.destination() == null) {
                        dispositions.put(doc.id(), new Disposition.Skip(ready.problem()));
                        continue;
                    }
                    dispositions.put(doc.id(), new Disposition.Create());
                    destinations.put(doc.id(), ready.destination());
                    if (jiraKind) {
                        String key = JiraMirrorDialog.lastProjectKey(project.get().id());
                        String type = JiraMirrorDialog.lastIssueType(project.get().id());
                        choices.putIfAbsent(ready.destination().project(), new TaskMirrorRun.JiraChoice(
                                key == null ? "" : key,
                                type == null || type.isBlank() ? JiraMirrorDialog.DEFAULT_ISSUE_TYPE : type));
                    }
                }
                Disposition rootDisposition = dispositions.get(plan.root().id());
                if (rootDisposition instanceof Disposition.Reuse reuse) {
                    return Prepared.refused("'" + plan.root().title() + "' already has a " + systemName(kind)
                            + " mirror: " + reuse.address());
                }
                TaskMirrorPlan prepared = plan.withDispositions(dispositions);
                return new Prepared(new TaskMirrorRun.Proposal(kind, prepared, destinations, choices),
                        new Context(Map.copyOf(projects), Map.copyOf(routes), Map.copyOf(endpoints)), null);
            }

            private Readiness readiness(SharedProject project) throws InterruptedException {
                String key = project.id().toString();
                Readiness known = readiness.get(key);
                if (known == null) {
                    known = kind == TaskMirror.Kind.GITHUB ? githubReadiness(project, key)
                            : jiraReadiness(project, key);
                    readiness.put(key, known);
                }
                return known;
            }

            private Readiness githubReadiness(SharedProject project, String key) throws InterruptedException {
                Optional<String> override = GitHubProjectSettings.load(project.id()).repository();
                Optional<String> problem = githubCreationProblem(project.displayName(), project.isRemote(),
                        !project.isRemote() && Files.isDirectory(project.root()), override);
                if (problem.isPresent()) {
                    return new Readiness(null, problem.get());
                }
                try {
                    GitHubIssueMirrorService.Preparation preparation =
                            github.prepare(project.root(), override).get(WAIT_SECONDS, TimeUnit.SECONDS);
                    if (preparation instanceof GitHubIssueMirrorService.Confirmed confirmed) {
                        routes.put(key, confirmed.route());
                        return new Readiness(new TaskMirrorRun.Destination(key,
                                "GitHub repository " + confirmed.route() + " (" + project.displayName() + ")",
                                null), null);
                    }
                    return new Readiness(null, "the GitHub repository of " + project.displayName()
                            + " could not be confirmed: "
                            + ((GitHubIssueMirrorService.PreparationFailed) preparation).message());
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "GitHub destination probe failed for " + project.displayName(), e);
                    return new Readiness(null, "the GitHub repository of " + project.displayName()
                            + " could not be confirmed: " + WorktreeErrors.visibleMessage(e));
                }
            }

            private Readiness jiraReadiness(SharedProject project, String key) {
                JiraProjectSettings settings = JiraProjectSettings.load(project.id());
                String secret = settings.enabled() ? JiraCredentials.read(settings).orElse("") : "";
                Optional<String> problem = jiraCreationProblem(project.displayName(), settings, !secret.isEmpty());
                if (problem.isPresent()) {
                    return new Readiness(null, problem.get());
                }
                JiraConnection connection = settings.connection("").orElseThrow();
                endpoints.put(key, new JiraEndpoint(connection, secret));
                String site = TaskMirror.jiraAddress(connection.browseUrl("DRYDOCK-1"))
                        instanceof TaskMirror.JiraAddress.Usable usable ? usable.site() : null;
                return new Readiness(new TaskMirrorRun.Destination(key,
                        "Jira " + connection.baseUrl() + " (" + project.displayName() + ")", site), null);
            }
        }

        // ---- callbacks ----------------------------------------------------

        /** Background: shows the dialog on the EDT; disposal or cancel completes it with no accepted plan. */
        private TaskMirrorRun.Consent confirm(TaskMirrorRun.Proposal proposal) throws InterruptedException {
            CompletableFuture<TaskMirrorRun.Consent> pending = new CompletableFuture<>();
            AtomicBoolean abandoned = new AtomicBoolean();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (abandoned.get() || from.isDisposed()) {
                    pending.complete(new TaskMirrorRun.Consent.Cancel());
                    return;
                }
                try {
                    pending.complete(TaskMirrorDialog.ask(from, proposal));
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "The mirror confirmation failed", e);
                    pending.complete(new TaskMirrorRun.Consent.Refuse("The confirmation dialog failed: "
                            + WorktreeErrors.visibleMessage(e)));
                }
            }, ModalityState.nonModal(), from.getDisposed());
            while (true) {
                try {
                    return pending.get(250, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (from.isDisposed() || indicator.isCanceled()) {
                        abandoned.set(true);
                        return new TaskMirrorRun.Consent.Cancel();
                    }
                } catch (ExecutionException e) {
                    return new TaskMirrorRun.Consent.Refuse("The confirmation failed: "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        }

        /** Background: a fresh preparation compared with what the user accepted. */
        private TaskMirrorRun.Revalidation revalidate(TaskMirrorRun.Accepted accepted) throws Exception {
            Prepared fresh = prepare();
            if (fresh.refusal() != null) {
                return new TaskMirrorRun.Revalidation.Stop(fresh.refusal());
            }
            TaskMirrorRun.Revalidation answer = revalidation(accepted, proposed, fresh.proposal());
            if (!(answer instanceof TaskMirrorRun.Revalidation.Stop)) {
                active.set(fresh.context());
            }
            return answer;
        }

        /** Background: the accepted choices, per resolved shared project. */
        private void remember(Map<String, TaskMirrorRun.JiraChoice> choices) {
            Context context = active.get();
            choices.forEach((project, choice) -> {
                SharedProject shared = context.projects().get(project);
                if (shared != null) {
                    JiraMirrorDialog.remember(shared.id(), choice.key(), choice.type());
                }
            });
        }

        /** Background: one create in the task's own project; unknown effects stay uncertain. */
        private TaskMirrorRun.CreateOutcome create(TaskMirrorRun.CreateRequest request) throws InterruptedException {
            Context context = active.get();
            String project = request.destination().project();
            SharedProject shared = context.projects().get(project);
            if (shared == null) {
                return new TaskMirrorRun.CreateOutcome.Failed("the project of '" + request.task().title()
                        + "' is not resolved");
            }
            TaskDocument task = request.task();
            return request.kind() == TaskMirror.Kind.GITHUB ? createGitHub(context, shared, request)
                    : createJira(context, shared, task, request);
        }

        private TaskMirrorRun.CreateOutcome createGitHub(Context context, SharedProject shared,
                                                         TaskMirrorRun.CreateRequest request)
                throws InterruptedException {
            String route = context.routes().get(request.destination().project());
            if (route == null) {
                return new TaskMirrorRun.CreateOutcome.Failed("no confirmed GitHub repository for "
                        + shared.displayName());
            }
            TaskDocument task = request.task();
            String body = TaskMirror.githubBody(task, shared.displayName(), request.dependencies());
            try {
                GitHubIssueMirrorService.CreateResult result = github.createPinned(shared.root(), route,
                        task.title(), body).get(WAIT_SECONDS, TimeUnit.SECONDS);
                return switch (result) {
                    case GitHubIssueMirrorService.Created created ->
                            new TaskMirrorRun.CreateOutcome.Created(created.url(), Optional.empty());
                    case GitHubIssueMirrorService.Rejected rejected ->
                            new TaskMirrorRun.CreateOutcome.Failed("GitHub did not create the issue: "
                                    + rejected.message());
                    case GitHubIssueMirrorService.Unavailable unavailable ->
                            new TaskMirrorRun.CreateOutcome.Failed(
                                    "Mirroring to GitHub needs an authenticated gh: " + unavailable.message());
                    case GitHubIssueMirrorService.Uncertain uncertain ->
                            new TaskMirrorRun.CreateOutcome.Uncertain(uncertain.message());
                };
            } catch (TimeoutException | ExecutionException e) {
                LOG.log(Level.WARNING, "GitHub create did not return for " + task.id().text(), e);
                return new TaskMirrorRun.CreateOutcome.Uncertain("the GitHub create did not return a result ("
                        + WorktreeErrors.visibleMessage(e) + ")");
            }
        }

        private TaskMirrorRun.CreateOutcome createJira(Context context, SharedProject shared, TaskDocument task,
                                                       TaskMirrorRun.CreateRequest request) {
            JiraEndpoint endpoint = context.jira().get(request.destination().project());
            Optional<TaskMirrorRun.JiraChoice> choice = request.choice();
            if (endpoint == null || choice.isEmpty()) {
                return new TaskMirrorRun.CreateOutcome.Failed("no usable Jira connection or project choice for "
                        + shared.displayName());
            }
            JiraIssueWriter.CreateResult result = jira.createChecked(endpoint.connection(), endpoint.secret(),
                    choice.get().key(), choice.get().type(), task.title(),
                    TaskMirror.jiraDescription(task, shared.displayName()), TaskMirror.JIRA_PROPERTY,
                    TaskMirror.metadata(task, shared.displayName()));
            return switch (result) {
                case JiraIssueWriter.Created created ->
                        new TaskMirrorRun.CreateOutcome.Created(created.url(), created.propertyProblem());
                case JiraIssueWriter.Failed failed ->
                        new TaskMirrorRun.CreateOutcome.Failed("Jira did not create the ticket: "
                                + failed.message());
                case JiraIssueWriter.Uncertain uncertain ->
                        new TaskMirrorRun.CreateOutcome.Uncertain(uncertain.message());
            };
        }

        /** Background: records through the service's single writer; failures surface to the coordinator. */
        private TaskMirrorRun.RecordOutcome record(TaskId task, String address) throws Exception {
            TaskToolContext.Outcome outcome = service.recordMirror(task, address)
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);
            return outcome.refused() ? new TaskMirrorRun.RecordOutcome.Refused(outcome.refusal())
                    : new TaskMirrorRun.RecordOutcome.Committed();
        }

        /**
         * Background: both endpoints are read with their own project's connection before a possible
         * POST; either correct answer is presence, a failed read blocks the POST, and the POST uses
         * the dependent's connection.
         */
        private TaskMirrorRun.LinkOutcome link(TaskMirrorRun.LinkRequest request) {
            Context context = active.get();
            JiraEndpoint prerequisite = context.jira().get(request.prerequisiteDestination().project());
            JiraEndpoint dependent = context.jira().get(request.dependentDestination().project());
            if (prerequisite == null || dependent == null) {
                return new TaskMirrorRun.LinkOutcome.LookupFailed("an endpoint's project has no usable Jira"
                        + " connection");
            }
            JiraIssueWriter.LinkLookup first = jira.lookupLinks(prerequisite.connection(), prerequisite.secret(),
                    request.prerequisiteKey());
            JiraIssueWriter.LinkLookup second = jira.lookupLinks(dependent.connection(), dependent.secret(),
                    request.dependentKey());
            boolean present = first instanceof JiraIssueWriter.Links links
                    && JiraIssueWriter.blocksPresent(links.entries(), JiraIssueWriter.Queried.PREREQUISITE,
                            request.prerequisiteKey(), request.dependentKey());
            present |= second instanceof JiraIssueWriter.Links links
                    && JiraIssueWriter.blocksPresent(links.entries(), JiraIssueWriter.Queried.DEPENDENT,
                            request.prerequisiteKey(), request.dependentKey());
            Optional<String> problem = lookupProblem(first, request.prerequisiteKey())
                    .or(() -> lookupProblem(second, request.dependentKey()));
            if (present) {
                return new TaskMirrorRun.LinkOutcome.AlreadyPresent(problem);
            }
            if (problem.isPresent()) {
                return new TaskMirrorRun.LinkOutcome.LookupFailed(problem.get());
            }
            return switch (jira.createBlocksLink(dependent.connection(), dependent.secret(),
                    request.prerequisiteKey(), request.dependentKey())) {
                case JiraIssueWriter.Linked linked -> new TaskMirrorRun.LinkOutcome.Linked();
                case JiraIssueWriter.LinkFailed failed -> new TaskMirrorRun.LinkOutcome.Failed(failed.message());
                case JiraIssueWriter.LinkUncertain uncertain ->
                        new TaskMirrorRun.LinkOutcome.Uncertain(uncertain.message());
            };
        }

        private static Optional<String> lookupProblem(JiraIssueWriter.LinkLookup lookup, String key) {
            return lookup instanceof JiraIssueWriter.LookupFailed failed
                    ? Optional.of("reading the links of " + key + " failed: " + failed.message())
                    : Optional.empty();
        }

        // ---- report -------------------------------------------------------

        /** Background: the one aggregate, on the EDT; refusals and stops as an error dialog. */
        private void render(TaskMirrorRun.Result result) {
            switch (result.status()) {
                case REFUSED, STOPPED -> refuse(from, result.message());
                case CANCELLED -> notifyReport(from, "Mirror cancelled", result.message(),
                        NotificationType.INFORMATION, List.of());
                case COMPLETED -> notifyReport(from, result.clean()
                                ? "Mirrored '" + row.title() + "' to " + systemName(kind)
                                : "Mirroring '" + row.title() + "' to " + systemName(kind)
                                        + " finished with problems",
                        report(result), severity(result),
                        result.created().stream().map(TaskMirrorRun.Created::address).toList());
            }
        }
    }

    /** Any thread: the one report balloon, with an Open action for the confirmed new addresses. */
    private static void notifyReport(Project from, String title, String text, NotificationType type,
                                     List<String> created) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Notification balloon = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                    .createNotification(title, escape(text), type);
            if (type != NotificationType.INFORMATION) {
                balloon.setImportant(true);
            }
            if (!created.isEmpty()) {
                balloon.addAction(NotificationAction.createSimpleExpiring("Open created",
                        () -> created.forEach(BrowserUtil::browse)));
            }
            balloon.notify(from);
        }, ModalityState.nonModal(), from.getDisposed());
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    /**
     * Pure: the shared-list project a task's repository id names. Delegates to
     * {@link SharedProjectResolver#resolve}, the single copy of the dual-registry
     * resolution this package shares with delayedtasks, so the mirror-time resolution
     * and the create-time stamp cannot drift apart. The shared entry is what the flow
     * needs: the Jira and GitHub settings are keyed by the shared registry's id. Empty
     * when neither registry maps the id -- the caller refuses.
     */
    static Optional<SharedProject> resolveShared(List<SharedProject> shared, List<Repository> install,
                                                 RepositoryId repository) {
        return SharedProjectResolver.resolve(shared, install, repository);
    }

    /** Any thread: one visible refusal, shown on the EDT. */
    private static void refuse(Project from, String message) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(from, message, "Drydock"),
                ModalityState.nonModal(), from.getDisposed());
    }
}
