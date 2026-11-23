package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.InlineBanner;
import com.intellij.util.ui.JBUI;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The tool window's "Add open project" banner, under the header: while
 * this window's project is not registered it offers to add it, and once
 * added it confirms in the success colour until closed. Holds no state of
 * its own: the panel decides what it shows ({@link #state}). EDT.
 */
final class AddProjectBanner extends JPanel {

    /** What the banner shows. */
    sealed interface State {
    }

    /** Nothing: the project is registered and nothing was just added, or the offer was dismissed. */
    record Hidden() implements State {
    }

    /** "Open project <name> isn't in Drydock yet" with Add project and Not now. */
    record Offer(AddCurrentProject project) implements State {
        Offer {
            Objects.requireNonNull(project, "project");
        }
    }

    /** "<name> added · syncing issues & PRs" with Open in Projects, until closed. */
    record Added(Path root, String name) implements State {
        Added {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(name, "name");
        }
    }

    /** What the owner does with the banner's buttons. */
    interface Actions {
        void add(AddCurrentProject project);

        /** "Not now", or the offer's ×: the offer stays away for this window's project. */
        void dismiss();

        void openInProjects();

        /** The confirmation's ×. */
        void closeAdded();
    }

    private final Actions actions;
    private State shown = new Hidden();

    AddProjectBanner(Actions actions) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        setOpaque(false);
        setBorder(JBUI.Borders.empty(4, 8));
        setVisible(false);
    }

    /**
     * Pure: what the banner shows. A project just added is confirmed until
     * its confirmation is closed; otherwise an unregistered window project
     * is offered unless the offer was dismissed.
     */
    static State state(Optional<AddCurrentProject> unregistered, boolean dismissed, Optional<Added> justAdded) {
        if (justAdded.isPresent()) {
            return justAdded.get();
        }
        return unregistered.filter(any -> !dismissed).<State>map(Offer::new).orElse(new Hidden());
    }

    /** The state on screen. */
    State shown() {
        return shown;
    }

    /** Shows {@code next}; a state equal to the one on screen rebuilds nothing. */
    void show(State next) {
        if (next.equals(shown)) {
            return;
        }
        shown = next;
        removeAll();
        switch (next) {
            case Hidden ignored -> setVisible(false);
            case Offer offer -> {
                String name = StringUtil.escapeXmlEntities(offer.project().repoName());
                InlineBanner banner = new InlineBanner("<html>Open project <b>" + name + "</b> isn't in Drydock yet</html>",
                        EditorNotificationPanel.Status.Info);
                banner.setIcon(AllIcons.Actions.NewFolder);
                banner.addAction("Add project", () -> actions.add(offer.project()));
                banner.addAction("Not now", actions::dismiss);
                banner.setCloseAction(actions::dismiss);
                add(banner, BorderLayout.CENTER);
                setVisible(true);
            }
            case Added added -> {
                String name = StringUtil.escapeXmlEntities(added.name());
                InlineBanner banner = new InlineBanner("<html><b>" + name + "</b> added · syncing issues &amp; PRs</html>",
                        EditorNotificationPanel.Status.Success);
                banner.addAction("Open in Projects", actions::openInProjects);
                banner.setCloseAction(actions::closeAdded);
                add(banner, BorderLayout.CENTER);
                setVisible(true);
            }
        }
        revalidate();
        repaint();
    }
}
