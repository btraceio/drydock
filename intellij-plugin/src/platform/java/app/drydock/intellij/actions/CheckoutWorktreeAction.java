package app.drydock.intellij.actions;

import app.drydock.domain.Repository;
import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchCheckout;
import app.drydock.git.BranchNameRules;
import app.drydock.git.BranchRef;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.worktree.WorktreeDialogs;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.intellij.worktree.WorktreeListPanel;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.ComboBoxEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * "Check out branch in worktree…" (T15, AC24): loads the core's {@link
 * BranchCatalog} off the EDT with visible progress, shows a branch picker
 * whose rows render {@link BranchCheckout#dropdownLabel} and whose choice is
 * classified by {@link BranchCheckout#resolve} (the app tree's one
 * classifier, reused — never a second notion of "which branch did the text
 * mean"), and on OK runs {@link GitStatusService#addWorktreeForBranch}
 * off the EDT with progress. Errors surface through the shared {@link
 * WorktreeErrors} mapping with the stderr excerpt (AC26); after success the
 * just-checked-out worktree can be opened as its own project (T14's
 * offer, reused) and the worktree list panel refreshes (coalesced, not
 * per-event).
 *
 * <p>Pure seams: {@link #checkoutRequest} — the only pure gate between the
 * picker and the git service, so "an occupied or unknown branch never
 * invokes git" is pinned by the test without opening a dialog — {@link
 * #hintFor}, {@link #directoryDefault}, and {@link #pickerRows}; none of
 * them touches Swing or the IDE.</p>
 *
 * <p>Keyboard access (AGENTS.md): a toolbar action renders as a real
 * focusable button, so Enter/Space trigger {@link #actionPerformed} like a
 * click.</p>
 */
public final class CheckoutWorktreeAction extends AnAction {

    private static final Logger LOG = System.getLogger(CheckoutWorktreeAction.class.getName());

    /** Bounded wait for the dialog's inputs (the catalog loads two git commands). */
    private static final long PREPARE_TIMEOUT_SECONDS = 30;

    /** Bounded wait for the checkout; git's own per-command timeout is far lower. */
    private static final long CHECKOUT_TIMEOUT_SECONDS = 60;

    public CheckoutWorktreeAction() {
        super("Check out branch in worktree…", "Check out an existing branch in a new worktree", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        showCheckout(project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
    }

    // ---- pure seams (no Swing, no IDE) -----------------------------------------

    /** The validated checkout inputs; the only thing that reaches the git service. */
    public record CheckoutRequest(BranchRef branch, String localName, Path directory) {
    }

    /**
     * One picker row: the value the combo carries (the branch's plain name,
     * so typed text and resolution agree) paired with the rendered {@link
     * BranchCheckout#dropdownLabel}.
     */
    public record PickerRow(BranchRef branch, String label) {
    }

    /**
     * What the OK button may ask git to do, or empty when it must not
     * (AC24): picker text that resolves to anything but a {@code Ready}
     * branch — unknown text, a branch already checked out somewhere, a
     * remote ref whose local name must be refused — or a blank directory
     * never reaches {@code addWorktreeForBranch}. Pure and service-free by
     * construction, exactly like T14's create request.
     */
    public static Optional<CheckoutRequest> checkoutRequest(BranchCatalog catalog, String branchText,
            String directoryText) {
        String directory = directoryText == null ? "" : directoryText.strip();
        if (!(BranchCheckout.resolve(catalog, branchText) instanceof BranchCheckout.Outcome.Ready ready)
                || directory.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CheckoutRequest(ready.ref(), ready.localName(), Path.of(directory)));
    }

    /**
     * The live hint under the picker: empty when the typed text names a
     * branch that can be checked out; otherwise the sentence that explains
     * the verdict in the user's register — the occupied row names where the
     * branch is held, the unmintable one reuses the core's human sentence,
     * the remote one names the local branch it would create and track.
     */
    public static String hintFor(BranchCatalog catalog, String branchText) {
        return switch (BranchCheckout.resolve(catalog, branchText)) {
            case BranchCheckout.Outcome.Ready ready -> ready.tracking()
                    .map(tracking -> "Will create local " + ready.localName() + " tracking " + tracking + ".")
                    .orElse("");
            case BranchCheckout.Outcome.NoSuchBranch missing ->
                "No branch named '" + missing.text() + "' exists here; New Worktree… can create it.";
            case BranchCheckout.Outcome.Occupied occupied -> occupied.ref().name() + " is already checked out at "
                    + occupied.ref().checkedOutAt().map(Path::toString).orElse("another worktree") + ".";
            case BranchCheckout.Outcome.Unmintable unmintable ->
                BranchNameRules.humanSentence(unmintable.refusal());
        };
    }

    /**
     * The directory default for the picker's current text: the same {@code
     * <home>/dev/wt/<repo>-<branch-slug>} derivation and the same
     * stops-after-a-manual-edit rule as T14's create dialog (via {@link
     * WorktreeDialogs#directoryFor}, reused), with the branch being the
     * <em>resolved local name</em> of the picked branch — a remote {@code
     * origin/feat/x} defaults a directory for {@code feat/x}, because that
     * is the branch the worktree will actually hold.
     */
    public static String directoryDefault(BranchCatalog catalog, String branchText, String currentDirectory,
            boolean directoryEdited, Path home, String repositoryName) {
        if (directoryEdited) {
            return currentDirectory == null ? "" : currentDirectory;
        }
        return WorktreeDialogs.directoryFor(resolvedLocalName(catalog, branchText), currentDirectory, false,
                home, repositoryName);
    }

    /** The picked branch's local name, or the stripped text when nothing resolves yet. */
    private static String resolvedLocalName(BranchCatalog catalog, String branchText) {
        return switch (BranchCheckout.resolve(catalog, branchText)) {
            case BranchCheckout.Outcome.Ready ready -> ready.localName();
            default -> branchText == null ? "" : branchText.strip();
        };
    }

    /**
     * The picker's rows: every catalog branch with its {@link
     * BranchCheckout#dropdownLabel} — free branches show the plain name,
     * occupied ones say where the branch is held, unmintable ones say why.
     */
    public static List<PickerRow> pickerRows(BranchCatalog catalog) {
        return catalog.branches().stream()
                .map(ref -> new PickerRow(ref,
                        BranchCheckout.dropdownLabel(ref, BranchCheckout.unmintable(catalog, ref))))
                .toList();
    }

    // ---- the flow ---------------------------------------------------------------

    /**
     * Opens the checkout dialog. Immediately visible progress (AC12): the
     * inputs — the project's repository and the branch catalog (two git
     * commands: {@code branch -a} and {@code worktree list}) — load off the
     * EDT inside a background task; every early return (timeout, init
     * failure, non-git project) ends the task, which clears it.
     */
    private static void showCheckout(Project project) {
        if (project.getBasePath() == null) {
            Messages.showInfoMessage(project,
                    "This project has no directory, so Drydock cannot create a worktree for it.",
                    "Drydock");
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading branches…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                prepareCheckout(project);
            }
        });
    }

    private static void prepareCheckout(Project project) {
        Repository repository;
        BranchCatalog catalog;
        try {
            DrydockPluginService plugin = DrydockPluginService.getInstance();
            plugin.initialization().get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            repository = DrydockStartupActivity.repositories(project)
                    .projectRepository(project).get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (repository == null) {
                LOG.log(Level.WARNING,
                        "Project {0} has no Drydock repository; cannot offer a checkout", project.getName());
                return;
            }
            try (GitStatusService git = new GitStatusService();
                    WorktreeService worktrees = new WorktreeService()) {
                catalog = BranchCatalog.load(git, worktrees, repository.root())
                        .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException e) {
            reportFailure(project, "Could not load branches", e);
            return;
        }
        BranchCatalog loaded = catalog;
        Repository repo = repository;
        ApplicationManager.getApplication().invokeLater(
                () -> new CheckoutDialog(project, repo, loaded).show(), ModalityState.any());
    }

    /** The checkout dialog: branch picker with live classification + editable directory field. */
    private static final class CheckoutDialog extends DialogWrapper {

        private final Project project;
        private final Repository repository;
        private final BranchCatalog catalog;
        private final Map<String, String> labels = new HashMap<>();
        private final JComboBox<String> branchPicker = new JComboBox<>();
        private final JTextField directoryField = new JTextField();
        private final JLabel hintLabel = new JLabel();
        private boolean directoryEdited;
        /** Programmatic directory updates must not count as the user's edit. */
        private boolean updatingDirectory;

        CheckoutDialog(Project project, Repository repository, BranchCatalog catalog) {
            super(project);
            this.project = project;
            this.repository = repository;
            this.catalog = catalog;
            setTitle("Check out branch in a worktree");
            // The combo's values are the branches' plain names, so typed
            // text, the selected value, and BranchCheckout.resolve all speak
            // the same string; the popup rows render the dropdown labels.
            for (PickerRow row : pickerRows(catalog)) {
                branchPicker.addItem(row.branch().name());
                labels.put(row.branch().name(), row.label());
            }
            branchPicker.setEditable(true);
            // The popup rows render the dropdown labels; the combo's value
            // (what the editor holds and what resolve sees) stays the plain
            // branch name — selection sets the editor text from the item,
            // not from the rendered label.
            branchPicker.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean selected, boolean focused) {
                    Component rendered = super.getListCellRendererComponent(list, value, index, selected, focused);
                    setText(labels.getOrDefault(String.valueOf(value), String.valueOf(value)));
                    return rendered;
                }
            });
            ComboBoxEditor editor = branchPicker.getEditor();
            ((JTextComponent) editor.getEditorComponent()).getDocument()
                    .addDocumentListener(refreshing(event -> onInputsChanged()));
            branchPicker.addActionListener(event -> onInputsChanged());
            directoryField.getDocument().addDocumentListener(refreshing(event -> {
                if (!updatingDirectory) {
                    directoryEdited = true;
                }
            }));
            directoryField.setToolTipText(
                    "Where the worktree is created. Defaults to <home>/dev/wt/<repository>-<branch-slug> "
                            + "until edited.");
            init();
            onInputsChanged();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
            form.add(new JLabel("Branch"));
            form.add(branchPicker);
            form.add(new JLabel("Worktree directory"));
            form.add(directoryField);
            form.add(hintLabel);
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.add(form, BorderLayout.NORTH);
            panel.setPreferredSize(new Dimension(440, panel.getPreferredSize().height));
            return panel;
        }

        private String pickerText() {
            Object value = branchPicker.getEditor().getItem();
            return value == null ? "" : value.toString();
        }

        private void onInputsChanged() {
            String text = pickerText();
            hintLabel.setText(hintFor(catalog, text));
            updatingDirectory = true;
            directoryField.setText(directoryDefault(catalog, text, directoryField.getText(), directoryEdited,
                    Path.of(System.getProperty("user.home")), repository.displayName()));
            updatingDirectory = false;
            setOKActionEnabled(checkoutRequest(catalog, text, directoryField.getText()).isPresent());
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            String text = pickerText();
            String hint = hintFor(catalog, text);
            if (!hint.isEmpty()) {
                return new ValidationInfo(hint, branchPicker);
            }
            if (directoryField.getText().strip().isEmpty()) {
                return new ValidationInfo("Choose a directory for the worktree", directoryField);
            }
            return null;
        }

        @Override
        protected void doOKAction() {
            Optional<CheckoutRequest> request =
                    checkoutRequest(catalog, pickerText(), directoryField.getText());
            if (request.isEmpty()) {
                return; // doValidate explains; never run git on a refused choice
            }
            super.doOKAction();
            checkout(project, repository.root(), request.get());
        }
    }

    /**
     * Runs the checkout with visible progress (AC24/AC12): the git work
     * runs on the service's executor; this background task only waits,
     * bounded, and every path — success, failure, timeout, interrupt — ends
     * it, which clears the progress. A failure surfaces the {@link
     * WorktreeErrors} message with the stderr excerpt (AC26), never a
     * silent no-op.
     */
    private static void checkout(Project project, Path repositoryRoot, CheckoutRequest request) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Checking out " + request.localName() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path created;
                try (GitStatusService git = new GitStatusService()) {
                    created = git.addWorktreeForBranch(repositoryRoot, request.directory(),
                            request.branch(), request.localName())
                            .get(CHECKOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException e) {
                    reportFailure(project, "Could not check out " + request.localName(), e);
                    return;
                }
                // The panel refresh is coalesced and EDT-hopping; the offer
                // reuses T14's post-create flow verbatim (AC25).
                WorktreeListPanel.refreshFor(project);
                WorktreeDialogs.offerOpenAsProject(project, created);
            }
        });
    }

    /** The failure the user sees (AC26): WorktreeErrors' mapped text, cause unwrapped. */
    private static void reportFailure(Project project, String title, Throwable failure) {
        LOG.log(Level.WARNING, title + ": " + failure);
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project,
                        title + ": " + WorktreeErrors.visibleMessage(failure), "Drydock"),
                ModalityState.any());
    }

    /** A {@link DocumentListener} with one body for all three callbacks. */
    private static DocumentListener refreshing(Consumer<DocumentEvent> body) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                body.accept(event);
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                body.accept(event);
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                body.accept(event);
            }
        };
    }
}
