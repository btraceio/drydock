package app.drydock.intellij.projects;

import app.drydock.domain.SshRemote;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.SshUnreachableException;
import app.drydock.ssh.SshConfigHosts;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;

import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Add remote repository…" (spec: SSH remote repositories): pick or type an
 * SSH host, type the repo path on that host, validate over ssh (via
 * {@link GitStatusService#resolveRemoteRepositoryRoot}, which runs {@code git
 * rev-parse --show-toplevel} in BatchMode), register on success.
 * Registration lands in {@link SharedProjectsService#registerRemote}, like
 * every other entry.
 *
 * <p>The validation is a real ssh round-trip, so it runs off the EDT and the
 * OK button stays down ("Validating…") until it concludes; every exit path —
 * success, classified failure, or cancel — restores the button. Failures are
 * classified from the exit code and stderr: host key, auth, git not on the
 * non-interactive PATH, not a repository, unreachable.</p>
 */
public final class RemoteRepositoryDialog extends DialogWrapper {

    private static final long VALIDATE_TIMEOUT_SECONDS = 30;

    private final SharedProjectsService sharedProjects;

    private final ComboBox<String> hostBox = new ComboBox<>();
    private final JBTextField pathField = new JBTextField();
    private final JBLabel errorLine = new JBLabel();

    public RemoteRepositoryDialog(Project project, SharedProjectsService sharedProjects) {
        super(project, true);
        this.sharedProjects = sharedProjects;
        setTitle("Add Remote Repository");
        setOKButtonText("Add");
        hostBox.setEditable(true);
        hostBox.setModel(new DefaultComboBoxModel<>(SshConfigHosts.loadUserHosts().toArray(String[]::new)));
        errorLine.setForeground(UIUtil.getErrorForeground());
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel hint = new JBLabel(
                "<html>Requires git and claude on the host's non-interactive PATH, "
                        + "a POSIX login shell, and an already-accepted host key.<br>"
                        + "Hosts from Include'd config files aren't listed — type a host.</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Host:", hostBox)
                .addLabeledComponent("Repository path:", pathField)
                .addComponent(hint)
                .addComponent(errorLine)
                .getPanel();
    }

    @Override
    protected void doOKAction() {
        String host = hostBox.getEditor().getItem() == null ? "" : hostBox.getEditor().getItem().toString().strip();
        String path = pathField.getText() == null ? "" : pathField.getText().strip();
        SshRemote candidate;
        try {
            candidate = new SshRemote(host, path);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        if (sharedProjects.byRoot(candidate.placeholderRoot()).isPresent()) {
            showError("Host " + candidate.host() + " path " + candidate.remotePath()
                    + " is already registered with Drydock.");
            return;
        }
        setOKButtonText("Validating…");
        setOKActionEnabled(false);
        validateAndAdd(candidate);
    }

    /**
     * The add flow's ssh round-trip, off the EDT: resolve the candidate's
     * toplevel, register with the resolved path, close. A failure surfaces
     * its classified message and hands control back.
     */
    private void validateAndAdd(SshRemote candidate) {
        CompletableFuture.runAsync(() -> {
            try (GitStatusService git = new GitStatusService()) {
                String topLevel = git.resolveRemoteRepositoryRoot(candidate)
                        .get(VALIDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                sharedProjects.registerRemote(new SshRemote(candidate.host(), topLevel))
                        .get(VALIDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The remote repository validation was cancelled", e);
            } catch (Exception failure) {
                throw failure instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException(failure);
            }
        }).whenComplete((ignored, failure) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (isDisposed()) {
                return;
            }
            if (failure != null) {
                showError(userMessage(unwrap(failure)));
                return;
            }
            close(OK_EXIT_CODE);
        }));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void showError(String message) {
        setOKActionEnabled(true);
        setOKButtonText("Add");
        errorLine.setText(message);
    }

    /** Maps validation failures to the spec's specific, actionable messages. */
    static String userMessage(Throwable failure) {
        if (failure instanceof SshUnreachableException unreachable) {
            String stderr = unreachable.stderr().toLowerCase(Locale.ROOT);
            if (stderr.contains("host key verification failed")) {
                return "This machine hasn't accepted the host's key yet. Run `ssh " + unreachable.host()
                        + "` once in a terminal to accept it, then retry.";
            }
            if (stderr.contains("permission denied")) {
                return "SSH authentication failed. Check ssh-agent and ~/.ssh/config for " + unreachable.host() + ".";
            }
            return "Could not reach " + unreachable.host() + ": " + unreachable.stderr();
        }
        if (failure instanceof NotAGitRepositoryException) {
            return "That path is not a git repository on the host.";
        }
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (message.toLowerCase(Locale.ROOT).contains("command not found")
                || message.toLowerCase(Locale.ROOT).contains("git: not found")) {
            return "git was not found on the host's non-interactive PATH "
                    + "(ssh host git … does not source .bashrc/.profile).";
        }
        return message;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return (JComponent) hostBox.getEditor().getEditorComponent();
    }
}
