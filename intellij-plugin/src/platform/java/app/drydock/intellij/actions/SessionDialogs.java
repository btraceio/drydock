package app.drydock.intellij.actions;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ConversationSource;
import app.drydock.domain.ApplicationState;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.git.BranchListing;
import app.drydock.git.BranchRef;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.worktree.WorktreeDialogs;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.project.ProjectIdentity;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.ScrollPaneFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The Swing bodies of the two session dialogs (T13): the new-session picker
 * (agent combo + name) and the resume-conversation picker (title / message
 * count / last-modified, newest first). The actions in this package are
 * thin: they forward to {@link #showNewSession} / {@link
 * #showResumeConversation}.
 *
 * <p>Threading (AC7/AC12): every blocking input — the plugin init, the
 * project's repository, the conversation scan ({@code
 * ConversationSource.listConversations} reads the agent's on-disk
 * transcripts), the session launch itself — runs inside a {@link
 * Task.Backgroundable} on a background thread, shown the moment the user
 * clicks and cleared on success, failure, and every early return (the
 * startup activity's bounded-wait pattern). Swing is touched only on the
 * EDT; the hops go through {@code invokeLater(..., ModalityState.any())}
 * so a completion while a dialog is showing still lands.</p>
 *
 * <p>Pure seams: the default-agent resolution (AC8), the provider choice
 * for the resume action (AC22), the newest-first ordering (AC20), and the
 * row texts (AC6) are static helpers with no Swing or IDE dependency,
 * pinned by {@code SessionDialogsTest} without opening a dialog.</p>
 */
public final class SessionDialogs {

    private static final Logger LOG = System.getLogger(SessionDialogs.class.getName());

    /** Bounded waits for preparation (mirrors the startup activity's pre-warm timeout). */
    private static final long PREPARE_TIMEOUT_SECONDS = 30;

    /** Bounded wait for a launch: tab creation plus command build, generously. */
    private static final long LAUNCH_TIMEOUT_SECONDS = 60;

    /**
     * Bounded wait for `git worktree add`. Generous next to the others: on a
     * repository with submodules the checkout is the slow part, and timing it
     * out would leave a half-made worktree nobody asked about.
     */
    private static final long WORKTREE_TIMEOUT_SECONDS = 180;

    /** UTC, so the rendered time is deterministic across machines (and testable). */
    private static final DateTimeFormatter LAST_MODIFIED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private SessionDialogs() { }

    // ---- pure seams (no Swing, no IDE) ----------------------------------------

    /**
     * The pre-selected agent for a new session (AC8): the repository's
     * last-used agent when it is still available, else the first available
     * kind in {@link AgentKind#preferenceOrder()} (unchanged by Q3), else
     * empty (no agent CLI at all).
     */
    static Optional<AgentKind> defaultAgent(AgentRegistry registry, Optional<AgentKind> repoLastUsed) {
        return registry.resolveDefault(repoLastUsed);
    }

    /**
     * The provider whose conversations the resume action lists (AC20 "for
     * the chosen provider"): the repository's last-used agent when it
     * exposes a {@link ConversationSource}, else the first kind in
     * {@link AgentKind#preferenceOrder()} that does. Empty means no
     * registered provider can list conversations, and the resume action
     * stays disabled rather than erroring (AC22).
     */
    static Optional<AgentKind> chosenConversationKind(AgentRegistry registry, Optional<AgentKind> repoLastUsed) {
        if (repoLastUsed.isPresent() && registry.conversations(repoLastUsed.get()).isPresent()) {
            return repoLastUsed;
        }
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            if (registry.conversations(kind).isPresent()) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * The conversations newest first (AC20): ordered by {@code lastModified}
     * descending, {@code sessionId} breaking ties so the order is total and
     * a test can pin it. Returns a copy; the given list is untouched.
     */
    static List<ConversationSource.Conversation> newestFirst(List<ConversationSource.Conversation> conversations) {
        return conversations.stream()
                .sorted(Comparator.comparing(ConversationSource.Conversation::lastModified)
                        .thenComparing(ConversationSource.Conversation::sessionId)
                        .reversed())
                .collect(Collectors.toList());
    }

    /**
     * One combo row's text (AC6 UI half): the agent's display name, with
     * the "where we looked" text appended to an unavailable provider so the
     * row explains itself instead of failing on OK. Same vocabulary as the
     * app tree's agent picker ("not found. Searched: …").
     */
    static String agentRowText(Agent agent) {
        return agent.isAvailable()
                ? agent.displayName()
                : agent.displayName() + " — not found. Searched: " + agent.describeSearched();
    }

    /**
     * One picker row's text (AC20): the conversation's title, message
     * count, and last-modified time.
     */
    static String conversationRowText(ConversationSource.Conversation conversation) {
        int count = conversation.messageCount();
        return conversation.title()
                + " · " + count + (count == 1 ? " message" : " messages")
                + " · " + LAST_MODIFIED_FORMAT.format(conversation.lastModified());
    }

    /**
     * The default suggestion for the new session's name, mirroring the app
     * tree's {@code SessionManager.defaultDisplayName}: "Session N+1" where
     * N is the number of sessions already recorded for the repository.
     */
    static String suggestedSessionName(long existingSessionsInRepository) {
        return "Session " + (existingSessionsInRepository + 1);
    }

    /** Sessions of {@code repositoryId} in {@code state} — the count behind {@link #suggestedSessionName}. */
    static long sessionCount(ApplicationState state, RepositoryId repositoryId) {
        return state.sessions().stream()
                .filter(session -> session.repositoryId().equals(repositoryId))
                .count();
    }

    /**
     * The default suggestion for a new session rooted at {@code
     * workingDirectory} (T17): the directory's own name — its last path
     * segment, e.g. "address-pr-800" — when that is a worktree rather than
     * the project itself, so a session opened from a worktree row names
     * itself after the worktree; the project-root case keeps the {@code
     * "Session N+1"} numbering of {@link #suggestedSessionName}. The two
     * roots are compared as {@link WorktreeService#canonical} real paths
     * (the toRealPath discipline of the panel's worktree rows): a root
     * spelled through a symlink must still count as the project root, not
     * as a worktree named after some path segment.
     */
    static String suggestedSessionNameFor(Path projectRoot, Path workingDirectory,
            long existingSessionsInRepository) {
        if (workingDirectory.getFileName() != null
                && !WorktreeService.canonical(projectRoot).equals(WorktreeService.canonical(workingDirectory))) {
            return workingDirectory.getFileName().toString();
        }
        return suggestedSessionName(existingSessionsInRepository);
    }

    // ---- new session ----------------------------------------------------------

    /**
     * Opens the new-session dialog. Immediately visible progress (AC12):
     * the dialog's inputs (registry, repository, suggested name) are
     * prepared off the EDT inside a background task; the dialog opens on
     * the EDT once they are ready, and every early return — timeout, init
     * failure, non-directory project — ends the task, which clears it.
     */
    public static void showNewSession(Project project) {
        if (project.getBasePath() == null) {
            Messages.showInfoMessage(project,
                    "This project has no directory, so Drydock cannot start a session in it.",
                    "Drydock");
            return;
        }
        showNewSession(project, Path.of(project.getBasePath()));
    }

    /**
     * Opens the new-session dialog for a session rooted at
     * {@code workingDirectory} — the open project, or a worktree listed in
     * the Drydock panel. The path decides everything the session is keyed
     * on: the repository it registers under (its own canonical root, R7)
     * and the terminal tab's working directory.
     */
    public static void showNewSession(Project project, Path workingDirectory) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing new Drydock session…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                NewSessionInput input = prepareNewSession(project, plugin, workingDirectory);
                if (input != null) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> openNewSessionDialog(project, input, workingDirectory), ModalityState.any());
                }
            }
        });
    }

    /**
     * Blocking, bounded preparation of the dialog's inputs on the calling
     * background thread. Returns null (after logging, and the task's end
     * clears the progress) when the plugin init or the repository
     * resolution failed or timed out — the early returns of AC12.
     */
    @Nullable
    private static NewSessionInput prepareNewSession(Project project, DrydockPluginService plugin,
            Path workingDirectory) {
        try {
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Repository repository = DrydockStartupActivity.repositories(project)
                    .repositoryFor(workingDirectory).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (repository == null) {
                LOG.log(Level.WARNING,
                        "Project {0} has no Drydock repository; cannot offer a new session", project.getName());
                return null;
            }
            AgentRegistry registry = plugin.registry();
            Optional<AgentKind> defaultKind = defaultAgent(registry, repository.settings().lastUsedAgent());
            // A session opened from a worktree row names itself after the
            // worktree (T17); only the project-root case keeps the
            // numbering. The null guard is belt-and-braces: the project-root
            // overload already refused a directory-less project, and the
            // panel only calls this with a directory it resolved a
            // repository in.
            Path projectRoot = project.getBasePath() != null ? Path.of(project.getBasePath()) : workingDirectory;
            String suggestedName = suggestedSessionNameFor(projectRoot, workingDirectory,
                    sessionCount(plugin.stateStore().state(), repository.id()));
            return new NewSessionInput(registry.agents(), defaultKind, suggestedName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "Could not prepare the new-session dialog: " + e);
            ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
                    "Drydock did not finish loading: " + e, "Drydock"), ModalityState.any());
            return null;
        }
    }

    private record NewSessionInput(List<Agent> agents, Optional<AgentKind> defaultKind, String suggestedName) {
    }

    private static void openNewSessionDialog(Project project, NewSessionInput input, Path workingDirectory) {
        if (input.agents().stream().noneMatch(Agent::isAvailable)) {
            // Mirrors the app tree's "No agent CLI found" alert: with no
            // agent at all (not even a shell) there is nothing to pick.
            String searched = input.agents().stream()
                    .map(agent -> agent.displayName() + ": " + agent.describeSearched())
                    .collect(Collectors.joining("; "));
            Messages.showWarningDialog(project, "No agent CLI found. Searched: " + searched, "Drydock");
            return;
        }
        new NewSessionDialog(project, input, workingDirectory).show();
    }

    /** The new-session dialog: agent combo (availability in every row, AC6) + name field. */
    private static final class NewSessionDialog extends DialogWrapper {

        private final Project project;
        private final Path workingDirectory;
        private final JComboBox<Agent> agentCombo;
        private final JTextField nameField;

        NewSessionDialog(Project project, NewSessionInput input, Path workingDirectory) {
            super(project);
            this.project = project;
            this.workingDirectory = workingDirectory;
            setTitle("New Drydock session");
            agentCombo = new JComboBox<>(input.agents().toArray(Agent[]::new));
            agentCombo.setRenderer(new AgentRowRenderer());
            input.defaultKind().ifPresent(kind -> {
                for (int i = 0; i < agentCombo.getItemCount(); i++) {
                    if (agentCombo.getItemAt(i).kind() == kind) {
                        agentCombo.setSelectedIndex(i);
                        break;
                    }
                }
            });
            if (agentCombo.getSelectedIndex() < 0) {
                agentCombo.setSelectedIndex(0);
            }
            nameField = new JTextField(input.suggestedName());
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
            form.add(new JLabel("Agent"));
            form.add(agentCombo);
            form.add(new JLabel("Session name"));
            form.add(nameField);
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.add(form, BorderLayout.NORTH);
            panel.setPreferredSize(new Dimension(360, panel.getPreferredSize().height));
            return panel;
        }

        /**
         * An unavailable agent is refused here with its "where we looked"
         * text (AC6/AC11): the row itself shows the same words, so the
         * refusal never names a reason the dialog has not already shown.
         */
        @Override
        protected @Nullable ValidationInfo doValidate() {
            Agent selected = (Agent) agentCombo.getSelectedItem();
            if (selected == null) {
                return new ValidationInfo("Choose an agent", agentCombo);
            }
            if (!selected.isAvailable()) {
                return new ValidationInfo(selected.displayName() + " was not found. Searched: "
                        + selected.describeSearched(), agentCombo);
            }
            if (nameField.getText().trim().isEmpty()) {
                return new ValidationInfo("Session name cannot be empty", nameField);
            }
            return null;
        }

        @Override
        protected void doOKAction() {
            Agent selected = (Agent) agentCombo.getSelectedItem();
            String displayName = nameField.getText().trim();
            if (selected == null || !selected.isAvailable() || displayName.isEmpty()) {
                return; // doValidate explains; never close on invalid input
            }
            super.doOKAction();
            launchNewSession(project, workingDirectory, selected.kind(), displayName);
        }
    }

    /**
     * Launches a prepared new session with visible progress (AC12) and the
     * prepare/launch split of {@code DrydockSessionService}: the future does
     * the work on the plugin's executor, this background task only waits —
     * bounded — and every path (success, failure, timeout, interrupt) ends
     * it, which clears the progress. A failure surfaces an error naming the
     * agent (AC11 UI half).
     *
     * <p>The console tab is opened between prepare and launch, as soon as the
     * session has an id. It is a separate step on purpose — showing a console
     * never starts an agent anywhere in this plugin — but it has to happen,
     * and happen first: without it the agent spawns with no tab, which looks
     * exactly like the dialog doing nothing at all.</p>
     */
    private static void launchNewSession(Project project, Path workingDirectory, AgentKind kind,
            String displayName) {
        launchNewSession(project, workingDirectory, kind, displayName, SessionMode.WRITES, Optional.empty(), false);
    }

    /**
     * @param freshWindow whether {@code project} is a window Drydock has just
     *                    opened for this session, where the IDE's
     *                    README-on-first-open is still to come
     */
    private static void launchNewSession(Project project, Path workingDirectory, AgentKind kind,
            String displayName, SessionMode mode, Optional<String> task, boolean freshWindow) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        String agentName = AgentLabels.displayName(plugin.registry(), kind);
        CompletableFuture<ManagedAgentSession> launch =
                DrydockStartupActivity.sessions(project).prepareSession(workingDirectory, kind, displayName, mode)
                        .thenApply(session -> {
                            showConsoleTab(project, session.id(), freshWindow);
                            return session;
                        })
                        .thenCompose(session -> DrydockStartupActivity.sessions(project).launchSession(session, task));
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Starting " + agentName + " session…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    ManagedAgentSession started = launch.get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    // The tab opened before the terminal existed; now that
                    // it does, put the agent in front and the keyboard in it.
                    AgentConsoles.focusConsoleLater(project, started.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    reportLaunchFailure(project, "start " + agentName + " session", e);
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING,
                            "Session start did not complete within {0}s", LAUNCH_TIMEOUT_SECONDS);
                }
            }
        });
    }

    /**
     * Opens the session's console tab on the EDT, without waiting for it: the
     * editor renders its Not-running state immediately and swaps to the live
     * console when the execution starts, which it learns from
     * {@code AgentExecutionListener}. Blocking the launch on the tab would put
     * an EDT round-trip in front of the spawn for no gain.
     */
    private static void showConsoleTab(Project project, ManagedSessionId sessionId, boolean freshWindow) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (freshWindow) {
                AgentConsoles.keepInFrontOfReadme(project, sessionId);
            }
            AgentConsoles.show(project, sessionId);
        }, ModalityState.any(), project.getDisposed());
    }

    // ---- new agent session (the handoff's dialog) -------------------------------

    /**
     * Opens the New Agent Session dialog for {@code project}'s own worktree.
     *
     * <p>Its inputs need git (current branch, default branch) and the catalog
     * (is a writer already live here), so they are resolved inside a
     * background task and the dialog opens on the EDT once they are ready.
     * Every early return ends that task, which clears its progress.</p>
     */
    public static void showNewAgentSession(Project project) {
        if (project.getBasePath() == null) {
            Messages.showInfoMessage(project,
                    "This project has no directory, so Drydock cannot start a session in it.", "Drydock");
            return;
        }
        Path workingDirectory = Path.of(project.getBasePath());
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing new Drydock session…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                NewAgentSessionDialog.Inputs inputs = prepareAgentSession(project, plugin, workingDirectory);
                if (inputs == null) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    NewAgentSessionDialog dialog = new NewAgentSessionDialog(project, inputs);
                    if (dialog.showAndGet()) {
                        startChosenSession(project, inputs, dialog.choice());
                    }
                }, ModalityState.any());
            }
        });
    }

    /** Blocking, bounded; null (after logging) on any failure, so the task always ends. */
    @Nullable
    private static NewAgentSessionDialog.Inputs prepareAgentSession(Project project, DrydockPluginService plugin,
            Path workingDirectory) {
        try (GitStatusService git = new GitStatusService()) {
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Repository repository = DrydockStartupActivity.repositories(project)
                    .repositoryFor(workingDirectory).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (repository == null) {
                LOG.log(Level.WARNING, "Project {0} has no Drydock repository; cannot offer a new session",
                        project.getName());
                return null;
            }
            String current = git.currentBranch(workingDirectory)
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse("detached HEAD");
            // The base for a new worktree is the repository's DEFAULT branch,
            // not whatever happens to be checked out here: a session branched
            // off another session's branch inherits work nobody asked for.
            String base = git.defaultBranch(repository.root())
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse("main");
            BranchListing branches = git.listBranches(repository.root())
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> remotes = branches.remotes();
            // Local names only: a new worktree's branch is local, and a remote
            // branch of the same name is not a collision git refuses.
            Set<String> localBranches = branches.branches().stream()
                    .filter(ref -> !ref.remote())
                    .map(BranchRef::name)
                    .collect(Collectors.toUnmodifiableSet());
            AgentRegistry registry = plugin.registry();
            AgentKind preselected = defaultAgent(registry, repository.settings().lastUsedAgent()).orElse(null);
            boolean writerActive = DrydockStartupActivity.sessions(project).hasActiveWriterIn(workingDirectory);
            // The main checkout's name, not this window's directory: a worktree
            // registers as its own repository (R7), so displayName() said
            // "Here — dd-scratch-add-a-farewell / add-a-farewell".
            String repositoryName = ProjectIdentity.resolved(project)
                    .map(ProjectIdentity::repository)
                    .orElse(repository.displayName());
            return new NewAgentSessionDialog.Inputs(workingDirectory, repositoryName, current, base,
                    registry.agents(), preselected, writerActive, remotes, localBranches);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not prepare the new-session dialog: " + e);
            return null;
        }
    }

    /**
     * Acts on the dialog's choice: start here, or create a worktree, open it
     * as its own window, and start there.
     */
    private static void startChosenSession(Project project, NewAgentSessionDialog.Inputs inputs,
            NewAgentSessionDialog.Choice choice) {
        String title = NewAgentSessionPlan.sessionTitle(choice.task());
        if (choice.placement() == NewAgentSessionPlan.Placement.HERE) {
            launchNewSession(project, inputs.workingDirectory(), choice.agent(), title, choice.mode(),
                    Optional.of(choice.task()), false);
            return;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Creating worktree " + choice.branch() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path worktree;
                try (GitStatusService git = new GitStatusService()) {
                    Path repositoryRoot = DrydockStartupActivity.repositories(project)
                            .repositoryFor(inputs.workingDirectory())
                            .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).root();
                    Path directory = WorktreeNaming.defaultDirectory(
                            Path.of(System.getProperty("user.home", "")),
                            UserConfig.load().worktreesDirectory(),
                            inputs.repositoryName(), choice.branch());
                    worktree = git.createWorktree(repositoryRoot, directory, choice.branch(),
                            Optional.of(inputs.defaultBranch())).get(WORKTREE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    reportLaunchFailure(project, "create the worktree for " + choice.branch(), e);
                    return;
                }
                // Opening a project wraps itself in modal progress when called
                // from the EDT, which is the sanctioned path; the agent then
                // starts in the NEW window, not this one.
                ApplicationManager.getApplication().invokeLater(() -> {
                    Project opened = WorktreeDialogs.openAsProject(worktree);
                    if (opened == null) {
                        LOG.log(Level.WARNING, "Worktree {0} was created but its project did not open", worktree);
                        return;
                    }
                    // Immediately, without waiting for indexing: the strip
                    // says "IDE still indexing" while the project is dumb,
                    // and an agent that only appeared after a large import
                    // would be useless.
                    launchNewSession(opened, worktree, choice.agent(), title, choice.mode(),
                            Optional.of(choice.task()), true);
                    // Non-modal, not any(): opening a project is a model
                    // change, allowed only from a write-safe context. The
                    // dialog has closed by now, so nothing modal holds this
                    // back.
                }, ModalityState.nonModal());
            }
        });
    }

    // ---- resume conversation --------------------------------------------------

    /**
     * Whether the resume action can do anything: a repository must be
     * resolved (non-git/no-directory projects cannot record a session) and
     * {@link #chosenConversationKind} must find a provider with a
     * conversation source — otherwise the action is disabled, never an
     * error (AC22). Cheap by construction: only in-memory reads after the
     * warm-up, safe from {@code update(AnActionEvent)} on the EDT.
     */
    static boolean resumeAvailable(Project project) {
        if (project.getBasePath() == null) {
            return false;
        }
        try {
            DrydockPluginService plugin = DrydockPluginService.getInstance();
            if (!plugin.initialization().isDone()) {
                return false;
            }
            CompletableFuture<Void> warmup = DrydockStartupActivity.warmup(project);
            if (!warmup.isDone() || warmup.isCompletedExceptionally()) {
                return false;
            }
            Repository repository = DrydockStartupActivity.repositories(project)
                    .projectRepository(project).getNow(null);
            Optional<AgentKind> lastUsed = repository != null
                    ? repository.settings().lastUsedAgent()
                    : Optional.empty();
            return chosenConversationKind(plugin.registry(), lastUsed).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Opens the resume-conversation picker. Progress is visible from the
     * click (AC12): the conversation scan — {@code listConversations} reads
     * the agent's on-disk transcripts — runs off the EDT inside the
     * background task (AC20), and the picker opens on the EDT with the
     * result, newest first.
     */
    public static void showResumeConversation(Project project) {
        if (project.getBasePath() == null) {
            Messages.showInfoMessage(project,
                    "This project has no directory, so there is nothing to resume here.",
                    "Drydock");
            return;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Listing conversations…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                prepareResume(project, plugin);
            }
        });
    }

    /**
     * Blocking, bounded resume preparation on the calling background
     * thread; the task's end clears the progress on every path.
     */
    private static void prepareResume(Project project, DrydockPluginService plugin) {
        Repository repository;
        try {
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            repository = DrydockStartupActivity.repositories(project)
                    .projectRepository(project).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException e) {
            reportFailureOnEdt(project, "Drydock did not finish loading", e);
            return;
        }
        if (repository == null) {
            LOG.log(Level.WARNING,
                    "Project {0} has no Drydock repository; cannot resume a conversation", project.getName());
            return;
        }
        AgentRegistry registry = plugin.registry();
        Optional<AgentKind> kind = chosenConversationKind(registry, repository.settings().lastUsedAgent());
        if (kind.isEmpty()) {
            // AC22: no provider can list conversations — disabled in update();
            // reaching here means it changed since. Informational, not an error.
            ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(project,
                    "No agent here can list conversations to resume.", "Drydock"), ModalityState.any());
            return;
        }
        Optional<ConversationSource> source = registry.conversations(kind.get());
        if (source.isEmpty()) {
            return; // chosenConversationKind guarantees presence; belt and braces
        }
        String agentName = AgentLabels.displayName(registry, kind.get());
        List<ConversationSource.Conversation> conversations;
        try {
            conversations = newestFirst(source.get().listConversations(repository.root()));
        } catch (RuntimeException e) {
            reportFailureOnEdt(project, "Could not list " + agentName + " conversations", e);
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                () -> openResumePicker(project, repository, kind.get(), agentName, conversations),
                ModalityState.any());
    }

    private static void openResumePicker(Project project, Repository repository, AgentKind kind, String agentName,
            List<ConversationSource.Conversation> conversations) {
        if (conversations.isEmpty()) {
            // An empty catalog is a state, not a failure (AC22's spirit):
            // inform, never a stack trace or a red icon.
            Messages.showInfoMessage(project,
                    "No " + agentName + " conversations were found in " + repository.root() + ".",
                    "Drydock");
            return;
        }
        new ResumePickerDialog(project, agentName, repository.root(), conversations,
                picked -> launchResume(project, repository, kind, agentName, picked)).show();
    }

    /**
     * The resume picker: one row per conversation with title, message
     * count, and last-modified (AC20), newest first; OK enabled only with
     * a selection. The picked conversation leaves through the
     * constructor-supplied callback, so the dialog owns no launch
     * knowledge.
     */
    private static final class ResumePickerDialog extends DialogWrapper {

        private final Consumer<ConversationSource.Conversation> onPick;
        private final Path root;
        private final JList<ConversationSource.Conversation> list;

        ResumePickerDialog(Project project, String agentName, Path root,
                List<ConversationSource.Conversation> conversations,
                Consumer<ConversationSource.Conversation> onPick) {
            super(project);
            this.onPick = Objects.requireNonNull(onPick, "onPick");
            setTitle("Resume " + agentName + " conversation");
            this.root = root;
            list = new JList<>(conversations.toArray(ConversationSource.Conversation[]::new));
            list.setCellRenderer(new ConversationRowRenderer());
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setVisibleRowCount(12);
            if (!conversations.isEmpty()) {
                list.setSelectedIndex(0);
            }
            list.addListSelectionListener(e -> setOKActionEnabled(list.getSelectedValue() != null));
            init();
            setOKActionEnabled(list.getSelectedValue() != null);
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.add(new JLabel("Recent conversations in " + root + ":"), BorderLayout.NORTH);
            panel.add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(480, 320));
            return panel;
        }

        @Override
        protected void doOKAction() {
            ConversationSource.Conversation picked = list.getSelectedValue();
            if (picked == null) {
                return;
            }
            super.doOKAction();
            onPick.accept(picked);
        }
    }

    /**
     * Resumes the picked conversation with visible progress (AC12): the
     * service adopts/records the managed session and opens the terminal tab
     * on the plugin's executor (AC20's record/update + launch halves);
     * this task only waits, bounded, and a failure surfaces an error naming
     * the agent (AC11 UI half).
     */
    private static void launchResume(Project project, Repository repository, AgentKind kind, String agentName,
            ConversationSource.Conversation picked) {
        CompletableFuture<ManagedAgentSession> resume = DrydockStartupActivity.sessions(project)
                .resumeConversation(repository, kind, picked, picked.title());
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Resuming " + agentName + " conversation…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    resume.get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    reportLaunchFailure(project, "resume " + agentName + " conversation", e);
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING,
                            "Conversation resume did not complete within {0}s", LAUNCH_TIMEOUT_SECONDS);
                }
            }
        });
    }

    // ---- shared Swing bits ------------------------------------------------------

    /** Renders agent rows with {@link #agentRowText}; unavailable rows are grayed. */
    private static final class AgentRowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Component rendered = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Agent agent && rendered instanceof JLabel label) {
                label.setText(agentRowText(agent));
                label.setEnabled(agent.isAvailable());
            }
            return rendered;
        }
    }

    /** Renders conversation rows with {@link #conversationRowText}. */
    private static final class ConversationRowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Component rendered = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ConversationSource.Conversation conversation && rendered instanceof JLabel label) {
                label.setText(conversationRowText(conversation));
            }
            return rendered;
        }
    }

    /** The launch/resume failure the user sees (AC11): names the action's agent, keeps the cause's own words. */
    private static void reportLaunchFailure(Project project, String what, Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        LOG.log(Level.WARNING, "Could not " + what + ": " + cause);
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project,
                        "Could not " + what + ": " + message(cause), "Drydock"),
                ModalityState.any());
    }

    private static void reportFailureOnEdt(Project project, String title, Throwable failure) {
        LOG.log(Level.WARNING, title + ": " + failure);
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, title + ": " + message(failure), "Drydock"),
                ModalityState.any());
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message != null && !message.isBlank() ? message : failure.toString();
    }
}
