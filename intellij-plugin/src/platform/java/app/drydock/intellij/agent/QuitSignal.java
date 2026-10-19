package app.drydock.intellij.agent;

import com.intellij.ide.AppLifecycleListener;

/**
 * Tells {@link AgentCloseConfirmation} that the IDE has started to quit, so
 * the window closes that follow are answered with one dialog for all windows
 * rather than one per window. {@code appClosing()} is published before any
 * project is asked whether it may close.
 */
public final class QuitSignal implements AppLifecycleListener {

    @Override
    public void appClosing() {
        AgentCloseConfirmation.quitStarted();
    }
}
