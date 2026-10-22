package app.drydock.intellij.agent;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;

/** A compact file list for task changes with two explicit comparison scopes. */
final class TaskChangesPanel extends SimpleToolWindowPanel implements Disposable {

    private static final System.Logger LOG = System.getLogger(TaskChangesPanel.class.getName());
    private final Project project;
    private final DefaultListModel<ChangedFile> files = new DefaultListModel<>();
    private final JBList<ChangedFile> list = new JBList<>(files);
    private final JLabel detail = new JLabel("Select Changes on a Drydock session", SwingConstants.LEADING);
    private TaskChanges.Prepared prepared;
    private int selectedScope;

    TaskChangesPanel(Project project) {
        super(true, true);
        this.project = project;

        list.setCellRenderer(new Renderer());
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        new ListSpeedSearch<>(list, file -> file.name() + " " + file.location());
        list.addListSelectionListener(event -> selectionChanged());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                    openSelected();
                    event.consume();
                }
            }
        });

        DefaultActionGroup scopes = new DefaultActionGroup();
        scopes.add(scopeAction(0, "Current vs Base", "Commits and local edits compared with the task base",
                AllIcons.Vcs.Branch));
        scopes.add(scopeAction(1, "Uncommitted", "Staged, unstaged, and untracked changes compared with HEAD",
                AllIcons.Actions.Edit));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                "Drydock.TaskChanges.Scopes", scopes, true);
        toolbar.setTargetComponent(this);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(toolbar.getComponent(), BorderLayout.NORTH);
        header.add(detail, BorderLayout.SOUTH);
        setToolbar(header);
        setContent(ScrollPaneFactory.createScrollPane(list));
    }

    void show(TaskChanges.Prepared value) {
        LOG.log(System.Logger.Level.INFO, () -> "Drydock Changes panel received data: project="
                + project.getName() + ", task=" + value.taskName() + ", scopes=" + value.scopes().stream()
                .map(scope -> scope.label() + "=" + scope.changes().size() + "/" + scope.discoveredFiles())
                .toList());
        prepared = value;
        selectedScope = 0;
        rebuild();
    }

    void showLoading(String message) {
        prepared = null;
        files.clear();
        detail.setText(message);
        detail.setToolTipText(null);
        list.getEmptyText().setText("Loading changes…");
    }

    void showRestoreFailure(String message) {
        prepared = null;
        files.clear();
        detail.setText("Could not restore changes");
        detail.setToolTipText(message);
        list.getEmptyText().setText(message);
    }

    private ToggleAction scopeAction(int index, String text, String description, javax.swing.Icon icon) {
        return new ToggleAction(text, description, icon) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent event) {
                return selectedScope == index;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent event, boolean state) {
                if (state && prepared != null && prepared.scopes().size() > index) {
                    selectedScope = index;
                    rebuild();
                }
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                super.update(event);
                event.getPresentation().setEnabled(prepared != null && prepared.scopes().size() > index);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private void rebuild() {
        if (prepared == null || prepared.scopes().size() <= selectedScope) {
            return;
        }
        TaskChanges.ScopeView selected = prepared.scopes().get(selectedScope);
        files.clear();
        for (int index = 0; index < selected.changes().size(); index++) {
            Change change = selected.changes().get(index);
            Path relative = relativePath(change);
            Path parent = relative.getParent();
            files.addElement(new ChangedFile(relative.getFileName().toString(),
                    parent == null ? "" : parent.toString(), change, index));
        }
        String count = selected.changes().size() + (selected.changes().size() == 1 ? " file" : " files");
        LOG.log(System.Logger.Level.INFO, () -> "Drydock Changes list rebuilt: project=" + project.getName()
                + ", task=" + prepared.taskName() + ", scope=" + selected.label()
                + ", displayed=" + selected.changes().size()
                + ", discovered=" + selected.discoveredFiles());
        detail.setText(prepared.taskName() + " — " + count);
        detail.setToolTipText(selected.description());
        list.getEmptyText().setText(selected.discoveredFiles() == 0
                ? "No changes in this scope"
                : "IntelliJ cannot display the changes in this scope");
    }

    private Path relativePath(Change change) {
        ContentRevision revision = change.getAfterRevision() != null
                ? change.getAfterRevision() : change.getBeforeRevision();
        Path absolute = revision == null ? prepared.checkout() : revision.getFile().getIOFile().toPath();
        try {
            return prepared.checkout().relativize(absolute.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ignored) {
            return absolute.getFileName();
        }
    }

    private void selectionChanged() {
        ChangedFile file = list.getSelectedValue();
        if (file != null) {
            detail.setText(prepared.taskName() + " — "
                    + (file.location().isEmpty() ? file.name() : file.location() + "/" + file.name()));
        }
    }

    private void openSelected() {
        ChangedFile file = list.getSelectedValue();
        if (file == null || prepared == null || prepared.scopes().size() <= selectedScope) {
            return;
        }
        TaskChanges.ScopeView selected = prepared.scopes().get(selectedScope);
        if (ShowDiffAction.canShowDiff(project, selected.changes())) {
            ShowDiffAction.showDiffForChange(project, selected.changes(), file.index());
        }
    }

    @Override
    public void dispose() {
    }

    private record ChangedFile(String name, String location, Change change, int index) {
    }

    private static final class Renderer extends ColoredListCellRenderer<ChangedFile> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends ChangedFile> list, ChangedFile file,
                                             int index, boolean selected, boolean hasFocus) {
            if (file != null) {
                setIcon(AllIcons.FileTypes.Any_type);
                append(file.name());
                if (!file.location().isEmpty()) {
                    append("  " + file.location(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        }
    }
}
