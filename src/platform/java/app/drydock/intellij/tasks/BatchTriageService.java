package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService;
import app.drydock.git.JiraConnection;
import app.drydock.git.JiraIssue;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.JiraIssueWriter;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.projects.SharedProject;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.ProjectWorkflowResolver;
import app.drydock.sdlc.WorkflowResolution;
import app.drydock.sdlc.WorkflowTarget;
import app.drydock.tasks.BatchTriagePlan;
import app.drydock.tasks.BatchTriageQueue;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.messages.MessageBusConnection;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The batch triage behind the Issues nodes' {@code Triage untriaged…} action:
 * it runs the registered triage skill (or the built-in triage steps when the
 * project registers none) over every issue the section lists that does not
 * carry the {@code triaged} label, one agent session per issue, at most N at
 * a time — N asked per run, remembered — and launches the next session as
 * each running one ends.
 *
 * <p>Labeling is split by what can actually write. A GitHub session labels
 * its own issue through {@code gh}: the labeling step is appended to the
 * prompt unless the triage methodology already talks about labels (the
 * hybrid rule, {@link BatchTriagePlan#mentionsLabeling}). A Jira session has
 * no Jira credentials — the token never leaves the password safe — so the
 * service itself adds the label through {@link JiraIssueWriter} just before
 * the session launches, and only when the methodology does not already
 * handle labeling. Either way an issue is in-flight from the moment it is
 * queued, so a second invocation while a batch runs (or against the
 * one-minute-cached listing) never double-queues it.</p>
 *
 * <p>One batch per system per project: a second click while one runs is
 * refused with a visible message, never silently queued. Sessions run in the
 * project's main checkout in write mode — the shape the per-issue Triage
 * dialog produces — because the GitHub labeling step needs the agent's
 * shell.</p>
 *
 * <p>Pause and resume. {@code Cancel} (the node's {@code Cancel Triage
 * Batch}) stops launching; sessions already asked for keep running and label
 * their own issues, and the not-yet-launched remainder is persisted per
 * project and system — also updated as the queue drains, so an IDE quit
 * mid-batch leaves the same remainder. {@code Resume Triage Batch…} asks the
 * parallelism again and intersects the stored queue with a fresh listing:
 * issues labeled meanwhile, or gone from the tracker, drop out; an empty
 * survivor set clears the pause and says so. Resuming while a paused batch's
 * sessions still run carries those in-flight identities into the new batch's
 * exclusion set, so a still-running session's issue is never queued
 * twice.</p>
 *
 * <p>Threading: the entry points assert the EDT (they show dialogs), then
 * every fetch, resolution, label write and launch runs on background
 * threads; completions from the agent-execution message bus arrive on
 * whatever thread published them and only mutate state under the lock. The
 * service is a project service, so the queue survives tool-window rebuilds
 * and dies with the project.</p>
 */
public final class BatchTriageService implements Disposable {

    private static final Logger LOG = Logger.getInstance(BatchTriageService.class);

    /** The balloon group the batch summary publishes through. */
    static final String NOTIFICATION_GROUP = "Drydock tasks";

    /** The listing and launch budget for one step; a wedged target must not park the batch. */
    private static final long STEP_TIMEOUT_SECONDS = 60;

    /** The parallelism the dialog pre-fills when nothing is remembered. */
    static final int DEFAULT_PARALLELISM = 3;

    /** The remembered parallelism, shared by both systems. */
    static final String PARALLELISM_PROPERTY = "drydock.batchTriage.parallelism";

    /** The PropertiesComponent key prefix of a paused batch's remainder, per repository and system. */
    private static final String PAUSED_PREFIX = "drydock.batchTriage.paused.";

    private static final String GITHUB = "GitHub";
    private static final String JIRA = "Jira";

    /** The registered triage methodology, or empty when the project registers none (built-in steps apply). */
    interface Resolution {
        Optional<String> promptText();
    }

    /** One issue's session launch; completes with the running session or exceptionally. */
    interface Launcher {
        CompletableFuture<ManagedAgentSession> launch(String displayName, String prompt);
    }

    /** The Jira label write for one issue; present names the failure. */
    interface Labeler {
        Optional<String> addLabel(JiraIssue issue);
    }

    /** A failed listing, retaining the group name for an actionable diagnostic. */
    record JiraListingFailure(String groupName, String message) {
    }

    /** The production-used gate shared by Jira triage and resume after group fetches. */
    record JiraListingDecision(boolean canceled, Optional<String> failureMessage) {
        boolean mayProceed() {
            return !canceled && failureMessage.isEmpty();
        }
    }

    /** Pure gate/message construction; cancellation always takes precedence over listing failures. */
    static JiraListingDecision jiraListingDecision(boolean canceled, String projectName, String action,
                                                    List<JiraListingFailure> failures) {
        if (canceled) {
            return new JiraListingDecision(true, Optional.empty());
        }
        if (failures.isEmpty()) {
            return new JiraListingDecision(false, Optional.empty());
        }
        String groups = failures.stream()
                .map(failure -> failure.groupName() + ": " + failure.message())
                .collect(java.util.stream.Collectors.joining("; "));
        return new JiraListingDecision(false, Optional.of("The Jira issues of " + projectName
                + " could not be listed: " + groups + ". " + action));
    }

    /** Closeable per-call adapter for the HTTP-backed label writer. */
    interface LabelWriter extends AutoCloseable {
        JiraIssueWriter.LabelOutcome addLabel(JiraConnection connection, String secret, String issueKey,
                                               String label);

        @Override
        void close();
    }

    interface LabelWriterFactory {
        LabelWriter create();
    }

    /** Snapshot for deterministic platform tests; it exposes no mutable batch state. */
    record JiraBatchSnapshot(int pending, int running, int launchFailures, int labelFailures, boolean paused) {
    }

    private final Project project;
    private final Object lock = new Object();
    private GitHubBatch githubBatch;
    private JiraBatch jiraBatch;
    private volatile boolean disposed;

    public BatchTriageService(@NotNull Project project) {
        this.project = project;
        MessageBusConnection bus = project.getMessageBus().connect(this);
        bus.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override
            public void executionTerminated(@NotNull ManagedSessionId sessionId, boolean stopRequested,
                                            Integer exitCode) {
                boolean freed;
                synchronized (lock) {
                    freed = (githubBatch != null && githubBatch.ended(sessionId))
                            | (jiraBatch != null && jiraBatch.ended(sessionId));
                }
                if (freed) {
                    drain();
                }
            }
        });
    }

    // ---- entries (EDT; the actions call these) -------------------------------

    /** The action body for the GitHub Issues node: fetches fresh, filters, asks, queues. */
    static void triageGitHub(Project project, SharedProject shared, Path mainRoot) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        BatchTriageService service = project.getService(BatchTriageService.class);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing batch triage…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    GitHubProjectSettings settings = GitHubProjectSettings.load(shared.id());
                    if (!settings.issuesEnabled()) {
                        service.info("GitHub issues are disabled for " + shared.displayName()
                                + "; enable them in the project's repository settings.");
                        return;
                    }
                    List<GhCliService.OpenIssue> untriaged;
                    try (GhCliService github = new GhCliService()) {
                        GhCliService.IssueListing listing = github
                                .openIssues(mainRoot, settings.repository())
                                .get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (indicator.isCanceled()) {
                            return;
                        }
                        if (listing instanceof GhCliService.IssueListing.Failed failed) {
                            service.info("The GitHub issues of " + shared.displayName()
                                    + " could not be listed: " + failed.message());
                            return;
                        }
                        if (listing instanceof GhCliService.IssueListing.Unsupported) {
                            service.info("No GitHub repository is configured for " + shared.displayName()
                                    + "; set it in the project's repository settings.");
                            return;
                        }
                        untriaged = listing instanceof GhCliService.IssueListing.Listed listed
                                ? BatchTriagePlan.untriagedGitHub(listed.issues(), service.githubInFlight())
                                : List.of();
                    }
                    if (indicator.isCanceled()) {
                        return;
                    }
                    service.offerGitHubBatch(shared, mainRoot, untriaged);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.warn("Batch triage of " + shared.displayName() + " could not list the GitHub issues", e);
                    service.info("Batch triage could not list the GitHub issues of " + shared.displayName()
                            + ": " + message(e));
                }
            }
        });
    }

    /** The action body for the Jira node: fetches every group fresh, filters, asks, queues. */
    static void triageJira(Project project, SharedProject shared, Path mainRoot) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        BatchTriageService service = project.getService(BatchTriageService.class);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing batch triage…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    JiraProjectSettings settings = JiraProjectSettings.load(shared.id());
                    List<JiraGroup> groups = settings.enabled() ? JiraGroups.load(shared.id()).groups() : List.of();
                    if (groups.isEmpty()) {
                        service.info("Jira is not configured for " + shared.displayName()
                                + "; add a group under the Jira node first.");
                        return;
                    }
                    Map<String, JiraIssue> all = new LinkedHashMap<>();
                    List<JiraListingFailure> failures = new ArrayList<>();
                    try (JiraIssueSource source = new JiraIssueSource()) {
                        for (JiraGroup group : groups) {
                            if (indicator.isCanceled()) {
                                return;
                            }
                            JiraIssueListing listing = source
                                    .issuesOf(mainRoot, settings, group)
                                    .get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            if (indicator.isCanceled()) {
                                return;
                            }
                            if (listing instanceof JiraIssueListing.Listed listed) {
                                for (JiraIssue issue : listed.issues()) {
                                    all.putIfAbsent(issue.key(), issue);
                                }
                            } else if (listing instanceof JiraIssueListing.Failed f) {
                                failures.add(new JiraListingFailure(group.name(), f.message()));
                            }
                        }
                    }
                    JiraListingDecision decision = jiraListingDecision(indicator.isCanceled(), shared.displayName(),
                            "The batch was not started.", failures);
                    if (decision.canceled()) {
                        return;
                    }
                    if (decision.failureMessage().isPresent()) {
                        service.info(decision.failureMessage().get());
                        return;
                    }
                    if (indicator.isCanceled()) {
                        return;
                    }
                    service.offerJiraBatch(shared, mainRoot, settings,
                            BatchTriagePlan.untriagedJira(List.copyOf(all.values()), service.jiraInFlight()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.warn("Batch triage of " + shared.displayName() + " could not list the Jira issues", e);
                    service.info("Batch triage could not list the Jira issues of " + shared.displayName()
                            + ": " + message(e));
                }
            }
        });
    }

    // ---- batch setup (background thread) ---------------------------------------

    /** Asks the parallelism and, when the user confirms, queues the GitHub batch. */
    private void offerGitHubBatch(SharedProject shared, Path mainRoot, List<GhCliService.OpenIssue> untriaged) {
        if (untriaged.isEmpty()) {
            info("Every GitHub issue of " + shared.displayName() + " is already triaged.");
            return;
        }
        Optional<Integer> asked = TriageParallelismDialog.ask(project, shared.displayName(),
                untriaged.size(), "GitHub");
        if (asked.isEmpty()) {
            return;
        }
        Resolution resolution = resolve(shared);
        Launcher launcher = launcherFor(mainRoot);
        synchronized (lock) {
            Set<Integer> carried = Set.of();
            if (githubBatch != null) {
                if (!githubBatch.paused) {
                    info("A GitHub batch triage is already running for " + shared.displayName() + ".");
                    return;
                }
                // Replacing a paused batch: its still-running issues stay
                // excluded (in the new batch's identity set) until their
                // sessions end and label them; the paused remainder that the
                // fresh listing still shows unlabeled is re-queued above.
                carried = githubBatch.heldNumbers();
            }
            githubBatch = new GitHubBatch(shared.id(), untriaged, resolution, launcher, asked.get(), carried);
            githubBatch.persistRemainder();
        }
        drain();
    }

    /** Asks the parallelism and, when the user confirms, queues the Jira batch. */
    private void offerJiraBatch(SharedProject shared, Path mainRoot, JiraProjectSettings settings,
                                List<JiraIssue> untriaged) {
        if (untriaged.isEmpty()) {
            info("Every Jira issue of " + shared.displayName() + " is already triaged.");
            return;
        }
        Optional<Integer> asked = TriageParallelismDialog.ask(project, shared.displayName(),
                untriaged.size(), "Jira");
        if (asked.isEmpty()) {
            return;
        }
        Resolution resolution = resolve(shared);
        boolean skillLabels = BatchTriagePlan.mentionsLabeling(
                resolution.promptText().orElseGet(NewTaskPreset::triageSteps));
        Labeler labeler = skillLabels ? issue -> Optional.empty() : new JiraLabeler(settings);
        Launcher launcher = launcherFor(mainRoot);
        boolean alreadyRunning;
        synchronized (lock) {
            alreadyRunning = jiraBatch != null && !jiraBatch.paused;
        }
        if (alreadyRunning) {
            info("A Jira batch triage is already running for " + shared.displayName() + ".");
            return;
        }
        startJiraBatch(shared.id(), untriaged, resolution, launcher, labeler, asked.get());
    }

    /** Starts a Jira batch with injectable dependencies for the queue's platform tests. */
    void startJiraBatch(RepositoryId repoId, List<JiraIssue> issues, Resolution resolution,
                        Launcher launcher, Labeler labeler, int parallelism) {
        synchronized (lock) {
            Set<String> carried = Set.of();
            if (jiraBatch != null) {
                if (!jiraBatch.paused) {
                    return;
                }
                carried = jiraBatch.heldKeys();
            }
            jiraBatch = new JiraBatch(repoId, issues, resolution, launcher, labeler, parallelism, carried);
            jiraBatch.persistRemainder();
        }
        drain();
    }

    /** A lock-protected, immutable view of the active Jira batch's slot and outcome counts. */
    JiraBatchSnapshot jiraSnapshot() {
        synchronized (lock) {
            if (jiraBatch == null) {
                return new JiraBatchSnapshot(0, 0, 0, 0, false);
            }
            return new JiraBatchSnapshot(jiraBatch.pending.size(), jiraBatch.running.size(),
                    jiraBatch.launchFailures, jiraBatch.labelFailures, jiraBatch.paused);
        }
    }

    private Launcher launcherFor(Path mainRoot) {
        return (name, prompt) -> SessionDialogs.launchSession(project, mainRoot, mainRoot,
                defaultAgent(), name, SessionMode.WRITES, Optional.of(prompt),
                Optional.empty(), Optional.empty(), false);
    }

    // ---- pause and resume ---------------------------------------------------------

    /** The action body for the GitHub node's {@code Cancel Triage Batch}; EDT. */
    static void cancelGitHub(Project project, SharedProject shared) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        project.getService(BatchTriageService.class).cancel(GITHUB, shared);
    }

    /** The action body for the Jira node's {@code Cancel Triage Batch}; EDT. */
    static void cancelJira(Project project, SharedProject shared) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        project.getService(BatchTriageService.class).cancel(JIRA, shared);
    }

    /**
     * Pauses {@code system}'s batch: nothing further launches, the
     * never-launched remainder is persisted, and sessions already asked for
     * keep running — they label their own issues when they end, so pausing
     * never wastes or double-counts their work.
     */
    private void cancel(String system, SharedProject shared) {
        synchronized (lock) {
            Batch<?> batch = system.equals(GITHUB) ? githubBatch : jiraBatch;
            if (batch == null || batch.paused) {
                info("No triage batch is running for " + shared.displayName() + ".");
                return;
            }
            batch.pause();
            batch.persistRemainder();
            int left = batch.remainder().size();
            info("Batch triage paused: " + left + " " + system + " "
                    + (left == 1 ? "issue remains" : "issues remain") + " queued. Sessions already running"
                    + " finish on their own; use Resume Triage Batch to continue the rest.");
        }
    }

    /** Whether the {@code system}'s batch is live (queued issues or in-flight sessions). */
    boolean activeGitHub() {
        synchronized (lock) {
            return githubBatch != null && !githubBatch.paused;
        }
    }

    boolean activeJira() {
        synchronized (lock) {
            return jiraBatch != null && !jiraBatch.paused;
        }
    }

    /** Whether a paused {@code system} batch has a stored remainder for {@code id}. */
    static boolean hasPaused(RepositoryId id, String system) {
        return PropertiesComponent.getInstance().getValue(pausedKey(id, system)) != null;
    }

    /**
     * The action body for the GitHub node's {@code Resume Triage Batch…}:
     * loads the stored remainder, drops the issues that are meanwhile labeled
     * or gone, and re-asks the parallelism. Canceling the dialog keeps the
     * pause; resuming replaces the paused batch and persists the new
     * remainder as the queue drains. EDT.
     */
    static void resumeGitHub(Project project, SharedProject shared, Path mainRoot) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        BatchTriageService service = project.getService(BatchTriageService.class);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing batch triage…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    Optional<BatchTriageQueue> paused = paused(shared.id(), GITHUB);
                    if (paused.isEmpty()) {
                        service.info("No paused GitHub triage batch for " + shared.displayName() + ".");
                        return;
                    }
                    GitHubProjectSettings settings = GitHubProjectSettings.load(shared.id());
                    List<GhCliService.OpenIssue> survivors;
                    try (GhCliService github = new GhCliService()) {
                        GhCliService.IssueListing listing = github
                                .openIssues(mainRoot, settings.repository())
                                .get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (indicator.isCanceled()) {
                            return;
                        }
                        survivors = listing instanceof GhCliService.IssueListing.Listed listed
                                ? BatchTriagePlan.resumeSurvivorsGitHub(paused.get().entries(), listed.issues())
                                : List.of();
                        if (listing instanceof GhCliService.IssueListing.Failed failed) {
                            service.info("The GitHub issues of " + shared.displayName()
                                    + " could not be listed: " + failed.message());
                            return;
                        }
                        if (listing instanceof GhCliService.IssueListing.Unsupported) {
                            service.info("No GitHub repository is configured for " + shared.displayName()
                                    + "; set it in the project's repository settings.");
                            return;
                        }
                    }
                    if (indicator.isCanceled()) {
                        return;
                    }
                    if (survivors.isEmpty()) {
                        service.clearPaused(shared.id(), GITHUB);
                        service.info("Nothing left to triage from the paused batch: every issue of it is"
                                + " already triaged or no longer listed.");
                        return;
                    }
                    if (indicator.isCanceled()) {
                        return;
                    }
                    service.offerGitHubBatch(shared, mainRoot, survivors);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.warn("Resuming the GitHub batch triage of " + shared.displayName() + " failed", e);
                    service.info("Resuming the batch triage failed: " + message(e));
                }
            }
        });
    }

    /** The Jira analogue of {@link #resumeGitHub}; every group is fetched fresh. EDT. */
    static void resumeJira(Project project, SharedProject shared, Path mainRoot) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        BatchTriageService service = project.getService(BatchTriageService.class);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing batch triage…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    Optional<BatchTriageQueue> paused = paused(shared.id(), JIRA);
                    if (paused.isEmpty()) {
                        service.info("No paused Jira triage batch for " + shared.displayName() + ".");
                        return;
                    }
                    JiraProjectSettings settings = JiraProjectSettings.load(shared.id());
                    List<JiraGroup> groups = settings.enabled() ? JiraGroups.load(shared.id()).groups() : List.of();
                    if (groups.isEmpty()) {
                        service.info("Jira is not configured for " + shared.displayName() + ".");
                        return;
                    }
                    Map<String, JiraIssue> all = new LinkedHashMap<>();
                    List<JiraListingFailure> failures = new ArrayList<>();
                    try (JiraIssueSource source = new JiraIssueSource()) {
                        for (JiraGroup group : groups) {
                            if (indicator.isCanceled()) {
                                return;
                            }
                            JiraIssueListing listing = source
                                    .issuesOf(mainRoot, settings, group)
                                    .get(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            if (indicator.isCanceled()) {
                                return;
                            }
                            if (listing instanceof JiraIssueListing.Listed listed) {
                                for (JiraIssue issue : listed.issues()) {
                                    all.putIfAbsent(issue.key(), issue);
                                }
                            } else if (listing instanceof JiraIssueListing.Failed failed) {
                                failures.add(new JiraListingFailure(group.name(), failed.message()));
                            }
                        }
                    }
                    JiraListingDecision decision = jiraListingDecision(indicator.isCanceled(), shared.displayName(),
                            "Resume was not performed; the paused queue was kept.", failures);
                    if (decision.canceled()) {
                        return;
                    }
                    if (decision.failureMessage().isPresent()) {
                        service.info(decision.failureMessage().get());
                        return;
                    }
                    if (indicator.isCanceled()) {
                        return;
                    }
                    List<JiraIssue> survivors = BatchTriagePlan.resumeSurvivorsJira(paused.get().entries(),
                            List.copyOf(all.values()));
                    if (indicator.isCanceled()) {
                        return;
                    }
                    if (survivors.isEmpty()) {
                        service.clearPaused(shared.id(), JIRA);
                        service.info("Nothing left to triage from the paused batch: every issue of it is"
                                + " already triaged or no longer listed.");
                        return;
                    }
                    if (indicator.isCanceled()) {
                        return;
                    }
                    service.offerJiraBatch(shared, mainRoot, settings, survivors);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.warn("Resuming the Jira batch triage of " + shared.displayName() + " failed", e);
                    service.info("Resuming the batch triage failed: " + message(e));
                }
            }
        });
    }

    private static String pausedKey(RepositoryId id, String system) {
        return PAUSED_PREFIX + id + "." + system.toLowerCase(java.util.Locale.ROOT);
    }

    private static Optional<BatchTriageQueue> paused(RepositoryId id, String system) {
        return BatchTriageQueue.fromJson(system,
                PropertiesComponent.getInstance().getValue(pausedKey(id, system)));
    }

    /** Stores or clears ({@code queue} empty) a system's paused remainder. */
    private static void storePaused(RepositoryId id, String system, BatchTriageQueue queue) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        Optional<String> json = queue == null ? Optional.empty() : queue.toJson();
        if (json.isPresent()) {
            properties.setValue(pausedKey(id, system), json.get());
        } else {
            properties.unsetValue(pausedKey(id, system));
        }
    }

    private void clearPaused(RepositoryId id, String system) {
        storePaused(id, system, null);
    }

    // ---- the queue ---------------------------------------------------------------

    /**
     * Launches into every free slot of whichever batch runs; ends a finished
     * batch with its summary; drops a paused batch once its last carried
     * session has ended (its persisted remainder stays resumable). Called
     * after queueing, after every launch completion and after every session
     * end; safe from any thread.
     */
    private void drain() {
        if (disposed || !project.isOpen()) {
            return;
        }
        List<CompletableFuture<ManagedAgentSession>> githubStarted = new ArrayList<>();
        List<Reservation> reservations = new ArrayList<>();
        JiraBatch reservationBatch = null;
        Runnable summary = null;
        synchronized (lock) {
            if (githubBatch != null) {
                githubStarted.addAll(githubBatch.fill());
                if (!githubStarted.isEmpty() && !githubBatch.paused) {
                    githubBatch.persistRemainder();
                }
                if (githubBatch.finished()) {
                    GitHubBatch done = githubBatch;
                    githubBatch = null;
                    if (done.paused) {
                        // Everything it carried has ended; the remainder stays
                        // persisted for the Resume action.
                    } else {
                        done.clearPersisted();
                        int total = done.total;
                        int launchFailures = done.launchFailures;
                        int labelFailures = done.labelFailures;
                        summary = () -> summarize(GITHUB, total, launchFailures, labelFailures);
                    }
                }
            }
            if (jiraBatch != null) {
                JiraBatch current = jiraBatch;
                reservationBatch = current;
                reservations.addAll(current.reserve());
                if (!reservations.isEmpty() && !current.paused) {
                    current.persistRemainder();
                }
                if (current.finished()) {
                    JiraBatch done = current;
                    jiraBatch = null;
                    if (done.paused) {
                        // Same as the GitHub pause: the store stays resumable.
                    } else {
                        done.clearPersisted();
                        int total = done.total;
                        int launchFailures = done.launchFailures;
                        int labelFailures = done.labelFailures;
                        summary = () -> summarize(JIRA, total, launchFailures, labelFailures);
                    }
                }
            }
        }
        if (reservationBatch != null) {
            for (Reservation reservation : reservations) {
                dispatchReservation(reservationBatch, reservation);
            }
        }
        if (summary != null) {
            ApplicationManager.getApplication().executeOnPooledThread(summary);
        }
    }

    /** Submits the reserved Jira operation after drain has released the service lock. */
    private void dispatchReservation(JiraBatch batch, Reservation reservation) {
        try {
            ApplicationManager.getApplication().executeOnPooledThread(() -> runReservation(batch, reservation));
        } catch (RuntimeException e) {
            LOG.warn("Batch triage could not schedule a Jira issue for labeling and launch", e);
            reservation.placeholder().completeExceptionally(e);
        }
    }

    /** Runs one reserved Jira label then launch operation without holding the service lock. */
    private void runReservation(JiraBatch batch, Reservation reservation) {
        JiraIssue issue = reservation.issue();
        try {
            try {
                Optional<String> problem = batch.labeler.addLabel(issue);
                if (problem.isPresent()) {
                    LOG.warn("Batch triage could not label " + issue.key() + ": " + problem.get());
                    synchronized (lock) {
                        batch.labelFailures++;
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Batch triage could not label " + issue.key(), e);
                synchronized (lock) {
                    batch.labelFailures++;
                }
            } finally {
                // JiraIssueWriter marks its calling thread interrupted after an uncertain write.
                Thread.interrupted();
            }
            CompletableFuture<ManagedAgentSession> launch = batch.launcher.launch(
                    batch.displayName(issue), batch.prompt(issue));
            launch.whenComplete((session, failure) -> {
                if (failure != null) {
                    reservation.placeholder().completeExceptionally(failure);
                } else {
                    reservation.placeholder().complete(session);
                }
            });
        } catch (Throwable failure) {
            reservation.placeholder().completeExceptionally(failure);
        }
    }

    /**
     * One system's queue. Slot accounting is future-based: a launch that has
     * been asked for holds its slot until the launch chain completes (a
     * failure frees it and counts it) or the session is RUNNING (the slot
     * moves to {@link #running} until the process ends). Every queue mutation
     * and read happens under the owning service's lock; inner (non-static)
     * because the slot hand-off and the drain call reach that lock.
     *
     * <p>{@code launched} never shrinks: it is every issue this batch has
     * sent to a session, kept so a paused batch's still-running issues can be
     * carried into a resumed batch's exclusion set. The remainder, in
     * contrast, is what was never launched — the part a pause persists.</p>
     */
    private abstract class Batch<I> {
        final RepositoryId repoId;
        final String system;
        final Deque<I> queue;
        final Resolution resolution;
        final Launcher launcher;
        final int parallelism;
        final int total;
        final Set<ManagedSessionId> running = new HashSet<>();
        final Set<CompletableFuture<ManagedAgentSession>> pending = new HashSet<>();
        boolean paused;
        int launchFailures;
        int labelFailures;

        Batch(RepositoryId repoId, String system, List<I> issues, Resolution resolution, Launcher launcher,
              int parallelism) {
            this.repoId = repoId;
            this.system = system;
            this.queue = new ArrayDeque<>(issues);
            this.resolution = resolution;
            this.launcher = launcher;
            this.parallelism = Math.max(1, parallelism);
            this.total = issues.size();
        }

        /** Launches into every free slot. Caller holds the lock. A paused batch launches nothing. */
        List<CompletableFuture<ManagedAgentSession>> fill() {
            List<CompletableFuture<ManagedAgentSession>> started = new ArrayList<>();
            while (!paused && running.size() + pending.size() < parallelism && !queue.isEmpty()) {
                started.add(launch(queue.poll()));
            }
            return started;
        }

        /** Starts one issue's session; the launch future holds its slot until it settles. */
        CompletableFuture<ManagedAgentSession> launch(I issue) {
            CompletableFuture<ManagedAgentSession> launch;
            try {
                launch = launcher.launch(displayName(issue), prompt(issue));
            } catch (RuntimeException e) {
                LOG.warn("Batch triage could not launch a session for " + displayName(issue), e);
                launchFailures++;
                return CompletableFuture.failedFuture(e);
            }
            track(launch);
            return launch;
        }

        /** Registers a future as a reserved slot and performs its locked completion hand-off. */
        void track(CompletableFuture<ManagedAgentSession> launch) {
            pending.add(launch);
            launch.whenComplete((session, failure) -> {
                boolean slotFreed;
                synchronized (lock) {
                    pending.remove(launch);
                    if (failure != null) {
                        launchFailures++;
                        slotFreed = true;
                    } else {
                        running.add(session.id());
                        slotFreed = false;
                    }
                }
                if (slotFreed) {
                    drain();
                }
            });
        }

        /** Records a session's end; true when it belonged to this batch and its slot freed. */
        boolean ended(ManagedSessionId sessionId) {
            return running.remove(sessionId);
        }

        boolean finished() {
            return queue.isEmpty() && pending.isEmpty() && running.isEmpty();
        }

        /** Marks the batch paused: nothing further launches; running sessions finish on their own. */
        void pause() {
            paused = true;
        }

        List<I> remainder() {
            return new ArrayList<>(queue);
        }

        /** Persists the never-launched remainder, so the batch can be resumed later or after a quit. */
        void persistRemainder() {
            storePaused(repoId, system, new BatchTriageQueue(system, entriesOf(remainder())));
        }

        /** Drops the persisted remainder: the batch finished, or its survivors were all gone. */
        void clearPersisted() {
            storePaused(repoId, system, null);
        }

        /** The queue entries for {@code issues}, as the pause store serializes them. */
        abstract List<BatchTriageQueue.Entry> entriesOf(List<I> issues);

        abstract String displayName(I issue);

        abstract String prompt(I issue);
    }

    private final class GitHubBatch extends Batch<GhCliService.OpenIssue> {
        private final Set<Integer> held = new HashSet<>();

        GitHubBatch(RepositoryId repoId, List<GhCliService.OpenIssue> issues, Resolution resolution,
                    Launcher launcher, int parallelism, Set<Integer> carriedInFlight) {
            super(repoId, GITHUB, issues, resolution, launcher, parallelism);
            for (GhCliService.OpenIssue issue : issues) {
                held.add(issue.number());
            }
            held.addAll(carriedInFlight);
        }

        /** Every issue this batch owns, queued, launched, or carried: the in-flight filter's identity set. */
        Set<Integer> heldNumbers() {
            return Set.copyOf(held);
        }

        @Override
        List<BatchTriageQueue.Entry> entriesOf(List<GhCliService.OpenIssue> issues) {
            return issues.stream()
                    .map(issue -> new BatchTriageQueue.Entry(String.valueOf(issue.number()), issue.title(),
                            issue.url().orElse("")))
                    .toList();
        }

        @Override
        String displayName(GhCliService.OpenIssue issue) {
            return BatchTriagePlan.sessionName(issue);
        }

        @Override
        String prompt(GhCliService.OpenIssue issue) {
            String methodology = resolution.promptText().orElseGet(NewTaskPreset::triageSteps).strip();
            if (!BatchTriagePlan.mentionsLabeling(methodology)) {
                methodology = methodology + BatchTriagePlan.githubLabelingStep();
            }
            return BatchTriageService.prompt(NewTaskPreset.triageIdentity(issue), methodology);
        }
    }

    /** A Jira issue reserved under the lock for label-then-launch work outside it. */
    private record Reservation(JiraIssue issue, CompletableFuture<ManagedAgentSession> placeholder) {
    }

    /** The Jira queue: the plugin labels each issue just before its session launches. */
    private final class JiraBatch extends Batch<JiraIssue> {
        private final Labeler labeler;
        private final Set<String> held = new HashSet<>();

        JiraBatch(RepositoryId repoId, List<JiraIssue> issues, Resolution resolution, Launcher launcher,
                  Labeler labeler, int parallelism, Set<String> carriedInFlight) {
            super(repoId, JIRA, issues, resolution, launcher, parallelism);
            this.labeler = labeler;
            for (JiraIssue issue : issues) {
                held.add(issue.key());
            }
            held.addAll(carriedInFlight);
        }

        Set<String> heldKeys() {
            return Set.copyOf(held);
        }

        /** Reserves queue entries and their slots. Caller holds the service lock. */
        List<Reservation> reserve() {
            List<Reservation> reservations = new ArrayList<>();
            while (!paused && running.size() + pending.size() < parallelism && !queue.isEmpty()) {
                JiraIssue issue = queue.poll();
                CompletableFuture<ManagedAgentSession> placeholder = new CompletableFuture<>();
                track(placeholder);
                reservations.add(new Reservation(issue, placeholder));
            }
            return reservations;
        }

        @Override
        List<BatchTriageQueue.Entry> entriesOf(List<JiraIssue> issues) {
            return issues.stream()
                    .map(issue -> new BatchTriageQueue.Entry(issue.key(), issue.summary(), issue.url()))
                    .toList();
        }

        @Override
        String displayName(JiraIssue issue) {
            return BatchTriagePlan.sessionName(issue);
        }

        @Override
        String prompt(JiraIssue issue) {
            return BatchTriageService.prompt(NewTaskPreset.triageIdentity(issue),
                    resolution.promptText().orElseGet(NewTaskPreset::triageSteps).strip());
        }
    }

    // ---- helpers ------------------------------------------------------------------

    /** The framing, the separator, then the issue's identity and the triage methodology. */
    static String prompt(String identity, String methodology) {
        return NewTaskPlan.clarifyFirst() + NewTaskPlan.titleGuidance()
                + CreateContext.TASK_SEPARATOR + "\n"
                + identity + "\n\n" + methodology;
    }

    /** Resolves the registered {@code actions.issue.triage} workflow; blocking, off the EDT. */
    private Resolution resolve(SharedProject shared) {
        WorkflowResolution resolution = new ProjectWorkflowResolver().resolve(shared, WorkflowTarget.ISSUE_TRIAGE,
                Cancellation.none());
        if (resolution instanceof WorkflowResolution.Selected selected
                && !selected.workflow().promptText().isBlank()) {
            String text = selected.workflow().promptText();
            return () -> Optional.of(text);
        }
        if (resolution instanceof WorkflowResolution.Invalid invalid) {
            LOG.warn("The registered triage workflow of " + shared.displayName() + " is invalid ("
                    + invalid.problem().message() + "); the built-in triage steps apply");
        }
        return Optional::empty;
    }

    private AgentKind defaultAgent() {
        return DrydockPluginService.getInstance().registry().resolveDefault(Optional.empty())
                .orElseThrow(() -> new IllegalStateException("no agent CLI is available"));
    }

    /**
     * The GitHub issue numbers a batch holds that a fresh {@code Triage
     * Untriaged} must not re-queue: everything of a live batch, but only the
     * launched-and-maybe-unlabeled issues of a paused one — its remainder is
     * deliberately re-queueable, because a fresh batch replaces the pause and
     * takes the remainder over.
     */
    private Set<Integer> githubInFlight() {
        synchronized (lock) {
            if (githubBatch == null) {
                return Set.of();
            }
            Set<Integer> inFlight = new HashSet<>(githubBatch.heldNumbers());
            if (githubBatch.paused) {
                for (GhCliService.OpenIssue issue : githubBatch.remainder()) {
                    inFlight.remove(issue.number());
                }
            }
            return Set.copyOf(inFlight);
        }
    }

    /** The Jira analogue of {@link #githubInFlight()}, keyed by issue key. */
    private Set<String> jiraInFlight() {
        synchronized (lock) {
            if (jiraBatch == null) {
                return Set.of();
            }
            Set<String> inFlight = new HashSet<>(jiraBatch.heldKeys());
            if (jiraBatch.paused) {
                for (JiraIssue issue : jiraBatch.remainder()) {
                    inFlight.remove(issue.key());
                }
            }
            return Set.copyOf(inFlight);
        }
    }

    /** The plugin-side Jira labeling: the project's stored connection and secret, one bounded write. */
    final class JiraLabeler implements Labeler {
        private final Optional<JiraConnection> connection;
        private final Optional<String> secret;
        private final LabelWriterFactory writerFactory;

        /** Built on the batch-setup background thread; the secret read asserts it is off the EDT. */
        JiraLabeler(JiraProjectSettings settings) {
            this.connection = settings.connection("");
            this.secret = connection.isPresent() ? JiraCredentials.read(settings) : Optional.empty();
            this.writerFactory = BatchTriageService::newJiraLabelWriter;
        }

        /** Injectable resource ownership seam for the platform tests. */
        JiraLabeler(Optional<JiraConnection> connection, Optional<String> secret, LabelWriterFactory writerFactory) {
            this.connection = connection;
            this.secret = secret;
            this.writerFactory = writerFactory;
        }

        @Override
        public Optional<String> addLabel(JiraIssue issue) {
            if (connection.isEmpty()) {
                return Optional.of("Jira is disabled for this project");
            }
            if (secret.isEmpty()) {
                return Optional.of("No Jira API token is stored; open Jira Settings");
            }
            try (LabelWriter writer = writerFactory.create()) {
                return switch (writer.addLabel(connection.get(), secret.get(), issue.key(),
                        BatchTriagePlan.TRIAGED_LABEL)) {
                    case JiraIssueWriter.Labeled labeled -> Optional.empty();
                    case JiraIssueWriter.LabelFailed failed -> Optional.of(failed.message());
                    case JiraIssueWriter.LabelUncertain uncertain -> Optional.of(uncertain.message());
                };
            }
        }
    }

    private static LabelWriter newJiraLabelWriter() {
        JiraIssueWriter writer = new JiraIssueWriter(JiraIssueSource::ideClientBuilder);
        return new LabelWriter() {
            @Override
            public JiraIssueWriter.LabelOutcome addLabel(JiraConnection connection, String secret, String issueKey,
                                                          String label) {
                return writer.addLabel(connection, secret, issueKey, label);
            }

            @Override
            public void close() {
                writer.close();
            }
        };
    }

    private void summarize(String system, int total, int launchFailures, int labelFailures) {
        String text = summaryText(total, launchFailures, labelFailures);
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Batch triage finished (" + system + ")", text, NotificationType.INFORMATION)
                .notify(project);
    }

    /** Pure summary wording; label failures do not reduce the count of sessions that ran. */
    static String summaryText(int total, int launchFailures, int labelFailures) {
        if (launchFailures == 0 && labelFailures == 0) {
            return "All " + total + " triage sessions were queued and have ended.";
        }
        StringBuilder text = new StringBuilder(total - launchFailures).append(" of ").append(total)
                .append(" triage sessions ran");
        if (launchFailures > 0) {
            text.append("; ").append(launchFailures).append(" could not be launched");
        }
        if (labelFailures > 0) {
            text.append("; ").append(labelFailures).append(" could not be labeled");
        }
        return text.append(" — see the log.").toString();
    }

    /** An informational dialog, hoppy: called from any thread, always shown on the EDT. */
    private void info(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                Messages.showInfoMessage(project, text, "Triage Untriaged");
            }
        });
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.toString() : text;
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}
