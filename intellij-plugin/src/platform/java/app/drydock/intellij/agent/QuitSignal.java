package app.drydock.intellij.agent;

import app.drydock.intellij.tasks.TaskWindows;

import com.intellij.ide.AppLifecycleListener;

/** Restores Drydock task windows at startup and marks the beginning of an IDE quit. */
public final class QuitSignal implements AppLifecycleListener {

    @Override
    public void appStarted() {
        TaskWindows.restoreRemembered();
    }

    @Override
    public void appClosing() {
        AgentCloseConfirmation.quitStarted();
    }
}
