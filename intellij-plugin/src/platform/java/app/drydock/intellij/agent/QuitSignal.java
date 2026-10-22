package app.drydock.intellij.agent;

import app.drydock.intellij.tasks.TaskWindows;

import com.intellij.ide.AppLifecycleListener;

import java.util.List;

/** Restores Drydock task windows at startup and marks the beginning of an IDE quit. */
public final class QuitSignal implements AppLifecycleListener {

    /**
     * Once, at startup, before the application frame shows. Not {@code
     * appStarted()}: that method is internal API.
     */
    @Override
    public void appFrameCreated(List<String> commandLineArgs) {
        TaskWindows.restoreRemembered();
    }

    @Override
    public void appClosing() {
        AgentCloseConfirmation.quitStarted();
    }
}
