package app.drydock.intellij.tasks;

import app.drydock.intellij.DrydockPluginService;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.SplitButtonAction;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The tool window's {@code ＋ New Session} toolbar button with a project
 * dropdown. The main half is the registered {@code Drydock.NewTask} action,
 * permanently: {@link #getMainAction} always answers it and
 * {@link #useDynamicSplitButton} is off, so picking a project from the arrow
 * never retargets the next click — that and the shortcut (which dispatches
 * to the registered action directly) stay on this window's project.
 *
 * <p>The arrow lists the shared registry's current snapshot, read fresh per
 * invocation off the EDT, and starts the existing launcher in the picked
 * project's main checkout ({@link TaskLauncher#openInMainCheckout}). The
 * popup shows a loading entry at once; a late answer fills only the popup it
 * was asked for, while it is still open and its project still live.</p>
 */
final class NewSessionSplitAction extends SplitButtonAction {

    private static final Logger LOG = System.getLogger(NewSessionSplitAction.class.getName());

    static final String LOADING = "Loading registered projects…";
    static final String EMPTY = "No registered projects";
    static final String FAILED = "Could not list registered projects";
    /** Bounded like the launcher's own wait on the plugin's initialization. */
    private static final long INITIALIZATION_TIMEOUT_SECONDS = 30;

    /**
     * One dropdown entry: its text, the project it starts in (bound to the
     * record, never resolved again by its text), and why it is disabled.
     */
    record Entry(String text, Optional<SharedProject> target, Optional<String> disabledReason) {
        Entry {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(disabledReason, "disabledReason");
        }

        boolean enabled() {
            return target.isPresent() && disabledReason.isEmpty();
        }
    }

    /** Opens the existing launcher for a picked project: the seam a test records. */
    interface Launcher {
        void open(Project from, Path mainRoot);
    }

    private final AnAction primary;
    private final Callable<List<SharedProject>> listing;
    private final Executor background;
    private final Launcher launcher;
    /** Shows the popup under the button: the seam a headless test replaces. */
    private final BiConsumer<JPopupMenu, Component> display;

    NewSessionSplitAction(AnAction primary) {
        this(primary, NewSessionSplitAction::currentProjects,
                command -> DrydockPluginService.getInstance().executor().execute(command),
                TaskLauncher::openInMainCheckout,
                (menu, anchor) -> menu.show(anchor, 0, anchor.getHeight()));
    }

    NewSessionSplitAction(AnAction primary, Callable<List<SharedProject>> listing, Executor background,
                          Launcher launcher, BiConsumer<JPopupMenu, Component> display) {
        super(new DefaultActionGroup(Objects.requireNonNull(primary, "primary")));
        this.primary = primary;
        this.listing = Objects.requireNonNull(listing, "listing");
        this.background = Objects.requireNonNull(background, "background");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.display = Objects.requireNonNull(display, "display");
        // Read from, never written to: the registered action's own template stays its own.
        getTemplatePresentation().setText(primary.getTemplatePresentation().getText());
        getTemplatePresentation().setDescription(primary.getTemplatePresentation().getDescription());
        getTemplatePresentation().setIcon(primary.getTemplatePresentation().getIcon());
    }

    /**
     * Pure: one entry per registered project, in the registry's order, by
     * display name — duplicates stay separate entries bound to their own
     * record. A remote (SSH) project is enabled like a local one: its task
     * dialog runs in the repository itself, over ssh, and the launcher
     * refuses it visibly when something else refuses (a local entry stays
     * enabled even if its folder is gone for the same reason). No projects:
     * one disabled {@link #EMPTY} entry.
     */
    static List<Entry> projectEntries(List<SharedProject> projects) {
        if (projects.isEmpty()) {
            return List.of(new Entry(EMPTY, Optional.empty(), Optional.of(EMPTY)));
        }
        List<Entry> entries = new ArrayList<>(projects.size());
        for (SharedProject project : projects) {
            entries.add(new Entry(project.displayName(), Optional.of(project), Optional.empty()));
        }
        return List.copyOf(entries);
    }

    /** The shared registry's current snapshot, after the plugin's bounded initialization. Off the EDT. */
    static List<SharedProject> currentProjects() throws Exception {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.initialization().get(INITIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return plugin.sharedProjects().projects();
    }

    @Override
    protected AnAction getMainAction(@NotNull AnActionEvent e) {
        return primary;
    }

    @Override
    protected boolean useDynamicSplitButton() {
        return false;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** The arrow: our own popup, so the platform shows no fallback group (returns true). EDT. */
    @Override
    protected boolean showPopup(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return true;
        }
        InputEvent input = e.getInputEvent();
        Component anchor = input != null ? input.getComponent()
                : e.getPresentation().getClientProperty(CustomComponentAction.COMPONENT_KEY);
        if (anchor == null) {
            return false;
        }
        openPicker(project, anchor);
        return true;
    }

    /** Shows the loading popup at once, then lists off the EDT and fills it on the EDT. */
    Picker openPicker(Project project, Component anchor) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        Picker picker = new Picker(project);
        display.accept(picker.menu, anchor);
        ModalityState modality = ModalityState.stateForComponent(anchor);
        CompletableFuture<List<Entry>> entries;
        try {
            entries = CompletableFuture.supplyAsync(() -> {
                try {
                    return projectEntries(listing.call());
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, background);
        } catch (RejectedExecutionException shutDown) {
            LOG.log(Level.WARNING, "Drydock could not list the registered projects", shutDown);
            picker.fail(shutDown);
            return picker;
        }
        entries.whenComplete((listed, failure) -> {
            if (failure != null) {
                LOG.log(Level.WARNING, "Drydock could not list the registered projects", failure);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (failure != null) {
                    picker.fail(failure);
                } else {
                    picker.fill(listed);
                }
            }, modality);
        });
        return picker;
    }

    /** One arrow invocation's popup; never reopened once dismissed. */
    final class Picker {
        final JBPopupMenu menu = new JBPopupMenu();
        private final Project project;
        private boolean dismissed;

        private Picker(Project project) {
            this.project = project;
            JBMenuItem loading = new JBMenuItem(LOADING);
            loading.setEnabled(false);
            menu.add(loading);
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                    dismissed = true;
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent event) {
                    dismissed = true;
                }
            });
        }

        /** Still worth updating: open, and its project not closed. EDT. */
        boolean live() {
            return !dismissed && !project.isDisposed();
        }

        /** False when the answer is moot; a popup whose project closed is hidden, not left loading. */
        private boolean accepting() {
            if (live()) {
                return true;
            }
            if (!dismissed) {
                menu.setVisible(false);
            }
            return false;
        }

        private void fill(List<Entry> entries) {
            if (!accepting()) {
                return;
            }
            menu.removeAll();
            for (Entry entry : entries) {
                JBMenuItem item = new JBMenuItem(entry.text());
                item.setEnabled(entry.enabled());
                entry.disabledReason().or(() -> entry.target().map(target -> target.root().toString()))
                        .ifPresent(item::setToolTipText);
                entry.target().filter(target -> entry.enabled()).ifPresent(target ->
                        item.addActionListener(event -> choose(target)));
                menu.add(item);
            }
            repack();
        }

        private void fail(Throwable failure) {
            if (!accepting()) {
                return;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            menu.removeAll();
            JBMenuItem item = new JBMenuItem(FAILED);
            item.setEnabled(false);
            item.setToolTipText(String.valueOf(cause.getMessage()));
            menu.add(item);
            repack();
        }

        private void choose(SharedProject target) {
            if (project.isDisposed()) {
                return;
            }
            launcher.open(project, target.root());
        }

        private void repack() {
            menu.revalidate();
            if (menu.isVisible()) {
                menu.pack();
            }
        }
    }
}
