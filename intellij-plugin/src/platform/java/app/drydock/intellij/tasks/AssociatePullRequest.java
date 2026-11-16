package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.git.GhCliService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** The human-only association action; neither session titles nor conversation text are PR identities. */
final class AssociatePullRequest {
    private static final Logger LOG = System.getLogger(AssociatePullRequest.class.getName());
    private static final Set<ManagedSessionId> BUSY = ConcurrentHashMap.newKeySet();

    private AssociatePullRequest() { }

    static boolean available(ManagedSessionId session) {
        return !BUSY.contains(session);
    }

    static Optional<Integer> number(String text) {
        String value = text.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!value.matches("#?[1-9][0-9]*")) {
            throw new IllegalArgumentException("Use a positive PR number, or leave blank to clear");
        }
        return Optional.of(Integer.parseInt(value.startsWith("#") ? value.substring(1) : value));
    }

    static String displayMessage(Throwable cause) {
        String message = cause.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        String className = cause.getClass().getSimpleName();
        return className.isBlank() ? "An unexpected error occurred." : className;
    }

    static void show(Project project, ManagedAgentSession session) {
        String answer = Messages.showInputDialog(project,
                "PR number in this session's repository (leave blank to use branch discovery):",
                "Associate with PR", Messages.getQuestionIcon(),
                session.targetPullRequest().map(target -> String.valueOf(target.number())).orElse(""),
                new InputValidator() {
                    @Override
                    public boolean checkInput(String input) {
                        try {
                            number(input);
                            return true;
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    }

                    @Override
                    public boolean canClose(String input) {
                        return checkInput(input);
                    }
                });
        if (answer == null || !BUSY.add(session.id())) {
            return;
        }
        Optional<Integer> number = number(answer);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Associating pull request…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    Optional<PullRequestTarget> target = Optional.empty();
                    if (number.isPresent()) {
                        DrydockPluginService plugin = DrydockPluginService.getInstance();
                        Path root = plugin.stateStore().state().repositories().stream()
                                .filter(repo -> repo.id().equals(session.repositoryId()))
                                .findFirst().orElseThrow(() -> new IllegalStateException("Repository no longer exists"))
                                .root();
                        Optional<String> repository = plugin.sharedProjects().byRoot(root)
                                .map(shared -> GitHubProjectSettings.load(shared.id()).repository())
                                .orElse(Optional.empty());
                        try (GhCliService github = new GhCliService()) {
                            var pr = github.pullRequest(root, repository, number.get()).get(20, TimeUnit.SECONDS)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Could not validate PR #" + number.get() + "; association unchanged"));
                            target = Optional.of(PullRequestTarget.fromUrl(pr.url().orElseThrow(
                                    () -> new IllegalStateException("GitHub returned no PR URL"))));
                        }
                    }
                    DrydockStartupActivity.sessions(project).associatePullRequest(session.id(), target)
                            .get(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Could not associate PR with session " + session.id(), e);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()) {
                            Messages.showErrorDialog(project, displayMessage(cause), "Could Not Associate PR");
                        }
                    }, ModalityState.nonModal());
                } finally {
                    BUSY.remove(session.id());
                }
            }
        });
    }
}
