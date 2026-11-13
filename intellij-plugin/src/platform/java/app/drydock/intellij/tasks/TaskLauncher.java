package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.git.BranchListing;
import app.drydock.git.BranchRef;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GitStatusService;
import app.drydock.git.JiraIssue;
import app.drydock.git.WorktreeService;
import app.drydock.github.GitHubLinkService;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskLaunchBinding;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.projects.SharedProjectsService;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.workspace.WorkspaceSettings;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.mcp.TaskToolContext;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsSeed;
import app.drydock.tasks.NewTaskLauncherResult;
import app.drydock.tasks.TaskDocument;
import app.drydock.workspace.BoxCheckout;
import app.drydock.workspace.BoxLifecycle;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;
import app.drydock.workspace.ProxyBootstrap;
import app.drydock.workspace.WorkspaceBoxNames;
import app.drydock.workspace.WorkspacesCli;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.ui.branch.GitRefDialog;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Starts a task: gathers the New task dialog's inputs off the EDT, shows
 * it, then registers the project if needed, creates the worktree if
 * needed, opens or reuses the window, and launches the agent there.
 *
 * <p>Every step that touches git or the disk runs in a background task with
 * visible progress; the window and the agent are started from the EDT with
 * a non-modal modality, because opening a project is a model change.</p>
 */
public final class TaskLauncher {

    private static final Logger LOG = System.getLogger(TaskLauncher.class.getName());

    private static final long PREPARE_TIMEOUT_SECONDS = 30;
    /** Generous: on a repository with submodules the checkout is the slow part. */
    private static final long WORKTREE_TIMEOUT_SECONDS = 180;

    private TaskLauncher() {
    }

    /**
     * What starting a chosen task takes, in order. {@code hosted}: shown in
     * this window although its worktree is another project's.
     */
    public record Steps(boolean registerFirst, boolean createWorktree, Path workingDirectory, OpenIn openIn,
                        boolean reuseOpenWindow, boolean hosted) {
        public Steps {
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            Objects.requireNonNull(openIn, "openIn");
        }
    }

    /**
     * Pure: the steps for {@code choice}. {@code windowOpenOnTarget}: a window
     * already shows its folder; {@code targetIsThisWindow}: that window is the
     * one asking. This window shows the task either way, hosted when its
     * folder is another project's; its own window is reused when open.
     */
    static Steps plan(Choice choice, Optional<SharedProject> registered, Path mainRoot, boolean windowOpenOnTarget,
                      boolean targetIsThisWindow) {
        Path directory = choice.workIn() == WorkIn.MAIN_CHECKOUT ? mainRoot : choice.folder();
        boolean here = choice.openIn() == OpenIn.THIS_WINDOW;
        return new Steps(registered.isEmpty(), choice.workIn() == WorkIn.NEW_WORKTREE, directory, choice.openIn(),
                !here && windowOpenOnTarget, here && !targetIsThisWindow);
    }

    /** Pure: the remote's default branch ({@code origin/main}) when it exists, else the local default. */
    static String defaultStart(String defaultBranch, List<String> startPoints, List<String> remotes) {
        for (String remote : remotes) {
            String candidate = remote + "/" + defaultBranch;
            if (startPoints.contains(candidate)) {
                return candidate;
            }
        }
        return defaultBranch;
    }

    // ---- the workspace-box remote branch (T20) ---------------------------------

    /** The one local git query of the remote branch: quick, like {@code GitHubLinkService}'s own. */
    private static final Duration ORIGIN_TIMEOUT = Duration.ofSeconds(10);

    /** A remote URL is a few dozen bytes; anything past this is drift the launch refuses. */
    private static final int ORIGIN_MAX_OUTPUT_BYTES = 4096;

    /** The progress texts, one per step, in call order; every exit clears them (INV-8). */
    static final String ORIGIN_STEP = "Checking the project's GitHub origin…";
    static final String LIST_STEP = "Listing the workspace boxes…";
    static final String BOOTSTRAP_STEP = "Bootstrapping the box proxy…";
    static final String WORKTREE_STEP = "Preparing the box worktree…";

    /** The create step's text; {@code ≈3 min} is the measured end-to-end create time
      * ({@code workspace-persistent-sessions/find-box-provisioning-facts}). */
    static String createStep(String boxName) {
        return "Creating workspace box " + boxName + " (≈3 min)…";
    }

    /** The probe step's text. */
    static String probeStep(String boxName) {
        return "Probing workspace box " + boxName + "…";
    }

    /**
     * The launcher-half gates before any box command runs (INV-16): only
     * a Claude launch, in a new worktree or the main checkout, without
     * task bindings, may go remote. The dialog enforces the same
     * conditions on its checkbox (T19); this is the belt-and-braces half
     * for a programmatically built {@link Choice}, and it refuses BEFORE
     * the origin query, so no box create or bootstrap can ever run for a
     * launch the session service would refuse anyway. The service keeps
     * its own refusal as the last line (the service tests pin it).
     */
    static Optional<String> remoteLaunchRefusal(AgentKind kind, WorkIn workIn, boolean taskBindings) {
        if (kind != AgentKind.CLAUDE) {
            return Optional.of(kind + " (" + kind.persistedName()
                    + ") sessions cannot run on a workspace box; only Claude sessions can");
        }
        if (taskBindings) {
            return Optional.of("A task-bound launch cannot run on a workspace box");
        }
        if (workIn != WorkIn.NEW_WORKTREE && workIn != WorkIn.MAIN_CHECKOUT) {
            return Optional.of("A session in an existing worktree cannot run on a workspace box");
        }
        return Optional.empty();
    }

    /**
     * The orgs whose repositories may run on workspace boxes, compared
     * case-insensitively because GitHub names are: the {@code workspaces}
     * CLI refuses every other org even with {@code -R}
     * ({@code workspace-persistent-sessions/find-box-provisioning-facts}),
     * so the launch refuses before any {@code workspaces create} can run
     * (AC-15's launcher half).
     */
    private static boolean datadogOrg(String owner) {
        return "DataDog".equalsIgnoreCase(owner) || "ddoghq".equalsIgnoreCase(owner);
    }

    /**
     * The whole box half of one remote launch, in the contract's exact
     * call order: origin ({@code GitHubLinkService.parseRemote} of the one
     * local git query) → refusal for non-GitHub origins and non-DataDog
     * owners before any {@code workspaces create} → the repo's mapping
     * ({@code WorkspaceSettings}' {@code .box} key, through the {@link
     * BoxMapping} seam) → {@link BoxShell} probe → {@link WorkspacesCli}
     * {@code list} → {@link BoxLifecycle#decide} → {@code workspaces
     * create} or the destructive re-create confirmation ({@link
     * RecreateConfirm}; {@code nextGeneration} never reuses a name the
     * mapping has pointed at, INV-25) → {@link BoxBootstrap} (the {@link
     * ProxyBootstrap} seam) → {@link BoxCheckout} prepare → the {@link
     * RemoteBoxOutcome.ReadyBox} binding the launch chain receives.
     *
     * <p>The whole chain holds the repo-scoped provisioning claim
     * ({@link ProvisioningGuard}) from the first mapping read through the
     * create's mapping write, so two overlapping launches for one
     * repository converge on one provisioning attempt: the second fails
     * visibly while the first holds the claim — never two concurrent
     * creates.</p>
     *
     * <p>No {@code workspaces restart}, {@code delete}, or any other
     * mutating subcommand is ever issued — the CLI class cannot express
     * one, and this path only ever asks it to {@code list} and {@code
     * create}; stopped and gone boxes are left in place (INV-11, AC-18).
     * A failed bootstrap refuses the launch visibly with its reason
     * (INV-19). Box create and re-create happen ONLY here — never from
     * Resume, Stop, Forget, or startup (INV-12).</p>
     *
     * <p>Every exit clears the progress (INV-8): the {@code finally} covers
     * success, refusal, cancellation ({@link InterruptedException}
     * propagates) and every early return. Blocking — never call on the EDT;
     * the platform half runs this inside the existing {@code
     * Task.Backgroundable}.</p>
     */
    static RemoteBoxOutcome prepareBox(RemoteBoxDeps deps, RemoteBoxRequest request) throws InterruptedException {
        Objects.requireNonNull(deps, "deps");
        Objects.requireNonNull(request, "request");
        // The repo-scoped provisioning claim: one launch at a time may read
        // this repository's mapping and provision its box. Held from the
        // first mapping read through the create's mapping write, it makes a
        // second overlapping launch for the same repository fail visibly
        // instead of racing a second, differently-named create — the
        // approved topology is ONE shared box per user and repo.
        boolean claimed = deps.provisioning().acquire(request.mainRoot());
        try {
            if (!claimed) {
                return refuse(deps, "Another Drydock launch is already preparing a workspace box for this"
                        + " repository; wait for it to finish and start the session again");
            }
            return prepareBoxSteps(deps, request);
        } finally {
            if (claimed) {
                deps.provisioning().release(request.mainRoot());
            }
            deps.progress().clear();
        }
    }

    private static RemoteBoxOutcome prepareBoxSteps(RemoteBoxDeps deps, RemoteBoxRequest request)
            throws InterruptedException {
        // 1. Origin: one local git query through the CommandRunner seam,
        //    parsed by the one GitHub remote parser.
        deps.progress().step(ORIGIN_STEP);
        String originUrl = originUrl(deps.runner(), request.mainRoot());
        if (originUrl == null) {
            return refuse(deps, "Drydock could not read the project's GitHub origin"
                    + " (git remote get-url origin failed), so the task cannot start on a workspace box");
        }
        Optional<GitHubLinkService.Remote> parsed = GitHubLinkService.parseRemote(originUrl);
        if (parsed.isEmpty()) {
            return refuse(deps, "The project's origin is not a GitHub repository"
                    + " (" + ProcessRunner.excerpt(originUrl) + "), so its sessions cannot run on a workspace box");
        }
        GitHubLinkService.Remote origin = parsed.get();
        if (!datadogOrg(origin.owner())) {
            return refuse(deps, "Workspace boxes can only be created for DataDog or ddoghq"
                    + " repositories; the origin " + origin.owner() + "/" + origin.repo() + " is not one");
        }

        // 2. The repo's mapping: the `.box` key — a user override or a
        //    re-created generation; empty on a first launch. The
        //    used-names history holds every name the mapping has ever
        //    pointed at, so a box the repo has used before is never a
        //    first-ever box even when the override was cleared.
        Optional<String> mapped = deps.mapping().box();
        if (mapped.isPresent() && !WorkspaceBoxNames.validate(mapped.get())) {
            return refuse(deps, "The workspace box name \"" + mapped.get() + "\" recorded for this"
                    + " repository is not a valid box name; fix it in the Workspace box dialog");
        }
        String base = mapped.orElseGet(() -> WorkspaceBoxNames.defaultName(request.repoName()));
        String host = "workspace-" + base; // the alias WorkspacesBinding.sshRemote() derives
        Set<String> usedBoxes = deps.mapping().usedBoxes();
        // A box the repo has used before — the mapping points at it now, or
        // the history shows an earlier incarnation of the same name — is
        // NEVER a first-ever box: its absence is OFFER_RECREATE(GONE),
        // never a same-name CREATE that would rebind old rows' alias to a
        // fresh box (D2, INV-25).
        boolean boxKnown = mapped.isPresent() || usedBoxes.contains(base);

        // 3-5. Probe, list, decide.
        deps.progress().step(probeStep(base));
        BoxShell.BoxResult probe = new BoxShell(deps.runner()).probe(host);
        deps.progress().step(LIST_STEP);
        WorkspacesCli.ListResult listed = deps.cli().list();
        BoxLifecycle.Outcome decision = BoxLifecycle.decide(probe,
                BoxLifecycle.cliStatus(listed, base), boxKnown, deps.boxAlreadyBootstrapped());

        return switch (decision) {
            case BoxLifecycle.Outcome.Reuse ignored -> usedBox(deps, request, base, host, origin, mapped);
            case BoxLifecycle.Outcome.BootstrapThenReuse ignored ->
                    usedBox(deps, request, base, host, origin, mapped);
            case BoxLifecycle.Outcome.Create ignored -> {
                deps.progress().step(createStep(base));
                Optional<String> failure = createBox(deps, request, origin, base);
                if (failure.isPresent()) {
                    yield refuse(deps, failure.get());
                }
                // The mapping now points at the box it created, so a later
                // gone box is OFFER_RECREATE(GONE) — never a same-name CREATE
                // that would rebind old rows to a fresh box (D2, INV-25).
                deps.mapping().recordBox(base);
                yield readyBox(deps, request, base, host, origin);
            }
            case BoxLifecycle.Outcome.OfferRecreate(BoxLifecycle.RecreateReason reason) -> {
                String root = generationRoot(base);
                Set<String> neverReuse = neverReuseNames(listed, base, root, usedBoxes);
                String fresh;
                try {
                    fresh = WorkspaceBoxNames.nextGeneration(root, neverReuse);
                } catch (IllegalArgumentException e) {
                    // A mapped name too long to suffix (a valid 62/63-character
                    // override) leaves no valid generation: refuse BEFORE the
                    // confirmation is offered, never offer and then submit a
                    // candidate the same CLI adapter necessarily rejects.
                    yield refuse(deps, "The workspace box name \"" + base + "\" leaves no valid"
                            + " fresh box name (a generation name would exceed the 63-character"
                            + " box-name limit); choose a shorter box name in the Workspace box dialog");
                }
                String message = reason == BoxLifecycle.RecreateReason.GONE
                        ? "Box " + base + " no longer exists, so its conversations are gone and are"
                        + " not recovered by this action. Create a fresh workspace box " + fresh
                        + " for this repository?"
                        : "Box " + base + " is stopped, so its conversations are unreachable and are"
                        + " not recovered by this action. The stopped box is left in place. Create a"
                        + " fresh workspace box " + fresh + " for this repository?";
                if (!deps.confirm().reCreate(message)) {
                    // Cancelled: nothing is created, nothing is written, no
                    // launch happens (AC-18) — a user decision, not a failure.
                    yield new RemoteBoxOutcome.BoxCancelled();
                }
                deps.progress().step(createStep(fresh));
                Optional<String> failure = createBox(deps, request, origin, fresh);
                if (failure.isPresent()) {
                    yield refuse(deps, failure.get());
                }
                deps.mapping().recordBox(fresh);
                yield readyBox(deps, request, fresh, "workspace-" + fresh, origin);
            }
            case BoxLifecycle.Outcome.TransientError ignored -> refuse(deps,
                    "The workspace box " + base + " could not be reached and its status could not"
                            + " be determined; try again");
        };
    }

    /**
     * The post-decision steps every surviving path shares: an unmapped
     * default box is remembered as used before anything else (R2: its
     * later loss must be a GONE re-create under a fresh name, never a
     * first-ever same-name create), then bootstrap the box proxy, then
     * prepare the box worktree. A failed bootstrap refuses the launch
     * with its reason (INV-19) — never a launch without the proxy.
     */
    private static RemoteBoxOutcome usedBox(RemoteBoxDeps deps, RemoteBoxRequest request, String base,
            String host, GitHubLinkService.Remote origin, Optional<String> mapped) throws InterruptedException {
        if (mapped.isEmpty()) {
            // The launch is about to use this default box for a persisted
            // session: record it now, so once it disappears the next launch
            // offers the destructive re-create instead of silently creating
            // the same name again (old rows would alias to a fresh box).
            deps.mapping().recordBox(base);
        }
        return readyBox(deps, request, base, host, origin);
    }

    /**
     * The post-decision steps every surviving path shares: bootstrap the
     * box proxy, then prepare the box worktree. A failed bootstrap refuses
     * the launch with its reason (INV-19) — never a launch without the
     * proxy.
     */
    private static RemoteBoxOutcome readyBox(RemoteBoxDeps deps, RemoteBoxRequest request,
            String boxName, String host, GitHubLinkService.Remote origin) throws InterruptedException {
        deps.progress().step(BOOTSTRAP_STEP);
        try {
            deps.bootstrap().ensure(host);
        } catch (BootstrapRefusal e) {
            return refuse(deps, "The box proxy bootstrap failed, so the launch was refused: " + e.getMessage());
        }
        deps.progress().step(WORKTREE_STEP);
        // The clone cache is scoped to THIS box: a path discovered or
        // chosen on another box generation is not trusted, so a fresh
        // generation discovers its own clone (R2 defect 4).
        BoxCheckout.Outcome prepared = new BoxCheckout(deps.runner())
                .prepare(host, origin, request.work(), deps.mapping().cloneCache(boxName));
        return switch (prepared) {
            case BoxCheckout.Outcome.Ready(String clone, String workingPath) ->
                    new RemoteBoxOutcome.ReadyBox(new WorkspaceBoxBinding(boxName, workingPath));
            case BoxCheckout.Outcome.Refused(String reason) -> refuse(deps, reason);
        };
    }

    /**
     * Runs {@code workspaces create <name> -R <owner>/<repo> -s bash -y --no-color} in
     * the local main checkout (the CLI derives the box's repo context from
     * the cwd); empty when the box was created, else the visible refusal
     * reason — the CLI's own typed, already-logged outcome, so a failed or
     * hung create is never a silent success (INV-9, AC-17).
     */
    private static Optional<String> createBox(RemoteBoxDeps deps, RemoteBoxRequest request,
            GitHubLinkService.Remote origin, String name) throws InterruptedException {
        WorkspacesCli.CreateResult result = deps.cli().create(name,
                origin.owner() + "/" + origin.repo(), request.mainRoot());
        return switch (result) {
            case WorkspacesCli.Created ignored -> Optional.empty();
            case WorkspacesCli.NotInstalled(String detail) -> Optional.of(
                    "Creating workspace box " + name + " failed: the workspaces CLI is not"
                            + " installed (" + detail + ")");
            case WorkspacesCli.Failed(int exit, String excerpt) -> Optional.of(
                    "Creating workspace box " + name + " failed (exit " + exit + "): " + excerpt);
            case WorkspacesCli.TimedOut(String excerpt) -> Optional.of(
                    "Creating workspace box " + name + " timed out after 10 minutes: " + excerpt);
            case WorkspacesCli.OutputTooLarge(String excerpt) -> Optional.of(
                    "Creating workspace box " + name + " produced more output than Drydock accepts: "
                            + excerpt);
        };
    }

    /**
     * The lineage root for the next generation: the mapped name with one
     * trailing {@code -N} generation suffix removed. The mapping holds a
     * single {@code .box} key — no separate root key — so a generation
     * like {@code drydock-x-2} recovers its {@code drydock-x} lineage this
     * way; the caller always excludes the un-stripped name too, so the
     * strip can never make a candidate collide with the box being replaced.
     */
    private static String generationRoot(String name) {
        String stripped = name.replaceFirst("-[0-9]+$", "");
        return WorkspaceBoxNames.validate(stripped) ? stripped : name;
    }

    /**
     * The names a re-create must never reuse (INV-25): everything {@code
     * workspaces list} already answers for (stopped boxes stay listed;
     * existing boxes of other users are name-scoped anyway), the mapped
     * name and its lineage root, and — decisively, because the listing
     * only shows boxes that still exist — every name the repo's mapping
     * has EVER pointed at, from the persisted used-names history. An
     * earlier generation that has been garbage-collected is absent from
     * the current listing; the history is what keeps it out of the
     * re-create's reach.
     */
    private static Set<String> neverReuseNames(WorkspacesCli.ListResult listed, String base, String root,
            Set<String> usedBoxes) {
        Set<String> names = new HashSet<>();
        if (listed instanceof WorkspacesCli.Listed table) {
            names.addAll(table.names());
        }
        names.add(base);
        names.add(root);
        names.addAll(usedBoxes);
        return names;
    }

    /**
     * The project's own origin, through the one local git query —
     * {@code git -C <mainRoot> remote get-url origin} over the {@link
     * CommandRunner} seam (argument list, 10 s timeout, stdin from
     * {@code /dev/null}, bounded output), parsed upstream by {@link
     * GitHubLinkService#parseRemote}, the one GitHub remote parser. Null
     * means the query failed: a failed query is a refusal, never an empty
     * origin (INV-9).
     */
    private static String originUrl(CommandRunner runner, Path mainRoot) throws InterruptedException {
        List<String> argv = List.of("git", "-C", mainRoot.toString(), "remote", "get-url", "origin");
        ProcessResult result;
        try {
            result = runner.run(argv,
                    new ProcessRunner.Options(null, ORIGIN_TIMEOUT, true, Map.of()), ORIGIN_MAX_OUTPUT_BYTES);
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "Reading the project's origin timed out", e);
            return null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Reading the project's origin failed", e);
            return null;
        }
        if (result.exitCode() != 0) {
            LOG.log(Level.WARNING, "git remote get-url origin exited {0}: {1}",
                    new Object[] {result.exitCode(), ProcessRunner.excerpt(result.stderr())});
            return null;
        }
        return result.stdout();
    }

    /** One refusal: surfaced through the deps' reporter, and carried back to the caller. */
    private static RemoteBoxOutcome refuse(RemoteBoxDeps deps, String reason) {
        deps.reporter().accept(reason);
        return new RemoteBoxOutcome.RefusedBox(reason);
    }

    /**
     * The platform halves of one remote launch: every dependency the pure
     * box chain above cannot own — the {@link CommandRunner} seam, the
     * CLI, the bootstrap and confirmation and progress and reporting
     * bindings — so tests drive the whole chain on fakes with no IDE
     * behind it. {@code boxAlreadyBootstrapped} is whether the box is
     * known provisioned; production passes {@code false} because nothing
     * records it and {@link ProxyBootstrap#ensure} is idempotent — a
     * second run on a bootstrapped box issues no scp and no start — so
     * REUSE and BOOTSTRAP_THEN_REUSE behave identically here and the
     * bootstrap still runs on every launch.
     */
    record RemoteBoxDeps(CommandRunner runner, WorkspacesCli cli, BoxBootstrap bootstrap, BoxMapping mapping,
                         ProvisioningGuard provisioning, RecreateConfirm confirm, Consumer<String> reporter,
                         ProgressSink progress, boolean boxAlreadyBootstrapped) {
        RemoteBoxDeps {
            Objects.requireNonNull(runner, "runner");
            Objects.requireNonNull(cli, "cli");
            Objects.requireNonNull(bootstrap, "bootstrap");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(provisioning, "provisioning");
            Objects.requireNonNull(confirm, "confirm");
            Objects.requireNonNull(reporter, "reporter");
            Objects.requireNonNull(progress, "progress");
        }
    }

    /**
     * One remote launch's box-side request: the local main checkout (the
     * create cwd and the origin the box clone must match), the repository
     * name the default box name derives from, and the box work to prepare.
     */
    record RemoteBoxRequest(Path mainRoot, String repoName, BoxCheckout.Work work) {
        RemoteBoxRequest {
            Objects.requireNonNull(mainRoot, "mainRoot");
            Objects.requireNonNull(repoName, "repoName");
            Objects.requireNonNull(work, "work");
        }
    }

    /** What preparing the box for one remote launch ended in. */
    sealed interface RemoteBoxOutcome {

        /** The box is ready; {@code binding} is what the launch chain receives. */
        record ReadyBox(WorkspaceBoxBinding binding) implements RemoteBoxOutcome {
        }

        /** The launch is refused; the reason was already surfaced through the reporter. */
        record RefusedBox(String reason) implements RemoteBoxOutcome {
        }

        /** The user declined the destructive re-create; nothing changed. */
        record BoxCancelled() implements RemoteBoxOutcome {
        }
    }

    /**
     * The repo's box mapping — {@code WorkspaceSettings}' {@code .box},
     * {@code .usedBoxes} and {@code .clone} keys — bound to the registered
     * repository's stable id in production; tests record writes against an
     * in-memory fake.
     */
    interface BoxMapping {

        /** The repository's current box name — a user override or a re-created generation. */
        Optional<String> box();

        /** Records the box the repo now maps to (a first create, a confirmed re-create,
          * or a first use of an unmapped default box); the write also lands the name in the
          * used-names history. */
        void recordBox(String boxName);

        /** Every name the repo's mapping has ever pointed at — earlier generations and
          * cleared overrides included (INV-25); a fresh box never takes one of them back,
          * even after the old boxes have left {@code workspaces list}. */
        Set<String> usedBoxes();

        /** The per-repo {@code .clone} cache {@link BoxCheckout} reads and stores through,
          * scoped to {@code boxName}: a path recorded for another box generation is not
          * served, so a fresh generation discovers its own clone. */
        BoxCheckout.CloneCache cloneCache(String boxName);
    }

    /**
     * The repo-scoped, in-process provisioning claim: one launch at a time
     * may read a repository's mapping and provision its box. A second
     * overlapping launch for the same repository fails visibly (the refusal
     * names the overlap) instead of racing a second, differently-named
     * create — the approved topology is one shared box per user and repo.
     * {@link #acquire} is granted before the mapping is first read and
     * {@link #release} is called on every exit of the chain, so two
     * concurrent creates can never interleave. Production binds the
     * process-wide set below; tests inject fakes.
     */
    interface ProvisioningGuard {

        /** Whether this launch now owns the repository's provisioning; {@code false} = another launch holds it. */
        boolean acquire(Path mainRoot);

        /** Returns a claim {@link #acquire} granted; every exit of the chain calls it. */
        void release(Path mainRoot);
    }

    /** The repositories whose box provisioning one launch currently owns (in-process, repo-scoped). */
    private static final Set<Path> PROVISIONING_REPOS = ConcurrentHashMap.newKeySet();

    /** The production {@link ProvisioningGuard} bound into every remote launch's deps. */
    static final ProvisioningGuard PRODUCTION_PROVISIONING = new ProvisioningGuard() {
        @Override
        public boolean acquire(Path mainRoot) {
            return PROVISIONING_REPOS.add(mainRoot);
        }

        @Override
        public void release(Path mainRoot) {
            PROVISIONING_REPOS.remove(mainRoot);
        }
    };

    /**
     * The bootstrap seam: production binds the real {@link ProxyBootstrap#ensure},
     * carrying its typed refusal as this launcher's own {@link BootstrapRefusal}
     * so tests (and callers) need no IDE to refuse a launch.
     */
    @FunctionalInterface
    interface BoxBootstrap {

        /** Makes the box serve the gateway-only proxy; throws {@link BootstrapRefusal} on failure. */
        void ensure(String host) throws InterruptedException, BootstrapRefusal;
    }

    /** A bootstrap refusal as the launcher surfaces it: the message is the visible, secret-free reason (INV-19). */
    static final class BootstrapRefusal extends Exception {

        BootstrapRefusal(String reason) {
            super(reason);
        }
    }

    /**
     * The destructive re-create confirmation. Production hops to the EDT
     * for a {@code Messages} yes/no dialog carrying the full message the
     * box chain composed — naming the old box and the loss of its
     * conversations; tests answer directly.
     */
    @FunctionalInterface
    interface RecreateConfirm {

        /** {@code true} = create the new generation; {@code false} = leave everything unchanged. */
        boolean reCreate(String message);
    }

    /** The progress the box chain sets per step and clears on every exit (INV-8). */
    interface ProgressSink {

        /** Shows one step's text. */
        void step(String text);

        /** Clears the progress; called on every exit of the chain. */
        void clear();
    }

    /**
     * Opens the New task dialog for the project at {@code mainRoot} — this
     * window's project when empty — optionally fixed to an existing
     * worktree.
     */
    public static void open(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed) {
        open(from, mainRoot, fixed, NewTaskPreset.empty());
    }

    /** Opens a task dialog fixed to a registered project's main checkout. */
    static void openInMainCheckout(Project from, Path mainRoot) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.mainCheckout());
    }

    /** Opens a writable new-worktree task prefilled from a GitHub issue. */
    static void implement(Project from, Path mainRoot, OpenIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.implement(issue));
    }

    /** Opens a main-checkout task prefilled to validate and prioritize a GitHub issue. */
    static void triage(Project from, Path mainRoot, OpenIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.triage(issue));
    }

    /** Opens a writable new-worktree task prefilled from a Jira issue. */
    static void implement(Project from, Path mainRoot, JiraIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.implement(issue));
    }

    /** Opens a main-checkout task prefilled to validate and prioritize a Jira issue. */
    static void triage(Project from, Path mainRoot, JiraIssue issue) {
        open(from, Optional.of(mainRoot), Optional.empty(), NewTaskPreset.triage(issue));
    }

    /** As {@link #open(Project, Optional, Optional)}, with the dialog preset ({@code Start ▸}'s menu, an issue). */
    static void open(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed,
                     NewTaskPreset preset) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                prepareAndShow(from, mainRoot, fixed, Optional.empty(), preset);
            }
        });
    }

    /**
     * Opens the New task dialog for a branch picked in Git's menus
     * ({@code fullRefName} is {@code refs/heads/…} or {@code refs/remotes/…})
     * in the repository at {@code repositoryRoot}: fixed to the worktree the
     * branch is checked out in, else with the branch as the start point —
     * see {@link BranchTaskEntry}.
     */
    public static void openFromRef(Project from, Path repositoryRoot, String fullRefName) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<WorktreeService.Worktree> worktrees;
                try (WorktreeService service = new WorktreeService()) {
                    worktrees = service.list(repositoryRoot).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not list the worktrees of " + repositoryRoot, e);
                    report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
                    return;
                }
                switch (BranchTaskEntry.forRef(fullRefName, worktrees).orElse(null)) {
                    case BranchTaskEntry.InWorktree in ->
                            prepareAndShow(from, Optional.of(repositoryRoot), Optional.of(in.worktree()), Optional.empty(),
                                    NewTaskPreset.empty());
                    case BranchTaskEntry.StartFrom branchOff ->
                            prepareAndShow(from, Optional.of(repositoryRoot), Optional.empty(),
                                    Optional.of(branchOff.startPoint()), NewTaskPreset.empty());
                    case null -> LOG.log(Level.DEBUG, "No task entry for " + fullRefName);
                }
            }
        });
    }

    // ---- the observable facade (T22a, D8/P4) ---------------------------------

    /**
     * Prepares a task for choosing in the background (T22a, D8/P4): the
     * same dialog-input resolution the launch path uses, off the EDT, with
     * the same visible progress and the same refusals reported to the
     * user. The future completes with the prepared task, or empty when
     * the preparation refused or failed; no path leaves it pending.
     */
    public static CompletableFuture<Optional<PreparedTask>> prepareTask(Project from, Optional<Path> mainRoot,
                                                                       Optional<FixedWorktree> fixed) {
        CompletableFuture<Optional<PreparedTask>> prepared = new CompletableFuture<>();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Preparing new task…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    prepared.complete(prepare(from, mainRoot, fixed, Optional.empty())
                            .<PreparedTask>map(PreparedTask::new));
                } finally {
                    // Refusal, failure and interruption all end inside
                    // prepare() with a reported, empty result; this is the
                    // backstop that no path strands the future.
                    prepared.complete(Optional.empty());
                }
            }
        });
        return prepared;
    }

    /**
     * Shows the New task dialog for a prepared task WITHOUT launching on
     * OK (T22a, D8/P4): the future completes with the accepted choice, or
     * empty when the dialog was canceled, its window was gone, or the user
     * saved the task for later -- a save is dispatched to the background
     * exactly as the launch path dispatches it, only the launch itself is
     * withheld. The choice travels as the opaque {@link ChosenTask}: the
     * dialog, its preset and its wiring stay this package's own. The
     * dialog shows on the EDT; the future completes when it closes.
     */
    public static CompletableFuture<Optional<ChosenTask>> chooseTask(Project from, PreparedTask preparedTask) {
        return chooseTask(from, preparedTask, NewTaskPreset.empty());
    }

    /** As the public {@link #chooseTask(Project, PreparedTask)}, for a preset-carrying entry (the single-project
      * start's segment preset, T31a; public so the delayed-task layer can open the SAME dialog for its one segment).
      * This package only otherwise. */
    public static CompletableFuture<Optional<ChosenTask>> chooseTask(Project from, PreparedTask preparedTask,
                                                                     NewTaskPreset preset) {
        CompletableFuture<Optional<ChosenTask>> chosen = new CompletableFuture<>();
        Runnable show = () -> {
            Optional<Supplier<Optional<String>>> refChooser;
            try {
                refChooser = refChooser(preparedTask.inputs().project().root());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not prepare the New task dialog's ref chooser", e);
                report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
                chosen.complete(Optional.empty());
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> chooseChosen(from, preparedTask, refChooser,
                    preset, chosen), ModalityState.nonModal());
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            // The ref chooser blocks on git (worktree list): never on the EDT.
            ApplicationManager.getApplication().executeOnPooledThread(show);
        } else {
            show.run();
        }
        return chosen;
    }

    /** Shows the dialog on the EDT and completes {@code chosen} with what it accepted; never leaves it pending. */
    private static void chooseChosen(Project from, PreparedTask preparedTask,
                                     Optional<Supplier<Optional<String>>> refChooser, NewTaskPreset preset,
                                     CompletableFuture<Optional<ChosenTask>> chosen) {
        try {
            if (from.isDisposed()) {
                chosen.complete(Optional.empty());
                return;
            }
            NewTaskDialog dialog = new NewTaskDialog(from, preparedTask.inputs(), refChooser, preset);
            if (dialog.showAndGet()) {
                NewTaskLauncherResult result = dialog.launcherResult();
                if (result.isLaunch()) {
                    chosen.complete(Optional.of(new ChosenTask(result.choice())));
                } else {
                    saveTask(from, result.saved());
                    chosen.complete(Optional.empty());
                }
            } else {
                chosen.complete(Optional.empty());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not show the New task dialog", e);
            report(from, "Could not show the New task dialog: " + WorktreeErrors.visibleMessage(e));
            chosen.complete(Optional.empty());
        }
    }

    /**
     * T38's production Configure seam (a coordinator-granted addition): one
     * segment's launch choice from the EXISTING New Session dialog, opened
     * NESTED over the plan dialog. The git-backed ref chooser prepares OFF
     * the EDT (never on the Configure button handler); the dialog then opens
     * in the PARENT's own modality state ({@code ModalityState.stateForComponent},
     * the T19b-verified nested-modal pattern this dialog's own OK dispatch
     * uses), so it shows while the plan dialog is up -- unlike
     * {@link #chooseTask}, whose non-modal invokeLater would wait for the
     * parent to close. OK answers the accepted {@link Choice} ONLY, never a
     * started session; a save-for-later OK saves through the existing path
     * and answers empty; cancel answers empty. The future completes on every
     * path, also when the parent was closed mid-preparation -- then empty,
     * and no orphan dialog ever opens.
     */
    public static CompletableFuture<Optional<Choice>> chooseSegment(Project from, PreparedTask preparedTask,
                                                                     NewTaskPreset preset,
                                                                     DialogWrapper parent) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(preparedTask, "preparedTask");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(parent, "parent");
        CompletableFuture<Optional<Choice>> answer = new CompletableFuture<>();
        ModalityState modality = ModalityState.stateForComponent(parent.getContentPanel());
        Runnable prepare = () -> {
            Optional<Supplier<Optional<String>>> refChooser;
            try {
                refChooser = refChooser(preparedTask.inputs().project().root());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not prepare the New task dialog's ref chooser", e);
                report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
                answer.complete(Optional.empty());
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (answer.isDone() || from.isDisposed()
                        || parent.getContentPanel() == null || !parent.getContentPanel().isShowing()) {
                    answer.complete(Optional.empty()); // the parent closed: never an orphan dialog
                    return;
                }
                try {
                    NewTaskDialog dialog = new NewTaskDialog(from, preparedTask.inputs(), refChooser, preset);
                    if (dialog.showAndGet()) {
                        NewTaskLauncherResult result = dialog.launcherResult();
                        if (result.isLaunch()) {
                            answer.complete(Optional.of(result.choice()));
                        } else {
                            saveTask(from, result.saved());
                            answer.complete(Optional.empty());
                        }
                    } else {
                        answer.complete(Optional.empty());
                    }
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not show the New task dialog", e);
                    report(from, "Could not show the New task dialog: " + WorktreeErrors.visibleMessage(e));
                    answer.complete(Optional.empty());
                }
            }, modality);
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            // the ref chooser blocks on git (worktree list): never on the EDT
            ApplicationManager.getApplication().executeOnPooledThread(prepare);
        } else {
            prepare.run();
        }
        return answer;
    }

    /**
     * One confirmed launch choice as the opaque {@link ChosenTask} (T38): the
     * staged plan holds the confirmed {@link Choice} as a stored value, and the
     * launch chain takes it back through this wrapper, never re-inspected.
     */
    public static ChosenTask chosen(Choice choice) {
        return new ChosenTask(Objects.requireNonNull(choice, "choice"));
    }

    /**
     * Launches a chosen task through the existing start chain (T22a,
     * D8/P4): register, worktree, window, then
     * {@link SessionDialogs#launchSession} -- no second process path. The
     * future completes with the RUNNING session (its id is the observable
     * outcome), or exceptionally on every refusal, cancel, failure and
     * disposed path; no wait, timeout or cancel on it cancels or fails the
     * underlying launch, which can still complete it late and accurately.
     */
    public static CompletableFuture<ManagedAgentSession> launchChosen(Project from, PreparedTask preparedTask,
                                                                      ChosenTask chosen) {
        return launchChosen(from, preparedTask, chosen, new SessionDialogs.PreparedSessionCallback() {
        });
    }

    /**
     * As {@link #launchChosen(Project, PreparedTask, ChosenTask)}, with the
     * pre-spawn prepared-session callback (T28's task binding seam).
     */
    public static CompletableFuture<ManagedAgentSession> launchChosen(Project from, PreparedTask preparedTask,
                                                                      ChosenTask chosen,
                                                                      SessionDialogs.PreparedSessionCallback preparedSession) {
        return start(from, preparedTask.inputs(), chosen.choice(), preparedSession);
    }

    /**
     * As {@link #launchChosen(Project, PreparedTask, ChosenTask, SessionDialogs.PreparedSessionCallback)},
     * with the whole one-project segment's pre-spawn bindings (T31a,
     * T28a): every binding runs on the launch chain before the spawn, and
     * any bind refusal prevents the launch. Still the existing start chain
     * -- register, worktree, window, {@code SessionDialogs} -- no second
     * process path.
     */
    public static CompletableFuture<ManagedAgentSession> launchChosen(Project from, PreparedTask preparedTask,
                                                                        ChosenTask chosen,
                                                                        SessionDialogs.PreparedSessionCallback preparedSession,
                                                                        List<TaskLaunchBinding> bindings) {
        return start(from, preparedTask.inputs(), chosen.choice(), preparedSession, bindings);
    }

    /**
     * A task prepared for choosing (T22a, D8/P4): the New task dialog's
     * inputs, resolved off the EDT by {@link #prepareTask}. Opaque outside
     * this package on purpose: the inputs' shape is the dialog's wiring,
     * so a coordinator (the staged plan, T28) only passes it back to
     * {@link #chooseTask} and {@link #launchChosen}.
     */
    public static final class PreparedTask {

        private final NewTaskPlan.Inputs inputs;

        PreparedTask(NewTaskPlan.Inputs inputs) {
            this.inputs = Objects.requireNonNull(inputs, "inputs");
        }

        /** The dialog's inputs; this package only. */
        NewTaskPlan.Inputs inputs() {
            return inputs;
        }

        /** The worktree folder a NEW branch of this preparation would use:
          * the dialog's own {@code folderFor} derivation for the prepared
          * project and worktree base. The stacked launch units use it to
          * mint one folder per stacked branch without reopening the dialog. */
        public Path worktreeFolder(String branch) {
            return NewTaskPlan.folderFor(inputs.worktreeBase(), inputs.repoName(), branch);
        }
    }

    /**
     * A task chosen without launching (T22a, D8/P4): the launch choice the
     * New task dialog accepted for a {@link PreparedTask}. Opaque outside
     * this package: it only travels back to
     * {@link #launchChosen}, unchanged and uninspected.
     */
    public static final class ChosenTask {

        private final Choice choice;

        ChosenTask(Choice choice) {
            this.choice = Objects.requireNonNull(choice, "choice");
        }

        /** The accepted launch choice. Widened for the stacked launch
          * (S1 of docs/StackedPrExecution.md): each unit's Choice is DERIVED
          * from the confirmed one -- same agent, mode, window and start
          * point, the branch and instructions replaced by the unit's own --
          * and only the launcher's launch chain needs it. */
        public Choice choice() {
            return choice;
        }
    }

    /** Off the EDT: resolves the dialog's inputs, then shows it; {@code start} overrides the default start point. */
    private static void prepareAndShow(Project from, Optional<Path> mainRoot, Optional<FixedWorktree> fixed,
                                       Optional<String> start, NewTaskPreset preset) {
        Optional<NewTaskPlan.Inputs> inputs = prepare(from, mainRoot, fixed, start);
        if (inputs.isEmpty()) {
            return;
        }
        // The dialog path is unchanged: the same ref chooser, the same
        // preset, the same save on Save-for-later, the same launch on OK.
        chooseTask(from, new PreparedTask(inputs.get()), preset)
                .thenAccept(chosen -> chosen.ifPresent(task -> start(from, inputs.get(), task.choice())));
    }

    private static Optional<NewTaskPlan.Inputs> prepare(Project from, Optional<Path> requestedRoot,
                                                        Optional<FixedWorktree> fixed, Optional<String> start) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try (GitStatusService git = new GitStatusService(); WorktreeService worktrees = new WorktreeService()) {
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Optional<Path> base = requestedRoot.or(() -> Optional.ofNullable(from.getBasePath()).map(Path::of));
            if (base.isEmpty()) {
                report(from, "This window has no project folder, so a task cannot start here.");
                return Optional.empty();
            }
            Optional<Path> main = SharedProjectsSeed.mainCheckoutVia(worktrees).apply(base.get())
                    .map(RepositoryCatalog::canonicalize);
            if (main.isEmpty()) {
                report(from, base.get() + " is not a git repository, so Drydock cannot start a task in it.");
                return Optional.empty();
            }
            Path mainRoot = main.get();
            SharedProject project = plugin.sharedProjects().byRoot(mainRoot)
                    .orElseGet(() -> SharedProject.local(RepositoryId.newId(), mainRoot,
                            String.valueOf(mainRoot.getFileName()), Instant.now()));
            String defaultBranch = git.defaultBranch(mainRoot).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElse("main");
            BranchListing listing = git.listBranches(mainRoot).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> startPoints = new ArrayList<>(listing.branches().stream().map(BranchRef::name).toList());
            // Local names only are taken: a new worktree's branch is local.
            Set<String> taken = listing.branches().stream().filter(ref -> !ref.remote()).map(BranchRef::name)
                    .collect(Collectors.toUnmodifiableSet());
            // The project's own record holds its last-used agent and, per
            // harness, its last-used starting model.
            Repository local = DrydockStartupActivity.repositories(from).repositoryFor(mainRoot)
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Function<AgentKind, Optional<String>> lastUsedModel = kind -> local == null ? Optional.empty()
                    : local.settings().lastUsedModel(kind);
            Function<AgentKind, ModelTiers> lastUsedTiers = kind -> local == null ? ModelTiers.EMPTY
                    : local.settings().lastUsedTiers(kind).orElse(ModelTiers.EMPTY);
            AgentRegistry registry = plugin.registry();
            AgentKind preselected = SessionDialogs.defaultAgent(registry,
                    local == null ? Optional.empty() : local.settings().lastUsedAgent()).orElse(null);
            Path worktreeBase = DrydockConfigurable.effectiveBase(UserConfig.load().worktreesDirectory(),
                    Path.of(System.getProperty("user.home")));
            boolean writerActive = DrydockStartupActivity.sessions(from).hasActiveWriterIn(mainRoot);
            return Optional.of(new NewTaskPlan.Inputs(project, project.displayName(), fixed, registry.agents(),
                    preselected,
                    start.filter(startPoints::contains)
                            .orElseGet(() -> defaultStart(defaultBranch, startPoints, listing.remotes())),
                    startPoints,
                    listing.remotes(), taken, worktreeBase, writerActive, lastUsedModel,
                    registry::modelSuggestions, lastUsedTiers, registry::supportsSubagents));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare the New task dialog", e);
            report(from, "Drydock could not prepare a new task: " + WorktreeErrors.visibleMessage(e));
            return Optional.empty();
        }
    }

    /**
     * Git's own branch/tag picker for the repository at {@code mainRoot},
     * when an open window belongs to that repository and holds exactly one
     * Git repository; otherwise none.
     *
     * <p>No {@link GitRepository} method is called: {@code getRoot()} is
     * inherited from {@code com.intellij.dvcs.repo.Repository}, which
     * 2026.3 moved into a separate VCS plugin Plugin Verifier cannot resolve
     * from here, and {@code getGitDir()} is scheduled for removal. The
     * window is matched by its own main checkout instead. Blocking ({@code
     * git worktree list}): background only.</p>
     */
    private static Optional<Supplier<Optional<String>>> refChooser(Path mainRoot) {
        try (WorktreeService worktrees = new WorktreeService()) {
            for (Project open : ProjectManager.getInstance().getOpenProjects()) {
                if (open.isDisposed() || open.getBasePath() == null) {
                    continue;
                }
                boolean sameRepository = SharedProjectsSeed.mainCheckoutVia(worktrees)
                        .apply(Path.of(open.getBasePath()))
                        .map(RepositoryCatalog::canonicalize)
                        .map(mainRoot::equals)
                        .orElse(false);
                List<GitRepository> repositories = sameRepository
                        ? GitRepositoryManager.getInstance(open).getRepositories() : List.of();
                if (repositories.size() == 1) {
                    return Optional.of(() -> {
                        GitRefDialog dialog = new GitRefDialog(open, repositories, "Start From",
                                "Branch, tag or revision:");
                        return dialog.showAndGet() ? Optional.of(dialog.getReference()) : Optional.empty();
                    });
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Saves the task the dialog accepted instead of starting it (T19a,
     * D5/P5): the write runs in the background with progress, and every
     * completion path ends the task -- the Backgroundable owns the
     * indicator, so a failed save surfaces visibly and never strands the
     * progress. A save registers the project, creates the task through the
     * task service and stores the body as its notes. It never registers a
     * session, creates a worktree or spawns an agent, and never touches the
     * agent registry, so it works with no agent installed.
     */
    static void saveTask(Project from, app.drydock.tasks.NewTaskPlan saved) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Saving task \"" + saved.title() + "\"", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Saving task \"" + saved.title() + "\"");
                try {
                    saveTask(plugin.sharedProjects(), plugin.taskService(), saved);
                } catch (TaskSaveException e) {
                    // A refused save: visible, and the background task ends
                    // here so the progress goes with it.
                    LOG.log(Level.WARNING, "Could not save the task " + saved.title(), e);
                    report(from, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not save the task " + saved.title(), e);
                    report(from, "Drydock could not save the task \"" + saved.title() + "\": "
                            + WorktreeErrors.visibleMessage(e));
                }
            }
        });
    }

    /**
     * The save write itself, as the background task runs it and the smoke
     * exercises it headlessly: the project's STABLE shared-registry id
     * (registering it first only when needed; registration is idempotent
     * and keeps the id), one task created through the task service, then
     * the body's note paragraphs. Domain refusals surface as
     * {@link TaskSaveException} with the visible message; store and init
     * failures as the future's checked failures. No session is registered,
     * no worktree created, nothing spawned.
     */
    static TaskDocument saveTask(SharedProjectsService projects, DelayedTaskService tasks,
                                 app.drydock.tasks.NewTaskPlan saved)
            throws TaskSaveException, InterruptedException, ExecutionException, TimeoutException {
        tasks.initialize().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Optional<SharedProject> registered = projects.byRoot(saved.projectRoot());
        RepositoryId stable;
        if (registered.isPresent()) {
            stable = registered.get().id();
        } else {
            // Registers only when needed; registration keeps the entry and its id either way.
            stable = projects.register(saved.projectRoot(), saved.projectName())
                    .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).id();
        }
        TaskDocument created = committed(tasks.create(saved.title(), saved.after(), stable)
                        .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Drydock could not save the task \"" + saved.title() + "\"");
        TaskDocument task = created;
        for (String note : saved.bodyNotes()) {
            task = committed(tasks.appendNote(task.id(), note)
                            .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "The task '" + created.id().text() + "' was saved but its body could not be stored");
        }
        return task;
    }

    /** One service outcome: its committed document, or the visible refusal. */
    private static TaskDocument committed(TaskToolContext.Outcome outcome, String visible)
            throws TaskSaveException {
        if (outcome.refused()) {
            throw new TaskSaveException(visible + ": " + outcome.refusal());
        }
        return outcome.document();
    }

    /** A save the task domain refused or half-completed; the message is what the user sees. */
    static final class TaskSaveException extends Exception {
        TaskSaveException(String message) {
            super(message);
        }
    }

    /**
     * Runs the chosen task: register, create, open, launch — with progress.
     *
     * <p>Observable (T22a, D8/P4): the returned future completes with the
     * RUNNING session -- its id is the observable outcome -- or
     * exceptionally on every refusal, cancel, failure and disposed path;
     * never permanently pending. It only ever mirrors the real launch
     * chain ({@link SessionDialogs#launchSession}), so a timed-out wait on
     * it cancels and fails nothing, and a late launch still completes it
     * accurately.</p>
     */
    static CompletableFuture<ManagedAgentSession> start(Project from, NewTaskPlan.Inputs inputs, Choice choice) {
        return start(from, inputs, choice, new SessionDialogs.PreparedSessionCallback() {
        });
    }

    /** As {@link #start(Project, NewTaskPlan.Inputs, Choice)}, with the pre-spawn prepared-session callback (T28). */
    static CompletableFuture<ManagedAgentSession> start(Project from, NewTaskPlan.Inputs inputs, Choice choice,
                                                        SessionDialogs.PreparedSessionCallback preparedSession) {
        return start(from, inputs, choice, preparedSession, List.of());
    }

    /** As {@link #start(Project, NewTaskPlan.Inputs, Choice, SessionDialogs.PreparedSessionCallback)}, with the
      * one-project segment's pre-spawn bindings (T31a, T28a): an empty list is the byte-identical no-binding
      * chain; a non-empty one composes the bind step before the tab and the spawn, where a refusal prevents
      * the launch. A choice that asks for a workspace box is refused before the local chain runs at all. */
    static CompletableFuture<ManagedAgentSession> start(Project from, NewTaskPlan.Inputs inputs, Choice choice,
                                                        SessionDialogs.PreparedSessionCallback preparedSession,
                                                        List<TaskLaunchBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        if (choice.onWorkspaceBox()) {
            // The remote arm (T20): the whole box chain, then the same
            // launch chain with a binding. The local half below runs
            // byte-identically for every local choice (INV-1).
            return startRemote(from, inputs, choice, preparedSession, bindings);
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Path mainRoot = inputs.project().root();
        TrackedLaunch tracked = new TrackedLaunch();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting " + choice.name() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path directory = choice.workIn() == WorkIn.MAIN_CHECKOUT ? mainRoot : choice.folder();
                Optional<Project> openWindow = windowOn(directory);
                Steps steps = plan(choice, plugin.sharedProjects().byRoot(mainRoot), mainRoot, openWindow.isPresent(),
                        openWindow.map(from::equals).orElse(false));
                try (GitStatusService git = new GitStatusService()) {
                    if (steps.registerFirst()) {
                        indicator.setText("Adding " + inputs.repoName() + " to Drydock…");
                        plugin.sharedProjects().register(mainRoot, inputs.repoName())
                                .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }
                    if (steps.createWorktree()) {
                        indicator.setText("Creating worktree " + choice.branch() + "…");
                        git.createWorktree(mainRoot, steps.workingDirectory(), choice.branch(),
                                Optional.of(choice.start())).get(WORKTREE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    tracked.cancelled();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not start task " + choice.name(), e);
                    report(from, "Could not start \"" + choice.name() + "\": " + WorktreeErrors.visibleMessage(e));
                    tracked.failed(e);
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        Project target;
                        boolean fresh;
                        if (steps.openIn() == OpenIn.THIS_WINDOW) {
                            // A tab here; hosted when the folder is another
                            // project's. No window opens.
                            target = from;
                            fresh = false;
                        } else if (steps.reuseOpenWindow()) {
                            target = openWindow.get();
                            fresh = false;
                            ProjectUtil.focusProjectWindow(target, true);
                        } else {
                            target = TaskWindows.openAsProject(steps.workingDirectory());
                            fresh = true;
                        }
                        if (target == null || target.isDisposed()) {
                            LOG.log(Level.WARNING, "The window for {0} did not open", steps.workingDirectory());
                            Messages.showErrorDialog(from, windowDidNotOpen(steps.workingDirectory()), "Drydock");
                            tracked.windowDidNotOpen(steps.workingDirectory());
                            return;
                        }
                        // The observable result IS the launch chain's own
                        // future: launch claims, FAILED persistence and the
                        // RUNNING session id all complete it; this wrapper
                        // adds no outcome of its own. The bindings compose
                        // the pre-spawn bind step (T31a); an empty list is
                        // the byte-identical no-binding chain.
                        tracked.launched(bindings.isEmpty()
                                ? SessionDialogs.launchNewSession(target, steps.workingDirectory(), mainRoot,
                                        choice.agent(), choice.name(), choice.mode(),
                                        NewTaskPlan.initialPrompt(choice), choice.model(), choice.tiers(), fresh,
                                        preparedSession)
                                : SessionDialogs.launchNewSession(target, steps.workingDirectory(), mainRoot,
                                        choice.agent(), choice.name(), choice.mode(),
                                        NewTaskPlan.initialPrompt(choice), choice.model(), choice.tiers(), fresh,
                                        preparedSession, bindings));
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Could not open the task's window", e);
                        report(from, "Could not start \"" + choice.name() + "\": " + WorktreeErrors.visibleMessage(e));
                        tracked.failed(e);
                    }
                }, ModalityState.nonModal());
            }
        });
        return tracked.future();
    }

    /**
     * The remote half of one {@link #start} (T20): the whole box chain runs
     * inside the same {@code Task.Backgroundable} as the local half, with
     * per-step progress cleared on every exit (INV-8 — {@link #prepareBox}'s
     * finally covers refusal, cancel, failure and early return, and the
     * task's own end covers the rest), and the launch itself — {@link
     * SessionDialogs#launchNewSession} receiving the {@link WorkspaceBoxBinding}
     * — hops to the EDT like the local half's window open. No local worktree
     * is created and no project window opens: the console is an ssh client of
     * the box-side tmux session, shown as a tab in the window the launch
     * came from (the dialog's open-in choice is a local-workspace notion).
     *
     * <p>This is the ONLY path that creates or re-creates a box (INV-12):
     * Resume, Stop, Forget and startup never reach it, and nothing here
     * issues any mutating workspaces subcommand but {@code create}
     * (INV-11). With the flag off (INV-31) or a launch the dialog's gates
     * would have refused (INV-16), the launch is refused before a single
     * box command runs.</p>
     */
    static CompletableFuture<ManagedAgentSession> startRemote(Project from, NewTaskPlan.Inputs inputs,
                                                                Choice choice,
                                                                SessionDialogs.PreparedSessionCallback preparedSession,
                                                                List<TaskLaunchBinding> bindings) {
        TrackedLaunch tracked = new TrackedLaunch();
        if (!WorkspaceSettings.enabled()) {
            String reason = "Workspace sessions are turned off in Settings › Tools › Drydock";
            report(from, reason);
            tracked.failed(new IllegalStateException(reason));
            return tracked.future();
        }
        Optional<String> gated = remoteLaunchRefusal(choice.agent(), choice.workIn(), !bindings.isEmpty());
        if (gated.isPresent()) {
            report(from, gated.get());
            tracked.failed(new IllegalStateException(gated.get()));
            return tracked.future();
        }
        Path mainRoot = inputs.project().root();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting " + choice.name() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    // The stable RepositoryId the box mapping's keys hang
                    // off — the same register-first step the local half
                    // takes, so a remote row and its mapping agree.
                    Optional<SharedProject> registered = plugin.sharedProjects().byRoot(mainRoot);
                    if (registered.isEmpty()) {
                        indicator.setText("Adding " + inputs.repoName() + " to Drydock…");
                        registered = Optional.of(plugin.sharedProjects()
                                .register(mainRoot, inputs.repoName())
                                .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    }
                    RemoteBoxDeps deps = new RemoteBoxDeps(CommandRunner.PRODUCTION,
                            new WorkspacesCli(CommandRunner.PRODUCTION), productionBootstrap(),
                            settingsMapping(registered.get().id()), PRODUCTION_PROVISIONING,
                            message -> confirmRecreateOnEdt(from, message),
                            reason -> report(from, reason),
                            new ProgressSink() {
                                @Override
                                public void step(String text) {
                                    indicator.setText(text);
                                }

                                @Override
                                public void clear() {
                                    indicator.setText("");
                                }
                            }, false);
                    RemoteBoxRequest request = new RemoteBoxRequest(mainRoot, inputs.repoName(),
                            choice.workIn() == WorkIn.MAIN_CHECKOUT
                                    ? new BoxCheckout.Work.MainCheckout()
                                    : new BoxCheckout.Work.NewWorktree(choice.branch(),
                                            Optional.of(choice.start())));
                    switch (prepareBox(deps, request)) {
                        case RemoteBoxOutcome.RefusedBox(String reason) ->
                                // Already surfaced through the reporter.
                                tracked.failed(new IllegalStateException(reason));
                        case RemoteBoxOutcome.BoxCancelled ignored -> tracked.cancelled();
                        case RemoteBoxOutcome.ReadyBox(WorkspaceBoxBinding binding) ->
                                ApplicationManager.getApplication().invokeLater(() -> tracked.launched(
                                        SessionDialogs.launchNewSession(from,
                                                binding.sshRemote().placeholderRoot(), mainRoot,
                                                choice.agent(), choice.name(), choice.mode(),
                                                NewTaskPlan.initialPrompt(choice), choice.model(), choice.tiers(),
                                                false, preparedSession, bindings, Optional.of(binding))),
                                        ModalityState.nonModal());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    tracked.cancelled();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not start task " + choice.name() + " on a workspace box", e);
                    report(from, "Could not start \"" + choice.name() + "\" on a workspace box: "
                            + WorktreeErrors.visibleMessage(e));
                    tracked.failed(e);
                }
            }
        });
        return tracked.future();
    }

    /**
     * The production bootstrap seam: the real {@link ProxyBootstrap} on the
     * production runner, its typed refusal carried as this launcher's own
     * {@link BootstrapRefusal} (the reason text is the visible, secret-free
     * reason the bootstrap composed).
     */
    private static BoxBootstrap productionBootstrap() {
        return host -> {
            try {
                new ProxyBootstrap(CommandRunner.PRODUCTION).ensure(host);
            } catch (ProxyBootstrap.BootstrapException e) {
                throw new BootstrapRefusal(e.reason());
            }
        };
    }

    /**
     * The repo's box mapping bound to the {@code WorkspaceSettings} keys —
     * {@code .box} for the current box, {@code .clone} for the discovered
     * box clone — keyed by the registered repository's stable id, the same
     * key family the Workspace box dialog edits (ASM-17's escape hatch).
     */
    static BoxMapping settingsMapping(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repositoryId");
        return new BoxMapping() {
            @Override
            public Optional<String> box() {
                return WorkspaceSettings.box(repositoryId);
            }

            @Override
            public void recordBox(String boxName) {
                WorkspaceSettings.setBox(repositoryId, boxName);
            }

            @Override
            public Set<String> usedBoxes() {
                return new HashSet<>(WorkspaceSettings.usedBoxes(repositoryId));
            }

            @Override
            public BoxCheckout.CloneCache cloneCache(String boxName) {
                return new BoxCheckout.CloneCache() {
                    @Override
                    public Optional<String> cached() {
                        // Trusted only when recorded for THIS box: a fresh
                        // generation is served no path and discovers its own.
                        return WorkspaceSettings.clonePathFor(repositoryId, boxName);
                    }

                    @Override
                    public void store(String clonePath) {
                        WorkspaceSettings.recordDiscoveredClone(repositoryId, boxName, clonePath);
                    }
                };
            }
        };
    }

    /**
     * The destructive re-create confirmation on the EDT (INV-8: the box
     * chain blocks on a background thread, the dialog shows on the EDT):
     * the message already names the old box and the loss of its
     * conversations (AC-18); only an explicit Yes creates the new
     * generation.
     */
    private static boolean confirmRecreateOnEdt(Project from, String message) {
        AtomicBoolean confirmed = new AtomicBoolean();
        ApplicationManager.getApplication().invokeAndWait(() -> confirmed.set(
                Messages.showYesNoDialog(from, message, "Create a new workspace box?",
                        Messages.getQuestionIcon()) == Messages.YES), ModalityState.nonModal());
        return confirmed.get();
    }

    /**
     * The observable half of one {@link #start} (T22a): a single future
     * that every refusal, cancel, failure and disposed path completes --
     * never a permanently pending one -- and whose only source of the real
     * result is the launch chain itself ({@link #launched}): no wait, no
     * timeout and no cancel here fails or cancels the underlying launch,
     * so a slow launch can still complete it, late and accurately. The
     * one edge that can still leave the observable pending is an
     * invokeLater that never runs, which only happens at application
     * shutdown -- the same edge the session service documents for its
     * launch claims.
     */
    static final class TrackedLaunch {

        private final CompletableFuture<ManagedAgentSession> outcome = new CompletableFuture<>();

        /** The observable future callers wait on. */
        CompletableFuture<ManagedAgentSession> future() {
            return outcome;
        }

        /** The background half was interrupted: the launch never started, nothing failed. */
        void cancelled() {
            outcome.cancel(false);
        }

        /** A refusal or failure before the launch chain exists: registration or worktree. */
        void failed(Throwable failure) {
            outcome.completeExceptionally(failure);
        }

        /** The window the launch needs did not open (a canceled import, a disposed host). */
        void windowDidNotOpen(Path directory) {
            outcome.completeExceptionally(new IllegalStateException(TaskLauncher.windowDidNotOpen(directory)));
        }

        /**
         * The launch chain -- the only source of the real outcome: it
         * completes the observable exactly when the launch itself
         * completes, and nothing else does.
         */
        void launched(CompletableFuture<ManagedAgentSession> launch) {
            launch.whenComplete((session, failure) -> {
                if (failure != null) {
                    outcome.completeExceptionally(failure);
                } else {
                    outcome.complete(session);
                }
            });
        }
    }

    /** The open window showing {@code directory}, compared canonically. Blocking: background only. */
    private static Optional<Project> windowOn(Path directory) {
        Path wanted = RepositoryCatalog.canonicalize(directory);
        for (Project open : ProjectManager.getInstance().getOpenProjects()) {
            if (!open.isDisposed() && open.getBasePath() != null
                    && RepositoryCatalog.canonicalize(Path.of(open.getBasePath())).equals(wanted)) {
                return Optional.of(open);
            }
        }
        return Optional.empty();
    }

    /** The worktree exists but its window did not open (cancelled, or a failed import). */
    static String windowDidNotOpen(Path directory) {
        return "The worktree " + directory + " is ready, but its window did not open. "
                + "Open it with File › Open, or double-click the task in the Drydock tool window.";
    }

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }
}
