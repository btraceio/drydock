package app.drydock.intellij;

import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Probe tool window: proves the extracted headless engine (ProcessRunner +
 * GitStatusService) actually runs inside the IDE's JVM. Reads the branch/dirty
 * state of the project's own checkout off the EDT and prints it.
 */
public final class DrydockToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JLabel status = new JLabel("Detecting repository…");
        JButton refresh = new JButton("Refresh");
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(status, BorderLayout.NORTH);
        panel.add(refresh, BorderLayout.SOUTH);

        GitStatusService git = new GitStatusService();
        Runnable detect = () -> {
            status.setText("Detecting repository…");
            Path root = Path.of(project.getBasePath());
            CompletableFuture<GitStatus> future = Files.isDirectory(root.resolve(".git"))
                    ? git.getStatus(root)
                    : CompletableFuture.failedFuture(new IllegalStateException("Not a git repository: " + root));
            future.whenComplete((s, err) -> SwingUtilities.invokeLater(() -> {
                if (err != null) {
                    status.setText("No repository state: " + err.getMessage());
                } else {
                    status.setText(s.branch() + (s.dirty() ? "  (dirty)" : "  (clean)"));
                }
            }));
        };
        refresh.addActionListener(e -> detect.run());
        detect.run();
        toolWindow.getContentManager()
                .addContent(com.intellij.ui.content.ContentFactory.getInstance()
                        .createContent(panel, "Probe", false));
    }
}
